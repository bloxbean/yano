# ADR-034 Phase 0: Measurements and Contract Decisions

Date: 2026-08-14  
Status: complete for implementation baseline

## Scope and environment

Measurements used DuckDB JDBC 1.5.5.0, DuckLake 1.0, Xerial SQLite JDBC
3.53.1.0, one DuckDB thread, and a 256 MB memory limit. The checked-in
`archiveLayoutBenchmark` task reproduces the layout probe from a trusted Yaci
Store-style `address_utxo` Parquet input. Generated databases and Parquet files
remain build artifacts and are not committed.

Mainnet fixtures came from a local Yaci Store DuckLake export. The preprod
fixture was projected read-only from a local cardano-db-sync database at
2026-05-05, then passed through the same benchmark. Counts are facts about the
selected ranges, not network-wide storage estimates.

## Fixture cardinality

| Network/profile | Range | Outputs | Native-asset rows | Wide amount rows | Wide rows bearing datum | Wide rows bearing script |
|---|---:|---:|---:|---:|---:|---:|
| mainnet ADA-only | 2018-01-30 | 6,805 | 0 | 6,805 | 0 | 0 |
| mainnet multi-asset | 2021-04-26 | 82,276 | 42,404 | 124,680 | 0 | 0 |
| mainnet asset/datum heavy | 2024-03-23 | 138,849 | 512,394 | 651,243 | 37,949 | 31 |
| mainnet recent datum heavy | 2025-11-27 | 81,897 | 158,917 | 240,814 | 39,952 | 1 |
| preprod mixed | 2026-05-05 | 53,738 | 12,442 | 66,180 | 5,349 | 176 |

The normalized layout always emits one output row, zero or more native-asset
rows, and content-addressed datum/script rows. It does not emit an asset child
for lovelace.

## Layout results

Sizes include deduplicated datum and script payload tables in the normalized
result. SQLite measurements include the query indexes created by the probe.

| Fixture | Wide Parquet | Normalized Parquet | Wide SQLite | Normalized SQLite |
|---|---:|---:|---:|---:|
| mainnet ADA-only | 643,216 B | 642,383 B | 2,850,816 B | 2,711,552 B |
| mainnet multi-asset | 9,167,812 B | 6,839,916 B | 74,088,448 B | 47,456,256 B |
| mainnet asset/datum heavy | 16,551,721 B | 10,192,367 B | 467,468,288 B | 191,807,488 B |
| mainnet recent datum heavy | 7,949,289 B | 5,279,452 B | 181,936,128 B | 78,262,272 B |
| preprod mixed | 2,878,297 B | 2,265,788 B | 67,227,648 B | 47,255,552 B |

The result supports the normalized physical design. It is essentially neutral
for ADA-only output sets and materially smaller when an output has multiple
amount rows. A stable merged view will provide the convenient one-row-per-
amount shape.

On the largest fixture, best warm measurements were approximately 3.4 ms for a
direct single-file Parquet tx-hash scan and 0.3 ms for a slot range. Direct
Xerial SQLite indexed lookups were approximately 13 microseconds and the
indexed range approximately 184 microseconds. Queries routed through DuckDB's
SQLite extension were much slower (roughly 9-44 ms), so the standalone SQLite
backend must use Xerial JDBC directly; it is not implemented as DuckDB over a
SQLite file.

The benchmark rewrote the largest Parquet fixture in about 0.7 seconds and
built both indexed SQLite layouts in about 2.1 seconds on the development
machine. These are comparative microbenchmarks, not sync-throughput promises.

## Tx-hash lookup and Parquet metadata

DuckDB's emitted metadata confirmed the ADR assumption:

- high-cardinality `tx_hash` used plain encoding and had no Bloom filter;
- dictionary-encoded stake-credential and slot columns had Bloom filters;
- a tx hash cannot depend on epoch partitions, zone maps, or a presumed Bloom
  filter for bounded point lookup.

DuckLake therefore uses a small rebuildable locator first and verifies the row
in `chain_transaction`. SQLite uses its native B-tree on `tx_hash`. Forcing
dictionary encoding of tx hashes is rejected.

## DuckLake SQLite-catalog snapshot gate

`DuckLakeSnapshotConcurrencyTest` creates a DuckLake 1.0 archive with a SQLite
catalog, captures its snapshot ID, and attaches it using `SNAPSHOT_VERSION` and
`READ_ONLY`. Multiple reader subqueries remained on the captured one-row
snapshot while another DuckDB connection committed a second row. A fresh
reader observed two rows. Detach and cancellation completed within the bounded
test.

