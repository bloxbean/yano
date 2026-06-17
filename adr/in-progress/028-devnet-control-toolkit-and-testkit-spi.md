# ADR-028 Devnet Control Toolkit And Testkit SPI

**Status:** Proposed
**Date:** 2026-06-17
**Related:** ADR-027 R1 (Test-network kit), ADR-028 (Runtime Decomposition), ADR-021 (Snapshot Restore Coordinator), GitHub issue #19

## Context

ADR-028 extracted devnet operations from the runtime implementation into
runtime-owned services and an explicit `DevnetToolkit` implementation of the
public `DevnetControl` role. That was a useful first boundary: devnet operations
are now grouped, recipe-gated, and tested. The issue #19 packaging decision kept
the implementation in `runtime` because, at that point, the code did not add a
separate dependency stack and there was no immediate consumer for a devnet-free
runtime artifact.

The roadmap has changed enough to revisit that decision. ADR-027 R1 calls for a
`yano-testkit` module, and devnet functionality is expected to grow beyond the
current fund/snapshot/restore/rollback/time-advance/genesis-shift/catch-up set.
If those capabilities continue to grow inside `runtime`, `runtime` will again
become the place where production node behavior, developer tooling, and test
harness orchestration all accumulate.

The goal is not merely to move files. A premature move would create module cycles
or expose `RuntimeNode`, mutable `ChainState`, RocksDB handles, or ad hoc method
references as public contracts. The goal is to define a stable runtime SPI that
lets a devnet toolkit and testkit extend Yano without weakening the production
runtime boundary.

## Decision

Create a separate `yano-devnet-toolkit` module and make it the owner of devnet
control implementations. Add a `yano-testkit` module later on top of
`yano-devnet-toolkit`.

The intended module shape is:

| Module | Responsibility |
|---|---|
| `core-api` | Public role interfaces and DTOs, including `DevnetControl`. No runtime implementation dependencies. |
| `runtime` | Production runtime, subsystem lifecycle, sync, storage, ledger, producers, maintenance gate, and a small devnet extension SPI. |
| `yano-devnet-toolkit` | Optional devnet control implementation: snapshot/restore, rollback, faucet, time advance, genesis shift, catch-up, and future devnet operations. |
| `yano-testkit` | JUnit/plain Java test harness built on `runtime` + `yano-devnet-toolkit`: ephemeral nodes, wallets/faucet helpers, snapshots, deterministic time, multi-node fixtures, and assertions. |
| `app` | Quarkus adapter. It depends on `yano-devnet-toolkit` only because it exposes devnet REST endpoints. Other adapters can choose whether to include it. |

`DevnetControl` remains in `core-api` as the public optional role. The concrete
toolkit implementation moves out of `runtime`.

## Runtime SPI

Add a runtime-owned SPI package, for example:

```text
com.bloxbean.cardano.yano.runtime.devnet.spi
```

The SPI must be capability-oriented and stable. It must not expose `RuntimeNode`,
`DirectRocksDBChainState`, mutable store implementations, raw RocksDB handles, or
general-purpose "do anything" callbacks.

### `DevnetRuntime`

The primary SPI is a narrow facade over devnet-safe runtime capabilities:

```java
public interface DevnetRuntime {
    YanoConfig config();
    RuntimeMaintenance maintenance();
    DevnetChainAccess chain();
    DevnetProducerAccess producer();
    DevnetUtxoAccess utxo();
    DevnetSnapshotAccess snapshots();
    DevnetGenesisAccess genesis();
    DevnetChronologyAccess chronology();
}
```

This is an SPI object supplied by `runtime` to `yano-devnet-toolkit` during
assembly. It is not a public user API; it is an internal runtime extension
contract. It may live in `runtime` and be exported to optional Yano modules.

### `RuntimeMaintenance`

Devnet operations that mutate runtime state must use the same maintenance gate as
startup, rollback, restore, and recovery:

```java
public interface RuntimeMaintenance {
    <T> T runExclusive(String reason, MaintenanceMutation<T> mutation);
    <T> T runRead(String reason, Supplier<T> read);
    void markDegraded(String operation, Throwable cause);
}
```

Rules:

- Toolkit code never pauses producers, pruners, async handlers, or servers by
  directly reaching into runtime classes.
- Mutating toolkit operations run inside `runExclusive(...)`.
- If an operation mutates state and later fails, it must call `markDegraded(...)`
  or use an operation wrapper that does so automatically.
- Validation failures before mutation should fail to the caller without marking
  runtime degraded.

### `DevnetChainAccess`

Expose only chain operations needed by devnet controls:

```java
public interface DevnetChainAccess {
    Optional<ChainTip> tip();
    Optional<ChainTip> headerTip();
    Optional<Block> blockByNumber(long blockNumber);
    Optional<Block> blockBySlot(long slot);
    DevnetRollbackResult rollback(DevnetRollbackTarget target);
    long produceUntilSlot(long targetSlot);
}
```

