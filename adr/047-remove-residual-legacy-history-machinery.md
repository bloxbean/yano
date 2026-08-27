# ADR-047: Remove Residual Legacy History Machinery

## Status

Accepted and implemented

## Date

2026-08-27

## Related decisions and evidence

- [ADR-035](in-progress/035-remove-legacy-synchronous-account-history.md) removed
  the synchronous RocksDB account-history writer.
- [ADR-039](in-progress/039-canonical-projection-outbox.md) made the canonical
  projection outbox the only archive writer.
- [ADR-039 Phase 7a parity](reports/adr-039-phase-7a-parity-2026-08-22.md)
  accepted row-for-row parity for every block dataset, including Byron.
- [ADR-039 Phase 5/7b validation](reports/adr-039-phase5-7b-validation-2026-08-23.md)
  moved all five epoch artifacts onto the projection path and retained the
  staged-file foundation required by irreproducible artifacts.
- [ADR-044](044-selective-epoch-artifact-projection.md) and
  [ADR-045](045-epoch-artifact-gaps-and-resumable-capture.md) define the current
  epoch-artifact selection, coverage, gap and recovery model.
- [Implementation report](reports/adr-047-implementation-report-2026-08-27.md)
  records the deletion inventory, automated verification and live preprod/devnet
  acceptance evidence.

## Context

Yano previously had two history implementations.

1. A synchronous account-history index wrote `account_history` and
   `account_history_delta` in the node's RocksDB apply path. ADR-035 removed that
   writer, its event handler, codec, rollback integration and runtime subsystem.
2. ADR-034 introduced an asynchronous replay-worker archive. It decoded retained
   blocks into a hot RocksDB store, maintained catch-up and live tracks, promoted
   rows into DuckLake, and tracked per-dataset jobs and coverage.

ADR-039 replaced the second implementation with one ordered flow:

```text
canonical apply
  -> projection outbox
  -> finality-gated ProjectionOutboxConsumer
  -> ProjectionSink
  -> projection receipt and acknowledgement
```

The current runtime has one ongoing archive writer. The block replay workers and
their sources have already been removed, and shipped configuration no longer
enables the old path. Nevertheless, a sizeable part of the replay-worker model is
still compiled, packaged and tested:

- worker/start-mode/dataset configuration records;
- the complete hot-history RocksDB API and implementation;
- catch-up/live progress and track types;
- sequential outpoint and pointer resolvers used only by that hot store;
- stateful dataset implementations whose small stateless row-building pieces are
  still reused by the projection;
- dead replay-worker fields, helper methods and imports in
  `HistoryArchiveService`; and
- the old generic backend job/write contract and DuckLake write session, although
  current archive writes use `ProjectionSink` instead.

This residue is not an alternative operational path: no production code constructs
`RocksDbHotHistoryStore`, starts an archive worker, or calls
`ArchiveBackend.begin(ArchiveJob)`. It is still harmful. It preserves a second
vocabulary for rollback, coverage, promotion, retention and failure; makes source
searches suggest that two archive writers exist; keeps unused RocksDB and DuckLake
write code in the distribution; and causes new work to be designed against obsolete
abstractions.

### What was already removed

The original synchronous account-history implementation is gone. The following are
intentional remnants, not active synchronous history:

- `AccountHistoryProvider` is the REST-facing compatibility read interface;
- `ArchiveAccountHistoryProvider` reads projection-created DuckLake tables;
- `LegacyAccountHistoryCleanup` is an explicit offline tool for preview databases
  that still contain the two removed column families; and
- configuration rejection guards prevent stale settings from being silently
  ignored.

New chain states do not create the two synchronous-history column families, and the
repository verification task prevents the removed store, codec, handler and runtime
subsystem from returning.

### Why deletion is now authorized

ADR-039 deliberately blocked deletion until the replacement had served as a proven
equivalent writer. That gate is now met:

- the Phase 7a preprod oracle compared every block dataset through block 5,081,500
  and found zero differences;
- Phase 7b proved all five epoch-artifact representations and recovery paths;
- the projection-only preprod run reached tip with zero drain failures and passed
  graceful and kill-9 recovery; and
- ADR-044/045 subsequently passed fresh, restart, prospective-enrollment and
  crash-recovery validation on current projection archives.

The remaining work is therefore deletion and narrowing, not another writer cutover.

## Decision

Remove the residual replay-worker implementation and narrow the archive read facade
to the capabilities the projection architecture actually uses.

