# ADR-028 Implementation Tracker

**ADR:** `028-runtime-decomposition-nodekernel-composition-root.md`
**Design doc:** `028-runtime-decomposition-high-level-design.md`
**Current status:** Issue #17 core decomposition and reviewer hardening are implemented and validated. Issue #21 runtime-kernel follow-up is implemented and validated: kernel ownership is load-bearing, shared scheduler ownership is wired, adapter readiness uses kernel health, chronology and producer startup orchestration are extracted, and maintenance failure signaling is consistent. The earlier issue #19 "keep in runtime for now" packaging decision is superseded by `029-devnet-control-toolkit-and-testkit-spi.md`, because the roadmap now includes larger devnet functionality and a `testkit` module.
**Last updated:** 2026-06-17

ADR-029 implementation update: the initial devnet toolkit split is implemented.
`runtime` now exposes a reuse-first devnet SPI and no longer installs
`DevnetControl` directly on runtime devnet recipes. `devnet-toolkit`
provides `DevnetToolkit` and `YanoDevnetAssembly`; Quarkus app composition uses
the toolkit assembly; `testkit` has an initial managed devnet test-kit
foundation.

## Summary

ADR-028's issue #17 implementation scope is complete. The composition/API slice
landed an interim runtime kernel boundary, explicit assembly root, and narrow API seams; Stage B
extracted the chain-storage boundary, downstream serving boundary, runtime
maintenance gate used by devnet restore/rollback/recovery, UTXO ownership, full
derived ledger-state ownership, upstream sync ownership, and degraded runtime
status for failed exclusive maintenance. The first Stage C transaction slice moved
validator/evaluator installation out of `YanoProducer` and into runtime assembly
through a small runtime SPI. The follow-up transaction slice put mempool construction,
validation/evaluation service ownership, eviction lifecycle, REST submission, and
N2N tx-submission admission behind `TxSubsystem`. The first producer slice
collapsed the old producer fields behind a `ProducerSubsystem` and explicit
`BlockProduction` strategies. The follow-up producer/transaction boundary moved
block transaction selection behind a TxSubsystem-owned selector so producer
construction no longer receives a raw mempool from `Yano`. The same slice now
serializes Tx admission, producer controls, node stop/close, and REST
write-locking endpoints through the runtime maintenance gate. The latest producer
slices make startup strategy selection explicit, move devnet block-builder
construction behind a runtime factory, move slot-leader stake-provider
selection/validation behind a runtime factory, and move slot-leader key loading
plus pool-hash derivation and slot-leader signed-builder/check construction
behind runtime value objects. The latest slices move live slot-leader nonce
listener registration, final slot-leader producer assembly, and final devnet
producer assembly behind runtime factories, and move producer startup-strategy
selection into runtime assembly recipes. The Stage D devnet-toolkit slices moved
faucet validation, UTXO injection, regular slots/seconds time advance,
wall-clock catch-up, shifted-genesis startup, snapshot catalog operations, and
snapshot restore orchestration behind runtime devnet services and now exposes
those operations through an explicit `DevnetToolkit` implementation of
`DevnetControl`. The remaining debug resource now injects a dedicated internal
debug interface instead of broad `NodeAPI`. The latest Stage E slices moved
config-derived recipe routing, rollback-retention planning, bootstrap partial-state
policy, bootstrap-provider selection, bundled genesis resolution/resources, and
known-network default selection out of app-local logic. The pre-release cleanup removed the old broad public
`Yano` construction surface, the `NodeAPI` facade, raw `Yano.getMemPool()`, the public
`TxSubsystem` mempool accessor, and the unused in-memory devnet genesis setter;
runtime construction now goes through `YanoAssembly`/`Yano` and role
interfaces. `ChainQuery` now exposes tip/block reads directly instead of leaking
raw `ChainState`, account-state providers receive `ChainBlockReader` plus optional
`RocksDbAccess` instead of mutable chain storage, and the implementation class
lives in `runtime.internal`. Direct kernel ownership of the real runtime stages,
shared scheduler ownership, producer startup orchestration, chronology ownership,
and maintenance-failure signaling were completed under issue #21.

## Completed In Issue #17

- Added runtime kernel primitives:
  - `NodeKernel`
  - `Subsystem`
  - `SubsystemContext`
  - `ServiceRegistry`
  - `Schedulers`
  - `SubsystemHealth`
- Added explicit assembly entry points:
  - `YanoAssembly`
  - `Yano`
  - `RuntimeYano`
- Split and removed `NodeAPI`; `core-api` now exposes narrow role interfaces:
  - `NodeLifecycle`
  - `ChainQuery`
  - `LedgerQuery`
  - `TxGateway`
  - `TxEvaluationGateway`
  - `ProducerControl`
  - `DevnetControl`
- Updated Quarkus CDI producers to expose narrow role beans.
- Migrated REST resources from broad `NodeAPI` injection to narrow roles.
- Added low-risk service extractions:
  - chronology service
  - devnet snapshot store
  - app-level rollback retention, genesis, bootstrap, and protocol-parameter helpers
- Fixed lifecycle semantics:
  - `stop()` remains restartable
  - `close()` performs terminal cleanup
  - app shutdown goes through `Yano.close()`
- Scoped devnet controls to devnet assembly recipes through `DevnetToolkit`.
- Plain slot-leader assemblies no longer advertise `DevnetControl`; devnet
  controls are exposed only for devnet/devnet-time-travel recipes.
- Moved `RuntimeNode` to `com.bloxbean.cardano.yano.runtime.internal`; native-image
  metadata and docs now point at `YanoAssembly`/`Yano` instead of `new Yano(...)`.
- Removed raw `ChainState` from `ChainQuery`; REST resources now use role-level
  tip/block methods.
- Removed raw `ChainState` from the account-state public SPI;
  `AccountStateStore.reconcile(...)` now uses `ChainBlockReader`, and
  `AccountStateStoreContext` carries read-only block access plus optional
  `RocksDbAccess`.
- Ensured block-body, UTXO, and account-history pruning start from runtime
  lifecycle/resume paths rather than during construction.
- Added Haskell sync PV10 overlay for `cardano-node` 10.5.2 compatibility. The
  normal devnet recipe remains protocol major 11; only the Haskell sync overlay
  uses protocol major 10.

## Stage B Completed In Current Slice

- Added Yano-owned chain-state capability interfaces instead of widening upstream
  `com.bloxbean.cardano.yaci.core.storage.ChainState`:
  - `ByronEbHeaderStore`
  - `OriginRollbackCapable`
  - `ChainStateRecovery`
  - `EraMetadataStore`
  - `ByronGenesisUtxoMetadataStore`
  - `NearestSlotLookup`
  - `ChainStateSnapshots`
  - `BootstrapChainStateWriter`
- Updated `DirectRocksDBChainState` and `InMemoryChainState` to implement only the
  capabilities they support.
- Removed concrete `DirectRocksDBChainState` / `InMemoryChainState` checks from
  production consumers such as header/body sync, chronology, bootstrap, pruning,
  snapshot, era, and migration code.
- Extracted `ChainStorageSubsystem` around:
  - `ChainState` construction
  - storage capability exposure
  - RocksDB access/supplier exposure
  - startup migrations
  - block-body prune lifecycle
  - runtime maintenance gate ownership
- Extracted `ServeSubsystem` around:
  - `NodeServer`
  - `YaciTxSubmissionHandler`
  - N2N server start/stop
  - downstream notification
  - server-thread cleanup during stop
- Added `RuntimeMaintenanceGate` and Quarkus `RuntimeMaintenanceGateFilter`.
  Normal REST requests hold read leases; exclusive maintenance endpoints bypass
  the read lease and acquire the write lease inside runtime code.
- Routed `/devnet/rollback`, `/devnet/restore/{name}`, and `/node/recover`
  through exclusive maintenance.
- Added result-returning devnet control APIs:
  - `rollbackDevnet(DevnetRollbackTarget)`
  - `restoreDevnetSnapshotAndGetTip(String)`
- Moved rollback target resolution and restore response tip resolution under the
  maintenance lease so REST resources do not read chain state after maintenance
  finishes.
- Switched block producers to receive `Supplier<NodeServer>` so they use the
  current server after restart or snapshot restore.
- Exposed the maintenance gate through `Yano.maintenanceGate()` so the app
  layer no longer downcasts directly to the legacy `Yano` facade.
- Extracted `UtxoSubsystem` around:
  - UTXO store construction
  - plugin/address filter wiring
  - event-handler registration
  - startup reconciliation
  - prune service and lag-metric lifecycle
  - async event-handler drain/restart
  - snapshot-restore reinitialization
  - terminal event-handler/store close
- Extracted `AccountHistorySubsystem` around:
  - account-history store construction
  - event-handler registration
  - startup reconciliation
  - prune service lifecycle
  - snapshot-restore reinitialization
  - rollback verification
- Extracted full `LedgerStateSubsystem` around:
  - account-state store construction
  - `AccountHistorySubsystem` composition
  - epoch-boundary processing
  - epoch-parameter tracking and effective provider resolution
  - reward/adapot/governance handler wiring
  - startup derived-state recovery ordering
  - snapshot-restore reinitialization and reconciliation
  - era-transition bookkeeping and Shelley-start UTXO total capture
  - genesis bootstrap publication ownership
- Extracted `SyncSubsystem` around:
  - `PeerSessionCallbacks`
  - `PeerSessionSupervisor`
  - peer recovery executor and failure tracking
  - header/body manager access
  - pipeline/sequential sync startup decisions
  - sync-phase state machine
  - ChainSync rollback classification and recovery
  - body-fetch resume and downstream notification hooks
  - peer recovery/status projection
- Updated devnet rollback to pause UTXO, account-history, and block-body pruners
  before `ChainState.rollbackTo`, publish the rollback event, drain/restart the
  async UTXO handler, verify account-history rollback, then resume producer/server
  work.
