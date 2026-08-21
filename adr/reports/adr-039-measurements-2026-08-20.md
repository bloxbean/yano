# ADR-039 measurements — gates 1 and 4

**Date:** 2026-08-19 / 2026-08-20
**Branch:** `feat/adr-039-canonical-projection-outbox`

## Deployments

| Run | Purpose | Ports | Artifact SHA-256 | State |
|---|---|---|---|---|
| `adr039-phase0-baseline-preprod-20260819-230037` | history **disabled** core baseline | 6291 / 14291 | `d22c9640…50a081` | reached preprod tip (block 5,074,810, Conway), stopped cleanly |
| `adr039-projection-preprod-20260819-234600` | history **enabled**, `sink=none` | 6293 / 14293 | `42dd0da0…0b6978` | running |

Both are fresh, isolated deployments under `/Users/satya/Downloads/yano-try/`. Neither
touched any pre-existing directory.

## Gate 4 — outbox bytes per canonical block (OPEN, first real data)

Measured on a live fresh preprod sync with both first-slice sections
(`transaction:v2`, `utxo-history:v5`) enabled and no sink draining, so the figure is
total projection volume rather than a steady-state backlog.

| block | bytes/block | cumulative outbox |
|---|---|---|
| 123,000 | 42 | 5 MB |
| 285,000 | 615 | 175 MB |
| 439,000 | 899 | 0.37 GB |
| 604,000 | 1,044 | 0.59 GB |
| 699,000 | 1,439 | 0.94 GB |
| 771,000 | 1,818 | 1.31 GB |
| 945,000 | 1,878 | 1.65 GB |
| 1,082,000 | 1,888 | 1.90 GB |
| 1,183,000 | 2,059 | 2.27 GB |

The figure rises monotonically with block density and has not yet plateaued; Alonzo and
Conway ranges are still ahead. This is a **cumulative mean**, not a per-block
distribution — p95/p99 require per-block instrumentation and are not yet measured, so
ADR-039 acceptance criterion 4 is only partially satisfied.

Final observed value before the run was stopped: **2,397 bytes/block at block
1,716,699**, with 3.83 GB of accumulated outbox.

Preliminary capacity implication, to be revised as density rises: at ~2.4 KB/block a
13.8M-block mainnet genesis sync implies on the order of **33 GB** of projection volume.

**This is a no-sink ceiling, not a working-set estimate.** The measurement ran with
`sink=none`, so nothing ever acknowledged or deleted an envelope and the figure is
cumulative *total projection volume produced*. The steady-state outbox size under a
draining sink is a different quantity — it is the backlog, governed by `(Rc - Rs) * B *
elapsed`, and it is bounded by the soft/hard limits rather than by total volume. The two
must not be conflated later:

- **total volume produced** (measured here): what a sink must eventually absorb;
- **retained outbox size** (not measured): what sits in the hot RocksDB at any moment.

Under the §0.8 sink-paced regime the second approaches the first, which is exactly the
compaction and write-amplification consequence review finding D3 asked to be named. Under
a hypothetical sink that kept up, the second would stay near zero.

## Gate 1 — core-sync regression (NOT YET VALIDLY MEASURED)

A comparison was computed and then **rejected as invalid**. It is recorded here rather
than discarded, because the failure mode is easy to repeat.

Matched-block-range comparison, as computed:

| block range | baseline | projection | apparent delta |
|---|---|---|---|
| 0–250k | 3,323 blk/s | 2,534 blk/s | −23.7% |
| 250k–500k | 3,613 blk/s | 2,666 blk/s | −26.2% |
| 500k–750k | 2,228 blk/s | 1,783 blk/s | −20.0% |
| 750k–1000k | 2,440 blk/s | 1,383 blk/s | −43.3% |
| 1000k–1250k | 2,562 blk/s | 1,866 blk/s | −27.2% |

**Why this does not measure ADR-039.** The two runs covered those blocks at different
times under different machine load:

- baseline covered blocks 0–1.25M at **15:01–15:08 UTC**, running alone;
- projection covered blocks 0–1.18M at **15:51–16:00 UTC**, running **concurrently with
  the baseline node**, which was by then deep in dense Conway blocks. Load average
  during the projection window was 5–7.5.

So the numbers above conflate projection overhead with CPU and I/O contention from a
second full node syncing on the same machine. Reporting −20% to −43% as the gate-1
result would be wrong, and would wrongly condemn the design.

The honest statement is: **gate 1 is unmeasured.** The apparent regression is an upper
bound that includes contention; the true overhead is somewhere between 0% and that
figure.

### The valid experiment, specified

