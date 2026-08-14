# ADR-009 Review: Yano App-Chain Support and Readiness

## Status

Historical review complete — findings tracked or superseded

## Date

2026-07-10

> **Current-status note (2026-07-17):** This report is a point-in-time review
> of the ADR-008 integration. Later accepted ADRs and delivery work closed or
> reclassified findings. It remains an audit record; use
> [open_item.md](open_item.md) for the current backlog and release posture.

## Review target

- Branch: feat/app_layer_adr008
- Reviewed revision: 85b12d1 (Iteration 3 merge; the source was reviewed immediately before and after this merge)
- Target state: the integrated ADR-008.4 script-anchor and L1-observation implementation
- This is a review document, not an architecture decision or a security audit.

## Scope

This review covers the app-chain feature as a whole:

- appmsg transport integration and peer topology
- sequencing, voting, finality certificates, catch-up, and persistence
- fixed and rotating sequencer modes
- static and chain-governed membership
- state-machine and plugin APIs
- MPF state commitments, proofs, evidence bundles, snapshots, and retention
- metadata and script anchoring
- L1 references and L1 observations
- REST, SSE, clients, Spring support, testkit, sinks, metrics, health, and security controls
- standard-library and experimental ZK state machines
- ADRs, user documentation, implementation tests, and the current pending-task register

## Verification performed

The review traced the implementation from the public API through runtime construction, network ingress, consensus, storage, L1 integration, and application surfaces. It also ran this focused cross-module suite:

    ./gradlew :core-api:test \
      :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.appchain.*' \
      :appchain-stdlib:test :appchain-client:test :appchain-testkit:test \
      :appchain-kafka-sink:test :appchain-zk:test \
      :appchain-spring-boot-starter:test :appchain-anchor-onchain:test \
      :app:test --tests 'com.bloxbean.cardano.yano.app.*AppChain*' --continue

Result: **BUILD SUCCESSFUL in 3m25s**.

| Module/scope | Tests reported | Failures |
|---|---:|---:|
| core-api | 118 | 0 |
| runtime app-chain package | 66 | 0 |
| appchain-stdlib | 11 | 0 |
| appchain-client | 4 | 0 |
| appchain-testkit | 2 | 0 |
| appchain-kafka-sink | 3 | 0 |
| appchain-zk | 9 | 0 |
| appchain-spring-boot-starter | 3 | 0 |
| appchain-anchor-onchain | 48 | 0 |
| Quarkus app-chain tests | 10 | 0 |

The green suite is meaningful: it exercises real sockets, multi-node finality, catch-up, restart, sender sequences, rotation, governed membership, L1-reference checks, snapshots, sinks, script-anchor construction, twin on-chain validators, and the REST surface. It does not invalidate the findings below; most findings are missing invariants or uncovered adversarial/recovery cases.

---

## Executive assessment

Yano now has a **substantive app-chain framework**, not a toy message queue. Its strongest path is a permissioned replicated state machine with member-authenticated messages, independently re-executed blocks, threshold Ed25519 certificates, atomic RocksDB/MPF commits, verified history catch-up, and optional Cardano anchoring. ADR-008 materially improved the implementation reviewed in ADR-007: backpressure, sender-sequence handling, L1-reference verification, signed snapshots, rotating proposers, governed membership, script anchors, L1 observations, health, metrics, and UI support now exist.

The correct current label is still:

> **Developer preview / controlled permissioned pilot.**

It is **not production-ready as a general multi-chain, adversarial consortium, bridge-custody, settlement, or public L2 platform**. Several issues are release blockers rather than polish:

1. live and catch-up block verification does not bind a block or its inner messages to the configured chain id/version;
2. a persisted vote lock becomes permanently unrecoverable after its proposal envelope expires;
3. rotating eligibility accepts proposers from a large lookback window without committing the proposal window, undermining single-proposer scheduling and increasing split-lock risk;
4. governed membership applies future epochs immediately on some runtime paths and can overwrite multiple activations at the same future height;
5. zero-config script-anchor identity can be persisted before the proposed anchor is fully verified and is not bound to a locally expected validator artifact;
6. proposer timestamps are unconstrained even though a standard-library workflow treats them as a business clock;
7. snapshot restore does not bind the manifest chain id and does not verify the full certified history.

