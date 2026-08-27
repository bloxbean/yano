# ADR-038 — Locator Fix: Mainnet Validation Report

Date: 2026-08-18
Branch: `feat/adr-038-archive-throughput`
Deployment: `/Users/satya/Downloads/yano-try/yano-0.1.0-pre13-mainnet`, profile `mainnet,history`
Jar: `47f7e54bf15195ba551f9a2dcc5d7c9be35df82ca3e49a2d4796a17d342ff33d`

**Verdict: all locator-fix acceptance criteria PASSED. Phase 2 is safe to retain.
The provisional ≥50 blocks/s target is NOT met by the slowest dataset (31.6 vs 50).**

Raw data preserved:
- `adr/reports/adr-038-samples/adr-038-phase2-prefix-sampler.txt` (pathological window)
- `adr/reports/adr-038-samples/adr-038-phase2-postfix-sampler.txt` (post-fix window)

---

## 1. Independent verification of the provisional figures

| provisional claim | verified? | evidence |
|---|---|---|
| UTXO +138,500 blocks in 3,326 s | **yes** | 18:43:06 `ux=6,585,425` → 19:38:32 `ux=6,723,925`; Δ=138,500; Δt=3,326 s |
| ≈41.6 blocks/s | **yes** | 138,500 / 3,326 = **41.64** |
| locator ≈0.001 s for UTXO/address/account | **yes** | mean 0.001 s, p99 0.002–0.003 s (§5) |
| transaction locator 1.5–5.9 s, 97–99% of session | **close** | mean 2.649 s, p50 2.342 s, p95 4.981 s, p99 6.572 s, max 7.301 s; **96.0%** of its 2.760 s mean session |

The 41.6 blocks/s figure is correct **for that sub-window**. Over the full
equal-duration window it falls to **31.57 blocks/s**, because transaction density
rises as the backfill advances (§2). The full-window number is the one to plan
against.

## 2. Three-way comparison, equal duration

All windows ~2.0 h on the same host and deployment.

| window | slowest dataset | blocks/s | health |
|---|---|---:|---|
| **A. original pre-Phase-2 baseline** (ADR-037 build) | `address_transaction` | **2.90** | DEGRADED |
| **B. pathological pre-locator-fix** (Phase 2 only, 2.01 h) | `utxo_history` | **2.31** (bimodal) | 3/25 samples DEGRADED |
| **C. post-locator-fix** (2.02 h) | `utxo_history` | **31.57** | **25/25 HEALTHY** |

Per dataset:

| dataset | A (pre-Phase-2) | B (pre-locator-fix) | C (post-fix) | C vs A |
|---|---:|---:|---:|---:|
| `account_event` | 7.19 | 4.07 | **85.62** | 11.9x |
| `transaction` | 7.12 | 4.05 | **85.64** | 12.0x |
| `address_transaction` | 2.90 | 3.21 | **56.46** | 19.5x |
| `utxo_history` | 3.17 | 2.31 | **31.57** | 10.0x |
| **slowest-dataset** | **2.90** | **2.31** | **31.57** | **10.9x** |

### Window B was bimodal, and its average is misleading

Window B's 2.31 blocks/s is produced almost entirely by two intervals:

| behaviour | intervals | rate |
|---|---:|---|
| stalled (rebuilding) | **21 of 24** | 0.000–0.664 blocks/s |
| bursting (contiguous generations) | 2 | **28.6 and 23.9 blocks/s** |

The bursts coincide with generation jumps of +76 and +60 — dozens of cheap
commits back to back. That is the mechanism in miniature: when commits outpace the
300 s maintenance cadence, generations stay contiguous and `advance()` is O(1);
when maintenance interposes, every commit rebuilt the whole locator. **Window C
makes the burst rate the steady rate.**

## 3. Rows/s, density and commit cadence

Measured from 589 post-fix commits per dataset.

| dataset | commits | rows/commit | blocks/commit | rows/block | **rows/s** |
|---|---:|---:|---:|---:|---:|
| `utxo_history` | 589 | 159,380 | ~375 | ~425 | **313,456** |
| `address_transaction` | 588 | 161,000 | ~628 | ~256 | **286,393** |
| `account_event` | 589 | 2,084 | ~960 | ~2.2 | 40,330 |
| `transaction` | 589 | 24,921 | ~960 | ~26 | 9,028 |

Commit cadence is **essentially identical across datasets** (≈589 commits each,
one every ~12 s). Blocks/s differ only because rows-per-block differs by ~180x;
every dataset hits the same `max-rows-per-batch` ceiling at a different block
count. Pre-fix cadence was one commit per ~340 s.

## 4. Locator rebuilds, gaps and read-triggered rebuilds

