# ADR-021: Snapshot Restore Coordinator and DB Handle Lifecycle

**Status:** Proposed
**Date:** 2026-05-03
**Authors:** Claude (Opus 4.7), reviewing a plan by Codex
**Related:** ADR-013 (Modular Library, Plugin and Event Gaps)

## Context

Devnet snapshot restore (`Yano.restoreSnapshot`, `runtime/.../Yano.java:2371`) closes and reopens the underlying RocksDB instance. Every cached `RocksDB` and `ColumnFamilyHandle` held by stores becomes invalid at that moment. Today the method manually orchestrates ~140 lines of bespoke pause/rebind/reconcile/resume logic across a hand-picked set of components:

- `blockProducerService.stop()` / `.start()` / `.resetToChainTip()`
- `utxoEventHandlerAsync` close + recreate
- three `PruneService` instances (UTXO, account-history, block-body)
- `utxoStore.reinitialize()` + `.reconcile(...)`
- `accountStateStore.reinitialize()` + `.reconcile(...)`
- `accountHistoryStore.reinitialize(db, cfSupplier)` + `.reconcile(...)`
- `memPool.clear()`
- `nodeServer.notifyNewDataAvailable()`
- `slotTimeCalculator.invalidateCache()`
- `lastKnownChainTip` refresh

This works today but is fragile. A grep across `ledger-state/` shows additional DB-backed components that already implement `reinitialize`-style methods but are **not** invoked from `restoreSnapshot`:

- `GovernanceStateStore`, `EpochParamTracker`, `AdaPotTracker`, `EpochBoundaryProcessor`, `EpochRewardCalculator`, `DRepDistributionCalculator`, `GovernanceBlockProcessor`, `GovernanceEpochProcessor`

Any future DB-backed component is silently at risk: the author of `restoreSnapshot` must remember to wire it in. There is also naming confusion that makes the surface harder to reason about:

- `rollbackTo(long)` (`Yano.java:2228`) is devnet-only but its name is generic.
- `handleRollback(Point)` (`Yano.java:3167`) is the chain-sync rollback path.
- `createSnapshot` / `restoreSnapshot` are devnet-only but live on `NodeAPI`.
- `reinitialize()` vs `reinitialize(db, cfSupplier)` — inconsistent rebinding contract across stores.

## Codex's Proposal (Summary)

1. Synchronous, ordered `SnapshotRestoreCoordinator` (not event-driven).
2. Phases: pause writers → drain async → close+reopen DB → rebind handles → reconcile derived → reset producer/cache → resume.
3. `SnapshotRestoreParticipant` SPI with `int order()` and four hooks (`beforeSnapshotRestore`, `afterRocksDbReopened(ctx)`, `afterSnapshotRestore`, `onSnapshotRestoreFailed(t)`).
4. `RocksDbRestoreContext(db, columnFamily)` passed to participants.
5. Naming cleanup: `rollbackDevnetToSlot`, `createDevnetSnapshot`, `restoreDevnetSnapshot`, `handleChainSyncRollback`, `performStartupAdhocRollback`. Standardize on `reinitializeAfterSnapshotRestore`.
6. Longer-term: handle provider so stores stop caching raw handles.

## Review of Codex's Plan

**What's right**

- **Synchronous, ordered orchestration.** Restore is stop-the-world. An event bus cannot guarantee ordering or completion before downstream work touches stale handles. Reserve events (e.g. `SnapshotRestoredEvent`) for observers only.
- **Coordinator extracted from `Yano`.** `restoreSnapshot` is already 140 lines of bespoke wiring. Extracting it makes the policy testable and decouples the orchestration from `Yano`'s many responsibilities.
- **Naming cleanup.** `rollbackTo` vs `handleRollback` is a real footgun today. The proposed renames remove that ambiguity.
- **Standardize rebinding contract.** Replacing the current `reinitialize()` / `reinitialize(db, cfSupplier)` mix with one method removes per-store special-casing.

**Pushback / gaps**

1. **`int order()` is the wrong knob.** Magic numbers chosen independently per participant collide as the set grows. Use **explicit phases** (`QUIESCE → REOPEN_DB → REBIND → RECONCILE → RESET_DERIVED → RESUME`); within a phase, registration order suffices. Numeric weights are a maintenance trap.

