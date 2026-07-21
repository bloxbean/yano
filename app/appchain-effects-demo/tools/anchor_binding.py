#!/usr/bin/env python3
"""Validate and atomically reconcile the demo's private script-anchor binding."""

from __future__ import annotations

import argparse
import errno
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time
from typing import Any


SCHEMA_VERSION = 2
KIND = "yano.demo.anchor-binding"
STATE_PENDING = "pending-genesis"
STATE_ADOPTED = "member-adopted"
MAX_BYTES = 65_536
MAX_STATUS_BYTES = 1_048_576
MAX_I64 = (1 << 63) - 1
HEX_28 = re.compile(r"[0-9a-f]{56}\Z")
MEMBER_KEY = re.compile(r"[0-9a-f]{64}\Z")
STATE_ROOT = re.compile(r"[0-9a-f]{64}\Z")
TEST_ADDRESS = re.compile(r"addr_test1[a-z0-9]{20,240}\Z")
NETWORK = re.compile(r"[a-z][a-z0-9-]{0,31}\Z")
INSTANCE = re.compile(r"[a-z0-9][a-z0-9-]{0,31}\Z")
CHAIN_ID = re.compile(r"[a-z][a-z0-9-]{0,62}\Z")
STATE_MACHINES = frozenset(("evidence-registry", "composite"))
FIELDS = {
    "schemaVersion", "kind", "networkName", "instanceId", "deployment",
    "chainId", "threadPolicyId", "scriptHash", "scriptAddress",
    "verificationState", "verifiedHeight", "verifiedMembers", "verifiedAtMillis",
}
PRISTINE_COUNTERS = (
    "tipHeight", "poolSize", "submitted", "received", "relayed", "duplicates",
    "seenIds", "storedMessages",
)


class BindingError(ValueError):
    """A fail-closed binding or live-status validation error."""


class NotConverged(RuntimeError):
    """A safe, transient live state that may converge on a later poll."""


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BindingError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_constant(value: str) -> None:
    raise BindingError(f"unsupported JSON numeric constant: {value}")


def canonical_json(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, ensure_ascii=False, allow_nan=False,
                       sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def canonical_path(path: Path, *, must_exist: bool) -> Path:
    if not path.is_absolute() or path != path.absolute() or path != path.parent / path.name:
        raise BindingError(f"path must be absolute and normalized: {path}")
    try:
        resolved = path.resolve(strict=must_exist)
    except OSError as error:
        raise BindingError(f"path cannot be resolved: {path}") from error
    if resolved != path:
        raise BindingError(f"path must not traverse symbolic links: {path}")
    return path


def read_bounded(path: Path, maximum: int, description: str,
                 *, private: bool) -> bytes:
    canonical_path(path, must_exist=True)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        if error.errno == errno.ELOOP:
            raise BindingError(f"{description} must not be a symlink") from error
        raise BindingError(f"cannot open {description}: {path}") from error
    try:
        before = os.fstat(descriptor)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or before.st_nlink != 1 or not 1 <= before.st_size <= maximum):
            raise BindingError(f"{description} is not a bounded owner file")
        if private and stat.S_IMODE(before.st_mode) not in (0o400, 0o600):
            raise BindingError(f"{description} must have mode 0400 or 0600")
        content = b""
        while len(content) <= maximum:
            chunk = os.read(descriptor, maximum + 1 - len(content))
            if not chunk:
                break
            content += chunk
        after = os.fstat(descriptor)
        if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
                or len(content) != before.st_size):
            raise BindingError(f"{description} changed while being read")
        return content
    finally:
        os.close(descriptor)


def parse_json(content: bytes, description: str) -> Any:
    try:
        return json.loads(content.decode("utf-8"), object_pairs_hook=unique_object,
                          parse_constant=reject_constant)
    except BindingError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
        raise BindingError(f"{description} is not valid UTF-8 JSON") from error


def bounded_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= MAX_I64


def validate_expected(expected: argparse.Namespace) -> None:
    if not NETWORK.fullmatch(expected.network):
        raise BindingError("network name is malformed")
    if not INSTANCE.fullmatch(expected.instance):
        raise BindingError("instance id is malformed")
    if expected.deployment not in ("compose", "host"):
        raise BindingError("deployment must be compose or host")
    if not CHAIN_ID.fullmatch(expected.chain_id):
        raise BindingError("chain id is malformed")
    if expected.state_machine not in STATE_MACHINES:
        raise BindingError("state machine is not an accepted demo provider")


def expected_member_keys(raw: str) -> list[str]:
    members = raw.split(",")
    if (len(members) != 3 or len(set(members)) != 3
            or any(not MEMBER_KEY.fullmatch(member) for member in members)):
        raise BindingError("expected member identity must contain three unique canonical keys")
    return members


