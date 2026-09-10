#!/usr/bin/env python3
"""Expected sparse-backfill slots, mirroring the Yano runtime.

Java sources this file mirrors (keep in sync):
  runtime/.../blockproducer/BackfillPolicy.java          -> resolve_interval / forecast_window
  runtime/.../blockproducer/DevnetBlockProducer.java     -> next_backfill_slot / expected_slots
                                                            (nextBackfillSlot, produceEmptyBlocksToSlot)

Empty-block backfill places a block every `interval` slots, pulled back to the first
slot of the next epoch when the step would cross an epoch boundary, and never past the
target. The target slot always gets a block.

Usage:
  backfill_expect.py --self-test
  backfill_expect.py --from 0 --target 3600 --interval 299 --epoch-length 1200
  backfill_expect.py --resolve 0 --security-param 100 --active-slots-coeff 1
"""
import argparse
import json
import math
import sys


def forecast_window_slots(security_param, active_slots_coeff):
    """floor(3k/f) — BackfillPolicy.forecastWindowSlots."""
    if security_param <= 0 or not math.isfinite(active_slots_coeff) \
            or active_slots_coeff <= 0 or active_slots_coeff > 1:
        raise ValueError("backfill requires positive k and 0 < f <= 1")
    return int(math.floor(3.0 * security_param / active_slots_coeff))


def resolve_interval(requested, security_param, active_slots_coeff, slot_leader=False):
    """BackfillPolicy.resolveInterval. 0 = automatic, 1 = dense, >= window rejected."""
    if requested < 0:
        raise ValueError("backfill interval must be non-negative")
    window = forecast_window_slots(security_param, active_slots_coeff)
    if requested >= window:
        raise ValueError("backfill interval must be below forecast window of %d slots" % window)
    if requested > 0:
        return requested
    return max(1, window // 2 if slot_leader else window - 1)


def next_backfill_slot(from_slot, target_slot, interval, epoch_length=None):
    """DevnetBlockProducer.nextBackfillSlot. epoch_length None/0 = no epoch provider."""
    nxt = min(from_slot + interval, target_slot)
    if interval > 1 and epoch_length:
        epoch_start = (from_slot // epoch_length + 1) * epoch_length
        if from_slot < epoch_start < nxt:
            nxt = epoch_start
    return nxt


def expected_slots(from_slot, target_slot, interval, epoch_length=None):
    """Slots produceEmptyBlocksToSlot() forges, given lastUsedSlot=from_slot."""
    if target_slot <= from_slot:
        return []
    slots = []
    current = next_backfill_slot(from_slot, target_slot, interval, epoch_length)
    while current <= target_slot:
        slots.append(current)
        if current >= target_slot:
            break
        current = next_backfill_slot(current, target_slot, interval, epoch_length)
    return slots


# (from, target, interval, epoch_length, expected) — the first three come from
# DevnetBlockProducerTest, the fourth from BackfillPolicyTest's automatic spacing,
# and the last is the k=100/f=1 three-epoch scenario this skill exercises.
SELF_TESTS = [
    ((0, 1050, 100, None), [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1050]),
    ((0, 620, 100, 250), [100, 200, 250, 350, 450, 500, 600, 620]),
    ((0, 175, 1000, 50), [50, 100, 150, 175]),
    ((0, 20, 1, 1200), list(range(1, 21))),
]


def self_test():
    failures = []
    for args, expected in SELF_TESTS:
        actual = expected_slots(*args)
        if actual != expected:
            failures.append("expected_slots%s -> %s, want %s" % (args, actual, expected))

    three_epochs = expected_slots(0, 3600, resolve_interval(0, 100, 1), 1200)
    if len(three_epochs) != 15 or three_epochs[-1] != 3600 or 1200 not in three_epochs \
            or 2400 not in three_epochs or 3600 not in three_epochs:
        failures.append("k=100,f=1,epoch=1200,0->3600 -> %s (want 15 slots incl. every epoch start)"
                        % three_epochs)

    for args, want in [((0, 100, 1), 299), ((0, 100, 1, True), 150), ((0, 100, 0.2), 1499),
                       ((1, 100, 1, True), 1), ((42, 100, 1), 42)]:
        got = resolve_interval(*args)
        if got != want:
            failures.append("resolve_interval%s -> %s, want %s" % (args, got, want))
    for args in [(300, 100, 1), (0, 0, 1), (-1, 100, 1)]:
        try:
            resolve_interval(*args)
            failures.append("resolve_interval%s should have been rejected" % (args,))
        except ValueError:
            pass

    for line in failures:
        print("SELF-TEST FAIL: " + line, file=sys.stderr)
    if failures:
        return 1
    print("backfill_expect self-test OK (%d vectors)" % (len(SELF_TESTS) + 6))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--from", dest="from_slot", type=int)
    parser.add_argument("--target", type=int)
    parser.add_argument("--interval", type=int)
    parser.add_argument("--epoch-length", type=int, default=0)
    parser.add_argument("--resolve", type=int, help="requested interval to resolve through the policy")
    parser.add_argument("--security-param", type=int)
    parser.add_argument("--active-slots-coeff", type=float)
    parser.add_argument("--slot-leader", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if args.resolve is not None:
        resolved = resolve_interval(args.resolve, args.security_param, args.active_slots_coeff,
                                    args.slot_leader)
        print(json.dumps({
            "requested": args.resolve,
            "resolved": resolved,
            "forecast_window_slots": forecast_window_slots(args.security_param,
                                                           args.active_slots_coeff),
        }))
        return 0

    if args.from_slot is None or args.target is None or args.interval is None:
        parser.error("--from, --target and --interval are required")
    slots = expected_slots(args.from_slot, args.target, args.interval, args.epoch_length)
    print(json.dumps({"from": args.from_slot, "target": args.target, "interval": args.interval,
                      "epoch_length": args.epoch_length, "count": len(slots), "slots": slots}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