Run the two configurations **sequentially, each alone**, over the same bounded block
range on the same hardware:

1. stop every other Yano process and confirm with `ps`;
2. fresh chainstate, `projection.enabled=false`, sync to block N, record wall clock;
3. fresh chainstate, `projection.enabled=true`, `sink=none`, sync to block N, record
   wall clock;
4. repeat both at least twice, alternating order, and report the median.

Bounding at N ≈ 1.5M keeps each leg near ten minutes while covering Byron, Shelley,
Allegra and Mary, so the comparison spans real density change rather than only empty
early blocks.

### The valid experiment — result

Four legs, alternating `off / on / on / off`, each running **alone** on an otherwise idle
machine (only 0.1%-CPU Gradle daemons), fresh chainstate each time, wall clock to block
1,000,000, preprod. Artifact SHA-256 `997a186efca42f0cabe7debce62f54ca6679aa447b01adc3248e828a7ba3bd9b`.

| leg | mode | elapsed | reached block | blk/s | chainstate |
|---|---|---|---|---|---|
| 1 | off | 436 s | 1,000,368 | 2,294 | 10.44 GB |
| 2 | on | 482 s | 1,005,604 | 2,086 | 13.79 GB |
| 3 | on | 487 s | 1,000,379 | 2,054 | 13.65 GB |
| 4 | off | 396 s | 1,000,399 | 2,526 | 10.62 GB |

Legs stop at the first sampled block at or beyond the target and so overshoot by
different amounts; normalising to blocks/s removes that, which comparing raw wall clock
would not.

```
median off : 2,410 blk/s
median on  : 2,070 blk/s
delta      : -14.1%
threshold  : -5.0%
VERDICT    : FAIL
```

**Uncertainty is real and must be stated.** The two `off` legs differ by 10%
(2,294 vs 2,526) while the two `on` legs agree to 1.5% (2,086 vs 2,054). Taking the
extremes, the true regression lies between **-10% and -18%**, with -14% the median
estimate. Two legs per mode is the minimum useful sample and more would tighten it — but
the entire plausible range sits well above the 5% threshold, so the verdict does not
depend on where in that range the truth lies.

**Gate 1 fails.** ADR-039 anticipates exactly this: "If deterministic projection encoding
exceeds it, the design must first remove avoidable copies/encoding work. It must not hide
the overhead by making the canonical/outbox boundary best-effort."

### What the regression is made of, and one caveat

Likely contributions, in order:

1. fact derivation — `YaciBlockArchiveDecoder.project` and `YaciUtxoHistoryDecoder.project`
   on every block, on the apply thread;
2. deterministic encoding of both sections;
3. the additional RocksDB write, ~2.4 KB/block logical;
4. compaction of a column family that, at `sink=none`, **never drains**.

Item 4 deserves care. This ran with no sink, so the outbox grew monotonically and its
compaction load grew with it. Under a sink that kept up, acknowledged ranges are deleted
and the CF stays small, so this figure is **pessimistic for a draining configuration** —
but **representative of the sink-paced regime**, which §0.8 shows is the only reachable
one. It should not be quoted as the overhead of a hypothetical keeping-up sink.

No attempt was made to optimise before measuring, so the ADR's prescribed next step —
remove avoidable copies and encoding work, then re-measure — is untried and may well
recover a meaningful part of the gap.

### Disk amplification — review finding D3, quantified

Same 1,000,000 blocks:

```
chainstate off : 10.53 GB (median)
chainstate on  : 13.72 GB (median)
extra          :  3.42 GB  =  3,419 bytes/block on disk
logical outbox :             ~2,400 bytes/block
amplification  :             ~1.4x
```

D3 asked that the hot-RocksDB consequence be named rather than left implicit in a
disk-growth bullet. It is now measured: a lagging sink costs roughly 1.4x its logical
outbox volume in real chainstate, on top of the compaction and cache pressure that the
throughput loss reflects.

### The two gates are independent failures

Neither rescues the other:

- **§0.8** — no sink approaches core production rate; sink-paced sync is the only
  reachable mode.
- **Gate 1** — producing envelopes costs 10–18% of core throughput against a 5% budget.

A change that fixed gate 1 (leaner encoding, fewer copies) would not move §0.8 by a
meaningful factor, and accepting sink-paced sync does not repair gate 1. Both need an
explicit answer.

## Relationship to the §0.8 blocking decision

Gate 1 and the §0.8 drain-margin failure are independent.

- §0.8 concerns whether a sink can keep up with core production. Its input — the
  history-disabled core rate of roughly 1,500–3,600 blk/s depending on density — is
  measured and uncontended, and no plausible sink approaches it.
