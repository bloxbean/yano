# ADR-028: Runtime Decomposition — NodeKernel, Subsystems, and an Explicit Composition Root

**Status:** Accepted; implemented for issue #17; pre-release compatibility cleanup completed
**Date:** 2026-06-13
**Related:** ADR-027 (Strategic Direction, item P0.1), ADR-021 (Snapshot Restore Coordinator), adr/network/002 (Peer Management Module), GitHub issue #17

## Context

At ADR creation, `Yano.java` (~4,900 lines, ~60 fields) was the runtime's
orchestration class. It owned, in one class:

| Responsibility cluster | Evidence in `Yano.java` |
|---|---|
| Config & genesis resolution | constructor, genesis fallback logic, `resolvedGenesisTimestamp` |
| Peer session lifecycle & recovery | implements `PeerSessionCallbacks`; `onPeerDisconnected`, `requestPeerRecovery`, `peerSessionLock`, recovery executor |
| Sync orchestration | sync phases, `updateSyncProgress`, `maybeFastTransitionToSteadyState`, rollback classification |
| Storage wiring & reconcile | UTXO store, prune services (×3), event handlers, `*ReconcilePending` flags |
| Derived ledger state wiring | account state/history stores, epoch boundary processor, epoch params |
| Block production | **three** producer fields (`DevnetBlockProducer`, `SlotLeaderTimeTravelBlockProducer`, `BlockProducerService`) |
| Epoch nonce APIs | `getEpochNonce*`, `EpochNonceState` exposure |
| Tx validation/evaluation | setter-injected validators, `submitTransaction` |
| Devnet tooling | snapshot/restore/fund/time-advance/shift/catch-up (~1,000 lines, incl. inline `FileVisitor` and RocksDB handle adapters) |
| Listener registries & metrics | two `CopyOnWriteArrayList`s, `blocksProcessed`, status |
| Executors | inline `ScheduledExecutorService` and recovery `ExecutorService` |

The problem extends into the app module: **`YanoProducer.java` (~1,160 lines)** in
the Quarkus app re-implements assembly logic that belongs in the runtime — protocol
parameter resolution chains, transaction validator/evaluator bootstrap, bootstrap-mode
flag derivation, retention/pruning config normalization. Any non-Quarkus integration
(Spring Boot, Micronaut, plain Java) would have to duplicate this work today.

`NodeAPI` (formerly in `core-api`) was a fat interface mixing lifecycle, ledger
queries, devnet operations, and producer control, forcing all consumers to see all
concerns.

ADR-027 defines multiple future node roles (testkit, edge/indexer, tx gateway,
verifying relay, rollup framework). Each role is a *different assembly of the same
components*. Today the assembly is hardcoded in two god classes.

## Decision Drivers

1. **Multiple roles, one codebase** — assemblies must be data, not inheritance.
2. **Framework-neutral embedding** — first-class integration with Spring Boot,
   Quarkus, Micronaut, and plain Java `main()` without favoring any.
3. **GraalVM native image** — no runtime reflection, no classpath scanning.
4. **Debuggability** — a maintainer must be able to read the wiring top-to-bottom.
5. **Incremental migration during implementation, clean API at completion** — use
   compatibility while extracting seams, then remove the pre-release `Yano` and
   `NodeAPI` surfaces once downstream code is migrated.

## Decision 1 — No DI container in the runtime. Explicit composition root.

We will **not** adopt a dependency-injection framework (neither reflective like
Spring/Guice nor compile-time like Dagger) inside `core-api`/`runtime`. Instead:

- All components use **plain constructor injection** (no setters for mandatory
  collaborators, no field injection, no annotations).
- A single, explicit **composition root** (`YanoAssembly`) wires components in
  readable Java.

Rationale:

- **DI containers are for applications; Yano runtime is a library.** A library that
  embeds a container forces it transitively on every host application and invites
  container-vs-container conflicts (CDI/Arc vs Spring context vs Micronaut).
- **Native image:** reflective DI undermines the GraalVM goal; even compile-time DI
  adds build coupling and a contributor learning curve.
- **Scale doesn't justify it:** the runtime has ~15–25 top-level components. A
  hand-written composition root at this scale is a feature — it is the single place
  where the node's structure is visible and diff-reviewable.
- **Precedent:** mature JVM infrastructure (Netty bootstraps, Kafka's server
  composition) uses explicit wiring; DI containers live at the application edge.
- Framework integration is solved by **thin adapter modules** (Decision 5), not by
  putting a framework in the core.

Consequence: constructor injection discipline becomes a code-review rule. Components
must not reach for statics, singletons, or self-created executors.

## Decision 2 — Subsystem model with a minimal lifecycle SPI

Decompose the runtime into **subsystems**: cohesive units owning their components,
state, and lifecycle. The SPI is deliberately tiny:

```java
public interface Subsystem extends AutoCloseable {
    String name();
    default void init(SubsystemContext ctx) {}   // resolve cross-subsystem refs, register listeners
    void start();                                 // begin active work
    void stop();                                  // stop active work (idempotent)
    @Override default void close() {}             // release resources (DB handles, executors)
    default Health health() { return Health.up(name()); }
}
```

`SubsystemHealth` is a new runtime model, not the Quarkus/MicroProfile health type.
The Quarkus app adapts kernel health into MicroProfile readiness/liveness at the
application edge.

`SubsystemContext` exposes only: `EventBus`, `Schedulers` (shared, kernel-owned
executor factory), config view, and a typed service registry for the few
cross-subsystem lookups that cannot be constructor-passed.

### Target subsystems

| Subsystem | Extracted from | Owns |
|---|---|---|
| `ChainStorageSubsystem` | chain-state construction + storage capabilities | `ChainState`, storage capability adapters, startup migrations, block-body prune lifecycle, maintenance gate |
| `LedgerStateSubsystem` | derived-state wiring | account state/history stores, `EpochBoundaryProcessor`, epoch param tracking, their event handlers |
| `SyncSubsystem` | peer + sync logic | `PeerSessionSupervisor`, header/body managers, `LedgerApplyProcessor`, sync-phase machine, rollback classification. **Implements `PeerSessionCallbacks` here**, not on the facade |
| `ServeSubsystem` | server fields | `NodeServer` + server agents, downstream notification |
| `TxSubsystem` | mempool/validation fields | `MemPool`, eviction policy, validation/evaluation services, submission handler, **plus the validator bootstrap logic currently in `YanoProducer`** |
| `ProducerSubsystem` | three producer fields | one `BlockProduction` strategy interface; `DEVNET`, `SLOT_LEADER`, `TIME_TRAVEL` as implementations selected by assembly, not by nullable fields |
| `ChronologySubsystem` | genesis/time/nonce | genesis resolution, `SlotTimeCalculator`, era service, `EpochNonceState` + replay |
| `PluginSubsystem` | `PluginManager` | plugin discovery/lifecycle (unchanged behavior) |
| `DevnetToolkit` *(optional)* | ~1,000 devnet lines | snapshot/restore (delegating to ADR-021 coordinator), faucet, time advance, genesis shift/catch-up |

