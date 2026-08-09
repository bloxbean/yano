# ADR app-layer/028: Historical L1 ledger-state attestation chain

## Protocol parameters, stake, and governance

**Status:** Accepted with authenticated-snapshot amendment — M1–M7 direct-state baseline implemented;
M8 reopened for the reusable snapshot capability, Cardano History adoption, and preprod evidence
**Date:** 2026-08-09
**Depends on:** ADR app-layer/027 §2.4 (deep-rollback detection) — implementation and devnet work may
proceed, but this is **blocking for every production/preprod epoch attestation**, including M3 and
M5–M6
**Extends:** ADR app-layer/008.4 §3 (L1View / observations), ADR-025.x (authenticated map),
ADR app-layer/031 (reusable state-machine capabilities)
**Productized by:** ADR app-layer/035 (Cardano History plugin, console, client/CLI, and distribution)
**Supersedes nothing.**

**Preview reset:** ADR app-layer/031 replaces the preview apply overloads with one
`AppStateMachine.apply(AppBlockExecutionContext, ...)` path for typed, position-preserving access to
L1 observations already sequenced in an app block. It does **not** expose the ephemeral
`L1EpochState` to app-state execution; replay still uses observation bytes retained in block history.
No previous app-chain observation wire, state, proof, plugin, or anchor format is migrated.

---

## 1. Context

Cardano does not retain historical ledger state. Protocol parameters for epoch 400, the
stake distribution for epoch 480, a proposal's lifecycle status, and the DRep distribution at a
past epoch are not recoverable from the chain; they exist only in whatever indexer happened to
record them. Today, answering *"what was `minPoolCost` in epoch 400?"*, *"what was stake
credential X's active stake in epoch 480?"*, *"was proposal P ratified in epoch E?"*, or *"what
was DRep D's voting stake in epoch E?"* means **trusting an indexer operator**. There is no proof,
and no way for a third party to check.

This ADR specifies an app chain whose members each derive these facts **independently from
their own Cardano ledger state**, agree by threshold, and commit them to an authenticated map
whose root is anchored on L1. A consumer can then verify a historical value with an inclusion
proof against an on-chain anchor, trusting nobody.

ADR-027 §4 established that this is tractable, and why. This ADR is the implementation plan.

## 2. Why the existing mechanism cannot do it

Recapped from ADR-027 §4.1, verified in source:

1. **`L1Observer` is block-scoped** — `observe(long slot, byte[] blockHash, Block block)`.
   Epoch parameters are the accumulated result of enactment *at the boundary*; the stake
   snapshot derives from the whole UTxO + delegation state. Neither is a function of a block.
2. **`AppStateMachineContext` has no live L1 handle**, and correctly must not — `apply()` is
   contractually a pure function of block-bound inputs and committed state. ADR-031's separate
   `AppBlockExecutionContext` exposes only observations already encoded in that block, not the
   observer's ledger-state handle.
3. **`L1View` was designed in ADR-008.4 §3.1 and never shipped** (`grep` finds nothing), and
   even as designed it is pointer-based, so it would not answer this question either.

## 3. Why this case *is* tractable

Epoch-boundary state has three properties arbitrary "state at slot S" lacks:

- **Canonical.** Every correct Cardano node computes the identical value — that is what L1
  consensus means. So "every member recomputes and compares", the model already implemented
  for `L1Observer`, applies unchanged. **No new trust assumption is introduced.**
- **Low cadence.** One value per epoch (5 days). A k-deep (2160-block) stability wait costs
  ~12 hours on a 5-day-old fact — affordable in a way it never is for a bridge deposit.
- **Already computed.** `EpochParamProvider` (core-api) exposes per-epoch parameters;
  `ledger-state` computes epoch stake snapshots, DRep distributions, and governance ratification;
  and the node already fires
  `PreEpochTransitionEvent` → `EpochTransitionEvent` (SNAP) → `PostEpochTransitionEvent`,
  all before the first `BlockAppliedEvent` of the new epoch.

## 4. Goals / non-goals

**Goals**

| # | Goal |
|---|---|
| G1 | Attest per-epoch protocol parameters as threshold-agreed, provable L2 state |
| G2 | Attest per-epoch stake distribution (credential → coin, pool) likewise |
| G3 | Attest proposal lifecycle status and per-epoch DRep voting-stake distribution likewise |
| G4 | Inclusion proofs verifiable **off-chain by anyone**, and on-chain where the profile allows |
| G5 | **No new trust**: every member re-derives; the proposer supplies but cannot lie |
| G6 | **Replay-safe forever**: a node joining in 2030 rebuilds epoch 500 with no L1 history |
| G7 | Provide an opt-in authenticated-snapshot capability reusable by any state machine for large immutable/checkpoint datasets, without changing chains that do not select it |
| G8 | Permit node-local online retention, archival, restore, and eviction without changing consensus or preventing another node from retaining a full online copy |

**Non-goals**

| # | Non-goal |
|---|---|
| NG1 | Arbitrary "UTxO set at address A as of slot S" — still deferred (ADR-008.4 §3.1) |
| NG2 | Negative facts outside epoch-boundary data |
| NG3 | A general DBSync replacement — this attests a pinned, small set of epoch facts |
| NG4 | Mutating history — corrections **supersede**, never overwrite (§8.3) |
| NG5 | Automatically snapshotting an arbitrary state machine with no canonical adapter/coverage declaration |
| NG6 | Consensus enforcement that every member keeps the same snapshots physically online or uses the same archive provider |

## 5. Design

### 5.1 New SPI — `L1EpochObserver`

Mirrors `L1ObserverProvider` exactly (ServiceLoader, `observers.<id>.*` config, plugin jar).

```java
package com.bloxbean.cardano.yano.api.appchain.l1view;

/**
 * An epoch-boundary observer: a PURE function from a finalized L1 epoch
 * boundary to a bounded manifest and canonical observation stream. Same
 * determinism contract as {@link L1Observer}; same follower-verification
 * model (consensus-critical, fail-closed).
 */
public interface L1EpochObserver {
    String observerId();

    EpochObservationManifest prepare(
            L1EpochBoundary boundary,
            L1EpochState state);

    void writeObservations(
            EpochObservationManifest manifest,
            L1EpochState state,
            L1EpochObservationSink sink);

    default Map<String, Object> status() { return Map.of(); }
}

public record L1EpochBoundary(long previousEpoch,
                              long newEpoch,
                              long boundarySlot,
                              byte[] boundaryBlockHash,
                              long boundaryBlockNumber) {}
```

`L1EpochState` is a **narrow, read-only, epoch-pinned** handle, valid only for the dynamic
extent of the callback and not retainable — the same discipline
`AppStateMachine.query(path, params, AppQueryContext)` already imposes:

```java
public interface L1EpochState {
    long previousEpoch();

    long newEpoch();

    /** Canonical, epoch-pinned parameters. Rationals, never floats (§7 D4). */
    ProtocolParamsView protocolParams(long effectiveEpoch);

    boolean hasStakeSnapshot(long snapshotEpoch);

    /**
     * Iterate the stake snapshot in CANONICAL ORDER — ascending by
     * (credType, credHash) bytes. Ordering is the HOST's guarantee, not the
     * plugin's: this is what removes the HashMap hazard (§7 D1).
     */
    void forEachStakeEntry(long snapshotEpoch, StakeEntryConsumer consumer);

    boolean hasProposalStatusSnapshot(long snapshotEpoch);

    boolean hasDRepDistributionSnapshot(long snapshotEpoch);

    /**
     * Iterate proposal lifecycle records in canonical order by
     * (transactionId bytes, governanceActionIndex). The host persists these
     * epoch-pinned records before exposing the snapshot; the observer must not
     * reconstruct history from the current active-proposal set.
     */
    void forEachProposalStatus(long snapshotEpoch, ProposalStatusConsumer consumer);

    /**
     * Iterate DRep voting stake in canonical order by (drepType, drepHash).
     * V1 exposes persisted key/script credential DReps. Virtual tally buckets
     * are excluded and that exclusion is committed in the dataset semantics.
     */
    void forEachDRepDistributionEntry(long snapshotEpoch,
                                      DRepDistributionConsumer consumer);

    @FunctionalInterface
    interface StakeEntryConsumer {
        void accept(int credType, byte[] credHash, BigInteger coin, byte[] poolHash);
    }

    @FunctionalInterface
    interface ProposalStatusConsumer {
        void accept(byte[] transactionId, int governanceActionIndex,
                    GovernanceActionType actionType, GovernanceProposalStatus status,
                    GovernanceProposalStatusReason reason, long proposedEpoch,
                    long expiresAfterEpoch);
    }

    @FunctionalInterface
    interface DRepDistributionConsumer {
        void accept(int drepType, byte[] drepHash, BigInteger coin);
    }
}
```

The boundary is identified by the **first applied L1 block of `newEpoch`**. For a transition
`E -> E+1`, `boundaryBlockHash`, `boundarySlot`, and `boundaryBlockNumber` therefore identify the
first block in `E+1`; protocol parameters are read at effective epoch `E+1`; the stake snapshot is
read at end-of-epoch label `E`; and the DRep distribution and post-boundary proposal lifecycle are
read at epoch `E+1`. Every manifest carries both boundary epochs and its own explicit dataset epoch.
No consumer is allowed to infer one label from another.

The first block itself is not part of any boundary dataset. In particular, governance actions in
that block must not leak into the `E -> E+1` proposal-status snapshot. The host exposes only the
immutable epoch-pinned records committed by boundary processing.

`prepare` performs the deterministic first pass that fixes snapshot semantics, total entries,
chunk-entry count, chunk count, and snapshot root. `writeObservations` performs a second canonical
pass and streams the manifest/chunks into a host-owned sink. It must not return a
`List<L1Observation>`: mainnet epoch stake is tens of megabytes, and materializing or injecting the
whole list is unnecessary preview debt.

The host sink writes each bounded observation into a durable local epoch-observation spool keyed by
`(observerId, previousEpoch, newEpoch, datasetEpoch, manifestRoot, chunkIndex)`. The callback may not
retain the state or sink.

Making ordering the host's contract rather than the plugin's is deliberate: today some ledger
views are exposed as maps, and a plugin author who iterates one directly can produce
nondeterministic chunking that stalls the chain. The SPI must make the wrong thing impossible.

`GovernanceProposalStatus` is a closed wire enum with at least `ACTIVE`, `RATIFIED`, `ENACTED`,
`EXPIRED`, and `DROPPED`; a dropped record also carries a canonical reason in the concrete claim
codec. The current read surface is insufficient for this contract: it exposes active/pending
proposals, while enactment and expiry remove records, and `EpochSnapshotExporter` receives
ratification results only transiently. The ledger-state implementation must therefore add a
rollback-safe, epoch-pinned proposal lifecycle history. Every write follows the account-state
delta rules so rollback and replay cannot duplicate or lose a status transition. DRep distribution
is already persisted by epoch, but its host adapter must provide the canonical ordered iterator.

