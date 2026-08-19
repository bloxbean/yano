# Finding — the transaction locator has re-entered a throughput-limiting regime

Date: 2026-08-19
Severity: **throughput regression, production mainnet**
Attribution: **two stages.** Stage 1 began before the restart and before the
4 MiB change. Stage 2 followed the restart. **Neither is caused by the 4 MiB
change**, which cannot affect throughput by construction.
Relationship to ADR-038 Phase 2c: **contaminates the post-calibration validation
window**; does not invalidate the Phase 2c result, which was measured earlier.

> **Correction.** An earlier draft of this report attributed the whole regression
> to a pre-restart onset. Measuring `utxo_history` throughput by period showed
> that is only half right: a substantial further drop followed the restart. The
> attribution below is the corrected one.

---

## 1. What was observed

While sampling the 4 MiB calibration window, all four datasets advanced in
lockstep at ~5–19 blocks/s — far below the 59.84 blocks/s Phase 2c measured. The
proximate cause is in the commit-stage log:

```
17:13:48 TRANSACTION rows=4433 total=28.795s append=0.007s verifyKeys=0.002s
         stagingCopy=0.003s metadata=0.009s commit=1.024s locator=27.749s (96.4%)
```

The locator stage is again ~96–99% of the write session — the same *shape* as the
Amendment 2 pathology, but a **different mechanism**.

## 2. It is not a rebuild

`DuckLakeTransactionLocator.rebuild()` logs at INFO on every invocation:

```java
LOG.log(INFO, "Locator full rebuild #{0}: {1}->{2}, reason={3}, rows={4}, {5}s", ...);
```

**Zero such lines appear in any current or retained log.** The `fullRebuilds`
counter is not incrementing. The locator-generation fix is holding; this is not a
regression of it.

The time is being spent inside `advance()` — the incremental batch upsert:

```java
INSERT INTO tx_locator VALUES(?,?,?)
  ON CONFLICT(tx_hash) DO UPDATE SET block_number=..., job_id=...
```

## 3. It is confined to the one dataset that feeds the locator

Post-restart, every slow locator stage belongs to `TRANSACTION`:

```
26 TRANSACTION      (all stages >1s)
 0 ACCOUNT_EVENT / ADDRESS_TRANSACTION / UTXO_HISTORY
```

Those other datasets show `locator=0.001–0.03s`. This is consistent with
`advance()` cost scaling with the number of entries inserted, not with session
overhead.

## 4. When it started — two stages

### Measured `utxo_history` throughput by period

| period | rate | vs Phase 2c |
|---|---:|---:|
| Phase 2c window, 13:41–15:44 | **59.84 blocks/s** | baseline |
| pre-restart, 15:44–17:06 (includes ~3 min downtime) | **41.87 blocks/s** | −30% |
| post-restart, 17:06–17:32 | **7.29 blocks/s** | **−88%** |

### Stage 1 — the locator tail grows ~10x, before the restart

Maximum locator stage per hour, across the retained logs (deduplicated — see the
logging note below):

| window | commits/h | total locator/h | **max stage** |
|---|---:|---:|---:|
| 2026-08-18 20h–23h | ~1050–1140 | 763–870 s (21–24%) | 5.3–7.6 s |
| 2026-08-19 00h–13h | ~1000–1220 | 499–1092 s (14–30%) | 4.7–9.2 s |
| 2026-08-19 13h–15h (Phase 2c window) | ~500–1570 | 212–706 s (6–20%) | 3.6–5.6 s |
| 2026-08-19 15h | 416 | 101 s (3%) | 2.6 s |
| **2026-08-19 16h** | 1161 | 1056 s (29%) | **77.2 s** |
| 2026-08-19 17h (post-restart) | 131 | 840 s (23%) | 52.6 s |

The **tail** jumps roughly 10x at ~16:00, while total locator time per hour stays
in its usual 14–30% band. Locator cost did not grow in aggregate; it *concentrated*
into far fewer, far longer stages. Throughput fell 59.84 → 41.87 blocks/s.

This stage is **entirely pre-restart**, on the 2 MiB reservation and a jar that
differs from the current one only by that setting.

### Stage 2 — the restart drops it a further 6x

