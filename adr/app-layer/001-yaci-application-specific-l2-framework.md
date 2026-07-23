# ADR-001: Yaci Application-Specific L2 Framework

## Status
Historical vision — superseded by ADR-005 and the ADR-006/008 extensions

## Date
2026-03-11

> **Current-status note (2026-07-17):** This document preserves the original
> pre-Yano-split product vision. It is not the current implementation contract.
> The concrete app-chain architecture is [ADR-005](005-yano-app-chain-framework.md),
> with shipped extensions in ADR-006/008 and the live residual backlog in
> [open_item.md](open_item.md). In particular, the trustless/BFT items below
> remain aspirations unless a later accepted ADR explicitly delivers them.

## Context

Yaci is a Java implementation of Cardano mini-protocols that currently serves as a modular library for building relay nodes. The core libraries (core, helper, node-api, node-runtime) provide block synchronization, transaction submission, state queries, and a plugin system — but are limited to **Cardano L1 relay** functionality.

There is a significant gap in the Cardano ecosystem: **no Java-based, lightweight, application-specific chain framework exists that anchors to Cardano**. The existing options — Partner Chains SDK (Rust/Substrate, heavyweight full L1), Hydra (Haskell, narrow state channels), Milkomeda (EVM wrapper) — do not serve the Java enterprise ecosystem or lightweight L2 use cases.

We propose evolving Yaci into a **modular L2 framework** where:
- Yaci nodes relay Cardano L1 blocks (existing functionality, unchanged)
- In parallel, an **application-specific ledger** runs on the same nodes
- Web2 applications submit arbitrary data/computations to Yaci nodes via REST API
- Other Yaci nodes in the network **verify and co-sign** the data via pluggable consensus
- Finalized data is written to a separate ledger in RocksDB
- Periodically, finalized data is **anchored to Cardano L1**
- Communication between Yaci nodes uses a **CIP-0137-inspired overlay protocol**
- Consensus, validation, and authentication are all **pluggable**
- Trust models range from single-entity (trusted) to multi-entity (semi-trusted) to trustless

This also requires **multi-peer upstream support** — today Yaci connects to a single upstream Cardano node. The L2 vision requires a mesh of Yaci nodes talking to each other AND to Cardano nodes simultaneously.

## Goals

- Enable developers to build application-specific L2s on Cardano using Java
- Support pluggable consensus (from trusted single-signer to BFT)
- Support pluggable validation logic (application-specific verification)
- Support pluggable authentication (from open to SPO-authenticated)
- Anchor finalized L2 data to Cardano L1
- Multi-peer upstream: connect to multiple Cardano nodes AND Yaci peer nodes
- Target enterprise use cases: data attestation, trusted messaging, DPP, game score verification
- Backward-compatible: existing single-upstream relay mode continues to work unchanged

## Non-Goals (Initial Phase)

- Full EVM or WASM execution environment
- ZK proof generation or verification
- Token economics or slashing (can be added later)
- Complete parity with Cosmos SDK or OP Stack
- Replacing Partner Chains SDK for full L1 sidechain use cases

## Competitive Landscape

| Framework | Language | Consensus | L1 Anchoring | Trust Model | Enterprise Ready | Cardano Native |
|---|---|---|---|---|---|---|
| **Partner Chains SDK** (IOG) | Rust/Substrate | Pluggable (Minotaur/Jolteon) | SPO shared security + SNARK bridges | SPO opt-in staking | High | Yes (bridge) |
| **Hydra** | Haskell | Unanimity (state channels) | UTxO commitments | All participants honest | Moderate (narrow) | Yes |
| **Mithril** | Rust | Lottery + multi-sig | Reads L1 only | SPO stake-weighted | High (read-side) | Yes |
| **EigenLayer AVS** | Solidity/Go | Per-AVS custom | Smart contracts + OCR | Economic (restaked ETH) | High | No (Ethereum) |
| **Cosmos SDK** | Go | CometBFT | IBC light clients | Per-chain validators | Very High | No |
| **Substrate/Polkadot** | Rust | NPoS (Relay Chain) | PoV to Relay Chain | Shared security | High | No |
| **OP Stack** | Go | Optimistic rollup | Fraud proofs to Ethereum | 1 honest verifier | Very High | No (Ethereum) |
| **Polygon CDK** | Go/Rust | ZK rollup | ZK proofs to Ethereum | Cryptographic (ZK) | Very High | No (Ethereum) |
| **Hyperledger Fabric** | Go/Java | Pluggable (Raft/ARMA) | None by default | Permissioned consortium | Very High | No |
| **Celestia** | Go | CometBFT (DA only) | Raw data blobs | DA only; sovereign execution | Moderate | No |
| **Chainlink OCR** | Go | BFT (OCR) | Aggregated reports | Economic + cryptographic | Very High | Partial |
| **Proposed Yaci L2** | **Java** | **Pluggable** | **Native Cardano mini-protocols** | **Configurable spectrum** | **Target: High** | **Yes (native)** |

