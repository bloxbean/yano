#!/usr/bin/env python3
"""Add the ADR-028 qualification chain to a staged showcase distribution."""

from __future__ import annotations

import json
import re
import secrets
import sys
from pathlib import Path


CHAIN_ID = "cardano-history-chain"
FINGERPRINT = "91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b"


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: add_cardano_history_pilot.py CONFIG CATALOG CATALOG_TOOL")
    config_path, catalog_path, tool_path = map(Path, sys.argv[1:])

    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    chains = catalog.get("chains")
    if not isinstance(chains, list) or len(chains) != 12:
        raise ValueError("ADR-028 pilot requires the released twelve-chain catalog")
    if any(chain.get("chainId") == CHAIN_ID for chain in chains):
        raise ValueError("Cardano History pilot is already present")
    chains.append({
        "chainId": CHAIN_ID,
        "scenarioId": "cardano-history",
        "classification": "qualification",
        "stateMachine": "cardano-history-pilot",
        "proofBackend": "mpf",
        "anchorEligible": True,
        "smokeHandler": "cardano-history",
        "expectedCapabilities": ["state-commitment:mpf-blake2b256-v1"],
    })
    catalog_path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")

    tool = tool_path.read_text(encoding="utf-8")
    exact = "if not isinstance(chains, list) or len(chains) != 12:"
    replacement = "if not isinstance(chains, list) or len(chains) not in (12, 13):"
    if tool.count(exact) != 1:
        raise ValueError("staged catalog tool no longer has the expected cardinality guard")
    tool = tool.replace(exact, replacement)
    tool = tool.replace(
        'raise ValueError("light-v1 catalog must contain twelve chains")',
        'raise ValueError("light-v1 catalog must contain twelve or thirteen chains")')
    tool_path.write_text(tool, encoding="utf-8")

    config = config_path.read_text(encoding="utf-8")
    yano_header = "yano:\n  app-chain:\n"
    replacement_header = (
        "yano:\n"
        "  # ADR-028 qualification: reconcile exactly ten retained epoch boundaries.\n"
        "  account-state:\n"
        "    snapshot-retention-epochs: 10\n"
        "  app-chain:\n"
    )
    if config.count(yano_header) != 1:
        raise ValueError("staged app-chain YAML no longer has the expected yano root")
    config = config.replace(yano_header, replacement_header)
    if re.search(r"(?m)^    chains\[12]:", config):
        raise ValueError("staged app-chain YAML already has chain index 12")
    genesis_id = secrets.token_hex(32)
    config += f'''\n
    # ADR-028 preprod qualification fixture. ADR-035 owns product packaging/UI.
    chains[12]:
      chain-id: "{CHAIN_ID}"
      state-machine: "cardano-history-pilot"
      max-message-bytes: 3145728
      membership:
        mode: "governed"
      block:
        interval-ms: 1000
        max-bytes: 4194304
      l1:
        stability-depth: 36
        epoch-stability-depth: 2160
      retention:
        enabled: false
      state:
        commitment-profile: "mpf-blake2b256-v1"
        format-fingerprint: "{FINGERPRINT}"
        genesis-id: "{genesis_id}"
        l1-proof-consumption-required: true
        proof-pruning:
          enabled: false
          retain-heights: 10000
          interval-seconds: 3600
      observers:
        epoch-params:
          type: "l1-epoch-params-v1"
        epoch-stake:
          type: "l1-epoch-stake-v1"
          chunk-entries: 25000
        epoch-governance:
          type: "l1-epoch-governance-v1"
          include-proposals: true
          include-drep-distribution: true
          drep-chunk-entries: 25000
      machines:
        epoch-stake:
          chunk-entries: 25000
        epoch-governance:
          drep-chunk-entries: 25000
'''
    config_path.write_text(config, encoding="utf-8")
    print(f"Added {CHAIN_ID} with a fresh MPF commitment generation")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        raise SystemExit(f"error: {failure}")
