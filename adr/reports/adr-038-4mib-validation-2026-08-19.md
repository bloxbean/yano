# ADR-038 — 4 MiB reservation calibration: mainnet validation

Date: 2026-08-19
Deployment: mainnet only (preprod untouched throughout)
Jar: rebuilt from `feat/adr-038-archive-throughput` @ `386c3c6e`
Change applied: **only** `estimated-bytes-per-block` 2 MiB → 4 MiB, in
`UtxoPrefetchConfig.disabled()` and the deployed `config/application-history.yml`

**Window status: still sampling at the time of writing.** The calibration
criteria below are already conclusive and are not expected to change. The
sampler (`adr-038-samples/adr-038-4mib-mainnet-sampler.txt`) continues to append,
specifically to answer whether the throughput regression in §"Throughput
criteria" recovers as the working set rebuilds. This report was written before the
window closed because the regression is material and time-sensitive — waiting
several more hours to surface it would not have improved the calibration verdict.

---

## Headline

**The calibration is validated. The throughput criteria could not be measured,
because an unrelated pre-existing regression took hold before this deployment
went out.**

Those two statements are independent and both are load-bearing, so the criteria
are reported separately below rather than rolled into a single verdict.

## Deployment record

| step | result |
|---|---|
| `:archive-modules:*:test` (api, core, sqlite, ducklake) | BUILD SUCCESSFUL |
| `:app:test` | BUILD SUCCESSFUL |
| `:app:quarkusBuild` | BUILD SUCCESSFUL |
| jar backup | `yano.jar.20260819-170500.pre-4mib.bak` |
| log backups | `yano.log.20260819-170500.pre-4mib.bak`, `yano.log.20260819-170500.phase2c-2mib-final.bak` |
| config backup | `config/application-history.yml.20260819-170500.pre-4mib.bak` |
| shutdown | SIGTERM, graceful, **27.8 s**, no retained lock |
| startup | **72.7 s**, profiles `history,mainnet` |
| effective setting confirmed live | `estimatedBytesPerBlock=4194304` in the startup log |
| preprod (PID 85099) | **not signalled, not restarted, untouched** |

Shutdown produced one WARNING — `archive cycle stage catchup-handoff failed;
continuing with the remaining stages: ... interrupted waiting for archive
duckdb-total`. That is the expected shutdown interrupt, caught by the per-stage
guard and logged rather than swallowed, and shutdown then completed cleanly.

### Coverage continuity across the restart

| dataset | before | after | ranges |
|---|---:|---:|---|
| `account_event` | 13,523,250 | 13,524,250 | 1, contiguous |
| `transaction` | 13,526,100 | 13,527,100 | 1, contiguous |
| `address_transaction` | 12,929,225 | 12,930,225 | 1, contiguous |
| `utxo_history` | 9,657,350 | 9,658,350 | 1, contiguous |

No regression, no gap, no split range on any dataset.

## Calibration criteria — all pass

These are the five criteria that replace the superseded "no memory-budget
underestimate" (ADR-038 Amendment 6).

| criterion | evidence | result |
|---|---|---|
| no **unreported** underestimate | `footprint estimate exceeded`: **0** occurrences post-change | **PASS** |
| no **unbounded** overshoot | no overshoot occurred; bound unexercised | **PASS** |
| no submission while accounting debt remains | no debt incurred | **PASS** |
| no lease release or cursor advance **caused by** an underestimate | `drain exceeded`: **0**; `Prefetch reaper`: **0** | **PASS** |
| reservation exceeds max observed graph with documented headroom | 4 MiB vs 2,962,432 B, **~35%** | **PASS** |
| no locator **rebuild** | `Locator full rebuild`: **0** — the Amendment 3 fix is holding | **PASS** |
| coverage continuity | 1 contiguous range per dataset across restart | **PASS** |

The 2 MiB reservation was exceeded once in 438,350 blocks. At 4 MiB it was not
exceeded at all in this window, which is the expected and intended outcome.

## Throughput criteria — not measurable in this window

