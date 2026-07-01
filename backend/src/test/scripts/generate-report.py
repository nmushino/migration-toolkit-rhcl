#!/usr/bin/env python3
"""
Generates target/test-report/index.html from surefire/failsafe XML,
checkstyle-result.xml, pmd.xml, jacoco.csv,
semgrep-results.json, gitleaks-report.json, trivy.txt, wapiti.json.
"""
import csv
import glob
import json
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta

TARGET       = sys.argv[1] if len(sys.argv) > 1 else "target"
PROJECT_NAME = sys.argv[2] if len(sys.argv) > 2 else "migration-tool"

_JACOCO_CANDIDATES = [
    os.path.join(TARGET, "jacoco-merged-report", "jacoco.csv"),  # unit + quarkus merged
    os.path.join(TARGET, "site", "jacoco-merged", "jacoco.csv"),
    os.path.join(TARGET, "jacoco-report", "jacoco.csv"),
]
JACOCO_CSV     = next((p for p in _JACOCO_CANDIDATES
                       if os.path.exists(p) and os.path.getsize(p) > 0),
                      _JACOCO_CANDIDATES[-1])
CHECKSTYLE_XML  = os.path.join(TARGET, "checkstyle-result.xml")
PMD_XML         = os.path.join(TARGET, "pmd.xml")
SEMGREP_JSON    = os.path.join(TARGET, "semgrep-reports", "results.json")
GITLEAKS_JSON   = os.path.join(TARGET, "gitleaks-report.json")
TRIVY_TXT       = os.path.join(TARGET, "trivy.txt")
WAPITI_JSON     = os.path.join(TARGET, "wapiti.json")
OUT_DIR         = os.path.join(TARGET, "test-report")
OUT_FILE        = os.path.join(OUT_DIR, "index.html")

JST     = timezone(timedelta(hours=9))
now     = datetime.now(JST)
now_str = now.strftime("%Y-%m-%d %H:%M:%S JST")

if os.path.exists(OUT_DIR):
    shutil.rmtree(OUT_DIR)
os.makedirs(OUT_DIR)


# ── Helpers ──────────────────────────────────────────────────────────────────
def fmt_time(t):
    return f"{t:.2f}s" if t >= 1 else f"{int(t * 1000)}ms"


def pct_str(c, t):
    return f"{100 * c / t:.1f}" if t else "0.0"


def badge(status):
    icons = {
        "pass": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
                ' stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>',
        "fail": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
                ' stroke-width="3"><line x1="18" y1="6" x2="6" y2="18"/>'
                '<line x1="6" y1="6" x2="18" y2="18"/></svg>',
        "skip": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
                ' stroke-width="3"><line x1="5" y1="12" x2="19" y2="12"/>'
                '</svg>',
        "warn": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
                ' stroke-width="3"><path d="M10.29 3.86L1.82 18a2 2 0 001.71'
                ' 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>'
                '<line x1="12" y1="9" x2="12" y2="13"/>'
                '<line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
        "info": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
                ' stroke-width="3"><circle cx="12" cy="12" r="10"/>'
                '<line x1="12" y1="16" x2="12" y2="12"/>'
                '<line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
    }
    labels = {"pass": "PASS", "fail": "FAIL", "skip": "SKIP",
              "warn": "WARN", "info": "INFO"}
    return (f'<span class="badge {status}">'
            f'{icons[status]} {labels[status]}</span>')


