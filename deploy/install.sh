#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# 3scale Migration Toolkit - OpenShift プロビジョニングスクリプト
# ============================================================

NAMESPACE=${NAMESPACE:-migration-toolkit}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# カラー定義
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

# 前提条件チェック
check_prerequisites() {
  log_section "前提条件チェック"

  for cmd in oc node npm; do
    if command -v "$cmd" &>/dev/null; then
      log_ok "$cmd が見つかりました: $(command -v $cmd)"
    else
      log_error "$cmd が見つかりません。インストールしてください。"
      exit 1
    fi
  done

  if ! oc whoami &>/dev/null; then
    log_error "OpenShift にログインしていません。'oc login' を実行してください。"
    exit 1
  fi
  log_ok "OpenShift ログイン済み: $(oc whoami)"
}

# Namespace 作成
create_namespace() {
  log_section "Namespace 作成: $NAMESPACE"

  if oc get namespace "$NAMESPACE" &>/dev/null; then
    log_warn "Namespace '$NAMESPACE' は既に存在します"
  else
    oc new-project "$NAMESPACE" --description="3scale Migration Toolkit" || true
    log_ok "Namespace '$NAMESPACE' を作成しました"
  fi

  oc project "$NAMESPACE"
}

# ============================================================
# Kuadrant / Istio 前提条件セットアップ
# ============================================================
setup_kuadrant_prerequisites() {
  log_section "Kuadrant / Istio 前提条件セットアップ"

  # 1. istio-system namespace
  if oc get namespace istio-system &>/dev/null; then
    log_warn "istio-system namespace は既に存在します"
  else
    oc create namespace istio-system
    log_ok "istio-system namespace を作成しました"
  fi

  # 2. Istio CR (Sail Operator v1) — バージョンはクラスターに応じて自動選択
  if oc get istio default &>/dev/null 2>&1; then
    log_warn "Istio CR 'default' は既に存在します"
  else
    log_info "Istio CR を作成中..."
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
    log_ok "Istio CR を作成しました"
  fi

  # 3. istiod Pod が Ready になるまで待機（最大5分）
  log_info "istiod の起動を待機中（最大5分）..."
  local count=0
  until oc get pods -n istio-system -l app=istiod --no-headers 2>/dev/null | grep -q "Running"; do
    if [ $count -ge 30 ]; then
      log_warn "istiod のタイムアウト。状態を確認してください: oc get pods -n istio-system"
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  if oc get pods -n istio-system -l app=istiod --no-headers 2>/dev/null | grep -q "Running"; then
    log_ok "istiod が起動しました"
  fi

  # 4. GatewayClass 確認
  if oc get gatewayclass istio &>/dev/null 2>&1; then
    log_ok "GatewayClass 'istio' が存在します"
  else
    log_warn "GatewayClass 'istio' がまだ作成されていません。istiod の起動後に自動作成されます。"
  fi

  # 5. kuadrant-system namespace
  if oc get namespace kuadrant-system &>/dev/null; then
    log_warn "kuadrant-system namespace は既に存在します"
  else
    oc create namespace kuadrant-system
    log_ok "kuadrant-system namespace を作成しました"
  fi

  # 6. Kuadrant CR
  if oc get kuadrant kuadrant -n kuadrant-system &>/dev/null 2>&1; then
    log_warn "Kuadrant CR 'kuadrant' は既に存在します"
  else
    log_info "Kuadrant CR を作成中..."
    cat <<EOF | oc apply -f -
apiVersion: kuadrant.io/v1beta1
kind: Kuadrant
metadata:
  name: kuadrant
  namespace: kuadrant-system
spec:
  observability: {}
EOF
    log_ok "Kuadrant CR を作成しました"
  fi

  # 7. Kuadrant Ready 待機（最大3分）
  log_info "Kuadrant の準備を待機中（最大3分）..."
  count=0
  until oc get kuadrant kuadrant -n kuadrant-system \
        -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null | grep -q "True"; do
    if [ $count -ge 18 ]; then
      log_warn "Kuadrant のタイムアウト: oc get kuadrant -n kuadrant-system"
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""
  log_ok "Kuadrant セットアップ完了"
}

