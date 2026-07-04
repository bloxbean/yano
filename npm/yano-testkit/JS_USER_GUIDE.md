# Yano JavaScript Testkit User Guide

This guide is for JavaScript and TypeScript developers who want to run tests
against a local Cardano devnet without starting Docker or a JVM test fixture.

`@bloxbean/yano-testkit` starts the native Yano binary in devnet mode, waits for
the HTTP API to be ready, and gives your test code a small helper object for
funding addresses, querying chain state, moving devnet time, snapshots, rollback,
transaction submission, and assertions.

## What You Get

- A real Yano native process started from your JS test.
- RocksDB-backed devnet storage by default, isolated in a temporary directory.
- Random free HTTP and node-to-node ports by default.
- Yano's production Blockfrost-compatible HTTP API under `/api/v1/`.
- Devnet-only helpers for faucet funding, time travel, snapshots, and rollback.
- No wallet custody inside Yano. Your JS app or Cardano SDK owns keys and signs
  transactions.

## Install

```bash
npm install --save-dev @bloxbean/yano-testkit
```

For preview releases:

```bash
npm install --save-dev @bloxbean/yano-testkit@preview
```

The `latest` npm tag is reserved for stable releases. Until a stable package is
promoted, install prereleases explicitly with `@preview`.

The package depends on optional platform packages that contain the native Yano
binary for the local OS and CPU. You normally do not need to set any binary
environment variable after installing from npm.

Supported native packages:

- Linux x64
- Linux arm64
- macOS arm64
- Windows x64

Node.js 20.8 or newer is required.

## Minimal Node Test

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { startYanoDevnet } from "@bloxbean/yano-testkit";

test("starts a Yano devnet", async () => {
  const yano = await startYanoDevnet({ blockTimeMillis: 200 });

  try {
    const tip = await yano.queries.tip();
    assert.ok(tip);

    await yano.time.advanceSlots(3);
    await yano.assertions.slotAtLeast(3);
  } finally {
    await yano.stop();
  }
});
```

Plain Node.js tests must call `await yano.stop()`. Put it in a `finally` block
so the native process is stopped even when an assertion fails.

## Vitest

Use the Vitest adapter when one devnet should be shared by a suite:

```js
import { describe, expect, test } from "vitest";
import { yanoDevnet } from "@bloxbean/yano-testkit/vitest";

const yano = yanoDevnet({ blockTimeMillis: 200 });

describe("my Cardano app", () => {
  test("reads the chain tip", async () => {
    const tip = await yano.queries.tip();
    expect(tip).toBeTruthy();
  });
});
```

The adapter starts Yano in `beforeAll()` and stops it in `afterAll()`.

## Devnet Object

`startYanoDevnet()` returns:

```js
const yano = await startYanoDevnet();