These findings do not imply that threshold finality or MPF commits are fake. They mean the enforcement boundary around those strong primitives is not yet consistently closed.

## Readiness summary

| Deployment/use case | Current readiness | Review judgment |
|---|---|---|
| Local development, demos, application prototyping | **Ready** | Strong tooling and broad passing tests. |
| Single-organization, non-value-bearing ordered log | **Pilot-ready with controls** | Use one chain per isolated deployment, fixed sequencer, metadata anchoring, backups, API auth, and an explicit restart runbook. |
| Trusted consortium pilot | **Conditional pilot** | Viable when every member is operationally trusted and the group accepts liveness/manual recovery limits. Avoid simultaneous governance changes and treat rotation as beta. |
| Multi-chain hosting with overlapping member sets | **Not ready** | Missing block/inner-message chain-domain checks permit cross-chain graft/replay scenarios. |
| Rotating-sequencer production consortium | **Not ready** | Honest failover works, but lookback eligibility plus persistent vote locks can create unrecoverable stalls. There is no BFT view change. |
| Governed-membership production | **Not ready** | Deterministic history exists, but activation-lag/current-epoch semantics and same-height composition need correction. |
| Metadata-anchored audit/notarization | **Pilot-ready** | Useful timestamped evidence; it is not on-chain settlement or custody enforcement. |
| Script-anchor experimentation/devnet | **Advanced beta** | Real validators and live devnet success are strong, but identity pinning, crash recovery, leader liveness, and tx-builder hardening remain. |
| Value-bearing bridge, custody, withdrawals | **Not ready** | The framework does not yet provide the end-to-end trust, data-availability, recovery, and security guarantees needed for funds. |
| Public sidechain or trustless L2/rollup | **Not ready / out of scope** | Permissioned membership, no public BFT, no fraud/validity proof settlement, and experimental ZK only. |

---

## Architecture and capability inventory

| Area | Current implementation | Assessment |
|---|---|---|
| Transport | CIP-137-shaped appmsg protocols 100/103, signed envelopes, TTL/size limits, pull gossip, catch-up | Solid private-overlay basis; app peers still use dedicated outbound connections. |
| Consensus | Fixed or L1-slot-derived rotating proposer, persisted vote locks, threshold Ed25519 certs | Strong safety skeleton; liveness and proposer-window binding remain incomplete. |
| Ledger | Per-chain RocksDB, hash-linked blocks, message/query indexes, MPF state | Good atomic design and clear append-only model. |
| State machines | Blob-first SPI, ServiceLoader plugins, stdlib machines, determinism harness | Good developer seam; lifecycle/system-message contracts need tightening. |
| Catch-up | Protocol 103 range fetch, cert/hash/message/state-root re-verification | Valuable and correctly fail-closed on checked invariants; domain validation is missing. |
| Membership | Static epochs or threshold-approved governance messages | Good direction and replayable history; effective-epoch handling has correctness defects. |
| L1 linkage | Stable-depth references checked by followers; metadata or script anchors | Substantive, especially script validation; historical verification and recovery are limited. |
| L1 observations | Event-fed deterministic observers, stability-gated injection, follower recomputation | Useful scoped v1; lossy under restart/offline proposer and cannot prove negative facts. |
| Proofs/evidence | MPF inclusion proofs, JSON evidence bundles, signed snapshot manifests | Useful primitives; independent L1 verification and full snapshot/history binding are incomplete. |
| APIs/tooling | REST, SSE, client SDK, Spring starter, JUnit cluster, Kafka/webhooks, metrics/UI | Broad and unusually complete for a preview; typed queries/security scopes are unfinished. |
| ZK | Proof-gated state-machine plugins | Correctly labeled experimental; not a rollup or settlement proof system. |

---

## What is good

### 1. The finality path has real cryptographic and execution checks

AppChainEngine verifies member signatures, recomputes message roots, independently applies the state machine, compares the post-state root, verifies distinct certificate signers against membership-at-height, and only then commits. Catch-up repeats the important checks rather than trusting peer storage. This is the right shape for a permissioned replicated application ledger.

Evidence:

- runtime/.../AppChainEngine.java:202-272 — certified-block verification and apply
- runtime/.../AppChainEngine.java:417-525 — live proposal verification and voting
- runtime/.../AppChainEngine.java:674-690 — certificate verification