The rollback method is intentionally result-returning and runtime-owned. The
runtime keeps rollback ordering, event publication, prune pause/resume, async
UTXO drain, account-history verification, and downstream notification semantics.
The toolkit chooses targets and exposes user-facing operations; the runtime owns
the safety-critical mutation.

### `DevnetSnapshotAccess`

Snapshot restore remains safety-critical and must stay behind an explicit
capability:

```java
public interface DevnetSnapshotAccess {
    SnapshotInfo create(String name);
    DevnetRestoreResult restore(String name);
    List<SnapshotInfo> list();
    void delete(String name);
}
```

The runtime implementation preserves ADR-021 ordering:

- validate snapshot name/path before pausing runtime services;
- pause admission, producer/server, async UTXO handler, metrics samplers, and
  pruners in the existing safe order;
- replace storage only after pre-drain succeeds;
- reinitialize and reconcile UTXO, ledger-state, account-state/history;
- resume only services that were running before restore;
- mark degraded on post-replacement resume failure.

The toolkit must not manipulate RocksDB checkpoints directly.

### `DevnetProducerAccess`

Producer controls must be mode-aware and restart-safe:

```java
public interface DevnetProducerAccess {
    boolean isAvailable();
    Optional<ProducerMode> mode();
    void stop();
    void start();
    void resetToChainTip();
    TimeAdvanceResult produceSlots(int slots);
    TimeAdvanceResult produceUntilWallClock();
    long shiftGenesisAndStartProducer(int epochs);
}
```

The runtime keeps `ProducerSubsystem` ownership. The toolkit may request
operations, but it does not install raw producer implementations or manipulate
strategy state directly.

### `DevnetUtxoAccess`

Faucet and future wallet/testkit helpers need a narrow UTXO write boundary:

```java
public interface DevnetUtxoAccess {
    FundResult fundAddress(String address, long lovelace);
    List<FundResult> fundAddresses(List<FundingRequest> requests);
}
```

The runtime implementation validates dev mode/block-production availability and
serializes writes with the same UTXO store mutation discipline used by rollback,
restore, pruning, and block apply.

### `DevnetGenesisAccess`

Genesis operations should be explicit because they affect Haskell compatibility,
slot timing, epoch length, and producer startup:

```java
public interface DevnetGenesisAccess {
    Optional<Path> shelleyGenesisFile();
    Optional<Path> byronGenesisFile();
    Optional<Path> alonzoGenesisFile();
    Optional<Path> conwayGenesisFile();
    Optional<Path> protocolParametersFile();
    GenesisConfig currentGenesisConfig();
    GenesisConfig shiftSystemStartByEpochs(int epochs);
}
```

The toolkit can provide higher-level flows, but the runtime owns cache
invalidation, slot-time recalculation, producer restart, and validation of
deferred time-travel modes.

### `DevnetChronologyAccess`

Time controls should use the chronology boundary, not reparse genesis files:

```java
public interface DevnetChronologyAccess {
    long currentWallClockSlot();
    long slotLengthMillis();
    long epochLength();
    long slotToUnixTime(long slot);
    void invalidateCaches();
}
```

This keeps `ChronologySubsystem` as the runtime owner for slot-time state while
allowing toolkit operations to reason about time.

## Assembly

The runtime should support two assembly paths:

```java
YanoNode node = YanoAssembly.relay(config).build();
YanoNode node = YanoDevnetAssembly.devnet(config).build();
YanoNode node = YanoDevnetAssembly.devnetTimeTravel(config).build();
```

`YanoAssembly` remains the production runtime composition root. `YanoDevnetAssembly`
is provided by `yano-devnet-toolkit` and wraps/decorates the runtime assembly with
a `DevnetControl` implementation.

Rules:

- Relay and plain slot-leader assemblies do not expose `DevnetControl`.
- Devnet and devnet-time-travel assemblies expose `DevnetControl` only when the
  toolkit module is present.
- App adapters that expose devnet REST APIs must depend on `yano-devnet-toolkit`.
- A plain Java runtime user can depend only on `runtime` and never see devnet
  implementation classes.
- The ServiceLoader may be used for optional module discovery only if native-image
  configuration remains deterministic. Prefer explicit assembly methods for now.

## Testkit Module

`yano-testkit` should depend on `yano-devnet-toolkit`, not directly on runtime
internals. It should provide:

- JUnit 5 extension for ephemeral devnet lifecycle;
- temporary chainstate and isolated port allocation;
- wallet/key fixture creation and faucet funding;
- snapshot save/restore helpers;
- deterministic time advance and epoch crossing helpers;
- Haskell compatibility helpers that apply the protocol-10 overlay when needed;
- multi-node local topology helpers for downstream sync and future peer tests;
- assertions for tip progress, epoch nonce, AdaPot, ledger-state, and REST
  readiness.