Inter-subsystem communication: the existing `EventBus` for asynchronous concerns;
direct constructor-passed interfaces where strict ordering matters (the apply path
through `LedgerApplyProcessor` stays synchronous and direct — no event-bus
indirection on the hot path).

### `DevnetToolkit` extraction path

First extract devnet tooling as runtime services and a `DevnetControl`
implementation. A physical `yano-devnet` module comes later, after the API seam is
stable. Snapshot restore currently coordinates chain state, producer state, async
UTXO handlers, prune services, account state/history, server notification, and
slot-time cache invalidation; moving it across a module boundary before those seams
exist would create unnecessary risk. Production relay assemblies should still be
able to omit this subsystem once recipes exist, and the testkit role (ADR-027 R1)
will build on the extracted services.

## Decision 3 — `NodeKernel`: small, explicit lifecycle owner

```java
public final class NodeKernel implements AutoCloseable {
    // holds an ORDERED list of subsystems (order fixed by the assembly)
    // state machine: CREATED → INITIALIZED → STARTING → RUNNING → STOPPING → STOPPED / FAILED
    // start: init all (in order), then start all; on failure → stop/close started ones in reverse
    // stop/close: reverse order, exceptions aggregated, always completes
    // exposes: state(), health() (aggregated), subsystem(Class<T>)
}
```

Rules:

- **Explicit ordering, no topological magic.** Subsystem order is written in the
  assembly. (The plugin system keeps its topo-sort; kernel subsystems are few and
  their order is architecture, which should be visible.)
- **Kernel owns executors** via a `Schedulers` component; subsystems request, never
  create. Migration is gradual: new/extracted subsystems must use `Schedulers`, and
  existing components that currently create workers internally are converted as they
  are touched. This fixes today's scattered inline executors and gives one shutdown
  point (and one place to adopt virtual threads).
- Fail-fast startup with guaranteed reverse-order cleanup — replacing today's
  ad-hoc partial-start states (`isRunning` / `isSyncing` / `isServerRunning` flags).

## Decision 4 — `YanoAssembly`: roles as recipes, builder for overrides

The composition root is a first-class, testable artifact:

```java
// Role presets (each ADR-027 role = one recipe)
YanoNode node = YanoAssembly.relay(config).build();
YanoNode node = YanoAssembly.devnet(genesis).build();
YanoNode node = YanoAssembly.devnetTimeTravel(genesis).build();
// future: YanoAssembly.verifyingRelay(config), .txGateway(config), .edgeNode(config)

// Targeted overrides for embedders and tests — replace any component, keep the recipe
YanoNode node = YanoAssembly.relay(config)
        .memPool(customMemPool)
        .stakeDataProvider(customProvider)
        .addSubsystem(myMetricsSubsystem)
        .disable(ServeSubsystem.class)
        .build();
```

- A recipe = ordered subsystem list + component construction. Recipes are plain
  methods; diffing two roles is diffing two methods.
- **All assembly intelligence currently in `YanoProducer` moves here** (or into the
  owning subsystem): protocol-params resolution chain, validator/evaluator
  bootstrap, bootstrap-mode derivation, retention config normalization. This is the
  single biggest payoff: every framework gets it for free.
- `YanoNode` is the new thin handle: `start()`, `stop()`, `close()`, `kernel()`,
  plus typed accessors to the segregated APIs (Decision 6).

## Decision 5 — Framework integration via thin adapters, config as the contract

The integration contract is: **framework config → `YanoConfig` → `YanoAssembly` →
expose `YanoNode` (and selected APIs) as beans → bridge lifecycle.** Nothing else.

| Host | Adapter | Contents (~100–300 lines each) |
|---|---|---|
| Plain Java | none needed | `YanoAssembly.relay(cfg).build().start()` |
| Quarkus | existing `app` module, refactored | thin CDI producer + `StartupEvent`/`ShutdownEvent` bridge; `YanoProducer` shrinks from ~1,160 lines to a config mapper |
| Spring Boot | new `yano-spring-boot-starter` | `@AutoConfiguration` + `@ConfigurationProperties("yano")` → `YanoConfig`; `SmartLifecycle` bridge; exposes `YanoNode` + segregated API beans |
| Micronaut | new `yano-micronaut` (on demand) | `@Factory` + `@ConfigurationProperties`; `ApplicationEventListener` lifecycle bridge |

Adapter rules: **zero assembly logic** in adapters; adapters may only (a) map
configuration, (b) expose beans, (c) bridge lifecycle and health endpoints.
If an adapter needs more, the missing piece belongs in `YanoAssembly` — that keeps
all frameworks at parity by construction.

Configuration property names are part of this adapter contract. Yano keeps the
canonical property keys in `core-api` as framework-neutral compile-time constants
(`YanoPropertyKeys`) grouped by subsystem. Quarkus, Spring Boot, Micronaut, plain
Java examples, runtime globals, and optional modules should all use those
constants instead of repeating string literals. Runtime-owner classes may keep
compatibility aliases when the key is tightly related to a policy component
(`RollbackRetentionPlanner`, `DnsCachePolicy`), but those aliases must delegate to
the shared key contract. Default values and typed binding objects remain separate
from the key constants so this cleanup does not change runtime behavior.

## Decision 6 — Remove `NodeAPI` and expose role interfaces

Split the fat interface into role interfaces in `core-api` and remove the broad
facade:

```
NodeLifecycle      start/stop/status/health
ChainQuery         tip, blocks, sync progress
LedgerQuery        protocol params, epoch nonces, genesis params
TxGateway          submitTransaction
TxEvaluationGateway optional script evaluation
ProducerControl    producer start/stop/reset
DevnetControl      snapshot/restore/fund/time (implemented only by DevnetToolkit)
```

`NodeAPI` is deleted rather than retained as a deprecated umbrella interface.
REST resources, embedders, and tests depend on the narrow interface they need; a
production relay assembly simply has no `DevnetControl` implementation to expose.
`ChainQuery` exposes tip/block reads directly and does not expose the raw
`ChainState` object.

Account-state providers follow the same rule. The public account-state SPI uses
`ChainBlockReader` for read-only replay/reconciliation and `RocksDbAccess` only
when a provider explicitly needs RocksDB handles. It does not receive the mutable
`ChainState` implementation, so a future storage engine can supply the same read
contract without emulating `DirectRocksDBChainState`.

The former public `Yano` construction surface is removed in the same cleanup. The
runtime implementation is `runtime.internal.RuntimeNode`, wired by `YanoAssembly`
and exposed through `YanoNode` plus the role interfaces above. Direct embedders
should use:

```java
YanoNode node = YanoAssembly.relay(config).build();
node.lifecycle().start();
```

