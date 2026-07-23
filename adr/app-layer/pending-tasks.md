# App-Chain Pending Tasks (deferred with intent — do not lose)

> **Tracking note (2026-07-17):**
> [open_item.md](open_item.md) is now the canonical live app-layer work index.
> This file retains the detailed ADR-008-era rationale and revival triggers.
> Keep every still-open row indexed in `open_item.md`, and remove/annotate rows
> here when they ship so the two documents do not contradict one another.

Single collection point for items deliberately deferred across the ADR-008
program, each with its ADR reference and the trigger that should revive it.
Index new app-layer work in `open_item.md`; add ADR-008-specific rationale here
when useful. Strike items when they ship and move evidence to the owning ADR's
delivery notes.

## Protocol / consensus

| Item | Deferred in | Revive when |
|---|---|---|
| Per-member missed-window accountability metric (`yano.appchain.proposer.missed_windows_total{member}`) | 008.2 delivery notes (I2.1 deviation) | Needs per-member attribution design; next observability pass |
| ~~Block proposal can exceed `maxMessageBytes` → follower rejects → consensus stalls~~ **DONE 2026-07-11 (block-bytes fix)** — a proposal is diffused as ONE `~consensus/propose` app message whose body IS the whole serialized block, so bounding it by the per-message `max-message-bytes` was wrong. Fix: (1) a first-class `block.max-bytes` cap (default 4 MiB; `max-message-bytes` default kept at 64 KiB, `block.max-messages` raised to 5000 as a flood backstop); (2) the leader serializes-and-trims each proposal to fit `block.max-bytes`, draining a large backlog across blocks; (3) the verifier rejects an oversized proposal (> `block.max-bytes`) and any inner message over `max-message-bytes`, instead of silently dropping; (4) the transport frame limit is derived from `block.max-bytes` (+64 KiB margin); (5) **the multi-chain inbound validator (`AppChainManager.verifyByChain`) now size-caps by topic** — reserved `~` topics (proposals) get the `block.max-bytes` cap, ordinary user messages keep `max-message-bytes`. This last gate was the actual binding limit the cluster load test exposed (single-chain worked; multi-chain stalled at height 0). Gated 2026-07-11: 3-node devnet finalized 2000-msg and 1500-msg loads with no stall, blocks up to 1300 msgs, identical state roots; regression `AppChainMultiChainIntegrationTest.largeProposal_exceedsMaxMessageBytes_stillFinalizesAcrossNodes` (fails-without/passes-with) + `AppChainBlockBytesTest` (single-node trim-across-blocks) | delivery note in `008-appchain-next-iteration-plan.md` tracking table | — |
| Pool straggler on non-proposer submit (found by the block-bytes cluster gate, 2026-07-11): a message submitted to a FOLLOWER of a fixed-proposer chain and lost on the follower→proposer gossip relay is never proposed, so it lingers in follower pools forever (observed ~1 in 4000 under `--spread` load; does NOT stall the chain or diverge state roots — all finalized blocks match). Proposer-direct submit finalizes 100%. Fix = periodic re-gossip of aged pending pool messages toward the proposer, or a submit-side hint to prefer the current proposer | cluster load-test finding; `AppMsgPool` has no re-gossip/TTL-retry for un-proposed messages | When follower-submit loss is operationally visible, or when clients can't target the proposer |
| BFT view-change mode (removes the 008.2 §0 residual split-vote stall) | 008.2 §2.8 (SequencerMode SPI keeps the slot open) | A deployment that cannot accept the split-vote runbook |
| Earliest-window proposal preference (convergence heuristic) | 008.2 delivery notes (dropped for first-acceptable-wins) | If split-vote observations occur in practice |
| `MembershipGovernance` as a public SPI (custom governance modes) | 008.3 §2.5 (interface designed, kept internal) | A second governance model (explicit ballots / separate quorum) |
| Ops runbook section in the user guide (split-vote resolution, governed break-glass, anchor sub-threshold pause) | referenced by 008.2 §0 / 008.3 §2.4 | Before recommending `rotating`/`governed` as defaults |
| ~~Single-connection app transport — reuse the L1 peer session for protocols 100/103~~ **DONE 2026-07-12 (shared app transport)** — yaci `feat/yaci_103_enhancement` (`0.5.0-pre12`: `AppProtocolManager` carries the 103 client agent, `enableAppChainSync()` on PeerClient/N2NPeerFetcher, `isAppLayerNegotiated()` gate, call-order safety) + Yano `yano.app-chain.transport.mode: shared` (DEFAULT) | `dedicated`. `AppChainManager.wrapPeerClientFactory` decorates the sync factory: L1 sessions to an upstream that is also an app peer are armed with 100/103 pre-connect; per-peer `SharedAppPeerLink` prefers the shared session with a grace-then-dedicated-fallback state machine (`AppPeerLink` seam; dedicated `AppPeerClient` unchanged and still selectable for bandwidth isolation). Catch-up over the shared 103 agent is single-owner routed across chains. 16 unit tests + devnet cluster gate PASSED 12/12 (single connection, diffusion, 103 catch-up, fallback engage/retire, dedicated control; the gate caught 3 wiring bugs — see the ADR-008 tracking row). Inbound was already unified (one server port) — this closes the outbound double dial | ADR 005 M1 note in `AppPeerClient` javadoc | — |

