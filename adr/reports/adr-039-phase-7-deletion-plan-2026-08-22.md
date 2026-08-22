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

## Scope correction (2026-08-22): Phase 7 splits into 7a and 7b

An earlier draft of this plan listed `DurableEpochFileSource`, `EpochArchiveStagingService`
and the epoch codec/manifest/lease machinery as removable because the projection has
no caller for them. **That inference was wrong.** Phase 5 shipped only `EPOCH_STAKE`
and `ADA_POT`; `REWARD`, `DREP_DISTRIBUTION` and `GOVERNANCE_PROPOSAL_STATUS` are
explicitly deferred. The absence of a projection caller therefore reflects
**incomplete artifact wiring, not architectural obsolescence** - deleting that
foundation would remove the only working path for three datasets before their
replacement exists.

Static absence of callers is a necessary condition for deletion, never a sufficient
one. Where a required Phase 5 path is still unwired, "no caller" means "not yet
migrated".

| | scope | gate |
|---|---|---|
| **7a** | the block replay pipeline only: block replay workers and sources, per-dataset catch-up/live cursors, hot-history promotion, replay-only prefetch and caches, legacy block orchestration and configuration | block-dataset differential parity |
| **7b** | the epoch staging foundation | every epoch artifact migrated to a proven projection representation, **or** explicitly retired by product decision |

`HistoryArchiveService` spans both. It cannot be deleted in 7a: it carries epoch
staging initialisation and lifecycle wiring that the remaining artifacts still need.
Any such wiring must be **extracted into the projection service first**, or removing
the service silently disables the epoch artifacts that still depend on it — a failure
with exactly the shape of the genesis omission, and just as invisible.

### Remaining Phase 5 artifact decisions, required before 7b

- **REWARD** — prove and retain the complete deterministic calculation closure, or
  capture it as non-reconstructible durable evidence.
- **DREP_DISTRIBUTION** — whole-dataset strictest representation, as already decided.
  Capture the amount together with the boundary-time expiry, dormancy and active
  fields as durable evidence, unless a simpler complete immutable source is proven.
- **GOVERNANCE_PROPOSAL_STATUS** — capture the boundary observation and decision
  semantics as durable evidence. Later mutable governance state is **not** an
  equivalent source: it records the outcome, not the observation that produced it.

Where the staged-artifact mechanism is the simplest correct representation it should
be reused and hardened, not replaced. Required irreproducible evidence must be
power-loss durable, checksummed, restartable, lease-safe and fail-closed; the current
flush-only, fail-open behaviour is not acceptable for it.

## The 7a deletion set, once block parity is authorised

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

## Caller proofs (2026-08-22)

Required before any deletion: prove the type has no non-legacy caller. Static analysis
over main sources, excluding the legacy set itself:

**No non-legacy caller — safe to delete once the gate opens**
`BlockArchiveWorker`, `EpochArchiveWorker`, `ArchiveProgressStore`, `CoreSyncView`,
`CycleScopedCoverageCache`, `RocksDbHotHistoryStore`, `ChainBlockArchiveSource`,
`OrderedPrefetchingBlockArchiveSource`, `DurableEpochFileSource`.

**Has non-legacy callers — must NOT be deleted as-is**

| type | reached from | disposition |
|---|---|---|
| `ArchiveTrack` | `AddressTransactionDataset`, `UtxoHistoryDataset` | **retain** — those datasets are shared; the projection builds its rows through `UtxoHistoryDataset` |
| `HotHistoryStore` | same two datasets, plus the SQLite store | **retain** or narrow; not a pure legacy type |
| `HotArchiveRows` | `ArchiveAccountHistoryProvider` | the provider is retained for projection reads, so its hot-rows usage must be removed *before* this can go |
| `CycleCachingBlockArchiveSource` | only `OrderedPrefetchingBlockArchiveSource` | transitively safe — becomes unreferenced once that is deleted |

Two lessons for the deletion commit. First, the safe set is not a flat list: it has to
be computed as a **closure**, re-running the proof after each removal, because types
like `CycleCachingBlockArchiveSource` only become unreferenced once their sole caller
goes. Second, the hot-history package is **not** cleanly legacy — `ArchiveTrack` and
`HotHistoryStore` are reached from `UtxoHistoryDataset`, which the projection itself
depends on. Deleting the package wholesale, as the original plan implied, would break
the projection's own row derivation.