The testkit must not expose `RuntimeNode`, `DirectRocksDBChainState`, or raw
RocksDB handles. Advanced tests that need lower-level inspection should use
dedicated debug roles or explicit test-only capabilities.

## Implementation Plan

### Stage 1 - SPI Characterization

- Add the runtime SPI interfaces listed above under `runtime.devnet.spi`.
- Implement a runtime adapter internally, backed by existing runtime services.
- Keep current `DevnetToolkit` in `runtime` temporarily.
- Add contract tests that exercise the SPI against in-memory/runtime test doubles.
- Ensure no SPI method leaks concrete runtime implementation classes.

### Stage 2 - Toolkit Module Split

- Add `yano-devnet-toolkit` Gradle module.
- Move `DevnetToolkit` and devnet service classes into the new module.
- Replace direct `RuntimeNode` method references with `DevnetRuntime` SPI calls.
- Keep `DevnetControl` in `core-api`.
- Update runtime assembly tests to assert production assemblies do not require the
  toolkit module.
- Add toolkit tests for rollback, restore, faucet, time advance, genesis shift,
  catch-up, and failure/degraded signaling.

### Stage 3 - Adapter Integration

- Update Quarkus `app` to depend on `yano-devnet-toolkit`.
- Move devnet assembly routing from app-local code to `YanoDevnetAssembly`.
- Keep REST resources injected only with `DevnetControl`.
- Add plain Java examples for:
  - production relay without toolkit dependency;
  - devnet node with toolkit dependency;
  - time-travel devnet with toolkit dependency.
- Add Spring Boot and Micronaut notes or minimal examples showing optional
  inclusion of the toolkit module.

### Stage 4 - Testkit Module

- Add `yano-testkit` Gradle module.
- Build JUnit 5 lifecycle extension on `YanoDevnetAssembly`.
- Move reusable Haskell/devnet test harness utilities out of app tests where
  appropriate.
- Add examples for integration tests using faucet, snapshots, time advance, and
  epoch crossing.

### Stage 5 - Native And Packaging Checks

- Verify JVM and native app builds with devnet toolkit included.
- Verify a plain runtime consumer builds without `yano-devnet-toolkit`.
- Verify native-image config does not require reflective scanning for toolkit
  discovery.
- Compare native image size/reachability for relay-only and devnet-enabled
  assemblies when practical.
- Keep GraalVM resources/reflect config deterministic.

## Validation Requirements

Before the split is considered complete:

- `./gradlew :runtime:test`
- `./gradlew :yano-devnet-toolkit:test`
- `./gradlew :yano-testkit:test` once the module exists
- `./gradlew :app:test`
- `./gradlew :app:quarkusBuild`
- Haskell sync validation via `:app:haskellSyncTest` or the future testkit
  equivalent
- devnet AdaPot comparison against Haskell and YaciStore
- at least one plain Java example that depends on `runtime` only
- at least one devnet example that depends on `yano-devnet-toolkit`
- native-image smoke build/check when native CI or local GraalVM is available

## Consequences

**Positive**

- Production runtime remains focused on node behavior and does not absorb growing
  devnet/testkit orchestration.
- Devnet features get an explicit ownership boundary and can grow without
  expanding `RuntimeNode` or app adapters.
- `yano-testkit` can build on stable toolkit APIs instead of runtime internals.
- Framework adapters can choose whether to include devnet tooling.
- Future optional dependencies for richer devnet/testkit features stay out of
  production runtime.
- The split forces a clean extension SPI and reduces regression risk from ad hoc
  runtime method references.

**Negative**

- More modules and more assembly paths to maintain.
- The SPI must be designed carefully; too much surface recreates a broad facade,
  too little pushes toolkit code back toward internals.
- Some short-term duplication may exist while current runtime services are
  adapted to the new SPI.
- Native-image configuration needs extra verification because optional modules
  can otherwise introduce accidental reachability or reflection assumptions.

## Non-Goals

- Do not move production block-producer primitives into the toolkit.
- Do not expose `RuntimeNode`, `DirectRocksDBChainState`, or RocksDB handles.
- Do not make devnet controls available from relay or plain slot-leader recipes.
- Do not add reflective DI or runtime classpath scanning.
- Do not implement the full `yano-testkit` in the same PR as the initial toolkit
  module split unless the SPI has already stabilized.

## Open Questions

- Should `YanoDevnetAssembly` live in `com.bloxbean.cardano.yano.devnet` or under
  `com.bloxbean.cardano.yano.runtime.devnet`? Prefer a package that makes module
  ownership clear.
- Should the devnet SPI be public API or explicitly internal-to-Yano modules?
  Prefer internal SPI at first, with semantic-version promises only for
  `core-api`.
- Should app devnet REST endpoints move to a separate Quarkus extension/module in
  the future? Not required for the first split, but useful if app packaging needs
  production/devnet variants.
- Should testkit own Haskell-node setup/download helpers or only consume an
  already installed node? Prefer reusable helpers with explicit opt-in downloads.
