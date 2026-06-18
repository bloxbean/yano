# ADR-030: Yano Testkit For JVM Integration Testing

**Status:** Proposed
**Date:** 2026-06-18
**Related:** ADR-027 R1 (Test-network kit), ADR-028 (Runtime Decomposition), ADR-029 (Devnet Control Toolkit And Testkit SPI), ADR-021 (Snapshot Restore Coordinator)

## Context

ADR-027 defines R1 as a short-term test-network kit: publish a
`yano-testkit` artifact, for example a JUnit 5 extension, wrapping in-memory
genesis, snapshot/restore, faucet, time advance, and past-time-travel for JVM
integration testing.

ADR-029 created the module foundation:

- `devnet-toolkit` owns the optional `DevnetControl` adapter and devnet assembly
  helpers.
- `testkit` exists as a first foundation module on top of `devnet-toolkit`.
- Runtime still owns safety-critical mutation execution: rollback, restore,
  faucet UTXO writes, time advance, catch-up, and genesis shift.
- The runtime devnet SPI has been pruned to only the ports used by real
  toolkit/testkit consumers.

The current `testkit` module intentionally remains small. It has
`YanoDevnetTestKit` and `YanoDevnetExtension`, which can build or wrap a devnet
node and expose `DevnetControl`. That proves the module boundary but does not yet
give downstream Java applications a complete integration-test experience.

This ADR defines the user-facing testkit API and implementation plan. The goal
is to let Java applications write deterministic tests such as:

```java
@RegisterExtension
static YanoDevnetExtension yano = YanoDevnetExtension.devnet()
        .withEphemeralStorage()
        .startNode();

@Test
void submitsTransfer(YanoDevnetTestKit kit) {
    TestWallet alice = kit.wallets().newWallet();
    kit.faucet().fund(alice, ada(100));
    kit.time().advanceSlots(1);
    kit.assertions().wallet(alice).hasAtLeast(ada(100));
}
```

The exact API can evolve during implementation, but the responsibilities and
boundaries below are binding.

## Decision

Grow `testkit` as a JUnit-ready and plain-Java-friendly test harness built on
public Yano roles and `DevnetControl`.

The testkit should provide:

- ephemeral devnet lifecycle;
- deterministic configuration and temporary storage;
- wallet and address fixtures;
- faucet/funding helpers;
- query helpers for tip, status, UTXO, protocol params, and epoch state;
- await/poll helpers for asynchronous node progress;
- assertion helpers for common integration-test conditions;
- snapshot helpers for test setup/rollback;
- deterministic time and epoch helpers;
- optional transaction submission/evaluation helpers;
- optional Cardano Client Lib `BackendService` integration for Java
  applications that already use CCL abstractions;
- optional app-process HTTP fixture exposing Blockfrost-compatible endpoints for
  CCL `BFBackendService` and non-Java clients;
- later, optional external-process helpers for Haskell node and Yaci Store
  compatibility tests.

The testkit must not expose `RuntimeNode`, concrete `ChainState`
implementations, RocksDB handles, raw runtime maintenance gates, or broad
"execute arbitrary runtime code" callbacks.

The first implementation slices must not add new runtime devnet SPI. They should
reuse:

- `YanoNode`
- `NodeLifecycle`
- `ChainQuery`
- `LedgerQuery`
- `TxGateway`
- `TxEvaluationGateway`
- `ProducerControl`
- `DevnetControl`

Add a new `Devnet*` runtime SPI port only when a concrete testkit feature cannot
be implemented through those public roles or the existing `DevnetControl`
surface, and record the reason in this ADR or a follow-up ADR update.

## Module And Artifact

The Gradle project remains:

```text
testkit
```

The published Maven artifact follows the repository convention:

```text
yano-testkit
```

`testkit` depends on `devnet-toolkit`. Downstream applications that only need a
production runtime should not depend on `testkit`.

CCL integration must not force Cardano Client Lib backend dependencies on every
testkit user unless those dependencies are already acceptable for the base
module. If they are large or pull in conflicting transitive dependencies, add a
focused optional Gradle project:

```text
testkit-ccl
```

The published Maven artifact would follow repository convention:

```text
yano-testkit-ccl
```