console.log(yano.baseUrl);     // http://127.0.0.1:<port>/
console.log(yano.apiBaseUrl);  // http://127.0.0.1:<port>/api/v1/
console.log(yano.n2nPort);
console.log(yano.storage.path);
console.log(yano.workDir);
```

Important fields and helpers:

- `baseUrl` - root Quarkus URL, useful for health endpoints such as
  `/q/health/ready`.
- `apiBaseUrl` - Yano's Blockfrost-compatible API root.
- `url(path)` - creates a URL under `apiBaseUrl`.
- `logs()` - returns the recent Yano stdout/stderr log tail.
- `stop()` - stops the native process and cleans temporary storage.
- `process` - the underlying Node `ChildProcess`, mainly for diagnostics.

## Start Options

```js
const yano = await startYanoDevnet({
  blockTimeMillis: 200,
  timeoutMs: 60_000,
  networkMagic: 42,
  httpPort: 0,
  n2nPort: 0,
  storage: "temp-rocksdb",
  onStdout: line => console.log(`[yano] ${line}`),
  onStderr: line => console.error(`[yano] ${line}`)
});
```

Common options:

- `blockTimeMillis` - devnet block production interval.
- `timeoutMs` - startup readiness timeout.
- `httpPort` and `n2nPort` - set fixed ports, or omit them for random free
  ports.
- `networkMagic` - protocol magic for the devnet.
- `storage` - `"temp-rocksdb"` or `"persistent-rocksdb"`.
- `workDir` / `storagePath` - explicit directories for persistent tests.
- `preserveWorkDir` - keep temporary work directory after `stop()`.
- `binaryPath` - path to a local Yano binary.
- `cwd` - source directory containing `config/` when using a local app build.
- `env` - extra environment variables for the Yano process.
- `extraArgs` - extra `-D...` JVM/native image system properties.
- `onStdout` / `onStderr` - stream Yano logs into your test output.

## Storage Modes

The default is `temp-rocksdb`:

```js
const yano = await startYanoDevnet({
  storage: "temp-rocksdb"
});
```

This uses real RocksDB storage in a test-owned temporary directory. The wrapper
copies `cwd/config` or the packaged platform `config` into that directory and
runs Yano from the copy, so devnet time-travel and genesis rewrites do not
mutate installed package files. The wrapper deletes the directory when `stop()`
succeeds.

The JavaScript testkit is RocksDB-only. It does not expose an in-memory storage
mode.

Use persistent storage when you need to inspect chain state after shutdown:

```js
const yano = await startYanoDevnet({
  storage: "persistent-rocksdb",
  workDir: "./tmp/yano-case-1"
});
```

`persistent-rocksdb` requires `workDir` or `storagePath`.

## Wallets And Funding

Yano HTTP does not own wallets, mnemonics, private keys, or signing callbacks.
That boundary is intentional. Create test wallets with your normal Cardano JS
library, then fund their addresses through the devnet faucet.

```js
const address = "addr_test1...";

const result = await yano.faucet.fundAddress(address, 1000);
console.log(result.tx_hash, result.index, result.lovelace);
```

Funding helpers:

```js
await yano.faucet.fundAddress(address, 1000);          // ADA
await yano.faucet.fundAddressLovelace(address, 1_000_000n);

await yano.faucet.fundAll([
  { address: alice, ada: 1000 },
  { address: bob, lovelace: 2_000_000n }
]);
```

`fundAll()` is sequential and non-atomic. If one funding request fails, previous
funding transactions are not rolled back.

There is also a compatibility alias:

```js
await yano.fundAddress(address, 1000);
```

## Use With Cardano JavaScript SDKs

Yano exposes a Blockfrost-compatible API at `yano.apiBaseUrl`. Cardano JS
libraries that can use a Blockfrost-style provider should point at that URL.

MeshJS example:

```js
import { BlockfrostProvider, MeshWallet } from "@meshsdk/core";

const provider = new BlockfrostProvider(yano.apiBaseUrl);
const wallet = new MeshWallet({
  networkId: 0,
  fetcher: provider,
  submitter: provider,
  key: {
    type: "mnemonic",
    words: testMnemonicWords
  }
});

await wallet.init();

const address = await wallet.getChangeAddress();
const funding = await yano.faucet.fundAddress(address, 20);
await yano.await.untilTxVisible(funding.tx_hash);
```

After funding, build and sign transactions with the SDK, then submit through the
SDK provider or through `yano.transactions`.

## Query Chain State

Use `yano.queries` for common Blockfrost-compatible and Yano query endpoints:

```js
const status = await yano.queries.status();
const tip = await yano.queries.tip();
const config = await yano.queries.config();
const latestBlock = await yano.queries.latestBlock();
const protocolParams = await yano.queries.protocolParameters();