The cleanup must preserve all current projection row semantics, archive identity,
physical data tables, epoch evidence, coverage outcomes and public history query
behavior. It must not introduce a replacement hot store, worker or backend write
contract.

### 1. Extract the two shared pieces before deleting their legacy owners

Two legacy dataset classes cannot be deleted as-is because the projection reuses
small parts of them.

#### Address parsing

Extract the static address decomposition from `AddressTransactionDataset` into a
small projection-neutral component, for example `ArchiveAddressParser`. It owns:

- Shelley and Byron address decoding;
- canonical address key and payment credential derivation;
- pre-Conway pointer-coordinate lookup;
- the Conway rule that pointer stake references are no longer effective; and
- immutable address-parts, pointer-coordinate and resolved-credential value types.

The current projection supplies pointer credentials from
`PointerCredentialSource`; the extracted parser must not depend on a hot store,
archive track or sequential replay cursor. `ProjectionAddressParticipation` moves to
the extracted types.

After extraction, remove `AddressTransactionDataset`. Current address-transaction
row materialization continues through `ProjectionAddressParticipation`,
`AddressSubjectRows` and `ProjectionRowBuilder`.

#### UTXO row materialization

Extract the stateless row emission currently performed by
`new UtxoHistoryDataset().derive(...)` into a component such as
`UtxoHistoryRows`. It accepts the existing immutable `UtxoHistoryFact` and row
context and emits exactly the same archive rows.

The extracted component has no hot-store constructor, progress track, commit hook,
pointer mutation or rollback behavior. Pointer resolution required during canonical
capture remains in the existing projection/account-state path; sink-time row
materialization consumes the already normalized fact.

After extraction, remove `UtxoHistoryDataset`. Retain `UtxoHistoryProjection` while
the canonical decoder uses it to select the shipped UTXO table set.

### 2. Remove the replay configuration model

Delete these `archive-core` types:

- `ArchiveConfiguration`;
- `ArchivePathValidator`;
- `ArchiveStartMode`;
- `ArchiveWorkerConfig`; and
- `DatasetArchiveConfig`.

Retain `ArchiveEngine`; projection-backed reads still use it to select the DuckLake
provider. Retain projection-specific bounds and maintenance configuration.

### 3. Remove the hot-history and progress model

Delete the complete `archive-core/hot` production package:

- `HotArchiveRows`;
- `HotBlockCheckpoint`;
- `HotBlockUpdate`;
- `HotHistoryMutation`;
- `HotHistoryOperation`;
- `HotHistorySnapshot`;
- `HotHistoryStore`;
- `HotHistoryStoreProvider`;
- `RocksDbHotHistorySnapshot`; and
- `RocksDbHotHistoryStore`.

Delete the replay progress types:

- `ArchiveProgress`;
- `ArchiveProgressStore`; and
- `ArchiveTrack`.

Delete the stateful replay dataset interfaces:

- `StatefulBlockArchiveDataset`; and
- `LiveStatefulBlockArchiveDataset`.

Once their callers are gone, delete the replay-only address state types:

- `Outpoint` from `archive-core/address`;
- `ResolvedOutput`;
- `SequentialOutpointResolver`; and
- `SequentialPointerResolver`.

This does not affect the unrelated current UTXO `Outpoint` type in `core-api`.

### 4. Reduce `HistoryArchiveService` to a projection read facade

Keep `HistoryArchiveService` for now because REST resources use it to open and query
the archive written by `ProjectionHistoryService`. Remove its replay-worker state and
semantics.

As the first cleanup action in this class, replace its wildcard imports (especially
the archive API, address, hot-store and worker packages) with explicit imports. This
lets compiler errors identify the exact remaining dependencies as legacy types are
deleted, instead of allowing wildcard imports to hide the dependency boundary until
a later deletion fails.

Retained responsibilities are:

- validate removed configuration;
- open the projection archive in read mode;
- expose selected datasets and the committed projection coordinate;
- enforce ADR-045 epoch coverage before epoch-history reads;
- provide a lifecycle/query lease;
- perform repository reads and transaction lookup;
- expose projection-backed read health/status; and
- close the read backend safely.

Remove:

- hot-store engine, worker executor and projection-parallelism fields;
- decoded-block counters and stage-error maps owned by the old worker;
- catch-up dataset, resolver seed and promotion state;
- per-dataset applied retention and promotion batches;
- pending worker rollback and maintenance fields;
- `handoffBaseline`;
- `shouldReanchorLive`;
- `LiveRollbackAction` and `liveRollbackAction`;
- `genesisOutpoints`;
- `legacyGenesisBaseCanBeNormalized`;
- `hotRecords` and `liveCoverage`;
- the permanently false `datasetBuilding` and `datasetFailed` states; and
- all replay-only imports, comments and tests.

