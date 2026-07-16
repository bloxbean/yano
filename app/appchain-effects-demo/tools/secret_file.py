#!/usr/bin/env python3
"""Atomically install one launcher-owned demo secret without exposing it in argv."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import os
from pathlib import Path
import stat
import sys
from typing import NoReturn


MAX_SECRET_BYTES = 4096
SECRET_MODE = 0o600
DIRECTORY_MODE = 0o700
TEMPORARY_PREFIX = ".yano-secret-tmp-"


def fail(message: str) -> NoReturn:
    raise SystemExit(f"error: {message}")


def directory_flags() -> int:
    if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
        fail("this platform cannot safely open the secret directory")
    return os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)


def file_flags() -> int:
    if not hasattr(os, "O_NOFOLLOW"):
        fail("this platform cannot safely open secret files")
    return os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)


def open_secret_directory(path: Path) -> int:
    raw_path = os.path.expanduser(os.fspath(path))
    if not raw_path or ".." in Path(raw_path).parts:
        fail("secret path must not contain parent-directory traversal")

    absolute = Path(os.path.abspath(raw_path))
    parent = absolute.parent
    descriptor = os.open(os.path.sep, directory_flags())
    try:
        for component in parent.parts[1:]:
            try:
                child = os.open(component, directory_flags(), dir_fd=descriptor)
            except OSError:
                fail(f"secret directory contains a symlink or non-directory component: {parent}")
            os.close(descriptor)
            descriptor = child

        info = os.fstat(descriptor)
        if (
            not stat.S_ISDIR(info.st_mode)
            or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) != DIRECTORY_MODE
        ):
            fail(f"secret directory must be owned by the current user with mode 0700: {parent}")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def read_candidate() -> bytes:
    data = sys.stdin.buffer.read(MAX_SECRET_BYTES + 2)
    if len(data) > MAX_SECRET_BYTES + 1:
        fail("candidate secret exceeds the 4096-byte limit")
    if data.endswith(b"\n"):
        data = data[:-1]
    if not data or len(data) > MAX_SECRET_BYTES:
        fail("candidate secret must contain one non-empty bounded line")
    if b"\n" in data or b"\r" in data:
        fail("candidate secret must contain exactly one line")
    if any(byte < 0x21 or byte > 0x7E for byte in data):
        fail("candidate secret must contain printable ASCII without spaces or controls")
    return data + b"\n"


def temporary_name(target_name: str) -> str:
    digest = hashlib.sha256(target_name.encode("utf-8")).hexdigest()
    return f"{TEMPORARY_PREFIX}{digest}"


def path_info(directory_descriptor: int, name: str) -> os.stat_result | None:
    try:
        return os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
    except FileNotFoundError:
        return None
    except OSError:
        fail(f"could not safely inspect secret path: {name}")


def open_regular(directory_descriptor: int, name: str) -> int:
    try:
        return os.open(name, os.O_RDONLY | file_flags(), dir_fd=directory_descriptor)
    except OSError:
        fail(f"secret must be a regular non-symlink file: {name}")


def validate_file_identity(
    descriptor: int,
    directory_descriptor: int,
    name: str,
    *,
    allowed_links: tuple[int, ...],
) -> os.stat_result:
    info = os.fstat(descriptor)
    if not stat.S_ISREG(info.st_mode):
        fail(f"secret must be a regular file: {name}")
    if info.st_uid != os.geteuid():
        fail(f"secret must be owned by the current user: {name}")
    if stat.S_IMODE(info.st_mode) != SECRET_MODE:
        fail(f"secret must have mode 0600: {name}")
    if info.st_nlink not in allowed_links:
        fail(f"secret has an unsafe hard-link count: {name}")
    current = path_info(directory_descriptor, name)
    if current is None or (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
        fail(f"secret changed during validation: {name}")
    return info


def read_valid_secret_descriptor(
    descriptor: int,
    directory_descriptor: int,
    name: str,
) -> tuple[bytes, os.stat_result]:
    before = validate_file_identity(
        descriptor, directory_descriptor, name, allowed_links=(1,)
    )
    data = b""
    while len(data) <= MAX_SECRET_BYTES + 1:
        chunk = os.read(descriptor, MAX_SECRET_BYTES + 2 - len(data))
        if not chunk:
            break
        data += chunk
    after = validate_file_identity(
        descriptor, directory_descriptor, name, allowed_links=(1,)
    )
    if (
        before.st_size != after.st_size
        or before.st_mtime_ns != after.st_mtime_ns
        or before.st_ctime_ns != after.st_ctime_ns
        or len(data) != after.st_size
    ):
        fail(f"secret changed while it was being read: {name}")
    if len(data) < 2 or len(data) > MAX_SECRET_BYTES + 1 or not data.endswith(b"\n"):
        fail(f"secret must contain one non-empty bounded line: {name}")
    value = data[:-1]
    if b"\n" in value or b"\r" in value:
        fail(f"secret must contain exactly one line: {name}")
    if any(byte < 0x21 or byte > 0x7E for byte in value):
        fail(f"secret must contain printable ASCII without spaces or controls: {name}")
    return data, after


def read_valid_secret(directory_descriptor: int, name: str) -> bytes:
    descriptor = open_regular(directory_descriptor, name)
    try:
        data, _ = read_valid_secret_descriptor(descriptor, directory_descriptor, name)
        return data
    finally:
        os.close(descriptor)


def same_inode(left: os.stat_result, right: os.stat_result) -> bool:
    return (left.st_dev, left.st_ino) == (right.st_dev, right.st_ino)


def unlink_exact(
    directory_descriptor: int,
    name: str,
    expected: os.stat_result,
) -> None:
    current = path_info(directory_descriptor, name)
    if current is None or not same_inode(current, expected):
        fail(f"temporary secret changed before removal: {name}")
    try:
        os.unlink(name, dir_fd=directory_descriptor)
    except OSError:
        fail(f"could not remove the exact temporary secret: {name}")


def recover_linked_temporary(
    directory_descriptor: int,
    target_name: str,
    temporary: str,
) -> bool:
    target_info = path_info(directory_descriptor, target_name)
    temporary_info = path_info(directory_descriptor, temporary)
    if target_info is None:
        return False
    if target_info.st_nlink == 1:
        if temporary_info is not None:
            fail(f"unexpected temporary artifact exists beside secret: {target_name}")
        return False
    if target_info.st_nlink != 2 or temporary_info is None or not same_inode(target_info, temporary_info):
        fail(f"secret has an unrecognized hard link: {target_name}")

    descriptor = open_regular(directory_descriptor, target_name)
    try:
        validate_file_identity(
            descriptor, directory_descriptor, target_name, allowed_links=(2,)
        )
        if (
            not stat.S_ISREG(temporary_info.st_mode)
            or temporary_info.st_uid != os.geteuid()
            or stat.S_IMODE(temporary_info.st_mode) != SECRET_MODE
            or temporary_info.st_nlink != 2
        ):
            fail(f"linked temporary secret is unsafe: {temporary}")
        unlink_exact(directory_descriptor, temporary, temporary_info)
        os.fsync(directory_descriptor)
    finally:
        os.close(descriptor)
    read_valid_secret(directory_descriptor, target_name)
    return True


def recover_unpublished_temporary(
    directory_descriptor: int,
    target_name: str,
    temporary: str,
) -> bool:
    if path_info(directory_descriptor, target_name) is not None:
        return False
    temporary_info = path_info(directory_descriptor, temporary)
    if temporary_info is None:
        return False
    descriptor = open_regular(directory_descriptor, temporary)
    try:
        data, before_fsync = read_valid_secret_descriptor(
            descriptor, directory_descriptor, temporary
        )
        os.fsync(descriptor)
        after_fsync = validate_file_identity(
            descriptor, directory_descriptor, temporary, allowed_links=(1,)
        )
        if (
            not same_inode(before_fsync, after_fsync)
            or before_fsync.st_size != after_fsync.st_size
            or before_fsync.st_mtime_ns != after_fsync.st_mtime_ns
        ):
            fail(f"temporary secret changed while it was made durable: {temporary}")
    finally:
        os.close(descriptor)
    try:
        os.link(
            temporary,
            target_name,
            src_dir_fd=directory_descriptor,
            dst_dir_fd=directory_descriptor,
            follow_symlinks=False,
        )
        os.fsync(directory_descriptor)
    except FileExistsError:
        return False
    except OSError:
        fail(f"could not publish recovered temporary secret: {target_name}")
    target_info = path_info(directory_descriptor, target_name)
    temporary_info = path_info(directory_descriptor, temporary)
    if (
        target_info is None
        or temporary_info is None
        or target_info.st_nlink != 2
        or not same_inode(target_info, temporary_info)
    ):
        fail(f"recovered temporary secret did not publish atomically: {target_name}")
    unlink_exact(directory_descriptor, temporary, temporary_info)
    os.fsync(directory_descriptor)
    if read_valid_secret(directory_descriptor, target_name) != data:
        fail(f"recovered secret changed during publication: {target_name}")
    return True


def write_all(descriptor: int, data: bytes) -> None:
    offset = 0
    while offset < len(data):
        written = os.write(descriptor, data[offset:])
        if written <= 0:
            fail("could not write candidate secret")
        offset += written


def install(directory_descriptor: int, target_name: str, candidate: bytes) -> None:
    temporary = temporary_name(target_name)
    fcntl.flock(directory_descriptor, fcntl.LOCK_EX)

    if recover_linked_temporary(directory_descriptor, target_name, temporary):
        return
    if path_info(directory_descriptor, target_name) is not None:
        read_valid_secret(directory_descriptor, target_name)
        return
    if recover_unpublished_temporary(directory_descriptor, target_name, temporary):
        return

    descriptor = -1
    temporary_info: os.stat_result | None = None
    try:
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | file_flags(),
            SECRET_MODE,
            dir_fd=directory_descriptor,
        )
        os.fchmod(descriptor, SECRET_MODE)
        write_all(descriptor, candidate)
        os.fsync(descriptor)
        temporary_info = os.fstat(descriptor)
        if (
            not stat.S_ISREG(temporary_info.st_mode)
            or temporary_info.st_uid != os.geteuid()
            or stat.S_IMODE(temporary_info.st_mode) != SECRET_MODE
            or temporary_info.st_nlink != 1
            or temporary_info.st_size != len(candidate)
        ):
            fail("could not securely create candidate secret")
        os.close(descriptor)
        descriptor = -1

        try:
            os.link(
                temporary,
                target_name,
                src_dir_fd=directory_descriptor,
                dst_dir_fd=directory_descriptor,
                follow_symlinks=False,
            )
            os.fsync(directory_descriptor)
        except FileExistsError:
            unlink_exact(directory_descriptor, temporary, temporary_info)
            os.fsync(directory_descriptor)
            read_valid_secret(directory_descriptor, target_name)
            return
        except OSError:
            fail(f"could not atomically publish secret: {target_name}")

        published = path_info(directory_descriptor, target_name)
        linked_temporary = path_info(directory_descriptor, temporary)
        if (
            published is None
            or linked_temporary is None
            or published.st_nlink != 2
            or not same_inode(published, linked_temporary)
        ):
            fail(f"secret publication identity is inconsistent: {target_name}")
        unlink_exact(directory_descriptor, temporary, linked_temporary)
        os.fsync(directory_descriptor)
        if read_valid_secret(directory_descriptor, target_name) != candidate:
            fail(f"published secret changed during validation: {target_name}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        if temporary_info is not None:
            current = path_info(directory_descriptor, temporary)
            if current is not None and same_inode(current, temporary_info):
                try:
                    os.unlink(temporary, dir_fd=directory_descriptor)
                    os.fsync(directory_descriptor)
                except OSError:
                    fail(f"could not remove exact temporary secret: {temporary}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--path", required=True)
    args = parser.parse_args()

    candidate = read_candidate()
    path = Path(args.path)
    if path.name in ("", ".", ".."):
        fail("secret path must name a file")
    directory_descriptor = open_secret_directory(path)
    try:
        install(directory_descriptor, path.name, candidate)
    finally:
        os.close(directory_descriptor)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