- Updated snapshot restore to pause the async UTXO handler, UTXO prune,
  UTXO RocksDB metrics sampler, account-history prune, and block-body prune before
  RocksDB checkpoint replacement, then reinitialize/reconcile UTXO, account-state,
  and account-history stores before resuming runtime services.
- Updated unsafe shutdown so terminal plugin/listener/event-bus/subsystem close is
  skipped if an apply worker cannot be stopped or drained. This avoids closing
  shared state underneath queued listener work.
- Updated `DefaultUtxoStore` so apply, rollback, prune, genesis/faucet writes, and
  snapshot reinitialization are serialized, and its RocksDB metrics sampler can be
  paused/resumed for snapshot restore.
- `SyncSubsystem` now cancels/generation-gates delayed intersection phase
  transitions on stop/recovery, and terminal peer recovery failures project as
  non-syncing/down health so app readiness does not stay green.
- `LedgerStateSubsystem` startup recovery now reconciles UTXO/account state before
  interrupted epoch-boundary recovery, starts account-history pruning only after
  client-mode recovery completes, owns cleanup of derived epoch export artifacts
  after rollback, and fails closed when genesis bootstrap data requires era
  metadata that is unavailable.
- `RuntimeMaintenanceGate` now records degraded runtime state for failed exclusive
  maintenance/startup operations, exposes operation-scoped degraded status, and
  preserves the correct active reason across nested leases. `NodeStatus`, app
  readiness, `ChainStorageSubsystem` health, and the legacy kernel health adapter
  all project this state consistently.
- Runtime startup now takes the same exclusive maintenance lease as restore,
  rollback, and recovery. `/node/start` bypasses the normal REST read lease to
  avoid read-to-write lock upgrade deadlock while startup bootstrap/recovery
  mutates storage.

## Stage C Transaction Bootstrap Slice

- Added runtime transaction bootstrap contracts:
  - `TransactionBootstrapContext`
  - `TransactionBootstrapOptions`
  - `TransactionServices`
  - `TransactionServicesFactory`
- `YanoAssembly` now owns transaction-service installation. App code supplies a
  concrete factory, but the factory receives only the runtime context/options and
  returns validator/evaluator services; it no longer mutates the concrete `Yano`
  facade directly.
- `YanoAssembly` applies legacy pre-start configuration, including adhoc rollback
  and bootstrap data provider wiring, before invoking transaction bootstrap so the
  transaction factory observes the same runtime state as the old `YanoProducer`
  path.
- `TransactionBootstrapContext` exposes in-memory devnet genesis so devnet
  assemblies can bootstrap transaction validation/evaluation without requiring
  genesis files on disk.
- Moved `EffectiveProtocolParamsSupplier` to `runtime.tx` and kept a deprecated
  `runtime.blockproducer` compatibility shim for source compatibility.
- Added a transaction-facing `ProtocolParamsMapper` facade in `runtime.tx` so app
  transaction bootstrap code no longer depends on block-producer package helpers.
- Added optional `tx-services` module for concrete transaction-service
  construction:
  - `DefaultTransactionServicesFactory`
  - `RuntimeSlotConfigSupplier`
  - `TransactionProtocolParamsResolver`
  - `ProtocolParamsResolution`
- `tx-services` depends on `runtime` and `scalus-bridge`; `runtime` keeps only the
  transaction bootstrap SPI and does not depend on Scalus.
- The Quarkus app now depends on `tx-services` instead of direct `scalus-bridge`
  for transaction bootstrap and wires `DefaultTransactionServicesFactory::create`
  into `YanoAssembly`.
- Real app integration coverage now exercises:
  - static protocol-param bootstrap through `YanoAssembly` and the optional
    transaction factory
  - in-memory devnet genesis bootstrap without configured genesis/protocol files
  - lifecycle ordering between legacy pre-start hooks and transaction bootstrap
- Reviewer follow-up restored eager transaction slot-config validation whenever a
  zero-time source is already available, while preserving deferred resolution for
  cases that genuinely depend on runtime genesis timestamp resolution.

## Stage C TxSubsystem Ownership Slice

- Added runtime transaction-admission contract:
  - `TransactionAdmission`
- Added `TxSubsystem` as the owner for:
  - `DefaultMemPool` construction
  - transaction validation/evaluation services
  - default transaction validator listener registration
  - mempool eviction subscription and periodic eviction task
  - REST transaction submission admission and relay callback
  - N2N tx-submission admission through `YaciTxSubmissionHandler`
- `ServeSubsystem` now depends on `TransactionAdmission` instead of direct
  `MemPool`/`EventBus` wiring, so the server no longer duplicates transaction
  validation and mempool-event publication logic.
- `YaciTxSubmissionHandler` now tracks N2N stats while delegating all validation,
  mempool insertion, and mempool event publication to `TxSubsystem`.
- `Yano` delegates `TxGateway` and `TxEvaluationGateway` methods to
  `TxSubsystem`, and producer construction now reads transaction-validation
  service state through the subsystem.
- Focused coverage now verifies:
  - accepted REST submission publishes validation and mempool events, admits to
    mempool, and relays the accepted transaction
  - rejected REST submission stops before mempool admission and relay
  - admission origin is preserved for validation and mempool event metadata
  - N2N tx-submission delegates admission with `txsubmission` origin
  - N2N validation rejection increments rejected stats without accepted admission
  - stopped/closed transaction admission is rejected
  - runtime transaction admission rejects REST submission while the node is stopped
  - mempool-event delivery failure rolls back only newly admitted transactions
  - default validator listener replacement follows the current validation service
  - `TxSubsystem.close()` unsubscribes its default validator listener
  - mempool eviction start is idempotent across restartable stop/start
- Reviewer follow-up fixed admission lifecycle ordering:
  - `RuntimeNode.start()` enables transaction admission before exposing the N2N server
  - `RuntimeNode.stop()` disables admission before stopping the server
  - `RuntimeNode.close()` closes admission before server close so half-stopped server
    handlers cannot admit into a closing runtime
  - N2N tx-submission config now uses blocking mode consistently with the
    blocking-only handler
- Reviewer follow-up fixed listener ownership:
  - `DefaultTransactionValidatorListener` dereferences a current-service supplier
    instead of capturing the first installed validation service
  - `TxSubsystem` retains and closes annotation-registered listener handles
- The remaining transaction work is not admission ownership; it is deciding
  whether concrete Scalus/Aiken/JULC bootstrap belongs in runtime or in an
  optional transaction module consumed by `YanoAssembly`.

## Stage C Producer Strategy Slice

- Added runtime producer contracts:
  - `ProducerMode`
  - `BlockProduction`
  - `BlockProductions`
  - `ProducerSubsystem`
- Replaced the old runtime fields:
  - `BlockProducerService blockProducerService`
  - `DevnetBlockProducer devnetBlockProducer`
  - `SlotLeaderTimeTravelBlockProducer slotLeaderTimeTravelBlockProducer`
- The runtime still constructs the existing producer implementations, but installs
  them into `ProducerSubsystem` as explicit strategies:
  - `DEVNET`
  - `SLOT_LEADER`
  - `DEVNET_TIME_TRAVEL`
  - `SLOT_LEADER_TIME_TRAVEL`
- Public `ProducerControl`, devnet rollback, snapshot restore, time advance,
  catch-up, and shutdown now use the subsystem lifecycle/capability methods
  instead of direct nullable fields.
- The subsystem allows a stopped producer to be replaced during the restartable
  `stop()`/`start()` lifecycle, but rejects replacing a running producer.
- Block-building, key-loading, nonce setup, and slot-leader construction have
  moved behind strategy factories or assembly recipes.

## Stage C Producer Startup Planning Slice

- Added `ProducerStartupPlan` so producer flag interpretation is explicit and
  testable before concrete producer construction starts.
- `Yano.startBlockProducer()` now consumes the plan:
  - immediate `DEVNET` starts the devnet producer
  - immediate `SLOT_LEADER` starts the slot-leader producer
  - deferred time-travel plans load/propagate genesis and timing, then wait for
    genesis shift
- `Yano.shiftGenesisAndStartProducer(...)` now preserves the old
  `pastTimeTravelMode=false` error ordering, then accepts only deferred plans
  before mutating genesis or constructing producers.
- Compatibility cases are covered:
  - `slotLeaderMode` still wins over `pastTimeTravelMode` during normal startup
  - isolated `pastTimeTravelSlotLeaderMode=true` still falls back to normal
    immediate devnet startup
- Focused coverage now verifies normal devnet startup planning, slot-leader
  precedence, both deferred time-travel plans, disabled block-producer rejection,
  isolated slot-leader-time-travel flag compatibility, and shift rejection for
  immediate plans.

## Stage C Devnet Block-Builder Factory Slice

- Added `DevnetBlockBuilderFactory` as the runtime owner for devnet block-builder
  construction policy.
- `Yano` no longer owns the old signed-vs-unsigned devnet builder branch; normal
  devnet startup and devnet past-time-travel shift call the factory instead.
- The factory owns:
  - complete producer-key detection
  - producer key loading for signed devnet mode
  - Shelley genesis defaults for signed builder KES/nonce parameters
  - signed restart `NonceReplayService` preparation when the chain state supports
    `NonceStateStore`
  - signed `SignedBlockBuilder` and unsigned `DevnetBlockBuilder` creation
- Shared nonce/protocol behavior remains callback-based for this slice:
  `Yano` still supplies effective epoch params, genesis hash resolution,
  Shelley-start-slot initialization, producer nonce initialization, and lazy
  block protocol-version supplier creation. This keeps relay, signed-devnet, and
  slot-leader nonce behavior unified until the next nonce/slot-leader factory
  extraction.
