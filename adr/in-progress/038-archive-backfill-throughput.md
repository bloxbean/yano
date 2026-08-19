# ADR-038: Archive Backfill Throughput

## Status

Partially implemented. Reconciled with Amendments 3 and 4.

| phase | status |
|---|---|
| **Phase 0** — measurement harness and Appender feasibility | **Completed** |
| **Phase 2** — Appender-to-staging write path (shape (b)) | **Implemented and validated on mainnet** |
| **Phase 2b** — live-path anchor recheck | **Implemented and validated on mainnet** |
| **Locator-generation fix** (added after Phase 2 measurement) | **Implemented and validated on mainnet** |
| **Phase 2c** — bounded ordered UTXO block prefetch | **Deployed to mainnet at parallelism 2 and measured: slowest dataset 31.57 → 59.84 blocks/s. 6 of 7 acceptance criteria met, 1 missed** (Amendment 5) |
| **Phase 1** — configuration and profile | **Not started.** Its throughput rationale is measurably unsupported: DuckDB thread/memory settings showed no effect on the append path, and the production `UTXO_HISTORY` session already runs under the production DuckDB configuration |
| **Phase 3** — unified block pipeline and group-atomic storage transitions | **Blocked on C1–C5** (see *Prerequisite contract changes for Phase 3*). Must not begin until those contracts are agreed |
| **Phase 4** — retry without repeated derivation | **Not started, deprioritised.** Writer waits and discarded batches are now zero |
| **Phase 5** — transaction locator lifecycle | **Not implemented.** Remains the durable locator solution and still requires the generation-ordered delta journal; it is no longer the slowest-dataset throughput lever |
| **Phases 6 and 7** — commit granularity, concurrent per-table writers | **Not implemented** |

Outcome to date: slowest dataset **2.90 → 31.57 blocks/s (10.9x)**; all locator-fix
acceptance criteria passed. The provisional **≥50 blocks/s** target remains
**unmet** (`utxo_history` is slowest at 31.57) and is deliberately not lowered.
Details in Amendment 3.

## Date

2026-08-18

## Amendment 5 — Phase 2c production result (2026-08-19)

Evidence: `adr/reports/adr-038-phase2c-prototype-2026-08-19.md` §9, raw samples in
`adr/reports/adr-038-samples/adr-038-phase2c-mainnet-sampler.txt`.

Deployed at **parallelism 2**, measured over an equal 2.03 h window:

| dataset | before | after | gain |
|---|---:|---:|---:|
| `utxo_history` (slowest) | 31.57 | **59.84** | **1.90x** |
| `address_transaction` | 56.46 | **111.78** | 1.98x |
| `transaction` | 85.64 | 111.75 | 1.30x |
| `account_event` | 85.62 | 111.75 | 1.31x |

**The provisional ≥50 blocks/s target is met** by the slowest dataset at 59.84,
and the measured 1.90x closely tracks the 1.70x the synthetic benchmark predicted.
All datasets gained, not only UTXO: prefetching is UTXO-scoped, so the rest comes
from reduced shared-writer contention.

Against the original pre-Phase-2 baseline the cumulative improvement is
`utxo_history` **3.17 → 59.84 (18.9x)** and `address_transaction` **2.90 → 111.78
(38.5x)**.

Zero stuck pauses, writer waits, drain timeouts, reaper releases, locator rebuilds
and digest or duplicate-row issues; HEALTHY across all 25 samples; RSS 3.7–10.4 GB;
graceful restart recovered in 24.2 s with contiguous coverage and no rebuild.

**One criterion missed:** a single estimate underflow — one block in 438,350 whose
decoded fact graph measured 2.96 MB against the 2 MiB reservation. The mechanism
surfaced it and throttled submission exactly as designed, and overshoot stayed
bounded to active tasks. **The estimate was deliberately not raised**, since doing
so afterwards would hide the signal the criterion exists to produce; raising
`estimated-bytes-per-block` to 4 MiB is recommended as an explicit, approved
change. Reverting to serial is one configuration line and needs no rebuild.

The 18 `BlockSerializer ... redeemer does not have the same size` errors are
**pre-existing** — 70 occurrences in the pre-Phase-2c log, originating in yaci-core
and emitted by projection rather than prefetch threads — not a Phase 2c regression.

## Amendment 4 — Phase 2c: bounded ordered UTXO block prefetch (2026-08-19)

Full evidence: `adr/reports/adr-038-phase2c-concurrency-boundary-2026-08-18.md`
(boundary inspection) and `adr/reports/adr-038-phase2c-prototype-2026-08-19.md`
(prototype, correctness and benchmarks).

**Status: prototype complete, all local gates passed, opt-in and serial by
default, not deployed.**

### Design

An ordered prefetching `BlockArchiveSource` **decorator**, so `BlockArchiveWorker`
and `UtxoHistoryDataset` are unchanged. Block fetch and pure deserialization run in
parallel across blocks; UTXO derivation, the pointer resolver, undo records,
cursor, coverage and hot state stay strictly sequential in canonical order, and
transaction order within a block is untouched. Applied to the **UTXO catch-up
worker only**.

The boundary is sound because `YaciUtxoHistoryDecoder` is documented and verified
as resolver-independent, `BlockSerializer` is a stateless enum singleton, chain
reads are concurrent-safe, and `UtxoHistoryDataset` already **enforces** sequential
consumption — an ordering bug fails loudly at the dataset rather than corrupting
state.

### Memory budget is a conservative estimate, not a byte ceiling

`readCanonical` fetches and decodes together, so exact accounting is unavailable
through this interface. Reservation is per-block and taken on the submitting
thread in canonical order before task registration, so **no task blocks on a
memory permit and the permit-ordering deadlock is structurally impossible**.
Measured footprint is tracked; exceeding a reservation is surfaced, throttles
further submission, and bounds overshoot to active tasks. A strict in-flight block
count bound is retained.

### Lease safety

The block-body lease is released only after every registered task body has
genuinely terminated; `CompletableFuture` cancellation is never treated as proof
of termination. **On drain timeout the lease is not released** — the failure is
raised and a reaper retains pruning protection until tasks finish. Converting a
hang into an early lease release would drop pruning protection while a task is
still reading a body.

### Results

| config | blocks/s | vs serial | spread |
|---|---:|---:|---:|
| serial + cycle cache | 204.0 | — | 2.8% |
| prefetch p=1 | 203.8 | **1.00x** | 2.2% |
| prefetch p=2 | 346.6 | **1.70x** | 0.6% |
| prefetch p=4 | 531.0 | **2.61x** | 0.1% |

**Cache bypass and the prefetch machinery contribute exactly nothing (1.00x at
p=1)**; all gain above is genuine concurrency.

Correctness: 19 decorator tests, 4 end-to-end equivalence tests (byte-identical
rows, ordered digest, per-table counts, cursor, stateful call sequence and undo
records against serial, including under forced out-of-order completion and across
repeated runs), 7 configuration tests. Whole repository: **2,334 tests, 0
failures**, `:app:quarkusBuild` successful.

### Selected setting

**Parallelism 2**, the smallest value that clears the target: projected
`utxo_history` **≈53.7 blocks/s** (31.57 × 1.70) against the ≥50 provisional
target — a real but **thin 7% margin**. p=4 would project ≈82 blocks/s. p=2 also
perturbs `address_transaction` (56.46 blocks/s) least, which must stay above 50.

**The projection rests on a simulated-decode benchmark**: real mainnet CBOR bodies
were unavailable because both deployments hold their chainstate open. Decode cost
was modelled with CPU-bound work calibrated to the production profile (86% decode
here against ~82% measured). The ≥50 target therefore remains **provisional and
unproven in production**, and is not lowered.

### Not done

The real `UtxoHistoryDataset` with a live hot store was not driven end to end, and
multi-asset / datum / redeemer / collateral / pre-Conway pointer fixture variants
were not constructed — those distinctions live inside decoder output, which
prefetching does not alter, and equivalence was proven through the real worker
with a dependency-carrying dataset instead. Low residual risk, but recorded rather
than claimed.

## Amendment 3 — locator fix validated on mainnet; Phase 2 + 2b accepted (2026-08-18)

Full evidence: `adr/reports/adr-038-locator-fix-validation-2026-08-18.md`.
Raw samplers preserved under `adr/reports/adr-038-samples/`.

**Phase 2, Phase 2b and the locator-generation fix are accepted as successful.**

### Final equal-duration results (~2.0 h windows, same host and deployment)

| dataset | pre-Phase-2 | post-fix | gain |
|---|---:|---:|---:|
| `account_event` | 7.19 | **85.62** | 11.9x |
| `transaction` | 7.12 | **85.64** | 12.0x |
| `address_transaction` | 2.90 | **56.46** | 19.5x |
| `utxo_history` | 3.17 | **31.57** | 10.0x |
| **slowest dataset** | **2.90** | **31.57** | **10.9x** |

- Sustained archive writes ≈ **313k rows/s** (`utxo_history`), 286k (`address_transaction`).
- Stuck pauses **45 → 0**; writer waits **0** post-fix; DEGRADED samples 3/25 → **0/25**.
- Startup **455.6 s / 327.7 s precedents → 21.98 s**, with no locator rebuild.
- Shutdown **62 s → 1.5 s**, no "worker did not terminate" error.
- **All locator-fix acceptance criteria passed.**
- **≥50 blocks/s remains unmet**, because `utxo_history` is the slowest at 31.57.
  The target stays provisional and is **not** lowered.

