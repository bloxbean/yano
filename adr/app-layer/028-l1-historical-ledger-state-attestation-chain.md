# ADR app-layer/028: Historical L1 ledger-state attestation chain

## Protocol parameters, stake, and governance

**Status:** Proposed — implementation plan, no code written
**Date:** 2026-08-09
**Depends on:** ADR app-layer/027 §2.4 (deep-rollback detection) — **blocking for M5–M6**
**Extends:** ADR app-layer/008.4 §3 (L1View / observations), ADR-025.x (authenticated map)
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

public record L1EpochBoundary(long epoch,
                              long boundarySlot,
                              byte[] boundaryBlockHash,
                              long boundaryBlockNumber) {}
```

`L1EpochState` is a **narrow, read-only, epoch-pinned** handle, valid only for the dynamic
extent of the callback and not retainable — the same discipline
`AppStateMachine.query(path, params, AppQueryContext)` already imposes:

```java
public interface L1EpochState {
    long epoch();

    /** Canonical, epoch-pinned parameters. Rationals, never floats (§7 D4). */
    ProtocolParamsView protocolParams();

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
     * Virtual DReps use their canonical type and value representation.
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

`prepare` performs the deterministic first pass that fixes snapshot semantics, total entries,
chunk-entry count, chunk count, and snapshot root. `writeObservations` performs a second canonical
pass and streams the manifest/chunks into a host-owned sink. It must not return a
`List<L1Observation>`: mainnet epoch stake is tens of megabytes, and materializing or injecting the
whole list is unnecessary preview debt.

The host sink writes each bounded observation into a durable local epoch-observation spool keyed by
`(observerId, epoch, manifestRoot, chunkIndex)`. The callback may not retain the state or sink.

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
anchor = [0, tx-hash]   ; transaction pointer
       / [1, epoch]     ; epoch-boundary pointer
```

- `decode()` accepts only the tagged supported format. The transaction-only preview format is
  deleted rather than retained as a second wire path.
- `key()` becomes `observerId + '/' + anchorKey + '/' + slot`, where `anchorKey` is
  `tx:<hex>` or `epoch:<n>`.
- The storage/app-chain format marker and plugin API version reject old observation bytes before
  execution.
- *Rejected alternative:* set `txHash = sha256("yano-epoch-anchor" ‖ chainId ‖ epoch)`. It produces
  a value that looks like a transaction hash and is not one.

### 5.3 Runtime wiring

- Extend `AppChainSubsystem.subscribeL1Events` to acquire a **third** subscription,
  `PostEpochTransitionEvent`, in the same transactional block (a half-installed L1 view is
  already treated as a start failure — keep that property). `PostEpochTransitionEvent` is the
  correct hook: it fires after Pre (param finalization), SNAP (mark snapshot) and POOLREAP,
  and all three precede the first `BlockAppliedEvent` of the new epoch.
- Replace the in-memory all-ready `pendingInjection` behavior for epoch data with a durable,
  byte-bounded observation spool/outbox. All members build the same spool for verification; the
  scheduled proposer injects only the configured message/byte budget per app block.
- The spool survives restart, prunes entries invalidated by an L1 rollback before finality, and
  reconciles finalized observation ids so a rotating/new proposer resumes at the next missing chunk
  without duplicating or skipping data.
- Reuse `L1ObservationService` verification/window semantics, but change `drainInjectable()` into a
  bounded poll/ack protocol. Do not preserve the preview drain-all API.
- `verifyProposalObservations` and `verifyCatchUpObservations` consume the same canonical spool
  records and remain fail-closed.

### 5.4 Stability gating

New key `yano.app-chain.l1.epoch-stability-depth` (default: falls back to
`l1.stability-depth`).

- Injection gated on `boundarySlot + epochStabilityDepth <= stableTipSlot`.
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

Sizing (ADR-027 §4.6): ~1.3M mainnet credentials, ~50 B/entry → **~65 MB/epoch**, ~17–26
messages at `max-message-bytes: 4MB`, **~4.7 GB/year**. Note `MAX_MESSAGE_BYTES` is ~16 MB
(`MAX_BLOCK_BYTES` 16 MiB − 5,120 headroom); the 64 KB figure is only
`DEFAULT_MAX_MESSAGE_BYTES`. The bounded outbox spreads chunks across several blocks so a boundary
is never a 65 MB pool/memory burst.

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

### 5.7 Storage variant to benchmark

- **Variant A (specified default):** stake and DRep entries live in the authmap. Direct inclusion
  proofs (G4), larger trie.
- **Variant B (benchmark-gated):** chunks live in block history; only the stake/DRep epoch metadata
  lives in the trie; the maps are rebuilt as off-consensus indexes. Replay-safe like A, much smaller
  trie, but proofs need the derived index. **Requires `retention.enabled: false`** — pruning
  bodies destroys the source (`AppLedgerStore.pruneBodiesBelow` strips bodies; its own javadoc
  notes from-genesis catch-up past the horizon then becomes impossible).

Decide between A and B on the M4 benchmark, not in advance.

### 5.8 Module boundaries

Epoch observation is a framework capability. Protocol parameters, epoch stake, and governance are
out-of-box applications, packaged in the existing standard library but independently selectable.
They use these module and package boundaries:

| Module/boundary | Responsibility |
|---|---|
| `core-api` | `L1EpochObserver`, provider, boundary, manifest, sink, and epoch-pinned `L1EpochState` SPIs only |
| `runtime` | `PostEpochTransitionEvent` subscription, host `L1EpochState` adapter, stability gate, durable bounded spool, proposal verification, rollback, and lifecycle wiring |
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
Client convenience methods may live in `appchain-client`, consume `appchain-stdlib-contracts`, and
continue using the generic `state/proof/{key}` endpoint; no epoch-specific core REST routes are
required. A future artifact split is justified only by heavyweight optional dependencies or a
genuinely independent release lifecycle, not by data volume alone.

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
- Cross-verification against yaci-store **and** DBSync is a **merge gate**, not a nice-to-have
  (§12). This matches the repo's standing rule that the Haskell ledger is the source of truth
  and yaci-store is the cross-reference.
- Governance claims carry an explicit boundary epoch. DRep distribution uses the distribution
  calculated for that boundary epoch. Proposal status is the lifecycle state after that boundary's
  enact/drop and ratification phases; the discriminator is committed in the governance contract so
  consumers cannot reinterpret `RATIFIED` as already `ENACTED`.

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
`onL1Rollback` prunes the epoch pending queue exactly as it does block observations —
existing code, no change.

### 8.2 After finality
Requires **ADR-027 §2.4 deep-rollback detection**, which does not exist today
(`onL1RollbackWithinGeneration` never compares the rollback target against what has been
finalized; `BridgeRollbackGuard` is written but has no production caller). Here the predicate
is clean and low-cardinality: *"epoch E's attestation is disputed"* — unlike "which of these
4,000 deposits is now unbacked." With `epoch-stability-depth = k` the event should be
effectively unreachable, but it must fail closed rather than silently.

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

**On-chain verification** requires care. ADR-025 offers two commitment profiles: MPF and
classic JMT. MPF proofs are verifiable inside Plutus (cardano-client-lib MPF + existing
product-local eUTxO validators demonstrate the cryptography), but the generic app-chain proof ABI,
converter, anchor-reference binding, and reusable validator are ADR-031 Phase 6 work. JMT
verification is designed as follower-side/off-chain — ADR-008.4 explicitly notes JMT's on-chain
cost is irrelevant *because* verification is off-chain. **If on-chain verification of
protocol-param, stake, proposal-status, or DRep-distribution proofs is a requirement, the MPF
profile must be pinned**, the ADR-031 generic verifier is a dependency, and MPF insert cost at
1.3M entries/epoch is unproven — see R5.

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
      block: { interval-ms: 1000 }
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
          chunk-entries: 50000
          include-pool: true
        epoch-governance:
          type: l1-epoch-governance-v1
          include-proposals: true
          include-drep-distribution: true
          drep-chunk-entries: 50000
      machines:
        l1state:
          profile: yano-l1state-v1
          snapshot-semantics: end-of-epoch
```

**Hard startup gates** (fail to start, same shape as the existing
`observers require stability-depth > 0` check):

1. ledger state enabled and epoch snapshots being computed;
2. `yano.ledger-state.snapshot-retention-epochs` ≥ the chain's horizon (unbounded for an
   archival chain) — otherwise the *verification source* is pruned out from under the chain;
