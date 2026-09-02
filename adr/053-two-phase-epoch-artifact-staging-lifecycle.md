# ADR-053: Anchor Epoch Artifacts to the Last Block of the Source Epoch

## Status

Proposed

## Date

2026-09-01

## Context

Epoch stake, Ada pot, reward, DRep distribution and governance proposal status
are calculated while the ledger transitions from epoch `N` to epoch `N+1`.
They describe state determined before transactions from the first block of
`N+1` are applied.

The archive currently gives those artifacts the block number, slot and hash of
the first block in `N+1`. For staged-file datasets, that coordinate is also an
input to the durable source identity:

```text
UUID(network | dataset | semantic epoch | boundary block number |
     boundary block hash | source state version)
```

That choice exposes an ordering difference between fetched blocks and locally
produced blocks:

```text
fetched/public-network sync:
  store first block of N+1
  process N -> N+1 transition
  publish BlockApplied(first block of N+1)

local production:
  process N -> N+1 transition
  build and store first block of N+1
  publish BlockApplied(first block of N+1)
```

The fetched path can resolve the current boundary block while the transition is
running. The producer path cannot: the transition establishes protocol
parameters needed to build the block, so its hash does not exist yet.

PR #111 bridged that difference with a hash-less provisional source. The
transition wrote a `ProvisionalEpochArchiveJob`; the later block-application
batch rebound its rows to a final `EpochArchiveJob` through a hard link or copy.
This introduced a second durable-file lifecycle, provisional manifests,
interrupted-bind recovery and provisional rollback cleanup.

It also made a predicted carrier slot part of the binding contract. A lazy
producer can complete the transition, find no transaction that fits, and mint
no block. A later attempt uses the same block number at a later slot, so strict
slot equality rejects the real block and can wedge application. An incomplete
provisional capture can also be skipped during binding without producing a
complete artifact or an explicit archive gap.

The first block of `N+1` is not the state boundary that determines these facts.
The last canonical block of `N` is. It already exists in both ingestion paths
when transition processing begins.

## Terminology

This decision separates two coordinates that the current implementation often
treats as one:

- **anchor `A`**: the full canonical point of the last block before the epoch
  transition. For `N -> N+1`, this is normally the last block of epoch `N`.
- **carrier `C`**: the currently applying first block of `N+1` whose projection
  outbox entry carries the artifact reference or gap outcome.

The anchor establishes provenance and durable identity. The carrier establishes
outbox ordering, rollback attachment, sealing and finality eligibility.

## Decision

### 1. Re-anchor every epoch artifact to the end of its source epoch

At transition `N -> N+1`, resolve `A` before opening an artifact writer or
constructing a direct artifact reference.

For the fetched path, `C` is already canonical, so `A` is the canonical
predecessor at block number `C - 1`. For the producer path, `C` is not yet
canonical, so the current local full-point tip must be `C - 1` and is `A`.
Resolution requires an exact block number, slot and non-empty hash. A missing or
non-adjacent predecessor fails closed.

This lookup is valid only for eligible Shelley+ source epochs. Existing
first-post-Byron filtering remains authoritative, so Byron epoch-boundary blocks
do not enter this predecessor-by-number path. Byron EBBs do not advance chain
difficulty and therefore cannot be treated as dense numbered predecessors.

Several skipped epoch transitions may share the same `A` and intended `C`.
Their semantic epochs remain distinct.

### 2. Write only final staged-file sources

Reward, DRep distribution and governance proposal status continue to use the
existing `DurableEpochFileSource`. Their `EpochArchiveJob` is constructed
directly from `A`:

```text
job id = UUID(network | dataset | semantic epoch |
              A.blockNumber | A.blockHash | source state version)
```

The job's boundary block number, slot, time and hash all describe `A`. No
hash-less provisional job, provisional directory, provisional manifest,
adoption step, hard link or fallback copy is created.

Epoch stake and Ada pot retain their existing representations: a protected
immutable snapshot generation and inline atomic evidence respectively. Their
artifact references also use `A` as the producing coordinate. Deterministic
source and archive-job identities that distinguish canonical forks include the
full anchor identity; they must not remain keyed only by semantic epoch when a
same-height anchor hash can change.

