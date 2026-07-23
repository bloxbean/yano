#!/usr/bin/env python3
"""Derive and verify private node overlays for the ADR-013 failover E2E."""

from __future__ import annotations

import argparse
import ipaddress
import os
from pathlib import Path
import re
import stat
import sys


MAX_CONFIG_BYTES = 1_048_576
CHAIN_BASE = "yano.app-chain.chains[0]."
BASE = CHAIN_BASE + "effects."
EXECUTOR_PREFIX = BASE + "executor."
CONNECTORS_PREFIX = BASE + "executors."
ENABLED = EXECUTOR_PREFIX + "enabled"
IDENTITY = EXECUTOR_PREFIX + "identity"
TYPES = EXECUTOR_PREFIX + "types"
MEMBERS = CHAIN_BASE + "members"
RESULT_SIGNERS = BASE + "result.signers"
EXACT_TYPES = "object.put,ipfs.pin,kafka.publish"
IDENTITY_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
MEMBER_PATTERN = re.compile(r"[0-9a-f]{64}")


class FailoverError(ValueError):
    pass


def validate_private_directory(path: Path) -> Path:
    requested = path.absolute()
    if path != requested or path != path.resolve(strict=True) or path.is_symlink():
        raise FailoverError("output directory must be an absolute canonical path")
    info = os.lstat(path)
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) != 0o700):
        raise FailoverError("output directory must be launcher-owned mode 0700")
    return path


def read_private_config(path: Path) -> tuple[list[str], dict[str, str]]:
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise FailoverError(f"config path cannot be resolved: {path}") from error
    if (not path.is_absolute() or path != path.absolute()
            or path != resolved or path.is_symlink()):
        raise FailoverError(f"config path must be absolute and non-symlink: {path}")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        before = os.fstat(descriptor)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or before.st_nlink != 1 or stat.S_IMODE(before.st_mode) != 0o600
                or not 1 <= before.st_size <= MAX_CONFIG_BYTES):
            raise FailoverError(f"config must be an owner-only regular file: {path}")
        raw = b""
        while len(raw) <= MAX_CONFIG_BYTES:
            chunk = os.read(descriptor, MAX_CONFIG_BYTES + 1 - len(raw))
            if not chunk:
                break
            raw += chunk
        after = os.fstat(descriptor)
        if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
                or len(raw) != before.st_size):
            raise FailoverError(f"config changed while being read: {path}")
    finally:
        os.close(descriptor)
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise FailoverError(f"config is not UTF-8: {path}") from error
    if "\r" in text or "\0" in text or (text and not text.endswith("\n")):
        raise FailoverError(f"config has a non-canonical text shape: {path}")
    lines = text.splitlines()
    values: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            continue
        if line.endswith("\\") or "=" not in line:
            raise FailoverError(f"unsupported property syntax in {path}")
        key, value = line.split("=", 1)
        if key != key.strip() or not key or key in values:
            raise FailoverError(f"duplicate or non-canonical property in {path}: {key}")
        values[key] = value
    return lines, values


def executor_lines(lines: list[str]) -> list[str]:
    selected = []
    for line in lines:
        if "=" not in line or line.lstrip().startswith(("#", "!")):
            continue
        key = line.split("=", 1)[0]
        if key.startswith(EXECUTOR_PREFIX) or key.startswith(CONNECTORS_PREFIX):
            selected.append(line)
    return selected


def result_signer_profile(values: dict[str, str]) -> tuple[str, str]:
    members = values.get(MEMBERS, "").split(",")
    signers = values.get(RESULT_SIGNERS, "").split(",")
    if (len(members) != 3 or len(set(members)) != 3
            or any(not MEMBER_PATTERN.fullmatch(member) for member in members)
            or signers != members[:2]):
        raise FailoverError(
            "result signer policy must pre-authorize exactly the primary and failover members")
    return signers[0], signers[1]


def validate_source(node0: dict[str, str], node1: dict[str, str]) -> None:
    if result_signer_profile(node0) != result_signer_profile(node1):
        raise FailoverError("source nodes disagree on the immutable result signer policy")
    if node0.get(ENABLED) != "true":
        raise FailoverError("source node 0 is not the enabled executor")
    if node0.get(IDENTITY) != "evidence-executor-0":
        raise FailoverError("source node 0 has an unexpected executor identity")
    if node0.get(TYPES) != EXACT_TYPES:
        raise FailoverError("source node 0 does not own the exact connector partition")
    if node1.get(ENABLED) != "false":
        raise FailoverError("source node 1 is not explicitly executor-disabled")
    if any(key.startswith(CONNECTORS_PREFIX) for key in node1):
        raise FailoverError("source node 1 unexpectedly contains connector credentials")
    if any(key.startswith(EXECUTOR_PREFIX) and key != ENABLED for key in node1):
        raise FailoverError("source node 1 unexpectedly contains executor settings")
    for scheme in ("kafka", "objectstore-s3", "ipfs"):
        key = CONNECTORS_PREFIX + scheme + ".enabled"
        if node0.get(key) != "true":
            raise FailoverError(f"source node 0 does not enable {scheme}")