This proves the request-scoped snapshot mechanism selected by the ADR on the
pinned versions. Phase 2 extends this gate with lock contention, retry
exhaustion, snapshot leases, expiration, and cleanup tests in the production
manager.

## Stable schema and transaction scalar decision

`ArchiveSchemas` is the executable version-one schema registry for all nine
datasets. Stable physical names, primary keys, source kinds, and value-based
pagination order are shared by both backends. `chain_transaction` is the
physical table name in both engines.

The version-one transaction scalar allowlist is deliberately closed:

| Field | Meaning | Availability | API use |
|---|---|---|---|
| `tx_hash` | canonical transaction body hash | every on-chain transaction | identity/status lookup |
| canonical block tuple | block hash/number, slot, epoch, block time, tx index | every on-chain transaction | provenance/order |
| `valid` | phase-2 ledger validity flag | Shelley-family transactions; true where the era has no phase-2 concept | status/collateral semantics |
| `fee` | fee declared by the transaction body | every supported transaction era | transaction detail |

No other scalar transaction fields are approved for version one. New columns
must declare ledger meaning, historical completeness, and an actual query use.

## Pointer-address semantics

The reference was checked against local `cardano-ledger` commit `c8cbb661d3`.
Shelley through Babbage resolve `StakeRefPtr` through the registered pointer map
when calculating stake. The Conway constrained-ledger model explicitly sets
`hasPtrs` false, while the address codec continues to preserve pointer-form
addresses. Therefore the archive:

- always stores the raw address and pointer coordinates;
- resolves a pointer to a stake credential only in eras where ledger stake
  semantics use the pointer map;
- reports a Conway/later pointer as not stake-effective rather than unresolved;
- treats a missing pre-Conway pointer mapping as unresolved, not as an address
  with no stake reference.

This corrects the overly broad interpretation that pointer addresses cannot be
decoded in Conway: they remain address facts but no longer contribute through
pointer stake resolution.

## Governance observation vocabulary

Yano's current evaluation result is `ACTIVE`, `RATIFIED`, or `EXPIRED`.
`delayed_by_prior_action` is a reason for an `ACTIVE` evaluation, not a fourth
status. Repository issue 47/PR 48 established that parameter changes are not
delaying actions; no-confidence, committee update, constitution, and hard-fork
actions are delaying. The pinned Haskell ledger represents an epoch-boundary
ratification result using enacted actions, expired actions, and a separate
`rsDelayed` flag. Yano intentionally has a one-boundary pending-enactment stage.

The archive vocabulary is therefore phase-qualified text, not a closed enum:

- `observation_phase=ratification`: `active`, `ratified`, `expired`, with an
  exact decision reason including `delayed_by_prior_action` where applicable;
- `observation_phase=enactment`: `enacted`;
- `observation_phase=removal`: `dropped_sibling`, `dropped_descendant`, or
  `dropped_expired`.

The durable Phase-1 staging source must preserve those removal causes; the
current pending-key sets alone are insufficient.

## Safety and core-path boundary

`ArchiveSafetyWindows` reads an explicit genesis `k`, defaults rollback and
archive finality to `2*k`, rejects either below `k`, and rejects finality below
rollback retention. There is no unsafe override.

The only Phase-0 runtime change is read-only chain capability exposure for a
canonical block reference and the earliest retained block body. RocksDB block
apply, ledger state, account state, UTXO state, event priorities, and pruning
decisions are unchanged. Existing pruning tests and the new capability tests
pass.

An isolated preprod JVM smoke run with history disabled synced 59,286 block
bodies from genesis in about 33 seconds. A restart on the same RocksDB directory
validated chain-state integrity, restored the epoch-6 nonce at body block
59,286/slot 1,269,937, resumed from the persisted header tip, and progressed
past block 144,000 without a nonce mismatch, missing body, unrepaired rollback,
corruption, exception, or no-progress error. Logs are retained under the local
`app/build/test-runs/adr034-phase0-preprod` build-artifact directory.

## Reproduction

```text
./gradlew :archive-modules:archive-api:test
./gradlew :archive-modules:archive-api:integrationTest \
  --tests com.bloxbean.cardano.yano.archive.benchmark.DuckLakeSnapshotConcurrencyTest
./gradlew :archive-modules:archive-api:archiveLayoutBenchmark \
  -Pfixture='/trusted/address_utxo/range/*.parquet' \
  -PbenchmarkOutput='/tmp/yano-archive-layout'
```

The abstract backend conformance fixture is published through Gradle test
fixtures. Each concrete backend phase must execute it; it covers abort,
deterministic idempotent receipt replay, explicit coverage, and pinned read
generation behavior.