REST resources must report current projection states: not selected, not yet
committed, incomplete epoch coverage, unhealthy sink/read backend, or available.
They must not report obsolete worker states such as parked, catch-up track or live
promotion.

`GET /history/watermark` uses the projection consistency point only. The fallback to
legacy `archive_coverage` is removed. If projection history is disabled or has no
eligible committed point, the endpoint returns its documented unavailable or
incomplete response rather than consulting legacy metadata.

The class name may be reconsidered with a REST API refactor, but renaming it is not
required by this decision and must not delay deletion.

### 5. Make the backend used by REST reads read-only

`ArchiveBackend` is retained as the backend-neutral read facade, but its unused
replay-writer surface is removed:

- `begin(ArchiveJob)`;
- `findReceipt(UUID)`;
- dataset/range invalidation;
- epoch-job invalidation;
- replay-worker retention mutation; and
- replay-worker maintenance/writer diagnostics that are not used by current reads.

Keep identity, capabilities needed by reads, generation-pinned read sessions,
repositories, coverage derived from projection receipts, committed-boundary lookup,
transaction lookup, health and close.

Delete the resulting unused types and implementation:

- `ArchiveMaintenanceBudget`;
- `ArchiveWriteSession`;
- `ArchiveRetentionCutoff`;
- `DuckLakeWriteSession`; and
- `ArchiveReceipt`, after all other write-session and dataset references have been
  removed.

`ArchiveReceipt` is deliberately sequenced last within this step because it is the
most widely referenced type in the legacy backend cluster. Deleting it earlier would
create noisy intermediate compiler failures without proving an additional invariant.

`ArchiveJob` is not removed in this ADR. Current shared row factories use it as a
deterministic row context, including `archive_job_id`. Renaming or replacing that
value type would change more surface without improving the one-writer invariant.

`DuckLakeHistoryArchiveBackend` and `DuckLakeArchiveBackendProvider` remain because
they serve current projection-backed queries. Their replay job commit, receipt,
invalidation and retention code is removed. Projection writes, rollback,
maintenance, receipt verification and acknowledgements remain exclusively in
`DuckLakeProjectionSink` through `ProjectionSink`.

The shared DuckDB capacity gate and diagnostics remain where used by the current
projection sink and read manager; this ADR removes only replay-writer ownership of
them.

### 6. Remove legacy physical-schema creation without destructive migration

Fresh archives must no longer create replay-only metadata such as:

- `archive_coverage`;
- `archive_commits`; and
- `archive_commit_counts`.

Projection data tables, `projection_receipts`, artifact coverage/gap tables and
identity/enrollment metadata are retained unchanged.

Do not automatically drop legacy metadata tables from an existing preview archive.
They are not authoritative after cutover, and leaving inert tables is safer than a
startup-time destructive migration. Current reads ignore them. A separately
confirmed offline cleanup may be added later if reclaiming their small metadata
footprint is worthwhile.

### 7. Treat removed configuration consistently

Remove `YanoPropertyKeys.History.ENABLED` from the supported public configuration
surface. Keep the string `yano.history.enabled` only inside a focused removed-config
validator, alongside the other rejected replay-worker keys.

Because all packaged profiles have removed the property, any explicit occurrence of
`yano.history.enabled`—including `false`—is rejected with a migration message naming
`yano.history.projection.enabled`. Presence must not look like a meaningful switch.

Existing deployment files may still contain this formerly harmless property. The
release notes must therefore instruct operators to remove it before upgrading, not
merely list the deleted Java API. A refusal must also be operationally visible:
`HistoryArchiveService` records a removed-configuration/read-facade initialization
failure, and readiness reports `DOWN` when either that failure or
`ProjectionHistoryService` initialization failure is present. Liveness may remain
`UP` so diagnostics stay reachable. A rejected configuration must never leave a
process reporting ready while synchronization has not started.

Consolidate the duplicate `yano.account-history.enabled` checks so startup has one
authoritative removed-history validator. Continue rejecting stale replay-worker
prefixes rather than silently ignoring them:

- `yano.history.worker.*`;
- `yano.history.hot-store.*`;
- `yano.history.start-mode`;
- `yano.history.datasets.*`;
- removed replay maintenance keys; and
- the removed SQLite archive configuration.

The validator is compatibility/safety code, not a retained writer.

