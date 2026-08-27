# ADR-038 — UTXO_HISTORY Bottleneck Analysis (diagnose only)

Date: 2026-08-18
Deployment: mainnet PID 93631, jar `47f7e54b…`, profile `mainnet,history`
Method: existing per-commit stage instrumentation, plus 124 low-overhead
`jstack` samples of the four `yano-archive-projection-*` threads, `ps` CPU
sampling and `jcmd GC.heap_info`.

**No binary was deployed, no restart performed, no configuration changed.**

---

## 1. Headline: the archive is no longer the cost

`utxo_history` advanced 229,375 blocks in 7,272 s over 589 commits.

| | value |
|---|---:|
| blocks per commit | ~389 |
| wall clock per commit cycle | **12.35 s** |
| measured archive write session | **0.508 s (4.1%)** |
| everything else | **11.84 s (95.9%)** |

The whole Phase 2 write session — append, verifyKeys, staging copy, metadata,
DuckLake commit, locator — is now **4.1%** of the UTXO cycle. The remaining
**95.9%** is per-block work: block-source read, decode, fact extraction, resolver,
row derivation, hot-store commit and scheduling.

## 2. Stage attribution from thread sampling

124 samples × 4 projection threads. Topmost application frame per stack:

| bucket | samples | representative frames |
|---|---:|---|
| **block decode** | **~82** | `HexUtil.encodeHexString` (48), `RocksDB.get` (22), `CborSerializationUtil.deserializeOne` (5), `Bech32.polymod` (5), `TransactionBodySerializer` |
| gate acquisition | 50 | `ArchiveResourceGate.acquire` |
| hot-store commit | ~32 | `SqliteHotHistoryStore.transaction` → `NativeDB.step` |
| locator advance (TRANSACTION only) | 13 | `DuckLakeTransactionLocator.advance` |
| DuckDB archive session | ~13 | `DuckDBNative.*`, `DuckDBAppender.putStringOrBlob` |

Thread states across samples: WAITING 243, RUNNABLE 192, TIMED_WAITING 174,
BLOCKED 11.

### Block decoding is the largest bucket, and its biggest single frame is hex encoding

`HexUtil.encodeHexString` alone is **48 samples (~10% of all projection-thread
samples)**. Its callers are unambiguous:

```
BlockSerializer.deserialize(BlockSerializer.java:32)          67
YaciBlockDecoder.decode(YaciBlockDecoder.java:42)             59
ChainBlockArchiveSource.lambda$readCanonical$0(:33)           58
BlockSerializer.deserializeBlock(:66,:77,:121)                61
WitnessesSerializer.deserializeDI(:84)                        17
TransactionBodySerializer / TransactionOutputSerializer       33
```

This is **inside yaci-core's CBOR block deserializer**, on the
`ChainBlockArchiveSource.readCanonical` path — i.e. it is block *decoding* cost,
not Yano row derivation. It is an upstream dependency cost, not a Yano hot-path
defect.

## 3. Decode work is ~4x redundant

| metric | value |
|---|---:|
| `decodedBlocks` | 1,284,384 |
| `decodedBlockCacheHits` | 54,216 |
| **cache hit rate** | **4.1%** |

Each of the four datasets decodes every block independently. The per-cycle decode
cache cannot help because the datasets sit far apart:

```
account_event 7,843,000 | transaction 7,842,000 | address_transaction 7,353,125 | utxo_history 6,960,950
```

`utxo_history` trails `account_event` by **882,050 blocks**, so their working sets
never overlap and the cache hits 4.1% of the time. Decode is therefore performed
roughly four times per block across the group.

## 4. Batch sizing — which limit binds, and why raising it will not help

| dataset | rows/commit | blocks/commit | binding limit |
|---|---:|---:|---|
| `utxo_history` | 159,380 | ~375 | **`max-rows-per-batch`** (250,000 cap; ~425 rows/block) |
| `address_transaction` | 161,000 | ~628 | `max-rows-per-batch` |
| `transaction` | 24,921 | ~960 | `max-blocks-per-batch` (1,000) |
| `account_event` | 2,084 | ~960 | `max-blocks-per-batch` (1,000) |