def filter_executor_settings(lines: list[str]) -> list[str]:
    retained = []
    for line in lines:
        if "=" in line and not line.lstrip().startswith(("#", "!")):
            key = line.split("=", 1)[0]
            if key.startswith(EXECUTOR_PREFIX) or key.startswith(CONNECTORS_PREFIX):
                continue
        retained.append(line)
    while retained and not retained[-1]:
        retained.pop()
    return retained


def replace_identity(lines: list[str], identity: str) -> list[str]:
    result = []
    seen = False
    for line in lines:
        if line.startswith(IDENTITY + "="):
            if seen:
                raise FailoverError("source executor identity is duplicated")
            result.append(f"{IDENTITY}={identity}")
            seen = True
        else:
            result.append(line)
    if not seen:
        raise FailoverError("source executor identity is missing")
    return result


def write_private(path: Path, lines: list[str]) -> None:
    content = ("\n".join(lines) + "\n").encode("utf-8")
    flags = (os.O_WRONLY | os.O_CREAT | os.O_EXCL
             | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0))
    descriptor = os.open(path, flags, 0o600)
    try:
        written = 0
        while written < len(content):
            written += os.write(descriptor, content[written:])
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def verify_derived(fenced: dict[str, str], replacement: dict[str, str], identity: str) -> None:
    if result_signer_profile(fenced) != result_signer_profile(replacement):
        raise FailoverError("derived nodes disagree on the immutable result signer policy")
    if fenced.get(ENABLED) != "false":
        raise FailoverError("fenced node is not executor-disabled")
    if any(key.startswith(CONNECTORS_PREFIX) for key in fenced):
        raise FailoverError("fenced node retained connector credentials")
    if any(key.startswith(EXECUTOR_PREFIX) and key != ENABLED for key in fenced):
        raise FailoverError("fenced node retained executor ownership settings")
    if replacement.get(ENABLED) != "true":
        raise FailoverError("replacement node is not executor-enabled")
    if replacement.get(IDENTITY) != identity:
        raise FailoverError("replacement executor identity does not match")
    if replacement.get(TYPES) != EXACT_TYPES:
        raise FailoverError("replacement does not own the exact connector partition")
    for scheme in ("kafka", "objectstore-s3", "ipfs"):
        if replacement.get(CONNECTORS_PREFIX + scheme + ".enabled") != "true":
            raise FailoverError(f"replacement does not enable {scheme}")


def derive(args: argparse.Namespace) -> None:
    output = validate_private_directory(args.output_directory)
    node0_lines, node0 = read_private_config(args.node0)
    node1_lines, node1 = read_private_config(args.node1)
    validate_source(node0, node1)

    executor = replace_identity(executor_lines(node0_lines), args.identity)
    fenced_lines = filter_executor_settings(node0_lines) + ["", f"{ENABLED}=false"]
    replacement_lines = filter_executor_settings(node1_lines) + [""] + executor
    fenced_path = output / "node0-fenced.properties"
    replacement_path = output / "node1-replacement.properties"
    write_private(fenced_path, fenced_lines)
    write_private(replacement_path, replacement_lines)
    _, fenced = read_private_config(fenced_path)
    _, replacement = read_private_config(replacement_path)
    verify_derived(fenced, replacement, args.identity)
    print(f"FENCED_CONFIG={fenced_path}")
    print(f"REPLACEMENT_CONFIG={replacement_path}")


def verify(args: argparse.Namespace) -> None:
    _, fenced = read_private_config(args.fenced)
    _, replacement = read_private_config(args.replacement)
    verify_derived(fenced, replacement, args.identity)
    print("FAILOVER_CONFIG_OK")


def subnet_check(args: argparse.Namespace) -> None:
    try:
        candidate = ipaddress.ip_network(args.candidate, strict=True)
    except ValueError as error:
        raise FailoverError("candidate subnet is malformed or non-canonical") from error
    for value in args.existing:
        try:
            existing = ipaddress.ip_network(value, strict=False)
        except ValueError as error:
            raise FailoverError("existing subnet inventory is malformed") from error
        if candidate.version == existing.version and candidate.overlaps(existing):
            raise FailoverError(
                f"candidate subnet {candidate} overlaps existing subnet {existing}")
    print("SUBNET_AVAILABLE")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    derive_parser = commands.add_parser("derive")
    derive_parser.add_argument("--node0", required=True, type=Path)
    derive_parser.add_argument("--node1", required=True, type=Path)
    derive_parser.add_argument("--output-directory", required=True, type=Path)
    derive_parser.add_argument("--identity", default="evidence-executor-1")
    derive_parser.set_defaults(handler=derive)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--fenced", required=True, type=Path)
    verify_parser.add_argument("--replacement", required=True, type=Path)
    verify_parser.add_argument("--identity", default="evidence-executor-1")
    verify_parser.set_defaults(handler=verify)
    subnet_parser = commands.add_parser("subnet-check")
    subnet_parser.add_argument("--candidate", required=True)
    subnet_parser.add_argument("--existing", action="append", default=[])
    subnet_parser.set_defaults(handler=subnet_check)
    return root


def main() -> None:
    args = parser().parse_args()
    if hasattr(args, "identity") and not IDENTITY_PATTERN.fullmatch(args.identity):
        raise FailoverError("executor identity is malformed")
    args.handler(args)


if __name__ == "__main__":
    try:
        main()
    except (FailoverError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
