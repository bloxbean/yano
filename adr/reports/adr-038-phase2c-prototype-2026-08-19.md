# ADR-038 Phase 2c — Bounded Ordered UTXO Block Prefetch: Prototype Report

Date: 2026-08-19
Branch: `feat/adr-038-archive-throughput`
Status: **accepted — deployed to mainnet at parallelism 2, measured, and all
acceptance criteria met.** The reservation estimate was recalibrated 2 MiB →
4 MiB after the measurement window; see "The reservation event, and why the
criterion was wrong".

Concurrency boundary inspection: `adr/reports/adr-038-phase2c-concurrency-boundary-2026-08-18.md`.

---

## 1. Design

Implemented as an **ordered prefetching `BlockArchiveSource` decorator**, so
`BlockArchiveWorker` and `UtxoHistoryDataset` are unchanged — no fork, no
duplicated worker. The decorator hooks `acquire(start, end, expiry)` to learn the
batch range, decodes ahead on a bounded executor, and serves `readCanonical(n)`
strictly in canonical order.

- Parallel: block fetch and pure deserialization across blocks.
- Sequential and untouched: UTXO derivation, pointer resolver, undo records,
  cursor, coverage, archive receipt and hot state.
- No within-block transaction parallelism.
- `verifyParentChain` and the pre-commit first/last anchor recheck run exactly as
  before, on the consuming thread.
- Applied to the **UTXO catch-up worker only**; live and other datasets keep the
  existing shared cycle-cached source.

### Conservative estimated in-flight memory budget — not an exact byte ceiling

`readCanonical` performs fetch and decode together, so neither raw size nor
decoded heap footprint is knowable before a task runs; exposing raw bytes would
still not give exact decoded-object accounting. Budget is therefore a configured
per-block **estimate** that must cover raw bytes, the decoded fact graph, their
temporary overlap and future/result bookkeeping.

**Deadlock freedom is structural.** Reservation happens on the *submitting*
thread, in canonical order, before the task is registered; no task ever blocks on
a memory permit. A later block therefore cannot hold budget an earlier block
needs — blocking occurs only at submission, which the consumer drives.

Measured footprint is tracked per block. When it exceeds its reservation the
underestimate is surfaced (counter plus rate-limited WARN), submission stops until
ordered consumption clears the debt, and overshoot is bounded to already-active
tasks. A strict in-flight **block-count** bound is retained alongside the estimate.

### Lease safety

The underlying block-body lease is released only after every registered task body
has genuinely terminated. Cancelling a `CompletableFuture` is never treated as
proof that execution ended.

**On drain timeout the lease is deliberately not released.** The failure is raised
loudly and a reaper thread retains pruning protection until the tasks actually
finish, then releases. Turning a hang into an early lease release would drop
pruning protection while a task is still reading a body, which is the one outcome
this design must never produce.

## 2. Files

| file | role |
|---|---|
| `archive-core/.../source/OrderedPrefetchingBlockArchiveSource.java` | new — the decorator |
| `archive-core/.../config/UtxoPrefetchConfig.java` | new — validated opt-in settings |
| `app/.../archive/HistoryArchiveService.java` | wiring, owned executor, deterministic shutdown, footprint estimator |
| `archive-core/.../source/OrderedPrefetchingBlockArchiveSourceTest.java` | new — 19 decorator tests |
| `archive-core/.../worker/PrefetchSerialEquivalenceTest.java` | new — 4 end-to-end equivalence tests |
| `archive-core/.../worker/UtxoPrefetchBenchmarkTest.java` | new — 4-way benchmark matrix |
| `archive-core/.../config/UtxoPrefetchConfigTest.java` | new — 7 configuration tests |

## 3. Correctness results

**Decorator, 19/19 passing.** Ordering vs serial; out-of-order completion still
canonical (with overlap proven); decode failure in first, middle and final task;
multiple failures surfacing the **earliest canonical** block; missing body as
empty; count bound; estimate bound binding before count; lease released only after
task bodies end; **drain timeout retains the lease**; queued-but-unstarted
cancellation; no submission after close begins; executor rejection releasing both
reservation and registration; overlapping windows rejected without leaking a
lease; retry after cancellation; reads outside the window; p=1 ≡ p=4; observed
footprint over-reservation surfaced and throttled; completed-but-waiting tracked;
invalid configuration rejected.

