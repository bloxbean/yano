#!/usr/bin/env python3
"""Crash-safe lifecycle manager for the demo's foreground host UI process.

The process is identified by a current-owner record containing its PID, kernel
start token, and exact argv.  A gated fork and durable launch fence ensure that
the child cannot escape unrecorded if the launcher is killed during startup.
"""

from __future__ import annotations

import argparse
import ctypes
import errno
import fcntl
import json
import os
from pathlib import Path
import signal
import stat
import struct
import sys
import time
from dataclasses import dataclass
from typing import Any, NoReturn, Sequence


SCHEMA_VERSION = 1
NAME = "host-ui"
DIRECTORY_MODE = 0o700
FILE_MODE = 0o600
MAX_DOCUMENT_BYTES = 64 * 1024
MAX_ARGV_ITEMS = 256
MAX_ARGV_BYTES = 48 * 1024
LOCK_FILE = ".host-ui.lock"
RECORD_FILE = "host-ui.process.json"
RECORD_TEMP = ".host-ui.process.tmp"
RECORD_UPDATE_TEMP = ".host-ui.process-update.tmp"
FENCE_FILE = ".host-ui.launch.json"
FENCE_TEMP = ".host-ui.launch.tmp"
RECORD_KIND = "yano.demo.managed-process"
FENCE_KIND = "yano.demo.managed-process-launch"
TEST_STOP_ENV = "YANO_MANAGED_PROCESS_TEST_STOP_AFTER"
TEST_FAIL_ENV = "YANO_MANAGED_PROCESS_TEST_FAIL_AFTER"
TEST_UNPROVEN_ENV = "YANO_MANAGED_PROCESS_TEST_CLEANUP_UNPROVEN"


class ManagedProcessError(Exception):
    """An operator-actionable validation or lifecycle failure."""


@dataclass(frozen=True)
class ProcessSnapshot:
    pid: int
    uid: int
    start_token: str
    argv: tuple[str, ...] | None
    zombie: bool = False


def fail(message: str) -> NoReturn:
    raise ManagedProcessError(message)


def canonical_json(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        + "\n"
    ).encode("utf-8")


def validate_argv(argv: Sequence[str]) -> tuple[str, ...]:
    result = tuple(argv)
    if not result:
        fail("a managed command is required after '--'")
    if len(result) > MAX_ARGV_ITEMS:
        fail(f"managed command exceeds {MAX_ARGV_ITEMS} arguments")
    total = 0
    for item in result:
        if not isinstance(item, str) or not item or "\0" in item:
            fail("managed command arguments must be non-empty strings without NUL bytes")
        try:
            total += len(item.encode("utf-8")) + 1
        except UnicodeEncodeError as error:
            raise ManagedProcessError("managed command contains invalid Unicode") from error
    if total > MAX_ARGV_BYTES:
        fail(f"managed command exceeds the {MAX_ARGV_BYTES}-byte limit")
    return result


def directory_flags() -> int:
    if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
        fail("this platform cannot safely open the runtime directory")
    return os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)


def open_runtime_root(path: str) -> tuple[Path, int]:
    raw = os.path.expanduser(path)
    if not raw or ".." in Path(raw).parts:
        fail("runtime root must be an absolute path without parent-directory traversal")
    # Darwin exposes the system temporary directory through the fixed /tmp ->
    # /private/tmp compatibility link.  Canonicalize that OS-owned prefix while
    # continuing to reject caller-controlled symlink components below it.
    if sys.platform == "darwin" and any(
        raw == prefix or raw.startswith(prefix + "/")
        for prefix in ("/tmp", "/var")
    ):
        raw = "/private" + raw
    absolute = Path(os.path.abspath(raw))
    descriptor = os.open(os.path.sep, directory_flags())
    try:
        for component in absolute.parts[1:]:
            try:
                child = os.open(component, directory_flags(), dir_fd=descriptor)
            except OSError as error:
                raise ManagedProcessError(
                    f"runtime root contains a symlink, missing path, or non-directory: {absolute}"
                ) from error
            os.close(descriptor)
            descriptor = child
        info = os.fstat(descriptor)
        if (
            not stat.S_ISDIR(info.st_mode)
            or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) != DIRECTORY_MODE
        ):
            fail(
                f"runtime root must be owned by the current user with mode 0700: {absolute}"
            )
        return absolute, descriptor
    except BaseException:
        os.close(descriptor)
        raise


def path_info(root_fd: int, name: str) -> os.stat_result | None:
    try:
        return os.stat(name, dir_fd=root_fd, follow_symlinks=False)
    except FileNotFoundError:
        return None
    except OSError as error:
        raise ManagedProcessError(f"cannot safely inspect managed artifact {name}") from error


def same_inode(left: os.stat_result, right: os.stat_result) -> bool:
    return (left.st_dev, left.st_ino) == (right.st_dev, right.st_ino)


def validate_owned_file(info: os.stat_result, name: str, links: tuple[int, ...] = (1,)) -> None:
    if not stat.S_ISREG(info.st_mode):
        fail(f"managed artifact must be a regular file: {name}")
    if info.st_uid != os.geteuid():
        fail(f"managed artifact is not owned by the current user: {name}")
    if stat.S_IMODE(info.st_mode) != FILE_MODE:
        fail(f"managed artifact must have mode 0600: {name}")
    if info.st_nlink not in links:
        fail(f"managed artifact has an unsafe hard-link count: {name}")


