#!/usr/bin/env python3
"""Render a release-QA run into results.json, report.md and report.html.

Usage: render_report.py <run-dir> [--history <results-root>] [--known-issues <file>]

Reads <run-dir>/meta.json, results/*.json, logs/*.log and optional triage/<id>.md.
Exit code 0 when every test passed or failed only as a listed known issue.
"""
import argparse
import datetime
import html
import json
import re
import sys
from pathlib import Path

ANSI = re.compile(r"\x1b\[[0-9;]*m")
KEY_LINE = re.compile(
    r"^(elapsed=|tip:|yano tip|\[final\]|final haskell tip|hash match|haskell caught|catch-up|"
    r"post-catch-up|epoch transitions|yano epoch transitions|missed slots|tip slot delta|"
    r"yano ERROR lines|haskell error-like|native-init errors|smoke exit|functional(-shift)? exit|"
    r"summary\s*:|run-suite exit|  node-[ab] ERROR lines)")
CHECK_LINE = re.compile(r"^\s+(PASS|FAIL)\s{2}(.*)$")
# sparse-backfill prints "| case-check | PASS | detail |" rows
TABLE_CHECK = re.compile(r"^\|\s*([^|]+?)\s*\|\s*(PASS|FAIL|BLOCKED)\s*\|")
REPO_ROOT = str(Path(__file__).resolve().parents[2])
CATEGORY_NAMES = {"l1": "L1 node", "appchain": "App chain", "e2e": "Endpoints (e2e)",
                  "compat": "SDK compatibility", "load": "SDK load"}
GOOD = {"PASS", "KNOWN"}


def load_json(path, default=None):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return default


def parse_known_issues(path):
    """Rows of `| test id | match regex | issue | note |` from KNOWN-ISSUES.md."""
    rows = []
    if not path or not Path(path).is_file():
        return rows
    for line in Path(path).read_text().splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3 or not line.lstrip().startswith("|"):
            continue
        if cells[0].lower() in ("test", "test id") or set(cells[0]) <= set("-: "):
            continue
        rows.append({"test": cells[0].strip("`"), "match": cells[1].strip("`"),
                     "issue": cells[2], "note": cells[3] if len(cells) > 3 else ""})
    return rows


def highlights(log_path):
    try:
        text = Path(log_path).read_text(errors="replace").replace(REPO_ROOT + "/", "")
        lines = [ANSI.sub("", l.rstrip()) for l in text.splitlines()]
    except OSError:
        return {"lines": [], "checks_passed": 0, "checks_failed": []}
    passed, failed = 0, []
    for line in lines:
        m = CHECK_LINE.match(line)
        t = TABLE_CHECK.match(line)
        if m:
            if m.group(1) == "PASS":
                passed += 1
            else:
                failed.append(m.group(2).strip())
        elif t:
            if t.group(2) == "PASS":
                passed += 1
            else:
                failed.append(f"{t.group(1)} ({t.group(2).lower()})")
    keys, seen = [], set()
    for line in lines:
        if KEY_LINE.match(line) and line not in seen:
            seen.add(line)
            keys.append(line.strip()[:240])
    if not keys and not passed and not failed:
        keys = [l.strip()[:240] for l in lines if l.strip()][-6:]
    return {"lines": keys[:12], "checks_passed": passed, "checks_failed": failed[:12]}


def fmt_duration(seconds):
    seconds = int(seconds or 0)
    if seconds < 60:
        return f"{seconds}s"
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h}h {m:02d}m" if h else f"{m}m {s:02d}s"


def previous_statuses(run, history_dir):
    """Per test, the status from the most recent earlier run that included it."""
    found, runs = {}, []
    if history_dir and Path(history_dir).is_dir():
        runs = sorted((d for d in Path(history_dir).iterdir()
                       if d.is_dir() and d.name < run.name and (d / "results.json").is_file()), reverse=True)
    for d in runs:
        for t in (load_json(d / "results.json", {}) or {}).get("tests", []):
            found.setdefault(t["id"], (t["status"], d.name))
    return found, bool(runs)