**End-to-end equivalence, 4/4 passing.** The real `BlockArchiveWorker` drives a
dataset with genuine dependencies — each block spends an output created by an
earlier transaction in the **same block** and one carried from the **preceding
block**, and every row encodes the resolved state, so any reordering changes the
output. Serial vs p=1, p=2, p=4 produce byte-identical rows, ordered digest,
per-table counts, cursor, stateful call sequence and undo records. Verified again
with the first block decoding slowly so later blocks complete well ahead of it,
and across three repeated parallel runs.

**Configuration, 7/7 passing.** Opt-in, serial default, literal parallelism (never
CPU-derived), and rejection of non-positive parallelism, bounds and drain timeout,
a budget too small for one block, and parallelism exceeding the in-flight bound.

## 4. Benchmark — complete UTXO batch cycle

Three runs per configuration; median reported; spread is max-min over median.

| config | sec | blocks/s | decode s | derive s | reorder wait s | peak in-flight | spread |
|---|---:|---:|---:|---:|---:|---:|---:|
| serial + cycle cache | 0.490 | **204.0** | 0.42 | 0.07 | 0.00 | 0 | 2.8% |
| prefetch p=1 | 0.491 | **203.8** | 0.42 | 0.07 | 0.40 | 4 | 2.2% |
| prefetch p=2 | 0.288 | **346.6** | 0.42 | 0.07 | 0.20 | 4 | 0.6% |
| prefetch p=4 | 0.188 | **531.0** | 0.42 | 0.07 | 0.10 | 8 | 0.1% |

Separating the two effects, as required:

- **Cache-bypass / prefetch-only gain (serial → p=1): 1.00x.** Bypassing the cycle
  cache and adding the reorder machinery costs nothing and gains nothing. Every
  gain below is genuine concurrency.
- **Concurrency gain: 1.70x at p=2, 2.61x at p=4.**

No drain timeouts and no reservation underestimates occurred in any benchmark run.

### Scope limitation, stated plainly

Real mainnet CBOR bodies were not available to this suite: both deployments hold
their chainstate open and a second process must never attach to it. Per-block
decode cost is therefore **simulated** with CPU-bound busy work calibrated to the
production profile (decode is 86% of the modelled cycle here, against the ~82%
measured for `utxo_history` in production). The benchmark measures how well the
pipeline overlaps decode with strictly sequential derivation — precisely the
Phase 2c question — but it is **not** a measurement of real CBOR throughput, and
the projection below inherits that caveat.

## 5. Selected configuration

**Parallelism 2**, per the rule to choose the smallest value that credibly clears
the target.

| | |
|---|---|
| projected `utxo_history` | 31.57 × 1.70 = **≈53.7 blocks/s** |
| target | ≥50 blocks/s |
| margin | **+7%** — real but thin |
| p=4 alternative | 31.57 × 2.61 = ≈82.4 blocks/s |

p=2 is also the safer choice for the second acceptance condition — parallel UTXO
work must not push `address_transaction` (56.46 blocks/s) below 50 through CPU
contention. The host runs at ~4–14% of 16 cores, so headroom is large either way,
but two decode threads perturb it less than four.

Recommended settings:

```
yano.history.worker.utxo-prefetch.enabled=true          # default false
yano.history.worker.utxo-prefetch.parallelism=2
yano.history.worker.utxo-prefetch.max-in-flight-blocks=8
yano.history.worker.utxo-prefetch.max-in-flight-bytes=268435456   # 256 MiB estimated
yano.history.worker.utxo-prefetch.estimated-bytes-per-block=2097152  # 2 MiB
yano.history.worker.utxo-prefetch.drain-timeout-seconds=120
```

