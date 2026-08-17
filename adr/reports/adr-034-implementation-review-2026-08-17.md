# ADR-034 Implementation Review

Date: 2026-08-17
Branch: `fix/adr-034-review-hardening`
Reviewed revision: `a8e1887c` plus the uncommitted working tree
Subject: the optional asynchronous history archive (`archive-modules/`, `app/.../archive/`)

Companion to the ten `adr-034-phase-*.md` reports in this directory. Those
record what each phase delivered; this one assesses the delivered system as a
whole against its own ADR contracts, judges resiliency for a long-running
process, and proposes simplifications that preserve correctness.

Related ADRs: [ADR-034](../in-progress/034-optional-asynchronous-history-archive.md),
[ADR-035](../in-progress/035-remove-legacy-synchronous-account-history.md),
[ADR-036](../in-progress/036-pluggable-hot-history-store.md).

---

## Context

Scope: the optional asynchronous history archive. Three questions:

1. review feedback on the implementation;
2. is the design resilient for a long-running process;
3. what can be simplified without weakening correctness or resiliency.

Material covered: ADR-034 (2,720 lines), ADR-035, ADR-036; `archive-modules/`
(147 main sources, ~10.7k LOC); `app/.../archive/` (2,579 LOC, of which
`HistoryArchiveService` is 1,792); the archive seams in `runtime/` and
`core-api/`; 29 archive test classes (~4.2k LOC) plus 8 app-level archive tests;
the console-ui archive panel; the 10 ADR-034 phase reports; and the ~40-commit
implementation history.

Findings marked **[verified]** were read directly in the source during this
review.

---

## Verdict

**Design as specified: strong — among the better-specified subsystems I have
reviewed.** The central decisions are the ones that determine whether a
projection subsystem survives years of uptime, and they are all made correctly.

**Implementation fidelity: high.** The ADR is not aspirational. The recovery
matrices, deterministic job IDs, digest-verified idempotent replay, bounded
budgets, and fail-closed identity checks all exist in code, and the test names
read like the ADR's acceptance criteria rather than coverage padding.

**Residual risk: concentrated and fixable.** One defect candidate in the
cross-engine rollback path, one shutdown path that leaks native handles, one
durability gap in epoch staging, and a systemic observability shortfall. None
require redesign.

The one-line summary for a long-running deployment: **the archive will not
corrupt itself and will not take the node down; it can stop making progress
without anyone finding out, and one of its recovery paths may not fire on the
default hot-store engine.**

---

## What is right

Worth stating explicitly, because these are the decisions that matter most.

1. **History is a projection over durable canonical sources, not an event
   consumer.** ADR-034 explicitly rejects an in-memory event stream as the
   source of truth; events are a latency hint only. The rollback listener does
   nothing but three `AtomicLong` min-accumulates on the event thread
   (`HistoryArchiveService.java:859-867`). This single decision is what makes
   crash, restart, and reconnect safe by construction. **[verified]**

2. **The independent outpoint resolver.** Address history resolves a spend's
   sender from the archive's *own* durable outpoint state, never the live UTXO
   store — and refuses to resolve at all until genesis-seeded
   (`SequentialOutpointResolver:52-59`). This is what keeps sender history
   correct after spent-output pruning and during asynchronous replay, the
   failure mode that silently corrupts most naive wallet indexers. The *only*
   place core UTXO state is read is the one-shot `tip`-mode seed, which streams
   to a temp file specifically to release the core RocksDB snapshot before
   writing archive pages (`HistoryArchiveService.java:1423-1487`).

3. **The dual-write problem is solved, not hidden.** The archive transaction and
   the hot cursor cannot share an atomic commit, and the ADR says so plainly.
   The resolution is deterministic job IDs
   (`ArchiveJob.deterministic(network, dataset, projectionVersion, range,
   anchor, kind)`), plus replay verification of **both** row counts and a
   SHA-256 ordered digest before a committed job is accepted as a repeat
   (`SqliteWriteSession.java:95-105`, `DuckLakeWriteSession.java:116-126`).
   A differently-shaped retry fails loudly rather than double-writing
   (`rejectCoverageOverlap`). **[verified]**

4. **Anchors are re-checked inside the write session.** `BlockArchiveWorker`
   re-verifies first and last canonical anchors before derivation *and* again
   after appending rows but before `commit()` (`:101-102`, `:121-122`), with
   `verifyParentChain` (`:251`) proving intra-batch contiguity. A fork switch
   during a long batch aborts instead of committing a mixed-fork range.
   **[verified]**

5. **No unsafe escape hatch on the safety window.** `rollbackRetentionBlocks`
   and `archiveFinalityBlocks` are validated `>= k` from parsed Shelley genesis
   with `finality >= rollback`, defaulting to `2k`. The ADR also explains why it
   refuses to read `k` from `EpochParamProvider` — its mainnet-oriented default
   would silently validate a devnet against the wrong value. That is unusually
   sharp reasoning.

6. **Coverage is gap-explicit.** `ArchiveCoverage` stores sorted non-overlapping
   complete ranges and validates the invariant, with the comment *"gaps are
   meaningful and never presented as empty history."* Every REST endpoint
   honours it: 503 with a distinct building/disabled/unhealthy message rather
   than an empty 200, and `/history/watermark` returns **409** when the selected
   datasets share no common finalized coverage.

7. **The read path is genuinely shared, not duplicated.** Both backends use one
   schema-driven `JdbcArchiveRepositorySet` over `ArchiveSchemas`, parameterized
   only by a connection accessor, table prefix, and coverage-table name. SQL
   identifiers come only from the schema; caller values are always bound.
   **[verified]**