# ── Parse test XML ────────────────────────────────────────────────────────────
def parse_test_dir(report_dir):
    suites = []
    for xml_path in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        root = ET.parse(xml_path).getroot()
        suite = {
            "name":  root.get("name", "").split(".")[-1],
            "pkg":   ".".join(root.get("name", "").split(".")[:-1]),
            "tests": int(root.get("tests", 0)),
            "pass": 0, "fail": 0, "skip": 0,
            "time":  float(root.get("time", 0)),
            "cases": [],
        }
        for tc in root.findall("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                status = "fail"
            elif tc.find("skipped") is not None:
                status = "skip"
            else:
                status = "pass"
            suite["cases"].append({
                "name":    tc.get("name", ""),
                "status":  status,
                "time":    float(tc.get("time", 0)),
                "message": (
                    tc.find("failure") or tc.find("error") or ET.Element("x")
                ).get("message", ""),
            })
            suite[status] += 1
        suites.append(suite)
    return suites


_ARCH_CLASS = "io.quarkusdroneshop.ArchitectureTest"

def parse_test_dir_filtered(report_dir, exclude_fqn=None):
    """Parse TEST-*.xml; optionally exclude a specific test class by FQN."""
    suites = []
    for xml_path in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        root = ET.parse(xml_path).getroot()
        fqn  = root.get("name", "")
        if exclude_fqn and fqn == exclude_fqn:
            continue
        suite = {
            "name":  fqn.split(".")[-1],
            "pkg":   ".".join(fqn.split(".")[:-1]),
            "tests": int(root.get("tests", 0)),
            "pass": 0, "fail": 0, "skip": 0,
            "time":  float(root.get("time", 0)),
            "cases": [],
        }
        for tc in root.findall("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                status = "fail"
            elif tc.find("skipped") is not None:
                status = "skip"
            else:
                status = "pass"
            suite["cases"].append({
                "name":    tc.get("name", ""),
                "status":  status,
                "time":    float(tc.get("time", 0)),
                "message": (
                    tc.find("failure") or tc.find("error") or ET.Element("x")
                ).get("message", ""),
            })
            suite[status] += 1
        suites.append(suite)
    return suites


# ArchUnit suite is separated from unit tests
arch_xml = os.path.join(TARGET, "surefire-reports",
                        f"TEST-{_ARCH_CLASS}.xml")
arch_suites = parse_test_dir(os.path.dirname(arch_xml)) if False else []  # init
if os.path.exists(arch_xml):
    _root = ET.parse(arch_xml).getroot()
    _suite = {
        "name":  "ArchitectureTest",
        "pkg":   "io.quarkusdroneshop",
        "tests": int(_root.get("tests", 0)),
        "pass": 0, "fail": 0, "skip": 0,
        "time":  float(_root.get("time", 0)),
        "cases": [],
    }
    for _tc in _root.findall("testcase"):
        if _tc.find("failure") is not None or _tc.find("error") is not None:
            _st = "fail"
        elif _tc.find("skipped") is not None:
            _st = "skip"
        else:
            _st = "pass"
        _suite["cases"].append({
            "name":    _tc.get("name", ""),
            "status":  _st,
            "time":    float(_tc.get("time", 0)),
            "message": (
                _tc.find("failure") or _tc.find("error") or ET.Element("x")
            ).get("message", ""),
        })
        _suite[_st] += 1
    arch_suites = [_suite]

surefire_suites = parse_test_dir_filtered(
    os.path.join(TARGET, "surefire-reports"), exclude_fqn=_ARCH_CLASS)

# failsafe-reports から E2E テスト（クラス名が E2ETest / E2E / IT で終わるもの）だけを抽出
_E2E_RE = re.compile(r"(E2ETest|E2E|IT)$")
failsafe_suites = [
    s for s in parse_test_dir(os.path.join(TARGET, "failsafe-reports"))
    if _E2E_RE.search(s["name"])
]


def stats(suites):
    total   = sum(s["tests"] for s in suites)
    passed  = sum(s["pass"]  for s in suites)
    failed  = sum(s["fail"]  for s in suites)
    skipped = sum(s["skip"]  for s in suites)
    elapsed = sum(s["time"]  for s in suites)
    pct     = round(100 * passed / total) if total else 0
    return total, passed, failed, skipped, elapsed, pct


su_total, su_pass, su_fail, su_skip, su_time, su_pct = stats(surefire_suites)
fa_total, fa_pass, fa_fail, fa_skip, fa_time, fa_pct = stats(failsafe_suites)
ar_total, ar_pass, ar_fail, ar_skip, ar_time, ar_pct = stats(arch_suites)

all_total   = su_total + fa_total + ar_total
all_passed  = su_pass  + fa_pass  + ar_pass
all_failed  = su_fail  + fa_fail  + ar_fail
all_skipped = su_skip  + fa_skip  + ar_skip
all_elapsed = su_time  + fa_time  + ar_time
all_pct     = round(100 * all_passed / all_total) if all_total else 0


# ── Parse JaCoCo CSV ─────────────────────────────────────────────────────────
cov_pkgs = {}
total_inst_m = total_inst_c = total_line_m = total_line_c = 0
total_br_m   = total_br_c   = 0
if os.path.exists(JACOCO_CSV):
    with open(JACOCO_CSV) as f:
        for row in csv.DictReader(f):
            pkg = row["PACKAGE"].replace("/", ".")
            if pkg not in cov_pkgs:
                cov_pkgs[pkg] = {
                    "classes": 0,
                    "im": 0, "ic": 0,
                    "lm": 0, "lc": 0,
                    "bm": 0, "bc": 0,
                }
            p = cov_pkgs[pkg]
            p["classes"] += 1
            p["im"] += int(row["INSTRUCTION_MISSED"])
            p["ic"] += int(row["INSTRUCTION_COVERED"])
            p["lm"] += int(row["LINE_MISSED"])
            p["lc"] += int(row["LINE_COVERED"])
            p["bm"] += int(row["BRANCH_MISSED"])
            p["bc"] += int(row["BRANCH_COVERED"])
            total_inst_m += int(row["INSTRUCTION_MISSED"])
            total_inst_c += int(row["INSTRUCTION_COVERED"])
            total_line_m += int(row["LINE_MISSED"])
            total_line_c += int(row["LINE_COVERED"])
            total_br_m   += int(row["BRANCH_MISSED"])
            total_br_c   += int(row["BRANCH_COVERED"])

inst_pct   = pct_str(total_inst_c, total_inst_m + total_inst_c)
line_pct   = pct_str(total_line_c, total_line_m + total_line_c)
branch_pct = pct_str(total_br_c,   total_br_m   + total_br_c)


# ── Parse Checkstyle XML ──────────────────────────────────────────────────────
cs_files = []
cs_total = 0
cs_skipped = not os.path.exists(CHECKSTYLE_XML)
if not cs_skipped:
    try:
        root = ET.parse(CHECKSTYLE_XML).getroot()
        for fnode in root.findall("file"):
            errors = fnode.findall("error")
            if not errors:
                continue
            fname = fnode.get("name", "")
            short = fname.split("/src/main/java/")[-1] if "/src/main/java/" in fname \
                else fname.split("/src/")[-1] if "/src/" in fname else fname
            items = [{
                "line":     e.get("line", "-"),
                "col":      e.get("column", "-"),
                "severity": e.get("severity", "error"),
                "message":  e.get("message", ""),
                "source":   e.get("source", "").split(".")[-1],
            } for e in errors]
            cs_files.append({"path": short, "items": items})
            cs_total += len(items)
    except ET.ParseError:
        cs_skipped = True

cs_status = "skip" if cs_skipped else ("pass" if cs_total == 0 else "fail")


# ── Parse PMD XML ─────────────────────────────────────────────────────────────
pmd_files = []
pmd_total = 0
if os.path.exists(PMD_XML):
    ns = {"p": "http://pmd.sourceforge.net/report/2.0.0"}
    root = ET.parse(PMD_XML).getroot()
    # handle namespace or not
    files_iter = (root.findall("p:file", ns) or root.findall("file"))
    for fnode in files_iter:
        fname = fnode.get("name", "")
        short = fname.split("/src/main/java/")[-1] if "/src/main/java/" in fname \
            else fname.split("/src/")[-1] if "/src/" in fname else fname
        viols_iter = (fnode.findall("p:violation", ns) or fnode.findall("violation"))
        items = [{
            "line":     v.get("beginline", "-"),
            "rule":     v.get("rule", ""),
            "ruleset":  v.get("ruleset", ""),
            "priority": v.get("priority", "5"),
            "message":  (v.text or "").strip(),
            "url":      v.get("externalInfoUrl", ""),
        } for v in viols_iter]
        if items:
            pmd_files.append({"path": short, "items": items})
            pmd_total += len(items)

pmd_status = "pass" if pmd_total == 0 else "fail"

PRIORITY_LABEL = {"1": "高", "2": "中高", "3": "中", "4": "低", "5": "最低"}
PRIORITY_COLOR = {
    "1": "var(--fail)", "2": "#e67e22",
    "3": "#f39c12",     "4": "var(--accent)", "5": "var(--text-light)",
}

SEV_COLOR = {
    "CRITICAL": "var(--fail)", "HIGH": "#e74c3c",
    "MEDIUM": "#e67e22",       "LOW": "#f39c12",
    "INFO": "var(--accent)",   "WARNING": "#f39c12",
}


# ── Parse Semgrep JSON ────────────────────────────────────────────────────────
semgrep_items = []
semgrep_parse_errors = 0
semgrep_skipped = not os.path.exists(SEMGREP_JSON)
if not semgrep_skipped:
    with open(SEMGREP_JSON) as f:
        data = json.load(f)
    semgrep_parse_errors = len(data.get("errors", []))
    for r in data.get("results", []):
        ex = r.get("extra", {})
        meta = ex.get("metadata", {})
        semgrep_items.append({
            "path":     r.get("path", ""),
            "line":     r.get("start", {}).get("line", "-"),
            "rule":     r.get("check_id", "").split(".")[-1],
            "rule_id":  r.get("check_id", ""),
            "severity": ex.get("severity", "INFO").upper(),
            "message":  ex.get("message", ""),
            "cwe":      ", ".join(meta.get("cwe", [])),
            "owasp":    ", ".join(meta.get("owasp", [])),
            "lines":    ex.get("lines", ""),
        })
semgrep_total  = len(semgrep_items)
semgrep_by_sev = {
    "ERROR":   sum(1 for i in semgrep_items if i["severity"] == "ERROR"),
    "WARNING": sum(1 for i in semgrep_items if i["severity"] == "WARNING"),
    "HIGH":    sum(1 for i in semgrep_items if i["severity"] == "HIGH"),
    "MEDIUM":  sum(1 for i in semgrep_items if i["severity"] == "MEDIUM"),
    "LOW":     sum(1 for i in semgrep_items if i["severity"] == "LOW"),
    "INFO":    sum(1 for i in semgrep_items if i["severity"] == "INFO"),
}
# WARNING level findings do not block release — only ERROR and above
semgrep_blocking = sum(
    1 for i in semgrep_items if i["severity"] not in ("WARNING", "INFO", "LOW")
)
semgrep_status = "skip" if semgrep_skipped else ("pass" if semgrep_blocking == 0 else "fail")


# ── Parse Gitleaks JSON ───────────────────────────────────────────────────────
gitleaks_items = []
if os.path.exists(GITLEAKS_JSON):
    with open(GITLEAKS_JSON) as f:
        data = json.load(f)
    if isinstance(data, list):
        for r in data:
            gitleaks_items.append({
                "rule":    r.get("RuleID", r.get("Description", "")),
                "file":    r.get("File", ""),
                "line":    str(r.get("StartLine", "-")),
                "match":   r.get("Match", ""),
                "secret":  r.get("Secret", ""),
                "commit":  r.get("Commit", "")[:8] if r.get("Commit") else "-",
                "author":  r.get("Author", ""),
            })
gitleaks_total  = len(gitleaks_items)
gitleaks_status = "pass" if gitleaks_total == 0 else "fail"


# ── Parse Trivy TXT ───────────────────────────────────────────────────────────
# Trivy box-drawing chars: │ (U+2502), ─ (U+2500), ┌├└┐┤┘ etc.
_TRIVY_SKIP_FIRST = re.compile(
    r'^(Library|ID|Package|Target|Type|Module|Vulnerability|'
    r'Total|Tests|Report\s|┌|├|└|╔|╠|╚|═).*',
    re.IGNORECASE
)


def parse_trivy(path):
    """Extract target sections and finding rows from Trivy plain-text output."""
    if not os.path.exists(path):
        return [], 0
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()

    # Strip Tekton/kubectl timestamp prefixes (2026-06-28T11:xx:xx.xxxZ INFO ...)
    text = re.sub(r'^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s+\S+\s+', '', text, flags=re.MULTILINE)

    sections = []
    # Match section headers: "target (type)\n===========..."
    # Allow optional leading spaces and ≥3 '=' chars
    section_re = re.compile(r'^(.+?) \(([^)]+)\)\n[=]{3,}', re.MULTILINE)
    matches = list(section_re.finditer(text))

    for idx, m in enumerate(matches):
        target_name = m.group(1).strip()
        target_type = m.group(2).strip()
        body_start  = m.end()
        body_end    = matches[idx + 1].start() if idx + 1 < len(matches) else len(text)
        body        = text[body_start:body_end]

        # Summary line: "Total: 5 (UNKNOWN: 0, LOW: 0, MEDIUM: 3, HIGH: 2, CRITICAL: 0)"
        total_line = next(
            (ln.strip() for ln in body.splitlines()
             if re.match(r'(Total|Tests):', ln.strip())),
            ""
        )

        findings = []
        for line in body.splitlines():
            if "│" not in line:
                continue
            cols = [c.strip() for c in line.split("│")]
            cols = [c for c in cols if c]
            if not cols:
                continue
            # Skip header / separator rows
            if _TRIVY_SKIP_FIRST.match(cols[0]):
                continue
            # Need at least: library, CVE-id, severity
            if len(cols) >= 3:
                findings.append(cols)

        if findings or total_line:
            sections.append({
                "target":     target_name,
                "type":       target_type,
                "total_line": total_line,
                "items":      findings,
            })

    total = sum(len(s["items"]) for s in sections)
    return sections, total


trivy_sections, trivy_total = parse_trivy(TRIVY_TXT)
trivy_status = "pass" if trivy_total == 0 else "fail"


# ── Parse Wapiti JSON ─────────────────────────────────────────────────────────
wapiti_items = []
wapiti_info  = {}
if os.path.exists(WAPITI_JSON):
    with open(WAPITI_JSON) as f:
        data = json.load(f)
    wapiti_info = data.get("infos", {})
    classify    = data.get("classifications", {})
    for cat, vulns in {**data.get("vulnerabilities", {}),
                       **data.get("anomalies", {})}.items():
        for v in vulns:
            cls = classify.get(cat, {})
            wapiti_items.append({
                "category": cat,
                "path":     v.get("path", ""),
                "method":   v.get("method", "GET"),
                "parameter": v.get("parameter", ""),
                "info":     v.get("info", ""),
                "level":    str(v.get("level", 0)),
                "solution": cls.get("sol", ""),
            })
wapiti_total  = len(wapiti_items)
wapiti_status = "pass" if wapiti_total == 0 else "fail"

WAPITI_LEVEL_LABEL = {"0": "情報", "1": "低", "2": "中", "3": "高"}
WAPITI_LEVEL_COLOR = {
    "0": "var(--accent)", "1": "#f39c12",
    "2": "#e67e22",       "3": "var(--fail)",
}


# ── Release Gate ──────────────────────────────────────────────────────────────
# PMD: count high-priority (1=高, 2=中高) violations
pmd_high = sum(
    1 for f in pmd_files for i in f["items"] if i["priority"] in ("1", "2")
)

# Trivy: count CRITICAL / HIGH findings across all sections
def _trivy_sev(cols):
    return cols[2].upper().strip() if len(cols) > 2 else ""

trivy_critical_high = sum(
    1 for sec in trivy_sections for cols in sec["items"]
    if _trivy_sev(cols) in ("CRITICAL", "HIGH")
)

# Coverage gate: use line coverage
_line_cov_pct = float(line_pct)

GATES = [
    {
        "key":   "unit",
        "label": "ユニットテスト",
        "desc":  f"失敗 {su_fail} 件",
        "ok":    su_fail == 0,
        "cond":  "エラー 0 件",
    },
    {
        "key":   "e2e",
        "label": "統合テスト (E2E)",
        "desc":  f"失敗 {fa_fail} 件",
        "ok":    fa_fail == 0,
        "cond":  "エラー 0 件",
    },
    {
        "key":   "arch",
        "label": "アーキテクチャ (ArchUnit)",
        "desc":  f"失敗 {ar_fail} 件 / 全 {ar_total} ルール",
        "ok":    ar_fail == 0,
        "cond":  "アーキテクチャ違反 0 件",
    },
    {
        "key":   "coverage",
        "label": "カバレッジ (行)",
        "desc":  f"現在 {line_pct}%",
        "ok":    _line_cov_pct >= 80.0,
        "cond":  "80% 以上",
    },
    {
        "key":   "checkstyle",
        "label": "Checkstyle",
        "desc":  "スキップ / 評価できません" if cs_skipped else f"違反 {cs_total} 件",
        "ok":    cs_skipped or cs_total == 0,
        "cond":  "違反 0 件",
    },
    {
        "key":   "pmd",
        "label": "PMD",
        "desc":  f"高優先度 {pmd_high} 件 (全 {pmd_total} 件)",
        "ok":    pmd_high == 0,
        "cond":  "高優先度 (P1/P2) 0 件",
    },
    {
        "key":   "semgrep",
        "label": "Semgrep (SAST)",
        "desc":  (
            "スキップ / 評価できません" if semgrep_skipped else
            f"ブロッキング {semgrep_blocking} 件 / 全 {semgrep_total} 件"
            + (f" / パースエラー {semgrep_parse_errors} 件" if semgrep_parse_errors else "")
        ),
        "ok":    semgrep_skipped or semgrep_blocking == 0,
        "cond":  "ERROR/HIGH 0 件 (WARNING・LOW は合格)",
    },
    {
        "key":   "gitleaks",
        "label": "Gitleaks",
        "desc":  f"漏洩 {gitleaks_total} 件",
        "ok":    gitleaks_total == 0,
        "cond":  "シークレット漏洩なし",
    },
    {
        "key":   "trivy",
        "label": "Trivy",
        "desc":  f"CRITICAL/HIGH {trivy_critical_high} 件 (全 {trivy_total} 件)",
        "ok":    trivy_critical_high == 0,
        "cond":  "CRITICAL / HIGH 0 件",
    },
    {
        "key":   "wapiti",
        "label": "Wapiti (DAST)",
        "desc":  f"脆弱性 {wapiti_total} 件",
        "ok":    wapiti_total == 0,
        "cond":  "脆弱性なし",
    },
]

RELEASE_OK    = all(g["ok"] for g in GATES)
GATES_PASSED  = sum(1 for g in GATES if g["ok"])
GATES_TOTAL   = len(GATES)


# ── HTML builders ─────────────────────────────────────────────────────────────
def release_gate_html():
    if RELEASE_OK:
        verdict_color  = "var(--pass)"
        verdict_bg     = "#eafaf1"
        verdict_border = "#a9dfbf"
        verdict_icon   = """<svg width="36" height="36" viewBox="0 0 24 24" fill="none"
          stroke="currentColor" stroke-width="2.5">
          <path d="M22 11.08V12a10 10 0 11-5.93-9.14"/>
          <polyline points="22 4 12 14.01 9 11.01"/>
        </svg>"""
        verdict_text   = "リリース可能"
        verdict_sub    = f"全 {GATES_TOTAL} 件の条件をすべてクリアしています"
    else:
        verdict_color  = "var(--fail)"
        verdict_bg     = "#fdedec"
        verdict_border = "#f5b7b1"
        verdict_icon   = """<svg width="36" height="36" viewBox="0 0 24 24" fill="none"
          stroke="currentColor" stroke-width="2.5">
          <circle cx="12" cy="12" r="10"/>
          <line x1="15" y1="9" x2="9" y2="15"/>
          <line x1="9" y1="9" x2="15" y2="15"/>
        </svg>"""
        ng_count       = GATES_TOTAL - GATES_PASSED
        verdict_text   = "リリース不可"
        verdict_sub    = (
            f"{GATES_TOTAL} 件中 {ng_count} 件の条件が未達です"
            f"（{GATES_PASSED} 件クリア済み）"
        )

    # progress bar
    bar_pct = round(100 * GATES_PASSED / GATES_TOTAL)
    bar_color = verdict_color

    # gate rows
    rows = ""
    for g in GATES:
        icon_ok = """<svg width="16" height="16" viewBox="0 0 24 24" fill="none"
          stroke="var(--pass)" stroke-width="3">
          <polyline points="20 6 9 17 4 12"/></svg>"""
        icon_ng = """<svg width="16" height="16" viewBox="0 0 24 24" fill="none"
          stroke="var(--fail)" stroke-width="3">
          <circle cx="12" cy="12" r="10"/>
          <line x1="15" y1="9" x2="9" y2="15"/>
          <line x1="9" y1="9" x2="15" y2="15"/></svg>"""
        icon      = icon_ok if g["ok"] else icon_ng
        row_bg    = "" if g["ok"] else "background:#fff8f8;"
        desc_col  = "color:var(--pass)" if g["ok"] else "color:var(--fail);font-weight:600"
        rows += f"""<tr style="{row_bg}">
          <td style="width:28px;padding:10px 4px 10px 14px">{icon}</td>
          <td style="font-weight:600;font-size:13px;padding:10px 10px">{g['label']}</td>
          <td style="font-size:12px;color:var(--text-light);padding:10px 10px">{g['cond']}</td>
          <td style="font-size:12px;{desc_col};padding:10px 14px 10px 10px">{g['desc']}</td>
        </tr>"""

    return f"""<div style="background:{verdict_bg};border:2px solid {verdict_border};
      border-radius:12px;padding:20px 24px;margin-bottom:24px;">
      <div style="display:flex;align-items:center;gap:16px;margin-bottom:16px">
        <div style="color:{verdict_color};flex-shrink:0">{verdict_icon}</div>
        <div>
          <div style="font-size:22px;font-weight:800;color:{verdict_color};
            letter-spacing:-.3px">{verdict_text}</div>
          <div style="font-size:13px;color:var(--text-light);margin-top:2px">
            {verdict_sub}</div>
        </div>
        <div style="margin-left:auto;text-align:right">
          <div style="font-size:32px;font-weight:800;color:{bar_color}">{bar_pct}%</div>
          <div style="font-size:11px;color:var(--text-light)">
            {GATES_PASSED} / {GATES_TOTAL} 条件クリア</div>
        </div>
      </div>
      <div style="height:6px;background:rgba(0,0,0,.08);border-radius:3px;
        margin-bottom:20px;overflow:hidden">
        <div style="height:100%;width:{bar_pct}%;background:{bar_color};
          border-radius:3px;transition:width .4s"></div>
      </div>
      <table style="width:100%;border-collapse:collapse;
        background:rgba(255,255,255,.7);border-radius:8px;overflow:hidden">
        <thead>
          <tr style="background:rgba(0,0,0,.04)">
            <th style="width:28px"></th>
            <th style="text-align:left;font-size:11px;font-weight:700;
              color:var(--text-light);padding:7px 10px;text-transform:uppercase;
              letter-spacing:.5px">チェック項目</th>
            <th style="text-align:left;font-size:11px;font-weight:700;
              color:var(--text-light);padding:7px 10px;text-transform:uppercase;
              letter-spacing:.5px">合格条件</th>
            <th style="text-align:left;font-size:11px;font-weight:700;
              color:var(--text-light);padding:7px 14px 7px 10px;
              text-transform:uppercase;letter-spacing:.5px">現在の状態</th>
          </tr>
        </thead>
        <tbody>{rows}</tbody>
      </table>
    </div>"""


def donut_svg(pct_val, color):
    circ = 314.16
    arc  = circ * pct_val / 100
    fail_arc = circ - arc
    fail_seg = (
        f'<circle cx="70" cy="70" r="50" fill="none" stroke="var(--fail)"'
        f' stroke-width="24" stroke-dasharray="{fail_arc:.2f} {circ:.2f}"'
        f' stroke-dashoffset="{-arc:.2f}" transform="rotate(-90 70 70)"/>'
        if pct_val < 100 else ""
    )
    return f"""<svg width="120" height="120" viewBox="0 0 140 140">
      <circle cx="70" cy="70" r="50" fill="none" stroke="#eef0f3" stroke-width="24"/>
      <circle cx="70" cy="70" r="50" fill="none" stroke="{color}"
        stroke-width="24" stroke-dasharray="{arc:.2f} {circ:.2f}"
        stroke-dashoffset="0" transform="rotate(-90 70 70)"/>
      {fail_seg}
      <text x="70" y="65" text-anchor="middle" font-size="22"
        font-weight="700" fill="{color}">{pct_val}%</text>
      <text x="70" y="83" text-anchor="middle" font-size="11"
        fill="#7f8c8d">成功率</text>
    </svg>"""


def suite_table_rows(suites):
    rows = ""
    for s in suites:
        p = round(100 * s["pass"] / s["tests"]) if s["tests"] else 0
        rows += f"""<tr>
          <td><strong>{s['name']}</strong>
            <br><span style="font-size:11px;color:var(--text-light)">{s['pkg']}</span>
          </td>
          <td>{s['tests']}</td>
          <td style="color:var(--pass);font-weight:600">{s['pass']}</td>
          <td style="color:{'var(--fail)' if s['fail'] > 0 else 'var(--text-light)'}">
            {s['fail']}</td>
          <td>{fmt_time(s['time'])}</td>
          <td><div class="mini-bar">
            <div class="mini-fill" style="width:{p}%"></div></div></td>
        </tr>"""
    return rows


def suite_detail_html(suites):
    pkgs: dict = {}
    for s in suites:
        pkgs.setdefault(s["pkg"], []).append(s)
    html = ""
    for pkg, ss in pkgs.items():
        pkg_total = sum(x["tests"] for x in ss)
        pkg_pass  = sum(x["pass"]  for x in ss)
        html += f"""<div class="pkg-group">
          <div class="pkg-header" onclick="togglePkg(this)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="2">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2
                3h9a2 2 0 012 2z"/>
            </svg>
            {pkg}
            <span class="pkg-badge badge pass"
              style="margin-left:auto;">{pkg_pass}/{pkg_total}</span>
          </div>
          <div class="pkg-body">"""
        for s in ss:
            html += f"""<div style="border-bottom:1px solid var(--border);">
              <div style="padding:8px 28px;background:#fcfcfd;font-size:12px;
                font-weight:600;display:flex;align-items:center;gap:8px;cursor:pointer;"
                onclick="toggleCases(this)">
                {badge('fail' if s['fail'] > 0 else 'pass')} {s['name']}
                <span style="color:var(--text-light);margin-left:auto;font-size:11px">
                  {s['tests']} tests &nbsp; {fmt_time(s['time'])}</span>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="2">
                  <polyline points="6 9 12 15 18 9"/>
                </svg>
              </div>
              <div class="cases-body" style="display:none;">"""
            for c in s["cases"]:
                msg = (
                    f'<div style="font-size:11px;color:var(--fail);'
                    f'padding:4px 0 0 26px;">{c["message"]}</div>'
                    if c["message"] else ""
                )
                html += f"""<div class="test-row"
                  style="padding-left:44px;flex-direction:column;align-items:flex-start;">
                  <div style="display:flex;align-items:center;gap:10px;width:100%">
                    {badge(c['status'])}
                    <span class="test-name">{c['name']}</span>
                    <span class="test-time" style="margin-left:auto">
                      {fmt_time(c['time'])}</span>
                  </div>{msg}
                </div>"""
            html += "</div></div>"
        html += "</div></div>"
    return html


def test_list_html(suites):
    html = ""
    for s in suites:
        for c in s["cases"]:
            html += f"""<div class="test-row" data-status="{c['status']}">
              {badge(c['status'])}
              <div style="flex:1">
                <div class="test-name">{c['name']}</div>
                <div class="test-class">{s['pkg']}.{s['name']}</div>
              </div>
              <span class="test-time">{fmt_time(c['time'])}</span>
            </div>"""
    return html


def checkstyle_html():
    if cs_skipped:
        return (
            '<div style="padding:24px 20px;display:flex;align-items:center;gap:12px;">'
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#95a5a6" stroke-width="2">'
            '<circle cx="12" cy="12" r="10"/>'
            '<line x1="12" y1="8" x2="12" y2="12"/>'
            '<line x1="12" y1="16" x2="12.01" y2="16"/>'
            '</svg>'
            '<span style="color:var(--text-light);font-size:14px;">'
            'スキップ / 評価できません — Checkstyle の結果ファイルが見つかりませんでした'
            '</span></div>'
        )
    if cs_total == 0:
        return '<div style="padding:24px;color:var(--pass);font-weight:600;">✔ 違反なし</div>'
    html = ""
    for f in cs_files:
        html += f"""<div class="pkg-group">
          <div class="pkg-header" onclick="togglePkg(this)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
            </svg>
            {f['path']}
            <span class="pkg-badge badge fail"
              style="margin-left:auto;">{len(f['items'])} 件</span>
          </div>
          <div class="pkg-body">
            <table style="width:100%;border-collapse:collapse;font-size:12px;">
              <thead><tr>
                <th style="width:60px">行</th>
                <th style="width:80px">種別</th>
                <th>ルール</th>
                <th>メッセージ</th>
              </tr></thead><tbody>"""
        for item in f['items']:
            sev_color = "var(--fail)" if item['severity'] == "error" else "#e67e22"
            html += f"""<tr>
              <td style="color:var(--text-light)">{item['line']}</td>
              <td><span style="color:{sev_color};font-weight:600">
                {item['severity']}</span></td>
              <td style="font-family:monospace">{item['source']}</td>
              <td>{item['message']}</td>
            </tr>"""
        html += "</tbody></table></div></div>"
    return html


def pmd_html():
    if pmd_total == 0:
        return '<div style="padding:24px;color:var(--pass);font-weight:600;">✔ 違反なし</div>'
    html = ""
    for f in pmd_files:
        html += f"""<div class="pkg-group">
          <div class="pkg-header" onclick="togglePkg(this)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
            </svg>
            {f['path']}
            <span class="pkg-badge badge fail"
              style="margin-left:auto;">{len(f['items'])} 件</span>
          </div>
          <div class="pkg-body">
            <table style="width:100%;border-collapse:collapse;font-size:12px;">
              <thead><tr>
                <th style="width:60px">行</th>
                <th style="width:60px">優先度</th>
                <th style="width:120px">ルール</th>
                <th style="width:100px">カテゴリ</th>
                <th>メッセージ</th>
              </tr></thead><tbody>"""
        for item in f['items']:
            p_label = PRIORITY_LABEL.get(item['priority'], item['priority'])
            p_color = PRIORITY_COLOR.get(item['priority'], "var(--text)")
            rule_link = (
                f'<a href="{item["url"]}" target="_blank"'
                f' style="color:var(--accent)">{item["rule"]}</a>'
                if item["url"] else item["rule"]
            )
            html += f"""<tr>
              <td style="color:var(--text-light)">{item['line']}</td>
              <td><span style="color:{p_color};font-weight:600">{p_label}</span></td>
              <td style="font-family:monospace;font-size:11px">{rule_link}</td>
              <td style="font-size:11px;color:var(--text-light)">{item['ruleset']}</td>
              <td>{item['message']}</td>
            </tr>"""
        html += "</tbody></table></div></div>"
    return html


def cov_color(p):
    return ("var(--pass)" if float(p) >= 70
            else ("#e67e22" if float(p) >= 40 else "var(--fail)"))


def coverage_rows():
    rows = ""
    for pkg, r in sorted(cov_pkgs.items()):
        ip = pct_str(r["ic"], r["im"] + r["ic"])
        lp = pct_str(r["lc"], r["lm"] + r["lc"])
        bp = pct_str(r["bc"], r["bm"] + r["bc"])
        rows += f"""<tr>
          <td style="font-size:12px;font-family:monospace">{pkg}</td>
          <td>{r['classes']}</td>
          <td>
            <div style="font-size:11px;color:var(--text-light);margin-bottom:4px">
              {r['ic']} / {r['im'] + r['ic']}</div>
            <div class="mini-bar" style="width:100px">
              <div class="mini-fill"
                style="width:{ip}%;background:{cov_color(ip)}"></div></div>
            <div style="font-size:11px;font-weight:600;color:{cov_color(ip)}">{ip}%</div>
          </td>
          <td>
            <div style="font-size:11px;color:var(--text-light);margin-bottom:4px">
              {r['lc']} / {r['lm'] + r['lc']}</div>
            <div class="mini-bar" style="width:100px">
              <div class="mini-fill"
                style="width:{lp}%;background:{cov_color(lp)}"></div></div>
            <div style="font-size:11px;font-weight:600;color:{cov_color(lp)}">{lp}%</div>
          </td>
          <td>
            <div style="font-size:11px;color:var(--text-light);margin-bottom:4px">
              {r['bc']} / {r['bm'] + r['bc']}</div>
            <div class="mini-bar" style="width:100px">
              <div class="mini-fill"
                style="width:{bp}%;background:{cov_color(bp)}"></div></div>
            <div style="font-size:11px;font-weight:600;color:{cov_color(bp)}">{bp}%</div>
          </td>
        </tr>"""
    return rows


def semgrep_html():
    # ── Summary block (always shown) ──────────────────────────────────────────
    if semgrep_skipped:
        return (
            '<div style="padding:24px 20px;display:flex;align-items:center;gap:12px;">'
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#95a5a6" stroke-width="2">'
            '<circle cx="12" cy="12" r="10"/>'
            '<line x1="12" y1="8" x2="12" y2="12"/>'
            '<line x1="12" y1="16" x2="12.01" y2="16"/>'
            '</svg>'
            '<span style="color:var(--text-light);font-size:14px;">'
            'スキップ / 評価できません — Semgrep の結果ファイルが見つかりませんでした'
            '</span></div>'
        )

    sev_defs = [
        ("ERROR",   "var(--fail)",    semgrep_by_sev["ERROR"]),
        ("HIGH",    "#e74c3c",        semgrep_by_sev["HIGH"]),
        ("MEDIUM",  "#e67e22",        semgrep_by_sev["MEDIUM"]),
        ("WARNING", "#f39c12",        semgrep_by_sev["WARNING"]),
        ("LOW",     "var(--accent)",  semgrep_by_sev["LOW"]),
        ("INFO",    "var(--text-light)", semgrep_by_sev["INFO"]),
    ]
    sev_cells = "".join(
        f'<div style="text-align:center;padding:12px 16px;border-right:1px solid var(--border)">'
        f'<div style="font-size:22px;font-weight:700;color:{color}">{cnt}</div>'
        f'<div style="font-size:11px;color:var(--text-light);margin-top:2px">{sev}</div>'
        f'</div>'
        for sev, color, cnt in sev_defs
    )
    parse_warn_html = (
        f'<div style="display:flex;align-items:center;gap:8px;padding:8px 16px;'
        f'background:#fef9e7;border-top:1px solid #f9ca24;font-size:12px;color:#856404">'
        f'<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">'
        f'<path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>'
        f'<line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>'
        f'</svg>'
        f'パースエラー {semgrep_parse_errors} 件 — 一部のファイルはスキャンできませんでした'
        f'</div>'
        if semgrep_parse_errors > 0 else ""
    )
    summary_block = (
        f'<div style="border:1px solid var(--border);border-radius:10px;'
        f'overflow:hidden;margin-bottom:20px;">'
        f'<div style="padding:12px 16px;background:#f8f9fb;border-bottom:1px solid var(--border);'
        f'font-size:13px;font-weight:600;">スキャン結果サマリ</div>'
        f'<div style="display:flex;border-bottom:1px solid var(--border)">{sev_cells}</div>'
        f'{parse_warn_html}'
        f'</div>'
    )

    if not semgrep_items:
        return (
            summary_block +
            '<div style="padding:20px 16px;color:var(--pass);font-weight:600;">✔ 検出なし</div>'
        )

    by_path: dict = {}
    for item in semgrep_items:
        by_path.setdefault(item["path"], []).append(item)
    html = summary_block
    for path, items in sorted(by_path.items()):
        html += f"""<div class="pkg-group">
          <div class="pkg-header" onclick="togglePkg(this)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
            </svg>{path}
            <span class="pkg-badge badge fail"
              style="margin-left:auto">{len(items)} 件</span>
          </div>
          <div class="pkg-body">
            <table style="width:100%;border-collapse:collapse;font-size:12px">
              <thead><tr>
                <th style="width:60px">行</th>
                <th style="width:80px">深刻度</th>
                <th style="width:160px">ルール</th>
                <th>メッセージ</th>
                <th style="width:200px">CWE / OWASP</th>
              </tr></thead><tbody>"""
        for item in items:
            sc = SEV_COLOR.get(item["severity"], "var(--text)")
            code_block = (
                f'<div style="font-family:monospace;font-size:11px;'
                f'background:#f8f9fb;padding:4px 8px;margin-top:4px;'
                f'border-left:3px solid {sc}">{item["lines"]}</div>'
                if item["lines"] else ""
            )
            html += f"""<tr>
              <td style="color:var(--text-light)">{item['line']}</td>
              <td><span style="color:{sc};font-weight:600">{item['severity']}</span></td>
              <td style="font-family:monospace;font-size:11px">{item['rule']}</td>
              <td>{item['message']}{code_block}</td>
              <td style="font-size:11px;color:var(--text-light)">
                {item['cwe']}<br>{item['owasp']}</td>
            </tr>"""
        html += "</tbody></table></div></div>"
    return html


def gitleaks_html():
    if not gitleaks_items:
        return '<div style="padding:24px;color:var(--pass);font-weight:600;">✔ シークレット漏洩なし</div>'
    html = """<table style="width:100%;border-collapse:collapse;font-size:12px">
      <thead><tr>
        <th>ルール</th><th>ファイル</th><th>行</th>
        <th>コミット</th><th>作成者</th><th>マッチ</th>
      </tr></thead><tbody>"""
    for item in gitleaks_items:
        secret_display = item['secret'][:40] + "…" if len(item['secret']) > 40 \
            else item['secret']
        html += f"""<tr>
          <td><span style="color:var(--fail);font-weight:600">{item['rule']}</span></td>
          <td style="font-family:monospace;font-size:11px">{item['file']}</td>
          <td>{item['line']}</td>
          <td style="font-family:monospace;font-size:11px">{item['commit']}</td>
          <td>{item['author']}</td>
          <td style="font-family:monospace;font-size:11px;
            color:var(--fail)">{secret_display}</td>
        </tr>"""
    html += "</tbody></table>"
    return html


_TRIVY_COL_HEADS = ["ライブラリ", "脆弱性 ID", "深刻度", "ステータス", "インストール版", "修正版", "タイトル"]


def trivy_html():
    if not trivy_sections:
        return '<div style="padding:24px;color:var(--pass);font-weight:600;">✔ 脆弱性なし</div>'
    html = ""
    for sec in trivy_sections:
        item_cnt  = len(sec["items"])
        badge_cls = "fail" if item_cnt > 0 else "pass"
        badge_lbl = f"{item_cnt} 件" if item_cnt > 0 else "✔ なし"
        total_html = (
            f'<div style="font-size:11px;color:var(--text-light);padding:6px 14px;'
            f'border-bottom:1px solid var(--border);background:#fafbfc">'
            f'{sec["total_line"]}</div>'
            if sec.get("total_line") else ""
        )
        # Build column headers from actual data width
        n_cols = max((len(r) for r in sec["items"]), default=0)
        heads  = _TRIVY_COL_HEADS[:n_cols] + [""] * max(0, n_cols - len(_TRIVY_COL_HEADS))
        th_row = "".join(
            f'<th style="padding:8px 10px;text-align:left;font-size:11px;'
            f'font-weight:600;color:var(--text-light);text-transform:uppercase;'
            f'letter-spacing:.4px;background:#f8f9fb;border-bottom:1px solid var(--border)">'
            f'{h}</th>'
            for h in heads
        )
        html += f"""<div class="pkg-group">
          <div class="pkg-header" onclick="togglePkg(this)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="2">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
            </svg>
            <span style="font-weight:600">{sec['target']}</span>
            <span style="font-size:11px;color:var(--text-light);margin-left:6px">({sec['type']})</span>
            <span class="pkg-badge badge {badge_cls}" style="margin-left:auto">{badge_lbl}</span>
          </div>
          <div class="pkg-body">
            {total_html}
            <div style="overflow-x:auto">
            <table style="width:100%;border-collapse:collapse;font-size:12px;min-width:700px">
              <thead><tr>{th_row}</tr></thead>
              <tbody>"""
        for cols in sec["items"]:
            # Severity is column index 2 (0-based)
            sev = cols[2].upper().strip() if len(cols) > 2 else ""
            sc  = SEV_COLOR.get(sev, "var(--text)")
            row_html = ""
            for i, c in enumerate(cols):
                if i == 2:
                    cell_style = f"color:{sc};font-weight:700;white-space:nowrap"
                elif i == 1:
                    cell_style = "font-family:monospace;font-size:11px;white-space:nowrap"
                elif i in (4, 5):
                    cell_style = "font-family:monospace;font-size:11px;white-space:nowrap"
                else:
                    cell_style = "word-break:break-word"
                row_html += (
                    f'<td style="padding:8px 10px;border-bottom:1px solid #f0f2f5;'
                    f'vertical-align:top;{cell_style}">{c}</td>'
                )
            html += f"<tr>{row_html}</tr>"
        html += "</tbody></table></div></div></div>"
    return html


def wapiti_scan_info_html():
    """Render scan metadata and module coverage table."""
    if not os.path.exists(WAPITI_JSON):
        return ""
    target    = wapiti_info.get("target", "-")
    date_s    = wapiti_info.get("date", "-")
    version   = wapiti_info.get("version", "-")
    scope     = wapiti_info.get("scope", "-")
    pages_n   = wapiti_info.get("crawled_pages_nbr", 0)
    classify  = {}
    with open(WAPITI_JSON) as f:
        raw = json.load(f)
    classify = raw.get("classifications", {})

    # Build module summary table: all categories, detected count
    all_cats = {**raw.get("vulnerabilities", {}), **raw.get("anomalies", {})}
    cat_rows = ""
    for cat, vulns in sorted(all_cats.items()):
        cnt   = len(vulns)
        cls   = classify.get(cat, {})
        desc  = cls.get("desc", "")[:100] + ("…" if len(cls.get("desc","")) > 100 else "")
        badge_html = (
            f'<span style="color:var(--fail);font-weight:700">{cnt} 件</span>'
            if cnt > 0 else
            f'<span style="color:var(--pass);font-weight:600">✔ クリア</span>'
        )
        cat_rows += f"""<tr>
          <td style="font-weight:600;font-size:12px">{cat}</td>
          <td style="text-align:center">{badge_html}</td>
          <td style="font-size:11px;color:var(--text-light)">{desc}</td>
        </tr>"""

    return f"""<div style="background:#f8f9fb;border:1px solid var(--border);
      border-radius:10px;padding:20px;margin-bottom:20px;">
      <h3 style="font-size:14px;font-weight:600;margin-bottom:14px">スキャン情報</h3>
      <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:12px;
        margin-bottom:16px">
        <div><div style="font-size:11px;color:var(--text-light)">ツール</div>
          <div style="font-size:13px;font-weight:600">{version}</div></div>
        <div><div style="font-size:11px;color:var(--text-light)">対象 URL</div>
          <div style="font-size:13px;font-weight:600;word-break:break-all">{target}</div></div>
        <div><div style="font-size:11px;color:var(--text-light)">スキャン日時</div>
          <div style="font-size:13px;font-weight:600">{date_s}</div></div>
        <div><div style="font-size:11px;color:var(--text-light)">スコープ</div>
          <div style="font-size:13px;font-weight:600">{scope}</div></div>
        <div><div style="font-size:11px;color:var(--text-light)">クロールページ数</div>
          <div style="font-size:13px;font-weight:600">{pages_n}</div></div>
        <div><div style="font-size:11px;color:var(--text-light)">発見脆弱性数</div>
          <div style="font-size:13px;font-weight:600;
            color:{'var(--fail)' if wapiti_total > 0 else 'var(--pass)'}">{wapiti_total}</div>
        </div>
      </div>
      <h4 style="font-size:12px;font-weight:600;color:var(--text-light);
        text-transform:uppercase;letter-spacing:.5px;margin-bottom:8px">
        チェックモジュール一覧</h4>
      <table style="width:100%;border-collapse:collapse;font-size:12px">
        <thead><tr style="background:rgba(0,0,0,.04)">
          <th style="text-align:left;padding:6px 10px">カテゴリ</th>
          <th style="text-align:center;padding:6px 10px;width:100px">検出結果</th>
          <th style="text-align:left;padding:6px 10px">説明</th>
        </tr></thead>
        <tbody>{cat_rows}</tbody>
      </table>
    </div>"""


def wapiti_html():
    scan_block = wapiti_scan_info_html()

    if not wapiti_items:
        return (
            scan_block +
            '<div style="padding:24px;color:var(--pass);font-weight:600;">'
            '✔ 脆弱性なし</div>'
        )
    by_cat: dict = {}
    for item in wapiti_items:
        by_cat.setdefault(item["category"], []).append(item)
    html = scan_block
    for cat, items in sorted(by_cat.items()):
        html += f"""<div class="pkg-group">
          <div class="pkg-header" onclick="togglePkg(this)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="2">
              <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3
                L13.71 3.86a2 2 0 00-3.42 0z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>{cat}
            <span class="pkg-badge badge fail"
              style="margin-left:auto">{len(items)} 件</span>
          </div>
          <div class="pkg-body">
            <table style="width:100%;border-collapse:collapse;font-size:12px">
              <thead><tr>
                <th>パス</th><th style="width:70px">メソッド</th>
                <th style="width:100px">パラメータ</th>
                <th style="width:60px">レベル</th><th>詳細</th>
              </tr></thead><tbody>"""
        for item in items:
            lc = WAPITI_LEVEL_COLOR.get(item["level"], "var(--text)")
            ll = WAPITI_LEVEL_LABEL.get(item["level"], item["level"])
            html += f"""<tr>
              <td style="font-family:monospace;font-size:11px">{item['path']}</td>
              <td>{item['method']}</td>
              <td style="font-family:monospace;font-size:11px">{item['parameter']}</td>
              <td><span style="color:{lc};font-weight:600">{ll}</span></td>
              <td>{item['info']}</td>
            </tr>"""
        html += "</tbody></table></div></div>"
    return html


# ── Assemble HTML ─────────────────────────────────────────────────────────────
su_color = "#27ae60" if su_pct == 100 else ("#f39c12" if su_pct >= 70 else "#e74c3c")
fa_color = "#27ae60" if fa_pct == 100 else ("#f39c12" if fa_pct >= 70 else "#e74c3c")

# Wapiti nav item and page are rendered only when findings exist
_wapiti_nav = (
    '<div class="nav-item" onclick="showPage(\'wapiti\',this)">'
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">'
    '<path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3'
    ' L13.71 3.86a2 2 0 00-3.42 0z"/>'
    '<line x1="12" y1="9" x2="12" y2="13"/>'
    '<line x1="12" y1="17" x2="12.01" y2="17"/>'
    f'</svg>Wapiti (DAST)'
    f'<span class="nav-badge ng">{wapiti_total}</span>'
    '</div>'
) if wapiti_total > 0 else ""

_wapiti_page = (
    f'<div id="page-wapiti" class="page">'
    f'<div class="page-header">'
    f'<h1>Wapiti — DAST 動的スキャン</h1>'
    f'<p>{wapiti_total} 件の検出</p>'
    f'</div>'
    f'<div class="content"><div class="card">{wapiti_html()}</div></div>'
    f'</div>'
) if wapiti_total > 0 else ""

html = f"""<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>{PROJECT_NAME} — テスト結果レポート</title>
<style>
  :root {{
    --pass:#27ae60; --fail:#e74c3c; --skip:#f39c12;
    --bg:#f4f6f9; --card:#ffffff; --border:#dde3ec;
    --text:#2c3e50; --text-light:#7f8c8d; --accent:#3498db;
    --sidebar-bg:#1e2a3a;
  }}
  *{{box-sizing:border-box;margin:0;padding:0}}
  body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
    background:var(--bg);color:var(--text);display:flex;min-height:100vh}}
  .sidebar{{width:230px;background:var(--sidebar-bg);color:#ecf0f1;
    flex-shrink:0;display:flex;flex-direction:column}}
  .sidebar-logo{{padding:20px 16px 16px;border-bottom:1px solid rgba(255,255,255,.1)}}
  .sidebar-logo .project{{font-size:12px;font-weight:700;color:var(--accent);
    letter-spacing:.5px;text-transform:uppercase}}
  .sidebar-logo .module{{font-size:16px;font-weight:600;color:#fff;margin-top:2px}}
  .sidebar-logo .date{{font-size:11px;color:#95a5a6;margin-top:4px}}
  nav{{padding:12px 0;flex:1}}
  .nav-section{{font-size:10px;font-weight:700;color:#636e72;
    padding:12px 16px 4px;text-transform:uppercase;letter-spacing:.8px}}
  .nav-item{{display:flex;align-items:center;gap:10px;padding:9px 16px;
    cursor:pointer;font-size:13px;color:#bdc3c7;transition:background .15s}}
  .nav-item:hover,.nav-item.active{{background:rgba(255,255,255,.08);color:#fff}}
  .nav-item.active{{border-left:3px solid var(--accent)}}
  .nav-item svg{{width:15px;height:15px;flex-shrink:0}}
  .nav-badge{{margin-left:auto;font-size:10px;font-weight:700;
    padding:1px 6px;border-radius:10px;background:rgba(255,255,255,.12)}}
  .nav-badge.ok{{color:#27ae60}} .nav-badge.ng{{color:#e74c3c}}
  .sidebar-footer{{padding:12px 16px;border-top:1px solid rgba(255,255,255,.1);
    font-size:11px;color:#7f8c8d}}
  .main{{flex:1;overflow:auto}}
  .page{{display:none}} .page.active{{display:block}}
  .page-header{{background:var(--card);border-bottom:1px solid var(--border);
    padding:20px 28px}}
  .page-header h1{{font-size:20px;font-weight:600}}
  .page-header p{{font-size:13px;color:var(--text-light);margin-top:4px}}
  .content{{padding:24px 28px}}
  .summary-strip{{display:grid;grid-template-columns:repeat(5,1fr);
    gap:14px;margin-bottom:24px}}
  .strip-card{{background:var(--card);border:1px solid var(--border);
    border-radius:10px;padding:16px;display:flex;flex-direction:column;
    align-items:center;gap:4px}}
  .strip-card .val{{font-size:28px;font-weight:700}}
  .strip-card .lbl{{font-size:12px;color:var(--text-light);font-weight:500}}
  .strip-card.pass .val{{color:var(--pass)}}
  .strip-card.fail .val{{color:var(--fail)}}
  .strip-card.skip .val{{color:var(--skip)}}
  .strip-card.total .val{{color:var(--accent)}}
  .strip-card.time .val{{color:#8e44ad}}
  .two-col{{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-bottom:24px}}
  .chart-card{{background:var(--card);border:1px solid var(--border);
    border-radius:10px;padding:20px}}
  .chart-card h3{{font-size:14px;font-weight:600;margin-bottom:14px}}
  .donut-wrap{{display:flex;align-items:center;gap:20px}}
  .donut-legend{{display:flex;flex-direction:column;gap:8px}}
  .legend-item{{display:flex;align-items:center;gap:8px;font-size:13px}}
  .legend-dot{{width:10px;height:10px;border-radius:50%;flex-shrink:0}}
  .cov-bars{{display:flex;flex-direction:column;gap:14px}}
  .cov-item label{{font-size:12px;font-weight:600;color:var(--text-light);
    display:flex;justify-content:space-between;margin-bottom:6px}}
  .bar-track{{background:#eef0f3;border-radius:4px;height:10px;overflow:hidden}}
  .bar-fill{{height:100%;border-radius:4px}}
  .card{{background:var(--card);border:1px solid var(--border);
    border-radius:10px;overflow:hidden;margin-bottom:20px}}
  .card-head{{padding:14px 18px;border-bottom:1px solid var(--border);
    display:flex;align-items:center;justify-content:space-between}}
  .card-head h3{{font-size:14px;font-weight:600}}
  table{{width:100%;border-collapse:collapse;font-size:13px}}
  th{{background:#f8f9fb;padding:10px 14px;text-align:left;font-size:11px;
    font-weight:600;color:var(--text-light);text-transform:uppercase;
    letter-spacing:.5px;border-bottom:1px solid var(--border)}}
  td{{padding:10px 14px;border-bottom:1px solid #f0f2f5;vertical-align:middle}}
  tr:last-child td{{border-bottom:none}} tr:hover td{{background:#f8f9fb}}
  .badge{{display:inline-flex;align-items:center;gap:5px;padding:3px 9px;
    border-radius:20px;font-size:11px;font-weight:700;letter-spacing:.3px}}
  .badge svg{{width:10px;height:10px}}
  .badge.pass{{background:#eafaf1;color:var(--pass)}}
  .badge.fail{{background:#fdedec;color:var(--fail)}}
  .badge.skip{{background:#fef9e7;color:var(--skip)}}
  .badge.warn{{background:#fef9e7;color:#e67e22}}
  .badge.info{{background:#ebf5fb;color:var(--accent)}}
  .mini-bar{{width:80px;height:6px;background:#eef0f3;border-radius:3px;overflow:hidden}}
  .mini-fill{{height:100%;background:var(--pass);border-radius:3px}}
  .test-row{{display:flex;align-items:center;gap:10px;padding:9px 14px;
    border-bottom:1px solid #f0f2f5;font-size:13px}}
  .test-row:last-child{{border-bottom:none}} .test-row:hover{{background:#f8f9fb}}
  .test-name{{flex:1;font-family:'SF Mono','Consolas',monospace;font-size:12px}}
  .test-class{{color:var(--text-light);font-size:11px}}
  .test-time{{font-size:11px;color:var(--text-light);min-width:50px;text-align:right}}
  .pkg-group{{margin-bottom:0}}
  .pkg-header{{padding:9px 14px;background:#f8f9fb;font-size:12px;font-weight:600;
    color:var(--text-light);border-bottom:1px solid var(--border);
    display:flex;align-items:center;gap:8px;cursor:pointer;user-select:none}}
  .pkg-header:hover{{background:#eef0f3}}
  .pkg-body{{display:block}}
  .stat-row{{display:flex;gap:20px;margin-bottom:24px}}
  .stat-box{{flex:1;background:var(--card);border:1px solid var(--border);
    border-radius:10px;padding:18px 20px}}
  .stat-box h4{{font-size:13px;font-weight:600;color:var(--text-light);
    margin-bottom:12px;text-transform:uppercase;letter-spacing:.5px}}
</style>
</head>
<body>
<aside class="sidebar">
  <div class="sidebar-logo">
    <div class="project">Quarkus Droneshop</div>
    <div class="module">{PROJECT_NAME}</div>
    <div class="date">{now_str}</div>
  </div>
  <nav>
    <div class="nav-section">概要</div>
    <div class="nav-item active" onclick="showPage('overview',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
        <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/>
      </svg>ダッシュボード
    </div>

    <div class="nav-section">テスト</div>
    <div class="nav-item" onclick="showPage('surefire',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M9 11l3 3L22 4"/>
        <path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11"/>
      </svg>ユニットテスト
      <span class="nav-badge {'ok' if su_fail==0 else 'ng'}">{su_pass}/{su_total}</span>
    </div>
    <div class="nav-item" onclick="showPage('failsafe',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/>
        <path d="M8 14s1.5 2 4 2 4-2 4-2"/><line x1="9" y1="9" x2="9.01" y2="9"/>
        <line x1="15" y1="9" x2="15.01" y2="9"/>
      </svg>統合テスト (E2E)
      <span class="nav-badge {'ok' if fa_fail==0 else 'ng'}">{fa_pass}/{fa_total}</span>
    </div>

    <div class="nav-section">品質</div>
    <div class="nav-item" onclick="showPage('archunit',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <rect x="2" y="3" width="6" height="6"/><rect x="16" y="3" width="6" height="6"/>
        <rect x="9" y="15" width="6" height="6"/>
        <line x1="5" y1="9" x2="12" y2="15"/><line x1="19" y1="9" x2="12" y2="15"/>
      </svg>アーキテクチャ (ArchUnit)
      <span class="nav-badge {'ok' if ar_fail==0 else 'ng'}">{ar_pass}/{ar_total}</span>
    </div>
    <div class="nav-item" onclick="showPage('coverage',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
      </svg>カバレッジ (JaCoCo)
    </div>
    <div class="nav-item" onclick="showPage('checkstyle',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="9 11 12 14 22 4"/>
        <path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11"/>
      </svg>Checkstyle
      <span class="nav-badge {'ok' if cs_total==0 else 'ng'}">{cs_total}</span>
    </div>
    <div class="nav-item" onclick="showPage('pmd',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/>
        <line x1="12" y1="8" x2="12" y2="12"/>
        <line x1="12" y1="16" x2="12.01" y2="16"/>
      </svg>PMD
      <span class="nav-badge {'ok' if pmd_total==0 else 'ng'}">{pmd_total}</span>
    </div>

    <div class="nav-section">セキュリティ</div>
    <div class="nav-item" onclick="showPage('semgrep',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="11" cy="11" r="8"/>
        <line x1="21" y1="21" x2="16.65" y2="16.65"/>
      </svg>Semgrep (SAST)
      <span class="nav-badge {'ok' if semgrep_total==0 else 'ng'}">{semgrep_total}</span>
    </div>
    <div class="nav-item" onclick="showPage('gitleaks',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
        <path d="M7 11V7a5 5 0 0110 0v4"/>
      </svg>Gitleaks (シークレット)
      <span class="nav-badge {'ok' if gitleaks_total==0 else 'ng'}">{gitleaks_total}</span>
    </div>
    <div class="nav-item" onclick="showPage('trivy',this)">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
      </svg>Trivy (依存関係)
      <span class="nav-badge {'ok' if trivy_total==0 else 'ng'}">{trivy_total}</span>
    </div>
    {_wapiti_nav}
  </nav>
  <div class="sidebar-footer">Surefire · JaCoCo · Semgrep · Trivy</div>
</aside>

<main class="main">

  <!-- ── Overview ── -->
  <div id="page-overview" class="page active">
    <div class="page-header">
      <h1>ダッシュボード</h1>
      <p>Maven テスト結果 &nbsp;·&nbsp; {now_str}</p>
    </div>
    <div class="content">
      {release_gate_html()}
      <div class="summary-strip">
        <div class="strip-card total"><div class="val">{all_total}</div>
          <div class="lbl">総テスト数</div></div>
        <div class="strip-card pass"><div class="val">{all_passed}</div>
          <div class="lbl">成功</div></div>
        <div class="strip-card fail"><div class="val">{all_failed}</div>
          <div class="lbl">失敗</div></div>
        <div class="strip-card skip"><div class="val">{all_skipped}</div>
          <div class="lbl">スキップ</div></div>
        <div class="strip-card time"><div class="val">{fmt_time(all_elapsed)}</div>
          <div class="lbl">実行時間</div></div>
      </div>

      <div class="two-col">
        <div class="chart-card">
          <h3>ユニットテスト (Surefire)</h3>
          <div class="donut-wrap" style="flex-wrap:wrap;gap:16px">
            {donut_svg(su_pct, su_color)}
            <div class="donut-legend">
              <div class="legend-item">
                <div class="legend-dot" style="background:var(--pass)"></div>
                成功: {su_pass}</div>
              <div class="legend-item">
                <div class="legend-dot" style="background:var(--fail)"></div>
                失敗: {su_fail}</div>
              <div class="legend-item">
                <div class="legend-dot" style="background:var(--skip)"></div>
                スキップ: {su_skip}</div>
              <div class="legend-item" style="font-size:11px;color:var(--text-light)">
                計: {su_total} / {fmt_time(su_time)}</div>
            </div>
          </div>
        </div>
        <div class="chart-card">
          <h3>統合テスト / E2E (Failsafe)</h3>
          <div class="donut-wrap">
            {donut_svg(fa_pct, fa_color)}
            <div class="donut-legend">
              <div class="legend-item">
                <div class="legend-dot" style="background:var(--pass)"></div>
                成功: {fa_pass}</div>
              <div class="legend-item">
                <div class="legend-dot" style="background:var(--fail)"></div>
                失敗: {fa_fail}</div>
              <div class="legend-item">
                <div class="legend-dot" style="background:var(--skip)"></div>
                スキップ: {fa_skip}</div>
              <div class="legend-item" style="font-size:11px;color:var(--text-light)">
                計: {fa_total} / {fmt_time(fa_time)}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="two-col">
        <div class="chart-card">
          <h3>コードカバレッジ (JaCoCo)</h3>
          <div class="cov-bars">
            <div class="cov-item">
              <label><span>命令</span>
                <span>{total_inst_c}/{total_inst_m + total_inst_c}</span></label>
              <div class="bar-track">
                <div class="bar-fill"
                  style="width:{inst_pct}%;background:var(--accent)"></div></div>
              <div style="font-size:12px;color:var(--accent);margin-top:4px;
                font-weight:600">{inst_pct}%</div>
            </div>
            <div class="cov-item">
              <label><span>行</span>
                <span>{total_line_c}/{total_line_m + total_line_c}</span></label>
              <div class="bar-track">
                <div class="bar-fill"
                  style="width:{line_pct}%;background:var(--pass)"></div></div>
              <div style="font-size:12px;color:var(--pass);margin-top:4px;
                font-weight:600">{line_pct}%</div>
            </div>
            <div class="cov-item">
              <label><span>分岐</span>
                <span>{total_br_c}/{total_br_m + total_br_c}</span></label>
              <div class="bar-track">
                <div class="bar-fill"
                  style="width:{branch_pct}%;background:#e67e22"></div></div>
              <div style="font-size:12px;color:#e67e22;margin-top:4px;
                font-weight:600">{branch_pct}%</div>
            </div>
          </div>
        </div>
        <div class="chart-card">
          <h3>静的解析サマリ</h3>
          <table style="font-size:13px">
            <tbody>
              <tr><td style="padding:10px 8px;font-weight:600">Checkstyle</td>
                <td>{badge(cs_status)}</td>
                <td style="color:var(--text-light)">{cs_total} 件の違反</td></tr>
              <tr><td style="padding:10px 8px;font-weight:600">PMD</td>
                <td>{badge(pmd_status)}</td>
                <td style="color:var(--text-light)">{pmd_total} 件の違反</td></tr>
              <tr><td style="padding:10px 8px;font-weight:600">Semgrep</td>
                <td>{badge(semgrep_status)}</td>
                <td style="color:var(--text-light)">{semgrep_total} 件の検出</td></tr>
              <tr><td style="padding:10px 8px;font-weight:600">Gitleaks</td>
                <td>{badge(gitleaks_status)}</td>
                <td style="color:var(--text-light)">{gitleaks_total} 件のシークレット</td></tr>
              <tr><td style="padding:10px 8px;font-weight:600">Trivy</td>
                <td>{badge(trivy_status)}</td>
                <td style="color:var(--text-light)">{trivy_total} 件の脆弱性</td></tr>
              <tr><td style="padding:10px 8px;font-weight:600">Wapiti</td>
                <td>{badge(wapiti_status)}</td>
                <td style="color:var(--text-light)">{wapiti_total} 件の脆弱性</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>

  <!-- ── Surefire ── -->
  <div id="page-surefire" class="page">
    <div class="page-header">
      <h1>ユニットテスト (Surefire)</h1>
      <p>{su_total} テスト &nbsp;·&nbsp; 成功 {su_pass} &nbsp;·&nbsp;
        失敗 {su_fail} &nbsp;·&nbsp; {fmt_time(su_time)}</p>
    </div>
    <div class="content">
      <div class="card">
        <div class="card-head"><h3>スイート別サマリ</h3></div>
        <table>
          <thead><tr>
            <th>テストスイート</th><th>テスト数</th>
            <th>成功</th><th>失敗</th><th>実行時間</th><th>進捗</th>
          </tr></thead>
          <tbody>{suite_table_rows(surefire_suites)}</tbody>
        </table>
      </div>
      <div class="card">
        <div class="card-head"><h3>テスト詳細</h3></div>
        {suite_detail_html(surefire_suites)}
      </div>
    </div>
  </div>

  <!-- ── Failsafe ── -->
  <div id="page-failsafe" class="page">
    <div class="page-header">
      <h1>統合テスト / E2E (Failsafe)</h1>
      <p>{fa_total} テスト &nbsp;·&nbsp; 成功 {fa_pass} &nbsp;·&nbsp;
        失敗 {fa_fail} &nbsp;·&nbsp; {fmt_time(fa_time)}</p>
    </div>
    <div class="content">
      <div class="card">
        <div class="card-head"><h3>スイート別サマリ</h3></div>
        <table>
          <thead><tr>
            <th>テストスイート</th><th>テスト数</th>
            <th>成功</th><th>失敗</th><th>実行時間</th><th>進捗</th>
          </tr></thead>
          <tbody>{suite_table_rows(failsafe_suites)}</tbody>
        </table>
      </div>
      <div class="card">
        <div class="card-head"><h3>テスト詳細</h3></div>
        {suite_detail_html(failsafe_suites)}
      </div>
    </div>
  </div>

  <!-- ── Coverage ── -->
  <div id="page-coverage" class="page">
    <div class="page-header">
      <h1>カバレッジ詳細 (JaCoCo)</h1>
      <p>命令 {inst_pct}% &nbsp;·&nbsp; 行 {line_pct}% &nbsp;·&nbsp; 分岐 {branch_pct}%</p>
    </div>
    <div class="content">
      <div class="card">
        <table>
          <thead><tr>
            <th>パッケージ</th><th>クラス</th><th>命令</th><th>行</th><th>分岐</th>
          </tr></thead>
          <tbody>{coverage_rows()}</tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- ── Checkstyle ── -->
  <div id="page-checkstyle" class="page">
    <div class="page-header">
      <h1>Checkstyle</h1>
      <p>{cs_total} 件の違反</p>
    </div>
    <div class="content">
      <div class="card">{checkstyle_html()}</div>
    </div>
  </div>

  <!-- ── PMD ── -->
  <div id="page-pmd" class="page">
    <div class="page-header">
      <h1>PMD 静的解析</h1>
      <p>{pmd_total} 件の違反</p>
    </div>
    <div class="content">
      <div class="card">{pmd_html()}</div>
    </div>
  </div>

  <!-- ── ArchUnit ── -->
  <div id="page-archunit" class="page">
    <div class="page-header">
      <h1>アーキテクチャテスト (ArchUnit)</h1>
      <p>{ar_total} ルール &nbsp;·&nbsp; 合格 {ar_pass} &nbsp;·&nbsp;
        違反 {ar_fail} &nbsp;·&nbsp; {fmt_time(ar_time)}</p>
    </div>
    <div class="content">
      <div class="card">
        <div class="card-head"><h3>ルール一覧</h3></div>
        {suite_detail_html(arch_suites)}
      </div>
    </div>
  </div>

  <!-- ── Semgrep ── -->
  <div id="page-semgrep" class="page">
    <div class="page-header">
      <h1>Semgrep — SAST 静的解析</h1>
      <p>{semgrep_total} 件の検出</p>
    </div>
    <div class="content">
      <div class="card">{semgrep_html()}</div>
    </div>
  </div>

  <!-- ── Gitleaks ── -->
  <div id="page-gitleaks" class="page">
    <div class="page-header">
      <h1>Gitleaks — シークレット漏洩スキャン</h1>
      <p>{gitleaks_total} 件の検出</p>
    </div>
    <div class="content">
      <div class="card">{gitleaks_html()}</div>
    </div>
  </div>

  <!-- ── Trivy ── -->
  <div id="page-trivy" class="page">
    <div class="page-header">
      <h1>Trivy — 依存関係・設定スキャン</h1>
      <p>{trivy_total} 件の検出</p>
    </div>
    <div class="content">
      <div class="card">{trivy_html()}</div>
    </div>
  </div>

  <!-- ── Wapiti (findings only) ── -->
  {_wapiti_page}

</main>

<script>
function showPage(id, el) {{
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  document.getElementById('page-' + id).classList.add('active');
  el.classList.add('active');
}}
function togglePkg(el) {{
  const b = el.nextElementSibling;
  b.style.display = b.style.display === 'none' ? '' : 'none';
}}
function toggleCases(el) {{
  const b = el.nextElementSibling;
  b.style.display = b.style.display === 'none' ? '' : 'none';
}}
</script>
</body>
</html>"""

with open(OUT_FILE, "w", encoding="utf-8") as f:
    f.write(html)

release_label = "✔ リリース可能" if RELEASE_OK else f"✖ リリース不可 ({GATES_PASSED}/{GATES_TOTAL})"
print(
    f"[test-report] Generated: {OUT_FILE}\n"
    f"  Tests  — unit:{su_total}({su_pass}✔/{su_fail}✗)"
    f"  e2e:{fa_total}({fa_pass}✔/{fa_fail}✗)"
    f"  arch:{ar_total}({ar_pass}✔/{ar_fail}✗)\n"
    f"  Quality— cs:{cs_total}  pmd:{pmd_total}\n"
    f"  Security— semgrep:{semgrep_total}"
    f"  gitleaks:{gitleaks_total}"
    f"  trivy:{trivy_total}"
    f"  wapiti:{wapiti_total}\n"
    f"  Release— {release_label}"
)