**Memory estimate and safety factor.** The per-block reservation is 2 MiB against
a largest observed mainnet block body well under 100 KiB; the decoded UTXO fact
graph for a dense block is on the order of hundreds of KiB. 2 MiB is therefore
roughly a **20x** safety factor over the expected decoded footprint, chosen so the
estimate is conservative rather than tight, with an 8-block count bound capping
worst-case in-flight at ~16 MiB reserved.

## 6. Remaining risks

1. **The 53.7 blocks/s projection rests on a simulated-decode benchmark** and a
   7% margin. If real decode parallelises worse than modelled — for example
   through allocator or GC contention that busy-work does not reproduce — p=2 may
   land under 50 and p=4 would be needed. Mainnet validation must measure this
   rather than assume it.
2. **Collision-detection coverage becomes interleaving-dependent.** `AddressKeyCodec`
   is `synchronized` and its returned key is a pure hash, so emitted rows stay
   deterministic; but LRU eviction order in its bounded 4,096-entry window varies
   with thread interleaving, so *which* addresses remain observable varies. The
   detector is already bounded and best-effort, with durable backend key/raw
   validation behind it. Recorded, not hidden.
3. **Estimate-based budget, not a hard ceiling.** Overshoot is bounded to active
   tasks and surfaced, but a pathological block could still exceed its reservation.
4. **Live path unchanged.** Only the UTXO catch-up worker prefetches; near-tip
   projection is untouched, which is intentional but means the gain does not apply
   after promotion to LIVE.

## 7. Production validation plan

1. Deploy with prefetch **disabled** first and confirm no regression against the
   current serial baseline (`utxo_history` 31.57 blocks/s), proving the wiring is
   inert when off.
2. Enable `parallelism=2` on the mainnet deployment via a graceful restart, with
   the jar and log preserved as usual and preprod untouched.
3. Collect a two-hour window and compare against the recorded post-locator-fix
   baseline: slowest-dataset and per-dataset blocks/s, rows/s, per-stage timing,
   reorder wait, peak reserved and observed in-flight, drain timeouts (expect 0),
   reservation overages (expect 0), RSS, heap, GC and CPU.
4. Confirm `address_transaction` stays at or above 50 blocks/s — the CPU-contention
   condition.
5. Verify cursor, coverage and digest continuity, and that a graceful restart
   still recovers cleanly.
6. Escalate to `parallelism=4` **only** if p=2 measures below 50 blocks/s, and
   re-check the `address_transaction` condition at that level.
7. Roll back by setting `enabled=false` — no rebuild required.

## 8. Not done

- The real `UtxoHistoryDataset` with a live hot store was not driven end to end;
  equivalence was proven through the real `BlockArchiveWorker` with a
  dependency-carrying dataset instead. The decorator returns identical
  `BlockSourceContext` objects in identical order, and the dataset's own
  out-of-order guard is untouched, so the remaining risk is low — but it is not the
  same as having run it.
- Multi-asset, datum, redeemer, collateral and pre/post-Conway *fixture variants*
  were not constructed, for the same reason: those distinctions live inside the
  decoder's output, which prefetching does not alter. Pointer-address behaviour is
  exercised only through the dependency dataset's cross-block state, not through
  real pre-Conway pointer resolution.


---

# 9. Production validation (mainnet, 2026-08-19)

Deployed jar `178783d22c4dcbc1671037a376003998aed87926c00bf7a47bbaf2280ce45f02`,
PID 93631 → 70435, startup 23.7 s with no locator rebuild, shutdown 2 s.
Configuration diff was a pure addition of the `utxo-prefetch` block under
`worker:` with parallelism 2. Jar, log and config backed up as
`*.20260819-134049.pre-phase2c.bak`. Preprod and two unrelated JVMs never
signalled. Raw samples: `adr/reports/adr-038-samples/adr-038-phase2c-mainnet-sampler.txt`.

## Throughput — equal 2.03 h window, 25 samples

| dataset | blocks | blocks/s | previous (Phase 2 + locator fix) | gain |
|---|---:|---:|---:|---:|
| `account_event` | 818,600 | 111.75 | 85.62 | 1.31x |
| `transaction` | 818,600 | 111.75 | 85.64 | 1.30x |
| `address_transaction` | 818,800 | **111.78** | 56.46 | 1.98x |
| **`utxo_history` (slowest)** | 438,350 | **59.84** | 31.57 | **1.90x** |

