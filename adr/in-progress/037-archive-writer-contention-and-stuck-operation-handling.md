# ADR-037: Archive Writer Contention and Stuck-Operation Handling

## Status

Proposed

## Date

2026-08-17

## Context

[ADR-034](034-optional-asynchronous-history-archive.md) places optional historical projection outside the authoritative node path. [ADR-036](036-pluggable-hot-history-store.md) keeps block decoding and dataset row derivation parallel and bounded while DuckLake and standalone SQLite each retain one durable archive writer.

Both archive backends already use a fair `Semaphore(1, true)` for their single writer. This supplies FIFO acquisition among contenders. `HistoryArchiveService` uses one archive subsystem cycle thread and a bounded projection executor. Each cycle joins all block-projection futures before it starts live promotion, epoch commits, retention, or periodic maintenance. Consequently, epoch work, promotion, retention, rollback invalidation, and maintenance do not normally race block projections within a cycle. The genuine normal writer contention is between concurrently derived block-dataset batches, bounded by configured projection parallelism.

The backend currently uses the same short `acquireTimeout` (30 seconds by default) for two different concerns:

1. ordinary waiting for the fair writer semaphore or a DuckDB permit; and
2. detecting an operation that is actually stuck.

Expiry becomes `ArchiveStoreException`. The generic worker failure path marks the dataset `DEGRADED`, emits a full `paused` stack trace, leaves its cursor unchanged, and retries later. This preserves archive correctness and does not affect core sync, but incorrectly labels normal contention as a projection failure and wastes the derived batch.

Mainnet replay has shown the symptom. The precise holder must be measured before attributing an individual incident: a large block projection may hold the writer, and the current `begin()` order can also acquire the writer before waiting for a DuckDB bulk/total permit. Long-lived REST reads can consume the two total DuckDB permits; a writer then monopolizes the writer semaphore while waiting for a permit, causing other projections to time out behind it.

`BlockArchiveWorker` also reads backend coverage at the start of each batch. Those reads take a steady DuckDB permit. This is required after a crash to recognize a committed receipt whose control progress was not persisted, but it need not repeat unnecessarily within one process/cycle once coverage is known unchanged.

Maintenance already obtains the same writer semaphore. It cannot run in parallel with a commit, but it currently returns immediately while any pinned read snapshot is active. A long-running query can therefore defer maintenance indefinitely. Shutdown has a separate close-order failure mode: an exception while closing the archive subsystem must not skip the backend close.

No core UTXO, account, reward, governance, ledger, chainstate, block apply, or rollback behavior is part of this decision. Archive correctness continues to depend on its existing deterministic job IDs, digest-verified receipt replay, coverage records, and fail-closed cursor progression.

## Decision

### Retain the Existing Fair Single Writer

Do not add a second archive mutation coordinator. The existing fair backend semaphore is the single-writer and FIFO mechanism. A new queue would duplicate it, add lifecycle complexity, and risk widening the archive writer critical section to include independent hot-store/control-store work.

Parallel block decoding and row derivation remain enabled. Each worker retains at most one derived batch while waiting to commit. The explicit aggregate bound that must be benchmarked is:

```text
projectionParallelism × maxRowsPerBatch archive rows
```

The current default maximum is `4 × 250,000` rows. It is a hard operational input, not an invitation to construct an unbounded producer queue. Batch and parallelism tuning must be backed by replay memory measurements.

### Separate Normal Waiting from Stuck-Operation Failure

Writer acquisition becomes an interruptible wait loop using the existing fair semaphore:

- Waiting for a writer is normal flow control. At a configurable warning interval (default 30 seconds), log structured diagnostics: requested operation, dataset/job where available, wait duration, current holder operation/duration, and DuckDB permit state.
- Continue waiting until a separate configurable stuck-operation threshold (default five minutes). At that threshold, fail only the waiting archive mutation with an explicit stuck-writer exception and complete no cursor.
- Shutdown closes admission and interrupts/cancels waiters according to the normal archive shutdown policy. An interrupt is not reclassified as a successful archive operation.

The same distinction applies to DuckDB bulk and total-permit acquisition. A short wait may produce diagnostics; only the stuck threshold or an actual JDBC failure is a failed mutation.

No timeout change may silently convert an incomplete archive commit into a successful receipt, coverage record, or cursor advance.

### Do Not Hold the Writer While Waiting for DuckDB Capacity

For every DuckLake mutation that uses a DuckDB lease, the resource order is an
invariant:

```text
DuckDB bulk/total lease -> archive writer -> DuckLake transaction
```

No path may acquire the writer and subsequently wait for a DuckDB lease. If a
caller cannot acquire a bulk or total permit, it must not occupy the writer
semaphore while it waits. The affected paths are write-session begin, rollback
invalidation, retention, and maintenance; they change together.

After it owns both resources, the operation attaches DuckLake and enters the
existing write-session protocol. Every error path releases a lease and writer
permit exactly once. This ordering is tested under REST-read saturation and
concurrent projections.

Catalog backup is the explicit exception because it does not acquire a DuckDB
lease. Its order is `sessionGate write lock -> archive writer -> file copy`; it
must never acquire a DuckDB lease while holding either lock. This prevents a
mixed writer/lease order without weakening the quiescent-backup guarantee.
Because the write gate prevents new reads while backup waits for the writer,
backup uses a short bounded writer wait (default 30 seconds), not the five-
minute stuck-operation threshold. A busy archive defers backup rather than
blocking new history reads for an extended interval.

The implementation must retain one bulk job and one steady-state reservation
under the configured 256 MiB default. It must not validate
`projectionParallelism <= maxConcurrentQueries`: projections perform mostly
CPU/block work and may legitimately exceed the small number of concurrent
DuckDB contexts. Instead, instrumentation and bounded waits expose an unsafe
configuration, and coverage reads are reduced as below.

