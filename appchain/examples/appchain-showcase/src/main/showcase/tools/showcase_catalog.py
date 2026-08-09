#!/usr/bin/env python3
"""Read and validate the ADR-033 showcase catalog without third-party modules."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != 1 or value.get("profileId") != "light-v1":
        raise ValueError("unsupported showcase catalog")
    chains = value.get("chains")
    if not isinstance(chains, list) or len(chains) != 13:
        raise ValueError("light-v1 catalog must contain thirteen chains")
    ids: set[str] = set()
    scenarios: set[tuple[str, str]] = set()
    for chain in chains:
        chain_id = chain.get("chainId")
        if not isinstance(chain_id, str) or not re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", chain_id):
            raise ValueError("invalid catalog chain id")
        if chain_id in ids:
            raise ValueError("duplicate catalog chain id")
        ids.add(chain_id)
        pair = (str(chain.get("scenarioId")), str(chain.get("proofBackend")))
        if pair in scenarios and chain.get("classification") != "backend-comparison":
            raise ValueError("duplicate catalog scenario/backend")
        scenarios.add(pair)
        if not isinstance(chain.get("expectedCapabilities"), list):
            raise ValueError("catalog capabilities must be a list")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("catalog", type=Path)
    parser.add_argument("command", choices=("list", "describe", "json"))
    args = parser.parse_args()
    catalog = load(args.catalog)
    if args.command == "list":
        print("\n".join(chain["chainId"] for chain in catalog["chains"]))
    elif args.command == "describe":
        for chain in catalog["chains"]:
            capabilities = ", ".join(chain["expectedCapabilities"])
            print(f"{chain['chainId']:<32} {chain['classification']:<18} "
                  f"{chain['scenarioId']:<28} {chain['proofBackend'].upper():<3}  {capabilities}")
    else:
        print(json.dumps(catalog, sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        raise SystemExit(f"error: {failure}")