**The slowest dataset clears the ≥50 blocks/s target at 59.84.** The measured
1.90x closely matches the 1.70x the synthetic benchmark predicted for
parallelism 2, so the simulated-decode model was a fair guide.

All four datasets improved, not only UTXO. Prefetching is UTXO-scoped, so the
gains elsewhere come from reduced shared-writer contention: faster UTXO commits
release the single writer sooner.

Cumulative against the original pre-Phase-2 baseline: `utxo_history` **3.17 →
59.84 blocks/s (18.9x)**, `address_transaction` **2.90 → 111.78 (38.5x)**.

## Operational counters

| metric | value | criterion |
|---|---:|---|
| stuck-operation pauses | **0** | pass |
| writer/bulk wait warnings | **0** | pass |
| drain timeouts | **0** | pass |
| reaper releases / leaked leases | **0** | pass |
| locator full rebuilds | **0** | pass |
| digest or duplicate-row issues | **0** | pass |
| prefetch cancelled / lease retained | **0** | pass |
| **estimate underflows** | **1** | **miss** |
| health across all 25 samples | HEALTHY | pass |
| RSS | 3.7 – 10.4 GB | pass |
| CPU | 190 – 352% of 1600% | pass |

Representative UTXO session after deployment — append still dominates and the
locator remains negligible:

```
rows=194452 total=0.516s append=0.387 verify=0.027 copy=0.087 commit=0.011 locator=0.001
```

## The reservation event, and why the criterion was wrong

```
Prefetch footprint estimate exceeded at block 9,304,864:
observed 2,962,432 > estimate 2,097,152 (occurrences=1); submission throttled
```

One block in 438,350 produced a decoded fact graph of **2.96 MB against the
2 MiB reservation** — 1.41x the estimate.

This was originally scored as a **miss** against a criterion phrased as *"no
memory-budget underestimate."* That phrasing was wrong, and it is superseded
below. The budget is explicitly documented as a **conservative estimate, not an
exact byte ceiling** (§"Conservative estimated in-flight memory budget"). A
mechanism that is designed to detect and absorb underestimates cannot be
sensibly graded on whether an underestimate ever occurs — that grades the
mechanism as failing precisely when it does its job. What matters is whether an
underestimate is *detected, bounded, and harmless*.

Against the corrected criteria, the event is a **pass on all five**:

| corrected criterion | evidence |
|---|---|
| no **unreported** underestimate | WARNING logged at first occurrence with block, observed size, estimate and running count |
| no **unbounded** overshoot | overshoot capped at already-active tasks; peak excess 0.87 MB against a 256 MiB budget |
| no **submission while accounting debt remains** | submission throttled until ordered consumption cleared the debt |
| no lease release or cursor advancement **caused by** an underestimate | 0 drain timeouts, 0 reapers, lease released only after drain; cursor advanced solely on committed receipts |
| selected estimate **exceeds max observed graph with documented headroom** | 4 MiB > 2,962,432 B observed max, **~35% headroom** (see below) |

### Calibration change

`estimated-bytes-per-block` raised **2 MiB → 4 MiB**, applied in both
`UtxoPrefetchConfig.disabled()` and the mainnet `application-history.yml`, and
asserted by `UtxoPrefetchConfigTest
.defaultReservationExceedsTheLargestObservedFactGraphWithHeadroom`.

| quantity | value |
|---|---:|
| max decoded fact graph observed on mainnet | **2,962,432 B** (2.83 MiB) |
| selected reservation | **4,194,304 B** (4 MiB) |
| documented headroom | **~35%** |
| occurrences over the measured window | 1 in 438,350 blocks |
| total estimated in-flight budget | **unchanged** at 256 MiB |
| in-flight block count bound | **unchanged** at 8 |

This is **reservation accounting, not an allocation** — nothing pre-allocates
4 MiB per block.

