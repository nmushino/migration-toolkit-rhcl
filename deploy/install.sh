#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Migration Toolkit - OpenShift Provisioning Script
# Migration Toolkit - OpenShift プロビジョニングスクリプト
#
# Language / 言語設定:
#   LANG=ja  → Japanese messages (日本語)
#   LANG=en  → English messages  (英語)
#   Auto-detected from system locale if not set.
# ============================================================

NAMESPACE=${NAMESPACE:-migration-toolkit}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# ── 言語判定 ──────────────────────────────────────────────
# INSTALL_LANG 環境変数 → システムロケール → デフォルト英語
_detect_lang() {
  local l="${INSTALL_LANG:-${LANG:-}}"
  case "$l" in
    ja*|JP*) echo "ja" ;;
    *)       echo "en" ;;
  esac
}
MSG_LANG="$(_detect_lang)"

# msg <ja_text> <en_text>  →  選択中の言語を出力
msg() { [ "$MSG_LANG" = "ja" ] && echo "$1" || echo "$2"; }

# ── カラー定義 ────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()      { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*"; }
log_section() { echo -e "\n${BLUE}====== $* ======${NC}"; }

# 前提条件チェック / Check prerequisites
check_prerequisites() {
  log_section "$(msg '前提条件チェック' 'Checking Prerequisites')"

  for cmd in oc node npm; do
    if command -v "$cmd" &>/dev/null; then
      log_ok "$(msg "$cmd が見つかりました: $(command -v $cmd)" "$cmd found: $(command -v $cmd)")"
    else
      log_error "$(msg "$cmd が見つかりません。インストールしてください。" "$cmd not found. Please install it.")"
      exit 1
    fi
  done

  if ! oc whoami &>/dev/null; then
    log_error "$(msg "OpenShift にログインしていません。'oc login' を実行してください。" "Not logged in to OpenShift. Run 'oc login'.")"
    exit 1
  fi
  log_ok "$(msg "OpenShift ログイン済み: $(oc whoami)" "Logged in to OpenShift: $(oc whoami)")"
}

# Namespace 作成 / Create Namespace
create_namespace() {
  log_section "$(msg "Namespace 作成: $NAMESPACE" "Creating Namespace: $NAMESPACE")"

  if oc get namespace "$NAMESPACE" &>/dev/null; then
    log_warn "$(msg "Namespace '$NAMESPACE' は既に存在します" "Namespace '$NAMESPACE' already exists")"
  else
    oc new-project "$NAMESPACE" --description="Migration Toolkit" || true
    log_ok "$(msg "Namespace '$NAMESPACE' を作成しました" "Namespace '$NAMESPACE' created")"
  fi

  oc project "$NAMESPACE"
}