- Gate 1 concerns how much slower core sync becomes when it also produces envelopes.
  It is unmeasured.

A gate-1 pass would not rescue the drain margin, and a gate-1 failure would be a
second, separate problem. They must not be reported as one number.

---

# Round 2 — after address memoisation

## The optimisation, attributed

Profiling the projection hot path first (`ProjectionEncodingProfileTest`, dense 40-tx
block) contradicted the intuition that the copies were the problem:

| stage | before | share | after |
|---|---|---|---|
| derive utxo-history facts | 388.9 us | **81.9%** | 229.1 us |
| encode utxo-history | 48.9 us | 10.3% | 47.8 us |
| manifest + digest | 19.0 us | 4.0% | 17.9 us |
| derive transaction facts | 11.7 us | 2.5% | 9.1 us |
| encode transaction | 2.4 us | 0.5% | 2.4 us |
| chunk split (copy) | 1.0 us | 0.2% | 1.0 us |
| section construct (copy) | 1.8 us | 0.4% | 1.0 us |
| `chunks()` accessor (copy) | 1.0 us | 0.2% | 1.7 us |
| **total** | **474.7 us** | | **310.1 us** |

All four copy sites together are **0.8%**. The cost is address decoding, and
`YaciUtxoHistoryDecoder` had **no memoisation**: every output parsed its address,
extracted both credentials, hashed a key and re-encoded the whole thing back to bech32,
even when the same address repeated. `seenAddresses` deduplicated only the resulting fact
list, after the expensive work had already been done.

Per-block memoisation of that decode cut the stage 389 -> 229 us and the total by 35%. It
is semantics-preserving (a pure function of the display string), is passed as a parameter
rather than held as a field because one decoder instance can serve two worker threads, and
lives in the **shared** decoder — so the differential oracle gets the same speedup and
parity holds by construction.

## Isolated benchmark, round 2

Same protocol: four legs alternating, each alone on an idle machine, fresh chainstate,
wall clock to block 1,000,000, preprod. Artifact SHA-256
`0ea8285011349cc7a8f8d9566482060acbb2eb4a065da7de03b1d3494d83bac5`.

| leg | mode | elapsed | reached | blk/s | chainstate |
|---|---|---|---|---|---|
| 1 | off | 472 s | 1,005,559 | 2,130 | 10.55 GB |
| 2 | on | 421 s | 1,005,338 | 2,388 | 13.31 GB |
| 3 | on | 401 s | 1,001,899 | 2,499 | 13.38 GB |
| 4 | off | 381 s | 1,000,378 | 2,626 | 10.53 GB |

```
median off : 2,378 blk/s   (spread 20.8%)
median on  : 2,443 blk/s   (spread  4.5%)
delta      : +2.7%
```

## This is NOT a pass, and the reason matters

The nominal +2.7% clears the -5% threshold, but the `off` legs vary by **20.8%**
(2,130 vs 2,626). A signal of 2.7% inside noise of 20.8% is not a measurement. Round 1's
-14.1% *was* meaningful because the signal exceeded that round's 9.6% noise; round 2's
does not, and reporting it as PASS would repeat the mistake this report already documents
once.

A curious and consistent asymmetry: the `on` legs are tight in both rounds (1.6%, 4.5%)
while the `off` legs are wild (9.6%, 20.8%). The faster history-disabled path appears more
sensitive to whatever the machine is doing between legs.

## What can be claimed

The cross-round comparison is better behaved than either round's within-round delta,
because the `off` medians are nearly identical:

```
off  median  round 1  2,410  ->  round 2  2,378     (-1.3%, i.e. comparable machine)
on   median  round 1  2,070  ->  round 2  2,443     (+18.0%)
```

So the memoisation is worth roughly **+18% on the history-enabled path**, measured against
a baseline that did not move. That is the defensible attribution.

The residual regression is now somewhere between "none" and "a few percent" and is **not
resolvable at this sample size**. Four more legs are running to tighten it.

Disk cost also improved: extra chainstate for 1M blocks fell from 3,420 to
**3,009 bytes/block**, though part of that may be compaction timing rather than the
optimisation.

## Status of gate 1

| | round 1 | round 2 |
|---|---|---|
| delta | -14.1% | +2.7% |
| within-mode noise | 9.6% | 20.8% |
| verdict | **FAIL** (signal > noise) | **INCONCLUSIVE** (signal << noise) |

Gate 1 is no longer a demonstrated failure. It is also not yet a demonstrated pass.

---

# Round 2 extended to 8 legs — gate 1 is INCONCLUSIVE on this hardware

Four more legs were run on the same memoised artifact to bound the residual.

