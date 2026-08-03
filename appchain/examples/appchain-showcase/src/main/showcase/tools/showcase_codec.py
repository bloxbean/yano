#!/usr/bin/env python3
"""Dependency-free canonical CBOR helpers for the showcase shell scripts."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys


def head(major: int, value: int) -> bytes:
    if value < 0:
        raise ValueError("negative CBOR length/value")
    prefix = major << 5
    if value < 24:
        return bytes([prefix | value])
    if value <= 0xFF:
        return bytes([prefix | 24, value])
    if value <= 0xFFFF:
        return bytes([prefix | 25]) + value.to_bytes(2, "big")
    if value <= 0xFFFFFFFF:
        return bytes([prefix | 26]) + value.to_bytes(4, "big")
    if value <= 0xFFFFFFFFFFFFFFFF:
        return bytes([prefix | 27]) + value.to_bytes(8, "big")
    raise ValueError("CBOR integer is too large")


def uint(value: int) -> bytes:
    return head(0, value)


def bstr(value: bytes) -> bytes:
    return head(2, len(value)) + value


def text(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return head(3, len(encoded)) + encoded


def array(*items: bytes) -> bytes:
    return head(4, len(items)) + b"".join(items)


def emit(value: bytes) -> None:
    print(value.hex())


def canonical_order(value: str) -> bytes:
    parsed = json.loads(value)
    return json.dumps(parsed, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    kv = sub.add_parser("kv")
    kv.add_argument("operation", choices=("put", "delete"))
    kv.add_argument("key")
    kv.add_argument("value", nargs="?", default="")
    approval = sub.add_parser("approval")
    approval.add_argument("operation", choices=("propose", "approve", "reject"))
    approval.add_argument("item")
    approval.add_argument("payload", nargs="?", default="")
    approval.add_argument("--required", type=int, default=2)
    approval.add_argument("--deadline", type=int, default=0)
    balance = sub.add_parser("balance")
    balance.add_argument("operation", choices=("mint", "transfer"))
    balance.add_argument("account")
    balance.add_argument("amount", type=int)
    document = sub.add_parser("document")
    document.add_argument("entity")
    document.add_argument("content")
    document.add_argument("--reference", default="")
    release = sub.add_parser("release")
    release.add_argument("release_id")
    release.add_argument("order_key")
    release.add_argument("approval_id")
    role_probe = sub.add_parser("role-probe")
    role_probe.add_argument("mutation_id")
    order = sub.add_parser("order")
    order.add_argument("json")
    digest = sub.add_parser("blake2b")
    digest.add_argument("value")
    state_key = sub.add_parser("state-key")
    state_key.add_argument("kind", choices=("approval", "balance", "document", "release"))
    state_key.add_argument("value")
    args = parser.parse_args()

    if args.command == "kv":
        op = 0 if args.operation == "put" else 1
        emit(array(uint(op), bstr(args.key.encode()), bstr(args.value.encode())))
    elif args.command == "approval":
        if args.operation == "propose":
            if args.required < 1 or args.deadline < 0:
                raise ValueError("invalid approval policy")
            emit(array(uint(0), text(args.item), bstr(args.payload.encode()),
                       uint(args.required), uint(args.deadline)))
        else:
            emit(array(uint(1 if args.operation == "approve" else 2), text(args.item)))
    elif args.command == "balance":
        if args.amount < 1:
            raise ValueError("amount must be positive")
        emit(array(uint(0 if args.operation == "mint" else 1),
                   text(args.account), uint(args.amount)))
    elif args.command == "document":
        emit(array(text(args.entity),
                   bstr(hashlib.blake2b(args.content.encode(), digest_size=32).digest()),
                   text(args.reference)))
    elif args.command == "release":
        emit(array(uint(1), text(args.release_id), bstr(args.order_key.encode()),
                   text(args.approval_id)))
    elif args.command == "role-probe":
        if re.fullmatch(r"[a-z][a-z0-9-]{0,62}", args.mutation_id) is None:
            raise ValueError("invalid role probe id")
        emit(array(uint(1), uint(1), text(args.mutation_id),
                   bstr(hashlib.sha256(args.mutation_id.encode()).digest()), uint(0)))
    elif args.command == "order":
        print(canonical_order(args.json).decode("utf-8"))
    elif args.command == "blake2b":
        print(hashlib.blake2b(args.value.encode(), digest_size=32).hexdigest())
    elif args.command == "state-key":
        prefixes = {"approval": "i/", "balance": "b/", "document": "e/", "release": "r/"}
        print((prefixes[args.kind] + args.value).encode().hex())


if __name__ == "__main__":
    try:
        main()
    except (ValueError, json.JSONDecodeError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        raise SystemExit(2)