- Protocol-version supplier creation stays lazy inside the chosen builder path,
  preserving old failure ordering when configured key loading fails.
- Focused coverage now verifies unsigned fallback, partial-key compatibility,
  signed builder creation, protocol-version supplier propagation, signed restart
  nonce replay preparation, and key-load failure ordering.

## Stage C Stake-Provider Factory Slice

- Added `StakeDataProviderFactory` as the runtime owner for slot-leader
  stake-provider selection.
- Live slot-leader startup now asks the factory for stake data:
  - nonblank `stakeDataProviderUrl` creates `YaciStoreStakeDataProvider`
  - blank or missing URL creates `FixedStakeDataProvider`
- Slot-leader past-time-travel startup now asks the factory for genesis-backed
  stake data. The factory creates `GenesisStakeDataProvider`, validates positive
  pool stake and total stake for epoch 0, and preserves the old missing-stake
  exception message.
- `Yano` invokes the factory inside the same construction try blocks as before,
  preserving exception wrapping for genesis-file and missing-stake failures.
- Focused coverage now verifies YaciStore selection, fixed-provider fallback,
  genesis stake loading for the fixture producer pool, and missing genesis stake
  rejection.

## Stage C Slot-Leader Key Material Slice

- Added `SlotLeaderKeyMaterial` as the runtime owner for slot-leader key loading
  and pool-hash derivation.
- Live slot-leader startup and slot-leader past-time-travel startup no longer
  call `BlockProducerKeys.load(...)` or derive pool hash inline in `Yano`.
- The value object preserves existing behavior:
  - config-path conversion still happens before key loading
  - key loading still uses `BlockProducerKeys.load(...)`
  - pool hash still comes from `SlotLeaderBlockProducer.derivePoolHash(...)`
  - both `Yano` call sites still run inside their existing construction
    try/catch blocks
- Focused coverage now verifies explicit-path loading, config-path loading, and
  old null-config-path failure ordering.

## Stage C Slot-Leader Signing Components Slice

- Added `SlotLeaderSigningComponents` as the runtime owner for the paired
  construction of:
  - `SignedBlockBuilder`
  - `SlotLeaderCheck`
- Live slot-leader startup and slot-leader past-time-travel startup no longer
  construct `SignedBlockBuilder` or `SlotLeaderCheck` inline in `Yano`.
- The value object keeps the previous wiring:
  - same slot-leader keys and KES limits
  - same shared `EpochNonceState`
  - same optional `NonceStateStore`
  - same runtime `ProtocolVersionSupplier`
  - same VRF secret key and active-slot coefficient
  - same `SignedBlockBuilder.getBlockSigner()` shared with `SlotLeaderCheck`
- Focused coverage now verifies signed builder/check construction, derived pool
  hash propagation, shared-signer usability through the check path, and required
  protocol supplier validation.

## Stage C Nonce Listener Factory Slice

- Added `NonceEvolutionListenerFactory` as the runtime owner for live
  slot-leader nonce-listener creation and event-bus annotation registration.
- Live slot-leader startup no longer constructs or registers
  `NonceEvolutionListener` inline in `Yano`.
- The factory preserves the previous wiring:
  - own issuer vkey still comes from `SignedBlockBuilder.getIssuerVkeyHex()`
  - same shared `EpochNonceState`
  - same optional `NonceStateStore`
  - same effective epoch-param provider and tracked-params flag
  - same protocol magic
  - same nonce snapshot cursor resolver
  - same optional replay service
  - same `AnnotationListenerRegistrar` and default `SubscriptionOptions`
- Relay nonce tracking remains inline with `ownIssuerVkey=null`; only the
  startup tracking-mode log helper moved to the factory so the warning policy is
  in one place.
- Focused coverage now verifies slot-leader registration subscribes the
  annotated block-apply and rollback listeners, plus the existing nonce replay
  and extra-entropy tests still pass.

## Stage C Slot-Leader Producer Assembly Slice

- Added `SlotLeaderProducerFactory` as the runtime owner for live slot-leader and
  slot-leader time-travel producer assembly.
- Live slot-leader startup no longer constructs `SlotLeaderBlockProducer` or
  installs/starts the strategy inline in `Yano`.
- Slot-leader past-time-travel startup no longer constructs
  `SlotLeaderTimeTravelBlockProducer` or installs the deferred strategy inline in
  `Yano`.
- The factory preserves the previous wiring:
  - same `ChainState`
  - same `BlockTransactionSelector` from `TxSubsystem`
  - same current `NodeServer` supplier
  - same event bus and scheduler
  - same signed block builder, nonce state, slot-leader check, stake provider,
    pool hash, genesis timestamp, and slot timing parameters
  - live startup still installs `SLOT_LEADER` and starts immediately
  - shifted-genesis time-travel startup still installs `SLOT_LEADER_TIME_TRAVEL`
    without starting until the existing shifted-genesis flow enables sequential
    mode and starts it
- Genesis seeding and epoch fast-forward shortcuts intentionally remain in
  `Yano` for the devnet-toolkit extraction, not in the producer factory.
- Focused coverage now verifies live slot-leader install/start behavior,
  deferred slot-leader time-travel installation, and the related key, signing,
  nonce-listener, startup-plan, and producer-subsystem compatibility tests still
  pass.

## Stage C Devnet Producer Assembly Slice

- Added `DevnetProducerFactory` as the runtime owner for live devnet and devnet
  time-travel producer assembly.
- Normal devnet startup no longer constructs `DevnetBlockProducer` or installs
  the strategy inline in `Yano`.
- Devnet past-time-travel startup no longer constructs `DevnetBlockProducer` or
  installs the deferred strategy inline in `Yano`.
- The factory preserves the previous wiring:
  - same `ChainState`
  - same `BlockTransactionSelector` from `TxSubsystem`
  - same current `NodeServer` supplier
  - same event bus and scheduler
  - same block builder, block interval, lazy-production flag, resolved genesis
    timestamp, slot length, and `GenesisConfig`
  - live startup still installs `DEVNET` and remains unstarted until the
    existing caller starts it before epoch fast-forward and genesis UTXO storage
  - shifted-genesis time-travel startup still installs `DEVNET_TIME_TRAVEL`
    without starting until the existing shifted-genesis flow enables sequential
    mode and starts it
- Genesis timestamp mutation, epoch fast-forward, genesis UTXO seeding, and
  shifted-genesis file persistence intentionally remain in `RuntimeNode` because
  they are shared startup/runtime-state transitions, not producer factory policy.
- Focused coverage now verifies live devnet deferred install/start behavior,
  deferred devnet time-travel installation, and the related block-builder,
  block-producer, startup-plan, slot-leader-factory, and producer-subsystem
  compatibility tests still pass.

## Stage C Producer Recipe Selection Slice

- Added `YanoAssembly.slotLeader(config)` and `Role.SLOT_LEADER` as an explicit
  assembly recipe for public or dev-mode slot-leader block production.
- `YanoAssembly` now passes a recipe-selected `ProducerStartupPlan` into
  `RuntimeNode`:
  - relay recipes can still use config-derived planning internally when no
    explicit plan is supplied
  - slot-leader recipes install immediate `SLOT_LEADER`
  - plain devnet recipes install immediate `DEVNET`
  - time-travel recipes install deferred `DEVNET_TIME_TRAVEL`, or deferred
    `SLOT_LEADER_TIME_TRAVEL` when the dedicated past-time-travel slot-leader
    flag is set
- Direct public `new Yano(...)` construction has been removed; embedders use
  `YanoAssembly`.
- The Quarkus adapter now routes `enableBlockProducer && slotLeaderMode` to the
  slot-leader recipe before devnet/time-travel routing. This preserves the
  existing `%devnet-slotleader` profile and direct slot-leader precedence for
  `slotLeaderMode=true + pastTimeTravelMode=true`.
- Devnet controls remain capability-gated: dev-mode slot-leader assemblies expose
  `DevnetControl`, while public slot-leader assemblies do not.
- Focused coverage verifies recipe-selected plans for devnet, slot-leader,
  devnet time-travel, slot-leader time-travel, invalid recipe/flag combinations,
  and app routing for the devnet-slotleader profile shape.

## Stage C Producer Transaction Selection Slice

- Added a Tx-owned block transaction-selection contract:
  - `BlockTransactionSelector`
  - `BlockTransactionSelectors`
- `TxSubsystem` now implements `BlockTransactionSelector` and owns the block-time
  drain semantics for pending transactions:
  - drain-all behavior when validation or UTXO state is unavailable
  - validation-backed drain behavior when transaction validation is available
  - spent-input overlay behavior through the existing `BlockBuildUtxoOverlay`
- Snapshot restore now pauses Tx admission before server/producer pause and
  RocksDB/UTXO replacement, clears pending transactions while admission is
  paused, then resumes admission before the server and producer only if admission
  was active before restore.
- `DevnetBlockProducer`, `SlotLeaderBlockProducer`, and
  `SlotLeaderTimeTravelBlockProducer` now depend internally on
  `BlockTransactionSelector` instead of raw `MemPool`.
- Existing `MemPool` constructors and `withServerSupplier(...)` factories remain
  as compatibility adapters, but production runtime wiring now uses the explicit
  `withTransactionSelector(...)` factories and passes `txSubsystem`.
- `RuntimeNode` no longer stores a raw mempool field, no longer passes raw mempool into
  producers, and snapshot restore clears pending transactions through
  `TxSubsystem.clearPendingTransactions()`.
- The public raw mempool escape hatch has been removed. `TxSubsystem.memPool()`
  remains public only for focused runtime tests and compatibility adapters. New
  code uses `TxGateway` for admission and `BlockTransactionSelector` for producer
  selection.
