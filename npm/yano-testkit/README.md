# @bloxbean/yano-testkit

Programmatic Yano devnet fixture for JavaScript and TypeScript tests.

The package starts a local Yano native binary in devnet mode, waits until the
HTTP API is ready, and returns connection details for your test code.

For a detailed JavaScript developer guide, see
[JS_USER_GUIDE.md](./JS_USER_GUIDE.md).

## Install

```bash
npm install --save-dev @bloxbean/yano-testkit
```

Preview releases are published under the `preview` tag until the first stable
release promotes a version to `latest`:

```bash
npm install --save-dev @bloxbean/yano-testkit@preview
```

The package resolves a platform-specific native Yano binary from optional npm
packages. For local development before publishing those packages, set
`YANO_TESTKIT_BINARY` or pass `binaryPath`.

## Basic Usage

```js
import { startYanoDevnet } from "@bloxbean/yano-testkit";

const yano = await startYanoDevnet({
  blockTimeMillis: 200
});

try {
  await yano.faucet.fundAddress("addr_test1...", 1000);
  await yano.time.advanceSlots(3);

  const tip = await yano.queries.tip();
  console.log(tip);
} finally {
  await yano.stop();
}
```

Plain Node.js callers must call `await yano.stop()` when the test is done. Use
`try/finally` so the native process is stopped even when assertions fail.

The default fixture uses:

- real RocksDB-backed Yano storage in a test-owned temporary directory;
- a copied `config/` directory inside that temporary directory, so devnet
  time-travel and genesis rewrites do not mutate installed package files;
- a random available HTTP port;
- a random available node-to-node port;
- the production Blockfrost-compatible API under `/api/v1/`.

The JavaScript testkit is RocksDB-only. It does not expose Yano's in-memory
runtime storage mode.

## Funding Test Addresses

Yano devnet exposes a devnet-only faucet endpoint. The wrapper provides a small
helper so tests do not need to hand-roll the HTTP request:

```js
const result = await yano.fundAddress("addr_test1...", 1000);
console.log(result.tx_hash, result.index, result.lovelace);
```

`yano.fundAddress(address, ada)` is a compatibility alias for
`yano.faucet.fundAddress(address, ada)`.

The amount is in ADA, matching the HTTP API:

```http
POST /api/v1/devnet/fund
{ "address": "addr_test1...", "ada": 1000 }
```

The response shape is `{ tx_hash, index, lovelace }`.

The npm testkit does not expose a default JavaScript wallet or pre-funded
account. JS tests should create or load their own test wallet/address using the
application's normal Cardano library, then call `fundAddress()` before building
transactions. The devnet's initial funds are owned by the runtime faucet, not by
an npm-exported mnemonic.

## Advanced Helpers

The returned devnet object includes typed, HTTP-backed helper groups:

```js
await yano.faucet.fundAddress(address, 1000);
await yano.faucet.fundAddressLovelace(address, 1_000_000n);

await yano.time.advanceSlots(5);
await yano.time.advanceToEpoch(1);
await yano.time.crossEpochBoundary();

await yano.snapshots.create("before-case");
await yano.snapshots.withSnapshot("case", async () => {
  await yano.time.advanceSlots(1);
});

await yano.devnet.rollback({ count: 1 });

const utxos = await yano.queries.utxosByAddress(address);
const txHash = await yano.transactions.submitAndAwait(signedTxCbor);

await yano.await.untilTxVisible(txHash);
await yano.assertions.address(address).hasAtLeastAda(1000);
```

Query helpers and `yano.client` preserve Yano's HTTP response shape, including
Blockfrost-style snake_case fields such as `tx_hash`, `block_number`, and
`output_index`. Wrapper request options use normal JavaScript camelCase where a
convenience method needs an option object.

`advanceToSlot()`, `advanceToEpoch()`, and `crossEpochBoundary()` are
best-effort helpers. They read current chain state and then ask Yano to advance
by the computed delta, so a live producer can move the chain while the helper is
running. Use `await.untilSlotAtLeast()`, `await.untilEpochAtLeast()`, or an
assertion after advancing when the test needs to verify the final chain state.