Configured: `maxBlocksPerBatch=1000`, `maxRowsPerBatch=250000`,
`projectionParallelism=4`.

**Increasing batch size cannot materially help.** The archive fixed cost is
already only 0.508 s of a 12.35 s cycle. Doubling the batch would at best halve
that 4.1% — a gain under 2.1% — because the other 95.9% is per-block work that
scales linearly with blocks. This directly answers the question posed: no.

## 5. CPU and GC

| metric | value |
|---|---|
| machine | 16 cores (1600% available) |
| observed JVM CPU | 60–220%, typically ~100% |
| **machine utilisation** | **~4–14%** |
| top threads | the four `yano-archive-projection-*`, roughly equal cumulative CPU (1.55–1.65M ms each) |
| heap | G1, 31 GB reserved, **2.7 GB committed, 1.1 GB used** |
| GC threads | G1 Conc ~131 s cumulative each |

**UTXO derivation is single-thread-bound.** Projection parallelism is 4 — one
worker thread per dataset — so all of `utxo_history`'s decode, derive, resolver
and hot-commit work runs on a single thread at ~32.8 ms/block. The machine is
**86–96% idle** and the heap is barely used, so this is neither CPU-capacity nor
GC-pressure limited. Allocation pressure is unremarkable.

## 6. Smallest measured change capable of moving UTXO toward ≥50 blocks/s

Ranked by measured leverage against effort and risk. **None implemented.**

1. **Parallelise decode/derive within the `utxo_history` worker.**
   The strongest candidate on this evidence: one thread is doing 32.8 ms/block
   while 14–15 cores sit idle, and ~82/124 samples are in decode. Decoding a batch
   of blocks concurrently before derivation is contained inside the worker,
   changes no archive contract, and needs no group-cursor work. If decode is ~60%
   of per-block cost, 4-way decode parallelism implies roughly **1.8–2.2x → 57–70
   blocks/s**, which would clear the target. *Estimate, not a promise — it needs a
   measured prototype.*

2. **Stop decoding each block four times.** Worth up to 4x on the decode bucket
   group-wide, but the datasets are 882k blocks apart, so the cycle cache cannot
   be made to hit without aligning them — that is Phase 3, which is blocked on
   C1–C5. Not available cheaply today.

3. **Reduce decode cost itself.** The `HexUtil.encodeHexString` hot spot is inside
   yaci-core's `BlockSerializer`, not Yano. Any fix is an upstream change or a
   decoder that avoids hex-encoding hashes it will immediately re-encode as
   binary. Potentially large (~10% of projection samples) but outside this repo.

4. **Raise `projection-parallelism` above 4.** Cheapest to try, but it adds
   *dataset* parallelism, not within-dataset parallelism; `utxo_history` would
   still be one thread. Unlikely to move the slowest dataset.

**Explicitly not recommended:** larger batches (§4), and SQLite locator-ingestion
tuning — the latter is the largest cost inside `TRANSACTION` sessions, but
`transaction` already runs at 85.64 blocks/s with zero writer waits, so it is not
the constraint on the slowest-dataset target.

## 7. What would require new instrumentation, and why

The buckets in §2 are attributed by stack sampling across all four projection
threads together. Splitting the UTXO cycle precisely into *block-source read vs
lease acquisition vs fact extraction vs pointer/address resolver vs row
materialisation vs hot commit vs scheduling delay* would need worker-level timing
in `BlockArchiveWorker`, which means a new binary and a restart.

**That is not proposed yet.** Sampling already identifies decode as the dominant
bucket and single-threading as the structural limit, which is sufficient to choose
the next experiment. A prototype of option 1 measured on a devnet or preprod
fixture would be more informative than another mainnet restart.

## 8. Target status

The provisional **≥50 blocks/s** target is retained unchanged and unmet:
`utxo_history` is the slowest dataset at **31.57 blocks/s**. It is not lowered.