def open_lock(root_fd: int) -> int:
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    created = path_info(root_fd, LOCK_FILE) is None
    try:
        descriptor = os.open(LOCK_FILE, flags, FILE_MODE, dir_fd=root_fd)
    except OSError as error:
        raise ManagedProcessError(f"cannot safely open managed-process lock: {error}") from error
    try:
        validate_owned_file(os.fstat(descriptor), LOCK_FILE)
        current = path_info(root_fd, LOCK_FILE)
        if current is None or not same_inode(current, os.fstat(descriptor)):
            fail("managed-process lock changed during validation")
        if created:
            os.fsync(descriptor)
            os.fsync(root_fd)
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        after = os.fstat(descriptor)
        validate_owned_file(after, LOCK_FILE)
        current = path_info(root_fd, LOCK_FILE)
        if current is None or not same_inode(current, after):
            fail("managed-process lock path changed while acquiring the lock")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def open_log(root_fd: int, name: str) -> int:
    if name != "host-ui.log":
        fail("host UI log must be the fixed runtime-root file host-ui.log")
    flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(name, flags, FILE_MODE, dir_fd=root_fd)
    except OSError as error:
        raise ManagedProcessError(f"cannot safely open host UI log: {error}") from error
    try:
        validate_owned_file(os.fstat(descriptor), name)
        current = path_info(root_fd, name)
        if current is None or not same_inode(current, os.fstat(descriptor)):
            fail("host UI log changed during validation")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def write_new_file(root_fd: int, name: str, content: bytes) -> os.stat_result:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(name, flags, FILE_MODE, dir_fd=root_fd)
    except FileExistsError as error:
        raise ManagedProcessError(f"managed artifact already exists: {name}") from error
    try:
        written = 0
        while written < len(content):
            written += os.write(descriptor, content[written:])
        os.fsync(descriptor)
        info = os.fstat(descriptor)
        validate_owned_file(info, name)
        return info
    except BaseException:
        try:
            os.unlink(name, dir_fd=root_fd)
        except OSError:
            pass
        raise
    finally:
        os.close(descriptor)


def unlink_exact(root_fd: int, name: str, expected: os.stat_result) -> None:
    current = path_info(root_fd, name)
    if current is None or not same_inode(current, expected):
        fail(f"managed artifact changed before removal: {name}")
    os.unlink(name, dir_fd=root_fd)
    os.fsync(root_fd)
    os.fsync(root_fd)


def read_document(
    root_fd: int,
    name: str,
    kind: str,
    *,
    links: tuple[int, ...] = (1,),
) -> tuple[dict[str, Any], os.stat_result]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(name, flags, dir_fd=root_fd)
    except OSError as error:
        raise ManagedProcessError(f"cannot safely open managed artifact {name}") from error
    try:
        before = os.fstat(descriptor)
        validate_owned_file(before, name, links)
        data = b""
        while len(data) <= MAX_DOCUMENT_BYTES:
            chunk = os.read(descriptor, MAX_DOCUMENT_BYTES + 1 - len(data))
            if not chunk:
                break
            data += chunk
        after = os.fstat(descriptor)
        current = path_info(root_fd, name)
        if (
            current is None
            or not same_inode(before, after)
            or not same_inode(after, current)
            or before.st_size != after.st_size
            or before.st_mtime_ns != after.st_mtime_ns
        ):
            fail(f"managed artifact changed while being read: {name}")
        if len(data) > MAX_DOCUMENT_BYTES:
            fail(f"managed artifact exceeds {MAX_DOCUMENT_BYTES} bytes: {name}")
        try:
            value = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ManagedProcessError(f"managed artifact is not valid UTF-8 JSON: {name}") from error
        if not isinstance(value, dict) or canonical_json(value) != data:
            fail(f"managed artifact is not canonical JSON: {name}")
        if value.get("schemaVersion") != SCHEMA_VERSION or value.get("kind") != kind:
            fail(f"managed artifact has an unsupported schema or kind: {name}")
        return value, after
    finally:
        os.close(descriptor)


def validate_record(
    value: dict[str, Any],
) -> tuple[int, str, tuple[str, ...], tuple[str, ...] | None]:
    expected = {
        "kind", "name", "observedArgv", "pid", "requestedArgv",
        "schemaVersion", "startToken", "uid",
    }
    if set(value) != expected:
        fail("managed-process record has unexpected fields")
    if value["name"] != NAME or value["uid"] != os.geteuid():
        fail("managed-process record has the wrong name or owner identity")
    pid = value["pid"]
    token = value["startToken"]
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        fail("managed-process record has an invalid PID")
    if not isinstance(token, str) or not token or len(token) > 128:
        fail("managed-process record has an invalid start token")
    requested = value["requestedArgv"]
    observed = value["observedArgv"]
    if not isinstance(requested, list):
        fail("managed-process record requested argv is invalid")
    if observed is not None and not isinstance(observed, list):
        fail("managed-process record observed argv is invalid")
    return (
        pid,
        token,
        validate_argv(requested),
        None if observed is None else validate_argv(observed),
    )


def validate_fence(
    value: dict[str, Any],
) -> tuple[
    str, int, str, int | None, str | None, tuple[str, ...], tuple[str, ...] | None
]:
    expected = {
        "childPid", "childStartToken", "gatedArgv", "kind", "launcherPid",
        "launcherStartToken", "name", "phase", "requestedArgv", "schemaVersion", "uid",
    }
    if set(value) != expected:
        fail("managed-process launch fence has unexpected fields")
    if value["name"] != NAME or value["uid"] != os.geteuid():
        fail("managed-process launch fence has the wrong name or owner identity")
    phase = value["phase"]
    if phase not in ("prepared", "forked", "published"):
        fail("managed-process launch fence has an invalid phase")
    launcher_pid = value["launcherPid"]
    launcher_token = value["launcherStartToken"]
    if not isinstance(launcher_pid, int) or isinstance(launcher_pid, bool) or launcher_pid <= 0:
        fail("managed-process launch fence has an invalid launcher PID")
    if not isinstance(launcher_token, str) or not launcher_token:
        fail("managed-process launch fence has an invalid launcher start token")
    child_pid = value["childPid"]
    child_token = value["childStartToken"]
    gated_raw = value["gatedArgv"]
    if phase == "prepared":
        if child_pid is not None or child_token is not None or gated_raw is not None:
            fail("prepared launch fence unexpectedly identifies a child")
    elif (
        not isinstance(child_pid, int)
        or isinstance(child_pid, bool)
        or child_pid <= 0
        or not isinstance(child_token, str)
        or not child_token
    ):
        fail("managed-process launch fence has an invalid child identity")
    if phase != "prepared" and not isinstance(gated_raw, list):
        fail("managed-process launch fence has invalid gated argv")
    requested = value["requestedArgv"]
    if not isinstance(requested, list):
        fail("managed-process launch fence requested argv is invalid")
    return (
        phase,
        launcher_pid,
        launcher_token,
        child_pid,
        child_token,
        validate_argv(requested),
        None if gated_raw is None else validate_argv(gated_raw),
    )