### 5.2 Wire format — tagged `L1Observation` baseline

`L1Observation` v1 is `[1, observer-id, tx-hash, slot, block-hash, claim]` and its constructor
**requires a 32-byte `txHash`**. An epoch fact points at no transaction.

**Decision: replace the preview transaction-only format with a tagged anchor:**

```
l1-observation-v1 = [1, observer-id, anchor, slot, block-hash, claim]
anchor = [0, tx-hash]      ; transaction pointer
       / [1, new-epoch]    ; first-block boundary pointer for previous -> new
```

- `decode()` accepts only the tagged supported format. The transaction-only preview format is
  deleted rather than retained as a second wire path.
- `key()` becomes `observerId + '/' + anchorKey + '/' + slot`, where `anchorKey` is
  `tx:<hex>` or `epoch:<n>`.
- The storage/app-chain format marker and plugin API version reject old observation bytes before
  execution.
- *Rejected alternative:* set `txHash = sha256("yano-epoch-anchor" ‖ chainId ‖ epoch)`. It produces
  a value that looks like a transaction hash and is not one.

### 5.3 Runtime wiring and asynchronous preparation

- Reuse `AppChainSubsystem`'s existing `BlockAppliedEvent` subscription. Detect the first applied
  block of a new epoch and use that existing event's slot, block number, and block hash as the
  boundary identity. Do **not** change `PostEpochTransitionEvent`, the L1 block-application event
  publisher, or the order of existing ledger-state boundary processing merely to support this ADR.
- The event callback performs only non-blocking, in-memory bookkeeping and a coalescing wake-up of a
  dedicated epoch-observation coordinator. It takes no blocking lock, performs no durable I/O, and
  must not scan stake, calculate roots, encode chunks, or inject messages on the `BlockAppliedEvent`
  publisher thread. A full wake-up queue is coalesced rather than blocking; reconciliation makes the
  event lossless.
- The coordinator opens its own consistent, epoch-pinned read snapshot and performs both observer
  passes asynchronously. The callback never hands a live or retainable ledger object to plugin code.
- Treat the event as a wake-up signal, not the sole source of work. On start and after failure, the
  coordinator reconciles completed ledger boundary markers, first-block identities, spool
  manifests, and finalized app-chain receipts and recreates every missing job within the configured
  source-retention horizon.
- Replace the in-memory all-ready `pendingInjection` behavior for epoch data with a durable,
  byte-bounded observation spool/outbox. All members build the same spool for verification; the
  scheduled proposer injects only the configured message/byte/state-operation budget per app block.
- Spool records follow the idempotent lifecycle `GENERATING -> READY -> OFFERED -> FINALIZED`.
  Process death in either generation pass, after offer but before acknowledgement, or during
  proposer rotation resumes from durable manifest/chunk/receipt identity without duplicating or
  skipping data. Non-proposers retain their records for verification and never drain-and-discard.
- Reuse `L1ObservationService` verification/window semantics, but change `drainInjectable()` into a
  bounded poll/ack protocol. Do not preserve the preview drain-all API. Generation failure,
  unavailable pinned input, or spool corruption marks the observer unhealthy and prevents the
  affected chain member from voting; it must never silently omit an attestation.
- `verifyProposalObservations` and `verifyCatchUpObservations` consume the same canonical spool
  records and remain fail-closed for live proposals. Historical catch-up may accept an observation
  outside the local L1 window only through the existing finality-certificate trust path.

This keeps L1-side changes narrow: new read-only, canonical epoch iterators and rollback-safe
proposal-history persistence are required, but existing block application, epoch-event records, and
consensus processing remain unchanged.

### 5.4 Stability gating

New framework key `yano.app-chain.l1.epoch-stability-depth` (default: falls back to
`l1.stability-depth`). It is parsed into `AppChainConfig`; it is not a plugin setting.

- Depth is measured in **applied L1 blocks**, never slots. The runtime selects the existing
  depth-confirmed stable L1 point and permits injection once that selected point has reached or
  passed the boundary block in canonical application order. No code may add a block depth to a slot
  number.
- **Recommended value = the network security parameter k** (2160 on mainnet/preprod). At a
  5-day cadence this costs ~12 hours of latency and removes the probabilistic-reorg exposure
  that ADR-027 §2.3 flags for the bridge.
- The engine's existing `observation.slot() <= block.l1Slot()` invariant still applies and is
  enforced unchanged.

### 5.5 State machines

The state machines consume accepted observations through ADR-031's block execution context rather
than reopening live L1 ledger state or independently decoding reserved envelopes. The observer's
`L1EpochState` handle exists only during observation generation. The execution context carries the
decoded, sequenced `L1Observation` plus its original message position/id, so block ordering, replay,
and composite routing remain deterministic. All first-party consumers move to this path in the
clean-break change; manual reserved-message decoding is removed rather than retained as a legacy
path.

Three independently selectable components, composable into any ADR-031 `AppStateMachine`.

**`epoch-params`** — small and self-contained.

| Aspect | Value |
|---|---|
| Topic | `~l1/<params-observer-id>` |
| Claim | `[epoch, paramsCanonicalCbor]` |
| State | `params/<epoch>` → canonical CBOR; `params/latest` → epoch |
| Size | a few hundred bytes; **one message per epoch** |

**`epoch-stake`** — chunked.

| Aspect | Value |
|---|---|
| Topic | `~l1/<stake-observer-id>` |
| Manifest claim | `[epoch, snapshotSemantics, totalEntries, chunkEntries, chunkCount, snapshotRoot]` |
| Chunk claim | `[epoch, snapshotRoot, chunkIndex, entries]`, entries = `[[credType, credHash, coin, poolHash], …]` in canonical order |
| Direct-state profile | `stake/<epoch>/<credType><credHash>` → `[coin, poolHash]`; retained as the capability-disabled baseline |
| Snapshot profile | primary state stores `snapshots/stake/<sequence>` → `SnapshotDescriptorV1`; the logical per-epoch snapshot stores `<credType><credHash>` → `[coin, poolHash]` |
| Completeness | the epoch is **not** marked complete until all `chunkCount` chunks are applied, the source commitment equals the manifest `snapshotRoot`, every member derives the same secondary authenticated root, and the descriptor commits atomically |

`poolHash` is mandatory in the v1 value and is the credential's 28-byte delegation target for this
active-stake snapshot. Coin and pool are deliberately one authenticated record, not separate keys.
An application validator verifies the leaf once, decodes `[coin, poolHash]`, and may enforce only
`coin >= minimum`, only `poolHash == expectedPool`, both predicates, or exact equality. The proof
still carries the complete value, but the claimant need not know the pool hash in advance when the
application predicate concerns only coin. One record avoids doubled trie entries and makes it
impossible to prove coin and pool from inconsistent state roots.

The current ~1.3M-mainnet-credential estimate plus the mandatory pool hash is expected to encode
roughly **90–120 MB/epoch before trie overhead**; M4 records the actual canonical-CBOR size rather
than retaining the earlier 50-byte estimate. The initial benchmark profile uses **25,000 entries per
chunk**, normally below 2 MiB of claim data, and at most one stake/DRep chunk per app block. The
bounded outbox spreads chunks across blocks so an epoch never becomes one pool/memory burst.

The normative limit applies to the whole app block, not merely one chunk:

```text
stake/DRep entry mutations + metadata + receipts + composite/framework mutations <= 32,768
```

The history component rejects a block that exceeds its committed ingestion quota. `chunk-entries`
is consensus/genesis committed, fixed for the chain generation, and must leave explicit headroom
below `TransitionPlan.MAX_STATE_OPERATIONS`.

Queries and typed proofs must reject an incomplete epoch. In the snapshot profile, completeness is
part of the primary `SnapshotDescriptorV1`; the secondary tree is immutable after sealing. A positive
or absence assertion proves the descriptor from primary state and the credential leaf/absence from
the descriptor's secondary root. In the direct-state profile, the existing same-root leaf plus
`stake/<epoch>/meta.complete` proof remains unchanged.

**`epoch-governance`** — proposal lifecycle plus chunked DRep distribution.

| Aspect | Value |
|---|---|
| Topic | `~l1/<governance-observer-id>` |
| Proposal claim | `[epoch, txHash, govActionIndex, actionType, status, reason, proposedEpoch, expiresAfterEpoch]` |
| DRep manifest | `[epoch, totalEntries, chunkEntries, chunkCount, distributionRoot]` |
| DRep chunk | `[epoch, distributionRoot, chunkIndex, entries]`, entries = `[[drepType, drepHash, coin], ...]` in canonical order |
| Proposal state | `governance/<epoch>/proposals/<txHash>/<govActionIndex>` → canonical lifecycle record |
| Direct DRep profile | `governance/<epoch>/dreps/<drepType>/<drepHash>` → coin |
| Snapshot DRep profile | primary descriptor under `snapshots/dreps/<sequence>`; logical per-epoch snapshot maps `<drepType><drepHash>` → coin |
| Metadata | proposal completeness remains in primary state; DRep completeness is either direct metadata or the sealed snapshot descriptor |

Proposal and DRep completeness are independent so a deployment can attest proposal status without
paying the DRep-distribution cost. Configuration flags `include-proposals` and
`include-drep-distribution` are consensus-critical and committed in genesis. A proposal-status
snapshot contains one record for every proposal known at that boundary; terminal status is carried
forward so `status(P, E)` has direct point-proof semantics. A proposal-status proof names the epoch
explicitly and is valid only alongside the same-root proposal completeness proof; it never infers
a historical outcome from a current active set. A DRep amount or absence proof is likewise valid
only alongside the same-root DRep completeness proof.

### 5.6 Who populates what (the three-party split)

| Role | Who | What |
|---|---|---|
| **Supplier** | the scheduled **proposer's** epoch observer | reads its own `L1EpochState`, emits canonical chunks |
| **Verifier** | **every member**, independently | recomputes the same chunk from its own ledger state; MISMATCH → reject proposal |
| **Populator** | the **state machine**, in `apply()` | direct profile uses `writer.put(...)`; snapshot profile uses the transaction-scoped snapshot writer and commits only the sealed descriptor to primary state |

The proposer supplies the data but **cannot lie about it**, because the fact is canonical and
every member re-derives it. Identical trust model to the EUTxO bridge deposit; only the
payload is bigger.

### 5.7 Authenticated storage decision

The mainnet-scale M4 measurements reject direct append-only MPF storage as the scalable Cardano
History default: one 1.3M-entry epoch used 3.84 GB before reachability GC and 3.19 GB after GC and
compaction. The remaining entries are reachable from the latest root, so primary-root pruning cannot
remove them. Direct authenticated state remains the exact behavior when the new capability is not
selected; it is useful for small datasets and as a differential-test baseline.

