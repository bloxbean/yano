# ADR-038 Phase 2 — Mainnet Deployment and Validation

Date: 2026-08-18
Branch: `feat/adr-038-archive-throughput`
Deployment: `/Users/satya/Downloads/yano-try/yano-0.1.0-pre13-mainnet`
Profile: `mainnet,history`

**Status: deployed and serving; multi-hour throughput validation IN PROGRESS.**

Startup took **455.6 s (7 m 36 s)**, of which the locator rebuild was the bulk —
**not** the 4 h 05 m of the previous restart. The rebuild fired, but was an order
of magnitude cheaper, so the multi-hour stall was avoided.

Contents: Phase 2b (live anchor recheck), Phase 2 (Appender staging path), and the
per-commit stage instrumentation, deployed together in a single restart as
directed.

---

## 1. Process resolution

Both JVMs were re-resolved from the process table rather than trusting a recorded
PID:

| role | PID | command | action |
|---|---|---|---|
| mainnet | **88709** | `java -Dquarkus.profile=mainnet,history -jar …/yano-0.1.0-pre13-mainnet/yano.jar` | restarted |
| preprod | 85099 | `java -Dquarkus.profile=preprod,wallet -jar …/yano-0.1.0-pre13-preprod/yano.jar` | **untouched, verified alive after restart** |

Distinct directories and profiles; the mainnet target was re-verified by matching
its jar path immediately before signalling.

## 2. Pre-restart baseline

Captured 16:24 from `GET /api/v1/status`.

- Core block **13,824,014**, slot 195,475,024, `lagBlocks: 0`
- Archive generation **29,883**, engine `ducklake`, hot store `sqlite`
- Deployed jar sha256 `0eebb129f36517e7217a5840717051adbe628aecc92517deba2136b30ef3c68c`
- Log 8,953 lines; RSS ~6.5 GB; uptime 14h55m
- Disk 520 GB free; `chainstate` 283 GB; `history` 30 GB

| dataset | coverage | worker state |
|---|---|---|
| `account_event` | 1–6,808,500 | DEGRADED |
| `transaction` | 1–6,807,500 | DEGRADED |
| `address_transaction` | 1–6,598,425 | RUNNING |
| `utxo_history` | 1–6,568,125 | DEGRADED |

**The DEGRADED state was diagnosed before deploying and is benign.** Every entry
is `ArchiveStuckOperationException` from the 300 s stuck threshold on
`duckdb-bulk`, held by another dataset's `begin` for 301–302 s. `cycleError` and
`maintenance.error` were both null, and coverage was still advancing
(`account_event` +26,000 blocks over the preceding hours). This is the contention
Phase 2 targets: a 3.3–4.2x faster session should bring holds below the threshold.

## 3. Preservation

- Jar backup: `yano.jar.20260818-162414.pre-adr038.bak` (299,451,548 bytes)
- Log backup: `yano.log.20260818-162414.pre-adr038.bak` (8,961 lines)
- `chainstate/` (283 GB), `history/` (31 GB) and `config/` untouched; nothing
  deleted, reset or recreated
- `config/application-history.yml` preserved unmodified — its explicit legacy
  values still apply, so bundled default changes do not reach this deployment
  (Phase 1 is not part of this deployment in any case)

## 4. Graceful shutdown

`kill -TERM 88709`; exited after **62 s**, `yano stopped in 60.243s`. No `kill -9`.

Two archive warnings, both **ADR-037 behaving as designed**:

```
WARN  Archive projection executor did not stop within 30 seconds
ERROR Archive worker did not terminate within the shutdown budget; leaving the hot
      store open rather than racing native handle destruction
```

The pre-ADR-037 build threw `IllegalStateException` here and skipped
`backend.close()` entirely. The guarded path logs and continues instead. The
worker did not terminate in budget because a projection was mid-300 s stuck wait —
again the contention Phase 2 addresses.

## 5. Deployment

- Built with `./gradlew :app:quarkusBuild` — the same uber-jar artifact and path
  as the deployed one.
- Installed sha256 **`2d8f9fbc179d6e6100b71a0d881fa21b33636dc8d7e56ba27260b8c43050a486`**,
  299,456,763 bytes; verified identical to the build output after copying.
- Restarted with exactly `-Dquarkus.profile=mainnet,history`, same deployment
  directory, `nohup`, appending to the same `yano.log`.
- New PID **66751**; HTTP port 6370; node port 14337.
- **Append mode left at the default (`appender`)** — the Phase 2 path is active.
  Rollback remains available via `-Dyano.archive.ducklake.append-mode=legacy`
  without a rebuild.

## 6. Startup — locator rebuild fired but completed in 7 m 36 s

Startup reached `UTXO archive tables: [...]` at 16:26:40 and then went quiet. A
thread dump confirms the cause precisely:

```
"main" ... java.lang.Thread.State: RUNNABLE
  at org.sqlite.core.NativeDB._exec_utf8(Native Method)
  at org.sqlite.jdbc3.JDBC3Statement.executeUpdate
  at DuckLakeTransactionLocator.rebuild(DuckLakeTransactionLocator.java:83)
  at DuckLakeTransactionLocator.rebuildIfRequired(DuckLakeTransactionLocator.java:77)
  at DuckLakeHistoryArchiveBackend.<init>(DuckLakeHistoryArchiveBackend.java:155)
```

