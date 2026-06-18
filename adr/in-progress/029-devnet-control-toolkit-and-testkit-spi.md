# ADR-029: Devnet Control Toolkit And Testkit SPI

**Status:** Accepted - initial implementation complete
**Date:** 2026-06-17
**Related:** ADR-027 R1 (Test-network kit), ADR-028 (Runtime Decomposition), ADR-021 (Snapshot Restore Coordinator), GitHub issue #19
**Supersedes:** the ADR-028 issue #19 decision to keep `DevnetToolkit` in
`runtime` for the current slice
**Implementation:** initial module split implemented and validated on 2026-06-17

## Context

ADR-028 extracted devnet operations from the runtime implementation into
runtime-owned services and an explicit `DevnetToolkit` implementation of the
public `DevnetControl` role. That was a useful first boundary: devnet operations
are now grouped, recipe-gated, and tested. The issue #19 packaging decision kept
the implementation in `runtime` because, at that point, the code did not add a
separate dependency stack and there was no immediate consumer for a devnet-free
runtime artifact.

The roadmap has changed enough to revisit that decision. ADR-027 R1 calls for a
`testkit` module, and devnet functionality is expected to grow beyond the
current fund/snapshot/restore/rollback/time-advance/genesis-shift/catch-up set.
If those capabilities continue to grow inside `runtime`, `runtime` will again
become the place where production node behavior, developer tooling, and test
harness orchestration all accumulate.

This ADR explicitly supersedes the earlier issue #19 packaging decision because
ADR-027 R1 gives us a concrete consumer for a reusable devnet control layer:
`testkit`.

The goal is not merely to move files. A premature move would create module cycles
or expose `RuntimeNode`, mutable `ChainState`, RocksDB handles, or ad hoc method
references as public contracts. The goal is to define a stable runtime SPI that
lets a devnet toolkit and testkit extend Yano without weakening the production
runtime boundary.

## Decision

Create a separate `devnet-toolkit` module and make it the owner of the
optional `DevnetControl` adapter, devnet assembly helpers, and future high-level
devnet flows. Add an initial `testkit` module on top of
`devnet-toolkit`, then grow it as real integration-test needs appear.

The runtime still owns safety-critical devnet mutation execution. Toolkit code
selects and composes operations; runtime code performs rollback, snapshot
restore, producer/time-travel mutation, genesis shifting, and UTXO writes behind
stable SPI ports.

The intended module shape is:

| Module | Responsibility |
|---|---|
| `core-api` | Public role interfaces and DTOs, including `DevnetControl`. No runtime implementation dependencies. |
| `runtime` | Production runtime, subsystem lifecycle, sync, storage, ledger, producers, maintenance gate, safety-critical devnet backing services, and a small devnet extension SPI. |
| `devnet-toolkit` | Optional devnet control adapter and assembly helpers. It exposes snapshot/restore, rollback, faucet, time advance, genesis shift, catch-up, and future devnet operations by calling runtime SPI ports. |
| `testkit` | JUnit/plain Java test harness built on `runtime` + `devnet-toolkit`: ephemeral nodes, wallets/faucet helpers, snapshots, deterministic time, multi-node fixtures, and assertions. |
| `app` | Quarkus adapter. It depends on `devnet-toolkit` only because it exposes devnet REST endpoints. Other adapters can choose whether to include it. |

Gradle project names intentionally omit the `yano-` prefix. The repo publishing
convention sets library artifact IDs to `yano-` + project name, so
`devnet-toolkit` publishes as `yano-devnet-toolkit` and `testkit` publishes as
`yano-testkit`.

`DevnetControl` remains in `core-api` as the public optional role. The concrete
toolkit adapter moves out of `runtime`.

## Runtime/Toolkit Code Partition

The split is intentionally thin at first. Moving orchestration code too early
would put the highest-risk ordering rules in the optional toolkit module and
violate ADR-021/ADR-028 safety guarantees.