const utxos = await yano.queries.utxosByAddress(address);
const tx = await yano.queries.tx(txHash);
const txUtxos = await yano.queries.txUtxos(txHash);
```

`protocolParameters()` calls `/api/v1/epochs/latest/parameters`. With the
default devnet profile, protocol parameters come from Yano's epoch-param
tracker. If a test disables tracking:

```js
const yano = await startYanoDevnet({
  extraArgs: ["-Dyano.epoch-params.tracking-enabled=false"]
});
```

then Yano falls back to static `protocol-param.json` content for the
Blockfrost-compatible parameters endpoint.

Useful derived queries:

```js
const slot = await yano.queries.currentSlot();
const block = await yano.queries.currentBlockNumber();
const epoch = await yano.queries.currentEpoch();
const epochStart = await yano.queries.epochStartSlot(2);
```

Response objects preserve Yano's HTTP JSON shape. For Blockfrost-compatible
endpoints this usually means snake_case fields such as `tx_hash`,
`output_index`, and `block_number`.

## Devnet Time

Use time helpers to move the devnet quickly:

```js
await yano.time.advanceSlots(5);
await yano.time.advanceSeconds(10);
await yano.time.advanceEpochs(1);

await yano.time.advanceToSlot(50);
await yano.time.advanceToEpoch(2);
await yano.time.crossEpochBoundary();
```

`advanceToSlot()`, `advanceToEpoch()`, and `crossEpochBoundary()` are
best-effort helpers. They read current chain state and then request a relative
advance. When a producer is running, the chain can move during that calculation.
Follow them with an await helper or assertion when the exact final state matters:

```js
await yano.time.advanceToEpoch(2);
await yano.await.untilEpochAtLeast(2);
```

Epoch maintenance helpers:

```js
await yano.time.shiftGenesisAndStartProducer(3);
await yano.time.catchUpToWallClock();
```

## Snapshots And Rollback

Create snapshots around destructive test cases:

```js
await yano.snapshots.create("before-case");

try {
  await runCase();
} finally {
  await yano.snapshots.restore("before-case");
}
```

Or use `withSnapshot()`:

```js
await yano.snapshots.withSnapshot("case", async () => {
  await yano.time.advanceSlots(10);
  await yano.devnet.rollback({ count: 1 });
});
```

Other snapshot helpers:

```js
const snapshots = await yano.snapshots.list();
const exists = await yano.snapshots.exists("case");
await yano.snapshots.delete("case");
```

Rollback can target exactly one of `slot`, `blockNumber`, or `count`:

```js
await yano.devnet.rollback({ count: 1 });
await yano.devnet.rollback({ slot: 20 });
await yano.devnet.rollback({ blockNumber: 5 });
```

## Submit And Evaluate Transactions

Build and sign transactions in your app or Cardano JS SDK. Submit the serialized
transaction through Yano:

```js
const txHash = await yano.transactions.submitHex(signedTxHex);
await yano.await.untilTxVisible(txHash);
```

For CBOR bytes:

```js
const txHash = await yano.transactions.submitCbor(signedTxCborBytes);
```

Submit and wait in one call:

```js
const txHash = await yano.transactions.submitAndAwait(signedTxHex, {
  timeoutMs: 20_000,
  pollIntervalMs: 200
});
```

Evaluate transactions:

```js
const result = await yano.transactions.evaluateHex(unsignedOrSignedTxHex);
```

The MeshJS example in `test-examples/yano-testkit-meshjs` shows the complete
flow: create a Mesh wallet, fund it, build a self-transfer, sign, submit, wait,
and verify.

## Await Helpers

Await helpers poll until the condition is true or a timeout is reached:

```js
await yano.await.untilReady();
await yano.await.untilSlotAtLeast(10);
await yano.await.untilBlockAtLeast(5);
await yano.await.untilEpochAtLeast(1);
await yano.await.untilTxVisible(txHash);
```

Override polling per call:

```js
await yano.await.untilTxVisible(txHash, {
  timeoutMs: 30_000,
  pollIntervalMs: 200
});
```

Custom condition:

```js
await yano.await.until(
  async () => (await yano.queries.utxosByAddress(address)).length > 0,
  "address to have at least one UTXO"
);
```

## Assertions

Assertions are runner-neutral. They throw normal errors, so they work with
Node's test runner, Vitest, Jest, Mocha, and custom runners.

```js
await yano.assertions.nodeIsRunning();
await yano.assertions.runtimeNotDegraded();
await yano.assertions.slotAtLeast(10);
await yano.assertions.blockAtLeast(5);
await yano.assertions.epochAtLeast(1);
await yano.assertions.snapshotExists("case");
await yano.assertions.snapshotMissing("case");
```

Address assertions:

```js
await yano.assertions.address(address).hasAtLeastAda(1000);
await yano.assertions.address(address).hasAtLeast(1_000_000n);
await yano.assertions.address(address).hasExactly(2_000_000n);