def build(run_dir, history_dir, known_file):
    run = Path(run_dir)
    meta = load_json(run / "meta.json", {}) or {}
    order = meta.get("selected") or []
    tests = []
    for rid in order:
        r = load_json(run / "results" / f"{rid}.json")
        if r:
            tests.append(r)
    for p in sorted((run / "results").glob("*.json")):
        r = load_json(p)
        if r and r["id"] not in order:
            tests.append(r)

    known = parse_known_issues(known_file)
    prev, have_history = previous_statuses(run, history_dir)
    meta.pop("previous_run", None)
    meta["compared_runs"] = sorted({name for _, name in prev.values()})

    for t in tests:
        log_text = ""
        try:
            log_text = (run / t["log"]).read_text(errors="replace")
        except OSError:
            pass
        t["highlights"] = highlights(run / t["log"])
        t["known_issue"] = None
        if t["status"] in ("FAIL", "TIMEOUT"):
            for k in known:
                try:
                    hit = not k["match"] or re.search(k["match"], t.get("reason", "") + "\n" + log_text)
                except re.error as e:
                    print(f"WARN: KNOWN-ISSUES.md regex for {k['test']} is invalid: {e}", file=sys.stderr)
                    hit = False
                if k["test"] == t["id"] and hit:
                    t["known_issue"] = k
                    t["status"] = "KNOWN"
                    break
        triage = run / "triage" / f"{t['id']}.md"
        t["triage"] = triage.read_text().strip() if triage.is_file() else ""
        before, t["previous_run"] = prev.get(t["id"], (None, None))
        # BLOCKED means "not run", so it neither regresses nor fixes a test.
        if before is None:
            t["change"] = "new" if have_history else ""
        elif "BLOCKED" in (before, t["status"]):
            t["change"] = ""
        elif before in GOOD and t["status"] not in GOOD:
            t["change"] = "regressed"
        elif before not in GOOD and t["status"] in GOOD:
            t["change"] = "fixed"
        else:
            t["change"] = ""

    counts = {}
    for t in tests:
        counts[t["status"]] = counts.get(t["status"], 0) + 1
    meta["finished"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
    summary = {"total": len(tests), "counts": counts,
               "passed": bool(tests) and all(t["status"] in GOOD for t in tests) and not meta.get("build_failed"),
               "duration_s": sum(t.get("duration_s", 0) for t in tests)}
    return {"meta": meta, "summary": summary, "tests": tests}


# ------------------------------------------------------------------ markdown ----
def md_cell(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def render_md(data):
    m, s, tests = data["meta"], data["summary"], data["tests"]
    c = s["counts"]
    out = [f"# Yano release QA {m.get('commit_short', '')}", ""]
    verdict = "PASS" if s["passed"] else "ATTENTION"
    out.append(f"**{verdict}**: {c.get('PASS', 0)} passed, {c.get('KNOWN', 0)} known issues, "
               f"{c.get('FAIL', 0)} failed, {c.get('TIMEOUT', 0)} timed out, {c.get('BLOCKED', 0)} blocked "
               f"of {s['total']} tests in {fmt_duration(s['duration_s'])}.")
    out += ["", f"- Commit: `{m.get('commit', '')}` on `{m.get('branch', '')}`"
            f"{' (uncommitted changes)' if m.get('dirty') else ''}: {m.get('subject', '')}",
            f"- Version: {m.get('version', '')}; run `{m.get('run_id', '')}` on {m.get('host', '')}",
            f"- Java: {m.get('java', '')}; cardano-node: {m.get('haskell_node', '') or 'n/a'}"]
    if m.get("build_failed"):
        out.append(f"- Build failed: {', '.join(m['build_failed'])}")
    if m.get("compared_runs"):
        out.append("- Compared with the latest earlier result of each test (runs "
                   + ", ".join(f"`{r}`" for r in m["compared_runs"]) + ")")
    out.append("")
    for cat in dict.fromkeys(t["category"] for t in tests):
        out += [f"## {CATEGORY_NAMES.get(cat, cat)}", "",
                "| Test | Mode | Status | Duration | Change | Notes |", "|---|---|---|---|---|---|"]
        for t in (t for t in tests if t["category"] == cat):
            note = t.get("reason") or ""
            if t["known_issue"]:
                note = f"known: {t['known_issue']['issue']}"
            out.append(f"| {md_cell(t['title'])} (`{t['id']}`) | {t['mode']} | {t['status']} | "
                       f"{fmt_duration(t['duration_s'])} | {t['change']} | {md_cell(note)} |")
        out.append("")
    bad = [t for t in tests if t["status"] not in ("PASS",)]
    if bad:
        out += ["## Details", ""]
        for t in bad:
            out += [f"### {t['id']}: {t['status']}", "", f"Log: `{t['log']}`", ""]
            if t.get("reason"):
                out += [f"Reason: {t['reason']}", ""]
            if t["highlights"]["checks_failed"]:
                out += ["Failed checks: " + "; ".join(t["highlights"]["checks_failed"]), ""]
            if t["triage"]:
                out += [t["triage"], ""]
    if bad:
        out += ["", f"Re-run: `qa/release-qa.sh --only {','.join(t['id'] for t in bad)}`"]
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------- html ----
CSS = """
:root{--ground:#f5f7f6;--surface:#ffffff;--ink:#1b2220;--muted:#5b6662;--rule:#dce2df;
--accent:#2f5d8a;--pass:#1f7a4a;--pass-bg:#e3f2e9;--fail:#b3261e;--fail-bg:#fbe7e5;
--known:#8a5a00;--known-bg:#fbf0d9;--timeout:#b14a0e;--timeout-bg:#fde9dc;
--blocked:#5d5a7a;--blocked-bg:#ecebf3;--code-bg:#eef2f0;}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){color-scheme:dark;
--ground:#121715;--surface:#1a201e;--ink:#e3e9e6;--muted:#9aa6a1;--rule:#2c3532;--accent:#7fb0de;
--pass:#6fd39c;--pass-bg:#16301f;--fail:#ff8a80;--fail-bg:#3a1a18;--known:#f0c060;--known-bg:#34290f;
--timeout:#ffa36b;--timeout-bg:#3a2415;--blocked:#b8b4d8;--blocked-bg:#27263a;--code-bg:#202826;}}
:root[data-theme="dark"]{color-scheme:dark;--ground:#121715;--surface:#1a201e;--ink:#e3e9e6;
--muted:#9aa6a1;--rule:#2c3532;--accent:#7fb0de;--pass:#6fd39c;--pass-bg:#16301f;--fail:#ff8a80;
--fail-bg:#3a1a18;--known:#f0c060;--known-bg:#34290f;--timeout:#ffa36b;--timeout-bg:#3a2415;
--blocked:#b8b4d8;--blocked-bg:#27263a;--code-bg:#202826;}
body{background:var(--ground);color:var(--ink);font-family:"IBM Plex Sans",system-ui,-apple-system,
"Segoe UI",sans-serif;font-size:15px;line-height:1.55;}
.wrap{max-width:1080px;margin:0 auto;padding-inline:20px;padding-block:28px 56px;display:grid;gap:28px}
.mono,code{font-family:"IBM Plex Mono",ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.88em}
code{background:var(--code-bg);padding:1px 5px;border-radius:4px}
header{display:grid;gap:6px}
.eyebrow{text-transform:uppercase;letter-spacing:.08em;font-size:12px;color:var(--muted);font-weight:600}
h1{font-size:28px;line-height:1.2;margin:0;text-wrap:balance;font-weight:600}
h2{font-size:18px;margin:0;font-weight:600}
.meta{display:flex;flex-wrap:wrap;gap:4px 18px;color:var(--muted);font-size:13px}
.meta b{color:var(--ink);font-weight:500}
.verdict{display:grid;gap:12px;padding:18px 20px;border-radius:10px;border:1px solid var(--rule);
background:var(--surface)}
.verdict.ok{border-color:var(--pass);box-shadow:inset 4px 0 0 var(--pass)}
.verdict.bad{border-color:var(--fail);box-shadow:inset 4px 0 0 var(--fail)}
.verdict p{margin:0;font-size:20px;font-weight:600;text-wrap:balance}
.chips{display:flex;flex-wrap:wrap;gap:8px}
.pill{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:2px 10px;font-size:12.5px;
font-weight:600;letter-spacing:.02em;white-space:nowrap;font-variant-numeric:tabular-nums}
.PASS{color:var(--pass);background:var(--pass-bg)}.FAIL{color:var(--fail);background:var(--fail-bg)}
.KNOWN{color:var(--known);background:var(--known-bg)}.TIMEOUT{color:var(--timeout);background:var(--timeout-bg)}
.BLOCKED{color:var(--blocked);background:var(--blocked-bg)}
.cats{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}
.cat{display:grid;gap:8px;padding:14px 16px;border:1px solid var(--rule);border-radius:10px;background:var(--surface)}
.cat .n{font-size:22px;font-weight:600;font-variant-numeric:tabular-nums}
.cat .n small{font-size:14px;color:var(--muted);font-weight:500}
.bar{display:flex;height:6px;border-radius:3px;overflow:hidden;background:var(--rule)}
.bar i{display:block}.bar .PASS{background:var(--pass)}.bar .FAIL{background:var(--fail)}
.bar .KNOWN{background:var(--known)}.bar .TIMEOUT{background:var(--timeout)}.bar .BLOCKED{background:var(--blocked)}
section{display:grid;gap:12px}
.table{overflow-x:auto;border:1px solid var(--rule);border-radius:10px;background:var(--surface)}
table{border-collapse:collapse;width:100%;min-width:640px;font-variant-numeric:tabular-nums}
th,td{text-align:left;padding:10px 14px;border-bottom:1px solid var(--rule);vertical-align:top}
th{font-size:12px;text-transform:uppercase;letter-spacing:.06em;color:var(--muted);font-weight:600}
tr:last-child td{border-bottom:0}
td .id{display:block;color:var(--muted)}
.change{font-size:12.5px;font-weight:600}.change.regressed{color:var(--fail)}.change.fixed{color:var(--pass)}
.change.new{color:var(--accent)}
details summary{cursor:pointer;color:var(--accent);font-size:13px}
details summary:focus-visible{outline:2px solid var(--accent);outline-offset:2px;border-radius:3px}
.hl{margin:8px 0 0;padding:10px 12px;background:var(--code-bg);border-radius:6px;white-space:pre-wrap;
word-break:break-word;font-family:"IBM Plex Mono",ui-monospace,Menlo,monospace;font-size:12px;line-height:1.5}
.fail-card{display:grid;gap:8px;padding:16px 18px;border:1px solid var(--rule);border-radius:10px;background:var(--surface)}
.fail-card h3{margin:0;font-size:15px;display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.fail-card p{margin:0;max-width:75ch}
.triage{white-space:pre-wrap;max-width:80ch}
footer{color:var(--muted);font-size:13px;display:grid;gap:4px}
@media (max-width:520px){h1{font-size:23px}.verdict p{font-size:17px}}
"""


def esc(x):
    return html.escape(str(x if x is not None else ""))


def render_html(data):
    m, s, tests = data["meta"], data["summary"], data["tests"]
    c = s["counts"]
    order = ["PASS", "KNOWN", "FAIL", "TIMEOUT", "BLOCKED"]
    need = s["total"] - c.get("PASS", 0) - c.get("KNOWN", 0)
    if s["passed"]:
        headline = f"All {s['total']} tests passed" if not c.get("KNOWN") else \
            f"{c.get('PASS', 0)} of {s['total']} tests passed; the rest are known issues"
    else:
        headline = f"{need} of {s['total']} tests need attention"
    chips = "".join(f'<span class="pill {k}">{c[k]} {k.lower()}</span>' for k in order if c.get(k))
    meta_bits = [
        ("Commit", f'<span class="mono">{esc(m.get("commit_short"))}</span>'
                   f'{" + local changes" if m.get("dirty") else ""}'),
        ("Branch", esc(m.get("branch"))), ("Version", esc(m.get("version"))),
        ("Run", f'<span class="mono">{esc(m.get("run_id"))}</span>'), ("Host", esc(m.get("host"))),
        ("Total time", fmt_duration(s["duration_s"])),
    ]
    parts = [f"<title>Yano QA {esc(m.get('commit_short', ''))}</title>",
             '<link rel="preconnect" href="https://fonts.googleapis.com">',
             '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500'
             '&family=IBM+Plex+Sans:wght@400;500;600&display=swap">',
             f"<style>{CSS}</style>", '<div class="wrap">', "<header>",
             '<div class="eyebrow">Yano release QA</div>',
             f"<h1>{esc(m.get('subject') or 'Release QA run')}</h1>",
             '<div class="meta">' + "".join(f"<span>{k} <b>{v}</b></span>" for k, v in meta_bits) + "</div>",
             "</header>",
             f'<div class="verdict {"ok" if s["passed"] else "bad"}"><p>{esc(headline)}</p>'
             f'<div class="chips">{chips}</div>']
    if m.get("build_failed"):
        parts.append(f'<div>Build failed: {esc(", ".join(m["build_failed"]))}. See logs/build-*.log.</div>')
    parts.append("</div>")

    cats = list(dict.fromkeys(t["category"] for t in tests))
    parts.append('<div class="cats">')
    for cat in cats:
        ct = [t for t in tests if t["category"] == cat]
        ok = sum(1 for t in ct if t["status"] in GOOD)
        segs = "".join(f'<i class="{st}" style="flex:{sum(1 for t in ct if t["status"] == st)}"></i>'
                       for st in order if any(t["status"] == st for t in ct))
        parts.append(f'<div class="cat"><div class="eyebrow">{esc(CATEGORY_NAMES.get(cat, cat))}</div>'
                     f'<div class="n">{ok}<small> / {len(ct)} passing</small></div>'
                     f'<div class="bar" role="img" aria-label="{ok} of {len(ct)} passing">{segs}</div></div>')
    parts.append("</div>")

    for cat in cats:
        parts += [f"<section><h2>{esc(CATEGORY_NAMES.get(cat, cat))}</h2>", '<div class="table"><table>',
                  "<thead><tr><th>Test</th><th>Mode</th><th>Status</th><th>Time</th><th>Change</th>"
                  "<th>Evidence</th></tr></thead><tbody>"]
        for t in (t for t in tests if t["category"] == cat):
            h = t["highlights"]
            ev = []
            if h["checks_passed"] or h["checks_failed"]:
                ev.append(f"{h['checks_passed']} checks passed, {len(h['checks_failed'])} failed")
            if t["known_issue"]:
                ev.append(f"Known issue {t['known_issue']['issue']}")
            elif t.get("reason"):
                ev.append(t["reason"])
            body = esc("\n".join(h["lines"])) if h["lines"] else ""
            detail = (f'<details><summary>Key lines</summary><pre class="hl">{body}</pre></details>'
                      if body else "")
            parts.append(
                f'<tr><td>{esc(t["title"])}<span class="id mono">{esc(t["id"])}</span></td>'
                f'<td>{"Native" if t["mode"] == "native" else "JVM"}</td>'
                f'<td><span class="pill {t["status"]}">{esc(t["status"].lower())}</span></td>'
                f'<td class="mono">{fmt_duration(t["duration_s"])}</td>'
                f'<td><span class="change {t["change"]}">{esc(t["change"])}</span></td>'
                f'<td>{esc("; ".join(ev))}{detail}</td></tr>')
        parts.append("</tbody></table></div></section>")

    bad = [t for t in tests if t["status"] != "PASS"]
    if bad:
        parts.append("<section><h2>What needs a look</h2>")
        for t in bad:
            h = t["highlights"]
            parts.append(f'<div class="fail-card"><h3><span class="pill {t["status"]}">'
                         f'{esc(t["status"].lower())}</span>{esc(t["title"])} '
                         f'<span class="mono">{esc(t["id"])}</span></h3>')
            if t["known_issue"]:
                k = t["known_issue"]
                parts.append(f"<p>Known issue {esc(k['issue'])}. {esc(k['note'])}</p>")
            if t.get("reason"):
                parts.append(f"<p>Reason: {esc(t['reason'])}</p>")
            if h["checks_failed"]:
                parts.append(f"<p>Failed checks: {esc('; '.join(h['checks_failed']))}</p>")
            if t["triage"]:
                parts.append(f'<div class="triage">{esc(t["triage"])}</div>')
            parts.append(f'<p>Log: <code>{esc(t["log"])}</code></p></div>')
        parts.append("</section>")

    rerun = ",".join(t["id"] for t in bad)
    parts += ["<footer>",
              f'<span>Commit <span class="mono">{esc(m.get("commit"))}</span>: {esc(m.get("subject"))}</span>',
              f"<span>Java: {esc(m.get('java'))}. cardano-node: {esc(m.get('haskell_node') or 'n/a')}.</span>"]
    if m.get("compared_runs"):
        parts.append("<span>Changes compare each test with its latest earlier result (runs "
                     + ", ".join(f'<span class="mono">{esc(r)}</span>' for r in m["compared_runs"]) + ").</span>")
    if rerun:
        parts.append(f'<span>Re-run: <code>qa/release-qa.sh --only {esc(rerun)}</code></span>')
    parts += ["</footer>", "</div>"]
    return "\n".join(parts) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("--history", help="directory holding earlier run directories")
    ap.add_argument("--known-issues")
    a = ap.parse_args()
    data = build(a.run_dir, a.history, a.known_issues)
    run = Path(a.run_dir)
    (run / "results.json").write_text(json.dumps(data, indent=2))
    (run / "report.md").write_text(render_md(data))
    (run / "report.html").write_text(render_html(data))
    print(render_md(data).split("\n\n")[1] if data["tests"] else "no results")
    return 0 if data["summary"]["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