def validate_binding(document: Any, expected: argparse.Namespace,
                     raw: bytes | None = None) -> dict[str, Any]:
    if not isinstance(document, dict) or set(document) != FIELDS:
        raise BindingError("anchor binding has an unexpected schema")
    fixed = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": KIND,
        "networkName": expected.network,
        "instanceId": expected.instance,
        "deployment": expected.deployment,
        "chainId": expected.chain_id,
    }
    if any(document.get(key) != value for key, value in fixed.items()):
        raise BindingError("anchor binding does not match the immutable demo identity")
    if not HEX_28.fullmatch(document.get("threadPolicyId", "")):
        raise BindingError("anchor binding thread policy id is malformed")
    if not HEX_28.fullmatch(document.get("scriptHash", "")):
        raise BindingError("anchor binding script hash is malformed")
    if not TEST_ADDRESS.fullmatch(document.get("scriptAddress", "")):
        raise BindingError("anchor binding script address is malformed")
    state = document.get("verificationState")
    height = document.get("verifiedHeight")
    members = document.get("verifiedMembers")
    verified_at = document.get("verifiedAtMillis")
    if not bounded_int(height) or not bounded_int(verified_at):
        raise BindingError("anchor binding counters are malformed")
    if state == STATE_PENDING:
        if height != 0 or members != 1:
            raise BindingError("pending anchor binding must be leader-only at height 0")
    elif state == STATE_ADOPTED:
        if height < 1 or members != 3:
            raise BindingError("adopted anchor binding must cover three members above height 0")
    else:
        raise BindingError("anchor binding verification state is malformed")
    if raw is not None and raw != canonical_json(document):
        raise BindingError("anchor binding JSON is not canonical")
    return document


def read_binding(path: Path, expected: argparse.Namespace) -> dict[str, Any] | None:
    canonical_path(path, must_exist=False)
    if not path.exists():
        return None
    raw = read_bounded(path, MAX_BYTES, "anchor binding", private=True)
    return validate_binding(parse_json(raw, "anchor binding"), expected, raw)


def read_status(path: Path, expected_chain_id: str) -> dict[str, Any]:
    raw = read_bounded(path, MAX_STATUS_BYTES, "member status", private=False)
    document = parse_json(raw, "member status")
    if not isinstance(document, dict) or document.get("chainId") != expected_chain_id:
        raise BindingError("member status chain id does not match")
    return document


def anchor_identity(anchor: Any) -> dict[str, str]:
    if not isinstance(anchor, dict) or anchor.get("bootstrapped") is not True:
        raise NotConverged("script-anchor identity has not been adopted")
    identity = {
        "threadPolicyId": anchor.get("threadPolicyId"),
        "scriptHash": anchor.get("scriptHash"),
        "scriptAddress": anchor.get("scriptAddress"),
    }
    if (not HEX_28.fullmatch(identity["threadPolicyId"] or "")
            or not HEX_28.fullmatch(identity["scriptHash"] or "")
            or not TEST_ADDRESS.fullmatch(identity["scriptAddress"] or "")):
        raise BindingError("live script-anchor identity is malformed")
    if (anchor.get("enabled") is not True or anchor.get("mode") != "script"
            or anchor.get("identityCandidatePending") is not False):
        raise BindingError("live script-anchor identity is not authoritative")
    if anchor.get("address") != identity["scriptAddress"]:
        raise BindingError("live script-anchor address does not match its script identity")
    return identity


def pristine_statuses(documents: list[dict[str, Any]]) -> bool:
    for document in documents:
        for key in PRISTINE_COUNTERS:
            counter = document.get(key)
            if not bounded_int(counter):
                raise BindingError(f"member status counter is malformed: {key}")
            if counter != 0:
                return False
        if document.get("stateRoot") != "0" * 64:
            return False
    return True


def validate_topology(documents: list[dict[str, Any]],
                      expected_members: list[str], expected_state_machine: str) -> None:
    expected_roles = ("proposer", "member", "member")
    observed: list[str] = []
    for index, document in enumerate(documents):
        member = document.get("memberKey")
        members = document.get("members")
        threshold = document.get("threshold")
        tip = document.get("tipHeight")
        if member != expected_members[index] or not MEMBER_KEY.fullmatch(member or ""):
            raise BindingError(f"node {index} does not report its immutable member key")
        if member in observed:
            raise BindingError("member statuses contain a duplicate member key")
        observed.append(member)
        if (type(members) is not int or members != 3
                or type(threshold) is not int or threshold != 2):
            raise BindingError("member status does not report the fixed 3-of-2 topology")
        if document.get("stateMachine") != expected_state_machine:
            raise BindingError("member status reports an unexpected state machine")
        if (document.get("running") is not True
                or document.get("sequencing") is not True
                or document.get("role") != expected_roles[index]):
            raise BindingError(f"node {index} reports an unexpected runtime role")
        if not bounded_int(tip):
            raise BindingError("member app-chain tip is malformed")
        if not STATE_ROOT.fullmatch(document.get("stateRoot") or ""):
            raise BindingError("member state root is malformed")