`utxo_history ≥50 blocks/s`, `address_transaction ≥50 blocks/s`, and "no writer
waits or stuck pauses" are **blocked, not failed**.

Observed rates in this window are roughly **5–19 blocks/s** across all datasets,
with the three fastest advancing in near-perfect lockstep. The cause is a
transaction-locator write regression that degraded in **two stages**:

| period | `utxo_history` rate |
|---|---:|
| Phase 2c window, 13:41–15:44 | 59.84 blocks/s |
| pre-restart, 15:44–17:06 | **41.87 blocks/s** |
| post-restart, 17:06 onward | **7.29 blocks/s** |

- **Stage 1 is entirely pre-restart and pre-change.** The hourly maximum locator
  stage was 4.7–9.2 s throughout 2026-08-18 and up to 15h on 2026-08-19, then
  jumped ~10x at 16h — peaking at **77.2 s** on the *identical jar* and the
  **2 MiB** reservation, worse than anything measured since.
- **Stage 2 followed the restart**, with the process 94% idle, RSS at 1.5 GB
  against 10.4 GB before, and the disk at ~3,300 IOPS of small random reads. The
  most consistent explanation is a warm cache the process can no longer rebuild
  now that the locator is 20.64 GB.
- **The 4 MiB change is not the cause of either.** It cannot affect throughput by
  construction — admission is bound by `maxInFlightBlocks` (8), not the byte
  budget — and it is the *only* non-documentation change between the two jars.

The restart was authorised and required to apply the change, so stage 2 is a cost
of the deployment even though the change itself is not its cause. That distinction
is recorded rather than elided.

Full diagnosis, including the verified mechanism and why all four datasets move in
lockstep: [`finding-locator-write-cliff-2026-08-19.md`](finding-locator-write-cliff-2026-08-19.md).

**Reporting these as calibration failures would be wrong, and reporting them as
passes would be worse.** They are recorded as blocked, with the blocker attributed
and documented.

### The Phase 2c result is unaffected

The 31.57 → 59.84 blocks/s measurement was taken 13:41–15:44, entirely before the
cliff. It stands as recorded.

## Health status during the window

Health reported **DEGRADED** from 17:16 onward, with detail:

```
DuckLake snapshot open failed: Invalid Error: Failed to probe DuckLake metadata:
Failed to prepare query "SELECT type FROM sqlite_master WHERE
lower(name)=lower('ducklake_metadata');": database is locked
```

Assessment: **a record of failed probes, not a stuck writer.** While DEGRADED,
`observedAt` stayed frozen at `09:16:12Z` while `generation` continued to advance
(59145 → 59164 → …), and all four workers stayed `IDLE` with committed generations
advancing. The probe contended for a SQLite lock during a long locator stage — the
same root contention as the cliff.

**It self-cleared.** Health returned to HEALTHY at **17:58:15**, roughly 37 minutes
after first going DEGRADED, and stayed HEALTHY thereafter. So a re-observation
path does exist and does downgrade a transient failure back to healthy.

> **Correction.** An earlier draft of this report asserted that the status does not
> clear, and cited it as ADR-034 review finding D manifesting live. That was wrong
> — it was written while the condition was still active, before the sampler had
> observed recovery. Finding D's substance (no metrics, no readiness check, no
> machine-alertable signal) is unaffected; the specific "never clears" claim is
> withdrawn.

Two samples (17:42:42, 18:13:51) recorded a **parse error** because
`/api/v1/status` returned nothing within its 25 s timeout, with process CPU at
0.4% and 1.7% at those moments. The status endpoint is therefore occasionally
unresponsive while the writer gate is held through a long locator stage — a
read-path symptom of the same contention, worth noting because `/status` is what
an operator would reach for during exactly this condition.

## What was deliberately not done

Per the standing constraints: no compaction, no data deleted, no retention or
maintenance change, no preprod contact, no `kill -9`, no second restart, no
parallelism increase, no PRAGMA or VACUUM experiment on the live locator, nothing
pushed. No change was made in response to the locator finding.