**Decision: add an opt-in, reusable `authenticated-snapshots-v1` cross-cutting capability.** It is
defined in `core-api`, implemented by `runtime`, namespaced by the ADR-031 composition layer, and may
be selected by any stock or custom `AppStateMachine` that supplies deterministic snapshot semantics.
Cardano History is its first consumer. Enabling the capability does not redirect arbitrary writes:
only components that declare a snapshot series use the secondary store; all other state remains in
the primary authenticated backend.

#### 5.7.1 Primary state and logical snapshot state

The capability has two authenticated levels:

1. the existing primary app-chain state stores permanent, small `SnapshotDescriptorV1` records; and
2. a shared online snapshot store contains many independently rooted logical MPF/JMT snapshots.

The online snapshots use fixed secondary node/root/build/lifecycle column families in the chain
generation's existing `AppLedgerStore` RocksDB, with a separately budgeted block cache. A snapshot is
logical—not a RocksDB instance, column family, or directory per epoch. Snapshot node/catalog
mutations and the primary receipt/descriptor/head are staged in the same finalization `WriteBatch`,
so a finalized primary record can never reference unpublished local nodes. Proof generation uses the
already-open store. A physical immutable archive is created only when that node archives the
snapshot. A future separate-database implementation would require an explicit durable PREPARED →
PRIMARY_COMMITTED → PUBLISHED recovery protocol and is not v1.

V1 physical node keys are prefixed by immutable storage identity
`(chainGeneration, formatFingerprint, scopedSeriesId, sequence)`. This deliberately sacrifices
cross-snapshot node deduplication in exchange for isolation, safe per-snapshot leases, predictable
export, and prefix/range eviction without a global mark/sweep that blocks unrelated proofs/builds.
Active-builder and sealed-root metadata use the same identity. Per-snapshot construction-node
cleanup marks only within that prefix and must include its finalized partial/sealed root; GC for one
snapshot can never enumerate or delete another snapshot's namespace.

```text
primary authenticated state                    shared online snapshot RocksDB
snapshots/stake/170 -> descriptor(root A)       root A -> stake-170 logical MPF
snapshots/stake/171 -> descriptor(root B)       root B -> stake-171 logical MPF
snapshots/dreps/171 -> descriptor(root C)       root C -> dreps-171 logical MPF
```

The clean-break developer API adds `AppStateWriter.capabilities()`. It returns a composition-scoped
registry; disabled chains receive the immutable empty registry. An enabled component obtains only a
predeclared opaque `SnapshotSeriesHandle` and submits bounded `begin`/`appendChunk`/`seal` intents to
an in-memory `AuthenticatedSnapshotPlanCollector`. These calls return no generated root and perform
no RocksDB, file, network, archive, or clock access. Plugin `apply()` therefore cannot branch on
node-local storage results or escape its component namespace.

After the application transition, the runtime validates and executes the collected plan against the
secondary candidate, computes the root, and owns all reserved build-receipt, descriptor, and head
writes in primary state. Secondary node mutations and those primary writes join the same finalization
`WriteBatch`. A follower independently recomputes the plan/root and rejects a different proposed
primary root. `NamespacedStateViews` scopes the capability registry alongside the normal writer;
composed components cannot collide or request another component's series.

Large snapshots are multi-block builds. V1 permits one active build per scoped series. A small
authenticated primary receipt records series/sequence, source commitment and algorithms, expected
chunk/entry counts, next chunk, received entries, last canonical key, a fixed-size source
accumulator, and current secondary root. Every enabled declaration has exactly one matching
`AuthenticatedSnapshotSourceCommitmentV1`; startup rejects missing, duplicate, or algorithm/wire
mismatches. The runtime advances that adapter over the same canonical chunks used to build the
secondary tree and compares `finish(...)` with the declared source root before sealing. The adapter
is deterministic plugin code under the ordinary state-machine conformance rules and performs no I/O.
Each block applies only its bounded candidate delta against that finalized root. Duplicate,
reordered, missing, overlapping, or unexpected chunks/keys reject deterministically. The final seal
atomically writes the immutable descriptor, advances the series head, and deletes the receipt.
Restart/replay resumes from the receipt; because the secondary CFs share the finalization batch, a
receipt whose root node is missing is corruption and fails closed rather than being regenerated from
untrusted local metadata.

A series declares a deterministic creation trigger: an application message authorized by that state
machine, a committed app-height interval, or a canonical L1 boundary observation. Wall-clock time
alone never triggers consensus. Cardano History uses the L1 epoch boundary automatically; a stock
balances or DPP adapter may accept a governed `snapshot/create` application message or a fixed-height
schedule. This is separate from node-local archive/restore admin commands.

Sealing is bounded and fail-closed. It proves that every declared chunk index appeared exactly once,
entries and keys were globally canonical and unique, no unexpected/skipped entry exists, the exact
entry count and source commitment match, and the authenticated root follows from precisely those
entries plus the reserved identity leaf. Descriptor, local logical-root registration, and latest-head
update publish atomically with the finalized app block. Per-series limits bound active builders,
candidate entries/bytes, and concurrent seals; discarded candidates cannot advance a logical root or
leave advertised state, and unreferenced content-addressed nodes are bounded/background-GC eligible.
Secondary and primary mutations/bytes from every composed component share the existing per-block
operation/message/block limits; collectors do not each receive an independent 32,768-operation
allowance.

`AuthenticatedSnapshotSeriesDescriptorV1` is the versioned machine declaration. It binds scoped
series ID, schema, trigger, secondary profile/fingerprint/proof wire, verification target
(`ON_CHAIN` or `OFF_CHAIN`), descriptor/proof visibility, source-commitment identity, key/value and
chunk/snapshot quotas, and recovery coverage. Its canonical digest contributes to the immutable
application identity and the enabled `AppCapabilityManifest` cross-cutting entry. Composite assembly
sorts by scoped ID and rejects duplicates or incompatible declarations. Provider/catalog metadata
may advertise a series as available, but only enabled deployed series appear in the chain capability
manifest or REST surface.

Absent configuration and explicit `enabled:false` canonicalize identically: zero effective series,
no manifest capability, no application-identity change, no secondary CF access, no writer wrapper,
and no hidden/dual primary write. This is the normative capability-off byte-identity rule.

`SnapshotDescriptorV1` canonically binds exactly:

```text
chainGenerationId, applicationProfileDigest, seriesId, sequence, snapshotId,
snapshotProfile, snapshotFormatFingerprint, snapshotProofWireVersion, snapshotRoot,
sourceDatasetRoot, sourceCommitmentAlgorithm, sourceCommitmentWireVersion, schemaId,
entryCount, baseAppChainHeight, completedAppChainHeight, coveredFromHeight,
coveredThroughHeight, previousSnapshotCommitment, sourceBoundary, recoveryCoverage, complete
```

The committed wire is the following exact, fixed-order canonical-CBOR contract (no
optional/trailing fields):

```cddl
snapshot-descriptor-v1 = [
  1,                              ; version
  bytes .size 32,                 ; chainGenerationId
  bytes .size 32,                 ; applicationProfileDigest
  text, uint, text,               ; seriesId, sequence, snapshotId
  text, bytes .size 32, text,     ; snapshotProfile, formatFingerprint, proofWireVersion
  bytes .size 32,                 ; authenticated snapshotRoot
  bytes .size 32, text, text,     ; sourceDatasetRoot, algorithm, wireVersion
  text, uint,                     ; schemaId, application entryCount
  uint, uint, uint, uint,         ; base/completed/from/through app heights
  bytes .size 32,                 ; previousSnapshotCommitment
  source-boundary-v1,
  0 / 1,                          ; DATASET / FULL_STATE
  1                               ; complete=true
]
source-boundary-v1 =
  [0, uint] /                     ; APP_HEIGHT, appHeight
  [1, uint, uint, uint, uint, bytes .size 32]
                                  ; L1_EPOCH, previousEpoch, newEpoch,
                                  ; datasetEpoch, boundarySlot, blockHash
snapshot-head-v1 = [1, uint, bytes .size 32]
snapshot-build-receipt-v1 = [
  1, uint, text, source-boundary-v1, uint, uint, uint, 0 / 1,
  bytes .size 32,
  bytes .size 32, text, text,
  uint, uint, uint, uint, bytes, bytes .size 32
] ; sequence, snapshotId, immutable boundary, base/from/through heights,
  ; recoveryCoverage, canonical descriptor-draft digest,
  ; source root/algorithm/wire, expected/next chunks, expected/received entries,
  ; last application key, partial authenticated root
```

Text and byte fields use the framework's released bounds; series IDs match
`[a-z0-9][a-z0-9._-]{0,63}`. Heights/counts are nonnegative canonical uints. APP_HEIGHT boundaries
carry the exact app height; L1_EPOCH boundaries explicitly bind transition and dataset epochs plus a
32-byte block hash. Descriptor keys
are canonical ASCII `snapshots/v1/<series>/<20-digit-sequence>`, heads are
`snapshots/v1/<series>/latest`, and receipts are `snapshots/v1/<series>/build`, all inside the
component namespace. Application keys are unsigned-lexicographically increasing, unique, nonempty,
and must not use reserved `~snapshot/`; values use the declared deterministic schema. The reserved
identity leaf is outside application keyspace and excluded from `entryCount`. Empty snapshots have
exactly that identity leaf and its profile-defined root.

`begin` fixes every immutable final-descriptor field and stores their domain-separated canonical
draft digest in the receipt. Every later chunk/seal intent repeats that digest; mismatch rejects.
Together with the receipt fields and immutable series descriptor, restart can reconstruct the exact
final descriptor without trusting plugin memory or local catalog state.

```text
snapshotCommitment = Blake2b-256(
  "yano-authenticated-snapshot-descriptor-v1" || canonicalDescriptorBytes)
```

Each descriptor carries the previous
descriptor commitment in the same series. The primary authenticated map already acts as the
random-access map of roots, so a second dedicated "MPF of roots" would be redundant. The descriptor
chain adds ordering, gap detection, and portable continuity; it does not by itself prove a state
transition. Empty or unchanged consecutive snapshots remain distinct because the commitment binds
sequence and coverage, not only the secondary root.

Each series also has an authenticated `snapshots/<series>/latest` head. Sequence starts at zero with
an all-zero previous commitment; every successor must be exactly `latest.sequence + 1` and name the
current head commitment. Descriptor creation and latest-head replacement are one primary-state
transition. Duplicate sequences, multiple successors, gaps, overwritten descriptors, stale heads,
and rollback-inconsistent heads fail closed.

