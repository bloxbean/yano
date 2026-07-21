#!/usr/bin/env python3
"""Fail-closed identity and cleanup transactions for the Yano effects demo.

The launcher supplies canonical identity/plan documents and exact paths. This
helper owns the filesystem safety boundary: one network lock, a global durable
claim snapshot, immutable marker/lease publication, and journaled bounded
cleanup. The network bootstrap marker command remains independently callable;
normal deployment startup and reset use ``deployment-acquire`` and
``cleanup-execute`` so their multi-path transitions cannot interleave.
"""

from __future__ import annotations

import argparse
import contextlib
from dataclasses import dataclass
import errno
import fcntl
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Iterator, Optional, Sequence


SCHEMA_VERSION = 1
NETWORK_KIND = "yano.demo.network-identity"
INSTANCE_KIND = "yano.demo.appchain-identity"
LEASE_KIND = "yano.demo.l1-lease"
RETIREMENT_PLAN_KIND = "yano.demo.retirement-plan"
RETIRED_INSTANCE_KIND = "yano.demo.retired-instance"
RESET_RESERVATION_KIND = "yano.demo.reset-reservation"
CLEANUP_TRANSACTION_KIND = "yano.demo.cleanup-transaction"
CLEANUP_PLAN_KIND = "yano.demo.cleanup-plan"
DEVNET_RESET_KIND = "yano.demo.devnet-reset-transaction"
DEVNET_RESET_PENDING_NETWORK_IDENTITY = {
    "schemaVersion": SCHEMA_VERSION,
    "kind": NETWORK_KIND,
    "networkName": "devnet",
    "factoryResetPending": True,
}
DEFAULT_NETWORK_MARKER = "network-identity.json"
DEFAULT_INSTANCE_MARKER = "appchain-identity.json"
DEFAULT_LEASE_MARKER = "demo-owner.json"
LOCK_NAME = ".yano-lifecycle.lock"
MAX_IDENTITY_BYTES = 64 * 1024
MAX_IDENTITY_DEPTH = 16
MAX_IDENTITY_ITEMS = 4096
MARKER_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
INSTANCE_NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,31}\Z")
DEPLOYMENT_NAME = re.compile(r"(?:compose|host)\Z")
NETWORK_NAME = re.compile(r"[a-z][a-z0-9-]{0,31}\Z")
CHAIN_ID = re.compile(r"[a-z][a-z0-9-]{0,62}\Z")
SHA256_HEX = re.compile(r"[0-9a-f]{64}\Z")
ATOMIC_TEMP_SUFFIX = re.compile(r"[1-9][0-9]*\.[0-9a-f]{16}\Z")
CLEANUP_TRANSACTION_ID = re.compile(r"[0-9a-f]{32}\Z")
CLEANUP_SCOPE = re.compile(r"(?:observability|reports|runtime|instance|l1|all)\Z")
CLEANUP_PLAN_FIELDS = {
    "schemaVersion",
    "kind",
    "networkName",
    "instanceId",
    "deployment",
    "chainId",
    "appchainIdentitySha256",
    "scope",
    "dataRoot",
    "l1Root",
    "runtimeRoot",
    "replacementInstanceId",
    "replacementChainId",
}
CENTRAL_CLEANUP_MARKER = ".yano-cleanup-transaction.json"
DEVNET_RESET_MARKER = ".yano-devnet-reset-transaction.json"
OPERATION_LOCK_DIRECTORY = ".yano-operation-locks"


class LifecycleError(Exception):
    """An expected, operator-actionable lifecycle validation failure."""


def _reject_json_constant(value: str) -> None:
    raise LifecycleError(f"identity JSON contains unsupported numeric constant {value!r}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise LifecycleError(f"identity JSON contains duplicate key {key!r}")
        result[key] = value
    return result


def _validate_json_value(
    value: Any,
    depth: int = 0,
    budget: Optional[list[int]] = None,
) -> None:
    if budget is None:
        budget = [MAX_IDENTITY_ITEMS]
    budget[0] -= 1
    if budget[0] < 0:
        raise LifecycleError("identity JSON has too many values")
    if depth > MAX_IDENTITY_DEPTH:
        raise LifecycleError("identity JSON is nested too deeply")

    if value is None or isinstance(value, bool):
        return
    if isinstance(value, str):
        try:
            value.encode("utf-8")
        except UnicodeEncodeError as error:
            raise LifecycleError("identity JSON contains an invalid Unicode scalar") from error
        return
    if isinstance(value, int) and not isinstance(value, bool):
        if abs(value) > (1 << 63) - 1:
            raise LifecycleError("identity JSON integer is outside the signed 64-bit range")
        return
    if isinstance(value, float):
        raise LifecycleError("identity JSON floating-point values are not supported")
    if isinstance(value, list):
        for item in value:
            _validate_json_value(item, depth + 1, budget)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise LifecycleError("identity JSON object keys must be strings")
            try:
                key.encode("utf-8")
            except UnicodeEncodeError as error:
                raise LifecycleError("identity JSON contains an invalid Unicode key") from error
            _validate_json_value(item, depth + 1, budget)
        return
    raise LifecycleError(f"identity JSON contains unsupported value type {type(value).__name__}")


def _read_regular_file(path: Path, description: str, owner_safe: bool = False) -> bytes:
    flags = os.O_RDONLY
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        if error.errno == errno.ELOOP:
            raise LifecycleError(f"{description} must not be a symlink: {path}") from error
        raise LifecycleError(f"cannot open {description} {path}: {error.strerror}") from error

    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode):
            raise LifecycleError(f"{description} is not a regular file: {path}")
        if owner_safe and hasattr(os, "geteuid") and info.st_uid != os.geteuid():
            raise LifecycleError(f"{description} is not owned by the current user: {path}")
        if owner_safe and info.st_nlink != 1:
            raise LifecycleError(f"{description} must not have hard links: {path}")
        if owner_safe and info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
            raise LifecycleError(f"{description} is writable by another user: {path}")
        if info.st_size > MAX_IDENTITY_BYTES:
            raise LifecycleError(
                f"{description} exceeds the {MAX_IDENTITY_BYTES}-byte safety limit: {path}"
            )
        chunks: list[bytes] = []
        remaining = MAX_IDENTITY_BYTES + 1
        while remaining > 0:
            chunk = os.read(descriptor, min(remaining, 16 * 1024))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        content = b"".join(chunks)
        if len(content) > MAX_IDENTITY_BYTES:
            raise LifecycleError(
                f"{description} exceeds the {MAX_IDENTITY_BYTES}-byte safety limit: {path}"
            )
        return content
    finally:
        os.close(descriptor)


def _parse_identity(content: bytes, description: str, expected_kind: str) -> dict[str, Any]:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as error:
        raise LifecycleError(f"{description} is not UTF-8 JSON") from error
    try:
        value = json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_json_constant,
        )
    except LifecycleError:
        raise
    except (json.JSONDecodeError, RecursionError) as error:
        raise LifecycleError(f"{description} is not valid bounded JSON: {error}") from error

    if not isinstance(value, dict):
        raise LifecycleError(f"{description} must be a JSON object")
    _validate_json_value(value)
    schema_version = value.get("schemaVersion")
    if (
        not isinstance(schema_version, int)
        or isinstance(schema_version, bool)
        or schema_version != SCHEMA_VERSION
    ):
        raise LifecycleError(
            f"{description} must declare schemaVersion={SCHEMA_VERSION}"
        )
    if value.get("kind") != expected_kind:
        raise LifecycleError(f"{description} must declare kind={expected_kind!r}")
    return value


def _canonical_json(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _identity_digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _raw_path(value: str, description: str) -> Path:
    if not value or not value.strip():
        raise LifecycleError(f"{description} must not be empty")
    expanded = os.path.expanduser(value)
    if ".." in Path(expanded).parts:
        raise LifecycleError(f"{description} must not contain '..': {value}")
    return Path(os.path.abspath(expanded))


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _repository_roots() -> set[Path]:
    roots: set[Path] = set()
    for starting_point in (Path(__file__).resolve(), Path.cwd().resolve()):
        candidate = starting_point if starting_point.is_dir() else starting_point.parent
        for directory in (candidate, *candidate.parents):
            if (directory / ".git").exists():
                roots.add(directory.resolve())
                break
    return roots


def _forbidden_roots() -> set[Path]:
    roots = {Path("/").resolve(), Path.home().resolve()}
    roots.update(_repository_roots())
    return roots


def _final_component_is_symlink(path: Path, description: str) -> None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        return
    except OSError as error:
        raise LifecycleError(f"cannot inspect {description} {path}: {error.strerror}") from error
    if stat.S_ISLNK(info.st_mode):
        raise LifecycleError(f"{description} must not be a symlink: {path}")


def _prepare_allowed_root(value: str, create: bool) -> tuple[Path, Path]:
    raw = _raw_path(value, "allowed root")
    _final_component_is_symlink(raw, "allowed root")
    if create:
        try:
            raw.mkdir(mode=0o700, parents=True, exist_ok=True)
        except OSError as error:
            raise LifecycleError(f"cannot create allowed root {raw}: {error.strerror}") from error
    if not raw.exists():
        raise LifecycleError(f"allowed root does not exist: {raw}")
    if not raw.is_dir():
        raise LifecycleError(f"allowed root is not a directory: {raw}")

    info = raw.stat()
    if hasattr(os, "geteuid") and info.st_uid != os.geteuid():
        raise LifecycleError(f"allowed root is not owned by the current user: {raw}")
    if stat.S_IMODE(info.st_mode) & (stat.S_IWGRP | stat.S_IWOTH):
        raise LifecycleError(f"allowed root must not be group/world writable: {raw}")

    resolved = raw.resolve(strict=True)
    if resolved in _forbidden_roots():
        raise LifecycleError(f"refusing unsafe allowed root: {resolved}")
    if hasattr(os, "geteuid"):
        trusted_owners = {0, os.geteuid()}
        child = resolved
        while child.parent != child:
            parent = child.parent
            parent_info = parent.stat()
            child_info = child.stat()
            if not stat.S_ISDIR(parent_info.st_mode) or parent_info.st_uid not in trusted_owners:
                raise LifecycleError(f"allowed root has an untrusted ancestor: {parent}")
            parent_mode = stat.S_IMODE(parent_info.st_mode)
            if parent_mode & (stat.S_IWGRP | stat.S_IWOTH):
                if not parent_mode & stat.S_ISVTX or child_info.st_uid not in trusted_owners:
                    raise LifecycleError(f"allowed root has a replaceable ancestor: {parent}")
            child = parent
    return raw, resolved


def _prepare_optional_allowed_root(value: str) -> tuple[Path, Path, bool]:
    raw = _raw_path(value, "optional allowed root")
    _final_component_is_symlink(raw, "optional allowed root")
    if raw.exists():
        prepared_raw, prepared = _prepare_allowed_root(str(raw), create=False)
        return prepared_raw, prepared, True

    resolved = raw.resolve(strict=False)
    if resolved in _forbidden_roots():
        raise LifecycleError(f"refusing unsafe optional allowed root: {resolved}")
    trusted_owners = {0, os.geteuid()} if hasattr(os, "geteuid") else set()
    current = Path(resolved.anchor)
    for part in resolved.parts[1:]:
        candidate = current / part
        try:
            info = candidate.lstat()
        except FileNotFoundError:
            break
        except OSError as error:
            raise LifecycleError(
                f"cannot inspect optional allowed-root ancestor {candidate}: {error.strerror}"
            ) from error
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            raise LifecycleError(f"optional allowed root traverses an unsafe component: {candidate}")
        if trusted_owners and info.st_uid not in trusted_owners:
            raise LifecycleError(f"optional allowed root has an untrusted ancestor: {candidate}")
        mode = stat.S_IMODE(info.st_mode)
        if mode & (stat.S_IWGRP | stat.S_IWOTH) and not mode & stat.S_ISVTX:
            raise LifecycleError(f"optional allowed root has a replaceable ancestor: {candidate}")
        current = candidate
    return raw, resolved, False


def _relative_input_path(target: Path, raw_root: Path, resolved_root: Path) -> tuple[str, ...]:
    for base in (raw_root, resolved_root):
        try:
            return target.relative_to(base).parts
        except ValueError:
            continue
    raise LifecycleError(f"path is not lexically beneath allowed root {resolved_root}: {target}")


def _reject_symlink_components(base: Path, parts: Sequence[str], description: str) -> None:
    current = base
    for part in parts:
        current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError:
            return
        except OSError as error:
            raise LifecycleError(f"cannot inspect {description} {current}: {error.strerror}") from error
        if stat.S_ISLNK(info.st_mode):
            raise LifecycleError(f"{description} traverses a symlink: {current}")


def _validated_target(
    value: str,
    raw_root: Path,
    resolved_root: Path,
    description: str,
) -> Path:
    raw_target = _raw_path(value, description)
    parts = _relative_input_path(raw_target, raw_root, resolved_root)
    if not parts:
        raise LifecycleError(f"{description} must be a proper child of allowed root {resolved_root}")
    _reject_symlink_components(raw_root if _is_relative_to(raw_target, raw_root) else resolved_root, parts, description)

    resolved_target = raw_target.resolve(strict=False)
    if not _is_relative_to(resolved_target, resolved_root) or resolved_target == resolved_root:
        raise LifecycleError(f"{description} escapes allowed root {resolved_root}: {raw_target}")
    if resolved_target in _forbidden_roots():
        raise LifecycleError(f"refusing unsafe {description}: {resolved_target}")
    return resolved_target


def _validated_guards(
    values: Sequence[str],
    raw_root: Path,
    resolved_root: Path,
) -> list[Path]:
    guards = [
        _validated_target(value, raw_root, resolved_root, "absent-state guard")
        for value in values
    ]
    if len(guards) != len(set(guards)):
        raise LifecycleError("duplicate absent-state guard")
    return guards


def _require_guards_absent(guards: Sequence[Path]) -> None:
    for guard in guards:
        if guard.exists() or guard.is_symlink():
            raise LifecycleError(f"guarded state is active or fenced: {guard}")


def _validate_marker_name(name: str) -> str:
    if not MARKER_NAME.fullmatch(name) or name in {".", "..", LOCK_NAME}:
        raise LifecycleError(f"invalid marker filename: {name!r}")
    return name


def _validate_owned_directory(path: Path, description: str) -> None:
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode):
        raise LifecycleError(f"{description} is not a directory: {path}")
    if hasattr(os, "geteuid") and info.st_uid != os.geteuid():
        raise LifecycleError(f"{description} is not owned by the current user: {path}")
    if stat.S_IMODE(info.st_mode) & (stat.S_IWGRP | stat.S_IWOTH):
        raise LifecycleError(f"{description} must not be group/world writable: {path}")