def linux_snapshot(pid: int, include_argv: bool) -> ProcessSnapshot | None:
    try:
        raw_stat = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
        proc_info = os.stat(f"/proc/{pid}")
    except (FileNotFoundError, ProcessLookupError):
        return None
    except (OSError, UnicodeError) as error:
        raise ManagedProcessError(f"cannot inspect process {pid}: {error}") from error
    closing = raw_stat.rfind(")")
    if closing < 0:
        fail(f"cannot parse kernel process identity for PID {pid}")
    fields = raw_stat[closing + 2 :].split()
    if len(fields) < 20:
        fail(f"kernel process identity is incomplete for PID {pid}")
    state = fields[0]
    token = f"linux:{fields[19]}"
    argv: tuple[str, ...] | None = None
    if include_argv and state != "Z":
        try:
            raw_argv = Path(f"/proc/{pid}/cmdline").read_bytes()
        except FileNotFoundError:
            return None
        if not raw_argv:
            argv = ()
        else:
            parts = raw_argv.rstrip(b"\0").split(b"\0")
            try:
                argv = tuple(os.fsdecode(item) for item in parts)
            except UnicodeError as error:
                raise ManagedProcessError(f"cannot decode argv for PID {pid}") from error
    return ProcessSnapshot(pid, proc_info.st_uid, token, argv, state == "Z")


class DarwinBsdInfo(ctypes.Structure):
    _fields_ = [
        ("pbi_flags", ctypes.c_uint32), ("pbi_status", ctypes.c_uint32),
        ("pbi_xstatus", ctypes.c_uint32), ("pbi_pid", ctypes.c_uint32),
        ("pbi_ppid", ctypes.c_uint32), ("pbi_uid", ctypes.c_uint32),
        ("pbi_gid", ctypes.c_uint32), ("pbi_ruid", ctypes.c_uint32),
        ("pbi_rgid", ctypes.c_uint32), ("pbi_svuid", ctypes.c_uint32),
        ("pbi_svgid", ctypes.c_uint32), ("rfu_1", ctypes.c_uint32),
        ("pbi_comm", ctypes.c_char * 16), ("pbi_name", ctypes.c_char * 32),
        ("pbi_nfiles", ctypes.c_uint32), ("pbi_pgid", ctypes.c_uint32),
        ("pbi_pjobc", ctypes.c_uint32), ("e_tdev", ctypes.c_uint32),
        ("e_tpgid", ctypes.c_uint32), ("pbi_nice", ctypes.c_int32),
        ("pbi_start_tvsec", ctypes.c_uint64),
        ("pbi_start_tvusec", ctypes.c_uint64),
    ]


def darwin_process_info(pid: int) -> tuple[int, str, bool] | None:
    library = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
    function = library.proc_pidinfo
    function.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int]
    function.restype = ctypes.c_int
    info = DarwinBsdInfo()
    result = function(pid, 3, 0, ctypes.byref(info), ctypes.sizeof(info))
    if result == 0:
        error = ctypes.get_errno()
        if error in (errno.ESRCH, errno.ENOENT, errno.EINVAL):
            return None
        raise ManagedProcessError(f"cannot inspect process {pid}: errno {error}")
    if result != ctypes.sizeof(info):
        fail(f"kernel returned an incomplete identity for PID {pid}")
    token = f"darwin:{info.pbi_start_tvsec}:{info.pbi_start_tvusec}"
    return info.pbi_uid, token, info.pbi_status == 5


def darwin_argv(pid: int) -> tuple[str, ...]:
    library = ctypes.CDLL("/usr/lib/libSystem.B.dylib", use_errno=True)
    sysctl = library.sysctl
    sysctl.argtypes = [
        ctypes.POINTER(ctypes.c_int), ctypes.c_uint, ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t), ctypes.c_void_p, ctypes.c_size_t,
    ]
    mib = (ctypes.c_int * 3)(1, 49, pid)
    size = ctypes.c_size_t(0)
    if sysctl(mib, 3, None, ctypes.byref(size), None, 0) != 0:
        error = ctypes.get_errno()
        if error in (errno.ESRCH, errno.ENOENT, errno.EINVAL):
            raise ProcessLookupError(pid)
        raise ManagedProcessError(f"cannot size argv for PID {pid}: errno {error}")
    buffer = ctypes.create_string_buffer(size.value)
    if sysctl(mib, 3, buffer, ctypes.byref(size), None, 0) != 0:
        error = ctypes.get_errno()
        if error in (errno.ESRCH, errno.ENOENT):
            raise ProcessLookupError(pid)
        raise ManagedProcessError(f"cannot read argv for PID {pid}: errno {error}")
    raw = buffer.raw[: size.value]
    if len(raw) < struct.calcsize("i"):
        fail(f"kernel returned incomplete argv for PID {pid}")
    argc = struct.unpack_from("i", raw)[0]
    if argc <= 0 or argc > MAX_ARGV_ITEMS:
        fail(f"kernel returned an invalid argc for PID {pid}")
    offset = struct.calcsize("i")
    executable_end = raw.find(b"\0", offset)
    if executable_end < 0:
        fail(f"kernel returned malformed argv for PID {pid}")
    offset = executable_end + 1
    while offset < len(raw) and raw[offset] == 0:
        offset += 1
    values: list[str] = []
    for _ in range(argc):
        end = raw.find(b"\0", offset)
        if end < 0:
            fail(f"kernel returned truncated argv for PID {pid}")
        values.append(os.fsdecode(raw[offset:end]))
        offset = end + 1
    return tuple(values)


