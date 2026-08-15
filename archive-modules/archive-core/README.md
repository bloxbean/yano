# Yano Archive Core

`archive-core` implements backend-independent projection and orchestration for
the optional history subsystem.

## Responsibilities

- archive configuration and dataset dependency validation;
- canonical Yaci block decoding for transaction, account-event,
  address-transaction, and UTXO-history facts;
- durable epoch-source decoding for rewards and epoch datasets;
- independent sequential outpoint and pointer resolution;
- the recent mutable RocksDB hot zone with exact undo information;
- backfill, live, promotion, rollback, retention, progress, and health workers.

The UTXO decoder emits only configured row families. Inline datum/reference
script bytes remain output-local, while transaction datums and redeemers are
streamed as transaction-scoped rows. No global content index or historical
archive lookup participates in projection.

The workers read durable canonical block or epoch sources. They do not read
mutable core UTXO/account state as the source of historical truth and they do
not participate in authoritative block commits. Dataset or backend failure
must degrade only archive health.

Block backfill parses each canonical body once per bounded coordinator cycle.
Transaction, account-event, UTXO, and address projections share the immutable
decoded block and may derive concurrently on an archive-owned fixed executor.
The default parallelism is conservative and container-aware: half the reported
processors, capped by four and by the number of enabled block projections.
Both DuckLake and SQLite retain a single serialized writer. Batch size remains
adaptive through the existing halve-on-capacity / three-successes-before-grow
rule.

## Tracks

Backfill advances historical coverage from the configured start mode. The live
track can project near-tip blocks independently so wallet data does not wait
for a long backfill. Finalized hot rows are promoted atomically through the
selected `ArchiveBackend`; recent rows remain rollback-aware in the dedicated
hot store.

## Dependency direction

This module depends on `archive-api`, RocksDB, and Cardano decoding libraries.
It contains no DuckLake- or SQLite-specific storage logic and no Quarkus
application wiring.

## Testing

```bash
./gradlew :archive-modules:archive-core:test
```

Tests cover era decoding, genesis UTXOs, resolver behavior, dataset projection,
hot-store rollback, parent-chain validation, backfill/live convergence,
restart, and worker failure isolation.
