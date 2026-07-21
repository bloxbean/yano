#!/usr/bin/env python3
"""Create and validate the demo's raw Ed25519 member key material."""

from __future__ import annotations

import argparse
import errno
import fcntl
import hashlib
import os
from pathlib import Path
import re
import secrets
import stat
import sys
from typing import NoReturn


HEX_32 = re.compile(r"[0-9a-f]{64}")
DEVNET_PUBLIC_KEYS = (
    "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
    "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
    "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1",
)
LOCK_FILE = ".member-keys.lock"
MODE_FILE = ".member-keys.mode"
MAX_KEY_FILE_BYTES = 65
MAX_MODE_FILE_BYTES = 14
OWNER_FILE_MODES = (0o400, 0o600)
FIELD = 2**255 - 19
CURVE_D = (-121665 * pow(121666, FIELD - 2, FIELD)) % FIELD
BASE_POINT = (
    15112221349535400772501151409588531511454012693041857206046113283949847762202,
    46316835694926478169428394003475163141307993866256225615783033603165251855960,
)


def fail(message: str) -> NoReturn:
    raise SystemExit(f"error: {message}")


def point_add(left: tuple[int, int], right: tuple[int, int]) -> tuple[int, int]:
    x1, y1 = left
    x2, y2 = right
    product = CURVE_D * x1 * x2 * y1 * y2 % FIELD
    x3 = (x1 * y2 + y1 * x2) * pow(1 + product, FIELD - 2, FIELD) % FIELD
    y3 = (y1 * y2 + x1 * x2) * pow(1 - product, FIELD - 2, FIELD) % FIELD
    return x3, y3


def scalar_multiply(scalar: int, point: tuple[int, int]) -> tuple[int, int]:
    result = (0, 1)
    addend = point
    while scalar:
        if scalar & 1:
            result = point_add(result, addend)
        addend = point_add(addend, addend)
        scalar >>= 1
    return result


def private_to_public(seed: bytes) -> bytes:
    if len(seed) != 32:
        fail("Ed25519 member seeds must contain exactly 32 bytes")
    digest = bytearray(hashlib.sha512(seed).digest())
    digest[0] &= 248
    digest[31] &= 63
    digest[31] |= 64
    scalar = int.from_bytes(digest[:32], "little")
    x, y = scalar_multiply(scalar, BASE_POINT)
    encoded = y | ((x & 1) << 255)
    return encoded.to_bytes(32, "little")


def generate_seed() -> bytes:
    return secrets.token_bytes(32)


def directory_open_flags() -> int:
    required = ("O_DIRECTORY", "O_NOFOLLOW")
    if any(not hasattr(os, name) for name in required):
        fail("this platform cannot safely open the member key directory")
    return os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)


def file_open_flags() -> int:
    if not hasattr(os, "O_NOFOLLOW"):
        fail("this platform cannot safely open member key files")
    return os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)


def validate_directory_fd(descriptor: int, display_path: Path) -> None:
    info = os.fstat(descriptor)
    if not stat.S_ISDIR(info.st_mode):
        fail(f"member key directory must be a regular directory: {display_path}")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        fail(f"member key directory must be owned by the current user with mode 0700: {display_path}")