@contextlib.contextmanager
def _exclusive_lock(root: Path) -> Iterator[int]:
    lock_path = root / LOCK_NAME
    flags = os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
    flags |= getattr(os, "O_CLOEXEC", 0)
    descriptor = -1
    created = False
    try:
        descriptor = os.open(lock_path, flags | os.O_CREAT | os.O_EXCL, 0o600)
        created = True
    except FileExistsError:
        try:
            descriptor = os.open(lock_path, flags)
        except OSError as error:
            if error.errno == errno.ELOOP:
                raise LifecycleError(f"lifecycle lock must not be a symlink: {lock_path}") from error
            raise LifecycleError(f"cannot open lifecycle lock {lock_path}: {error.strerror}") from error
    except OSError as error:
        if error.errno == errno.ELOOP:
            raise LifecycleError(f"lifecycle lock must not be a symlink: {lock_path}") from error
        raise LifecycleError(f"cannot open lifecycle lock {lock_path}: {error.strerror}") from error
    try:
        if created:
            os.fchmod(descriptor, 0o600)
            os.fsync(descriptor)
            _fsync_directory(root)
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode):
            raise LifecycleError(f"lifecycle lock is not a regular file: {lock_path}")
        if hasattr(os, "geteuid") and info.st_uid != os.geteuid():
            raise LifecycleError(f"lifecycle lock is not owned by the current user: {lock_path}")
        if info.st_nlink != 1:
            raise LifecycleError(f"lifecycle lock must not have hard links: {lock_path}")
        if stat.S_IMODE(info.st_mode) != 0o600 or info.st_size != 0:
            raise LifecycleError(f"lifecycle lock must be an empty owner-only 0600 file: {lock_path}")
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        current = os.fstat(descriptor)
        path_info = os.stat(lock_path, follow_symlinks=False)
        if (
            not stat.S_ISREG(current.st_mode)
            or current.st_uid != info.st_uid
            or current.st_nlink != 1
            or stat.S_IMODE(current.st_mode) != 0o600
            or current.st_size != 0
            or (current.st_dev, current.st_ino) != (path_info.st_dev, path_info.st_ino)
        ):
            raise LifecycleError(f"lifecycle lock changed during validation: {lock_path}")
        yield descriptor
    except OSError as error:
        raise LifecycleError(f"cannot lock lifecycle root {root}: {error.strerror}") from error
    finally:
        if descriptor >= 0:
            # Close-only release is deliberate. Operation supervision passes a
            # duplicate of this same open-file-description to its watchdog;
            # an explicit LOCK_UN here would also unlock that retained copy
            # before crash cleanup had proven the command group gone.
            os.close(descriptor)