### 2. Atomic block and state persistence is a major strength

The finalized block, tip, hash, message index, query indexes, sender-sequence floors, governance metadata, MPF trie nodes, and state root share a RocksDB WriteBatch with synchronous write. That greatly reduces partial-commit ambiguity and is stronger than many early app-chain implementations.

Evidence: runtime/.../AppLedgerStore.java:316-377.

### 3. Catch-up is verification-first

Late joiners fetch historical blocks but re-check the hash link, proposer membership, message root, L1 references/observations where available, message signatures, sender sequences, certificate, and re-executed state root. The late-joiner and governed-membership replay tests are especially valuable.

### 4. ADR-008 fixed several concrete v1 correctness problems

The previous review's highest-confidence implementation defects were not ignored:

| Earlier gap | Current state |
|---|---|
| Local submit could report success after pool drop | Fixed: local admission precedes relay; pool full raises PoolFullException/HTTP 429. |
| Sender sequence was unused | Improved: finalized floors persist; admission replay checks are always active; consensus enforcement is configurable. |
| Followers trusted proposer L1 references | Fixed for the recent stable window with match/ahead/mismatch verdicts. |
| Fixed proposer was the only mode | Rotating mode and honest-proposer crash tests now exist. |
| Membership was only node-local | Governed membership is finalized and replayed from history. |
| Snapshots were unsigned | Signed file manifests plus tip/root/epoch binding now exist. |
| Anchor tx construction used a fixed fee/simple input | Fee/TTL/multi-input handling is materially improved. |
| Plugin determinism was only prose | A repeat/restart root-conformance harness exists. |
| Health/metrics/UI were shallow | App-chain health group, lag/drop metrics, dashboard, and status page are present. |

### 5. The script anchor is a real technical step up

The script-anchor path is not just metadata under a new name. It includes:

- a one-shot state-thread NFT;
- monotonic datum progression;
- threshold authorization based on the input datum's members;
- locked-value preservation;
- Java/julc and Aiken implementations of one ABI;
- shared conformance tests;
- runtime artifact loading and script-hash/address derivation;
- off-chain member verification and co-signing;
- a live two-node devnet gate with bootstrap and repeated advances.

That is credible engineering progress. It should still be called beta until the identity and recovery findings below are closed.

### 6. L1 observations are scoped honestly and checked at the consensus boundary

Each member recomputes configured facts from its own L1 event stream. Observation messages must be stability-deep relative to the app block's L1 reference, and mismatches reject proposals/catch-up. This is much stronger than trusting a proposer-supplied oracle claim.

### 7. The extension model is broad without polluting the consensus core

State machines, signers, sinks, observers, and sequencer modes have focused interfaces or ServiceLoader provider seams. The standard library, client SDK, Spring starter, Kafka sink, testkit, and optional ZK module show that the extension model is usable rather than theoretical.

### 8. The test portfolio includes adversarial and operational cases

The suite covers rogue proposers, invalid signatures, pool saturation, replay sequences, restart, catch-up, rotation failover, governance replay, rollback, retention, webhook retry, signer providers, twin validators, and Quarkus auth/UI behavior. The live devnet reports add useful evidence beyond mocked unit tests.

### 9. Risk is often documented candidly

The ZK README explicitly says it is not a zk-rollup. The pending-task register records the expired vote-lock wedge, observation loss, anchor-leader liveness, QuickTx refactor, and historical L1View limitations. This honesty is valuable, even though some user-facing documents have not caught up.

---

## Bad, risky, or incomplete behavior

Findings are ordered by release impact. “Critical” means a claimed trust/domain boundary can be bypassed. “High” means production safety, liveness, recovery, or settlement readiness is materially affected. “Medium” means the feature is usable with constraints but its contract or operations are incomplete.

### F-01 — Critical: blocks and inner messages are not bound to the configured chain domain

AppChainEngine checks height, previous hash, proposer, message root, L1 data, signatures, certificate, and state root, but it does not require:

- block.version == AppBlock.BLOCK_VERSION;
- block.chainId == config.chainId;
- each inner message.chainId == config.chainId or block.chainId;
- each inner message's auth scheme and configured per-message/block limits.

The omission exists in both live proposals and protocol-103 catch-up:

