#!/usr/bin/env python3
"""Adopt the exact ConfigBlock emitted by showcase settlement bootstrap."""

from __future__ import annotations

import os
import json
import pathlib
import re
import sys
import tempfile


def fail(message: str) -> None:
    raise SystemExit(message)


if len(sys.argv) != 4:
    fail("usage: adopt_settlement_config.py <application-appchain.yml> <bootstrap.log> <catalog.json>")

config_path = pathlib.Path(sys.argv[1])
log_path = pathlib.Path(sys.argv[2])
catalog_path = pathlib.Path(sys.argv[3])
config = config_path.read_text(encoding="utf-8")
catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
catalog_ids = [chain["chainId"] for chain in catalog.get("chains", [])]
try:
    settlement_index = catalog_ids.index("payment-chain-settlement")
except ValueError:
    fail("catalog contains no payment-chain-settlement")
if re.search(r'^\s*chain-id:\s*["\']?payment-chain-settlement["\']?\s*$',
             config, re.MULTILINE):
    print("payment-chain-settlement already exists; no change")
    raise SystemExit(0)

lines = log_path.read_text(encoding="utf-8").splitlines()
start = None
first = None
for index, line in enumerate(lines):
    match = re.match(r"^ConfigBlock\s*:\s+(chains\[0\]:)\s*$", line)
    if match:
        start = index + 1
        first = "    " + match.group(1)
        break
if start is None or first is None:
    fail("bootstrap log contains no generated ConfigBlock")

block = [first]
for line in lines[start:]:
    if not line.strip():
        break
    block.append(line)
if len(block) < 20 or not any("expected-profile-digest:" in line for line in block):
    fail("generated settlement ConfigBlock is incomplete")
if not any('profile: "yano-eutxo-v3-bridge-settlement"' in line for line in block):
    fail("generated settlement ConfigBlock is not the production profile")

# Insert at the catalog position and shift later blocks. Public prepare has
# removed the devnet settlement block, so document-review currently occupies
# the settlement slot. This preserves catalog order without migrating any
# app-chain state.
def shift(match: re.Match[str]) -> str:
    index = int(match.group(1))
    return f"    chains[{index + 1 if index >= settlement_index else index}]:"

config = re.sub(r"^    chains\[(\d+)]\s*:", shift, config, flags=re.MULTILINE)
block[0] = f"    chains[{settlement_index}]:"
block_text = "\n".join(block)
next_header = f"    chains[{settlement_index + 1}]:"
if re.search(rf"^{re.escape(next_header)}\s*$", config, re.MULTILINE):
    # Keep textual YAML order aligned with numeric/catalog order. Appending the
    # adopted block after the shifted successor gives valid indexed keys but a
    # misleading chain declaration order and fails retained-identity checks.
    updated = re.sub(
        rf"^{re.escape(next_header)}\s*$",
        block_text + "\n" + next_header,
        config,
        count=1,
        flags=re.MULTILINE,
    ).rstrip() + "\n"
else:
    updated = config.rstrip() + "\n" + block_text + "\n"
# Only the chain declaration directly under ``chains[n]`` is a catalog entry.
# Production settlement profiles also contain nested observer/indexer
# ``chain-id`` fields; treating those as peers in the catalog rejects a valid
# ConfigBlock after the L1 bootstrap has already spent funds.
configured_ids = re.findall(
    r'^ {6}chain-id:\s*["\']?([^"\'\s]+)["\']?\s*$', updated, re.MULTILINE)
if configured_ids != catalog_ids:
    fail("adopted settlement config does not match catalog order")

mode = config_path.stat().st_mode & 0o777
with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=config_path.parent,
                                 prefix=config_path.name + ".", delete=False) as handle:
    handle.write(updated)
    temporary = pathlib.Path(handle.name)
os.chmod(temporary, mode)
os.replace(temporary, config_path)
print(f"adopted payment-chain-settlement as chains[{settlement_index}]")