## Initial issue #17 implementation slice

The first implementation slice for GitHub issue #17 should land the seams without
rewriting the whole runtime:

- Add `NodeKernel`, `Subsystem`, `SubsystemContext`, `Schedulers`, and typed
  `SubsystemHealth`, with lifecycle tests for ordered start/stop/close and failure
  cleanup.
- Add `YanoAssembly` and `YanoNode` recipes backed by `RuntimeYanoNode`. This
  makes the composition root real and testable while keeping construction policy
  out of framework adapters.
- Split `NodeAPI` into narrow role interfaces and delete the broad umbrella
  facade. REST resources and CDI injection depend on the narrow interfaces they
  actually need.
- Extract low-risk runtime services first (`ChronologyService`, devnet snapshot
  storage) and move Quarkus-side helper logic out of the large producer where it can
  be unit-tested.
- Keep Haskell sync compatibility tests as part of the safety net. If the bundled
  Haskell node version only supports protocol major 10, the test harness may use a
  protocol-10 devnet overlay; that compatibility shim must stay confined to the
  Haskell test harness and not change the normal devnet recipe.

## Stage B design note — chain-state capabilities before subsystem extraction

Stage B must not move the existing concrete-store coupling into the new subsystems.
The upstream `com.bloxbean.cardano.yaci.core.storage.ChainState` contract stays
small and sync-oriented. Yano should not turn it into a large "YanoChainState"
interface just to avoid casts. Instead, Yano-specific storage behavior is modeled
as local runtime capabilities implemented by concrete stores:

| Capability | Purpose | Expected consumers |
|---|---|---|
| `ByronEbHeaderStore` | Store Byron EBB headers without normal block indexes | `HeaderSyncManager` |
| `OriginRollbackCapable` | Clear header/body state back to origin | sync recovery and stale-header cleanup |
| `ChainStateRecovery` | Detect and repair storage corruption | startup validation and runtime probes |
| `EraMetadataStore` | Persist era starts and Shelley-boundary UTXO totals | chronology, nonce, ledger-state config |
| `ByronGenesisUtxoMetadataStore` | Persist Byron genesis outpoint metadata for Allegra cleanup | UTXO wiring |
| `NearestSlotLookup` | Resolve ad-hoc rollback targets to stored slots | rollback tooling |
| `ChainStateSnapshots` | Create/restore storage-level snapshots | devnet snapshot tooling |
| `BootstrapChainStateWriter` | Write synthetic bootstrap headers/bodies | bootstrap-state mode |

Callers should ask only for the role they need. If a collaborator needs raw
RocksDB handles, it should depend on `RocksDbSupplier` or `RocksDbAccess`
explicitly, not on `DirectRocksDBChainState`. Concrete store classes should
appear in orchestration only at construction/factory boundaries and in tests that
instantiate the store.

This capability layer is the first Stage B implementation slice because it makes
the upcoming `ChainStorageSubsystem`, `SyncSubsystem`, `ChronologySubsystem`, and
`DevnetToolkit` extractions safer: each extracted component receives a narrow
contract instead of inheriting the entire concrete chain-state implementation.

Stage B follow-up rule: if a consumer needs chain-state metadata plus another
store-specific resource, the owning subsystem should hide the resource details.
For example, `Yano` may pass `ByronGenesisUtxoMetadataStore` to the UTXO store, but
the UTXO store owns the metadata column-family lookup needed for the Allegra
bootstrap cleanup marker. Likewise, chronology adapts `ChainState` to
`EraMetadataStore` once and gives `SlotTimeCalculator` only era metadata, not a
general chain-state object.

Snapshot restore remains part of the hard Stage B seam. Until it is extracted into
`DevnetToolkit` / `SnapshotRestoreCoordinator`, restore must:

- resolve checkpoint paths through `DevnetSnapshotStore` validation, not ad-hoc
  `Path.resolve(name)` logic;
- pause producer, N2N server, async UTXO handling, and prune jobs before replacing
  RocksDB state;
- reinitialize/reconcile dependent stores before resuming server/producer work;
- run under a global maintenance/read gate so REST/query paths are quiesced while
  storage handles are replaced and response tips are resolved.

## Stage B implementation update — 2026-06-16

The current implementation has landed the main Stage B hard-seam extractions
and the pre-release compatibility cleanup:

- `ChainStorageSubsystem` now owns `DirectRocksDBChainState` /
  `InMemoryChainState` construction, storage capability exposure, startup
  migrations, block-body pruning lifecycle, and the runtime maintenance gate.
  Block-body pruning starts from the runtime lifecycle path, not from
  implementation construction. UTXO and account-history pruning follow the same
  rule: subsystem construction can create/reconcile stores, but background
  prune/lag services start only from lifecycle start/resume paths. Production
  orchestration outside this subsystem should use `ChainState`,
  `RocksDbSupplier`, `RocksDbAccess`, or one of the narrow storage capability
  interfaces.
- Public account-state extension points no longer receive raw `ChainState`.
  `AccountStateStore.reconcile(...)` accepts `ChainBlockReader`, and
  `AccountStateStoreContext` carries `ChainBlockReader` plus optional
  `RocksDbAccess`. This keeps replay/query needs separate from mutable storage
  implementation details.
- `ServeSubsystem` now owns `NodeServer`, `YaciTxSubmissionHandler`, start/stop,
  downstream notifications, and server-thread drain during stop. Block producers
  receive a `Supplier<NodeServer>` so they see the current server after snapshot
  restore or server restart. If server shutdown times out, the subsystem keeps the
  old handles for diagnosis and reports itself as stopped/degraded so restart will
  not create a second server while the old thread is still alive.
- `RuntimeMaintenanceGate` provides a fair read/write boundary. Normal REST
  requests hold a read lease. Exclusive maintenance endpoints (`devnet/rollback`,
  `devnet/restore/{name}`, `node/start`, and `node/recover`) bypass the read lease
  and acquire a write lease inside the runtime operation.
- Devnet rollback and restore now return result models from `DevnetControl`, so
  the REST response tip is resolved while the maintenance lease is still held.
- Devnet snapshot restore now fails closed during preparation: if the N2N server,
  async UTXO worker, UTXO/account-history prune workers, or block-body prune worker
  cannot drain, restore aborts before RocksDB replacement and does not create
  duplicate replacement workers. If RocksDB replacement succeeds but service resume
  fails, the operation reports failure instead of returning a successful restore
  response.
- `UtxoSubsystem` now owns UTXO store construction, event-handler registration,
  plugin/address filter wiring, startup reconciliation, prune lifecycle, lag
  metrics, async handler drain/restart, snapshot-restore reinitialization, and
  terminal store close. Devnet rollback waits for the async UTXO handler to drain
  after publishing the rollback event before producer/server resume.