### Key Gap

No one offers a **Java-based, lightweight, application-specific chain framework that natively integrates with Cardano** and supports a trust spectrum from enterprise (single-signer) to public (SPO-authenticated). Yaci's unique advantage is native Cardano mini-protocol implementation — deep L1 integration that no other non-Haskell framework provides.

## Decision

Build the L2 framework as a set of pluggable layers on top of existing Yaci infrastructure. Adopt CIP-0137's protocol shape for inter-node communication but generalize authentication. Implement multi-peer upstream as a prerequisite.

## Architecture

### High-Level Layer Diagram

```
┌───────────────────────────────────────────────────────────────┐
│                      Application Layer                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐│
│  │ REST API     │  │ Validation   │  │ L1 Anchor Service    ││
│  │ (submit data,│  │ Logic Plugin │  │ (batch finalized     ││
│  │  query state)│  │ (pluggable)  │  │  data, submit to L1) ││
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘│
├─────────┼─────────────────┼──────────────────────┼────────────┤
│                    Consensus Layer                             │
│  ┌───────────────────────────────────────────────────────────┐│
│  │ AppConsensus (pluggable interface)                        ││
│  │  ┌──────────┐ ┌────────────┐ ┌───────────┐ ┌───────────┐││
│  │  │SingleSign│ │MultiSig    │ │RoundRobin │ │BFT        │││
│  │  │(trusted) │ │(n-of-m)    │ │(rotating) │ │(2f+1)     │││
│  │  └──────────┘ └────────────┘ └───────────┘ └───────────┘││
│  └───────────────────────────────────────────────────────────┘│
├───────────────────────────────────────────────────────────────┤
│              Transport Layer (CIP-0137-inspired)               │
│  ┌─────────────────┐ ┌─────────────┐ ┌───────────────────┐  │
│  │ AppMsgSubmission │ │ LocalAppMsg │ │ LocalAppMsg       │  │
│  │ Agent (N2N)      │ │ Submit      │ │ Notification      │  │
│  │ Protocol ID: 100 │ │ (N2C) : 101│ │ (N2C) : 102       │  │
│  └─────────────────┘ └─────────────┘ └───────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐│
│  │ MessageAuthenticator (pluggable)                          ││
│  │  Mode 0: Open │ Mode 1: Permissioned │ Mode 2: SPO-KES   ││
│  └───────────────────────────────────────────────────────────┘│
├───────────────────────────────────────────────────────────────┤
│                  App Ledger (RocksDB)                          │
│  ┌───────────┐ ┌─────────────┐ ┌───────────────────────────┐│
│  │ app_data   │ │ app_state   │ │ app_consensus_proofs      ││
│  │ (submitted │ │ (finalized  │ │ (signatures, votes,       ││
│  │  entries)  │ │  state)     │ │  proofs per app block)    ││
│  └───────────┘ └─────────────┘ └───────────────────────────┘│
├───────────────────────────────────────────────────────────────┤
│              Multi-Peer Connection Layer (new)                 │
│  ┌───────────────────────────────────────────────────────────┐│
│  │ PeerPool                                                  ││
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐││
│  │  │ Cardano     │ │ Cardano     │ │ Yaci Peer Node      │││
│  │  │ Upstream 1  │ │ Upstream 2  │ │ (L1 + App Layer)    │││
│  │  │ (L1 only)   │ │ (L1 only)   │ │                     │││
│  │  └─────────────┘ └─────────────┘ └─────────────────────┘││
│  └───────────────────────────────────────────────────────────┘│
├───────────────────────────────────────────────────────────────┤
│              Yaci Core (existing, unchanged)                   │
│  L1 ChainSync │ BlockFetch │ TxSubmission │ KeepAlive         │
│  L1 ChainState (RocksDB) │ EventBus │ Plugin SPI              │
│  NodeServer (multi-session inbound)                            │
└───────────────────────────────────────────────────────────────┘
```

### Network Topology