| Code or responsibility | Location | Rationale |
|---|---|---|
| `DevnetToolkit` public `DevnetControl` implementation | Move to `devnet-toolkit` | It is an optional control adapter and should not be part of a relay-only runtime artifact. |
| `YanoDevnetAssembly` and devnet-specific example/testkit assembly helpers | Add to `devnet-toolkit` | These compose runtime recipes with `DevnetControl` and future testkit ergonomics. |
| Future composite devnet/test flows that only call safe SPI ports | `devnet-toolkit` | They are tooling behavior, not production runtime mechanics. |
| `DevnetSnapshotRestoreService` | Stay in `runtime` | Restore pause/drain/replace/reconcile/resume ordering is safety-critical and must remain runtime-owned. |
| `DevnetSnapshotStore` and `DevnetSnapshotCatalogService` | Stay in `runtime` | Checkpoint/path handling touches storage layout and must not leak RocksDB details. |
| `DevnetGenesisShiftService` | Stay in `runtime` | Genesis mutation affects chronology, producer restart, cache invalidation, and Haskell compatibility. |
| `DevnetTimeAdvanceService` and `DevnetCatchUpService` | Stay in `runtime` | They coordinate producer stop/produce/resume under runtime maintenance. |
| `DevnetFaucetService` UTXO write discipline | Stay in `runtime` | UTXO mutation must share rollback/restore/prune/block-apply serialization rules. |
| Rollback execution, event publication, prune pause/resume, async UTXO drain, account-history verification | Stay in `runtime` | This is node correctness behavior, not toolkit behavior. |

Later, a class may move to the toolkit only if it has become a pure coordinator
over safe SPI ports and no longer owns runtime ordering, mutable stores, or
storage handles.

## Runtime SPI

Add a runtime-owned SPI package, for example:

```text
com.bloxbean.cardano.yano.runtime.devnet.spi
```

The SPI must be capability-oriented and stable. It must not expose `RuntimeNode`,
`DirectRocksDBChainState`, mutable store implementations, raw RocksDB handles, or
general-purpose "do anything" callbacks.

### Reuse-First SPI Rule

The devnet SPI must not duplicate existing non-devnet roles just to give them a
`Devnet*` name. Runtime should compose existing role/capability interfaces where
they already express the needed contract, and introduce new devnet-specific ports
only when an operation has devnet-only mutation semantics or needs to hide unsafe
runtime ordering.

Examples:

| Existing capability | Reuse in devnet SPI | Add a new devnet port only for |
|---|---|---|
| `ChainBlockReader` / `ChainQuery` | read-only chain tip/block access | rollback and other chain-shortening mutations that must preserve runtime ordering |
| `ProducerControl` | start/stop/reset/running-state controls | mode-aware devnet production, produce-until-slot, time-travel, catch-up, or genesis-shift flows |
| `ChronologyService` / `ChronologySubsystem` | slot/time conversion ownership | a narrow query adapter if the toolkit must not depend on runtime subsystem classes |
| Snapshot restore coordinator/services | snapshot create/list/delete/restore execution | only the stable operation boundary; never raw checkpoints or RocksDB handles |
| Existing genesis/config classes | genesis metadata and protocol-parameter inputs | controlled genesis shifting and cache invalidation |

New `Devnet*` SPI interfaces are therefore thin adapters over runtime-owned
services. They must not contain parallel logic for block lookup, slot-time math,
producer lifecycle, rollback ordering, restore ordering, or UTXO mutation.

### `DevnetRuntime`

The primary SPI is a narrow facade over devnet-safe runtime capabilities. It
should expose existing roles directly when they are sufficient, and devnet-only
ports for operations that do not belong in the public production roles:

```java
public interface DevnetRuntime {
    YanoConfig config();
    RuntimeMaintenance maintenance();
    ChainBlockReader chainBlocks();
    ProducerControl producerControl();
    DevnetChainMutation chainMutation();
    DevnetProducerExtensions producerExtensions();
    DevnetFundingAccess funding();
    DevnetSnapshotAccess snapshots();
    DevnetGenesisAccess genesis();
    DevnetChronologyAccess chronology();
}
```

This is an SPI object supplied by `runtime` to `devnet-toolkit` during
assembly. It is not a public user API; it is an internal runtime extension
contract. It may live in `runtime` and be exported to optional Yano modules.
The concrete names can change during implementation, but the reuse rule above is
binding: do not create a `Devnet*` wrapper when an existing role is already the
right abstraction.

`DevnetRuntime` is an assembly-time supplier of ports, not an operation-level
facade. Toolkit operation classes should receive the specific port or ports they
need. They should not receive the whole `DevnetRuntime` object and reach across
unrelated capabilities.

