# ADR-045: Preserve Epoch Artifact Gaps and Resume Future Capture

## Status

Accepted and implemented on ADR-044's enrollment/`NOT_PROJECTED` foundation

## Date

2026-08-27

## Related decisions

- [ADR-039](in-progress/039-canonical-projection-outbox.md) — canonical
  projection outbox, epoch artifact evidence and fail-closed archive semantics.
- [ADR-043](043-shelley-only-epoch-artifact-staging.md) — only Shelley+ source
  epochs may create staged-file evidence.
- [ADR-044](044-selective-epoch-artifact-projection.md) — explicit selection and
  durable identity for epoch artifacts.
- [ADR-046](046-archive-aware-epoch-artifact-apis.md) — public artifact reads
  that consume the coverage and gap outcomes defined here.

## Context

`REWARD`, `DREP_DISTRIBUTION` and `GOVERNANCE_PROPOSAL_STATUS` are captured as
staged-file evidence at an epoch boundary. If staging fails today,
`EpochArchiveStagingService` writes one persistent `epoch-source/FAILED` marker.
That marker disables every staged dataset at every later boundary and survives
restart.

This is safe: an epoch whose irreproducible evidence was never captured must not
be presented as an empty epoch. It is also coarse. One reward capture failure at
epoch 450 can prevent valid reward evidence for epochs 451 through 500, and can
also stop DRep and governance capture even when their writers remain healthy.

The current policy conflates three questions:

1. **Repairability:** can the missing artifact for epoch 450 be recreated from
   retained state?
2. **Coverage:** which epochs can the archive answer honestly?
3. **Continuation:** can the same or another dataset capture valid artifacts at
   later boundaries?

An irreproducible gap answers the first question with “no.” It does not imply
that future boundary results are invalid.

ADR-044 also permits an artifact to join a populated archive at a future
boundary. That creates a second kind of intentionally unavailable history:
epochs before the dataset's `projectedFromEpoch` were never selected. They are
`NOT_PROJECTED`, not gaps. The distinction is load-bearing:

- `NOT_SELECTED` is the dataset-level state for an artifact that has never been
  enrolled in this archive;
- `NOT_PROJECTED` is expected configuration history and needs no repair;
- `GAP` means capture was required but evidence was lost and needs operator
  attention; and
- both differ from a valid complete epoch whose artifact contains zero rows.

### Dataset semantics after a gap

Epoch artifacts do not all have the same query semantics:

- `EPOCH_STAKE`, `ADA_POT` and `DREP_DISTRIBUTION` are epoch-local snapshots. A
  missing epoch makes that epoch unavailable but does not invalidate a complete
  later snapshot.
- `GOVERNANCE_PROPOSAL_STATUS` is an epoch-local boundary observation. A later
  observation remains valid, but it does not restore a terminal decision missed
  in the gap.
- `REWARD` is an event history. Later reward rows remain individually valid, but
  an all-history list or total crossing a missing epoch is incomplete for every
  address, including an address for which the archive returns no row in the
  missing epoch: absence cannot be distinguished from lost evidence.

Therefore continuing capture is safe only if gaps become durable, query-visible
coverage facts. Continuing without that representation would trade an explicit
failure for silent false absence.

### Failure stage matters

Not every “projection failure” loses an artifact:

1. **Capture failure before durable evidence.** The boundary result may be lost;
   this creates a gap unless the authoritative boundary itself is retried before
   advancing.
2. **Failure after staged evidence and its outbox reference are durable.** No gap
   exists. The sink can retry from the staged source, which is retained until a
   verified receipt acknowledges it.
3. **Reconstructible artifact sink failure.** Epoch stake remains protected by
   its snapshot clamp and AdaPot remains stored by epoch. The existing retry path
   applies while the source is retained.

Only the first class requires gap semantics.

## Constraints

- A gap must never be inferred from the absence of rows. Empty output can be a
  legitimate result.
- Epochs before ADR-044's durable `projectedFromEpoch` must be reported as
  `NOT_PROJECTED`, never expanded into one gap record per epoch.