- `AccountHistorySubsystem` now owns account-history store construction, event
  handler registration, startup reconciliation, prune lifecycle,
  snapshot-restore reinitialization, and rollback verification. If rollback
  verification fails after chain state has already moved, the runtime takes
  the same fail-closed path used for rollback-event delivery failures. In client
  mode, account-history pruning starts only after startup recovery completes.
- `LedgerStateSubsystem` now composes the full derived ledger-state boundary:
  account-state store construction, account-history composition, epoch-boundary
  processing, epoch-param tracking, reward/adapot/governance handler wiring,
  startup recovery ordering, snapshot-restore reinitialization/reconciliation,
  era-transition bookkeeping, direct-start genesis bootstrap publication, and
  rollback cleanup of derived epoch export artifacts. Startup recovery reconciles
  UTXO/account state before interrupted epoch-boundary recovery, and genesis
  bootstrap publication fails closed when fail-closed payloads cannot resolve era
  metadata. `RuntimeNode` delegates ledger-state/account-history accessors and
  rollback/snapshot hooks instead of retaining those stores and processors.
- `SyncSubsystem` now owns upstream peer-session lifecycle and sync state:
  `PeerSessionCallbacks`, `PeerSessionSupervisor`, peer recovery executor,
  header/body manager access, pipeline configuration, sync-phase transitions,
  rollback classification, post-rollback chain-state recovery, body-fetch resume,
  and peer recovery status. Delayed intersection transitions are cancelled or
  generation-gated on stop/recovery, and terminal peer recovery reports down
  health/readiness instead of looking like active sync. `RuntimeNode` keeps only
  status projection and externally-called callback methods.
- Failed exclusive maintenance and startup operations now mark a first-class
  degraded runtime state on `RuntimeMaintenanceGate`. `NodeStatus`, Quarkus
  readiness, `ChainStorageSubsystem` health, and kernel health projection
  all project that state. Degradation clearing is operation-scoped so a successful
  restore cannot hide an unrelated failed rollback or recovery. Startup takes the
  same exclusive write lease as restore/rollback/recovery before bootstrap,
  startup recovery, and one-shot rollback mutate storage.
- `DefaultUtxoStore` serializes block apply, rollback, prune, genesis/faucet
  writes, and snapshot reinitialization. Its RocksDB metrics sampler is now
  pause/resume aware so snapshot restore can quiesce all store-owned RocksDB
  readers before handle replacement.
- Unsafe shutdown is explicitly handled: if an apply worker cannot be stopped or
  drained, `RuntimeNode.close()` skips terminal plugin/listener/event-bus/subsystem close
  and lets `ChainStorageSubsystem` close only through the unsafe-drain path,
  avoiding listener failures against closed shared state.
- Stage B hard seams and degraded-maintenance hardening are implemented. Later
  stages completed producer, transaction, devnet-toolkit, and app-layer assembly
  extractions.

## Stage C implementation update — transaction bootstrap

The first transaction boundary is implemented as an assembly-owned bootstrap SPI:

- `runtime.tx.TransactionBootstrapContext` exposes the runtime state needed to
  build transaction services without exposing the concrete runtime implementation
  to app code.
- `runtime.tx.TransactionBootstrapOptions` carries the effective configuration
  for validation/evaluation, protocol-parameter tracking, supplementary rules,
  and script evaluator selection.
- `runtime.tx.TransactionServicesFactory` returns `TransactionServices`
  (validator and script evaluator). `YanoAssembly` installs those services on
  `RuntimeNode`.
- Pre-start configuration (`adhocRollback` and `bootstrapDataProvider`) is applied
  before transaction bootstrap, preserving startup ordering.
- In-memory devnet genesis is available through the bootstrap context, so devnet
  assemblies do not require genesis/protocol files just to initialize transaction
  validation/evaluation.
- Protocol-parameter helpers used by transaction bootstrap are exposed from
  `runtime.tx`. A deprecated block-producer-package compatibility shim remains
  for source compatibility.

The concrete Scalus/Aiken/JULC factory now lives in the optional
`tx-services` module. Runtime keeps only the SPI and does not depend on
`scalus-bridge`; Quarkus consumes `DefaultTransactionServicesFactory` from the
optional module. This preserves framework-neutral runtime assembly while giving
Spring/Micronaut/plain-Java adapters the same concrete transaction-service
factory without copying Quarkus code.

## Stage C implementation update — TxSubsystem admission boundary

The transaction runtime boundary now owns admission and mempool lifecycle:

- `runtime.tx.TransactionAdmission` is the narrow admission contract used by
  serving code.
- `runtime.tx.TxSubsystem` owns `DefaultMemPool`, transaction
  validation/evaluation services, default validator listener registration,
  mempool eviction subscription/scheduling, REST transaction submission, and N2N
  tx-submission admission.
- `ServeSubsystem` depends on `TransactionAdmission` instead of direct
  `MemPool`/`EventBus` wiring.
- `YaciTxSubmissionHandler` keeps protocol statistics, but delegates validation,
  mempool insertion, and mempool-event publication to `TxSubsystem`.
- `runtime.internal.RuntimeNode` delegates `TxGateway` and `TxEvaluationGateway` behavior to
  `TxSubsystem`; the runtime no longer owns separate transaction evaluator
  or mempool eviction fields.
- Admission lifecycle now follows runtime lifecycle: startup enables admission
  before the N2N server is exposed, restartable stop disables admission before
  server shutdown, terminal close disables admission before server close, and the
  runtime rejects REST submission while stopped.
- `TxSubsystem` owns the default validator listener subscription handles it
  creates and closes them on subsystem close. The listener resolves the current
  validation service through a supplier, so validator replacement updates
  REST/N2N admission and block-production callers consistently.
- Mempool admission rolls back newly added transactions if
  `MemPoolTransactionReceivedEvent` delivery fails, while preserving previously
  accepted duplicate entries.
- N2N tx-submission uses blocking mode consistently with the blocking-only
  handler implementation.

Concrete transaction-service construction is no longer app-local.

## Stage C implementation update — producer strategy boundary

The first producer boundary is implemented as a runtime-owned strategy holder:

- `runtime.producer.BlockProduction` is the producer strategy contract over the
  common lifecycle/control operations.
- `runtime.producer.ProducerMode` names the selected strategy:
  `DEVNET`, `SLOT_LEADER`, `DEVNET_TIME_TRAVEL`, and
  `SLOT_LEADER_TIME_TRAVEL`.
- `runtime.producer.ProducerSubsystem` owns the active strategy and exposes
  capability methods for devnet empty-block production and slot-leader
  time-travel production.
- The legacy `Yano` facade no longer holds separate `BlockProducerService`,
  `DevnetBlockProducer`, and `SlotLeaderTimeTravelBlockProducer` fields. It
  installs the concrete producer it creates into `ProducerSubsystem`.
- Devnet rollback, snapshot restore, time advance/catch-up, public
  `ProducerControl`, and shutdown now use the subsystem rather than nullable
  concrete producer fields.
