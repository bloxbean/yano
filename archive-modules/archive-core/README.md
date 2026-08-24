# Yano Archive Core

`archive-core` implements backend-independent projection and orchestration for
the optional history subsystem.

## Responsibilities

- archive configuration and dataset dependency validation;
- canonical Yaci block decoding for transaction, account-event,
  address-transaction, and UTXO-history facts;
- durable epoch-source decoding for rewards and epoch datasets;
- independent sequential outpoint and pointer resolution;
- the semantic, backend-neutral `HotHistoryStore` contract and RocksDB adapter
  with exact undo information;
- the canonical projection outbox, its finality gate, and the ordered drain.

The UTXO decoder emits only configured row families. Inline datum/reference
script bytes remain output-local, while transaction datums and redeemers are
streamed as transaction-scoped rows. No global content index or historical
archive lookup participates in projection.

Pointer resolution is temporal. Each output carries its canonical address and
the stake credential/address effective at its own ledger coordinate; no
DuckLake address dimension or address locator participates in projection.

The workers read durable canonical block or epoch sources. They do not read
mutable core UTXO/account state as the source of historical truth and they do
not participate in authoritative block commits. Dataset or backend failure
must degrade only archive health.

Each canonical body is parsed once as it is applied. Transaction, account-event,
UTXO and address projections share the immutable decoded block and may derive
concurrently on an archive-owned fixed executor.
The default parallelism is conservative and container-aware: half the reported
processors, capped by four and by the number of enabled block projections.
DuckLake retains a single serialized writer. Batch size remains
adaptive through the existing halve-on-capacity / three-successes-before-grow
rule.

## One write path

There are no backfill and live tracks. Every canonical block is projected into
the outbox as it is applied, in the same RocksDB write batch as the state it
derives from, and drains to the sink once it is past the finality gate. The
archive therefore trails the chain tip by design rather than catching up behind
it, and rollback-sensitive rows stay in the outbox until they are final.

## Dependency direction

This module depends on `archive-api`, RocksDB, and Cardano decoding libraries.
It contains no DuckLake- or SQLite-specific storage logic and no Quarkus
application wiring.

`HotHistoryStore` and `HotHistorySnapshot` keep resolvers and query code
independent of the physical hot engine. RocksDB is the only implementation; the
relational alternative went with the SQLite store module. The semantic
conformance fixture still applies to it.

## Testing

```bash
./gradlew :archive-modules:archive-core:test
```

Tests cover era decoding, genesis UTXOs, resolver behavior, dataset projection,
outbox rollback, parent-chain validation, receipt identity and artifact
handshake, restart, and failure isolation.