# ============================================================
# Kuadrant / Istio 前提条件セットアップ / Setup
# ============================================================
setup_kuadrant_prerequisites() {
  log_section "$(msg 'Kuadrant / Istio 前提条件セットアップ' 'Kuadrant / Istio Prerequisites Setup')"

  # 1. istio-system namespace
  if oc get namespace istio-system &>/dev/null; then
    log_warn "$(msg 'istio-system namespace は既に存在します' 'istio-system namespace already exists')"
  else
    oc create namespace istio-system
    log_ok "$(msg 'istio-system namespace を作成しました' 'istio-system namespace created')"
  fi

  # 2. Istio CR (Sail Operator v1) — バージョンはクラスターに応じて自動選択
  if oc get istio default &>/dev/null 2>&1; then
    log_warn "$(msg 'Istio CR '\''default'\'' は既に存在します' 'Istio CR '\''default'\'' already exists')"
  else
    log_info "$(msg 'Istio CR を作成中...' 'Creating Istio CR...')"
    # サポートされているバージョンを自動取得（最新の v1.28 系を優先）
    local istio_version
    istio_version=$(oc get crd istios.sailoperator.io \
      -o jsonpath='{.spec.versions[0].schema.openAPIV3Schema.properties.spec.properties.version.enum[0]}' \
      2>/dev/null || echo "v1.28-latest")
    log_info "Istio バージョン: ${istio_version}"

    cat <<EOF | oc apply -f -
apiVersion: sailoperator.io/v1
kind: Istio
metadata:
  name: default
spec:
  version: ${istio_version}
  namespace: istio-system
EOF
    log_ok "$(msg 'Istio CR を作成しました' 'Istio CR created')"
  fi

  # 3. istiod Pod が Ready になるまで待機（最大5分）
  log_info "$(msg 'istiod の起動を待機中（最大5分）...' 'Waiting for istiod to be ready (up to 5 min)...')"
  local count=0
  until oc get pods -n istio-system -l app=istiod --no-headers 2>/dev/null | grep -q "Running"; do
    if [ $count -ge 30 ]; then
      log_warn "$(msg 'istiod のタイムアウト。状態を確認してください: oc get pods -n istio-system' 'istiod timed out. Check: oc get pods -n istio-system')"
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  if oc get pods -n istio-system -l app=istiod --no-headers 2>/dev/null | grep -q "Running"; then
    log_ok "$(msg 'istiod が起動しました' 'istiod is running')"
  fi

  # 4. GatewayClass 確認
  if oc get gatewayclass istio &>/dev/null 2>&1; then
    log_ok "$(msg 'GatewayClass '\''istio'\'' が存在します' 'GatewayClass '\''istio'\'' is available')"
  else
    log_warn "$(msg 'GatewayClass '\''istio'\'' がまだ作成されていません。istiod の起動後に自動作成されます。' 'GatewayClass '\''istio'\'' not yet created. It will be created after istiod starts.')"
  fi

  # 5. kuadrant-system namespace
  if oc get namespace kuadrant-system &>/dev/null; then
    log_warn "$(msg 'kuadrant-system namespace は既に存在します' 'kuadrant-system namespace already exists')"
  else
    oc create namespace kuadrant-system
    log_ok "$(msg 'kuadrant-system namespace を作成しました' 'kuadrant-system namespace created')"
  fi

  # 6. Kuadrant CR
  if oc get kuadrant kuadrant -n kuadrant-system &>/dev/null 2>&1; then
    log_warn "$(msg 'Kuadrant CR '\''kuadrant'\'' は既に存在します' 'Kuadrant CR '\''kuadrant'\'' already exists')"
  else
    log_info "$(msg 'Kuadrant CR を作成中...' 'Creating Kuadrant CR...')"
    cat <<EOF | oc apply -f -
apiVersion: kuadrant.io/v1beta1
kind: Kuadrant
metadata:
  name: kuadrant
  namespace: kuadrant-system
spec:
  observability: {}
EOF
    log_ok "$(msg 'Kuadrant CR を作成しました' 'Kuadrant CR created')"
  fi

  # 7. Kuadrant Ready 待機（最大3分）
  log_info "$(msg 'Kuadrant の準備を待機中（最大3分）...' 'Waiting for Kuadrant to be ready (up to 3 min)...')"
  count=0
  until oc get kuadrant kuadrant -n kuadrant-system \
        -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null | grep -q "True"; do
    if [ $count -ge 18 ]; then
      log_warn "$(msg 'Kuadrant のタイムアウト: oc get kuadrant -n kuadrant-system' 'Kuadrant timed out: oc get kuadrant -n kuadrant-system')"
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""
  log_ok "$(msg 'Kuadrant セットアップ完了' 'Kuadrant setup complete')"
}