This order change applies to DuckLake only. Standalone SQLite has a writer
semaphore and independent reader semaphore but no nested DuckDB lease; it
adopts the timeout and status semantics, not this resource-order rule.

### Coverage Reads and Worker State

Keep a durable backend coverage read at process start/restart and after a
mutation outcome that can change coverage. Cache coverage by dataset for the
duration of a shared projection cycle. A successful commit, invalidation
(including `reconcileCommittedTip`), retention, or reset invalidates only that
dataset's entry. This reduces steady permit pressure without weakening
crash/restart reconciliation or allowing a commit in dataset A to make dataset
B's cache stale.

Add a non-error `WAITING_FOR_WRITER` worker state (or explicit equivalent) that reports writer/DuckDB-capacity wait duration and reason. Reserve:

- `DISABLED` for a dataset disabled by effective archive configuration; it has
  no activation or worker work;
- `DEGRADED` for retryable backend/source failures after a mutation actually fails;
- `FAILED` for non-retryable configuration, schema, identity, or projection errors; and
- `PAUSED_CORE_LAG` only for the configured core-lag policy.

The status and console history view expose current writer holder, holder duration, waiter count, DuckDB bulk/total permit use, last wait warning, and last genuine mutation failure. No sensitive row data, transaction payload, or credential value is exposed.

### Maintenance and Shutdown

Maintenance checks for active snapshots before it acquires a DuckDB bulk lease
or the writer semaphore. It retains its existing time and rewrite budgets, and
must expose why it was deferred: active snapshot, writer wait, time budget, or
rewrite budget. Repeated active-reader deferral is an operational health signal,
not a silent no-op.

The archive close path is made exception-safe: an archive subsystem close failure
is recorded, but backend/hot-store/DuckDB close still executes in a
`finally`-equivalent path. `ArchiveSubsystem.close()` clears its executor
reference in that path, so a second close is idempotent rather than repeating a
previous timeout failure. This prevents a shutdown leak without force-closing an
active native session unsafely.

With history disabled, none of this constructs archive writers, DuckDB, SQLite, Flyway, hot store, staging, worker state, or threads.

## Consequences

### Positive

- Normal fair-semaphore contention no longer appears as a false archive failure.
- A real stuck writer, permit starvation, or JDBC failure is diagnosable with its holder and capacity context.
- DuckDB-reader saturation is visible independently from writer contention and
  cannot make a mutation hold the writer merely while obtaining capacity.
- Receipt/coverage/cursor replay and the existing archive↔hot-store crash protocol remain unchanged.
- The timeout, non-error wait-state, diagnostics, and fail-closed semantics
  apply to the standalone SQLite archive backend; DuckLake's resource-order
  rule does not.

### Trade-offs

- A long valid archive write can retain up to one bounded derived batch per projection while other projections wait; operational memory sizing is mandatory.
- A five-minute stuck threshold delays the error surface for a genuinely hung
  native operation. Diagnostics begin at 30 seconds and no data is falsely
  committed; at the threshold the in-memory derived batch is deliberately
  discarded and re-derived on retry rather than retained without bound.
- The implementation adds backend instrumentation and carefully tested resource-acquisition ordering, rather than a new scheduler.

## Rejected Alternatives

### New archive mutation coordinator

Rejected for now. The backend already supplies fair FIFO single-writer semantics, and service-cycle ordering already serializes epoch, promotion, retention, rollback, and maintenance after block projections join. A new queue does not resolve reader/bulk permit starvation and would risk enlarging the writer critical section. Reconsider only if measurements show a remaining ordering problem after this ADR is implemented.

### Increase the 30-second timeout only

Rejected. A longer threshold is necessary for real stuck-operation detection, but it is insufficient without distinguishing ordinary waits, recording the holder/capacity state, and avoiding writer-before-capacity acquisition.

### Multiple DuckLake writers

Rejected. DuckLake mutations require a single consistent catalog writer.

### Disable parallel projection derivation

Rejected. Parallel derivation is useful and bounded; durable mutation remains single-writer by design.

## Implementation and Verification

1. Add per-call-site measurements for writer wait/hold duration, DuckDB bulk
   and total permit wait/hold duration, active reader count, operation type,
   and batch row/block counts. Capture a mainnet replay baseline before tuning.
2. Implement the split warning/stuck thresholds and `WAITING_FOR_WRITER` status while preserving fair semaphore acquisition and fail-closed cursor behavior.
3. Reorder DuckLake lease and writer acquisition safely, with exact-once release
   tests for every failure, interruption, replay, backup, and shutdown path.
4. Add cycle-scoped, per-dataset, restart-safe coverage caching and validate it
   against a committed archive receipt with an intentionally absent control
   cursor and a concurrent reconciliation invalidation.
5. Improve maintenance-deferred and shutdown-close observability; fix the
   close path so backend cleanup cannot be skipped and repeated close succeeds.
6. Run DuckLake and SQLite conformance tests; concurrent-projection,
   saturated-reader, slow-write, epoch-boundary, restart/kill-9, and
   disabled-history tests; then a mainnet DuckLake soak.

Acceptance requires zero stuck-threshold failures during the soak and bounded
p99 writer, bulk-permit, and total-permit waits sized from the Phase-0 baseline.
The soak must deliberately exercise and record at least one warning-interval
diagnostic, proving the instrumentation and non-error wait state. A true
threshold failure must identify the waiting operation, current holder, holder
duration, DuckDB permit state, and preserve retry-safe no-cursor-advance
semantics. The disabled-history core regression matrix must remain unchanged.