- Later capture must not erase, hide or implicitly repair an earlier gap.
- A query crossing a gap must not return ordinary success with partial rows.
- A dataset-specific failure must not disable unrelated healthy datasets.
- A shared infrastructure failure may pause multiple datasets when their
  durability cannot be trusted.
- Core ledger transitions must remain authoritative. Archive recovery must not
  roll back or mutate ledger state merely to improve history availability.
- Gap and pause state must survive crash/restart and participate in exact-point
  rollback.
- Resume means “capture future eligible boundaries.” It does not claim to
  reconstruct a missed boundary.
- Existing global `FAILED` markers must not be silently cleared or interpreted
  as a precisely known gap when they lack sufficient dataset/epoch provenance.
- Coverage summaries and capture health must be available through
  `/history/coverage` and low-cardinality metrics; log messages alone are not an
  operator contract.
- The design must work on every network through semantic epoch and canonical
  boundary coordinates; no network-specific epochs are introduced.

## Decision

### 1. Make artifact outcome explicit for every selected dataset and epoch

Introduce a durable epoch artifact outcome model:

```java
enum EpochArtifactOutcome {
    PENDING,
    COMPLETE,
    GAP
}

record EpochArtifactGap(
        ArchiveDatasetId dataset,
        int semanticEpoch,
        long boundaryBlockNumber,
        long boundarySlot,
        byte[] boundaryBlockHash,
        String failureClass,
        String detail,
        Instant recordedAt
) {}
```

`NOT_PROJECTED` is a query/status coverage outcome but is not written as one
`EpochArtifactOutcome` per epoch. It is derived for every epoch below the
dataset's durable ADR-044 `projectedFromEpoch`. Likewise, epochs beyond the last
eligible boundary are future/unknown rather than gaps.

The boundary hash is required. Slot or block number alone is insufficient across
rollback and same-coordinate Byron EBB/main-block cases.

`COMPLETE` includes zero-row artifacts. A durable completion fact is what
distinguishes “the calculation produced nothing” from “capture never happened.”

The outcome is scoped by dataset and semantic epoch. A reward gap does not imply
a DRep gap at the same boundary.

ADR-044 owns selection, enrollment and the prospective join rule. This ADR owns
the coverage representation that makes the join honest. Mid-archive addition is
not a resume operation: a joined dataset begins `ACTIVE` at its persisted first
eligible epoch and has no defect to repair in its `NOT_PROJECTED` prefix.

### 2. Persist gap intent before allowing silent continuation

If capture cannot produce or validate durable evidence, the system must durably
record the gap before it allows synchronization to continue as a degraded
archive.

The projection outbox/chainstate database is the source of pending gap records,
because it already shares canonical rollback coordinates with the state
transition. The sink later commits gap/coverage facts into the history archive
so reads remain honest after outbox pruning.

If neither evidence nor the gap record can be made durable—for example because
the underlying volume is full or RocksDB cannot write—the node cannot safely
continue archival operation. It retains the existing fail-closed/global-pause
behavior and surfaces a fatal projection health error. “Continue with an
unrecorded gap” is never an option.

Before declaring a gap, restart reconciliation checks for recoverable evidence:

- a complete staged file with valid size and digest;
- committed source parts from restartable boundary phases;
- a pending outbox reference;
- or a matching durable sink receipt.

Only proven absence/corruption becomes `GAP`. A source that can still complete
normally remains `PENDING`.

### 3. Separate dataset pause from archive-global failure

Maintain durable capture state per selected staged dataset:

```java
enum EpochArtifactCaptureState {
    ACTIVE,
    PAUSED
}
```

On a dataset-specific capture failure:

1. preserve or reconcile any usable evidence;
2. otherwise record the exact dataset/epoch gap;
3. mark that dataset `PAUSED`; and
4. keep other selected datasets active.

Pausing after the first gap avoids generating one gap every epoch while a
persistent serializer bug, permission error or capacity problem remains.

