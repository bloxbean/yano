# ADR-039 Phase 7 — deletion plan and gate status (2026-08-22)

## Gate status: NOT MET. Deletion is not executed.

The ADR's condition is explicit:

> This phase cannot start after parity over only the six transaction/UTXO tables.
> Every currently shipped block dataset — `TRANSACTION`, `UTXO_HISTORY`,
> `ACCOUNT_EVENT`, and `ADDRESS_TRANSACTION` — must have accepted equivalent section
> coverage, including Byron semantics [...] Deletion occurs in a separate commit
> after the old path has served as the differential oracle.

Per-dataset evidence as it actually stands:

| dataset | evidence on record | oracle-grade? |
|---|---|---|
| `TRANSACTION` | Phase 3 differential parity met | **yes** |
| `UTXO_HISTORY` | Phase 3 differential parity met | **yes** |
| `ACCOUNT_EVENT` | seven certificate fixtures (Phase 4b) | no — fixtures, not a differential run |
| `ADDRESS_TRANSACTION` | one real spend verified side by side (Phase 4b) | no — spot check, not a differential run |

The Phase 5–7 validation sync ran with `history.enabled: false`, so the legacy path
was **off** and served as an oracle for nothing in this run. It produced strong
evidence about the projection — completeness, recovery, throughput, artifact parity
against ledger state — but it is structurally incapable of producing differential
parity evidence, because there was no second producer to differ from.

Deleting the legacy pipeline is irreversible and it is one shared machinery
(`BlockArchiveWorker` and its sources serve all four datasets), so partial
authorisation does not permit partial deletion.

## What would authorise it

A differential oracle run over a full preprod sync with **both** producers active,
diffing all four datasets row for row, including Byron blocks. Two shapes work:

1. **Two archives, one node.** Run the legacy worker into a separate history
   directory from the projection sink, then diff dataset by dataset. Requires the
   legacy path to target a non-primary archive, which the current one-primary-sink
   rule permits only if the legacy archive is explicitly not primary.
2. **Two sequential syncs, same chainstate snapshot.** Sync once with the legacy path
   only, snapshot the archive, resync with the projection only, and diff. Simpler to
   reason about and needs no concurrency rule change; costs two full syncs.

Either way the acceptance criterion is a row-level diff of `transactions`,
`transaction_inputs`/`outputs`, `utxo_history`, `account_events` and
`address_transactions` over the whole chain, with Byron included, and an explicit
sign-off recorded — the ADR says "accepted", not "measured".

## The deletion set, once authorised

Enumerated now so the authorised commit is mechanical rather than exploratory.

**`archive-core` — block replay workers and sources**
- `core/worker/`: `BlockArchiveWorker`, `EpochArchiveWorker`, `ArchiveProgress*`,
  `ArchiveTrack`, `ArchiveSubsystem`, `ArchiveCoverageView`, `CoreSyncView`,
  `CycleScopedCoverageCache`, `ArchiveWorkerMetrics`, `ArchiveWorkerStatus`,
  `BackfillActivationInvalidatedException`
- `core/source/`: `ChainBlockArchiveSource`, `OrderedPrefetchingBlockArchiveSource`,
  `CycleCachingBlockArchiveSource`, `MappingBlockArchiveSource`, `BoundedDecodeCache`,
  `ArchiveSourceLease`, `DurableEpochFileSource`, `EpochArchiveJob`,
  `EpochSourcePage`, `EpochArchiveSource`, `EpochFactCodec`
- `core/hot/`: the whole package — hot-history promotion is superseded by the outbox

**Retained from those packages**: the decoders the projection contributors also use
(`CanonicalBlockDecoder`, `YaciBlockDecoder`, `YaciBlockArchiveDecoder`,
`YaciUtxoHistoryDecoder`, `ByronBlockNormalizer`) and the shared row builders
(`StandardEpochDatasets`, `StandardEpochFactCodecs`) — the projection's artifact rows
are built through them, and that sharing is what keeps both paths byte-identical.

**`app`**
- `HistoryArchiveService`, `EpochArchiveStagingService`, `ActivationStore`,
  `LegacyAccountHistoryCleanup`, `NoArtifactReader`
- `HistoryResource.watermark`'s legacy branch, once nothing can reach it

**`archive-store-ducklake`**
- `archive_coverage`, `archive_commits`, `archive_commit_counts` tables and the
  queries over them, replaced by `projection_receipts`
- `DuckLakeTransactionLocator`, **only** once a derived tx-hash index exists for
  projection archives — deleting it before then makes today's documented full-scan
  fallback the permanent behaviour rather than a temporary one

**Configuration**: `yano.history.enabled` and the legacy worker tuning keys beneath
it, after confirming no deployment still sets them.

## Ordering

1. Cut every read path over (Phase 6 — done for coverage and watermark; the row
   queries already read the same physical tables, so they need no change).
2. Run the differential oracle above and record acceptance.
3. Delete, in one separate commit, in the order: app services → workers/sources →
   hot package → coverage tables → configuration.
4. Re-run the full suite and `:app:quarkusBuild`, then verify the console-ui History
   tab against the projection APIs.