# PostgreSQL オペレータインストール
install_postgres_operator() {
  log_section "PostgreSQL オペレータインストール"

  # openshift-operators にはグローバル OperatorGroup が既に存在する。
  # 重複 OperatorGroup があると OLM が CSV/InstallPlan を生成しないため
  # Subscription のみ apply する。

  # SCC 適用（fsGroup:26 + seccomp を許可 / OpenShift 4.12+ 対応）
  log_info "CrunchyData 用 SCC (crunchy-postgres) を適用しています..."
  sed "s/NAMESPACE_PLACEHOLDER/$NAMESPACE/g" \
    "$SCRIPT_DIR/postgres/03-postgresql-scc.yaml" | oc apply -f -
  log_ok "SCC を適用しました"

  if oc get subscription crunchy-postgres-operator -n openshift-operators &>/dev/null; then
    log_warn "CrunchyData PostgreSQL オペレータは既にインストール済みです"
  else
    # 既存 OperatorGroup の確認（念のため表示）
    log_info "openshift-operators の OperatorGroup:"
    oc get operatorgroup -n openshift-operators --no-headers 2>/dev/null || true

    log_info "CrunchyData PostgreSQL オペレータ Subscription を作成しています..."
    oc apply -f "$SCRIPT_DIR/postgres/01-operator-subscription.yaml"
  fi

  # InstallPlan が生成されるまで待機
  log_info "InstallPlan の生成を待機中 (最大3分)..."
  local count=0
  until oc get installplan -n openshift-operators \
        -l operators.coreos.com/crunchy-postgres-operator.openshift-operators \
        --no-headers 2>/dev/null | grep -q .; do
    if [ $count -ge 18 ]; then
      log_warn "InstallPlan がタイムアウトしました。状態を確認してください:"
      oc get subscription crunchy-postgres-operator -n openshift-operators -o yaml 2>/dev/null | grep -A5 "status:" || true
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  # CSV が Succeeded になるまで待機
  log_info "CSV (ClusterServiceVersion) の準備を待機中 (最大5分)..."
  count=0
  until oc get csv -n openshift-operators \
        --no-headers 2>/dev/null | grep -i "crunchy\|pgo\|postgres" | grep -q "Succeeded"; do
    if [ $count -ge 30 ]; then
      log_warn "CSV のタイムアウト。現在の状態:"
      oc get csv -n openshift-operators 2>/dev/null | grep -i "crunchy\|pgo\|postgres" || echo "  (CSVなし)"
      break
    fi
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  # CRD 確認
  if oc get crd postgresclusters.postgres-operator.crunchydata.com &>/dev/null; then
    log_ok "PostgreSQL オペレータCRDが利用可能です"
  else
    log_warn "CRDがまだ確認できません。'oc get csv -n openshift-operators' で状態を確認してください。"
  fi
}

# PostgreSQL クラスター作成
create_postgres_cluster() {
  log_section "PostgreSQL クラスター作成"

  if oc get postgrescluster migration-tool-db -n "$NAMESPACE" &>/dev/null; then
    log_warn "PostgreSQL クラスターは既に存在します"
    return
  fi

  sed "s/NAMESPACE_PLACEHOLDER/$NAMESPACE/g" \
    "$SCRIPT_DIR/postgres/02-postgresql-cluster.yaml" | oc apply -f -

  log_info "PostgreSQL クラスターの起動を待機中 (最大5分)..."
  local count=0
  until oc get secret "migration-tool-db-pguser-migrationtool" -n "$NAMESPACE" &>/dev/null || [ $count -ge 30 ]; do
    echo -n "."
    sleep 10
    ((count++))
  done
  echo ""

  if oc get secret "migration-tool-db-pguser-migrationtool" -n "$NAMESPACE" &>/dev/null; then
    log_ok "PostgreSQL シークレットが作成されました"
  else
    log_warn "PostgreSQL シークレットがまだ作成されていません。しばらく待ってください。"
  fi

}

# バックエンドビルド&デプロイ
deploy_backend() {
  log_section "バックエンド ビルド & デプロイ"

  # ImageStream / BuildConfig 作成
  for f in 01-imagestream.yaml 02-buildconfig.yaml 03-deployment.yaml 04-service.yaml 05-route.yaml; do
    sed "s/NAMESPACE_PLACEHOLDER/$NAMESPACE/g" \
      "$SCRIPT_DIR/backend/$f" | oc apply -f -
  done
  log_ok "バックエンドリソースを適用しました"

  # oc start-build --from-dir は .s2iignore を無視して target/ ごと tar にしてしまうため、
  # アップロード前に target/ を削除する。
  # BuildConfig の MAVEN_ARGS により OpenShift 上の openjdk-21 S2I イメージが Maven ビルドを実行する。
  if [ -d "$ROOT_DIR/backend/target" ]; then
    log_info "target/ を削除中（S2I アップロード前のクリーンアップ）..."
    rm -rf "$ROOT_DIR/backend/target"
    log_ok "target/ を削除しました"
  fi

  log_info "S2I ビルドを開始中（S2I 内部で Maven ビルドを実行します）..."
  oc start-build migration-tool-backend \
    --from-dir="$ROOT_DIR/backend" \
    --follow \
    --wait \
    -n "$NAMESPACE"
  log_ok "バックエンドビルド完了"

  # Rollout 待機
  log_info "バックエンドデプロイを待機中..."
  oc rollout status deployment/migration-tool-backend -n "$NAMESPACE" --timeout=5m
  log_ok "バックエンドデプロイ完了"
}