- Focused coverage now verifies:
  - `TxSubsystem` drains pending block transactions and clears its mempool
  - the selector dereferences the current validation-service supplier at drain
    time instead of capturing stale services
  - rejected block-selection transactions are dropped and removed from the pool
  - `DevnetBlockProducer.withTransactionSelector(...)` produces through the new
    selector boundary
  - existing devnet block-producer mempool compatibility tests still pass

Follow-up quiescence hardening completed for this slice:

- `TxSubsystem` now uses a fair admission read/write gate. Normal admission holds
  a read lease through validation, mempool insertion, and mempool-event
  publication; pause/close/clear take the write lease so snapshot restore waits
  for in-flight admission before clearing pending transactions.
- `TxSubsystem.clearPendingTransactions()` clears under the same admission write
  gate, avoiding races with an admission that passed validation but has not yet
  inserted or published the mempool event.
- Devnet snapshot restore pauses Tx admission before server/producer/storage
  replacement and only resumes admission if it was active before restore.
- Scheduled producer callbacks check running state after entering their
  synchronized production path, so producer stop waits for an active scheduled
  production attempt to exit.
- Direct runtime mutators that can move state now enter the runtime maintenance
  write lease: devnet snapshot/faucet/time/epoch controls, producer
  start/stop/reset, and node stop/close. Snapshot listing uses a runtime read
  lease.
- `RuntimeMaintenanceGateFilter` now excludes all REST endpoints that call
  write-locking runtime methods, including `POST /node/stop`, so request
  filtering does not create a read-to-write deadlock.
- Additional focused coverage now verifies:
  - Tx admission pause and clear wait for in-flight admission
  - slot-leader time-travel producer stop waits for running scheduled production
  - direct devnet mutation, producer control, and node stop/close wait behind an
    active maintenance lease
  - REST maintenance endpoint classification covers devnet mutators,
    `/node/start`, `/node/stop`, and `/node/recover`

## Stage F Debug Endpoint Interface Slice

- Added `DebugLedgerStateAccess` as the narrow internal interface for debug-only
  access to:
  - the default account-state store, when the runtime is using one
  - the current `UtxoState`
- `Yano` implements the interface through existing subsystem accessors without
  widening `NodeAPI` or the public role interfaces.
- `YanoProducer` exposes `DebugLedgerStateAccess` as a CDI bean from the created
  legacy node and fails clearly if a future recipe does not provide that debug
  access.
- `DebugSnapshotResource` no longer injects broad `NodeAPI`, unwraps the CDI
  proxy, or casts the result back to `Yano`.
- Focused coverage now verifies unavailable account-state and UTXO debug paths
  still return 503 through the narrow interface.

## Stage D Devnet Faucet Service Slice

- Added `DevnetFaucetService` as the runtime devnet owner for faucet validation
  and synthetic UTXO injection.
- `Yano.fundAddress(...)` now only takes the runtime maintenance write lease and
  delegates to the service.
- The service receives narrow capabilities instead of the full facade:
  - dev-mode supplier
  - installed block-production supplier
  - current `UtxoStoreWriter` supplier
- The extracted behavior preserves the old validation order and messages:
  - dev mode is required
  - a block producer must be installed
  - UTXO storage must be enabled
  - address must be non-empty
  - lovelace must be positive
- The service preserves faucet semantics by calling
  `UtxoStoreWriter.injectFaucetUtxo(...)` and returning
  `FundResult(txHash, 0, lovelace)`.
- Reviewer follow-up fixed dev-mode slot-leader compatibility by checking generic
  installed block production rather than only `DEVNET`/`DEVNET_TIME_TRAVEL`
  producer modes.
- Focused coverage verifies success, all validation failures, and a real
  `ProducerSubsystem` slot-leader install remains accepted for dev-mode faucet
  use.

## Stage D Devnet Time Advance Service Slice

- Added `DevnetTimeAdvanceService` as the runtime devnet owner for regular
  slots/seconds time advance.
- `Yano.advanceTimeBySlots(...)` and `Yano.advanceTimeBySeconds(...)` now only
  take the runtime maintenance write lease and delegate the mutation to the
  service.
- The service receives narrow capabilities instead of the full facade:
  - dev-mode supplier
  - devnet-production supplier
  - `ChainState`
  - `ProducerSubsystem`
- The extracted behavior preserves the old validation order and messages:
  - dev mode is required
  - a devnet block producer must be installed
  - slots and seconds must be positive
  - the per-request max-slot guard is enforced
  - slot length must be configured
- The service preserves the old rapid-block behavior by stopping a running
  devnet producer, producing empty blocks to the target slot, returning the new
  tip/block count, and restarting the producer when it was previously running.
- Seconds-to-slots conversion now stays in `long` until max-slot validation so
  oversized `seconds` inputs cannot wrap before the existing limit is applied.
- Focused coverage verifies slot advance, seconds conversion, sub-slot minimum,
  validation failures, invalid slot length, stop/produce/resume behavior, and the
  oversized-seconds guard.

## Stage D Devnet Catch-Up Service Slice

- Added `DevnetCatchUpService` as the runtime devnet owner for wall-clock
  catch-up.
- `Yano.catchUpToWallClock()` now only takes the runtime maintenance write lease
  and delegates the mutation to the service.
- The service receives narrow capabilities instead of the full facade:
  - dev-mode supplier
  - past-time-travel slot-leader mode supplier
  - `ChainState`
  - `ProducerSubsystem`
  - resolved genesis timestamp supplier
  - slot-length supplier
  - current-time supplier
- The extracted behavior preserves the old devnet path:
  - dev mode is required
  - a devnet block producer must be installed
  - wall-clock target slot is derived from current time, resolved genesis
    timestamp, and slot length
  - already-caught-up requests return the current tip without producer mutation
  - running producers are stopped, rapid empty blocks are produced to the target
    slot, sequential-slot mode is disabled, and previously running producers are
    restarted
- The extracted behavior also preserves the old slot-leader time-travel path:
  - `pastTimeTravelSlotLeaderMode` selects leader-aware catch-up
  - the installed producer must be `SLOT_LEADER_TIME_TRAVEL`
  - `lastCheckedSlot` is used for catch-up progress and no-tip result fallback
  - leader blocks are produced to the wall-clock target slot before sequential
    mode is disabled
- Focused coverage verifies devnet catch-up, already-caught-up devnet result,
  slot-leader catch-up, slot-leader no-tip/already-caught-up result, validation
  failures, stop/produce/resume behavior, and sequential-mode reset.

## Stage D Shifted-Genesis Service Slice

- Added `DevnetGenesisShiftService` as the runtime devnet owner for
  past-time-travel shifted-genesis startup orchestration.
- `Yano.shiftGenesisAndStartProducer(...)` now only takes the runtime maintenance
  write lease and delegates the mutation/startup flow to the service.
- The service receives narrow capabilities and explicit callbacks instead of the
  full facade:
  - past-time-travel mode supplier
  - `ProducerStartupPlan` supplier
  - installed-production supplier
  - current-time supplier
  - shifted-genesis action adapter
- The extracted behavior preserves the old validation order and messages:
  - past-time-travel mode is required
  - only deferred producer startup plans are accepted
  - a producer cannot already be installed
  - epochs must be positive
  - Shelley genesis data is required
- The service now owns epoch-shift calculation, shifted `systemStart` truncation
  to seconds, config genesis timestamp update, Shelley genesis file persistence,
  shifted `GenesisConfig` application, resolved genesis timestamp update,
  slot-time initialization, Conway fresh-start marker, genesis UTXO seeding, and
  deferred producer-mode dispatch.
- `Yano` keeps the existing concrete producer factory calls behind explicit
  actions for slot-leader time-travel and devnet time-travel startup, and still
  sets sequential-slot mode before starting either producer.
- Focused coverage verifies devnet shifted-genesis startup, slot-leader
  shifted-genesis startup, validation messages/order, shift-millis calculation,
  shifted `systemStart` truncation, resolved timestamp propagation, fresh-start
  callbacks, and producer-mode dispatch.

## Stage D Snapshot Restore Service Slice

- Added `DevnetSnapshotRestoreService` as the runtime devnet owner for snapshot
  restore orchestration.
- `Yano.restoreDevnetSnapshotAndGetTip(...)` now keeps only dev-mode validation
  and the runtime maintenance write lease before delegating to the service.
- The service receives explicit action callbacks instead of the full facade:
  producer lifecycle/reset, server pause/resume/notification, Tx admission
  pause/clear/resume, UTXO and ledger-state pause/reinitialize/resume hooks,
  block-body prune pause/resume, and slot-time cache invalidation.
- The extracted behavior preserves the previous ordering:
  - validate checkpoint through `DevnetSnapshotStore`
  - pause Tx admission before producer/server/storage handle changes
  - stop producer and N2N server
  - drain async UTXO work and pause UTXO metrics/prune, account-history prune,
    and block-body prune services
  - restore the RocksDB checkpoint
  - reinitialize/reconcile UTXO and ledger-state stores
  - clear pending transactions, reset producer tip, notify downstream clients,
    and invalidate slot-time cache
  - resume previously active services in UTXO, ledger, prune, Tx, server, then
    producer order
- Failure behavior remains fail-closed:
  - missing snapshots fail before runtime services are paused
  - preparation drain failures mark the maintenance lease degraded without
    starting restore
  - failures after checkpoint replacement starts mark the runtime degraded and
    leave services paused for operator restart
  - successful restore with resume failure throws and marks the runtime degraded
- Focused coverage verifies success ordering, missing snapshot behavior,
  preparation failure degradation, restore failure degradation, and resume
  failure degradation.

## Stage D Snapshot Catalog Service Slice

- Added `DevnetSnapshotCatalogService` as the runtime devnet owner for snapshot
  create/list/delete catalog operations.
- `Yano.createDevnetSnapshot(...)`, `listDevnetSnapshots()`, and
  `deleteDevnetSnapshot(...)` now keep only the runtime maintenance/read leases
  and delegate to the catalog service.