### 3. Keep the reference on the current carrier block

Re-anchoring does not move artifact references backward in the outbox.

The outbox key remains `C`:

```text
putArtifact(writer, C.blockNumber, ref(anchor = A))
```

Both ingestion paths write the final source under `A`, record that it awaits
carrier block number `C`, and attach the reference from the actual
`BlockAppliedEvent(C)` contribution batch. Using this one thin attachment path
keeps the fetched/public-network transition ordering unchanged while removing
the ordering-dependent split in artifact publication.

Neither path calls `putArtifactDirect` against a future block. The actual
carrier event supplies the envelope's slot and hash. A slot predicted when the
transition ran is not compared with the slot at which a lazy producer
eventually mints `C`.

The existing projection-contributor decorator is retained only as a thin
carrier attachment mechanism. Its provisional hash-binding and source-adoption
responsibilities are removed.

Binding is also semantic-epoch aware. A currently applying block may carry an
outcome only when `slotToEpoch(C.slot) >= ref.semanticEpoch`. Equality is the
normal boundary case and `>` permits several skipped epochs to share one
carrier. This prevents a same-height replacement block that is still in epoch
`N` from consuming an epoch-`N+1` intent retained across rollback. Checking only
that the carrier descends from `A` is insufficient because that replacement and
the eventual boundary block can have the same predecessor.

Epoch stake and Ada pot stage a small durable intent through their existing
state-owned atomic writers. This keeps the intent atomic with the immutable
snapshot or inline evidence without creating an outbox reference for a future
block. The carrier contributor copies that intent to an artifact reference
keyed by `C`. The intent remains until acknowledgement so a carrier-only
rollback can attach it again. Their snapshot-retention and inline-evidence
contracts do not otherwise change.

### 4. Govern visibility and finality by the carrier

Artifact sealing, acknowledgement guards, projection-envelope ordering and the
archive finality gate use `C`, never `A`.

`ProjectionArtifactRef.producingBlockNumber` and `producingSlot` describe `A`.
Their existing names are retained to limit API churn, but their documentation is
amended to define them as the canonical coordinate that produced the epoch
facts, not the block whose outbox envelope transports the reference.

The carrier is deliberately not added to published artifact rows. It is an
outbox transport coordinate, not part of the epoch fact's source provenance.

### 5. Re-key completion and zero-row evidence by transition identity

Completion markers no longer use block number alone as their file identity. A
marker filename is keyed by semantic epoch and anchor block number, validates
the full anchor identity from its content, and records:

- previous and new epoch;
- `A` block number, slot and hash; and
- intended carrier block number `C`.

The full anchor is checked when a pending job is accepted as complete. Marker
cleanup waits until every staged part for that semantic epoch and anchor has
been acknowledged.

In-memory announced evidence and zero-row suppression use dataset, semantic
epoch and anchor identity. They do not use carrier block number alone. This lets
several skipped epochs share `A` and `C` without overwriting one completion
marker or suppressing the later epochs' zero-row artifacts.

The marker format is a clean break. Old markers are not migrated or interpreted
under the new semantics.

### 6. Separate gap provenance from gap transport

A selected dataset must still produce exactly one explicit outcome for every
eligible semantic epoch: complete evidence or a gap.

An `EpochArtifactGap` records `A` as the canonical point at which the missing
facts should have been produced. Its outbox insertion, artifact-set replacement,
sealing checks and visibility are associated with `C`.

Failure before carrier application records a small durable deferred outcome
containing the dataset, semantic epoch and intended carrier number. It is not a
published gap and carries no invented hash. The actual eligible carrier batch
resolves `A = C - 1`, writes the full `EpochArtifactGap`, and thereby supplies
both provenance and transport from canonical data. It is not sink-visible until
the outbox has acknowledged `C`; therefore a predicted number alone can never
publish a gap. Rollback below the expected predecessor deletes the intent,
while a carrier-only rollback retains it for the replacement transition. The
durable gap outcome replaces the dataset outcome for that epoch.

