# ADR App Layer Codex Report: Yano App-Chain Implementation Review

## Status
Historical review report — findings superseded by later ADRs and delivery work

## Date
2026-07-09

> **Current-status note (2026-07-17):** This is a point-in-time review, not a
> current readiness statement. Later ADRs and implementation passes addressed
> or reclassified findings. Use [open_item.md](open_item.md) for the live
> backlog and standing release posture.

## Reviewed Documents
- `adr/app-layer/005-yano-app-chain-framework.md`
- `adr/app-layer/006-appchain-enterprise-extensions-and-zk.md`

## Reviewed Implementation Areas
- Runtime app-chain subsystem:
  - `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/appchain/AppChainSubsystem.java`
  - `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/appchain/AppChainEngine.java`
  - `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/appchain/AppLedgerStore.java`
  - `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/appchain/AnchorService.java`
  - `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/appchain/AppPeerClient.java`
  - `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/appchain/AppChainManager.java`
  - `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/appchain/MemberGroup.java`
- Core API, codec, evidence, proof APIs:
  - `core-api/src/main/java/com/bloxbean/cardano/yano/api/appchain/`
  - `core-api/src/main/java/com/bloxbean/cardano/yano/api/appchain/evidence/EvidenceVerifier.java`
  - `core-api/src/main/java/com/bloxbean/cardano/yano/api/appchain/codec/AppBlockCodec.java`
- REST/API layer:
  - `app/src/main/java/com/bloxbean/cardano/yano/app/api/appchain/AppChainResource.java`
  - `app/src/main/java/com/bloxbean/cardano/yano/app/api/appchain/AppChainApiKeyFilter.java`
- Extension modules:
  - `appchain/appchain-client`
  - `appchain/appchain-stdlib`
  - `appchain/appchain-testkit`
  - `appchain/extensions/appchain-kafka-sink`
  - `appchain/extensions/appchain-zk`
  - `spring-starters/appchain-spring-boot-starter`

## Verification Performed

Focused app-chain test command:

```bash
./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.appchain.*' \
  :appchain-stdlib:test \
  :appchain-client:test \
  :appchain-testkit:test \
  :appchain-kafka-sink:test \
  :appchain-zk:test \
  :appchain-spring-boot-starter:test \
  :app:test --tests 'com.bloxbean.cardano.yano.app.AppChainApiKeyFilterTest'
```

Result:

```text
BUILD SUCCESSFUL in 1m 42s
68 actionable tasks: 10 executed, 58 up-to-date
```

The focused suite covered, among other things:
- authenticated peer diffusion and non-member rejection
- sequenced block finalization
- identical roots across members
- restart/replay behavior
- adversarial rogue-proposer rejection
- late joiner catch-up
- multi-chain isolation
- evidence bundle verification
- retention behavior
- snapshots
- admin controls
- key rotation persistence
- custom signer provider
- webhooks and sinks
- appchain client proof verification
- appchain testkit
- standard-library state machines
- ZK verification-gated state machines
- Spring starter behavior
- REST API-key filter behavior

## Executive Summary

The Yano app-chain implementation is a credible v1 permissioned app-chain framework. It is much stronger than a typical early blockchain prototype because it has real cryptographic finality checks, hash-linked app blocks, deterministic re-execution before voting, atomic durable ledger commits, MPF state commitments, catch-up verification, client-side proofs, and a useful developer surface.

The current implementation is best described as an L1-anchored, permissioned replicated application ledger. It is not yet a production-grade public sidechain, trustless rollup, value-bearing bridge, or decentralized settlement layer.

The most important positive signal is that the code backs up many of the ADR security claims. The most important limitation is that the trust and liveness model remains intentionally narrow: fixed sequencer, operator-coordinated membership, metadata-only anchoring, and convention-based deterministic plugins.

Current readiness:

| Use case | Readiness | Assessment |
|---|---:|---|
| Developer preview | High | Good enough to publish for early developers with clear caveats. |
| Internal trusted replicated log | High | Suitable with basic operational hardening. |
| Consortium pilot with known operators | Medium | Viable if participants accept fixed-sequencer and manual governance risks. |
| Enterprise audit/attestation product | Medium | Promising, but needs auth, ops, anchoring, retention, and KMS hardening. |
| Value-bearing appchain | Low | Needs stronger consensus, governed membership, script anchors, and economic/security model. |
| Public sidechain | Low | Missing decentralized liveness, permissionless membership, robust network assumptions, and governance. |
| Rollup or zk-rollup | Low | ZK features are verification-gated app logic, not rollup validity proofs. |
| Bridge custody or settlement | Not ready | Metadata anchoring plus trusted operators is insufficient for bridge security. |

