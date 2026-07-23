# ADR-004: M2 — Consensus + Validation Framework

## Status
Historical draft — superseded by ADR-005

## Date
2026-03-12

> **Current-status note (2026-07-17):** M2 was redesigned and implemented in
> Yano through [ADR-005](005-yano-app-chain-framework.md). The interfaces and
> storage shape proposed below are retained as historical context and must not
> be read as the current implementation contract.

## Context

M1 (Transport Layer) is complete — Protocol 100 (N2N gossip), Protocol 101 (N2C submit), Protocol 102 (N2C notification), authentication (Open + Permissioned), app message mempool, and event publishing are all implemented and tested.

M2 builds the consensus and validation layer on top of M1's transport. The goal: authenticated app messages flowing through Protocol 100 are now collected into **app blocks**, validated by pluggable logic, agreed upon via pluggable consensus, and stored in a persistent app ledger.

## Goals

- Pluggable consensus interface with `SingleSigner` and `MultiSig` implementations
- Pluggable validation interface for application-specific data verification
- App block production: scheduled draining of app message mempool into ordered blocks
- Persistent app ledger in RocksDB with dedicated column families
- Events for the full lifecycle: submitted → validated → finalized → block produced

## Non-Goals (Deferred to M4)

- BFT and RoundRobin consensus (complex view-change protocols)
- SPO-KES and Delegated authentication
- L1 anchoring (M3)
- REST API for app data (M3)

## Design Decisions

### 1. Consensus as VetoableEvent (not direct interface call)

Rather than a direct `AppConsensus.checkFinalization()` call, consensus decisions flow through the existing `VetoableEvent` mechanism. This allows:
- Multiple consensus listeners composing naturally (pre-checks, main consensus, post-hooks)
- Short-circuit on early rejection (expensive validation skipped)
- Plugin system integration (consensus as a `@DomainEventListener`)
- Same ordering convention as `BlockConsensusEvent` and `TransactionValidateEvent`

**Decision**: Create `AppBlockConsensusEvent` as a `VetoableEvent`. Default listener accepts all (SingleSigner). MultiSig listener requires n-of-m signatures.

### 2. App Block = Batch of Finalized Messages

An **app block** is a batch of messages drained from the app message mempool at a configurable interval. Each app block has:
- `blockNumber` (sequential per topic)
- `topicId`
- `messages` (ordered list of `AppMessage`)
- `stateHash` (SHA-256 of serialized messages — integrity proof)
- `timestamp` (wall clock when produced)
- `consensusProof` (signatures/votes from consensus)
- `prevBlockHash` (chain linkage)
- `blockHash` (SHA-256 of the full block)

**Decision**: App blocks are topic-scoped. Each topic has its own independent block sequence. This prevents cross-topic ordering dependencies and allows different topics to have different consensus modes.

### 3. Validation Happens Before Consensus

The flow is: `mempool drain → validate each message → batch valid messages → consensus on batch → finalize`.

Validation is per-message (application-specific business rules). Consensus is per-block (agreement that the batch is correct). This separation allows:
- Validation to be stateless and fast (can reject bad data early)
- Consensus to be heavyweight (signatures, network round-trips) only for valid batches

**Decision**: Create `AppDataValidateEvent` as a `VetoableEvent` (per-message), separate from `AppBlockConsensusEvent` (per-block).

### 4. App Ledger as Separate RocksDB Store

Rather than adding CFs to the existing `DirectRocksDBChainState` (which is L1-specific and tightly coupled), the app ledger gets its own RocksDB instance with its own lifecycle.

**Decision**: Create `AppLedger` interface + `RocksDBAppLedger` implementation with dedicated CFs:
- `app_blocks` — finalized app blocks by `{topicId}:{blockNumber}`
- `app_state` — latest state per topic (tip block number, hash, message count)
- `app_consensus_proofs` — consensus proofs by `{topicId}:{blockNumber}`
- `app_topics` — topic configuration and metadata

### 5. SingleSigner = Default (Accept All)

`SingleSigner` consensus simply accepts all proposed app blocks without requiring external signatures. The app block producer itself is the sole authority. This matches the existing `DefaultConsensusListener` pattern which accepts all L1 blocks.

**Decision**: SingleSigner is the default. No configuration needed beyond `app-layer.enabled=true`. MultiSig requires explicit `consensus-mode: multisig` and key configuration.

### 6. MultiSig Consensus = Collect-and-Check