# フロントエンドビルド&デプロイ
deploy_frontend() {
  log_section "フロントエンド ビルド & デプロイ"

  # バックエンドURLを取得してConfigMap作成
  local backend_route
  backend_route=$(oc get route migration-tool-backend -n "$NAMESPACE" -o jsonpath='{.spec.host}' 2>/dev/null || echo "")
  local backend_url="https://${backend_route}"

  oc create configmap migration-tool-config \
    --from-literal=backend-url="$backend_url" \
    -n "$NAMESPACE" \
    --dry-run=client -o yaml | oc apply -f -
  log_ok "ConfigMap 作成: backend-url=$backend_url"

  # ImageStream / BuildConfig / Deployment / Service / Route 作成
  # BuildConfig の BACKEND_URL_PLACEHOLDER も置換する
  for f in 01-imagestream.yaml 02-buildconfig.yaml 03-deployment.yaml 04-service.yaml 05-route.yaml; do
    sed -e "s|NAMESPACE_PLACEHOLDER|$NAMESPACE|g" \
        -e "s|BACKEND_URL_PLACEHOLDER|$backend_url|g" \
      "$SCRIPT_DIR/frontend/$f" | oc apply -f -
  done
  log_ok "フロントエンドリソースを適用しました"

  # ローカルで Vite ビルド（OOMKill 回避のため S2I 内ではビルドしない）
  # Vite 5 は Node 18+ 必須。nvm 環境では Node 20 を優先的に使用する。
  local NODE_BIN="node"
  for v in 20 22 18; do
    local candidate="$HOME/.nvm/versions/node/$(ls "$HOME/.nvm/versions/node/" 2>/dev/null | grep "^v${v}\." | sort -V | tail -1)/bin/node"
    if [ -x "$candidate" ]; then
      NODE_BIN="$candidate"
      log_info "Node.js: $($NODE_BIN --version) を使用 ($candidate)"
      break
    fi
  done

  log_info "npm install & vite build を実行中..."
  (cd "$ROOT_DIR/frontend" && \
    "$NODE_BIN" "$(dirname "$NODE_BIN")/npm" install --legacy-peer-deps --silent && \
    VITE_API_URL="$backend_url" "$NODE_BIN" "$(dirname "$NODE_BIN")/npx" vite build)
  log_ok "Vite ビルド完了"

  # nginx 設定ファイルを build/ に配置
  # nginx-default-cfg/ に API プロキシ設定を配置（S2I が /opt/app-root/etc/nginx.default.d/ にコピーする）
  mkdir -p "$ROOT_DIR/frontend/build/nginx-default-cfg"
  cp "$ROOT_DIR/frontend/nginx-default-cfg/api-proxy.conf" "$ROOT_DIR/frontend/build/nginx-default-cfg/api-proxy.conf"

  # S2I バイナリビルド: ビルド済み静的ファイルを nginx イメージに載せる
  log_info "S2I フロントエンドビルドを開始中..."
  oc start-build migration-tool-frontend \
    --from-dir="$ROOT_DIR/frontend/build" \
    --follow \
    --wait \
    -n "$NAMESPACE"
  log_ok "フロントエンドビルド完了"

  # Rollout 待機
  log_info "フロントエンドデプロイを待機中..."
  oc rollout status deployment/migration-tool-frontend -n "$NAMESPACE" --timeout=3m
  log_ok "フロントエンドデプロイ完了"
}

# URL 表示
print_urls() {
  log_section "デプロイ完了"

  local frontend_url backend_url
  frontend_url=$(oc get route migration-tool-frontend -n "$NAMESPACE" \
    -o jsonpath='https://{.spec.host}' 2>/dev/null || echo "取得中...")
  backend_url=$(oc get route migration-tool-backend -n "$NAMESPACE" \
    -o jsonpath='https://{.spec.host}' 2>/dev/null || echo "取得中...")

  echo ""
  log_ok "=== アクセスURL ==="
  echo -e "  フロントエンド : ${GREEN}${frontend_url}${NC}"
  echo -e "  バックエンドAPI: ${GREEN}${backend_url}${NC}"
  echo -e "  Swagger UI     : ${GREEN}${backend_url}/q/swagger-ui${NC}"
  echo ""
}

# メイン処理
main() {
  echo ""
  echo -e "${BLUE}╔══════════════════════════════════════════════╗${NC}"
  echo -e "${BLUE}║   3scale Migration Toolkit インストーラー    ║${NC}"
  echo -e "${BLUE}╚══════════════════════════════════════════════╝${NC}"
  echo ""
  echo "  Namespace: $NAMESPACE"
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
    echo "Usage: NAMESPACE=<ns> $0 [options]"
    echo ""
    echo "Options:"
    echo "  --help              このヘルプを表示"
    echo "  --kuadrant-only     Kuadrant / Istio 前提条件のみセットアップ"
    echo "  --backend-only      バックエンドのみデプロイ"
    echo "  --frontend-only     フロントエンドのみデプロイ"
    echo "  --db-only           DBのみセットアップ"
    echo ""
    echo "Environment variables:"
    echo "  NAMESPACE  デプロイ先Namespace (デフォルト: migration-toolkit)"
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