The initial toolkit adapter uses only the ports needed to implement
`DevnetControl`: `chainMutation`, `snapshots`, `funding`, and
`producerExtensions`. The `maintenance`, `chainBlocks`, `producerControl`,
`genesis`, and `chronology` accessors are intentionally forward-looking for
future toolkit/testkit flows; they should remain unused until a real operation
needs them.

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
- `runExclusive(reason, ...)` clears degraded state only for the same `reason`
  after a successful operation. It must not clear unrelated degraded operations.
- The SPI intentionally has no broad `clearDegraded()` method.

### `DevnetChainMutation`

Expose only devnet chain mutations that cannot safely live in read-only chain
roles:

```java
public interface DevnetChainMutation {
    DevnetRollbackResult rollback(DevnetRollbackTarget target);
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

### `DevnetProducerExtensions`

Common producer lifecycle operations should reuse `ProducerControl`. Additional
devnet producer operations must be mode-aware and restart-safe:

```java
public interface DevnetProducerExtensions {
    boolean isAvailable();
    Optional<ProducerMode> mode();
    TimeAdvanceResult advanceBySlots(int slots);
    TimeAdvanceResult advanceUntilSlot(long targetSlot);
    TimeAdvanceResult advanceBySeconds(int seconds);
    TimeAdvanceResult catchUpToWallClock();
    long shiftGenesisAndStartProducer(int epochs);
}
```

The runtime keeps `ProducerSubsystem` ownership. The toolkit may request
operations, but it does not install raw producer implementations or manipulate
strategy state directly.

### `DevnetFundingAccess`

Faucet and future wallet/testkit helpers need a narrow UTXO write boundary:

```java
public interface DevnetFundingAccess {
    FundResult fundAddress(String address, long lovelace);
    List<FundResult> fundAddresses(List<FundingRequest> requests);
}
```

`FundingRequest` lives in `core-api` next to `FundResult` so batch funding can
be added without pulling toolkit/runtime types into public DTOs.

Batch funding is ordered but not atomic in the current runtime implementation:
if a later request fails, earlier successful funding writes remain committed.

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
}
```

The toolkit can provide higher-level flows, but the runtime owns cache
invalidation, slot-time recalculation, producer restart, and validation of
deferred time-travel modes.

The `Optional<Path>` accessors exist for Haskell compatibility tests and external
comparison tools that need to pass real genesis files to `cardano-node` or other
processes. In-memory devnet assemblies may return `Optional.empty()`. Consumers
that only need values should prefer `currentGenesisConfig()` or future content
DTOs over filesystem paths.

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

### Type Ownership

The runtime SPI lives in `runtime`, not `core-api`. It may reference runtime
types where those types are implementation contracts between Yano modules. Do not
promote this SPI to `core-api` without replacing runtime-owned types with public
DTOs.

| Type | Owner |
|---|---|
| `DevnetControl` | `core-api` public optional role |
| `FundResult`, `TimeAdvanceResult`, `SnapshotInfo`, `DevnetRollbackResult`, `DevnetRestoreResult` | `core-api` public DTOs |
| `FundingRequest` | `core-api` public DTO |
| `RuntimeMaintenance` and `Devnet*` SPI ports | `runtime.devnet.spi` internal Yano module SPI |
| `ProducerMode`, `GenesisConfig` | `runtime`; acceptable only because the SPI is runtime-owned |

## Assembly

The runtime and toolkit support distinct assembly paths:

```java
YanoNode node = YanoAssembly.relay(config).build();
YanoNode node = YanoAssembly.devnet(config).build();
YanoNode node = YanoAssembly.devnetTimeTravel(config).build();

YanoNode node = YanoDevnetAssembly.devnet(config).build();
YanoNode node = YanoDevnetAssembly.devnetTimeTravel(config).build();
```

`YanoAssembly` remains the runtime composition root. Its `devnet(...)`,
`devnetTimeTravel(...)`, and `fromConfig(...)` recipes stay in `runtime` and can
build a block-producing devnet-capable node without the toolkit module.

`YanoDevnetAssembly` is provided by `devnet-toolkit`. It delegates to the
runtime recipes and decorates the resulting node with a `DevnetControl`
implementation backed by `DevnetRuntime` SPI ports.

Rules:

- Relay and plain slot-leader assemblies do not expose `DevnetControl`.
- Runtime-only devnet and devnet-time-travel assemblies build devnet-capable
  nodes but do not expose `DevnetControl`.