8. **ADR-036 already executed the big simplification.** Killing the concurrent
   live/backfill tracks for one sequential `CATCHING_UP → LIVE` lifecycle
   removed duplicate resolver state, dual activation anchors, and a merge path
   — the highest-risk part of the original design. Commit `89e20108` is net
   **-104 lines** while deleting a class of failure modes. This is the right
   instinct, and it is why everything below is an increment, not a redesign.

9. **Engineering discipline.** Zero `TODO`/`FIXME` in 147 files. The unreleased
   SQLite schema was re-consolidated into one `V1` migration (`e593ee8b`)
   instead of accumulating patches. A speculative `DuckLakeAddressLocator` (261
   lines) was added in `2b41f8d3` and later removed — matching the ADR's own
   rule that an accelerator ships only on measured evidence. **[verified]**

---

## Findings

Ranked by impact on a long-running deployment.

### A. Cross-engine divergence: backfill reactivation may not fire on the default hot store (defect candidate — highest)

This is the most consequential finding, and it sits exactly where two engines
are assumed interchangeable.

`BlockArchiveWorker.reconcileCommittedTip` converts a hot-store failure into the
dedicated recovery signal by catching **`IllegalStateException` specifically**:

```java
if (progress instanceof HotHistoryStore hot) {
    try {
        hot.resetTrackFrom(dataset.dataset(), ArchiveTrack.BACKFILL, requestedStart);
    } catch (IllegalStateException e) {
        throw new BackfillActivationInvalidatedException(dataset.dataset(), requestedStart, e);
    }
}
```
`archive-modules/archive-core/.../worker/BlockArchiveWorker.java:222-227`

The two hot engines implement `resetTrackFrom` with **different post-conditions
and different exception types**: **[verified — all four facts read directly]**

| | RocksDB hot store | SQLite hot store |
|---|---|---|
| Implementation | replays exact undo, then `batch.delete(progressKey)` — deletes progress outright (`RocksDbHotHistoryStore.java:218-243`) | `rollback(conn, dataset, track, firstBlock - 1)` — range-deletes, then **sets** progress to `firstBlock-1` (`SqliteHotHistoryStore.java:379-383, 386-417`) |
| Needs a checkpoint at `firstBlock-1`? | **no** | **yes** — `.orElseThrow(() -> new ArchiveStoreException("missing checkpoint at rollback target"))` |
| Throws | `IllegalStateException` | `ArchiveStoreException extends RuntimeException` — **not** an `IllegalStateException` |

Consequences when the SQLite hot store is selected (it is the configured default
in `application.yml`, and the shipped devnet profile uses it with
`start-mode: tip`):

- For any block dataset activated at block **N > 0** — i.e. `tip` mode,
  `earliest-available` on a pruned node, or any dataset enabled after the node
  already synced — the hot store has checkpoints only for blocks ≥ N, so
  `resetTrackFrom(dataset, BACKFILL, N)` looks for a checkpoint at N-1, does not
  find one, and throws `ArchiveStoreException`.
- That type is **not** caught at `:225`, so it is never converted to
  `BackfillActivationInvalidatedException`.
- `runCatchupDataset`'s dedicated handler (`:709-717` →
  `reactivateCatchupDataset`: clear track, reset and re-seed the resolver,
  re-establish the body requirement) is therefore **never invoked**. The
  exception falls through to the generic `catch (Exception e)` at `:718-722`,
  the dataset is marked DEGRADED, and it retries the identical failing path
  every poll interval forever.
- `full-required` from genesis is unaffected: `firstBlock - 1 == -1` takes the
  `if (commonBlock < 0)` branch that deletes progress without a checkpoint
  lookup.

The `LIVE` path happens to be safe: `transitionCaughtUpDatasets` writes a
checkpoint at `baseline` and sets `hotStart = baseline + 1`, so the checkpoint at
`activation - 1` exists — and the live rollback path catches generic `Exception`
and falls back to `reactivateLiveDataset` anyway.

**Why the conformance suite did not catch it:** grepping
`AbstractHotHistoryStoreConformanceTest` for `resetTrackFrom` returns **nothing**
— the shared semantic fixture never exercises the method at all. **[verified]**

*Fix:* two independent changes, both cheap. (1) Give `resetTrackFrom` one
declared failure type in the `HotHistoryStore` contract and make both engines
throw it (or catch `RuntimeException` at `:225`). (2) Add `resetTrackFrom` —
with a non-zero activation and with a pruned/absent checkpoint — to the shared
conformance fixture, so the two engines are held to identical post-conditions.

I have not executed this path; it is a code-reading conclusion from four
directly verified facts. A test at the conformance layer will confirm or refute
it in minutes, and is worth writing either way.

### B. A hung worker leaks every native handle on shutdown (high)

`closePartial()` joins the worker first, for a documented reason:

```java
// Join the optional worker before detaching dependencies or closing JNI
// stores. shutdownNow alone permits in-flight RocksDB/DuckDB calls to race
// native handle destruction during graceful application shutdown.
if (subsystem != null) subsystem.close();
```
`app/.../archive/HistoryArchiveService.java:1739-1743` **[verified]**

But `ArchiveSubsystem.close()` **throws** on join timeout:

```java
if (!current.awaitTermination(30, TimeUnit.SECONDS)) {
    throw new IllegalStateException("archive worker did not stop within 30 seconds");
}
executor = null;
```
`archive-modules/archive-core/.../worker/ArchiveSubsystem.java:45-48`

