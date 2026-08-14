# Yano archive modules

These modules implement the optional asynchronous history architecture from
[ADR-034](../adr/in-progress/034-optional-asynchronous-history-archive.md).
They keep historical projection outside the authoritative block-apply path and
support two interchangeable durable backends.

## Modules

| Gradle project | Purpose |
|---|---|
| `:archive-modules:archive-api` | Backend-neutral schemas, storage contracts, query sessions, and typed repositories. |
| `:archive-modules:archive-core` | Block and epoch projection, independent resolvers, hot rollback-aware RocksDB, progress tracking, and workers. |
| `:archive-modules:archive-store-ducklake` | DuckLake tables over Parquet, using DuckDB for access and SQLite as the default catalog database. |
| `:archive-modules:archive-store-sqlite` | Standalone relational SQLite backend with a Yano-owned Flyway schema. |

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

The `sqlite` engine instead stores catalog metadata and historical rows in the
single default file `<history-dir>/history.sqlite`. Yano owns that schema and
migrates it with Flyway.

## Datasets

The shared schemas cover account events, address transactions, transactions,
rewards, optional UTXO history, epoch stake, DRep distribution, Ada pots, and
governance proposal status. Each dataset has independent enablement, start
mode, coverage, projection version, retention, and health.

## Runtime use

History is disabled by default. The JVM distribution includes the optional
`history` profile:

```bash
./yano.sh start:preprod,history
```

See [`application-history.yml`](../app/config/application-history.yml) for the
operator defaults. DuckLake currently requires the JVM distribution; native
startup rejects enabled history.

## Build and test

```bash
./gradlew \
  :archive-modules:archive-api:test \
  :archive-modules:archive-core:test \
  :archive-modules:archive-store-ducklake:test \
  :archive-modules:archive-store-sqlite:test
```

Backend implementations share the conformance suite supplied by
`archive-api` test fixtures. Integration and release gates are recorded in the
[ADR-034 phase reports](../adr/reports/).