2. **Two concerns conflated in one SPI.** Quiescing writers and rebinding handles are different jobs. Quiescing also matters for normal rollback, shutdown, and (future) backup — it is not snapshot-specific. Split into:
   - `Quiescable { pause(); resume(); }`
   - `RocksDbRebindable { afterRocksDbReopened(ctx); }`
   - `RestoreReconcilable { reconcile(chainState); }`

   The coordinator composes them. Each contract is small, independently testable, and reusable beyond restore.

3. **The "longer-term" handle provider should be in scope now, not later.** As long as every store caches raw `RocksDB` / `ColumnFamilyHandle` references, the participant list will keep growing, and "forgot to register" remains the dominant failure mode. The participant SPI without a context entrenches the original sin. Done now, the rebind phase becomes a one-liner — swap the context — and most participants need no rebind hook at all.

4. **Read-side gating is missing.** The plan only quiesces writers. In-flight REST and n2n reads against `RocksDB` will SEGV when the DB is closed. A read-write lock on the context (or a node state machine that returns 503 during restore) is required.

5. **Failure semantics undefined.** `onSnapshotRestoreFailed(Throwable)` has no recovery contract. If reopen partially succeeded, no participant can magically restore prior state. Pin the contract: **restore failure = node enters `HALTED`, no resume, surfaced via health endpoint, operator must restart.** Today the implicit `if (restored)` guard at `Yano.java:2480` already does this; make it explicit.

6. **Devnet API needs type-level segregation, not just name suffixes.** Renaming `restoreSnapshot` → `restoreDevnetSnapshot` on `NodeAPI` still pollutes the production surface. Move devnet-only methods to a separate `DevnetAPI` interface, exposed only when dev mode is active. Naming inside `DevnetAPI` can stay short.

7. **Codex's participant list is incomplete.** Missing: governance state store, ada-pot tracker, epoch-param tracker, epoch-boundary processor, epoch reward calculator, drep distribution calculator, governance block/epoch processors, mempool, `nodeServer.notifyNewDataAvailable()`, in-memory tip caches (`lastKnownChainTip`). `ChainState` itself must reopen first; everyone else depends on it.

8. **No test plan.** This is exactly the bug class that recurs silently. Required: (a) coordinator unit test with a fake participant verifying phase order and halt-on-failure; (b) integration test that creates a snapshot, mutates each store category, restores, and asserts every store reads consistent values. Without (b), the next added store breaks the same way.

## Decision

Adopt Codex's direction (sync coordinator, participant SPI, naming cleanup) **with the following modifications**:

### 1. RocksDb context (do this first)

Introduce a single `RocksDbContext` owned by `Yano` (or by `DirectRocksDBChainState`):

```java
final class RocksDbContext {
    private volatile Generation gen; // RocksDB + Map<String, ColumnFamilyHandle> + version
    private final ReentrantReadWriteLock lock;

    <T> T withDb(Function<Generation, T> work);          // read lock
    void swap(RocksDB newDb, Map<String,CFH> newCfs);    // write lock; bumps version
    Generation current();                                // for callers that cache by version
}
```

Stores receive the context (not raw handles) and either resolve per call or cache `(handle, generation)` and re-resolve when `generation` changes. Restore then calls `context.swap(...)` once and most stores need no explicit rebind.

### 2. Coordinator + phased SPI

```java
final class SnapshotRestoreCoordinator {
    void restore(String snapshotName) {
        try (var gate = nodeState.enterRestore()) {     // halts new readers/writers
            run(Phase.QUIESCE,        Quiescable::pause);
            chainState.restoreFromSnapshot(snapshotName);
            rocksDbContext.swap(chainState.db(), chainState.cfs());
            run(Phase.REBIND,         p -> p.afterRocksDbReopened(ctx));   // mostly empty
            run(Phase.RECONCILE,      p -> p.reconcile(chainState));
            resetDerivedState();                                            // mempool, slotTime, producer tip, server notify
            run(Phase.RESUME,         Quiescable::resume);
        } catch (Throwable t) {
            nodeState.halt(t);                                              // do NOT resume
            throw t;
        }
    }
}
```