# PostgreSQL オペレータインストール / Install PostgreSQL Operator
install_postgres_operator() {
  log_section "$(msg 'PostgreSQL オペレータインストール' 'Installing PostgreSQL Operator')"

  # SCC 適用（fsGroup:26 + seccomp を許可 / OpenShift 4.12+ 対応）
  log_info "$(msg 'CrunchyData 用 SCC (crunchy-postgres) を適用しています...' 'Applying SCC (crunchy-postgres) for CrunchyData...')"
  sed "s/NAMESPACE_PLACEHOLDER/$NAMESPACE/g" \
    "$SCRIPT_DIR/postgres/03-postgresql-scc.yaml" | oc apply -f -
  log_ok "$(msg 'SCC を適用しました' 'SCC applied')"

  if oc get subscription crunchy-postgres-operator -n openshift-operators &>/dev/null; then
    log_warn "$(msg 'CrunchyData PostgreSQL オペレータは既にインストール済みです' 'CrunchyData PostgreSQL operator is already installed')"
  else
    log_info "$(msg 'openshift-operators の OperatorGroup:' 'OperatorGroup in openshift-operators:')"
    oc get operatorgroup -n openshift-operators --no-headers 2>/dev/null || true

    log_info "$(msg 'CrunchyData PostgreSQL オペレータ Subscription を作成しています...' 'Creating CrunchyData PostgreSQL operator Subscription...')"
    oc apply -f "$SCRIPT_DIR/postgres/01-operator-subscription.yaml"
  fi

  # InstallPlan が生成されるまで待機
  log_info "$(msg 'InstallPlan の生成を待機中 (最大3分)...' 'Waiting for InstallPlan (up to 3 min)...')"
  local count=0
  until oc get installplan -n openshift-operators \
        -l operators.coreos.com/crunchy-postgres-operator.openshift-operators \
        --no-headers 2>/dev/null | grep -q .; do
    if [ $count -ge 18 ]; then
      log_warn "$(msg 'InstallPlan がタイムアウトしました。状態を確認してください:' 'InstallPlan timed out. Check:')"
      oc get subscription crunchy-postgres-operator -n openshift-operators -o yaml 2>/dev/null | grep -A5 "status:" || true
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  # CSV が Succeeded になるまで待機
  log_info "$(msg 'CSV (ClusterServiceVersion) の準備を待機中 (最大5分)...' 'Waiting for CSV (ClusterServiceVersion) to succeed (up to 5 min)...')"
  count=0
  until oc get csv -n openshift-operators \
        --no-headers 2>/dev/null | grep -i "crunchy\|pgo\|postgres" | grep -q "Succeeded"; do
    if [ $count -ge 30 ]; then
      log_warn "$(msg 'CSV のタイムアウト。現在の状態:' 'CSV timed out. Current state:')"
      oc get csv -n openshift-operators 2>/dev/null | grep -i "crunchy\|pgo\|postgres" || echo "  (no CSV)"
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  # CRD 確認
  if oc get crd postgresclusters.postgres-operator.crunchydata.com &>/dev/null; then
    log_ok "$(msg 'PostgreSQL オペレータCRDが利用可能です' 'PostgreSQL operator CRD is available')"
  else
    log_warn "$(msg 'CRDがまだ確認できません。'\''oc get csv -n openshift-operators'\'' で状態を確認してください。' 'CRD not yet available. Check: oc get csv -n openshift-operators')"
  fi
}

# PostgreSQL クラスター作成 / Create PostgreSQL Cluster
create_postgres_cluster() {
  log_section "$(msg 'PostgreSQL クラスター作成' 'Creating PostgreSQL Cluster')"

  if oc get postgrescluster migration-tool-db -n "$NAMESPACE" &>/dev/null; then
    log_warn "$(msg 'PostgreSQL クラスターは既に存在します' 'PostgreSQL cluster already exists')"
    return
  fi

  sed "s/NAMESPACE_PLACEHOLDER/$NAMESPACE/g" \
    "$SCRIPT_DIR/postgres/02-postgresql-cluster.yaml" | oc apply -f -

  log_info "$(msg 'PostgreSQL クラスターの起動を待機中 (最大5分)...' 'Waiting for PostgreSQL cluster to start (up to 5 min)...')"
  local count=0
  until oc get secret "migration-tool-db-pguser-migrationtool" -n "$NAMESPACE" &>/dev/null || [ $count -ge 30 ]; do
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  if oc get secret "migration-tool-db-pguser-migrationtool" -n "$NAMESPACE" &>/dev/null; then
    log_ok "$(msg 'PostgreSQL シークレットが作成されました' 'PostgreSQL secret created')"
  else
    log_warn "$(msg 'PostgreSQL シークレットがまだ作成されていません。しばらく待ってください。' 'PostgreSQL secret not yet created. Please wait a moment.')"
  fi
}

