#!/usr/bin/env python3
"""
Functional tests for destructive Yano devnet operations.

This script is intentionally separate from yano_endpoint_smoke.py. The smoke
runner validates endpoint routing with loose status expectations; this runner
executes an ordered devnet workflow and checks post-conditions.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from dataclasses import dataclass, field
from typing import Any

from yano_endpoint_smoke import HttpClient, ResponseData


JSON_HEADERS = {"Content-Type": "application/json"}


class TestFailure(Exception):
    pass


@dataclass
class TestStats:
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    failures: list[str] = field(default_factory=list)

    def pass_step(self, name: str, detail: str = "") -> None:
        self.passed += 1
        suffix = f": {detail}" if detail else ""
        print(f"PASS {name}{suffix}")

    def skip_step(self, name: str, reason: str) -> None:
        self.skipped += 1
        print(f"SKIP {name}: {reason}")

    def fail_step(self, name: str, message: str) -> None:
        self.failed += 1
        self.failures.append(f"{name}: {message}")
        print(f"FAIL {name}: {message}")


@dataclass(frozen=True)
class Tip:
    slot: int
    block: int
    epoch: int | None
    hash: str | None

    @staticmethod
    def from_block(block: dict[str, Any]) -> "Tip":
        return Tip(
            slot=int(block["slot"]),
            block=int(block.get("number", block.get("height"))),
            epoch=int(block["epoch"]) if block.get("epoch") is not None else None,
            hash=block.get("hash"),
        )

    def label(self) -> str:
        epoch = "n/a" if self.epoch is None else str(self.epoch)
        return f"slot={self.slot}, block={self.block}, epoch={epoch}"


class DevnetFunctionalRunner:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.client = HttpClient(args.base_url, args.timeout)
        self.stats = TestStats()
        self.snapshot_name = args.snapshot_name or f"functional-{int(time.time())}-{os.getpid()}"

    def run(self) -> int:
        if not self.step("devnet guard", self.assert_devnet):
            return self.finish()

        if self.args.include_shift:
            self.step("epoch shift", self.test_epoch_shift)

        try:
            baseline_tip = self.wait_for_tip()
        except TestFailure as e:
            self.stats.fail_step("latest block available", str(e))
            return self.finish()
        self.stats.pass_step("latest block available", baseline_tip.label())

        if self.args.include_catch_up:
            self.step("catch up", lambda: self.test_catch_up(self.latest_tip()))

        if not self.step("snapshot create", lambda: self.create_snapshot(self.snapshot_name)):
            return self.finish()

        try:
            snapshot_tip = self.snapshot_tip(self.snapshot_name)
        except TestFailure as e:
            self.stats.fail_step("snapshot lookup", str(e))
            self.step("snapshot cleanup", lambda: self.delete_snapshot(self.snapshot_name, required=True))
            return self.finish()

        if self.step("time advance", lambda: self.test_time_advance(snapshot_tip)):
            try:
                rollback_start = self.latest_tip()
            except TestFailure as e:
                self.stats.fail_step("rollback setup", str(e))
            else:
                self.step("rollback", lambda: self.test_rollback(rollback_start))

            try:
                after_rollback = self.latest_tip()
            except TestFailure as e:
                self.stats.fail_step("post-rollback setup", str(e))
            else:
                self.step("post-rollback advance", lambda: self.test_post_rollback_advance(after_rollback))

        try:
            before_restore = self.latest_tip()
        except TestFailure as e:
            self.stats.fail_step("snapshot restore setup", str(e))
            before_restore = snapshot_tip
        self.step("snapshot restore", lambda: self.test_restore(self.snapshot_name, snapshot_tip, before_restore))
        if self.args.post_restore_advance_slots > 0:
            self.step("post-restore advance", lambda: self.test_post_restore_advance(snapshot_tip))
        self.step("snapshot cleanup", lambda: self.delete_snapshot(self.snapshot_name, required=True))

        return self.finish()

    def finish(self) -> int:
        print(f"\nSummary: passed={self.stats.passed}, failed={self.stats.failed}, skipped={self.stats.skipped}")
        if self.stats.failures:
            print("\nFailures:")
            for failure in self.stats.failures:
                print(f"  - {failure}")
        return 1 if self.stats.failed else 0

    def step(self, name: str, fn) -> bool:
        try:
            fn()
            return True
        except TestFailure as e:
            self.stats.fail_step(name, str(e))
            return False
        except Exception as e:
            self.stats.fail_step(name, f"{type(e).__name__}: {e}")
            return False

    def assert_devnet(self) -> None:
        config = self.request_json("GET", "/node/config")
        if not config.get("devMode"):
            raise TestFailure("target is not devnet mode; refusing destructive tests")

        status = self.request_json("GET", "/node/status")
        if status.get("running") is False:
            raise TestFailure("node is not running")

        self.stats.pass_step("devnet guard", "devMode=true")

    def test_epoch_shift(self) -> None:
        body = {"epochs": self.args.shift_epochs}
        resp = self.request("POST", "/devnet/epochs/shift", body=body)
        if resp.status == 200:
            value = self.require_json_object(resp, "/devnet/epochs/shift")
            shift_millis = int(value.get("shift_millis", 0))
            if shift_millis <= 0:
                raise TestFailure(f"expected positive shift_millis, got {shift_millis}")
            if not value.get("new_system_start"):
                raise TestFailure("missing new_system_start")
            self.wait_for_tip()
            self.stats.pass_step("epoch shift", f"epochs={self.args.shift_epochs}, shift_millis={shift_millis}")
            return

        if resp.status == 409 and not self.args.require_shift:
            self.stats.skip_step("epoch shift", self.error_text(resp) or "shift not applicable to this running devnet")
            return

        raise TestFailure(self.status_failure(resp, [200] if self.args.require_shift else [200, 409]))

    def test_catch_up(self, before: Tip) -> None:
        resp = self.request("POST", "/devnet/epochs/catch-up", body={})
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))

        value = self.require_json_object(resp, "/devnet/epochs/catch-up")
        new_slot = int(value["new_slot"])
        new_block = int(value["new_block_number"])
        produced = int(value["blocks_produced"])

        if new_slot < before.slot:
            raise TestFailure(f"catch-up moved slot backwards: before={before.slot}, after={new_slot}")
        if new_block < before.block:
            raise TestFailure(f"catch-up moved block backwards: before={before.block}, after={new_block}")

        self.stats.pass_step("catch up", f"blocks_produced={produced}, new_slot={new_slot}, new_block={new_block}")

    def create_snapshot(self, name: str) -> None:
        self.delete_snapshot(name, required=False)
        resp = self.request("POST", "/devnet/snapshot", body={"name": name})
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))
        value = self.require_json_object(resp, "/devnet/snapshot")
        if value.get("name") != name:
            raise TestFailure(f"snapshot name mismatch: expected {name}, got {value.get('name')}")
        if int(value.get("block_number", -1)) < 0 or int(value.get("slot", -1)) < 0:
            raise TestFailure(f"invalid snapshot coordinates: {value}")
        self.stats.pass_step("snapshot create", f"name={name}, slot={value['slot']}, block={value['block_number']}")

    def snapshot_tip(self, name: str) -> Tip:
        snapshots = self.request_json("GET", "/devnet/snapshots")
        if not isinstance(snapshots, list):
            raise TestFailure("/devnet/snapshots did not return an array")
        for item in snapshots:
            if isinstance(item, dict) and item.get("name") == name:
                return Tip(slot=int(item["slot"]), block=int(item["block_number"]), epoch=None, hash=None)
        raise TestFailure(f"snapshot {name} not found after creation")

    def test_time_advance(self, before: Tip) -> None:
        resp = self.request("POST", "/devnet/time/advance", body={"slots": self.args.advance_slots})
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))

        value = self.require_json_object(resp, "/devnet/time/advance")
        new_slot = int(value["new_slot"])
        new_block = int(value["new_block_number"])
        produced = int(value["blocks_produced"])

        if produced <= 0:
            raise TestFailure(f"expected blocks_produced > 0, got {produced}")
        if new_slot <= before.slot:
            raise TestFailure(f"slot did not advance: before={before.slot}, after={new_slot}")
        if new_block <= before.block:
            raise TestFailure(f"block did not advance: before={before.block}, after={new_block}")

        self.stats.pass_step("time advance", f"slots={self.args.advance_slots}, produced={produced}, new={new_slot}/{new_block}")

    def test_rollback(self, before: Tip) -> None:
        if before.block < self.args.rollback_count:
            raise TestFailure(f"not enough blocks to rollback count={self.args.rollback_count}: tip={before.block}")

        resp = self.request("POST", "/devnet/rollback", body={"count": self.args.rollback_count})
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))

        value = self.require_json_object(resp, "/devnet/rollback")
        new_slot = int(value["slot"])
        new_block = int(value["block_number"])

        if new_slot >= before.slot:
            raise TestFailure(f"rollback did not reduce slot: before={before.slot}, after={new_slot}")
        if new_block >= before.block:
            raise TestFailure(f"rollback did not reduce block: before={before.block}, after={new_block}")

        self.stats.pass_step("rollback", f"count={self.args.rollback_count}, before={before.block}, after={new_block}")

    def test_post_rollback_advance(self, before: Tip) -> None:
        resp = self.request("POST", "/devnet/time/advance", body={"slots": 1})
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))

        value = self.require_json_object(resp, "/devnet/time/advance")
        new_slot = int(value["new_slot"])
        new_block = int(value["new_block_number"])
        produced = int(value["blocks_produced"])

        if produced <= 0:
            raise TestFailure(f"expected post-rollback blocks_produced > 0, got {produced}")
        if new_slot <= before.slot:
            raise TestFailure(f"post-rollback slot did not advance: before={before.slot}, after={new_slot}")
        if new_block <= before.block:
            raise TestFailure(f"post-rollback block did not advance: before={before.block}, after={new_block}")

        self.stats.pass_step("post-rollback advance", f"produced={produced}, new={new_slot}/{new_block}")

    def test_restore(self, name: str, snapshot_tip: Tip, before_restore: Tip) -> None:
        resp = self.request("POST", f"/devnet/restore/{name}", body={})
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))

        value = self.require_json_object(resp, f"/devnet/restore/{name}")
        restored_slot = int(value["slot"])
        restored_block = int(value["block_number"])

        if restored_block > before_restore.block:
            raise TestFailure(f"restore moved past pre-restore block: before={before_restore.block}, restored={restored_block}")
        if restored_slot > before_restore.slot:
            raise TestFailure(f"restore moved past pre-restore slot: before={before_restore.slot}, restored={restored_slot}")
        if restored_block < snapshot_tip.block:
            raise TestFailure(f"restore went before snapshot block: snapshot={snapshot_tip.block}, restored={restored_block}")

        self.stats.pass_step(
            "snapshot restore",
            f"snapshot_block={snapshot_tip.block}, restored_block={restored_block}, before_restore={before_restore.block}",
        )

    def test_post_restore_advance(self, snapshot_tip: Tip) -> None:
        before = self.latest_tip()
        resp = self.request("POST", "/devnet/time/advance", body={"slots": self.args.post_restore_advance_slots})
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))

        value = self.require_json_object(resp, "/devnet/time/advance")
        new_slot = int(value["new_slot"])
        new_block = int(value["new_block_number"])
        produced = int(value["blocks_produced"])

        if produced <= 0:
            raise TestFailure(f"expected post-restore blocks_produced > 0, got {produced}")
        if new_slot <= before.slot:
            raise TestFailure(f"post-restore slot did not advance: before={before.slot}, after={new_slot}")
        if new_block <= before.block:
            raise TestFailure(f"post-restore block did not advance: before={before.block}, after={new_block}")
        if new_block < snapshot_tip.block:
            raise TestFailure(f"post-restore block is before snapshot: snapshot={snapshot_tip.block}, after={new_block}")

        self.stats.pass_step(
            "post-restore advance",
            f"slots={self.args.post_restore_advance_slots}, produced={produced}, new={new_slot}/{new_block}",
        )

    def delete_snapshot(self, name: str, required: bool) -> None:
        resp = self.request("DELETE", f"/devnet/snapshot/{name}")
        if resp.status == 200:
            if required:
                self.stats.pass_step("snapshot cleanup", f"name={name}")
            return
        if not required and resp.status in {400, 404, 500}:
            return
        raise TestFailure(self.status_failure(resp, [200]))

    def wait_for_tip(self) -> Tip:
        deadline = time.monotonic() + self.args.wait_timeout
        last_error = ""
        while time.monotonic() < deadline:
            resp = self.request("GET", "/blocks/latest", parse_json=True)
            if resp.status == 200 and isinstance(resp.json_value, dict):
                return Tip.from_block(resp.json_value)
            last_error = self.error_text(resp) or f"status={resp.status}"
            time.sleep(self.args.poll_interval)
        raise TestFailure(f"latest block unavailable after {self.args.wait_timeout}s: {last_error}")

    def latest_tip(self) -> Tip:
        return Tip.from_block(self.request_json("GET", "/blocks/latest"))

    def request_json(self, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        resp = self.request(method, path, body=body)
        if resp.status != 200:
            raise TestFailure(self.status_failure(resp, [200]))
        return self.require_json(resp, path)

    def request(
            self,
            method: str,
            path: str,
            body: dict[str, Any] | None = None,
            parse_json: bool = True) -> ResponseData:
        encoded = json.dumps(body) if body is not None else None
        headers = JSON_HEADERS if body is not None else {}
        return self.client.request(method, path, body=encoded, headers=headers, parse_json=parse_json)

    def require_json_object(self, resp: ResponseData, path: str) -> dict[str, Any]:
        value = self.require_json(resp, path)
        if not isinstance(value, dict):
            raise TestFailure(f"{path} did not return a JSON object: {resp.text[:200]}")
        return value

    def require_json(self, resp: ResponseData, path: str) -> Any:
        if resp.error:
            raise TestFailure(f"{path} request failed: {resp.error}")
        if resp.json_value is None:
            raise TestFailure(f"{path} did not return JSON: {resp.text[:200]}")
        return resp.json_value

    def status_failure(self, resp: ResponseData, expected: list[int]) -> str:
        body = self.error_text(resp) or resp.text.replace("\n", " ")[:300]
        return f"expected status {expected}, got {resp.status}; body={body}"

    def error_text(self, resp: ResponseData) -> str:
        if resp.error:
            return resp.error
        if isinstance(resp.json_value, dict) and resp.json_value.get("error"):
            return str(resp.json_value["error"])
        return resp.text.replace("\n", " ")[:300]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Functional tests for destructive Yano devnet operations.")
    parser.add_argument("--base-url", default=os.getenv("YANO_E2E_BASE_URL", "http://localhost:7070/api/v1"),
                        help="Base API URL, e.g. http://localhost:7070/api/v1")
    parser.add_argument("--timeout", type=float, default=float(os.getenv("YANO_E2E_TIMEOUT", "10")))
    parser.add_argument("--wait-timeout", type=float, default=30.0)
    parser.add_argument("--poll-interval", type=float, default=0.5)
    parser.add_argument("--snapshot-name", help="Snapshot name to use. Defaults to a unique functional-* name.")
    parser.add_argument("--advance-slots", type=int, default=5, help="Slots to advance in the bounded time-advance check.")
    parser.add_argument("--post-restore-advance-slots", type=int, default=0,
                        help="Optional extra slots to advance after restore; useful for crossing an epoch boundary.")
    parser.add_argument("--rollback-count", type=int, default=3, help="Blocks to rollback in count mode.")
    parser.add_argument("--include-catch-up", action="store_true",
                        help="Run /devnet/epochs/catch-up. This can produce many blocks on shifted devnets.")
    parser.add_argument("--include-shift", action="store_true",
                        help="Run /devnet/epochs/shift before the rest of the workflow. Use only on fresh past-time-travel devnets.")
    parser.add_argument("--shift-epochs", type=int, default=1)
    parser.add_argument("--require-shift", action="store_true",
                        help="Fail if epoch shift returns 409 instead of treating it as not applicable.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.advance_slots <= 0:
        print("--advance-slots must be positive")
        return 2
    if args.post_restore_advance_slots < 0:
        print("--post-restore-advance-slots must be >= 0")
        return 2
    if args.rollback_count <= 0:
        print("--rollback-count must be positive")
        return 2
    if args.shift_epochs <= 0:
        print("--shift-epochs must be positive")
        return 2
    return DevnetFunctionalRunner(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