MultiSig consensus follows a propose-collect-finalize pattern:
1. Proposer node produces app block, signs it, gossips via Protocol 100
2. Verifier nodes receive block, validate, sign, gossip response back
3. Proposer collects signatures until threshold met → finalize
4. Finalized block (with aggregated proof) gossiped to all peers

**Implementation**: For M2, MultiSig is implemented as a local threshold check. The consensus round uses Protocol 100 to gossip `ConsensusVote` messages (a special topicId like `{topicId}:consensus`). When threshold is met, the block is finalized locally.

## New Files

### 1. Interfaces in `node-api/`

#### Consensus (`node-api/.../consensus/`)

| File | Purpose |
|------|---------|
| `AppConsensus.java` | Interface: `canPropose()`, `createProof()`, `verifyProof()`, `consensusMode()` |
| `ConsensusMode.java` | Enum: SINGLE_SIGNER, MULTI_SIG, ROUND_ROBIN, BFT |
| `ConsensusProof.java` | Value class: mode, signatures list, threshold, proposerKey |
| `ConsensusParams.java` | Value class: threshold, totalSigners, timeoutMs, blockInterval |

#### Validation (`node-api/.../validation/app/`)

| File | Purpose |
|------|---------|
| `AppDataValidator.java` | Interface: `validate(AppMessage, ValidationContext) → ValidationResult` |
| `AppValidationResult.java` | Value class: valid/invalid + reason |
| `AppValidationContext.java` | Context: topicId, current block number, app state reference |

#### App Ledger (`node-api/.../ledger/`)

| File | Purpose |
|------|---------|
| `AppLedger.java` | Interface: storeBlock, getBlock, getLatestBlock, getTip, getBlocks(range) |
| `AppBlock.java` | Value class: blockNumber, topicId, messages, stateHash, timestamp, prevBlockHash, blockHash, consensusProof |
| `AppLedgerTip.java` | Value class: topicId, blockNumber, blockHash, timestamp |

#### Events (`node-api/.../events/`)

| File | Purpose |
|------|---------|
| `AppDataValidateEvent.java` | VetoableEvent: per-message validation before inclusion in app block |
| `AppBlockConsensusEvent.java` | VetoableEvent: per-block consensus check |
| `AppBlockProducedEvent.java` | Regular Event: after app block finalized and stored |
| `AppDataFinalizedEvent.java` | Regular Event: per-message notification that data is finalized |

### 2. Implementations in `node-runtime/`

#### Consensus (`node-runtime/.../consensus/`)

| File | Purpose |
|------|---------|
| `SingleSignerConsensus.java` | Always returns true for canPropose/verifyProof. Creates trivial proof. |
| `MultiSigConsensus.java` | Threshold-based signature collection. Creates aggregated proof. |
| `DefaultAppConsensusListener.java` | `@DomainEventListener(order=100)` on AppBlockConsensusEvent. Delegates to AppConsensus. |

#### Validation (`node-runtime/.../validation/app/`)

| File | Purpose |
|------|---------|
| `DefaultAppDataValidator.java` | Default: accepts all messages (no-op validation) |
| `DefaultAppValidationListener.java` | `@DomainEventListener(order=100)` on AppDataValidateEvent. Delegates to AppDataValidator. |

#### App Ledger (`node-runtime/.../ledger/`)

| File | Purpose |
|------|---------|
| `RocksDBAppLedger.java` | RocksDB-backed app ledger with 4 CFs |
| `InMemoryAppLedger.java` | In-memory implementation for testing |
| `AppLedgerCfNames.java` | Constants for CF names |

#### App Block Producer (`node-runtime/.../appmsg/`)

| File | Purpose |
|------|---------|
| `AppBlockProducer.java` | Scheduled service: drains mempool → validate → consensus → store → publish events |

### 3. Configuration Changes

Add to `YaciNodeConfig`:
```java
private String appConsensusMode = "single-signer";  // single-signer | multisig
private int appBlockIntervalMs = 5000;               // app block production interval
private int appConsensusThreshold = 1;               // for multisig: required signatures
private int appConsensusTotalSigners = 1;             // for multisig: total signers
private String appLedgerPath;                         // RocksDB path for app ledger
private boolean appLedgerEnabled = true;              // enable persistent storage
```

Add to `application.yml`:
```yaml
yaci:
  node:
    app-layer:
      consensus-mode: single-signer   # single-signer | multisig
      block-interval-ms: 5000
      consensus-threshold: 1
      consensus-total-signers: 1
      ledger-path: ${yaci.node.storage-path}/app-ledger
      ledger-enabled: true
```