- Toolkit devnet and devnet-time-travel assemblies expose `DevnetControl`.
- `YanoAssembly.fromConfig(config)` remains a runtime-only compatibility bridge:
  for devnet configs it builds a node-only devnet recipe when the toolkit is not
  used.
- `YanoDevnetAssembly.fromConfig(config)` mirrors runtime recipe selection and
  adds `DevnetControl` for devnet/devnet-time-travel recipes. Non-devnet configs
  intentionally fall back to the node-only runtime assembly so app/framework
  adapters can use one composition path safely.
- App adapters that expose devnet REST APIs must depend on `devnet-toolkit`.
- Quarkus devnet REST wiring should use `YanoDevnetAssembly.fromConfig(config)`;
  runtime-only adapters should use `YanoAssembly.fromConfig(config)`.
- A plain Java runtime user can depend only on `runtime` and never see devnet
  toolkit implementation classes.
- The ServiceLoader may be used for optional module discovery only if native-image
  configuration remains deterministic. Prefer explicit assembly methods for now.

## Testkit Module

`testkit` should depend on `devnet-toolkit`, not directly on runtime
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

Current implementation status:

- Stage 1 is implemented: `runtime.devnet.spi` exposes reuse-first ports backed
  by existing runtime services, and runtime assemblies provide those ports
  through `DevnetRuntimeProvider`.
- Stage 2 is implemented for the initial split: `devnet-toolkit` owns
  `DevnetToolkit` and `YanoDevnetAssembly`; safety-critical services remain in
  `runtime` according to the partition table.
- Stage 3 is implemented for the Quarkus adapter: app composition uses
  `YanoDevnetAssembly.fromConfig(...)` so devnet REST endpoints receive
  `DevnetControl` only through the toolkit decorator.
- Stage 4 foundation is implemented: `testkit` exists with a closeable
  `YanoDevnetTestKit` wrapper and JUnit 5 `YanoDevnetExtension`.
- Full Stage 4 testkit ergonomics, examples, native packaging checks, and
  framework examples remain future work.

Validation completed for the initial split:

- `./gradlew :runtime:compileJava :devnet-toolkit:compileJava --console=plain`
- `./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest' :devnet-toolkit:test :testkit:test :app:compileJava :app:compileTestJava --console=plain`
- `./gradlew :runtime:test :devnet-toolkit:test :testkit:test :app:test --console=plain`
- `./gradlew :app:quarkusBuild --console=plain`
- `./gradlew :app:haskellSyncTest --console=plain -q`
- `JAVA_TOOL_OPTIONS='-Dyano.exit-on-epoch-calc-error=false -Dyano.remote.protocol-magic=42' YACI_STORE_JAR="$HOME/.yaci-cli/components/store/yaci-store-all" bash scripts/devnet-adapot-comparison/run-devnet-haskell-yacistore-adapot-comparison.sh`
- Devnet epoch-crossing skill workflow with 50-slot epochs
- Past-time-travel skill workflow with dynamic PV10 genesis-shift expectation
- `git diff --check`

### Stage 1 - SPI Characterization

- Audit existing runtime/core-api roles first and map each devnet operation to an
  existing capability or a genuinely devnet-only port.
- Add the runtime SPI interfaces listed above under `runtime.devnet.spi`, using
  existing roles directly where they are sufficient.
- Implement a runtime adapter internally, backed by existing runtime services.
- Keep current `DevnetToolkit` in `runtime` temporarily.
- Add contract tests that exercise the SPI against in-memory/runtime test doubles.
- Ensure no SPI method leaks concrete runtime implementation classes and no new
  SPI interface duplicates an existing read/control role without a documented
  devnet-specific mutation reason.
- Assert operation implementations receive narrow ports rather than the aggregate
  `DevnetRuntime`.

### Stage 2 - Toolkit Module Split

- Add `devnet-toolkit` Gradle module.
- Move `DevnetToolkit` into the new module as a thin `DevnetControl` adapter over
  runtime SPI ports.
- Add `YanoDevnetAssembly` in the toolkit module.
- Keep safety-critical devnet services in `runtime` behind the SPI according to
  the code partition table above.
- Replace direct `RuntimeNode` method references with `DevnetRuntime` SPI calls.
- Keep `DevnetControl` in `core-api`.
- Update runtime assembly tests to assert production assemblies do not require the
  toolkit module.
- Add toolkit tests for rollback, restore, faucet, time advance, genesis shift,
  catch-up, and failure/degraded signaling.