def _fsync_directory(directory: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(directory, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _atomic_write_marker(marker: Path, content: bytes) -> None:
    temporary = marker.parent / (
        f".{marker.name}.tmp.{os.getpid()}.{secrets.token_hex(8)}"
    )
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    descriptor = -1
    try:
        descriptor = os.open(temporary, flags, 0o600)
        view = memoryview(content)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise LifecycleError(f"short write while creating marker {marker}")
            view = view[written:]
        os.fchmod(descriptor, 0o600)
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = -1
        # link(2) gives us create-only publication: unlike rename/replace it
        # cannot overwrite a marker created by a non-cooperating process.
        os.link(temporary, marker, follow_symlinks=False)
        temporary.unlink()
        _fsync_directory(marker.parent)
    except OSError as error:
        raise LifecycleError(f"cannot atomically write marker {marker}: {error.strerror}") from error
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _atomic_replace_marker(marker: Path, content: bytes) -> None:
    temporary = marker.parent / (
        f".{marker.name}.tmp.{os.getpid()}.{secrets.token_hex(8)}"
    )
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    descriptor = -1
    try:
        descriptor = os.open(temporary, flags, 0o600)
        view = memoryview(content)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise LifecycleError(f"short write while replacing marker {marker}")
            view = view[written:]
        os.fchmod(descriptor, 0o600)
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = -1
        os.replace(temporary, marker)
        _fsync_directory(marker.parent)
    except OSError as error:
        raise LifecycleError(f"cannot atomically replace marker {marker}: {error.strerror}") from error
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _recover_marker_temporaries(marker: Path) -> None:
    prefix = f".{marker.name}.tmp."
    removed = False
    try:
        candidates = [
            path
            for path in marker.parent.iterdir()
            if path.name.startswith(prefix)
            and ATOMIC_TEMP_SUFFIX.fullmatch(path.name[len(prefix) :])
        ]
    except OSError as error:
        raise LifecycleError(
            f"cannot inspect atomic marker temporaries beside {marker}: {error.strerror}"
        ) from error

    marker_info: Optional[os.stat_result] = None
    try:
        marker_info = marker.lstat()
    except FileNotFoundError:
        pass
    except OSError as error:
        raise LifecycleError(f"cannot inspect lifecycle record {marker}: {error.strerror}") from error
    if marker_info is not None and stat.S_ISLNK(marker_info.st_mode):
        raise LifecycleError(f"lifecycle record must not be a symlink: {marker}")

    for candidate in candidates:
        try:
            info = candidate.lstat()
        except OSError as error:
            raise LifecycleError(
                f"cannot inspect atomic marker temporary {candidate}: {error.strerror}"
            ) from error
        if (
            stat.S_ISLNK(info.st_mode)
            or not stat.S_ISREG(info.st_mode)
            or (hasattr(os, "geteuid") and info.st_uid != os.geteuid())
            or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_size > MAX_IDENTITY_BYTES
        ):
            raise LifecycleError(f"unsafe atomic marker temporary requires inspection: {candidate}")
        if info.st_nlink > 1 and (
            marker_info is None
            or (info.st_dev, info.st_ino) != (marker_info.st_dev, marker_info.st_ino)
        ):
            raise LifecycleError(f"atomic marker temporary has an unexpected hard link: {candidate}")
        try:
            candidate.unlink()
            removed = True
        except OSError as error:
            raise LifecycleError(
                f"cannot recover atomic marker temporary {candidate}: {error.strerror}"
            ) from error
    if removed:
        _fsync_directory(marker.parent)


def _ensure_owned_parent(root: Path, target: Path, description: str) -> None:
    try:
        parts = target.parent.relative_to(root).parts
    except ValueError as error:
        raise LifecycleError(f"{description} parent escapes allowed root {root}") from error

    current = root
    _validate_owned_directory(current, "allowed root")
    for part in parts:
        current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError:
            try:
                current.mkdir(mode=0o700)
                os.chmod(current, 0o700)
                _fsync_directory(current)
                _fsync_directory(current.parent)
                info = current.lstat()
            except OSError as error:
                raise LifecycleError(
                    f"cannot create {description} parent {current}: {error.strerror}"
                ) from error
        except OSError as error:
            raise LifecycleError(
                f"cannot inspect {description} parent {current}: {error.strerror}"
            ) from error
        if stat.S_ISLNK(info.st_mode):
            raise LifecycleError(f"{description} traverses a symlink: {current}")
        _validate_owned_directory(current, f"{description} parent")


def _validate_owned_parent_prefix(root: Path, target: Path, description: str) -> None:
    try:
        parts = target.parent.relative_to(root).parts
    except ValueError as error:
        raise LifecycleError(f"{description} parent escapes allowed root {root}") from error
    current = root
    _validate_control_directory(current, "allowed root")
    for part in parts:
        current = current / part
        if not _exists(current):
            return
        _validate_control_directory(current, f"{description} parent")


def _retirement_fixed(plan: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": RETIRED_INSTANCE_KIND,
        "networkName": plan["networkName"],
        "instanceId": plan["instanceId"],
        "deployment": plan["deployment"],
        "chainId": plan["chainId"],
        "appchainIdentitySha256": plan["appchainIdentitySha256"],
        "replacementInstanceId": plan["replacementInstanceId"],
        "replacementChainId": plan["replacementChainId"],
    }


def _reservation_fixed(plan: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": RESET_RESERVATION_KIND,
        "networkName": plan["networkName"],
        "retiredInstanceId": plan["instanceId"],
        "deployment": plan["deployment"],
        "oldChainId": plan["chainId"],
        "newInstanceId": plan["replacementInstanceId"],
        "newChainId": plan["replacementChainId"],
    }


def _read_canonical_record(path: Path, description: str, expected_kind: str) -> dict[str, Any]:
    _final_component_is_symlink(path, description)
    content = _read_regular_file(path, description, owner_safe=True)
    document = _parse_identity(content, description, expected_kind)
    if content != _canonical_json(document):
        raise LifecycleError(f"{description} is not canonical JSON: {path}")
    return document


@dataclass(frozen=True)
class ActiveClaim:
    path: Path
    document: dict[str, Any]
    digest: str
    pair: tuple[str, str]
    chain_ids: tuple[str, ...]


@dataclass(frozen=True)
class RetirementClaim:
    path: Path
    document: dict[str, Any]

    @property
    def old_pair(self) -> tuple[str, str]:
        return self.document["deployment"], self.document["instanceId"]

    @property
    def replacement_pair(self) -> tuple[str, str]:
        return self.document["deployment"], self.document["replacementInstanceId"]


@dataclass(frozen=True)
class ReservationClaim:
    path: Path
    document: dict[str, Any]

    @property
    def old_pair(self) -> tuple[str, str]:
        return self.document["deployment"], self.document["retiredInstanceId"]

    @property
    def replacement_pair(self) -> tuple[str, str]:
        return self.document["deployment"], self.document["newInstanceId"]


@dataclass
class ClaimSnapshot:
    active_by_pair: dict[tuple[str, str], ActiveClaim]
    retirement_by_old: dict[tuple[str, str], RetirementClaim]
    retirement_by_replacement: dict[tuple[str, str], RetirementClaim]
    reservation_by_replacement: dict[tuple[str, str], ReservationClaim]
    chain_pairs: dict[str, tuple[str, str]]


def _exists(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def _validate_control_directory(path: Path, description: str) -> None:
    try:
        info = path.lstat()
    except OSError as error:
        raise LifecycleError(f"cannot inspect {description} {path}: {error.strerror}") from error
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise LifecycleError(f"{description} is not a real directory: {path}")
    _validate_owned_directory(path, description)


def _validate_inert_lock(path: Path) -> None:
    content = _read_regular_file(path, "nested lifecycle lock", owner_safe=True)
    info = path.stat(follow_symlinks=False)
    if content or stat.S_IMODE(info.st_mode) != 0o600:
        raise LifecycleError(f"nested lifecycle lock must be an empty owner-only 0600 file: {path}")


def _safe_directory_entries(path: Path, description: str) -> list[Path]:
    try:
        return sorted(path.iterdir(), key=lambda candidate: candidate.name)
    except OSError as error:
        raise LifecycleError(f"cannot inspect {description} {path}: {error.strerror}") from error


def _canonical_supplied_identity(
    value: str,
    description: str,
    expected_kind: str,
) -> tuple[dict[str, Any], bytes]:
    path = _raw_path(value, description)
    _final_component_is_symlink(path, description)
    content = _read_regular_file(path, description, owner_safe=True)
    document = _parse_identity(content, description, expected_kind)
    canonical = _canonical_json(document)
    if content != canonical:
        raise LifecycleError(f"{description} is not canonical JSON: {path}")
    return document, canonical


def _identity_coordinates(
    document: dict[str, Any],
    description: str,
) -> tuple[str, str, str, tuple[str, ...]]:
    network = document.get("networkName")
    instance = document.get("instanceId")
    deployment = document.get("deployment")
    chains = document.get("chainIds")
    if not isinstance(network, str) or not NETWORK_NAME.fullmatch(network):
        raise LifecycleError(f"{description} has an invalid networkName")
    if not isinstance(instance, str) or not INSTANCE_NAME.fullmatch(instance):
        raise LifecycleError(f"{description} has an invalid instanceId")
    if not isinstance(deployment, str) or not DEPLOYMENT_NAME.fullmatch(deployment):
        raise LifecycleError(f"{description} has an invalid deployment")
    if (
        not isinstance(chains, list)
        or not chains
        or any(not isinstance(chain, str) or not CHAIN_ID.fullmatch(chain) for chain in chains)
        or len(chains) != len(set(chains))
    ):
        raise LifecycleError(f"{description} has invalid or duplicate chainIds")
    return network, instance, deployment, tuple(chains)


def _positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _validate_active_identity_shape(document: dict[str, Any], marker: Path) -> None:
    expected_fields = {
        "schemaVersion", "kind", "layoutVersion", "networkName",
        "networkIdentitySha256", "instanceId", "deployment", "composeProject",
        "chainIds", "stateMachine", "membership", "effects", "anchor", "connectors",
    }
    if set(document) != expected_fields:
        raise LifecycleError(f"app-chain identity marker has an invalid field set: {marker}")
    layout_version = document.get("layoutVersion")
    if layout_version not in {1, 2, 3, 4}:
        raise LifecycleError(f"app-chain identity marker has an invalid layoutVersion: {marker}")
    digest = document.get("networkIdentitySha256")
    if not isinstance(digest, str) or not SHA256_HEX.fullmatch(digest):
        raise LifecycleError(f"app-chain identity marker has an invalid network digest: {marker}")
    project = document.get("composeProject")
    if document.get("deployment") == "compose":
        if not isinstance(project, str) or not project or len(project) > 255:
            raise LifecycleError(f"compose identity marker has an invalid project: {marker}")
    elif project is not None:
        raise LifecycleError(f"host identity marker must use a null composeProject: {marker}")

    state_machine = document.get("stateMachine")
    state_machine_fields = (
        set(state_machine) if isinstance(state_machine, dict) else set()
    )
    base_state_machine_fields = {"provider", "profileVersion", "effectEmissionVersion"}
    if layout_version == 4:
        base_state_machine_fields.add("evidenceCapacityPerBlock")
    provider = state_machine.get("provider") if isinstance(state_machine, dict) else None
    valid_state_machine_profile = (
        provider == "evidence-registry"
        and state_machine_fields == base_state_machine_fields
    ) or (
        provider == "composite"
        and state_machine_fields == base_state_machine_fields | {"preset"}
        and state_machine.get("preset") in {"evidence-v1", "evidence-v1-gated"}
    ) or (
        provider == "role-evidence"
        and state_machine_fields
        == base_state_machine_fields | {"preset", "profileDigest"}
        and state_machine.get("preset") == "evidence-role-v1"
        and isinstance(state_machine.get("profileDigest"), str)
        and SHA256_HEX.fullmatch(state_machine["profileDigest"]) is not None
    )
    if (
        not isinstance(state_machine, dict)
        or not valid_state_machine_profile
        or not _positive_int(state_machine.get("profileVersion"))
        or not _positive_int(state_machine.get("effectEmissionVersion"))
        or (
            layout_version == 4
            and state_machine.get("evidenceCapacityPerBlock") != 8
        )
    ):
        raise LifecycleError(f"app-chain identity marker has invalid stateMachine metadata: {marker}")

    membership = document.get("membership")
    if not isinstance(membership, dict) or set(membership) != {
        "members", "threshold", "proposer", "resultSigners"
    }:
        raise LifecycleError(f"app-chain identity marker has invalid membership metadata: {marker}")
    members = membership.get("members")
    result_signers = membership.get("resultSigners")
    proposer = membership.get("proposer")
    threshold = membership.get("threshold")
    if (
        not isinstance(members, list)
        or not members
        or any(not isinstance(member, str) or not member for member in members)
        or len(members) != len(set(members))
        or not isinstance(result_signers, list)
        or not result_signers
        or any(not isinstance(signer, str) or signer not in members for signer in result_signers)
        or not isinstance(proposer, str)
        or proposer not in members
        or not _positive_int(threshold)
        or threshold > len(members)
    ):
        raise LifecycleError(f"app-chain identity marker has inconsistent membership: {marker}")

    effects = document.get("effects")
    legacy_effect_fields = {"storageGate", "requireAnchor"}
    current_effect_fields = legacy_effect_fields | {
        "continuationMode", "directResultEmissionActivationHeight"
    }
    if not isinstance(effects, dict):
        raise LifecycleError(f"app-chain identity marker has invalid effect metadata: {marker}")
    if layout_version in {1, 2}:
        valid_effect_shape = set(effects) == legacy_effect_fields
        valid_continuation_profile = state_machine.get("profileVersion") == 1
    else:
        continuation_mode = effects.get("continuationMode")
        activation_height = effects.get("directResultEmissionActivationHeight")
        valid_effect_shape = set(effects) == current_effect_fields
        valid_continuation_profile = (
            continuation_mode == "explicit"
            and activation_height is None
            and state_machine.get("profileVersion") == 1
        ) or (
            continuation_mode == "direct"
            and activation_height == 1
            and state_machine.get("profileVersion") == 2
        )
    if (
        not valid_effect_shape
        or effects.get("storageGate") not in {"app-final", "l1-anchored", "zk-settled"}
        or not isinstance(effects.get("requireAnchor"), bool)
        or not valid_continuation_profile
    ):
        raise LifecycleError(f"app-chain identity marker has invalid effect metadata: {marker}")

    anchor = document.get("anchor")
    if not isinstance(anchor, dict) or set(anchor) != {
        "enabled", "mode", "everyBlocks", "maxIntervalMinutes", "signerFingerprint"
    } or not isinstance(anchor.get("enabled"), bool):
        raise LifecycleError(f"app-chain identity marker has invalid anchor metadata: {marker}")
    if anchor["enabled"]:
        if (
            anchor.get("mode") != "script"
            or not _positive_int(anchor.get("everyBlocks"))
            or not _positive_int(anchor.get("maxIntervalMinutes"))
            or not isinstance(anchor.get("signerFingerprint"), str)
            or not SHA256_HEX.fullmatch(anchor["signerFingerprint"])
            or effects.get("requireAnchor") is not True
        ):
            raise LifecycleError(f"enabled anchor metadata is inconsistent: {marker}")
    elif (
        anchor.get("mode") != "none"
        or anchor.get("everyBlocks") is not None
        or anchor.get("maxIntervalMinutes") is not None
        or anchor.get("signerFingerprint") is not None
        or effects.get("requireAnchor") is not False
    ):
        raise LifecycleError(f"disabled anchor metadata is inconsistent: {marker}")

    connectors = document.get("connectors")
    if not isinstance(connectors, dict) or set(connectors) != {"s3", "ipfs", "kafka"}:
        raise LifecycleError(f"app-chain identity marker has invalid connector metadata: {marker}")
    for connector, value in connectors.items():
        expected_connector_fields = {"targetId", "locator", "profile"}
        if layout_version >= 2 and connector == "s3":
            expected_connector_fields |= {
                "provider", "providerVersion", "dataLayoutVersion", "iamConfigSha256"
            }
        if (
            not isinstance(value, dict)
            or set(value) != expected_connector_fields
            or any(not isinstance(value.get(field), str) or not value[field]
                   for field in ("targetId", "locator", "profile"))
        ):
            raise LifecycleError(
                f"app-chain identity marker has invalid {connector} connector metadata: {marker}"
            )
    if layout_version >= 2:
        s3 = connectors["s3"]
        if document.get("deployment") == "compose":
            if (
                s3.get("provider") != "rustfs"
                or s3.get("providerVersion") != "1.0.0-beta.9"
                or s3.get("profile") != "local-demo-v2"
                or s3.get("dataLayoutVersion") != 2
                or not isinstance(s3.get("iamConfigSha256"), str)
                or not SHA256_HEX.fullmatch(s3["iamConfigSha256"])
            ):
                raise LifecycleError(
                    f"compose identity marker has incompatible S3 provider metadata: {marker}"
                )
        elif (
            s3.get("provider") != "external-s3-compatible"
            or s3.get("providerVersion") is not None
            or s3.get("profile") != "operator-managed-v1"
            or s3.get("dataLayoutVersion") != 1
            or s3.get("iamConfigSha256") is not None
        ):
            raise LifecycleError(
                f"host identity marker has invalid provider-neutral S3 metadata: {marker}"
            )


def _validate_lease_identity_shape(document: dict[str, Any], description: str) -> None:
    expected_fields = {
        "schemaVersion", "kind", "networkName", "instanceId", "deployment", "chainIds",
        "project", "networkIdentitySha256", "appchainIdentitySha256",
    }
    if set(document) != expected_fields:
        raise LifecycleError(f"{description} has an invalid field set")
    for field in ("networkIdentitySha256", "appchainIdentitySha256"):
        value = document.get(field)
        if not isinstance(value, str) or not SHA256_HEX.fullmatch(value):
            raise LifecycleError(f"{description} has an invalid {field}")
    project = document.get("project")
    if not isinstance(project, str) or not project or len(project) > 255:
        raise LifecycleError(f"{description} has an invalid project")


def _read_active_claim(
    marker: Path,
    network_name: str,
    logical_instance: str,
    logical_deployment: str,
) -> ActiveClaim:
    content = _read_regular_file(marker, "app-chain identity marker", owner_safe=True)
    document = _parse_identity(content, "app-chain identity marker", INSTANCE_KIND)
    if content != _canonical_json(document):
        raise LifecycleError(f"app-chain identity marker is not canonical JSON: {marker}")
    _validate_active_identity_shape(document, marker)
    network, instance, deployment, chains = _identity_coordinates(
        document,
        "app-chain identity marker",
    )
    if network != network_name or instance != logical_instance or deployment != logical_deployment:
        raise LifecycleError(f"app-chain identity marker does not match its control path: {marker}")
    return ActiveClaim(
        marker,
        document,
        _identity_digest(content),
        (deployment, instance),
        chains,
    )


def _recover_control_record_temporaries(directory: Path) -> None:
    bases: set[str] = set()
    for entry in _safe_directory_entries(directory, "lifecycle record directory"):
        name = entry.name
        if not name.startswith(".") or ".tmp." not in name:
            continue
        base, suffix = name[1:].split(".tmp.", 1)
        if MARKER_NAME.fullmatch(base) and ATOMIC_TEMP_SUFFIX.fullmatch(suffix):
            bases.add(base)
    for base in sorted(bases):
        _recover_marker_temporaries(directory / base)


def _scan_active_claims(
    root: Path,
    network_name: str,
    candidate: Optional[tuple[tuple[str, str], Path]] = None,
    logical_overrides: Optional[dict[Path, Path]] = None,
) -> dict[tuple[str, str], ActiveClaim]:
    instances = root / "instances"
    if not _exists(instances):
        return {}
    _validate_control_directory(instances, "instances control directory")
    overrides = logical_overrides or {}
    candidate_pair = candidate[0] if candidate else None
    candidate_path = candidate[1] if candidate else None
    active: dict[tuple[str, str], ActiveClaim] = {}

    for instance_entry in _safe_directory_entries(instances, "instances control directory"):
        if instance_entry.name == LOCK_NAME:
            _validate_inert_lock(instance_entry)
            continue
        if not INSTANCE_NAME.fullmatch(instance_entry.name):
            raise LifecycleError(f"unexpected entry in instances control directory: {instance_entry}")
        _validate_control_directory(instance_entry, "instance control directory")
        seen_deployment = False
        for deployment_entry in _safe_directory_entries(instance_entry, "instance control directory"):
            logical_path = instance_entry / deployment_entry.name
            if deployment_entry in overrides.values():
                # A recognized transaction quarantine is scanned below using
                # its immutable logical path and coordinates.
                continue
            if not DEPLOYMENT_NAME.fullmatch(deployment_entry.name):
                raise LifecycleError(f"unexpected entry in instance control directory: {deployment_entry}")
            seen_deployment = True
            _validate_control_directory(deployment_entry, "deployment state directory")
            marker = deployment_entry / DEFAULT_INSTANCE_MARKER
            _recover_marker_temporaries(marker)
            if not _exists(marker):
                entries = _safe_directory_entries(deployment_entry, "deployment state directory")
                pair = (deployment_entry.name, instance_entry.name)
                if pair == candidate_pair and deployment_entry == candidate_path and not entries:
                    continue
                raise LifecycleError(f"deployment state is missing its identity marker: {marker}")
            claim = _read_active_claim(
                marker,
                network_name,
                instance_entry.name,
                deployment_entry.name,
            )
            if claim.pair in active:
                raise LifecycleError(f"duplicate active app-chain identity for {claim.pair}")
            active[claim.pair] = claim

        for logical_path, physical_path in overrides.items():
            if logical_path.parent != instance_entry or not _exists(physical_path):
                continue
            deployment = logical_path.name
            if not DEPLOYMENT_NAME.fullmatch(deployment):
                raise LifecycleError(f"invalid quarantined deployment path: {logical_path}")
            seen_deployment = True
            _validate_control_directory(physical_path, "quarantined deployment state directory")
            marker = physical_path / DEFAULT_INSTANCE_MARKER
            _recover_marker_temporaries(marker)
            if not _exists(marker):
                raise LifecycleError(f"quarantined deployment is missing its identity marker: {marker}")
            claim = _read_active_claim(marker, network_name, instance_entry.name, deployment)
            if claim.pair in active:
                raise LifecycleError(f"deployment exists at both active and quarantine paths: {logical_path}")
            active[claim.pair] = claim
        if not seen_deployment:
            if candidate_path is not None and instance_entry == candidate_path.parent:
                continue
            raise LifecycleError(f"partial instance control directory has no deployment record: {instance_entry}")
    return active


def _scan_record_files(root: Path, category: str) -> list[Path]:
    category_root = root / category
    if not _exists(category_root):
        return []
    _validate_control_directory(category_root, f"{category} control directory")
    records: list[Path] = []
    for deployment_dir in _safe_directory_entries(category_root, f"{category} control directory"):
        if deployment_dir.name == LOCK_NAME:
            _validate_inert_lock(deployment_dir)
            continue
        if not DEPLOYMENT_NAME.fullmatch(deployment_dir.name):
            raise LifecycleError(f"unexpected entry in {category} control directory: {deployment_dir}")
        _validate_control_directory(deployment_dir, f"{category} deployment directory")
        _recover_control_record_temporaries(deployment_dir)
        for record in _safe_directory_entries(deployment_dir, f"{category} deployment directory"):
            if not record.name.endswith(".json"):
                raise LifecycleError(f"unexpected entry in {category} deployment directory: {record}")
            instance = record.name[:-5]
            if not INSTANCE_NAME.fullmatch(instance):
                raise LifecycleError(f"invalid lifecycle record filename: {record}")
            records.append(record)
    return records


def _generic_retirement_claim(path: Path, network_name: str) -> RetirementClaim:
    document = _read_canonical_record(path, "retirement record", RETIRED_INSTANCE_KIND)
    expected_fields = {
        "schemaVersion", "kind", "networkName", "instanceId", "deployment", "chainId",
        "appchainIdentitySha256", "replacementInstanceId", "replacementChainId", "status",
        "updatedAtMillis",
    }
    if set(document) != expected_fields:
        raise LifecycleError(f"retirement record has an invalid field set: {path}")
    for field, pattern in (
        ("networkName", NETWORK_NAME),
        ("instanceId", INSTANCE_NAME),
        ("deployment", DEPLOYMENT_NAME),
        ("chainId", CHAIN_ID),
        ("appchainIdentitySha256", SHA256_HEX),
        ("replacementInstanceId", INSTANCE_NAME),
        ("replacementChainId", CHAIN_ID),
    ):
        value = document.get(field)
        if not isinstance(value, str) or not pattern.fullmatch(value):
            raise LifecycleError(f"retirement record field {field!r} is invalid: {path}")
    if document["networkName"] != network_name:
        raise LifecycleError(f"retirement record belongs to another network: {path}")
    if document["instanceId"] == document["replacementInstanceId"]:
        raise LifecycleError(f"retirement record reuses its old instance id: {path}")
    if document["chainId"] == document["replacementChainId"]:
        raise LifecycleError(f"retirement record reuses its old chain id: {path}")
    if path.parent.name != document["deployment"] or path.stem != document["instanceId"]:
        raise LifecycleError(f"retirement record does not match its control path: {path}")
    if document.get("status") not in {"retiring", "retired"}:
        raise LifecycleError(f"retirement record has an invalid status: {path}")
    updated = document.get("updatedAtMillis")
    if not isinstance(updated, int) or isinstance(updated, bool) or updated < 0:
        raise LifecycleError(f"retirement record has an invalid updatedAtMillis: {path}")
    return RetirementClaim(path, document)


def _generic_reservation_claim(path: Path, network_name: str) -> ReservationClaim:
    document = _read_canonical_record(path, "replacement reservation", RESET_RESERVATION_KIND)
    expected_fields = {
        "schemaVersion", "kind", "networkName", "retiredInstanceId", "deployment",
        "oldChainId", "newInstanceId", "newChainId",
    }
    if set(document) != expected_fields:
        raise LifecycleError(f"replacement reservation has an invalid field set: {path}")
    for field, pattern in (
        ("networkName", NETWORK_NAME),
        ("retiredInstanceId", INSTANCE_NAME),
        ("deployment", DEPLOYMENT_NAME),
        ("oldChainId", CHAIN_ID),
        ("newInstanceId", INSTANCE_NAME),
        ("newChainId", CHAIN_ID),
    ):
        value = document.get(field)
        if not isinstance(value, str) or not pattern.fullmatch(value):
            raise LifecycleError(f"replacement reservation field {field!r} is invalid: {path}")
    if document["networkName"] != network_name:
        raise LifecycleError(f"replacement reservation belongs to another network: {path}")
    if document["retiredInstanceId"] == document["newInstanceId"]:
        raise LifecycleError(f"replacement reservation reuses its old instance id: {path}")
    if document["oldChainId"] == document["newChainId"]:
        raise LifecycleError(f"replacement reservation reuses its old chain id: {path}")
    if path.parent.name != document["deployment"] or path.stem != document["newInstanceId"]:
        raise LifecycleError(f"replacement reservation does not match its control path: {path}")
    return ReservationClaim(path, document)


def _reservation_matches_retirement(
    retirement: RetirementClaim,
    reservation: ReservationClaim,
) -> bool:
    left = retirement.document
    right = reservation.document
    return (
        left["networkName"] == right["networkName"]
        and left["instanceId"] == right["retiredInstanceId"]
        and left["deployment"] == right["deployment"]
        and left["chainId"] == right["oldChainId"]
        and left["replacementInstanceId"] == right["newInstanceId"]
        and left["replacementChainId"] == right["newChainId"]
    )


def _claim_snapshot(
    root: Path,
    network_name: str,
    candidate: Optional[tuple[tuple[str, str], Path]] = None,
    logical_overrides: Optional[dict[Path, Path]] = None,
    recoverable_missing_reservation: Optional[tuple[str, str]] = None,
) -> ClaimSnapshot:
    network_digest = _validate_network_root_identity(root, network_name)
    active = _scan_active_claims(root, network_name, candidate, logical_overrides)
    for claim in active.values():
        if claim.document.get("networkIdentitySha256") != network_digest:
            raise LifecycleError(
                f"app-chain identity does not bind the retained network marker: {claim.path}"
            )
    retirements = [
        _generic_retirement_claim(path, network_name)
        for path in _scan_record_files(root, "retired")
    ]
    reservations = [
        _generic_reservation_claim(path, network_name)
        for path in _scan_record_files(root, "reservations")
    ]
    by_old: dict[tuple[str, str], RetirementClaim] = {}
    by_replacement: dict[tuple[str, str], RetirementClaim] = {}
    reservation_by_replacement: dict[tuple[str, str], ReservationClaim] = {}
    chain_pairs: dict[str, tuple[str, str]] = {}

    def register_chain(chain: str, pair: tuple[str, str], source: Path) -> None:
        previous = chain_pairs.get(chain)
        if previous is not None and previous != pair:
            raise LifecycleError(
                f"chain id {chain!r} is claimed by both {previous} and {pair}; source={source}"
            )
        chain_pairs[chain] = pair

    for retirement in retirements:
        if retirement.old_pair in by_old:
            raise LifecycleError(f"multiple retirement records claim {retirement.old_pair}")
        if retirement.replacement_pair in by_replacement:
            raise LifecycleError(
                f"multiple retirement records reserve {retirement.replacement_pair}"
            )
        by_old[retirement.old_pair] = retirement
        by_replacement[retirement.replacement_pair] = retirement
        register_chain(retirement.document["chainId"], retirement.old_pair, retirement.path)
        register_chain(
            retirement.document["replacementChainId"],
            retirement.replacement_pair,
            retirement.path,
        )

    for reservation in reservations:
        pair = reservation.replacement_pair
        if pair in reservation_by_replacement:
            raise LifecycleError(f"multiple replacement reservations claim {pair}")
        reservation_by_replacement[pair] = reservation
        register_chain(reservation.document["oldChainId"], reservation.old_pair, reservation.path)
        register_chain(reservation.document["newChainId"], pair, reservation.path)

    # A lone retirement still owns both identities, but it is an incomplete
    # global transaction: fail every acquisition until its exact reservation
    # is recovered.  The same applies to an orphan reservation.
    for pair, retirement in by_replacement.items():
        reservation = reservation_by_replacement.get(pair)
        if reservation is None:
            if pair == recoverable_missing_reservation:
                continue
            raise LifecycleError(
                f"retirement is missing its durable replacement reservation: {retirement.path}"
            )
        if not _reservation_matches_retirement(retirement, reservation):
            raise LifecycleError(
                f"retirement and replacement reservation disagree for {pair}"
            )
    for pair, reservation in reservation_by_replacement.items():
        retirement = by_replacement.get(pair)
        if retirement is None:
            raise LifecycleError(
                f"replacement reservation has no matching retirement: {reservation.path}"
            )

    for pair, claim in active.items():
        for chain in claim.chain_ids:
            register_chain(chain, pair, claim.path)
        outgoing = by_old.get(pair)
        incoming = by_replacement.get(pair)
        if outgoing is not None:
            if (
                claim.digest != outgoing.document["appchainIdentitySha256"]
                or claim.chain_ids != (outgoing.document["chainId"],)
            ):
                raise LifecycleError(
                    f"active identity does not match its retirement fence: {claim.path}"
                )
            if outgoing.document["status"] == "retired":
                raise LifecycleError(f"retired identity still has active state: {claim.path}")
        if incoming is not None:
            if incoming.document["status"] != "retired":
                raise LifecycleError(f"replacement identity became active before retirement: {claim.path}")
            if claim.chain_ids != (incoming.document["replacementChainId"],):
                raise LifecycleError(f"active replacement does not match its reserved chain: {claim.path}")

    return ClaimSnapshot(
        active,
        by_old,
        by_replacement,
        reservation_by_replacement,
        chain_pairs,
    )


def _walk_lifecycle_artifacts(root: Path) -> list[Path]:
    artifacts: list[Path] = []
    if not _exists(root):
        return artifacts
    if root.is_symlink() or not root.is_dir():
        raise LifecycleError(f"lifecycle artifact root is not a real directory: {root}")

    def walk_error(error: OSError) -> None:
        raise LifecycleError(
            f"cannot inspect lifecycle artifacts below {error.filename or root}: {error.strerror}"
        ) from error

    for directory, directory_names, file_names in os.walk(
        root,
        topdown=True,
        onerror=walk_error,
        followlinks=False,
    ):
        base = Path(directory)
        for name in (*directory_names, *file_names):
            if ".yano-quarantine." in name or name.endswith(".yano-cleanup.json"):
                artifacts.append(base / name)
    return sorted(set(artifacts))


def _reject_active_cleanup(root: Path) -> None:
    central = root / CENTRAL_CLEANUP_MARKER
    _recover_marker_temporaries(central)
    if _exists(central):
        # Validate instead of merely testing existence so malformed or aliased
        # global state always produces a fail-closed diagnostic.
        _read_canonical_record(central, "central cleanup transaction", CLEANUP_TRANSACTION_KIND)
        raise LifecycleError(f"a cleanup transaction is active: {central}")
    artifacts = _walk_lifecycle_artifacts(root)
    if artifacts:
        rendered = ", ".join(str(path) for path in artifacts)
        raise LifecycleError(f"cleanup journal or quarantine requires recovery: {rendered}")


def _validate_network_root_identity(root: Path, network_name: str) -> str:
    marker = root / DEFAULT_NETWORK_MARKER
    content = _read_regular_file(marker, "network identity marker", owner_safe=True)
    document = _parse_identity(content, "network identity marker", NETWORK_KIND)
    if content != _canonical_json(document):
        raise LifecycleError(f"network identity marker is not canonical JSON: {marker}")
    if document.get("networkName") != network_name:
        raise LifecycleError(
            f"network identity marker does not match requested network {network_name!r}: {marker}"
        )
    return _identity_digest(content)


def _operation_lock_root(data_root: Path, network: str, deployment: str) -> Path:
    if not NETWORK_NAME.fullmatch(network):
        raise LifecycleError("operation lock has an invalid network name")
    if not DEPLOYMENT_NAME.fullmatch(deployment):
        raise LifecycleError("operation lock has an invalid deployment name")
    return data_root / OPERATION_LOCK_DIRECTORY / network / deployment


def _prepare_operation_lock(args: argparse.Namespace, create: bool) -> tuple[Path, Path]:
    _, data_root = _prepare_allowed_root(args.data_root, create=create)
    lock_root = _operation_lock_root(data_root, args.network, args.deployment)
    if create:
        _ensure_owned_parent(data_root, lock_root / LOCK_NAME, "operation lock")
    if not _exists(lock_root):
        raise LifecycleError(f"operation lock directory does not exist: {lock_root}")
    if lock_root.is_symlink() or not lock_root.is_dir():
        raise LifecycleError(f"operation lock root is not a real directory: {lock_root}")
    _validate_owned_directory(lock_root, "operation lock directory")
    return data_root, lock_root


def _process_group_exists(group_id: int) -> bool:
    try:
        os.killpg(group_id, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError as error:
        raise LifecycleError(
            f"cannot prove operation process group {group_id} is owned by this user"
        ) from error


def _terminate_operation_group(group_id: int) -> None:
    if not _process_group_exists(group_id):
        return
    try:
        os.killpg(group_id, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if not _process_group_exists(group_id):
            return
        time.sleep(0.05)
    try:
        os.killpg(group_id, signal.SIGKILL)
    except ProcessLookupError:
        return
    # Fail closed: an unkillable process tree must retain serialization rather
    # than allowing another lifecycle command to overlap it.
    while _process_group_exists(group_id):
        time.sleep(0.1)


def _operation_run(args: argparse.Namespace) -> int:
    command = list(args.command)
    if command and command[0] == "--":
        command.pop(0)
    if not command:
        raise LifecycleError("operation-run requires a command after --")
    expected_launcher = (Path(__file__).resolve().parent.parent / "demo.sh").resolve(strict=True)
    try:
        supplied_launcher = Path(command[0]).resolve(strict=True)
    except OSError as error:
        raise LifecycleError(
            f"cannot resolve operation launcher {command[0]!r}: {error.strerror}"
        ) from error
    if supplied_launcher != expected_launcher:
        raise LifecycleError("operation-run is internal and accepts only the bundled demo.sh")
    _, lock_root = _prepare_operation_lock(args, create=True)
    with _exclusive_lock(lock_root) as descriptor:
        environment = os.environ.copy()
        environment["YANO_DEMO_OPERATION_LOCK_ROOT"] = str(lock_root)
        environment["YANO_DEMO_OPERATION_LOCK_FD"] = str(descriptor)
        watch_read = -1
        watch_write = -1
        ready_read = -1
        ready_write = -1
        process: Optional[subprocess.Popen[bytes]] = None
        previous_handlers: dict[int, Any] = {}
        try:
            watch_read, watch_write = os.pipe()
            ready_read, ready_write = os.pipe()
            environment["YANO_DEMO_OPERATION_WATCH_FD"] = str(watch_read)
            environment["YANO_DEMO_OPERATION_READY_READ_FD"] = str(ready_read)
            environment["YANO_DEMO_OPERATION_READY_WRITE_FD"] = str(ready_write)
            process = subprocess.Popen(
                command,
                env=environment,
                pass_fds=(descriptor, watch_read, ready_read, ready_write),
                start_new_session=True,
            )
            os.close(watch_read)
            watch_read = -1
            os.close(ready_read)
            ready_read = -1
            os.close(ready_write)
            ready_write = -1

            def forward_signal(signum: int, _frame: Any) -> None:
                if process is None or process.poll() is not None:
                    return
                try:
                    os.killpg(process.pid, signum)
                except ProcessLookupError:
                    pass

            forwarded_signals = [signal.SIGINT, signal.SIGTERM]
            if hasattr(signal, "SIGHUP"):
                forwarded_signals.append(signal.SIGHUP)
            for forwarded in forwarded_signals:
                previous_handlers[forwarded] = signal.signal(forwarded, forward_signal)

            return_code = process.wait()
            if return_code != 0:
                _terminate_operation_group(process.pid)
            else:
                try:
                    if os.write(watch_write, b"S") != 1:
                        raise OSError(errno.EIO, "short operation-success write")
                except OSError:
                    _terminate_operation_group(process.pid)
                    raise
            return return_code if return_code >= 0 else 128 + abs(return_code)
        except OSError as error:
            raise LifecycleError(
                f"cannot execute operation-locked command {command[0]!r}: {error.strerror}"
            ) from error
        finally:
            for forwarded, previous in previous_handlers.items():
                signal.signal(forwarded, previous)
            if watch_read >= 0:
                os.close(watch_read)
            if watch_write >= 0:
                os.close(watch_write)
            if ready_read >= 0:
                os.close(ready_read)
            if ready_write >= 0:
                os.close(ready_write)


def _parse_descriptor(value: str, description: str) -> int:
    try:
        descriptor = int(value, 10)
    except ValueError as error:
        raise LifecycleError(f"{description} must be a decimal descriptor") from error
    if descriptor < 3 or descriptor > 1_048_576:
        raise LifecycleError(f"{description} is outside the supported range")
    return descriptor


def _validate_operation_lock_descriptor(lock_root: Path, descriptor: int) -> None:
    lock_path = lock_root / LOCK_NAME
    try:
        descriptor_info = os.fstat(descriptor)
        path_info = os.stat(lock_path, follow_symlinks=False)
    except OSError as error:
        raise LifecycleError(f"cannot validate inherited operation lock: {error.strerror}") from error
    if (
        not stat.S_ISREG(descriptor_info.st_mode)
        or descriptor_info.st_uid != os.geteuid()
        or descriptor_info.st_nlink != 1
        or stat.S_IMODE(descriptor_info.st_mode) != 0o600
        or descriptor_info.st_size != 0
        or (descriptor_info.st_dev, descriptor_info.st_ino)
        != (path_info.st_dev, path_info.st_ino)
    ):
        raise LifecycleError("inherited operation lock does not match its exact lock file")
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as error:
        raise LifecycleError("inherited operation lock is not exclusively owned") from error


def _operation_validate(args: argparse.Namespace) -> None:
    _, lock_root = _prepare_operation_lock(args, create=False)
    descriptor = _parse_descriptor(args.fd, "operation lock fd")
    _validate_operation_lock_descriptor(lock_root, descriptor)
    print(f"VALID operation-lock {lock_root}")


def _operation_watch(args: argparse.Namespace) -> None:
    try:
        expected_parent = int(args.parent_pid, 10)
    except ValueError as error:
        raise LifecycleError("operation watch parent pid must be a decimal integer") from error
    if expected_parent <= 1:
        raise LifecycleError("operation watch parent pid is outside the supported range")
    watch_descriptor = _parse_descriptor(args.fd, "operation watch fd")
    lock_descriptor = _parse_descriptor(args.lock_fd, "operation lock fd")
    ready_descriptor = _parse_descriptor(args.ready_fd, "operation ready fd")
    unused_ready_read = _parse_descriptor(
        args.unused_ready_read_fd,
        "operation unused ready-read fd",
    )
    _, lock_root = _prepare_operation_lock(args, create=False)

    # Detach before announcing readiness so terminating the command group can
    # never terminate this lock-owning watchdog.
    try:
        os.setsid()
    except OSError as error:
        raise LifecycleError(f"cannot detach operation watchdog: {error.strerror}") from error
    for ignored in (signal.SIGINT, signal.SIGTERM, getattr(signal, "SIGHUP", signal.SIGTERM)):
        signal.signal(ignored, signal.SIG_IGN)
    _validate_operation_lock_descriptor(lock_root, lock_descriptor)
    try:
        info = os.fstat(watch_descriptor)
        ready_info = os.fstat(ready_descriptor)
    except OSError as error:
        raise LifecycleError(f"cannot validate operation watchdog pipes: {error.strerror}") from error
    if not stat.S_ISFIFO(info.st_mode) or not stat.S_ISFIFO(ready_info.st_mode):
        raise LifecycleError("operation watchdog descriptors are not pipes")
    os.close(unused_ready_read)
    try:
        if os.write(ready_descriptor, b"R") != 1:
            raise LifecycleError("operation watchdog readiness write was incomplete")
    finally:
        os.close(ready_descriptor)

    # A single authenticated-by-inheritance byte means the supervisor reaped a
    # successful command whose intentionally detached services may survive.
    # EOF without that byte is every failure/crash case, including a double
    # crash after the command shell has already been re-parented.
    completion = b""
    try:
        while True:
            chunk = os.read(watch_descriptor, 2 - len(completion))
            if not chunk:
                break
            completion += chunk
            if len(completion) > 1:
                break
    finally:
        os.close(watch_descriptor)
    if completion == b"S":
        return
    _terminate_operation_group(expected_parent)


def _authorize_deployment_claim(
    snapshot: ClaimSnapshot,
    pair: tuple[str, str],
    chain_ids: tuple[str, ...],
    identity_digest: str,
) -> None:
    outgoing = snapshot.retirement_by_old.get(pair)
    if outgoing is not None:
        raise LifecycleError(
            f"deployment identity {pair} is permanently fenced by {outgoing.path}"
        )
    incoming = snapshot.retirement_by_replacement.get(pair)
    if incoming is not None:
        if incoming.document["status"] != "retired":
            raise LifecycleError(
                f"replacement identity {pair} cannot activate until retirement completes"
            )
        if chain_ids != (incoming.document["replacementChainId"],):
            raise LifecycleError(
                f"replacement identity {pair} must use its exactly reserved chain id"
            )
        if pair not in snapshot.reservation_by_replacement:
            # ClaimSnapshot normally rejects this partial state. Keep this
            # guard local so authorization remains correct if scanning evolves.
            raise LifecycleError(f"replacement identity {pair} has no durable reservation")
    for chain in chain_ids:
        claimed_pair = snapshot.chain_pairs.get(chain)
        if claimed_pair is not None and claimed_pair != pair:
            raise LifecycleError(
                f"chain id {chain!r} is permanently claimed by {claimed_pair}"
            )
    active = snapshot.active_by_pair.get(pair)
    if active is not None and active.digest != identity_digest:
        raise LifecycleError(
            f"deployment identity {pair} conflicts with its immutable active marker"
        )


def _deployment_acquire(args: argparse.Namespace) -> None:
    identity, identity_content = _canonical_supplied_identity(
        args.identity_file,
        "app-chain identity file",
        INSTANCE_KIND,
    )
    lease, lease_content = _canonical_supplied_identity(
        args.lease_identity_file,
        "lease identity file",
        LEASE_KIND,
    )
    _validate_active_identity_shape(identity, _raw_path(args.identity_file, "app-chain identity file"))
    _validate_lease_identity_shape(lease, "lease identity file")
    network_name, instance, deployment, chain_ids = _identity_coordinates(
        identity,
        "app-chain identity file",
    )
    lease_network, lease_instance, lease_deployment, lease_chains = _identity_coordinates(
        lease,
        "lease identity file",
    )
    if (
        (lease_network, lease_instance, lease_deployment, lease_chains)
        != (network_name, instance, deployment, chain_ids)
    ):
        raise LifecycleError("lease identity coordinates do not match the app-chain identity")
    identity_digest = _identity_digest(identity_content)
    if lease.get("appchainIdentitySha256") != identity_digest:
        raise LifecycleError("lease identity does not bind the supplied app-chain identity digest")
    if lease.get("networkIdentitySha256") != identity.get("networkIdentitySha256"):
        raise LifecycleError("lease identity does not bind the app-chain network identity digest")
    if deployment == "compose" and lease.get("project") != identity.get("composeProject"):
        raise LifecycleError("compose lease project does not match the app-chain identity")

    raw_root, root = _prepare_allowed_root(args.network_root, create=False)
    data_root = _validated_target(args.data_root, raw_root, root, "exact DATA_ROOT")
    l1_root = _validated_target(args.l1_root, raw_root, root, "exact L1_ROOT")
    expected_data = root / "instances" / instance / deployment
    expected_l1 = root / "l1" / deployment
    if data_root != expected_data:
        raise LifecycleError(
            f"DATA_ROOT must exactly match the identity coordinates: expected {expected_data}"
        )
    if l1_root != expected_l1:
        raise LifecycleError(
            f"L1_ROOT must exactly match the identity deployment: expected {expected_l1}"
        )

    pair = (deployment, instance)
    with _exclusive_lock(root):
        data_root = _validated_target(str(data_root), root, root, "exact DATA_ROOT")
        l1_root = _validated_target(str(l1_root), root, root, "exact L1_ROOT")
        network_digest = _validate_network_root_identity(root, network_name)
        if identity.get("networkIdentitySha256") != network_digest:
            raise LifecycleError("app-chain identity does not bind the retained network marker")
        _reject_active_cleanup(root)
        snapshot = _claim_snapshot(root, network_name, candidate=(pair, data_root))
        _authorize_deployment_claim(snapshot, pair, chain_ids, identity_digest)

        marker = data_root / DEFAULT_INSTANCE_MARKER
        lease_marker = l1_root / DEFAULT_LEASE_MARKER
        _validate_owned_parent_prefix(root, data_root, "deployment state directory")
        _validate_owned_parent_prefix(root, l1_root, "L1 state directory")
        if _exists(l1_root):
            _validate_control_directory(l1_root, "L1 state directory")
            _recover_marker_temporaries(lease_marker)
        if _exists(lease_marker):
            current_bytes = _read_regular_file(
                lease_marker,
                "L1 lease",
                owner_safe=True,
            )
            current = _parse_identity(current_bytes, "L1 lease", LEASE_KIND)
            if current_bytes != _canonical_json(current):
                raise LifecycleError(f"L1 lease is not canonical JSON: {lease_marker}")
            _validate_lease_identity_shape(current, "existing L1 lease")
            raise LifecycleError(
                f"L1 state is already leased at {lease_marker}; acquisition is non-reentrant"
            )

        # Only after every semantic and filesystem preflight succeeds do we
        # create control directories. In particular, a lease loser cannot
        # leave a markerless DATA_ROOT that poisons the global claim snapshot.
        _ensure_owned_parent(root, l1_root, "L1 state directory")
        if not _exists(l1_root):
            l1_root.mkdir(mode=0o700)
            os.chmod(l1_root, 0o700)
            _fsync_directory(l1_root.parent)
        _ensure_owned_parent(root, data_root, "deployment state directory")
        if not _exists(data_root):
            data_root.mkdir(mode=0o700)
            os.chmod(data_root, 0o700)
            _fsync_directory(data_root.parent)
        _validate_control_directory(data_root, "deployment state directory")
        _recover_marker_temporaries(marker)
        if not _exists(marker):
            _atomic_write_marker(marker, identity_content)
        _atomic_write_marker(lease_marker, lease_content)
        print(
            f"DEPLOYMENT ACQUIRED network={network_name} deployment={deployment} "
            f"instance={instance} chains={','.join(chain_ids)} lease={lease_marker}"
        )


def _validate_retirement_record(
    document: dict[str, Any],
    expected_fixed: dict[str, Any],
    path: Path,
) -> str:
    expected_fields = set(expected_fixed) | {"status", "updatedAtMillis"}
    if set(document) != expected_fields:
        raise LifecycleError(f"retirement record has an invalid field set: {path}")
    if any(document.get(key) != value for key, value in expected_fixed.items()):
        raise LifecycleError(f"retirement record identity mismatch: {path}")
    status_value = document.get("status")
    if status_value not in {"retiring", "retired"}:
        raise LifecycleError(f"retirement record has an invalid status: {path}")
    updated = document.get("updatedAtMillis")
    if not isinstance(updated, int) or isinstance(updated, bool) or updated < 0:
        raise LifecycleError(f"retirement record has an invalid updatedAtMillis: {path}")
    return status_value


def _validate_reservation_record(
    document: dict[str, Any],
    expected: dict[str, Any],
    path: Path,
) -> None:
    if set(document) != set(expected) or document != expected:
        raise LifecycleError(f"replacement reservation identity mismatch: {path}")


def _ensure_marker(args: argparse.Namespace, expected_kind: str) -> None:
    identity_path = _raw_path(args.identity_file, "identity file")
    _final_component_is_symlink(identity_path, "identity file")
    supplied = _parse_identity(
        _read_regular_file(identity_path, "identity file"),
        "identity file",
        expected_kind,
    )
    canonical = _canonical_json(supplied)
    marker_name = _validate_marker_name(args.marker_name)
    raw_root, root = _prepare_allowed_root(args.allowed_root, create=True)
    guards = _validated_guards(args.guard_absent, raw_root, root)

    with _exclusive_lock(root):
        guards = _validated_guards([str(path) for path in guards], root, root)
        _require_guards_absent(guards)
        state_directory = _validated_target(
            args.directory,
            raw_root,
            root,
            "state directory",
        )
        try:
            state_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        except OSError as error:
            raise LifecycleError(
                f"cannot create state directory {state_directory}: {error.strerror}"
            ) from error
        state_directory = _validated_target(
            str(state_directory),
            root,
            root,
            "state directory",
        )
        if not state_directory.is_dir():
            raise LifecycleError(f"state directory is not a directory: {state_directory}")
        _validate_owned_directory(state_directory, "state directory")

        marker = state_directory / marker_name
        # link(2)-based create-only publication can be interrupted after the
        # marker link exists but before its temporary link is removed. Recover
        # that exact safe state before enforcing the single-link invariant.
        _recover_marker_temporaries(marker)
        if marker.exists() or marker.is_symlink():
            _final_component_is_symlink(marker, "identity marker")
            current_bytes = _read_regular_file(marker, "identity marker", owner_safe=True)
            current = _parse_identity(current_bytes, "identity marker", expected_kind)
            current_canonical = _canonical_json(current)
            if current_bytes != current_canonical:
                raise LifecycleError(f"identity marker is not canonical JSON: {marker}")
            if current_canonical != canonical:
                replace_reset_pending = bool(
                    getattr(args, "replace_factory_reset_pending", False)
                )
                if replace_reset_pending:
                    if state_directory != root / "networks" / "devnet":
                        raise LifecycleError(
                            "factory-reset devnet marker is outside its exact managed directory"
                        )
                    with _exclusive_lock(state_directory):
                        current_bytes = _read_regular_file(
                            marker,
                            "factory-reset network identity marker",
                            owner_safe=True,
                        )
                        current = _parse_identity(
                            current_bytes,
                            "factory-reset network identity marker",
                            NETWORK_KIND,
                        )
                        if (
                            current_bytes != _canonical_json(current)
                            or expected_kind != NETWORK_KIND
                            or marker_name != DEFAULT_NETWORK_MARKER
                            or current != DEVNET_RESET_PENDING_NETWORK_IDENTITY
                            or supplied.get("networkName") != "devnet"
                            or supplied.get("factoryResetPending") is not None
                        ):
                            raise LifecycleError(
                                "network identity is not an exact devnet factory-reset "
                                f"marker: {marker}"
                            )
                        unexpected = {
                            path
                            for path in state_directory.iterdir()
                            if path not in {marker, state_directory / LOCK_NAME}
                        }
                        if unexpected:
                            rendered = ", ".join(str(path) for path in sorted(unexpected))
                            raise LifecycleError(
                                "devnet factory-reset identity cannot be replaced while "
                                f"state exists: {rendered}"
                            )
                        _atomic_replace_marker(marker, canonical)
                    print(
                        f"RESEEDED {expected_kind} {marker} "
                        f"sha256={_identity_digest(canonical)}"
                    )
                    return
                raise LifecycleError(
                    "identity marker mismatch at "
                    f"{marker} (expected sha256={_identity_digest(canonical)}, "
                    f"actual sha256={_identity_digest(current_canonical)})"
                )
            print(f"VALID {expected_kind} {marker} sha256={_identity_digest(canonical)}")
            return

        try:
            entries = list(state_directory.iterdir())
        except OSError as error:
            raise LifecycleError(
                f"cannot inspect state directory {state_directory}: {error.strerror}"
            ) from error
        if entries:
            raise LifecycleError(
                f"refusing to adopt nonempty state without marker {marker}; "
                "move, restore, or explicitly clean that state first"
            )
        _atomic_write_marker(marker, canonical)
        print(f"CREATED {expected_kind} {marker} sha256={_identity_digest(canonical)}")


def _lease(args: argparse.Namespace, operation: str) -> None:
    if operation not in {"validate", "release"}:
        raise LifecycleError(f"unsupported lease operation: {operation}")
    identity_path = _raw_path(args.identity_file, "lease identity file")
    _final_component_is_symlink(identity_path, "lease identity file")
    supplied = _parse_identity(
        _read_regular_file(identity_path, "lease identity file"),
        "lease identity file",
        LEASE_KIND,
    )
    canonical = _canonical_json(supplied)
    marker_name = _validate_marker_name(args.marker_name)
    raw_root, root = _prepare_allowed_root(args.allowed_root, create=False)

    with _exclusive_lock(root):
        state_directory = _validated_target(
            args.directory,
            raw_root,
            root,
            "L1 state directory",
        )
        if not state_directory.exists():
            raise LifecycleError(f"L1 state directory does not exist: {state_directory}")
        _validate_owned_directory(state_directory, "L1 state directory")
        marker = state_directory / marker_name
        _recover_marker_temporaries(marker)

        if not marker.exists() and not marker.is_symlink():
            raise LifecycleError(f"L1 lease does not exist: {marker}")
        _final_component_is_symlink(marker, "L1 lease")
        current_bytes = _read_regular_file(marker, "L1 lease", owner_safe=True)
        current = _parse_identity(current_bytes, "L1 lease", LEASE_KIND)
        current_canonical = _canonical_json(current)
        if current_bytes != current_canonical:
            raise LifecycleError(f"L1 lease is not canonical JSON: {marker}")
        if current_canonical != canonical:
            raise LifecycleError(
                f"L1 lease belongs to another instance: {marker} "
                f"(expected sha256={_identity_digest(canonical)}, "
                f"actual sha256={_identity_digest(current_canonical)})"
            )
        if operation == "validate":
            print(f"VALID {marker} sha256={_identity_digest(canonical)}")
            return
        marker.unlink()
        _fsync_directory(marker.parent)
        print(f"RELEASED {marker} sha256={_identity_digest(canonical)}")


def _reject_overlapping_targets(targets: Sequence[tuple[str, Path]]) -> None:
    for index, (left_name, left_path) in enumerate(targets):
        for right_name, right_path in targets[index + 1 :]:
            if left_path == right_path:
                raise LifecycleError(
                    f"cleanup categories {left_name!r} and {right_name!r} use the same path"
                )
            if _is_relative_to(left_path, right_path) or _is_relative_to(right_path, left_path):
                raise LifecycleError(
                    f"cleanup categories {left_name!r} and {right_name!r} overlap"
                )


def _reject_symlinks_in_tree(
    target: Path,
    allowed_symlinks: frozenset[Path] = frozenset(),
) -> None:
    def walk_error(error: OSError) -> None:
        raise LifecycleError(
            f"cannot inspect cleanup target {error.filename or target}: {error.strerror}"
        ) from error

    target_device = target.stat().st_dev
    if not getattr(shutil.rmtree, "avoids_symlink_attacks", False):
        raise LifecycleError("this Python runtime cannot safely remove directory trees")

    for directory, directory_names, file_names in os.walk(
        target,
        topdown=True,
        onerror=walk_error,
        followlinks=False,
    ):
        base = Path(directory)
        if base != target and (base.stat().st_dev != target_device or os.path.ismount(base)):
            raise LifecycleError(f"cleanup target crosses a mount/device boundary: {base}")
        for name in (*directory_names, *file_names):
            child = base / name
            try:
                if child.is_symlink():
                    if child not in allowed_symlinks:
                        raise LifecycleError(f"cleanup target contains a symlink: {child}")
                    continue
                child_info = child.stat()
                if child_info.st_dev != target_device or (child.is_dir() and os.path.ismount(child)):
                    raise LifecycleError(
                        f"cleanup target crosses a mount/device boundary: {child}"
                    )
            except OSError as error:
                raise LifecycleError(f"cannot inspect cleanup target {child}: {error.strerror}") from error


def _remove_quarantine(path: Path) -> None:
    try:
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
    except OSError as error:
        raise LifecycleError(
            f"cleanup could not remove quarantine {path}: {error.strerror}; "
            "inspect and remove that exact quarantine manually"
        ) from error
    _fsync_directory(path.parent)


def _read_cleanup_plan(path_value: str) -> tuple[dict[str, Any], bytes]:
    """Read the exact external cleanup intent journaled by cleanup-execute.

    DATA_ROOT and L1_ROOT are derived again from network/instance/deployment;
    runtimeRoot must be the deployment child of --runtime-allowed-root. The
    app-chain digest is required except for l1-only deletion. Replacement
    fields are non-null only for the retirement scopes (instance/all).
    """
    document, content = _canonical_supplied_identity(
        path_value,
        "cleanup plan file",
        CLEANUP_PLAN_KIND,
    )
    if set(document) != CLEANUP_PLAN_FIELDS:
        missing = sorted(CLEANUP_PLAN_FIELDS - set(document))
        unexpected = sorted(set(document) - CLEANUP_PLAN_FIELDS)
        raise LifecycleError(
            "cleanup plan has an invalid field set "
            f"(missing={missing}, unexpected={unexpected})"
        )
    for field, pattern in (
        ("networkName", NETWORK_NAME),
        ("instanceId", INSTANCE_NAME),
        ("deployment", DEPLOYMENT_NAME),
        ("chainId", CHAIN_ID),
        ("scope", CLEANUP_SCOPE),
    ):
        value = document.get(field)
        if not isinstance(value, str) or not pattern.fullmatch(value):
            raise LifecycleError(f"cleanup plan field {field!r} is invalid")
    for field in ("dataRoot", "l1Root", "runtimeRoot"):
        value = document.get(field)
        if not isinstance(value, str) or value != str(_raw_path(value, f"cleanup plan {field}")):
            raise LifecycleError(f"cleanup plan field {field!r} must be a canonical absolute path")

    scope = document["scope"]
    digest = document.get("appchainIdentitySha256")
    if scope == "l1":
        if digest is not None:
            raise LifecycleError("an l1-only cleanup plan must use null appchainIdentitySha256")
    elif not isinstance(digest, str) or not SHA256_HEX.fullmatch(digest):
        raise LifecycleError("cleanup plan requires a valid appchainIdentitySha256")

    replacement_instance = document.get("replacementInstanceId")
    replacement_chain = document.get("replacementChainId")
    if scope in {"instance", "all"}:
        if (
            not isinstance(replacement_instance, str)
            or not INSTANCE_NAME.fullmatch(replacement_instance)
            or replacement_instance == document["instanceId"]
        ):
            raise LifecycleError("retiring cleanup requires a distinct replacementInstanceId")
        if (
            not isinstance(replacement_chain, str)
            or not CHAIN_ID.fullmatch(replacement_chain)
            or replacement_chain == document["chainId"]
        ):
            raise LifecycleError("retiring cleanup requires a distinct replacementChainId")
    elif replacement_instance is not None or replacement_chain is not None:
        raise LifecycleError("replacement fields are only valid for instance/all cleanup")
    return document, content


@dataclass(frozen=True)
class CleanupTarget:
    category: str
    allowed_root: Path
    allowed_root_initially_present: bool
    target: Path
    quarantine: Path


@dataclass(frozen=True)
class ManagedHostLink:
    path: Path
    target: Path


def _cleanup_coordinates(
    plan: dict[str, Any],
    root: Path,
    runtime_root: Path,
) -> tuple[Path, Path, Path]:
    instance = plan["instanceId"]
    deployment = plan["deployment"]
    data_root = Path(plan["dataRoot"])
    l1_root = Path(plan["l1Root"])
    selected_runtime = Path(plan["runtimeRoot"])
    expected_data = root / "instances" / instance / deployment
    expected_l1 = root / "l1" / deployment
    expected_runtime = runtime_root / deployment
    if data_root != expected_data:
        raise LifecycleError(f"cleanup DATA_ROOT must be exactly {expected_data}")
    if l1_root != expected_l1:
        raise LifecycleError(f"cleanup L1_ROOT must be exactly {expected_l1}")
    if selected_runtime != expected_runtime:
        raise LifecycleError(f"cleanup runtimeRoot must be exactly {expected_runtime}")
    return data_root, l1_root, selected_runtime


def _target_specs(
    plan: dict[str, Any],
    root: Path,
    runtime_allowed_root: Path,
    transaction_id: str,
    runtime_root_initially_present: bool,
) -> tuple[list[CleanupTarget], list[ManagedHostLink]]:
    data_root, l1_root, selected_runtime = _cleanup_coordinates(
        plan,
        root,
        runtime_allowed_root,
    )
    scope = plan["scope"]
    raw_targets: list[tuple[str, Path, bool, Path]]
    if scope == "observability":
        raw_targets = [("observability", root, True, data_root / "observability")]
    elif scope == "reports":
        raw_targets = [("reports", root, True, data_root / "reports")]
    elif scope == "runtime":
        raw_targets = [
            ("runtime", runtime_allowed_root, runtime_root_initially_present, selected_runtime)
        ]
    elif scope == "instance":
        raw_targets = [
            ("runtime", runtime_allowed_root, runtime_root_initially_present, selected_runtime),
            ("instance", root, True, data_root),
        ]
    elif scope == "l1":
        raw_targets = [("l1", root, True, l1_root)]
    else:
        raw_targets = [
            ("runtime", runtime_allowed_root, runtime_root_initially_present, selected_runtime),
            ("instance", root, True, data_root),
            ("l1", root, True, l1_root),
        ]

    targets: list[CleanupTarget] = []
    for category, allowed, allowed_present, target in raw_targets:
        quarantine = target.parent / f".{target.name}.yano-quarantine.{transaction_id}"
        targets.append(CleanupTarget(category, allowed, allowed_present, target, quarantine))
    _reject_overlapping_targets([(target.category, target.target) for target in targets])

    links: list[ManagedHostLink] = []
    if plan["deployment"] == "host" and scope in {"instance", "l1", "all"}:
        for index in range(3):
            links.append(
                ManagedHostLink(
                    l1_root / "host-cluster" / f"node{index}" / "chainstate" / "app-chain",
                    data_root / "app-chain" / f"node{index}",
                )
            )
    return targets, links


def _cleanup_transaction_document(
    plan: dict[str, Any],
    plan_digest: str,
    transaction_id: str,
    targets: Sequence[CleanupTarget],
    links: Sequence[ManagedHostLink],
    runtime_root_initially_present: bool,
) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": CLEANUP_TRANSACTION_KIND,
        "transactionId": transaction_id,
        "planSha256": plan_digest,
        "runtimeAllowedRootInitiallyPresent": runtime_root_initially_present,
        "plan": plan,
        "targets": [
            {
                "category": target.category,
                "allowedRoot": str(target.allowed_root),
                "allowedRootInitiallyPresent": target.allowed_root_initially_present,
                "target": str(target.target),
                "quarantine": str(target.quarantine),
            }
            for target in targets
        ],
        "hostLinks": [
            {"path": str(link.path), "target": str(link.target)}
            for link in links
        ],
    }


def _read_or_publish_cleanup_transaction(
    root: Path,
    runtime_allowed_root: Path,
    plan: dict[str, Any],
    plan_digest: str,
    runtime_root_initially_present: bool,
    new_transaction_id: Optional[str] = None,
) -> tuple[Path, list[CleanupTarget], list[ManagedHostLink]]:
    marker = root / CENTRAL_CLEANUP_MARKER
    _recover_marker_temporaries(marker)
    if _exists(marker):
        current = _read_canonical_record(
            marker,
            "central cleanup transaction",
            CLEANUP_TRANSACTION_KIND,
        )
        transaction_id = current.get("transactionId")
        if not isinstance(transaction_id, str) or not CLEANUP_TRANSACTION_ID.fullmatch(transaction_id):
            raise LifecycleError(f"central cleanup transaction has an invalid id: {marker}")
        recorded_runtime_state = current.get("runtimeAllowedRootInitiallyPresent")
        if not isinstance(recorded_runtime_state, bool):
            raise LifecycleError(f"central cleanup transaction has invalid runtime-root state: {marker}")
        targets, links = _target_specs(
            plan,
            root,
            runtime_allowed_root,
            transaction_id,
            recorded_runtime_state,
        )
        expected = _cleanup_transaction_document(
            plan,
            plan_digest,
            transaction_id,
            targets,
            links,
            recorded_runtime_state,
        )
        if current != expected:
            raise LifecycleError(
                f"another or malformed cleanup plan is already published: {marker}"
            )
        return marker, targets, links

    artifacts = _walk_lifecycle_artifacts(root) + _walk_lifecycle_artifacts(runtime_allowed_root)
    if artifacts:
        rendered = ", ".join(str(path) for path in sorted(set(artifacts)))
        raise LifecycleError(f"orphan cleanup journal or quarantine requires inspection: {rendered}")
    transaction_id = new_transaction_id or secrets.token_hex(16)
    targets, links = _target_specs(
        plan,
        root,
        runtime_allowed_root,
        transaction_id,
        runtime_root_initially_present,
    )
    document = _cleanup_transaction_document(
        plan,
        plan_digest,
        transaction_id,
        targets,
        links,
        runtime_root_initially_present,
    )
    _atomic_write_marker(marker, _canonical_json(document))
    print(f"CLEANUP TRANSACTION {transaction_id} planSha256={plan_digest} marker={marker}")
    _test_stop("transaction")
    return marker, targets, links


def _validate_known_cleanup_artifacts(
    root: Path,
    runtime_allowed_root: Path,
    targets: Sequence[CleanupTarget],
) -> None:
    expected = {target.quarantine for target in targets}
    actual = set(_walk_lifecycle_artifacts(root) + _walk_lifecycle_artifacts(runtime_allowed_root))
    unexpected = actual - expected
    if unexpected:
        rendered = ", ".join(str(path) for path in sorted(unexpected))
        raise LifecycleError(f"unknown cleanup quarantine or journal requires inspection: {rendered}")


def _prevalidate_cleanup_targets(
    targets: Sequence[CleanupTarget],
    links: Sequence[ManagedHostLink] = (),
) -> None:
    allowed_symlinks = frozenset(link.path for link in links)
    for item in targets:
        if not item.allowed_root_initially_present:
            if _exists(item.allowed_root) or _exists(item.target) or _exists(item.quarantine):
                raise LifecycleError(
                    "cleanup runtime root was absent when the transaction was published "
                    f"but has since appeared: {item.allowed_root}"
                )
            continue
        target_exists = _exists(item.target)
        quarantine_exists = _exists(item.quarantine)
        if target_exists and quarantine_exists:
            raise LifecycleError(
                f"cleanup target and quarantine both exist for {item.category}: "
                f"{item.target}, {item.quarantine}"
            )
        candidate = item.target if target_exists else item.quarantine if quarantine_exists else None
        if candidate is None:
            continue
        if candidate.is_symlink() or not candidate.is_dir():
            raise LifecycleError(f"cleanup target is not a real directory: {candidate}")
        _validate_owned_directory(candidate, f"cleanup {item.category} directory")
        _reject_symlinks_in_tree(candidate, allowed_symlinks)


def _prevalidate_host_links(links: Sequence[ManagedHostLink]) -> None:
    for link in links:
        l1_root = link.path.parents[3]
        _reject_symlink_components(
            l1_root,
            link.path.relative_to(l1_root).parts[:-1],
            "managed host-link parent",
        )
        if not _exists(link.path):
            continue
        try:
            info = link.path.lstat()
        except OSError as error:
            raise LifecycleError(f"cannot inspect managed host link {link.path}: {error.strerror}") from error
        if not stat.S_ISLNK(info.st_mode):
            raise LifecycleError(f"managed host link is not a symlink: {link.path}")
        if hasattr(os, "geteuid") and info.st_uid != os.geteuid():
            raise LifecycleError(f"managed host link is not owned by the current user: {link.path}")
        if Path(os.path.realpath(link.path)) != link.target.resolve(strict=False):
            raise LifecycleError(
                f"managed host link points outside its exact app-chain target: {link.path}"
            )


def _require_cleanup_lease_absent(
    l1_root: Path,
    targets: Sequence[CleanupTarget],
) -> None:
    locations = [l1_root]
    locations.extend(
        target.quarantine for target in targets if target.category == "l1"
    )
    for location in locations:
        if not _exists(location):
            continue
        if location.is_symlink() or not location.is_dir():
            raise LifecycleError(f"L1 lease container is not a real directory: {location}")
        lease = location / DEFAULT_LEASE_MARKER
        _recover_marker_temporaries(lease)
        if _exists(lease):
            # Read it strictly so a malformed/symlinked/hardlinked guard is
            # never interpreted as an absent lease.
            _read_canonical_record(lease, "L1 lease", LEASE_KIND)
            raise LifecycleError(f"L1 state is actively leased; stop it before cleanup: {lease}")


def _remove_managed_host_links(links: Sequence[ManagedHostLink]) -> None:
    for link in links:
        if not _exists(link.path):
            continue
        # All links were validated before the first mutation. Revalidate each
        # immediately before unlinking to fail closed against replacement.
        _prevalidate_host_links([link])
        try:
            link.path.unlink()
            _fsync_directory(link.path.parent)
        except OSError as error:
            raise LifecycleError(f"cannot remove managed host link {link.path}: {error.strerror}") from error
        print(f"UNLINK host-appchain {link.path}")


def _cleanup_retirement_plan(plan: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": RETIREMENT_PLAN_KIND,
        "networkName": plan["networkName"],
        "instanceId": plan["instanceId"],
        "deployment": plan["deployment"],
        "chainId": plan["chainId"],
        "appchainIdentitySha256": plan["appchainIdentitySha256"],
        "replacementInstanceId": plan["replacementInstanceId"],
        "replacementChainId": plan["replacementChainId"],
    }


def _validate_cleanup_retirement_request(
    plan: dict[str, Any],
    snapshot: ClaimSnapshot,
) -> None:
    retirement_plan = _cleanup_retirement_plan(plan)
    retirement_fixed = _retirement_fixed(retirement_plan)
    old_pair = (plan["deployment"], plan["instanceId"])
    replacement_pair = (plan["deployment"], plan["replacementInstanceId"])
    current = snapshot.retirement_by_old.get(old_pair)
    if current is not None:
        _validate_retirement_record(current.document, retirement_fixed, current.path)
        return
    active = snapshot.active_by_pair.get(old_pair)
    if active is None:
        raise LifecycleError("retiring cleanup requires the exact active app-chain marker")
    if (
        active.digest != plan["appchainIdentitySha256"]
        or active.chain_ids != (plan["chainId"],)
    ):
        raise LifecycleError("retiring cleanup identity does not match active app-chain state")
    if replacement_pair in snapshot.active_by_pair:
        raise LifecycleError("replacement instance already has active state")
    if (
        replacement_pair in snapshot.retirement_by_old
        or replacement_pair in snapshot.retirement_by_replacement
        or replacement_pair in snapshot.reservation_by_replacement
    ):
        raise LifecycleError("replacement instance is already claimed by another lifecycle record")
    claimed_pair = snapshot.chain_pairs.get(plan["replacementChainId"])
    if claimed_pair is not None:
        raise LifecycleError(f"replacement chain id is permanently claimed by {claimed_pair}")


def _begin_cleanup_retirement(
    root: Path,
    plan: dict[str, Any],
    snapshot: ClaimSnapshot,
) -> tuple[Path, Path]:
    retirement_plan = _cleanup_retirement_plan(plan)
    old_pair = (plan["deployment"], plan["instanceId"])
    replacement_pair = (plan["deployment"], plan["replacementInstanceId"])
    retirement = root / "retired" / plan["deployment"] / f"{plan['instanceId']}.json"
    reservation = (
        root / "reservations" / plan["deployment"] / f"{plan['replacementInstanceId']}.json"
    )
    retirement_fixed = _retirement_fixed(retirement_plan)
    reservation_fixed = _reservation_fixed(retirement_plan)
    _ensure_owned_parent(root, retirement, "retirement record")
    _ensure_owned_parent(root, reservation, "replacement reservation")
    _recover_marker_temporaries(retirement)
    _recover_marker_temporaries(reservation)

    current = snapshot.retirement_by_old.get(old_pair)
    _validate_cleanup_retirement_request(plan, snapshot)
    if current is None:
        document = {
            **retirement_fixed,
            "status": "retiring",
            "updatedAtMillis": time.time_ns() // 1_000_000,
        }
        _atomic_write_marker(retirement, _canonical_json(document))
        _test_stop("retirement-record")
    else:
        _validate_retirement_record(current.document, retirement_fixed, retirement)

    if _exists(reservation):
        reservation_document = _read_canonical_record(
            reservation,
            "replacement reservation",
            RESET_RESERVATION_KIND,
        )
        _validate_reservation_record(reservation_document, reservation_fixed, reservation)
    else:
        _atomic_write_marker(reservation, _canonical_json(reservation_fixed))
    print(f"RETIREMENT FENCED record={retirement} reservation={reservation}")
    _test_stop("retirement")
    return retirement, reservation


def _complete_cleanup_retirement(
    retirement: Path,
    reservation: Path,
    plan: dict[str, Any],
) -> None:
    retirement_plan = _cleanup_retirement_plan(plan)
    retirement_fixed = _retirement_fixed(retirement_plan)
    reservation_fixed = _reservation_fixed(retirement_plan)
    current = _read_canonical_record(retirement, "retirement record", RETIRED_INSTANCE_KIND)
    status_value = _validate_retirement_record(current, retirement_fixed, retirement)
    reservation_document = _read_canonical_record(
        reservation,
        "replacement reservation",
        RESET_RESERVATION_KIND,
    )
    _validate_reservation_record(reservation_document, reservation_fixed, reservation)
    if status_value != "retired":
        completed = {
            **retirement_fixed,
            "status": "retired",
            "updatedAtMillis": time.time_ns() // 1_000_000,
        }
        _atomic_replace_marker(retirement, _canonical_json(completed))
    print(f"RETIREMENT COMPLETE record={retirement}")
    _test_stop("retirement-complete")


def _logical_cleanup_overrides(targets: Sequence[CleanupTarget]) -> dict[Path, Path]:
    return {
        target.target: target.quarantine
        for target in targets
        if target.category == "instance" and _exists(target.quarantine)
    }


def _verify_l1_attachments(
    snapshot: ClaimSnapshot,
    plan: dict[str, Any],
) -> None:
    if plan["scope"] not in {"l1", "all"}:
        return
    deployment = plan["deployment"]
    selected = (deployment, plan["instanceId"])
    attached = {pair for pair in snapshot.active_by_pair if pair[0] == deployment}
    attached.update(
        pair
        for pair, claim in snapshot.retirement_by_old.items()
        if pair[0] == deployment and claim.document["status"] == "retiring"
    )
    allowed = {selected} if plan["scope"] == "all" else set()
    unexpected = attached - allowed
    if unexpected or not attached.issubset(allowed):
        raise LifecycleError(
            "shared L1 state is still attached; retained app-chain attachments: "
            f"{sorted(attached)}"
        )


def _validate_cleanup_snapshot(plan: dict[str, Any], snapshot: ClaimSnapshot) -> None:
    pair = (plan["deployment"], plan["instanceId"])
    if plan["scope"] in {"instance", "all"}:
        _validate_cleanup_retirement_request(plan, snapshot)
    elif plan["scope"] != "l1":
        active = snapshot.active_by_pair.get(pair)
        if (
            active is None
            or active.digest != plan["appchainIdentitySha256"]
            or active.chain_ids != (plan["chainId"],)
        ):
            raise LifecycleError("cleanup plan does not match the exact active app-chain identity")
    _verify_l1_attachments(snapshot, plan)


def _execute_central_cleanup_target(target: CleanupTarget) -> None:
    if not target.allowed_root_initially_present:
        if _exists(target.allowed_root) or _exists(target.target) or _exists(target.quarantine):
            raise LifecycleError(
                f"absent cleanup root appeared during execution: {target.allowed_root}"
            )
        print(f"SKIP {target.category} {target.target} (allowed root was absent)")
        return
    if _exists(target.target):
        try:
            os.rename(target.target, target.quarantine)
            _fsync_directory(target.target.parent)
        except OSError as error:
            raise LifecycleError(
                f"cannot quarantine cleanup target {target.target}: {error.strerror}"
            ) from error
        print(f"QUARANTINE {target.category} {target.target} -> {target.quarantine}")
        _test_stop(f"quarantine:{target.category}")
    if _exists(target.quarantine):
        _reject_symlinks_in_tree(target.quarantine)
        _remove_quarantine(target.quarantine)
        print(f"DELETED {target.category} {target.quarantine}")
        _remove_empty_instance_parent(target)
        _test_stop(f"delete:{target.category}")
    else:
        print(f"SKIP {target.category} {target.target} (missing or already deleted)")
        _remove_empty_instance_parent(target)


def _remove_empty_instance_parent(target: CleanupTarget) -> None:
    if target.category != "instance":
        return
    instance_parent = target.target.parent
    try:
        if _exists(instance_parent) and not any(instance_parent.iterdir()):
            instance_parent.rmdir()
            _fsync_directory(instance_parent.parent)
    except OSError as error:
        raise LifecycleError(
            f"cannot retire empty instance control directory {instance_parent}: {error.strerror}"
        ) from error


def _test_stop(phase: str) -> None:
    if os.environ.get("YANO_LIFECYCLE_TEST_STOP_AFTER") == phase:
        raise LifecycleError(f"test-requested stop after durable phase {phase}")


def _cleanup_execute(args: argparse.Namespace) -> None:
    if not args.yes:
        raise LifecycleError("cleanup-execute requires explicit --yes; nothing was changed")
    plan, plan_content = _read_cleanup_plan(args.plan_file)
    raw_root, root = _prepare_allowed_root(args.network_root, create=False)
    raw_runtime, runtime_allowed_root, runtime_root_present = _prepare_optional_allowed_root(
        args.runtime_allowed_root
    )
    if (
        _is_relative_to(runtime_allowed_root, root)
        or _is_relative_to(root, runtime_allowed_root)
    ):
        raise LifecycleError("network and runtime allowed roots must be disjoint")
    # Canonicalize and constrain all caller-controlled paths before locking.
    data_root = _validated_target(plan["dataRoot"], raw_root, root, "cleanup DATA_ROOT")
    l1_root = _validated_target(plan["l1Root"], raw_root, root, "cleanup L1_ROOT")
    if runtime_root_present:
        selected_runtime = _validated_target(
            plan["runtimeRoot"],
            raw_runtime,
            runtime_allowed_root,
            "cleanup runtimeRoot",
        )
    else:
        selected_runtime = _raw_path(plan["runtimeRoot"], "cleanup runtimeRoot")
        if selected_runtime != runtime_allowed_root / plan["deployment"]:
            raise LifecycleError(
                "cleanup runtimeRoot must be the exact deployment child of its absent allowed root"
            )
    if (str(data_root), str(l1_root), str(selected_runtime)) != (
        plan["dataRoot"],
        plan["l1Root"],
        plan["runtimeRoot"],
    ):
        raise LifecycleError("cleanup plan paths must resolve to their exact canonical locations")
    _cleanup_coordinates(plan, root, runtime_allowed_root)
    plan_digest = _identity_digest(plan_content)

    with _exclusive_lock(root):
        _validate_network_root_identity(root, plan["networkName"])
        central = root / CENTRAL_CLEANUP_MARKER
        _recover_marker_temporaries(central)
        transaction_exists = _exists(central)
        if transaction_exists:
            marker, targets, links = _read_or_publish_cleanup_transaction(
                root,
                runtime_allowed_root,
                plan,
                plan_digest,
                runtime_root_present,
            )
        else:
            # Every non-mutating check is completed while holding the network
            # lock before the global transaction is published. A bad lease,
            # attachment, link, identity, target, or lineage therefore cannot
            # strand a fence that blocks a healthy running deployment.
            transaction_id = secrets.token_hex(16)
            targets, links = _target_specs(
                plan,
                root,
                runtime_allowed_root,
                transaction_id,
                runtime_root_present,
            )
            initial_artifacts = set(
                _walk_lifecycle_artifacts(root)
                + _walk_lifecycle_artifacts(runtime_allowed_root)
            )
            if initial_artifacts:
                rendered = ", ".join(str(path) for path in sorted(initial_artifacts))
                raise LifecycleError(
                    f"orphan cleanup journal or quarantine requires inspection: {rendered}"
                )
            _prevalidate_host_links(links)
            _prevalidate_cleanup_targets(targets, links)
            _require_cleanup_lease_absent(Path(plan["l1Root"]), targets)
            pair = (plan["deployment"], plan["instanceId"])
            snapshot = _claim_snapshot(
                root,
                plan["networkName"],
                candidate=(pair, Path(plan["dataRoot"]))
                if plan["scope"] in {"instance", "all"}
                else None,
            )
            _validate_cleanup_snapshot(plan, snapshot)
            marker, targets, links = _read_or_publish_cleanup_transaction(
                root,
                runtime_allowed_root,
                plan,
                plan_digest,
                runtime_root_present,
                new_transaction_id=transaction_id,
            )

        _validate_known_cleanup_artifacts(root, runtime_allowed_root, targets)
        _prevalidate_host_links(links)
        _prevalidate_cleanup_targets(targets, links)
        _require_cleanup_lease_absent(Path(plan["l1Root"]), targets)

        pair = (plan["deployment"], plan["instanceId"])
        replacement_pair = (
            (plan["deployment"], plan["replacementInstanceId"])
            if plan["scope"] in {"instance", "all"}
            else None
        )
        overrides = _logical_cleanup_overrides(targets)
        snapshot = _claim_snapshot(
            root,
            plan["networkName"],
            candidate=(pair, Path(plan["dataRoot"]))
            if plan["scope"] in {"instance", "all"}
            else None,
            logical_overrides=overrides,
            recoverable_missing_reservation=replacement_pair,
        )
        _validate_cleanup_snapshot(plan, snapshot)

        retirement: Optional[Path] = None
        reservation: Optional[Path] = None
        if plan["scope"] in {"instance", "all"}:
            retirement, reservation = _begin_cleanup_retirement(root, plan, snapshot)
            # Re-snapshot after publishing/recovering both lifecycle records.
            snapshot = _claim_snapshot(
                root,
                plan["networkName"],
                candidate=(pair, Path(plan["dataRoot"])),
                logical_overrides=overrides,
            )
        _verify_l1_attachments(snapshot, plan)
        _remove_managed_host_links(links)
        for target in targets:
            _execute_central_cleanup_target(target)

        if retirement is not None and reservation is not None:
            # Completion is deliberately after every requested deletion. This
            # makes a durable `retired` record proof that the reset boundary
            # was crossed in full, not merely started.
            _complete_cleanup_retirement(retirement, reservation, plan)

        try:
            marker.unlink()
            _fsync_directory(marker.parent)
        except OSError as error:
            raise LifecycleError(
                f"cleanup completed but central transaction could not be removed {marker}: "
                f"{error.strerror}"
            ) from error
        print(f"CLEANUP COMPLETE planSha256={plan_digest}")


def _devnet_reset_targets(
    root: Path,
    runtime_base: Path,
    runtime_base_present: bool,
) -> list[CleanupTarget]:
    targets = [
        CleanupTarget(
            f"devnet-{name}",
            root,
            True,
            root / name,
            root / f".{name}.yano-devnet-reset-quarantine",
        )
        for name in ("instances", "l1", "retired", "reservations")
    ]
    runtime_target = runtime_base / "networks" / "devnet"
    targets.append(
        CleanupTarget(
            "devnet-runtime",
            runtime_base,
            runtime_base_present,
            runtime_target,
            runtime_target.parent / ".devnet.yano-devnet-reset-quarantine",
        )
    )
    _reject_overlapping_targets([(target.category, target.target) for target in targets])
    return targets


def _validate_devnet_reset_lease(root: Path, compose_stopped: bool) -> None:
    l1_root = root / "l1"
    expected = {
        l1_root / "compose" / DEFAULT_LEASE_MARKER,
        l1_root / "host" / DEFAULT_LEASE_MARKER,
    }
    if l1_root.exists():
        discovered = {
            path for path in l1_root.glob(f"*/{DEFAULT_LEASE_MARKER}")
            if path.exists() or path.is_symlink()
        }
        unexpected = discovered - expected
        if unexpected:
            rendered = ", ".join(str(path) for path in sorted(unexpected))
            raise LifecycleError(f"unexpected devnet L1 lease location: {rendered}")

    host_lease = l1_root / "host" / DEFAULT_LEASE_MARKER
    if host_lease.exists() or host_lease.is_symlink():
        raise LifecycleError(
            "host devnet is active or uncertain; stop the host deployment before reset-devnet"
        )

    compose_lease = l1_root / "compose" / DEFAULT_LEASE_MARKER
    if not compose_lease.exists() and not compose_lease.is_symlink():
        return
    if not compose_stopped:
        raise LifecycleError("Compose devnet lease remains without proven Docker shutdown")
    _final_component_is_symlink(compose_lease, "Compose devnet lease")
    raw = _read_regular_file(compose_lease, "Compose devnet lease", owner_safe=True)
    document = _parse_identity(raw, "Compose devnet lease", LEASE_KIND)
    canonical = _canonical_json(document)
    if raw != canonical:
        raise LifecycleError(f"Compose devnet lease is not canonical JSON: {compose_lease}")
    _validate_lease_identity_shape(document, "Compose devnet lease")
    if document.get("networkName") != "devnet" or document.get("deployment") != "compose":
        raise LifecycleError(f"Compose devnet lease identity is inconsistent: {compose_lease}")


def _reject_active_host_devnet_artifacts(root: Path, runtime_base: Path) -> None:
    host_cluster = root / "l1" / "host" / "host-cluster"
    patterns = (
        "node*.pid",
        "node*.pid.meta",
        "node*.launch",
        "node*.pid.tmp.*",
        "node*.pid.meta.tmp.*",
    )
    active: set[Path] = set()
    if host_cluster.exists():
        for pattern in patterns:
            active.update(host_cluster.glob(pattern))

    runtime_network = runtime_base / "networks" / "devnet"
    if runtime_network.exists():
        for instance_root in runtime_network.iterdir():
            host_runtime = instance_root / "host"
            for name in (
                "host-ui.process.json",
                ".host-ui.launch.json",
                ".host-ui.process.tmp",
                ".host-ui.process-update.tmp",
                ".host-ui.launch.tmp",
            ):
                path = host_runtime / name
                if path.exists() or path.is_symlink():
                    active.add(path)
    if active:
        rendered = ", ".join(str(path) for path in sorted(active))
        raise LifecycleError(
            "host devnet process state is active or uncertain; stop it before reset-devnet: "
            f"{rendered}"
        )


def _devnet_reset_document(targets: Sequence[CleanupTarget]) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": DEVNET_RESET_KIND,
        "networkName": "devnet",
        "targets": [
            {
                "category": target.category,
                "target": str(target.target),
                "quarantine": str(target.quarantine),
            }
            for target in targets
        ],
    }


def _reset_devnet(args: argparse.Namespace) -> None:
    if not args.yes:
        raise LifecycleError("reset-devnet requires explicit --yes; nothing was changed")
    if not args.compose_stopped:
        raise LifecycleError("reset-devnet requires proven Compose shutdown")

    raw_data, data_base = _prepare_allowed_root(args.data_base, create=False)
    _, runtime_base, runtime_base_present = _prepare_optional_allowed_root(
        args.runtime_base
    )
    if _is_relative_to(runtime_base, data_base) or _is_relative_to(data_base, runtime_base):
        raise LifecycleError("devnet data and runtime roots must be disjoint")

    raw_network = raw_data / "networks" / "devnet"
    if not raw_network.exists() and not raw_network.is_symlink():
        print("DEVNET RESET SKIP: no retained devnet data exists")
        return
    _, root = _prepare_allowed_root(str(raw_network), create=False)
    expected_root = data_base / "networks" / "devnet"
    if root != expected_root:
        raise LifecycleError(f"devnet network root must be exactly {expected_root}")

    targets = _devnet_reset_targets(root, runtime_base, runtime_base_present)
    marker = root / DEVNET_RESET_MARKER
    expected_document = _devnet_reset_document(targets)
    expected_content = _canonical_json(expected_document)

    with _exclusive_lock(root):
        network_marker = root / DEFAULT_NETWORK_MARKER
        _validate_network_root_identity(root, "devnet")
        _reject_active_cleanup(root)
        _validate_devnet_reset_lease(root, args.compose_stopped)
        _reject_active_host_devnet_artifacts(root, runtime_base)

        allowed_entries = {
            root / DEFAULT_NETWORK_MARKER,
            root / LOCK_NAME,
            marker,
            *(target.target for target in targets if target.allowed_root == root),
            *(target.quarantine for target in targets if target.allowed_root == root),
        }
        unexpected = {
            path for path in root.iterdir()
            if path not in allowed_entries
        }
        if unexpected:
            rendered = ", ".join(str(path) for path in sorted(unexpected))
            raise LifecycleError(f"unknown devnet state blocks factory reset: {rendered}")

        if marker.exists() or marker.is_symlink():
            current = _read_canonical_record(
                marker,
                "devnet reset transaction",
                DEVNET_RESET_KIND,
            )
            if current != expected_document:
                raise LifecycleError(f"devnet reset transaction does not match: {marker}")
        else:
            _prevalidate_cleanup_targets(targets, [])
            _atomic_write_marker(marker, expected_content)
            print(f"DEVNET RESET START marker={marker}")

        _prevalidate_cleanup_targets(targets, [])
        for target in targets:
            _execute_central_cleanup_target(target)

        _atomic_replace_marker(
            network_marker,
            _canonical_json(DEVNET_RESET_PENDING_NETWORK_IDENTITY),
        )
        _test_stop("network-identity-reset")
        try:
            marker.unlink()
            _fsync_directory(marker.parent)
        except OSError as error:
            raise LifecycleError(
                f"devnet reset completed but transaction marker could not be removed {marker}: "
                f"{error.strerror}"
            ) from error
        print("DEVNET RESET COMPLETE network=devnet secrets=preserved identity=regenerate")


def _add_identity_arguments(
    parser: argparse.ArgumentParser,
    default_marker: str,
) -> None:
    parser.add_argument("--allowed-root", required=True, help="validated demo-data root")
    parser.add_argument("--directory", required=True, help="exact state directory for the marker")
    parser.add_argument(
        "--identity-file",
        required=True,
        help="UTF-8 JSON identity document (values are never accepted in argv)",
    )
    parser.add_argument(
        "--marker-name",
        default=default_marker,
        help=f"marker basename (default: {default_marker})",
    )
    parser.add_argument(
        "--guard-absent",
        action="append",
        default=[],
        help="exact state path that must remain absent under the lifecycle lock",
    )


_INTERNAL_OPERATION_COMMANDS = frozenset(
    {"operation-run", "operation-validate", "operation-watch"}
)


def _parser(include_internal: bool = False) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Guard Yano demo state identities and perform exact, safe cleanup.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    if include_internal:
        operation_run = subparsers.add_parser("operation-run")
        operation_run.add_argument("--data-root", required=True, help="validated demo data root")
        operation_run.add_argument("--network", required=True, help="network lock namespace")
        operation_run.add_argument("--deployment", required=True, help="deployment lock namespace")
        operation_run.add_argument("command", nargs=argparse.REMAINDER)
        operation_run.set_defaults(handler=_operation_run)

        operation_validate = subparsers.add_parser("operation-validate")
        operation_validate.add_argument("--data-root", required=True, help="validated demo data root")
        operation_validate.add_argument("--network", required=True, help="network lock namespace")
        operation_validate.add_argument("--deployment", required=True, help="deployment lock namespace")
        operation_validate.add_argument("--fd", required=True, help="inherited lock file descriptor")
        operation_validate.set_defaults(handler=_operation_validate)

        operation_watch = subparsers.add_parser("operation-watch")
        operation_watch.add_argument("--data-root", required=True)
        operation_watch.add_argument("--network", required=True)
        operation_watch.add_argument("--deployment", required=True)
        operation_watch.add_argument("--fd", required=True)
        operation_watch.add_argument("--lock-fd", required=True)
        operation_watch.add_argument("--ready-fd", required=True)
        operation_watch.add_argument("--unused-ready-read-fd", required=True)
        operation_watch.add_argument("--parent-pid", required=True)
        operation_watch.set_defaults(handler=_operation_watch)

    network = subparsers.add_parser(
        "ensure-network",
        help="atomically create or validate a network identity marker",
    )
    _add_identity_arguments(network, DEFAULT_NETWORK_MARKER)
    network.add_argument(
        "--replace-factory-reset-pending",
        action="store_true",
        help="replace only the exact empty devnet factory-reset marker",
    )
    network.set_defaults(handler=lambda args: _ensure_marker(args, NETWORK_KIND))

    acquire = subparsers.add_parser(
        "deployment-acquire",
        help="atomically claim an app-chain identity and acquire its non-reentrant L1 lease",
    )
    acquire.add_argument("--network-root", required=True, help="exact retained network root")
    acquire.add_argument("--data-root", required=True, help="exact instance/deployment DATA_ROOT")
    acquire.add_argument("--l1-root", required=True, help="exact deployment L1_ROOT")
    acquire.add_argument(
        "--identity-file",
        required=True,
        help="canonical app-chain identity document",
    )
    acquire.add_argument(
        "--lease-identity-file",
        required=True,
        help="canonical lease identity bound to the app-chain identity digest",
    )
    acquire.set_defaults(handler=_deployment_acquire)

    for command, operation, help_text in (
        ("lease-validate", "validate", "validate ownership of a shared L1 lease"),
        ("lease-release", "release", "release an exactly matching shared L1 lease"),
    ):
        lease = subparsers.add_parser(command, help=help_text)
        _add_identity_arguments(lease, DEFAULT_LEASE_MARKER)
        lease.set_defaults(handler=lambda args, op=operation: _lease(args, op))

    cleanup_execute = subparsers.add_parser(
        "cleanup-execute",
        help="execute or resume one exact network-locked cleanup plan",
    )
    cleanup_execute.add_argument(
        "--network-root",
        required=True,
        help="exact retained network root and lifecycle lock authority",
    )
    cleanup_execute.add_argument(
        "--runtime-allowed-root",
        required=True,
        help="exact per-network/instance runtime parent containing the deployment runtime root",
    )
    cleanup_execute.add_argument(
        "--plan-file",
        required=True,
        help="canonical yano.demo.cleanup-plan document",
    )
    cleanup_execute.add_argument(
        "--yes",
        action="store_true",
        help="required explicit acknowledgement for deletion",
    )
    cleanup_execute.set_defaults(handler=_cleanup_execute)

    reset_devnet = subparsers.add_parser(
        "reset-devnet",
        help="factory-reset all retained local devnet demo state",
    )
    reset_devnet.add_argument(
        "--data-base",
        required=True,
        help="exact demo data base containing networks/devnet",
    )
    reset_devnet.add_argument(
        "--runtime-base",
        required=True,
        help="exact demo runtime base containing networks/devnet",
    )
    reset_devnet.add_argument(
        "--compose-stopped",
        action="store_true",
        help="internal assertion that all managed devnet Compose projects are absent",
    )
    reset_devnet.add_argument(
        "--yes",
        action="store_true",
        help="required explicit acknowledgement for deletion",
    )
    reset_devnet.set_defaults(handler=_reset_devnet)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    include_internal = bool(arguments and arguments[0] in _INTERNAL_OPERATION_COMMANDS)
    parser = _parser(include_internal=include_internal)
    args = parser.parse_args(arguments)
    try:
        result = args.handler(args)
        return result if isinstance(result, int) else 0
    except LifecycleError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"ERROR: lifecycle filesystem operation failed: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("ERROR: interrupted; inspect any reported quarantine before retrying", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
