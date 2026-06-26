# ADR-031: Native Yano Testkit For Non-JVM And npm Testing

**Status:** Accepted / Implemented
**Date:** 2026-06-25
**Related:** ADR-023 (Docker Images And Compose Release), ADR-029 (Devnet Control Toolkit And Testkit SPI), ADR-030 (Yano Testkit For JVM Integration Testing)

## Context

ADR-030 defines the JVM testkit: in-process Yano devnet fixtures, JUnit 5
support, Java helper facades, and optional Cardano Client Lib integration. That
is the right shape for Java applications because they can depend on Yano
libraries directly and can use either the embedded `YanoBackendService` adapter
or an HTTP-backed CCL `BFBackendService`.

Non-JVM applications need a different contract. A JavaScript or TypeScript test
suite cannot use `YanoDevnetTestKit` directly, and it should not need to embed a
JVM app helper or understand Quarkus. Those tests need a programmatic way to
start a real local Yano devnet, receive stable connection details, exercise the
same HTTP endpoints used in production, and clean up temporary state at the end
of the test.

Yano already has a native binary distribution path. That makes the native binary
the right process boundary for non-JVM users:

- it is language-neutral;
- it can expose the production Blockfrost-compatible HTTP API;
- it can use the real RocksDB-backed storage implementation;
- it can be spawned by npm, Python, Go, Rust, or shell tests;
- it avoids making `YanoAppProcess` a public cross-language contract.

`YanoAppProcess` remains useful for Yano's own JVM app E2E tests, especially
while the app is built and launched from a local jar. It is not the intended
public fixture for JavaScript applications.

## Decision

Provide a native-process test fixture contract for non-JVM integration tests.
The first language-specific wrapper is an npm package for JavaScript and
TypeScript applications.

The implemented contract starts the existing Yano native binary in the
foreground with devnet Quarkus/system properties. The npm wrapper owns port
allocation, temporary directory creation, readiness polling, log capture, and
cleanup. This intentionally avoids adding a new app command parser or changing
normal production startup behavior.

The wrapper launches the binary with the equivalent of:

```bash
yano \
  -Dquarkus.profile=devnet \
  -Dquarkus.http.host=127.0.0.1 \
  -Dquarkus.http.port=<allocated-http-port> \
  -Dyano.server.port=<allocated-n2n-port> \
  -Dyano.remote.protocol-magic=42 \
  -Dyano.storage.rocksdb=true \
  -Dyano.storage.path=<work-dir>/chainstate \
  -Dyano.block-producer.block-time-millis=200 \
  -Dyano.exit-on-epoch-calc-error=false
```

A future first-class command such as `yano testkit devnet start --json` can be
added on top of this same process contract if other language wrappers need a
CLI-native manifest. It is not required for the initial npm unit-test fixture.

The native fixture must use production-like behavior by default:

- RocksDB storage enabled;
- a test-owned temporary storage directory;
- random/free HTTP and n2n ports when `0` is requested;
- devnet block production enabled;
- Blockfrost-compatible API mounted under `/api/v1`;
- deterministic cleanup on normal process termination;
- explicit persistent-storage mode when callers want to inspect state after the
  process exits.

In this ADR, **ephemeral** has the same meaning as ADR-030: the fixture owns the
process lifecycle, ports, temporary directories, and cleanup. It does not mean
in-memory storage. The default storage implementation must be real RocksDB.

## Readiness Contract

The npm wrapper returns a typed readiness object after both `/q/health/ready`
and `/api/v1/node/tip` return successfully:

```json
{
  "type": "ready",
  "pid": 12345,
  "baseUrl": "http://127.0.0.1:43123/",
  "apiBaseUrl": "http://127.0.0.1:43123/api/v1/",
  "n2nPort": 43124,
  "networkMagic": 42,
  "storage": {
    "mode": "temp-rocksdb",
    "path": "/tmp/yano-testkit-abc/chainstate"
  },
  "workDir": "/tmp/yano-testkit-abc"
}
```

After readiness, the process continues to run until `stop()` terminates it.
stdout/stderr are retained in a bounded log buffer for failed tests.

The fixture should handle:

- graceful process termination and cleanup from `stop()`;
- failed startup with a non-zero exit and useful stderr/stdout diagnostics;
- explicit `workDir` or `storagePath` for persistent/debug runs.

## npm Package

Publish an npm package such as:

```text
@bloxbean/yano-testkit
```

The package should:

- resolve a Yano native binary for the current OS and architecture;
- allow `YANO_TESTKIT_BINARY` to point at a local binary;
- install platform binaries through optional npm packages;
- verify the binary exists and is executable on Unix-like platforms;
- spawn the native fixture;
- poll the real HTTP endpoints until the fixture is ready;
- expose a small typed API for lifecycle and connection details;
- expose small HTTP-backed devnet conveniences such as `fundAddress(address,
  ada)` where they avoid boilerplate without hiding runtime behavior;
- stream or retain logs for failed tests;
- avoid depending on a specific JavaScript test runner.

Expected TypeScript shape:

```ts
import { startYanoDevnet } from "@bloxbean/yano-testkit";

const yano = await startYanoDevnet({
  storage: "temp-rocksdb",
  blockTimeMillis: 200,
});

try {
  await yano.fundAddress("addr_test1...", 1000);

  const response = await fetch(new URL("node/tip", yano.apiBaseUrl));
  const tip = await response.json();
  // application test assertions
} finally {
  await yano.stop();
}
```

