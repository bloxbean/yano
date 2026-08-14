# Yano DuckLake Archive Store

`archive-store-ducklake` is the default durable archive backend. DuckDB executes
queries and writes DuckLake-managed Parquet data; SQLite is the default
DuckLake catalog database.

## Default layout

Given history directory `./history`, the backend uses:

```text
./history/ducklake-catalog.sqlite
./history/ducklake-catalog.sqlite.tx-locator.sqlite
./history/ducklake-data/
./history/tmp/
```

The catalog file stores DuckLake metadata, snapshots, schema, and file
references. Historical table rows live in Parquet under `ducklake-data/`. The
separate transaction locator is an accelerator only: misses are verified
against the pinned DuckLake snapshot and it can be rebuilt from
`chain_transaction`.

Paths can be overridden with:

- `yano.history.archive.ducklake.catalog.path`
- `yano.history.archive.ducklake.data-path`
- `yano.history.archive.ducklake.extensions-path`

## Resource and consistency model

The backend never accepts DuckDB's process-wide 80%-of-memory default. It uses
the bounded steady-state and bulk-catch-up budgets supplied by the application,
limits query/job concurrency, and bounds temporary disk usage. Reads attach a
specific DuckLake snapshot read-only, while one process owns the archive writer
lock. Commits are idempotent by deterministic job ID, and coverage becomes
visible with the committed snapshot.

Signed `ducklake` and `sqlite_scanner` extensions are bundled for the build
platform. Runtime never downloads extensions. This backend is JVM-only.

## Testing

```bash
./gradlew :archive-modules:archive-store-ducklake:test
```

The suite runs the shared backend conformance contract plus DuckDB memory,
permit, interrupt, extension, snapshot, rollback, and locator checks.
