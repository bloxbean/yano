#!/usr/bin/env python3
"""Verify a Yano devnet chain after a sparse/dense empty-block backfill.

Race-free by construction: the initial tip and the catch-up target are never taken from
a /node/tip poll around the API call. They are recovered from three independent
after-the-fact sources that must agree:

  1. runtime log  "Catching up to wall-clock: N slots (current=S0, target=T)"
  2. runtime log  "Sparse backfill: one block every I slots from slot S0+1 to T"
                  (absent when the resolved interval is 1)
  3. arithmetic   S0 = new_block_number - blocks_produced, from the catch-up response,
                  cross-checked against the slot of block S0 (the past-time-travel
                  prefix is dense, so block i sits at slot i)

The expected slot sequence is then derived from the recorded (S0, T) with
backfill_expect.py — never hardcoded.
"""
import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from backfill_expect import expected_slots, resolve_interval  # noqa: E402

RE_CATCHING_UP = re.compile(r"Catching up to wall-clock: (\d+) slots \(current=(-?\d+), target=(\d+)\)")
RE_SPARSE = re.compile(r"Sparse backfill: one block every (\d+) slots from slot (\d+) to (\d+)")
RE_ADVANCE_DONE = re.compile(r"Time advance complete: (\d+) empty blocks produced, new tip slot=(\d+), block=(\d+)")
RE_PRODUCER_STARTED = re.compile(r"Block producer started: interval=(\d+)ms, lazy=(\w+), slotLength=(\d+)ms")
RE_EPOCH_TRANSITION = re.compile(r"Epoch transition detected \(block producer\): (\d+) -> (\d+) at slot (\d+), block (\d+)")
RE_SKIPPED_EPOCHS = re.compile(r"Processing (\d+) skipped epoch transitions")
ERROR_PATTERNS = [
    re.compile(r"\bERROR\b"),
    re.compile(r"forecast window", re.I),
    re.compile(r"no eligible block", re.I),
    re.compile(r"Exception|Caused by:"),
    re.compile(r"nonce.*(missing|unavailable|failed)", re.I),
    re.compile(r"Epoch transition.*fail", re.I),
]
# Known-harmless lines. Extend from the runner with --ignore <regex> rather than
# widening these, so a new noisy line is a deliberate, reviewable decision.
ERROR_IGNORE = [
    re.compile(r"ExceptionMapper|ExceptionHandler"),          # class names in startup banners
    re.compile(r"backfill-block-interval"),                   # config echo of the property name
]


class Checks:
    def __init__(self):
        self.items = []

    def add(self, name, ok, detail=""):
        self.items.append({"check": name, "status": "PASS" if ok else "FAIL", "detail": str(detail)})
        return ok

    def failed(self):
        return [i for i in self.items if i["status"] == "FAIL"]


def get_json(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode())


def fetch_block(base_url, number_or_hash):
    try:
        return get_json("%s/api/v1/blocks/%s" % (base_url.rstrip("/"), number_or_hash))
    except urllib.error.HTTPError as e:
        return {"error": "HTTP %s" % e.code}
    except Exception as e:  # noqa: BLE001 - reported as a failed check
        return {"error": str(e)}


