# ADR-039 Phases 5–6 — implementation report (2026-08-22)

Branch `feat/adr-039-phase5-epoch-artifacts`, stacked on the Phase 0–4b head.

## Phase 5 — epoch artifacts

### Representation decisions

| Dataset | Representation | Reconstructibility | Why |
|---|---|---|---|
| `EPOCH_STAKE` | `IMMUTABLE_GENERATION` | `RECONSTRUCTIBLE` | The delegation snapshot is already persisted per epoch with a 50-epoch retention. Referencing it copies nothing, so the epoch transition does not slow as the stake distribution grows. A staged file would write ~1M rows on the critical path of every mainnet boundary. |
| `ADA_POT` | `ATOMIC_EVIDENCE` | `RECONSTRUCTIBLE` | Eight numbers. The pot is stored directly rather than through the boundary batch, and is re-stored as rewards and then governance adjust it, so there is no generation to point at. Carrying the values inline is cheaper than any protection scheme and needs no lease. |

`RECONSTRUCTIBLE` for both: neither source has a retention prune path that a lease
does not already cover, and rollback removes the artifact reference together with
the source it points at.

### Artifact wire form

The reader emits the **final archive row**, not the source record.

The sink cannot build the row itself: `ProjectionArtifactRef` carries no boundary
block hash and no block time, and `ArchiveArtifactReader`'s contract is that a sink
reaches artifacts only through that interface — it may not consult ledger state to
fill them in. The reader resolves the boundary once per artifact from the same two
sources the replay worker uses (`getCanonicalBlockReference` for the hash,
`slotToUnixTime` for the time), so both pipelines produce identical rows, and both
build them through the same shared row builder in `archive-core`.

`ArchiveRowCodec` (archive-api) carries a row, table name included, so a decoded
row cannot be mis-routed into another dataset's table.

### Atomicity

- `EPOCH_STAKE` stages its reference into the **same `WriteBatch`** as the snapshot
  it points at. Neither half can become durable alone.
- `ADA_POT` has no such batch, so `contributeAdaPotArtifact` writes the final pot
  value and the artifact reference in one synced `WriteBatch`. Re-writing identical
  bytes is semantically a no-op and costs one small record per epoch.
- At the sink, artifact rows join the **same transaction** as the block rows, and
  merge into the same receipt's row counts.

### Durable lease reconciliation

This was the gap the ADR flagged as "must ship with the first artifact"
(acknowledging a range deletes artifact *references*, so a crash before lease
release orphans the lease).

Leases are in-memory; the outbox's surviving references are the durable contract.
`ProjectionOutboxStore.pendingArtifacts()` exposes them, and
`ArchiveArtifactReader.reconcileAfterRestart()` re-derives protection:

- at startup, before the drain begins and before anything can prune;
- after a rollback, which deletes references and would otherwise leave a source
  pinned for an artifact that no longer exists.

An empty pending set is meaningful and is applied: it releases protection rather
than leaving a stale floor. Pending epochs are tracked as a **set**, so
acknowledging one epoch cannot release protection for another still pending.

### Verification at the sink

`contentDigest` is empty for `IMMUTABLE_GENERATION` by design: a boundary-time
content digest would cost a full extra pass over the snapshot, which is exactly
what the persisted-generation representation exists to avoid. The compensating
controls are enforced at commit:

- an artifact that declares **no** expected row count is refused outright;
- a row-count mismatch aborts the whole commit, block rows included;
- a row for a table outside the artifact's dataset is refused.

### Artifact contract identity — now enforced

`ProjectionArtifactIdentity` existed but was never wired. It is now built from
`ProjectionArtifactContracts.shipped()`, persisted in outbox meta
(`META_ARTIFACTS`), and checked at every start.

This matters because artifacts are **not** part of the section fingerprint: without
it, a node capturing epoch stake and a node capturing nothing present the same
fingerprint, and the second reports itself complete for artifacts it never captured.

