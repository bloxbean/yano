# ADR app-layer/028: Historical L1 ledger-state attestation chain

## Protocol parameters, stake, and governance

**Status:** Accepted — M1–M7 implemented; M8 in progress
**Date:** 2026-08-09
**Depends on:** ADR app-layer/027 §2.4 (deep-rollback detection) — implementation and devnet work may
proceed, but this is **blocking for every production/preprod epoch attestation**, including M3 and
M5–M6
**Extends:** ADR app-layer/008.4 §3 (L1View / observations), ADR-025.x (authenticated map)
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

**Non-goals**

| # | Non-goal |
|---|---|
| NG1 | Arbitrary "UTxO set at address A as of slot S" — still deferred (ADR-008.4 §3.1) |
| NG2 | Negative facts outside epoch-boundary data |
| NG3 | A general DBSync replacement — this attests a pinned, small set of epoch facts |
| NG4 | Mutating history — corrections **supersede**, never overwrite (§8.3) |

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
| State | `stake/<epoch>/<credType><credHash>` → `[coin, poolHash]`; `stake/<epoch>/chunks/<index>` → `chunkHash`; `stake/<epoch>/meta` → manifest + received count + complete |
| Completeness | the epoch is **not** marked complete until all `chunkCount` chunks applied **and** the recomputed root equals `snapshotRoot` |

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

Queries and typed proofs must reject an incomplete epoch. A positive stake assertion proves both
the credential entry and `stake/<epoch>/meta.complete = true` at the same state root (two point
proofs initially, or a later MPF multiproof). An exclusion/zero-stake assertion also requires the
same completeness proof.

**`epoch-governance`** — proposal lifecycle plus chunked DRep distribution.

| Aspect | Value |
|---|---|
| Topic | `~l1/<governance-observer-id>` |
| Proposal claim | `[epoch, txHash, govActionIndex, actionType, status, reason, proposedEpoch, expiresAfterEpoch]` |
| DRep manifest | `[epoch, totalEntries, chunkEntries, chunkCount, distributionRoot]` |
| DRep chunk | `[epoch, distributionRoot, chunkIndex, entries]`, entries = `[[drepType, drepHash, coin], ...]` in canonical order |
| Proposal state | `governance/<epoch>/proposals/<txHash>/<govActionIndex>` → canonical lifecycle record |
| DRep state | `governance/<epoch>/dreps/<drepType>/<drepHash>` → coin |
| Metadata | `governance/<epoch>/proposals/meta` and `governance/<epoch>/dreps/meta` carry independent roots/counts/completeness |

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
| **Populator** | the **state machine**, in `apply()` | `writer.put(...)` per entry from the sequenced chunks |

The proposer supplies the data but **cannot lie about it**, because the fact is canonical and
every member re-derives it. Identical trust model to the EUTxO bridge deposit; only the
payload is bigger.

### 5.7 Authenticated storage decision

**Decision: Variant A with MPF is the v1 reference profile.** Stake and DRep entries live directly in
the authenticated map. A chain that needs Cardano validators to consume its proofs pins
`mpf-blake2b256-v1` with `state.l1-proof-consumption-required=true`. A chain may instead select
`jmt-blake2b256-v1` at initial creation for off-chain verification. The selection is part of the
immutable `StateCommitmentIdentity`; it is not a mutable Cardano-History-specific setting and cannot
change without creating a fresh chain generation. Classic JMT does not satisfy this ADR's on-chain
verification requirement.

M4 is a feasibility gate for this decision, not an invitation to substitute an unauthenticated
derived index. If direct MPF insertion is operationally infeasible, M5 stops and a follow-up ADR must
specify a two-level proof profile: an MPF proof of the epoch's secondary snapshot root plus a bounded,
on-chain-verifiable credential proof under that root. The former "chunks plus derived index" Variant
B is not accepted because an index rebuilt from block bodies alone is not a trustless proof.

`retention.enabled: false` remains mandatory for the reference chain because from-genesis replay of
the state machine requires retained observation bodies.

