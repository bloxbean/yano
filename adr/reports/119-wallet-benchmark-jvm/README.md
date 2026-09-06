# JVM synthetic wallet-index storage spike — 2026-09-06

This is a physical storage/filter-walk measurement, **not a historical-chain sync
benchmark or a production default recommendation**. All four modes completed one
million synthetic block batches. The filters-only and combined databases each
contain one million filters; every scan verified the physical count and continuity.

Command:

```sh
./gradlew :runtime:benchmarkWalletIndexes \
  -PwalletBenchmarkDirectory=/private/tmp/yano-119-bench-million-v2 \
  -PwalletBenchmarkBlocks=1000000
```

The databases and full log remain under `/private/tmp/yano-119-bench-million-v2`
and `/private/tmp/yano-119-bench-million-v2.log`. The earlier `...-million` run
was intentionally stopped: repetitive fixture bytes made compression unrealistic.
None of its figures are used here.

## Workload and environment

- Java 25.0.2, macOS ARM64, JVM max heap 1 GiB; production RocksDB CF setup.
- Logged RocksDB configuration: 32 MiB configured block cache, 96 MiB total shared
  cache capacity, 64 MiB write-buffer budget, two background jobs, tuning enabled.
- One million distinct base addresses with deterministic pseudorandom payment and
  stake credential bytes. Four created-address observations per block cycle
  through that population; later observations repeat existing addresses.
- Pseudorandom block hashes; filter cardinalities cycle through 0, 8, 40, 80.
- An identical baseline cursor update and one RocksDB write batch per block in
  every mode; 86,400-block undo window. No ledger, UTxO, or body writes.
- Query IDs are disjoint from inserted elements; candidates are false positives.
- A process sample during first-seen measured about 1.30 GB RSS, including heap,
  native memory and caches. This is a sample, not a measured peak or a memory limit.

## Results

Decimal MB/GB are used below. Raw values are in [report.json](report.json).

| Mode | Apply wall s | Process CPU s | p50 / p99 microseconds | Main-thread allocation GB | GC ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 6.15 | 6.29 | 6.17 / 10.96 | 0.27 | 4 |
| First-seen | 181.42 | 156.84 | 29.25 / 1546.83 | 10.68 | 28 |
| Filters | 13.39 | 14.53 | 11.71 / 22.25 | 7.42 | 19 |
| Both | 175.58 | 177.87 | 74.25 / 450.79 | 17.81 | 36 |

| Mode | First-seen SST MB | Filter SST MB | Undo SST MB | Closed directory MB | Sampled peak directory MB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 0 | 0 | 0 | 0.35 | 46.53 |
| First-seen | 63.48 | 0 | 4.59 | 68.60 | 234.36 |
| Filters | 0 | 107.69 | 4.60 | 112.83 | 200.00 |
| Both | 63.48 | 107.82 | 9.19 | 181.26 | 265.73 |

Coverage metadata occupies about 1.2 KB per database after compaction. Peak
samples include WAL and temporary files; short-lived compaction peaks can be missed.
Raw per-CF RocksDB reports accompany this file. For example, the combined mode
reported approximately 0.17 GB of first-seen compaction writes, 0.10 GB of filter
compaction writes and 0.22 GB of undo compaction writes. These rounded counters
are not total device writes; WAL totals were not available from the collected
statistics and zero-valued WAL counters must not be interpreted as zero I/O.

The filters-only scan results were:

| Credentials | Reopened walk s | Repeat walk s | False-positive blocks / 1M |
| --- | ---: | ---: | ---: |
| 1 | 1.615 | 1.557 | 70 |
| 10 | 1.777 | 1.767 | 592 |
| 200 | 6.751 | 6.713 | 12,147 |

The combined database returned the same candidate counts. Reopening clears the
RocksDB cache but does not evict the OS page cache. No candidate bodies were read;
these times are not wallet history scan completion times. In particular, 200
credentials generated more than twelve thousand candidate body reads per million
blocks for a completely disjoint query in this fixture.

## Interpretation and remaining validation

First-seen existence checks need a real CPU/cache budget. A main-thread sample
during a slow interval was in RocksDB.get; GC consumed only tens of milliseconds
in the timed section. Median latency alone hides the lookup tail. The combined
wall time being slightly lower than first-seen-only is not evidence of a speedup:
this is a single run in fixed order with shared OS caches and variable compaction.
Do not calculate production sync slowdown percentages from this minimal baseline.

Storage is additive and temporary disk can exceed final compacted size materially.
The approximately 108 bytes/filter and 63.5 bytes/address observed here depend on
this cardinality distribution and compaction state. They do not replace the
conservative issue estimates or establish a mainnet address count. Real blocks
with different credential counts and real LSM state must be measured.

The benchmark compiled-class hashes are retained in
[loaded-runtime-class-hashes.json](loaded-runtime-class-hashes.json). Subsequent
review added first-seen record validation and a canonical-point query guard; this
spike does not measure API latency for those checks. Re-run on the final release
candidate before making a production recommendation.

Outstanding: representative serialized-era replay and brute-force comparison,
normal sync/apply overhead for all four modes, native performance, true cold-disk
reads, candidate body confirmation, live-sync contention, and measured peak memory
and physical write totals. The wallet profile remains unswitched pending those gates.