The same rule applies to app-process HTTP fixtures if Quarkus/process-management
dependencies make the base `testkit` artifact too heavy. Keep the fast
in-process testkit dependency-light.

## Target Users

The primary users are Java application developers who want to run local
integration tests against a real in-process Yano devnet. Examples:

- wallet services that need funded addresses and deterministic block progress;
- transaction builders that need submit/evaluate feedback;
- Java/Cardano Client Lib applications that already use `BackendService`,
  `QuickTxBuilder`, and backend-provider abstractions;
- non-Java applications that need a local Blockfrost-compatible devnet endpoint;
- indexers that need stable chain fixtures and rollback/snapshot flows;
- governance/reward tests that need epoch crossing;
- downstream node-sync tests that need a local n2n producer.

The API should be comfortable in plain Java and JUnit 5. It should not require
Spring, Micronaut, Quarkus, or any application framework.

The primary path remains embedded JVM tests. HTTP/server fixtures are an
additional compatibility layer for clients whose production code is already built
around backend-provider URLs.

## API Shape

### Lifecycle

`YanoDevnetTestKit` remains the root object.

Expected capabilities:

```java
YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(config);
kit.start();
kit.stop();
kit.close();

YanoNode node = kit.node();
DevnetControl devnet = kit.devnet();
```

Add a test-oriented config layer:

```java
YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
        .ephemeralStorage()
        .inMemoryGenesis()
        .blockTimeMillis(200)
        .epochLength(100)
        .build();
```

The config helper should create safe defaults for tests:

- temporary chainstate directory;
- devnet block producer enabled;
- random/free ports when server or n2n is enabled;
- optional in-memory genesis;
- fast block time;
- deterministic cleanup.

It should remain possible to pass a raw `YanoConfig` for advanced tests.

### JUnit 5 Extension

`YanoDevnetExtension` should support:

```java
@RegisterExtension
static YanoDevnetExtension yano = YanoDevnetExtension.devnet()
        .withEphemeralStorage()
        .startNode();
```

Parameter injection should include:

- `YanoDevnetTestKit`
- `YanoNode`
- `DevnetControl`
- `YanoAwait`
- `YanoFaucet`
- `YanoSnapshots`
- `YanoTime`
- `YanoQueries`
- `YanoAssertions`

The extension should support per-test lifecycle by default. A per-class mode may
be added later for long-running tests, but only with explicit cleanup semantics.

### Faucet And Wallets

Funding helpers should wrap `DevnetControl.fundAddress(...)` and stay honest
about current runtime semantics.

```java
TestWallet alice = kit.wallets().newWallet();
FundResult result = kit.faucet().fund(alice, ada(100));
List<FundResult> results = kit.faucet().fundAll(requests);
```

Rules:

- funding writes remain runtime-owned;
- batch funding is sequential and non-atomic unless runtime changes;
- helper names should not imply transaction finality beyond what the runtime
  guarantees;
- wallet helpers should create keys and addresses only, not hide unsafe runtime
  mutation.

Initial wallet helpers:

- create payment-key wallet;
- expose base/payment address;
- expose signing key material for transaction builders;
- generate deterministic wallets from seed when requested.

Stake-key, delegation, DRep, and governance wallet helpers should be added only
when tests need them.

### Queries

`YanoQueries` should wrap existing node roles:

```java
long slot = kit.queries().currentSlot();
long block = kit.queries().currentBlockNumber();
NodeStatus status = kit.queries().status();
String protocolParams = kit.queries().protocolParams();
UtxoState utxo = kit.queries().utxoState();
Optional<ProtocolParamsSnapshot> params = kit.queries().protocolParams(epoch);
```

Preferred data source order:

1. public role interfaces on `YanoNode`;
2. public DTOs in `core-api`;
3. REST endpoints only for process/app-level tests, not in-process helpers;
4. debug roles only behind explicitly named debug/test helpers.

Do not add a devnet SPI just to query data already available from `ChainQuery`,
`LedgerQuery`, or `NodeLifecycle`.

### Await Helpers

Most integration tests need polling because block production, event handling,
and ledger application are asynchronous.

`YanoAwait` should provide:

```java
kit.await().untilReady();
kit.await().untilRunning();
kit.await().untilSlotAtLeast(100);
kit.await().untilBlockAtLeast(20);
kit.await().untilEpochAtLeast(2);
kit.await().untilTxVisible(txHash);
kit.await().untilUtxo(address, predicate);
kit.await().untilNotDegraded();
```

All await methods should accept configurable timeout and poll interval. Defaults
should be short enough for unit-style integration tests and overridable for
external-process tests.

### Assertions

`YanoAssertions` should use JUnit-friendly `AssertionError` failures and avoid
forcing AssertJ or another assertion library as a required dependency.

Examples:

```java
kit.assertions().node().isRunning();
kit.assertions().node().isNotDegraded();
kit.assertions().tip().hasSlotAtLeast(100);
kit.assertions().wallet(alice).hasAtLeast(ada(100));
kit.assertions().snapshot("before-submit").exists();
kit.assertions().epoch(2).hasAdaPot(...);
```

Assertion helpers should be thin wrappers over query helpers. They should not
mutate runtime state.

### Snapshots

Snapshot helpers should make deterministic setup/rollback easy:

```java
SnapshotInfo baseline = kit.snapshots().create("baseline");
kit.snapshots().restore("baseline");

kit.snapshots().withSnapshot("before-submit", () -> {
    // test flow
});
```

Rules:

- use `DevnetControl` snapshot methods;
- never manipulate RocksDB checkpoints directly;
- expose restore results when available;
- make cleanup explicit.

### Time And Epoch Control

`YanoTime` should wrap existing devnet controls:

```java
kit.time().advanceSlots(10);
kit.time().advanceSeconds(5);
kit.time().shiftGenesisAndStartProducer(4);
kit.time().catchUpToWallClock();
kit.time().advanceToEpoch(2);
kit.time().crossEpochBoundary();
```

`advanceToEpoch` and `crossEpochBoundary` should be composed from existing time
advance and query helpers. They should not introduce a chronology SPI unless a
real feature cannot be expressed through public query/config data.

### Transactions

Start with low-level transaction helpers:

```java
String txHash = kit.transactions().submit(txCbor);
kit.transactions().submitAndAwait(txCbor);
kit.transactions().evaluate(txCbor);
```

Higher-level transaction builders should be added later and only where existing
Cardano client libraries make them straightforward:

- ADA transfer;
- metadata transaction;
- stake registration/delegation;
- DRep/governance actions.

The testkit should not become a second wallet SDK. Its role is to remove
integration-test friction around Yano devnet.

Transaction submission must be supported in both backend styles:

- embedded mode: call `YanoNode.txGateway().submitTransaction(byte[])`;
- HTTP mode: call the app-level Blockfrost-compatible
  `POST /api/v1/tx/submit` endpoint.

Evaluation should follow the same split:

- embedded mode: call `YanoNode.txEvaluationGateway()` when configured;
- HTTP mode: call the app-level evaluation endpoint.

### Backend Provider Compatibility

Many Java applications using Yano in tests will already use Cardano Client Lib's
`BackendService` abstraction in production code. The testkit should support that
adoption path without making all tests pay for HTTP if they do not need it.

Provide two compatibility modes.

Embedded CCL adapter:

```java
YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(config);
BackendService backend = YanoBackendService.from(kit);
```

Rules:

- place the adapter in `testkit-ccl` if CCL backend dependencies are too heavy
  for base `testkit`;
- implement CCL service methods from public Yano roles first:
  `TxGateway`, `TxEvaluationGateway`, `ChainQuery`, and `LedgerQuery`;
- start with the subset needed by real CCL integration tests: UTXO query,
  protocol params, transaction submit, transaction lookup where available, block
  or epoch query where available;
- unsupported CCL services should fail clearly instead of returning misleading
  empty data;
- do not add runtime SPI only to satisfy the full CCL interface surface until a
  concrete test needs that data.

HTTP/backend-provider fixture:

```java
YanoHttpDevnetTestKit kit = YanoHttpDevnetTestKit.app()
        .withEphemeralStorage()
        .start();

String baseUrl = kit.blockfrostBaseUrl();
BackendService backend = new BFBackendService(baseUrl, "test");
```