### Caveats preserved deliberately

- **"No runtime rebuild observed", not "no generation gaps."** The gap-advance
  line is logged at DEBUG and DEBUG was not enabled, so gap frequency is unknown.
  What is supported: whatever gaps occurred were absorbed without any runtime
  rebuild — one rebuild total, at startup.
- **Read-side behaviour is proven by tests, not by the live probe.** The
  production tx-status request returned 503 while datasets were `catching_up`, so
  it may have short-circuited before consulting the locator. The binding proof is
  `pinnedHistoricalLookupNeverChangesLocatorGeneration` and
  `concurrentPinnedReadsCannotOscillateGeneration`.

### Where the remaining cost sits

`TRANSACTION` sessions spend ~2.65 s mean (p95 4.98 s) inserting ~25k locator
entries — 96% of that session. It is **not** the limiting factor for the
slowest-dataset target: `transaction` already runs at 85.64 blocks/s and writer
waits are zero. SQLite locator-ingestion tuning is therefore **deferred**, and the
next investigation targets `utxo_history`.

### Phase status after this amendment

- **Phase 1** — throughput rationale remains unsupported; not started.
- **Phase 3** — blocked on C1–C5.
- **Phase 4** — deprioritised: writer waits and discarded batches are now zero.
- **Phase 5** — remains the durable locator lifecycle solution, but is not the
  current slowest-dataset throughput lever.

## Amendment 2 — the transaction locator is the real production bottleneck (2026-08-18)

Phase 2 shipped to mainnet and **worked**: append, logical-key verification,
staging copy and the DuckLake commit together fall under 0.4 s per session. But
sustained throughput got *worse*, not better, and the instrumentation showed why.

Measured on mainnet after the Phase 2 restart, across every dataset:

```
TRANSACTION   rows=3045   total=342.963s  copy=0.002s commit=0.006s LOCATOR=342.947s (100.0%)
ACCOUNT_EVENT rows=265    total=340.205s  copy=0.001s commit=0.006s LOCATOR=340.193s (100.0%)
UTXO_HISTORY  rows=115197 total=342.907s  copy=0.050s commit=0.012s LOCATOR=342.578s (99.9%)
```

UTXO coverage advanced 600 blocks in 4,514 s — **~0.133 blocks/s**, against a
~3.17 blocks/s baseline. 45 stuck pauses in the first 90 minutes.

**The locator is ~99.9% of write-session time**, including for datasets that
supply *zero* locator entries.

### Mechanism — proven, and not the one first hypothesised

The leading hypothesis was a read-triggered backward rebuild: a lookup through an
older pinned generation calls `rebuildIfRequired` and rewinds the global locator,
after which the next writer rebuilds forward. That path is **real and was fixed**,
but it is **not** what is happening on this deployment — no tx-status lookups are
being issued at all.

The active mechanism is generation gaps on the writer side:

- `advance(duckLake, generation, entries)` required the locator to sit at exactly
  `generation - 1`, and otherwise performed a **full `chain_transaction` rebuild**;
- DuckLake generations advance for **maintenance snapshots** and for **other
  datasets' commits**, neither of which changes `chain_transaction`;
- maintenance runs every 300 s while commits took ~340 s, so a gap was almost
  always present, and **nearly every commit rebuilt the entire locator**.

Generation progression confirms it: ~5 generations per ~5-minute commit cycle,
with maintenance completing on the same cadence.

**This also closes the unexplained gap from Phase 0.** The isolated benchmarks ran
against a fresh locator with no maintenance, so locator time measured ~0.002 s;
production pays ~340 s. That, not row width or contention, is the missing 22–40x.

### Minimal correctness-safe fix (implemented)

1. **Reads never mutate the locator.** `block(connection, pinnedGeneration, hash)`
   no longer calls `rebuildIfRequired`. The locator is strictly a hint — verified,
   not assumed: `findTransaction` already issues an authoritative full-range query
   on an absent hint and already falls back when a hinted block holds no row.
2. **Forward generation gaps are absorbed in O(entries).** Gaps come from
   operations that do not change `chain_transaction`, and every TRANSACTION commit
   applies its own entries, so no entry can be skipped by a gap.
3. **Backward moves and an uninitialised locator still trigger fail-safe rebuild** —
   gaps are not silently skipped.
4. Diagnostics added: rebuild count, reason, duration, row count, and
   generation before/after. No hashes or row contents are logged.

### Consequences for the ADR

- **Phase 2 is validated but was not the binding constraint.** Its ~3.3–4.2x
  write-session gain is real and now visible; it simply exposed a larger cost.
  Appender speed must not be conflated with durable block throughput.
- **Phase 5 is promoted from "availability fix" to the throughput-critical item.**
  The locator, not the append path, governs production throughput.
- The archive/locator seam remains a **Phase 5 prerequisite**: DuckLake commits
  before the SQLite locator update, and the replay path returns before
  `updateTransactionLocator`, so a failed locator update is not reconciled by
  replay. With forward-gap tolerance this degrades to a stale *negative* — safe,
  because the caller then queries authoritatively — but it is not repaired. This
  is recorded, not claimed as fixed.

## Amendment 1 — Phase 0 outcome (2026-08-18)

Phase 0 has run. Evidence: `adr/reports/adr-038-phase0-baseline-2026-08-18.md`.
This amendment records decisions and withdrawals that supersede the phase text
below where they conflict.

1. **Phase 2 selects shape (b)** — DuckDB Appender into native staging tables,
   followed by the existing `verifyLogicalKeys()` and `INSERT ... SELECT`
   publication. Measured at ~874k–953k rows/s against ~37.7k for today's
   prepared-statement batches, and **~2.5x faster than direct-to-DuckLake
   appending (shape (a))**. It is also the only shape that preserves the write
   contract unchanged, because logical-key verification queries the staging
   tables before publication. Shape (a) is confirmed feasible and asserted in
   test, but is not the Phase 2 path.
2. **The ~23x figure is append-technique-only.** It compares how rows enter
   DuckLake, in isolation, on one table. It is **not** node throughput and must
   never be quoted as such.
3. **The isolated write-session Amdahl bound is ~4.3–4.6x.** Append is ~82% of a
   production-shaped `UTXO_HISTORY` session, so ~23x on that stage yields roughly
   4.3–4.6x on the session. Derive, writer wait and the hot commit sit outside the
   session, so the node-level gain is lower still.
4. **Phase 1's "3–8x" expectation is withdrawn**, together with its rationale that
   DuckDB thread and memory limits throttle the append path. Measurement shows
   1 thread/128MB matching 8 threads/8GB on that path, and the production
   `UTXO_HISTORY` session running at ~17.5k rows/s *under the production DuckDB
   configuration*. The conservative defaults and the opt-in profile remain
   justified as prudent sizing, not as a throughput measure, pending end-to-end
   evidence from another component.
5. **Phase 3's motivation is architectural alignment, single decode and contention
   removal — not fixed commit cost.** Per-commit fixed overhead measures ~75 ms,
   so collapsing four commits into one saves ~225 ms against a ~180 s commit
   (~0.1%). The same correction applies to Phase 6.
6. **≥50 blocks/s remains unvalidated.** Phase 0 did not demonstrate it, and the
   remaining production gap is not yet attributed. Per the target policy this is a
   matter for an explicit decision, not an automatic revision. **Phase 2 proceeds
   on its own merits** — a measured, contract-preserving component gain — and not
   on the target.

Additionally withdrawn by measurement: the claim that the staging round-trip is
"pure duplicated work" (staging is *faster* than direct 256-row inserts), and the
claim that quadratic placeholder construction is a meaningful cost (~1.0x).

## Context

[ADR-034](034-optional-asynchronous-history-archive.md) placed historical projection outside the authoritative node path. [ADR-036](036-pluggable-hot-history-store.md) made the hot store pluggable and reduced the design to a single sequential `CATCHING_UP → LIVE` track. [ADR-037](037-archive-writer-contention-and-stuck-operation-handling.md) separated ordinary writer contention from genuinely stuck operations, and its mainnet validation confirmed that fix in production.

That validation also established that the archive cannot complete a mainnet backfill in an acceptable time. Measured over the full ADR-037 run on a 16-core / 128 GB host:

| dataset | blocks/s | days to tip |
|---|---:|---:|
| `account_event` | 7.19 | 11.3 |
| `transaction` | 7.12 | 11.4 |
| `utxo_history` | 3.17 | 26.6 |
| `address_transaction` | 2.90 | 28.9 |

`/history/watermark` gates on the slowest selected dataset, so **2.90 blocks/s** is the rate that determines when history becomes usable. A comparable projection store (yaci-store) sustains 200–300 blocks/s. The gap is **~70–100x**, and the projection is structurally optimistic: transaction density rises as the backfill advances (22 → 50 tx/block over ~100k blocks measured), and with a fixed rows-per-commit cap, blocks per commit falls correspondingly.

`adr/reports/archive-throughput-bottleneck-2026-08-18.md` records the diagnosis. The decisive measurement is that a `utxo_history` write session holds the writer for 300+ seconds to commit ~125,000 rows — approximately **400 rows/s inside the write session**, against a DuckDB bulk-load capability of 100k+ rows/s. Only ~0.72 GB was written in 2.70 h (≈74 KB/s), so the workload is not I/O-bound. The deficit is in the write path, not in derivation, disk, or the canonical block source.

Six contributing causes were identified by direct source reading plus deployment measurement:

1. **DuckDB is throttled to 1 thread and 128 MB** (`max-total-memory: 256MB`) on a 16-core / 128 GB host — 6% of cores, 0.2% of RAM (`DuckDbManager.java:176-177`).
2. **Rows are inserted through JDBC in 256-row batches** — 488 parsed SQL statements per commit — and the placeholder string is built with a quadratic `reduce((l, r) -> l + ", " + r)`. The DuckDB Appender API is not used anywhere in the repository (`DuckLakeWriteSession.java:194-210`).
3. **Every row is written twice**: into a TEMP staging table, then `INSERT INTO target SELECT * FROM staging` (`:172, :218`).
4. **Each block is decoded roughly four times.** `decodedBlockCacheHits: 10165` against `decodedBlocks: 174835` — a 5.8% hit rate, because the four datasets have drifted ~240,000 blocks apart and never request the same block within a cycle.
5. **All four datasets serialize behind one writer.** `ducklake-writer` is hard-coded to a single permit (`DuckLakeHistoryArchiveBackend.java:140`); `SqliteHistoryArchiveBackend` likewise declares `new Semaphore(1, true)` (`:68`).
6. **Per-commit fixed cost is amortized over only 1,000 blocks**: a DuckLake snapshot, inserts into three metadata tables, Parquet creation at `target-file-size: 4MB`, and a transaction-locator update into a 4.7 GB SQLite opened with `PRAGMA synchronous=FULL`.

Causes 4, 5 and 6 live in `archive-core` and the service layer, not in a backend. Switching from DuckLake to SQLite would therefore not resolve them; a comparable ceiling is expected on either backend.

Separately, ADR-034 review finding **L2** was confirmed in production: `DuckLakeTransactionLocator.rebuildIfRequired` runs a full rebuild inside the backend constructor, which cost **4h05m of unavailable startup** on this deployment. The same path is reachable from `/api/v1/txs/{txHash}/status` on a generation mismatch, which currently makes that endpoint unsafe to expose. This is both a production blocker and a per-commit throughput cost, so it is scheduled here.

No core UTXO, account, reward, governance, ledger, chainstate, block-apply, or rollback behaviour is part of this decision.

## Decision

Improve archive backfill throughput in ordered phases, each independently shippable and independently revertible, subject to a fixed set of correctness and resilience invariants that no phase may weaken.

### Non-negotiable invariants

Every phase below must preserve all of the following. A phase that cannot is rejected, not adjusted.

1. **Deterministic job identity.** `ArchiveJob.deterministic(network, dataset, projectionVersion, range, anchor, kind)` remains the job ID. Retrying the same logical work must produce the same ID.
2. **Digest-verified idempotent replay.** A committed job replayed must be accepted only after both row count *and* the SHA-256 ordered digest match; a differently shaped retry must fail loudly rather than double-write. The digest must be computed from the same ordered row stream regardless of the physical insert mechanism.
3. **Progress is durable-write-ordered, in two distinct cases.** An earlier draft stated this as a single rule — "the hot cursor, resolver state, and coverage advance only after a durable archive receipt" — which was too broad. It describes the catch-up path but contradicts near-tip `LIVE` processing, where `LiveBlockArchiveWorker` advances hot facts and the live cursor with **no archive write and no receipt at all**. The invariant has two cases:
   - **3a. Receipt-before-archival-progress.** Archive coverage, the catch-up cursor, and finalized-promotion cleanup advance only after all durable archive receipts. A failed or abandoned batch never advances any of them.
   - **3b. Atomic near-tip progress.** **For each dataset before Phase 3, and across the whole group after C2**, unfinalized hot facts, resolver state, undo records, and the corresponding live cursor advance atomically in the hot store. They advance no archive coverage and require no archive receipt. This is what makes rollback of unfinalized data possible at all. Stating it per-dataset first matters: the group live cursor and the group-atomic hot transaction do not exist until C2, so a group-only phrasing would require Phase 3 behaviour of phases that precede it.
4. **Anchors re-checked immediately before the durable commit.** First and last canonical anchors are verified before derivation and again immediately before the commit that makes the batch durable, with intra-batch parent-chain verification. A fork switch during a batch aborts rather than committing a mixed-fork range.
   - The **archive-write clause** — recheck inside the write session before `commit()` — is preserved today by `BlockArchiveWorker.recheck(first)` / `recheck(last)`, and is the clause Phases 0–2 already satisfy.
   - The **near-tip clause** — recheck immediately before the hot commit — is **not** satisfied today: `LiveBlockArchiveWorker` derives its batch and calls `hot.applyBlocks(…)` with no equivalent recheck at the commit boundary. It is therefore **new work, delivered by Phase 2b**, not an invariant the earlier phases inherit.
5. **Gap-explicit coverage.** Coverage remains sorted, non-overlapping, complete ranges. Gaps are never presented as empty history; reads outside coverage return an explicit refusal, never an empty success.
6. **History failure never degrades core.** Archive faults remain contained to history; core sync, ledger, and rollback are unaffected.
7. **Independent outpoint resolution.** Address and UTXO history resolve senders from the archive's own durable outpoint state, never from the live UTXO store.
8. **Fail-closed identity and schema checks** on open, and exact-undo rollback semantics, are unchanged.
9. **Bounded memory.** Every phase must state its peak-memory effect; no phase may make batch memory unbounded.

### Phase 0 — Measurement harness

**Goal.** Make throughput a measured, regression-guarded property before changing anything.

**Changes.** A repeatable backfill benchmark over a fixed block range on a devnet- or preprod-sized archive, reporting blocks/s and rows/s per dataset, plus a micro-benchmark of the insert path in three variants: current 256-row JDBC batches, the same with `StringBuilder`, and the Appender API. Record baseline numbers in the ADR report directory.

**The insert micro-benchmark must target a DuckLake-attached table (`history_lake.*`), not a plain DuckDB table.** This is the phase's most important question, because Phase 2 depends on the answer. The Appender's native path targets DuckDB tables; whether JDBC `createAppender` can write directly to a DuckLake-attached catalog table through the extension is **not established**. Benchmark both shapes:

- **(a) direct** — Appender straight to `history_lake.<table>`, if supported;
- **(b) via a native staging table** — Appender into a plain DuckDB temp table, then one `INSERT INTO history_lake.<table> SELECT * FROM staging`.

Shape (b) is the current staging round-trip that Phase 3 proposes to remove. If (a) is unsupported, staging is not waste but the *vehicle* for the fast path, and Phases 2 and 3 must be read accordingly.

**Expected gain.** None directly. This phase exists so that later phases can be attributed and so a regression is detectable.

**Correctness.** No production code changes.

**A devnet- or preprod-sized archive is not sufficient on its own.** It will not reproduce current mainnet transaction density (25.9 tx/block measured at block ~6.7M and rising), the 4.7 GB transaction locator, the ~4,000 existing Parquet files, or large-catalog metadata and maintenance costs. Phase 0 must therefore include, in addition to the small reproducible benchmark:

- at least one **dense mainnet-derived block fixture**, drawn from a high-density range rather than early history;
- a **realistic catalog and data-file cardinality** test, so per-commit metadata cost is measured at production scale rather than on an empty catalog.

**Acceptance SLO for the campaign.** Phases must be measured against a stated target, not merely record whatever gain they happen to produce. Without a pinned workload definition, different phases can report incomparable "50 blocks/s" numbers, so the SLO fixes all of the following:

| dimension | definition |
|---|---|
| threshold | **≥50 blocks/s sustained** |
| measured as | **the slowest enabled block dataset**, not group aggregate — it is what gates the watermark |
| datasets enabled | all four block datasets, with the full UTXO table set (`transaction_outputs`, `transaction_output_assets`, `transaction_inputs`, `transaction_datums`, `transaction_redeemers`) |
| fixture | a fixed dense mainnet block range, with its measured tx/block density recorded alongside the result |
| catalog | production-sized catalog and data-file cardinality, not a fresh one |
| cache state | cold start, warm steady-state reported separately |
| elapsed-time boundary | wall clock from batch read to durable cursor — **the slowest dataset's cursor before Phase 3, the group cursor after** — **including** locator update and hot-state commit |
| resource ceiling | stated JVM heap and RSS ceiling on the reference host, with the high-throughput profile named |

Individual phases report their contribution against this same definition.

**Target policy.** The ≥50 blocks/s figure was chosen before several review rounds reduced the phases meant to deliver it — Phase 1's defaults became conservative, Phase 3's per-commit saving proved smaller than 4x and its decode saving lands on derivation rather than the bottleneck, Phase 3 became blocked on C1–C5, and Phase 5 gained per-commit journal work. It is therefore **provisional**, and governed by this policy:

> The provisional campaign target is **≥50 blocks/s under the named high-throughput profile**, measured on the pinned dense-mainnet workload and resource ceiling above. Phase 0 validates its feasibility. **Failure to validate triggers an explicit decision to change the design, the resources, or the target — it does not automatically lower the target.** The conservative default profile is measured separately and **must not regress**; no numeric SLO is set for it before Phase 0, and its target is adopted from Phase 0 evidence by ADR amendment. The 200–300 blocks/s yaci-store result is a **comparator, not a Yano acceptance target**.

Two consequences worth stating plainly. First, revising the number is a product and design decision recorded as an amendment, not a benchmark side effect — otherwise the target silently becomes whatever the implementation happens to achieve. Second, the SLO is a *tuned-profile* claim; because the shipped default is deliberately conservative, "SLO met" must never be reported as the throughput a default installation receives.