- runtime/.../AppChainEngine.java:202-260
- runtime/.../AppChainEngine.java:417-525
- runtime/.../AppChainEngine.java:753-757

Why this matters:

- A fresh chain can accept a certified history from another chain when the member set, threshold, and state machine are compatible.
- Signed inner messages can be replayed across chains with overlapping members.
- Cross-chain replay is particularly dangerous for governed membership: approvals signed for one chain can be inserted into another chain's block and counted there.
- Sender-sequence floors and application state can be polluted by foreign-chain messages.
- Snapshot restore can graft a ledger from another chain because SnapshotManifest.verifyPostOpen does not compare manifest.chainId with the configured chain.

This is the most important correctness finding in this review.

Required fix:

1. Add one central block-domain validator used by live proposal, catch-up, startup restore, and evidence verification.
2. Check block version/chain id, every inner message chain id/auth scheme, field lengths, message count, individual size, and aggregate block size.
3. Bind snapshot manifest chain id to the configured chain before the verified marker is written.
4. Add adversarial cross-chain proposal, catch-up, governance replay, and snapshot tests using overlapping keys.

### F-02 — High: an expired persisted vote lock can wedge a height forever

Once a member votes, the engine persists the block hash and proposal envelope. On every later proposer tick, the existence of the lock prevents any competing proposal. Recovery only re-gossips the stored envelope. When that envelope expires, regossipLockedProposal logs a warning and returns, while the lock remains permanently authoritative.

Evidence:

- runtime/.../AppChainEngine.java:293-301
- runtime/.../AppChainEngine.java:535-559
- adr/app-layer/pending-tasks.md — “Vote-lock liveness wedge after crash-restart”

This is reproducible after a crash/restart that outlives proposal TTL. Rotation does not repair it because all future proposers honor the same local lock. A quorum of wedged members can permanently stop the chain at that height.

Required fix: design a recovery rule that preserves non-equivocation while making the locked proposal durably re-proposable. Good options include a non-expiring consensus proposal object separate from the expiring gossip envelope, a view-change certificate, or a narrowly authorized unlock/recovery transaction. Simply deleting the lock on TTL expiry would weaken safety and is not sufficient.

### F-03 — High: rotating mode does not strongly bind a proposal to its claimed window

RotatingSequencerMode accepts a proposal when its proposer was scheduled in any of the last lookbackWindows windows. The default lookback is 64. The block/header contains no proposal window, so a node cannot distinguish a genuinely re-gossiped old locked proposal from a fresh proposal created now by a member who happened to be scheduled recently.

Evidence: runtime/.../RotatingSequencerMode.java:68-81 and 99-105.

With a small committee, most or all members will appear in a 64-window lookback, so adversarial eligibility approaches “any member may propose.” Honest nodes call shouldProposeNow correctly, which is why crash-failover tests pass, but a malicious or divergent implementation can create competing proposals. Persistent one-vote locks preserve important safety, yet split locks can destroy liveness because there is no view-change protocol.

The implementation also diverges from the ADR schedule description: it hashes chainId and global window only, not an epoch nonce or chain-genesis offset.

Required fix: commit the proposal window/view into the signed block or proposal proof, validate a narrow current/grace rule, and give locked old rounds an explicit recovery proof. If that is deferred, market rotating mode as honest-failover beta, not adversarial sequencer consensus.

### F-04 — High: governed membership does not consistently honor future activation

MemberGroup.current returns the last appended epoch even when its fromHeight is in the future. GovernedMembership appends an epoch at height + activationLag immediately after approval.

Evidence:

- runtime/.../MemberGroup.java:53-56
- runtime/.../MemberGroup.java:89-95
- runtime/.../GovernedMembership.java:158-178

Consequences before the activation height:

- transport admission group.contains uses the future membership immediately;
- members(), effectiveThreshold(), status, and admin responses report the future epoch as current;
- script-anchor datum/signing uses membersSupplier/thresholdSupplier from the future epoch;
- removed members may be rejected early and added members accepted early, defeating the operational purpose of activation lag.

There is a second composition defect. Multiple approved commands targeting the same future fromHeight do not reliably compose: appendEpoch removes existing epochs at or after that height, while activate derives its base from group.membersAt(the current block height), which cannot see the first future effect. A later activation in the same block can replace the earlier one. The source comment claiming later same-block commands see the new epoch is therefore not true for a positive activation lag.