# バックエンドビルド&デプロイ / Build & Deploy Backend
deploy_backend() {
  log_section "$(msg 'バックエンド ビルド & デプロイ' 'Backend Build & Deploy')"

  for f in 01-imagestream.yaml 02-buildconfig.yaml 03-deployment.yaml 04-service.yaml 05-route.yaml; do
    sed "s/NAMESPACE_PLACEHOLDER/$NAMESPACE/g" \
      "$SCRIPT_DIR/backend/$f" | oc apply -f -
  done
  log_ok "$(msg 'バックエンドリソースを適用しました' 'Backend resources applied')"

  if [ -d "$ROOT_DIR/backend/target" ]; then
    log_info "$(msg 'target/ を削除中（S2I アップロード前のクリーンアップ）...' 'Removing target/ (cleanup before S2I upload)...')"
    rm -rf "$ROOT_DIR/backend/target"
    log_ok "$(msg 'target/ を削除しました' 'target/ removed')"
  fi

  log_info "$(msg 'S2I ビルドを開始中（S2I 内部で Maven ビルドを実行します）...' 'Starting S2I build (Maven runs inside S2I)...')"
  oc start-build migration-tool-backend \
    --from-dir="$ROOT_DIR/backend" \
    --follow \
    --wait \
    -n "$NAMESPACE"
  log_ok "$(msg 'バックエンドビルド完了' 'Backend build complete')"

  log_info "$(msg 'バックエンドデプロイを待機中...' 'Waiting for backend deployment...')"
  oc rollout status deployment/migration-tool-backend -n "$NAMESPACE" --timeout=5m
  log_ok "$(msg 'バックエンドデプロイ完了' 'Backend deployment complete')"
}

# フロントエンドビルド&デプロイ / Build & Deploy Frontend
deploy_frontend() {
  log_section "$(msg 'フロントエンド ビルド & デプロイ' 'Frontend Build & Deploy')"

  local backend_route
  backend_route=$(oc get route migration-tool-backend -n "$NAMESPACE" -o jsonpath='{.spec.host}' 2>/dev/null || echo "")
  local backend_url="https://${backend_route}"

  oc create configmap migration-tool-config \
    --from-literal=backend-url="$backend_url" \
    -n "$NAMESPACE" \
    --dry-run=client -o yaml | oc apply -f -
  log_ok "$(msg "ConfigMap 作成: backend-url=$backend_url" "ConfigMap created: backend-url=$backend_url")"

  for f in 01-imagestream.yaml 02-buildconfig.yaml 03-deployment.yaml 04-service.yaml 05-route.yaml; do
    sed -e "s|NAMESPACE_PLACEHOLDER|$NAMESPACE|g" \
        -e "s|BACKEND_URL_PLACEHOLDER|$backend_url|g" \
      "$SCRIPT_DIR/frontend/$f" | oc apply -f -
  done
  log_ok "$(msg 'フロントエンドリソースを適用しました' 'Frontend resources applied')"

  # ローカルで Vite ビルド（OOMKill 回避のため S2I 内ではビルドしない）
  local NODE_BIN="node"
  for v in 20 22 18; do
    local candidate="$HOME/.nvm/versions/node/$(ls "$HOME/.nvm/versions/node/" 2>/dev/null | grep "^v${v}\." | sort -V | tail -1)/bin/node"
    if [ -x "$candidate" ]; then
      NODE_BIN="$candidate"
      log_info "$(msg "Node.js: $($NODE_BIN --version) を使用 ($candidate)" "Node.js: $($NODE_BIN --version) ($candidate)")"
      break
    fi
  done

  log_info "$(msg 'npm install & vite build を実行中...' 'Running npm install & vite build...')"
  (cd "$ROOT_DIR/frontend" && \
    "$NODE_BIN" "$(dirname "$NODE_BIN")/npm" install --legacy-peer-deps --silent && \
    VITE_API_URL="$backend_url" "$NODE_BIN" "$(dirname "$NODE_BIN")/npx" vite build)
  log_ok "$(msg 'Vite ビルド完了' 'Vite build complete')"

  mkdir -p "$ROOT_DIR/frontend/build/nginx-default-cfg"
  cp "$ROOT_DIR/frontend/nginx-default-cfg/api-proxy.conf" "$ROOT_DIR/frontend/build/nginx-default-cfg/api-proxy.conf"

  log_info "$(msg 'S2I フロントエンドビルドを開始中...' 'Starting S2I frontend build...')"
  oc start-build migration-tool-frontend \
    --from-dir="$ROOT_DIR/frontend/build" \
    --follow \
    --wait \
    -n "$NAMESPACE"
  log_ok "$(msg 'フロントエンドビルド完了' 'Frontend build complete')"

  log_info "$(msg 'フロントエンドデプロイを待機中...' 'Waiting for frontend deployment...')"
  oc rollout status deployment/migration-tool-frontend -n "$NAMESPACE" --timeout=3m
  log_ok "$(msg 'フロントエンドデプロイ完了' 'Frontend deployment complete')"
}