## Data Flow

```
AppMessage (from Protocol 100 or 101)
    │
    ▼
AppMessageMemPool (M1, already exists)
    │
    ▼ (scheduled interval)
AppBlockProducer.produceBlock()
    │
    ├─► For each message: publish AppDataValidateEvent (VetoableEvent)
    │     └─► DefaultAppValidationListener → AppDataValidator.validate()
    │     └─► Rejected messages excluded from block
    │
    ├─► Build AppBlock from valid messages
    │
    ├─► Publish AppBlockConsensusEvent (VetoableEvent)
    │     └─► DefaultAppConsensusListener → AppConsensus.verifyProof()
    │     └─► If rejected: block not finalized (logged, retried next interval)
    │
    ├─► If accepted: store in AppLedger
    │     ├─► app_blocks CF
    │     ├─► app_state CF (update tip)
    │     └─► app_consensus_proofs CF
    │
    ├─► Publish AppBlockProducedEvent
    │
    └─► For each message in block: publish AppDataFinalizedEvent
```

## Implementation Order

### Step 1: Core interfaces in node-api
- `AppConsensus`, `ConsensusMode`, `ConsensusProof`, `ConsensusParams`
- `AppDataValidator`, `AppValidationResult`, `AppValidationContext`
- `AppLedger`, `AppBlock`, `AppLedgerTip`
- Events: `AppDataValidateEvent`, `AppBlockConsensusEvent`, `AppBlockProducedEvent`, `AppDataFinalizedEvent`
- **Tests**: None (interfaces only)

### Step 2: App ledger implementations
- `RocksDBAppLedger` with 4 CFs
- `InMemoryAppLedger` for tests
- `AppLedgerCfNames` constants
- **Tests**: Store/retrieve/tip for both implementations

### Step 3: Consensus implementations
- `SingleSignerConsensus`
- `MultiSigConsensus` (Ed25519 threshold signatures)
- `DefaultAppConsensusListener`
- **Tests**: SingleSigner always accepts, MultiSig threshold logic, edge cases

### Step 4: Validation implementation
- `DefaultAppDataValidator`
- `DefaultAppValidationListener`
- **Tests**: Default accepts all, custom validator rejects

### Step 5: AppBlockProducer
- Drain mempool → validate → consensus → store → publish
- Scheduled execution with configurable interval
- Topic-scoped block production
- **Tests**: Unit tests with InMemoryAppLedger, mock validators

### Step 6: Configuration + wiring
- Add config fields to `YaciNodeConfig`
- Add to `application.yml`
- Wire in `YaciNodeProducer`
- Wire in `YaciNode`: create AppBlockProducer, register listeners, start/stop lifecycle
- **Tests**: Config parsing, lifecycle

### Step 7: Integration testing
- Start YaciNode with app-layer + consensus enabled
- Submit messages via Protocol 100
- Verify app blocks produced at interval
- Verify messages validated and stored in app ledger
- Verify events published

## Key Reusable Code

| Existing | Path | Reuse For |
|---|---|---|
| `BlockProducer` | `node-runtime/.../blockproducer/BlockProducer.java` | Template for `AppBlockProducer` (scheduled drain + produce + publish) |
| `BlockConsensusEvent` | `node-api/.../events/BlockConsensusEvent.java` | Template for `AppBlockConsensusEvent` |
| `TransactionValidateEvent` | `node-api/.../events/TransactionValidateEvent.java` | Template for `AppDataValidateEvent` |
| `DefaultConsensusListener` | `node-runtime/.../validation/DefaultConsensusListener.java` | Template for `DefaultAppConsensusListener` |
| `DirectRocksDBChainState` | `node-runtime/.../chain/DirectRocksDBChainState.java` | CF creation pattern for `RocksDBAppLedger` |
| `InMemoryChainState` | `node-runtime/.../chain/InMemoryChainState.java` | Template for `InMemoryAppLedger` |
| `YaciAppMessageHandler` | `node-runtime/.../appmsg/YaciAppMessageHandler.java` | Event publishing pattern |

## Verification

1. **Unit tests**: Ledger store/retrieve, consensus accept/reject, validation accept/reject, block producer lifecycle
2. **Integration test**: Full flow — submit messages → block produced → stored in ledger → events published
3. **Backward compat**: App-layer disabled by default; consensus defaults to single-signer
4. **Build**: `./gradlew clean build -x integrationTest` passes
