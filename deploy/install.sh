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

# PostgreSQL オペレータインストール
install_postgres_operator() {
  log_section "PostgreSQL オペレータインストール"

  # openshift-operators にはグローバル OperatorGroup が既に存在する。
  # 重複 OperatorGroup があると OLM が CSV/InstallPlan を生成しないため
  # Subscription のみ apply する。

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
  mkdir -p "$ROOT_DIR/frontend/build/.nginx"
  cp "$ROOT_DIR/frontend/nginx/nginx.conf" "$ROOT_DIR/frontend/build/.nginx/nginx.conf"

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
    echo "  --help          このヘルプを表示"
    echo "  --backend-only  バックエンドのみデプロイ"
    echo "  --frontend-only フロントエンドのみデプロイ"
    echo "  --db-only       DBのみセットアップ"
    echo ""
    echo "Environment variables:"
    echo "  NAMESPACE  デプロイ先Namespace (デフォルト: migration-toolkit)"
    exit 0
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