- The subsystem preserves restartability: a stopped producer can be replaced
  during the `stop()`/`start()` lifecycle, while replacing a running producer is
  rejected.

This is still an incremental boundary. Subsequent Stage C slices have moved
startup planning, devnet block-builder construction, devnet producer assembly,
stake-provider selection, slot-leader key-material loading,
slot-leader signing/check construction, slot-leader nonce listener registration,
and final slot-leader producer assembly behind runtime factories. Final producer
startup strategy selection now belongs to assembly recipes: relay recipes keep
legacy config-derived compatibility, slot-leader recipes install an immediate
`SLOT_LEADER` plan, devnet recipes install an immediate `DEVNET` plan, and
time-travel recipes install deferred `DEVNET_TIME_TRAVEL` or
`SLOT_LEADER_TIME_TRAVEL` plans.

## Stage C implementation update — producer transaction-selection boundary

The producer-to-mempool coupling is now behind a Tx-owned selector boundary:

- `runtime.tx.BlockTransactionSelector` is the narrow producer-facing contract for
  pending transaction checks and block-time draining.
- `runtime.tx.BlockTransactionSelectors` centralizes the legacy mempool adapter
  and preserves previous block-production semantics:
  drain all transactions when validation/UTXO state is unavailable, or validate
  each candidate through `TransactionValidationService` with the existing
  `BlockBuildUtxoOverlay` when validation is available.
- `TxSubsystem` implements `BlockTransactionSelector`, so devnet, slot-leader,
  and slot-leader time-travel producers receive the Tx subsystem rather than a
  raw `MemPool` from `Yano`.
- The producer classes keep existing `MemPool` constructors and
  `withServerSupplier(...)` factories as compatibility adapters. New runtime
  wiring uses explicit `withTransactionSelector(...)` factories to avoid source
  ambiguity and make the boundary visible.
- Snapshot restore clears pending transactions through
  `TxSubsystem.clearPendingTransactions()` while Tx admission is paused. Restore
  resumes admission before server/producer resume only if admission was active
  before restore, and disables admission again if service resume fails. The raw
  public mempool escape hatch is removed; producers consume the
  `TxSubsystem`-owned selector.
- `TxSubsystem.memPool()` is package-private for subsystem tests only, so the
  runtime module no longer publishes a mutable mempool accessor.

This completes the raw-mempool producer boundary for runtime wiring. Producer
construction policy is still incremental, but devnet block-builder construction,
devnet producer assembly, slot-leader stake-provider selection, slot-leader
key/signed-builder/check construction, slot-leader nonce listener setup, final
slot-leader strategy assembly, and recipe-owned startup strategy selection are
now extracted.

## Stage C implementation update - producer recipe selection

Producer startup-plan selection is now owned by runtime assembly recipes instead
of a branch on the old public facade:

- `YanoAssembly.slotLeader(config)` is an explicit recipe for public or dev-mode
  slot-leader block production and installs an immediate `SLOT_LEADER` plan.
  It exposes `ProducerControl`, but not `DevnetControl`; rollback/snapshot/time
  controls are devnet-toolkit operations.
- `YanoAssembly.devnet(config)` installs an immediate `DEVNET` plan and rejects
  slot-leader/time-travel flags so plain devnet startup remains unambiguous.
- `YanoAssembly.devnetTimeTravel(config)` installs a deferred
  `DEVNET_TIME_TRAVEL` plan, or deferred `SLOT_LEADER_TIME_TRAVEL` when the
  dedicated past-time-travel slot-leader flag is set.
- The Quarkus adapter routes enabled slot-leader producer configs to the
  slot-leader recipe before devnet/time-travel routing, preserving the existing
  `%devnet-slotleader` profile and direct `ProducerStartupPlan.from(config)`
  slot-leader precedence.
- `RuntimeNode` still supports a nullable internal producer plan so focused
  runtime tests can exercise config-derived producer planning, but external
  construction goes through `YanoAssembly` recipes.

This completes producer startup-strategy selection for assembly-owned wiring.
Concrete producer construction is now assembly-owned at the recipe boundary, with
remaining startup/time/restore operations exposed through `DevnetToolkit`.

## Stage C implementation update — runtime quiescence hardening

The producer/transaction boundary now includes the maintenance and lifecycle
guards needed for restore-safe operation:

- `TxSubsystem` uses a fair admission read/write gate. Normal REST/N2N
  transaction admission holds a read lease through validation, mempool insertion,
  and mempool event publication; `pauseAdmissionAndAwait()`, terminal close, and
  `clearPendingTransactions()` take the write lease so snapshot restore can wait
  for in-flight admission before clearing the pool.
- Snapshot restore pauses Tx admission before stopping producer/server services
  and replacing storage. It clears pending transactions while admission is
  paused, and restores the prior admission/server/producer state only after
  storage and derived state are ready.
- Scheduled producer callbacks check running state after entering their
  synchronized production path, so `stop()` cannot return while a queued
  production callback is still building against old state.
- Direct runtime mutators that can move chain state or producer state now enter
  the runtime maintenance write lease: devnet snapshot/faucet/time/epoch
  controls, producer start/stop/reset, and node stop/close. Read-only snapshot
  listing uses the runtime read lease.
- The REST maintenance filter treats write-locking endpoints as exclusive
  maintenance endpoints (`devnet/*` mutators, `/node/start`, `/node/stop`, and
  `/node/recover`) so request filtering does not acquire a read lease before the
  resource calls a runtime method that needs the write lease.

Focused tests cover Tx admission pause/clear waiting for in-flight admission,
producer scheduled-stop quiescence, direct devnet/producer/lifecycle calls
waiting for active maintenance, and REST maintenance endpoint classification.
Poincare and Boole re-reviewed the Stage C quiescence slice after these fixes and
reported no remaining blockers.

## Stage C implementation update — producer startup planning

Producer strategy selection is now an explicit runtime plan instead of another
implicit branch inside producer startup:

- `runtime.producer.ProducerStartupPlan` maps producer configuration to a
  `ProducerMode` plus whether startup is deferred until genesis shift.
- Normal startup consumes the plan and starts only immediate `DEVNET` or
  `SLOT_LEADER` strategies. Past-time-travel plans load/propagate genesis and
  derive timing, but defer concrete producer construction until `/epochs/shift`.
- `shiftGenesisAndStartProducer(...)` now accepts only deferred producer plans
  (`DEVNET_TIME_TRAVEL` or `SLOT_LEADER_TIME_TRAVEL`) before mutating genesis and
  constructing producers. Immediate plans are rejected with a clear runtime error.
- Compatibility is preserved for existing unvalidated facade behavior:
  `slotLeaderMode` still takes precedence during normal startup, and
  `pastTimeTravelSlotLeaderMode=true` without `pastTimeTravelMode=true` still
  falls back to immediate devnet startup.

