# ADR-053: Defer Canonical Binding for Locally Produced Epoch Artifacts

## Status

Proposed

## Date

2026-09-01

## Context

Issue #105 requires projection history to work in a native image as well as on
the JVM. Native reachability is no longer the only gap: the devnet smoke test
also exposes different epoch-boundary ordering between fetched and locally
produced blocks.

```text
public-network sync: store canonical block -> epoch transition -> BlockApplied
local production:    epoch transition -> build/store block -> BlockApplied
```

The public-network path can construct its final `EpochArchiveJob` during the
transition because `ChainQuery` already returns the boundary slot and hash.
This is the path used by the successful mainnet and preprod archive runs.

The local path cannot know the boundary hash during the transition. The block
does not exist yet because the transition determines the protocol parameters
used to build it. Reordering local production would risk building the block
with parameters from the previous epoch. Substituting the previous hash or a
zero hash would create false canonical identity.

The existing behavior therefore pauses reward, DRep distribution and
governance artifact capture on devnet with `canonical boundary is unavailable`.
Epoch stake and Ada pots are unaffected because their contributor uses the
canonical block batch directly.

Local producer startup also replaces the configured Shelley `systemStart` on
the first boot. That established behavior happens after projection history is
initialized. An archive identity made from the complete file would therefore
describe the pre-launch file on the first boot and reject the same devnet after
its normal restart.

## Decision

Preserve the existing fetched-block lifecycle. Add one conditional bridge for
the local-producer ordering:

1. `EpochArchiveStagingService.writer` first asks for the canonical boundary.
2. When it is present, the service follows the existing final-job, durable-file
   and direct-outbox path without provisional state.
3. Only when it is absent, the service writes a `ProvisionalEpochArchiveJob`.
   The record contains the network, dataset, semantic epoch, intended block
   number, candidate slot, source version and part, but deliberately no hash.
4. `completeBoundary` writes the existing durable completion marker after all
   successful and zero-row local parts are durable.
5. The application decorates the existing `CanonicalProjectionContributor`.
   During `BlockAppliedEvent` contribution, after the collector has rejected
   any conflicting existing block identity, it binds completed provisional
   parts to the event's actual block number, slot and hash.
6. Each resulting artifact reference is staged through
   `ProjectionOutboxStore.putArtifact(writer, ...)` in the same RocksDB batch as
   the block's UTXO state, projection identity and sections.
7. Sink acknowledgement removes both the final manifest and its provisional
   source. Rollback removes provisional and bound files above the retained
   point.
8. For a local dev-mode block producer only, derive the archive's Shelley
   identity with the root `systemStart` field removed. The producer owns that
   mutable launch value. Every other genesis field remains identity-protected.
   Public networks and explicitly configured genesis hashes retain their exact
   existing identity behavior.

The final job ID uses the same deterministic inputs as the existing path. The
sink sees the same final staged-file representation, row codec, checksums,
projection version and receipt protocol regardless of how the block arrived.

## Durability and recovery

Provisional rows are published before their manifest and carry row count, byte
count and SHA-256 checksum. They are not discoverable by the archive consumer
and cannot be included in a receipt.

Binding publishes a small durable intent, then gives the same rows their final
job name. It uses a hard link where supported, with a streaming file copy as a
portable fallback. The provisional name remains until sink acknowledgement, so
the following failures are replayable without recomputing epoch facts:

- process death after provisional commit;
- process death after binding intent;
- process death after final rows but before final manifest;
- failure of the canonical RocksDB batch after final manifest publication; and
- replay of the same canonical block.

The final artifact reference and canonical block commit together. If their
batch fails, neither becomes visible in RocksDB and the next block application
stages the same deterministic reference again.

## Scope boundary

This decision does not change:

- fetched mainnet or preprod epoch capture;
- archive tables, row schemas or codecs;
- public-network projection identity or versions;
- finality, rollback or receipt semantics;
- DuckLake behavior or query APIs;
- ledger epoch-transition order; or
- block construction and protocol-parameter selection.

The provisional type lives in `archive-core` because durable epoch files are
owned there, but it is activated only by the application when the canonical
lookup is empty. It is an additive source state, not a second archive backend
or a new public-network ingestion architecture.

## Rejected alternatives

### Reorder local epoch transition after block construction

Rejected because block construction needs the new epoch's effective protocol
parameters.

### Use a placeholder, parent or predicted hash

Rejected because provisional facts would claim an incorrect canonical anchor
and could not be distinguished safely across forks.

### Publish the reference after `BlockAppliedEvent`

Rejected because a separate write could race the sink or survive without the
canonical block state. The existing contributor batch is the correct atomic
boundary.

### Replace the fetched path with provisional capture

Rejected because it adds work and recovery state to the proven public-network
path without solving a public-network problem.

## Verification gates

The change is acceptable only when all of these pass:

- archive-core restart, interrupted-bind, integrity and rollback tests;
- application tests proving the canonical-present path remains direct and the
  canonical-absent path remains invisible until binding;
- JVM and native devnet runs completing all five epoch artifact datasets with
  no active staging failure;
- clean JVM and native shutdown/restart with pending history work; and
- retained preprod JVM and native recovery with unchanged coverage, no archive
  gaps and no startup recovery error.

If the retained public-network validation changes archive rows, coverage or
recovery behavior, this decision is not accepted.