Line 83 is `DELETE FROM tx_locator` across roughly 29M rows in a **5.2 GB**
locator. This is ADR-034 finding L2 / ADR-038 Phase 5, pre-existing and unrelated
to Phase 2, and was accepted in advance as the cost of a single combined restart.

**It completed in 7 m 36 s, against the 4 h 05 m precedent** — roughly 30x
cheaper despite a larger locator (5.2 GB versus 4.2 GB). The earlier case followed
an *unclean* shutdown in which `backend.close()` was skipped and the locator was
left mid-flight with a 2.68 GB WAL to recover; this time the WAL had checkpointed
to 0 bytes. So the 4-hour figure appears to be the pathological case, not the
normal cost of a rebuild, and my earlier characterisation of the rebuild as
inherently "multi-hour" was too pessimistic.

### New finding — why the rebuild fires even after a clean shutdown

The previous shutdown was clean: the locator WAL checkpointed to **0 bytes**, so
the locator was closed properly. The rebuild still ran, which means the
generation comparison in `rebuildIfRequired` failed for a reason other than an
unclean close.

The likely mechanism is that **maintenance advances the DuckLake generation
without advancing the locator**: `maintain()` creates snapshots
(`merge_adjacent`) but only a successful commit calls `updateTransactionLocator`.
Any maintenance after the last commit therefore leaves
`generation(locator) != currentSnapshot`, and the next open performs a full
rebuild. That would make the multi-hour rebuild effectively unavoidable on most
restarts, not a consequence of unclean shutdown alone.

This strengthens the case for Phase 5's generation-tolerant locator and delta
journal, and should be verified directly before Phase 5 is designed in detail.

## 7. First validation window — Phase 2 worked, and exposed a larger bottleneck

The early "HEALTHY, zero stuck pauses" reading did not survive sustained load.

**Phase 2 itself is confirmed working.** Across every commit, append, logical-key
verification, staging copy and the DuckLake commit together finish in **under
0.4 s**. The Appender path is not the constraint.

**The transaction locator is ~100% of write-session time.**

| dataset | commits | avg total | avg locator | locator share |
|---|---:|---:|---:|---:|
| `ACCOUNT_EVENT` | 6 | 283.4 s | 283.4 s | **100.0%** |
| `ADDRESS_TRANSACTION` | 3 | 341.4 s | 341.3 s | **100.0%** |
| `TRANSACTION` | 6 | 288.7 s | 288.6 s | **100.0%** |
| `UTXO_HISTORY` | 5 | 269.7 s | 269.5 s | **99.9%** |

Representative lines:

```
TRANSACTION   rows=3045   total=342.963s copy=0.002s commit=0.006s LOCATOR=342.947s (100.0%)
ACCOUNT_EVENT rows=265    total=340.205s copy=0.001s commit=0.006s LOCATOR=340.193s (100.0%)
UTXO_HISTORY  rows=115197 total=342.907s copy=0.050s commit=0.012s LOCATOR=342.578s (99.9%)
```

Three of those four datasets supply **zero** locator entries and still pay
~280–340 s, which rules out transaction-hash volume as the cause.

### Pre-fix throughput baseline

From the preserved sampler (`adr/reports/adr-038-samples/adr-038-phase2-prefix-sampler.txt`),
16:34:40 → 18:14:59:

| dataset | blocks advanced | blocks/s |
|---|---:|---:|
| `account_event` | 800 | 0.133 |
| `transaction` | 700 | 0.117 |
| `utxo_history` | 800 | 0.133 |
| **`address_transaction` (slowest)** | **300** | **0.050** |

Against a pre-Phase-2 baseline of roughly 3.17 blocks/s for `utxo_history`. Stuck
pauses returned: 45 in the first 90 minutes.

### Mechanism, proven

Not the read-triggered rewind first hypothesised — no tx-status lookups are issued
on this deployment, so `block()` is never called. The cause is writer-side
generation gaps: `advance()` demanded the locator sit at exactly
`generation - 1`, while DuckLake generations also advance for maintenance
snapshots and other datasets' commits. Maintenance runs every 300 s and commits
took ~340 s, so a gap was nearly always present and almost every commit rebuilt
the entire `chain_transaction` locator. Generation progression confirms ~5
generations per ~5-minute cycle.

**This closes the unexplained gap from Phase 0**: the isolated benchmarks ran on a
fresh locator with no maintenance and measured ~0.002 s; production pays ~340 s.

## 8. Validation still outstanding

Blocked until the rebuild completes and the node serves:

- slowest-dataset blocks/s and rows/s over a multi-hour window
- per-stage attribution from the new INFO line: derive, writer wait, append,
  logical-key verification, staging copy, metadata, DuckLake commit, locator, hot commit
- commit frequency and stuck-pause count versus the baseline in §2
- RSS, temporary storage and file growth
- digest, coverage and cursor continuity; no duplicate archive rows
- a second graceful restart/recovery check after progress is observed

## 8. Safety statement

No chainstate, history, configuration or log was deleted, reset or replaced. The
preprod process was never signalled and remains running. No `kill -9` was used.
The previous jar and log are preserved with timestamps, and the Phase 2 append
path can be disabled at runtime without redeploying.
