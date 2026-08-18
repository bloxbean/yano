# ADR-038 Phase 0 — Baseline and Appender Feasibility

Date: 2026-08-18
Branch: `feat/adr-038-archive-throughput`
Host: 16 cores, 128 GB RAM (macOS)
DuckDB / JDBC: `org.duckdb:duckdb_jdbc:1.5.5.0`, packaged `ducklake` + `sqlite_scanner` extensions

Isolated fixtures only. The retained mainnet catalog was **not** opened, read, or
modified by any benchmark.

Artifacts:
- `archive-modules/archive-store-ducklake/src/test/.../DuckLakeAppenderFeasibilityTest.java`
- `archive-modules/archive-store-ducklake/src/test/.../DuckLakeAppendPathBenchmarkTest.java`
- `archive-modules/archive-store-ducklake/src/test/.../DuckLakeWriteSessionBenchmarkTest.java`

Command: `./gradlew :archive-modules:archive-store-ducklake:test --tests "*<Test>*" -i`

---

## 1. Appender feasibility — the blocking question

**Shape (a), direct-to-DuckLake, is SUPPORTED.**

`org.duckdb.DuckDBConnection` exposes `createAppender(String, String, String)`, and
appending against a real attached DuckLake catalog (`history_lake.main.<table>`)
succeeds and the row is visible via `SELECT COUNT(*)`. Shape (b), native staging
plus one `INSERT ... SELECT`, also works.

The Appender covers every `ArchiveColumn` type the schema uses: `byte[]` (BINARY),
`String` (TEXT), `boolean`, `int`, `long`, `BigDecimal` (DECIMAL_38) and `UUID`.

**Consequence.** Phase 2 may take the direct path, and Phase 3's staging-removal
item — which the ADR made conditional on this answer — stays live. The finding is
asserted in the feasibility test so a driver or extension upgrade that removes it
fails loudly.

## 2. Append-path benchmark (50,000 rows × 10 columns, DuckLake target)

`PRODUCTION staged` reproduces what `DuckLakeWriteSession` does today: 256-row
`INSERT ... VALUES` batches into a native staging table, then one
`INSERT INTO <ducklake> SELECT * FROM staging`.

| profile | PRODUCTION staged 256 | staged sb 10k | direct 256 (quad) | direct sb 10k | **appender** | appender vs production |
|---|---:|---:|---:|---:|---:|---:|
| legacy (1t/128MB) | 36,644 | 32,544 | 23,383 | 32,015 | **341,531** | **9.3x** |
| default (4t/512MB) | 37,908 | 32,752 | 20,869 | 32,213 | **328,646** | **8.7x** |
| highmem (8t/8GB) | 37,660 | 32,890 | 21,186 | 32,369 | **320,256** | **8.5x** |

(rows/s)

### Three ADR claims are corrected by this measurement

1. **Threads and memory do not affect the append path.** Legacy (1 thread /
   128 MB) matches, and marginally beats, highmem (8 threads / 8 GB). The ADR's
   Phase 1 rationale — that DuckDB being throttled to 1 thread is a major
   throughput bottleneck — **is not supported for this path**. Phase 1's expected
   "3–8x" must be withdrawn pending evidence from another component.
2. **The staging round-trip is not waste.** Staged (36,644 rows/s) is *faster*
   than direct 256-row inserts into DuckLake (23,383 rows/s) — 1.6x. The report's
   "every row is written twice … pure duplicated work on the hot path" framing was
   wrong; staging into a native table and bulk-copying is an optimisation at the
   current batch size.
3. **The quadratic placeholder construction is immaterial.** Replacing `reduce`
   with `StringBuilder` at 256 rows gives 1.00–1.04x. The ADR listed it as a
   compounding cost with "~350 MB of string garbage per commit"; the garbage is
   real but the time is not measurable. Cost is dominated by per-statement SQL
   parsing and JDBC binding — exactly what the Appender removes.

Batch size 256 → 10k on the direct path is worth ~1.4–1.5x, well short of the
Appender.

## 3. Write-session stage attribution (250,000 rows, `chain_transaction`)

Real `DuckLakeHistoryArchiveBackend` sessions via `begin` → `append` → `commit`.