The existing observer manifest field named `snapshotRoot` remains the canonical chunk/dataset root;
the descriptor records it unambiguously as `sourceDatasetRoot`. It is not assumed to be an MPF/JMT
root. The descriptor's `snapshotRoot` is produced by applying those canonical entries to the selected
authenticated-snapshot profile. Every member checks both commitments, preventing an implementation
from relabelling a chunk Merkle root as a proof-generating tree root. Future wire revisions should
rename the observation field, but this amendment does not require a second epoch-observation wire.

The secondary tree contains a reserved identity leaf binding the chain generation, application
profile, scoped series, sequence, schema, proof profile/fingerprint/wire, and source dataset
commitment. Snapshot node keys in the shared physical store are namespaced by the complete immutable
storage identity above. This prevents cross-profile/chain/series contamination and makes identical
application entries under different series/generations produce independently identified snapshots.

#### 5.7.2 Profiles and on-chain eligibility

For an on-chain-consumable claim, both levels are pinned to compatible MPF profiles:

```text
L1 anchor -> primary MPF descriptor proof -> secondary MPF claim proof
```

The descriptor identifies the secondary proof profile and schema. Startup rejects
an `ON_CHAIN` series unless the primary and that secondary series use released on-chain MPF profiles;
the chain-level `l1-proof-consumption-required=true` is mandatory when any enabled series is
`ON_CHAIN`. A JMT primary makes every series off-chain-only. An MPF primary may mix MPF `ON_CHAIN`
series with JMT `OFF_CHAIN` series. Snapshot profile/verification-target/schema selection is immutable
chain configuration; changing it requires a fresh generation or a governed, height-gated new series.

The direct-state and snapshot profiles are distinct application/profile fingerprints. Because the
feature is still preview, no migration or dual-write compatibility path is provided.

#### 5.7.3 Node-local online retention and archival

Snapshot creation and descriptor commitment are consensus operations. Online availability,
archive destination, restore, and physical eviction are node-local administration and never change
the app-chain root. One member may keep every epoch online while another archives all but the most
recent ten.

```text
ONLINE -> ARCHIVING -> ARCHIVED_VERIFIED -> EVICTING -> ARCHIVED_ONLY
ARCHIVED_ONLY -> RESTORING -> ONLINE
```

Archival traverses nodes reachable from the logical root, writes an immutable package, reopens it in
isolated staging, verifies every reachable node hash/encoding, profile fingerprint, identity leaf,
entry count, completeness, and exact committed root before the root becomes locally evictable. It
rejects conflicting bytes for an existing content-addressed key. Import forbids path traversal,
symlinks, unconfigured URLs/paths, decompression bombs, and configured byte/node/count overruns.
Internal cleanup traces only the finalized active/sealed root inside that snapshot's leased prefix;
eviction deletes that prefix under an exclusive root lease and then compacts its range. Active-build
prefixes are never treated as unreferenced. Operations are
idempotent and crash-safe. A local proof request for unavailable data returns
`SNAPSHOT_NOT_LOCAL`/restore status, never an absence proof; another member retaining the snapshot
may still serve it. Previously issued proofs remain verifiable without any snapshot store.

The archive contract is `authenticated-snapshot-archive-v1`, not a raw RocksDB checkpoint/SST ABI.
It consists of a canonical manifest (descriptor commitment, storage identity, profile/fingerprint,
root, entry/node counts, uncompressed/compressed byte limits, chunk digests, and archive digest) plus
bounded chunks of canonical `(nodeHash,nodeBytes)` records. Compression is an operator transport
choice covered by declared size limits; verification always consumes the canonical uncompressed
records. This keeps archives portable across RocksDB/JVM upgrades.

Proof generation, archive, restore, and GC acquire root leases; eviction/GC cannot remove nodes from
an active operation. Archive/restore publication uses durable intent/result records so process death
before or after catalog publication resumes to one valid state. Operational monitoring may enforce a
desired number of online/archive replicas, but that replica count is not consensus evidence and the
responding node never claims peer availability it has not independently checked.

An optional consensus policy may declare archival eligibility, but actual I/O remains node-local.
Cluster-wide archive commands are orchestration that call each admin API independently, not app-chain
messages. Eviction requires app finality, stable L1 source boundary, an L1-confirmed anchor, and being
outside the configured app rollback horizon; it is also forbidden before archive verification when
policy requires an archive copy.

`retention.enabled: false` remains mandatory for the Cardano History pilot because from-genesis
replay currently uses retained observation bodies. Snapshot-based app-chain bootstrap plus suffix
replay is a separate recovery enhancement; producing roots alone does not authorize block-body
pruning.

During fresh replay every node reconstructs each logical snapshot far enough to verify the committed
descriptor/root. It need not leave every historical snapshot online: after verification/finality it
may retain, archive, or ephemerally discard according to local policy. That local decision cannot
change primary replay writes or roots. If archive-required policy is selected, replay cannot discard
until its own verified archive exists.

Primary MPF reachability pruning remains a separate node-local policy. It prunes obsolete primary
construction roots, while authenticated-snapshot GC manages secondary logical roots. Neither policy
may delete permanent primary descriptors or any node reachable from a retained online root.

#### 5.7.4 High-volume applications, event retention, and recovery checkpoints

The capability is intentionally not Cardano-specific. A high-throughput DPP or similar application
may process millions of events per day while having no business requirement to keep two-year-old
event bodies in every consensus node. Such an application can define two complementary series:

```text
materialized-state/2026-08-10 -> complete state needed to resume at height H
event-period/2026-08-01..10   -> authenticated event IDs/hashes for that period
```

It may create logical snapshots every five or ten days, keep the most recent snapshots online,
archive older snapshot/event packages, and eventually delete packages beyond its legal/business
retention horizon. Primary state retains only compact descriptors, roots, headers, finality/anchor
evidence, and the suffix required for rollback/recovery. When an old package is deliberately deleted,
new proofs for its contents cannot be generated; previously issued proofs remain verifiable against
the retained descriptor and L1 anchor.

This reduces primary state only when the adapter routes the high-volume period data into secondary
snapshots from inception, or performs a separate deterministic primary deletion/compaction
transition. Enabling the capability does not shrink historical entries already written by an
`OrderLog`-style append-only primary state machine.

Snapshot publication and operational recovery cadence are separate. For example, a DPP may create
six-hour internal recovery checkpoints but publish/anchor a five-day authenticated snapshot. This
bounds worst-case replay without forcing every operational checkpoint into its public proof model.
Construction is asynchronous over a finalized immutable view and must not delay block application.

**A selective dataset snapshot does not authorize app-block/event-body pruning.** A future descriptor
may set `recoveryCoverage=FULL_STATE` only when every enabled state-machine component and all required
runtime/consensus recovery metadata participate. Only a finalized full-state checkpoint, plus the
rollback/finality margin and retained suffix, may advance the block-body retention floor. Recovery
would then become:

```text
verified full-state checkpoint at H + finalized app blocks after H
```

Cardano History's per-epoch stake/DRep snapshots are dataset snapshots, not full-chain recovery
checkpoints, so they do not change its `retention.enabled: false` requirement. `FULL_STATE` capture
needs a frozen-root scan API, component coverage protocol, checkpoint installation, suffix replay,
and consensus/runtime metadata specification that the current point-read `AppStateReader` cannot
provide. A follow-up ADR owns that work; it is outside M8 and remains disabled. This ADR records the
future pruning strategy and prerequisites without claiming app-block/event pruning is implemented.

Representative reusable series include balances/account checkpoints, finalized order-book windows,
document/audit batches, authenticated-map namespaces, asset registries, periodic reports, and event
periods. Each state machine owns canonical selection and encoding; the core capability owns storage,
rooting, lifecycle, proof transport, retention, and archive mechanics.

The resulting app-chain pruning strategy is deliberately layered:

| Data | Pruning authority | Required safety evidence |
|---|---|---|
| Obsolete primary MPF/JMT versions/nodes | node-local proof-root retention | every advertised retained root still proves; rollback horizon preserved |
| Online logical snapshot nodes | node-local archive/eviction policy | immutable archive reopened at the exact descriptor root, unless explicit destructive retention permits no archive |
| App-block/event bodies | node-local chain retention bounded by consensus checkpoint | finalized `FULL_STATE` checkpoint restores exactly, suffix replay reaches the original tip root, rollback margin retained |
| Snapshot descriptors, headers, finality certificates, and L1 anchor evidence | retained compact history | never removed by snapshot GC; sufficient to verify roots and previously issued proofs |

This is the architectural pruning strategy, not a claim that every layer is implemented today. M8
implements and qualifies dataset snapshots; full-state checkpoint-authorized event pruning remains
disabled and belongs to the follow-up recovery ADR.

### 5.8 Module boundaries

Epoch observation is a framework capability. Protocol parameters, epoch stake, and governance are
out-of-box applications, packaged in the existing standard library but independently selectable.
They use these module and package boundaries:

| Module/boundary | Responsibility |
|---|---|
| `core-api` | `L1EpochObserver` family plus deterministic `AppStateCapabilities`, `AuthenticatedSnapshotSeriesDescriptorV1`, scoped plan collector/handle, descriptor/head/receipt, and nested-proof contracts; no archive I/O or Cardano-specific implementation |
| `runtime` | asynchronous epoch coordinator plus the shared online logical-snapshot RocksDB, candidate atomicity, replay/rollback, proof service, cache/backpressure, archive/restore/GC, and node-local status/admin implementation |
| `appchain-composite` | Namespace snapshot series and capability access exactly like primary state, preventing composed components from colliding or escaping their route |
| `appchain-config` | Validate opt-in series/profile/retention settings and include consensus-critical snapshot schema/profile settings in the immutable application identity |
| `appchain-client` / on-chain proof modules | Generic two-level proof bundle, strict MPF pair verification, semantic decoder hooks, and released Plutus verifier/profile vectors |
| `AppChainGateway` node/admin boundary | Snapshot catalog/proof service plus privileged archive/restore/evict/job operations; never exposed through the deterministic state-machine capability registry |
| `app` REST/OpenAPI | Chain-scoped v1 resources, authorization, stable HTTP/error mapping, bounded async execution, and metrics; no storage implementation |
| `appchain-stdlib-contracts/.../l1/epoch` | Shared canonical snapshot semantics, manifests/chunks, and internal contract utilities |
| `appchain-stdlib-contracts/.../l1/epoch/params` | Parameter claim codecs, state keys, query records, and typed proof subjects |
| `appchain-stdlib-contracts/.../l1/epoch/stake` | Stake claim codecs, state keys, completeness records, and typed proof subjects |
| `appchain-stdlib-contracts/.../l1/epoch/governance` | Proposal-status and DRep-distribution codecs, state keys, completeness records, and typed proof subjects |
| `appchain-stdlib/.../l1/epoch/params` | First-party parameter observer, component/state-machine transition, provider, query adapter, and configuration |
| `appchain-stdlib/.../l1/epoch/stake` | First-party chunked stake observer and the first `AuthenticatedSnapshotCapability` adapter/series |
| `appchain-stdlib/.../l1/epoch/governance` | First-party governance observer; direct proposal history plus optional logical DRep snapshot series |

