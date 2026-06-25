# ADR-031: Native Yano Testkit For Non-JVM And npm Testing

**Status:** Proposed
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
The first language-specific wrapper should be an npm package for JavaScript and
TypeScript applications.

The shared contract is the Yano native binary started in a testkit mode. The
exact command name can evolve during implementation, but the intended shape is:

```bash
yano testkit devnet start \
  --json \
  --http-port 0 \
  --n2n-port 0 \
  --storage temp-rocksdb \
  --block-time-millis 200
```

The process should stay in the foreground. Test wrappers spawn it, read a
machine-readable readiness record from stdout, stream logs for diagnostics, and
terminate the process when the test finishes.

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

When started with `--json`, the native fixture should emit one newline-delimited
JSON object after the HTTP server and devnet are ready:

```json
{
  "type": "ready",
  "version": "0.0.0",
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

The wrapper should ignore non-JSON log lines until it sees `type: "ready"`.
After readiness, the process continues to run until terminated.

The fixture should handle:

- `SIGTERM` and `SIGINT` with graceful shutdown and cleanup;
- failed startup with a non-zero exit and useful stderr/stdout diagnostics;
- optional manifest-file output for tools that cannot easily parse stdout;
- explicit `--work-dir` and `--storage-path` for persistent/debug runs.

## npm Package

Publish an npm package such as:

```text
@bloxbean/yano-testkit
```

The package should:

- resolve a Yano native binary for the current OS and architecture;
- allow `YANO_TESTKIT_BINARY` to point at a local binary;
- cache downloaded binaries under a user cache directory;
- verify the binary is executable and reports an expected version;
- spawn the native fixture;
- parse the readiness JSON;
- expose a small typed API for lifecycle and connection details;
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

1. Add native binary testkit start mode.
   - Start a local devnet with temp RocksDB storage by default.
   - Bind HTTP and n2n ports from `0`.
   - Emit the readiness JSON only after the HTTP API is usable.

2. Add lifecycle and cleanup guarantees.
   - Gracefully stop block production and HTTP on termination.
   - Remove temp work directories on normal shutdown.
   - Preserve work directories when persistent storage is requested or startup
     fails before cleanup ownership is established.

3. Add npm package.
   - Implement binary resolution, download/cache, spawn, readiness parsing, and
     stop logic.
   - Provide TypeScript definitions.
   - Support env overrides for binary path and version.

4. Add JavaScript examples and validation tests.
   - Plain Node.js smoke test querying `/api/v1/node/tip`.
   - Vitest or Jest example using test lifecycle hooks.
   - Test that temp RocksDB storage is created and removed.
   - Test that logs are surfaced when startup fails.

5. Add optional transaction workflow examples.
   - Submit through `POST /api/v1/tx/submit`.
   - Query UTXOs through Blockfrost-compatible endpoints.
   - Keep wallet/key generation in the application or a JS Cardano library, not
     in the native fixture.

6. Consider optional container wrappers after the native contract is stable.

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

- the native fixture starts with `--http-port 0 --n2n-port 0` and emits a valid
  readiness JSON record;
- `apiBaseUrl + "node/tip"` returns successfully after readiness;
- temporary RocksDB storage is used by default;
- temp directories are removed after `stop()`;
- persistent-storage mode keeps its configured path;
- the npm wrapper can start and stop the fixture from a plain Node.js test;
- failed startup includes enough process output to diagnose the issue;
- the fixture remains independent of JVM-only testkit types.

## Consequences

Non-JVM applications get the same production HTTP surface that deployed Yano
provides, with a small language-native lifecycle wrapper. Java applications keep
the faster embedded path from ADR-030 and can still choose HTTP when they want
to test their production client wiring.

This creates a stable process boundary that can be reused by npm, Python, Go,
shell scripts, and future Testcontainers wrappers. The tradeoff is that native
binary releases and platform asset naming become part of the testkit release
discipline.
