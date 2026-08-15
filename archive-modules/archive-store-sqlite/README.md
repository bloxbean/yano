# Yano SQLite Archive Store

`archive-store-sqlite` provides the standalone SQLite archive engine and the
optional ADR-036 SQLite hot-history engine. Unlike
DuckLake's SQLite catalog mode, this backend stores both archive metadata and
historical dataset rows in an ordinary SQLite database.

## Default layout

The default database is:

```text
<history-dir>/history.sqlite
```

It can be overridden with `yano.history.archive.sqlite.path` when
`yano.history.archive.engine=sqlite`.

The independent hot database defaults to:

```text
<history-dir>/hot-history.sqlite
```

Select it with `yano.history.hot-store.engine=sqlite`; override its path with
`yano.history.hot-store.sqlite.path`. It works with either DuckLake or SQLite
as the durable archive engine.

## Schema management

Yano owns the relational schema. Flyway applies migrations from:

```text
db/migration/history-sqlite/
db/migration/history-hot-sqlite/
```

The migration namespace is private to this archive database. The backend does
not run migrations against an application's unrelated datasource, so an
embedding application's own `V1`, `V10`, or other Flyway versions do not
conflict. The current pre-release schema is consolidated in
`V1__archive_schema.sql`.

The hot schema uses WAL and `synchronous=FULL`. Its relational fact tables,
append-only resolver output/spend records, pointer lifecycle, checkpoints,
progress, receipts, and pruning requirements commit in one transaction per
canonical batch. Rollback deletes the exact orphan suffix; it does not use a
generic before-image journal or query the cold archive.

Durable archive writes use transactions and deterministic archive job IDs for atomic,
idempotent commits. Read sessions pin a database generation and expose the same
repositories and logical schemas as the DuckLake backend. A file lock prevents
multiple archive writers.

SQLite normalizes repeated address data internally through private `addresses`
and `stake_addresses` dimensions. Writable logical views join those dimensions
back into the same flat schema exposed by DuckLake. Address text uses
unrestricted SQLite `TEXT` (including long Byron addresses), while credential
hashes use `BLOB`; backend-private integer IDs never leave this module.

## Intended use

SQLite is suitable for devnets, smaller histories, embedded deployments, and
workloads favoring indexed point lookups and a single portable database file.
DuckLake/Parquet remains the default for large, analytics-oriented archives.

## Testing

```bash
./gradlew :archive-modules:archive-store-sqlite:test
```

The module runs the shared backend conformance suite and SQLite-specific
migration, rollback, retention, locking, and query tests.