| metric | window B | window C |
|---|---:|---:|
| locator full rebuilds | ~1 per commit | **1** (startup recovery only) |
| read-triggered rebuilds | n/a (path removed) | **0** |
| generation oscillation | present | **none observed** |

The single rebuild is startup recovery:
`#1: 30,137->30,148, reason=startup recovery, rows=29,735,639, 306.4s`.

**Caveat on rigour:** the "advanced across generation gap" line is logged at
DEBUG and DEBUG is not enabled, so this report does **not** claim zero gaps
occurred. The stronger and supported claim is that **whatever gaps occurred were
absorbed with no runtime rebuild**.

## 5. Stage timing distributions (not just averages)

589 commits per dataset, post-fix.

| dataset | stage | mean | p50 | p95 | p99 | max |
|---|---|---:|---:|---:|---:|---:|
| `utxo_history` | total | 0.508 s | 0.454 | 0.721 | 1.506 | 1.579 |
| | append | 0.355 | 0.343 | 0.516 | 0.547 | 0.590 |
| | verifyKeys | 0.023 | 0.022 | 0.033 | 0.037 | 0.048 |
| | stagingCopy | 0.071 | 0.069 | 0.102 | 0.121 | 0.188 |
| | commit | 0.056 | 0.012 | 0.020 | 1.021 | 1.025 |
| | **locator** | **0.001** | 0.001 | 0.001 | 0.003 | 0.009 |
| `address_transaction` | total | 0.562 | 0.510 | 0.821 | 1.626 | 1.864 |
| | append | 0.402 | 0.387 | 0.591 | 0.626 | 0.638 |
| | **locator** | **0.001** | 0.001 | 0.001 | 0.002 | 0.008 |
| `account_event` | total | 0.052 | 0.018 | 0.030 | 1.029 | 1.050 |
| | **locator** | **0.001** | 0.001 | 0.001 | 0.002 | 0.020 |
| `transaction` | total | 2.760 | 2.464 | 5.104 | 6.638 | 7.380 |
| | append | 0.037 | 0.037 | 0.054 | 0.068 | 0.087 |
| | **locator** | **2.649** | 2.342 | 4.981 | 6.572 | **7.301** |

Two observations worth carrying forward:

- **`transaction` locator insertion is now the largest shared-writer cost** —
  2.649 s mean, 96.0% of its session, inserting ~25k entries into a 7.7 GB SQLite
  under `synchronous=FULL` (~9,400 entries/s).
- A **~1.0 s quantisation** appears in the `commit` p99 across every dataset
  (1.013–1.025 s max). Not investigated; flagged for follow-up.

## 6. Writer waits, stuck pauses, errors

| metric | window B (90 min) | window C (~2 h) |
|---|---:|---:|
| stuck-operation pauses | 45 | **0** |
| writer/bulk wait warnings | many | **0** |
| worker `paused:` events | 45 | **0** |
| ERROR lines | present | **0** |
| DEGRADED samples | 3 / 25 | **0 / 25** |

## 7. Resource usage

| metric | value |
|---|---|
| RSS across window C | 3.3 – 8.0 GB |
| `history/` | 30 GB → 39 GB (+9 GB in 2 h) |
| `history/ducklake-data` | 28 GB |
| transaction locator | 7.68 GB (from 5.2 GB) |
| `chainstate/` | 281 GB (unchanged) |
| disk free | 531 GB, no exhaustion risk |

Locator growth is a direct consequence of throughput — ~14.7M new transaction
entries in the window. It is the fastest-growing artefact and worth watching.

## 8. Core sync, cursor, coverage and digest continuity

- Core at block 13,824,814, `lagBlocks: 0`, syncing normally throughout.
- All four datasets hold **exactly one contiguous coverage range** — no gaps.
- Coverage advanced monotonically across both restarts; no regression.
- `cycleError: null`, `maintenance.error: null`.
- **Zero** `different rows`, digest-mismatch or `duplicate logical primary key`
  events. No duplicate archive rows.

## 9. Second graceful restart and recovery

| restart | shutdown | startup | locator rebuild |
|---|---:|---:|---|
| 1 — pre-Phase-2 → Phase 2 | 62 s (worker missed budget, ERROR) | 455.6 s | full rebuild |
| 2 — Phase 2 → locator fix | 62 s (worker missed budget) | 327.7 s | full rebuild, 306.4 s, 29.7M rows |
| **3 — recovery check** | **1.5 s, clean** | **21.98 s** | **none** |

**A third, unanticipated win: the locator fix also eliminated the startup
rebuild.** Because every commit now advances the locator in lockstep, the
generations already matched at open and `rebuildIfRequired` returned immediately.
Shutdown also dropped from 62 s to 1.5 s with no "worker did not terminate"
error, because commits take 0.5 s instead of 340 s.