```
leg 1: off  2130 blk/s      leg 5: on   2119 blk/s
leg 2: on   2388 blk/s      leg 6: off  3098 blk/s
leg 3: on   2499 blk/s      leg 7: off  1935 blk/s
leg 4: off  2626 blk/s      leg 8: on   2464 blk/s
```

| mode | n | median | min | max | spread |
|---|---|---|---|---|---|
| off | 4 | 2,378 | 1,935 | 3,098 | **49%** |
| on | 4 | 2,426 | 2,119 | 2,499 | 16% |

- naive median delta: **+2.0%**
- paired adjacent-leg delta (cancels slow-moving shared conditions): median **-4.8%**,
  individual pairs ranging **-31.6% to +27.3%**

**An earlier hypothesis in this report was wrong and is withdrawn.** After six legs the
data looked like a monotone position effect (first leg slow, later legs faster). Leg 7
(1,935 blk/s, the slowest of all, in seventh position) refutes that. The correct
description is high, unpatterned variance concentrated in the history-**disabled** legs.

The asymmetry is consistent across all eight legs and both rounds: the `on` legs are tight
(16% spread; three of four within 2,388-2,499) while the `off` legs swing 49%. A plausible
reading is that the history-disabled node is more I/O-bound and therefore more exposed to
page-cache and filesystem state, while the history-enabled node is more CPU-bound and
steadier — but that is a hypothesis, not a measurement, and nothing here tests it.

## Verdict

**Gate 1 cannot be resolved on this host.** The effect being tested is 5%; the measurement
floor is 16-49%.

What the data does support:

- the round-1 **-14.1%** regression is **no longer reproducible** after the memoisation;
- the residual sits somewhere around **-5% to +2%**, i.e. bounded far below round 1;
- the history-enabled path itself improved **+17%** (median 2,070 -> 2,426) against `off`
  medians that barely moved (2,410 -> 2,378).

What it does not support: any claim that the 5% budget is met. Reporting the naive +2.0%
as PASS would repeat precisely the error this report documents at the top.

## How to resolve it properly

Wall-clock full-node legs are the wrong instrument for a 5% effect on a shared laptop.
Better options, in order of expected signal-to-noise:

1. **Per-block CPU time** rather than wall clock — attribute via thread CPU accounting on
   the apply thread, which excludes I/O jitter entirely.
2. **The micro-profile harness** (`ProjectionEncodingProfileTest`), which resolved a 35%
   change cleanly on the same machine that cannot resolve 5% end-to-end.
3. **Many more legs** (20+ per mode) or a quiet dedicated host.

Option 1 is the recommended next step and is cheap to add; it would make the 5% budget
testable rather than aspirational.

---

# The real DuckLake sink, measured — §0.8 is WITHDRAWN

**Date:** 2026-08-20
**Deployment:** `/Users/satya/Downloads/yano-try/adr039-ducklake-preprod-20260820-0930/`
**Artifact SHA-256:** `4937c4abda22e1899d59a4213cd2fff667d78d2da8f1e724ecf9bbdf8a139d42`
**Ports:** HTTP 6295, node 14295
**Config:** history enabled at genesis, `projection.sink=ducklake`, fresh chainstate

## The finding

The ADR-039 DuckLake projection sink **keeps up with full-speed bootstrap**. Measured over a
61-second window mid-sync, with producer and sink running concurrently:

```
producer (complete)     1,193 blocks/s
sink (acknowledged)     1,292 blocks/s
backlog drift             -95 blocks/s   (shrinking)
```

The backlog does not accumulate. It sits at **4,319 blocks** — one below the configured
`archiveFinalityBlocks = 4,320` — from block ~170,000 through ~4.8M. That is not the sink
falling behind; it is the sink draining every block the moment it becomes rollback-safe and
then waiting. **Rollback-safety, not sink throughput, is the binding constraint.**

## What this retracts

§0.8 of the Phase 0 report declared the drain margin "unreachable" and raised it as a
blocking product decision. That conclusion was wrong, and it is withdrawn.

The error was inferential, not arithmetic. ADR-038's **59.84 blocks/s** is the throughput of
the *old replay-worker* path — independent per-dataset workers, repeated CBOR decode,
contended shared writer, synchronous transaction locator — measured on mainnet. I used it as
a proxy for the ADR-039 sink's capability. It is not one. The projection sink does no decode,
no ledger resolution, and no locator work; it receives pre-derived rows and commits them in
one transaction. Measured directly, it is roughly **20x** that figure and comfortably ahead of
canonical production.