**The single largest determinant is Phase 2.** Reaching 50 blocks/s from the measured 2.90 is ~17x, and Phase 2 is the only phase that plausibly delivers most of that alone, since it attacks the measured ~400 rows/s inside the write session directly. Phase 0's Appender-against-DuckLake feasibility question is therefore the pivotal input to validating the target at all.

**Exit criteria.** Reproducible baseline within ±10% across three runs; relative weight of causes 1 and 2 established empirically rather than by inference; dense-fixture baseline and RSS ceiling recorded; Appender-against-DuckLake feasibility (shapes (a) and (b)) answered.

### Phase 1 — Configuration right-sizing

**Goal.** Stop under-provisioning DuckDB and stop paying per-commit overhead every 1,000 blocks.

**Changes.** Defaults and one new documented profile; no code.

**Product defaults stay conservative.** An earlier draft proposed 8 GB per bulk connection, 24 GB total and 32 GB temp — appropriate for the 16-core / 128 GB reference host, but **unsafe as application defaults**, since Yano must also run on small VMs and in memory-capped containers. "Safe on a 128 GB host" is not a product default. Defaults therefore move modestly, and every value remains operator-configurable:

| setting | current | new default | rationale |
|---|---|---|---|
| `duckdb.bulk-catch-up.threads` | 1 | **4** (literal) | 1 thread is the pathology; 4 is safe on a 4-core VM |
| `duckdb.bulk-catch-up.memory-limit` | 128MB | **512MB** | modest, container-safe |
| `duckdb.steady-state.memory-limit` | 128MB | **256MB** | |
| `duckdb.max-total-memory` | 256MB | **1GB** | must cover queries × steady + bulk × bulk |
| `duckdb.max-temp-directory-size` | 2GB | **4GB** | |
| `worker.max-blocks-per-batch` | 1000 | **2000** (see limit below) | |
| `worker.max-rows-per-batch` | 250000 | **1000000** | |
| `archive.ducklake.target-file-size` | 4MB | **64MB** | |

**A separate high-throughput profile** (for example `history-highmem`) carries the aggressive values — 8 GB bulk, 24 GB total, 32 GB temp, higher thread count — documented for operators with the RAM to spend, and used for the Phase 0 reference-host benchmarks. It is opt-in, never the default.

The thread value is a **literal**, not `cores − 2`. An earlier draft specified `cores − 2, capped`, which is resource-aware computation and contradicted this phase's "no code" scope; computed sizing belongs to the deferred sizing phase below.

**`max-concurrent-bulk-jobs` is not raised.** An earlier draft proposed 1 → 2. This ADR's own rejected alternative explains why it cannot help: capacity is acquired *before* the writer, and the writer permit remains one, so a second bulk worker merely occupies capacity while queueing for the writer. Raise it only if Phase 0 demonstrates a benefit. `max-concurrent-queries` stays at 2 for the same reason, preserving the `maxConcurrentBulkJobs < maxConcurrentQueries` invariant.

`worker.max-rows-per-batch` is externally bound (`YanoPropertyKeys.WORKER_MAX_ROWS`), so it remains a configuration change.

**Resource-aware sizing is explicitly deferred to its own phase.** An earlier draft said values "must be derived from detected host resources", which is not a configuration change at all: current parsing uses literal fallbacks, and automatic CPU/RAM derivation requires code plus policy decisions about container limits, JVM heap interaction, concurrent connection budgets, and operator override precedence. That phase should define explicit formulas and caps and may then replace both the conservative defaults and the profile.

**The `stuck-operation-seconds` change is withdrawn.** Raising it from 300 to 1800 does not improve throughput — it only weakens stuck detection, and it would make "no stuck pauses" an artificial exit criterion for this phase. The real fixes for wasted waits are Phases 3 and 4. Revisit only if measurement later justifies it.

**`max-blocks-per-batch` cannot safely be raised far in this phase.** `CycleCachingBlockArchiveSource` is constructed with `maximumEntries` only — **entry-count accounting with no size accounting**, which is ADR-034 review finding L. Each of the two cycle-caching sources holds `maxBlocksPerBatch × 2` decoded blocks, so a bump to 8000 would cache 16,000 decoded blocks per source with no byte ceiling; on dense blocks that is potentially gigabytes of uncounted heap, violating invariant 9. This phase therefore caps the bump at 2000 pending measurement. **Reaching the larger batch sizes assumed by Phase 6 requires the cache size-accounting fix, which is code and belongs to that phase, not this one.**

**`target-file-size` affects only newly written files.** Mainnet measurement found **zero** data files attributed to `merge_adjacent` (compaction) snapshots, and maintenance repeatedly logged `DuckLake maintenance exceeded its configured budget` — so compaction has never completed a rewrite and the ~4,000 existing small Parquet files persist regardless of this setting. **Decision: the existing files are explicitly out of scope for this phase**, and `target-file-size` applies to newly written files only. Raising the maintenance budget is a separate change with its own risk — maintenance holds the same writer, so a larger budget lengthens the window in which it blocks projections — and it should be made on measured evidence rather than bundled into a defaults bump. The backlog of small files is recorded as a known condition of the existing deployment, to be addressed when maintenance itself is revisited.

**Expected gain (estimate, pending Phase 0).** 3–8x, and lower than that if the `max-blocks-per-batch` cap above binds. This does not close the gap, because cause 2 is single-threaded SQL parsing and binding: additional DuckDB threads do not parallelize 488 sequential `INSERT` statements.

**Correctness.** No invariant is touched. Larger batches increase peak heap, since `runBatch` materializes all decoded blocks and all derived rows before opening the write session — invariant 9 requires that the new defaults be validated against a measured heap ceiling, and that adaptive halving on capacity failure still applies.

**Exit criteria.** Measured gain recorded; no increase in stuck-operation pauses; peak RSS within budget on the reference host.

### Phase 2 — Write path: Appender and the quadratic build

**Goal.** Remove the dominant in-session cost.

**Changes.** Confined to `DuckLakeWriteSession`.

- Replace `INSERT ... VALUES` batching with the DuckDB Appender API, in whichever shape Phase 0 establishes as supported — **(a)** appending directly to the DuckLake table, or **(b)** appending to a native DuckDB staging table followed by a single `INSERT ... SELECT`.
- Replace the quadratic `reduce` placeholder construction with `StringBuilder`, or precompute it per (table, batch size) since it is constant. Ship this even if Appender is deferred.
- Raise `STAGING_BATCH_SIZE` from 256 (moot once Appender lands).

**Expected gain (estimate).** 10–30x on the insert path — the single highest-leverage change in this ADR. The estimate is expected to hold under either shape, because both remove the per-statement SQL parsing and the ~1.25M `setObject` calls per commit; shape (b) retains one bulk column-wise copy, which is cheap relative to what it replaces.

**Correctness.** Invariant 2 is the one at risk and must be explicitly protected: the ordered digest must continue to be computed from the logical row stream in append order, independent of the physical write mechanism. A conformance test must assert that a job written via Appender produces a byte-identical digest to the same job written via the previous path, and that replay verification still rejects a differently shaped retry. Appender failure semantics must map onto the existing abort path so that invariant 3 holds.

**Exit criteria.** Digest equivalence proven across both mechanisms; replay-rejection tests unchanged and passing; measured insert-path gain recorded.

### Phase 2b — Live-path anchor recheck (independent correctness fix)

**Goal.** Close an existing fork-switch window on the near-tip path, and thereby satisfy invariant 4's near-tip clause.

**This is not a throughput change** and does not depend on any other phase. It is listed here because invariant 4 would otherwise require behaviour that no phase delivers.

**The gap.** `BlockArchiveWorker` re-reads the first and last canonical references immediately before committing (`recheck(first)` / `recheck(last)`, `:268-275`). `LiveBlockArchiveWorker` does not: it reads the batch, derives rows, and calls `hot.applyBlocks(…)` (`:83`) — or `commitLiveBatch` for stateful datasets — **with no anchor recheck at the commit boundary**. If the chain switches fork between the batch read and the hot commit, hot facts for a now-orphaned range are made durable. Rollback would subsequently correct this, so it is not a corruption risk, but it is an avoidable window and it is inconsistent with the catch-up path. It is a concrete instance of the catch-up/live divergence recorded as ADR-034 review finding F.

**Changes.** Re-read and compare first and last canonical references immediately before the hot commit on the live path, aborting the batch on mismatch, mirroring the catch-up worker's `recheck`.

**Correctness.** Strictly tightening: it can only abort batches that would otherwise have committed against a changed anchor. No cursor, coverage or receipt semantics change.

**Exit criteria.** An anchor-change test on **both** live commit branches — stateful datasets commit via `commitLiveBatch`, stateless ones call `hot.applyBlocks` directly — each proving the batch aborts without advancing the live cursor or writing hot facts, and the stateful branch additionally proving `abortBatch()` runs after the failed recheck. Testing both is what forces the recheck to sit **before** the branch rather than accidentally protecting only one path.

**Sequencing.** Ship independently and early. Phase 3 subsumes it — the unified worker replaces both workers — but Phase 3 is blocked and this window exists now.

### Phase 3 — Unified block pipeline and group-atomic storage transitions

**Goal.** Replace independently scheduled per-dataset cursors with one block pipeline in the yaci-store shape: **read and decode a block batch once, derive every enabled block dataset from it in parallel, and make each storage transition group-atomic** — so that datasets advance together rather than drifting apart.