```
                    ┌──────────────────┐
                    │ Cardano Relay    │
                    │ (upstream L1)    │
                    └────┬────────┬────┘
                         │        │
              L1 sync    │        │   L1 sync
                         │        │
                ┌────────▼──┐  ┌──▼────────┐
   REST API ──►│ Yaci Node  │◄─►│ Yaci Node  │◄── REST API
   (submit     │ Operator A │   │ Operator B │    (submit
    app data)  └────────┬───┘   └───┬────────┘     app data)
                        │           │
              App Layer │           │ App Layer
              (DMQ P2P) │           │ (DMQ P2P)
                        │    ┌──────┘
                        │    │
                    ┌───▼────▼───┐
                    │ Yaci Node  │
                    │ Operator C │
                    └────────────┘
                         │
                    L1 anchor tx
                         │
                    ┌────▼────────────┐
                    │ Cardano L1      │
                    │ (anchor data)   │
                    └─────────────────┘
```

Each Yaci node maintains:
- **Outbound connections to Cardano nodes** — for L1 block sync (protocols 0-10)
- **Outbound connections to Yaci peer nodes** — for L1 redundancy AND app layer gossip (protocols 0-10 + 100-102)
- **Inbound connections from Yaci peer nodes** — via NodeServer (already multi-session)

A Yaci node connecting to another Yaci node gets **both L1 relay AND app layer** on the same connection (protocol multiplexing).

## Transport Layer: CIP-0137-Inspired with Pluggable Authentication

### Protocol Design

We adopt CIP-0137's protocol shape (pull-based gossip, topic isolation, TTL, deduplication) but generalize the message envelope to support pluggable authentication.

**Protocol IDs:**

| ID | Protocol | Direction | Purpose |
|----|----------|-----------|---------|
| 100 | App Message Submission | N2N | Pull-based app message gossip between Yaci peers |
| 101 | Local App Message Submission | N2C | Local application submits data for network diffusion |
| 102 | Local App Message Notification | N2C | Local application receives finalized/verified data |
| 103-199 | Reserved | — | Future app-layer protocols |

**Message Envelope (Generalized):**

```
CIP-0137 original:
  [messagePayload, kesSignature, operationalCertificate, coldVerificationKey]

Yaci generalized:
  [messagePayload, authMethod, authProof, topicId, expiresAt]

  authMethod:
    0 = open         → authProof = [nodeId, ed25519Signature]
    1 = permissioned → authProof = [partyId, signature]  (verified against allow-list)
    2 = spo-kes      → authProof = [kesSignature, opcert, coldVk]  (CIP-0137 compatible)
    3 = delegated    → authProof = [delegateSignature, delegationCert, spoSignature]
```

**Topic Isolation:** Each application/L2 instance gets its own `topicId`. Messages for different topics are logically separated. A single Yaci node can serve multiple app-layer topics simultaneously.

### MessageAuthenticator Interface

```java
public interface MessageAuthenticator {
    /** Verify an inbound message's authentication proof */
    boolean verify(AppMessage message);

    /** Sign an outbound message */
    AuthProof sign(byte[] messagePayload);

    /** Authentication mode identifier */
    int authMethod();
}
```

**Implementations:**
| Mode | Class | Key Source | Verification |
|------|-------|-----------|--------------|
| 0: Open | `OpenAuthenticator` | Self-generated Ed25519 keypair | Any valid signature accepted |
| 1: Permissioned | `PermissionedAuthenticator` | Pre-distributed keys | Verified against configured allow-list of public keys |
| 2: SPO-KES | `SpoKesAuthenticator` | KES key + operational cert | Verified against on-chain stake distribution (CIP-0137 compatible) |
| 3: Delegated | `DelegatedAuthenticator` | SPO-signed delegation cert | Delegation cert verified against SPO, then delegate signature verified |

## Pluggable Consensus

### AppConsensus Interface

```java
public interface AppConsensus {
    /** Can this node propose data at this round/time? */
    boolean canPropose(AppConsensusContext ctx);

    /** Given collected verification responses, should we finalize? */
    FinalizeDecision checkFinalization(AppBlock block, List<VerifyResponse> responses);

    /** Consensus parameters (threshold, timeout, etc.) */
    ConsensusParams params();

    /** Called when a new round starts */
    void onRoundStart(long round);
}
```

### Consensus Implementations

| Mode | Trust Level | Description | Use Case |
|------|-------------|-------------|----------|
| **SingleSigner** | Trusted (1 entity) | Proposer finalizes immediately, no peer verification | Enterprise internal, dev/test |
| **MultiSig(n,m)** | Semi-trusted | Requires n-of-m operator signatures to finalize | Consortium, partner networks |
| **RoundRobin** | Semi-trusted | Rotating proposer, others verify and co-sign | Fair ordering, oracle networks |
| **BFT(threshold)** | Trustless | 2f+1 agreement required, view change on timeout | Public verification networks |

### Trust Model Spectrum