Overall technical readiness as a permissioned app-chain framework: 6.5/10.

Overall readiness as a blockchain-grade L2/sidechain: 3/10.

## What Is Good

### 1. The Core Safety Path Is Substantive

`AppChainEngine` performs meaningful verification before accepting finalized history. The catch-up path checks:
- height continuity
- previous block hash
- configured proposer
- messages root
- message ids
- message signatures
- member set at height
- threshold finality certificate
- re-executed state root

This is the right shape for a replicated state machine. A late joiner does not blindly import peer history; it verifies block and state transition data before commit.

The proposal path is similarly strong. Followers verify proposer identity, previous hash, message root, message expiry, message signatures, vote locks, and local state-root reproduction before voting. This directly addresses the most serious defects called out in the old PoC: fake finality, decorative auth, and state commitment weakness.

### 2. Atomic Ledger Commit Is Well Designed

`AppLedgerStore.commitBlock()` writes finalized block bytes, tip metadata, message index entries, query indexes, state root, and MPF trie nodes in one RocksDB `WriteBatch` with sync enabled. This is the correct durability boundary. It reduces crash-consistency risk and avoids a class of "block committed but state missing" failures.

This design is notably better than many appchain prototypes that treat the block log, state store, and proof store as independent writes.

### 3. The ADR "Blob First" Principle Is Preserved

The framework does not try to understand application payloads below the state-machine boundary. Transport and consensus operate on opaque bodies. Application interpretation is pushed to `AppStateMachine`.

This is a good architectural decision. It keeps the framework general, makes plugin state machines possible, and avoids accidental coupling between consensus and application schemas.

### 4. MPF Commitments and Client-Side Proofs Are a Real Differentiator

Using MPF roots and exposing state proofs gives the framework a verifiability story beyond "trust the REST API." The client SDK proof verifier is also framed correctly: verification is strongest when checked against a root obtained independently from an L1 anchor, not merely against the node's own claimed root.

For enterprise audit, compliance, and evidence workflows, this is one of the most compelling parts of the implementation.

### 5. Catch-Up Exists and Verifies History

The presence of AppChainSync protocol 103 and a late-joiner integration test is important. Many early sidechain-like designs omit historical sync and end up with a network that only works while all nodes are online from genesis.

The current implementation has a clean v1 history-sync path for unpruned blocks.

### 6. The Developer Surface Is Broad

ADR-006 has been implemented with useful product-facing features:
- multi-chain hosting
- client SDK
- JUnit testkit
- Spring Boot starter
- standard-library state machines
- SSE streaming
- webhook/Kafka sink model
- snapshot/restore
- evidence bundles
- retention pruning
- encrypted bodies
- signer-provider SPI
- ZK-gated experimental state machines

This makes the framework much more adoptable than a bare consensus library.

### 7. ZK Integration Uses the Correct Consensus Boundary

The ZK state machines verify proofs inside `apply()`, not only at admission. This is the correct boundary: followers independently re-run `apply()` during block verification, so invalid proofs cannot be accepted solely because the proposer admitted them.

The anonymous membership machine also deduplicates nullifiers in committed state, which is the right place for replay/double-action protection in a replicated ledger.

### 8. Tests Cover More Than Happy Paths

The test suite includes adversarial and operational cases:
- non-member rejection
- rogue proposer rejection
- restart persistence
- catch-up
- retention and evidence
- key rotation persistence
- API key bypass regression
- ZK invalid proof paths
- sink retry behavior

This does not replace a formal audit or large-scale chaos testing, but it is a strong sign for a v1 feature branch.

## What Is Bad Or Risky

### 1. Fixed Sequencer Is the Largest Architectural Weakness

The current S1 fixed proposer model gives safety but weak liveness. If the proposer is offline, partitioned, overloaded, maliciously censoring, or operationally misconfigured, the chain stalls.

This is acceptable for a trusted single-operator deployment and some consortium pilots. It is not acceptable for a production sidechain, bridge, public service, or high-value multi-party system.

The ADR correctly identifies rotating sequencer and chain-governed membership as deferred. From an outside blockchain architecture perspective, those are not optional hardening items; they are the line between a replicated enterprise log and a robust appchain.