Paused-epoch checkpoints record their own carrier numbers. The drain filters
both point gaps and interval checkpoints through the acknowledged carrier, so
neither becomes visible early.

### 7. Roll back sources by anchor and references by carrier

For a staged source anchored at block `M`:

- rollback to `R >= M` retains the source and completion evidence;
- rollback to `R < M` discards them; and
- rollback of carrier `C` while retaining `M` deletes the old carrier reference
  but keeps the source available for a replacement carrier.

Outbox rollback continues deleting artifact references with their carrier
envelopes. Gap outcomes use their carrier association for visibility and their
anchor full point for canonical provenance and rollback checks.

This makes a carrier-only fork idempotent: byte-identical facts retain the same
source identity and are attached to the replacement first block. A fork that
changes the end-of-epoch anchor changes the source identity, even when a
particular dataset's resulting bytes happen to be equal.

### 8. Isolate archive failures from canonical L1 application

Archive correctness checks remain strict, but their exceptions do not own L1
availability.

Artifact readers run on the asynchronous drain thread. A non-canonical anchor,
checksum error or sink failure aborts that drain attempt, increments
`yano.history.projection.drain.failures`, updates `lastDrainFailure`, and is
retried. It does not propagate to block application.

Canonical projection contribution shares the UTXO write batch to retain its
atomicity guarantee. The runtime establishes a write-batch savepoint before the
contributor runs. If any archive contribution fails, it rolls the batch back to
that savepoint, writes a bounded durable capture-failure marker containing the
failed block's full canonical point, and commits the unaffected UTXO/L1 writes.
The consumer reports `PAUSED` at that marker instead of treating the missing
envelope as idle. Status, metrics and logs identify the failed block. Rollback
compares the marker's slot and hash with the retained point, so it removes the
marker only with its discarded fork even though the failed block has no outbox
envelope.
This prevents both outcomes that are unacceptable: wedging sync, and committing
a partially written archive envelope.

Epoch-stake and Ada-pot transition hooks similarly buffer their archive writes
until capture succeeds. Anchor resolution or intent construction failure omits
only those buffered archive writes; the ledger transition continues. Both
synchronous paths increment `yano.history.projection.capture.failures`, update
`lastCaptureFailure`, and emit an error log with the carrier coordinate. Failure
reporting itself is best-effort and cannot escape back into ledger or UTXO
application.

These counters describe data loss or delayed archive progress and therefore
require operator alerting. They do not replace the intentional disk-safety
ingest gate: configured archive disk exhaustion may still pause ingestion to
avoid destroying retained evidence.

### 9. Make a clean identity and row-semantics break

The archive feature is unreleased and has no compatibility requirement for its
existing epoch-artifact identity. This change provides no migration, dual read,
legacy-marker interpretation or projection-version negotiation. Existing epoch
artifact archives must be rebuilt or replayed.

The published columns:

```text
boundary_block_hash
boundary_block_number
boundary_slot
boundary_block_time
```

now mean "the canonical block that ends the artifact's source epoch" rather
than "the first block of the following epoch". This is a deliberate schema
semantic change across all five epoch datasets, not an incidental consequence
of producer recovery.

## Why this does not use a rejected placeholder

The earlier ADR rejected using a parent, placeholder or predicted hash while
continuing to claim that the artifact was anchored to the first block of the new
epoch. That would have stored false canonical identity.

This decision does something different: it changes the canonical definition of
the artifact anchor. The last block of the source epoch is not substituted for
an unknown future value. It is the authoritative full point whose resulting
ledger state determines the epoch facts. No field claims to identify the future
carrier.

## Durability and recovery

The existing final staged-file protocol remains authoritative: rows are forced
and published before their manifest, carry row count, byte count and SHA-256
checksum, and are released only after a verified sink receipt.

Relevant crash points behave as follows:

- **after final source commit, before carrier:** the source and completion
  marker remain discoverable and are attached when `C` is actually applied;