Test-runner helpers can be layered on top:

```ts
import { yanoDevnet } from "@bloxbean/yano-testkit/vitest";

const yano = yanoDevnet();
```

Runner-specific adapters are convenience APIs only. The base npm package must be
usable from plain Node.js.

## HTTP And Backend Compatibility

The non-JVM fixture should use Yano's real HTTP endpoints. JavaScript
applications should call those endpoints directly or through their chosen
Blockfrost-compatible client.

The npm wrapper may provide thin helpers over devnet-only HTTP endpoints. The
initial helper is `fundAddress(address, ada)`, which calls
`POST /api/v1/devnet/fund` and returns the HTTP response shape
`{ tx_hash, index, lovelace }`. The amount is in ADA, matching the endpoint.
This helper does not create keys, sign transactions, or expose runtime internals.

The npm testkit does not publish a default JavaScript wallet, mnemonic, or
pre-funded account. JS applications should create or load their own test wallet
with their normal Cardano library, fund that address through `fundAddress()`,
and then build/sign transactions through application code.

This is intentionally separate from the embedded Java CCL adapter:

- `testkit-ccl` provides `YanoBackendService.from(kit)` for in-process Java
  tests without HTTP;
- Java tests that want production HTTP behavior can use CCL
  `BFBackendService` against `apiBaseUrl`;
- non-JVM tests use the native fixture and HTTP only.

The fixture should not expose Java objects, `RuntimeNode`, RocksDB handles, or
test-only runtime internals.

## Testcontainers And Docker

Docker/Testcontainers support is useful but should be optional and separate.
The native CLI contract should come first because every language wrapper can use
it without requiring Docker.

A later module or package can wrap the same contract in containers, for example:

- Java: `yano-testkit-containers`;
- npm: `@bloxbean/yano-testkit-containers`.

The base npm package should not require Docker.

## Implementation Plan

1. Completed: add npm-owned native devnet start mode.
   - Starts a local devnet with temp RocksDB storage by default.
   - Allocates HTTP and n2n ports when callers pass `0` or omit them.
   - Waits until the existing HTTP API is usable before returning readiness.

2. Completed: add lifecycle and cleanup guarantees.
   - `stop()` terminates the process and removes owned temp directories.
   - Persistent storage mode keeps its configured work directory.
   - Startup failures include retained stdout/stderr.

3. Completed: add npm package.
   - Implements binary resolution from optional platform packages.
   - Supports `YANO_TESTKIT_BINARY` and `binaryPath` for local/native builds.
   - Provides TypeScript definitions and a plain Node.js API.
   - Provides an optional Vitest helper as a peer-only convenience API.

4. Completed: add JavaScript validation tests and local instructions.
   - Plain Node unit tests cover readiness, cleanup, persistent storage, platform
     mapping, passed native properties, and failure diagnostics.
   - README documents local fake-process checks and real native binary smoke
     testing.

5. Completed in documentation: keep transaction workflows HTTP-only.
   - `fundAddress(address, ada)` wraps Yano's devnet-only HTTP faucet endpoint.
   - Applications submit through `POST /api/v1/tx/submit`.
   - Applications query UTXOs through Blockfrost-compatible endpoints.
   - Wallet/key generation stays in the application or a JS Cardano library.

6. Deferred: optional container wrappers.
   - Docker/Testcontainers wrappers should be separate packages after the native
     npm fixture is released.

## Non-Goals

- Do not embed Yano runtime classes in JavaScript.
- Do not use `YanoAppProcess` as the public non-JVM fixture.
- Do not require Quarkus, Maven, Gradle, or a JVM test harness for npm users.
- Do not make Docker/Testcontainers mandatory.
- Do not add an in-memory storage default for speed. Tests that need production
  storage behavior must run on RocksDB.
- Do not build a JavaScript wallet SDK as part of this package.

## Validation

The implementation is complete when these checks pass:

- the npm wrapper starts the native fixture with allocated HTTP and n2n ports
  and returns a valid readiness object;
- `apiBaseUrl + "node/tip"` returns successfully after readiness;
- temporary RocksDB storage is used by default;
- temp directories are removed after `stop()`;
- persistent-storage mode keeps its configured path;
- the npm wrapper can start and stop the fixture from a plain Node.js test;
- `fundAddress(address, ada)` calls the devnet HTTP faucet and returns
  `{ tx_hash, index, lovelace }`;
- failed startup includes enough process output to diagnose the issue;
- the fixture remains independent of JVM-only testkit types.

Current validation:

- `npm test` runs the plain Node.js fake-process lifecycle tests.
- `npm run typecheck` validates the JavaScript source and TypeScript
  definitions.
- `npm run pack:dry` validates the npm package layout.
- `.github/workflows/npm-testkit.yml` runs those checks on Linux, macOS, and
  Windows.
- `.github/workflows/release-dist.yml` builds native Linux x64, Linux arm64,
  macOS arm64, and Windows x64 distributions and publishes the matching npm
  platform packages before publishing `@bloxbean/yano-testkit`.

## Consequences

Non-JVM applications get the same production HTTP surface that deployed Yano
provides, with a small language-native lifecycle wrapper. Java applications keep
the faster embedded path from ADR-030 and can still choose HTTP when they want
to test their production client wiring.

This creates a stable process boundary that can be reused by npm, Python, Go,
shell scripts, and future Testcontainers wrappers. The tradeoff is that native
binary releases and platform asset naming become part of the testkit release
discipline.
