# ADR-039 — Parquet granularity, commit batching and compaction

**Design brief for review. Self-contained; assumes no prior context from the implementation
session.**
**Date:** 2026-08-20 · **Status:** open decision, not blocking correctness

---

## 1. Context

ADR-039 replaces per-dataset replay workers with a canonical projection outbox:

```
canonical apply -> per-contributor RocksDB outbox -> finality gate -> DuckLake sink
```

A full fresh preprod sync with the real DuckLake sink completed successfully:

| | |
|---|---|
| blocks | 5,076,688 (genesis to tip) |
| wall clock | 1 h 07 m |
| producer rate | 1,193 blocks/s |
| sink rate | 1,292 blocks/s (concurrent, keeps up) |
| outbox backlog | 4,320 blocks, pinned at the finality window |
| drain failures | 0 |
| coverage gaps | 0 |
| archive size | 3.7 GB (chainstate 27 GB) |
| rows committed | 87.4 M across 6 tables |

Correctness is not in question. This brief is about **file granularity**.

---

## 2. The observation

```
67,603 Parquet files    average 56 KB    catalog 95 MB
```

---

## 3. Cause — measured, and it is not the obvious one

**File count tracks commits x tables.**

```
receipts x tables = 10,233 x 6 = 61,398        actual files 67,603
configured target_file_size = 4 MiB            observed average 56 KB
```

Two candidate explanations were tested and rejected:

- **`target_file_size` mis-tuned** — no. Each commit flushes its own file per touched table
  regardless of how far below target it lands; 4 MiB was never approached. The setting caps
  compaction *output*; it does not merge per-commit files. Raising it changes nothing alone.
- **Batch cap too small** — no. Measured commit sizes:

  ```
  avg blocks/commit  495.7      max 2,000 (the cap)
  commits at cap        81 of 10,233  = 0.8%
  under 100 blocks   2,153       = 21%
  single-block         171
  ```

**Actual cause: the drain loop commits whatever is eligible the instant it is eligible.**
Because the sink outpaces the producer, a backlog never accumulates, so each pass takes only
the few hundred blocks that just crossed the rollback-safety window. The loop's own speed
fragments the output.

---

## 4. The fact that governs steady state

```
archiveFinalityBlocks = 4,320 blocks x 20 s = 24 HOURS
```

A block is not archive-eligible until ~24 h after production. **The archive is already a day
behind tip by design.** Freshness was never measured in seconds, which makes a generous
commit interval cheap in relative terms.

---

## 5. Two regimes with opposite characteristics

| | bootstrap | at tip |
|---|---|---|
| block arrival | ~1,200 blocks/s | 1 block / 20 s |
| binding limit | throughput | freshness |
| 10,000-block floor costs | **8.4 s** of added lag | **2.3 days** of stalling |
| query load | none (`catching_up`) | real |

A block-count floor works for bootstrap and is unusable at tip. A time interval works at tip
and is pointless during bootstrap. The rule must be **N blocks OR T elapsed, whichever comes
first**, with different values per regime.

### Bootstrap, with a 10,000-block floor

| | now | with floor |
|---|---|---|
| commits | 10,233 | **508** (20x fewer) |
| Parquet files | 67,603 | **3,046** (22x fewer) |
| avg file | 56 KB | 1.2 MB |
| catalog | 95 MB | 4.3 MB |
| added lag | — | 8.4 s |

### At tip, files are a pure function of commit interval

| interval | blocks/commit | files/day | files/year | added staleness |
|---|---|---|---|---|
| 1 min | 3 | 8,640 | 3,153,600 | +1 min |
| 10 min | 30 | 864 | 315,360 | +10 min |
| 1 hour | 180 | 144 | 52,560 | +1 hour |
| 6 hours | 1,080 | 24 | 8,760 | +6 hours |
| 1 day | 4,320 | **6** | 2,190 | +1 day |

Relative to an already-24 h-stale archive, an hourly interval adds ~4%.

---

## 6. Goal

**~1 Parquet file per table per day at mainnet scale, without compromising throughput during
initial sync or freshness at tip.**

---

## 7. Compaction — required, and constrained

`ArchiveBackend.maintain(budget)` already implements budgeted `merge_adjacent_files`, with
correct deferral on active reader snapshots and on capacity pressure.

Two obstacles:

1. **`ProjectionSink` has no maintenance operation at all.** Its contract is `append`,
   `coordinate`, `receiptFor`, `health`, `close`. The only caller of `maintain` is
   `HistoryArchiveService`, the replay-worker path being replaced. Wiring compaction into the
   projection path is a **contract change**, not a scheduler change.