That call at `:1743` is the **only unguarded statement in the method**.
Everything after it — projection-executor shutdown, detaching the epoch staging
sink, resetting `chain.setBlockBodyRetentionBoundary(...NONE)`, `backend.close()`,
`controlStore.close()` — is individually `try { } catch (Exception ignored)`. A
timed-out join skips all of it:

- the DuckLake/DuckDB or archive-SQLite handle stays open and the **archive
  directory lock stays held**, so the next start can fail to acquire it;
- the hot store stays open;
- core's block-body retention boundary keeps pointing at the archive control
  store;
- `subsystem` is left non-null (`executor = null` is *inside* the `try`, after
  the throw), so a retry re-throws instead of recovering.

A timeout is plausible: `shutdownNow()` interrupting a virtual thread parked in
a JNI call is a no-op, and `runBlockProjectionCycle`'s join loop (`:674-691`)
*deliberately* ignores interrupts and re-blocks until every projection returns.
The phase-10 report records a real JNI close race that motivated this join — the
failure path of that same join now defeats its purpose.

*Fix:* wrap `subsystem.close()` in `try/catch` + `log.warn`, exactly like the
projection executor immediately below it; move `executor = null` out of the
`try`. Two lines.

### C. Epoch staging is the only place that does not verify what it wrote (high)

Every other layer verifies. Archive write sessions compare row counts **and**
ordered digest before accepting a replay. The RocksDB hot store writes with
`new WriteOptions().setSync(true)`. The durable epoch source — the input for
`reward`, `epoch_stake`, `drep_distribution`, `ada_pot`, and
`governance_proposal_status` — does not:

```java
public void commit() {
    output.flush();
    output.close();                                   // no force/fsync
    Properties p = properties(job);
    p.setProperty("rowCount", Long.toString(count));
    ...
    move(temporary, rows(job.jobId()));               // metadata op
    move(temporaryManifest, manifest(job.jobId()));   // commit marker
```
`archive-modules/archive-core/.../source/DurableEpochFileSource.java:281-292`

Four compounding gaps, all **[verified]**:

- **No fsync anywhere in the staging path.** A repo-wide grep for
  `FileChannel`/`.force(` across `archive-modules` finds only the two advisory
  lock files. The manifest move is the commit marker, but nothing forces the
  rows file's data pages first.
- **`rowCount` is written and never read.** It occurs exactly once in the whole
  repository, at `:285`. The detection hook exists and is unused.
- **`cleanInterruptedWrites` checks presence, not completeness** (`:214-233`) —
  a `.rows` file is discarded only if its `.properties` is missing.
- **`move()` silently degrades** from `ATOMIC_MOVE` to non-atomic `Files.move`
  on `AtomicMoveNotSupportedException` (`:234-239`).

Consequence: on host power loss or VM crash (not `kill -9`, where the page cache
survives — which is why the phase-10 SIGKILL run would not catch this), a
truncated `epoch_stake` or `reward` staging file can be committed to the archive
as a complete epoch, with coverage claiming it. That is exactly the "silently
incomplete history" the ADR forbids.

*Fix:* force the rows file before the move; validate the recorded `rowCount` on
read and in `cleanInterruptedWrites`. Both small and local.

### D. Failures are contained but not visible (high, systemic)

Three separate gaps that compound.

**D1 — the cycle's tail can fail silently.** `ArchiveSubsystem.runSafely`
discards every `Throwable` with no log and no health update:

```java
try { boundedWork.run(); }
catch (Throwable ignored) { /* status/health is owned by the bounded worker */ }
```
`ArchiveSubsystem.java:35-38` **[verified]**

The comment's premise holds for the block workers, which own their metrics. It
does **not** hold for the stages of `runBoundedWork()` that have no guard of
their own: **[verified]**

| Stage | Guarded? |
|---|---|
| `processPendingEpochRollback` / `processPendingLiveRollback` | yes — internal catch + retry re-arm |
| `runBlockProjectionCycle` | per-dataset yes; `beginSharedSourceCycle` / `submit` no |
| `transitionCaughtUpDatasets` (`:730`) | **no** |
| live-worker loop | yes — per-dataset |
| `runEpochWork` (`:1062`) | **no** |
| `applyRetention` (`:991`) | **no** |
| `runMaintenanceIfDue` | yes |

A persistent failure in `transitionCaughtUpDatasets` — it calls
`controlStore.applyBlock`, `backend.openReadSession`, `activations.setHotStart`
— aborts the live workers, epoch work, retention, and maintenance every cycle,
forever, with no log line and no degraded health. Same for `runEpochWork`
(whose `"missing epoch activation"` and `"epoch source parts do not share a
canonical boundary"` throws are reachable) and `applyRetention`.

**D2 — no backoff, and no `FAILED` state.** There is no exponential backoff, no
attempt counter, and no circuit breaker anywhere in the layer. A DEGRADED
dataset retries every **1000 ms** by default, each attempt logging a full stack
trace (`:718-722`, `:649-653`). For a permanent condition — pruned bodies below
a required range (`ChainBlockArchiveSource.java:62-69`), a corrupt archive, a
full disk — that is ~86,400 stack traces per day per dataset with no escalation.
ADR-034 specifies *"retry with bounded exponential backoff and a hard
attempt/deadline limit"*; `ArchiveWorkerStatus.State` even declares `FAILED` and
`DISABLED` constants that are **never emitted**. The only adaptive behaviour is
batch *size*, not retry *rate*.

