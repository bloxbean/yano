# App-Chain Pending Tasks (deferred with intent — do not lose)

Single collection point for items deliberately deferred across the ADR-008
program, each with its ADR reference and the trigger that should revive it.
Update this file whenever an iteration defers something; strike items when
they ship (move to the owning ADR's delivery notes).

## Protocol / consensus

| Item | Deferred in | Revive when |
|---|---|---|
| Per-member missed-window accountability metric (`yano.appchain.proposer.missed_windows_total{member}`) | 008.2 delivery notes (I2.1 deviation) | Needs per-member attribution design; next observability pass |
| BFT view-change mode (removes the 008.2 §0 residual split-vote stall) | 008.2 §2.8 (SequencerMode SPI keeps the slot open) | A deployment that cannot accept the split-vote runbook |
| Earliest-window proposal preference (convergence heuristic) | 008.2 delivery notes (dropped for first-acceptable-wins) | If split-vote observations occur in practice |
| `MembershipGovernance` as a public SPI (custom governance modes) | 008.3 §2.5 (interface designed, kept internal) | A second governance model (explicit ballots / separate quorum) |
| Ops runbook section in the user guide (split-vote resolution, governed break-glass, anchor sub-threshold pause) | referenced by 008.2 §0 / 008.3 §2.4 | Before recommending `rotating`/`governed` as defaults |

## L1 integration

| Item | Deferred in | Revive when |
|---|---|---|
| Full historical L1View (`utxosAt(address, slot)`) via **commitment-history + archival JMT proof-serving** (members store per-block 32-byte UTxO-set roots; archival nodes serve versioned-JMT proofs incl. exclusion proofs) | 008.4 §3.1 (architecture recorded, owner-proposed) | (a) sync hot-path benchmark of incremental commitment, (b) ccl JMT out of preview, (c) rotation-compatible archival role |
| **Checkpoint observations** — complete watched-state snapshots sequenced into the app ledger at anchor cadence (owner-proposed 2026-07-10; design note in 008.4 §3.3) | 008.4 §3.3 | Feasibility check of delta-window reconstruction (`forEachUtxoDeltaInSlotRange` retention vs `l1.stability-depth`); candidate for late Iteration 3 or Iteration 4 |
| Anchor leader election under rotation (today: the `anchor.enabled` node leads co-signing) | 008.4 §2.5 (v1 choice) | If anchor-leader liveness becomes a complaint; natural follow-on to rotation |
| Anchor identity pinning via governance (today: followers adopt identity from the first member-authenticated sign request verified against their own L1 view; a governed `~governance/anchor` command would pin it on-chain) | 008.4 delivery notes I3.1c | When membership becomes adversarial enough that "any current member is trusted to point at the right anchor" is too weak |
| Exact anchor ex-unit evaluation (today: fixed ceilings with headroom — spend 800M/3M, mint 200M/600K — priced into fees) | 008.4 delivery notes I3.1c | Evaluate via julc-vm/`JulcTransactionEvaluator` at build time when fee efficiency matters (large member sets or mainnet cost pressure) |
| Observation injection retry when the scheduled proposer is offline at drain time (v1: single deterministic drain per fact — the fact is skipped if that exact proposer is down; safe but lossy) | 008.4 delivery notes I3.2 | If skipped observations are noticed in practice; fix = keep drained facts N more L1 blocks and re-attempt on proposer change, dedup via observation key at the state-machine layer |
| Adopt cardano-client-lib QuickTx/`TxBuilderContext` for anchor tx construction (owner-requested 2026-07-10): both anchor services hand-roll fees/collateral/change following the I1.5 precedent; CCL's layer needs only UtxoSupplier + ProtocolParamsSupplier (both available in-node) and `withAdditionalSignersCount(n)` prices not-yet-attached witnesses exactly — which would have prevented the under-fee bug the I3.3 gate caught (draft sized before co-signed witnesses attached) | 008.4 delivery notes I3.3; 008.1 I1.5 | **Early Iteration 4** (owner emphasized twice during the I3.3 gate); adds cardano-client-function/quicktx to runtime deps |
| Vote-lock liveness wedge after crash-restart (found 2026-07-10 during the I3.3 gate): a node that vote-locked height H, crashed, and restarted after the locked proposal's messages EXPIRED warns "Locked proposal has expired — partial round cannot be re-gossiped" forever — the height can never finalize (the lock is honored but the locked block is un-proposable and no competing proposal can be voted). Needs design: expire the lock WITH the proposal TTL, or an operator unlock admin action (both must preserve the equivocation guarantee) | 008.2 vote-lock design; observed on `feat/adr008-iteration3` gate run | Iteration 4 correctness item — real crash-restart scenario, not devnet-only |
| Durable pending-observation queue across restarts (v1: in-memory ONLY — a proposer restart inside the stability window loses observed-but-not-yet-stable facts; safe but lossy). CAUTION: naive RocksDB persistence is UNSAFE — reloading a fact whose block was reorged away while the node was down can finalize a phantom fact if followers also restarted (empty windows → UNKNOWN → accepted). Fix requires reorg-safe reload: persist fact + block hash, re-verify against own chainstate before re-arming | 008.4 delivery notes I3.2 (same class as proposer-offline residual) | Together with the injection-retry item; becomes important when observation loss is operationally visible (long stability depths / frequent deploys) |
| BLS aggregate/threshold signatures for anchor advances (owner question 2026-07-10): replace N required-signer Ed25519 witnesses with one aggregate BLS sig verified on-chain via CIP-0381 builtins | 008.4 §2.2/§2.5 (v1 = Ed25519 required-signers) | Only at large committee sizes (≳50 members: tx-size, not budget, is the binding constraint) or when single-submitter UX matters; costs: separate BLS keys (no wallet reuse), proof-of-possession/DKG ops, in-script pairing budget vs free phase-1 ledger verification. Natural to revisit alongside the E7.5 ZK-verified advance (one succinct proof of the finality cert subsumes it) |

## L1 node track (not app-chain, found by app-chain work)

| Item | Recorded in | Notes |
|---|---|---|
| Peer store seeds the configured upstream even with client sync disabled — devnet nodes list (and would peer-share) the preprod relay | 008.1 delivery notes ("deliberately skipped") | Fix = gate `seedPeerStoreFromConfiguredPeers()` on `isClientEnabled()` + update `SyncSubsystemTest` expectations |
| First-boot chain-sync wedge (follower connected during producer's earliest blocks froze at tip; restart recovers) | 008.1 delivery notes; `adr/todo_header_continuity_recovery.md` | Known header-continuity gap; reproduced 2026-07-10 during the I-1 gate |

## DX track (ADR-008 §4 Iteration 4 / 008.5 — headline items already planned there)

Typed query surface (revive `AppStateMachine.query()`), SDK anchor
verification loop (`L1AnchorSource` + slim evidence module), API-key scopes +
admin audit log, mTLS/OIDC recipes, reference KMS SignerProvider jar,
`yano-appchain-client-zk` split, Quarkus client extension, Maven archetype,
Kafka per-record MPF proofs, `stateVersion()` fail-fast (008 §6.3),
fleet strip + machine-aware panels in the status page (after typed queries).

## Standing gates (unchanged)

- E7.4 private balances / E7.5 ZK anchors: gated on ZeroJ ADR-0026 production
  criteria; E7.5 builds on the 008.4 julc anchor validator.
- `authScheme=2` fully anonymous transport: touches the yaci core auth path
  (ADR-006 follow-up).
- Positioning language: keep ADR-007's honesty rules until the corresponding
  capability ships (metadata anchors ≠ settlement; script anchors change this
  for chains that adopt A2).