An earlier draft titled this "single cursor, one commit" and described every batch as one transaction advancing one cursor. That framing is superseded: the corrected design has separate catch-up and live group cursors, a distinct finalized-promotion boundary, archive transactions for operations 1 and 4, and hot-store-only transactions for operation 3.

This is the structural change of this ADR. It is not an optimization of the current design — it removes the conditions that produce causes 4, 5 and 6 simultaneously, rather than mitigating each.

**Why it dominates the alternative.** An earlier draft of this phase proposed merely *aligning* the four datasets' batch ranges so the per-cycle decode cache would hit. That is strictly worse: it depends on scheduling luck to reach a ~75% cache hit rate, and it leaves four separate commits, four writer acquisitions, and four sets of per-commit fixed cost in place. A unified batch makes single-decode structural rather than probabilistic, and pays the per-commit cost **once instead of four times**.

**Scope decision — the unified group governs the whole `CATCHING_UP → LIVE` lifecycle, not just backfill.**

An earlier draft unified only the `CATCHING_UP` cursor. That was a structural error. `liveWorkers` is a per-dataset `EnumMap`, each entry holding its own `LiveBlockArchiveWorker` invoked as `runBatch(dataset, start, end)` (`HistoryArchiveService:103, :1624-1625`), and `transitionCaughtUpDatasets` promotes datasets **individually** (`:848-851`). So under a backfill-only unification, every benefit — the group cursor as watermark, maintained alignment, absence of block-dataset writer contention, one cursor replacing four — would **evaporate at promotion**, precisely when the node enters the steady state it occupies for years.

Phase 3 therefore covers both phases of the ADR-036 lifecycle: one group cursor through catch-up, a **group lifecycle handoff** to live, and one group live cursor at the tip. The handoff becomes a group transition rather than a per-dataset one. This enlarges the phase considerably — and, as the next subsection establishes, the live half is a two-stage storage pipeline rather than a second copy of the catch-up path. Both are further reasons it is not approved with the rest.

**LIVE is a two-stage storage pipeline, not one archive-write path.** A previous draft said all datasets append through one archive write session "on both the catch-up and live paths". That is wrong, and the distinction is fundamental rather than incidental: **unfinalized rows must never enter the immutable archive.** Verified in source:

- `LiveBlockArchiveWorker` holds **no backend reference at all** — only `HotHistoryStore hot` — and its sole write is `hot.applyBlocks(dataset, updates, …, ArchiveTrack.LIVE)` (`:83`). Near-tip projection touches the hot store only.
- The archive write happens later, in `promoteLiveRows` (`HistoryArchiveService:1477`): it computes `promotableEnd = Math.min(finalized, live.coordinate())`, opens a pinned `controlStore.snapshot()`, calls `backend.begin(job)` with kind `hot-promotion-v1`, streams rows out of the hot snapshot, commits, then `cleanupPromotedRows` deletes the promoted hot facts.

**Phase 3 therefore specifies four distinct operations, not one:**

| # | operation | storage effect |
|---|---|---|
| 1 | **Catch-up batch** | decode once → **group archive commit** (C1) → **group hot resolver/cursor commit** (C2) |
| 2 | **Lifecycle handoff** | atomically move the group `CATCHING_UP` → `LIVE`; no row movement |
| 3 | **Near-tip projection** | decode once → atomically write all datasets' rollbackable hot facts, resolver undo and the **group live cursor** (C2). **No archive write occurs here.** |
| 4 | **Finalized promotion** | read a pinned hot snapshot → **group archive commit** (C1) → atomically clean exactly the promoted hot facts |

**Two coordinates necessarily coexist during LIVE**, and the ADR must name both:

- the **group live projection cursor**, at or near the chain tip;
- the **finalized archive coverage / promotion boundary**, which lags it by the finality window.

One group cursor can replace the four per-dataset *live* cursors, but it **cannot also represent finalized archive coverage**. Any claim that collapses the two is wrong.

**C1 and C2 scope accordingly:** C1 (multi-job archive session) applies to operations 1 and 4 only. C2 (group-atomic hot write) applies to operations 1 and 3. The earlier statement that "C1–C4 apply equally to the live path" is withdrawn.

**Changes.**

- One group live cursor replacing the four per-dataset live cursors, plus one group catch-up cursor — and a distinct, explicitly separate finalized-promotion boundary.
- Lifecycle handoff becomes atomic for the group: all enabled block datasets transition together, or none do.
- Read and decode the batch once; verify anchors and the parent chain once, in operations 1 and 3 alike.
- **Derivation stays parallel across datasets** — it is pure CPU work over an immutable decoded batch, with no shared writer — so the existing bounded projection executor is retained and the parallelism that matters is preserved.
- Group the archive commit in operations 1 and 4, and group the hot write in operations 1 and 3.
- Epoch-cadence datasets (`reward`, `epoch_stake`, `drep_distribution`, `ada_pot`, `governance_proposal_status`) are **out of scope**: they are per-epoch, not per-block, and keep their existing path.
- The unified worker inherits the divergences ADR-034 review finding F recorded between the catch-up and live workers (Byron EBB bridge, pruning-safe canonical reference, source lease, adaptive batching), since one worker replaces both.

**Expected gain.** Stated as **component reductions, which do not multiply.** The Context establishes that the decisive deficit is in the write path, not derivation; savings compound only to the extent that both costs dominate sequential wall time. An earlier draft implied roughly 16x by multiplying two 4x figures — that was wrong and is withdrawn.

| component | reduction | note |
|---|---|---|
| block decode | ~4x → 1x | structural, not cache-dependent; affects derivation input, which is **not** the measured bottleneck |
| DuckLake snapshots | 4 → 1 per batch | genuine |
| Parquet flushes | per-table, once per batch | genuine |
| locator update | 4 → 1 per batch | genuine |
| commit records, digests, coverage rows | **still 4** | per-dataset jobs are retained for correctness, so these do not collapse |

That last row corrects a second error in the earlier draft, which claimed "one set of metadata inserts". Retaining per-dataset jobs is required by invariants 1, 2 and 5, and it necessarily retains four commit records, four digests and four coverage updates. **The per-commit saving is therefore smaller than 4x.** Only an end-to-end measurement against the Phase 0 baseline may be quoted as this phase's gain.

**Contention.** This phase **eliminates contention among block-dataset backfill workers** — the case that produced 246 timeouts and 39 stuck pauses on mainnet. It does *not* eliminate writer contention generally: the same writer is still acquired by epoch archive commits, invalidation, retention, maintenance (`DuckLakeHistoryArchiveBackend:446`) and catalog backup. An earlier draft claimed "no queue" and that the stuck-wait problem was eliminated; that was too broad and is withdrawn. **Phase 4 therefore remains operationally useful, not merely a safety net**, and the ADR-037 wait/watchdog machinery stays in place for the remaining contenders.

#### Prerequisite contract changes for Phase 3

Phase 3 as first drafted promised atomicity that **neither the archive backend nor the hot-store contract can currently provide**. Five contract changes are required. Phase 3 must not begin until they are agreed, and each needs implementation plus conformance tests on **both** backends.

**C1 — Multi-job backend session.** Today `ArchiveBackend.begin(ArchiveJob)` accepts exactly one job and `ArchiveWriteSession.commit()` returns exactly one `ArchiveReceipt`. `DuckLakeWriteSession` derives `allowedTables` from `ArchiveSchemas.schema(job.dataset())`, so a session **physically cannot accept another dataset's tables**, and `append` additionally rejects a row whose `archive_job_id` differs from the active job. A backend-neutral group contract is required, roughly:

```java
ArchiveGroupWriteSession beginGroup(List<ArchiveJob> jobs);
void append(ArchiveDatasetId dataset, ArchiveRow row);
Map<ArchiveDatasetId, ArchiveReceipt> commit();
```

with per-dataset ordered digests computed independently inside one backend transaction, and the existing cross-backend conformance fixtures extended to cover group sessions.

**C2 — Group-atomic hot-state write.** `RocksDbHotHistoryStore.applyBlocks(ArchiveDatasetId dataset, …)` is dataset-scoped, validates `progress.dataset() == dataset`, and commits one `WriteBatch` per dataset. Four datasets therefore means four sequential hot writes, and a crash between them leaves archive coverage aligned while some resolver states and cursors are advanced and others are not. Required: a single hot-store transaction spanning all datasets, atomically applying resolver mutations, receipts, undo records, per-dataset progress and the group cursor. **This is implementable on both engines** — RocksDB keys are already dataset-prefixed via `dataKey(dataset, …)`, so one `WriteBatch` can span them, and the SQLite hot store is already a single transaction.

**C3 — Group-atomic invalidation and retention.** The backend invalidates one dataset at a time. A crash after invalidating two of four would break the alignment invariant this phase introduces, so the earlier claim that "rollback simplifies and exact-undo remains unchanged" was incomplete. **Decision: group-atomic invalidation and retention.** An earlier draft left this as a choice between atomicity and transient-divergence-plus-repair, while elsewhere assuming atomicity — an inconsistency. Atomic is chosen, because the rest of the design depends on it, including C4's group-wide retention enforcement. Should repair ever be reinstated as an alternative, it requires a **durable invalidation-intent record written before the first dataset mutation**, so that startup can tell an interrupted group invalidation from a healthy state. Either way this needs a crash-between-datasets test on both backends.

