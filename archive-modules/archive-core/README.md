# Yano Archive Core

`archive-core` implements backend-independent projection and orchestration for
the optional history subsystem.

## Responsibilities

- projection configuration and dataset dependency validation;
- canonical Yaci block decoding for transaction, account-event,
  address-transaction, and UTXO-history facts;
- durable epoch-source decoding for rewards and epoch datasets;
- capture-time pointer resolution against the account-state index;
- the canonical projection outbox, its finality gate, and the ordered drain.

The UTXO decoder emits only configured row families. Inline datum/reference
script bytes remain output-local, while transaction datums and redeemers are
streamed as transaction-scoped rows. No global content index or historical
archive lookup participates in projection.

Pointer resolution is temporal. Each output carries its canonical address and
the stake credential/address effective at its own ledger coordinate; no
DuckLake address dimension or address locator participates in projection.

Each canonical body is parsed once as it is applied. Transaction, account-event,
UTXO and address projections share the immutable normalized facts staged in the
canonical UTXO batch. DuckLake retains a single serialized projection sink.

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

## Testing

```bash
./gradlew :archive-modules:archive-core:test
```

Tests cover era decoding, genesis UTXOs, capture-time pointer behavior, dataset projection,
outbox rollback, parent-chain validation, receipt identity and artifact
handshake, restart, and failure isolation.