const balance = await yano.assertions.address(address).balanceLovelace();
```

ADA helpers accept JavaScript numbers for convenience. Use lovelace helpers with
`bigint` or string values when exact arithmetic matters.

## Low-Level HTTP Client

Use `yano.client` when a named helper does not exist:

```js
const params = await yano.client.getJson("epochs/latest/parameters");
const bytes = await yano.client.getBytes("devnet/genesis/download");
```

Available methods:

- `getJson(path, options)`
- `postJson(path, body, options)`
- `deleteJson(path, options)`
- `postCbor(path, body, options)`
- `postText(path, body, options)`
- `getBytes(path, options)`

The path is relative to `yano.apiBaseUrl`.

## Error Handling

HTTP failures throw `YanoHttpError`:

```js
import { YanoHttpError } from "@bloxbean/yano-testkit";

try {
  await yano.queries.tx("missing");
} catch (error) {
  if (error instanceof YanoHttpError) {
    console.error(error.method, error.url, error.status);
    console.error(error.bodyText);
  }
}
```

Startup failures include the recent Yano log tail in the error message. You can
also print logs after a failed test:

```js
try {
  await runTest();
} catch (error) {
  console.error(yano?.logs());
  throw error;
}
```

## Local Yano Binary

Use `YANO_TESTKIT_BINARY` only when testing a local build or an unsupported
platform. Published npm packages include the platform binary automatically.

Build a native binary from this repository:

```bash
./gradlew :app:quarkusBuild \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

Run a JS test with the local binary:

```bash
YANO_TESTKIT_BINARY=/path/to/yano/app/build/yano node my-test.mjs
```

When the binary is from `app/build`, set `cwd` to the app directory so Yano can
find `config/`:

```js
const yano = await startYanoDevnet({
  binaryPath: "/path/to/yano/app/build/yano",
  cwd: "/path/to/yano/app"
});
```

Windows PowerShell:

```powershell
$env:YANO_TESTKIT_BINARY="C:\path\to\yano\app\build\yano.exe"
node my-test.mjs
```

## TypeScript

The package ships TypeScript declarations:

```ts
import { startYanoDevnet, type YanoDevnet } from "@bloxbean/yano-testkit";

let yano: YanoDevnet | undefined;

yano = await startYanoDevnet();
```

No separate `@types` package is needed.

## Troubleshooting

### The process does not start

- Increase `timeoutMs` or set `YANO_TESTKIT_TIMEOUT_MS`.
- Pass `onStdout` and `onStderr` to see Yano logs while the test runs.
- Print `yano.logs()` in failure handlers when a process started but the test
  later failed.
- For local binaries, verify `cwd` points to a directory containing `config/`.

### The package cannot find a binary

- Verify the local platform is supported by the optional native packages.
- Reinstall dependencies so npm installs optional dependencies for your OS/CPU.
- For unsupported platforms or local development, set `YANO_TESTKIT_BINARY`.

### Tests hang after completion

- Plain Node.js tests must call `await yano.stop()`.
- Use `try/finally`.
- In Vitest, prefer `yanoDevnet()` so `afterAll()` stops the process.

### A funded address has no visible UTXO yet

- Wait for the faucet transaction:

```js
const result = await yano.faucet.fundAddress(address, 1000);
await yano.await.untilTxVisible(result.tx_hash);
```

- Then query UTXOs or use address assertions.

## Examples

- `test-examples/yano-testkit-js` - minimal published-package smoke test.
- `test-examples/yano-testkit-meshjs` - MeshJS wallet, funding, signed transfer,
  submit, await, and verification.