**C4 — Persisted group identity and migration.** "Enabling an additional dataset requires re-backfilling the group" is a policy, not an enforceable state transition. Required: a persisted group identity recording at least the enabled dataset set, each dataset's `projectionVersion`, the activation/start coordinate, **the group-wide retention policy**, and network identity — verified **fail-closed** on open, consistent with ADR-034's existing identity checks. Group-wide activation and retention are part of the identity precisely so that per-dataset divergence at the lower coverage boundary cannot be introduced by configuration without an explicit, detected migration. The ADR must additionally state whether existing jobs are invalidated before re-backfill, whether reads remain available during migration, and how an interrupted migration resumes. An operator procedure alone is weaker than the fail-closed invariants this ADR otherwise relies on.

**C5 — Group-atomic promotion cleanup.** Operation 4 has its own recurring seam, distinct from the one-time lifecycle handoff: a crash can land after the group archive receipt but before the promoted hot facts are cleaned. Deterministic replay makes that recoverable, but the contract must state that:

- cleanup is **group-atomic** across datasets;
- replay **verifies all group receipts** before any cleanup runs;
- cleanup identifies **exactly** the rows covered by those receipts;
- **no unfinalized or uncommitted hot facts are removed** — the rollback window must survive promotion.

This is the seam the node crosses continuously in steady state, so it matters more in practice than the handoff.

#### Crash recovery and partial writes

The archive write needs **no new rollback mechanism in the archival layer**. A single backend transaction is already atomic — `DuckLakeWriteSession` wraps the batch in `BEGIN TRANSACTION` … `COMMIT` with `ROLLBACK` on abort (`:372`) — so a crash mid-write leaves nothing partially written. Committing the whole group in one transaction makes this stronger, not weaker.

The residual risk is not inside the archive transaction but at the **seam between two transactions**: the archive commit, followed by the hot-store write (`BlockArchiveWorker:147`). ADR-034 identified and solved that seam already — deterministic job IDs plus digest-verified replay mean a dataset whose hot cursor did not persist replays the same job, and the backend recognises the committed receipt, verifies count and digest, and accepts it as a repeat rather than double-writing.

**Unified batching reduces that seam from four to one, provided C2 lands.** Without C2 there are four seams and alignment is broken until replay reconciles.

The alignment claim must therefore be stated precisely, and this ADR states it as: with C1 and C2, coverage alignment across enabled block datasets is **instantaneous in the absence of a crash and restored deterministically on restart after one**. It is not an unconditional instantaneous invariant, and no phase may claim one.

**Correctness.** This is the phase with the most invariant surface, and the design must be explicit:

- **Per-dataset jobs are retained inside the single transaction.** Do *not* introduce a composite job spanning datasets. Each dataset keeps its own `ArchiveJob.deterministic(...)`, its own `projectionVersion` (currently 2, 3, 3 and 5 — they genuinely differ), its own ordered digest and its own coverage record. Invariants 1, 2 and 5 are then unchanged; only the transaction boundary and the scheduling change.
- **Coverage stays per-dataset**, so a dataset can still be disabled or re-derived later. It advances in lockstep at the top while datasets share a cursor.

- **Tip alignment is not the same as available coverage, and the ADR must not conflate them.** A shared cursor proves only a common **upper** boundary. Lower boundaries can still diverge, because `retention-epochs` is bound *per dataset* (`yano.history.datasets.<name>.retention-epochs`, `HistoryArchiveService:1814`) and `start-mode` likewise differs by dataset. Two distinct notions therefore survive:

  | notion | meaning |
  |---|---|
  | **tip alignment** | all enabled block datasets share the same committed upper coordinate — this is what the group cursor guarantees |
  | **available coverage** | still the **intersection** of per-dataset ranges, because activation and retention may differ at the lower bound |

  An earlier draft claimed the group cursor simply *is* the watermark and that intersection-based planning disappears. That is wrong for historical reads. This ADR resolves it by **requiring group-wide activation and retention for datasets in the group** — enforced as part of the C4 group identity, and atomically applied by C3 — so that lower boundaries cannot silently diverge. Intersection-based planning is nevertheless **retained as a defensive check** rather than deleted: it is cheap, and it keeps the gap-explicit guarantee of invariant 5 independent of the group-policy enforcement being correct.
- **Anchor recheck (invariant 4)** is performed once per batch and covers all datasets, which is strictly stronger than today: all datasets commit against an identically verified anchor.
- **Atomicity improves, conditional on C1, C2 and C5.** All datasets for a range become durable together, so cross-dataset coverage is aligned at the top. `/history/watermark` still computes finalized coverage by intersection — that is retained deliberately — but it no longer has to reconcile datasets that have genuinely drifted apart. Without those contract changes this benefit does not exist, and the phase must not ship claiming it.
- **Rollback simplifies at the cursor level** — one cursor to unwind rather than four, with exact-undo semantics unchanged. **This holds only with C3**: group invalidation must itself be atomic, or a crash partway through invalidating four datasets recreates the divergence the phase eliminates.

**The cost: loss of independent per-dataset progress.** Datasets legitimately sit at different coordinates today — measured, `transaction` at 6,782,500 against `utxo_history` at 6,542,250, some 240,000 blocks apart. A unified cursor ends that.

**Decision: accept it, and do not build a hybrid.** All enabled block datasets share **one cursor per lifecycle track** and cross each relevant storage transition atomically — which accommodates the catch-up cursor, the live cursor, the separate finalized-promotion boundary, and the hot-only near-tip writes. Enabling an additional block dataset requires **re-backfilling the group**, which is acceptable precisely because this ADR makes backfill fast: at a post-Phase-3 target of 50–100 blocks/s, a full mainnet re-derivation is on the order of **2–3 days** rather than the ~29 days measured today.

An earlier draft proposed a hybrid — a convergence group plus a separate catch-up lane for laggards. That is rejected. It reintroduces exactly the divergent per-dataset coverage this phase exists to remove, and with it the scheduling complexity, the cache misses, and the multi-writer contention. The simpler model is also the more correct one.

Two consequences follow, both improvements:

- **Tip alignment across enabled block datasets becomes a maintained property rather than an accident.** The group cursor guarantees a common upper *projection* coordinate; **finalized available coverage remains determined defensively from the intersection of per-dataset archive coverage.** The two must not be conflated — during `LIVE` the group live cursor sits ahead of finalized archive coverage by the finality window, so it cannot serve as the watermark. Invariant 5 is strengthened only in the sense that divergence becomes detectable, not that intersection planning disappears.
- **Failure handling is deliberately strict.** A derivation failure in any dataset fails the whole batch; the group retries from an unadvanced cursor, so invariant 3 holds and no partial history is ever published. A *persistent* failure is resolved by an operator disabling that dataset — a configuration change that redefines the group and requires re-backfill — rather than by automatic ejection. Automatic ejection is rejected for the same reason as the hybrid: it would silently recreate divergent coverage at the moment the system is least healthy.

This is a genuine availability-for-correctness trade: one broken dataset halts all history progress until an operator intervenes. It is the right trade here because history is explicitly non-authoritative (invariant 6 — core sync is unaffected either way), and because publishing aligned-but-stalled history is safer than publishing misaligned history.

**Exit criteria.** One decode per block proven by instrumentation, not by cache hit rate. Transaction counts stated **per operation**, since "one commit per batch" is ambiguous across a two-stage pipeline:

| operation | required transactions |
|---|---|
| 1 — catch-up batch | one group archive transaction, then one group hot transaction |
| 3 — near-tip batch | one group hot transaction, and **zero** archive transactions |
| 4 — finalized promotion | one group archive transaction, then one group cleanup transaction |

Plus: digest and coverage records byte-identical to the per-dataset-commit implementation for the same input; a test proving a derivation failure in one dataset leaves *no* dataset's cursor or coverage advanced; and a documented, tested procedure for changing the enabled set and re-backfilling.

### Phase 4 — Stop discarding derived batches

**Goal.** Recover the work thrown away on stuck waits.

**Changes.** `BlockArchiveWorker.runBatch` derives every row before calling `backend.begin(job)` (`:136`), so a stuck-threshold breach discards a fully derived batch of up to `maxRowsPerBatch` rows. Retry `begin` with the derived rows still in hand instead of re-deriving. Mainnet measurement showed `account_event` taking 13 stuck-pauses against 3 commits, and a 91-minute gap with no `transaction` commit at all during peak contention.

**Expected gain (estimate).** Recovers most of the loss for starved datasets; largest effect on the datasets that currently gate the watermark.

**Correctness.** Safe under invariant 4 precisely because anchors are re-checked *inside* the write session: a batch that became stale while waiting is detected at `recheck(first)` / `recheck(last)` and aborted rather than committed. The retry must be bounded, and the held rows counted against the memory budget (invariant 9).

**Exit criteria.** A test proving a batch held across a writer wait, whose anchor changes meanwhile, aborts without advancing cursor or coverage.

### Phase 5 — Transaction locator: incremental, bounded, off the startup path

**Goal.** Remove a production blocker that is also a per-commit cost.

**Sequencing — this phase is independent of Phases 2–4 and may be pulled forward.** It is numbered here by throughput leverage, not by dependency. It touches the locator and the backend read path, whereas Phases 2–4 touch the write session and the worker; there is no ordering constraint between them. Because it is the phase that unblocks `/api/v1/txs/{txHash}/status` and removes the 4h05m startup stall, **it should be scheduled first if the wallet timeline requires tx-status**, and it remains a hard prerequisite for Phase 7 regardless.

**Changes.**