Required fix:

- define “effective current” as epochAt(tip + 1), not the last scheduled epoch;
- keep scheduled epochs separate from active ingress membership;
- stage every governance effect against an ordered working future-epoch model and commit the composed result once;
- add tests for two activations in one block, overlapping activation heights, transport behavior before/after activation, rotation, observations, and script anchoring during the lag.

### F-05 — High for script settlement: zero-config anchor identity can be poisoned before verification

verifyAdvance calls adoptOrMatchIdentity before validating the continuing datum against local app-chain history. On first sighting, adoptOrMatchIdentity writes the policy id and script hash to ledger metadata as soon as a matching L1 UTxO exists. Later verification may reject the request, but the incorrect identity remains persisted.

Evidence: runtime/.../ScriptAnchorService.java:762-837 and 840-864.

The follower also does not derive the supplied script hash from a locally pinned validator artifact. Therefore one authenticated current member can point a zero-config follower at a decoy or weaker script UTxO, permanently pin the wrong identity, and prevent the legitimate anchor from being adopted. This violates the intended n-of-m posture: anchor identity introduction effectively trusts one member.

Required fix:

1. Verify the complete input UTxO, input datum, monotonic transition, next datum, local block/root, member epoch, threshold, and expected artifact/script hash before persistence.
2. Persist policy id and script hash atomically only after successful verification.
3. Prefer an externally pinned identity, a genesis/config value, or a threshold-governed anchor-identity command. Zero-config discovery can remain a convenience only when its one-member trust is explicit.

### F-06 — High for workflow machines: proposer wall-clock is an unconstrained consensus input

AppBlock.timestamp is set by the proposer and is included in the signed block hash, but followers do not enforce monotonicity or a bound against L1/current time. ApprovalsStateMachine uses block.timestamp to expire business workflows.

Evidence:

- runtime/.../AppChainEngine.java:313-325 and 417-525
- appchain/appchain-stdlib/.../ApprovalsStateMachine.java:101-106

An eligible proposer can move the application clock far forward and expire pending approvals; followers deterministically reproduce and sign the same result. Rotation reduces control duration but does not validate time.

Required fix: specify a consensus clock. Prefer L1 slot/time derived from the verified l1-ref for time-sensitive logic, or enforce monotonic timestamp plus a narrow bound. The stdlib approvals machine should use the validated clock rather than raw proposer wall time.

### F-07 — High: snapshot trust and restore binding are still weaker than the documentation implies

Signed file manifests are a real improvement, but restore verification has gaps:

- manifest.chainId is written but never checked against config;
- the trusted signer set is the configured genesis set before persisted governed epochs are loaded, so a removed genesis member remains trusted and a newly governed member may be rejected;
- verifyTipCert verifies only the tip block and its certificate, not every block's hash link, message root, certificate, or state transition;
- a single trusted manifest signer can supply a file-consistent snapshot whose older stored history is altered while the certified tip remains intact;
- the verified marker is written in pre-open verification before post-open binding completes.

Evidence:

- runtime/.../SnapshotManifest.java:80-149
- runtime/.../AppChainSubsystem.java:1259-1296 and 1515-1549

Snapshots should currently be treated as member-trusted operational acceleration, not trust-minimized state sync.

Required fix: bind chain id and state-machine/state-version, validate the signer against membership at the snapshot height, move the marker after all post-open checks, and either verify the complete certified chain/state or bind the snapshot to an independently verified L1 anchor/checkpoint.

### F-08 — Medium-high: custom state-machine lifecycle and system-message contracts are inaccurate

AppStateMachine says apply is invoked exactly once per finalized block. In reality it runs speculatively before a proposer broadcasts, before a follower votes, after retries, and for proposals that may be discarded. A plugin with in-memory side effects can therefore behave incorrectly even if writes are batched.

The governed-membership design says user state machines do not see governance messages, but AppChainEngine only bypasses validate; applyBlock passes the full AppBlock to the state machine. Ordered-log records governance messages, and a custom decoder could interpret a governance body as application data.

Evidence:

- core-api/.../AppStateMachine.java:22-55
- runtime/.../AppChainEngine.java:327, 381-396, and 729-749