**D3 — no metrics, no health check, no scheduled integrity check.** There *is* a
console UI panel (`ArchiveHistoryPanel.svelte`, with a `DISABLED / UNAVAILABLE /
UNHEALTHY / CATCHING_UP / READY` state machine and an "Archive attention
required" card) and a rich `/status` payload — that part is good. What is
missing is anything a machine can alert on:

- `ArchiveWorkerMetrics` is a `ConcurrentHashMap<Key, ArchiveWorkerStatus>` — an
  in-process status holder, **not** metrics. The repo already has Micrometer
  wired for `NodeMetrics` and `PluginMetrics`; the archive is not.
- No MicroProfile `@Readiness`/`@Liveness` for history (unlike
  `PluginHealthGroupCheck`).
- `verifyIntegrity(Duration)`, `storageStats()`, `backup(Path)`, and
  `backupCatalog(Path)` are implemented on the backends and have **zero
  production callers** — grep across `app/src/main`, `runtime/src/main`,
  `core-api/src/main` finds none. **[verified]** So there is no scheduled
  `PRAGMA integrity_check`, no free-page monitoring, and no exposed backup path,
  all of which ADR-034 lists as maintenance jobs.

Together: a wedged archive holds block-body retention leases and grows disk
indefinitely, and the only signal is a human looking at a web page.

*Fix, in order:* wrap each `runBoundedWork` stage with a named health failure;
log in `runSafely`; project `ArchiveWorkerMetrics` into Micrometer; schedule
`verifyIntegrity` on the existing maintenance cadence; add one readiness check.

### E. Deep canonical mismatch recovers by full rebuild from activation (medium–high)

`reconcileCommittedTip` responds to *any* canonical mismatch at the catch-up
cursor by invalidating `BlockRange(requestedStart, current.coordinate())` — the
whole dataset from its activation anchor — and resetting the track. The inline
comment is honest about the trade-off, and it matches ADR-036.

The correctness argument holds. The **cost** argument is unbounded: on mainnet
with `full-required`, this is a re-read and re-derivation of the entire chain,
and it silently depends on every block body from activation still being
retained. If bodies were pruned in the interim, the dataset does not rebuild
slowly — it becomes permanently incomplete.

Compounding it, `invalidate()` deletes **whole jobs that merely intersect** the
requested range, so a caller can lose committed data outside the rollback range;
the activation-anchor rebuild is the compensation for that.

This is rare by construction (the cursor sits below `archiveFinalityBlocks`) and
is reachable only via devnet time-travel, snapshot restore, corruption repair,
or a deeper-than-`2k` reorg. But "rare and unbounded" is the shape of failure
that bites long-running processes.

*Suggestion:* `archive_commit` already records canonical range and hashes per
job, so the newest committed job boundary at or before the common block is
available as a safe restart point without partial surgery. At minimum, log the
projected rebuild span and the oldest required body at reactivation, so the
operator learns the cost before the disk does.

### E2. The DuckLake transaction locator can full-scan `chain_transaction` on the API path (medium–high)

`DuckLakeTransactionLocator.block(...)` — the point-lookup used by
`/txs/{hash}/status` on the DuckLake backend — rebuilds before it reads:

```java
synchronized OptionalLong block(Connection duckLake, long generation, byte[] txHash) {
    rebuildIfRequired(duckLake, generation);
    return block(txHash);
}
```
`DuckLakeTransactionLocator.java:37-41` **[verified]**

`rebuildIfRequired` triggers whenever the locator's stored generation is not
*exactly* the pinned generation, and `rebuild` is an unbounded full scan
executed inside the caller's thread:

```java
try (Statement clear = locator.createStatement()) { clear.executeUpdate("DELETE FROM tx_locator"); }
try (Statement scan = duckLake.createStatement();
     ResultSet rows = scan.executeQuery(
         "SELECT tx_hash,block_number,archive_job_id FROM history_lake.chain_transaction");
```
`:80-97` **[verified]**

The writer side keeps in step: `updateTransactionLocator` is called
unconditionally on every successful commit (`DuckLakeWriteSession.java:148`), so
steady-state `advance` is incremental. The problem is the **interleaving**:

- a read session pins generation *N* at open;
- the worker commits, moving the locator to *N+1* (commits happen every poll
  cycle during catch-up);
- a `/txs/{hash}/status` call on that pinned session asks for generation *N*,
  sees a mismatch, and **full-scans `chain_transaction` to rebuild backwards to
  N** — on the request thread, holding the object monitor;
- the next commit's `advance` then sees `generation(locator) != generation - 1`
  and **full-rebuilds forward again**.

On a mainnet-scale `chain_transaction` that is a multi-minute stall per
occurrence, and the two sides can alternate. Invalidation and retention also
force a full `rebuild` outright.

This is a code-reading conclusion — actual frequency depends on commit/read
interleaving, and the SQLite backend is unaffected (it uses a real B-tree index
on tx hash). But the locator is precisely the accelerator ADR-034 required for
DuckLake point lookups, and a generation-mismatch rebuild is the opposite of an
accelerator. Worth a benchmark under concurrent commit + lookup before DuckLake
is recommended as the default for a wallet-serving deployment.

*Fix direction:* make the locator generation-tolerant (serve a hit and verify
against the pinned snapshot — the fallback for stale positives already exists at
`DuckLakeHistoryArchiveBackend.java:300-307`) rather than rebuilding to match,
and make any rebuild a background job rather than request-path work. Note also
that `removeJobs` — the incremental invalidation path that would avoid a full
rebuild — is implemented but **has no callers**.

### F. Backfill and live workers have diverged (medium)

Six asymmetries — enough that they should be one class: **[verified]**

1. **Byron EBB bridge.** Backfill validates parent linkage through
   `source.extendsCanonicalParent(...)` — the bridge added across four commits
   (`a97620c5`, `cf6b7ed5`, `a8e1887c`, plus the uncommitted
   `getByronEpochBoundaryBlockAtOrBefore` change) because a Byron main block's
   parent may be an epoch-boundary block with no block number. Live uses raw
   `Arrays.equals(parentHash, previous.blockHash())`
   (`LiveBlockArchiveWorker.java:95-106`). Latent today, since live runs near
   tip — but this is precisely how a devnet/time-travel bug appears later.

   On the in-flight working-tree change itself: renaming
   `getByronEpochBoundaryBlock` to `getByronEpochBoundaryBlockAtOrBefore` and
   switching `DirectRocksDBChainState` from an exact
   `db.get(ebbBySlot0Handle, slot)` to a `seekForPrev` iterator looks correct.
   A Byron main block can follow its epoch boundary by several empty slots while
   still naming it as parent, so the exact-slot lookup was simply wrong. Seeking
   backwards can only return an earlier boundary, the bridge still requires
   *both* `ebb.blockHash == parentHash` **and**
   `ebb.parentHash == predecessorHash` (so a false bridge needs a hash
   collision), and the reference now reports the iterator's `ebbSlot` rather
   than echoing the requested slot. No objection to landing it.
2. **A pruned body is treated as a fork switch.** Live decides rollback with
   `source.readCanonical(previous)` and throws
   `LiveActivationInvalidatedException` when it returns `null` (`:29-34`) — so a
   pruned body triggers a full return to catch-up. Backfill deliberately uses
   `source.canonicalReference(...)`, documented as *"Canonical identity that
   remains available after the block body is pruned"* (`BlockArchiveSource:14`).
   Live also fully decodes that block every batch just to compare one hash.
3. **No source lease.** Backfill wraps every read in `source.acquire(...)`.
   Live calls `readCanonical` with no lease, relying only on
   `requireBlockBodiesFrom` plus the retention boundary — while
   `advanceBlockBodyRequirement` has already moved the floor to
   `coordinate + 1`, so the block live is about to re-read is no longer
   protected.
4. **No adaptive batching.** Backfill halves on `RowLimitExceeded` /
   `ArchiveBatchCapacityException` and regrows after three successes. Live uses
   `maxBlocksPerBatch` flat and throws with no retry (`:76`). A live track that
   has fallen behind — the `reanchorStaleLiveTrack` path exists because that
   happens — hits a hard failure where backfill would have adapted.
5. **The live row bound is per block, not per batch.** `operations` is
   re-allocated inside the block loop (`:67`) and the cap is checked against it,
   so a worst-case live batch is `maxBlocksPerBatch × maxRowsPerBatch`
   = 1000 × 250,000. Backfill correctly bounds the whole batch (`:112`).
6. **Duplicated logic.** `verifyParentChain`, job construction, block reading,
   and the stateful begin/commit/abort dance are written twice.

### G. Reader isolation covers shutdown, not reorg (medium)

Query paths take `lifecycleLock.readLock()` (`:380-383`), but the **write lock
is taken only by `close()`** (`:1731`). Rollback (`processPendingLiveRollback`),
`clearTrack`, `reactivateLiveDataset`, and cold `invalidate` all run on the
worker thread without it.

Within each store, isolation is sound: hot readers hold a real RocksDB snapshot
or SQLite WAL snapshot; cold readers hold a pinned generation, and
`ArchiveConsistencyPlanner` re-asserts `coverage.revision() == session.generation()`
inside the pinned session. Promotion is commit-then-delete and readers open hot
before cold, so a row is at worst duplicated (deduped by logical identity) and
never lost — `HistoryArchivePromotionTest` is the executable spec of that.

But across the seam: a reader that has already snapshotted hot can open a cold
session *after* an invalidation, and so observe hot rows for blocks the cold
side has just repudiated. Relatedly, `liveCoverage(dataset)` is read from live
progress **outside** the pinned hot snapshot, so the cold/live gap check compares
snapshot content against a possibly-newer cursor.

Not a data-loss bug; a bounded staleness/incoherence window during a reorg,
which the ADR's query-model section implies should not exist. Worth either
closing (take the write lock for invalidation) or documenting as accepted.

### H. Unbounded growth in four places (medium) — all **[verified]**

1. **`archive_invalidations`** is append-only in both backends; no `DELETE`
   exists against it anywhere. One row per invalidation/retention event, forever.
2. **Hot-store receipt digests** — RocksDB `r/<jobId>` keys and the SQLite
   `archive_receipts` table are written and never deleted. One row per archive
   job for the lifetime of the node.
3. **SQLite `addresses` / `stake_addresses` dimensions** are inserted by
   `INSTEAD OF INSERT` triggers and never garbage-collected; job-cascade deletes
   remove facts but leave the dimension rows.
4. **RocksDB tombstones** — deletion is exclusively point deletes
   (`pruneUndoThrough`, `deleteFacts`, `clearTrack`) with no `DeleteRange`, no
   compaction filter, no manual `compactRange`, and **stock `Options`** (no
   compression, write-buffer, bloom, block-cache, or compaction tuning) under a
   delete-heavy, prefix-scan workload.

Space reclamation is also weaker than it looks: SQLite `VACUUM` runs only if
`maxBytesToRewrite >= the entire database size` **and** all four reader permits
are free via a non-blocking `tryAcquire` — both conditions fail silently as the
archive grows or under any read load. DuckLake retention is logical only;
physical reclamation needs `expire_snapshots` (168 h default) then
`cleanup_old_files` (24 h), and `maintain()` returns immediately if any pinned
read session exists — on a busy node, cleanup may effectively never run.

### I. `HistoryArchiveService` is a 1,792-line orchestrator in the wrong module (medium)

ADR-034's module layout says: *"Thin assembly, configuration binding, lifecycle,
health/metrics registration, and canonical-source adapters remain in Yano's
existing `runtime` module."*

What exists in `app/` (the Quarkus module) is the entire orchestration brain:
cycle scheduling, catch-up→live handoff, promotion, rollback recovery and
reactivation, resolver seeding, retention, maintenance scheduling, watermark
computation, transaction lookup, status assembly, size-string parsing, and two
binary searches over canonical blocks — 1,792 lines with ~30 `volatile` fields,
paired `EnumMap`s of dataset runners, four `AtomicLong` pending-rollback slots,
and a `ReentrantReadWriteLock`. `runtime/` contributes only a three-method
read-only capability interface.

The class javadoc claims a backend-neutral core with a thin app boundary; the app
is the engine. Practical cost: the most safety-critical logic (rollback
recovery, handoff, promotion) can only be tested through Quarkus rather than in
`archive-core`'s fast suite — which is part of why finding A slipped through.

Related smaller smells in the same seam: three `instanceof HotHistoryStore`
downcasts out of an `ArchiveProgressStore`-typed field
(`BlockArchiveWorker.java:200`, `:222`, with runtime throws on the else-branch);
`ArchiveBackend.repositories()` defaulting to
`throw new UnsupportedOperationException` instead of being declared in
`ArchiveCapabilities`; and `__table` / `__offset` magic filter keys smuggling two
orthogonal query dimensions through the filter map.

### J. Correctness-critical code duplicated verbatim across modules (medium)

`updateDigest` — which produces the `orderedDigest` that gates every idempotent
replay — is **byte-for-byte identical** in `SqliteWriteSession.java:181-195` and
`DuckLakeWriteSession.java:339-353`. **[verified]** A fix applied to one and not
the other silently changes replay validation on one engine only. The same is
true of the replay compare-and-return block, the `allowedTables` construction,
`scalarLong`, the `[a-z][a-z0-9_]*` identifier validator (three copies), and the
two file-lock classes.

`mergeAdjacent` exists three times with **two semantics**: both backends require
exact adjacency (`current.end + 1 == next.start`), while
`JdbcArchiveRepositorySet.merge:258` tolerates overlap
(`next.start <= current.end + 1` with `Math.max`). **[verified]** They agree
today only because `rejectCoverageOverlap` guards inserts — the two
implementations disagree about whether non-overlap is an invariant or something
to tolerate.

Range-coverage walking is implemented four to six times across
`ArchiveCoverage.covers`, `JdbcArchiveRepositorySet.covers`/`merge`,
`HistoryArchiveService.coversRange`, the inline `promoteLiveRows` walk, and
`ArchiveConsistencyPlanner.intersect` (whose sorted, non-overlapping
precondition is undocumented and silently wrong if violated).

Pointer lifecycle is implemented near-verbatim three times
(`AddressTransactionDataset:113-158`, `UtxoHistoryDataset:62-100`,
`YaciUtxoHistoryDecoder:152-173`), and the `commitBatch` /
`commitCoveredBatch` / `commitLiveBatch` triplet is byte-identical between the
two stateful datasets.

### K. Two projections disagree about pointer semantics (low–medium)

For an unresolvable pre-Conway pointer address, `UtxoHistoryDataset` records an
explicit marker — `pointer_unresolved`, `pointer_not_effective`,
`pointer_resolved` (`:153-167`) — while `AddressTransactionDataset` silently
produces `stake = null` (`:302-315`). Same chain fact, two answers, one of them
unobservable. A user querying by stake credential gets a quietly different
result depending on which dataset answers.

### L. Batch memory bound is large and unbenchmarked (low–medium)

`BlockArchiveWorker.runBatch` holds simultaneously every decoded block of the
batch (up to `maxBlocksPerBatch` = **1000**) *and* every derived row (up to
`maxRowsPerBatch` = **250,000**) before opening the write session. Materializing
before `backend.begin(job)` is defensible — it makes halve-and-retry cheap and
keeps the write transaction short — but ADR-034 describes bulk rows as
"streamed directly into the archive backend", and the ADR's own open questions
still list "safe worker batch sizes for mainnet reward epochs" as unresolved.

The adaptive controller shrinks only on *backend capacity* failure; it never
reacts to JVM heap pressure. On top of that, the two `CycleCachingBlockArchiveSource`
instances each cache `maxBlocksPerBatch × 2` = 2000 decoded blocks per cycle,
with entry-count accounting only — no size accounting.

Also in this class: on DuckLake, capacity-vs-fatal classification is done by
**string-matching DuckDB error text** (`"Out of Memory Error"`,
`"failed to allocate data of size"`, `"max_temp_directory_size"`). A message
change across DuckDB versions silently converts adaptive backpressure into a
hard DEGRADED state, and a genuine ENOSPC matches none of them.

### M. Dead code (low, but it hides divergence) — all **[verified]**

- **`EpochArchiveWorker` (74 lines): zero references.** Its logic is
  re-implemented in `HistoryArchiveService.runEpochWork`/`commitEpochGroup`
  (which additionally handles multi-part boundary grouping). The two copies
  already disagree on the row-limit rule — accumulated across pages in the class
  (`:62`), per page in the service (`:1121-1123`).
- **`ArchiveConsistencyPlanner.open(...)`: unused** — the service calls `plan(...)`
  and manages its own session and lease.
- **`StandardBlockDatasets.addressTransactions(...)`: no production caller**,
  making `AddressTransactionFact`, `ArchiveBlockFacts.addressTransactions`, and
  the 3-arg `ArchiveBlockFacts` constructor permanently unpopulated.
- `DuckLakeTransactionLocator.removeJobs`: no callers.
- `ArchiveWorkerStatus.State.DISABLED` / `.FAILED`: declared, never emitted.
- Unused imports/helpers in `SequentialOutpointResolver`,
  `SequentialPointerResolver`, `HotArchiveRows`, `LiveBlockArchiveWorker`.

### N. Documentation drift (low, but actively misleading)

- `archive-modules/archive-core/README.md` still documents the **removed**
  dual-track model ("The live track can project near-tip blocks independently so
  wallet data does not wait for a long backfill"), contradicting ADR-036 and
  commit `89e20108`.
- The same file says "RocksDB remains the compatibility default"; the parent
  `archive-modules/README.md` says "SQLite is the validated default"; and
  `application.yml` sets `hot-store.engine: sqlite`.
- `adr/reports/adr-034-phase-10.md` describes per-dataset "independent backfill
  and live activation/cursor state" — accurate when written, stale now.
- Code says `ArchiveTrack.BACKFILL` where ADR-036 renamed the phase to
  `CATCHING_UP`.
- Both `ArchiveWorkerConfig` compatibility constructors hard-code
  `pauseBackfillDuringCoreCatchup = true` with the comment *"retaining the safe
  core-priority default"* — a default no production path uses (`defaults()` and
  the config binding both pass `false`, deliberately, per `e1a167d4`). And
  `CoreSyncView.lag()` falls back to `localBlock` when no upstream target is
  known, so the gate reads lag 0 and would not fire even if enabled.

### O. Verification evidence is uneven (low, but shapes confidence)

Phase reports 0–3 run 76–183 lines with concrete verification. Phases 5–9 —
epoch datasets, transactions/account events, address history, UTXO history,
rewards — run **8–18 lines each** and are descriptive rather than evidentiary.
Phase 10 is strong again (125 lines, a real preprod run, three named defects
found and fixed, a SIGKILL recovery result, a JNI shutdown race found and fixed).

ADR-034's testing section lists ~150 requirements. The unit and conformance
suites cover a good fraction, but several ADR-mandated matrices have no recorded
evidence: disk-full isolation, DuckDB OOM, SQLite corruption hiding,
long-reader tests, and the disabled-vs-`main`-baseline core sync non-regression
comparison that the ADR makes a rollout gate.

Separately, ADR-034's "Switching Archive Backends" section specifies an
offline/restartable migration command (record source coverage → stream rows in
bounded ranges → compare counts/digests → atomic control-store cutover).
Searching all main sources for `migrat` returns only Flyway schema migration,
the removed-config guards, and the unrelated `runtime/migration` startup
migrations; there are no `*Command*`/`*Cli*` classes in `app/src/main`.
**[verified]** So this appears not to be implemented. Given the fail-closed
engine/identity check, an operator who picks the wrong engine today has exactly
one option — rebuild from canonical sources — which is fine while bodies are
retained and impossible once they are pruned. Worth an explicit "deferred"
note in the ADR rather than leaving it as an unfulfilled requirement.

---

## Resiliency assessment

**Design-as-specified: resilient.** Every failure category the ADR enumerates
has a defined, bounded response, and the two genuinely hard problems —
non-atomic dual-write, and independent input resolution — are solved rather than
deferred.

**Implementation: resilient where exercised; unverified where not.**

| ADR guarantee | Implemented | Test evidence |
|---|---|---|
| Cursor advances only after durable archive receipt | yes | `resolverStateAndCursorCommitAtomicallyAfterBackendReceipt` |
| Idempotent retry of same job ID after crash | yes | `restartDiscoversCommittedReceiptAndMakesRetryIdempotent` |
| Conflicting retry for same job ID is fatal | yes (counts + digest) | `retryWithDifferentRowsFailsAndInvalidationRemoves*` |
| Anchor change aborts without advancing | yes | `changedCanonicalAnchorAbortsBackendAndDoesNotAdvanceCursor` |
| Exact undo, no full scan on rollback | yes (RocksDB); range-delete equivalent (SQLite) | `consumedResolverOutputIsRestoredBySuffixRollback`, `missingUndoFailsClosed…` |
| Catch-up→live handoff survives crash | yes — live cursor is the durable phase marker | `catchupToLiveHandoffPreservesTheSingleResolver` |
| Unresolved input is fatal, never skipped | yes | `AddressHistoryTest` |
| Both archive backends behave identically | yes — shared conformance fixtures | `DuckLake/SqliteBackendConformanceTest` |
| **Both hot stores behave identically** | **partial — see finding A** | conformance fixture omits `resetTrackFrom` |
| Failure degrades history only, never core | yes — four independent barriers | app tests + phase-10 preprod/SIGKILL run |
| Rollback re-armed rather than swallowed | yes | `failedLiveRollbackIsRearmedInsteadOfSilentlyConsumed` |
| Failures are **observable/alertable** | **no** — findings D1–D3 | none |
| Bounded backoff + `FAILED` classification | **no** — finding D2 | none |
| Epoch source durability | **partial** — finding C | none |
| Disk-full / OOM / corruption isolation | claimed | **unverified** |
| Backend-to-backend offline migration | **not found** — see note | none |
| Disabled-vs-baseline core non-regression | rollout gate | **unverified** |

---

## Recommended follow-up work (ranked, none applied)

Items 1–4 are small and strictly additive. 5–8 reduce line count while
preserving behaviour. 9 is a decision, not a change.

1. **Close finding A.** Add `resetTrackFrom` (non-zero activation; absent
   checkpoint) to `AbstractHotHistoryStoreConformanceTest`, then unify the
   declared failure type across both engines — or widen the catch at
   `BlockArchiveWorker:225` to `RuntimeException`. Highest priority: it is a
   recovery path that may not fire on the default engine.
2. **Fix shutdown (B).** Wrap `subsystem.close()` in `try/catch` + `log.warn`;
   move `executor = null` out of the `try`. Two lines, prevents a held archive
   lock across restarts.
3. **Make epoch staging durable (C).** Force the rows file before the move;
   validate the recorded `rowCount` on read and in `cleanInterruptedWrites`.
4. **Make failure visible (D).** Per-stage try/catch in `runBoundedWork`
   recording a named health failure; rate-limited WARN in `runSafely`; project
   `ArchiveWorkerMetrics` into Micrometer next to `NodeMetrics`; schedule the
   already-written `verifyIntegrity` on the maintenance cadence; add one
   readiness check; emit `FAILED` with bounded backoff after N consecutive
   identical failures.
5. **Benchmark, then fix, the DuckLake transaction locator (E2).** Run
   concurrent commits plus `/txs/{hash}/status` against a large archive and
   measure how often `rebuild` fires. If it fires at all under normal
   interleaving, make the locator generation-tolerant (verify hits against the
   pinned snapshot, which the code already does for stale positives) and move
   any rebuild off the request thread. Wiring the already-written `removeJobs`
   would also replace the invalidation full-rebuild with an incremental delete.
6. **Unify the two block workers (F).** One phase-parameterised worker; live
   inherits the EBB bridge, the pruning-safe `canonicalReference` check, the
   source lease, and adaptive batching for free. ~370 lines → ~250, and it
   closes a latent Byron divergence.
7. **Hoist the duplicated backend logic (J).** Start with `updateDigest` and the
   replay compare — correctness-critical and currently byte-identical in two
   modules — then an `AbstractJdbcArchiveBackend` for receipt/coverage/boundary/
   invalidation/health/permit handling parameterized by table prefix,
   transaction idiom, and generation source. Roughly 350–450 of ~1,670 combined
   backend lines are mechanically shareable; `JdbcArchiveRepositorySet` already
   proves the pattern works. Also collapse the 4–6 range-coverage
   implementations into one, and pick a single `mergeAdjacent` semantic.
8. **Delete the dead code (M).** `EpochArchiveWorker`,
   `ArchiveConsistencyPlanner.open`, `StandardBlockDatasets.addressTransactions`
   and its now-unreachable fact types, `DuckLakeTransactionLocator.removeJobs`,
   the unused helpers and imports. Each removal deletes a copy that can silently
   diverge from the live one.
9. **Extract orchestration from `app` into `archive-core` (I).** Move the cycle,
   handoff, promotion, rollback recovery, and retention scheduling into an
   `ArchiveOrchestrator`; leave `HistoryArchiveService` as CDI wiring, config
   binding, and status assembly. This is the ADR's own stated layout and moves
   the safety-critical logic into a suite that runs without Quarkus. Largest
   item — do it after 1–8, and it will be much easier once 6 and 8 have landed.
10. **Decide the hot-store matrix (ADR-036 Phase 4).** SQLite is the configured
   default; RocksDB is the "compatibility baseline" for an unreleased feature
   and costs ~735 LOC plus a conformance axis and a 2×2 support matrix — and
   finding A shows the two engines are not yet actually interchangeable. If the
   Phase-4 comparison has produced a decision, retiring the non-selected engine
   is a clean subtraction. If it is still pending, keep both and finish the
   conformance suite instead. Your call, not a refactor.

Near-zero-cost cleanups: refresh `archive-core/README.md` for the single-track
model; reconcile the two READMEs' "default hot store" claims; rename
`ArchiveTrack.BACKFILL` to ADR-036's `CATCHING_UP`; fix or delete the misleading
`ArchiveWorkerConfig` compatibility-constructor comment.

## Verification

- `./gradlew :archive-modules:archive-api:test :archive-modules:archive-core:test
  :archive-modules:archive-store-sqlite:test :archive-modules:archive-store-ducklake:test`
- `./gradlew :app:test` — covers `HistoryArchivePromotionTest`,
  `HistoryHotColdMatrixTest`, `HistoryArchiveActivationTest`,
  `HistoryArchiveMaintenanceTest`, `HistoryResourceTest`.
- **Finding A:** a conformance-fixture test is the fastest confirmation —
  seed a dataset activated at block N > 0, take a checkpoint at N only, call
  `resetTrackFrom(dataset, BACKFILL, N)`, and assert both engines agree on
  outcome and failure type.
- **Finding B:** make `boundedWork` block uninterruptibly, call `close()`, and
  assert the archive directory lock is released and a second `close()` is a
  no-op.
- **Finding C:** commit a staging file, truncate the `.rows` file behind the
  manifest, restart, and assert the source is rejected rather than archived.
- **Finding D:** run the devnet history profile (`start:devnet` already enables
  history with `archive.engine: sqlite`, `start-mode: tip`), inject a store
  failure, and confirm it surfaces in logs, `/status`, metrics, and readiness
  rather than vanishing.