### 2. Membership Changes Are Operational, Not Consensus-Governed

Membership epochs are persisted in ledger metadata and changed through admin operations. This is useful, but it is not a chain-governed governance model.

Risk:
- operators can apply changes inconsistently
- participants can disagree on signer sets at a height
- admin endpoints become governance-critical
- membership history is not itself a finalized app-chain transaction
- verification key distribution for ZK/governed parameters remains off-chain/config-driven

Until membership changes are encoded as finalized governance blocks and verified as part of consensus state, consortium-grade governance remains incomplete.

### 3. Metadata Anchoring Is Audit Anchoring, Not Settlement

The current `AnchorService` writes anchor commitments into Cardano transaction metadata. That is useful for timestamped audit evidence, but it does not enforce any on-chain rules.

Important limits:
- no script validator enforces anchor sequencing
- no on-chain membership or threshold verification
- no fraud proof or validity proof
- no bridge-like settlement semantics
- no on-chain rollback/dispute process
- fixed fee/input handling is not production transaction construction

This mode is fine as "commitment notarization." It should not be marketed as trustless L2 settlement.

### 4. L1 Reference Verification Is Incomplete

Blocks carry `l1-ref`, but followers do not yet verify that the reference is stable and consistent with their own L1 view before voting.

This matters if app logic uses L1 state. Without follower-side L1 reference validation, the proposer can choose stale, absent, or strategically selected L1 points. For pure ordered-log use cases this is low risk. For bridges, registries, or L1-aware apps, it is critical.

### 5. Plugin Determinism Is Mostly Convention-Based

The `AppStateMachine` SPI is powerful but dangerous. A plugin can introduce non-determinism by using:
- wall clock
- randomness
- network calls
- local files
- process environment
- unordered iteration
- database reads outside the committed state writer
- dependency-version-specific serialization behavior

The framework detects some divergence because followers reject state-root mismatches. That protects safety, but it can turn plugin mistakes into liveness failures.

The project needs a deterministic plugin certification model: replay corpus, conformance testkit, state-machine guidelines, forbidden API list, dependency pinning guidance, and examples of accepted deterministic patterns.

### 6. Pool Backpressure Is Not Safely Surfaced

`AppMsgPool.add()` returns `false` when full, but local submission and inbound routing do not consistently surface that as an error to callers. A local submit can return an accepted message id even if the message was not actually retained for sequencing.

This is a product and correctness issue. Under load, users need explicit rejection or a durable admission queue, not silent best-effort pooling.

### 7. Sender Sequence Numbers Are Not Enforced

Envelope v2 carries `sender-seq`, but the current verification path checks signature and membership, not monotonicity, gap detection, or replay semantics.

Message id dedup prevents exact duplicate message replay within the seen-window/ledger-index model, but sender sequence semantics are not providing the stronger anti-replay or gap-detection guarantees described in the ADR.

### 8. Network Architecture Still Diverges From the ADR Goal

ADR-005 emphasizes reuse of the same Yano-to-Yano TCP connection. `AppPeerClient` explicitly says it currently owns a dedicated app-chain connection and that unification is planned later.

This is not a fatal issue, but it is an operational mismatch:
- more sockets to manage
- separate connection lifecycle
- more reconnect/replay behavior
- less integration with the main peer governor
- less accurate "one node, one peer connection" story

### 9. Snapshot Trust Model Needs Hardening

RocksDB checkpoint snapshots are useful for onboarding, especially after retention pruning. But production snapshot onboarding needs more than a local integrity check.

Missing pieces:
- signed snapshot manifest
- snapshot height and block hash
- state root and app block hash
- membership epoch information
- anchor reference
- hash of snapshot archive or directory manifest
- independent verification against L1 anchor or peer quorum

Without that, snapshots are an operational convenience, not a trust-minimized sync mechanism.

### 10. REST/Admin Security Is Too Basic For Enterprise Production

API key auth is useful and has good bypass tests, but it is not enough for enterprise admin endpoints.

Missing or incomplete:
- mTLS
- OIDC/JWT integration
- read/write/admin scopes
- per-chain scopes
- rate limits
- audit logs for admin actions
- separation of operator actions from application client actions
- secret rotation story

Admin membership endpoints are especially sensitive because they affect consensus authorization.

## ZK-Specific Assessment

The ZK implementation should be described carefully.

What it is:
- an optional plugin module
- experimental
- proof verification through ZeroJ
- proof-gated state-machine execution
- BBS credential/disclosure support
- anonymous membership style with nullifier dedup