def live_candidate(documents: list[dict[str, Any]], allow_pending: bool,
                   expected_members: list[str], expected_state_machine: str) -> dict[str, Any]:
    validate_topology(documents, expected_members, expected_state_machine)
    anchors = [document.get("anchor") for document in documents]
    expected_leaders = (True, False, False)
    for index, anchor in enumerate(anchors):
        if anchor is None and index > 0:
            continue
        if not isinstance(anchor, dict):
            raise BindingError(f"node {index} script-anchor status is malformed")
        if anchor.get("leader") is not expected_leaders[index]:
            raise BindingError(f"node {index} reports an unexpected script-anchor role")
        if not bounded_int(anchor.get("lastAnchoredHeight")):
            raise BindingError(f"node {index} script-anchor height is malformed")
    leader_identity = anchor_identity(anchors[0])

    adopted = all(isinstance(anchor, dict) and anchor.get("bootstrapped") is True
                  for anchor in anchors)
    if adopted:
        identities = [anchor_identity(anchor) for anchor in anchors]
        if any(identity != leader_identity for identity in identities[1:]):
            raise BindingError("members report different script-anchor identities")
        heights = [anchor.get("lastAnchoredHeight") for anchor in anchors]
        if (any(not bounded_int(height) for height in heights)
                or len(set(heights)) != 1 or heights[0] < 1):
            raise NotConverged("members have not adopted one non-genesis anchor height")
        tips = [document.get("tipHeight") for document in documents]
        if any(not bounded_int(tip) or tip < heights[0] for tip in tips):
            raise NotConverged("member app-chain tips do not cover the adopted anchor")
        return {
            **leader_identity,
            "verificationState": STATE_ADOPTED,
            "verifiedHeight": heights[0],
            "verifiedMembers": 3,
        }

    followers_unadopted = anchors[1] is None and anchors[2] is None
    leader_height = anchors[0].get("lastAnchoredHeight")
    if (allow_pending and followers_unadopted and leader_height == 0
            and pristine_statuses(documents)):
        return {
            **leader_identity,
            "verificationState": STATE_PENDING,
            "verifiedHeight": 0,
            "verifiedMembers": 1,
        }
    raise NotConverged(
        "followers are not fully adopted and the cluster is not a pristine pending genesis")


def private_parent(path: Path) -> Path:
    parent = canonical_path(path.parent, must_exist=True)
    info = os.lstat(parent)
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) != 0o700):
        raise BindingError("anchor binding directory must be launcher-owned mode 0700")
    return parent


def write_binding(path: Path, document: dict[str, Any]) -> None:
    parent = private_parent(path)
    content = canonical_json(document)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".anchor-binding.", dir=parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        view = memoryview(content)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise OSError("short write")
            view = view[written:]
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = -1
        os.replace(temporary, path)
        directory = os.open(parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def reconcile(args: argparse.Namespace) -> None:
    validate_expected(args)
    if len(args.status) != 3:
        raise BindingError("exactly three member status files are required")
    members = expected_member_keys(args.member_keys)
    documents = [read_status(path, args.chain_id) for path in args.status]
    candidate = live_candidate(
        documents, args.allow_pristine_pending, members, args.state_machine)
    existing = read_binding(args.binding, args)
    if existing is not None:
        for key in ("threadPolicyId", "scriptHash", "scriptAddress"):
            if existing[key] != candidate[key]:
                raise BindingError(f"persisted anchor identity changed at {key}")
        if (existing["verificationState"] == STATE_ADOPTED
                and candidate["verificationState"] != STATE_ADOPTED):
            raise BindingError("an adopted anchor binding cannot be downgraded to pending")
        if (existing["verificationState"] == STATE_ADOPTED
                and candidate["verifiedHeight"] < existing["verifiedHeight"]):
            raise BindingError("adopted anchor height cannot move backwards")
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": KIND,
        "networkName": args.network,
        "instanceId": args.instance,
        "deployment": args.deployment,
        "chainId": args.chain_id,
        **candidate,
        "verifiedAtMillis": int(time.time() * 1000),
    }
    validate_binding(document, args)
    write_binding(args.binding, document)
    print(document["verificationState"])


def validate(args: argparse.Namespace) -> None:
    validate_expected(args)
    if read_binding(args.binding, args) is None:
        raise BindingError("anchor binding does not exist")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    for name in ("validate", "reconcile"):
        command = commands.add_parser(name)
        command.add_argument("--binding", type=Path, required=True)
        command.add_argument("--network", required=True)
        command.add_argument("--instance", required=True)
        command.add_argument("--deployment", required=True)
        command.add_argument("--chain-id", required=True)
        command.add_argument("--state-machine", required=True)
        if name == "reconcile":
            command.add_argument("--status", action="append", type=Path, required=True)
            command.add_argument("--member-keys", required=True)
            command.add_argument("--allow-pristine-pending", action="store_true")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "validate":
            validate(args)
        else:
            reconcile(args)
        return 0
    except NotConverged as error:
        print(f"PENDING: {error}", file=sys.stderr)
        return 3
    except (BindingError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
