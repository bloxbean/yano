#!/usr/bin/env python3
"""Render the demo's bounded @KEY@ templates without putting values in argv."""

from __future__ import annotations

import os
from pathlib import Path
import re
import sys


MAX_TEMPLATE_BYTES = 1_048_576
MAX_SUBSTITUTION_BYTES = 1_048_576
KEY = re.compile(rb"[A-Z0-9_]+")
PLACEHOLDER = re.compile(rb"@[A-Z0-9_]+@")


def fail(message: str) -> "NoReturn":
    raise SystemExit(message)


def read_bounded(path: Path, limit: int) -> bytes:
    with path.open("rb") as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        fail(f"input exceeds {limit} bytes")
    return value


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: render_template.py INPUT OUTPUT")

    raw = sys.stdin.buffer.read(MAX_SUBSTITUTION_BYTES + 1)
    if len(raw) > MAX_SUBSTITUTION_BYTES:
        fail("template substitutions exceed the byte limit")
    parts = raw.split(b"\0")
    if not parts or parts[-1] != b"" or (len(parts) - 1) % 2 != 0:
        fail("invalid NUL-delimited template substitutions")

    substitutions: dict[bytes, bytes] = {}
    for offset in range(0, len(parts) - 1, 2):
        key, value = parts[offset], parts[offset + 1]
        if not KEY.fullmatch(key) or key in substitutions:
            fail("invalid or duplicate template substitution key")
        if b"\n" in value or b"\r" in value or PLACEHOLDER.search(value):
            fail("invalid template substitution value")
        substitutions[key] = value

    rendered = read_bounded(Path(sys.argv[1]), MAX_TEMPLATE_BYTES)
    for key, value in substitutions.items():
        rendered = rendered.replace(b"@" + key + b"@", value)
        if len(rendered) > MAX_TEMPLATE_BYTES:
            fail("rendered template exceeds the byte limit")

    output = Path(sys.argv[2])
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(rendered)


if __name__ == "__main__":
    main()
