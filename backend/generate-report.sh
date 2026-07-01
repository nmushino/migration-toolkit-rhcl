#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Migration Toolkit Backend - Quality Gate & Report Generator
#
# 実行内容:
#   1. Maven Unit Test (Surefire)
#   2. Checkstyle
#   3. PMD
#   4. JaCoCo Coverage
#   5. Semgrep (SAST)
#   6. Gitleaks (Secret Detection)
#   7. Trivy (SCA / 脆弱性スキャン)
#   8. Wapiti (DAST) ※ オプション: --with-wapiti 指定時のみ
#   9. HTML Report 生成
#
# 使用方法:
#   ./generate-report.sh [--with-wapiti] [--wapiti-url <URL>]
#
# 必要ツール:
#   mvn, python3, semgrep, gitleaks, trivy, wapiti3 (optional)
# ---------------------------------------------------------------------------

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET="$SCRIPT_DIR/target"
ARTIFACT_ID="migration-tool"

MVN="${MVN:-$(command -v mvn 2>/dev/null || echo /usr/local/maven-3.9.3/bin/mvn)}"

WITH_WAPITI=false
WAPITI_URL="http://localhost:8080"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-wapiti) WITH_WAPITI=true; shift ;;
    --wapiti-url)  WAPITI_URL="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

log()  { echo "▶ $*"; }
ok()   { echo "✔ $*"; }
warn() { echo "⚠ $*"; }

# ---------------------------------------------------------------------------
# 1. Maven Unit Test + Checkstyle + PMD + JaCoCo
# ---------------------------------------------------------------------------
log "Maven build / Unit Test / Checkstyle / PMD / JaCoCo..."
cd "$SCRIPT_DIR"
"$MVN" verify \
  checkstyle:checkstyle \
  pmd:pmd \
  -Dquarkus.test.profile=test \
  --no-transfer-progress \
  -q
ok "Maven build complete"

# ---------------------------------------------------------------------------
# 2. Semgrep (SAST)
# ---------------------------------------------------------------------------
log "Semgrep SAST..."
SEMGREP_OUT="$TARGET/semgrep-reports"
mkdir -p "$SEMGREP_OUT"

if command -v semgrep &>/dev/null; then
  semgrep scan \
    --config "$SCRIPT_DIR/.semgrep.yml" \
    --json \
    --output "$SEMGREP_OUT/results.json" \
    "$SCRIPT_DIR/src/main/java" \
    || true
  ok "Semgrep complete → $SEMGREP_OUT/results.json"
else
  warn "semgrep not found. Skipping. Install: pip install semgrep"
  echo '{"results":[],"errors":[],"paths":{"scanned":[]}}' > "$SEMGREP_OUT/results.json"
fi

# ---------------------------------------------------------------------------
# 3. Gitleaks (Secret Detection)
# ---------------------------------------------------------------------------
log "Gitleaks secret detection..."
if command -v gitleaks &>/dev/null; then
  gitleaks detect \
    --source "$REPO_ROOT" \
    --config "$REPO_ROOT/.gitleaks.toml" \
    --report-format json \
    --report-path "$TARGET/gitleaks-report.json" \
    --exit-code 0 \
    || true
  ok "Gitleaks complete → $TARGET/gitleaks-report.json"
else
  warn "gitleaks not found. Skipping. Install: brew install gitleaks"
  echo '[]' > "$TARGET/gitleaks-report.json"
fi

# ---------------------------------------------------------------------------
# 4. Trivy (SCA: JAR / 依存関係の脆弱性スキャン)
# ---------------------------------------------------------------------------
log "Trivy SCA scan..."
if command -v trivy &>/dev/null; then
  trivy fs \
    --ignorefile "$REPO_ROOT/.trivyignore.yaml" \
    --scanners vuln,secret \
    --format json \
    --output "$TARGET/trivy.json" \
    "$SCRIPT_DIR" \
    || true
  ok "Trivy complete → $TARGET/trivy.json"
else
  warn "trivy not found. Skipping. Install: brew install trivy"
  echo '{"SchemaVersion":2,"Results":[]}' > "$TARGET/trivy.json"
fi

# ---------------------------------------------------------------------------
# 5. Wapiti (DAST) ※ オプション
# ---------------------------------------------------------------------------
if $WITH_WAPITI; then
  log "Wapiti DAST scan against $WAPITI_URL ..."
  if command -v wapiti3 &>/dev/null || command -v wapiti &>/dev/null; then
    WAPITI_CMD="wapiti3"
    command -v wapiti3 &>/dev/null || WAPITI_CMD="wapiti"
    $WAPITI_CMD \
      -u "$WAPITI_URL" \
      --format json \
      --output "$TARGET/wapiti.json" \
      --flush-session \
      || true
    ok "Wapiti complete → $TARGET/wapiti.json"
  else
    warn "wapiti not found. Skipping. Install: pip install wapiti3"
    echo '{}' > "$TARGET/wapiti.json"
  fi
else
  log "Wapiti skipped (pass --with-wapiti to enable)"
  echo '{}' > "$TARGET/wapiti.json"
fi

# ---------------------------------------------------------------------------
# 6. HTML Report 生成
# ---------------------------------------------------------------------------
log "Generating HTML report..."
python3 "$SCRIPT_DIR/src/test/scripts/generate-report.py" \
  "$TARGET" \
  "$ARTIFACT_ID"

ok "Report generated → $TARGET/test-report/index.html"

# ---------------------------------------------------------------------------
# 7. 結果サマリー
# ---------------------------------------------------------------------------
echo ""
echo "========================================="
echo "  Quality Gate Results"
echo "========================================="
echo "  Report: $TARGET/test-report/index.html"
echo "========================================="