- Make `rebuild` incremental and bounded rather than a single unbounded SQLite transaction, and move it off the backend constructor so startup is not blocked.
- Make the locator **generation-tolerant** on the read path: serve a hit and verify it against the pinned snapshot — the stale-positive fallback already exists at `DuckLakeHistoryArchiveBackend.java:300-307` — rather than rebuilding to match.
- Wire the already-implemented `DuckLakeTransactionLocator.removeJobs`, which currently has no callers, so invalidation is incremental instead of a full rebuild.
- Revisit `PRAGMA synchronous=FULL` per commit against a 4.7 GB locator.

**Required consistency model — shadow rebuild, checkpoint, atomic swap.** "Incremental, bounded, off the constructor" states a direction but not a design, and an implementation cannot proceed without answering: which generation is a rebuild targeting; where its output lands; how its progress survives restart; how it is published; what happens when generations advance during a multi-hour rebuild; and how long it may pin a DuckLake snapshot. This ADR adopts:

1. build a **shadow locator** (separate table or file), never in place, against a **fixed target generation G** recorded with the shadow;
2. with a **persisted scan checkpoint**, so an interrupted build resumes instead of restarting — the failure mode that made the observed 4h05m stall unrecoverable, since one transaction rolled back everything on interrupt;
3. **apply journal deltas G+1…H to the shadow** *before* publication, advancing the shadow's consumption checkpoint **in the same SQLite transaction as each delta**, so replay after a crash is idempotent;
4. under a **short cutover lock**, drain the remaining deltas to a recorded generation and only then **atomically publish** the shadow;
5. continue normal incremental consumption after publication;
6. with the existing locator remaining usable as a **hint** throughout, so reads are never blocked on the rebuild.

**Catch-up must precede publication, not follow it.** An earlier draft published the shadow first and caught it up afterwards. That would briefly replace a newer active locator with an older shadow, increasing fallback scans exactly at cutover — the opposite of this phase's goal. The cutover lock is held only for the final short drain, not for the multi-hour build.

Journal retention must also guarantee that **target-generation registration and the recorded journal start offset cannot race** with a concurrent mutation or with journal cleanup; otherwise a rebuild can begin from an offset whose earlier deltas have already been discarded.

The rebuild must not pin a DuckLake snapshot for its full duration, or it will indefinitely defer maintenance — which mainnet measurement already shows never completing a rewrite.

**Deletion history is not currently recoverable, so a locator-delta journal is required.** Catch-up by incremental job deltas assumes a generation-ordered record of what changed. Additions can in principle be reconstructed from surviving `archive_commits` rows, but **deletions cannot**:

- invalidation deletes the affected data rows `WHERE archive_job_id=?` and then the commit, coverage and count records `WHERE job_id=?` (`DuckLakeHistoryArchiveBackend:811`), so the job's own record is gone;
- `archive_invalidations` records only `(invalidation_id, dataset, source_kind, range_start, range_end, invalidated_at)` (`DuckLakeInitializer:49`) — **no backend generation and no removed job IDs**.

After a long rebuild there is therefore no reliable ordered stream telling the shadow locator which jobs were removed, and the design as stated is not implementable. Phase 5 must add a **durable locator-delta journal, written inside the same DuckLake transaction as each archive mutation**, carrying at minimum:

- backend generation;
- operation type (add / remove);
- job ID;
- enough data to add or remove the corresponding locator entries;
- an ordered consumption checkpoint;
- retention governed by the per-consumer model below.

**Retention is per-consumer, not shadow-only.** A single global checkpoint is insufficient, because after publication the **active** locator also continues incremental consumption — so journal entries a lagging active locator still needs could be discarded, and one global offset cannot correctly represent several concurrent rebuilds. Required:

- every locator — each shadow **and** the active one — has a durable **consumer ID and its own offset**;
- each offset lives in that locator's own SQLite metadata;
- **applying a delta and advancing its offset occur in the same SQLite transaction**, so replay after a crash is idempotent;
- journal cleanup uses the **low watermark across all registered consumers**, not only shadow rebuilds;
- registration, abandonment and publication **atomically update the consumer set** that cleanup reads, so a rebuild cannot register against an offset whose entries are being concurrently discarded.

The journal must be written in the same transaction as the mutation it describes — **an external SQLite-only queue is not sufficient, because it cannot commit atomically with DuckLake** and would reintroduce exactly the dual-write divergence this ADR is trying to remove. The same journal also gives the currently-callerless `removeJobs` a crash-consistent input.

**The target generation needs explicit protection.** Not holding a pinned read session for hours is correct, but reopening a fixed generation across checkpointed chunks only works while DuckLake still retains it — and `snapshot-retention-hours: 168` plus `cleanup-grace-hours: 24` mean expiry is a real possibility for a multi-hour rebuild. A resumable rebuild could otherwise discover between chunks that its fixed generation had been garbage-collected. Required:

- record the target generation as **rebuild-protected**;
- **prevent snapshot expiration and cleanup** of that generation while a shadow rebuild is active;
- **clear the protection** on atomic publication or explicit abandonment;
- **bound the lease** and surface stale or abandoned rebuilds operationally, so protection cannot silently pin a generation forever and re-create the maintenance starvation this phase is meant to relieve.

**Expected gain.** Removes a 4h05m startup stall, unblocks `/api/v1/txs/{txHash}/status`, and reduces per-commit cost.

**Correctness.** Invariant 5 governs: a locator miss must never be reported as "transaction absent" when coverage claims the range. Serving a hit requires verification against the pinned snapshot; a miss must fall back to an authoritative lookup or an explicit refusal.

**Latency is a correctness-adjacent requirement here, not just a performance one.** The authoritative fallback — a full archive scan — preserves correctness but at mainnet scale can still make `/txs/{hash}/status` operationally unusable. This phase therefore needs a **bounded-work exit criterion** alongside its correctness tests.

**Exit criteria.** Startup independent of locator size; tx-status served correctly across a commit that changes generation mid-request; no full rebuild triggered by ordinary commit/read interleaving; an interrupted rebuild resumes from its checkpoint rather than restarting; **a crash mid-delta-application replays idempotently, proving delta and checkpoint share one transaction**; **publication never regresses locator freshness**, verified by a test that mutates the archive throughout a rebuild and asserts the published shadow is at least as current as the locator it replaces; a stated p99 latency bound and a bounded worst-case work limit for a tx-status lookup, including the fallback path; maintenance still completes while a rebuild is in progress.

### Phase 6 — Commit granularity

**Goal.** Amortize the remaining fixed per-commit cost.

**Changes.** Increase the block span per commit substantially beyond Phase 1's values, informed by Phase 0 measurements, so that snapshot creation, metadata inserts, Parquet creation, and locator update are paid once per many thousands of blocks.

**Expected gain (estimate).** Proportional to the residual fixed cost after Phases 2, 3 and 5.

**Prerequisite.** This phase owns the **decode-cache size-accounting fix** deferred from Phase 1: `CycleCachingBlockArchiveSource` must bound cached decoded blocks by bytes, not entry count (ADR-034 finding L). Without it, `max-blocks-per-batch` cannot be raised beyond Phase 1's cap without an unbounded heap risk.

**Correctness.** Larger batches mean more work lost on failure, which is acceptable only because invariant 2 makes retry idempotent and invariant 3a keeps archival progress behind receipts. The binding constraint is invariant 9: peak memory grows linearly with batch size under the current materialize-then-write design, in **two** places — the materialized blocks and rows in `runBatch`, and the two cycle-caching sources. This phase must therefore bound both explicitly or stream rows, and must retain adaptive halving on capacity failure.

**Exit criteria.** Measured gain against a stated peak-heap ceiling; crash-mid-batch recovery test at the new batch size.

### Phase 7 — Concurrent per-table writers (contingency only)

**Goal.** Remove the last serialization point — **if Phase 3 has not already made it irrelevant.**

**Phase 3 largely obviates this phase.** Once all block datasets derive from one batch and, for archive-writing operations, commit in one transaction, there are no longer four block workers competing: the writer becomes **uncontended by other block-dataset workers** — though still shared with epoch commits, invalidation, retention, maintenance and backup — and the parallelism that matters has moved to *derivation*, which is pure CPU work over an immutable decoded batch and is already parallel and safe. Concurrent writers would then only reintroduce the generation-sequencing hazards below for little gain. **Do not begin this phase unless post-Phase-3 measurement shows the single writer is still the binding constraint.**

**Changes (if undertaken).** Allow block datasets to write concurrently. They write disjoint tables (`chain_transaction`, `account_events`, `address_transactions`, `transaction_*`), which is the case where per-table concurrency is sound. Three things currently assume a single serial writer and must be reworked first:

1. one DuckDB connection with explicit `BEGIN TRANSACTION` / `COMMIT` (`:391`, `:414`) — a connection per writer is required;
2. `DuckLakeTransactionLocator.advance` requires strictly sequential generations (`generation(locator) == generation - 1`) and otherwise falls back to a full rebuild — Phase 5 is a hard prerequisite;
3. generation is global and pinned by read sessions, so allocation and coverage `revision` semantics need rework.

**Expected gain (estimate).** Up to the number of concurrently writable datasets, bounded by DuckDB capacity.

**Correctness.** The highest-risk phase. Invariants 2, 3 and 5 all depend on generation semantics. Concurrent writers must not produce interleaved generations that break coverage `revision` pinning or the locator's sequencing assumption. If a design cannot preserve pinned-snapshot read semantics, this phase is abandoned rather than weakened — the preceding phases are expected to deliver the majority of the improvement.

**Exit criteria.** Pinned-generation reads proven coherent under concurrent commits; no locator rebuild triggered; conformance suite green on both backends.

### Applicability to the SQLite backend