All three capabilities ship in the default distribution but are disabled unless selected. Shipping
code is not permission to traverse stake or DRep snapshots: a params-only deployment performs no
stake or governance work. They may be composed into one deployed `AppStateMachine`, or reused
individually inside a custom machine. None is a dependency of the app-chain runtime, and stdlib
must depend only on the `core-api` epoch view—not concrete `ledger-state` or `runtime` classes.
Any other state machine may opt in by declaring canonical series keys/values and creation triggers;
merely enabling the runtime capability does not invent snapshot semantics for it.

### 5.9 Productization boundary

This ADR owns the consensus-sensitive foundation: epoch observation, canonical codecs and keys,
state transitions, authenticated storage, replay, rollback, and proof semantics. ADR app-layer/035
owns the optional **Cardano History** product assembly that makes those capabilities easy to install
and demonstrate: its plugin/profile, read-only domain API, typed client and CLI, console page,
showcase presets, reference on-chain validators, and release packaging.

That product boundary does not move reusable logic out of stdlib or make the runtime depend on the
product. A custom `AppStateMachine` can still compose any subset directly. Conversely, the product
must call the same stdlib transitions and generic ADR-031 query/proof/anchor services; it must not
fork epoch codecs, maintain a second authenticated state, add epoch-specific routes to the core REST
API, or infer capabilities from a chain name. The first product console is implemented in Yano's
reusable, sandboxed plugin-UI host and discovered from `AppCapabilityManifest`; its compiled product
UI is packaged by ADR-035, not by the epoch foundation. ADR-028 introduces no UI contracts or assets.
ADR-035 must present the generic nested proof result and distinguish consensus completeness from
node-local `ONLINE`/`ARCHIVED`/`RESTORING` availability; it must not implement its own snapshot store
or report an unavailable archive as a proven absence.

## 6. Snapshot semantics — the highest-risk detail

Yano's convention, from `EpochRewardCalculator.java:183-187`:

> *"Snapshot key E captures delegation state at the END of epoch E (matching yaci-store
> `epoch_stake` convention). Cardano ledger uses the mark snapshot from END of epoch N-4 for
> reward epoch N. Verified against Haskell node (DBSync): store uses `epoch_stake WHERE
> epoch = N-4`. With stakeEpoch = N-2, snapshotKey = stakeEpoch - 2 = N-4."*

**A mislabeled snapshot is a silent correctness bug** — the data is well-formed, threshold-agreed,
anchored, and wrong by two epochs. Therefore:

- The wire format **must carry an explicit `snapshotSemantics` discriminator**
  (v1 = `end-of-epoch-E`, matching yaci-store `epoch_stake.epoch = E`), so a consumer cannot
  silently reinterpret it as DBSync's active-epoch labeling.
- For boundary `E -> E+1`, the first applied block in `E+1` supplies only the boundary identity.
  The protocol-parameter dataset is explicitly labelled `effectiveEpoch = E+1`; the stake dataset
  is explicitly labelled `snapshotEpoch = E`; and the DRep dataset is explicitly labelled
  `distributionEpoch = E+1`. These labels are encoded in their respective manifests/claims.
- Cross-verification against yaci-store **and** DBSync is a **merge gate**, not a nice-to-have
  (§12). This matches the repo's standing rule that the Haskell ledger is the source of truth
  and yaci-store is the cross-reference.
- Governance claims carry an explicit boundary epoch. DRep distribution uses the distribution
  calculated for that boundary epoch. Proposal status is the lifecycle state after that boundary's
  enact/drop and ratification phases; the discriminator is committed in the governance contract so
  consumers cannot reinterpret `RATIFIED` as already `ENACTED`.
- DRep distribution v1 contains persisted key/script credential DReps only. Cardano's virtual
  abstain/no-confidence tally buckets are not credential claims and are explicitly excluded by a
  committed dataset-semantics discriminator. Supporting them later requires a versioned dataset,
  not synthetic 28-byte credential hashes.

## 7. Determinism rules (normative)

| # | Rule |
|---|---|
| D1 | Canonical sort ascending by `(credType, credHash)` bytes — **guaranteed by the SPI**, not the plugin |
| D2 | Chunk boundaries = **fixed entry count** over the sorted sequence (`chunk-entries`), never a byte budget — a byte budget makes the boundary depend on encoder details |
| D3 | Attest the **stake credential** (`credType` + 28-byte hash), never a bech32 address — the credential is what the ledger keys on |
| D4 | Parameters encoded as **rationals** (`UnitInterval` / `NonNegativeInterval` numerator/denominator), never `BigDecimal.toString()` or floats |
| D5 | No `HashMap`/`HashSet` iteration anywhere in observer or machine (the `AppStateMachine` javadoc already forbids this; here it is the single most likely defect) |
| D6 | Snapshot semantics pinned on the wire (§6) |
| D7 | Observer config byte-identical on every member — consensus-critical, as for all observers |
| D8 | Proposal order is `(txHash bytes, govActionIndex)` and DRep order is `(drepType, drepHash bytes)`; status and drop-reason values are closed wire enums |

## 8. Rollback

### 8.1 Before injection
`onL1Rollback` invalidates asynchronous jobs and spool records whose first-new-epoch boundary block
is above the rollback target. A concurrently running generator observes cancellation before making a
manifest READY; any partial `GENERATING` data is discarded idempotently.

### 8.2 After finality
Every production epoch attestation requires **ADR-027 §2.4 deep-rollback detection**. The epoch
observation spool now detects rollback below a finalized boundary and permanently removes the chain
from voting; the authenticated-snapshot runtime durably latches the descriptor lineage as
`DISPUTED`, including across restart. Here the predicate
is clean and low-cardinality: *"epoch E's attestation is disputed"* — unlike "which of these
4,000 deposits is now unbacked." With `epoch-stability-depth = k` the event should be
effectively unreachable, but it must fail closed rather than silently.

This applies equally to protocol parameters, stake, and governance. Broader ADR-027 governed
recovery remains a production release gate; there is deliberately no operator-only clear switch.

When the guard identifies a deep rollback that disputes a published snapshot, the chain halts new
epoch publication fail-closed and records/exposes `DISPUTED` operational status for the affected
descriptor lineage. Public proof generation must not present such a bundle as an undisputed claim;
verification APIs preserve the cryptographic result while separately reporting the disputed L1
attestation status. Archive/eviction is blocked for the disputed lineage until governed recovery
under ADR-027 completes.

### 8.3 Corrections supersede, never mutate
`params/<epoch>`, proposal records, direct-profile epoch keys, and snapshot descriptors are
**write-once**. A correction lands as a new version/sequence authorized by the chain's governance
role, with the prior descriptor and root retained. A sealed logical snapshot is never reopened or
mutated. Proofs name the version and series sequence, keeping every previously issued proof valid.

Governance authorization alone cannot rewrite canonical L1 history. A corrected L1 descriptor must
identify the superseded commitment and pass the normal independently derived observer verification
against the corrected canonical boundary data. An operator/governance assertion that cannot be
re-derived belongs to a separately typed correction/annotation series whose different trust model is
visible in its schema and proof subject; it never masquerades as the original L1-observed series.

## 9. Proof and verification

A direct-profile consumer verifies a historical value in two levels, trusting nobody:

1. **Inclusion proof** — `stake/<epoch>/<credential>` → value, against `stateRoot` at height H
   (existing ADR-025 authmap proof bundle).
2. **Anchor** — `stateRoot` at H is covered by an L1-confirmed, stability-deep anchor
   transaction (existing metadata or script anchor).

So: fetch the anchor tx from Cardano, read the root, verify the inclusion proof. No trust in
the chain operator, and no dependency on the chain still running.

An authenticated-snapshot consumer verifies three bindings:

1. **Primary descriptor proof** — `snapshots/<series>/<sequence>` → canonical
   `SnapshotDescriptorV1`, against primary `stateRoot` at height H.
2. **Secondary claim proof** — application key → value or absence, against the descriptor's
   `snapshotRoot`; completeness and schema are bound by the descriptor.
3. **Anchor** — primary `stateRoot` at H is covered by the L1-confirmed anchor.

The generic proof bundle binds both proof profiles, descriptor key/bytes, secondary key/value,
both proof wires, chain/application/commitment identity, and anchor identity. Verification never
trusts the node-local snapshot catalog or archive location.

Nested verification order is normative. The verifier constructs semantic keys and expectations from
the selected proof subject; it never trusts caller-supplied key/profile/schema fallbacks:

```text
independently verified L1 anchor -> exact primary root
primary MPF proof -> exact descriptor key and bytes
strict canonical descriptor decode -> version, chain generation, application, series, sequence,
                                      complete=true, schema, profiles/fingerprints/wires
descriptor.snapshotRoot -> exact secondary proof root
secondary MPF proof -> verifier-constructed canonical key and exact value/absence
application predicate -> strictly decoded proven value
```

Descriptor and nested-proof CDDL/golden vectors are shared by runtime, Java/browser clients, and
Plutus tests. Decoders reject noncanonical CBOR, trailing/duplicate fields, unsupported versions,
oversized nesting/collections, and proof splicing across anchors, chains, generations, applications,
series, sequences/epochs, schemas, profiles/fingerprints/wires, keys, values, or descriptors.

The nested transport is exact canonical CBOR:

```cddl
authenticated-snapshot-proof-bundle-v1 = [
  1,
  ["app-anchor-commitment-cbor-v1", bytes], ; canonical anchor-evidence-v1 below
  state-proof-envelope-v1,         ; primary descriptor proof + finality provenance
  state-proof-v1,                  ; secondary application proof
  [text, uint, text,               ; series, sequence, schema
   bytes, bytes .size 32, bytes .size 32],
                                    ; descriptor bytes, descriptor commitment, statement commitment
  bytes .size 32                   ; bundle commitment
]
anchor-evidence-v1 = [
  1, text, text, uint,              ; version, chainId, mode, anchored height
  bytes .size 32, bytes .size 32,   ; state root, app-block hash
  text, uint                         ; Cardano transaction hash, L1 slot
]
state-proof-envelope-v1 = [
  [1, text, bytes .size 32, 0,      ; schema, chainId, block hash, Ed25519 scheme
   [* [bytes .size 32, bytes .size 64]]],
  state-proof-v1
]
state-proof-v1 = [
  text, bytes .size 32, text,        ; profile, format fingerprint, native proof wire ID
  bytes .size 32, bytes .size 32,    ; generation/storage identity, root
  uint, bytes, 0 / 1 / 2, bytes,     ; height, key, PRESENT/ABSENT/TOMBSTONED, value
  bytes                              ; released profile-native proof wire
]
```