Epochs whose boundaries pass while the dataset is paused were selected and are
also unavailable because of the failure; they are not `NOT_PROJECTED`. Represent
them as one durable open pause-gap interval rather than one record per epoch:

```java
record EpochArtifactGapInterval(
        ArchiveDatasetId dataset,
        int fromEpoch,
        int throughEpoch,
        CanonicalPoint fromBoundary,
        CanonicalPoint throughBoundary,
        boolean open,
        int causedByEpoch,
        String failureClass
) {}
```

The interval is created when the first eligible boundary passes after the point
gap, and its `throughEpoch`/`throughBoundary` are advanced once per later missed
boundary. Resume closes it at the last eligible boundary that passed while
paused. If no additional boundary passed, no interval is created. Coverage
expands the point gap plus any closed/open pause interval into missing epoch
ranges for queries and status, without one stored row per missed epoch or
high-cardinality metrics.

A failure of shared staging infrastructure may pause every staged-file dataset,
but it records the affected dataset/epoch outcomes independently. Direct
`EPOCH_STAKE` and `ADA_POT` retry paths are not disabled merely because the
staged-file root is paused.

Archive health becomes `DEGRADED` when all committed state is trustworthy but
one or more durable gaps exist. It remains `FAILED` when durability or outcome
provenance is unknown.

### 4. Resume is an explicit state transition, not marker deletion

Add an operator operation equivalent to:

```java
resumeDataset(ArchiveDatasetId dataset)
```

The operation is accepted only when:

- the dataset is selected and enrolled by the persisted ADR-044 artifact
  identity;
- no epoch boundary is currently open;
- existing temporary/source files reconcile deterministically;
- the staging directory passes create, write, fsync, rename and cleanup probes;
- the outbox and canonical tip are consistent; and
- any implementation-specific failure precondition is cleared.

On success it atomically changes the dataset from `PAUSED` to `ACTIVE`. Existing
gap records remain unchanged. At the next eligible boundary, the existing ledger
call to `archiveStaging.enabled(dataset)` returns true and ordinary capture
resumes for that boundary's semantic epoch.

Resume does not attempt to synthesize the current epoch after its boundary has
already passed. For example, resuming during epoch 500 captures the next reward
artifact produced by the normal boundary pipeline; it does not recreate epoch
450 or retroactively capture an already completed 499-to-500 boundary.

The operator surface is the administrative
`POST /history/coverage/{dataset}/resume` endpoint. It calls the same service
operation used by recovery and returns the retained gaps and next eligible
capture point.

Deleting `epoch-source/FAILED` manually is not resume and remains unsupported.

Resume never changes selection or `projectedFromEpoch`. Adding a previously
unselected dataset is ADR-044 enrollment; resuming changes an already enrolled
dataset from `PAUSED` to `ACTIVE`.

### 5. Persist non-contiguous epoch coverage in the sink

Block receipts prove contiguous block projection ranges but do not prove which
semantic epochs exist for each artifact. Add sink-owned epoch artifact coverage,
committed transactionally with artifact rows or gap outcomes.

The logical record contains at least:

```text
dataset
semantic_epoch
boundary_block_number
boundary_slot
boundary_hash
outcome          COMPLETE | GAP
row_count        nullable for GAP
content_digest   nullable for GAP
failure_class    nullable for COMPLETE
recorded_at
```

Pause-gap intervals are stored separately with their start epoch, optional end
epoch, causing point gap and canonical pause/resume transition points. They are
not expanded into one row per missed epoch.

The coverage header for an enrolled dataset, sourced from ADR-044 enrollment
metadata, contains:

```text
dataset
selected
projected_from_epoch   nullable only for LEGACY_UNKNOWN
legacy_prefix_state   KNOWN | UNKNOWN_LEGACY_COVERAGE
capture_state         ACTIVE | PAUSED
```

Coverage reporting derives:

- `projectedFromEpoch` and the implied `NOT_PROJECTED` prefix;
- `observedThroughEpoch`: greatest epoch with a complete or gap outcome;
- `contiguousCompleteThroughEpoch`: greatest epoch in the contiguous complete
  run beginning at `projectedFromEpoch`, or absent when that run has not begun;
