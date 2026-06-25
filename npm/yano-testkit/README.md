# @bloxbean/yano-testkit

Programmatic Yano devnet fixture for JavaScript and TypeScript tests.

The package starts a local Yano native binary in devnet mode, waits until the
HTTP API is ready, and returns connection details for your test code.

## Install

```bash
npm install --save-dev @bloxbean/yano-testkit
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
  const response = await fetch(new URL("node/tip", yano.apiBaseUrl));
  const tip = await response.json();
  console.log(tip);
} finally {
  await yano.stop();
}
```

Plain Node.js callers must call `await yano.stop()` when the test is done. Use
`try/finally` so the native process is stopped even when assertions fail.

The default fixture uses:

- real RocksDB-backed Yano storage in a test-owned temporary directory;
- a random available HTTP port;
- a random available node-to-node port;
- the production Blockfrost-compatible API under `/api/v1/`.

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

Query UTXOs through the Blockfrost-compatible endpoints under `yano.apiBaseUrl`.
Wallet and key generation are intentionally outside this package.

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
  cwd,                 // process working directory; should contain config/
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
needs to inspect chain state after shutdown.

## Environment Variables

- `YANO_TESTKIT_BINARY` - absolute or relative path to a Yano native binary.
- `YANO_TESTKIT_TIMEOUT_MS` - default startup timeout.

## Supported Platforms

- Linux x64
- Linux arm64
- macOS arm64
- Windows x64

Unsupported platforms can still use `YANO_TESTKIT_BINARY`.
