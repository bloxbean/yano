# ADR-047 implementation report

Date: 2026-08-27

Branch: `cleanup/adr-047-legacy-history`

Base: `feat/selective-epoch-artifacts`

## Result

ADR-047 is implemented. The replay-worker archive, hot-history RocksDB store,
sequential replay resolvers, progress/configuration model and generic backend write
session have been removed. Projection capture remains the only ongoing archive write
path, while `HistoryArchiveService` and `ArchiveBackend` are narrowed to current
projection-backed reads.

The implementation removes more than 8,000 lines of tracked legacy code and tests.
The final exact count may vary slightly with documentation and extracted golden-test
additions, but the deletion inventory below is enforced by repository guards.

## Preserved row semantics

- Extracted address decomposition into `ArchiveAddressParser`.
- Extracted normalized UTXO row materialization into `UtxoHistoryRows`.
- Migrated projection contributors and decoders to the extracted components before
  deleting their stateful replay owners.
- Pinned resolved, unresolved and Conway pointer row outcomes as inline expected
  strings, and retained the historically fragile registration/deregistration/
  re-registration semantics in focused projection and persistent-index tests.
- Covered Byron, Shelley base/enterprise/reward, pre-Conway and Conway pointer
  behavior, malformed addresses, assets, collateral, inline datum, reference script,
  datum and redeemer rows.

## Deleted production surface

- Replay configuration: `ArchiveConfiguration`, `ArchivePathValidator`,
  `ArchiveStartMode`, `ArchiveWorkerConfig`, `DatasetArchiveConfig`.
- The complete `archive-core/hot` package.
- Replay progress: `ArchiveProgress`, `ArchiveProgressStore`, `ArchiveTrack`.
- Stateful replay datasets and owners: `AddressTransactionDataset`,
  `UtxoHistoryDataset`, `StatefulBlockArchiveDataset`,
  `LiveStatefulBlockArchiveDataset`.
- Replay address state/resolution: archive-core `Outpoint`, `ResolvedOutput`,
  `SequentialOutpointResolver`, `SequentialPointerResolver`.
- Generic backend write API: `ArchiveWriteSession`, `ArchiveRetentionCutoff`,
  `ArchiveMaintenanceBudget`, `ArchiveReceipt`, and `DuckLakeWriteSession`.
- Replay-only backend conformance, benchmark, hot-store and differential-oracle test
  fixtures after their relevant pointer lifecycle and row-output behavior was moved
  to focused tests of the current architecture.

Current epoch staging (`EpochArchiveStagingService`, staged epoch sources and ADR-044/
045 enrollment/coverage state), projection sinks, projection receipts, block
decoders and normalized row builders remain intact.

## Runtime and compatibility behavior

- `HistoryArchiveService` is a read-only projection facade.
- `ArchiveBackend` exposes only identity, generation-pinned reads, repositories,
  projection-derived coverage, committed boundaries, transaction lookup and health.
- Fresh DuckLake archives do not create replay-only `archive_coverage`,
  `archive_commits` or `archive_commit_counts` tables.
- Existing copies of those tables are ignored and left untouched.
- Transaction locator misses/stale hints fall back to the authoritative pinned
  transaction table.
- `/history/watermark` uses only the projection consistency point.
- Explicit removed configuration, including `yano.history.enabled=false`, fails
  initialization with a migration diagnostic. Readiness reports `DOWN`; liveness
  remains available.
- `YanoPropertyKeys.History.ENABLED` is removed.
- Upgrade guidance is recorded in `docs/UPGRADING.md`.

## Automated verification

The complete ADR matrix passed:

```text
:archive-modules:archive-api:test
:archive-modules:archive-core:test
:archive-modules:archive-store-ducklake:test
:ledger-state:test
:runtime:test
:app:test
verifyLegacyAccountHistoryRemoved
verifyLegacyHistoryReplayRemoved
:app:quarkusBuild
```

The independently repeated full matrix contained 2,670 tests with 0 failures and
0 errors. This follow-up added nine pointer-lifecycle regression cases, bringing the
suite inventory to 2,679; the affected `ledger-state` and `archive-core` suites and
both removal guards passed. The earlier follow-up `:app:test :app:quarkusBuild`
after final import/comment cleanup also passed.
`git diff --check` passed. The repository guard is wired into `check` and rejects
the deleted replay/hot/write types if they return.

The distributable JVM ZIP was rebuilt with `:app:yanoDistZip`. Its embedded
`yano.jar` SHA-256 exactly matched `app/build/yano.jar`:

```text
0934406aab5dc70e618c37bc672a58d0b5614e996d46225e4c5b262bafb2a1bd
```

## Live preprod acceptance

Deployment:
`/Volumes/data2/yano-try/adr047-final-preprod/yano-0.1.0-pre14`

The current rebuilt ZIP was extracted into an empty directory and started with the
`preprod,projection` profiles on HTTP port 7086 with the N2N server disabled.

- Fresh archive initialized with all four block projections and all five epoch
  artifacts; projection health was `READY`, genesis was captured and drain failures
  remained zero.
- Before crash injection, the node reached block 45,794. All five artifact datasets
  had durable complete ranges and zero gaps (`ada-pot`, `drep-distribution`,
  `governance-proposal-status`, and `reward` through epoch 5; `epoch-stake` through
  epoch 4).
- The process was killed with `SIGKILL` and restarted from the same directory.
- Startup reconciled UTXO/account state and logged nonce restoration at body tip
  block 76,739, slot 1,619,947.
- After recovery, readiness was `UP`, history remained available, projection health
  was `READY`, drain failures remained zero, and complete ranges advanced with zero
  gaps: the four output-epoch datasets through epoch 7 and `epoch-stake` through
  epoch 6.
- Address transaction and account withdrawal history queries returned HTTP 200.
  The reward query returned the intended ADR-045 HTTP 409 incomplete-history result
  naming the current `PENDING` epoch; it did not return a stale or fabricated answer.
- The recovered node advanced beyond block 87,000 and was then stopped cleanly.

A retained pre-cleanup projection archive was also opened by this implementation
during validation. Identity, selected datasets and coverage were preserved; history
queries and readiness remained healthy. An explicit legacy configuration refusal
reported readiness `DOWN` as designed.

## Live block-producer devnet acceptance

Deployment:
`/Volumes/data2/yano-try/adr047-final-devnet/yano-0.1.0-pre14`

The same rebuilt ZIP was extracted into an empty directory and started with the
`devnet` profile on HTTP port 7087 and N2N port 13349.

- Readiness was `UP`.
- The block producer created 261 blocks before shutdown.
- Projection health was `READY`, projection genesis captured 25 rows totaling
  9,010,500,000,000,000 lovelace, all epoch-artifact capture states were active,
  and drain failures remained zero.
- The node and N2N server stopped cleanly.

The smoke run also reconfirmed a pre-existing devnet restart identity issue outside
ADR-047: devnet shifts `systemStart` after projection identity is initially derived,
so reopening that archive can compare two different genesis-file hashes. The same
ordering exists on the base branch and was observed with a pre-cleanup archive; this
cleanup does not change identity derivation or producer startup ordering. It should
be handled separately rather than expanding this deletion ADR into a network
identity migration.

## Acceptance

All ADR-047 implementation, automated verification and required live smoke gates are
complete. No full-chain parity rerun was required because the retained row semantics
are pinned by extraction fixtures and the accepted ADR-039 parity oracle authorized
the deletion.