The primary and secondary native proof bytes remain profile-neutral transport: MPF carries
`mpf-proof-wire-v1`, while classic JMT carries `jmt-proof-cbor-v1` only in off-chain profiles. The
outer codec strictly bounds keys, values, proofs, signature count, text, and total bytes, rejects
non-canonical re-encoding, and validates the descriptor/anchor/root identities before returning a
bundle. Existing ADR-031 anchor evidence remains independently checked; embedding it does not turn
an operator assertion into a trusted anchor. The core golden vector fixes the complete encoded
bundle length and SHA-256 fingerprint, while field-tamper tests cover anchor and native-proof
substitution.

```text
statementCommitment = Blake2b-256(
  "yano-authenticated-snapshot-statement-v1" || canonical([
    chainGenerationId, applicationProfileDigest, series, sequence,
    descriptorCommitment, snapshotRoot, schema, applicationKey,
    presence, Blake2b-256(value)
  ]))
bundleCommitment = Blake2b-256(
  "yano-authenticated-snapshot-proof-bundle-v1" || canonical(bundle-without-final-field))
```

Profile IDs, fingerprints, wires, roots, heights, descriptor key/bytes, proof paths, requested key,
presence/value, anchor evidence, and commitments are transported. A typed verifier independently
constructs expected chain/application identity, series, sequence/dataset epoch, schema, descriptor
key, application key, allowed profiles/wires, presence semantics, and predicate, then compares them
to transport fields in the normative order above. The generic raw-key endpoint proves only the exact
bounded key supplied; it cannot confer application semantics without a typed proof-subject decoder.

**On-chain verification** uses the already-implemented ADR-031 Phase 6 path:
`mpf-proof-wire-v1`, the strict normalized-proof converter, the generic `MpfOnChainVerifier`, and
the eleven-field anchor datum that binds chain/application/commitment identity and state root.
The on-chain reference chain pins `mpf-blake2b256-v1` and
`state.l1-proof-consumption-required=true`. A `jmt-blake2b256-v1` Cardano History chain exposes the
same typed subjects and anchor binding to off-chain clients, but must advertise and enforce
`state.l1-proof-consumption-required=false`; it has no Cardano validator proof path. The bounded on-chain MPF
envelope permits keys up to 256 bytes, values up to 8 KiB, and at most 32 folds; every concrete
params/stake/governance value and key must be tested against those existing limits.

A stake amount/pool assertion uses one secondary proof of the canonical `[coin, poolHash]` leaf. The
application-specific validator decodes that proven value and applies its own predicate, for example
`coin >= minimum`, `poolHash == expectedPool`, or both. It does not need separate authenticated
coin and pool keys. In the snapshot profile, positive and absence/delegation assertions also verify
the primary complete descriptor. The Plutus validator therefore executes the released MPF verifier
twice: once for the descriptor and once for the leaf/absence. The pair must fit transaction size and
execution-unit limits; off-chain success alone is not an acceptance gate. The direct profile retains
the existing same-primary-root leaf plus completeness proof.

ADR-031 typed proof subjects hide the physical composite namespace and bind an epoch-param, stake,
proposal-status, or DRep-distribution claim to its canonical key/value schema. The first reference
validator should use the small `params/<epoch>` fact before attempting a mainnet-scale stake
profile.

### 9.1 Generic REST, client, and administration surface

The capability extends the existing chain-scoped `/api/v1/app-chain/chains/{chainId}` surface. These
are generic runtime endpoints and are present only when the selected chain advertises
`authenticated-snapshots-v1`:

| Method/path | Access | Semantics |
|---|---|---|
| `GET /snapshots?series=&cursor=&limit=` | series read policy | bounded descriptor catalog plus sanitized node-local availability |
| `GET /snapshots/{seriesId}/{sequence}` | series read policy | canonical descriptor, descriptor commitment, primary root/height, and local availability |
| `POST /snapshots/{seriesId}/{sequence}/proof` | series proof policy, bounded | generate a secondary inclusion/absence proof and its primary descriptor proof for a canonical key |
| `POST /snapshots/proof/verify` | read, bounded | pure verification of a supplied nested bundle; requires no local snapshot |
| `GET /snapshots/status` | read | cache/executor/online/archive counts and degraded state, with no filesystem path or credential disclosure |
| `POST /admin/snapshots/{seriesId}/{sequence}/archive` | snapshot-admin | enqueue idempotent node-local archive and optional verified eviction |
| `POST /admin/snapshots/{seriesId}/{sequence}/restore` | snapshot-admin | enqueue idempotent verified restore into the online store |
| `POST /admin/snapshots/{seriesId}/{sequence}/evict` | snapshot-admin | explicit local eviction subject to finality/rollback/archive policy |
| `GET /admin/snapshots/jobs/{jobId}` | snapshot-admin | bounded archive/restore/eviction job status with redacted diagnostics |

Archive/restore/eviction return `202` and a job ID; they never run storage traversal on an HTTP or
consensus thread. `404` means no committed descriptor, `409` means incomplete/not yet evictable,
`429` means bounded proof/admin capacity is saturated, and `503 SNAPSHOT_NOT_LOCAL` means the claim
is committed but this node cannot currently generate it. That last response must never be mapped to
absence. Proof requests have bounded key/body/response sizes, deadlines, per-chain concurrency, and
rate limiting. Cold public requests return `SNAPSHOT_NOT_LOCAL`; only explicit authenticated restore
jobs coalesce for the same descriptor.

By default proof generation selects the newest locally provable, L1-confirmed primary anchor height
at or after descriptor completion and proves the descriptor against that exact retained primary root.
The bundle includes the independently checkable anchor datum/output reference. It never silently
returns a current unanchored-root proof; absence of a suitable retained anchor is
`503 SNAPSHOT_NOT_ANCHORED`. An optional requested anchor height must identify an exact confirmed,
retained root. Catalog pagination executes against one root-fixed primary view; opaque cursors bind
API schema version, primary root/head commitment, series filter, and last sequence so pages cannot be
mixed across advancing roots.

Each series explicitly declares descriptor/proof visibility; enabling the capability never exposes a
custom state machine's keys or values by default. Cardano History may declare public proof access.
Admin operations require a dedicated snapshot-administration permission, audit entry, idempotency
key, and bounded job queue. Archive providers and restore sources come only from operator
configuration—request bodies cannot supply arbitrary filesystem paths or URLs. A public proof
request never triggers restore/download or disk mutation. Proof-cache keys bind chain generation,
descriptor commitment, snapshot root, profile fingerprint, canonical key, and proof kind.

The dedicated permission is configured independently from generic full/submit keys. Durable jobs
retain a non-secret hash-derived principal ID, use bounded completed-job retention, and hold the
current runtime-generation lease for their complete archive/restore/evict operation. Verification
requests are raw-body bounded and share the chain's proof admission semaphore. `local-anchor`
resolves the bundle's exact retained height rather than the latest anchor. `caller-pinned-root`
requires chain ID, anchor mode, primary profile/root/generation/application identity, anchored
height, block hash, anchor transaction hash, and L1 slot; partial trust contexts are rejected.

The generic client exposes catalog, descriptor, proof, verification, availability, and privileged
job methods with the same typed status distinctions. Application plugins such as Cardano History
may add semantic routes (`stake`, `drep`, predicates) but must delegate to this API/service and return
the generic nested proof bundle. Metrics cover request outcome/latency, executor saturation, cache
hit/miss, online/archive bytes, GC, archive/restore duration, and local availability.

Snapshot **creation** is intentionally not a generic admin endpoint: it changes consensus. Automatic
series use their deterministic boundary/height trigger; manual series define an application message
and authorization policy and submit it through the existing message/domain API. A node-local admin
credential alone cannot create or alter an authenticated descriptor.

V1 uses media/schema ID `yano-authenticated-snapshot-api-v1`. A proof request is an exact canonical
key byte string plus `INCLUSION_OR_ABSENCE`; semantic plugin endpoints construct that key themselves.
Nested bundles use the exact descriptor/proof contracts in §9 and never accept arbitrary physical
keys. List limits are `1..100` (default 20). Admin requests require an idempotency key scoped to
`(chain generation, operation, descriptor commitment)`; repeating it returns the original bounded
job result. Stable error codes distinguish `UNKNOWN_DESCRIPTOR`, `INCOMPLETE`, `DISPUTED`,
`SNAPSHOT_NOT_LOCAL`, `SNAPSHOT_NOT_ANCHORED`, `NOT_EVICTABLE`, `SATURATED`, and `INVALID_PROOF`.

## 10. Configuration

```yaml
yano:
  app-chain:
    chains[n]:
      chain-id: cardano-history
      state-machine: composite          # any configured stdlib/custom components
      membership: { mode: governed }
      max-message-bytes: 3145728        # 3 MiB; benchmark profile for 25k-entry chunks
      block:
        interval-ms: 1000
        max-bytes: 4194304              # 4 MiB; includes framework/certificate headroom
      state:
        commitment-profile: mpf-blake2b256-v1
        format-fingerprint: <profile-fingerprint-32B-hex>
        genesis-id: <fresh-chain-generation-id-32B-hex>
        l1-proof-consumption-required: true
        proof-pruning:
          enabled: false                # opt-in MPF pilot; never inferred from retention.enabled
          retain-heights: 10000         # retain this contiguous proof-generation horizon
          interval-seconds: 3600
      capabilities:
        authenticated-snapshots:
          enabled: true                 # opt-in; false preserves direct-state behavior
          series:
            stake:
              profile: mpf-blake2b256-v1
              schema: cardano-epoch-stake-v1
              creation: epoch-observer
              verification-target: on-chain
              visibility: public
              keep-online-count: 10
              eviction-mode: archive-required
              min-anchor-age-blocks: 2160
              disk-high-watermark-bytes: 53687091200
            dreps:
              profile: mpf-blake2b256-v1
              schema: cardano-drep-distribution-v1
              creation: epoch-observer
              verification-target: on-chain
              visibility: public
              keep-online-count: 10
              eviction-mode: archive-required
          archive:                      # node-local provider/configuration
            provider: filesystem
            path: appchain-snapshots/archive
          proof-service:                # node-local bounded resources
            concurrency: 32
            queue-capacity: 256
            cache-bytes: 67108864
            block-cache-bytes: 268435456
      l1:
        stability-depth: 36
        epoch-stability-depth: 2160     # = k; cheap at a 5-day cadence
      retention:
        enabled: false                  # MANDATORY (§5.7, replay needs bodies)
      observers:
        epoch-params:
          type: l1-epoch-params-v1
        epoch-stake:
          type: l1-epoch-stake-v1
          chunk-entries: 25000          # one chunk/block; leaves state-op headroom
        epoch-governance:
          type: l1-epoch-governance-v1
          include-proposals: true
          include-drep-distribution: true
          drep-chunk-entries: 25000
      machines:
        l1state:
          profile: yano-l1state-v1
          snapshot-semantics: end-of-epoch
```

