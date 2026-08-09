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


def cbor_map(entries: list[tuple[str, bytes]]) -> bytes:
    encoded = [(text(key), value) for key, value in entries]
    encoded.sort(key=lambda item: item[0])
    return head(5, len(encoded)) + b"".join(
        key + value for key, value in encoded)


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
    digest_hex = sub.add_parser("blake2b-hex")
    digest_hex.add_argument("value")
    review = sub.add_parser("document-review")
    review.add_argument("proposal")
    review.add_argument("entity")
    review.add_argument("document_hash")
    review.add_argument("reference")
    composite_key = sub.add_parser("composite-key")
    composite_key.add_argument("component")
    composite_key.add_argument("local_key_hex")
    state_key = sub.add_parser("state-key")
    state_key.add_argument("kind", choices=("approval", "balance", "document", "release"))
    state_key.add_argument("value")
    authmap = sub.add_parser("authmap")
    authmap.add_argument("operation", choices=("put", "query", "state-key"))
    authmap.add_argument("collection")
    authmap.add_argument("key")
    authmap.add_argument("value_hex", nargs="?", default="")
    authmap_receipt = sub.add_parser("authmap-receipt")
    authmap_receipt.add_argument("message_id")
    authmap_value = sub.add_parser("authmap-value")
    authmap_value.add_argument("kind", choices=("opaque", "event", "product", "gtin"))
    authmap_value.add_argument("values", nargs="+")
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
    elif args.command == "blake2b-hex":
        if re.fullmatch(r"(?:[0-9a-f]{2})+", args.value) is None:
            raise ValueError("blake2b-hex requires canonical non-empty hex")
        print(hashlib.blake2b(bytes.fromhex(args.value), digest_size=32).hexdigest())
    elif args.command == "document-review":
        if re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", args.proposal) is None \
                or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", args.entity) is None \
                or re.fullmatch(r"[0-9a-f]{64}", args.document_hash) is None:
            raise ValueError("invalid document-review fields")
        emit(array(uint(1), text(args.proposal), text("document-release"), uint(1),
                   text(args.entity), bstr(bytes.fromhex(args.document_hash)),
                   text(args.reference)))
    elif args.command == "composite-key":
        if re.fullmatch(r"[a-z][a-z0-9-]{0,62}", args.component) is None \
                or re.fullmatch(r"(?:[0-9a-f]{2})+", args.local_key_hex) is None:
            raise ValueError("invalid composite key fields")
        domain = b"yano-composite-state-v1\0"
        component = args.component.encode("ascii")
        local = bytes.fromhex(args.local_key_hex)
        if len(domain) + 1 + len(component) + 2 + len(local) > 256:
            raise ValueError("composite key exceeds 256 bytes")
        emit(domain + bytes([len(component)]) + component
             + len(local).to_bytes(2, "big") + local)
    elif args.command == "state-key":
        prefixes = {"approval": "i/", "balance": "b/", "document": "e/", "release": "r/"}
        print((prefixes[args.kind] + args.value).encode().hex())
    elif args.command == "authmap":
        collection = args.collection.encode("ascii")
        key = args.key.encode("utf-8")
        if not collection or len(collection) > 64 or not key or len(key) > 128:
            raise ValueError("authenticated-map collection/key is outside showcase bounds")
        if args.operation == "put":
            if not args.value_hex or re.fullmatch(r"(?:[0-9a-f]{2})*", args.value_hex) is None:
                raise ValueError("authenticated-map PUT requires canonical lowercase value hex")
            value = bytes.fromhex(args.value_hex)
            mutation = array(uint(0), text(args.collection), bstr(key), bstr(value),
                             uint(0), bstr(b""), bstr(b""))
            emit(array(uint(1), uint(0), array(mutation)))
        elif args.operation == "query":
            if args.value_hex:
                raise ValueError("authenticated-map query does not accept a value")
            emit(array(uint(1), uint(0), uint(0), text(args.collection), bstr(key)))
        else:
            if args.value_hex:
                raise ValueError("authenticated-map state-key does not accept a value")
            emit(b"\x01\x01" + len(collection).to_bytes(2, "big") + collection
                 + len(key).to_bytes(4, "big") + key)
    elif args.command == "authmap-receipt":
        if re.fullmatch(r"[0-9a-f]{64}", args.message_id) is None:
            raise ValueError("authenticated-map receipt requires a 32-byte message id")
        emit(array(uint(1), bstr(bytes.fromhex(args.message_id))))
    elif args.command == "authmap-value":
        if args.kind == "opaque":
            if len(args.values) != 1:
                raise ValueError("opaque value requires one text argument")
            emit(args.values[0].encode("utf-8"))
        elif args.kind == "event":
            if len(args.values) != 2 or not args.values[1].isdigit():
                raise ValueError("event value requires <event-name> <nonnegative-sequence>")
            emit(array(text(args.values[0]), uint(int(args.values[1]))))
        elif args.kind == "product":
            if len(args.values) not in (3, 4) or not args.values[1].isdigit():
                raise ValueError("product value requires <sku> <quantity> <status> [note]")
            entries = [
                ("sku", text(args.values[0])),
                ("quantity", uint(int(args.values[1]))),
                ("status", text(args.values[2])),
            ]
            if len(args.values) == 4:
                entries.append(("note", text(args.values[3])))
            emit(cbor_map(entries))
        else:
            if len(args.values) != 1:
                raise ValueError("GTIN value requires one identifier")
            emit(text(args.values[0]))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, json.JSONDecodeError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        raise SystemExit(2)