MPF reachability pruning is a separate, node-local authenticated-state storage policy. It may remove
construction nodes and roots below a configured proof-retention boundary, but never application
entries reachable from any retained root. Because Cardano History is append-only, its latest root
still authenticates every completed historical epoch. Pruning only changes which older anchor-height
roots the node can use to generate a new proof; an already-issued proof remains independently
verifiable from its root. The feature is opt-in and disabled by default until the M4/M8 tests prove
restart, retained-root inclusion/absence proofs, rollback safety, and multi-epoch storage behavior.

### 5.8 Module boundaries

Epoch observation is a framework capability. Protocol parameters, epoch stake, and governance are
out-of-box applications, packaged in the existing standard library but independently selectable.
They use these module and package boundaries:

| Module/boundary | Responsibility |
|---|---|
| `core-api` | `L1EpochObserver`, provider, boundary, manifest, sink, and epoch-pinned `L1EpochState` SPIs only |
| `runtime` | asynchronous first-new-epoch detection through the existing `BlockAppliedEvent` subscription, host `L1EpochState` adapter, stability gate, durable bounded spool, proposal verification, rollback, and lifecycle wiring |
| `appchain-stdlib-contracts/.../l1/epoch` | Shared canonical snapshot semantics, manifests/chunks, and internal contract utilities |
| `appchain-stdlib-contracts/.../l1/epoch/params` | Parameter claim codecs, state keys, query records, and typed proof subjects |
| `appchain-stdlib-contracts/.../l1/epoch/stake` | Stake claim codecs, state keys, completeness records, and typed proof subjects |
| `appchain-stdlib-contracts/.../l1/epoch/governance` | Proposal-status and DRep-distribution codecs, state keys, completeness records, and typed proof subjects |
| `appchain-stdlib/.../l1/epoch/params` | First-party parameter observer, component/state-machine transition, provider, query adapter, and configuration |
| `appchain-stdlib/.../l1/epoch/stake` | First-party chunked stake observer, transition, provider, completeness logic, and configuration |
| `appchain-stdlib/.../l1/epoch/governance` | First-party governance observer, proposal/DRep transitions, provider, completeness logic, query adapter, and configuration |

All three capabilities ship in the default distribution but are disabled unless selected. Shipping
code is not permission to traverse stake or DRep snapshots: a params-only deployment performs no
stake or governance work. They may be composed into one deployed `AppStateMachine`, or reused
individually inside a custom machine. None is a dependency of the app-chain runtime, and stdlib
must depend only on the `core-api` epoch view—not concrete `ledger-state` or `runtime` classes.

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
Every production epoch attestation requires **ADR-027 §2.4 deep-rollback detection**, which does not exist today
(`onL1RollbackWithinGeneration` never compares the rollback target against what has been
finalized; `BridgeRollbackGuard` is written but has no production caller). Here the predicate
is clean and low-cardinality: *"epoch E's attestation is disputed"* — unlike "which of these
4,000 deposits is now unbacked." With `epoch-stability-depth = k` the event should be
effectively unreachable, but it must fail closed rather than silently.

This applies equally to protocol parameters, stake, and governance. M1–M3 implementation and
devnet testing may proceed without the guard, but no preprod/production pilot may finalize these
facts until the guard is wired.

### 8.3 Corrections supersede, never mutate
`params/<epoch>`, `stake/<epoch>/*`, and `governance/<epoch>/*` are **write-once**. A correction lands as a new
versioned record (`params/<epoch>/v<n>`) authorized by the chain's governance role, with the
prior version retained. Proofs name the version. This is native to an append-only ledger and
keeps every previously issued proof verifiable.

## 9. Proof and verification

A consumer verifies a historical value in two levels, trusting nobody:

1. **Inclusion proof** — `stake/<epoch>/<credential>` → value, against `stateRoot` at height H
   (existing ADR-025 authmap proof bundle).
2. **Anchor** — `stateRoot` at H is covered by an L1-confirmed, stability-deep anchor
   transaction (existing metadata or script anchor).

So: fetch the anchor tx from Cardano, read the root, verify the inclusion proof. No trust in
the chain operator, and no dependency on the chain still running.