After the 17:03 restart, throughput fell to 7.29 blocks/s with the process
**94% idle** (CPU 4.2%) and RSS at **1.5–1.8 GB against 10.4 GB before**. The disk
shows ~3,300 IOPS at 25–39 KB average request size with the CPU 70% idle — a
system saturated by small random reads.

**The restart is implicated in stage 2, and this should be stated plainly.** The
most consistent explanation is that the restart discarded a warm cache the process
can no longer rebuild: at 20.64 GB the locator has outgrown the cache available to
it, so a cold start cannot return to the previous working set. Before the restart
the process held 10.4 GB RSS and ran at 41.87 blocks/s; after, it holds 1.5 GB and
runs at 7.29.

If that explanation is right, it carries an operational consequence beyond this
window: **restarting this deployment is now expensive and may not recover prior
throughput.** That should be verified before the next restart, not assumed.

### What is ruled out

- **The 4 MiB change.** It cannot affect throughput by construction: admission is
  bound by `maxInFlightBlocks` (8), not the byte budget, before and after.
- **Any other code change.** The only non-documentation commit between the two
  jar builds is the 4 MiB change itself; every other commit in that span is
  docs-only.
- **A locator rebuild.** Zero `Locator full rebuild` lines; `fullRebuilds` flat.

### Confounds and caveats

- Gradle test runs and a `quarkusBuild` ran on this host during 16h–17h,
  competing for disk and cache. Not the dominant mechanism — the 13:40 build
  caused no cliff, and long stages persist well after all builds finished — but
  the exact onset hour is not precise.
- **Logging artefact:** the restart command redirected stdout to `yano.log` while
  Quarkus's own file handler (`log.file.path: ./yano.log`) was already writing
  there, so the current log contains **every line exactly twice**. All figures
  above are deduplicated. The redirect should be dropped at the next restart; the
  earlier logs are unaffected.

## 5. Probable mechanism

The locator is a **20.64 GB SQLite database** with a primary key on `tx_hash`.
Transaction hashes are uniformly distributed, so every batch upsert touches
B-tree pages scattered across the whole file in random order.

While the index fit in page cache, upserts were memory-speed (~0.001 s). As it
outgrew the cache — competing with an 89 GB Parquet working set and a ~280 GB
chainstate on the same host — each batch degraded to scattered random disk reads.
That produces exactly this signature: a sharp knee rather than a gradual slope,
confined to the one writer that inserts, with no change in code or configuration.

### Three amplifiers, all verified in source

`DuckLakeTransactionLocator.advance()` opens its connection through:

```java
private Connection open() throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl);
    try (Statement sql = connection.createStatement()) {
        sql.execute("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA synchronous=FULL");
        sql.execute("PRAGMA busy_timeout=5000");
    }
    return connection;
}
```

1. **A fresh connection per `advance()` call** — `advance()` begins
   `try (Connection connection = open())`, and `close()` (line 198) is a no-op, so
   there is no persistent connection anywhere. Every batch therefore starts with a
   **completely cold page cache** and discards whatever it warmed on exit.
2. **No `cache_size` and no `mmap_size` are set** — SQLite's default page cache is
   ~2 MB. A 2 MB cache against a 20.6 GB random-order B-tree means nearly every
   page touch is a disk read.
3. **`synchronous=FULL`** — correct for durability, but it adds an fsync to each
   batch commit on top of the random-read cost.

### Fragmentation

Payload per entry is roughly 76 bytes (32-byte hash, 8-byte block number, 36-byte
job UUID stored as text). A 20.64 GB file is very large relative to that for any
plausible mainnet transaction count, which is consistent with random-order insert
fragmentation and with storing the job id as a UUID **string** rather than 16
bytes. The exact ratio was not computed: doing so would require opening the live
locator database, which was deliberately avoided while the deployment is running
and already showing lock contention.

Host state at time of measurement: 5.6 GB pages free, 40.6 GB active,
42.5 GB inactive; 582 GB disk free.

This is a **hypothesis consistent with all observed evidence**, not a proven
mechanism. It has not been confirmed by direct I/O measurement.

## 5b. The locator write holds the global writer gate

This is why *all four* datasets slow down together, and it is the single most
actionable structural fact in this finding.

`DuckLakeHistoryArchiveBackend.begin()` acquires the DuckDB capacity lease and the
single archive-writer permit, and hands both to the write session. The session
releases them in `close()` — which `commit()` calls at line 210, **after**
`updateTransactionLocator` at line 201. So the locator upsert runs while holding
both the single-permit `duckdb-bulk` gate and the writer ticket.