2. **It contends with the sink.** `DuckDbManagerConfig` constrains
   `maxConcurrentBulkJobs < maxConcurrentQueries`; sink commits and compaction draw from the
   same bulk pool. During bootstrap the sink uses it continuously.

Compaction cannot be avoided at tip: any time-based commit produces small files indefinitely,
so steady state accumulates them regardless of how well bootstrap is tuned.

---

## 8. Resolved: unbounded row materialisation

`ProjectionRowBuilder.materialise()` builds the whole batch's `ArchiveRow` list in heap before
the sink sees it. Three different sizes for the same 10,000 blocks:

| stage | size | bounded? |
|---|---|---|
| outbox section payload | 28 MB | yes — `maxBytesPerBatch` |
| materialised `ArchiveRow` objects | ~86 MB+ | **no** |
| final Parquet on disk | 7.1 MB | n/a |

Measured 2,817 B/block in the outbox vs 709 B/block as Parquet: the archive is **4x smaller**
than the payload it came from. The one stage that determines peak heap is the one stage
unmeasured, and the ratio is not constant — redeemer- or datum-heavy blocks inflate it.

Invisible at today's ~500-block commits (~4 MB). Material at a 10,000-block floor. ADR-039
requires bounded memory, so raising the floor without bounding this trades a file-count
problem for a heap problem.

**Resolved.** `ProjectionRowBuilder` now streams: `ProjectionRowBatch` carries a single-use
`Iterable<ArchiveRow>` that decodes one envelope at a time and never concatenates chunks.
Instrumented measurement against a densest-preprod fixture (see
`adr-039-memory-invariant-2026-08-20.md`) shows peak retained heap of **1,066 KiB at 5
envelopes and 1,111 KiB at 100 envelopes** — 20x the batch for 4% more retained heap. Batch
size no longer contributes to peak heap, so the block floor can be raised on file-count
grounds alone.

---

## 9. Design choices — decided

1. **Batch policy shape** — `N blocks OR T elapsed`, separate bootstrap and tip values.
   Bootstrap 10,000 blocks / 30 s; near tip a **preferred target** of 50 envelopes with a hard
   15-minute deadline. The regime is derived from the **eligible drain backlog**, not from a
   header gap or an explicit switch: a sink that falls behind while the node sits at tip gets
   the bootstrap regime and returns on its own. Self-correcting in both directions, and it
   cannot disagree with reality.
2. **Tip commit interval** — 15 minutes, which at 20 s slots flushes around 40-50 blocks and
   yields ~96 commits/day. Neither hourly nor daily: daily commits would make history up to
   24 h stale, and commit frequency is decoupled from file granularity because compaction
   supplies the daily-file goal afterwards.
3. **Compaction trigger** — on a timer (6 h default) **and** only when the sink is caught up
   with no eligible backlog. Housekeeping is scheduled separately, on a 30-minute interval,
   and runs during bootstrap: a mainnet bootstrap easily outlasts the snapshot retention
   window, and an archive that waits for tip before ever cleaning up accumulates obsolete
   files for the whole sync. Bulk-pool contention is arbitrated by construction — maintenance
   runs on the drain thread itself, only on a pass with nothing to commit, so it can never
   overlap an in-flight sink transaction.
4. **Contract change** — `ProjectionSink.maintain(ProjectionMaintenance.Budget)` was added.
   The result carries `UNSUPPORTED` so a backend without maintenance opts out honestly rather
   than reporting a no-op as success.
5. **`target_file_size`** — compaction output size in the 256 MB - 1 GB range. 8 GB is too
   coarse: DuckLake prunes at file granularity before row groups, which penalises exactly the
   `tx_hash` point lookups §15 already flags as weak without a locator, plus rewrite
   amplification and compaction memory.
6. **Bounding row materialisation** — resolved by streaming rather than by a cap; see §8. Row
   and estimated-heap ceilings exist as well (2,000,000 rows, 512 MiB) but as a backstop, not
   as the mechanism.

---

## 10. What is measured versus estimated

| claim | basis |
|---|---|
| 67,603 files / 56 KB / 95 MB catalog | measured |
| commits x tables drives file count | measured |
| avg 495.7 blocks/commit, 0.8% at cap | measured (`projection_receipts`) |
| sink 1,292 vs producer 1,193 blocks/s | measured, 61 s window |
| outbox 2,817 B/block, Parquet 709 B/block | measured |
| 24 h finality window | derived from config, exact |
| 10,000-floor projections | arithmetic from measured rates |
| ~86 MB materialised rows | superseded — streaming measured at 1.1 MiB peak retained regardless of batch size |
| peak retained flat across 20x batch size | measured, instrumented (2026-08-20) |
| mainnet file/catalog counts | extrapolated, not measured |
