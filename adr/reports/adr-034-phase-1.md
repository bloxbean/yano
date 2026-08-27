# ADR-034 Phase 1: Shared Archive Infrastructure

Date: 2026-08-14  
Status: implementation complete

## Module boundary

The nested Gradle projects now match the accepted four-module design:

- `archive-api`: backend-neutral identity, jobs, anchors, receipts, coverage,
  health, retention/maintenance, schemas, queries, typed repository markers,
  provider SPI, and shared backend conformance fixtures;
- `archive-core`: backend-neutral configuration, dataset projection contracts,
  canonical block sources, durable paged epoch sources/jobs, and source leases;
- `archive-store-ducklake`: DuckDB JDBC and its bounded manager only;
- `archive-store-sqlite`: Xerial SQLite only, ready for its Phase 3 backend.

No store module depends on runtime or ledger state. `archive-core` depends only
on `archive-api`. The application/runtime does not yet depend on a store, so a
disabled archive cannot load either JDBC driver in this phase.

## Correctness contracts refined during review

Deterministic jobs now include explicit network magic plus genesis fingerprint,
dataset/projection/source version, source range, and both canonical range
anchors. Receipts preserve the same identity and anchors. This closes an
ambiguity in the Phase 0 prototype where a single end anchor was implicit.

Epoch sources are restart-discoverable, lease-protected, and explicitly paged.
An `EpochSourcePage` cannot contain more rows than its requested bound. Epoch
jobs reject block-derived datasets and defensively copy boundary hashes. No
epoch callback or whole-epoch list is introduced.

Resolved configuration enforces bounded worker batches, per-dataset retention,
UTXO-history dependency on transaction coverage, strict `k`-based safety
windows, and non-overlapping mutable database/temp paths. Runtime property
binding and lifecycle remain scheduled for Phase 4.

## Bounded DuckDB manager

The default manager enforces:

- 256 MiB aggregate memory;
- at most two DuckDB contexts;
- one 128 MiB steady context remains possible while one 128 MiB bulk context
  runs;
- one bulk job, one thread per context, a managed temp directory, and a 2 GiB
  temp limit;
- bounded connection acquisition and JDBC statement timeouts;
- `autoinstall_known_extensions=false` and
  `autoload_known_extensions=false` before extension loading.

The build downloads official signed DuckDB 1.5.5 DuckLake and SQLite-scanner
extensions for the build target into the Gradle cache, expands them into
generated module resources, and records their SHA-256 digests. Runtime extracts
only the matching resource, verifies the digest, verifies the engine version
and platform, and uses `LOAD` with an absolute path. Runtime never executes
`INSTALL` and has no extension network fallback. Release builds for each
supported target therefore package that target's signed binaries without
committing native build artifacts to source control.

## Verification

The Phase 1 tests verify module contracts, dataset dependency/retention rules,
path isolation, durable epoch-job invariants, aggregate and reserved DuckDB
budgets, bounded bulk acquisition, disabled auto-install/autoload, and loading
both packaged signed extensions without `INSTALL`.

```text
./gradlew :archive-modules:archive-api:test \
  :archive-modules:archive-core:test \
  :archive-modules:archive-store-ducklake:test \
  :archive-modules:archive-store-sqlite:test
```

Full core/runtime regression tests and application packaging are run before the
phase branch is merged. No core block-apply, account-state, ledger-state, UTXO,
or pruning mutation is part of Phase 1.