## L1 integration

| Item | Deferred in | Revive when |
|---|---|---|
| Full historical L1View (`utxosAt(address, slot)`) via **commitment-history + archival JMT proof-serving** (members store per-block 32-byte UTxO-set roots; archival nodes serve versioned-JMT proofs incl. exclusion proofs) | 008.4 §3.1 (architecture recorded, owner-proposed) | (a) sync hot-path benchmark of incremental commitment, (b) ccl JMT out of preview, (c) rotation-compatible archival role |
| **Checkpoint observations** — complete watched-state snapshots sequenced into the app ledger at anchor cadence (owner-proposed 2026-07-10; design note in 008.4 §3.3) | 008.4 §3.3 | Feasibility check of delta-window reconstruction (`forEachUtxoDeltaInSlotRange` retention vs `l1.stability-depth`); candidate for late Iteration 3 or Iteration 4 |
| Anchor leader election under rotation (today: the `anchor.enabled` node leads co-signing) | 008.4 §2.5 (v1 choice) | If anchor-leader liveness becomes a complaint; natural follow-on to rotation |
| Anchor identity pinning via governance (today: followers adopt identity from the first member-authenticated sign request verified against their own L1 view; a governed `~governance/anchor` command would pin it on-chain) | 008.4 delivery notes I3.1c | When membership becomes adversarial enough that "any current member is trusted to point at the right anchor" is too weak |
| ~~Exact anchor ex-unit evaluation~~ **DONE 2026-07-11 (I4.1)** — QuickTx `withTxEvaluator(JulcTransactionEvaluator)` runs the real validators in the local julc VM at build time (replaced the fixed ceilings) | 008.4 delivery notes I4.1 | — |
| Observation injection retry when the scheduled proposer is offline at drain time (v1: single deterministic drain per fact — the fact is skipped if that exact proposer is down; safe but lossy) | 008.4 delivery notes I3.2 | If skipped observations are noticed in practice; fix = keep drained facts N more L1 blocks and re-attempt on proposer change, dedup via observation key at the state-machine layer |
| ~~Adopt QuickTx for anchor tx construction~~ **DONE 2026-07-11 (I4.1)** — ScriptAnchorService now composes QuickTx `ScriptTx` over in-node suppliers with `additionalSignersCount` witness pricing and exact julc-VM ex-units (008.4 delivery notes I4.1). Remaining slice: the METADATA anchor service (A1) still uses the I1.5 hand-rolled path — migrate if its tx shape ever changes | 008.4 delivery notes I4.1 | — |
| ~~Vote-lock liveness wedge after crash-restart~~ **DONE 2026-07-11 (I4.2)** — propose-around without self-vote (auto-heals threshold ≤ n−1) + `admin/unlock-stale-round` operator escape hatch + `staleLockedHeight` status surfacing (008.2 delivery notes I4.2) | 008.2 delivery notes I4.2 | — |
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

Docker-compose app-chain cluster (deferred 2026-07-12): the bash cluster
scripts now ship in the jar/native dist zips (host-run demo), but they are
process launchers with container-hostile assumptions — followers copy node
0's SHIFTED devnet genesis via shared filesystem, and all wiring assumes
localhost ports. A compose-shaped cluster needs N `yano` services (the
docker dist zip is the vehicle), env-var chain wiring, and a genesis
distribution mechanism (shared volume, or node 0 serving genesis over HTTP
devkit-style). Revive for container-native evaluation / real quick-network
setup.

## Standing gates (unchanged)

- E7.4 private balances / E7.5 ZK anchors: gated on ZeroJ ADR-0026 production
  criteria; E7.5 builds on the 008.4 julc anchor validator.
- `authScheme=2` fully anonymous transport: touches the yaci core auth path
  (ADR-006 follow-up).
- Positioning language: keep ADR-007's honesty rules until the corresponding
  capability ships (metadata anchors ≠ settlement; script anchors change this
  for chains that adopt A2).