- **after carrier attachment, before sink acknowledgement:** the outbox
  reference and source replay through the existing receipt protocol;
- **after sink acknowledgement, before source cleanup:** acknowledgement is
  idempotently replayed;
- **after a carrier rollback:** the reference is removed with its envelope, but
  a still-canonical anchor retains the source for the replacement carrier; and
- **after an anchor rollback:** anchor-keyed source and completion evidence are
  discarded.

A producer restart does not need the transition to be republished. Carrier
attachment discovers staged-file sources from durable completion evidence and
direct artifacts from durable transition intents. This covers the case where
ledger boundary phases are already durable and skip recomputation.

## Scope boundary

This decision does not change:

- ledger epoch-transition ordering;
- block construction or protocol-parameter selection;
- archive table shapes or column names;
- staged row codecs and checksums;
- epoch-stake snapshot retention;
- Ada-pot inline evidence;
- DuckLake receipt and generation behavior;
- block projection sections or canonical envelope identity; or
- the archive safety-window calculation.

Fetched/public-network block ingestion and local block production remain
unchanged. Both now use the same thin carrier attachment for already-final
epoch evidence.

Independent PR #111 changes retained alongside this decision are:

- cooperative projection-drain shutdown, which avoids interrupting an in-flight
  DuckDB commit; and
- local-network projection genesis identity that excludes mutable
  `systemStart` while protecting every other genesis field. The rule is keyed
  by genesis network magic, not current producer/dev-mode flags, so disabling a
  producer on restart cannot change an existing archive identity and a public
  network run with dev mode enabled keeps its full genesis identity; and
- the PR #110 pointer-index readiness rules, including exact canonical-marker
  validation. Rollback to origin preserves an existing genesis proof (block and
  slot zero with its real genesis hash), so a restart does not reject a valid
  genesis index; a non-genesis or otherwise unusable proof is still cleared.

## Rejected alternatives

### Retain the provisional two-phase source lifecycle

Rejected because the final anchor is already known. A second file format,
manifest lifecycle, adoption operation and interrupted-bind recovery provide no
correctness benefit once the artifact is anchored to `A`.

### Keep the first block of the new epoch as identity

Rejected because it binds facts to a block whose transactions did not produce
them, makes producer identity unavailable during calculation, and changes
identity across a carrier-only fork.

### Write a parent or zero hash while claiming the first-block anchor

Rejected because it records false canonical provenance. Re-anchoring to `A` is
not this substitution.

### Call `putArtifactDirect` or make a gap visible under predicted `C`

Rejected because a rollback or shutdown between transition processing and block
minting can leave an outcome attached to a block that never became canonical.
A durable gap intent may remember the intended number, but acknowledgement of
the actual carrier is required before publication.

### Move the reference back to anchor block `A`

Rejected because the anchor's artifact set may already be sealed or
acknowledged, rollback would no longer remove the reference with the transition
carrier, and finality would be evaluated one block too early.

### Reorder local transition processing after block construction

Rejected because block construction needs effective parameters established by
the new epoch transition.

### Extend every transition event with its predecessor full point

Deferred. It would make the anchor explicit at the runtime boundary, but it
expands the change through event records, account-state APIs, recovery metadata
and tests. The exact canonical resolver is sufficient for eligible Shelley+
epochs and keeps the implementation smaller. If the adjacency invariant cannot
be proven for a future chain mode, the event contract must be extended rather
than weakening anchor validation.

## Consequences

Positive:

- producer and fetched paths create the same final source identity during the
  transition;
- carrier-only forks reuse byte-identical durable evidence;
- provisional source types, directories, manifests, adoption and bind recovery
  are deleted;
- lazy producer slot drift cannot invalidate artifact identity;
- rollback behavior follows the coordinate that actually determines the facts;
  and
- public-network block ingestion remains unchanged and shares the attachment path.

Tradeoffs:

- anchor and carrier must be named separately throughout staging, gap and
  outbox code;
- completion markers require a breaking format change;
- direct epoch-stake and Ada-pot identities require an audit so they do not
  remain epoch-only across same-height anchor forks;