The conformance harness compares roots across independent runs and restart, which is useful, but it does not test concurrent validate, repeated speculative apply on one instance, validate/apply policy equivalence, system-topic isolation, or the promised JUnit annotation wrapper.

Required fix: document apply as repeatable/speculative and side-effect-free outside AppStateWriter; pass a filtered application block or explicit framework/application message views; extend conformance tests for retries and system topics.

### F-09 — Medium-high: script-anchor crash and rollback recovery is not durable

Pending bootstrap, co-sign, and submit state is in memory. A restart can lose knowledge of a bootstrap or advance submitted just before shutdown. If a bootstrap mint lands while the node is down, a new bootstrap can select a different seed and create another identity. If the bootstrap is rolled back, identity metadata is deliberately retained, but bootstrap() sees bootstrapped == true and cannot actually perform the “re-run bootstrap” recovery suggested by the log.

Evidence:

- runtime/.../ScriptAnchorService.java:148-156, 241-271, 389-443, and 896-951

Required fix: persist the anchor state machine (identity, seed outref, pending body/hash/tx, expected datum/outref, confirmation state) and reconcile it against local L1 state on startup before building a new transaction.

### F-10 — Medium: accepted message delivery is memory-backed and best-effort

The app message pool is in memory. A node restart loses unfinalized submissions. A disconnected peer has a 200-message replay queue that silently evicts oldest entries; the remaining pool is not re-scanned and re-offered after reconnect. A local HTTP 202 therefore means “admitted to this process,” not durable or guaranteed to reach a sequencer.

Evidence:

- runtime/.../AppMsgPool.java
- runtime/.../AppPeerClient.java:32, 45-47, and 99-112

This is acceptable for a diffusion layer only if the client contract is explicit and callers retry until finalized. It is not sufficient for a durable queue product without client idempotency/finality tracking or a persisted admission log.

### F-11 — Medium: liveness health can miss the most important stalls

The stalled flag requires bestPeerTip > localTip. A dead fixed proposer, a split-vote lock where every peer is at the same height, an expired locked proposal, or a chain with pending messages but no progress can remain “not stalled.” The missed-window metric and the operator runbook are still pending.

Also, SinkRunner records lastError only when deliver throws. KafkaStreamSink returns false on failure, so its health error can remain null even while delivery is retrying; lag is visible in metrics but the health group does not fail on lag alone.

Evidence:

- runtime/.../AppChainSubsystem.java:483-511 and 1204-1217
- runtime/.../SinkRunner.java:60-86
- appchain/extensions/appchain-kafka-sink/.../KafkaStreamSink.java:94-108

### F-12 — Medium: enterprise security is a framework seam, not a shipped posture

REST API-key authentication is disabled by default. When enabled, all valid keys can call admin endpoints; topic restrictions apply only to submit. There are no read/admin scopes, mTLS/OIDC integration recipe enforced by the app-chain layer, rate limits, audit log, or reference KMS/HSM provider. Member and anchor seeds are still commonly configured as raw hex.

The SignerProvider SPI is good groundwork, but a production enterprise claim needs a supported secure deployment profile, not only extension points.

### F-13 — Medium: query and independent-verification APIs remain incomplete

- AppStateMachine.query is not called by the runtime or REST, despite its javadoc saying it is exposed through /query.
- The REST surface provides generic state-key proofs and indexes, not typed application queries.
- The client SDK verifies MPF proofs but has no L1 anchor source or script-datum verification loop.
- EvidenceVerifier lives outside the deliberately slim client SDK.
- Evidence bundles intentionally omit anchor linkage across membership changes and long ranges rather than carrying per-height epochs/checkpoints.

This is adequate for developers who understand internal keys and manually obtain an L1 root, but it is not yet a complete “do not trust the node” client journey.

### F-14 — Medium: documentation is materially inconsistent with the current code

The ADR-008 documents and pending-task file are current, but major user documents still say rotating sequencing, governed membership, and script anchors are “designed, not shipped.” Other sections still describe only a fixed sequencer and metadata anchoring. Conversely some API javadocs claim /query support that does not exist.

Examples:

- docs/APP_CHAIN_USER_GUIDE.md sections 2 and 19
- docs/APP_CHAIN_USE_CASES.md “What this is not yet for”
- core-api/.../AppStateMachine.java query javadoc