Phases are an enum; participants register against a phase with no numeric ordering.

### 3. Three small SPIs (replace single `SnapshotRestoreParticipant`)

```java
interface Quiescable           { void pause(); void resume(); }
interface RocksDbRebindable    { void afterRocksDbReopened(RocksDbContext ctx); }
interface RestoreReconcilable  { void reconcile(ChainState chainState); }
```

A component implements only the contracts it needs.

### 4. Read-side gating

`NodeState` exposes a fast-path read lock; REST and n2n handlers acquire it per request. During restore, new acquisitions block (or fail-fast with 503 / protocol close) and the coordinator waits for in-flight reads to drain before closing the DB.

### 5. Halt-on-failure

Restore failure transitions the node to `HALTED`. Health endpoint reports it. No automatic recovery. Document that operator restart is required.

### 6. Devnet API segregation

```java
interface NodeAPI       { /* production surface */ }
interface DevnetAPI     { void rollbackToSlot(long); SnapshotInfo createSnapshot(String); void restoreSnapshot(String); }
```

`DevnetAPI` is bound only when `yaci.node.dev-mode=true`. Internal renames (`handleRollback` → `handleChainSyncRollback`, `performAdhocRollback` → `performStartupAdhocRollback`) stay as Codex proposed.

### 7. Participant inventory

Audit and register every DB-touching component. At minimum:

- **Quiescable:** `BlockProducerService`, `UtxoEventHandlerAsync`, all `PruneService` instances.
- **RocksDbRebindable:** any store that still caches handles after migrating to `RocksDbContext` (goal: zero).
- **RestoreReconcilable:** `UtxoStore`, `AccountStateStore`, `AccountHistoryStore`, `GovernanceStateStore`, `AdaPotTracker`, `EpochParamTracker`, `EpochBoundaryProcessor`, `EpochRewardCalculator`, `DRepDistributionCalculator`, `GovernanceBlockProcessor`, `GovernanceEpochProcessor`.
- **Inline reset (not a participant):** `Mempool.clear()`, `SlotTimeCalculator.invalidateCache()`, `BlockProducerService.resetToChainTip()`, `nodeServer.notifyNewDataAvailable()`, `lastKnownChainTip = chainState.getTip()`.

### 8. Tests

- Unit: `SnapshotRestoreCoordinator` with fake participants; assert phase order, halt-on-failure, no resume on failure.
- Integration: end-to-end snapshot create → mutate every store category → restore → verify each store reads consistent post-restore values. New store contributors are expected to extend this test.

## Consequences

Positive:

- Single source of truth for DB handles eliminates the "forgot to register" failure mode.
- Restore failure is explicit and observable rather than a partially-resumed node with stale handles.
- Devnet methods cannot be invoked from production code paths.
- Participant SPIs are small and independently testable.
- Quiescable is reusable for shutdown, backup, and (eventually) chain-sync rollback.

Tradeoffs:

- Migrating stores to `RocksDbContext` is more invasive than Codex's minimal participant-only proposal. This is a one-time cost paying down a known foot-gun.
- Read-side gating adds a hot-path lock acquisition. Use a `StampedLock` or per-shard RW lock if profiling shows contention; in normal operation the lock is uncontended.
- `NodeAPI` / `DevnetAPI` split touches every caller of the renamed methods. Mechanical change, caught by the compiler.

## Follow-Up

- Decide whether `Quiescable` should also gate normal chain-sync rollback (current `handleRollback`) for symmetry. Likely yes, but out of scope for this ADR.
- Consider whether `RocksDbContext` should be promoted into `core-api` so plugin authors can build their own DB-backed stores against it.

## References

- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/Yano.java` — current `restoreSnapshot` (line 2371), `rollbackTo` (line 2228), `handleRollback` (line 3167), `performAdhocRollback` (line 2095).
- `core-api/src/main/java/com/bloxbean/cardano/yano/api/NodeAPI.java` — current API surface.
- ADR-013 — modular library and plugin gaps (related plugin/store lifecycle concerns).