What it is not:
- a zk-rollup
- private balance system
- on-chain verified validity system
- anonymous transport
- production privacy system
- complete circuit/proving product

The key architecture choice is good: verify in `apply()` so every node agrees on accepted proof effects. However, this still leaves heavy dependencies on ZeroJ maturity, circuit design, verifying-key governance, and client-side proving tools.

Private balances and zk-verified script anchors are correctly left pending. They should stay gated until there are audited circuits, stable proving APIs, clear note/nullifier design, and on-chain verifier economics.

## Current Gaps By Category

### Consensus And Liveness

Gaps:
- no rotating sequencer
- no view change
- no leader election
- no proposer slashing/accountability
- no censorship detection beyond stalled event style observability
- no quorum-based proposer replacement

Impact:
- safe enough for trusted fixed-proposer operation
- weak under proposer failure or censorship
- unsuitable for decentralized or high-value use

Priority:
- high

### Governance And Membership

Gaps:
- membership changes are admin-local
- no chain-governed parameter blocks
- no on-chain or app-chain governed VK distribution
- no multi-party authorization for membership changes
- no governance event audit model beyond ordinary logs/admin APIs

Impact:
- consortium pilots require manual runbooks
- miscoordination can cause liveness failures or trust disputes

Priority:
- high

### L1 Anchoring And Settlement

Gaps:
- metadata-only anchor
- no script anchor
- no on-chain threshold cert verification
- no validity/fraud proof
- follower-side `l1-ref` verification deferred
- production transaction building is basic

Impact:
- good audit timestamping
- weak settlement guarantees
- not safe for bridge custody

Priority:
- high for L1-aware apps, medium for audit-only apps

### State Machine Determinism

Gaps:
- plugin determinism is not enforced
- no deterministic plugin certification suite
- no formal state-machine execution constraints
- no static/runtime guardrails for forbidden APIs
- no cross-JVM/version determinism matrix

Impact:
- state-root mismatch can stall chains
- custom app quality becomes a consensus risk

Priority:
- high

### Data Availability And Retention

Gaps:
- pruned bodies break from-genesis replay
- snapshot trust is not independently anchored
- no DA sampling or erasure coding
- no guaranteed historical availability beyond operator policy

Impact:
- acceptable for permissioned enterprise deployments
- insufficient for public verifiability

Priority:
- medium to high depending on use case

### Backpressure And Abuse Resistance

Gaps:
- pool-full rejection is not surfaced consistently
- no durable app mempool
- limited per-sender/topic quotas
- no fee or priority mechanism
- no spam economics

Impact:
- load can degrade into silent drops or weak UX
- hostile members can create operational pressure

Priority:
- high before production pilots

### Security Operations

Gaps:
- API auth is API-key-only
- no built KMS/HSM/Vault provider jars
- no comprehensive admin audit trail
- no key rotation for all key classes
- no documented secure secret provisioning model

Impact:
- acceptable for local/dev/test
- incomplete for regulated enterprise use

Priority:
- high

### Observability

Gaps:
- metrics exist, but dashboard template is pending
- health does not gate on appchain liveness or peer progress
- stalled event exists, but operational policy is not encoded
- no SLO model for block time/finality/anchor lag

Impact:
- operators can run it, but production incident response is underdeveloped

Priority:
- medium

## Current Readiness Assessment

### Ready Now

The implementation is ready for:
- demos
- developer preview
- local and CI app-chain tests
- internal ordered-log or KV registry pilots
- proof-of-concept enterprise attestation
- single-operator or tightly coordinated two/three-node clusters

Recommended label:

```text
Developer preview / permissioned pilot
```

### Ready After Moderate Hardening

The implementation can become ready for enterprise pilot use after:
- fixing pool backpressure behavior
- adding stronger admin auth
- adding KMS/HSM plugin implementations
- adding deterministic plugin conformance testing
- adding signed snapshot manifests
- adding dashboard/runbook templates
- running load and chaos tests
- documenting exact trust assumptions

Recommended label after those items:

```text
Enterprise permissioned beta
```

### Not Ready Yet

It is not ready for:
- public sidechain
- value-bearing sidechain
- bridge settlement
- trustless rollup
- zk-rollup
- permissionless participation
- production private balances

Recommended label to avoid:

```text
Trustless L2
Public sidechain
Rollup
Bridge-ready
```