Live gate telemetry during the slow period:

```
lastWaitWarning: gate=duckdb-bulk operation="begin UTXO_HISTORY"
                 holder="held by begin TRANSACTION for 33s (1/1 permits in use, 1 waiting)"
                 waitedSeconds=30
```

`UTXO_HISTORY` waited 30 s for a permit held by `TRANSACTION` for 33 s — the
duration of its locator stage. Every dataset is serialised behind the locator
write, which is why coverage advanced in near-perfect lockstep (2,000 blocks per
five-minute sample across all four).

The locator is a **separate SQLite database that requires no DuckDB resources at
all**, so holding DuckDB capacity across it is not required for correctness — only
for the current ordering. Moving it out of the gate is an obvious candidate fix,
with the important caveat in §8.

## 6. Why this matters more than the throughput number

The locator is an **accelerator whose write cost now scales with total archive
size**, and it is the only archive component with that property:

- It is **97.7% populated** (driven by the `transaction` dataset) and near final
  size, so this will not resolve itself.
- Its cost falls entirely on `TRANSACTION` commits, throttling the shared writer
  and therefore every dataset — which is why all four advance in lockstep.
- ADR-038 Amendment 3 already recorded the locator as the remaining Phase 5 item.
  This finding changes its character: it is not only an unrepaired-gap
  correctness seam, it is **once again the production throughput bottleneck**, by
  a mechanism Phase 5 as currently scoped does not address.

## 7. What this does and does not invalidate

| | |
|---|---|
| Phase 2c result (31.57 → 59.84 blocks/s) | **stands** — measured 13:41–15:44, entirely before stage 1 |
| 4 MiB calibration correctness | **stands** — verified live, zero underestimates, zero drain timeouts, zero reapers |
| 4 MiB validation *throughput* window | **contaminated** — rates measure the locator regression, not the calibration |
| locator-generation fix (Amendment 3) | **holding** — zero rebuilds, `fullRebuilds` flat |
| the restart's role | **stage 2 followed it**; the restart was authorised and necessary to apply the change, and the change itself is not the cause |

## 8. Cost, and recommended next steps

### What it costs

`utxo_history` has **4.17M blocks** remaining. Time to catch-up at each observed
rate:

| rate | period | time to catch-up |
|---:|---|---:|
| 59.84 blocks/s | Phase 2c | **~19 hours** |
| 41.87 blocks/s | pre-restart, stage 1 | ~28 hours |
| 7.29 blocks/s | post-restart, stage 2 | **~7 days** |

That spread — not the throughput number itself — is what makes this the
operator's decision. It also means the cheapest possible experiment is worth
running first: if stage 2 really is cold-cache, the deployment may partially
recover on its own as the working set rebuilds, and the sampler will show it.

### Candidate mitigations, cheapest first

1. **Hold one persistent locator connection with a real cache.** Today every
   `advance()` opens a fresh connection with SQLite's ~2 MB default cache and
   discards it. A single long-lived connection with `cache_size` and `mmap_size`
   sized for the workload is a small, local change that attacks the dominant
   amplifier directly.
2. **Move the locator write out of the DuckDB gate** (§5b). It needs no DuckDB
   resources. **Caveat:** this widens the crash window between the DuckLake commit
   and the locator update — the exact window whose gap is never repaired
   (`next-correctness-task-locator-seam-2026-08-19.md` §2). These two must ship
   **together**: reconcile-on-replay first, then move the write.
3. **Reconsider the structure.** A 20.6 GB random-order index is a poor fit for a
   write-heavy catch-up. `chain_transaction` already holds the mapping; the
   locator exists only because DuckLake lacks a point index. Worth measuring:
   partition by block range so writes stay in a hot recent partition; store the
   job id as 16 bytes rather than a 36-character UUID string; or skip the locator
   write entirely during catch-up and build it once at the catch-up→live
   transition, when it is first actually needed to serve queries.

### Process

Fold this into the reduced Phase 5 scope, which now needs a throughput dimension
it did not have when written.

**No change has been made in response to this finding**, and none should be made
without an explicit decision: the deployment is mid-catch-up, and every candidate
above touches the seam that is already known to be unrepaired.
