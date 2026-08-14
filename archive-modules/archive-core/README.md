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

The workers read durable canonical block or epoch sources. They do not read
mutable core UTXO/account state as the source of historical truth and they do
not participate in authoritative block commits. Dataset or backend failure
must degrade only archive health.

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