The set is a build constant, not configuration — a toggle would reintroduce exactly
that hole.

**Consequence, accepted deliberately:** an archive written before artifacts existed
is refused at startup and requires a fresh sync. That is correct — epoch artifacts
cannot be added to an archive whose earlier epochs never captured them — and no test
constructs the service, so the blast radius is real deployments only.

### Wire format v2

The envelope and artifact formats moved to v2, adding the inline-evidence payload.
With zero artifacts the encoding is byte-identical to v1, and any archive that
already holds blocks is refused at startup anyway, so the only outboxes v2 rejects
are ones that already required a fresh sync. No migration is offered, and the
golden-digest test records that decision.

### Deferred, with the ADR precondition each one fails

The ADR gates the remaining three explicitly. None of their preconditions has been
proven, and `shipped()` is persisted and refused-on-mismatch — so shipping a contract
whose precondition is unmet would bake a wrong promise into every archive from now on.

| Dataset | ADR precondition not yet met |
|---|---|
| `REWARD` / reward-rest | "only after proving and retaining their complete deterministic input and calculation-version closure, otherwise capture them as non-reconstructible evidence" |
| `DREP_DISTRIBUTION` | "stream the persisted amount generation and atomically snapshot or durably stage the boundary-time DRep-state columns" — the state columns are overwritten by later state |
| `GOVERNANCE_PROPOSAL_STATUS` | "only after their observation phase and decision semantics have an immutable source" |

## Phase 6 — operational and API cutover

### The read cutover was NOT done by inspection, and calling one endpoint proved it