### 8. Retain current epoch-artifact staging

Do not remove or weaken:

- `EpochArchiveStagingService`;
- `DurableEpochFileSource`;
- `EpochArchiveSource`;
- `EpochArchiveJob`;
- `EpochSourcePage`;
- `EpochFactCodec` and shipped codecs;
- `ArchiveSourceLease`; or
- the ADR-044/045 enrollment, gap and capture-state machinery.

These are current projection sources for irreproducible epoch artifacts. Their word
“archive” does not make them legacy.

Also retain canonical block decoders and normalized fact/row builders used by
projection contributors, including Byron normalization.

## Compatibility and data migration

This is a public Java source-breaking cleanup in `archive-api` and `archive-core`.
Yano is in preview; the removed APIs describe an architecture that cannot be enabled
by the current runtime. The release notes will list the removed types and direct
integrators to `ProjectionSink` and the read-only archive repositories.

There are two additional operator-visible compatibility changes:

- deployments must remove every explicit `yano.history.enabled` setting before
  upgrading and use `yano.history.projection.enabled` when projection history is
  intended; and
- `GET /history/watermark` no longer falls back to legacy `archive_coverage`.
  Without an eligible projection consistency point it returns the documented
  unavailable or incomplete response.

No current projection archive requires a rebuild. The cleanup does not change:

- projection identity or artifact identity wire forms;
- section, fact or row codecs;
- `archive_job_id` derivation;
- projection receipt identity or acknowledgement;
- current physical data-table schemas; or
- ADR-044/045 coverage and enrollment state.

An archive created by the current projection must open after upgrade and return the
same rows and coverage. Legacy replay metadata may remain physically present but is
ignored.

Keep `LegacyAccountHistoryCleanup` and its Gradle task for the existing documented
preview upgrade path. Removing that offline tool is a separate decision after the
two synchronous-history column-family names no longer need to be recognized.

## Implementation sequence

Implement in reviewable, buildable steps. Two PRs are acceptable, but no intermediate
commit may restore or create a second archive writer.

1. Pin current address decomposition and UTXO row output with golden/differential
   tests, including Byron, Shelley base/enterprise/reward addresses, pre-Conway
   pointer addresses, post-Conway pointer behavior, invalid transactions, collateral
   inputs/returns, assets, datums and redeemers. Before deleting the sequential
   resolver oracle, use its fixture matrix to identify the pointer lifecycle
   invariants that must survive. Preserve resolved, unresolved and Conway row output
   as inline expected strings; preserve registration/deregistration/re-registration,
   as-of replay, determinism and idempotence in focused tests of the current
   projection overlay and persistent pointer index.
2. Extract `ArchiveAddressParser` and `UtxoHistoryRows`; switch the projection to
   them and prove byte-for-byte/row-for-row parity before deleting legacy owners.
3. Remove `AddressTransactionDataset`, `UtxoHistoryDataset`, the stateful dataset
   interfaces, the hot package, progress package, replay address resolvers and replay
   configuration model. Deliberately delete `PointerResolutionDifferentialTest` and
   `AddressTransactionProjectionParityTest`, plus the other legacy-owner unit and
   test-fixture suites, only after their expected outputs are represented by the
   extraction golden tests.
4. Expand `HistoryArchiveService` wildcard imports, then simplify it and REST
   unavailable-state handling. Remove the watermark fallback and legacy helper tests
   while retaining projection read and coverage tests.
5. Narrow `ArchiveBackend`, delete the old write-session implementation, then delete
   `ArchiveReceipt` last within the legacy backend cluster, and
   replace write-oriented backend conformance tests with read/repository,
   generation-pinning, coverage and identity conformance tests.
6. Stop initializing replay-only metadata tables on a fresh archive. Verify an
   existing projection archive containing those tables still opens without mutation.
7. Consolidate removed-configuration validation, record refusal in
   `HistoryArchiveService` initialization health, make readiness fail for both read
   facade and projection initialization failures, remove the supported `ENABLED`
   constant, update packaged documentation and add a repository guard for the final
   forbidden type set.
8. Run the complete verification matrix and record the deletion inventory and live
   smoke evidence in an implementation report.

## Verification and acceptance criteria

The cleanup is complete only when all of the following hold.

### Static and build checks

- Production source contains none of the types explicitly deleted in §§2–5.
- No production code constructs a hot-history store or calls a replay backend write
  session.
- `ProjectionOutboxConsumer -> ProjectionSink` is the only ongoing archive write
  path; genesis bootstrap remains the only one-time fresh-archive write exception.
