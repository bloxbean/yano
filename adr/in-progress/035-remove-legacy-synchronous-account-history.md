# ADR-035: Remove the Legacy Synchronous Account-History Index

## Status

Proposed

## Date

2026-08-14

## Context

PR #58 introduced an optional account-history index before ADR-034 established
the asynchronous archive architecture. The implementation is still present in
the runtime and ledger-state modules even though the packaged application now
rejects `yano.account-history.enabled=true` and always passes `false` to the
runtime.

The legacy implementation consumes block-apply events and writes two RocksDB
column families synchronously:

- `account_history` stores withdrawals, stake registrations and
  deregistrations, stake-pool delegations, MIR entries, address transaction
  references, and optional reward rows;
- `account_history_delta` stores per-block keys used for rollback.

It also carries startup reconciliation, pruning, rollback verification,
snapshot-restore hooks, an event handler, a CBOR codec, configuration keys,
runtime accessors, and tests. Direct library users can still reach this path by
constructing the runtime with `yano.account-history.enabled=true`, even though
the application distribution cannot.

[ADR-034](034-optional-asynchronous-history-archive.md) replaces this design
with independently advancing archive datasets, a bounded rollback-aware hot
zone, and either DuckLake/Parquet or standalone SQLite durable storage. Keeping
both implementations creates two meanings for “account history,” preserves a
second rollback and pruning system, and leaves an unsupported synchronous write
path close to authoritative ledger processing.

The legacy feature has not been released. Backward compatibility for enabling
it is therefore not required. The authoritative account-state implementation
is released core behavior and is not part of this removal.

## Decision

Remove the legacy synchronous account-history implementation after ADR-034 is
the only supported history provider.

The removal includes:

1. `AccountHistoryStore`, `AccountHistoryEventHandler`,
   `AccountHistorySubsystem`, the legacy CBOR codec, and their tests.
2. Creation and runtime wiring of the `account_history` and
   `account_history_delta` column families.
3. Legacy configuration under `yano.account-history.*`, including rollback
   retention planning and application configuration bindings.
4. Legacy runtime/store accessors and snapshot, rollback, pruning, recovery,
   and shutdown hooks.
5. Fallback from REST resources to the legacy ledger query provider. When the
   ADR-034 archive is disabled or a required dataset lacks coverage, history
   endpoints continue to return the documented unavailable or incomplete
   response; they must never read the removed RocksDB index.

Retain the backend-neutral `AccountHistoryProvider` compatibility interface
while the REST layer and `ArchiveAccountHistoryProvider` use it. It may be
renamed or replaced separately after the archive API becomes the direct REST
contract.

Do not change or remove:

- `DefaultAccountStateStore` or any authoritative current account, reward,
  delegation, stake, protocol-parameter, or ledger state;
- the current UTXO store or its rollback behavior;
- ADR-034 archive datasets, workers, hot history RocksDB, epoch staging, or
  archive backends;
- shared address/outpoint utilities that have non-legacy callers.

## Existing Preview Data

No automatic migration from the legacy column families will be implemented.
Their rows can be rebuilt from canonical block and durable epoch sources by
ADR-034. Automatic copying would preserve known completeness problems and
would couple core RocksDB startup to archive migration.

Before the legacy column-family descriptors are removed, keep the explicit
offline `LegacyAccountHistoryCleanup` command long enough to clean databases
created by PR builds. It must require the exact database path and confirmation
token and must never run automatically. Since RocksDB requires all existing
column families to be opened, operators with a preview database must either:

- run the cleanup command before upgrading; or
- discard and rebuild that preview database.

The cleanup command and the two legacy column-family names can be removed once
no supported upgrade path needs them. Release notes must state this clearly.

## Implementation Sequence

1. Add a regression test proving the packaged application and direct runtime
   assembly cannot activate the legacy path.
2. Route wallet history APIs exclusively through the ADR-034 archive provider
   and verify disabled, unavailable, partial-coverage, and healthy responses.
3. Remove legacy block and reward write wiring, then remove the store,
   subsystem, codec, pruning service integration, and rollback hooks.
4. Remove legacy configuration properties and retention planning. Reject the
   removed umbrella key with a focused migration message for one release cycle
   if configuration validation would otherwise ignore it silently.
5. Remove legacy CF creation after documenting and testing the offline cleanup
   path against a database containing both legacy and core column families.
6. Remove obsolete tests and add a repository search/build guard preventing
   the legacy types and configuration keys from returning.

Each step must preserve a buildable tree. No intermediate commit may enable
both synchronous and asynchronous writers for the same history data.

## Verification and Acceptance Criteria

Removal is complete only when:

- a repository search finds no runtime construction or event registration for
  the legacy store;
- `yano.account-history.*` no longer appears as supported configuration;
- history disabled creates no archive resources and adds no work to block or
  epoch apply;
- history enabled uses only ADR-034 workers and storage backends;
- core account-state, ledger-state, UTXO, rollback, snapshot restore, devnet
  time travel, and restart tests pass unchanged;
- archive backend conformance, block/epoch projection, rollback, restart, and
  REST history tests pass;
- a clean build succeeds from a fresh checkout; and
- the ADR-034 disabled-versus-enabled sync regression and soak gates remain
  satisfied.

## Consequences

The runtime loses an unreleased synchronous history option and becomes easier
to reason about: authoritative current state remains in the core stores, while
all optional historical projection belongs to ADR-034. Preview users must
rebuild history and may need to clean the two old column families before
opening an existing RocksDB database.

The compatibility provider interface remains temporarily, so this decision
does not require a simultaneous REST DTO redesign.

## Rejected Alternatives

### Keep the implementation permanently disabled

Rejected because dead event, rollback, pruning, and storage code continues to
increase maintenance and can still be activated by direct runtime assembly.

### Migrate legacy rows into the archive

Rejected because the old address-input and reward paths have known
completeness and ordering limitations. Canonical replay is the trusted archive
source.

### Remove account state together with account history

Rejected because account state is authoritative current ledger state. It is a
different subsystem with different correctness and retention requirements.