This slice did not move construction policy by itself, but it made
strategy-selection policy testable and ready for the subsequent devnet
block-builder and stake-provider factory extractions.

## Stage C implementation update — devnet block-builder factory

The first concrete producer-construction policy has moved out of the facade:

- `runtime.producer.DevnetBlockBuilderFactory` owns devnet signed-vs-unsigned
  block-builder selection.
- The factory owns producer key loading, Shelley genesis defaults for signed
  builder parameters, signed restart nonce replay-service preparation, and
  signed/dummy `DevnetBlockBuilder` creation.
- `RuntimeNode` supplies explicit dependencies for behavior that is still shared with
  relay and slot-leader paths: effective epoch params, genesis hash resolution,
  Shelley-start-slot initialization, nonce initialization, and block protocol
  version supplier creation.
- Protocol-version supplier creation remains lazy inside the selected builder
  path, preserving the old ordering where configured-key loading fails before
  protocol-version resolution.
- Normal devnet startup and devnet past-time-travel shift now call the factory
  instead of owning builder construction branches directly.

Devnet producer assembly is now extracted separately. Slot-leader signing/check
construction and live slot-leader nonce-listener registration are also extracted
separately.

## Stage C implementation update - devnet producer assembly

Final devnet producer assembly is now outside the facade:

- `runtime.producer.DevnetProducerFactory` owns live devnet and devnet
  time-travel producer construction from explicit runtime dependencies.
- Normal devnet startup now gives the factory the existing block builder,
  transaction selector, current-server supplier, event bus, scheduler, block
  interval, lazy-production flag, resolved genesis timestamp, slot length, and
  genesis config. The factory installs the `DEVNET` strategy but deliberately
  does not start it; the existing startup flow still starts the producer before
  epoch fast-forward and genesis UTXO storage.
- Devnet time-travel startup uses the same factory, installs
  `DEVNET_TIME_TRAVEL`, and deliberately does not start it. Shifted-genesis
  startup still enables sequential slots before starting the producer.
- Genesis timestamp mutation, epoch fast-forward, genesis UTXO seeding, and
  shifted-genesis file persistence remain in `Yano` until the broader
  devnet-toolkit extraction.

## Stage C implementation update — stake-provider factory

Slot-leader stake-provider selection is now outside the facade:

- `runtime.producer.StakeDataProviderFactory` owns live slot-leader provider
  selection: a configured YaciStore URL creates `YaciStoreStakeDataProvider`;
  otherwise devnet/single-pool mode uses `FixedStakeDataProvider`.
- The same factory owns genesis-backed past-time-travel stake-provider creation
  and validation. It creates `GenesisStakeDataProvider` and rejects missing or
  zero active pool/total stake with the same runtime error used by the old inline
  branch.
- `RuntimeNode` still invokes the factory from the same startup construction blocks, so
  checked genesis-file failures and missing-stake failures keep the previous
  exception wrapping and timing.

Slot-leader signing/check construction, live slot-leader nonce-listener
registration, and final slot-leader producer assembly are now extracted
separately.

## Stage C implementation update - slot-leader key material

Slot-leader key loading and pool-hash derivation are now outside the facade:

- `runtime.producer.SlotLeaderKeyMaterial` owns loading VRF/KES/opcert files
  from either `YanoConfig` or explicit paths, and derives the pool hash through
  the existing `SlotLeaderBlockProducer.derivePoolHash(...)` path.
- Live slot-leader startup and slot-leader past-time-travel startup now ask this
  value object for `BlockProducerKeys` and pool hash before continuing with the
  existing signed-builder, nonce-listener, slot-leader-check, and producer
  assembly logic.
- The extraction preserves old failure ordering and wrapping: null config paths
  still fail during `Path.of(...)`, key-load failures still come from
  `BlockProducerKeys.load(...)`, and both live/time-travel call sites still run
  inside the same `Yano` construction try/catch blocks.

Slot-leader signing/check construction, live slot-leader nonce-listener
registration, and final slot-leader producer assembly are now extracted
separately.

## Stage C implementation update - slot-leader signing components

Slot-leader signed-builder and slot-leader-check construction are now outside
the facade:

- `runtime.producer.SlotLeaderSigningComponents` creates the
  `SignedBlockBuilder` from slot-leader key material, KES limits, shared
  `EpochNonceState`, optional `NonceStateStore`, and the runtime
  `ProtocolVersionSupplier`.
- The same value object creates `SlotLeaderCheck` from the VRF secret key, active
  slot coefficient, and the `SignedBlockBuilder`'s shared `BlockSigner`, making
  the signer-sharing contract explicit and unit-tested.
- Live slot-leader startup and slot-leader past-time-travel startup now consume
  this value object before continuing with existing nonce-listener registration,
  stake-provider selection, genesis/catch-up shortcuts, and producer assembly.

Live slot-leader nonce-listener registration and final slot-leader producer
assembly are now extracted separately.

## Stage C implementation update - nonce listener factory

Live slot-leader nonce-listener registration is now outside the facade:

- `runtime.producer.NonceEvolutionListenerFactory` owns live slot-leader
  `NonceEvolutionListener` creation and event-bus annotation registration.
- The factory derives the own issuer vkey from `SignedBlockBuilder`, preserving
  own-produced-block skipping, and passes through the same nonce state/store,
  epoch-param provider, tracked-params flag, network magic, cursor resolver, and
  replay service.
- The mainnet/custom-network nonce tracking startup notice now lives with the
  factory and is shared by relay nonce tracking, while relay still constructs
  its listener inline with `ownIssuerVkey=null`.
- The live slot-leader call site still runs inside the same construction
  try/catch, preserving failure wrapping.

## Stage C implementation update - slot-leader producer assembly

Final slot-leader producer assembly is now outside the facade:

- `runtime.producer.SlotLeaderProducerFactory` owns live slot-leader and
  slot-leader time-travel producer construction from explicit runtime
  dependencies.
- Live slot-leader startup now gives the factory the existing signed builder,
  shared nonce state, slot-leader check, stake provider, pool hash, resolved
  genesis timestamp, and slot length. The factory installs the `SLOT_LEADER`
  strategy into `ProducerSubsystem`, starts it immediately, and keeps the
  previous startup log message in the producer boundary.
- Slot-leader time-travel startup uses the same factory with the previous block
  interval and sequential-scan limit, installs `SLOT_LEADER_TIME_TRAVEL`, and
  deliberately does not start it. Shifted-genesis startup still sets sequential
  mode and starts the producer after construction.
- Genesis seeding and epoch fast-forward shortcuts remain inside `RuntimeNode`
  because they are startup concerns shared by producer recipes.

## Stage F implementation update - debug endpoint interface

The remaining debug resource no longer depends on broad `NodeAPI`:

- `runtime.debug.DebugLedgerStateAccess` is a narrow internal interface for
  debug-only access to the default account-state store and current UTXO state.