```
Trusted              Semi-Trusted            Trustless
(Enterprise)         (Consortium)            (Public)
    │                    │                       │
SingleSigner         MultiSig(3/5)           BFT(2f+1)
Open auth            Permissioned auth       SPO-KES auth
No peer verification Known validators        Open validator set
Fastest              Medium latency          Higher latency
Simplest             Moderate complexity      Full protocol
```

Enterprise users can start with SingleSigner (trusted) and graduate to MultiSig or BFT as their network grows — without changing the application code, only the consensus configuration.

## Pluggable Validation

### AppDataValidator Interface

```java
public interface AppDataValidator {
    /** Validate proposed data against external source or business rules */
    ValidationResult validate(AppData data, ValidationContext ctx);
}
```

The validation logic is **entirely application-specific**. Examples:

| Application | Validation Logic |
|---|---|
| Weather oracle | Fetch from OpenWeatherMap API, compare with proposed value within tolerance |
| DPP (Digital Product Passport) | Verify document hash against known issuer registry |
| Game score verification | Query game server API, confirm score matches |
| Price feed | Fetch from multiple exchanges, verify median within threshold |
| Merkle root attestation | Recompute Merkle root from provided leaves |
| Enterprise audit log | Verify sender authorization, timestamp ordering, schema compliance |

## App Ledger (RocksDB)

Separate column families alongside existing L1 CFs in `DirectRocksDBChainState`:

| Column Family | Key | Value | Purpose |
|---|---|---|---|
| `app_data` | `{topicId}:{sequence}` | Submitted data entry + metadata | Raw submissions (pending + finalized) |
| `app_state` | `{topicId}:{key}` | Finalized state | Application state after consensus |
| `app_blocks` | `{topicId}:{blockNumber}` | App block (batch of finalized data) | Ordered finalized blocks |
| `app_consensus_proofs` | `{topicId}:{blockNumber}` | Signatures, votes, proofs | Consensus evidence per app block |
| `app_anchors` | `{topicId}:{anchorTxHash}` | L1 anchor reference | Tracks what has been anchored to Cardano |
| `app_topics` | `{topicId}` | Topic config (consensus mode, validators, auth mode) | Topic metadata |

## L1 Anchoring Strategies

### Anchor Transaction Format

A standard Cardano transaction with metadata:

```json
{
  "label": 7014,
  "content": {
    "v": 1,
    "topic": "<topicId>",
    "from_block": 1000,
    "to_block": 1099,
    "state_root": "<32-byte hex hash>",
    "merkle_root": "<32-byte hex hash of app block hashes>",
    "proof_type": "multisig",
    "proof": "<aggregate signature or proof bytes>",
    "operators": ["<operator1_pubkey>", "<operator2_pubkey>", ...],
    "ts": 1710000000
  }
}
```

### Anchoring Modes

| Strategy | Trigger | What's Anchored | Cost |
|---|---|---|---|
| **Periodic batch** | Every N app blocks or T seconds | State root + Merkle root of block range | 1 Cardano tx per interval |
| **Threshold-triggered** | When accumulated data exceeds importance threshold | Selected high-value data with full proof | Variable |
| **On-demand** | Application explicitly requests anchor | Specific data entry with individual proof | 1 tx per request |

The L1 Anchor Service constructs and submits the Cardano transaction using Yaci's existing `TxSubmissionAgent` or `LocalTxSubmissionAgent`.

## Multi-Peer Upstream Support

### Current Single-Upstream Architecture (Bottleneck)

Today, the entire upstream path is single-peer:

```
YaciNodeConfig (single remoteHost:remotePort)
  → YaciNode (single PeerClient field)
    → PeerClient (wraps single N2NPeerFetcher)
      → N2NPeerFetcher (single TCPNodeClient)
        → TCPNodeClient (single host:port, one Session)
          → HeaderSyncManager (single peerClient)
          → BodyFetchManager (single peerClient)
```

### Required: Multi-Peer Architecture