3. `retention.enabled: false` on this chain;
4. `epoch-stability-depth > 0` when any epoch observer is configured.
5. the governance observer requires Conway governance tracking plus rollback-safe proposal-history
   persistence; DRep distribution additionally requires the requested epoch snapshot to be retained.

## 11. Milestones

| # | Milestone | Gate |
|---|---|---|
| **M1** | Replace preview observation wire with the tagged baseline + CDDL + new golden vectors | transaction and epoch anchors strict; old preview bytes rejected |
| **M2** | Streaming `L1EpochObserver` / `L1EpochState` / provider SPI; `PostEpochTransitionEvent`; durable bounded spool/outbox; `epoch-stability-depth`; startup gates | two-member devnet agrees on a synthetic epoch fact; restart and proposer rotation resume the next chunk |
| **M3** | `epoch-params` state machine using ADR-031 `AppBlockExecutionContext` + client + `StateMachineConformance` + devnet e2e | preprod params match yaci-store `epoch_param` and Koios; no historical L1 handle is used during replay |
| **M4** | **Benchmark**: stake and DRep snapshot recompute + root cost per epoch at mainnet scale; Variant A vs B; MPF vs JMT | decides §5.7 and §9 |
| **M5** | `epoch-stake` machine (chunked) — **blocked on M4 and on ADR-027 §2.4** | mainnet-scale epoch attested within the configured ingestion SLA without exceeding any per-block budget |
| **M6** | `epoch-governance` component: rollback-safe proposal history plus chunked DRep distribution — **blocked on M4 and ADR-027 §2.4** | proposal and DRep claims match independent sources; proposal-only configuration performs no DRep traversal |
| **M7** | Typed proof surface: REST + client + evidence bundle; ADR-031 generic on-chain MPF verifier if pinned | third party verifies params, stake, proposal status, and DRep amount from the anchor output alone |
| **M8** | Preprod pilot, 3 members, ≥10 consecutive epochs | full cross-verification (§12) green |

