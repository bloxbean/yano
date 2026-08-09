#!/usr/bin/env python3
"""Adopt the exact ConfigBlock emitted by showcase settlement bootstrap."""

from __future__ import annotations

import os
import pathlib
import re
import sys
import tempfile


def fail(message: str) -> None:
    raise SystemExit(message)


if len(sys.argv) != 3:
    fail("usage: adopt_settlement_config.py <application-appchain.yml> <bootstrap.log>")

config_path = pathlib.Path(sys.argv[1])
log_path = pathlib.Path(sys.argv[2])
config = config_path.read_text(encoding="utf-8")
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

indexes = [int(value) for value in re.findall(r"^\s*chains\[(\d+)]\s*:",
                                               config, re.MULTILINE)]
next_index = max(indexes, default=-1) + 1
block[0] = f"    chains[{next_index}]:"
updated = config.rstrip() + "\n" + "\n".join(block) + "\n"

mode = config_path.stat().st_mode & 0o777
with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=config_path.parent,
                                 prefix=config_path.name + ".", delete=False) as handle:
    handle.write(updated)
    temporary = pathlib.Path(handle.name)
os.chmod(temporary, mode)
os.replace(temporary, config_path)
print(f"adopted payment-chain-settlement as chains[{next_index}]")