| variant | begin | append | commit | total | rows/s | parquet files |
|---|---:|---:|---:|---:|---:|---:|
| fresh, 4MB target | 0.076 s (1.1%) | 6.493 s (**90.7%**) | 0.590 s (8.2%) | 7.158 s | 34,925 | 14 |
| fresh, 128MB target | 0.092 s (1.3%) | 6.530 s (90.8%) | 0.570 s (7.9%) | 7.192 s | 34,760 | 14 |
| aged, 200 commits | 0.059 s (0.8%) | 6.487 s (91.2%) | 0.567 s (8.0%) | 7.112 s | 35,150 | 814 |
| aged, 1000 commits | 0.067 s (0.9%) | 6.458 s (90.7%) | 0.592 s (8.3%) | 7.116 s | 35,130 | **4,014** |

### Findings

- **Append is ~91% of write-session time.** Phase 2 therefore targets the right
  component: at ~9x on append, an isolated session would fall from ~7.1 s to
  ~1.3 s, about **5.4x end-to-end on the write session**.
- **Catalog cardinality is not the bottleneck.** At 1,000 snapshots and **4,014
  Parquet files — matching the mainnet deployment's ~4,000** — the large-commit
  rate is unchanged at 35,130 rows/s. The leading hypothesis for the production
  gap is eliminated.
- **Per-commit fixed cost is ~75 ms** (200 commits in 14.5 s; 1,000 in 75.6 s),
  and constant with catalog age.
- `target-file-size` (4MB vs 128MB) makes no difference to commit rate.

### The ~75 ms fixed cost undercuts two other phases

Phase 3 collapses four commits into one, saving ~3 × 75 ms ≈ **225 ms per batch**.
Against production's ~180 s per commit that is **~0.1%**. Phase 6 (commit
granularity) amortises the same 75 ms and is similarly limited. Neither is a
throughput lever on this evidence; Phase 3's case rests on architecture and
contention removal, not on per-commit savings.

## 4. The unexplained gap — stated honestly

| measurement | rows/s |
|---|---:|
| isolated real write session (this report) | ~35,000 |
| mainnet, inside the write session (ADR-037 validation) | ~420–780 |

An **~45–84x** gap the code path alone does not explain, and which this report
does **not** close. Eliminated: catalog cardinality, file count, target file size,
DuckDB threads/memory, and quadratic string building. Remaining candidates, none
yet measured:

- **row width and table fan-out** — `utxo_history` writes five tables with far
  wider rows than `chain_transaction`;
- **CPU contention** — four projections deriving in parallel alongside core sync
  and ledger apply;
- **real I/O** against a 27 GB dataset versus a fresh temp directory.

Until this is attributed, no end-to-end production speedup can be predicted from
these numbers with confidence.

## 4b. Phase 0 extension — shape (a) vs shape (b), UTXO session, and contracts

### Shape (a) vs shape (b) — the Phase 2 decision

Shape (b) is **Appender into a native staging table, then one `INSERT ... SELECT`**
into DuckLake. It was missing from the first benchmark.

| profile | production staged 256 | (a) direct Appender | (b) Appender + staging | (b) vs production | (a) vs (b) |
|---|---:|---:|---:|---:|---:|
| legacy (1t/128MB) | 37,688 | 359,292 | **874,737** | **23.2x** | 0.41x |
| default (4t/512MB) | 38,652 | 348,794 | **899,217** | **23.3x** | 0.39x |
| highmem (8t/8GB) | 38,298 | 345,107 | **953,419** | **24.9x** | 0.36x |

(rows/s)

**Shape (b) is ~2.5x faster than direct Appender**, reversing the expectation that
bypassing staging would be quickest. Writing into a native DuckDB table and
bulk-copying beats appending row-by-row through the DuckLake extension.

**These multiples are component measurements, not node speedups.** They compare
only the technique for getting rows into DuckLake, in isolation, on one table.

### Recommended Phase 2 path: shape (b)

Two independent reasons, one performance and one correctness:

1. **It is ~2.5x faster than shape (a)** and ~23x faster than today's path.
2. **It preserves the write contract unchanged.** `verifyLogicalKeys()` queries the
   *staging* tables — both the in-batch duplicate check and the JOIN against the
   target run before `flushStaging()` publishes anything. Shape (a) has no staging
   table, so rows would reach the target *before* verification, and the
   "reject a duplicate logical key before publishing" contract would have to be
   re-implemented. Shape (b) changes only how rows enter staging.