- Land one real boundary consumer before freezing the SPI shape, such as a plain
  Java devnet example or a minimal testkit helper that uses faucet, snapshot, and
  time advance only through `YanoDevnetAssembly` and `DevnetControl`.

### Stage 3 - Adapter Integration

- Update Quarkus `app` to depend on `devnet-toolkit`.
- Move devnet-control assembly routing from app-local code to
  `YanoDevnetAssembly.fromConfig(...)`.
- Keep REST resources injected only with `DevnetControl`.
- Add plain Java examples for:
  - production relay without toolkit dependency;
  - devnet node with toolkit dependency;
  - time-travel devnet with toolkit dependency.
- Add Spring Boot and Micronaut notes or minimal examples showing optional
  inclusion of the toolkit module.

### Stage 4 - Testkit Module

- Add `testkit` Gradle module.
- Build JUnit 5 lifecycle extension on `YanoDevnetAssembly`.
- Move reusable Haskell/devnet test harness utilities out of app tests where
  appropriate.
- Add examples for integration tests using faucet, snapshots, time advance, and
  epoch crossing.

### Stage 5 - Native And Packaging Checks

- Verify JVM and native app builds with devnet toolkit included.
- Verify a plain runtime consumer builds without `devnet-toolkit`.
- Verify native-image config does not require reflective scanning for toolkit
  discovery.
- Compare native image size/reachability for relay-only and devnet-enabled
  assemblies when practical.
- Keep GraalVM resources/reflect config deterministic.

## Validation Requirements

The initial split is validated. Future slices that expand the toolkit/testkit
surface should continue to run:

- `./gradlew :runtime:test`
- `./gradlew :devnet-toolkit:test`
- `./gradlew :testkit:test`
- `./gradlew :app:test`
- `./gradlew :app:quarkusBuild`
- Haskell sync validation via `:app:haskellSyncTest` or the future testkit
  equivalent
- devnet AdaPot comparison against Haskell and YaciStore
- devnet epoch-crossing and past-time-travel skill workflows
- at least one plain Java example that depends on `runtime` only before the
  toolkit is promoted from internal Yano SPI to user-facing packaging guidance
- at least one devnet example that depends on `devnet-toolkit` before
  publishing toolkit ergonomics as stable
- native-image smoke build/check when native CI or local GraalVM is available

## Consequences

**Positive**

- Production runtime remains focused on node behavior and does not absorb growing
  devnet/testkit orchestration.
- Devnet features get an explicit ownership boundary and can grow without
  expanding `RuntimeNode` or app adapters.
- `testkit` can build on stable toolkit APIs instead of runtime internals.
- Framework adapters can choose whether to include devnet tooling.
- Future optional dependencies for richer devnet/testkit features stay out of
  production runtime.
- The split forces a clean extension SPI and reduces regression risk from ad hoc
  runtime method references.
- Runtime-only consumers can still build devnet-capable nodes without accepting
  the devnet control/testkit dependency stack.

**Negative**

- More modules and more assembly paths to maintain.
- The SPI must be designed carefully; too much surface recreates a broad facade,
  too little pushes toolkit code back toward internals.
- `devnet-toolkit` will be thin at first because the dangerous mutation
  services intentionally stay in `runtime`.
- Runtime artifact size reduction is modest initially; the main early value is
  API/assembly separation and a stable base for `testkit`.
- Native-image configuration needs extra verification because optional modules
  can otherwise introduce accidental reachability or reflection assumptions.

## Non-Goals

- Do not move production block-producer primitives into the toolkit.
- Do not expose `RuntimeNode`, `DirectRocksDBChainState`, or RocksDB handles.
- Do not make devnet controls available from relay or plain slot-leader recipes.
- Do not add reflective DI or runtime classpath scanning.
- Do not implement the full `testkit` in the same PR as the initial toolkit
  module split unless the SPI has already stabilized.

## Open Questions

- Should the devnet SPI be public API or explicitly internal-to-Yano modules?
  Prefer internal SPI at first, with semantic-version promises only for
  `core-api`.
- Should app devnet REST endpoints move to a separate Quarkus extension/module in
  the future? Not required for the first split, but useful if app packaging needs
  production/devnet variants.
- Should testkit own Haskell-node setup/download helpers or only consume an
  already installed node? Prefer reusable helpers with explicit opt-in downloads.
