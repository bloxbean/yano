# Yano REST Endpoint Smoke Tests

This folder contains a lightweight endpoint smoke test runner for a running
Yano instance. It is intentionally not wired into Gradle; run it manually
against a node you want to verify.

The runner is dependency-free Python 3. It keeps endpoint coverage in one table
inside `yano_endpoint_smoke.py`, so adding a new endpoint is usually one
`Case(...)` entry.

## Run

```bash
python3 e2e-tests/yano_endpoint_smoke.py \
  --base-url http://localhost:7070/api/v1
```

You can also use:

```bash
YANO_E2E_BASE_URL=http://localhost:7070/api/v1 python3 e2e-tests/yano_endpoint_smoke.py
```

The default run is safe for public-network nodes. It covers public read
endpoints, safe negative-submit/evaluate checks, and devnet endpoints in
non-devnet mode by expecting `403`. It skips operations that would mutate or
stop a node.

## Devnet Functional Tests

`yano_devnet_functional.py` is a separate destructive test runner for a running
devnet. It refuses non-devnet targets and checks post-conditions for an ordered
workflow: create snapshot, advance time, rollback, advance again, restore the
snapshot, and clean up.

```bash
python3 e2e-tests/yano_devnet_functional.py \
  --base-url http://localhost:7070/api/v1
```

Optional advanced checks:

```bash
# Catch up to wall-clock. This can produce many blocks on shifted devnets.
python3 e2e-tests/yano_devnet_functional.py --include-catch-up

# Epoch shift is one-shot and only succeeds on a fresh past-time-travel devnet.
python3 e2e-tests/yano_devnet_functional.py --include-shift --require-shift
```

## Useful Options

```bash
# Include debug endpoints. Some can return large payloads.
python3 e2e-tests/yano_endpoint_smoke.py --include-debug

# Include debug endpoints marked heavy, such as full epoch snapshot dumps.
python3 e2e-tests/yano_endpoint_smoke.py --include-debug --include-heavy

# Exercise mutating devnet endpoints on a disposable devnet only.
python3 e2e-tests/yano_endpoint_smoke.py --include-mutating

# Provide known fixtures for stronger positive checks.
python3 e2e-tests/yano_endpoint_smoke.py \
  --tx-hash <tx_hash> \
  --address <address> \
  --stake-address <stake_address> \
  --pool-id <pool_id> \
  --drep-id <drep_id>
```

## Coverage Model

The runner registers all current REST paths from the app resources:

- node/status/control endpoints
- blocks, txs, UTXOs, scripts
- epochs, AdaPot, active stake
- accounts and account history
- governance and network state
- devnet-only endpoints
- debug endpoints under `/api/debug`

It also fetches `/q/openapi` and warns if OpenAPI exposes a path that has no
registered smoke case.
