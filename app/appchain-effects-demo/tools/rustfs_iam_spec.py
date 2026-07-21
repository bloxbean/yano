#!/usr/bin/env python3
"""Build and verify the private immutable RustFS IAM bootstrap specification."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import tempfile
from pathlib import Path


MAX_VALUE_BYTES = 4096
MAX_SPEC_BYTES = 65_536


def read_private_line(path: Path) -> str:
    resolved = path.expanduser().absolute()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(resolved, flags)
    try:
        before = os.fstat(descriptor)
        mode = stat.S_IMODE(before.st_mode)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or before.st_nlink != 1 or mode & 0o077 or not mode & 0o400
                or mode & 0o111 or not 1 <= before.st_size <= MAX_VALUE_BYTES):
            raise ValueError("unsafe access-key file")
        raw = bytearray()
        while len(raw) <= MAX_VALUE_BYTES:
            chunk = os.read(descriptor, MAX_VALUE_BYTES + 1 - len(raw))
            if not chunk:
                break
            raw.extend(chunk)
        after = os.fstat(descriptor)
        if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
                or len(raw) != before.st_size):
            raise ValueError("access-key file changed while reading")
    finally:
        os.close(descriptor)
    if raw.endswith(b"\n"):
        del raw[-1]
    if (not raw or b"\n" in raw or b"\r" in raw
            or any(byte < 0x21 or byte > 0x7e for byte in raw)):
        raise ValueError("access key must contain one printable ASCII line")
    return raw.decode("ascii")


def policy(statements: list[dict[str, object]]) -> str:
    return json.dumps({"Version": "2012-10-17", "Statement": statements},
                      sort_keys=True, separators=(",", ":"))


def build_document(root_access: str, runner_access: str, executor_access: str) -> dict[str, object]:
    if len({root_access, runner_access, executor_access}) != 3:
        raise ValueError("S3 access keys must be distinct")

    bucket_resources = [
        "arn:aws:s3:::evidence-staging",
        "arn:aws:s3:::evidence-archive",
    ]
    object_resources = [
        "arn:aws:s3:::evidence-staging/*",
        "arn:aws:s3:::evidence-archive/*",
    ]
    common_bucket_actions = [
        "s3:GetBucketLocation",
        "s3:GetBucketVersioning",
    ]
    forbidden_mutations = [
        "s3:DeleteBucket",
        "s3:DeleteBucketPolicy",
        "s3:DeleteObject",
        "s3:DeleteObjectTagging",
        "s3:DeleteObjectVersion",
        "s3:PutBucketLifecycle",
        "s3:PutBucketObjectLockConfiguration",
        "s3:PutObjectLegalHold",
        "s3:PutObjectRetention",
    ]
    policies = [
        {
            "name": "YanoS3RunnerV1",
            "content": policy([
                {
                    "Sid": "RunnerBucketDiscovery",
                    "Effect": "Allow",
                    "Action": common_bucket_actions,
                    "Resource": bucket_resources,
                },
                {
                    "Sid": "RunnerListsOnlyIncomingPrefix",
                    "Effect": "Allow",
                    "Action": ["s3:ListBucket", "s3:ListBucketVersions"],
                    "Resource": "arn:aws:s3:::evidence-staging",
                    "Condition": {"StringLike": {"s3:prefix": [
                        "incoming/v1", "incoming/v1/*",
                    ]}},
                },
                {
                    "Sid": "RunnerListsOnlyVerifiedPrefix",
                    "Effect": "Allow",
                    "Action": ["s3:ListBucket", "s3:ListBucketVersions"],
                    "Resource": "arn:aws:s3:::evidence-archive",
                    "Condition": {"StringLike": {"s3:prefix": [
                        "verified/v1", "verified/v1/*",
                    ]}},
                },
                {
                    "Sid": "RunnerStagesIncomingEvidence",
                    "Effect": "Allow",
                    "Action": ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"],
                    "Resource": "arn:aws:s3:::evidence-staging/incoming/v1/*",
                },
                {
                    "Sid": "RunnerReadsVerifiedEvidence",
                    "Effect": "Allow",
                    "Action": ["s3:GetObject", "s3:GetObjectVersion"],
                    "Resource": "arn:aws:s3:::evidence-archive/verified/v1/*",
                },
                {
                    "Sid": "RunnerNeverDeletesOrChangesRetention",
                    "Effect": "Deny",
                    "Action": forbidden_mutations + ["s3:PutBucketVersioning"],
                    "Resource": bucket_resources + object_resources,
                },
            ]),
        },
        {
            "name": "YanoS3ExecutorV1",
            "content": policy([
                {
                    "Sid": "ExecutorBucketDiscovery",
                    "Effect": "Allow",
                    "Action": common_bucket_actions,
                    "Resource": bucket_resources,
                },
                {
                    "Sid": "ExecutorListsOnlyIncomingPrefix",
                    "Effect": "Allow",
                    "Action": ["s3:ListBucket", "s3:ListBucketVersions"],
                    "Resource": "arn:aws:s3:::evidence-staging",
                    "Condition": {"StringLike": {"s3:prefix": [
                        "incoming/v1", "incoming/v1/*",
                    ]}},
                },
                {
                    "Sid": "ExecutorListsOnlyVerifiedPrefix",
                    "Effect": "Allow",
                    "Action": ["s3:ListBucket", "s3:ListBucketVersions"],
                    "Resource": "arn:aws:s3:::evidence-archive",
                    "Condition": {"StringLike": {"s3:prefix": [
                        "verified/v1", "verified/v1/*",
                    ]}},
                },
                {
                    "Sid": "ExecutorReadsIncomingEvidence",
                    "Effect": "Allow",
                    "Action": ["s3:GetObject", "s3:GetObjectVersion"],
                    "Resource": "arn:aws:s3:::evidence-staging/incoming/v1/*",
                },
                {
                    "Sid": "ExecutorWritesVerifiedEvidence",
                    "Effect": "Allow",
                    "Action": ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"],
                    "Resource": "arn:aws:s3:::evidence-archive/verified/v1/*",
                },
                {
                    "Sid": "ExecutorNeverDeletesOrChangesRetention",
                    "Effect": "Deny",
                    "Action": forbidden_mutations + ["s3:PutBucketVersioning"],
                    "Resource": bucket_resources + object_resources,
                },
            ]),
        },
    ]
    roles = [
        {
            "name": "bootstrap",
            "principalType": "built-in-root",
            "accessKey": root_access,
        },
        {
            "name": "runner",
            "principalType": "managed-user",
            "accessKey": runner_access,
            "policyName": "YanoS3RunnerV1",
        },
        {
            "name": "executor",
            "principalType": "managed-user",
            "accessKey": executor_access,
            "policyName": "YanoS3ExecutorV1",
        },
    ]
    return {
        "schemaVersion": 1,
        "provider": "rustfs",
        "roles": roles,
        "policies": policies,
    }


def canonical(document: dict[str, object]) -> bytes:
    return (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def safe_existing(path: Path) -> bytes | None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except FileNotFoundError:
        return None
    try:
        before = os.fstat(descriptor)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or before.st_nlink != 1 or stat.S_IMODE(before.st_mode) != 0o600
                or not 1 <= before.st_size <= MAX_SPEC_BYTES):
            raise ValueError("unsafe existing RustFS IAM specification")
        content = bytearray()
        while len(content) <= MAX_SPEC_BYTES:
            chunk = os.read(descriptor, MAX_SPEC_BYTES + 1 - len(content))
            if not chunk:
                break
            content.extend(chunk)
        after = os.fstat(descriptor)
        if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
                or len(content) != before.st_size):
            raise ValueError("RustFS IAM specification changed while reading")
        return bytes(content)
    finally:
        os.close(descriptor)


def install(path: Path, content: bytes) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    parent = path.parent.lstat()
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid()
            or stat.S_IMODE(parent.st_mode) != 0o700):
        raise ValueError("specification parent must be owner-only")
    existing = safe_existing(path)
    if existing is not None:
        if existing != content:
            raise ValueError("existing RustFS IAM specification does not match roles/policies")
        return
    descriptor, temporary = tempfile.mkstemp(prefix=".rustfs-iam-spec-", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        offset = 0
        while offset < len(content):
            offset += os.write(descriptor, content[offset:])
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = -1
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--root-access", type=Path, required=True)
    parser.add_argument("--runner-access", type=Path, required=True)
    parser.add_argument("--executor-access", type=Path, required=True)
    args = parser.parse_args()
    content = canonical(build_document(
        read_private_line(args.root_access),
        read_private_line(args.runner_access),
        read_private_line(args.executor_access),
    ))
    install(args.output.expanduser().absolute(), content)
    print(hashlib.sha256(content).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