Phases 0, 1 (partially), 3, 4 and 6 apply to both backends, because causes 4, 5 and 6 live in `archive-core` and the service layer. **Phase 3's structural changes apply to both**, though their measured benefit may differ substantially and must be measured per backend rather than assumed: one decode, one commit and a writer uncontended by other block workers are backend-independent properties, and the SQLite backend's own `new Semaphore(1, true)` writer (`:68`) stops being a block-dataset contention point for the same reason DuckLake's does. The per-commit fixed cost each avoids is quite different, however — DuckLake pays snapshot and Parquet creation that SQLite does not — so equal structural change does not imply equal speedup. Phase 2 is DuckLake-specific; Phase 5 is DuckLake-specific. Backend selection is therefore not a throughput remedy and must not be presented as one.

## Consequences

### Positive

- A staged path from 2.90 blocks/s toward a usable mainnet backfill, with each phase independently measurable, shippable and revertible.
- Phase 1 costs nothing and can ship immediately; Phase 2 is localized to one class.
- Phase 5 removes a confirmed production blocker (4h05m startup stall, unsafe tx-status endpoint) independently of throughput, and can be pulled forward.
- **Phase 3 simplifies the design while making it faster — conditional on C1–C5 and on covering the full lifecycle.** One group cursor replaces the four per-dataset catch-up cursors and, separately, the four per-dataset live cursors; the lifecycle handoff becomes one group transition instead of four independent ones; the two divergent block workers (ADR-034 finding F) collapse into one; rollback has one cursor to unwind; and the `CycleCachingBlockArchiveSource` sharing machinery becomes deletable, which also retires ADR-034 finding L. **Tip alignment** becomes a maintained property rather than an accident — instantaneous absent a crash, deterministically restored on restart after one — while **finalized available coverage** remains an intersection, since lower boundaries depend on activation and retention and the live cursor legitimately runs ahead of the promotion boundary. It also renders Phase 7 largely unnecessary. It does **not** retire Phase 4, which stays useful for the writer contenders outside block backfill.
- Throughput becomes a guarded property rather than an unmeasured one.

### Trade-offs

- **Phase 3 trades availability for consistency.** A derivation failure in any dataset halts progress for all of them until an operator intervenes, and adding a block dataset requires re-backfilling the group. Both are accepted deliberately: history is non-authoritative so core is unaffected, aligned-but-stalled history is safer than misaligned history, and the re-backfill cost falls to roughly 2–3 days at the target rate.
- Larger batches and larger memory budgets increase peak heap and increase the work lost to a mid-batch failure. Idempotent replay makes this recoverable but not free. Phase 3 raises peak memory further, since all datasets' rows are materialized for the same batch.
- Phase 7 is genuinely risky, is now contingency-only, and may never be needed.
- **Phase 3 requires five contract changes (C1–C5) across both backends and the hot store, must cover the full `CATCHING_UP → LIVE` lifecycle, and must model near-tip hot projection separately from finalized archive promotion.** Scoping it to backfill alone would be cheaper but pointless — every structural benefit would evaporate at the handoff. That is substantially more work than the original draft implied, and it is the reason Phase 3 is not approved with the rest.
- **Phase 3 requires group-wide activation and retention**, removing today's ability to retain different block datasets for different periods. That capability appears unused (all block datasets currently run `retention-epochs: 0`, `start-mode: full-required`), but it is a real reduction in configurability.
- **yaci-store's 200–300 blocks/s is a comparator, not a goal this ADR adopts.** It achieves that with a structurally cheaper commit model: plain SQL tables, no immutable per-commit snapshot, no ordered digest, no coverage record. Yano's archive pays for immutability, digest-verified idempotent replay and gap-explicit coverage on every commit — and, with Phase 5, a locator-delta journal as well — a deliberate ADR-034 correctness trade this ADR does not revisit. Pursuing parity would mean unwinding that trade, so it is explicitly out of scope. The campaign target is the provisional Phase 0 SLO of **≥50 blocks/s under the named high-throughput profile**, and no higher figure is claimed.

## Rejected Alternatives

### Keep per-dataset cursors and merely align their batch ranges

Rejected in favour of Phase 3's unified pipeline. Alignment relies on scheduling luck to make the per-cycle decode cache hit (measured today at 5.8%), and even when it works it leaves four commits, four writer acquisitions and four sets of per-commit fixed cost. A unified batch makes single-decode structural rather than probabilistic and pays the fixed cost once. It is both faster and simpler.

### Unified pipeline with a catch-up lane for laggard datasets

Rejected. A hybrid — a convergence group plus a separate lane for datasets that are behind — would preserve independent enablement, but it reintroduces divergent per-dataset coverage, which is precisely what the unified pipeline removes, and with it the scheduling complexity, cache misses and writer contention. Accepting a re-backfill when the enabled dataset set changes is the simpler and more correct trade, and it is affordable once backfill is fast.

### Automatic ejection of a failing dataset from the group

Rejected. Ejecting a dataset on failure would silently recreate misaligned coverage at exactly the moment the system is least healthy. A persistent failure is instead surfaced and resolved by an operator disabling the dataset, which is an explicit configuration change with a known re-backfill cost.

### Switch the archive backend to SQLite to gain speed

Rejected. Causes 4, 5 and 6 are backend-independent, and `SqliteHistoryArchiveBackend` has the same single-writer semaphore. SQLite avoids DuckDB's misconfiguration and Parquet overhead but has no vectorized bulk path. This would move the ceiling, not raise it.

### Relax digest verification or coverage records to cut per-commit cost

Rejected. These are the mechanisms that make non-atomic dual-write safe (ADR-034). Removing them to gain throughput would trade the property the subsystem exists to guarantee.

### Raise `duckdb.max-concurrent-bulk-jobs` alone

Rejected, and **withdrawn from Phase 1** where an earlier draft had included it. Capacity is acquired *before* the writer (`:201-202`) and `ducklake-writer` is hard-coded to one permit, so additional bulk permits move the queue from one gate to the other without increasing mutation concurrency — and worse, let a second bulk worker occupy scarce DuckDB capacity while it merely queues for the writer. Reconsider only if Phase 0 measurement demonstrates a benefit.

### Reduce the number of enabled datasets

Rejected as a default. It changes the product rather than the performance, and the watermark still gates on the slowest enabled dataset. Remains available to operators.

### Skip the archive and reuse core UTXO state for history

Rejected. ADR-034 requires independent outpoint resolution (invariant 7); reading live UTXO state reintroduces exactly the corruption mode that design avoids.

## Implementation and Verification

- Phase 0 precedes all others, and Phase 5 precedes Phase 7. Otherwise the ordering reflects throughput leverage, not hard dependency: **Phase 5 is independent of Phases 2–4 and should be pulled forward if the wallet timeline requires `/txs/{txHash}/status`**, and **Phase 2b is independent of everything and should ship as soon as convenient**, since the window it closes exists today. Phase 6 depends on the decode-cache size-accounting fix.
- Invariant 4's near-tip clause is delivered by Phase 2b, not inherited by Phases 0–2; invariant 3b holds per dataset until C2 and per group thereafter. Neither should be read as a precondition on the earlier phases.
- Each phase records measured before/after blocks/s and rows/s per dataset in `adr/reports/`, against the Phase 0 baseline.
- Each phase must leave the existing archive and app test suites green, including the cross-backend conformance fixtures and the hot-store conformance fixtures.
- Each phase must state its peak-memory effect and be validated against a heap ceiling on the reference host.
- Correctness regression coverage required per phase: digest equivalence across insert mechanisms (Phase 2); **for Phase 3, digest and coverage records byte-identical to the per-dataset-commit implementation for the same input, no cursor or coverage advanced when any dataset's derivation fails, and a tested enabled-set-change/re-backfill procedure**; stale-anchor abort after a held wait (Phase 4); tx-status correctness across a generation change (Phase 5); crash-mid-batch recovery at the new batch size (Phase 6); pinned-generation read coherence under concurrent commits (Phase 7).
- Phase 3 is the largest behavioural change in this ADR and should ship behind a switch that allows falling back to per-dataset cursors until it has been validated on preprod.
- **Phase 3 additionally requires crash tests at all five transaction boundaries**, on both backends:
  1. crash between the group archive commit and the group hot-state write, in a catch-up batch (C1/C2);
  2. crash partway through a group invalidation (C3);
  3. crash partway through an enabled-set migration (C4);
  4. crash partway through the `CATCHING_UP` → `LIVE` group lifecycle handoff;
  5. **crash after the group archive promotion commits but before group hot cleanup (C5)** — then verify replay cleans every dataset without duplicating archive rows. This is the seam crossed continuously in steady state, so it is the most important of the five.

  Each must demonstrate that recovery restores tip alignment deterministically and that no dataset's coverage advances without its receipt.
- Phase 3 must also prove the live path specifically: near-tip batches write **only** to the hot store, finalized promotion is the sole archive writer at the tip, the group live cursor and the finalized promotion boundary are tracked separately, and the lifecycle handoff is all-or-nothing across the group.
- Every phase is measured against the Phase 0 **acceptance SLO — ≥50 blocks/s sustained on the dense mainnet fixture within the stated RSS ceiling** — reporting its contribution, rather than only recording whatever gain it produced.
- Measurement uses the DuckLake catalog (`ducklake_data_file.record_count` with per-file `block_number` statistics) rather than log inference. Rows/s is the stable metric; blocks/s must be reported alongside the block range it was measured over, since transaction density varies with block height.