Direct Appender feasibility (§1) remains a useful, asserted fact — it keeps
Phase 3's staging-removal option open — but it is **not** the path Phase 2 should take.

### UTXO_HISTORY session, all five tables, production-shaped rows

Real sessions, rows generated from the schema so column count and order cannot
drift, mixed in mainnet proportions (assets 53%, outputs 22%, inputs 20%).
DuckDB settings were `DuckDbManagerConfig.defaults()`, which is **exactly the
production configuration** (256MB total, 128MB bulk, 1 thread).

| target preload | wall rate | append | verifyKeys | stagingCopy | metadata | commit | locator |
|---|---:|---:|---:|---:|---:|---:|---:|
| 0 rows | 16,542 rows/s | **81.9%** | 1.2% | 9.0% | 0.1% | 7.7% | 0.1% |
| ~240,000 rows | 17,195 rows/s | **79.7%** | 0.3% | 11.6% | 0.1% | 8.1% | 0.1% |
| ~960,000 rows | 18,170 rows/s | **83.0%** | 0.3% | 8.2% | 0.1% | 8.3% | 0.0% |

Findings:

- **Append dominates for UTXO too (~80–83%)**, so Phase 2 targets the right stage
  for the dataset that actually gates the watermark.
- **`verifyLogicalKeys` prunes correctly and does not scale with target size** —
  0.040 s → 0.011 s as the target grew to ~960k rows. The JOIN-against-target
  hypothesis is eliminated.
- **Row width is a real factor**: 35k rows/s for 10-column `chain_transaction`
  versus ~17.5k rows/s for the production-shaped UTXO mix (`transaction_outputs`
  alone has 26 columns).
- **Production DuckDB settings are not the cause** — this ran under them.

Amdahl bound for Phase 2 on this evidence: with append at ~82% and shape (b) at
~23x on that stage, the write session improves by about **4.3–4.6x**, not 23x.
Derive, writer wait and the hot commit sit outside the session, so the node-level
gain is lower still.

### Production write contract, characterised

`DuckLakeWriteSessionContractTest` — 5 tests, all passing — pins the behaviour
Phase 2 must preserve:

| contract | test |
|---|---|
| explicit transaction commit publishes; abandoned session rolls back | `commitPublishesRowsAndAbortRollsBackTheTransaction` |
| mid-append failure after several staging flushes publishes nothing and frees the writer | `midAppendFailureLeavesNoPartialRowsAndReleasesTheSession` |
| replay returns identical digest, counts and generation; differently shaped retry is rejected | `replayOfCommittedJobReturnsIdenticalDigestAndRejectsDifferentRows` |
| duplicate logical key within a batch, and against the target, are both rejected | `logicalKeyVerificationRejectsDuplicatesWithinBatchAndAgainstTarget` |
| a UTXO_HISTORY session commits across all five tables with correct per-table counts | `utxoHistorySessionCommitsAcrossAllFiveTables` |

### Instrumentation added

`DuckLakeWriteStageTimings` records append, logical-key verification, staging
copy, metadata, commit and locator per session; `DuckLakeWriteSession` populates
it and emits one DEBUG line per commit. Cost is a few `System.nanoTime()` calls
per commit.

### The remaining gap, restated more carefully

Isolated UTXO session: ~17,500 rows/s. Mainnet in-session: ~420–780 rows/s — a
**~22–40x** gap. Now eliminated as causes: catalog cardinality, file count,
target file size, target table size, DuckDB threads/memory, quadratic string
building, and logical-key verification.

One caveat on the production figure itself: it derives from `holderSeconds` at a
300 s stuck-threshold breach, which samples a **worst case**, not an average. The
catalog-derived average is ~1,300 rows/s per commit *including* derivation and
waiting, which would put the in-session gap nearer 4–5x — plausibly explained by
CPU contention from four parallel projections plus core sync, and real I/O
against a 27 GB dataset. Attributing this properly needs the new stage timings
observed on a real deployment.

## 4c. Phase 2 implemented — measured write-session improvement

Shape (b) is implemented in `DuckLakeWriteSession`: rows enter the native staging
tables through one lazily created `DuckDBAppender` per table, all Appenders are
flushed and closed before `verifyLogicalKeys()`, and the existing
`INSERT ... SELECT` publication is unchanged.

