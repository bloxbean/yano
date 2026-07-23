#!/usr/bin/env python3
"""Dependency-free ADR-015 composite-profile governance operator CLI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import urllib.error
import urllib.request

MAX_PROFILE_BYTES = 65_536
MAX_CHUNK_BYTES = 16_384
PROFILE_DIGEST_DOMAIN = b"yano-composite-profile-v1"
PROPOSAL_DOMAIN = b"yano-composite-profile-proposal-v1\0"


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Never forward a privileged API key to a redirected origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(
            req.full_url, code, "redirect refused", headers, fp)


NO_REDIRECT_OPENER = urllib.request.build_opener(NoRedirectHandler())


def uint(value: int) -> bytes:
    if value < 0:
        raise ValueError("unsigned value must not be negative")
    if value < 24:
        return bytes([value])
    if value <= 0xFF:
        return b"\x18" + bytes([value])
    if value <= 0xFFFF:
        return b"\x19" + struct.pack(">H", value)
    if value <= 0xFFFFFFFF:
        return b"\x1a" + struct.pack(">I", value)
    if value <= 0x7FFFFFFFFFFFFFFF:
        return b"\x1b" + struct.pack(">Q", value)
    raise ValueError("unsigned value exceeds the v1 Java long range")


def byte_string(value: bytes) -> bytes:
    encoded_length = uint(len(value))
    return bytes([encoded_length[0] | 0x40]) + encoded_length[1:] + value


def array(*items: bytes) -> bytes:
    encoded_length = uint(len(items))
    return bytes([encoded_length[0] | 0x80]) + encoded_length[1:] + b"".join(items)


def digest(value: str, field: str) -> bytes:
    try:
        decoded = bytes.fromhex(value)
    except ValueError as error:
        raise ValueError(f"{field} must be hexadecimal") from error
    if len(decoded) != 32:
        raise ValueError(f"{field} must contain exactly 32 bytes")
    return decoded


def profile_bytes(path: str) -> bytes:
    value = Path(path).read_bytes()
    if not 1 <= len(value) <= MAX_PROFILE_BYTES:
        raise ValueError("canonical profile must contain 1-65536 bytes")
    return value


def profile_digest(profile: bytes) -> bytes:
    return hashlib.sha256(PROFILE_DIGEST_DOMAIN + profile).digest()


def begin_values(args: argparse.Namespace) -> tuple[bytes, bytes, int, int]:
    if args.profile:
        profile = profile_bytes(args.profile)
        target = profile_digest(profile)
        chunks = (len(profile) + MAX_CHUNK_BYTES - 1) // MAX_CHUNK_BYTES
        return target, profile, len(profile), chunks
    if not args.target_digest or args.total_bytes is None or args.chunk_count is None:
        raise ValueError(
            "begin requires --profile or all of --target-digest, --total-bytes, --chunk-count")
    target = digest(args.target_digest, "target digest")
    if not 1 <= args.total_bytes <= MAX_PROFILE_BYTES:
        raise ValueError("total bytes must be in [1, 65536]")
    if not 1 <= args.chunk_count <= 8:
        raise ValueError("chunk count must be in [1, 8]")
    return target, b"", args.total_bytes, args.chunk_count


def encode_command(args: argparse.Namespace) -> tuple[bytes, dict[str, object]]:
    operation = args.operation
    metadata: dict[str, object] = {"operation": operation}
    if operation == "begin":
        proposal_id = digest(args.proposal_id, "proposal id")
        base = digest(args.base_digest, "base digest")
        membership = digest(args.membership_digest, "membership digest")
        target, _, total, chunks = begin_values(args)
        if args.activation_height < 2 or args.expiry_height < args.activation_height:
            raise ValueError("activation must be >= 2 and expiry must be >= activation")
        body = array(uint(1), uint(1), byte_string(proposal_id), byte_string(base),
                     byte_string(membership), byte_string(target), uint(total), uint(chunks),
                     uint(args.activation_height), uint(args.expiry_height))
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", args.chain):
            raise ValueError("chain id must match [a-z][a-z0-9-]{0,62}")
        chain = args.chain.encode("ascii")
        intent = (PROPOSAL_DOMAIN + bytes([len(chain)]) + chain + proposal_id + base
                  + membership + target + struct.pack(">QQII", args.activation_height,
                                                       args.expiry_height, total, chunks))
        metadata.update(targetProfileDigest=target.hex(), totalBytes=total,
                        chunkCount=chunks, proposalHash=hashlib.sha256(intent).hexdigest())
        return body, metadata
    if operation == "chunk":
        proposal_id = digest(args.proposal_id, "proposal id")
        profile = profile_bytes(args.profile)
        chunks = [profile[index:index + MAX_CHUNK_BYTES]
                  for index in range(0, len(profile), MAX_CHUNK_BYTES)]
        if not 0 <= args.index < len(chunks):
            raise ValueError(f"chunk index must be in [0, {len(chunks) - 1}]")
        metadata.update(index=args.index, chunkCount=len(chunks),
                        targetProfileDigest=profile_digest(profile).hex())
        return array(uint(1), uint(2), byte_string(proposal_id), uint(args.index),
                     byte_string(chunks[args.index])), metadata
    if operation == "seal":
        return array(uint(1), uint(3), byte_string(digest(args.proposal_id, "proposal id"))), metadata
    if operation == "approve":
        return array(uint(1), uint(4), byte_string(digest(args.proposal_hash, "proposal hash"))), metadata
    if operation == "ready":
        return array(uint(1), uint(5), byte_string(digest(args.proposal_hash, "proposal hash")),
                     byte_string(digest(args.target_digest, "target digest"))), metadata
    if operation == "cancel":
        return array(uint(1), uint(6), byte_string(digest(args.proposal_hash, "proposal hash"))), metadata
    raise ValueError(f"unsupported operation: {operation}")


def api_key(args: argparse.Namespace) -> str | None:
    if args.api_key_file:
        value = Path(args.api_key_file).read_text(encoding="utf-8").strip()
    else:
        value = os.environ.get("YANO_APPCHAIN_API_KEY", "").strip()
    return value or None


def call(args: argparse.Namespace, path: str, payload: dict[str, object] | None = None) -> object:
    url = args.url.rstrip("/") + args.api_prefix.rstrip("/") + path
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data,
                                     method="GET" if payload is None else "POST")
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    key = api_key(args)
    if key:
        request.add_header("X-API-Key", key)
    try:
        with NO_REDIRECT_OPENER.open(request, timeout=args.timeout) as response:
            raw = response.read()
            return json.loads(raw) if raw else {"status": response.status}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {detail}") from error


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--url", default="http://127.0.0.1:7070")
    result.add_argument("--api-prefix", default="/api/v1/app-chain")
    result.add_argument("--chain", required=True)
    result.add_argument("--api-key-file")
    result.add_argument("--timeout", type=float, default=10.0)
    commands = result.add_subparsers(dest="operation", required=True)
    commands.add_parser("status")

    def common(command: argparse.ArgumentParser) -> None:
        command.add_argument("--dry-run", action="store_true")
        command.add_argument("--encode-only", action="store_true")

    begin = commands.add_parser("begin")
    common(begin)
    begin.add_argument("--proposal-id", required=True)
    begin.add_argument("--base-digest", required=True)
    begin.add_argument("--membership-digest")
    begin.add_argument("--profile")
    begin.add_argument("--target-digest")
    begin.add_argument("--total-bytes", type=int)
    begin.add_argument("--chunk-count", type=int)
    begin.add_argument("--activation-height", type=int, required=True)
    begin.add_argument("--expiry-height", type=int, required=True)

    chunk = commands.add_parser("chunk")
    common(chunk)
    chunk.add_argument("--proposal-id", required=True)
    chunk.add_argument("--profile", required=True)
    chunk.add_argument("--index", type=int, required=True)
    seal = commands.add_parser("seal")
    common(seal)
    seal.add_argument("--proposal-id", required=True)
    for name in ("approve", "cancel"):
        command = commands.add_parser(name)
        common(command)
        command.add_argument("--proposal-hash", required=True)
    ready = commands.add_parser("ready")
    common(ready)
    ready.add_argument("--proposal-hash", required=True)
    ready.add_argument("--target-digest", required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", args.chain):
            raise ValueError("chain id must match [a-z][a-z0-9-]{0,62}")
        if args.operation == "status":
            output = call(args, f"/chains/{args.chain}/profile-governance")
        else:
            if args.operation == "begin" and not args.membership_digest:
                if args.encode_only:
                    raise ValueError(
                        "offline begin requires --membership-digest; online begin can derive "
                        "the current digest from node status")
                status = call(args, f"/chains/{args.chain}/profile-governance")
                try:
                    args.membership_digest = status["profileGovernance"][
                        "currentMembershipDigest"]
                except (KeyError, TypeError) as error:
                    raise RuntimeError(
                        "node status does not expose currentMembershipDigest") from error
            body, metadata = encode_command(args)
            metadata["bodyHex"] = body.hex()
            if args.encode_only:
                output = metadata
            else:
                response = call(args,
                                f"/chains/{args.chain}/admin/profile-governance/commands",
                                {"bodyHex": body.hex(), "dryRun": args.dry_run})
                output = {**metadata, "response": response}
        print(json.dumps(output, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