This creates readiness risk because operators cannot tell which modes are stable, beta, experimental, or known-broken.

---

## Current limitations, even after the defects are fixed

### Consensus and trust model

- This is permissioned n-of-m finality, not public Sybil-resistant consensus.
- Threshold configuration expresses the trust assumption; the runtime does not enforce a BFT quorum/intersection policy.
- Fixed mode has an intentional proposer availability dependency.
- Rotating mode has no BFT view change, slashing, or accountable equivocation proof.
- A threshold of members can authorize arbitrary valid state transitions by definition; application security must match that model.

### L1 and settlement

- Metadata anchors are public notarization, not enforceable settlement.
- Script anchors enforce datum progression, but Yano does not yet ship a complete bridge/custody protocol around deposits, withdrawals, data availability, and recovery.
- L1-reference and observation verification use bounded event-fed windows. Old references become UNKNOWN and are accepted on certificate/monotonicity.
- The current L1View cannot prove negative facts or perform arbitrary historical UTxO-at-slot queries.
- Observations are in-memory before stable injection and use a single drain attempt; restart or an offline scheduled proposer can lose a fact.

### Data availability and recovery

- App blocks are append-only and have no app-ledger rollback/reconciliation protocol.
- Retention pruning removes bodies and makes from-genesis replay impossible; a trusted snapshot becomes mandatory for late joiners behind the prune horizon.
- The message pool is not durable.
- Evidence bundles cap history at 4096 blocks and do not span membership epochs.
- There is no independent archive availability protocol or erasure coding.

### Network and scale

- Outbound app peers use dedicated TCP connections rather than sharing the existing L1 peer session, despite the original architectural goal.
- Outbound topology is configured per chain; there is no app-peer governor, discovery policy, or service-quality isolation comparable to mature public P2P networks.
- No published scale results cover large committees, many hosted chains, large blocks, long histories, WAN latency, prolonged partitions, or adversarial traffic.
- Ed25519 required-signer witnesses make script-anchor transaction size the likely large-committee constraint.

### Application/plugin safety

- Determinism is tested, not sandboxed or enforced.
- State format has no stateVersion/migration contract.
- Runtime upgrades require all members to coordinate machine, observer, sequencer, and codec versions.
- Built-in machines are useful examples, not audited financial/business ledgers.

### ZK

- ZK modules verify application proofs inside a permissioned chain.
- They do not prove the whole state transition, settle a validity proof on L1, provide anonymous transport, or make Yano a zk-rollup.
- Circuit governance and production audit criteria remain external dependencies.

---

## Detailed readiness by component

| Component | Status | Notes |
|---|---|---|
| Envelope auth, TTL, size, dedup | **Beta/strong** | Good basics; inner-block domain/limit revalidation must be centralized. |
| Fixed sequencing | **Pilot** | Simple and understandable; proposer outage is an operational incident. |
| Rotating sequencing | **Beta** | Honest failover demonstrated; adversarial eligibility/view-change unresolved. |
| Threshold finality certs | **Beta/strong** | Crypto checks are real; trust rests on configured n-of-m and non-equivocation locks. |
| App ledger + MPF | **Beta/strong** | Atomic design is a highlight; needs format versioning and broader fault injection. |
| Catch-up | **Beta** | Verified replay works; chain-domain validation and pruned-history onboarding need hardening. |
| Static membership | **Pilot** | Safe only with coordinated operations and secured admin access. |
| Governed membership | **Beta, not production** | Replayable design is good; activation/composition defects are blockers. |
| Metadata anchoring | **Pilot** | Correctly useful for audit notarization. |
| Script anchoring | **Advanced beta** | Real on-chain enforcement; identity/restart/leader/tx-builder gaps block custody use. |
| L1 observations | **Beta** | Good positive-fact feed; lossy and bounded-history by design. |
| Snapshots | **Pilot convenience** | Signed and file-bound, but still member-trusted and not full-history verified. |
| Retention | **Beta** | Useful data minimization; requires snapshot/archive operations. |
| REST/SSE/status UI | **Pilot** | Broad surface; security scopes and contract cleanup required. |
| Java client/Spring | **Developer-ready** | Good integration start; independent anchor/evidence loop missing. |
| Kafka/webhooks | **Pilot** | At-least-once semantics are honest; Kafka health error reporting needs a fix. |
| Testkit/conformance | **Developer-ready** | Helpful and practical; should grow to rotation/governance/L1 and speculative lifecycle. |
| ZK extension | **Experimental** | Correct label; do not market as rollup readiness. |