The lesson is the one the review already stated and I failed to apply: *"Do not declare the
new sink incapable of keeping up based solely on ADR-038's old worker rate. Measure the new
sink directly first."*

## Consequences for the design

- **No bootstrap disk crisis.** The earlier projection of ~33 GB of accumulated outbox for a
  mainnet genesis sync assumed a sustained producer/sink deficit. There is none: retained
  outbox stays at the finality window, a few thousand blocks, regardless of chain length.
- **Disk backpressure remains necessary but is not the normal path.** It protects against a
  stalled or failed sink, not against ordinary bootstrap.
- **Criterion 2's replacement still stands.** The bootstrap-capacity / catch-up-time /
  steady-state-margin / disk-backpressure framing is the right contract; this measurement
  simply shows the bootstrap-capacity term is satisfied with room to spare.

## Status at the time of writing

```
block            4,823,565  of ~5.07M preprod tip
backlog              4,319  blocks (= finality window)
drain failures           0
sink health          READY
contributor status HEALTHY
bytes/block          1,782-2,817 depending on density
chainstate            27 GB
history (DuckLake)   2.6-3.2 GB
```

Time-to-tip, archive-time-to-tip, and steady-state drain margin are still being collected and
are reported separately once the run lands.

## Defect found by this run

The disk-backpressure hold was never attached. `installArchiveIngestHold` ran during
`initialize()`, before any `HeaderSyncManager` exists, and logged its own failure.

The deeper problem was worse than the timing: the manager belongs to a `PeerSession` that is
**recreated on every reconnect**, so even a correctly ordered one-shot install would have been
silently lost the first time upstream dropped — exactly when a backlog is most likely to have
grown. The hold now lives on `SyncSubsystem`, which remembers it and re-applies it to each new
session.

This is the third composition defect in this project with the same shape: correct logic, wrong
wiring, caught only because the failure path is loud.


# Gate 1 resolved by attribution, not by differencing runs — 2026-08-20

Wall-clock A/B legs could not decide a 5% effect on this host: the `off` legs spread 49%.
Rather than run more legs, the projection's cost is now attributed **within a single run**, so
cross-run variance cannot enter at all — there is no second run to difference against.

## The instrument

`DefaultUtxoStore` records, per applied block:

- time inside `CanonicalProjectionContributor.contributeBlock`;
- time inside the whole of `applyBlock`;
- the full block cycle — apply plus the gap until the next block reaches apply.

Thread CPU time would have been preferable and was tried first. It returns nothing here:
`applyBlock` runs on a **virtual thread**, where `ThreadMXBean` reports no CPU time. Wall time
is an acceptable substitute for this particular ratio because the contributor performs no I/O —
it stages into an in-memory `WriteBatch` — so its wall time is essentially its CPU time, while
the denominator legitimately includes the RocksDB write that projection also inflates.

## Result

Fresh preprod sync from genesis, DuckLake sink attached, **2,267,163 blocks**:

```
per-block cycle        520,033 ns     (1,923 blocks/s cumulative)
per-block apply        159,789 ns     30.7% of cycle
per-block projection    32,711 ns      6.3% of cycle, 20.5% of apply
```

The run continued to **3,107,640 blocks**, where the apply share had settled slightly lower —
apply 169,818 ns, projection 31,378 ns, **18.5% of apply** — as later, denser blocks made apply
itself more expensive. The ratio is stable in the 18-21% band across three million blocks, so
the cycle figure above is not a lucky window.

**The projection contributor costs at most 6.3% of sync throughput**, against a 5% budget.

## Why "at most"

The 6.3% assumes block apply sits wholly on the critical path. Where pipeline stages overlap,
removing the projection recovers less than its measured share. So 6.3% is an **upper bound on
the regression**, not a point estimate — which is the useful direction: the true cost is
somewhere in `(0, 6.3%]`.

It also excludes two things by construction:

- **the sink drain**, which is asynchronous by design and not part of core sync;
- **RocksDB write amplification** from the extra column families, measured separately at
  roughly **1.4x** logical. Part of that lands inside the measured `applyBlock` window (the
  batch write) and part in background compaction that does not.

## What this settles, and what it does not

Settled: the pre-optimisation **-14.1%** is not the current cost, and the residual is bounded
above by 6.3% on a measurement whose variance is negligible over 2.27M blocks.

Not settled: whether the true figure is under 5%. The bound straddles the budget. Closing it
needs either apply's critical-path share (how much of the cycle gap is genuinely serialised
behind apply) or a further reduction in staging cost. The remaining 20.5%-of-apply is
concentrated in section encoding, which is where any further optimisation should look.

This replaces "gate 1 is inconclusive on this hardware" with a number and an error direction.