**On-chain verification** uses the already-implemented ADR-031 Phase 6 path:
`mpf-proof-wire-v1`, the strict normalized-proof converter, the generic `MpfOnChainVerifier`, and
the eleven-field anchor datum that binds chain/application/commitment identity and state root.
The on-chain reference chain pins `mpf-blake2b256-v1` and
`state.l1-proof-consumption-required=true`. A `jmt-blake2b256-v1` Cardano History chain exposes the
same typed subjects and anchor binding to off-chain clients, but must advertise and enforce
`state.l1-proof-consumption-required=false`; it has no Cardano validator proof path. The bounded on-chain MPF
envelope permits keys up to 256 bytes, values up to 8 KiB, and at most 32 folds; every concrete
params/stake/governance value and key must be tested against those existing limits.

A stake amount/pool assertion uses one proof of the canonical `[coin, poolHash]` leaf. The
application-specific validator decodes that proven value and applies its own predicate, for example
`coin >= minimum`, `poolHash == expectedPool`, or both. It does not need separate authenticated
coin and pool keys. Positive, zero/absence, and delegation assertions also verify
`stake/<epoch>/meta.complete = true` at the **same state root**. Until an MPF multiproof exists this
is two normalized proofs, and M4 must establish that their combined Plutus budget is acceptable.

ADR-031 typed proof subjects hide the physical composite namespace and bind an epoch-param, stake,
proposal-status, or DRep-distribution claim to its canonical key/value schema. The first reference
validator should use the small `params/<epoch>` fact before attempting a mainnet-scale stake
profile.

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

## 11. Milestones

| # | Milestone | Gate |
|---|---|---|
| **M1** | Replace preview observation wire with the tagged baseline + CDDL + new golden vectors | transaction and epoch anchors strict; old preview bytes rejected |
| **M2** | Streaming `L1EpochObserver` / `L1EpochState` / provider SPI; asynchronous first-new-epoch `BlockAppliedEvent` coordinator; durable bounded spool/outbox; block-depth `epoch-stability-depth`; startup gates | publisher latency is independent of snapshot size; two-member devnet agrees on a synthetic epoch fact; restart and proposer rotation resume the next chunk |
| **M3** | `epoch-params` state machine using ADR-031 `AppBlockExecutionContext` + client + `StateMachineConformance` + devnet e2e | preprod params match yaci-store `epoch_param` and Koios; no historical L1 handle is used during replay |
| **M4** | **Authenticated-state feasibility benchmarks**: canonical two-pass stake/DRep generation, 25k-entry chunks, direct MPF baseline, reachability-pruned MPF, classic JMT, storage/restart/proof generation, and combined MPF stake+completeness on-chain verification at mainnet scale | direct MPF meets correctness/Plutus gates; MPF pruning preserves every retained root; JMT publishes comparable off-chain CPU/storage/proof results |
| **M5** | MPF `epoch-stake` machine with mandatory `[coin, poolHash]` values — **blocked on M4 and, for production, ADR-027 §2.4** | mainnet-scale epoch attested within the configured ingestion SLA; amount-only, pool-only, combined, and absence+completeness proofs pass on-chain without exceeding any per-block budget |
| **M6** | `epoch-governance` component: rollback-safe proposal history plus chunked DRep distribution — **blocked on M4 and ADR-027 §2.4** | proposal and DRep claims match independent sources; proposal-only configuration performs no DRep traversal |
| **M7** | Typed proof surface and application semantic decoders on top of the implemented ADR-031 REST/client/evidence/generic MPF verifier | third party verifies params, stake amount/pool predicates, proposal status, and DRep amount from the anchor output alone |
| **M8** | Preprod pilot, 3 members, ≥10 consecutive epochs — **blocked on ADR-027 §2.4** | full cross-verification (§12) green |

M1–M3 are the useful, self-contained increment. **M3 is the smallest thing that proves the
whole idea** and is worth building before committing to M5.

### 11.1 M4 questions and M5 acceptance decision

M4 evaluates the released MPF and classic-JMT authenticated-map profiles rather than comparing
underspecified storage models. MPF remains the on-chain decision; JMT is an optional off-chain
choice. It records, on representative mainnet-scale data:

1. canonical-CBOR bytes and chunk count for 25,000-entry `[coin, poolHash]` chunks;
2. wall time, peak heap/native memory, and restart behavior of both generation passes;
3. wall time and RocksDB growth for approximately 1.3M MPF and JMT inserts on every member;
4. time from L1 boundary detection until the complete epoch is app-final and L1 anchored;
5. three-member agreement while normal L1 synchronization continues without publisher-thread delay;
6. replay/catch-up time and retained storage growth per epoch and projected per year;
7. inclusion/exclusion proof generation latency, normalized proof size, and fold count;
8. combined Plutus CPU/memory/redeemer size for a stake leaf plus same-root completeness proof;
9. process death in each spool state and proposer rotation during a partially ingested epoch; and
10. rollback before stability, ensuring the asynchronous job and partial spool are cancelled; and
11. MPF mark/sweep duration, nodes and bytes reclaimed, post-compaction size, restart, and proof
    verification from every retained root. The unpruned 3.84 GB single-epoch result is a baseline,
    not the claimed steady-state cost per epoch, until this reachability test completes.

Before M4 is declared complete, the implementation report publishes the accepted ingestion SLA and
operator CPU/memory/storage budgets. Hard correctness gates are already fixed: no app block may
exceed 32,768 state operations; no message/block may exceed the configured framework bounds; proof
keys/values/folds must fit the released ADR-031 on-chain envelope; the BlockApplied publisher must do
no dataset-sized work; and the complete epoch must be finalized before its required L1 source can be
pruned. If any hard gate fails, M5 does not begin.

**Implementation learning.** Questions 1–3, 6–8, and the persistent restart portion of 9 are the
pre-M5 feasibility gate. Questions that require the concrete stake machine and a deployed chain—4,
5, the proposer-rotation portion of 9, and 10—remain hard M5/M8 release gates and may not be inferred
from the component benchmark. This staging removes the circular requirement to run a stake chain
before M5 has implemented one without weakening its release criteria. The accepted measurements and
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
  marked complete; reordered chunk → root mismatch; duplicate chunk → deterministic no-op;
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
  and absence-plus-completeness on-chain predicates without separate coin/pool state keys.
- **Rollback:** rollback crossing a finalized boundary → halt fail-closed (needs ADR-027 §2.4);
  rollback crossing an unfinalized boundary → asynchronous job/spool invalidated; rollback remaining
  above the boundary → no epoch-observation change.
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
| R5 | Direct MPF insertion and two-proof on-chain stake verification at this scale are unproven | M4 measures the fixed MPF profile; if infeasible, M5 stops and the proof architecture is revised explicitly rather than silently degrading to off-chain-only |
| R6 | Catch-up throughput is capped at ~10 blocks/s (50 per 5 s tick) — a chunk-heavy chain lengthens onboarding | snapshot onboarding (§14.3 of the user guide); consider tuning the catch-up tick for this chain |
| R7 | Current governance reads lose complete enacted/expired history | persist epoch-pinned lifecycle transitions with delta-backed rollback before exposing them through `L1EpochState` |

## 14. Resolved v1 product decisions

1. The three stdlib capabilities remain independently selectable and composable. The stock recipe
   is params-only; stake, proposal history, and DRep distribution require explicit opt-in.
2. Every v1 stake leaf is `[coin, poolHash]`. There is no `include-pool` mode and no duplicate coin
   key. Applications prove the one leaf and enforce the coin and/or pool projection they need.
3. The reference on-chain profile is direct authenticated-map storage under
   `mpf-blake2b256-v1`. JMT is off-chain-only. An alternative secondary-tree layout requires a new
   proof-profile ADR.
4. The initial stake and DRep chunk size is 25,000 entries, with no more than one such chunk per app
   block and mandatory headroom under the 32,768-operation framework limit. M4 may lower this value
   before the genesis/profile format is released, but may not raise it beyond the proven bound.
5. Proposal records contain lifecycle status, reason, and canonical identifiers in v1. Complete
   proposal payloads and vote tallies, if later required, are independently versioned datasets.
6. The first applied block of the new epoch is the boundary identity. Heavy observation generation
   is asynchronous and recoverable; existing L1 epoch events and block processing remain unchanged.
7. The reusable epoch capabilities remain in `core-api` and stdlib. The optional, single-plugin
   Cardano History experience and its console/client/distribution concerns are specified by
   ADR app-layer/035; it adds no alternate state or proof protocol.
