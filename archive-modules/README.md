# Yano archive modules

These modules implement the optional history archive. They keep historical
projection outside the authoritative block-apply path, writing through a
canonical projection outbox into a single durable backend, DuckLake.

For the complete runtime flow, durability boundaries, restart/rollback behavior,
staged-file layout, and failure cases, see the
[archive projection design](../docs/archive/ARCHIVE_PROJECTION_DESIGN.md).

## Modules

| Gradle project | Purpose |
|---|---|
| `:archive-modules:archive-api` | Backend-neutral schemas, storage contracts, query sessions, and typed repositories. |
| `:archive-modules:archive-core` | Block and epoch projection, the canonical projection outbox, independent resolvers, and progress tracking. |
| `:archive-modules:archive-store-ducklake` | DuckLake tables over Parquet, using DuckDB for access and SQLite as the default catalog database. |

Dependencies flow inward: stores and core depend on `archive-api`; application
composition lives in the top-level `app` module. The archive modules do not
own authoritative UTXO, account, reward, or ledger state.

## Storage choices

`ducklake` is the default archive engine. Its default layout is:

```text
<history-dir>/
  ducklake-catalog.sqlite          DuckLake metadata catalog
  ducklake-catalog.sqlite.tx-locator.sqlite
  ducklake-data/                   DuckLake-managed Parquet data
  tmp/                             bounded DuckDB temporary storage
```

The SQLite catalog does not contain the historical dataset rows; DuckLake owns
its catalog schema and stores those rows in Parquet. The transaction-locator
SQLite file is a rebuildable point-lookup accelerator.

DuckLake is the only archive engine. A standalone SQLite backend existed once
and was removed with its module; `yano.history.archive.engine=sqlite` is now
rejected at startup. SQLite itself has not left the stack — DuckLake keeps its
catalog and the transaction locator in SQLite files, as above.

There is no separate hot store. Rollback-sensitive state lives in the projection
outbox, inside the node's own RocksDB, so a contributor's write is atomic with the
state it derives from.

## Datasets

The shared schemas cover account events, address transactions, transactions,
UTXO history, rewards, epoch stake, DRep distribution, Ada pots, and governance
proposal status.

Block sections — `transaction:v1`, `utxo-history:v1`, `account-events:v1` and
`address-transaction:v1` — are selectable through
`yano.history.projection.sections`, all four by default. The choice is part of
the archive identity, so it is made once at fresh sync: an archive cannot gain
or drop a section later, because the earlier blocks would be missing from it.

Epoch artifacts are selected separately through
`yano.history.projection.epoch-artifacts`. On a fresh archive, omitting the setting
selects the shipped set; on an existing archive, omission preserves its stored
enrollment. Rewards, epoch stake, DRep distribution, Ada pots and governance
proposal status are available. A newly selected artifact joins a populated archive
only prospectively, with its first projected epoch recorded in durable enrollment.

Outputs carry inline datum and reference-script CBOR directly; witness datums
and redeemers use transaction-scoped tables.

DuckLake facts are flat and self-contained for address and stake queries; there
is no address dimension or address locator. Per-subject scoping of address
transactions — address, payment credential, stake credential — went with the
replay worker; the section is written whole.

## Runtime use

History is disabled by default. The JVM distribution includes the optional
`projection` profile:

```bash
./yano.sh start:preprod,projection
```

For stake-address transaction history without the other block sections, the
`wallet` profile selects `address-transaction:v1` alone:

```bash
./yano.sh start:preprod,wallet
```

See [`application-projection.yml`](../app/config/application-projection.yml) for
the operator defaults. DuckLake requires the JVM distribution; native startup
rejects enabled history.

There is no bulk backfill to schedule against core catch-up. The projection
records every canonical block as it is applied and drains to the sink once a
block is past the finality gate, so the archive trails the chain tip by design
rather than catching up behind it. The `worker.*` settings that tuned the old
backfill are rejected at startup.

## Build and test

```bash
./gradlew \
  :archive-modules:archive-api:test \
  :archive-modules:archive-core:test \
  :archive-modules:archive-store-ducklake:test
```

Backend implementations share the conformance suite supplied by
`archive-api` test fixtures. Integration and release gates are recorded in the
[archive phase reports](../adr/reports/).