def process_snapshot(pid: int, include_argv: bool = True) -> ProcessSnapshot | None:
    if pid <= 0:
        return None
    if sys.platform.startswith("linux"):
        return linux_snapshot(pid, include_argv)
    if sys.platform == "darwin":
        info = darwin_process_info(pid)
        if info is None:
            return None
        uid, token, zombie = info
        argv: tuple[str, ...] | None = None
        if include_argv and not zombie:
            try:
                argv = darwin_argv(pid)
            except ProcessLookupError:
                return None
        return ProcessSnapshot(pid, uid, token, argv, zombie)
    fail(f"managed process inspection is unsupported on {sys.platform}")


def exact_process(pid: int, token: str, argv: tuple[str, ...]) -> str:
    """Return absent, exact, or untrusted for a recorded process identity."""
    snapshot = process_snapshot(pid)
    if snapshot is None or snapshot.start_token != token or snapshot.zombie:
        return "absent"
    if snapshot.uid != os.geteuid() or snapshot.argv != argv:
        return "untrusted"
    return "exact"


def known_identity_state(process: ProcessSnapshot) -> str:
    current = process_snapshot(process.pid, include_argv=False)
    if current is None or current.start_token != process.start_token or current.zombie:
        return "absent"
    if current.uid != os.geteuid():
        return "untrusted"
    return "exact"