- deferred producer gaps need a small durable carrier intent even though
  provisional fact files disappear; and
- existing epoch-artifact archives require a fresh replay.

## Verification gates

The change is accepted only when all of the following pass:

1. **Anchor resolution**
   - fetched `C` resolves canonical `A = C - 1`;
   - producer mode resolves `A` from the retained local tip while `C` is absent;
   - missing, non-adjacent or hash-less anchors fail archive capture without
     stopping canonical L1 application; and
   - skipped Byron-source boundaries never attempt Shelley artifact resolution.

2. **Identity and row semantics**
   - the same `A` with different carrier slot/hash produces the same source
     identity;
   - a different `A.hash` produces a different identity;
   - all five datasets publish `A` in their boundary columns; and
   - outbox reads find each reference under `C`, not `A`.

3. **Completion and skipped epochs**
   - several semantic epochs sharing `A` and `C` retain distinct completion
     markers;
   - every healthy dataset receives explicit non-empty or zero-row evidence for
     every eligible semantic epoch; and
   - incomplete evidence becomes a gap rather than being silently skipped.

4. **Producer carrier recovery**
   - lazy transition at slot `S` followed by minting at `S+k` succeeds;
   - restart after transition but before carrier attaches the retained final
     source;
   - failed carrier application and same-height replacement are idempotent; and
   - a same-height replacement still in the source epoch cannot bind a future
     semantic epoch, while skipped epochs may share an eligible carrier; and
   - no artifact reference or sink-visible gap is published under a predicted
    future block.

5. **Rollback**
   - rollback to `R >= A` retains final source evidence;
   - rollback to `R < A` discards it;
   - carrier-only rollback removes the old reference and permits reattachment;
     and
   - finality and visibility remain governed by `C`.

6. **Packaged validation**
   - JVM and native devnet runs complete all five datasets with zero gaps,
     pending artifacts or staging failures;
   - clean JVM and native shutdown/restart does not interrupt sink work;
   - lazy production, skipped epochs and rollback/replacement are exercised;
     and
   - fresh preprod validation crosses Byron and at least one captured Shelley+
     boundary with identical JVM/native rows and coverage.

7. **Failure isolation and observability**
   - a contributor that writes one archive record and then throws leaves no
     partial archive record, while the same Shelley+ or Byron L1 block commits
     with a durable capture-failure marker;
   - direct epoch-stake or Ada-pot anchor failure does not abort its ledger
     transition;
   - reader anchor mismatch increments the drain-failure metric and remains
     retryable; and
   - synchronous capture failures increment the capture-failure metric and
     appear in status and error logs with their carrier coordinate; and
   - restart at a capture hole reports the consumer as paused rather than idle,
     and rollback of that fork removes the durable marker.

The earlier manual devnet and retained-preprod runs recorded by PR #111
validated the provisional implementation and old boundary-column semantics, so
their evidence does not transfer to this design.

Fresh validation on 2026-09-02 exercised the completed-epoch anchor design:

- the JVM devnet run crossed epochs 0 through 2 with all five datasets active,
  no gaps or capture/drain failures, and successful graceful and forced-crash
  restarts;
- the JVM preprod run started from origin, crossed Byron into Shelley through
  epoch 11, retained zero gaps and capture/drain failures, and restored the same
  epoch nonce after graceful and forced-crash restarts;
- the GraalVM 25.3.4.1 native smoke completed all five datasets through epoch 2
  with finality convergence and no forbidden archive failures; and
- the native preprod run started from origin, crossed Byron into Shelley, then
  recovered from a forced crash at body block 243367. Startup restored nonce
  state at that exact body tip and resumed through epoch 33 with zero archive
  capture failures, drain failures, or gaps before a clean shutdown.

These packaged runs supplement, but do not replace, the deterministic tests for
carrier replacement, skipped epochs, anchor mismatch, failure markers, and
rollback semantics listed above. Exact cross-runtime row equality remains a
release-validation gate; the separate fresh JVM and native network runs prove
their operational paths but were not stopped at one identical canonical tip.