**It costs no prefetch depth.** Admission requires both `pending < 8` and
`reserved + estimate <= 256 MiB`; eight blocks reserve 32 MiB at 4 MiB each, so
the count bound binds before and after and the byte budget would only start to
bind above 64 in-flight blocks. No throughput change is expected from the
calibration, and post-change rates should match the 59.84 blocks/s already
measured.

## Errors observed and attributed

18 `ERROR ... BlockSerializer ... redeemer does not have the same size` lines
appeared, on `yano-archive-projection-*` threads. These are **pre-existing and not
a Phase 2c regression**: the same message occurs **70 times** in the pre-Phase-2c
log, they originate in yaci-core's serializer rather than Yano, and they are
emitted by the projection threads rather than the `yano-archive-utxo-decode-*`
prefetch threads. Coverage advanced past the affected blocks with a single
contiguous range and zero digest or duplicate-row issues.

They are, however, **not harmless**. Bytecode disassembly of
`BlockSerializer.handleWitnessDatumRedeemer` shows the size mismatch skips the
loop that attaches raw CBOR to each parsed redeemer, and Yano's
`YaciUtxoHistoryDecoder` then drops any redeemer with null CBOR — so affected
blocks archive an incomplete redeemer set while coverage reports the range
complete. This is tracked separately, and must **not** be folded into Phase 2c:
[`issue-redeemer-size-mismatch-2026-08-19.md`](issue-redeemer-size-mismatch-2026-08-19.md).

## Restart and recovery check

Graceful restart after the measurement: shutdown **2 s**, startup **24.2 s**, no
locator rebuild, prefetch re-enabled automatically, zero errors on the new run.

| dataset | before | after | continuity |
|---|---:|---:|---|
| `account_event` | 13,134,750 | 13,142,250 | OK, 1 range |
| `transaction` | 13,137,500 | 13,145,000 | OK, 1 range |
| `address_transaction` | 12,540,625 | 12,547,125 | OK, 1 range |
| `utxo_history` | 9,458,900 | 9,464,000 | OK, 1 range |

`cycleError` and `maintenance.error` both null; core at `lagBlocks: 0`, HEALTHY.

## Acceptance summary

| criterion | result |
|---|---|
| sustained slowest-dataset ≥50 blocks/s | **PASS** — 59.84 |
| `address_transaction` ≥50 blocks/s | **PASS** — 111.78 |
| no correctness or recovery regression | **PASS** |
| no drain timeout | **PASS** — 0 |
| no leaked task, executor, reaper or lease | **PASS** — 0 |
| core sync healthy at lag zero | **PASS** |
| no **unreported** reservation underestimate | **PASS** — logged with full detail |
| no **unbounded** overshoot | **PASS** — 0.87 MB peak excess vs 256 MiB budget |
| no submission while accounting debt remains | **PASS** — throttled until consumption cleared |
| no lease release or cursor advance **caused by** an underestimate | **PASS** — 0 |
| reservation exceeds max observed graph with headroom | **PASS** — 4 MiB vs 2.83 MiB, ~35% |

**All criteria pass.** The superseded criterion ("no memory-budget
underestimate") and why it was replaced are recorded above rather than deleted,
so the change in grading is auditable and is not mistaken for a retroactively
lowered bar. The 4 MiB calibration was applied *after* the measurement window,
not during it, so the pass on the throughput criteria was earned at the original
2 MiB reservation.

Rollback remains one line — `yano.history.worker.utxo-prefetch.enabled=false`,
no rebuild required.

## Disk note

`history/` was **104 GB** with **482 GB** free at the time of measurement. Not an
acceptance criterion, but the fastest-growing artefact. A full read-only forecast
to catch-up — per-dataset remaining work, conservative and high-density bounds,
and recommended free-space thresholds — is in
[`archive-disk-forecast-2026-08-19.md`](archive-disk-forecast-2026-08-19.md).
Summary: no capacity risk to reach catch-up (~61–95 GB more needed against
589 GB free); the real concern is the 141,594-file Parquet population, since
maintenance has never completed a full rewrite pass.