For an off-chain-only deployment, create the chain with the classic JMT identity instead:

```yaml
      state:
        commitment-profile: jmt-blake2b256-v1
        format-fingerprint: <classic-jmt-profile-fingerprint-32B-hex>
        genesis-id: <fresh-chain-generation-id-32B-hex>
        l1-proof-consumption-required: false
```

The profile and fingerprint should normally be materialized by the app-chain launcher. A copied MPF
fingerprint with a JMT profile, or `l1-proof-consumption-required: true` with JMT, fails startup.

If `authenticated-snapshots.enabled` is absent/false, the existing direct primary-state layout and
proof behavior are unchanged. Enabling it is permitted only for state-machine components that
advertise deterministic snapshot adapters. Consensus-critical series/profile/schema/creation
settings are included in the immutable application/profile fingerprint; node-local retention,
paths, archive credentials, cache sizes, and current availability are excluded.

The showcase exposes this selection only while preparing a fresh instance:

```bash
./showcase.sh quickstart \
  --profile light \
  --nodes 3 \
  --instance snapshot-demo \
  --enable-authenticated-snapshots cardano-history-chain

# Multiple capable chains, or every capable chain in the selected catalog:
--enable-authenticated-snapshots balances-chain,cardano-history-chain
--enable-authenticated-snapshots all

# Optional immutable per-chain secondary profile selection:
--authenticated-snapshot-profile cardano-history-chain=mpf-blake2b256-v1
```

The option materializes each selected chain's immutable capability/profile configuration before
genesis and prints the selected series. An omitted option preserves today's catalog behavior. An
unknown chain or a chain without a snapshot adapter fails preparation and lists supported chain IDs;
the launcher never silently ignores a requested capability. This ADR requires Cardano History as
the first enabled showcase adapter. Additional stock adapters (for example balances or an
authenticated-map namespace) may be added independently and are not implied by the generic flag.
The resolved adapter/series/profile set and its digest are written to the instance marker and cannot
change after genesis. `all` expands once during preparation and does not silently gain series after a
distribution upgrade. Relative archive paths resolve below the node/chain-generation directory, so
cluster members never share one writable archive directory accidentally.

**Hard startup gates** (fail to start, same shape as the existing
`observers require stability-depth > 0` check):

1. persistent account/ledger state enabled, epoch boundary markers complete, and requested
   snapshots being computed; the in-memory account-state store is not a valid source;
2. `yano.account-state.snapshot-retention-epochs` covers the configured asynchronous generation and
   recovery horizon. Generation must normally complete before the next epoch. Once canonical bytes
   are durably spooled, members verify from that spool and the original ledger snapshot may follow
   the normal retention policy; unbounded L1 snapshot retention is not required;
3. `retention.enabled: false` on this chain;
4. `epoch-stability-depth > 0` when any epoch observer is configured.
5. the governance observer requires Conway governance tracking plus rollback-safe proposal-history
   persistence; DRep distribution additionally requires the requested epoch snapshot to be retained.
6. `state.l1-proof-consumption-required=true` requires the MPF commitment profile, and configured
   chunk/message/block/state-operation budgets must be mutually valid.
7. `state.proof-pruning.enabled=true` is accepted only for MPF during the pilot, requires a positive
   retained-height horizon and interval, and must never prune the current root. A failed GC or
   retained-root verification leaves pruning degraded/disabled without stopping consensus.
8. `authenticated-snapshots.enabled=true` requires at least one state-machine-declared series; every
   series has a canonical schema, deterministic trigger, positive operation/chunk limits, and a
   released authenticated profile.
9. On-chain consumption requires MPF at both primary and secondary levels. JMT at either level
   forces that proof subject to off-chain-only and is rejected when the chain declares it on-chain.
10. Archive eviction is disabled until the snapshot is final and outside rollback retention; a
    configured archive-required policy additionally requires reopen-and-root verification.

## 11. Milestones

| # | Milestone | Gate |
|---|---|---|
| **M1** | Replace preview observation wire with the tagged baseline + CDDL + new golden vectors | transaction and epoch anchors strict; old preview bytes rejected |
| **M2** | Streaming `L1EpochObserver` / `L1EpochState` / provider SPI; asynchronous first-new-epoch `BlockAppliedEvent` coordinator; durable bounded spool/outbox; block-depth `epoch-stability-depth`; startup gates | publisher latency is independent of snapshot size; two-member devnet agrees on a synthetic epoch fact; restart and proposer rotation resume the next chunk |
| **M3** | `epoch-params` state machine using ADR-031 `AppBlockExecutionContext` + client + `StateMachineConformance` + devnet e2e | preprod params match yaci-store `epoch_param` and Koios; no historical L1 handle is used during replay |
| **M4** | **Authenticated-state feasibility benchmarks**: canonical two-pass stake/DRep generation, 25k-entry chunks, direct MPF baseline, reachability-pruned MPF, classic JMT, storage/restart/proof generation, and combined proof verification at mainnet scale | publish the direct-layout cost and use it to select direct versus logical-snapshot storage; MPF pruning preserves every retained root; JMT publishes comparable off-chain results |
| **M5** | Direct-state `epoch-stake` baseline with mandatory `[coin, poolHash]` values — **blocked on M4 and, for production, ADR-027 §2.4** | mainnet-scale epoch attested within the configured ingestion SLA; amount-only, pool-only, combined, and absence+completeness proofs establish the differential baseline |
| **M6** | `epoch-governance` component: rollback-safe proposal history plus chunked DRep distribution — **blocked on M4 and ADR-027 §2.4** | proposal and DRep claims match independent sources; proposal-only configuration performs no DRep traversal |
| **M7** | Typed proof surface and application semantic decoders on top of the implemented ADR-031 REST/client/evidence/generic MPF verifier | third party verifies params, stake amount/pool predicates, proposal status, and DRep amount from the anchor output alone |
| **M8a** | Generic `authenticated-snapshots-v1` core/runtime/composition/config/client capability; shared online store; descriptor chain; candidate/replay/rollback; local archive/restore/GC | capability-disabled roots remain byte-identical; two generic state machines pass conformance; one node archives while peers retain online copies without consensus divergence |
| **M8b** | Cardano History stake and DRep adapters; MPF+MPF proof bundle and Plutus verifier; JMT off-chain variants; direct/snapshot differential tests | descriptor and secondary proofs bind the same canonical dataset; amount/pool/absence/DRep predicates pass on-chain within published limits |
| **M8c** | Showcase selective `--enable-authenticated-snapshots` preparation, discovery/UI status, archive/restore commands, and parallel proof benchmark | omitted flag preserves current catalog; unsupported selection fails closed; hot/mixed/warm p50/p95/p99, QPS, memory, file handles, IOPS, and consensus isolation are published |
| **M8d** | Three-member preprod qualification over ≥10 reconstructed historical epochs plus one observed live crossing — production release remains blocked on ADR-027 §2.4 and the ten-live-epoch soak | full cross-verification (§12), restart, peer-asymmetric archival, restore, and L1-anchored nested proofs green; continue the same deployment for ≥10 live epochs before production status |

M1–M3 are the useful, self-contained increment. **M3 is the smallest thing that proves the
whole idea** and is worth building before committing to M5.

### 11.1 M4 questions and M5 acceptance decision

M4 first evaluated the released direct MPF and classic-JMT authenticated-map profiles. Its direct
MPF result (3.84 GB before reachability GC; 3.19 GB after GC/compaction for one 1.3M-entry epoch)
demonstrates that obsolete-node pruning cannot reclaim live append-only epoch entries. That result
triggers the M8 authenticated-snapshot amendment rather than being projected as sustainable annual
growth. MPF remains the on-chain decision; JMT is an optional off-chain choice. The benchmark set
records, on representative mainnet-scale data:

1. canonical-CBOR bytes and chunk count for 25,000-entry `[coin, poolHash]` chunks;
2. wall time, peak heap/native memory, and restart behavior of both generation passes;
3. wall time and RocksDB growth for approximately 1.3M MPF and JMT inserts on every member;
4. time from L1 boundary detection until the complete epoch is app-final and L1 anchored;
5. three-member agreement while normal L1 synchronization continues without publisher-thread delay;
6. replay/catch-up time and retained storage growth per epoch and projected per year;
7. inclusion/exclusion proof generation latency, normalized proof size, and fold count;
8. combined Plutus CPU/memory/redeemer size for both the direct leaf/completeness profile and the
   snapshot descriptor/secondary-leaf profile;
9. process death in each spool state and proposer rotation during a partially ingested epoch; and
10. rollback before stability, ensuring the asynchronous job and partial spool are cancelled; and
11. MPF mark/sweep duration, nodes and bytes reclaimed, post-compaction size, restart, and proof
    verification from every retained root; and
12. logical-snapshot online growth, archive size/time, export verification, GC/compaction reclaimed
    bytes, restore time, and proof equivalence before and after restore.

Initial M8 acceptance budgets on the recorded benchmark hardware are:

1. one sealed 1.3M-entry MPF snapshot is at most 4.0 GB before internal cleanup and 3.5 GB after;
2. ten online epochs fit within the configured 50 GB high watermark; projected annual archive bytes
   and deletion policy are published rather than hidden as primary-state growth;
3. archive/restore completes within 15 minutes each, peak additional local bytes stay below 1.25×
   the sealed snapshot, and restored roots/proofs are byte-identical;
4. at 32 hot clients the HTTP proof service sustains at least 500 proofs/s with p95 ≤ 50 ms,
   p99 ≤ 100 ms, no incorrect/error response, and bounded `429 + Retry-After` above capacity;
5. simultaneous proof load changes app-block apply/finalization p95 by less than 10%; archive,
   restore, cleanup, and compaction never run on consensus/HTTP I/O threads;
6. the nested MPF transaction fits Cardano maximum transaction size/CPU/memory with at least 20%
   headroom in each execution budget; and
7. every report records CPU, RAM, storage device/filesystem, JVM, RocksDB options, cache/queue sizes,
   dataset digest, command, and raw result artifact.

A failed numeric gate reopens the design/budget through an ADR update; it is not silently waived.
The ten reconstructed epochs plus one live boundary is the implementation qualification that can run
now. Ten consecutive **live preprod epochs** is approximately fifty days and remains a production
soak/release gate, not evidence that can be fabricated by historical replay.