- complete epoch ranges;
- explicit point gaps plus closed/open pause-gap ranges; and
- current capture state (`ACTIVE` or `PAUSED`).

Rows and `COMPLETE` must commit in the same sink transaction. A `GAP` is also a
positive durable record; table-row absence is never treated as a gap.

### 6. Query completeness is range-aware

Before reading an epoch dataset, the history facade checks the requested semantic
epoch range against durable coverage.

- A query wholly inside complete ranges proceeds normally.
- A query intersecting `NOT_PROJECTED`, `PENDING`, unknown legacy coverage or a
  gap fails with an incomplete-history response that names the dataset, missing
  ranges and reason for each range.
- A point-in-time snapshot query after an earlier gap succeeds when its requested
  epoch is complete.
- An unbounded reward-history query fails if any gap exists in its implied range.
- Reward totals or pagination never silently skip a gap.

The default API remains fail-closed. A future explicitly named partial-results
mode may return rows plus machine-readable gaps, but must not reuse the ordinary
success contract. This ADR does not require that mode.

Because an unbounded reward query is a primary wallet operation, its incomplete
response must be actionable rather than a generic service-unavailable error. It
includes at least the selected dataset, requested/implied range,
`projectedFromEpoch`, complete ranges, bounded gap summaries, capture state,
whether resume is applicable, and a pointer to `/history/coverage`. A UI can then
show available-from or degraded-history state without treating a partial reward
sum as correct. ADR-046 fixes the final HTTP status/body contract.

Example coverage:

```yaml
dataset: reward
selected: true
projectedFromEpoch: 400
captureState: ACTIVE
observedThroughEpoch: 500
contiguousCompleteThroughEpoch: 449
completeRanges:
  - { from: 400, through: 449 }
  - { from: 451, through: 500 }
gaps:
  - epoch: 450
    failureClass: source-write-failed
```

A reward query for `451..500` is valid. `0..399` is refused as `NOT_PROJECTED`,
and `400..500` is refused because it crosses gap 450. A DRep point query for
epoch 500 is independent of a DRep gap at 450.

### 7. Expose coverage and failures through status and metrics

Extend `GET /history/coverage` with one summary per known epoch artifact:

```yaml
dataset: reward
selected: true
projectedFromEpoch: 400
captureState: PAUSED
observedThroughEpoch: 500
contiguousCompleteThroughEpoch: 449
completeRanges: [{ from: 400, through: 449 }]
gapCount: 1
gapRangeCount: 1
firstGapEpoch: 450
lastGapEpoch: 500
gapRanges: [{ from: 451, through: 500, open: true, causedByEpoch: 450 }]
legacyPrefixState: KNOWN
resumeApplicable: true
```

Unselected datasets appear with `selected: false` and do not masquerade as empty
selected datasets. This dataset-level state is named `NOT_SELECTED`;
`NOT_PROJECTED` is reserved for the pre-enrollment epoch range of a selected
dataset. A bounded detail query on the same resource accepts dataset/from/to/page
parameters and returns canonical gap records. The default summary does not
return an unbounded list of every gap.

Publish low-cardinality metrics equivalent to:

- total gap outcomes by dataset and bounded failure class;
- current `ACTIVE`/`PAUSED` state by dataset;
- selected state and `projectedFromEpoch` by dataset;
- last complete/observed epoch by dataset; and
- current point-gap and gap-range counts by dataset.

Semantic epoch, block hash, exception text, address and source filename are not
metric labels. Exact coordinates and diagnostic detail belong in the bounded
status response and logs. Metrics alert operators; `/history/coverage` explains
what is missing and whether join, resume or repair applies.

The `projectedFromEpoch` gauge is emitted only when the value is known; selection
and `legacyPrefixState` gauges distinguish unselected and legacy-unknown cases
without inventing a numeric sentinel epoch.

### 8. Repairing a gap requires proof and is atomic

Resume and repair are separate operations.