- Behavior is preserved:
  - create still validates dev mode and devnet-production availability inside the
    runtime maintenance lease before resolving snapshot storage
  - list still returns an empty list when the chain state has no snapshot
    capability
  - create/delete still use the required snapshot-capability supplier, preserving
    the previous `ChainStorageSubsystem.snapshotsOrThrow()` failure message
  - snapshot name/path validation remains in `DevnetSnapshotStore`
- Focused coverage verifies create gating, unsupported-list behavior,
  required-snapshot error preservation, store validation delegation, catalog
  create/list integration, and restore/store compatibility tests.

## Stage E Config-Derived Assembly Selection Slice

- Added `YanoAssembly.fromConfig(config)` as the runtime owner for legacy
  config-derived recipe selection.
- `YanoProducer` no longer branches over relay/devnet/time-travel/slot-leader
  recipes. It asks runtime assembly for the selected recipe, then applies the
  app-supplied runtime options, bootstrap provider, adhoc rollback, and
  transaction bootstrap factory.
- The runtime selection preserves existing compatibility:
  - enabled `slotLeaderMode` takes precedence over devnet/time-travel routing
  - enabled devnet block production chooses devnet or devnet time-travel
  - isolated `pastTimeTravelSlotLeaderMode=true` without
    `pastTimeTravelMode=true` remains normal devnet startup
  - all other shapes route to relay
- Explicit recipes remain the preferred API when embedders know the intended
  role; `fromConfig` is the compatibility bridge for legacy Quarkus config.
- Focused coverage verifies slot-leader precedence, devnet/time-travel routing,
  non-devnet producer compatibility fallback, isolated past-time-travel
  slot-leader flag compatibility, and that app producer smoke coverage still
  passes.

## Stage E App-Layer Config Helper Slice

- Moved rollback-retention policy into runtime config:
  - `RollbackRetentionPlanner`
  - `RollbackRetentionSettings`
  - `RollbackRetentionGenesisValues`
- `YanoProducer` now only adapts Quarkus property presence and writes the
  planner output into runtime globals.
- Moved concrete bootstrap-provider selection into the optional
  `bootstrap-providers` module:
  - `DefaultBootstrapDataProviderFactory`
- Moved bundled known-network genesis resolution into runtime:
  - `GenesisFileResolver`
  - runtime-owned `genesis/mainnet`, `genesis/preprod`, `genesis/preview`, and
    `genesis/sanchonet` resources
  - runtime native-image resource config includes `genesis/.*\.json`
- Added `YanoConfig.defaultForNetwork(...)` so known-network default selection is
  a shared config helper rather than a Quarkus switch. The helper stamps the
  canonical selected network, including preprod for unknown/blank/null fallback.
- Added `core-api.config.YanoPropertyKeys` as the framework-neutral property-key
  contract for Quarkus, future Spring/Micronaut/plain-Java adapters, runtime
  globals, ledger-state stores, transaction services, and runtime tuning
  properties. `RollbackRetentionPlanner` and `DnsCachePolicy` keep owner-local
  aliases that delegate to the shared constants.
- Focused coverage verifies retention math, Quarkus retention property presence
  and globals population, bootstrap-provider selection/defaults, runtime bundled
  genesis extraction, native-resource packaging shape, moved sanchonet resource
  paths, known-network default selection, and app producer smoke coverage.

## Review Results

Resolved reviewer findings:

- Devnet rollback/restore REST responses no longer read `ChainState` outside the
  maintenance gate.
- `/node/recover` now bypasses the normal REST read lease and acquires an
  exclusive maintenance lease in runtime code.
- Quarkus maintenance-gate production no longer depends on a direct app-layer
  downcast to `Yano`.
- `ServeSubsystem` now waits for the server thread to exit during stop and clears
  stale server references if the server thread exits unexpectedly.
- `ServeSubsystem` now retains server handles after a stop timeout, reports
  `isRunning=false`, and refuses restart while the old server thread is still
  alive. This avoids losing the only handle to a half-stopped server.
- Block producers no longer retain a stale `NodeServer` instance across server
  restart/restore.
- The no-op devnet role adapter overrides the new devnet result APIs and fails
  through the same unavailable-role path as older devnet operations.
- Snapshot restore preparation now aborts before RocksDB replacement when server,
  async UTXO, prune, or block-body prune workers cannot drain, and it does not
  spawn duplicate replacement workers for an undrained service.
- Snapshot restore no longer returns a successful REST response if RocksDB
  replacement succeeds but runtime services fail to resume.
- Async UTXO drain failures now retain the handler so a later shutdown/diagnostic
  path still has the only handle; successful drains restart only when safe.
- Terminal UTXO subsystem close drains async work before closing event handlers and
  store resources.
- UTXO metrics sampler pause timeouts retain the scheduler handle instead of
  pretending it was stopped.
- Chain-sync account-history rollback verification failures after chain-state
  rollback now use the same emergency fail-closed path as rollback-event delivery
  failures.
- Terminal peer recovery no longer leaves readiness green: sync health goes down
  and the app health check honors `peerRecoveryTerminal`.
- Delayed intersection-phase transition tasks are cancelled/generation-gated so
  a stopped or restarted sync session cannot be mutated by an old timer.
- Account-history pruning no longer starts before client-mode startup recovery.
- Ledger startup recovery now reconciles UTXO/account state before interrupted
  epoch-boundary recovery.
- Direct-start genesis bootstrap now fails closed if fail-closed payloads cannot
  resolve era metadata.
- Rollback cleanup for derived epoch snapshot exports moved under
  `LedgerStateSubsystem`.
- Degraded runtime state now reaches `NodeStatus`, Quarkus readiness, subsystem
  health, and legacy kernel health. Reviewer follow-up fixes addressed startup
  failure state reset, `/node/start` exclusivity, operation-scoped degradation
  clearing, and nested maintenance reason restoration.
- TxSubsystem reviewer follow-up fixed stopped/closed admission guards,
  close-time admission shutdown ordering, mempool-event failure rollback,
  current-service validator listener replacement, validator listener subscription
  cleanup, and tx-submission blocking-mode alignment. Poincare and Boole
  re-checked with no remaining blockers for this slice.
- Stage C producer/Tx quiescence reviewer follow-up fixed direct runtime
  mutators that bypassed the maintenance gate:
  - devnet snapshot/faucet/time/epoch controls now take runtime maintenance
  - producer start/stop/reset now take runtime maintenance
  - node stop/close now take runtime maintenance
  - REST write-locking endpoints, including `/node/stop`, bypass the request
    read lease
  Poincare and Boole re-checked the final lifecycle/restore/read-write gate
  shape with no remaining blockers.
- Stage C producer startup-planning reviewer follow-up fixed producer flag
  compatibility and shift safety:
  - isolated `pastTimeTravelSlotLeaderMode=true` without
    `pastTimeTravelMode=true` remains normal immediate devnet startup
  - `shiftGenesisAndStartProducer(...)` rejects immediate startup plans before
    mutating genesis or constructing producers
  Poincare and Boole re-checked the plan/start/shift behavior with no remaining
  blockers.
- Stage C devnet block-builder factory reviewer follow-up found no blockers.
  Poincare and Boole re-checked compatibility with the removed
  `Yano.createBlockBuilder(...)` branch, including partial-key unsigned fallback,
  lazy protocol-version supplier resolution, signed restart nonce replay setup,
  and the normal devnet/time-travel shift call sites.
- Stage C stake-provider factory reviewer follow-up found no blockers. Poincare
  and Boole re-checked live slot-leader YaciStore/fixed-provider selection,
  genesis time-travel stake validation, old missing-stake error text, and the
  unchanged exception wrapping from the existing `Yano` construction blocks.
- Stage C slot-leader key-material reviewer follow-up found no blockers.
  Poincare and Boole re-checked live and past-time-travel call sites, old null
  path/key-load behavior, existing exception wrapping, and continued downstream
  use of the loaded keys and derived pool hash.
- Stage C slot-leader signing-components reviewer follow-up found no blockers.
  Poincare and Boole re-checked old signed-builder/check constructor arguments,
  shared signer wiring, protocol/nonce/key propagation, unchanged construction
  try/catch wrapping, and ADR-028 boundary fit.
- Stage C nonce-listener factory reviewer follow-up found no blockers. Poincare
  and Boole re-checked live slot-leader listener arguments, own-issuer skip
  derivation, annotation registration, unchanged outer failure wrapping, relay
  nonce-listener compatibility, and ADR-028 boundary fit.
- Stage C slot-leader producer assembly reviewer follow-up found no blockers.
  Poincare and Boole re-checked live slot-leader dependency preservation,
  install/start behavior, time-travel deferred install behavior, shifted-genesis
  start ordering, and the intentional choice to keep genesis seeding and
  fast-forward shortcuts out of the producer factory.
- Stage C devnet producer assembly reviewer follow-up found no blockers.
  Poincare and Boole re-checked live devnet dependency preservation, deferred
  install/start behavior, devnet time-travel deferred install behavior,
  shifted-genesis sequential-slot ordering, and the intentional choice to keep
  genesis timestamp mutation, fast-forward, shifted-genesis persistence, and
  genesis UTXO seeding out of the producer factory.
- Stage C producer recipe-selection reviewer follow-up fixed the initial
  slot-leader routing blocker by adding an explicit slot-leader recipe and
  routing enabled `slotLeaderMode` configs to it before devnet/time-travel
  routing. Poincare and Boole re-checked direct `new Yano(...)` compatibility,
  `%devnet-slotleader` app routing, slot-leader plus past-time-travel precedence,
  devnet-control gating, and constructor surface risk with no remaining
  blockers.