## Recommended Priority Roadmap

### P0: Correctness And Operator Safety

1. Fix pool backpressure:
   - local submit must fail if the pool cannot accept the message
   - inbound drops must increment explicit metrics
   - expose rejected/dropped counts by reason

2. Enforce sender sequence policy:
   - persist last accepted sequence per sender or explicitly remove `sender-seq` guarantees from ADR/product claims
   - define gap/replay handling clearly

3. Add deterministic plugin conformance:
   - replay same block corpus across nodes/JDKs
   - verify identical state roots
   - provide a `@DeterministicAppStateMachineTest` style harness
   - document forbidden non-deterministic APIs

4. Harden admin security:
   - split read/write/admin scopes
   - require stronger admin authentication
   - log all admin actions as audit events

### P1: Consensus And Governance

1. Implement S2 rotating sequencer:
   - deterministic schedule
   - timeout/view-change semantics
   - no single proposer liveness dependency

2. Implement chain-governed membership:
   - membership/threshold changes as app-chain governance blocks
   - finalized and replayable
   - historical verification preserved
   - multi-party authorization policy

3. Add proposer accountability:
   - missed round metrics
   - censorship observability
   - equivocation evidence

### P2: L1 Integration

1. Add follower verification of `l1-ref`.

2. Build script-anchor mode:
   - on-chain datum/state commitment
   - sequence constraints
   - optional threshold cert verification path

3. Improve anchor transaction construction:
   - protocol-parameter fee calculation
   - change handling
   - validity intervals
   - UTXO locking policy
   - resubmission safety

### P3: Data Availability And Sync

1. Add signed snapshot manifests.

2. Bind snapshots to:
   - app block hash
   - state root
   - height
   - member epoch
   - latest L1 anchor

3. Add snapshot verification before restore.

4. Define retention policies per chain and per sink.

### P4: ZK Maturity

1. Keep ZK plugins experimental until ZeroJ production criteria are met.

2. Split client proving/disclosure helpers into a slim client module.

3. Move VK distribution into chain-governed parameters.

4. Defer private balances until note/nullifier/circuit/on-chain economics are fully designed and audited.

5. Do not call this a zk-rollup until block/state-transition validity proofs are generated and verified as part of anchoring or settlement.

## Good Product Positioning

Accurate positioning:

```text
Yano app-chain is a Java framework for building permissioned, L1-anchored,
application-specific ledgers on top of Yano nodes.
```

Strong use cases:
- regulated audit logs
- multi-company event trails
- shared registries
- credential issuance records
- approvals workflows
- tamper-evident internal ledgers
- enterprise message notarization
- L1-anchored evidence bundles

Positioning to avoid for now:
- trustless rollup
- decentralized public sidechain
- bridge settlement layer
- production private asset ledger
- permissionless L2

## Detailed Good/Bad Summary

Good:
- real Ed25519 signature verification
- threshold cert verification
- persisted vote locks
- deterministic re-execution before vote/commit
- atomic RocksDB commit
- hash-linked block history
- MPF state root and proofs
- catch-up with verification
- clean state-machine SPI
- useful stdlib and SDK
- testkit exists
- ZK verification in consensus path
- API-key bypass tests
- clear ADR acknowledgement of deferred hard problems

Bad or incomplete:
- fixed proposer liveness bottleneck
- no BFT view change
- admin-driven membership
- metadata-only anchors
- L1 reference not fully follower-verified
- plugin determinism depends on discipline
- pool-full behavior needs explicit handling
- sender sequence semantics are not fully enforced
- snapshot trust needs signatures/manifests
- no production KMS/HSM provider jars
- no mTLS/OIDC admin auth
- no public-data-availability story
- ZK is proof-gated app logic, not rollup validity

## Final Opinion

The implementation is a strong v1 for a permissioned enterprise app-chain framework. It is technically coherent, the core consensus safety path is much better than the earlier PoC described in the ADRs, and the developer/product surface is unusually complete for this stage.

The remaining gaps are architectural, not cosmetic. The project should be explicit that the current system is an anchored permissioned application ledger. To graduate toward "sidechain" or "L2" in the stronger blockchain sense, the next necessary steps are rotating sequencer/view-change, chain-governed membership, stronger L1/script anchoring, deterministic plugin certification, and production-grade operations/security.

Bottom line:

```text
Ready for developer preview and permissioned pilots.
Not ready for trustless, public, value-bearing, or bridge-grade deployments.
```