- **Resume** enables future capture and leaves gaps intact.
- **Repair** supplies the missing artifact, normally from a corrected replay,
  retained generation where coverage is proven, or an externally verified
  artifact export.

A gap changes to `COMPLETE` only in the same sink transaction that commits the
missing rows and their verified count/digest. Deleting a gap record before the
rows commit is forbidden.

Repairing an epoch inside a pause-gap interval atomically shrinks or splits that
interval in the same transaction. A range must never continue to claim the
repaired epoch is missing, and it must never be shortened before verified rows
and `COMPLETE` are durable.

For currently irreproducible datasets, ordinary live-state repair is not
available. A full replay from genesis, a future checkpointed replay with proven
input closure, or a verified import is required.

### 9. Rollback uses the full canonical point

Gap, pause and completion outcomes are derived state and follow canonical
rollback:

- outcomes whose boundary lies after the rollback point are removed;
- a same-slot outcome is retained only when its boundary hash matches the target
  point;
- pause/resume transitions and pause-gap interval endpoints use the same full
  canonical-point rule, reopening or removing an interval as rollback requires;
- sink coverage rollback and outbox outcome rollback are idempotent; and
- replaying the boundary may replace a rolled-back gap with complete evidence.

Rollback does not erase an earlier canonical gap merely because capture later
resumed.

### 10. Existing global failure markers remain visible and fail-closed where relevant

An existing `epoch-source/FAILED` marker may not contain enough structured
information to prove the affected dataset, semantic epoch and canonical hash.
Upgrade therefore does not automatically convert it into a gap or reactivate
capture. It is always exposed in `/history/coverage` as an unstructured legacy
failure.

Its blocking scope follows the configured staged set:

- if no staged-file artifact is selected, the marker is inactive and does not
  block block projection, direct `EPOCH_STAKE`/`ADA_POT`, or node startup;
- if any previously selected staged artifact remains selected, its provenance is
  unknown and staged capture remains fail-closed;
- joining a staged-file artifact while the marker exists is refused, because the
  old failure cannot safely be attributed to or excluded from the new dataset;
  and
- selecting `none` never deletes or acknowledges the marker.

An operator must either:

- rebuild from a known complete point/genesis; or
- use an explicit audited acknowledgement/migration operation that records the
  unknown historical prefix and permits future enrollment without pretending a
  precise gap was recovered.

The new implementation writes structured per-dataset state from its first fresh
boundary. It does not infer history from legacy marker text.

## Alternatives considered

### Keep the permanent archive-global latch

Safe and simple, but one permanent gap discards every later artifact and every
other staged dataset. This preserves a genesis-complete invariant by making the
archive progressively less useful.

### Automatically retry every dataset at every later boundary

Rejected as the default. A persistent disk or codec failure would create an
unbounded sequence of gaps. Pause plus explicit resume bounds damage and makes
operator intent visible.

### Clear the marker and continue without recording the gap

Rejected. Address reward history and totals would silently under-report, while
snapshot queries could mistake missing rows for an empty distribution.

### Return all available rows and document that results may be partial

Rejected for the ordinary query contract. A caller cannot infer which address
would have received a missing reward, so row-level output cannot communicate
completeness. Any partial mode must carry explicit coverage metadata and require
explicit caller opt-in.

### Treat every artifact as a cumulative stream after one gap

Rejected. Snapshot and observation datasets remain independently meaningful at
later epochs. Coverage and query validation must follow dataset semantics rather
than the strictest behavior of rewards.

## Consequences

Positive:

- One missing epoch no longer destroys all useful future history.
- Healthy datasets continue when another dataset fails.
- Every missing epoch is durable, inspectable and query-visible.
- Expected pre-enrollment history is distinguishable from an operational capture
  defect through `NOT_PROJECTED` versus `GAP`.
- Snapshot queries after an earlier gap remain available.
- Reward history remains fail-closed for ranges whose totals or events are
  incomplete.
- Resume is safe because it cannot erase or disguise a gap.
- Status and metrics expose degradation without requiring log inspection or
  direct DuckLake queries.