M1–M3 are the useful, self-contained increment. **M3 is the smallest thing that proves the
whole idea** and is worth building before committing to M5.

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
- **Rollback:** rollback below an attested boundary → halt fail-closed (needs ADR-027 §2.4);
  rollback above → pending queue pruned, no state change.
- **Replay:** a fresh node with **no L1 history** rebuilds attested epochs from block history
  alone through `AppBlockExecutionContext` and reproduces every state root (this is G6, and the
  single most important test).

## 13. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Mainnet scale: ~65 MB/epoch, ~4.7 GB/yr, 1.3M authmap inserts/epoch | M4 benchmark gates M5; Variant B fallback |
| R2 | Every member must run **full ledger state** — raises the bar for membership materially | startup gate + documented operator requirement; not every app-chain member can join this chain |
| R3 | **Snapshot mislabeling** — well-formed, agreed, anchored, and wrong | §6 wire discriminator + cross-verification merge gate |
| R4 | Depends on deep-rollback detection that does not exist | ADR-027 §2.4 is a hard prerequisite for M5–M6 |
| R5 | On-chain proof verification needs the MPF profile, whose cost at this scale is unproven | M4 measures MPF vs JMT; if MPF is infeasible, G4 degrades to off-chain-only and that must be stated, not discovered |
| R6 | Catch-up throughput is capped at ~10 blocks/s (50 per 5 s tick) — a chunk-heavy chain lengthens onboarding | snapshot onboarding (§14.3 of the user guide); consider tuning the catch-up tick for this chain |
| R7 | Current governance reads lose complete enacted/expired history | persist epoch-pinned lifecycle transitions with delta-backed rollback before exposing them through `L1EpochState` |

## 14. Open questions

1. The three stdlib capabilities remain independently selectable and composable. Should the stock
   recipe enable all three, or ship params-only and require explicit opt-in for the large stake and
   DRep datasets? **Leaning params-only by default.**
2. Does the stake attestation include `poolHash` (delegation target) or only `coin`? Including
   it is ~60% more bytes and makes the chain far more useful for delegation analytics.
   **Leaning include, behind `include-pool`.**
3. Should proposal records include only the lifecycle status and reason, or also the complete
   proposal payload and vote tallies? **Leaning status and canonical identifiers in v1; add payload
   and tallies only as separately versioned datasets.**