Same benchmark, same host, both implementations in one run. Order is
**appender-first** so JIT warmup cannot flatter the Appender; the result is
unchanged when the order is reversed.

| mode | preload | wall rate | append | verifyKeys | stagingCopy | commit |
|---|---|---:|---:|---:|---:|---:|
| legacy | 0 | 9,780 rows/s | 82.5% | 1.0% | 8.6% | 7.9% |
| legacy | ~960k rows | 9,942 rows/s | 82.9% | 0.4% | 7.8% | 8.9% |
| **appender** | 0 | **32,605 rows/s** | 27.8% | 4.5% | 34.7% | 31.8% |
| **appender** | ~960k rows | **42,029 rows/s** | 19.6% | 1.5% | 41.4% | 37.0% |

**Full write-session improvement: 3.3x–4.2x**, against the predicted Amdahl bound
of 4.3–4.6x. Slightly under, as expected: staging copy and the DuckLake commit do
not speed up, so they now dominate.

The stage profile inverts — append falls from ~82% to ~20–28%, leaving
**staging copy (35–41%) and the DuckLake commit (32–37%)** as the next targets for
any future work. Logical-key verification remains negligible and still prunes.

### Contract equivalence

`DuckLakeAppenderEquivalenceTest` runs the same interleaved five-table
`UTXO_HISTORY` job (including null-valued optional columns) through both
implementations and asserts:

- **byte-identical ordered digest**;
- identical per-table row counts, receipt generation, coverage ranges and
  physical row counts;
- replay accepted with matching digest, differently-shaped replay rejected;
- failure during append rolls back, publishes nothing and frees the writer permit;
- failure *after* Appenders close (duplicate logical key in `verifyLogicalKeys`)
  rolls everything back;
- explicit close without commit publishes nothing, and close is idempotent.

Injecting a fault inside `DuckDBAppender.flush()`/`close()` itself was not
achievable from outside the session — the staging tables are TEMP tables on the
session's own connection — so that path is covered indirectly by the
post-Appender-close failure test and by `discardAppenders()` on the close path.
This is stated rather than claimed as direct coverage.

### Rollback switch

`-Dyano.archive.ducklake.append-mode=legacy` restores the prepared-statement path
without a rebuild, for production rollback. Documented as temporary, to be removed
once the Appender path is validated.

### Instrumentation

Append is now timed as a window from first staged row to Appender close, so there
is **zero per-row timing cost** on the hot path; the previous 256-row flush
instrumentation would no longer have represented append time. The per-commit
summary is logged at **INFO** — one line per commit, tens per hour — so validation
does not require enabling broad DEBUG logging on a deployment.

## 5. Consequences for the ADR

1. **Appender confirmed; Phase 2 should take shape (b), not the direct path.**
   Appender-into-staging is ~2.5x faster than direct Appender and ~23x faster than
   today's technique, and it preserves `verifyLogicalKeys` unchanged because that
   check operates on the staging tables. Append is ~82% of the UTXO session, so
   the expected session-level gain is **~4.3–4.6x**, and the node-level gain is
   lower.
2. **Phase 1's throughput rationale is withdrawn.** Threads/memory show no effect
   on the append path. The conservative defaults remain justified as prudent
   sizing, and the `history-highmem` profile as an operator option, but neither
   should be presented as a throughput measure without evidence from another
   component.
3. **The staging round-trip and the quadratic build are removed as stated
   motivations** — measurement contradicts both.
4. **Phase 3 and Phase 6 per-commit savings are negligible** (~75 ms fixed).
5. **≥50 blocks/s is not yet demonstrable.** Phase 2 offers ~5.4x on the write
   session; from 2.90 blocks/s that suggests roughly **15 blocks/s**, not 50 —
   and only if production is append-dominated in the same proportion, which §4
   shows is unverified.

Per the ADR's own target policy, this triggers **an explicit decision to change
the design, the resources, or the target** — not an automatic revision. The
decision is the operator's; this report supplies the evidence.

### Recommended next measurement

Attribute the §4 gap before further implementation: instrument a real production
batch to separate decode, derive, append and commit per dataset, and repeat the
session benchmark using `utxo_history`'s five-table schema and row width rather
than `chain_transaction`. That determines whether Phase 2's ~9x on append
translates to production at all.
