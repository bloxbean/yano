#!/usr/bin/env python3
"""Dependency-free canonical CBOR command encoder for Yano tutorials.

This is a teaching/CLI convenience, not a replacement for the versioned Java
contract codecs. It intentionally supports only the stock tutorial commands.
"""

from __future__ import annotations

import argparse
import pathlib
import sys


def head(major: int, value: int) -> bytes:
    if value < 0:
        raise ValueError("CBOR values must be non-negative")
    if value < 24:
        return bytes([(major << 5) | value])
    if value <= 0xFF:
        return bytes([(major << 5) | 24, value])
    if value <= 0xFFFF:
        return bytes([(major << 5) | 25]) + value.to_bytes(2, "big")
    if value <= 0xFFFFFFFF:
        return bytes([(major << 5) | 26]) + value.to_bytes(4, "big")
    if value <= 0xFFFFFFFFFFFFFFFF:
        return bytes([(major << 5) | 27]) + value.to_bytes(8, "big")
    raise ValueError("tutorial encoder supports unsigned values up to uint64")


def uint(value: int) -> bytes:
    return head(0, value)


def bstr(value: bytes) -> bytes:
    return head(2, len(value)) + value


def tstr(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return head(3, len(encoded)) + encoded


def array(*values: bytes) -> bytes:
    return head(4, len(values)) + b"".join(values)


def map_canonical(values: dict[str, bytes]) -> bytes:
    encoded = [(tstr(key), value) for key, value in values.items()]
    encoded.sort(key=lambda item: (len(item[0]), item[0]))
    return head(5, len(encoded)) + b"".join(key + value for key, value in encoded)


def bounded_text(value: str, label: str) -> str:
    if not value or len(value.encode("utf-8")) > 1024:
        raise ValueError(f"{label} must be 1..1024 UTF-8 bytes")
    return value


def payload(args: argparse.Namespace) -> bytes:
    choices = [args.payload_text is not None, args.payload_file is not None,
               args.payload_hex is not None]
    if sum(choices) > 1:
        raise ValueError("choose only one payload source")
    if args.payload_file is not None:
        value = pathlib.Path(args.payload_file).read_bytes()
    elif args.payload_hex is not None:
        value = bytes.fromhex(args.payload_hex)
    elif args.payload_text is not None:
        value = args.payload_text.encode("utf-8")
    else:
        value = b""
    if len(value) > 16 * 1024 * 1024:
        raise ValueError("tutorial payload exceeds 16 MiB")
    return value


def encode(args: argparse.Namespace) -> bytes:
    if args.machine == "approvals":
        item = tstr(bounded_text(args.item_id, "item id"))
        if args.operation == "propose":
            if args.required <= 0:
                raise ValueError("required approvals must be positive")
            return array(uint(0), item, bstr(payload(args)),
                         uint(args.required), uint(args.deadline_millis))
        return array(uint(1 if args.operation == "approve" else 2), item)

    if args.machine == "balances":
        if args.amount <= 0:
            raise ValueError("amount must be positive")
        operation = 0 if args.operation == "mint" else 1
        return array(uint(operation),
                     tstr(bounded_text(args.account, "account")),
                     uint(args.amount))

    if args.machine == "doc-trail":
        entry_hash = bytes.fromhex(args.entry_hash)
        if not entry_hash:
            raise ValueError("entry hash must not be empty")
        return array(tstr(bounded_text(args.entity_id, "entity id")),
                     bstr(entry_hash), tstr(args.reference))

    if args.machine == "webhook":
        body = pathlib.Path(args.body_file).read_bytes()
        values = {
            "body": bstr(body),
            "content-type": tstr(args.content_type),
        }
        if args.url:
            values["url"] = tstr(args.url)
        return map_canonical(values)

    raise ValueError(f"unsupported tutorial machine: {args.machine}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        description="Encode canonical CBOR commands used by Yano tutorials")
    machines = root.add_subparsers(dest="machine", required=True)

    approvals = machines.add_parser("approvals")
    approval_ops = approvals.add_subparsers(dest="operation", required=True)
    propose = approval_ops.add_parser("propose")
    propose.add_argument("item_id")
    propose.add_argument("--required", type=int, default=2)
    propose.add_argument("--deadline-millis", type=int, default=0)
    propose_payload = propose.add_mutually_exclusive_group()
    propose_payload.add_argument("--payload-text")
    propose_payload.add_argument("--payload-file")
    propose_payload.add_argument("--payload-hex")
    for operation in ("approve", "reject"):
        command = approval_ops.add_parser(operation)
        command.add_argument("item_id")

    balances = machines.add_parser("balances")
    balance_ops = balances.add_subparsers(dest="operation", required=True)
    for operation in ("mint", "transfer"):
        command = balance_ops.add_parser(operation)
        command.add_argument("account")
        command.add_argument("amount", type=int)

    trail = machines.add_parser("doc-trail")
    trail.add_argument("entity_id")
    trail.add_argument("entry_hash", help="hex-encoded application hash")
    trail.add_argument("--reference", default="")

    webhook = machines.add_parser("webhook")
    webhook.add_argument("--body-file", required=True)
    webhook.add_argument("--content-type", default="application/json")
    webhook.add_argument("--url", default="")
    return root


def main() -> int:
    try:
        print(encode(parser().parse_args()).hex())
        return 0
    except (OSError, UnicodeError, ValueError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