- Stage D devnet faucet-service reviewer follow-up fixed dev-mode slot-leader
  compatibility by replacing the initial `hasDevnetProduction()` gate with a
  generic installed block-production gate. Poincare and Boole re-checked
  maintenance gating, validation messages/order, faucet UTXO injection semantics,
  and slot-leader-devnet coverage with no remaining blockers.
- Stage D devnet time-advance-service reviewer follow-up found no blockers after
  local self-review fixed seconds-to-slots conversion to keep the converted slot
  count in `long` until max-slot validation. Poincare and Boole re-checked
  maintenance gating, validation messages/order, devnet-production gating,
  stop/produce/resume behavior, seconds conversion, max-slot handling, and
  coverage with no remaining blockers.
- Stage D devnet catch-up-service reviewer follow-up found no blockers. Poincare
  and Boole re-checked maintenance gating, devnet vs slot-leader-time-travel
  routing, producer stop/produce/resume behavior, `forceSequentialSlots(false)`
  placement, already-caught-up/no-tip result semantics, and coverage with no
  remaining blockers.
- Stage D shifted-genesis-service reviewer follow-up found no blockers. Poincare
  and Boole re-checked validation order/messages, epoch-shift calculation,
  second-truncated shifted `systemStart`, config timestamp/update sequencing,
  Shelley genesis file persistence/error text, shifted-genesis propagation and
  bootstrap refresh, fresh-start checks, resolved timestamp and slot-time
  initialization order, Conway/genesis UTXO ordering, producer-mode dispatch, and
  sequential mode before producer start.
- Stage D snapshot-restore-service review found one facade maintenance-ordering
  blocker: `restoreDevnetSnapshotAndGetTip(...)` checked dev mode before
  acquiring the runtime maintenance lease. The follow-up moved that validation
  inside the lease and added facade coverage proving
  `restoreDevnetSnapshot("snap")` waits behind active maintenance before
  failing. Boole re-checked and confirmed the blocker resolved; Poincare's
  scoped review found no additional findings. Focused coverage now freezes
  restore ordering, service quiescence, checkpoint restore,
  reinitialize/reconcile hooks, Tx/server/producer resume order, and degraded
  maintenance behavior for preparation, restore, and resume failures.
- Stage D snapshot-catalog-service review found one low behavior-drift finding:
  unsupported snapshot capability errors initially used a new generic message.
  The follow-up split nullable and required snapshot suppliers so list remains
  empty-on-unsupported while create/delete preserve the old
  `ChainStorageSubsystem.snapshotsOrThrow()` message; focused coverage now
  verifies the required-supplier message path. Poincare and Boole re-checked with
  no remaining findings.
- Stage F debug endpoint interface reviewer follow-up found no blockers.
  Poincare and Boole re-checked behavior preservation for account-state and UTXO
  debug access, CDI producer wiring, removal of broad `NodeAPI` injection and
  concrete `Yano` unwrapping from `DebugSnapshotResource`, and ADR-028 boundary
  fit.
- Stage E config-derived assembly selection reviewer follow-up fixed isolated
  `pastTimeTravelSlotLeaderMode=true` compatibility by allowing that no-op flag
  in the plain devnet recipe and adding focused runtime coverage.
- Stage E rollback-retention review found two low issues after moving the planner
  into runtime config: an unused public planner parameter and app tests that
  duplicated runtime math instead of adapter wiring. The follow-up removed the
  unused parameter and added app coverage for Quarkus property-presence detection
  plus runtime-global population. Poincare and Boole re-checked with no remaining
  findings.
- Stage E bootstrap-provider factory review found no blockers after moving
  Blockfrost/Koios provider selection into `bootstrap-providers`. Additional
  tests now cover missing-provider defaulting to Blockfrost and unknown-provider
  compatibility fallback.
- Stage E genesis resolver/resource review found runtime native-image packaging
  and API-shape issues after moving bundled genesis resources from app to
  runtime. The follow-up added the runtime `genesis/.*\.json` resource pattern,
  a convenience resolver overload, and a final classloader fallback. A final
  regression test hides resources from both the explicit loader and TCCL so the
  runtime-loader fallback is exercised. Poincare and Boole re-checked with no
  remaining findings.
- Stage E network-default helper review found public API documentation drift and
  then a public-helper behavior issue where returned configs did not stamp the
  selected network. The follow-up corrected docs and stamps known/fallback
  network names. Poincare and Boole re-checked with no remaining findings.
- Stage C transaction factory-placement review found no blockers after moving
  concrete Scalus/Aiken/JULC construction into `tx-services`. Boole re-checked
  the module boundary, app/runtime dependency shape, and focused validation with
  no findings; Poincare did not return before the review window closed.
- Final ADR-028 completion review found one completion-claim mismatch: `Yano`
  and `NodeAPI` were documented as deprecated compatibility surfaces but were not
  annotated or documented as deprecated in code. The follow-up added type-level
  `@Deprecated` annotations and replacement-guidance Javadocs to both public
  compatibility surfaces. The broad JVM validation command and `git diff --check`
  were rerun after this fix and passed. Poincare and Boole re-checked the final
  completion claim with no remaining blockers.

Final Stage B lifecycle review:

- Reviewer Curie: no blockers after re-checking failed async drain retention,
  terminal UTXO close ordering, UTXO metrics handle retention, and the
  account-history fail-closed rollback path.
- Reviewer Hypatia: no blockers after re-checking async UTXO drain/restart,
  terminal close, metrics sampler pause timeout, and chain-sync account-history
  fail-closed behavior.
- Reviewer Darwin: no blockers after `SyncSubsystem` re-check; P2/P3 findings
  were addressed for terminal peer recovery health/readiness, stale intersection
  timers, server-only remote-host regression coverage, and stale ownership
  comments.
- Reviewer Euler: ledger-state findings were addressed for account-history prune
  timing, startup recovery ordering, fail-closed genesis bootstrap metadata,
  derived export cleanup ownership, and AdaPot validation wording.

Reviewer residual risk accepted for later work:

- Some deep failure-path tests still use focused status/gate/kernel coverage
  rather than forcing every possible post-mutation restore/rollback failure mode.
  The runtime now exposes a first-class degraded state for those paths.
- Some facade-level producer/devnet-toolkit orchestration remains in the legacy
  facade/app assembly and is later-stage work.

## Validation

Final validation refresh on 2026-06-17 passed with Java 25:

```bash
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :core-api:test :bootstrap-providers:test :tx-services:test :runtime:test :app:test
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :app:quarkusBuild
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :app:e2eTest
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :app:haskellSyncTest
git diff --check
```

After the final reviewer deprecation-doc mismatch was fixed, this broad JVM
command and `git diff --check` were rerun and passed again. The annotation-only
fix was not rerun through e2e/Haskell because the earlier final e2e/Haskell pass
already covered runtime behavior and the follow-up only changes public API
deprecation metadata.

The final Haskell sync refresh covered:

- past-time-travel devnet sync from slot 0, ending with matching Yano/Haskell tips
  at slot 4897
- regular block-producer sync for 2+ epochs, ending with matching Yano/Haskell
  tips at slot 1202 after the slot-1200 target

Reviewer hardening refresh on 2026-06-17 passed after implementing the
shutdown, peer-recovery, nonce-listener, bootstrap-policy, idempotent-close, and
small defensive fixes:

```bash
./gradlew :runtime:test --rerun-tasks --console=plain
./gradlew :core-api:test :ledger-state:test :tx-services:test :runtime:test :app:compileJava :app:compileTestJava --console=plain
./gradlew :app:quarkusBuild --console=plain
./gradlew :app:haskellSyncTest --console=plain
git diff --check
```

The first Haskell sync refresh exposed a test-environment leak from the local
`app/config/application.yml`: the e2e helper copied the local config and allowed
`yano.remote.protocol-magic=1` to override the devnet PV10 magic `42`, which made
Haskell fail the N2N handshake with `SDUDecodeError "short SDU"`. The helper now
sets `-Dyano.remote.protocol-magic=42` explicitly. The rerun passed:

- past-time-travel devnet sync from slot 0, ending with matching Yano/Haskell tips
  at slot 4903
- regular block-producer sync for 2+ epochs, ending with matching Yano/Haskell
  tips at slot 1201 after the slot-1200 target

The Claude `test-haskell-sync` skill path was also run with cardano-node 11.0.1,
PV10 genesis copied from an isolated temp directory, fixed ports
`7070/13337/3002`, and same-slot hash comparison. It passed at slot 1211 with
matching hash `c96adc4faccd7ed030f7aafabcb85f802eaf16baa64f17c258b3c46ce05a5399`.
Logs were retained at:

- `/tmp/claude-skill-haskell-sync-yano.log`
- `/tmp/claude-skill-haskell-sync-haskell.log`

Focused subsystem validations run during the staged implementation are retained
below for traceability:

```bash
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:compileJava
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:compileTestJava :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.ledger.AccountHistorySubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.utxo.UtxoSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoGenesisBootstrapPublicationTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:compileJava :runtime:compileTestJava :app:compileTestJava :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.ledger.AccountHistorySubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystemTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoHealthCheckTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :core-api:test --tests 'com.bloxbean.cardano.yano.api.model.NodeStatusTest' :runtime:compileJava :app:compileJava :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGateTest' --tests 'com.bloxbean.cardano.yano.runtime.storage.ChainStorageSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.PipelineIntegrationTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest' --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoHealthCheckTest' --tests 'com.bloxbean.cardano.yano.app.api.RuntimeMaintenanceGateFilterTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:compileJava :runtime:compileTestJava :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.utxo.UtxoSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.ledger.AccountHistorySubsystemTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:compileJava :runtime:compileTestJava :app:compileJava :app:compileTestJava :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.server.ServeSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.storage.ChainStorageSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGateTest' :app:test --tests 'com.bloxbean.cardano.yano.app.api.RuntimeMaintenanceGateFilterTest' --tests '*Devnet*'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:compileJava :runtime:compileTestJava :app:compileJava :app:compileTestJava :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.EffectiveProtocolParamsSupplierTest' :tx-services:test :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :tx-services:test :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :tx-services:test :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest' --tests 'com.bloxbean.cardano.yano.app.api.utils.EvaluationResourceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotStoreTest' --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' :tx-services:test :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.tx.TxSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.handlers.YaciTxSubmissionHandlerTest' --tests 'com.bloxbean.cardano.yano.runtime.validation.DefaultTransactionValidatorListenerTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoTransactionAdmissionTest' --tests 'com.bloxbean.cardano.yano.runtime.server.ServeSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.tx.TxSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoTransactionAdmissionTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :tx-services:test :app:test --tests 'com.bloxbean.cardano.yano.app.api.utils.EvaluationResourceTest' --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest' --tests 'com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGateTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' :app:test --tests 'com.bloxbean.cardano.yano.app.api.RuntimeMaintenanceGateFilterTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.tx.TxSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilderFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilderTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.StakeDataProviderFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilderFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderKeyMaterialTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.StakeDataProviderFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilderTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderSigningComponentsTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderKeyMaterialTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.StakeDataProviderFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilderTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.NonceEvolutionListenerFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.NonceEvolutionListenerTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.NonceEvolutionListenerExtraEntropyTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderSigningComponentsTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderKeyMaterialTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilderTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderProducerFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.NonceEvolutionListenerFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderSigningComponentsTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderKeyMaterialTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.producer.DevnetProducerFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducerTest' --tests 'com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilderFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.SlotLeaderProducerFactoryTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetFaucetServiceTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetTimeAdvanceServiceTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetCatchUpServiceTest' --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetTimeAdvanceServiceTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetGenesisShiftServiceTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotRestoreServiceTest' --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotStoreTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotCatalogServiceTest' --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotStoreTest' --tests 'com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotRestoreServiceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.devnet.*' --tests 'com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:compileJava :app:compileJava :app:test --tests 'com.bloxbean.cardano.yano.app.api.accounts.DebugSnapshotResourceTest' --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest' --tests 'com.bloxbean.cardano.yano.app.api.accounts.AccountStateResourceBalanceTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.config.RollbackRetentionPlannerTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :bootstrap-providers:test --tests 'com.bloxbean.cardano.yano.bootstrap.providers.DefaultBootstrapDataProviderFactoryTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.config.GenesisFileResolverTest' --tests 'com.bloxbean.cardano.yano.runtime.genesis.NetworkGenesisConfigTest' --tests 'com.bloxbean.cardano.yano.runtime.config.DefaultEpochParamProviderTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :core-api:test --tests 'com.bloxbean.cardano.yano.api.config.YanoConfigTest' :app:test --tests 'com.bloxbean.cardano.yano.app.YanoProducerTest'
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :runtime:test :app:test
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :app:e2eTest
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :app:haskellSyncTest
JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca ./gradlew :app:quarkusBuild
git diff --check
rg -n "utxoPruneService|utxoEventHandler|utxoEventHandlerAsync|utxoLagTask|utxoReconcilePending|initUtxoFilterChain" runtime/src/main/java runtime/src/test/java
rg -n "accountHistoryPruneService|accountHistoryEventHandler|accountHistoryReconcilePending|reconcileAccountHistoryStore|startAccountHistoryPruneService|pausePruneService\\(" runtime/src/main/java/com/bloxbean/cardano/yano/runtime/Yano.java
rg -n "DirectRocksDBChainState|InMemoryChainState" runtime/src/main/java app/src/main/java core-api/src/main/java
rg -n "chainQuery|getChainState\\(\\)|ChainState|ChainTip" app/src/main/java/com/bloxbean/cardano/yano/app/api/devnet/DevnetResource.java
rg -n "nodeServer|isServerRunning\\.get|YaciTxSubmissionHandler|TxSubmissionConfig|N2NVersionTableConstant" runtime/src/main/java/com/bloxbean/cardano/yano/runtime/Yano.java runtime/src/main/java/com/bloxbean/cardano/yano/runtime/server
```

Earlier Haskell sync validation covered:

- past-time-travel devnet sync from slot 0, ending with matching Yano/Haskell tips
  at slot 4901
- regular block-producer sync for 2+ epochs, ending with matching Yano/Haskell
  tips at slot 1200

Latest devnet AdaPot comparison covered Yano, Haskell, and YaciStore through epoch
32 using the Yaci CLI native store binary built from the YaciStore
`release/2.0.1_devkit` branch:

```bash
YANO_JAVA_HOME=/Users/satya/.sdkman/candidates/java/25.0.2-librca \
JAVA21_HOME=/Users/satya/.sdkman/candidates/java/21-librca \
TEST_DIR=/tmp/yano-devnet-adapot-comparison-yaci-cli \
YACI_STORE_JAR=/Users/satya/.yaci-cli/components/store/yaci-store-all \
CLEAN_PORTS=1 \
bash scripts/devnet-adapot-comparison/run-devnet-haskell-yacistore-adapot-comparison.sh
```

```text
COMPARISON_EPOCH=32
HASKELL deposits=502000000 treasury=648237277101659 reserves=35341200975078880
YANO    deposits=502000000 treasury=648237277101659 reserves=35341200975078880
STORE   deposits=502000000 treasury=648237277101659 reserves=35341200975078880
GENESIS_DEPOSITS expected=502000000 yano=502000000 store=502000000

PASS deposits all == 502000000
PASS treasury all == 648237277101659
PASS reserves all == 35341200975078880
PASS genesis deposits == 502000000
```

The prior three-way run with the local YaciStore `main` boot jar
(`3.0.0-beta3`, commit `6b73f79`) did not pass because that artifact did not
include the devkit AdaPot fixes and embedded `cf-rewards-calculation` `1.0.0`:

```text
STORE deposits=0 treasury=653894910906173 reserves=35335542675273113
GENESIS_DEPOSITS expected=502000000 yano=502000000 store=0
```

YaciStore was using the correct copied genesis files in both runs; the failing
artifact was from the wrong branch for the devkit genesis AdaPot case. Logs for
the passing run are under `/tmp/yano-devnet-adapot-comparison-yaci-cli/logs/`.

Known validation noise, not introduced by this slice:

- Quarkus reports the untracked `app/config/application.yml.working` file as an
  unrecognized config file.
- SLF4J reports multiple providers in test classpaths.
- Java 24+/25 Quarkus test shutdown can print JBoss Threads `--add-opens
  java.base/java.lang=ALL-UNNAMED` errors after tests have passed.
- Quarkus build reports existing duplicate dependency and deprecated native config
  warnings.
- Building YaciStore with Java 25 failed in `:components:events:compileJava`;
  Java 21 should be used for YaciStore builds. The comparison harness now also
  prepends `JAVA_HOME/bin` when `JAVA21_HOME` or `YANO_JAVA_HOME` is set, and it
  can launch either a YaciStore boot jar or a native Yaci CLI store executable.

## Static Coupling Check

Production references to concrete chain-state classes are confined to:

- `ChainStorageSubsystem` construction boundary
- `DirectRocksDBChainState`
- `InMemoryChainState`
- documentation comments describing `RocksDbAccess`

`DevnetResource` no longer imports or uses `ChainQuery`, `ChainState`, or
`ChainTip`.

Legacy UTXO, account-history, ledger-state, and upstream sync orchestration
fields/methods are no longer present in the runtime implementation; ownership now sits behind
`UtxoSubsystem`, `LedgerStateSubsystem`, and `SyncSubsystem`.

App REST resources no longer depend on broad `NodeAPI`, and the compatibility
CDI producer has been removed. App wiring exposes only narrow role beans.

## Pre-Release API Cleanup Completed

The remaining compatibility cleanup tasks are complete:

- Removed `core-api` `NodeAPI`; public consumers use `NodeLifecycle`,
  `ChainQuery`, `LedgerQuery`, `TxGateway`, `TxEvaluationGateway`,
  `ProducerControl`, and `DevnetControl`.
- Removed raw `ChainState` exposure from `ChainQuery`; resources now use
  role-level tip/block reads.
- Removed raw `ChainState` exposure from account-state extension points;
  providers receive `ChainBlockReader` and explicit `RocksDbAccess` only when a
  RocksDB-backed implementation is selected.
- Removed the old broad public `Yano` construction surface; runtime construction goes
  through `YanoAssembly` and returns `Yano`. The internal implementation is
  `com.bloxbean.cardano.yano.runtime.internal.RuntimeNode`.
- Removed deprecated raw `Yano.getMemPool()`, the public `TxSubsystem` mempool
  accessor, and the unused `setInMemoryDevnetGenesis(...)` compatibility setter.
- Replaced the app `NodeAPI` CDI producer with internal `Yano` caching and
  narrow role producers.
- Added explicit `DevnetToolkit` as the `DevnetControl` implementation. This
  packaging detail is superseded by ADR-029: `DevnetToolkit` and
  `YanoDevnetAssembly` now live in `devnet-toolkit`, while devnet and
  devnet-time-travel runtime recipes expose only devnet-safe SPI ports. Plain
  slot-leader recipes expose producer controls but not devnet-toolkit controls.

## Issue #21 Follow-Up Work Complete

Issue #21 promoted the remaining runtime kernel and producer/chronology work from
non-blocking ADR follow-up into completed implementation scope. The kernel
lifecycle is now load-bearing without regressing the existing restart, rollback,
snapshot restore, devnet, and sync behavior.

### Stage 21A - Kernel Ownership And Scheduler Context

- Implemented shape: assembled `RuntimeNode` now implements `RuntimeKernelProvider`
  and owns the `NodeKernel` returned by `YanoAssembly`. `RuntimeYano` uses that
  kernel directly for runtime assemblies instead of wrapping the node in a second
  one-subsystem kernel.