ADA assertion helpers such as `hasAtLeastAda(1000)` accept JavaScript numbers
for convenience. Use the lovelace helpers, for example `hasAtLeast(1_000_000n)`
or `hasExactly(1_000_000n)`, when exact arithmetic matters.

The low-level `yano.client` helper is available for endpoints that do not need a
named convenience method:

```js
const status = await yano.client.getJson("node/status");
const params = await yano.client.getJson("epochs/latest/parameters");
```

`yano.queries.protocolParameters()` uses
`/api/v1/epochs/latest/parameters`. In the default devnet profile this returns
the epoch-param tracker value for the current epoch. If a test disables tracking
with `extraArgs: ["-Dyano.epoch-params.tracking-enabled=false"]`, Yano falls
back to static `protocol-param.json` content.

All mutation helpers call Yano's existing devnet-only HTTP endpoints. They do
not expose runtime internals or RocksDB handles.

Wallet/key generation and transaction signing stay in your JavaScript
application or its chosen Cardano library. Yano helpers fund and query addresses
and submit serialized transactions.

## Vitest

```js
import { describe, expect, test } from "vitest";
import { yanoDevnet } from "@bloxbean/yano-testkit/vitest";

const yano = yanoDevnet();

describe("my app", () => {
  test("reads the Yano tip", async () => {
    const response = await fetch(new URL("node/tip", yano.apiBaseUrl));
    expect(response.ok).toBe(true);
  });
});
```

The Vitest helper starts the native process in `beforeAll()` and stops it in
`afterAll()`.

## Transaction Workflows

The fixture only starts Yano and returns connection details. Build/sign
transactions with your application or a JavaScript Cardano library, then use the
production HTTP API:

```js
await fetch(new URL("tx/submit", yano.apiBaseUrl), {
  method: "POST",
  headers: { "content-type": "application/cbor" },
  body: signedTxCbor
});
```

Use `yano.faucet.fundAddress(address, ada)` to create test UTXOs, then query
UTXOs through `yano.queries` or the Blockfrost-compatible endpoints under
`yano.apiBaseUrl`. Wallet and key generation are intentionally outside this
package.

## Local Wrapper Testing

Build the native binary first:

```bash
./gradlew :app:quarkusBuild \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

Then run a local smoke test from a JavaScript project:

```bash
YANO_TESTKIT_BINARY=/path/to/yano/app/build/yano node my-test.js
```

When using a locally built binary, set `cwd` to the Yano app directory if the
binary does not sit next to the `config/` directory:

```js
const yano = await startYanoDevnet({
  binaryPath: "/path/to/yano/app/build/yano",
  cwd: "/path/to/yano/app"
});
```

From this repository, run the npm wrapper checks without a native build:

```bash
cd npm
npm run install:testkit
npm test
npm run typecheck
npm run pack:dry
```

Those tests use a fake Yano process to validate lifecycle, readiness polling,
failure logs, and cleanup.

`npm run install:testkit` intentionally omits optional platform packages. The
platform packages are populated with native binaries by release CI; local wrapper
tests should use `YANO_TESTKIT_BINARY` or `binaryPath` when they need a real
binary.

## Options

```js
await startYanoDevnet({
  binaryPath,          // overrides platform package resolution
  cwd,                 // source directory containing config/
  httpPort: 0,         // 0 or undefined allocates a free port
  n2nPort: 0,          // 0 or undefined allocates a free port
  blockTimeMillis: 200,
  networkMagic: 42,
  storage: "temp-rocksdb",
  storagePath,
  timeoutMs: 60000,
  preserveWorkDir: false,
  env: {},
  extraArgs: [],
  onStdout: line => {},
  onStderr: line => {}
});
```

Use `storage: "persistent-rocksdb"` with `workDir` or `storagePath` when a test
needs to inspect chain state after shutdown. Default temporary runs copy
`cwd/config` or packaged `config` into the temporary `workDir` and run Yano from
that copy.

## Environment Variables

- `YANO_TESTKIT_BINARY` - absolute or relative path to a Yano native binary.
- `YANO_TESTKIT_TIMEOUT_MS` - default startup timeout.

## Supported Platforms

- Linux x64
- Linux arm64
- macOS arm64
- Windows x64

Unsupported platforms can still use `YANO_TESTKIT_BINARY`.