Rules:

- start the Yano app on random/free ports and expose the effective base URLs;
- reuse existing app REST resources rather than duplicating endpoint logic in
  testkit;
- support Blockfrost-compatible endpoints already provided by the app, including
  `POST /api/v1/tx/submit`;
- expose devnet control endpoints for setup flows such as fund, snapshot,
  restore, and time advance;
- keep this separate from the fast in-process fixture because it involves app
  startup, ports, logs, and process/server lifecycle.

For non-Java applications, the HTTP fixture is the primary compatibility story.
It lets MeshJS, PyCardano, Lucid, CLI scripts, or other clients point at a local
Yano Blockfrost-compatible base URL without requiring language-specific testkit
adapters.

### External Process Helpers

Move the existing app-level Haskell sync utilities only after the in-process
testkit API stabilizes.

Potential helpers:

- Haskell `cardano-node` setup/download;
- protocol-10 devnet overlay for Haskell 11.0.x compatibility;
- genesis copy helpers;
- Haskell tip query;
- Yaci Store process manager;
- AdaPot comparison helper;
- downstream n2n sync assertion.

These helpers add process management, downloads, logs, and longer timeouts. They
may remain in `testkit` if lightweight, or move to a future optional module if
they bring large dependencies or platform-specific behavior.

## Implementation Plan

### Stage 1 - Core In-Process Devnet Kit

- Add `YanoDevnetTestConfig`.
- Add temp directory and cleanup ownership.
- Expand `YanoDevnetTestKit` with:
  - `queries()`
  - `await()`
  - `faucet()`
  - `snapshots()`
  - `time()`
  - `assertions()`
- Expand `YanoDevnetExtension` to inject those helpers.
- Add tests using only in-process runtime assembly.

Validation:

- `./gradlew :testkit:test`
- `./gradlew :devnet-toolkit:test`
- focused runtime assembly tests

### Stage 2 - Wallet And Funding Helpers

- Add `TestWallet`.
- Add deterministic and random wallet creation.
- Add fund-wallet helpers.
- Add wallet balance assertions if public UTXO query support is sufficient.
- If wallet balance cannot be implemented cleanly through current public roles,
  add only a testkit-side limitation note first. Do not add runtime SPI until a
  concrete query gap is proven.

Validation:

- funding integration test;
- snapshot restore after funding;
- time advance after funding.

### Stage 3 - Time, Snapshot, And Epoch Workflows

- Add `withSnapshot(...)`.
- Add `advanceToEpoch(...)` and `crossEpochBoundary(...)`.
- Add assertions for epoch transition and no degraded runtime state.
- Add examples for reward/epoch-boundary tests.

Validation:

- devnet epoch-crossing smoke test;
- `:app:haskellSyncTest` when epoch/time semantics change.

### Stage 4 - Transaction Helpers

- Add low-level submit/evaluate wrappers.
- Add `submitAndAwait`.
- Add minimal transaction builder helpers only if they remain small and reuse
  existing Cardano client APIs.
- Verify that transaction helpers work through the same public surfaces that CCL
  adapters and app REST resources use.

Validation:

- submit transaction into devnet;
- observe UTXO/state change;
- evaluate transaction when evaluation services are configured.

### Stage 5 - Backend Provider And HTTP Compatibility

- Add a CCL `BackendService` adapter if the first CCL example needs direct
  embedded access.
- Prefer a separate `testkit-ccl` module if dependency weight or transitive
  conflicts make that cleaner.
- Add an HTTP/app fixture if non-Java or existing `BFBackendService` integration
  tests need a real base URL.
- Reuse the app's Blockfrost-compatible resources; do not reimplement REST
  semantics in testkit.
- Document which Blockfrost/CCL services are supported and which fail clearly.

Validation:

- CCL `QuickTxBuilder` example submits through `YanoBackendService` or
  `BFBackendService`;
- `BFBackendService` points at `kit.blockfrostBaseUrl()` and can query UTXO,
  protocol params, and submit tx;
- a plain HTTP client can fund an address and submit transaction CBOR through the
  app fixture;
- unsupported CCL services have explicit tests for clear failure behavior.