- `RuntimeNode` implements this interface through existing subsystem accessors
  without adding debug methods to the public role interfaces.
- The Quarkus adapter exposes a `DebugLedgerStateAccess` bean from the created
  assembled node, failing clearly if a future runtime recipe does not provide the
  debug access.
- `DebugSnapshotResource` now injects `DebugLedgerStateAccess` directly instead
  of injecting a broad facade and casting back to the runtime implementation.

## Stage D implementation update - devnet faucet service

The first devnet-toolkit operation moved out of the runtime implementation:

- `runtime.devnet.DevnetFaucetService` owns faucet validation and synthetic UTXO
  injection.
- `DevnetToolkit.fundAddress(...)` exposes the public role method. `RuntimeNode`
  keeps the maintenance write lease internally and delegates the actual faucet
  operation to the service.
- The service consumes narrow capabilities: dev-mode status, installed
  block-production status, and the current `UtxoStoreWriter`. It does not depend
  on the runtime implementation, chain storage, or the server.
- Existing behavior is preserved, including validation messages and returning
  `FundResult(txHash, 0, lovelace)` after
  `UtxoStoreWriter.injectFaucetUtxo(...)`.
- Dev-mode slot-leader assemblies remain compatible: faucet gating checks for any
  installed block producer after the dev-mode check rather than restricting to
  only unconditional devnet producer modes.

## Stage D implementation update - devnet time-advance service

Regular wall-clock-independent devnet time advance has moved out of the runtime
implementation:

- `runtime.devnet.DevnetTimeAdvanceService` owns `advanceTimeBySlots(...)` and
  `advanceTimeBySeconds(...)` validation, slot conversion, producer
  stop/produce/resume, and result construction.
- `DevnetToolkit.advanceTimeBySlots(...)` and
  `DevnetToolkit.advanceTimeBySeconds(...)` expose the public role methods.
  `RuntimeNode` keeps the runtime maintenance write lease internally and delegates
  the mutation to the service.
- The service consumes narrow capabilities: dev-mode status, devnet producer
  availability, `ChainState`, and `ProducerSubsystem`. It does not depend on
  the runtime implementation, REST resources, or server state.
- Existing behavior is preserved for validation messages, max-slot enforcement,
  slot-length validation, stopping a running producer before rapid block
  production, and restarting it afterwards.
- Seconds-to-slots conversion stays in `long` until max-slot validation so large
  `seconds` inputs cannot wrap before the existing per-request limit is applied.

## Stage D implementation update - devnet catch-up service

Wall-clock catch-up has moved out of the facade:

- `runtime.devnet.DevnetCatchUpService` owns `catchUpToWallClock()` behavior for
  both unconditional devnet producers and past-time-travel slot-leader producers.
- `DevnetToolkit.catchUpToWallClock()` exposes the public role method.
  `RuntimeNode` keeps the runtime maintenance write lease internally and delegates
  the catch-up operation to the service.
- The service consumes narrow capabilities: dev-mode status, the
  past-time-travel slot-leader mode flag, `ChainState`, `ProducerSubsystem`,
  resolved genesis timestamp, slot length, and current time.
- Existing behavior is preserved for devnet vs slot-leader routing, validation
  messages, wall-clock target-slot calculation, stopping a running producer,
  producing to the target slot, resetting sequential-slot mode, restarting the
  producer, and already-caught-up result semantics.

## Stage D implementation update - shifted-genesis service

Past-time-travel shifted-genesis startup has moved out of the facade:

- `runtime.devnet.DevnetGenesisShiftService` owns
  `shiftGenesisAndStartProducer(...)` validation, epoch-shift calculation,
  shifted `systemStart` calculation/persistence, shifted-genesis application,
  fresh-start bookkeeping, resolved genesis timestamp update, and deferred
  producer-mode dispatch.
- `DevnetToolkit.shiftGenesisAndStartProducer(...)` exposes the public role
  method. `RuntimeNode` keeps the runtime maintenance write lease internally and
  delegates the operation to the service.
- `RuntimeNode` provides an explicit action adapter for the remaining
  state transitions: applying shifted genesis into runtime config/bootstrap
  state, initializing slot-time state, setting Conway/fresh-start UTXO state,
  and invoking the existing producer factories.
- Existing behavior is preserved for validation order/messages, second-truncated
  shifted `systemStart`, config genesis timestamp sequencing, Shelley genesis
  file persistence and error text, fresh-start checks, slot-time initialization,
  genesis UTXO seeding, slot-leader/devnet time-travel dispatch, and sequential
  mode before producer start.

## Stage D implementation update - snapshot restore service

Snapshot restore orchestration has moved out of the public runtime surface:

- `runtime.devnet.DevnetSnapshotRestoreService` owns snapshot restore
  validation, runtime-service quiescence, RocksDB checkpoint restore,
  reinitialize/reconcile hooks, pending transaction cleanup, downstream
  notification, slot-time cache invalidation, and resume/degraded-state
  handling.
- `DevnetToolkit.restoreDevnetSnapshotAndGetTip(...)` exposes the public role
  method. `RuntimeNode` keeps dev-mode validation and the runtime maintenance
  write lease internally, then delegates the restore operation to the service.
- The service consumes explicit action callbacks for producer, server, Tx
  admission, UTXO, ledger-state, block-body pruning, and chronology behavior. It
  does not depend on the public API surface, REST resources, or concrete
  subsystem classes.
- Existing restore ordering is preserved: checkpoint validation, Tx admission
  pause, producer/server stop, UTXO/account/block prune quiescence, checkpoint
  restore, UTXO and ledger-state reinitialization/reconciliation, pending
  transaction clear, producer reset, downstream notification, slot-time cache
  invalidation, and prior-state resume.
- Fail-closed semantics are preserved and covered: missing snapshots fail before
  runtime pause, preparation failures mark degraded without starting restore,
  restore failures after checkpoint replacement starts mark degraded, and resume
  failures after a successful restore throw instead of returning a successful
  result.

Snapshot create/list/delete now sit behind `DevnetSnapshotCatalogService`, while
restore remains behind `DevnetSnapshotRestoreService`. `DevnetToolkit` is now the
explicit `DevnetControl` component collecting the catalog, restore, faucet, time,
catch-up, and genesis-shift operations. The later decision is physical packaging
only: keep it in `runtime` for now, and move it to a separate toolkit module only
when there is a concrete need to ship a runtime artifact without devnet controls.

## Stage E implementation update - config-derived assembly selection

The Quarkus adapter no longer owns config-to-recipe routing:

- `YanoAssembly.fromConfig(config)` now selects the assembly recipe from the
  existing producer/devnet flags.
- The selection preserves old precedence: enabled `slotLeaderMode` wins before
  devnet/time-travel routing, enabled devnet block production chooses devnet or
  devnet time-travel, and all other shapes use the relay recipe.