---

## Recommended release gates

### P0 — before any production or value-bearing recommendation

1. **Close chain-domain validation (F-01).** One validator for live, catch-up, restore, evidence; add cross-chain adversarial tests.
2. **Fix persisted vote-lock recovery (F-02).** Prove crash/restart after envelope TTL can finalize without permitting double vote.
3. **Correct governed epoch semantics (F-04).** Honor activation height on every path and compose multiple scheduled changes deterministically.
4. **Bind rotating proposals to a view/window (F-03), or explicitly exclude rotating mode from production.** Add partition and malicious-window tests.
5. **Constrain the application clock (F-06).** Move time-sensitive stdlib logic to a verified L1-derived clock.
6. **Harden snapshot identity/history verification (F-07).** At minimum chain id, machine version, membership-at-height, marker ordering, and full hash/cert walk.
7. **For script mode, pin and atomically adopt anchor identity only after full verification (F-05).** Persist and reconcile pending anchor state (F-09).

### P1 — before a general enterprise 1.0

1. Add API-key roles/scopes, admin audit events, rate limits, and documented mTLS/OIDC deployment profiles.
2. Ship at least one supported external signer provider or clearly keep KMS as integrator work.
3. Clarify and test state-machine speculative lifecycle and system-topic isolation.
4. Add state-machine stateVersion plus upgrade/migration compatibility checks.
5. Define durable submission semantics: client retry-until-final, idempotency API, or persisted admission log.
6. Complete typed query and independent L1-anchor verification in the client SDK.
7. Make liveness health detect pending-no-progress, lock wedges, missed windows, and anchor/sink lag policy.
8. Reconcile all ADR/user/tutorial/use-case documentation and assign stable/beta/experimental labels per mode.

### P2 — before public, bridge, or high-scale positioning

1. Implement a real BFT/view-change mode or state clearly that public/adversarial operation is a non-goal.
2. Complete historical L1 commitment/proof serving for the bridge facts the product intends to support.
3. Add durable/archive data availability and trust-minimized snapshot/checkpoint onboarding.
4. Replace hand-built anchor transaction construction with the maintained QuickTx path and measured ex-unit evaluation.
5. Run WAN partition, crash-loop, kill -9, disk-full, corrupted-WAL, large-committee, multi-chain, and long-retention chaos tests.
6. Commission independent protocol, Java/runtime, and Plutus/Aiken security reviews before custody use.

---

## Suggested product positioning now

Safe wording:

> Yano provides a developer-preview Java framework for permissioned application ledgers replicated by a configured member group, with threshold-certified blocks, MPF state proofs, verified catch-up, and optional Cardano metadata or script anchoring. It is suitable for development and controlled non-value-bearing pilots while consensus recovery, cross-chain domain enforcement, governed membership, script-anchor identity, and production security are hardened.

Avoid for now:

- production-ready sidechain
- trustless L2
- bridge-ready or custody-ready
- Byzantine-fault-tolerant rotating consensus
- exactly-once durable queue
- zk-rollup
- independently verifiable settlement without explaining how the anchor identity/root is obtained and checked

---

## Final opinion

Yano's app-chain support is technically ambitious and already demonstrates several hard things correctly: real multi-node signatures, re-executed deterministic state, atomic authenticated-state commits, verified catch-up, proof surfaces, and an actual threshold-governed Plutus anchor tested on live devnet. The breadth of supporting modules is unusually good for this stage.

The framework is nevertheless at the point where **boundary invariants and recovery semantics matter more than adding features**. The chain-domain omission, expired vote-lock wedge, governed future-epoch behavior, rotating lookback ambiguity, unconstrained application clock, and script-anchor identity/restart behavior are sufficient to keep the overall status at developer preview/controlled pilot.

After the P0 gates are closed and covered by adversarial tests, a credible next label would be **beta for permissioned enterprise app chains**. Production, bridge, or settlement readiness should require the P1/P2 operational and independent-audit work as well.
