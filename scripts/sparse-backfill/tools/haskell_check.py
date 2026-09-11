#!/usr/bin/env python3
"""Verify a downstream Haskell cardano-node accepted a Yano sparse-backfilled chain.

Two independent sources, both required:

  * `cardano-cli query tip` -> {slot, block, hash}, compared against Yano's block at the
    same block number (slot AND hash must match). syncProgress is recorded but never
    used as evidence on its own.
  * the node log's "Chain extended, new tip: <hash> at slot <n>" lines (or the
    "<hash>@<n>" variant), proving genesis, every crossed epoch start and the backfilled
    target were adopted. Each sampled hash is looked up in Yano by hash, so a match
    proves both nodes hold the identical block.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

RE_CHAIN_EXTENDED = [
    re.compile(r"Chain extended, new tip:\s*([0-9a-fA-F]{64})\s+at slot\s+(\d+)"),
    re.compile(r"Chain extended, new tip:\s*([0-9a-fA-F]{64})@(\d+)"),
]
ERROR_PATTERNS = [
    re.compile(r"\b(Invalid|invalid)\b"),
    re.compile(r"\breject", re.I),
    re.compile(r"\bValidationError|ValidationFailed|ExtValidationError", re.I),
    re.compile(r"\bForecastError|OutsideForecastRange\b"),
    re.compile(r"\bChainSyncClient.*(exception|error)", re.I),
    re.compile(r"\bException\b"),
    re.compile(r"\bError\b"),
]
ERROR_IGNORE = [
    re.compile(r"Node configuration:"),          # verbose startup dump
    re.compile(r"EKGView|Address already in use"),
    re.compile(r"TraceErrorPolicy|ErrorPolicySuspend"),   # tracer names, not failures
    re.compile(r"errorPolicy|TraceLocalErrorPolicy"),
    re.compile(r"InvalidBlockPunishment"),        # tracer/type name in normal forge lines
]


def get_json(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode())


def yano_block(base_url, key):
    try:
        return get_json("%s/api/v1/blocks/%s" % (base_url.rstrip("/"), key))
    except urllib.error.HTTPError as e:
        return {"error": "HTTP %s" % e.code}
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}


def query_tip(cli, node_dir, magic):
    """Run from the instance directory with a RELATIVE socket path: AF_UNIX caps
    paths at 104 bytes and absolute run-directory paths exceed it."""
    env = dict(os.environ, CARDANO_NODE_SOCKET_PATH="db/node.socket")
    out = subprocess.run([cli, "query", "tip", "--testnet-magic", str(magic)],
                         capture_output=True, text=True, env=env, cwd=node_dir, timeout=120)
    if out.returncode != 0:
        return {"error": (out.stderr or out.stdout).strip()[:400]}
    return json.loads(out.stdout)


def adopted_from_log(log_path):
    adopted = {}
    for line in Path(log_path).read_text(errors="replace").splitlines():
        for pattern in RE_CHAIN_EXTENDED:
            match = pattern.search(line)
            if match:
                adopted[int(match.group(2))] = match.group(1).lower()
                break
    return adopted


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--haskell-log", required=True)
    parser.add_argument("--cli", required=True)
    parser.add_argument("--node-dir", required=True,
                        help="Haskell instance directory (socket is read as db/node.socket from here)")
    parser.add_argument("--magic", type=int, default=42)
    parser.add_argument("--yano-base-url", required=True)
    parser.add_argument("--expect-slots", required=True,
                        help="comma-separated slots that must be adopted (genesis, epoch starts, target)")
    parser.add_argument("--first-slot", type=int, default=0)
    parser.add_argument("--max-tip-lag", type=int, default=-1,
                        help="maximum allowed lag behind Yano's tip sampled before the CLI query")
    parser.add_argument("--phase", default="initial", choices=["initial", "live"])
    parser.add_argument("--min-live-slot", type=int, default=-1,
                        help="live phase: Haskell tip slot must exceed this")
    parser.add_argument("--reach-slot", type=int, default=-1,
                        help="Haskell tip slot must be at or past this (the backfilled target)")
    parser.add_argument("--hash-samples", type=int, default=20,
                        help="how many adopted tips to hash-compare against Yano")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    checks = []

    def add(name, ok, detail=""):
        checks.append({"check": "haskell:%s:%s" % (args.phase, name),
                       "status": "PASS" if ok else "FAIL", "detail": str(detail)})
        return ok

    adopted = adopted_from_log(args.haskell_log)
    add("chain-extended lines parsed", bool(adopted),
        "parsed %d adopted tips; if 0, the tracer format changed -- fix the parser" % len(adopted))

    expected = [int(s) for s in args.expect_slots.split(",") if s.strip() != ""]
    max_adopted = max(adopted) if adopted else -1
    # During bulk sync the ChainDB tracer does not log a tip line for every block, so a
    # required slot can be adopted without appearing here. Direct adoption is the strong
    # evidence; otherwise the slot is covered by prefix implication -- the node started
    # from an EMPTY database and its tip hash matches Yano's block at the same number
    # (checked below), and a Cardano chain is a hash chain back to genesis, so every
    # earlier slot in the chain is byte-identical too.
    direct = [s for s in expected if s in adopted]
    by_prefix = [s for s in expected if s not in adopted and s <= max_adopted]
    uncovered = [s for s in expected if s not in adopted and s > max_adopted]
    add("first block covered", (0 in adopted if args.first_slot == 0 else args.first_slot <= max_adopted),
        "first slot=%s, max adopted=%s (identical prefix checked below)" % (args.first_slot, max_adopted))
    add("required slots covered", not uncovered,
        "uncovered=%s (directly adopted=%s, covered by prefix=%s, max adopted slot=%s)"
        % (uncovered[:10], direct, by_prefix, max_adopted))

    # Hash comparison: every required slot that was logged, plus a spread of other
    # adopted tips, must resolve in Yano at exactly that slot.
    sample = sorted(adopted)
    if len(sample) > args.hash_samples:
        step = len(sample) / float(args.hash_samples)
        sample = [sample[int(i * step)] for i in range(args.hash_samples)]
    hash_mismatch, hash_checked = [], []
    for slot in sorted(set(direct) | set(sample)):
        block_hash = adopted.get(slot)
        if not block_hash:
            continue
        block = yano_block(args.yano_base_url, block_hash)
        if "error" in block or int(block.get("slot", -1)) != slot:
            hash_mismatch.append({"slot": slot, "haskell_hash": block_hash, "yano": block})
        else:
            hash_checked.append({"slot": slot, "hash": block_hash, "block": block["number"]})
    add("adopted hashes exist in Yano at the same slot", not hash_mismatch and bool(hash_checked),
        "checked=%d mismatches=%s" % (len(hash_checked), json.dumps(hash_mismatch[:3])))

    live_tip_before = (get_json(args.yano_base_url.rstrip("/") + "/api/v1/node/tip")
                       if args.max_tip_lag >= 0 else None)
    tip = query_tip(args.cli, args.node_dir, args.magic)
    add("cardano-cli query tip succeeded", "error" not in tip, tip.get("error", ""))
    tip_compare = None
    if "error" not in tip:
        if live_tip_before is not None:
            lag = int(live_tip_before["slot"]) - int(tip["slot"])
            add("Haskell stays close to Yano live tip", lag <= args.max_tip_lag,
                "sampled slot lag=%s, maximum=%s" % (lag, args.max_tip_lag))
        yano_tip_block = yano_block(args.yano_base_url, str(tip["block"]))
        ok = ("error" not in yano_tip_block
              and int(yano_tip_block["slot"]) == int(tip["slot"])
              and yano_tip_block["hash"].lower() == tip["hash"].lower())
        tip_compare = {"haskell": {k: tip.get(k) for k in ("slot", "block", "hash", "epoch",
                                                           "syncProgress")},
                       "yano": {k: yano_tip_block.get(k) for k in ("slot", "number", "hash", "epoch")}}
        add("Haskell tip block matches Yano by slot and hash", ok, json.dumps(tip_compare))
        if args.reach_slot >= 0:
            add("Haskell reached the backfilled tip", int(tip["slot"]) >= args.reach_slot,
                "haskell slot=%s, backfilled target=%s" % (tip["slot"], args.reach_slot))
        if args.min_live_slot >= 0:
            add("Haskell followed live blocks past the backfilled tip",
                int(tip["slot"]) > args.min_live_slot,
                "haskell slot=%s must exceed %s" % (tip["slot"], args.min_live_slot))

    suspicious = []
    for line in Path(args.haskell_log).read_text(errors="replace").splitlines():
        if any(p.search(line) for p in ERROR_PATTERNS) and not any(p.search(line) for p in ERROR_IGNORE):
            suspicious.append(line.strip()[:220])
    add("no validation/forecast/rejection errors in the Haskell log", not suspicious,
        "lines=%s" % suspicious[:5])

    status = "FAIL" if any(c["status"] == "FAIL" for c in checks) else "PASS"
    result = {"status": status, "phase": args.phase, "adopted_tip_count": len(adopted),
              "adopted_max_slot": max_adopted,
              "expected_slots": expected, "directly_adopted": direct,
              "covered_by_prefix": by_prefix, "hash_checked": hash_checked,
              "tip_compare": tip_compare, "suspicious_lines": suspicious[:20], "checks": checks}
    Path(args.out).write_text(json.dumps(result, indent=2))
    for item in checks:
        print("  [%s] %s%s" % (item["status"], item["check"],
                               "" if item["status"] == "PASS" else " -- " + item["detail"]))
    print("haskell_check(%s): %s" % (args.phase, status))
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