The implementation report publishes the completed direct-layout measurements. Before M8b/M8d are
declared complete it must also publish accepted logical-snapshot ingestion, online/archive storage,
restore, and parallel-proof budgets. Hard correctness gates are fixed: no app block may
exceed 32,768 state operations; no message/block may exceed the configured framework bounds; proof
keys/values/folds must fit the released ADR-031 on-chain envelope; the BlockApplied publisher must do
no dataset-sized work; and the complete epoch must be finalized before its required L1 source can be
pruned. Failure of a snapshot/on-chain gate blocks M8d rather than silently falling back to an
unauthenticated index.

**Implementation learning.** The direct M4 measurements established correctness and exposed the
live-data storage limit. Questions that require the logical snapshot implementation—nested on-chain
proof cost, archive/restore equivalence, GC, concurrency, and three-member operation—are hard M8
release gates and may not be inferred from the direct component benchmark. Accepted measurements and
budgets are published in `028-implementation-report.md`.

## 12. Test plan (definition of done)

- **Wire:** new tagged baseline golden vectors; old transaction-only bytes and malformed anchors
  rejected.
- **Determinism:** two independently-built members produce **byte-identical** claims over
  ≥1,000 epochs; shuffled source ordering must not change output (guards D1/D2).
- **Conformance:** `StateMachineConformance` for every independently selectable component and
  their supported compositions.
- **Cross-verification (merge gate, per §6):**
  - preprod protocol params vs yaci-store `epoch_param` + Koios;
  - mainnet epoch stake vs `/Users/satya/work/dbsync-parquet/dbsync-mainnet-parquet` and
    yaci-store `epoch_stake` (schema `mainnet`);
  - preview vs `epoch_stake_from646.parquet`;
  - proposal lifecycle status vs Yaci Store and Koios on preprod;
  - DRep distribution vs Yaci Store, Koios, and the available DBSync preview/mainnet parquet
    snapshots.
- **Negative:** fabricated claim → MISMATCH → proposal rejected; missing chunk → epoch never
  marked complete; reordered or duplicate chunk → deterministic rejection;
  current-active state masquerading as historical proposal state → rejected.
- **Spool/backpressure:** process death during generation and injection resumes durably; proposer
  rotation continues from the next missing chunk; pool pressure never drops an attestation silently;
  configured per-block message/byte budgets are enforced.
- **Asynchronous boundary:** the first `BlockAppliedEvent` of epoch `E+1` supplies the exact boundary
  identity and returns without scanning/encoding; a deliberately slow 1.3M-entry observer does not
  delay the publisher. Startup reconciliation reconstructs a job lost before its first spool write.
- **Epoch labels:** the same boundary proves params `E+1`, stake `E`, and DRep distribution `E+1`;
  governance actions in the first block of `E+1` do not appear in the boundary snapshot.
- **Stake projection:** one `[coin, poolHash]` leaf supports amount-only, pool-only, combined, exact,
  and absence-plus-completeness on-chain predicates without separate coin/pool state keys. Snapshot
  mode verifies the primary descriptor and secondary leaf/absence under the released Plutus budget.
- **Capability off:** enabling no snapshot capability produces byte-identical blocks/state roots and
  the same direct proof behavior as the current baseline; there is no hidden dual write.
- **Snapshot conformance:** canonical ordering, shuffled-input invariance, duplicate/missing chunks,
  descriptor-chain continuity, empty/unchanged snapshots, namespace isolation, series/profile
  fingerprinting, candidate abort, rollback, restart, and replay reproduce identical logical roots.
- **Adversarial nested proofs:** reject cross-anchor/chain/generation/application/series/sequence/
  epoch/schema/profile/fingerprint/wire/key/value/descriptor substitution, a valid primary proof plus
  a secondary proof from another root, caller-selected semantic keys, noncanonical encodings,
  unsupported versions, and valid-looking absence under an incomplete/disputed descriptor.
- **Descriptor/seal adversarial:** reject skipped/forked/duplicate successors, stale latest head,
  overwrite, rollback-inconsistent head, missing/duplicate/reordered/overlapping/extra chunks and
  secondary keys, incorrect counts, and malicious staged-snapshot floods while honest members remain
  live under deterministic per-series limits.
- **Physical lifecycle:** multiple logical epochs share one online RocksDB; one member archives and
  evicts an epoch while peers retain it online; no app root changes. Archive reopen verifies the exact
  root, interrupted archive/eviction resumes safely, unavailable snapshots never return false
  absence, restore reproduces byte-identical inclusion/absence proofs, and GC retains nodes reachable
  from every online root.
- **Archive adversarial:** corrupt/truncated/oversized/wrong-profile archives, conflicting node bytes,
  traversal/symlink/decompression-bomb inputs, unconfigured URL/path injection, and crashes at each
  publish boundary fail closed. Proof/GC/archive/restore/eviction/restart races preserve leased roots.
- **API authorization:** public callers cannot trigger restore/archive/eviction, arbitrary physical
  key access, or unauthorized series disclosure; cross-chain admin requests and SSRF/path injection
  fail; overload/status responses remain distinct and redact paths, credentials, topology, and
  provider details.
- **Parallel proof serving:** publish hot-single-epoch, mixed-10/100-epoch, warm-open, cache hit/miss,
  and restored-snapshot QPS and p50/p95/p99 at 1/8/32/64 clients, including heap/native memory, file
  descriptors, block-cache hit rate, disk IOPS, bounded-executor rejection, and block-production
  latency. A request never opens a database-scoped store per proof.
- **Showcase:** no flag preserves the catalog; one/comma-separated/`all` selections materialize only
  supported fresh-chain profiles; invalid or post-genesis selection fails closed; capability
  discovery reports configured series and node-local availability without treating availability as
  consensus.
- **DPP boundary:** a selective dataset snapshot cannot advance event-body retention or shrink old
  primary entries. Period data routed into secondary snapshots may be archived/deleted locally with
  honest proof availability; any attempted `FULL_STATE` or body-pruning configuration fails startup
  until the follow-up recovery profile is released.
- **Rollback:** rollback crossing a finalized boundary → halt fail-closed (needs ADR-027 §2.4);
  rollback crossing an unfinalized boundary → asynchronous job/spool invalidated; rollback remaining
  above the boundary → no epoch-observation change. A disputed deep rollback marks the affected
  lineage `DISPUTED`, blocks new publication/archive eviction, and prevents undisputed proof claims.
- **Replay:** a fresh node with **no L1 history** rebuilds attested epochs from block history
  alone through `AppBlockExecutionContext` and reproduces every state root (this is G6, and the
  single most important test).

## 13. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Mainnet scale: estimated 90–120 MB canonical stake payload/epoch before trie overhead and ~1.3M authmap inserts | M4 publishes actual bytes/time/storage and gates M5; failure requires a separately specified two-level proof profile, not an unauthenticated index |
| R2 | Every member must run **full ledger state** — raises the bar for membership materially | startup gate + documented operator requirement; not every app-chain member can join this chain |
| R3 | **Snapshot mislabeling** — well-formed, agreed, anchored, and wrong | §6 wire discriminator + cross-verification merge gate |
| R4 | Depends on deep-rollback detection that does not exist | ADR-027 §2.4 is a hard prerequisite for every preprod/production epoch attestation, including M3 and M5–M6 |
| R5 | Direct append-only MPF retains multi-GB live data per mainnet-scale epoch | M4 result rejects it as the scalable default; opt-in logical per-epoch snapshots retain only descriptors in primary state, with independently archivable secondary roots |
| R6 | Catch-up throughput is capped at ~10 blocks/s (50 per 5 s tick) — a chunk-heavy chain lengthens onboarding | snapshot onboarding (§14.3 of the user guide); consider tuning the catch-up tick for this chain |
| R7 | Current governance reads lose complete enacted/expired history | persist epoch-pinned lifecycle transitions with delta-backed rollback before exposing them through `L1EpochState` |
| R8 | Many logical snapshots or parallel random-epoch proofs overload cache, file descriptors, or disk IOPS | one shared online RocksDB, in-memory descriptor catalog, bounded concurrent proof service, shared block cache, request/proof cache, backpressure, horizontal read-only proof replicas, and M8c load gates |
| R9 | Node-local archive failure is mistaken for consensus absence | separate consensus descriptor from local availability; explicit `SNAPSHOT_NOT_LOCAL`; reopen/root verification before eviction; peer retry/restore |
| R10 | Snapshot support is used to prune events that are still required for replay | only runtime-verified `FULL_STATE` recovery coverage may advance body retention; dataset snapshots never do; rollback margin and suffix replay are mandatory |

## 14. Resolved v1 product decisions

1. The three stdlib capabilities remain independently selectable and composable. The stock recipe
   is params-only; stake, proposal history, and DRep distribution require explicit opt-in.
2. Every v1 stake leaf is `[coin, poolHash]`. There is no `include-pool` mode and no duplicate coin
   key. Applications prove the one leaf and enforce the coin and/or pool projection they need.
3. Capability-disabled chains retain direct authenticated-map behavior. The scalable Cardano History
   profile uses a primary `mpf-blake2b256-v1` descriptor proof plus a secondary per-epoch
   `mpf-blake2b256-v1` claim proof. JMT at either level is off-chain-only. The nested wire/profile
   receives golden vectors and a Plutus budget gate before release.
4. The initial stake and DRep chunk size is 25,000 entries, with no more than one such chunk per app
   block and mandatory headroom under the 32,768-operation framework limit. M4 may lower this value
   before the genesis/profile format is released, but may not raise it beyond the proven bound.
5. Proposal records contain lifecycle status, reason, and canonical identifiers in v1. Complete
   proposal payloads and vote tallies, if later required, are independently versioned datasets.
6. The first applied block of the new epoch is the boundary identity. Heavy observation generation
   is asynchronous and recoverable; existing L1 epoch events and block processing remain unchanged.
7. The reusable epoch and authenticated-snapshot capability contracts remain in `core-api`, with
   generic physical storage/proof/archive behavior in runtime and Cardano adapters in stdlib. The
   optional, single-plugin Cardano History experience and its console/client/distribution concerns
   are specified by ADR app-layer/035; it adds no alternate state or proof protocol.
8. Logical snapshots share one online RocksDB per node. Separate immutable physical packages are
   created on archive, not at every epoch. Archive/restore/eviction are node-local; descriptor/root
   creation is consensus.
9. Permanent descriptor entries form both a primary-MPF random-access registry and a
   `previousSnapshotCommitment` chain per series. No redundant MPF-of-roots is introduced.
10. Snapshot capability selection is fresh-chain, explicit, and fail-closed. The showcase flag
    accepts one, comma-separated, or `all` capable chain IDs; omission preserves current behavior.
11. Dataset snapshots do not authorize app-block pruning. Only a complete, runtime-verified
    full-state recovery checkpoint plus rollback margin and replay suffix may advance retention.