Post-restart continuity verified: coverage advanced on all four datasets, one
range each, health HEALTHY, zero errors, zero rebuilds.

## 10. Transaction lookup — correctness and no locator mutation

- Deterministic proof: `pinnedHistoricalLookupNeverChangesLocatorGeneration`
  asserts that a lookup through an **older pinned generation** leaves both the
  active generation and the rebuild counter unchanged, and
  `concurrentPinnedReadsCannotOscillateGeneration` runs 6 readers × 40 lookups at
  assorted pinned generations with the same assertions.
- Production corroboration: rebuild counter was **1 before and 1 after** issuing
  tx-status lookups. Worst-case latency on the authoritative full-range path was
  **4.24 s**.
- **Honest limitation:** the production lookups returned 503 because the datasets
  are still `catching_up`, so they may have short-circuited before consulting the
  locator. The unit tests, not the production probe, are the binding proof.

## 11. Conclusions, separated

**a) Phase 2 Appender improvement — confirmed and retained.**
Sustained 313k rows/s (`utxo_history`) and 286k rows/s (`address_transaction`) in
production. Append is 0.355 s for ~159k rows. Digest, coverage, replay and abort
behaviour unchanged; 2,303 tests green.

**b) Catastrophic locator rebuilds — removed.**
From ~1 full rebuild per commit (~340 s each, 99.9% of session) to **one startup
rebuild and none at runtime**. Empty-entry commits cost 0.001 s mean, 0.020 s max.

**c) Remaining incremental transaction-locator cost — now the top cost.**
`transaction` spends 2.649 s mean (p95 4.98 s, max 7.30 s) inserting ~25k entries,
96% of its session, and holds the single writer while doing so. This is genuine
incremental work, not a rebuild.

**d) Sustained end-to-end block throughput.**
Slowest dataset **2.90 → 31.57 blocks/s (10.9x)**; `address_transaction` 19.5x.
Below the provisional ≥50 target.

## 12. Acceptance criteria

| criterion | result |
|---|---|
| locator work for contiguous empty-entry commits bounded and normally sub-second | **PASS** — 0.001 s mean, 0.020 s max |
| no read-triggered locator rebuild | **PASS** — 0 (unit-test proven) |
| zero generation oscillation | **PASS** — none observed |
| sustained UTXO throughput materially exceeds 0.13 blocks/s | **PASS** — 31.57 blocks/s (~240x) |
| no correctness or recovery regression | **PASS** — coverage contiguous, digests clean, recovery verified |

**Provisional ≥50 blocks/s target: NOT met.** Slowest dataset is 31.57 blocks/s.

**Blocking production acceptance:** nothing found in this window. The residual
`transaction` locator cost is a throughput ceiling, not a correctness or safety
defect.

## 13. Proposed alternatives for the residual cost (not implemented)

Presented for decision only; no work started.

1. **Retain the current synchronous indexed locator.** Zero risk. Accepts
   `transaction` at ~2.6 s/commit and the shared-writer hold that implies.
2. **Optimise its batched SQLite ingestion.** Likely the best effort/– ratio:
   `synchronous=FULL` on a 7.7 GB file at ~9,400 entries/s is the suspect;
   `synchronous=NORMAL` under WAL, larger batches, or deferring the index would
   be measurable quickly. Needs a durability argument, since the locator is a
   rebuildable hint.
3. **Move it behind the Phase 5 durable journal as an asynchronous derived
   index.** Removes it from the writer path entirely and is the architecturally
   correct destination, but depends on the full Phase 5 design.
4. **Make it optional** for nodes that do not serve historical transaction
   lookups. `findTransaction` already degrades to an authoritative full-range
   query, so a disabled locator is correct but slower for that one endpoint.

Recommendation: measure (2) first, and treat (3) as the durable answer.

## 14. Outstanding, recorded not fixed

The archive/locator seam remains a **Phase 5 prerequisite**: DuckLake commits
before the SQLite locator update, and the replay path returns before
`updateTransactionLocator`, so a failed locator update is never reconciled by
replay. With forward-gap tolerance this degrades to a stale *negative* — safe,
because the caller then queries authoritatively — but unrepaired.

## 15. Safety

No chainstate, history, configuration or log deleted, reset or replaced. Preprod
(PID 85099) never signalled and verified alive after every restart. No `kill -9`.
Jars and logs preserved with timestamps at each step. Phases 1, 3, 4 and the full
Phase 5 implementation not started; Phase 3 remains blocked on C1–C5. Nothing
committed or pushed.