- `yano.history.enabled` appears only in removed-configuration validation, tests and
  migration documentation.
- A repository guard prevents replay worker, hot store, progress/start-mode and old
  backend write types from returning.
- `git diff --check`, compilation and a clean `:app:quarkusBuild` pass.

### Required automated suites

- `:archive-modules:archive-api:test`
- `:archive-modules:archive-core:test`
- `:archive-modules:archive-store-ducklake:test`
- `:ledger-state:test`
- `:runtime:test`
- `:app:test`
- the legacy synchronous-history removal verification task

The new suites must cover:

- exact address-parser and UTXO-row parity across the fixture matrix;
- inline expected rows for resolved, unresolved and Conway pointer outcomes, plus
  persistent and same-block pointer lifecycle, as-of replay, determinism and
  idempotence;
- selected/unselected block and epoch datasets;
- zero-row epoch evidence;
- complete, not-projected and gap coverage responses;
- generation-pinned reads and transaction lookup;
- projection sink commit, rollback, maintenance and restart;
- opening an existing current projection archive without identity or schema drift;
- fresh DuckLake initialization without replay-only metadata tables;
- rejection of every removed configuration key, including
  `yano.history.enabled=false`, with `/q/health/ready` reporting `DOWN` and liveness
  remaining available for diagnostics; and
- `/history/watermark` returning unavailable or incomplete without a projection
  consistency point, never consulting legacy coverage metadata.

### Live smoke checks

- Start a fresh preprod projection archive, observe forward block and epoch-artifact
  commits, query address/account/reward history and `/history/coverage`, restart
  gracefully, then recover after kill-9 with no receipt or coverage regression.
- Open a retained archive produced before this cleanup and verify the same selected
  datasets, enrollment epochs, coverage outcomes, committed coordinate and sampled
  rows.
- Run the block-producer devnet smoke test to prove profile cleanup and history reads
  do not affect epoch processing or production.

A new full-chain parity run is not required: this ADR does not introduce a new row
producer, and the accepted ADR-039 oracle is the authorization for deletion. Any
parity failure in the extraction fixtures nevertheless blocks the cleanup.

## Consequences

Yano has one archive vocabulary and one writer. Projection code no longer depends on
replay tracks or a hidden hot RocksDB, the read backend cannot accidentally be used
as an alternate writer, and source searches accurately describe runtime behavior.

The deletion reduces production and test surface substantially but requires careful
extraction of two shared algorithms. The public archive Java API becomes smaller and
source-breaking for preview integrators. Existing current projection archives remain
readable without rebuild or automatic destructive migration.

## Risks and mitigations

### Address or UTXO rows drift during extraction

Mitigated by switching to extracted components before deletion and comparing exact
rows over the existing Byron/Shelley/pointer/collateral fixtures. No simultaneous
semantic cleanup is allowed.

### A class that looks legacy is a current artifact source

Mitigated by the explicit §8 retention list and a caller proof over production
sources. Static absence of callers is not sufficient when a planned feature is
unwired; here all shipped ADR-044 artifacts are already installed and tested before
the deletion closure is calculated.

### Existing archives contain old metadata tables

Mitigated by ignoring, not automatically dropping, those tables and by an upgrade
test over a retained archive. Their presence does not grant coverage authority.

### REST behavior loses useful failure information

Mitigated by mapping responses to the current projection state and ADR-045 coverage
outcomes. Obsolete “worker parked/building” states are removed only because no
current component can enter them.

## Alternatives considered

### Leave the classes compiled but unreachable

Rejected. Dead storage and rollback abstractions remain attractive nuisance APIs,
inflate the distribution and tests, and make the one-writer invariant difficult to
audit.

### Delete the complete archive read service and backend

Rejected. REST queries still require the repositories and generation-pinned DuckLake
read facade. This ADR narrows them rather than rebuilding query infrastructure.

### Delete epoch staging because it originated in the replay architecture

Rejected. It is the current durable source for reward, DRep distribution and
governance proposal-status artifacts. Removing it would lose irreproducible evidence.

### Keep the generic backend write API for possible future sinks

Rejected. Future sinks implement `ProjectionSink`, whose receipt, rollback,
maintenance and identity semantics match the current outbox. Preserving a second
unused write contract invites incompatible writers.

### Automatically drop every legacy table and column family

Rejected. Automatic destructive startup migration provides little value and creates
unnecessary recovery risk. The existing synchronous-history cleanup remains explicit;
inert replay metadata tables may be handled by a separately confirmed offline tool.
