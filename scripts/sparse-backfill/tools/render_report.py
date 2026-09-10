#!/usr/bin/env python3
"""Render the combined sparse-backfill report from per-case artifacts."""
import argparse
import json
from pathlib import Path

STATUS_ORDER = {"FAIL": 0, "BLOCKED": 1, "NOTE": 2, "PASS": 3}


def read_json(path):
    try:
        return json.loads(Path(path).read_text())
    except Exception:  # noqa: BLE001 - absent artifacts are reported as gaps
        return {}


def read_stages(path):
    rows = []
    if Path(path).exists():
        for line in Path(path).read_text().splitlines():
            parts = line.split("\t")
            if len(parts) >= 2:
                rows.append((parts[0], parts[1], parts[2] if len(parts) > 2 else ""))
    return rows


def case_status(stages):
    if not stages:
        return "BLOCKED"
    statuses = {s for _, s, _ in stages}   # NOTE is informational and never fails a case
    if "FAIL" in statuses:
        return "FAIL"
    if "BLOCKED" in statuses:
        return "BLOCKED"
    return "PASS"


def fmt(value, suffix=""):
    return "-" if value in (None, "", 0) and value != 0 else "%s%s" % (value, suffix)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    run_dir = Path(args.run_dir)
    cases = sorted(d for d in run_dir.glob("case-*") if d.is_dir())
    lines = ["# Sparse backfill regression report", "",
             "Run directory: `%s`" % run_dir, ""]

    header = ("| case | interval setting | resolved interval | slot ms | initial slot | target slot |"
              " blocks produced | backfill elapsed | Haskell sync | status |")
    lines += [header, "|---|---|---|---|---|---|---|---|---|---|"]

    dense_elapsed = None
    rows = []
    for case_dir in cases:
        meta = read_json(case_dir / "meta.json")
        verify = read_json(case_dir / "verify.json")
        stages = read_stages(case_dir / "stages.tsv")
        status = case_status(stages)
        name = meta.get("case", case_dir.name.replace("case-", ""))
        elapsed = verify.get("catch_up_elapsed_ms", meta.get("catch_up_elapsed_ms"))
        if name == "dense" and elapsed:
            dense_elapsed = elapsed
        rows.append({
            "name": name, "meta": meta, "verify": verify, "stages": stages,
            "status": status, "dir": case_dir, "elapsed": elapsed,
        })
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            name,
            meta.get("requested_interval", "-"),
            verify.get("resolved_interval", "-"),
            meta.get("slot_length_ms", "-"),
            verify.get("initial_slot", "-"),
            verify.get("target_slot", "-"),
            verify.get("blocks_produced", "-"),
            fmt(elapsed, " ms"),
            fmt(meta.get("haskell_sync_elapsed_ms"), " ms"),
            status,
        ))

    lines += ["", "## Speed vs the dense baseline", ""]
    if dense_elapsed:
        lines.append("Dense (interval=1) catch-up took **%d ms**. Ratios are reported, not asserted;"
                     " the only timing assertion is that a backfill never waits for the live block"
                     " timer." % dense_elapsed)
        lines.append("")
        for row in rows:
            if row["elapsed"] and row["name"] != "dense":
                lines.append("- `%s`: %d ms (%.2fx the dense baseline, %s blocks vs %s)" % (
                    row["name"], row["elapsed"], row["elapsed"] / dense_elapsed,
                    row["verify"].get("blocks_produced", "?"),
                    next((r["verify"].get("blocks_produced", "?") for r in rows
                          if r["name"] == "dense"), "?")))
    else:
        lines.append("Dense baseline not run in this invocation.")

    for row in rows:
        lines += ["", "## Case `%s` — %s" % (row["name"], row["status"]), ""]
        verify = row["verify"]
        if verify:
            lines.append("Recorded: initial tip slot **%s** (block %s), target slot **%s**, "
                         "resolved interval **%s** (policy %s, forecast window %s slots), "
                         "blocks produced **%s**, epoch starts %s."
                         % (verify.get("initial_slot"), verify.get("initial_block_number"),
                            verify.get("target_slot"), verify.get("resolved_interval"),
                            verify.get("policy_interval"), verify.get("forecast_window_slots"),
                            verify.get("blocks_produced"), verify.get("epoch_start_slots")))
            if verify.get("speedup_vs_wallclock"):
                lines.append("")
                lines.append("Backfill covered %s ms of wall-clock time in %s ms (%sx)."
                             % (verify.get("wallclock_span_ms"), verify.get("catch_up_elapsed_ms"),
                                verify.get("speedup_vs_wallclock")))
            if verify.get("sampled_verification"):
                lines.append("")
                lines.append("Block-by-block verification was sampled (consecutive windows plus every"
                             " epoch-start block); counts and the tip were checked in full.")
            lines.append("")
        lines += ["| stage | status | detail |", "|---|---|---|"]
        for stage, status, detail in sorted(row["stages"], key=lambda r: STATUS_ORDER.get(r[1], 3)):
            lines.append("| %s | %s | %s |" % (stage, status, detail.replace("|", "\\|")[:220]))
        for artifact in ("haskell-initial.json", "haskell-live.json", "haskell-post-restart.json"):
            data = read_json(row["dir"] / artifact)
            if data.get("tip_compare"):
                lines += ["", "`%s`: %s adopted tips, matched hashes at slots %s; tip compare: %s"
                          % (artifact, data.get("adopted_tip_count"),
                             [h["slot"] for h in data.get("hash_checked", [])],
                             json.dumps(data["tip_compare"]))]
        lines.append("")
        lines.append("Artifacts: `%s`" % row["dir"])

    overall = "PASS"
    for row in rows:
        if row["status"] == "FAIL":
            overall = "FAIL"
            break
        if row["status"] == "BLOCKED":
            overall = "BLOCKED"
    lines = lines[:3] + ["**Overall: %s**" % overall, ""] + lines[3:]

    Path(args.out).write_text("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