def open_secure_directory(directory: Path, create: bool = True) -> int:
    raw_path = os.fspath(directory)
    if not raw_path or ".." in Path(raw_path).parts:
        fail("member key directory must not contain parent-directory traversal")

    absolute_path = Path(os.path.abspath(raw_path))
    # macOS exposes the trusted system aliases /var -> /private/var and
    # /tmp -> /private/tmp. Resolve only that root-owned first component;
    # caller-controlled symlinks anywhere below it remain forbidden by openat
    # with O_NOFOLLOW.
    if len(absolute_path.parts) > 1:
        first_name = absolute_path.parts[1]
        first = Path(os.path.sep) / first_name
        if first.is_symlink():
            info = first.lstat()
            resolved_first = Path(os.path.realpath(first))
            trusted = {
                "var": Path("/private/var"),
                "tmp": Path("/private/tmp"),
            }
            if info.st_uid != 0 or trusted.get(first_name) != resolved_first:
                fail(f"member key directory contains a symlink component: {absolute_path}")
            absolute_path = resolved_first.joinpath(*absolute_path.parts[2:])
    components = absolute_path.parts[1:]
    descriptor = os.open(os.path.sep, directory_open_flags())
    try:
        for index, component in enumerate(components):
            is_final = index == len(components) - 1
            created = False
            try:
                child = os.open(component, directory_open_flags(), dir_fd=descriptor)
            except FileNotFoundError:
                if not create:
                    fail(f"member key directory does not exist: {absolute_path}")
                try:
                    os.mkdir(component, 0o700, dir_fd=descriptor)
                    created = True
                except FileExistsError:
                    # A concurrent creator won. The no-follow open below still validates it.
                    pass
                except OSError:
                    fail(f"could not create member key directory: {absolute_path}")
                try:
                    child = os.open(component, directory_open_flags(), dir_fd=descriptor)
                except OSError:
                    fail(f"member key directory contains an unsafe path component: {absolute_path}")
            except OSError as error:
                if error.errno in (errno.ELOOP, errno.ENOTDIR):
                    fail(f"member key directory contains a symlink or non-directory component: {absolute_path}")
                fail(f"could not safely open member key directory: {absolute_path}")

            os.close(descriptor)
            descriptor = child
            if created:
                os.fchmod(descriptor, 0o700)
            if is_final:
                validate_directory_fd(descriptor, absolute_path)

        if not components:
            validate_directory_fd(descriptor, absolute_path)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def validate_regular_file(
    descriptor: int,
    name: str,
    directory_descriptor: int,
    display_path: Path,
    maximum_size: int,
) -> os.stat_result:
    info = os.fstat(descriptor)
    if not stat.S_ISREG(info.st_mode):
        fail(f"member key file must be a regular file: {display_path / name}")
    if info.st_uid != os.getuid():
        fail(f"member key file must be owned by the current user: {display_path / name}")
    if stat.S_IMODE(info.st_mode) not in OWNER_FILE_MODES:
        fail(f"member key file must have mode 0400 or 0600: {display_path / name}")
    if info.st_nlink != 1:
        fail(f"member key file must not have hard links: {display_path / name}")
    if info.st_size > maximum_size:
        fail(f"member key file exceeds its bounded size: {display_path / name}")
    try:
        path_info = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
    except OSError:
        fail(f"member key file changed during validation: {display_path / name}")
    if (path_info.st_dev, path_info.st_ino) != (info.st_dev, info.st_ino):
        fail(f"member key file changed during validation: {display_path / name}")
    return info


def read_bounded_file(
    directory_descriptor: int,
    display_path: Path,
    name: str,
    maximum_size: int,
) -> bytes:
    try:
        descriptor = os.open(name, os.O_RDONLY | file_open_flags(), dir_fd=directory_descriptor)
    except OSError:
        fail(f"member key file must be a regular non-symlink file: {display_path / name}")
    try:
        before = validate_regular_file(
            descriptor,
            name,
            directory_descriptor,
            display_path,
            maximum_size,
        )
        chunks: list[bytes] = []
        remaining = maximum_size + 1
        while remaining:
            chunk = os.read(descriptor, remaining)
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        data = b"".join(chunks)
        after = validate_regular_file(
            descriptor,
            name,
            directory_descriptor,
            display_path,
            maximum_size,
        )
        if (
            before.st_size != after.st_size
            or before.st_mtime_ns != after.st_mtime_ns
            or before.st_ctime_ns != after.st_ctime_ns
            or len(data) != after.st_size
        ):
            fail(f"member key file changed during validation: {display_path / name}")
        return data
    finally:
        os.close(descriptor)


def read_key_file(directory_descriptor: int, display_path: Path, name: str) -> str:
    data = read_bounded_file(directory_descriptor, display_path, name, MAX_KEY_FILE_BYTES)
    try:
        text = data.decode("ascii")
    except UnicodeError:
        fail(f"member key file is not bounded ASCII: {display_path / name}")
    if len(text) != MAX_KEY_FILE_BYTES or not text.endswith("\n") or not HEX_32.fullmatch(text[:-1]):
        fail(f"member key file must contain exactly one lowercase 32-byte hex value: {display_path / name}")
    return text[:-1]