### Stage 6 - External Compatibility Helpers

- Move reusable Haskell sync process managers out of app e2e tests where
  appropriate.
- Add opt-in Haskell node setup and genesis-copy helpers.
- Add Yaci Store/AdaPot comparison wrapper if it can remain dependency-light.
- Keep long-running process tests tagged separately.

Validation:

- `./gradlew :app:haskellSyncTest`
- devnet AdaPot comparison against Haskell and Yaci Store
- downstream Haskell node sync from slot 0 after past-time-travel catch-up

## API Stability

The first testkit releases are allowed to evolve while Yano is pre-release.
However, the following should be treated as stable design commitments:

- testkit is user-facing and should be simple to use from any Java test suite;
- runtime remains the owner of safety-critical mutation semantics;
- testkit does not expose runtime internals;
- new runtime SPI is justified by concrete helper needs, not speculation;
- helpers are layered: lifecycle/config first, then queries/await/assertions,
  then wallets/transactions, then external process compatibility.

## Validation Requirements

Before considering this ADR implemented:

- `./gradlew :testkit:test`
- `./gradlew :devnet-toolkit:test`
- focused runtime assembly tests
- a real in-process JUnit example that funds a wallet, advances time, and asserts
  chain state
- a snapshot restore test that proves setup rollback works
- a past-time-travel test that shifts genesis, catches up, and verifies progress
- transaction submit/evaluate coverage through embedded testkit helpers
- CCL/backend-provider coverage once `YanoBackendService` or HTTP fixture support
  is added
- Blockfrost-compatible endpoint coverage for `POST /api/v1/tx/submit`, UTXO
  query, and protocol-params query once HTTP fixture support is added
- `:app:haskellSyncTest` after any change that affects genesis/time/sync
- AdaPot comparison after any change that affects epoch boundaries, rewards, or
  genesis fixtures

## Consequences

**Positive**

- Java applications get a practical local integration-test harness.
- CCL applications can test against Yano without rewriting code that already
  depends on `BackendService`.
- Non-Java clients can test against the same Blockfrost-compatible surface used
  by app-level integration tests.
- ADR-027 R1 becomes a concrete user-facing artifact rather than only packaging.
- Tests can use snapshots and time control instead of slow end-to-end setup for
  every scenario.
- Existing app e2e logic can gradually move into reusable helpers.
- Runtime/devnet boundaries stay clean because test ergonomics live in `testkit`.

**Negative**

- More public API surface to maintain.
- Wallet and transaction helpers can grow too broad if not constrained.
- CCL adapter support may introduce extra dependency-management pressure.
- HTTP fixtures are slower and more operationally complex than embedded tests.
- External process helpers can add platform-specific complexity.
- Await/assertion helpers can hide real timing problems if defaults are too
  forgiving.

## Non-Goals

- Do not turn testkit into a full wallet SDK.
- Do not attempt full Blockfrost API compatibility as part of the first testkit
  implementation.
- Do not require CCL dependencies for users who only need embedded Yano roles if
  those dependencies make the base artifact heavy.
- Do not require the app/REST fixture for fast in-process JVM tests.
- Do not expose `RuntimeNode`, RocksDB, or concrete chain-state implementations.
- Do not duplicate runtime rollback, restore, faucet, producer, or chronology
  mechanics.
- Do not require Spring, Micronaut, Quarkus, or CDI.
- Do not add Haskell/Yaci Store process management to the first implementation
  slice.
- Do not add new runtime devnet SPI without a concrete helper that needs it.

## Open Questions

- Should external Haskell/Yaci Store helpers stay in `testkit` or become a later
  optional module if dependencies grow?
- Should assertion helpers remain plain JUnit-style or offer optional AssertJ
  adapters?
- Should per-class JUnit lifecycle be supported in the first implementation or
  delayed until cleanup semantics are proven?
- How much transaction-building convenience belongs in testkit versus examples?
- Should the CCL adapter live in base `testkit` or a separate `testkit-ccl`
  module?
- What is the minimum supported `BackendService` surface for the first CCL
  integration slice?
- Should the HTTP/app fixture live in base `testkit` or a separate optional
  module if Quarkus/process lifecycle dependencies are too heavy?