- Isolated `pastTimeTravelSlotLeaderMode=true` without
  `pastTimeTravelMode=true` remains normal devnet-compatible behavior, matching
  the previous `ProducerStartupPlan.from(config)` fallback.
- `YanoProducer` now asks runtime assembly for the selected recipe instead of
  branching over relay/devnet/time-travel/slot-leader itself.
- Explicit recipes (`relay`, `devnet`, `devnetTimeTravel`, `slotLeader`) remain
  preferred for embedders that already know the intended role; `fromConfig` is a
  config-derived convenience bridge for app configuration.

This removes another app-layer assembly-policy branch while keeping source and
behavior compatibility for existing Quarkus profiles and direct `Yano`
construction.

## Stage E implementation update - app-layer config helpers

The remaining reusable configuration policy has moved out of the Quarkus
adapter:

- `runtime.config.RollbackRetentionPlanner`,
  `RollbackRetentionSettings`, and `RollbackRetentionGenesisValues` now own the
  rollback-retention umbrella math, property keys, and derived retention model.
  `YanoProducer` only detects Quarkus property presence and writes the effective
  values into runtime globals.
- `bootstrap-providers` now exposes
  `DefaultBootstrapDataProviderFactory` for Blockfrost/Koios provider selection.
  Runtime remains free of provider implementation dependencies, while Quarkus and
  future adapters can share the same optional module.
- `runtime.config.GenesisFileResolver` now owns known-network bundled genesis
  resolution, and the bundled mainnet/preprod/preview/sanchonet genesis JSON
  resources live in the runtime artifact. Runtime native-image resource config
  includes those genesis resources.
- `YanoConfig.defaultForNetwork(...)` centralizes known-network default config
  selection and stamps the canonical selected network on the returned config.
- `core-api.config.YanoPropertyKeys` now owns the framework-neutral property key
  contract used by the Quarkus adapter, runtime globals, ledger-state stores,
  transaction services, and runtime tuning properties. Existing DNS and rollback
  planner constants remain as owner-local aliases to the shared keys.

After these slices, `YanoProducer` no longer owns reusable assembly policy. It
maps Quarkus configuration into `YanoConfig`/`RuntimeOptions`, supplies optional
provider factories to the assembly, exposes beans, and bridges lifecycle.

## Migration Plan (strangler, always green)

Implementation note: issue #17 landed the kernel/assembly/API seams before all
hard Stage B extractions were complete. That is acceptable as long as each slice
kept compatibility during extraction. The final pre-release cleanup removes the
old broad API surfaces and the tracker records which seams are now real
subsystems.

**Stage A — Safety net + leaf extractions** *(low risk)*
1. Characterization tests: devnet e2e flows (snapshot/restore/fund/time-advance),
   sync-against-recorded-chain smoke test, REST status golden tests. Expand the
   existing app-level e2e tests and standalone manual `e2e-tests/` runners; do not
   describe `e2e-tests/` as a Gradle module unless it is intentionally wired later.
2. Extract leaf services first. `ChronologyService`, `DevnetSnapshotStore`,
   faucet, regular time advance, wall-clock catch-up, shifted-genesis startup,
   and snapshot restore are now extracted. `DevnetToolkit` is the explicit
   `DevnetControl` component; only separate physical module packaging remains
   optional.

**Stage B — The hard seams** *(highest risk, do early while attention is high)*
3. Extract `SyncSubsystem`: move the `PeerSessionCallbacks` implementation, recovery
   executor, rollback classification, and sync-phase machine. Implemented; the
   runtime implementation delegates sync callbacks and status projection.
4. Extract storage, serve, and ledger-state boundaries. Implemented for
   `ChainStorageSubsystem`, `ServeSubsystem`, `UtxoSubsystem`,
   `AccountHistorySubsystem`, and the full `LedgerStateSubsystem`. The maintenance
   gate and degraded-state reporting for failed exclusive maintenance are in place.

**Stage C — Kernel + assembly**
5. Introduce `Subsystem`, `NodeKernel`, `Schedulers`. This is implemented for the
   role-based assembly.
6. Introduce `YanoAssembly` with `relay`/`devnet`/`devnetTimeTravel` recipes. This
   is implemented through `RuntimeYanoNode`; the broad facade has been removed.
7. Collapse the three producer fields into `ProducerSubsystem` strategies.
   Implemented for the active strategy holder and capability methods; producer
   construction policy is now behind devnet and slot-leader runtime factories.
   Final strategy selection now lives in assembly recipes through
   recipe-selected `ProducerStartupPlan` injection into `RuntimeNode`.

**Stage D — API + framework story**
8. Split and remove `NodeAPI`; migrate `app` REST resources to narrow interfaces.
   Implemented. Debug endpoints use a dedicated internal interface, and no CDI
   producer exposes a broad facade.
9. Move `YanoProducer` assembly logic into `YanoAssembly`/`TxSubsystem`; shrink the
   Quarkus producer to a config mapper. Implemented for transaction
   validation/evaluation bootstrap, transaction admission ownership,
   config-derived recipe selection, reusable rollback-retention planning,
   bootstrap provider selection, bundled genesis resolution, and network default
   selection. Concrete transaction-service construction lives in the optional
   `tx-services` module; concrete bootstrap-provider construction lives in the
   optional `bootstrap-providers` module.
10. Ship `yano-spring-boot-starter` as the proof that Decision 5 holds (a starter
    that needs runtime changes reveals leaked assembly logic immediately).

Each stage is independently releasable. Because Yano is still pre-release, the
final cleanup removes the deprecated public artifacts (`Yano`, `NodeAPI`, raw
mempool access, and raw public `ChainState` exposure) now instead of carrying them
to a later major version.

## Consequences

**Positive**
- Every ADR-027 role becomes an assembly recipe instead of a fork or a flag jungle.
- Framework parity by construction; Spring/Micronaut support stops being a rewrite
  of 1,160 lines of Quarkus-side logic.
- No reflection added → native-image posture preserved.
- Subsystems are unit-testable with fakes; the assembly itself is testable
  ("assembly smoke tests" assert wiring and start/stop ordering).
- One shutdown discipline (kernel-owned executors, reverse-order close) replaces
  ad-hoc boolean state flags.

**Negative / risks**
- The composition root concentrates change traffic; mitigated by one-recipe-per-role
  methods and keeping recipes free of conditionals where possible.
- Stage B touches peer recovery and rollback paths — regression-prone; mitigated by
  Stage A characterization tests landing first.
- Temporary duplication while the facade and subsystems coexist.
- Builder overrides create unsupported combinations; mitigate with assembly-time
  validation (`build()` fails fast on incoherent recipes).

**Out of scope**
- Header verification design (ADR-027 R4) — but `SyncSubsystem` is shaped so a
  `HeaderVerifier` slots between header receipt and apply.
- Plugin SPI typing (P0.3) — follow-up ADR.