The first draft of this report claimed historical queries needed no change because
"the row queries already read the same physical tables". That claim was wrong, and one
REST call against the finished archive exposed it:

    GET /addresses/{addr}/transactions
    -> {"error":"Address transaction history disabled
        (enable yano.history and address-transactions dataset)"}

over an archive holding **39 million** address-transaction rows. Three separate
legacy couplings had to be cut, each found only by fixing the one above it:

1. **Lifecycle.** `historyProvider()` returns null whenever `yano.history.enabled` is
   false, so no backend existed to read through. `HistoryArchiveService` gained
   `initializeProjectionReads(...)`, which opens the backend and nothing else — no
   workers, no staging, no activations — and serves exactly the datasets the
   projection reports it covers.
2. **Identity.** The projection derives its `archiveId` from the projection
   fingerprint; the legacy path derives it from network, genesis, engine and
   directory. Recomputing it the legacy way was refused as an identity mismatch over
   the very archive it was meant to read. The projection now publishes the identity
   its sink was opened with, and the reader presents that.
3. **Coverage.** Reads then failed `history coverage is incomplete`, because both
   coverage paths — the backend's public `coverage()` and the repository's internal
   one — read `archive_coverage`, which the projection never writes and which this
   archive confirms is **empty**. Both now fall back to `projection_receipts`, whose
   committed block ranges are the equivalent proof: every required section for a range
   commits in one transaction with its receipt.

The fallback is deliberately conditional on the legacy table being empty, so a
genuinely incomplete legacy archive still reads as incomplete — the Phase 7 oracle run
depends on that answer staying authoritative. Receipts prove **block** ranges only; an
epoch dataset is asked about in epoch terms and a receipt cannot say which epochs a
block range spans, so epoch coverage stays empty and those reads fail closed rather
than answering from coverage nobody proved.

**Verified end to end.** `GET /addresses/{addr}/transactions?count=3` now returns the
archive's own first three rows for that address, matching on `tx_hash`, `tx_index`,
`block_height` and `slot`:

    65428c7377aa8e8ebeff652729e21a3fa7f3dd49c3f9456543da86294bd73003  idx 0  block 2,032,991  slot 54,669,470
    2012fea7ad77ab0a82749a549d7fac9014e0175b289f2a7fbf7d3f5812753ba5  idx 2  block 2,032,996  slot 54,669,588
    db81eedb465ee7cd4154745417db8cb3b6558657aaf67321a675fe6acaf29364  idx 2  block 2,033,023  slot 54,670,450

`GET /txs/{hash}` was already answering correctly from the archive before these
changes.


- `GET /history/coverage` — identity, sections, artifact contracts, committed range,
  distance from tip, and the honest upper bound on commit latency.
- `GET /history/watermark` now prefers the projection's own consistency point. The
  legacy watermark reads `archive_coverage`, which the projection never writes;
  reporting it once the projection is primary would say "nothing is archived" over a
  complete archive — the false absence the ADR forbids. The projection's answer is
  also stronger: every required section commits in one transaction with its receipt,
  so the committed coordinate is a cross-dataset consistency point by construction.
- Status now exposes artifact contracts, pending artifact count, oldest pending
  artifact epoch, and the live snapshot protection floor.
- **Transaction-hash lookup is documented as `full-scan`** in the coverage payload.
  The projection does not build the replay worker's SQLite locator, so lookup by hash
  falls back to a full-range scan. The result is correct — the locator was only ever
  an accelerator and the fallback is the authoritative query — but it is O(archive)
  until a derived index exists.

## Defects the live preprod run found that tests did not

Two bugs surfaced only once a real node ran, and both are worth recording because
they show where the unit-test surface was blind.

**1. `historyDirectory` was null at `openSink()`.** The Phase 0–4b change that moved
`openSink()` ahead of the startup guard left the `historyDirectory` assignment behind
it. The four-section run predates that reorder, so nothing had exercised the new
order. Startup failed immediately with `RUNTIME_INITIALIZATION_FAILED`. No unit test
could have caught it: nothing constructs `ProjectionHistoryService`. Fixed by
resolving the directory before `openSink()`.

**2. The retention clamp was being raised, not lowered.** The collector called
`clamp.protectSnapshotsFrom(epoch)` unconditionally at every boundary, so the floor
tracked the *newest* epoch. The sampler showed `oldestPendingArtifactEpoch = 0`
against `protectedSnapshotFloorEpoch = 29` — 29 pending artifacts whose snapshots
were no longer protected. Two writers were setting one value with opposite meanings:
the collector wrote "newest staged", the reader wrote "oldest still needed".

The floor means *oldest epoch still referenced*. The collector now lowers it only;
raising it is the reader's job, which recomputes the true minimum from open leases
plus the outbox's pending set on every acknowledgement. Covered by
`stagingALaterEpochDoesNotReleaseAnEarlierOneStillPending`.

This would not have caused loss at the shipped defaults — snapshot retention is 50
epochs and the floor never fell more than a few epochs behind — but it would have
bitten exactly when it mattered, with a sink lagging more than 50 epochs.

**3. `oldestRequiredSlot` had the wrong meaning, and it deadlocked the drain.**
The epoch-stake artifact declared the boundary slot as its `oldestRequiredSlot`. That
field feeds `ProjectionRetentionHealth`, which asks *"has the common rollback floor
passed a slot this batch still needs to replay from?"* — it is about **lost replay
data**, not about rollback invalidating a generation.

On a fresh sync the rollback floor passes an old boundary slot almost immediately, so
the drain paused permanently:

    drain paused: common rollback floor 26866073 has passed the oldest active
    required slot 21600; required replay data was lost under a lagging sink

Zero batches drained at 795,000 blocks, with 2.3 GB of backlog still accumulating.

What an epoch-stake artifact actually needs is the delegation snapshot, and that is
held by the pruning clamp — not by the node's ability to replay back to the boundary.
Rollback below the boundary is handled structurally: it deletes the artifact
reference along with the envelope carrying it. The correct value is therefore `-1`,
"no chain retention dependency", the same as inline evidence. The over-strict
validation that only allowed `-1` for `ATOMIC_EVIDENCE` was itself part of the same
mistake and was relaxed.

**4. ADA_POT carried the wrong `source_state_version`.** The collector took one state
version for both datasets, so ada-pot rows were written as
`ledger-boundary-v1/snapshot` where the replay worker writes
`ledger-boundary-v1/final`. Identical data would have looked like it came from two
different producers. The collector now takes the base and each dataset appends its own
part.

**5. The snapshot paging cursor dropped one row per page.** Found by self-review, not
by the run. `readEpochDelegSnapshotPage` returned the first *unread* key as its
cursor, while resuming treats the cursor as **exclusive** — so exactly one row was
skipped at every page boundary.

Invisible on preprod, where an epoch averages 266 delegators and fits in a single
50,000-row page. On mainnet, with roughly a million delegators per epoch, it would
drop a row every page. It would not have corrupted the archive silently: the sink
refuses a commit whose row count does not match the artifact's declared
`expectedRowCount`, so it would have deadlocked the drain instead — on mainnet only.
The cursor is now the last row *included*. Covered by `EpochSnapshotPagingTest`,
which uses a page size that divides neither the row count nor the last page.

Measured on the validation run: preprod's largest epoch holds **20,063** delegators
against a 50,000-row page, average 6,568, and **zero epochs exceed the page size**.
So preprod structurally cannot exercise the paging cursor at default settings — which
is both why the bug survived and why the pre-fix jar used for this run produced
correct data. To exercise it on real data, a run must set
`yano.history.projection.artifact.page-rows` below the largest epoch.

## Parity evidence

`ada_pots` epoch 33, projection-written row vs the node's own ledger
(`GET /epochs/33/adapot`) — every column identical:

| column | archive | ledger |
|---|---|---|
| treasury | 112487719438755 | 112487719438755 |
| reserves | 14887472519218419 | 14887472519218419 |
| deposits | 1914000000 | 1914000000 |
| fees | 4695677317 | 4695677317 |
| rewards_pot | 37012905863590 | 37012905863590 |

Read live from the archive while the node was running, via a read-only DuckLake
attach.

Steady-state drain health during the same run, from the sampler: acknowledgement
tracked the tip at the finality window (348,467 against 355,961), pending backlog
held at 8,280 blocks, 263 batches with **0 drain failures**, and
`protectedSnapshotFloorEpoch` tracked `oldestPendingArtifactEpoch` exactly — the
clamp fix working in the steady state that exposed the bug.

## Recovery — both aimed at a moment with artifacts actually pending

A restart taken at a random moment finds zero pending artifacts, so
`reconcileAfterRestart` receives an empty set and the durable-lease path never runs.
Both tests therefore waited for `pendingArtifacts > 0` before acting.

| | graceful (`SIGTERM`) | unclean (`SIGKILL`) |
|---|---|---|
| caught at | 2 pending, epoch 47, floor 47, ack 557,892 | 2 pending, epoch 50, floor 50, ack 613,092 |
| reconciliation on restart | `re-established source protection for 2 pending epoch artifact(s)` | same, at 08:26:23 |
| floor after restart | 47 — re-established to the exact pending epoch | (drain had already acknowledged by sample time) |
| acknowledgement | 557,892 → 559,777, no regression | 613,092 → 616,805, no regression |
| drain failures | 0 | 0 |

The reconciliation line is emitted before the drain starts, so it cannot be an
artifact of draining. Note that Quarkus truncates `yano.log` on each start, which is
why the graceful run's evidence was captured to its own file before the next restart.

## Completeness — no artifact epoch silently skipped

Correctness is not completeness: both contributors are `if (enabled)` early returns,
so a skipped boundary would leave no trace. Checked directly against the archive:

| dataset | rows | distinct epochs | range | verdict |
|---|---|---|---|---|
| `ada_pots` | 39 | 39 | 4–42 | CONTIGUOUS |
| `epoch_stakes` | 10,103 | 38 | 4–41 | CONTIGUOUS |

Zero missing epochs when enumerated explicitly rather than inferred from counts, and
zero gaps between projection receipts. Both datasets begin at epoch 4, which is where
preprod's Shelley era starts — there is no stake distribution or ada pot before it.

## Epoch boundary latency

Measured on this run, persisted-generation representation, 39 boundaries:

    min 0ms   p50 53ms   p90 420ms   max 609ms   mean 108ms

**Declared gap:** the staged-file arm was not run, so this is a measurement of the
chosen representation rather than the A/B the ADR asked for. The argument for
choosing it without the comparison is that a staged file writes one row per delegator
on the critical path of every boundary — on mainnet roughly a million rows — whereas
the reference is a single record whose cost does not grow with the distribution.
These figures are also from early preprod epochs with small distributions; the
persisted-generation cost should stay flat as epochs grow, and that is the claim a
later staged-file comparison would test.

## Preprod validation run — measurements

Fresh genesis sync, all four block sections plus both epoch artifacts, DuckLake as
the sole primary sink, legacy replay worker off.

| | |
|---|---|
| blocks | 5,084,279 (preprod tip) |
| time to tip | **74m 37s** (08:21:52 → 09:36:29) |
| throughput | p50 **1,062 blk/s**, p90 1,654, max 2,840, min 230 (69 samples) |
| batches drained | 4,134 over 4,466,865 blocks |
| **drain failures** | **0** |
| backlog at tip | 4,322 blocks — exactly the finality window |
| contributor status | HEALTHY throughout, all four cursors in lockstep |
| resident memory | 1.89 GB – 4.27 GB (`-Xmx4g`) |
| archive on disk | 5.6 GB (chainstate 46 GB) |
| maintenance | 2 housekeeping passes, 1 compaction, on the drain thread |

**These are lower bounds, not clean numbers.** The box was concurrently running a
mainnet sync (~246% CPU) and, for part of the run, this campaign's own Gradle builds;
load average sat around 12–15. The time to tip also includes two deliberate restarts
for the recovery tests. A quiet box would do better; nothing here should be quoted as
a ceiling.

Two hour-long log monitors watching for drain pauses, sink exceptions, receipt
mismatches, ingest pauses and OOM produced **zero events** across the run.

### Final archive

| table | rows |
|---|---|
| `transactions` | 6,608,841 |
| `transaction_inputs` | 27,459,006 |
| `transaction_outputs` | 22,077,160 |
| `output_lifecycle` | 22,077,160 |
| `unspent_outputs` | 4,378,913 |
| `address_transactions` | 39,031,011 |
| `account_events` | 428,411 |
| `epoch_stakes` | 4,565,194 (304 epochs, contiguous) |
| `ada_pots` | 305 (305 epochs, contiguous) |
| `projection_receipts` | 4,631 |
| `archive_coverage` (legacy) | **0** |

`output_lifecycle` matching `transaction_outputs` exactly is a consistency signal, and
the empty legacy `archive_coverage` is direct evidence that routing the watermark
through the projection was necessary rather than speculative — the legacy answer over
this archive would have been "nothing is archived".

### Final-jar restart on the populated archive

The jar carrying every fix was restarted against the completed 5.6 GB archive: the
artifact contract check accepted the stored contracts, the startup guard accepted the
populated sink, and the drain resumed. `GET /history/coverage` reported
`queryableThroughBlock 5,079,957` against tip 5,084,286 — 4,329 behind, the finality
window — with `maxCommitLatency PT15M`.

**One more defect, found through the API rather than the code.** The watermark's
`asOf` was publishing `slot 5079957` and `blockHash "01"` — the sink's internal
placeholders. A sink only needs the block number to recognise a committed range, so
DuckLake stores `new byte[]{1}` for the hash and reuses the block number as the slot;
that is fine internally and indefensible in an API callers use to pin a consistency
point. `asOf` is now resolved canonically from the chain, and omitted rather than
invented when it cannot be resolved. It now reads slot 131,585,511, hash
`e349708d5cd21e8cf6014b1b307489a54556ae363a586df53b40d3b17dd64dfb`.

## Why the two artifact datasets end one epoch apart

`ada_pots` spans epochs 4–308 and `epoch_stakes` spans 4–307. Both are internally
contiguous; they differ because the two artifacts describe different sides of the same
boundary. At the transition from 307 to 308 the delegation snapshot is written for the
**outgoing** epoch (`createAndCommitDelegationSnapshot(epoch)`) while the ada pot is
finalised for the **incoming** one (`contributeAdaPotArtifact(newEpoch)`). A one-epoch
difference at tip is therefore the expected steady state, not a missing row.

## Not validated: disk backpressure

`ingestState` read `RUNNING` for the entire run and was never driven to `PAUSED`. The
mechanism is wired and its limits are reported in status
(`retainedPhysicalBytes`, `retainedLimitBytes`, `filesystemFreeBytes`), but **it was
not exercised**, and a one-way pause would be a hang, so the resume is the half that
needs proving. Forcing it needs
`yano.history.projection.backlog.hard-bytes` (or the retained-bytes limit) set low
enough to trip during a sync, then confirming ingest pauses **and resumes** once the
drain catches up. Everything else on the validation list — finality window, retention
leases, maintenance, both restart paths, graceful shutdown, contributor lockstep,
acknowledgement contiguity — has evidence above.

## Contributor lockstep at tip

    account-events:v3      5,084,330
    address-transaction:v3 5,084,330
    transaction:v2         5,084,330
    utxo-history:v5        5,084,330

All four at the identical block — lag 0 — with `contributorStatus HEALTHY`,
`sinkHealth READY`, `ingestState RUNNING`, and acknowledgement contiguous through
5,080,007 with zero receipt gaps.

## Provenance of the archive

The 5.6 GB archive was written by a jar that predates three later fixes: the snapshot
paging cursor, the canonical `asOf`, and the read cutover. Each is write-path-inert
with respect to what is in it:

- **paging** — preprod's largest epoch is 20,063 rows against a 50,000-row page, so the
  cursor was never used during the sync;
- **`asOf`** — a read-side projection of the sink coordinate, never written;
- **read cutover** — read path only.

The strongest completeness evidence is not contiguity but the sink's own check:
**4,565,194 stake rows across 304 epochs each passed `expectedRowCount` verification
with zero drain failures.** A dropped or duplicated row would have aborted its commit.

The final jar was then restarted against this same archive, accepted its stored
artifact contracts and identity, resumed draining, and served both read endpoints
correctly.

## Build and suite

- Full repository suite: **BUILD SUCCESSFUL in 14m 17s**. (The `YANO_STARTUP_FAILURE`
  lines in its output are expected negative-path assertions, present in earlier green
  runs.)
- `:app:quarkusBuild`: **BUILD SUCCESSFUL**.

One regression was introduced and fixed during this: routing the watermark through
the projection broke two `HistoryResourceTest` cases that construct the resource
directly, so the injected collaborator was null. Rather than null-guard the resource —
which would have hidden a genuine wiring failure in production — the tests now supply
the collaborator, and a new case asserts the projection answers whenever it is the
primary writer and that the legacy service is not consulted at all.

## Tests

Focused suites green: `EpochSnapshotArtifactReaderTest` (7),
`AdaPotArtifactReaderTest` (4), `EpochArtifactCollectorTest`,
`ProjectionArtifactIdentityTest`, `DuckLakeProjectionSinkTest` (22, including six
artifact cases covering same-transaction commit, short-read abort, undeclared row
count, mis-routed table, and inline evidence).

Two real defects were caught by the new tests rather than by review:
`EpochStakeArtifactRows.columns()` said `credential_type` where the shipped schema
says `stake_credential_type`, and `oldestRequiredSlot` was minimised over `-1`,
which would have reported that a protected generation was free to prune whenever an
inline-evidence artifact shared its batch.
