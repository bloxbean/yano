#!/usr/bin/env python3
"""Copy a Haskell-compatible devnet genesis set into an isolated case directory.

The runtime rewrites `systemStart` inside whatever shelley-genesis.json it is pointed
at (GenesisConfig.resolveAndPersistGenesisTimestamp and DevnetGenesisShiftService), so
tests must never point Yano at the tracked fixtures under app/config/network/devnet/.
This copies them first and patches the Shelley parameters the case needs.

Prints JSON: resolved parameters, per-file sha256, derived shift/target values.
"""
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

GENESIS_FILES = ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json",
                 "conway-genesis.json", "protocol-param.json"]
KEY_FILES = ["vrf.skey", "kes.skey", "opcert.cert"]


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, help="tracked genesis dir (read-only input)")
    parser.add_argument("--dest", required=True, help="isolated per-case genesis dir")
    parser.add_argument("--slot-length", type=float, required=True, help="Shelley slotLength, seconds")
    parser.add_argument("--epoch-length", type=int, default=1200)
    parser.add_argument("--security-param", type=int, default=100)
    parser.add_argument("--active-slots-coeff", type=float, default=1.0)
    parser.add_argument("--epochs", type=int, default=3, help="epochs the shift moves genesis back")
    parser.add_argument("--slots-per-kes-period", type=int, default=0,
                        help="override slotsPerKESPeriod; 0 keeps the source value. Sum6KES has only "
                             "64 periods, so one opcert covers 64 * slotsPerKESPeriod slots")
    args = parser.parse_args()

    source, dest = Path(args.source), Path(args.dest)
    dest.mkdir(parents=True, exist_ok=True)
    for name in GENESIS_FILES + KEY_FILES:
        src = source / name
        if not src.exists():
            print("ERROR: missing %s" % src, file=sys.stderr)
            return 1
        shutil.copy2(src, dest / name)

    shelley_path = dest / "shelley-genesis.json"
    shelley = json.loads(shelley_path.read_text())
    original = {k: shelley.get(k) for k in
                ["epochLength", "slotLength", "securityParam", "activeSlotsCoeff"]}
    shelley["epochLength"] = args.epoch_length
    shelley["slotLength"] = args.slot_length
    shelley["securityParam"] = args.security_param
    shelley["activeSlotsCoeff"] = args.active_slots_coeff
    if args.slots_per_kes_period > 0:
        shelley["slotsPerKESPeriod"] = args.slots_per_kes_period
    shelley_path.write_text(json.dumps(shelley, indent=2) + "\n")

    window = 3.0 * args.security_param / args.active_slots_coeff
    result = {
        "source": str(source),
        "dest": str(dest),
        "original_shelley_params": original,
        "epoch_length": args.epoch_length,
        "slot_length_seconds": args.slot_length,
        "slot_length_millis": int(round(args.slot_length * 1000)),
        "security_param": args.security_param,
        "active_slots_coeff": args.active_slots_coeff,
        "network_magic": shelley.get("networkMagic"),
        "system_start_before_shift": shelley.get("systemStart"),
        # DevnetGenesisShiftService.computeEpochShiftMillis: epochs * epochLength * slotLength * 1000.
        # It reads genesis slotLength, NOT yano.block-producer.slot-length-millis.
        "expected_shift_millis": int(args.epochs * args.epoch_length * args.slot_length * 1000),
        "expected_target_slot": args.epochs * args.epoch_length,
        "forecast_window_slots": int(window),
        "slots_per_kes_period": shelley.get("slotsPerKESPeriod"),
        # Sum6KES = 2^6 periods. One operational certificate can never sign beyond this.
        "kes_coverage_slots": 64 * int(shelley.get("slotsPerKESPeriod", 0)),
        "kes_covers_run": 64 * int(shelley.get("slotsPerKESPeriod", 0))
                          >= args.epochs * args.epoch_length,
        # cardano-node validates the Shelley genesis: the epoch must cover the stability
        # window (3k/f) and the "epoch not long enough" bound (10k/f). Both must hold or
        # the Haskell peer refuses the genesis before any block is exchanged.
        "genesis_bounds_ok": args.epoch_length >= window and args.epoch_length >= 10.0 * window / 3.0,
        "sha256": {name: sha256(dest / name) for name in GENESIS_FILES + KEY_FILES},
    }
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
