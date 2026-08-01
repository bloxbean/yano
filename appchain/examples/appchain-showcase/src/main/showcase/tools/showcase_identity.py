#!/usr/bin/env python3
"""Create, validate, and render the immutable showcase deployment marker."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import stat
import sys


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def document(args: argparse.Namespace) -> dict:
    config = pathlib.Path(args.config).resolve()
    plugin = pathlib.Path(args.plugin).resolve()
    return {
        "schemaVersion": 1,
        "kind": "yano.showcase.deployment",
        "showcaseVersion": args.version,
        "profile": args.profile,
        "variant": args.variant,
        "network": args.network,
        "protocolMagic": 1 if args.network == "preprod" else 42,
        "bootstrapNodeCount": args.nodes,
        "bootstrapThreshold": args.threshold,
        "httpBase": args.http_base,
        "serverBase": args.server_base,
        "runtime": "jvm",
        "chainIds": [
            "orders-chain", "registry-chain", "approvals-chain", "balances-chain",
            "documents-chain", "workflow-chain", "roles-chain", "payments-chain"
        ] if args.profile == "light" else [],
        "configSha256": sha256(config),
        "pluginSha256": sha256(plugin),
        "anchor": {
            "enabled": args.anchor,
            "mode": args.anchor_mode if args.anchor else "none",
            "chainId": "workflow-chain" if args.anchor else None,
            "keyReference": str(pathlib.Path(args.anchor_key_file).resolve())
                if args.anchor_key_file else None,
        },
    }


def canonical(value: dict) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def secure_existing(path: pathlib.Path) -> bytes:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1:
        raise ValueError("deployment marker is not a launcher-owned regular file")
    if stat.S_IMODE(info.st_mode) not in (0o400, 0o600) or info.st_size > 65536:
        raise ValueError("deployment marker has unsafe permissions or size")
    return path.read_bytes()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("ensure", "show", "export"))
    parser.add_argument("--marker", required=True)
    parser.add_argument("--version", default="development")
    parser.add_argument("--profile", default="light")
    parser.add_argument("--variant", default="default")
    parser.add_argument("--network", default="devnet")
    parser.add_argument("--nodes", type=int, default=3)
    parser.add_argument("--threshold", type=int, default=2)
    parser.add_argument("--http-base", type=int, default=7070)
    parser.add_argument("--server-base", type=int, default=13337)
    parser.add_argument("--config")
    parser.add_argument("--plugin")
    parser.add_argument("--anchor", action="store_true")
    parser.add_argument("--anchor-mode", default="script")
    parser.add_argument("--anchor-key-file", default="")
    parser.add_argument("--output")
    args = parser.parse_args()
    marker = pathlib.Path(args.marker)
    if args.action == "show":
        parsed = json.loads(secure_existing(marker))
        if parsed.get("anchor", {}).get("keyReference"):
            parsed["anchor"]["keyReference"] = "<redacted-owner-file>"
        print(json.dumps(parsed, indent=2, sort_keys=True))
        return
    if args.action == "export":
        parsed = json.loads(secure_existing(marker))
        if parsed.get("anchor", {}).get("keyReference"):
            parsed["anchor"]["keyReference"] = "<redacted-owner-file>"
        encoded = json.dumps(parsed, indent=2, sort_keys=True) + "\n"
        if args.output:
            pathlib.Path(args.output).write_text(encoded, encoding="utf-8")
        else:
            print(encoded, end="")
        return
    if not args.config or not args.plugin:
        raise ValueError("ensure requires --config and --plugin")
    expected = canonical(document(args))
    marker.parent.mkdir(parents=True, exist_ok=True)
    if marker.exists():
        actual = secure_existing(marker)
        if actual != expected:
            raise ValueError("requested configuration differs from retained showcase identity; "
                             "use a new --instance or reset the existing instance")
        return
    temporary = marker.with_name(marker.name + f".tmp.{os.getpid()}")
    descriptor = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        os.write(descriptor, expected)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    os.replace(temporary, marker)
    os.chmod(marker, 0o600)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        raise SystemExit(2)