- Kernel subsystem order is explicit:
  `runtime-resources`, `runtime-startup-boundary`, `tx`, `serve`,
  `runtime-bootstrap-recovery`, `utxo`, `ledger-state`, `chain-storage`,
  `producer`, `chronology`, `serve-deferred`, `sync`,
  `runtime-startup-publication`, `runtime-shutdown-boundary`.
- Runtime scheduled work now uses the kernel `Schedulers` context. `SyncSubsystem`
  receives the shared task executor for peer recovery, so scheduler shutdown has
  one owner.
- Quarkus readiness now consumes `NodeKernel.health()` and reports readiness down
  when any kernel subsystem reports `DOWN` or `DEGRADED`.

### Stage 21B - Startup And Stop Coordinator Extraction

- Implemented shape: `RuntimeNode.start()` delegates to `kernel.start()`.
  Startup sequencing is now split into named kernel stages in
  `RuntimeKernelStages` for transaction admission, early/deferred server start,
  bootstrap/recovery, derived-state background services, chain pruning, producer
  startup, chronology init, sync startup, plugin/filter startup, and startup
  event publication. `RuntimeNode` supplies concrete operations through a narrow
  `RuntimeKernelStages.Actions` adapter instead of owning the lifecycle stage
  implementations.
- Stop sequencing is driven by reverse kernel order. The
  `runtime-shutdown-boundary` marks the runtime not-running before stopping sync,
  so peer recovery cannot restart during shutdown, and it holds the maintenance
  gate until `runtime-startup-boundary` releases it at the end of stop.
- Terminal close remains centralized in `runtime-resources` instead of being a
  plain reverse-order close of all stores. This is deliberate: UTXO async apply,
  event-bus shutdown, ledger-state handlers, plugin close, and final
  `ChainState`/RocksDB close require the existing drain-and-close ordering.
- Failed startup cleanup is fail-closed and inspectable: startup failures stop
  any partially started runtime services, mark runtime degraded after storage or
  recovery mutation begins, close terminal resources through the kernel-owned
  `runtime-resources` stage, and keep degraded status inspectable without reading
  closed RocksDB handles.

### Stage 21C - Producer And Chronology Extraction

- Implemented shape: `ChronologySubsystem` now owns slot-time initialization,
  era-transition subscription, cache invalidation, and `slotToUnixTime` access.
  `RuntimeNode` delegates chronology lifecycle and health to that subsystem.
- Implemented shape: `ProducerStartupCoordinator` owns producer startup mode
  selection, producer-wide helper wiring, deferred time-travel validation, devnet
  startup sequencing, and slot-leader startup sequencing. It now owns genesis
  timestamp resolution, epoch fast-forward orchestration, nonce-state setup,
  nonce listener registration, protocol-version supplier selection, genesis
  block/UTXO seeding calls, and final producer factory invocation. `RuntimeNode`
  supplies concrete dependencies through `ProducerStartupCoordinator.Actions`.
- Remaining producer construction primitives stay in existing producer-specific
  factories and helper methods, but the startup orchestration and mode policy no
  longer live in `RuntimeNode`.

### Stage 21D - Maintenance Failure Signaling

- Implemented shape: generic devnet controls receive a
  `MaintenanceFailureReporter` and mark runtime degraded when a failure happens
  after mutable state changes or producer restart fails.
- Implemented shape: producer start/stop/reset mark runtime degraded when the
  producer control mutation starts and then fails.
- Snapshot restore and devnet rollback keep their existing fail-closed degraded
  signaling.

### Validation Gate For Issue #21

- Focused runtime/kernel/readiness tests passed:
  `./gradlew :runtime:test --tests '*NodeKernelTest' --tests '*YanoStartupMaintenanceTest' --tests '*YanoAssemblyTest' --tests '*ProducerStartupCoordinatorTest' :app:test --tests '*YanoHealthCheckTest' --console=plain -q`.
- Touched-module JVM tests passed:
  `./gradlew :runtime:test :app:test --console=plain -q`.
- Haskell sync validation passed:
  `./gradlew :app:haskellSyncTest --console=plain -q`.
- Devnet AdaPot comparison passed through target epoch 32 using the Yaci CLI
  native store binary at `~/.yaci-cli/components/store/yaci-store-all`. Final
  comparison values matched across Haskell, Yano, and YaciStore:
  deposits `502000000`, treasury `653894896802805`, reserves
  `35335542735164389`, and genesis deposits `502000000`.
- The AdaPot rerun used explicit local-config overrides
  `-Dyano.remote.protocol-magic=42` and
  `-Dyano.exit-on-epoch-calc-error=false` because the dirty local
  `app/config/application.yml` overrides those values to `1` and `true`.
- Two independent follow-up reviewers found no remaining issue #21 lifecycle or
  design blockers after the final fixes. One reviewer also reran the focused
  lifecycle/kernel tests and `git diff --check`.

## Issue #19 DevnetToolkit Packaging Decision

Decision: superseded and implemented by
`029-devnet-control-toolkit-and-testkit-spi.md`.

The original issue #19 decision kept `DevnetToolkit` and the runtime devnet
services in the `runtime` artifact because there was no concrete consumer for a
separate module. ADR-027 R1 testkit work and planned devnet growth changed that
tradeoff. The implemented ADR-029 direction creates `devnet-toolkit` behind
a narrow runtime SPI and adds the initial `testkit` foundation.

Historical rationale for the original deferral:

- There is no concrete consumer or deployment profile that currently needs a
  runtime artifact without devnet classes.
- Splitting now would not reduce the runtime dependency graph. The devnet
  services use existing runtime dependencies and JDK APIs; they do not pull a
  separate framework, CLI, Haskell, database, or test-container stack into
  `runtime`.
- The devnet implementation is tightly coupled to runtime-internal coordination
  points that should not become public module contracts prematurely:
  `RuntimeMaintenanceGate`, snapshot/restore drain ordering,
  `ChainStateSnapshots`, `ProducerSubsystem`, `UtxoStoreWriter`, genesis shift,
  and chain-state rollback/recovery operations.
- The public API surface is already isolated. `core-api` exposes only the
  optional `DevnetControl` role, and `Yano.devnetControl()` is present only
  for devnet/devnet-time-travel recipes with dev mode and block production
  enabled. Relay and plain slot-leader assemblies do not expose devnet controls.
- Keeping the code in `runtime` preserves straightforward Quarkus and plain Java
  assembly: no hidden classpath assumption is needed to make devnet recipes work.

Revisit a split only when at least one of these is true:

- a real adapter, embedded deployment, or distribution needs a runtime artifact
  that excludes devnet classes;
- devnet tooling starts to require optional heavyweight dependencies not needed
  by relay/runtime users;
- native-image size/startup analysis shows material cost from reachable devnet
  classes in non-devnet assemblies;
- devnet APIs need independent versioning or release cadence.

The revisit condition is now met. ADR-029 implemented the preferred split:

- `runtime` owns the safety-critical devnet backing services and exposes
  `runtime.devnet.spi` capability ports.
- Runtime devnet recipes no longer install `DevnetControl` directly.
- `devnet-toolkit` owns `DevnetToolkit` and `YanoDevnetAssembly`.
- The Quarkus app depends on `devnet-toolkit` because it exposes devnet REST
  endpoints.
- `testkit` has an initial closeable `YanoDevnetTestKit` wrapper and JUnit 5
  `YanoDevnetExtension`.

Validation for the ADR-029 split passed with:

- `./gradlew :runtime:compileJava :devnet-toolkit:compileJava --console=plain`
- `./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoStartupMaintenanceTest' --tests 'com.bloxbean.cardano.yano.runtime.YanoProducerStartupPlanTest' :devnet-toolkit:test :testkit:test :app:compileJava :app:compileTestJava --console=plain`
- `./gradlew :runtime:test :devnet-toolkit:test :testkit:test :app:test --console=plain`
- `./gradlew :app:quarkusBuild --console=plain`
- `./gradlew :app:haskellSyncTest --console=plain -q`
- `JAVA_TOOL_OPTIONS='-Dyano.exit-on-epoch-calc-error=false -Dyano.remote.protocol-magic=42' YACI_STORE_JAR="$HOME/.yaci-cli/components/store/yaci-store-all" bash scripts/devnet-adapot-comparison/run-devnet-haskell-yacistore-adapot-comparison.sh`
  passed at epoch 32 with Haskell/Yano/YaciStore deposits `502000000`,
  treasury `653894896810552`, reserves `35335542735126511`, and genesis deposits
  `502000000`.
- Devnet epoch-crossing skill workflow with 50-slot epochs.
- Past-time-travel skill workflow with dynamic PV10 genesis-shift expectation.
- `git diff --check`.

## Remaining Follow-Up After Issue #19

- Keep Quarkus as a thin adapter: configuration mapping, bean exposure, and
  lifecycle bridging only.
- Add equivalent Spring/Micronaut/plain-Java examples after the runtime API is
  stable.
- Decide whether builder override APIs such as custom mempool injection,
  subsystem disabling, or external subsystem registration are part of the public
  extension model. Do not add them until there is a concrete adapter or
  embedding use case.

## Completion Criteria For Full ADR-028

ADR-028 can be considered fully implemented when:

- Runtime assembly owns construction policy previously split across the runtime
  implementation and `YanoProducer`.
- Sync/storage/ledger/producer/devnet responsibilities are independently testable.
- Quarkus app code only maps configuration and exposes adapters.
- No public app resource depends on broad `NodeAPI`.
- Full JVM, e2e, Haskell sync, and Quarkus build validations pass after the final
  extraction.

Current status: satisfied for the issue #17 ADR-028 decomposition,
pre-release cleanup, issue #21 runtime-kernel follow-up, and ADR-029's
replacement for the original issue #19 DevnetToolkit packaging decision.
