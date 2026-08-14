# Yano SQLite Archive Store

`archive-store-sqlite` provides the standalone SQLite archive engine. Unlike
DuckLake's SQLite catalog mode, this backend stores both archive metadata and
historical dataset rows in an ordinary SQLite database.

## Default layout

The default database is:

```text
<history-dir>/history.sqlite
```

It can be overridden with `yano.history.archive.sqlite.path` when
`yano.history.archive.engine=sqlite`.

## Schema management

Yano owns the relational schema. Flyway applies migrations from:

```text
db/migration/history-sqlite/
```

The migration namespace is private to this archive database. The backend does
not run migrations against an application's unrelated datasource, so an
embedding application's own `V1`, `V10`, or other Flyway versions do not
conflict. The current pre-release schema is consolidated in
`V1__archive_schema.sql`.

Writes use transactions and deterministic archive job IDs for atomic,
idempotent commits. Read sessions pin a database generation and expose the same
repositories and logical schemas as the DuckLake backend. A file lock prevents
multiple archive writers.

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
