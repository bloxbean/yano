# Archive Disk Forecast — mainnet history to catch-up

Date: 2026-08-19
Method: read-only measurement of the running deployment. **No compaction was run,
no data deleted, no retention changed.**

---

## 1. Measured state

| artefact | size |
|---|---:|
| `history/` total | **116 GB** |
| `history/ducklake-data` (Parquet) | 89 GB |
| transaction locator (SQLite) | **20.62 GB** |
| hot store (SQLite) | 6.13 GB |
| DuckLake catalog | 0.07 GB |
| hot-store WAL | 0.19 GB |
| **Parquet file count** | **141,594** |
| `chainstate/` | ~280 GB |
| **free space** | **589 GB** (disk 93% used) |

Free space **rose** from 482 GB to 589 GB over the measurement period, so
maintenance is reclaiming space even though it has never completed a full
compaction pass.

## 2. Remaining work

Chain tip 13,828,524.

| dataset | coverage | remaining | complete |
|---|---:|---:|---:|
| `transaction` | 13,513,000 | 315,524 | 97.7% |
| `account_event` | 13,510,250 | 318,274 | 97.7% |
| `address_transaction` | 12,916,125 | 912,399 | 93.4% |
| **`utxo_history`** | 9,651,750 | **4,176,774** | **69.8%** |

Three datasets are nearly finished. **Remaining growth is dominated almost
entirely by `utxo_history`**, which must still archive 4.18M blocks — and those
are the most recent, highest-density blocks on the chain.

## 3. Row-volume model

Measured rows per commit and blocks per commit give current density:

| dataset | rows/block (current) |
|---|---:|
| `utxo_history` | ~427 |
| `address_transaction` | ~256 |
| `transaction` | ~26 |
| `account_event` | ~2.2 |

89 GB of Parquet currently holds roughly 3 billion rows across the four datasets,
giving an effective **~30 bytes per row after compression**.

### Projected remaining Parquet growth

| dataset | remaining blocks | rows/block | rows | est. bytes |
|---|---:|---:|---:|---:|
| `utxo_history` | 4,176,774 | 427 | ~1.78 B | **~53 GB** |
| `address_transaction` | 912,399 | 256 | ~234 M | ~7 GB |
| `transaction` | 315,524 | 26 | ~8 M | ~0.3 GB |
| `account_event` | 318,274 | 2.2 | ~0.7 M | negligible |
| **total** | | | | **~60 GB** |

## 4. Forecast at catch-up

| component | conservative | high-density |
|---|---:|---:|
| Parquet data | 89 + 60 = **149 GB** | 89 + 90 = **179 GB** |
| transaction locator | ~21 GB | ~22 GB |
| hot store | ~6 GB | ~8 GB |
| catalog + WAL | ~1 GB | ~2 GB |
| **`history/` at catch-up** | **≈177 GB** | **≈211 GB** |
| **growth from today** | **+61 GB** | **+95 GB** |

The high-density bound applies a 1.5x density multiplier to the remaining
`utxo_history` range, since those blocks are the busiest on the chain and density
has already been observed rising (22 → 50 tx/block over ~100k blocks earlier in
this campaign).

### Locator growth is nearly finished

The locator carries one entry per transaction and is driven by the `transaction`
dataset, which is **97.7% complete**. At 20.62 GB it is therefore close to its
final size; expect only ~1–2 GB more. It is not a forecast risk, but it is the
second-largest artefact and worth remembering when sizing restores or backups.

## 5. Capacity assessment

**No capacity risk to reach catch-up.** Worst case needs ~95 GB against 589 GB
free — roughly 6x headroom. Even adding continued `chainstate/` growth at the
chain tip, the margin is comfortable.

The genuine concerns are not bytes:

1. **Parquet file count: 141,594 and rising.** Maintenance has never completed a
   full rewrite (measured earlier in this campaign: zero data files attributed to
   compaction snapshots, with the budget repeatedly exceeded). A growing small-file
   population raises catalog metadata cost, open-file pressure and restore time
   long before it raises byte usage.
2. **Shared disk at 93% used.** The 589 GB free is shared with `chainstate/`
   (~280 GB and growing with the chain) and everything else on the host. The
   archive's own forecast is safe; the *host's* margin is the thing to watch.
3. **Temporary space.** DuckDB is configured with a 2 GB temp directory
   (`max-temp-directory-size`), unchanged and comfortably within headroom.

## 6. Recommended thresholds

| level | free space | rationale |
|---|---:|---|
| **warning** | **< 200 GB** | Still roughly 2x the worst-case remaining archive growth; leaves time to act deliberately |
| **critical** | **< 100 GB** | Below the high-density remaining requirement — catch-up could fail partway |

A separate alert on **Parquet file count** is worth more than a byte alert:
maintenance completing at least one full rewrite pass is the health signal, and it
is currently not happening.

## 7. Explicitly not done

No compaction was triggered, no data deleted, no retention or maintenance budget
changed. All figures are from `du`, `ls`, `df` and the read-only status endpoint.
