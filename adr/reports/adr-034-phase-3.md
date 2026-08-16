# ADR-034 Phase 3 Verification Report

Date: 2026-08-14
Branch: `feat/adr-034-phase-3-sqlite`

## Outcome

Phase 3 delivers the standalone SQLite archive backend as a complete initial
backend, not a placeholder. It stores rows in one ordinary SQLite database and
does not initialize DuckDB, DuckLake, or Parquet.

Flyway 12.2.0 is pinned because it is the last open `flyway-core` line that
contains SQLite support. Flyway 13 moved SQLite to the separately licensed
`flyway-database-nc-sqlite` artifact. Xerial SQLite JDBC remains pinned at
3.53.1.0.

## Implemented contract

- two dedicated SQL migrations under
  `classpath:db/migration/history-sqlite`, with `baselineOnMigrate=false`,
  clean disabled, strict migration naming, migrate-time validation, and an
  explicit post-migration validation;
- stable, unversioned physical tables, B-tree indexes for documented access
  paths, exact logical keys, job foreign keys, and the same convenience views
  exposed by DuckLake;
- archive/network identity, projection metadata, canonical coverage,
  deterministic receipts, row counts, ordered digests, and a monotonic SQLite
  archive generation;
- WAL, foreign keys, finite busy/query timeouts, bounded readers, one serialized
  writer, and `synchronous=FULL` by default (`NORMAL` remains an explicit
  durability tradeoff);
- transactional job publication, exact retry verification, fail-closed overlap
  and provenance checks, and cascade-based job invalidation/retention;
- request-pinned WAL read transactions, including generation-consistent
  receipt and coverage metadata reads;
- passive WAL checkpoint, bounded optimize, budget-gated `VACUUM`, page/free
  page/WAL statistics, `PRAGMA integrity_check`, and Xerial's SQLite online
  backup API;
- ServiceLoader discovery and a non-blocking scale warning keyed by the known
  public mainnet Shelley-genesis hash rather than network magic.

Native-asset `DECIMAL(38,0)` values are stored as canonical decimal text. This
is intentional: SQLite NUMERIC affinity may represent an unsigned 64-bit value
as floating point, while text preserves exact values and lexical identity.

## Failure semantics reviewed

- unknown non-empty databases are not silently baselined;
- failed, closed, or constraint-rejected jobs publish no rows or coverage;
- a committed retry returns the same generation and receipt, while changed
  rows under the same job ID fail closed;
- duplicate logical keys and wrong job provenance fail before publication;
- retention deletes only whole committed job ranges;
- an old reader continues to see its WAL snapshot through concurrent commit or
  retention;
- reader count and writer wait are bounded, and aborting on another executor
  releases the writer permit;
- identity mismatch and a second process writer fail closed;
- backup is a consistent, independently queryable database.

## Verification

The shared backend conformance fixture and SQLite-specific tests cover Flyway
creation/restart, WAL snapshot isolation, independent read-only access,
ServiceLoader discovery, abort/retry, invalidation/retention, reader bounds,
cross-thread cleanup, identity/locking, integrity, maintenance, backup, and an
exact quantity of `18446744073709551615`.

Commands:

```text
./gradlew :archive-modules:archive-store-sqlite:test
./gradlew :archive-modules:archive-api:test \
  :archive-modules:archive-core:test \
  :archive-modules:archive-store-ducklake:test \
  :archive-modules:archive-store-sqlite:test
./gradlew :app:build -x test
```

All commands passed on Java 25. Runtime sync validation is deferred until the
archive worker is integrated; this backend branch changes no core or runtime
source and the application still has no archive lifecycle dependency.