Tradeoffs:

- Epoch coverage becomes non-contiguous and requires a new sink schema and query
  preflight.
- Capture state, gap state, rollback and operator controls add lifecycle
  complexity.
- Existing APIs without epoch ranges may become unavailable after a reward gap
  until rebuilt, even though later rows continue accumulating; their error must
  carry enough coverage detail for a wallet UI to degrade gracefully.
- Gap repair remains expensive for irreproducible datasets.
- Legacy global failure markers cannot be migrated automatically.

## Implementation areas

The implementation affects:

1. the core epoch staging SPI — structured failure/outcome callbacks;
2. `EpochArchiveStagingService` — per-dataset state and source reconciliation;
3. ADR-044 enrollment/outbox storage — `projectedFromEpoch`, legacy-prefix state,
   canonical gap and pause records;
4. `ProjectionHistoryService` — health, resume orchestration, rollback and drain;
5. projection sink API and DuckLake schema — transactional non-contiguous epoch
   coverage;
6. `HistoryResource` and history repositories — bounded status detail, range
   completeness checks and actionable diagnostics;
7. metrics — bounded dataset/failure-class counters and state gauges;
8. operator API/CLI — inspect, acknowledge legacy state and resume a paused
   dataset; and
9. snapshot/rebuild admission — preserve or validate enrollment/gap metadata.

Core reward, governance, AdaPot and stake calculations should not change.

## Verification plan

1. Epochs before an ADR-044 join are derived as `NOT_PROJECTED`, create no gap
   rows and do not increment gap metrics.
2. A zero-row selected artifact records `COMPLETE`, never `GAP`.
3. A complete staged file surviving a crash reconciles to `PENDING/COMPLETE`, not
   a gap.
4. A reward capture failure records exactly one canonical `REWARD/E` gap and
   pauses reward capture.
5. DRep and governance capture continue after a reward-only failure.
6. A shared staging-root durability failure pauses all staged datasets and never
   continues without durable outcome records.
7. Resume fails while a boundary is active or durability probes fail.
8. Successful resume leaves old gaps intact and captures the next eligible
   boundary.
9. Boundaries passed while paused form one compact pause-gap interval; resume
   closes it without emitting one record per epoch.
10. Restart preserves projected-from, point/range gaps and pause/active state.
11. Rollback before a gap's full point removes it; same-slot different-hash
   rollback does not retain a non-canonical outcome.
12. Rollback across pause/resume points reopens, shortens or removes the compact
    gap interval by full canonical point.
13. A snapshot query for a complete epoch after an earlier gap succeeds.
14. A reward range or unbounded history query intersecting a gap fails with
    machine-readable missing epochs, projected-from state, capture state and a
    coverage-status reference.
15. Pagination and totals cannot bypass the coverage check.
16. `/history/coverage` distinguishes unselected, active, paused, legacy-unknown,
    gapped and complete datasets; its detail paging is bounded.
17. Metrics change on join/gap/resume/repair without epoch/hash/error-text labels.
18. Gap repair changes point/range coverage only atomically with verified rows.
19. An existing unstructured `FAILED` marker is visible but non-blocking when no
    staged dataset is selected; staged join remains refused until acknowledged.
20. Kill/restart tests cover crashes before evidence, after evidence, after gap
    outbox write, after sink coverage commit and before acknowledgement.
21. Devnet tests induce a dataset-specific failure, resume after repair, cross
    later boundaries and verify both continued capture and honest query refusal
    across the gap.

## Release note

Epoch artifact capture now preserves explicit per-dataset gaps and allows
repaired datasets to resume at future boundaries.
Queries crossing a gap will fail as incomplete rather than silently returning
partial history. Pre-enrollment epochs are reported separately as
`NOT_PROJECTED`. Coverage summaries and low-cardinality metrics expose selected,
paused, complete and gapped state. Existing unstructured epoch staging failure
markers remain visible, are non-blocking when no staged artifact is selected,
and are never cleared automatically.