```
YaciNodeConfig
  upstreams:                              # New: list of upstream peers
    - { host, port, type: cardano }       # Pure L1 relay
    - { host, port, type: cardano }       # L1 redundancy
    - { host, port, type: yaci }          # Yaci peer (L1 + app layer)
    - { host, port, type: yaci }          # Yaci peer (L1 + app layer)

           ┌──────────────────────────────────┐
           │           PeerPool               │
           │  ┌────────┐ ┌────────┐ ┌───────┐│
           │  │Cardano │ │Cardano │ │Yaci   ││
           │  │Peer 1  │ │Peer 2  │ │Peer 1 ││  ... N peers
           │  │(L1)    │ │(L1)    │ │(L1+App)│
           │  └───┬────┘ └───┬────┘ └──┬────┘│
           └──────┼──────────┼─────────┼──────┘
                  │          │         │
        ┌─────────▼──────────▼─────────┘
        │                    │
  ┌─────▼──────┐    ┌───────▼────────┐
  │ HeaderFanIn│    │ AppMsgRouter   │
  │ (best tip, │    │ (route app     │
  │  dedupe)   │    │  messages to   │
  └─────┬──────┘    │  consensus)    │
        │           └────────────────┘
  ┌─────▼──────────────┐
  │ BodyFetchScheduler  │
  │ (distribute ranges  │
  │  across hot peers)  │
  └─────┬──────────────┘
        │
  ┌─────▼──────┐
  │ ChainState │
  └────────────┘
```

### Peer Types

| Type | L1 Protocols (0-10) | App Protocols (100-102) | Description |
|------|--------------------|-----------------------|-------------|
| **cardano** | Yes | No | Standard Cardano relay node (upstream L1 only) |
| **yaci** | Yes | Yes | Yaci peer node (L1 relay + app layer gossip) |
| **yaci-app-only** | No | Yes | App-layer-only peer (no L1 sync, just app data) |

Protocol negotiation during handshake determines which protocols are active per connection. A Yaci node connecting to a Cardano node uses only protocols 0-10. A Yaci node connecting to another Yaci node uses 0-10 + 100-102.

### Configuration

```yaml
yaci:
  node:
    # Existing single upstream (backward compatible)
    remote:
      host: preprod-node.world.dev.cardano.org
      port: 30000
      protocol-magic: 1

    # New: multi-peer upstream
    upstreams:
      - host: "cardano-relay-1.example.com"
        port: 3001
        type: cardano    # L1 only
      - host: "cardano-relay-2.example.com"
        port: 3001
        type: cardano    # L1 redundancy
      - host: "yaci-peer-1.partner.com"
        port: 13337
        type: yaci       # L1 + app layer
      - host: "yaci-peer-2.partner.com"
        port: 13337
        type: yaci       # L1 + app layer

    # App layer configuration
    app-layer:
      enabled: false     # Opt-in
      auth-mode: permissioned   # open | permissioned | spo-kes | delegated
      allowed-keys:              # For permissioned mode
        - "<operator1_pubkey_hex>"
        - "<operator2_pubkey_hex>"
      topics:
        - id: "weather-oracle"
          consensus: multisig
          consensus-params:
            threshold: 3
            total: 5
          validation-class: "com.example.WeatherValidator"
        - id: "audit-log"
          consensus: single-signer
          validation-class: "com.example.AuditValidator"
      anchor:
        enabled: true
        strategy: periodic
        interval-blocks: 100    # Anchor every 100 app blocks
        wallet-mnemonic-env: "ANCHOR_WALLET_MNEMONIC"
```

Backward compatibility: When `upstreams` is absent, the existing `remote.host`/`remote.port` config is used as a single cardano-type upstream.

### Key Changes to Existing Code

| Component | Current | Change Required |
|---|---|---|
| `YaciNodeConfig` | Single `remoteHost`/`remotePort` | Add `upstreams: List<UpstreamConfig>` with type field |
| `YaciNode` | Single `PeerClient` field | `PeerPool` managing multiple connections |
| `PeerClient` | Wraps single `N2NPeerFetcher` | Keep as single-peer worker; pool manages multiple instances |
| `NodeServerSession` | Hardcoded 4 agents in `createAgents()` | Extensible: allow plugins to register additional agents (100+) |
| `HeaderSyncManager` | Bound to single `PeerClient` | Receives best headers from `HeaderFanIn` (multiple peers) |
| `BodyFetchManager` | Bound to single `PeerClient` | Receives ranges from `BodyFetchScheduler` (distributed across peers) |
| `TCPNodeClient` / `NodeClient` | "Only one session per N2NClient" | Unchanged — one instance per peer; pool creates multiple instances |

## Use Cases

### 1. Data Attestation / Oracle Network

**Scenario:** Weather data from multiple cities, verified by operator nodes and anchored to Cardano.

**Flow:**
1. Web2 weather service submits `{ city: "London", temp: 15.2, source: "openweathermap" }` to Yaci Node A via REST API
2. Node A proposes via DMQ protocol (ID 100) to peers
3. Nodes B and C run `WeatherValidator` — fetch from same/different weather APIs, verify within tolerance
4. Nodes B and C send `VerifyResponse` with signatures back via DMQ
5. MultiSig(2/3) consensus: 2 of 3 verified → finalize
6. Data written to `app_state` CF in all nodes' RocksDB
7. Every 100 app blocks, L1 Anchor Service posts Merkle root to Cardano

