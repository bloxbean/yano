#!/usr/bin/env python3
"""Deterministic tests for bounded Darwin argv/identity sampling."""

from __future__ import annotations

import ctypes
import errno
import importlib.util
import os
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest import mock


TOOL = Path(__file__).resolve().parents[1] / "tools" / "managed_process.py"
SPEC = importlib.util.spec_from_file_location("managed_process", TOOL)
assert SPEC is not None and SPEC.loader is not None
managed_process = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = managed_process
SPEC.loader.exec_module(managed_process)


class DarwinSnapshotTest(unittest.TestCase):
    PID = 4242
    UID = os.geteuid()
    TOKEN = "darwin:100:200"
    ARGV = ("python3", "worker.py")

    def test_second_sysctl_einval_is_classified_as_transient(self) -> None:
        self.addCleanup(ctypes.set_errno, 0)

        class FakeSysctl:
            def __init__(self) -> None:
                self.calls = 0
                self.argtypes = None
                self.restype = None

            def __call__(self, _mib, _count, output, size_pointer, _new, _new_size):
                self.calls += 1
                size = ctypes.cast(size_pointer, ctypes.POINTER(ctypes.c_size_t))
                if output is None:
                    size.contents.value = 64
                    return 0
                ctypes.set_errno(errno.EINVAL)
                return -1

        sysctl = FakeSysctl()
        with mock.patch.object(
            managed_process.ctypes,
            "CDLL",
            return_value=SimpleNamespace(sysctl=sysctl),
        ):
            with self.assertRaises(managed_process.DarwinArgvTransientError) as raised:
                managed_process.darwin_argv(self.PID)

        self.assertEqual(raised.exception.error, errno.EINVAL)
        self.assertEqual(raised.exception.operation, "read")
        self.assertEqual(sysctl.calls, 2)

    def test_transient_einval_then_stable_snapshot_succeeds(self) -> None:
        identity = (self.UID, self.TOKEN, False)
        transient = managed_process.DarwinArgvTransientError(
            self.PID, "read", errno.EINVAL
        )
        with (
            mock.patch.object(
                managed_process,
                "darwin_process_info",
                side_effect=(identity, identity, identity),
            ) as process_info,
            mock.patch.object(
                managed_process,
                "darwin_argv",
                side_effect=(transient, self.ARGV),
            ) as argv,
            mock.patch.object(managed_process.time, "sleep") as sleep,
        ):
            snapshot = managed_process.darwin_snapshot(self.PID)

        self.assertEqual(
            snapshot,
            managed_process.ProcessSnapshot(
                self.PID, self.UID, self.TOKEN, self.ARGV, False
            ),
        )
        self.assertEqual(process_info.call_count, 3)
        self.assertEqual(argv.call_count, 2)
        sleep.assert_called_once_with(managed_process.DARWIN_ARGV_RETRY_SECONDS)

    def test_repeated_transient_einval_fails_closed_after_bound(self) -> None:
        identity = (self.UID, self.TOKEN, False)
        transient = managed_process.DarwinArgvTransientError(
            self.PID, "read", errno.EINVAL
        )
        with (
            mock.patch.object(managed_process.sys, "platform", "darwin"),
            mock.patch.object(
                managed_process, "darwin_process_info", return_value=identity
            ) as process_info,
            mock.patch.object(
                managed_process, "darwin_argv", side_effect=transient
            ) as argv,
            mock.patch.object(managed_process.time, "sleep") as sleep,
            mock.patch.object(managed_process.os, "kill") as kill,
        ):
            with self.assertRaisesRegex(
                managed_process.ManagedProcessError,
                rf"consistent argv snapshot for PID {self.PID} after "
                rf"{managed_process.DARWIN_ARGV_SNAPSHOT_ATTEMPTS} attempts",
            ):
                managed_process.signal_exact(
                    self.PID, self.TOKEN, self.ARGV, managed_process.signal.SIGTERM
                )

        attempts = managed_process.DARWIN_ARGV_SNAPSHOT_ATTEMPTS
        self.assertEqual(process_info.call_count, attempts)
        self.assertEqual(argv.call_count, attempts)
        self.assertEqual(sleep.call_count, attempts - 1)
        kill.assert_not_called()

    def test_token_change_across_argv_sample_is_never_exact(self) -> None:
        original = (self.UID, self.TOKEN, False)
        replacement_token = "darwin:101:300"
        replacement = (self.UID, replacement_token, False)
        with (
            mock.patch.object(managed_process.sys, "platform", "darwin"),
            mock.patch.object(
                managed_process,
                "darwin_process_info",
                side_effect=(original, replacement, replacement, replacement),
            ) as process_info,
            mock.patch.object(
                managed_process, "darwin_argv", return_value=self.ARGV
            ) as argv,
            mock.patch.object(managed_process.time, "sleep") as sleep,
            mock.patch.object(managed_process.os, "kill") as kill,
        ):
            state = managed_process.signal_exact(
                self.PID, self.TOKEN, self.ARGV, managed_process.signal.SIGTERM
            )

        self.assertEqual(state, "absent")
        self.assertEqual(process_info.call_count, 4)
        self.assertEqual(argv.call_count, 2)
        sleep.assert_called_once_with(managed_process.DARWIN_ARGV_RETRY_SECONDS)
        kill.assert_not_called()

    def test_uid_change_across_argv_sample_is_never_exact(self) -> None:
        original = (self.UID, self.TOKEN, False)
        replacement = (self.UID + 1, self.TOKEN, False)
        with (
            mock.patch.object(managed_process.sys, "platform", "darwin"),
            mock.patch.object(
                managed_process,
                "darwin_process_info",
                side_effect=(original, replacement, replacement, replacement),
            ),
            mock.patch.object(
                managed_process, "darwin_argv", return_value=self.ARGV
            ),
            mock.patch.object(managed_process.time, "sleep"),
            mock.patch.object(managed_process.os, "kill") as kill,
        ):
            state = managed_process.signal_exact(
                self.PID, self.TOKEN, self.ARGV, managed_process.signal.SIGTERM
            )

        self.assertEqual(state, "untrusted")
        kill.assert_not_called()


if __name__ == "__main__":
    unittest.main()
