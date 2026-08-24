# Yano Archive API

`archive-api` defines the backend-neutral contract for Yano historical data.
It contains no DuckDB, DuckLake, SQLite, RocksDB, worker, or application
wiring.

## Responsibilities

- dataset identifiers, ranges, coverage, receipts, retention cutoffs, and
  archive health;
- atomic and idempotent write-session contracts;
- generation-pinned read-session contracts;
- logical table schemas and projection versions;
- bounded query, pagination, and typed repository interfaces;
- the `ArchiveBackendProvider` ServiceLoader SPI used by storage engines.

`ArchiveSchemas` is the common logical schema source for both backends. A
schema change must declare its historical completeness semantics and update
the affected projection version when row meaning changes.

## Dependency direction

This module depends only on `core-api`. Archive backends and `archive-core`
depend on it; it must never depend on a backend or on the application module.

## Testing

The module publishes reusable test fixtures containing the backend conformance
suite. Each storage implementation runs that same contract.

```bash
./gradlew :archive-modules:archive-api:test
```

The Phase 0 layout and DuckLake snapshot checks use the integration-test source
set and are intentionally separate from the normal unit suite.
