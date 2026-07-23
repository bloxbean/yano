#!/usr/bin/env python3
"""Validate whether an anchor wallet's UTxO set is transaction-ready."""

from __future__ import annotations

import argparse
import json
import re
import sys
from typing import Any


MAX_ROWS = 1_000
MAX_LOVELACE = (1 << 63) - 1
MAX_OUTPUT_INDEX = (1 << 31) - 1
TX_HASH = re.compile(r"[0-9a-f]{64}")


def _pure_ada_quantities(document: Any) -> list[int]:
    if not isinstance(document, list) or len(document) > MAX_ROWS:
        return []
    quantities: list[int] = []
    outpoints: set[tuple[str, int]] = set()
    for row in document:
        if not isinstance(row, dict):
            continue
        tx_hash = row.get("tx_hash")
        output_index = row.get("output_index")
        if (
            not isinstance(tx_hash, str)
            or not TX_HASH.fullmatch(tx_hash)
            or isinstance(output_index, bool)
            or not isinstance(output_index, int)
            or not 0 <= output_index <= MAX_OUTPUT_INDEX
        ):
            continue
        outpoint = (tx_hash, output_index)
        if outpoint in outpoints:
            # A duplicated API row must never manufacture both the collateral
            # and spend inputs, or inflate the public-wallet reserve.
            return []
        outpoints.add(outpoint)
        amounts = row.get("amount")
        if not isinstance(amounts, list) or len(amounts) != 1:
            continue
        amount = amounts[0]
        if not isinstance(amount, dict) or amount.get("unit") != "lovelace":
            continue
        raw_quantity = amount.get("quantity")
        if isinstance(raw_quantity, bool):
            continue
        if isinstance(raw_quantity, int):
            quantity = raw_quantity
        elif (
            isinstance(raw_quantity, str)
            and raw_quantity.isascii()
            and raw_quantity.isdigit()
            and (raw_quantity == "0" or not raw_quantity.startswith("0"))
        ):
            quantity = int(raw_quantity)
        else:
            continue
        if 0 <= quantity <= MAX_LOVELACE:
            quantities.append(quantity)
    return quantities


def funding_ready(document: Any, public: bool) -> bool:
    quantities = _pure_ada_quantities(document)
    if not public:
        return any(quantity >= 1_000_000 for quantity in quantities)
    if sum(quantities) < 20_000_000:
        return False
    return any(
        left != right
        and quantities[left] >= 5_000_000
        and quantities[right] >= 10_000_000
        for left in range(len(quantities))
        for right in range(len(quantities))
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public", action="store_true")
    args = parser.parse_args()
    try:
        document = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError, RecursionError):
        return 1
    return 0 if funding_ready(document, args.public) else 1


if __name__ == "__main__":
    raise SystemExit(main())