def select_numbers(first, last, epoch_start_numbers, max_fetch):
    """All block numbers when small; otherwise consecutive windows so hash continuity
    stays checkable, always covering both ends and every epoch-start block."""
    total = last - first + 1
    if total <= 0:
        return [], False
    if total <= max_fetch:
        return list(range(first, last + 1)), False
    keep = set(range(first, min(first + 10, last + 1)))
    keep.update(range(max(first, last - 9), last + 1))
    for number in epoch_start_numbers:
        keep.update(n for n in (number - 1, number, number + 1) if first <= n <= last)
    step = max(3, total // 40)
    for start in range(first, last + 1, step):
        keep.update(n for n in (start, start + 1, start + 2) if first <= n <= last)
    return sorted(keep), True


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--yano-log", required=True)
    parser.add_argument("--catchup-json", required=True)
    parser.add_argument("--requested-interval", type=int, required=True)
    parser.add_argument("--security-param", type=int, required=True)
    parser.add_argument("--active-slots-coeff", type=float, required=True)
    parser.add_argument("--epoch-length", type=int, required=True)
    parser.add_argument("--slot-length-ms", type=int, required=True)
    parser.add_argument("--elapsed-ms", type=int, required=True)
    parser.add_argument("--max-fetch", type=int, default=400)
    parser.add_argument("--ignore", action="append", default=[],
                        help="extra regex for known-harmless log lines (repeatable)")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    checks = Checks()
    log_text = Path(args.yano_log).read_text(errors="replace")
    catchup = json.loads(Path(args.catchup_json).read_text())
    base = args.base_url.rstrip("/")

    # --- resolved configuration ------------------------------------------------
    producer_started = RE_PRODUCER_STARTED.findall(log_text)
    block_time_ms = int(producer_started[-1][0]) if producer_started else 0
    log_slot_length_ms = int(producer_started[-1][2]) if producer_started else 0
    checks.add("producer start line present", bool(producer_started), producer_started[-1:] or "none")
    checks.add("resolved slotLength matches genesis", log_slot_length_ms == args.slot_length_ms,
               "log=%sms expected=%sms" % (log_slot_length_ms, args.slot_length_ms))
    # RuntimeNode.autoDeriveBlockTimeMillis: slotLength * 1000 / activeSlotsCoeff.
    expected_block_time_ms = int(args.slot_length_ms / args.active_slots_coeff)
    checks.add("resolved block interval matches genesis derivation",
               block_time_ms == expected_block_time_ms,
               "blockTime=%sms expected=%sms (slotLength=%sms / f=%s)"
               % (block_time_ms, expected_block_time_ms, args.slot_length_ms, args.active_slots_coeff))

    policy_interval = resolve_interval(args.requested_interval, args.security_param,
                                       args.active_slots_coeff, slot_leader=False)

    # --- initial tip and target, from three sources ----------------------------
    catching_up = RE_CATCHING_UP.findall(log_text)
    sparse = RE_SPARSE.findall(log_text)
    produced = int(catchup["blocks_produced"])
    final_block = int(catchup["new_block_number"])
    final_slot = int(catchup["new_slot"])

    checks.add("catch-up log line present", bool(catching_up), catching_up[-1:] or "none")
    if not catching_up:
        Path(args.out).write_text(json.dumps({"status": "FAIL", "checks": checks.items}, indent=2))
        print("FAIL: catch-up log line missing", file=sys.stderr)
        return 1
    initial_slot = int(catching_up[-1][1])
    target_slot = int(catching_up[-1][2])

    derived_initial_block = final_block - produced
    checks.add("initial tip agrees with block arithmetic", derived_initial_block == initial_slot,
               "log current=%d, new_block_number-blocks_produced=%d" % (initial_slot, derived_initial_block))
    checks.add("catch-up response tip equals recorded target", final_slot == target_slot,
               "response new_slot=%d target=%d" % (final_slot, target_slot))

    resolved_interval = policy_interval
    if sparse:
        log_interval, log_from, log_to = (int(x) for x in sparse[-1])
        resolved_interval = log_interval
        checks.add("logged interval matches BackfillPolicy", log_interval == policy_interval,
                   "log=%d policy(requested=%d, k=%d, f=%s)=%d"
                   % (log_interval, args.requested_interval, args.security_param,
                      args.active_slots_coeff, policy_interval))
        checks.add("sparse log start slot equals initial tip + 1", log_from == initial_slot + 1,
                   "log from=%d initial=%d" % (log_from, initial_slot))
        checks.add("sparse log target equals recorded target", log_to == target_slot,
                   "log to=%d target=%d" % (log_to, target_slot))
    else:
        checks.add("dense run logs no sparse line", policy_interval == 1,
                   "resolved interval=%d but no 'Sparse backfill' line" % policy_interval)

    # --- expected slot sequence, derived from the recorded values --------------
    expected = expected_slots(initial_slot, target_slot, resolved_interval, args.epoch_length)
    checks.add("blocks_produced equals derived expectation", len(expected) == produced,
               "expected=%d produced=%d (from=%d target=%d interval=%d epochLength=%d)"
               % (len(expected), produced, initial_slot, target_slot, resolved_interval,
                  args.epoch_length))
    checks.add("final expected slot is the target",
               bool(expected) and expected[-1] == target_slot,
               "last expected=%s target=%d" % (expected[-1] if expected else None, target_slot))

    epoch_starts = [s for s in expected if s % args.epoch_length == 0]
    numbers_by_slot = {slot: derived_initial_block + 1 + index for index, slot in enumerate(expected)}
    epoch_start_numbers = [numbers_by_slot[s] for s in epoch_starts]

    # --- chain contents --------------------------------------------------------
    prefix_numbers, prefix_sampled = select_numbers(0, derived_initial_block, [], args.max_fetch)
    backfill_numbers, backfill_sampled = select_numbers(derived_initial_block + 1, final_block,
                                                        epoch_start_numbers, args.max_fetch)
    blocks = {}
    for number in prefix_numbers + backfill_numbers:
        blocks[number] = fetch_block(base, number)
    missing = [n for n, b in blocks.items() if "error" in b]
    checks.add("all sampled blocks readable", not missing, "missing=%s" % missing[:10])
    if missing:
        Path(args.out).write_text(json.dumps({"status": "FAIL", "checks": checks.items}, indent=2))
        print("FAIL: blocks unreadable: %s" % missing[:10], file=sys.stderr)
        return 1

    prefix_bad = [n for n in prefix_numbers if blocks[n]["slot"] != n]
    checks.add("past-time-travel prefix is dense (slot == block number)", not prefix_bad,
               "mismatched=%s%s" % (prefix_bad[:10], " (sampled)" if prefix_sampled else ""))

    slot_mismatch = [(n, blocks[n]["slot"], expected[n - derived_initial_block - 1])
                     for n in backfill_numbers
                     if blocks[n]["slot"] != expected[n - derived_initial_block - 1]]
    checks.add("backfilled slots match the derived sequence", not slot_mismatch,
               "first mismatches (number, actual, expected)=%s%s"
               % (slot_mismatch[:5], " (sampled)" if backfill_sampled else ""))

    gaps = [(expected[i - 1], expected[i]) for i in range(1, len(expected))
            if expected[i] - expected[i - 1] > resolved_interval]
    checks.add("no gap exceeds the resolved interval", not gaps, "gaps=%s" % gaps[:5])
    window = int(3.0 * args.security_param / args.active_slots_coeff)
    widest = max([expected[0] - initial_slot] + [expected[i] - expected[i - 1]
                                                 for i in range(1, len(expected))]) if expected else 0
    checks.add("widest gap stays inside the forecast window", widest < window,
               "widest=%d window(3k/f)=%d" % (widest, window))

    continuity_bad = []
    fetched = sorted(blocks)
    for previous, current in zip(fetched, fetched[1:]):
        if current == previous + 1:
            if (blocks[current].get("previous_block") or "").lower() != blocks[previous]["hash"].lower():
                continuity_bad.append((previous, current))
    checks.add("hash continuity across consecutive fetched blocks", not continuity_bad,
               "breaks=%s" % continuity_bad[:5])

    epoch_bad = [(n, blocks[n]["slot"], blocks[n]["epoch"]) for n in backfill_numbers
                 if blocks[n]["epoch"] != blocks[n]["slot"] // args.epoch_length]
    checks.add("reported epoch matches slot // epochLength", not epoch_bad, "mismatches=%s" % epoch_bad[:5])

    epoch_start_present = [s for s in epoch_starts
                           if numbers_by_slot[s] in blocks and blocks[numbers_by_slot[s]]["slot"] == s]
    checks.add("every epoch start in range has a block",
               len(epoch_start_present) == len(epoch_starts),
               "epoch starts=%s verified=%s" % (epoch_starts, epoch_start_present))

    transitions = [(int(a), int(b), int(s)) for a, b, s, _ in RE_EPOCH_TRANSITION.findall(log_text)]
    crossed = [t for t in transitions if initial_slot < t[2] <= target_slot]
    checks.add("one epoch transition logged per crossed boundary",
               len(crossed) == len(epoch_starts)
               and sorted(t[2] for t in crossed) == sorted(epoch_starts),
               "logged=%s expected slots=%s" % (crossed, epoch_starts))
    checks.add("no skipped-epoch batch processing", not RE_SKIPPED_EPOCHS.search(log_text),
               RE_SKIPPED_EPOCHS.findall(log_text)[:3])

    tip = get_json("%s/api/v1/node/tip" % base)
    checks.add("tip block number matches catch-up response", tip["blockNumber"] >= final_block,
               "tip=%s response block=%d" % (tip, final_block))

    # --- speed: backfill must not wait for the live block timer -----------------
    timer_bound_ms = produced * max(block_time_ms, 1)
    checks.add("backfill did not wait for live block timers", args.elapsed_ms < timer_bound_ms,
               "elapsed=%dms vs %d blocks x %dms=%dms" % (args.elapsed_ms, produced, block_time_ms,
                                                          timer_bound_ms))
    wallclock_span_ms = (target_slot - initial_slot) * args.slot_length_ms

    # --- log scan ---------------------------------------------------------------
    ignore = ERROR_IGNORE + [re.compile(pattern) for pattern in args.ignore]
    suspicious = []
    for line in log_text.splitlines():
        if any(p.search(line) for p in ERROR_PATTERNS) and not any(p.search(line) for p in ignore):
            suspicious.append(line.strip()[:200])
    checks.add("no validation/nonce/forecast/epoch errors in the Yano log", not suspicious,
               "lines=%s" % suspicious[:5])

    status = "FAIL" if checks.failed() else "PASS"
    result = {
        "status": status,
        "initial_slot": initial_slot,
        "initial_block_number": derived_initial_block,
        "target_slot": target_slot,
        "final_block_number": final_block,
        "blocks_produced": produced,
        "requested_interval": args.requested_interval,
        "resolved_interval": resolved_interval,
        "policy_interval": policy_interval,
        "forecast_window_slots": window,
        "slot_length_ms": args.slot_length_ms,
        "block_time_ms": block_time_ms,
        "epoch_length": args.epoch_length,
        "catch_up_elapsed_ms": args.elapsed_ms,
        "wallclock_span_ms": wallclock_span_ms,
        "speedup_vs_wallclock": round(wallclock_span_ms / args.elapsed_ms, 1) if args.elapsed_ms else None,
        "expected_slots": expected if len(expected) <= 200 else expected[:100] + ["..."] + expected[-100:],
        "epoch_start_slots": epoch_starts,
        "sampled_verification": prefix_sampled or backfill_sampled,
        "checks": checks.items,
    }
    Path(args.out).write_text(json.dumps(result, indent=2))
    for item in checks.items:
        print("  [%s] %s%s" % (item["status"], item["check"],
                               "" if item["status"] == "PASS" else " -- " + item["detail"]))
    print("verify_chain: %s (%d blocks from slot %d to %d, interval %d)"
          % (status, produced, initial_slot, target_slot, resolved_interval))
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
