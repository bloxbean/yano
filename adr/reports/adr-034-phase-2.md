# ADR-034 Phase 2 Verification Report

Date: 2026-08-14
Branch: `feat/adr-034-phase-2-ducklake`

## Outcome

Phase 2 implements the DuckLake archive backend with a SQLite catalog and a
separate DuckLake-managed Parquet directory. It does not add the archive to the
node runtime or alter block application, ledger state, account state, or UTXO
state.

## Implemented contract

- stable, unversioned dataset tables and convenience views from `archive-api`;
- archive/network identity and per-dataset projection metadata;
- zero data inlining, Zstandard compression, bounded row groups, epoch
  partitioning where applicable, and configured target file size;
- one process/file writer lock plus a fair, thread-independent writer permit;
- staged transactional jobs with deterministic IDs, ordered row digests,
  logical-key validation, receipts, row counts, and canonical coverage;
- exact idempotent replay and fail-closed detection of changed replay content;
- request-scoped `SNAPSHOT_VERSION` read sessions and active snapshot leases;
- conservative job-granular invalidation and retention in new snapshots;
- time/byte-bounded merge, expiration, cleanup, and orphan cleanup, deferred
  while a pinned reader is active;
- full-table integrity scans and coordinated SQLite catalog backup;
- `ServiceLoader` backend discovery and clear health degradation states.

The `address_asset_flow` view exposes both received and consumed amounts. An
independent read-only DuckDB context can attach the archive while Yano owns the
writer.

## Failure semantics reviewed

- an uncommitted or failed job leaves no rows, receipt, or coverage;
- a committed retry does not create a new snapshot;
- changed metadata or rows under the same job ID fails closed;
- overlapping coverage and duplicate logical keys fail before publication;
- rows carrying another job's provenance fail before publication;
- a pinned reader remains on its captured snapshot during writes, retention,
  and later queries;
- maintenance cannot race snapshot establishment and never consumes a zero
  rewrite budget;
- closing a write session on another executor cannot leak the writer permit;
- a second writer or mismatched archive/network identity fails closed;
- content-addressed datum/script re-insertion is idempotent while conflicting
  payloads fail closed.

Retention is deliberately job-granular: only committed ranges wholly below the
cutoff are removed. A cutoff inside a job is rounded down to the preceding job
boundary, retaining extra data instead of deleting still-retained rows.

## Verification

The DuckLake backend conformance suite covers abort, restart, retry,
snapshot-pinned reads, concurrent writer/readers, retention, invalidation,
physical Parquet creation, independent read-only attachment, identity/lock
failures, backup, integrity, provenance, and logical-key enforcement.

Commands:

```text
./gradlew :archive-modules:archive-store-ducklake:test
./gradlew :archive-modules:archive-api:test \
  :archive-modules:archive-core:test \
  :archive-modules:archive-store-ducklake:test \
  :archive-modules:archive-store-sqlite:test
./gradlew :app:build -x test
```

All commands passed on Java 25 with DuckDB JDBC 1.5.5.0 and the packaged,
checksum-verified DuckLake/SQLite extensions. Runtime network-sync validation
is not repeated in this phase because no runtime module depends on the backend
yet and no core-path source changed; the Phase 0 preprod baseline remains the
comparison point for the later integration phases.
