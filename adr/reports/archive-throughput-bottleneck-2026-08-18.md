# Archive Backfill Throughput — Bottleneck Analysis

Date: 2026-08-18
Deployment: `/Users/satya/Downloads/yano-try/yano-0.1.0-pre13-mainnet` (16 cores, 128 GB RAM)
Measured: **2.90 blocks/s** on the gating dataset
Comparator: yaci-store sustains ~200–300 blocks/s — a **reference point, not a Yano acceptance target**
Provisional campaign target: **≥50 blocks/s** under a named high-throughput profile, per
[ADR-038](../in-progress/038-archive-backfill-throughput.md), to be validated at its Phase 0

Analysis only. No configuration or code was changed.

---

## The smoking gun

A `utxo_history` write session holds the writer gate for **300+ seconds** to
commit **~125,000 rows** — about **400 rows/s inside the write session**. DuckDB
bulk-loads at 100k+ rows/s. The archive is therefore running its writer roughly
**250x below** the engine's capability, and the deficit is inside the write path,
not in derivation, disk, or the chain source.

Supporting measurement: ~0.72 GB written in 2.70 h ≈ **74 KB/s**. This workload
is nowhere near I/O-bound.

## Ranked bottlenecks

### 1. DuckDB is throttled to 1 thread and 128 MB (configuration)

```java
statement.execute("SET memory_limit = '" + workloadConfig.memoryLimitBytes() + "B'");
statement.execute("SET threads = " + workloadConfig.threads());
```
`DuckDbManager.java:176-177`

Deployed values:

| setting | configured | machine has |
|---|---|---|
| `bulk-catch-up.threads` | **1** | 16 cores |
| `bulk-catch-up.memory-limit` | **128MB** | 128 GB RAM |
| `max-total-memory` | **256MB** | |
| `max-temp-directory-size` | 2GB | 526 GB free |

DuckDB is a parallel vectorized engine. Running it single-threaded with a 128 MB
budget removes essentially all of its advantage — it is being used as a slow
row-store. This uses **6% of available cores and 0.2% of available RAM**.

### 2. Rows are inserted through JDBC with 256-row batches and quadratic SQL building

```java
static final int STAGING_BATCH_SIZE = 256;
```

```java
String valuesPlaceholders = IntStream.range(0, pending.size())
        .mapToObj(ignored -> rowPlaceholders)
        .reduce((left, right) -> left + ", " + right).orElseThrow();
try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO " + stagingName(...) + " VALUES " + valuesPlaceholders)) {
    for (List<Object> row : pending) {
        for (Object value : row) insert.setObject(parameter++, value);
    }
    insert.executeUpdate();
}
```
`DuckLakeWriteSession.java:194-210`

Three compounding costs per commit of ~125,000 rows:

- **488 separate `INSERT` statements** (125,000 ÷ 256), each a distinct ~5 KB SQL
  string that DuckDB must parse and bind from scratch.
- **Quadratic string construction.** `reduce((l, r) -> l + ", " + r)` over 256
  elements copies O(N²) characters — roughly 700 KB of string churn per flush,
  ~350 MB per commit, all garbage.
- **~1.25M `setObject` calls** per commit (125k rows × ~10 columns).

**The DuckDB Appender API is not used anywhere in the codebase** — a repo-wide
search for `createAppender` / `DuckDBAppender` returns nothing. Appender is
DuckDB's documented bulk-ingest path and is typically 10–50x faster than
parameterized `INSERT ... VALUES`.

### 3. Every row is written twice

```java
sql.execute("CREATE TEMP TABLE " + staging + " AS SELECT * FROM history_lake." + ...);
...
sql.execute("INSERT INTO " + target + " SELECT * FROM " + staging);
```
`DuckLakeWriteSession.java:172, 218`

Rows land in a TEMP staging table, then are copied again into the DuckLake table.
The staging table supports replay/idempotency checking, but the second full copy
is pure duplicated work on the hot path.

### 4. Each block is decoded ~4 times

From `/status`:

```
"decodedBlocks": 174835, "decodedBlockCacheHits": 10165
```

A **5.8% cache hit rate**. `CycleCachingBlockArchiveSource` caches decoded blocks
per cycle, but the four datasets have drifted ~240,000 blocks apart
(`transaction` at 6,782,500 vs `utxo_history` at 6,542,250), so they never
request the same block in the same cycle and the cache almost never hits. Every
block is read from RocksDB and CBOR-decoded once per dataset.

### 5. All four datasets serialize behind one writer

`ducklake-writer` is **hard-coded to a single permit**
(`DuckLakeHistoryArchiveBackend.java:140`), and `duckdb-bulk` has one permit.
Gate-holder attribution across 521 wait warnings: `begin UTXO_HISTORY` 78%,
`begin TRANSACTION` 11%, `begin ACCOUNT_EVENT` 11%.

### 6. Per-commit fixed overhead is paid every 1,000 blocks

Each commit performs: a DuckLake snapshot; inserts into `archive_commits`,
`archive_commit_counts`, and `archive_coverage`; Parquet file creation at
`target-file-size: 4MB`; and a transaction-locator update into a **4.7 GB SQLite
file opened with `PRAGMA synchronous=FULL`**. With `max-blocks-per-batch: 1000`,
this fixed cost is amortized over only 1,000 blocks.

---

## Configuration-only changes (no code)