def read_mode_file(directory_descriptor: int, display_path: Path) -> str:
    data = read_bounded_file(directory_descriptor, display_path, MODE_FILE, MAX_MODE_FILE_BYTES)
    try:
        text = data.decode("ascii")
    except UnicodeError:
        fail(f"member key mode file is not bounded ASCII: {display_path / MODE_FILE}")
    if text not in ("deterministic\n", "generated\n"):
        fail(f"member key mode file is invalid: {display_path / MODE_FILE}")
    return text[:-1]


def write_all(descriptor: int, data: bytes) -> None:
    offset = 0
    while offset < len(data):
        written = os.write(descriptor, data[offset:])
        if written <= 0:
            fail("could not write member key material")
        offset += written


def create_file_atomic(directory_descriptor: int, display_path: Path, name: str, data: bytes) -> None:
    temporary_name = f".tmp-member-key-{os.getpid()}-{secrets.token_hex(12)}"
    descriptor = -1
    linked = False
    try:
        descriptor = os.open(
            temporary_name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | file_open_flags(),
            0o600,
            dir_fd=directory_descriptor,
        )
        os.fchmod(descriptor, 0o600)
        write_all(descriptor, data)
        os.fsync(descriptor)
        info = os.fstat(descriptor)
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_nlink != 1
            or info.st_size != len(data)
        ):
            fail("could not securely create member key material")
        os.close(descriptor)
        descriptor = -1
        os.link(
            temporary_name,
            name,
            src_dir_fd=directory_descriptor,
            dst_dir_fd=directory_descriptor,
            follow_symlinks=False,
        )
        linked = True
        os.unlink(temporary_name, dir_fd=directory_descriptor)
        os.fsync(directory_descriptor)
        linked_info = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
        if linked_info.st_nlink != 1:
            fail(f"member key file must not have hard links: {display_path / name}")
    except FileExistsError:
        fail(f"member key file appeared during creation: {display_path / name}")
    except OSError:
        fail(f"could not atomically create member key file: {display_path / name}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            os.unlink(temporary_name, dir_fd=directory_descriptor)
        except FileNotFoundError:
            pass
        except OSError:
            if not linked:
                fail("could not remove temporary member key material")


def open_lock(directory_descriptor: int, display_path: Path,
              create: bool = True) -> int:
    flags = os.O_RDWR | file_open_flags()
    created = False
    if not create:
        try:
            descriptor = os.open(LOCK_FILE, flags, dir_fd=directory_descriptor)
        except OSError:
            fail(f"member key lock does not exist or is unsafe: {display_path / LOCK_FILE}")
    else:
        try:
            descriptor = os.open(
                LOCK_FILE,
                flags | os.O_CREAT | os.O_EXCL,
                0o600,
                dir_fd=directory_descriptor,
            )
            created = True
        except FileExistsError:
            try:
                descriptor = os.open(LOCK_FILE, flags, dir_fd=directory_descriptor)
            except OSError:
                fail(f"member key lock must be a regular non-symlink file: {display_path / LOCK_FILE}")
        except OSError:
            fail(f"could not securely create member key lock: {display_path / LOCK_FILE}")

    try:
        if created:
            os.fchmod(descriptor, 0o600)
            os.fsync(descriptor)
            os.fsync(directory_descriptor)
        info = validate_regular_file(
            descriptor,
            LOCK_FILE,
            directory_descriptor,
            display_path,
            0,
        )
        if info.st_size != 0 or stat.S_IMODE(info.st_mode) != 0o600:
            fail(f"member key lock must be an empty owner-only 0600 file: {display_path / LOCK_FILE}")
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        info = validate_regular_file(
            descriptor,
            LOCK_FILE,
            directory_descriptor,
            display_path,
            0,
        )
        if info.st_size != 0 or stat.S_IMODE(info.st_mode) != 0o600:
            fail(f"member key lock changed during validation: {display_path / LOCK_FILE}")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def ensure_exact_inventory(directory_descriptor: int, expected: set[str], display_path: Path) -> set[str]:
    try:
        actual = set(os.listdir(directory_descriptor))
    except OSError:
        fail(f"could not inspect member key directory: {display_path}")
    unexpected = actual - expected
    if unexpected:
        fail(f"member key directory contains unexpected entries: {display_path}")
    return actual


def ensure_keys(directory: Path, mode: str, count: int,
                existing_only: bool = False, purpose: str = "members") -> list[str]:
    if purpose == "members" and count != 3:
        fail("the evidence demo requires exactly three members")
    if purpose == "role-actors" and (count != 5 or mode != "generated"):
        fail("the role-aware evidence demo requires exactly five generated actor keys")

    display_path = Path(os.path.abspath(os.fspath(directory)))
    directory_descriptor = open_secure_directory(directory, create=not existing_only)
    lock_descriptor = -1
    try:
        lock_descriptor = open_lock(directory_descriptor, display_path, create=not existing_only)
        validate_directory_fd(directory_descriptor, display_path)

        key_names = [
            name
            for index in range(count)
            for name in (f"node{index}.seed", f"node{index}.public")
        ]
        expected_names = {LOCK_FILE, MODE_FILE, *key_names}
        actual_names = ensure_exact_inventory(directory_descriptor, expected_names, display_path)
        present_material = actual_names - {LOCK_FILE}
        if present_material and present_material != expected_names - {LOCK_FILE}:
            fail(f"incomplete member key set under {display_path}")

        if not present_material and existing_only:
            fail(f"member key set does not exist under {display_path}")
        if not present_material:
            create_file_atomic(directory_descriptor, display_path, MODE_FILE, (mode + "\n").encode("ascii"))
            for index in range(count):
                seed = bytes([index + 1]) * 32 if mode == "deterministic" else generate_seed()
                public = private_to_public(seed)
                if purpose == "members" and mode == "deterministic" \
                        and public.hex() != DEVNET_PUBLIC_KEYS[index]:
                    fail("deterministic devnet member identity does not match the frozen profile")
                create_file_atomic(
                    directory_descriptor,
                    display_path,
                    f"node{index}.seed",
                    (seed.hex() + "\n").encode("ascii"),
                )
                create_file_atomic(
                    directory_descriptor,
                    display_path,
                    f"node{index}.public",
                    (public.hex() + "\n").encode("ascii"),
                )

        if ensure_exact_inventory(directory_descriptor, expected_names, display_path) != expected_names:
            fail(f"incomplete member key set under {display_path}")
        retained_mode = read_mode_file(directory_descriptor, display_path)
        if retained_mode != mode:
            fail(f"member key set was created in {retained_mode} mode, not {mode} mode")

        public_keys: list[str] = []
        for index in range(count):
            seed_hex = read_key_file(directory_descriptor, display_path, f"node{index}.seed")
            public_hex = read_key_file(directory_descriptor, display_path, f"node{index}.public")
            derived = private_to_public(bytes.fromhex(seed_hex)).hex()
            if derived != public_hex:
                fail(f"member public key does not match node{index}.seed")
            if purpose == "members" and mode == "deterministic" and (
                seed_hex != f"{index + 1:02x}" * 32 or public_hex != DEVNET_PUBLIC_KEYS[index]
            ):
                fail("retained devnet member identity differs from the frozen deterministic profile")
            public_keys.append(public_hex)
        return public_keys
    finally:
        if lock_descriptor >= 0:
            os.close(lock_descriptor)
        os.close(directory_descriptor)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--mode", choices=("deterministic", "generated"), required=True)
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--purpose", choices=("members", "role-actors"), default="members")
    parser.add_argument("--existing-only", action="store_true")
    args = parser.parse_args()
    public_keys = ensure_keys(args.directory, args.mode, args.count,
                              args.existing_only, args.purpose)
    sys.stdout.write(",".join(public_keys) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