### 2. Trusted Message Queue

**Scenario:** Banks exchanging settlement instructions with audit trail.

**Flow:**
1. Bank A submits settlement message to its Yaci node via REST API
2. Node gossips message via DMQ to Bank B's and auditor's Yaci nodes
3. `SettlementValidator` checks message schema, sender authorization, sequence ordering
4. MultiSig(2/3) consensus: Bank A + Bank B + Auditor agree → finalize
5. Message written to `app_data` CF with consensus proof
6. Consumer (Bank B's backend) pulls from Local App Message Notification (protocol 102)
7. Periodic anchor: batch of message hashes posted to Cardano for regulatory proof

**Advantages over traditional MQ (Kafka, RabbitMQ):**
- Multi-party trust: neither Bank A nor Bank B controls the broker
- Tamper-proof: message ordering and content integrity guaranteed by consensus
- Auditable: Cardano anchor provides immutable proof of delivery
- Censorship-resistant: no single operator can drop messages

### 3. Digital Product Passport (DPP)

**Scenario:** Supply chain data verified by manufacturer, logistics provider, and regulator.

**Flow:**
1. Manufacturer submits product data (materials, origin, certifications) to Yaci node
2. Logistics provider's node verifies against shipping records
3. Regulator's node verifies against compliance database
4. MultiSig(2/3) → finalize
5. Merkle root of DPP entries anchored to Cardano
6. Any party can verify a specific DPP entry against the on-chain Merkle root

### 4. Game Score Verification

**Scenario:** High-speed web2 game, scores verified and anchored for on-chain reward distribution.

**Flow:**
1. Game server submits match result `{ player: "alice", score: 15000, matchId: "xyz" }` to Yaci node
2. Verifier nodes query game server API, confirm match result
3. RoundRobin consensus: rotating verifier confirms → finalize
4. Scores accumulated in app ledger
5. Periodic anchor to Cardano triggers smart contract reward distribution

## Data Flow: End-to-End

```
Web2 App                    Yaci Node A              Yaci Node B             Cardano L1
   │                            │                         │                      │
   │ POST /api/app/submit       │                         │                      │
   │ { data, topic }            │                         │                      │
   │───────────────────────────►│                         │                      │
   │                            │                         │                      │
   │                            │ AppMsgSubmission (P100) │                      │
   │                            │ ProposeData + AuthProof │                      │
   │                            │────────────────────────►│                      │
   │                            │                         │                      │
   │                            │                         │ Validate(data)       │
   │                            │                         │ (pluggable logic)    │
   │                            │                         │                      │
   │                            │ VerifyResponse + sig    │                      │
   │                            │◄────────────────────────│                      │
   │                            │                         │                      │
   │                            │ CheckFinalization()     │                      │
   │                            │ (consensus threshold    │                      │
   │                            │  met → finalize)        │                      │
   │                            │                         │                      │
   │                            │ FinalizeAppBlock        │                      │
   │                            │────────────────────────►│                      │
   │                            │                         │                      │
   │                            │ Store in app_state CF   │ Store in app_state   │
   │                            │                         │                      │
   │                            │ (every N blocks)        │                      │
   │                            │ Anchor: state_root +    │                      │
   │                            │ merkle_root + proof     │                      │
   │                            │─────────────────────────┼─────────────────────►│
   │                            │                         │                      │
   │ 200 OK { appBlockNo,       │                         │                      │
   │          status: finalized }│                         │                      │
   │◄───────────────────────────│                         │                      │
```

## Feasibility Against Existing Yaci Infrastructure

### What Exists and Can Be Reused

| Existing Component | File | Reuse For |
|---|---|---|
| Agent<T> state machine | `core/protocol/Agent.java` | Template for 3 new DMQ agents |
| Protocol multiplexing (Netty) | `core/network/` | Routes app protocols (100+) alongside L1 (0-10) automatically |
| TxSubmission pull-based pattern | `core/protocol/txsubmission/` | Direct model for AppMsgSubmission agent state machine |
| NodeServer multi-session | `core/network/server/NodeServer.java` | Already handles multiple inbound connections |
| RocksDB dynamic CFs | `node-runtime/chain/DirectRocksDBChainState.java` | Add `app_*` column families via `cfByName` map |
| Plugin SPI + service registry | `node-api/plugin/PluginContext.java` | Register consensus, validator, authenticator services |
| EventBus (type-safe) | `events-core/` | Publish L2 events alongside L1 events |
| Block producer pattern | `node-runtime/blockproducer/BlockProducer.java` | Template for app block production (scheduled, drain mempool, store, publish) |
| TxSubmission handler | `node-runtime/handlers/YaciTxSubmissionHandler.java` | Template for app data submission handling |

### What Needs to Be Built

| Component | Module | Effort |
|---|---|---|
| Multi-peer PeerPool | `node-runtime` | Medium — pool of N2NPeerFetcher instances with type tagging |
| HeaderFanIn | `node-runtime` | Medium — best-tip selection, dedup (per ADR-009) |
| BodyFetchScheduler | `node-runtime` | Medium — range distribution across peers (per ADR-009) |
| AppMsgSubmissionAgent (N2N, P100) | `core` | Medium — new Agent subclass, pull-based state machine |
| LocalAppMsgSubmitAgent (N2C, P101) | `core` | Small — request-response agent |
| LocalAppMsgNotificationAgent (N2C, P102) | `core` | Small — blocking/non-blocking notification agent |
| MessageAuthenticator interface + impls | `node-api` + `node-runtime` | Medium — 4 implementations |
| AppConsensus interface + impls | `node-api` + `node-runtime` | Medium-Large — SingleSigner easy, BFT complex |
| AppDataValidator interface | `node-api` | Small — interface only, impls are plugins |
| App Ledger (RocksDB CFs + manager) | `node-runtime` | Medium — new CFs, read/write/query layer |
| L1 Anchor Service | `node-runtime` | Small — tx construction + submission |
| NodeServerSession extensibility | `core` | Small — allow plugin-registered agents |
| REST API for app data | `node-app` | Medium — submit, query, status endpoints |
| App-layer events | `node-api` | Small — AppDataSubmittedEvent, AppDataFinalizedEvent, etc. |
| YaciNodeConfig multi-upstream | `node-api` | Small — add upstreams list, backward compat |

## Phased Roadmap

### M0: Multi-Peer Foundation
**Goal:** Support multiple upstream connections (Cardano and Yaci nodes).

- Add `upstreams: List<UpstreamConfig>` to `YaciNodeConfig` (backward-compatible)
- Build `PeerPool` managing multiple `N2NPeerFetcher` instances with peer type tagging
- Build `HeaderFanIn` for best-tip selection from multiple peers
- Build `BodyFetchScheduler` for range distribution across peers
- Refactor `HeaderSyncManager` and `BodyFetchManager` to use fan-in/scheduler
- All behind config flag; single-upstream mode unchanged

### M1: Transport Layer (CIP-0137-inspired)
**Goal:** Yaci nodes can gossip app-layer messages to each other.

- Implement `AppMsgSubmissionAgent` (protocol 100) — pull-based N2N gossip
- Implement `LocalAppMsgSubmitAgent` (protocol 101) — N2C submit
- Implement `LocalAppMsgNotificationAgent` (protocol 102) — N2C notification
- Implement `MessageAuthenticator` interface + Open and Permissioned modes
- Make `NodeServerSession.createAgents()` extensible for app-layer agents
- App message mempool with topic routing, TTL, dedup
- Basic events: `AppMessageReceivedEvent`, `AppMessagePropagatedEvent`

### M2: Consensus + Validation Framework
**Goal:** Pluggable consensus and validation for app data.

- Implement `AppConsensus` interface + `SingleSigner` and `MultiSig` implementations
- Implement `AppDataValidator` interface
- Build app block production (scheduled, configurable interval)
- App ledger RocksDB column families (`app_data`, `app_state`, `app_blocks`, `app_consensus_proofs`)
- Events: `AppDataSubmittedEvent`, `AppDataValidatedEvent`, `AppDataFinalizedEvent`, `AppBlockProducedEvent`

### M3: L1 Anchoring + First Use Cases
**Goal:** Anchor finalized app data to Cardano L1. Ship data attestation and MQ reference implementations.

- L1 Anchor Service (periodic batch strategy)
- Anchor transaction construction (metadata label 7014)
- REST API: `/api/app/{topic}/submit`, `/api/app/{topic}/query`, `/api/app/{topic}/status`
- Reference implementation: Data Attestation Oracle plugin
- Reference implementation: Trusted Message Queue plugin
- `app_anchors` CF for tracking anchored data

### M4: Advanced Consensus + Hardening
**Goal:** Add RoundRobin and BFT consensus. SPO-KES authentication. Production hardening.

- `RoundRobin` consensus implementation
- `BFT` consensus implementation (view change protocol)
- `SpoKesAuthenticator` (CIP-0137-compatible, verify against on-chain stake distribution)
- `DelegatedAuthenticator`
- Observability: metrics for app layer (messages/sec, consensus latency, anchor frequency)
- Peer scoring for app-layer peers
- Load/soak testing

## Events (App Layer)

| Event | Type | Published When |
|-------|------|---------------|
| `AppMessageReceivedEvent` | Regular | App message received from peer or local submission |
| `AppMessageValidatedEvent` | Regular | Validation logic completed (with result) |
| `AppDataFinalizedEvent` | Regular | Consensus threshold met, data finalized |
| `AppBlockProducedEvent` | Regular | App block created from finalized data |
| `AppAnchorSubmittedEvent` | Regular | L1 anchor transaction submitted |
| `AppAnchorConfirmedEvent` | Regular | L1 anchor transaction confirmed on-chain |
| `AppDataSubmitEvent` | Vetoable | Before data accepted into app mempool (plugins can reject) |

## CIP-0137 Relationship

CIP-0137 (Decentralized Message Queue) defines a standardized protocol for topic-based, authenticated message diffusion across Cardano's P2P network. It was designed for Mithril signature diffusion but is explicitly generic.

**What we adopt from CIP-0137:**
- Pull-based gossip protocol shape (consumer-driven, like TxSubmission)
- Topic-based message isolation
- Message TTL and expiration
- Deduplication by message ID
- Three mini-protocol pattern (N2N gossip, N2C submit, N2C notify)

**Where we diverge from CIP-0137:**
- **Authentication is pluggable**, not locked to SPO KES keys. Enterprise deployments use simpler auth modes (open, permissioned). SPO-KES remains available for trustless/public networks as one mode among several.
- **Protocol IDs**: We use 100-102 (Yaci app-layer range) rather than waiting for CIP-0137's eventual official allocation, since this protocol operates purely in the Yaci node layer.
- **Message envelope**: Generalized to include `authMethod` field, making the authentication scheme self-describing.

**CIP-0137 compatibility**: In SPO-KES mode (authMethod=2), the message envelope is wire-compatible with CIP-0137, enabling interoperability with future CIP-0137 DMQ nodes if needed.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Scope creep** | Framework becomes too complex | Start with M0-M1, ship incrementally. SingleSigner first, BFT last |
| **Protocol isolation** | Bug in app-layer agent affects L1 sync | Strict protocol isolation: separate message parsing, no shared mutable state between L1 and app agents |
| **L1 anchoring cost** | Cardano tx fees for anchoring | Batch aggressively, configurable interval, on-demand mode for cost-sensitive deployments |
| **Consensus complexity** | BFT is hard to get right | Defer BFT to M4. Start with SingleSigner/MultiSig (well-understood) |
| **Large app data** | RocksDB and gossip protocol not designed for large blobs | Store hashes on-chain and in gossip; data stored off-chain (IPFS/S3). Enforce max message size in protocol |
| **No economic security** | Trustless mode lacks slashing incentives | Acceptable for enterprise (trusted/semi-trusted). Economic model can be added later for public mode |
| **Multi-peer complexity** | PeerPool, HeaderFanIn, BodyFetchScheduler are non-trivial | Builds on ADR-009 design. Leverage NodeServer's proven multi-session pattern for outbound |
| **Single-upstream regression** | Refactoring breaks existing relay functionality | Backward-compatible config. Single upstream remains default. Feature-flagged rollout |

## Relationship to Other ADRs

- **ADR-009** (Archive/Relay/Governor/Tx Gossip): M0 of this ADR implements the multi-upstream components from ADR-009 (PeerPool, HeaderFanIn, BodyFetchScheduler). This ADR extends that foundation with app-layer protocols.
- **ADR-013** (Plugin/Event Gaps): The app-layer events and plugin interfaces proposed here address several gaps identified in ADR-013 (peer management, transaction lifecycle, observability).
- **ADR-012** (Bootstrap State Mode): Lightweight relay bootstrapping complements the app layer — a new Yaci node can bootstrap L1 state quickly, then begin participating in app-layer consensus.

## Open Questions

1. **Metadata label for L1 anchoring**: Should we use a registered CIP-10 label (requires CIP submission) or an unregistered high-range label (e.g., 7014)?
2. **App block interval**: Should app blocks be time-based (every N seconds), count-based (every N data entries), or triggered by consensus completion?
3. **Cross-topic dependencies**: Can app data in topic A depend on state from topic B? Or are topics fully isolated?
4. **Peer discovery for Yaci nodes**: Should we extend PeerSharing (protocol 10) to advertise app-layer capabilities, or use a separate discovery mechanism?
5. **App data pruning**: Should the app ledger support pruning (like L1 PRUNED mode in ADR-009), or is full history always retained?
6. **Governance of topic configuration**: Who can create/modify topics? Single admin, multi-sig, or on-chain governance action?