| setting | now | suggested | rationale |
|---|---|---|---|
| `duckdb.bulk-catch-up.threads` | 1 | **8** | 16 cores present |
| `duckdb.bulk-catch-up.memory-limit` | 128MB | **8GB** | 128 GB present |
| `duckdb.steady-state.memory-limit` | 128MB | **2GB** | |
| `duckdb.max-total-memory` | 256MB | **24GB** | must cover queries × steady + bulk × bulk |
| `duckdb.max-concurrent-queries` | 2 | **4** | required for bulk > 1 |
| `duckdb.max-concurrent-bulk-jobs` | 1 | **2** | invariant: must be `< max-concurrent-queries` |
| `duckdb.max-temp-directory-size` | 2GB | **32GB** | 526 GB free |
| `worker.max-blocks-per-batch` | 1000 | **8000** | amortize per-commit snapshot cost |
| `worker.max-rows-per-batch` | 250000 | **2000000** | otherwise rows bind before blocks |
| `archive.ducklake.target-file-size` | 4MB | **128MB** | fewer, larger Parquet files |
| `archive.wait.stuck-operation-seconds` | 300 | **1800** | stop discarding derived batches |

**Expected gain: roughly 3–8x** → on the order of 10–25 blocks/s. Real, and worth
doing first because it costs nothing, but it does **not** on its own reach the
provisional ≥50 blocks/s target.

The reason config alone cannot close the gap: bottleneck #2 is largely
**single-threaded SQL parsing and JDBC binding**. Giving DuckDB 8 threads does
not parallelize 488 sequential `INSERT` statements per commit.

Two cautions: raising batch sizes raises peak heap, since `runBatch` materializes
all decoded blocks *and* all derived rows before opening the write session; and
these values need validation on a devnet before a production deployment.

---

## Minimal code changes, ranked by leverage

1. **Switch the row path to the DuckDB Appender API.** Confined to
   `DuckLakeWriteSession.flushPendingStagingBatch`. This is the single highest-value
   change — expect **10–30x** on the insert path, and it removes the SQL-parsing
   and `setObject` costs entirely.

2. **Fix the quadratic placeholder construction** — replace `reduce` with a
   `StringBuilder`, or precompute it once per (table, batch size) since it is
   constant. Roughly three lines, and worth doing even if item 1 is deferred.

3. **Raise `STAGING_BATCH_SIZE` from 256.** A single constant. Only safe *after*
   item 2, because with quadratic string building a larger batch is worse. Moot
   once item 1 lands.

4. **Eliminate the staging round-trip** where replay verification does not require
   it — halves the rows physically written per commit.

5. **Align the four datasets on the same batch range** so the cycle cache actually
   hits. Turns ~4 decodes per block into 1 (a 5.8% hit rate becomes ~75%), and it
   also reduces gate contention because the datasets stop interleaving unrelated
   ranges. This is a scheduling change in `HistoryArchiveService`, not a storage
   change.

6. **Per-table concurrent writers.** The four datasets write disjoint tables, so
   this is sound in principle, but it requires reworking
   `DuckLakeTransactionLocator.advance`'s strict generation sequencing — which
   currently falls back to the full rebuild that cost 4h05m at startup. Largest
   change; do it last.

---

## Would SQLite behave the same?

**Yes for the architectural costs, no for the engine-specific ones.**

Shared, because they live in `archive-core` rather than the backend:

- the ~4x redundant block decode (#4);
- single-writer serialization — `SqliteHistoryArchiveBackend` also declares
  `private final Semaphore writer = new Semaphore(1, true)` (`:68`);
- per-commit coverage/receipt/commit-record overhead (#6);
- derive-before-`begin`, so stuck waits still discard derived batches.

Different:

- no DuckDB thread/memory throttle to misconfigure, and no Parquet file creation
  or snapshot-per-commit cost;
- but no vectorized bulk path either, and SQLite writes are inherently serial.

**Switching backends is therefore not the fix.** The dominant costs are the insert
path, the redundant decode, and write serialization — the first is DuckLake-specific,
the other two are common to both.

---

## Should 200–300 blocks/s be the target? No — it is a comparator

yaci-store reaches that rate with a structurally cheaper commit model: plain SQL
tables, no immutable per-commit snapshot, no Parquet file creation, no ordered
digest, no coverage record. Yano's archive pays for immutability, digest-verified
idempotent replay, and gap-explicit coverage **on every commit** — that is a
deliberate correctness trade, and it is why ADR-034 chose it.

Chasing parity would therefore mean unwinding that trade, so **ADR-038 does not
adopt 200–300 blocks/s as a target.** It is retained here only as the comparator
that motivated the investigation.

The adopted figure is the **provisional ≥50 blocks/s** campaign target under a
named high-throughput profile, validated at ADR-038's Phase 0 against a pinned
dense-mainnet workload. Failure to validate triggers an explicit decision to
change the design, resources, or target — not an automatic reduction. The
conservative default profile is measured separately, must not regress, and gets
its numeric target from Phase 0 evidence.

Reaching even 50 rests mainly on item 1 (the Appender path), since that is what
attacks the measured ~400 rows/s inside the write session. Items 2 and 5 help;
item 3 has been reframed in the ADR as a smaller write-path win than first
estimated.

## Recommended next step

Confirm the diagnosis cheaply before changing anything: micro-benchmark the
insert path — same 125k rows via (a) the current 256-row JDBC batches, (b) the
same with a `StringBuilder`, (c) the Appender API — on a devnet-sized DuckLake.
That settles the relative weight of #1 versus #2 in under an hour and makes the
case for the code change concrete rather than inferred.

This analysis is code-reading plus the measured 400 rows/s inside the write
session. It has not been profiled.
