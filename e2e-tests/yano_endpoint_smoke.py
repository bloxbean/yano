#!/usr/bin/env python3
"""
Smoke-test Yano REST endpoints against a running instance.

The tests intentionally validate routing and endpoint behavior, not ledger
correctness. Feature-dependent endpoints accept 404/503 when the backing index
or data is legitimately unavailable on the target node.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable


ZERO_TX = "0" * 64
ZERO_HASH_28 = "0" * 56
DEFAULT_ADDRESS = "addr_test1qpzry9x8gf2tvdw0s3jn54khce6mua7lg5f0u37t9k0cc6qf9s6q"
DEFAULT_STAKE_ADDRESS = "stake_test1upzry9x8gf2tvdw0s3jn54khce6mua7lg5f0u37t9k0cc6qzq9trm"
DEFAULT_DREP_ID = "drep1invalid"
DEFAULT_POOL_ID = "pool1invalid"


@dataclass
class ResponseData:
    status: int
    text: str
    headers: dict[str, str]
    elapsed_ms: int
    json_value: Any = None
    error: str | None = None


@dataclass
class Context:
    args: argparse.Namespace
    client: "HttpClient"
    fixtures: dict[str, Any] = field(default_factory=dict)

    def get(self, key: str, default: Any = None) -> Any:
        value = self.fixtures.get(key, default)
        return value if value not in ("", None) else default

    @property
    def dev_mode(self) -> bool:
        return bool(self.get("dev_mode", False))

    @property
    def node_running(self) -> bool:
        return bool(self.get("node_running", False))


PathFn = Callable[[Context], str]
BodyFn = Callable[[Context], bytes | str | None]
ExpectedFn = Callable[[Context], set[int]]
SkipFn = Callable[[Context], str | None]


@dataclass
class Case:
    name: str
    method: str
    path: str | PathFn
    expected: set[int] | ExpectedFn
    body: bytes | str | BodyFn | None = None
    headers: dict[str, str] = field(default_factory=dict)
    openapi_path: str | None = None
    debug: bool = False
    heavy: bool = False
    mutating: bool = False
    node_control: bool = False
    parse_json: bool = True
    skip: SkipFn | None = None

    def resolved_path(self, ctx: Context) -> str:
        return self.path(ctx) if callable(self.path) else self.path

    def resolved_body(self, ctx: Context) -> bytes | str | None:
        return self.body(ctx) if callable(self.body) else self.body

    def expected_statuses(self, ctx: Context) -> set[int]:
        return self.expected(ctx) if callable(self.expected) else self.expected

    def coverage_path(self) -> str:
        return self.openapi_path or (self.path if isinstance(self.path, str) else "")


class HttpClient:
    def __init__(self, base_url: str, timeout: float):
        self.base_url = base_url.rstrip("/")
        parsed = urllib.parse.urlparse(self.base_url)
        self.root_url = urllib.parse.urlunparse((parsed.scheme, parsed.netloc, "", "", "", ""))
        self.api_prefix = parsed.path.rstrip("/") or ""
        self.timeout = timeout

    def url(self, path: str) -> str:
        if path.startswith("http://") or path.startswith("https://"):
            return path
        return self.base_url + "/" + path.lstrip("/")

    def root_path_url(self, path: str) -> str:
        return self.root_url + "/" + path.lstrip("/")

    def request(
            self,
            method: str,
            path: str,
            body: bytes | str | None = None,
            headers: dict[str, str] | None = None,
            parse_json: bool = True) -> ResponseData:
        headers = dict(headers or {})
        data: bytes | None
        if body is None:
            data = None
        elif isinstance(body, bytes):
            data = body
        else:
            data = body.encode("utf-8")

        req = urllib.request.Request(self.url(path), data=data, method=method.upper(), headers=headers)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                status = resp.status
                response_headers = {k.lower(): v for k, v in resp.headers.items()}
        except urllib.error.HTTPError as e:
            raw = e.read()
            status = e.code
            response_headers = {k.lower(): v for k, v in e.headers.items()}
        except Exception as e:
            elapsed = int((time.monotonic() - started) * 1000)
            return ResponseData(0, "", {}, elapsed, None, str(e))

        elapsed = int((time.monotonic() - started) * 1000)
        text = raw.decode("utf-8", errors="replace")
        json_value = None
        if parse_json and text.strip():
            content_type = response_headers.get("content-type", "")
            looks_json = "json" in content_type or text.lstrip().startswith(("{", "[", '"'))
            if looks_json:
                try:
                    json_value = json.loads(text)
                except json.JSONDecodeError:
                    pass
        return ResponseData(status, text, response_headers, elapsed, json_value)


def statuses(*codes: int) -> set[int]:
    return set(codes)


def ok_or_unavailable() -> set[int]:
    return statuses(200, 404, 503)


def ok_or_not_found() -> set[int]:
    return statuses(200, 404)


def feature_list_statuses() -> set[int]:
    return statuses(200, 503)


def pool_expected(ctx: Context) -> set[int]:
    return ok_or_unavailable() if ctx.get("pool_id") else statuses(400, 503)


def drep_expected(ctx: Context) -> set[int]:
    return ok_or_unavailable() if ctx.get("drep_id") else statuses(400, 503)


def proposal_expected(ctx: Context) -> set[int]:
    return ok_or_unavailable()


def devnet_expected(ctx: Context) -> set[int]:
    if not ctx.dev_mode:
        return statuses(403)
    return statuses(200, 400, 404, 409)


def skip_if_no_fixture(key: str) -> SkipFn:
    def inner(ctx: Context) -> str | None:
        return None if ctx.get(key) else f"missing fixture: {key}"
    return inner


def safe_running_conflict_only(ctx: Context) -> str | None:
    return None if ctx.node_running else "would start node; pass --include-node-control on a disposable node"


def recover_conflict_only(ctx: Context) -> str | None:
    return None if ctx.node_running else "would recover chainstate; pass --include-node-control on a stopped disposable node"


def stake_addr(ctx: Context) -> str:
    return urllib.parse.quote(ctx.get("stake_address", DEFAULT_STAKE_ADDRESS), safe="")


def address(ctx: Context) -> str:
    return urllib.parse.quote(ctx.get("address", DEFAULT_ADDRESS), safe="")


def tx_hash(ctx: Context) -> str:
    return ctx.get("tx_hash", ZERO_TX)


def payment_credential(ctx: Context) -> str:
    return ctx.get("payment_credential", ZERO_HASH_28)


def script_hash(ctx: Context) -> str:
    return ctx.get("script_hash", ZERO_HASH_28)


def pool_id(ctx: Context) -> str:
    return urllib.parse.quote(ctx.get("pool_id", DEFAULT_POOL_ID), safe="")


def pool_hash(ctx: Context) -> str:
    return ctx.get("pool_hash", ZERO_HASH_28)


def drep_id(ctx: Context) -> str:
    return urllib.parse.quote(ctx.get("drep_id", DEFAULT_DREP_ID), safe="")


def epoch(ctx: Context) -> int:
    return int(ctx.get("epoch", 0))


def reward_epoch(ctx: Context) -> int:
    return max(2, epoch(ctx))


def proposal_tx(ctx: Context) -> str:
    return ctx.get("proposal_tx_hash", ZERO_TX)


def proposal_cert(ctx: Context) -> int:
    return int(ctx.get("proposal_cert_index", 0))


def json_body(value: Any) -> BodyFn:
    def inner(_: Context) -> str:
        return json.dumps(value)
    return inner


def cases() -> list[Case]:
    json_headers = {"Content-Type": "application/json"}
    text_headers = {"Content-Type": "text/plain"}
    cbor_headers = {"Content-Type": "application/cbor"}
    octet_headers = {"Content-Type": "application/octet-stream"}

    return [
        Case("node status", "GET", "/node/status", statuses(200)),
        Case("node start while running", "POST", "/node/start", statuses(409), skip=safe_running_conflict_only),
        Case("node stop", "POST", "/node/stop", statuses(200, 409), node_control=True),
        Case("node tip", "GET", "/node/tip", ok_or_not_found()),
        Case("node config", "GET", "/node/config", statuses(200)),
        Case("node binary tx submit empty", "POST", "/node/tx/submit", statuses(400), body=b"", headers=octet_headers),
        Case("node protocol params", "GET", "/node/protocol-params", ok_or_not_found()),
        Case("node epoch calc status", "GET", "/node/epoch-calc-status", statuses(200)),
        Case("node recover while running", "POST", "/node/recover", statuses(409), skip=recover_conflict_only),
        Case("node epoch nonce", "GET", "/node/epoch-nonce", ok_or_not_found()),
        Case("status", "GET", "/status", statuses(200)),
        Case("genesis", "GET", "/genesis", ok_or_not_found()),
        Case("network", "GET", "/network", ok_or_unavailable()),

        Case("latest block", "GET", "/blocks/latest", ok_or_not_found()),
        Case("block by number", "GET", lambda c: f"/blocks/{c.get('block_number')}", statuses(200),
             openapi_path="/blocks/{hashOrNumber}", skip=skip_if_no_fixture("block_number")),
        Case("block by hash", "GET", lambda c: f"/blocks/{c.get('block_hash')}", statuses(200),
             openapi_path="/blocks/{hashOrNumber}", skip=skip_if_no_fixture("block_hash")),

        Case("tx by hash", "GET", lambda c: f"/txs/{tx_hash(c)}",
             lambda c: statuses(200, 404, 503) if c.get("tx_hash") else statuses(404, 503),
             openapi_path="/txs/{txHash}"),
        Case("tx utxos by hash", "GET", lambda c: f"/txs/{tx_hash(c)}/utxos",
             lambda c: statuses(200, 404, 503) if c.get("tx_hash") else statuses(404, 503),
             openapi_path="/txs/{txHash}/utxos"),
        Case("blockfrost tx submit invalid text", "POST", "/tx/submit", statuses(400), body="not-hex", headers=text_headers),
        Case("tx evaluate invalid text", "POST", "/utils/txs/evaluate", statuses(200), body="not-hex", headers=text_headers),

        Case("address utxos", "GET", lambda c: f"/addresses/{address(c)}/utxos?count=1", feature_list_statuses(),
             openapi_path="/addresses/{address}/utxos"),
        Case("address utxos by asset", "GET", lambda c: f"/addresses/{address(c)}/utxos/lovelace?count=1", feature_list_statuses(),
             openapi_path="/addresses/{address}/utxos/{asset}"),
        Case("utxo by outpoint", "GET", lambda c: f"/utxos/{tx_hash(c)}/{int(c.get('utxo_index', 0))}",
             lambda c: statuses(200, 404, 503) if c.get("tx_hash") else statuses(404, 503),
             openapi_path="/utxos/{txHash}/{index}"),
        Case("credential utxos", "GET", lambda c: f"/credentials/{payment_credential(c)}/utxos?count=1", feature_list_statuses(),
             openapi_path="/credentials/{paymentCredential}/utxos"),
        Case("script cbor", "GET", lambda c: f"/scripts/{script_hash(c)}/cbor", ok_or_unavailable(),
             openapi_path="/scripts/{script_hash}/cbor"),

        Case("latest epoch", "GET", "/epochs/latest", statuses(200)),
        Case("latest epoch parameters", "GET", "/epochs/latest/parameters", ok_or_unavailable()),
        Case("epoch parameters", "GET", lambda c: f"/epochs/{epoch(c)}/parameters", ok_or_unavailable(),
             openapi_path="/epochs/{number}/parameters"),
        Case("latest adapot", "GET", "/epochs/latest/adapot", ok_or_unavailable()),
        Case("epoch adapot", "GET", lambda c: f"/epochs/{epoch(c)}/adapot", ok_or_unavailable(),
             openapi_path="/epochs/{number}/adapot"),
        Case("adapot list", "GET", lambda c: f"/epochs/adapots?from={max(0, epoch(c) - 2)}&to={epoch(c)}&count=3",
             feature_list_statuses(), openapi_path="/epochs/adapots"),
        Case("latest total stake", "GET", "/epochs/latest/stake/total", ok_or_unavailable()),
        Case("epoch total stake", "GET", lambda c: f"/epochs/{epoch(c)}/stake/total", ok_or_unavailable(),
             openapi_path="/epochs/{number}/stake/total"),
        Case("pool stake delegators", "GET", lambda c: f"/epochs/{epoch(c)}/stakes/{pool_id(c)}?count=1",
             pool_expected, openapi_path="/epochs/{number}/stakes/{poolId}"),
        Case("pool stake", "GET", lambda c: f"/epochs/{epoch(c)}/stake/pool/{pool_id(c)}",
             pool_expected, openapi_path="/epochs/{number}/stake/pool/{poolId}"),

        Case("account", "GET", lambda c: f"/accounts/{stake_addr(c)}",
             lambda c: ok_or_unavailable() if c.get("stake_address") else statuses(400),
             openapi_path="/accounts/{stakeAddress}"),
        Case("account current stake", "GET", lambda c: f"/accounts/{stake_addr(c)}/stake",
             lambda c: ok_or_unavailable() if c.get("stake_address") else statuses(400, 404, 503),
             openapi_path="/accounts/{stakeAddress}/stake"),
        Case("account stake by epoch", "GET", lambda c: f"/accounts/{stake_addr(c)}/stake/{epoch(c)}",
             lambda c: ok_or_unavailable() if c.get("stake_address") else statuses(400, 503),
             openapi_path="/accounts/{stakeAddress}/stake/{epoch}"),
        Case("account withdrawals", "GET", lambda c: f"/accounts/{stake_addr(c)}/withdrawals?count=1",
             lambda c: feature_list_statuses() if c.get("stake_address") else statuses(400, 503),
             openapi_path="/accounts/{stakeAddress}/withdrawals"),
        Case("account delegation history", "GET", lambda c: f"/accounts/{stake_addr(c)}/delegations?count=1",
             lambda c: feature_list_statuses() if c.get("stake_address") else statuses(400, 503),
             openapi_path="/accounts/{stakeAddress}/delegations"),
        Case("account registration history", "GET", lambda c: f"/accounts/{stake_addr(c)}/registrations?count=1",
             lambda c: feature_list_statuses() if c.get("stake_address") else statuses(400, 503),
             openapi_path="/accounts/{stakeAddress}/registrations"),
        Case("account mir history", "GET", lambda c: f"/accounts/{stake_addr(c)}/mirs?count=1",
             lambda c: feature_list_statuses() if c.get("stake_address") else statuses(400, 503),
             openapi_path="/accounts/{stakeAddress}/mirs"),
        Case("list registrations", "GET", "/accounts/registrations?count=1", feature_list_statuses(),
             openapi_path="/accounts/registrations"),
        Case("list delegations", "GET", "/accounts/delegations?count=1", feature_list_statuses(),
             openapi_path="/accounts/delegations"),
        Case("list drep delegations", "GET", "/accounts/drep-delegations?count=1", feature_list_statuses(),
             openapi_path="/accounts/drep-delegations"),
        Case("list pools", "GET", "/accounts/pools?count=1", feature_list_statuses(), openapi_path="/accounts/pools"),
        Case("list pool retirements", "GET", "/accounts/pool-retirements?count=1", feature_list_statuses(),
             openapi_path="/accounts/pool-retirements"),

        Case("list proposals", "GET", "/governance/proposals?status=all&count=1", feature_list_statuses(),
             openapi_path="/governance/proposals"),
        Case("proposal", "GET", lambda c: f"/governance/proposals/{proposal_tx(c)}/{proposal_cert(c)}",
             proposal_expected, openapi_path="/governance/proposals/{txHash}/{certIndex}"),
        Case("proposal votes", "GET", lambda c: f"/governance/proposals/{proposal_tx(c)}/{proposal_cert(c)}/votes?count=1",
             feature_list_statuses(), openapi_path="/governance/proposals/{txHash}/{certIndex}/votes"),
        Case("list dreps", "GET", "/governance/dreps?count=1", feature_list_statuses(), openapi_path="/governance/dreps"),
        Case("drep", "GET", lambda c: f"/governance/dreps/{drep_id(c)}", drep_expected,
             openapi_path="/governance/dreps/{drepId}"),
        Case("drep distribution latest", "GET", lambda c: f"/governance/dreps/{drep_id(c)}/distribution", drep_expected,
             openapi_path="/governance/dreps/{drepId}/distribution"),
        Case("drep distribution by epoch", "GET", lambda c: f"/governance/dreps/{drep_id(c)}/distribution/{epoch(c)}",
             drep_expected, openapi_path="/governance/dreps/{drepId}/distribution/{epoch}"),

        Case("devnet rollback guard", "POST", "/devnet/rollback", devnet_expected,
             body=json_body({"count": 0}), headers=json_headers, mutating=True),
        Case("devnet snapshot guard", "POST", "/devnet/snapshot", devnet_expected,
             body=json_body({"name": "smoke-test"}), headers=json_headers, mutating=True),
        Case("devnet restore guard", "POST", "/devnet/restore/smoke-test", devnet_expected,
             headers=json_headers, mutating=True, openapi_path="/devnet/restore/{name}"),
        Case("devnet snapshots", "GET", "/devnet/snapshots", devnet_expected),
        Case("devnet delete snapshot guard", "DELETE", "/devnet/snapshot/smoke-test", devnet_expected,
             headers=json_headers, mutating=True, openapi_path="/devnet/snapshot/{name}"),
        Case("devnet fund guard", "POST", "/devnet/fund", devnet_expected,
             body=json_body({"address": DEFAULT_ADDRESS, "ada": 1}), headers=json_headers, mutating=True),
        Case("devnet time advance guard", "POST", "/devnet/time/advance", devnet_expected,
             body=json_body({"slots": 1}), headers=json_headers, mutating=True),
        Case("devnet epoch shift guard", "POST", "/devnet/epochs/shift", devnet_expected,
             body=json_body({"epochs": 1}), headers=json_headers, mutating=True),
        Case("devnet catch up guard", "POST", "/devnet/epochs/catch-up", devnet_expected,
             body=json_body({}), headers=json_headers, mutating=True),
        Case("devnet genesis download", "GET", "/devnet/genesis/download", devnet_expected, parse_json=False),

        Case("debug epoch snapshot", "GET", lambda c: f"/api/debug/epoch-snapshot/{epoch(c)}", ok_or_unavailable(),
             debug=True, heavy=True, openapi_path="/api/debug/epoch-snapshot/{epoch}"),
        Case("debug epoch block counts", "GET", lambda c: f"/api/debug/epoch-block-counts/{epoch(c)}", ok_or_unavailable(),
             debug=True, openapi_path="/api/debug/epoch-block-counts/{epoch}"),
        Case("debug epoch fees", "GET", lambda c: f"/api/debug/epoch-fees/{epoch(c)}", ok_or_unavailable(),
             debug=True, openapi_path="/api/debug/epoch-fees/{epoch}"),
        Case("debug utxo balance", "GET", lambda c: f"/api/debug/utxo-balance/{payment_credential(c)}", ok_or_unavailable(),
             debug=True, heavy=True, openapi_path="/api/debug/utxo-balance/{credHash}"),
        Case("debug reward inputs", "GET", lambda c: f"/api/debug/reward-inputs/{reward_epoch(c)}", statuses(200, 503),
             debug=True, heavy=True, openapi_path="/api/debug/reward-inputs/{epoch}"),
        Case("debug adapot", "GET", lambda c: f"/api/debug/adapot/{epoch(c)}", statuses(200, 503),
             debug=True, openapi_path="/api/debug/adapot/{epoch}"),
        Case("debug pool params", "GET", lambda c: f"/api/debug/pool-params/{pool_hash(c)}", statuses(200, 503),
             debug=True, openapi_path="/api/debug/pool-params/{poolHash}"),
        Case("debug pool params by epoch", "GET", lambda c: f"/api/debug/pool-params/{pool_hash(c)}/epoch/{epoch(c)}",
             statuses(200, 503), debug=True, openapi_path="/api/debug/pool-params/{poolHash}/epoch/{epoch}"),
        Case("debug retired pools", "GET", lambda c: f"/api/debug/retired-pools/{epoch(c)}", statuses(200, 503),
             debug=True, openapi_path="/api/debug/retired-pools/{epoch}"),
        Case("debug deregistered accounts", "GET", lambda c: f"/api/debug/deregistered-accounts/{reward_epoch(c)}",
             statuses(200, 503), debug=True, openapi_path="/api/debug/deregistered-accounts/{epoch}"),
        Case("debug adapot chain", "GET", lambda c: f"/api/debug/adapot-chain?from={max(0, epoch(c) - 2)}&to={epoch(c)}",
             statuses(200, 503), debug=True, openapi_path="/api/debug/adapot-chain"),
    ]


def apply_case_skip(case: Case, ctx: Context) -> str | None:
    if case.debug and not ctx.args.include_debug:
        return "debug endpoint; pass --include-debug"
    if case.heavy and not ctx.args.include_heavy:
        return "heavy endpoint; pass --include-heavy"
    if case.node_control and not ctx.args.include_node_control:
        return "node control endpoint; pass --include-node-control on a disposable node"
    if case.mutating and ctx.dev_mode and not ctx.args.include_mutating:
        return "mutating devnet endpoint; pass --include-mutating on a disposable devnet"
    if case.skip:
        return case.skip(ctx)
    return None


def collect_fixtures(ctx: Context) -> None:
    args = ctx.args
    explicit = {
        "tx_hash": args.tx_hash,
        "utxo_index": args.utxo_index,
        "address": args.address,
        "stake_address": args.stake_address,
        "payment_credential": args.payment_credential,
        "script_hash": args.script_hash,
        "pool_id": args.pool_id,
        "pool_hash": args.pool_hash,
        "drep_id": args.drep_id,
        "proposal_tx_hash": args.proposal_tx_hash,
        "proposal_cert_index": args.proposal_cert_index,
        "epoch": args.epoch,
    }
    ctx.fixtures.update({k: v for k, v in explicit.items() if v not in (None, "")})

    node_status = ctx.client.request("GET", "/node/status")
    if isinstance(node_status.json_value, dict):
        ctx.fixtures["node_running"] = bool(node_status.json_value.get("running"))

    node_config = ctx.client.request("GET", "/node/config")
    if isinstance(node_config.json_value, dict):
        ctx.fixtures["dev_mode"] = bool(node_config.json_value.get("devMode"))

    latest_epoch = ctx.client.request("GET", "/epochs/latest")
    if isinstance(latest_epoch.json_value, dict) and "epoch" in latest_epoch.json_value:
        ctx.fixtures.setdefault("epoch", int(latest_epoch.json_value["epoch"]))

    latest_block = ctx.client.request("GET", "/blocks/latest")
    if isinstance(latest_block.json_value, dict):
        block = latest_block.json_value
        ctx.fixtures.setdefault("block_hash", block.get("hash"))
        ctx.fixtures.setdefault("block_number", block.get("number") or block.get("height"))
        if "epoch" in block:
            ctx.fixtures.setdefault("epoch", int(block["epoch"]))

    discover_from_lists(ctx)


def first_list_item(ctx: Context, path: str) -> dict[str, Any] | None:
    resp = ctx.client.request("GET", path)
    if resp.status != 200 or not isinstance(resp.json_value, list) or not resp.json_value:
        return None
    first = resp.json_value[0]
    return first if isinstance(first, dict) else None


def discover_from_lists(ctx: Context) -> None:
    registration = first_list_item(ctx, "/accounts/registrations?count=1")
    if registration:
        ctx.fixtures.setdefault("stake_address", registration.get("stake_address"))
        ctx.fixtures.setdefault("payment_credential", registration.get("credential"))

    delegation = first_list_item(ctx, "/accounts/delegations?count=1")
    if delegation:
        ctx.fixtures.setdefault("stake_address", delegation.get("stake_address"))
        ctx.fixtures.setdefault("pool_id", delegation.get("pool_id"))
        ctx.fixtures.setdefault("pool_hash", delegation.get("pool_hash"))

    pool = first_list_item(ctx, "/accounts/pools?count=1")
    if pool:
        ctx.fixtures.setdefault("pool_id", pool.get("pool_id"))
        ctx.fixtures.setdefault("pool_hash", pool.get("pool_hash"))

    drep = first_list_item(ctx, "/governance/dreps?count=1")
    if drep:
        ctx.fixtures.setdefault("drep_id", drep.get("drep_id"))

    proposal = first_list_item(ctx, "/governance/proposals?status=all&count=1")
    if proposal:
        ctx.fixtures.setdefault("proposal_tx_hash", proposal.get("tx_hash"))
        ctx.fixtures.setdefault("proposal_cert_index", proposal.get("cert_index"))


def openapi_paths(ctx: Context) -> set[str]:
    url = ctx.client.root_path_url("/q/openapi")
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=ctx.args.timeout) as resp:
            text = resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"WARN openapi coverage check skipped: {e}", file=sys.stderr)
        return set()

    paths: set[str] = set()
    for line in text.splitlines():
        match = re.match(r"^  (/[^:]+):\s*$", line)
        if match:
            paths.add(match.group(1))
    return paths


def registered_openapi_paths(ctx: Context, all_cases: list[Case]) -> set[str]:
    prefix = ctx.client.api_prefix
    paths: set[str] = set()
    for case in all_cases:
        coverage = case.coverage_path()
        if not coverage:
            continue
        coverage = coverage.split("?", 1)[0]
        paths.add(prefix + coverage)
    return paths


def check_openapi_coverage(ctx: Context, all_cases: list[Case]) -> None:
    exposed = openapi_paths(ctx)
    if not exposed:
        return
    registered = registered_openapi_paths(ctx, all_cases)
    missing = sorted(p for p in exposed - registered if not p.startswith("/q/"))
    extra = sorted(registered - exposed)
    if missing:
        print("\nWARN OpenAPI paths without smoke cases:")
        for path in missing:
            print(f"  {path}")
    if extra and ctx.args.verbose:
        print("\nINFO registered paths not present in OpenAPI:")
        for path in extra:
            print(f"  {path}")


def run(ctx: Context, all_cases: list[Case]) -> int:
    passed = failed = skipped = 0
    failures: list[str] = []

    for case in all_cases:
        reason = apply_case_skip(case, ctx)
        if reason:
            skipped += 1
            if ctx.args.verbose or ctx.args.show_skips:
                print(f"SKIP {case.method:6} {case.coverage_path() or case.name}: {reason}")
            continue

        path = case.resolved_path(ctx)
        resp = ctx.client.request(
            case.method,
            path,
            body=case.resolved_body(ctx),
            headers=case.headers,
            parse_json=case.parse_json,
        )
        expected = case.expected_statuses(ctx)
        label = f"{case.method:6} {path}"
        if resp.error:
            failed += 1
            failures.append(f"{case.name}: request failed: {resp.error}")
            print(f"FAIL {label} ERROR {resp.error}")
            continue

        if resp.status in expected:
            passed += 1
            print(f"PASS {label} -> {resp.status} ({resp.elapsed_ms}ms)")
        else:
            failed += 1
            snippet = resp.text.replace("\n", " ")[:300]
            failures.append(
                f"{case.name}: expected {sorted(expected)}, got {resp.status}; body={snippet}"
            )
            print(f"FAIL {label} -> {resp.status} expected {sorted(expected)} ({resp.elapsed_ms}ms)")

    print(f"\nSummary: passed={passed}, failed={failed}, skipped={skipped}")
    if failures:
        print("\nFailures:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke-test Yano REST endpoints.")
    parser.add_argument("--base-url", default=os.getenv("YANO_E2E_BASE_URL", "http://localhost:7070/api/v1"),
                        help="Base API URL, e.g. http://localhost:7070/api/v1")
    parser.add_argument("--timeout", type=float, default=float(os.getenv("YANO_E2E_TIMEOUT", "10")))
    parser.add_argument("--include-debug", action="store_true", help="Run debug endpoints under /api/debug.")
    parser.add_argument("--include-heavy", action="store_true", help="Run heavy endpoints that can return large payloads.")
    parser.add_argument("--include-mutating", action="store_true", help="Run mutating endpoints. Use only on disposable nodes.")
    parser.add_argument("--include-node-control", action="store_true",
                        help="Run node control endpoints such as /node/stop. Use only on disposable nodes.")
    parser.add_argument("--no-openapi-check", action="store_true", help="Skip /q/openapi coverage warning.")
    parser.add_argument("--show-skips", action="store_true", help="Print skipped cases.")
    parser.add_argument("-v", "--verbose", action="store_true")

    parser.add_argument("--epoch", type=int)
    parser.add_argument("--tx-hash")
    parser.add_argument("--utxo-index", type=int, default=0)
    parser.add_argument("--address")
    parser.add_argument("--stake-address")
    parser.add_argument("--payment-credential")
    parser.add_argument("--script-hash")
    parser.add_argument("--pool-id")
    parser.add_argument("--pool-hash")
    parser.add_argument("--drep-id")
    parser.add_argument("--proposal-tx-hash")
    parser.add_argument("--proposal-cert-index", type=int)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    client = HttpClient(args.base_url, args.timeout)
    ctx = Context(args=args, client=client)
    collect_fixtures(ctx)

    if args.verbose:
        print("Fixtures:")
        for key in sorted(ctx.fixtures):
            print(f"  {key}: {ctx.fixtures[key]}")
        print()

    all_cases = cases()
    if not args.no_openapi_check:
        check_openapi_coverage(ctx, all_cases)
    return run(ctx, all_cases)


if __name__ == "__main__":
    raise SystemExit(main())