# URL 表示 / Print URLs
print_urls() {
  log_section "$(msg 'デプロイ完了' 'Deployment Complete')"

  local frontend_url backend_url
  frontend_url=$(oc get route migration-tool-frontend -n "$NAMESPACE" \
    -o jsonpath='https://{.spec.host}' 2>/dev/null || msg "取得中..." "fetching...")
  backend_url=$(oc get route migration-tool-backend -n "$NAMESPACE" \
    -o jsonpath='https://{.spec.host}' 2>/dev/null || msg "取得中..." "fetching...")

  echo ""
  log_ok "$(msg '=== アクセスURL ===' '=== Access URLs ===')"
  echo -e "  $(msg 'フロントエンド' 'Frontend')  : ${GREEN}${frontend_url}${NC}"
  echo -e "  $(msg 'バックエンドAPI' 'Backend API'): ${GREEN}${backend_url}${NC}"
  echo -e "  Swagger UI     : ${GREEN}${backend_url}/q/swagger-ui${NC}"
  echo ""
}

# メイン処理 / Main
main() {
  echo ""
  echo -e "${BLUE}╔══════════════════════════════════════════════════╗${NC}"
  if [ "$MSG_LANG" = "ja" ]; then
  echo -e "${BLUE}║       Migration Toolkit インストーラー           ║${NC}"
  else
  echo -e "${BLUE}║       Migration Toolkit Installer                ║${NC}"
  fi
  echo -e "${BLUE}╚══════════════════════════════════════════════════╝${NC}"
  echo ""
  echo "  Namespace : $NAMESPACE"
  echo "  Language  : $([ "$MSG_LANG" = "ja" ] && echo '日本語 (ja)' || echo 'English (en)')"
  echo "  $(msg 'ヒント: INSTALL_LANG=en で英語に切り替えられます' 'Tip: Set INSTALL_LANG=ja to switch to Japanese')"
  echo ""

  check_prerequisites
  create_namespace
  setup_kuadrant_prerequisites
  install_postgres_operator
  create_postgres_cluster
  deploy_backend
  deploy_frontend
  print_urls
}

# 引数解析
case "${1:-}" in
  --help|-h)
    echo "Usage: NAMESPACE=<ns> [INSTALL_LANG=ja|en] $0 [options]"
    echo ""
    if [ "$MSG_LANG" = "ja" ]; then
    echo "オプション:"
    echo "  --help              このヘルプを表示"
    echo "  --kuadrant-only     Kuadrant / Istio 前提条件のみセットアップ"
    echo "  --backend-only      バックエンドのみデプロイ"
    echo "  --frontend-only     フロントエンドのみデプロイ"
    echo "  --db-only           DBのみセットアップ"
    echo ""
    echo "環境変数:"
    echo "  NAMESPACE      デプロイ先Namespace (デフォルト: migration-toolkit)"
    echo "  INSTALL_LANG   表示言語: ja (日本語) / en (English) (デフォルト: 自動検出)"
    else
    echo "Options:"
    echo "  --help              Show this help"
    echo "  --kuadrant-only     Set up Kuadrant / Istio prerequisites only"
    echo "  --backend-only      Deploy backend only"
    echo "  --frontend-only     Deploy frontend only"
    echo "  --db-only           Set up DB only"
    echo ""
    echo "Environment variables:"
    echo "  NAMESPACE      Target namespace (default: migration-toolkit)"
    echo "  INSTALL_LANG   Language: ja (Japanese) / en (English) (default: auto-detect)"
    fi
    exit 0
    ;;
  --kuadrant-only)
    check_prerequisites
    setup_kuadrant_prerequisites
    ;;
  --backend-only)
    check_prerequisites
    create_namespace
    deploy_backend
    print_urls
    ;;
  --frontend-only)
    check_prerequisites
    create_namespace
    deploy_frontend
    print_urls
    ;;
  --db-only)
    check_prerequisites
    create_namespace
    install_postgres_operator
    create_postgres_cluster
    ;;
  *)
    main
    ;;
esac