def wait_known_identity(process: ProcessSnapshot, timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = known_identity_state(process)
        if state != "exact":
            return state
        time.sleep(0.03)
    return known_identity_state(process)


def terminate_known_child(process: ProcessSnapshot) -> bool:
    """Stop the exact forked child; return only whether absence was proven."""
    if os.environ.get(TEST_UNPROVEN_ENV) == "1":
        return False
    state = known_identity_state(process)
    if state == "untrusted":
        return False
    if state == "absent":
        return True
    try:
        os.kill(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    state = wait_known_identity(process, 1.0)
    if state == "untrusted":
        return False
    if state == "absent":
        return True
    try:
        os.kill(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        return True
    return wait_known_identity(process, 2.0) == "absent"


def test_stop(point: str) -> None:
    if os.environ.get(TEST_STOP_ENV) == point:
        os._exit(97)


def test_fail(point: str) -> None:
    if os.environ.get(TEST_FAIL_ENV) == point:
        fail(f"injected managed-process failure after {point}")


def fence_document(
    phase: str,
    requested_argv: tuple[str, ...],
    launcher: ProcessSnapshot,
    child: ProcessSnapshot | None,
) -> dict[str, Any]:
    return {
        "childPid": None if child is None else child.pid,
        "childStartToken": None if child is None else child.start_token,
        "gatedArgv": None if child is None or child.argv is None else list(child.argv),
        "kind": FENCE_KIND,
        "launcherPid": launcher.pid,
        "launcherStartToken": launcher.start_token,
        "name": NAME,
        "phase": phase,
        "requestedArgv": list(requested_argv),
        "schemaVersion": SCHEMA_VERSION,
        "uid": os.geteuid(),
    }


def replace_fence(root_fd: int, document: dict[str, Any]) -> None:
    if path_info(root_fd, FENCE_TEMP) is not None:
        fail(f"uncertain launch artifact exists: {FENCE_TEMP}")
    write_new_file(root_fd, FENCE_TEMP, canonical_json(document))
    phase = document.get("phase")
    test_stop(f"fence-{phase}-temp")
    try:
        os.replace(FENCE_TEMP, FENCE_FILE, src_dir_fd=root_fd, dst_dir_fd=root_fd)
        test_stop(f"fence-{phase}-renamed")
        os.fsync(root_fd)
    except BaseException:
        raise


def publish_initial_fence(root_fd: int, document: dict[str, Any]) -> None:
    if path_info(root_fd, FENCE_FILE) is not None or path_info(root_fd, FENCE_TEMP) is not None:
        fail("managed-process launch fence or publication temporary already exists")
    temporary = write_new_file(root_fd, FENCE_TEMP, canonical_json(document))
    test_stop("fence-prepared-temp")
    os.link(
        FENCE_TEMP,
        FENCE_FILE,
        src_dir_fd=root_fd,
        dst_dir_fd=root_fd,
        follow_symlinks=False,
    )
    os.fsync(root_fd)
    target = path_info(root_fd, FENCE_FILE)
    current_temp = path_info(root_fd, FENCE_TEMP)
    if (
        target is None
        or current_temp is None
        or not same_inode(target, current_temp)
        or not same_inode(target, temporary)
        or target.st_nlink != 2
    ):
        fail("initial launch-fence publication changed unexpectedly")
    test_stop("fence-prepared-linked")
    os.unlink(FENCE_TEMP, dir_fd=root_fd)
    os.fsync(root_fd)


def publish_record(root_fd: int, document: dict[str, Any]) -> os.stat_result:
    if path_info(root_fd, RECORD_FILE) is not None or path_info(root_fd, RECORD_TEMP) is not None:
        fail("managed-process record or publication temporary already exists")
    temporary = write_new_file(root_fd, RECORD_TEMP, canonical_json(document))
    try:
        os.link(
            RECORD_TEMP, RECORD_FILE, src_dir_fd=root_fd, dst_dir_fd=root_fd,
            follow_symlinks=False,
        )
        os.fsync(root_fd)
        target = path_info(root_fd, RECORD_FILE)
        current_temp = path_info(root_fd, RECORD_TEMP)
        if (
            target is None
            or current_temp is None
            or not same_inode(target, current_temp)
            or not same_inode(target, temporary)
            or target.st_nlink != 2
        ):
            fail("managed-process record publication changed unexpectedly")
        test_stop("record-linked")
        os.unlink(RECORD_TEMP, dir_fd=root_fd)
        os.fsync(root_fd)
        result = path_info(root_fd, RECORD_FILE)
        if result is None:
            fail("managed-process record disappeared after publication")
        validate_owned_file(result, RECORD_FILE)
        test_stop("record-published")
        return result
    except BaseException:
        raise


def dead_fence_owner(fence: dict[str, Any]) -> None:
    (
        _phase, launcher_pid, launcher_token, _child_pid, _child_token,
        _requested, _gated,
    ) = validate_fence(fence)
    launcher = process_snapshot(launcher_pid, include_argv=False)
    if launcher is not None and launcher.start_token == launcher_token and not launcher.zombie:
        fail("cannot recover an atomic temporary owned by a live launcher")


def recover_fence_temporary(root_fd: int) -> None:
    temporary = path_info(root_fd, FENCE_TEMP)
    if temporary is None:
        return
    validate_owned_file(temporary, FENCE_TEMP, (1, 2))
    current_info = path_info(root_fd, FENCE_FILE)
    if current_info is None:
        try:
            unpublished, _ = read_document(root_fd, FENCE_TEMP, FENCE_KIND)
        except ManagedProcessError:
            # No child can exist before the initial prepared fence is
            # create-only published. A partial fixed temp is therefore safe to
            # discard, but only after its exact private inode was validated.
            unlink_exact(root_fd, FENCE_TEMP, temporary)
            os.fsync(root_fd)
            return
        phase, *_ = validate_fence(unpublished)
        if phase != "prepared":
            fail("unpublished launch-fence temporary is not the prepared state")
        dead_fence_owner(unpublished)
        unlink_exact(root_fd, FENCE_TEMP, temporary)
        os.fsync(root_fd)
        return
    if same_inode(current_info, temporary):
        if current_info.st_nlink != 2:
            fail("linked initial launch fence has an unsafe hard-link count")
        current, _ = read_document(root_fd, FENCE_FILE, FENCE_KIND, links=(2,))
        phase, *_ = validate_fence(current)
        if phase != "prepared":
            fail("linked initial launch fence is not the prepared state")
        dead_fence_owner(current)
        unlink_exact(root_fd, FENCE_TEMP, temporary)
        os.fsync(root_fd)
        return

    current, _ = read_document(root_fd, FENCE_FILE, FENCE_KIND)
    dead_fence_owner(current)
    try:
        replacement, _ = read_document(root_fd, FENCE_TEMP, FENCE_KIND)
    except ManagedProcessError:
        # The canonical old fence is durable.  An owner-only single-link fixed
        # temp can only be a write interrupted before fsync, so discard it.
        unlink_exact(root_fd, FENCE_TEMP, temporary)
        return
    (
        old_phase, old_launcher_pid, old_launcher_token, old_child_pid,
        old_child_token, old_requested, _old_gated,
    ) = validate_fence(current)
    (
        new_phase, new_launcher_pid, new_launcher_token, new_child_pid,
        new_child_token, new_requested, _new_gated,
    ) = validate_fence(replacement)
    progression = {("prepared", "forked"), ("forked", "published")}
    if (
        (old_phase, new_phase) not in progression
        or (old_launcher_pid, old_launcher_token, old_requested)
        != (new_launcher_pid, new_launcher_token, new_requested)
        or (old_child_pid is not None and old_child_pid != new_child_pid)
        or (old_child_token is not None and old_child_token != new_child_token)
    ):
        fail("launch-fence temporary is not the next state of the durable fence")
    os.replace(FENCE_TEMP, FENCE_FILE, src_dir_fd=root_fd, dst_dir_fd=root_fd)
    os.fsync(root_fd)


def recover_record_update_temporary(root_fd: int) -> None:
    temporary = path_info(root_fd, RECORD_UPDATE_TEMP)
    if temporary is None:
        return
    validate_owned_file(temporary, RECORD_UPDATE_TEMP)
    fence, _ = read_document(root_fd, FENCE_FILE, FENCE_KIND)
    dead_fence_owner(fence)
    current, _ = read_document(root_fd, RECORD_FILE, RECORD_KIND)
    current_pid, current_token, current_requested, current_observed = validate_record(current)
    if current_observed is not None:
        fail("record-update temporary exists beside an already finalized record")
    try:
        replacement, _ = read_document(root_fd, RECORD_UPDATE_TEMP, RECORD_KIND)
    except ManagedProcessError:
        unlink_exact(root_fd, RECORD_UPDATE_TEMP, temporary)
        return
    new_pid, new_token, new_requested, new_observed = validate_record(replacement)
    if (
        (new_pid, new_token, new_requested)
        != (current_pid, current_token, current_requested)
        or new_observed is None
    ):
        fail("record-update temporary is not a finalized form of the durable record")
    os.replace(RECORD_UPDATE_TEMP, RECORD_FILE, src_dir_fd=root_fd, dst_dir_fd=root_fd)
    os.fsync(root_fd)


def replace_record(root_fd: int, document: dict[str, Any]) -> os.stat_result:
    if path_info(root_fd, RECORD_UPDATE_TEMP) is not None:
        fail(f"uncertain record update artifact exists: {RECORD_UPDATE_TEMP}")
    write_new_file(root_fd, RECORD_UPDATE_TEMP, canonical_json(document))
    test_stop("record-update-temp")
    os.replace(RECORD_UPDATE_TEMP, RECORD_FILE, src_dir_fd=root_fd, dst_dir_fd=root_fd)
    test_stop("record-update-renamed")
    os.fsync(root_fd)
    result = path_info(root_fd, RECORD_FILE)
    if result is None:
        fail("managed-process record disappeared after exact-argv publication")
    validate_owned_file(result, RECORD_FILE)
    return result


def recover_record_temporary(root_fd: int, expected_argv: tuple[str, ...]) -> None:
    temporary = path_info(root_fd, RECORD_TEMP)
    target = path_info(root_fd, RECORD_FILE)
    if temporary is None:
        return
    validate_owned_file(temporary, RECORD_TEMP, (1, 2))
    if target is not None and same_inode(target, temporary) and target.st_nlink == 2:
        read_document(root_fd, RECORD_FILE, RECORD_KIND, links=(2,))
        unlink_exact(root_fd, RECORD_TEMP, temporary)
        return
    if target is None and temporary.st_nlink == 1:
        fence, _ = read_document(root_fd, FENCE_FILE, FENCE_KIND)
        (
            phase, _launcher_pid, _launcher_token, child_pid, child_token,
            requested, gated_argv,
        ) = validate_fence(fence)
        dead_fence_owner(fence)
        if (
            phase != "forked"
            or requested != expected_argv
            or child_pid is None
            or child_token is None
            or gated_argv is None
        ):
            fail("unpublished process temporary is not protected by its exact forked fence")
        child = ProcessSnapshot(child_pid, os.geteuid(), child_token, gated_argv)
        if not terminate_known_child(child):
            fail("unpublished process temporary child absence could not be proven")
        try:
            record, _ = read_document(root_fd, RECORD_TEMP, RECORD_KIND)
        except ManagedProcessError:
            record = None
        if record is not None:
            pid, token, record_requested, observed = validate_record(record)
            if (
                (pid, token, record_requested) != (child_pid, child_token, requested)
                or observed is not None
            ):
                fail("unpublished process temporary does not match its forked fence")
        unlink_exact(root_fd, RECORD_TEMP, temporary)
        os.fsync(root_fd)
        return
    fail("unrecognized managed-process publication temporary exists")


def remove_record(root_fd: int, expected: os.stat_result) -> None:
    unlink_exact(root_fd, RECORD_FILE, expected)


def wait_for_identity(
    pid: int, timeout: float = 2.0, *, include_argv: bool = False
) -> ProcessSnapshot:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snapshot = process_snapshot(pid, include_argv=include_argv)
        if snapshot is not None:
            if snapshot.uid != os.geteuid():
                fail("gated child is not owned by the current user")
            return snapshot
        time.sleep(0.01)
    fail("could not capture the gated child's kernel identity")


def wait_for_exec_argv(
    pid: int,
    token: str,
    gated_argv: tuple[str, ...],
    timeout: float,
) -> tuple[str, ...] | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snapshot = process_snapshot(pid)
        if snapshot is None or snapshot.start_token != token or snapshot.zombie:
            return None
        if snapshot.uid != os.geteuid() or snapshot.argv is None:
            fail("managed child changed ownership or has unreadable argv during exec")
        if snapshot.argv != gated_argv:
            return validate_argv(snapshot.argv)
        time.sleep(0.02)
    fail("managed child did not leave its gated pre-exec argv before the timeout")


def recover_fence(root_fd: int, expected_argv: tuple[str, ...]) -> None:
    fence_info = path_info(root_fd, FENCE_FILE)
    if fence_info is None:
        return
    value, exact_fence = read_document(root_fd, FENCE_FILE, FENCE_KIND)
    (
        phase, launcher_pid, launcher_token, child_pid, child_token,
        requested_argv, gated_argv,
    ) = validate_fence(value)
    if requested_argv != expected_argv:
        fail("launch fence belongs to a different exact command")
    launcher = process_snapshot(launcher_pid, include_argv=False)
    if launcher is not None and launcher.start_token == launcher_token and not launcher.zombie:
        fail("an active launch fence is still owned by a live launcher")

    if phase == "prepared":
        unlink_exact(root_fd, FENCE_FILE, exact_fence)
        return

    assert child_pid is not None and child_token is not None
    record_info = path_info(root_fd, RECORD_FILE)
    if phase == "forked":
        snapshot = process_snapshot(child_pid, include_argv=False)
        if snapshot is not None and snapshot.start_token == child_token and not snapshot.zombie:
            deadline = time.monotonic() + 1.0
            while time.monotonic() < deadline:
                snapshot = process_snapshot(child_pid, include_argv=False)
                if snapshot is None or snapshot.start_token != child_token or snapshot.zombie:
                    break
                time.sleep(0.02)
            else:
                fail("an unrecorded gated child remains active after its launcher exited")
        if record_info is not None:
            record, exact_record = read_document(root_fd, RECORD_FILE, RECORD_KIND)
            pid, token, record_requested, observed = validate_record(record)
            if (
                (pid, token, record_requested)
                != (child_pid, child_token, requested_argv)
                or observed is not None
            ):
                fail("forked launch fence has an unrelated or finalized process record")
            state = known_identity_state(
                ProcessSnapshot(child_pid, os.geteuid(), child_token, gated_argv)
            )
            if state == "exact":
                deadline = time.monotonic() + 1.0
                while time.monotonic() < deadline:
                    state = known_identity_state(
                        ProcessSnapshot(child_pid, os.geteuid(), child_token, gated_argv)
                    )
                    if state != "exact":
                        break
                    time.sleep(0.02)
            if state != "absent":
                fail("pre-gate published child absence could not be proven")
            remove_record(root_fd, exact_record)
        unlink_exact(root_fd, FENCE_FILE, exact_fence)
        return

    if record_info is None:
        fail("published launch fence is missing its process record")
    record, exact_record = read_document(root_fd, RECORD_FILE, RECORD_KIND)
    pid, token, record_requested, observed_argv = validate_record(record)
    if (pid, token, record_requested) != (child_pid, child_token, requested_argv):
        fail("launch fence and process record identify different children")
    if gated_argv is None:
        fail("published launch fence is missing its gated argv")
    if observed_argv is None:
        observed_argv = wait_for_exec_argv(pid, token, gated_argv, 1.0)
        if observed_argv is not None:
            record["observedArgv"] = list(observed_argv)
            exact_record = replace_record(root_fd, record)
    state = "absent" if observed_argv is None else exact_process(pid, token, observed_argv)
    if state == "untrusted":
        fail("published child identity exists with untrusted exact argv or ownership")
    if state == "absent":
        remove_record(root_fd, exact_record)
    unlink_exact(root_fd, FENCE_FILE, exact_fence)


def load_record(
    root_fd: int, expected_argv: tuple[str, ...]
) -> tuple[int, str, tuple[str, ...], tuple[str, ...], os.stat_result] | None:
    recover_fence_temporary(root_fd)
    recover_record_temporary(root_fd, expected_argv)
    recover_record_update_temporary(root_fd)
    recover_fence(root_fd, expected_argv)
    info = path_info(root_fd, RECORD_FILE)
    if info is None:
        return None
    value, exact = read_document(root_fd, RECORD_FILE, RECORD_KIND)
    pid, token, requested_argv, observed_argv = validate_record(value)
    if requested_argv != expected_argv:
        fail("managed-process record belongs to a different exact command")
    if observed_argv is None:
        fail("managed-process record lacks finalized exact argv without a launch fence")
    return pid, token, requested_argv, observed_argv, exact


def make_record(
    child: ProcessSnapshot,
    requested_argv: tuple[str, ...],
    observed_argv: tuple[str, ...] | None = None,
) -> dict[str, Any]:
    return {
        "kind": RECORD_KIND,
        "name": NAME,
        "observedArgv": None if observed_argv is None else list(observed_argv),
        "pid": child.pid,
        "requestedArgv": list(requested_argv),
        "schemaVersion": SCHEMA_VERSION,
        "startToken": child.start_token,
        "uid": os.geteuid(),
    }


def cleanup_failed_launch_artifacts(
    root_fd: int,
    launcher: ProcessSnapshot,
    child: ProcessSnapshot,
    requested_argv: tuple[str, ...],
) -> None:
    """Remove only canonical artifacts proven to belong to this stopped launch."""
    for name in (RECORD_UPDATE_TEMP, RECORD_TEMP, RECORD_FILE):
        info = path_info(root_fd, name)
        if info is None:
            continue
        value, exact = read_document(root_fd, name, RECORD_KIND, links=(1, 2))
        pid, token, requested, _observed = validate_record(value)
        if (pid, token, requested) != (child.pid, child.start_token, requested_argv):
            fail(f"failed-launch record does not belong to the stopped child: {name}")
        unlink_exact(root_fd, name, exact)

    for name in (FENCE_TEMP, FENCE_FILE):
        info = path_info(root_fd, name)
        if info is None:
            continue
        value, exact = read_document(root_fd, name, FENCE_KIND)
        (
            _phase, launcher_pid, launcher_token, child_pid, child_token,
            requested, _gated,
        ) = validate_fence(value)
        if (
            launcher_pid != launcher.pid
            or launcher_token != launcher.start_token
            or requested != requested_argv
            or (child_pid is not None and child_pid != child.pid)
            or (child_token is not None and child_token != child.start_token)
        ):
            fail(f"failed-launch fence does not belong to the stopped child: {name}")
        unlink_exact(root_fd, name, exact)


def close_child_descriptors(keep: tuple[int, ...]) -> None:
    maximum = os.sysconf("SC_OPEN_MAX")
    for descriptor in range(3, min(maximum, 65536)):
        if descriptor not in keep:
            try:
                os.close(descriptor)
            except OSError:
                pass


def gated_child(gate_fd: int, log_fd: int, argv: tuple[str, ...]) -> NoReturn:
    try:
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        signal.signal(signal.SIGHUP, signal.SIG_DFL)
        byte = os.read(gate_fd, 1)
        if byte != b"G":
            os._exit(125)
        os.dup2(log_fd, 1)
        os.dup2(log_fd, 2)
        close_child_descriptors(())
        environment = os.environ.copy()
        for name in (TEST_STOP_ENV, TEST_FAIL_ENV, TEST_UNPROVEN_ENV):
            environment.pop(name, None)
        os.execvpe(argv[0], list(argv), environment)
    except BaseException as error:
        try:
            os.write(2, f"managed host UI exec failed: {error}\n".encode("utf-8", "replace"))
        except OSError:
            pass
        os._exit(126)


def command_start(args: argparse.Namespace) -> int:
    argv = validate_argv(args.command)
    _, root_fd = open_runtime_root(args.runtime_root)
    lock_fd = -1
    log_fd = -1
    child_pid: int | None = None
    launcher: ProcessSnapshot | None = None
    child: ProcessSnapshot | None = None
    gate_write = -1
    try:
        lock_fd = open_lock(root_fd)
        existing = load_record(root_fd, argv)
        if existing is not None:
            pid, token, _requested, observed_argv, exact = existing
            state = exact_process(pid, token, observed_argv)
            if state == "exact":
                print(pid)
                return 0
            if state == "untrusted":
                fail("recorded PID has untrusted argv or ownership; refusing to replace it")
            remove_record(root_fd, exact)

        log_fd = open_log(root_fd, args.log_file)
        launcher = process_snapshot(os.getpid(), include_argv=False)
        if launcher is None:
            fail("cannot capture launcher process identity")
        publish_initial_fence(
            root_fd,
            fence_document("prepared", argv, launcher, None),
        )
        test_stop("fence")

        gate_read, gate_write = os.pipe()
        child_pid = os.fork()
        if child_pid == 0:
            os.close(gate_write)
            gated_child(gate_read, log_fd, argv)
        os.close(gate_read)
        child = wait_for_identity(child_pid, include_argv=True)
        if child.argv is None:
            fail("could not capture gated child argv")
        replace_fence(root_fd, fence_document("forked", argv, launcher, child))
        test_stop("fork")

        publish_record(root_fd, make_record(child, argv))
        replace_fence(root_fd, fence_document("published", argv, launcher, child))
        test_stop("publication")

        os.write(gate_write, b"G")
        os.close(gate_write)
        gate_write = -1
        test_stop("gate")
        test_fail("gate")
        observed_argv = wait_for_exec_argv(
            child.pid, child.start_token, child.argv, args.exec_timeout
        )
        if observed_argv is None:
            fail("host UI child exited before its exact command was observable")
        record_value, _ = read_document(root_fd, RECORD_FILE, RECORD_KIND)
        pid, token, requested_argv, current_observed = validate_record(record_value)
        if (
            (pid, token, requested_argv, current_observed)
            != (child.pid, child.start_token, argv, None)
        ):
            fail("managed-process record changed before exact argv finalization")
        record_value["observedArgv"] = list(observed_argv)
        replace_record(root_fd, record_value)
        fence_value, fence_info = read_document(root_fd, FENCE_FILE, FENCE_KIND)
        validate_fence(fence_value)
        unlink_exact(root_fd, FENCE_FILE, fence_info)
        print(child.pid)
        return 0
    except BaseException:
        if gate_write >= 0:
            try:
                os.close(gate_write)
            except OSError:
                pass
        if child is not None and launcher is not None:
            if terminate_known_child(child):
                try:
                    cleanup_failed_launch_artifacts(root_fd, launcher, child, argv)
                except ManagedProcessError as cleanup_error:
                    print(
                        f"warning: exact child stopped but launch artifacts remain fail-closed: "
                        f"{cleanup_error}",
                        file=sys.stderr,
                    )
            else:
                print(
                    "warning: exact child absence could not be proven; launch fence and "
                    "record are preserved",
                    file=sys.stderr,
                )
        if child_pid is not None:
            try:
                os.waitpid(child_pid, os.WNOHANG)
            except (ChildProcessError, OSError):
                pass
        raise
    finally:
        if log_fd >= 0:
            os.close(log_fd)
        if lock_fd >= 0:
            os.close(lock_fd)
        os.close(root_fd)


def command_status(args: argparse.Namespace) -> int:
    argv = validate_argv(args.command)
    _, root_fd = open_runtime_root(args.runtime_root)
    lock_fd = -1
    try:
        lock_fd = open_lock(root_fd)
        record = load_record(root_fd, argv)
        if record is None:
            print("stopped")
            return 3
        pid, token, _requested, observed_argv, exact = record
        state = exact_process(pid, token, observed_argv)
        if state == "untrusted":
            fail("recorded PID has untrusted argv or ownership")
        if state == "absent":
            remove_record(root_fd, exact)
            print("stopped")
            return 3
        print(f"running {pid}")
        return 0
    finally:
        if lock_fd >= 0:
            os.close(lock_fd)
        os.close(root_fd)


def signal_exact(pid: int, token: str, argv: tuple[str, ...], signum: int) -> str:
    state = exact_process(pid, token, argv)
    if state != "exact":
        return state
    try:
        os.kill(pid, signum)
    except ProcessLookupError:
        return "absent"
    return "exact"


def wait_until_absent(pid: int, token: str, argv: tuple[str, ...], timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = exact_process(pid, token, argv)
        if state != "exact":
            return state
        time.sleep(0.05)
    return exact_process(pid, token, argv)


def command_stop(args: argparse.Namespace) -> int:
    argv = validate_argv(args.command)
    _, root_fd = open_runtime_root(args.runtime_root)
    lock_fd = -1
    try:
        lock_fd = open_lock(root_fd)
        record = load_record(root_fd, argv)
        if record is None:
            return 0
        pid, token, _requested, observed_argv, exact = record
        state = exact_process(pid, token, observed_argv)
        if state == "untrusted":
            fail("recorded PID has untrusted argv or ownership; refusing to signal it")
        if state == "exact":
            state = signal_exact(pid, token, observed_argv, signal.SIGTERM)
            if state == "exact":
                state = wait_until_absent(pid, token, observed_argv, args.term_timeout)
            if state == "exact":
                state = signal_exact(pid, token, observed_argv, signal.SIGKILL)
                if state == "exact":
                    state = wait_until_absent(pid, token, observed_argv, args.kill_timeout)
        if state == "untrusted":
            fail("managed PID changed argv or ownership during shutdown; record preserved")
        if state == "exact":
            fail("managed host UI did not stop after TERM and KILL; record preserved")
        remove_record(root_fd, exact)
        return 0
    finally:
        if lock_fd >= 0:
            os.close(lock_fd)
        os.close(root_fd)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subparsers = result.add_subparsers(dest="action", required=True)
    for action, handler in (
        ("start", command_start), ("status", command_status), ("stop", command_stop)
    ):
        command = subparsers.add_parser(action)
        command.add_argument("--runtime-root", required=True)
        command.add_argument("--name", choices=(NAME,), default=NAME)
        if action == "start":
            command.add_argument("--log-file", default="host-ui.log")
            command.add_argument("--exec-timeout", type=float, default=5.0)
        if action == "stop":
            command.add_argument("--term-timeout", type=float, default=15.0)
            command.add_argument("--kill-timeout", type=float, default=5.0)
        command.add_argument("command", nargs=argparse.REMAINDER)
        command.set_defaults(handler=handler)
    return result


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    if args.command and args.command[0] == "--":
        args.command = args.command[1:]
    for field in ("exec_timeout", "term_timeout", "kill_timeout"):
        if hasattr(args, field) and (not 0 < getattr(args, field) <= 300):
            fail(f"{field.replace('_', '-')} must be greater than zero and at most 300 seconds")
    return args.handler(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ManagedProcessError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
