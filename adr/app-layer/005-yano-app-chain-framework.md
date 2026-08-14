# ADR-005: Yano App-Chain Framework — A Parallel Application Ledger over the appmsg Protocol

## Status
Accepted and implemented — core framework; evolved by ADR-006/008+

## Date
2026-07-07 (updated 2026-07-08)

## Implementation Status

> **Current-status note (2026-07-17):** M1-M6 below are complete. Later ADRs
> delivered rotating sequencing, governed membership, script anchors, effects,
> plugins, first-party connectors, and governed composite profiles. This
> section remains the delivery record for the v1 core; use
> [open_item.md](open_item.md) for the current residual backlog.

- **M1 (transport hardening + Yano wiring)**: done. Yaci `feat/app_layer_changes` (published locally as `0.5.0-app-layer-local`): envelope v2, Blake2b content-derived ids, `MsgInit`/`MsgInitAck` chain-scoped negotiation, TTL/size/id enforcement, pluggable `AppMsgValidator`, CDDL specs under `core/src/main/cddl/appmsg/`. Yano `feat/app_layer`: `api.appchain` (config/gateway/events), `runtime.appchain` (`AppChainSubsystem`, `AppPeerClient`, Ed25519+membership validation), `ServeSubsystem.enableAppLayer`, REST `/app-chain/*`, two-node socket-level integration test (bidirectional exchange + non-member rejection) green.
- **M2 (sequenced durable ledger)**: done. S1 fixed sequencer (`AppChainEngine`) with persisted vote locks, re-propose on timeout, fully verified threshold finality certs; `AppLedgerStore` (own RocksDB) committing block + tip + message index + MPF trie nodes + state root in ONE atomic WriteBatch (MPF `withBatch` staging); `AppStateMachine` SPI + built-in `ordered-log`; consensus rides system topics `~consensus/propose|vote|cert` over protocol 100; REST tip/blocks/proof; CDDL `core-api/src/main/cddl/appchain/app-block.cddl`. Verified: 2-node integration (identical roots, restart/replay, dedup vs ledger), adversarial (rogue proposer fail-closed), process-level devnet demo (identical roots + certs + MPF proofs over REST, L1 lock-step).
- **M3 (L1 anchoring, metadata mode)**: done. `AnchorService` builds/signs/submits anchor txs (metadata label 7014: chain-id, height range, block-hash, state-root) through the node's **own** tx gateway, selects inputs from the node's own UTXO state, confirms via its own `BlockAppliedEvent` stream, resubmits on timeout, and un-confirms on L1 rollback. Blocks carry a stable-depth `l1-ref` (proposer-side, from the subsystem's observed L1 block window). Verified on live devnet: anchor wallet faucet-funded → anchor tx confirmed on L1 → REST anchor status. *Deferred to M4:* strict follower verification of `l1-ref` (needs wait/retry semantics that pair naturally with catch-up), script-anchor mode (A2) and the full `L1View` read API.
- **M4 (catch-up + liveness observability)**: done, with a scope adjustment. **Protocol 103 AppChainSync** (yaci: `protocol/appchainsync`, CDDL `app-chain-sync-v103.cddl`): BlockFetch-style range fetch of finalized blocks; the yano client verifies every fetched block fail-closed (hash chain, proposer, message envelopes, threshold cert, re-executed state root) before committing — verified by a late-joiner integration test. `AppChainStalledEvent` fires when a peer advertises a higher tip with no local progress. *Scope adjustment (2026-07-08):* the S2 rotating sequencer and chain-governed membership are deferred to a follow-up milestone — S1 + catch-up + anchoring is a coherent, honest v1 (sequencer failure = ops runbook, as Option S1 documented), and plugin loading / cluster tooling (M5/M6) deliver more user value first. The D2/D6 designs remain the blueprint.
- **M5 (plugin loading + reference app)**: done. `AppStateMachineProvider` ServiceLoader SPI — custom app chains deploy as jars (implementation + `META-INF/services` entry) on a stock yano distribution, selected via `yano.app-chain.state-machine`; verified by a test provider (`test-kv`, interprets opaque bodies as key=value — the reference pattern for custom apps) driving a single-member self-proposing chain end-to-end incl. MPF proofs, plus fail-fast on unknown ids. Haskell-downstream L1 compatibility is covered by the existing `test-haskell-sync` regression (the app layer is invisible to Haskell peers — V100 never negotiated); recommended as a pre-release regression run.
- **M6 (tooling)**: done. `.claude/skills/test-app-chain-cluster` — full two-node cluster smoke test (sequencer + member + anchoring on devnet L1) with pass criteria; validated end-to-end against the final build.
- **CDDL specs** (for third-party compatible implementations): yaci `core/src/main/cddl/appmsg/` (envelope v2, protocols 100/101/102/103) + yano `core-api/src/main/cddl/appchain/app-block.cddl` (block, finality cert, vote, cert-notice).

## Related
- Supersedes the vision/roadmap portions of `adr/app-layer/001` (written pre-split, when Yano was part of Yaci)
- `adr/app-layer/002` — multi-peer foundation; superseded by Yano's `p2p`/`consensus` modules and `adr/017`
- `adr/app-layer/003` — appmsg transport spec; **implemented** in Yaci `next` (protocols 100/101/102), refined here
- `adr/app-layer/004` — M2 consensus/validation design; redesigned here (never implemented in Yano)
- `adr/in-progress/027` — product vision; this ADR is the follow-up design for **R5 (rollup node framework)** and groundwork for **R6 (sidechain framework)**
- `adr/in-progress/028` — runtime decomposition; the app chain integrates as a `Subsystem` under `NodeKernel`
- `adr/x402/001` — x402 facilitator; candidate flagship use case
- CIP-137 (Decentralised Message Queue), status Proposed; Haskell `dmq-node` ships with cardano-node 10.7.0

---

## 1. Context

### 1.1 Where we are now

Yano is a Cardano node implementation in Java, built as a **library first** (`YanoAssembly` composition root, role interfaces, `NodeKernel`/`Subsystem` lifecycle) with a Quarkus app wrapper. One of Yano's product goals is to become a **framework for building app chains**: a parallel, application-specific ledger maintained by a trusted or semi-trusted group of Yano instances, alongside the Cardano L1 they already sync — with app state committed to L1 periodically.

The building blocks are further along than when ADRs 001–004 were written:

| Layer | Status today |
|---|---|
| **appmsg transport** (CIP-137-derived, protocols 100/101/102) | **Built and merged** in Yaci `next` (v0.5.0-pre10 — Yano's current dependency). N2N gossip agent pair, N2C submit/notify client agents, V100 handshake capability negotiation, full multiplexing on the same TCP connection as ChainSync/BlockFetch/TxSubmission. Verified by a real socket-level integration test. |
| **Server extensibility** (`AgentFactory` on `NodeServer`/`NodeServerSession`) | Built; already used in Yano for `PeerSharingServerAgent` |
| **Client activation** (`PeerClient.enableAppMsg()`, `AppProtocolManager`) | Built in Yaci helper; **not yet called anywhere in Yano** |
| **Multi-peer foundation** (peer governor, header fan-in, pluggable chain selection) | Substantially built in Yano `p2p`/`consensus` modules (`feat/upstream_selection`) |
| **Kernel extension point** (`NodeKernel`, `Subsystem`, `SubsystemContext` with EventBus + ServiceRegistry) | Built and idiomatic; currently underused (one subsystem registered) |
| **State commitment structures** | **Available in cardano-client-lib `verified-structures`**: Merkle Patricia Forestry (`MpfTrie`, Aiken-compatible, proof export as `PlutusData`, tested on-chain validator) and Jellyfish Merkle Tree (versioned state, historical proofs). RocksDB + RDBMS backends. Not yet Yano dependencies. |
| **L1 tx submission** | Built — Yano has its own mempool, validation and tx diffusion to upstream peers; an anchor tx can be submitted through the node itself, no external provider needed |
| **Everything above the transport** (app mempool policy, sequencing, app ledger, commitment, anchoring, events, REST, catch-up) | **Missing in Yano** — this ADR designs it |

### 1.2 What the PoC taught us

The `feat/app_layer` branch in Yaci (never merged; ~12.5K lines across 134 files) prototyped the full stack: gossip transport, single-signer/multisig consensus (`AppConsensusCoordinator`, `ConsensusRound`), RocksDB app ledger, block producer, REST API. The transport was later rewritten and merged into Yaci `next`; everything above it was left behind. Lessons the fresh design must incorporate:

1. **No L1 anchoring existed** — the PoC was a pure off-chain overlay, not an L2. Anchoring is not an add-on; the commitment structure must be designed for it from the start.
2. **No merkle structure** — `stateHash` was a linear SHA-256 fold over message IDs: no inclusion proofs, no partial verification, useless for bridges. A real authenticated structure (MPF/JMT) is mandatory.
3. **Security must be real, not decorative** — `SingleSignerConsensus.verifyProof()` only checked `signatureCount() > 0` (any peer could forge a finalized block); `PermissionedAuthenticator.verify()` did a hex-prefix match instead of signature verification. Every proof and auth check in the new design must be cryptographically enforced and covered by adversarial tests.
4. **Topic flooding doesn't scale** — every message for every topic was gossiped to every peer with no subscription filtering.
5. **Liveness gaps** — a timed-out multisig round was silently dropped with no retry; the chain stalls forever at that height.
6. **Ephemeral identity trap** — auto-generated per-restart Ed25519 keys silently rotated nodes out of the proposer set.
7. **One serialization format** — the PoC had three incompatible hand-rolled encodings (CBOR wire, `DataOutputStream` ledger, a near-duplicate framing inside consensus DTOs) and five copies of the same SHA-256 helper. The new design uses **CBOR end-to-end** with one codec per type.
8. **No catch-up path** — a node that joined late or fell behind had no way to fetch historical app blocks. Protocol 100 is a mempool-style diffusion channel, not a history sync channel.
9. **Unbounded memory** — the wire-level dedup set grew forever.
10. **What worked and is worth keeping**: the tx-submission-shaped pull gossip protocol, the `AgentFactory` extension point, VetoableEvent integration with the plugin system, the topic-scoped design, and the layered api/runtime/app packaging.

### 1.3 CIP-137 alignment and deliberate divergence

CIP-137 (DMQ) gives us a battle-tested protocol *shape*: pull-based, ack/req-windowed gossip identical in structure to tx-submission, with content-derived message IDs and TTL-bounded buffering. Two properties of CIP-137 are deliberate **non-goals for DMQ but requirements for us**, and drive this design:

- **DMQ is ephemeral and order-agnostic** — messages expire, fetch order is unspecified, and equivocation/ordering is explicitly pushed to the application. An app chain needs **durable, totally-ordered** history. Therefore: the gossip layer stays ephemeral (it is a diffusion mechanism, nothing more), and **ordering + durability live in the layers above it** (sequencer + app ledger). We do not try to make the queue itself a log.
- **DMQ auth is hard-wired to SPO identity** (KES + opcert + stake distribution membership). An app group's trust root is its own membership registry. The envelope shape (payload + proof + identity-in-known-set check) is preserved; the credential scheme is pluggable.

Interop note: a Haskell `dmq-node` now ships with cardano-node 10.7.0. We keep a **compatibility path** (an SPO-KES authenticator mode and a CDDL-conformant envelope mapping) as a future option, but do not constrain the core design by it.

### 1.4 Blob-first invariant

**App messages are opaque blobs.** This is a load-bearing design rule, not an implementation detail:

- The **transport** sees only the envelope (id, blob, auth, topic, expiry) — routing, dedup, flow control.
- The **sequencer/consensus** orders opaque blobs into app blocks without ever parsing them.
- Only the **application state machine** (developer-supplied) decodes the blob — optionally through a typed codec SPI so Java applications get type-safe message classes while the framework stays schema-agnostic.

Consequences: one Yano network can carry any application's message format (CBOR, JSON, protobuf, flat bytes); framework upgrades never require app-schema migrations; and multiple apps can share one node via topics/chains without the framework knowing anything about their payloads.

---

## 2. Vision

**One node, two ledgers.** A Yano node keeps doing exactly what it does today — sync, verify, store and serve Cardano L1 — and, when enabled, additionally participates in one or more **app chains**: ordered, replicated, application-specific ledgers maintained by a defined group of Yano nodes, communicating over the *same* N2N connections via the appmsg protocol family, with app state authenticated by a Merkle Patricia Forestry root and periodically **anchored to Cardano L1**.

```
        The same yano-to-yano TCP connection
┌──────────────────────────────────────────────────────┐
│ L1 protocols            App protocols                │
│  2 ChainSync             100 AppMsgSubmission (gossip)│
│  3 BlockFetch            103 AppChainSync (catch-up)  │
│  4 TxSubmission                                       │
│  8 KeepAlive            (101/102 N2C — local clients) │
│ 10 PeerSharing                                        │
└──────────────────────────────────────────────────────┘
   Cardano L1 ledger          App ledger (per chain)
   RocksDB chain state        RocksDB app store + MPF root
        │                              │
        └────────── anchor tx ─────────┘
              (MPF root → L1 metadata / script datum)
```

**Framework and product at once.** Because Yano is a library:

- **As a framework**: a developer implements one interface — an `AppStateMachine` (validate / apply / query over opaque messages) — and assembles a custom node with `YanoAssembly....appChain(spec).build()`. The framework supplies networking, ordering, persistence, state commitment, anchoring, catch-up, events and REST. This is deliberately the ABCI/Cosmos-SDK shape, minus a token economy, plus native Cardano anchoring — in Java, for the JVM enterprise ecosystem no existing Cardano L2 tooling serves.
- **As a product**: out of the box, enabling one config option turns two or more Yano nodes into an app-chain group running the built-in **ordered log / KV state machine** — a tamper-evident, multi-party, L1-anchored message log with REST APIs and inclusion proofs. No code required.

**Why L1 stays on.** Every app-chain node is also an L1 node, which buys three things for free that standalone frameworks have to build: (a) a **shared clock and randomness source** (L1 slots, epoch nonces) for sequencer scheduling; (b) a **trusted data feed** of L1 state for app logic (bridges, registries, oracles of on-chain facts) with well-defined rollback semantics; (c) a **native settlement path** — the node itself builds, submits and observes anchor transactions through its own upstream connections and its own chain-follower, with no external API dependency.

**Trust spectrum.** The primary target is trusted (single operator) and semi-trusted (consortium, n-of-m) groups — enterprise data attestation, inter-company messaging, bridges with known operators. The SPIs keep the door open to trustless modes (SPO-authenticated membership, BFT) without committing to them now.

---

## 3. Goals and Non-Goals

### Goals
1. Ship an **app-chain subsystem** in Yano: diffusion → sequencing → durable ordered app ledger → MPF state commitment → L1 anchoring → catch-up, behind clean SPIs.
2. **Out-of-box mode**: config-only activation of a default ordered-log/KV app chain between ≥2 Yano nodes.
3. **Library mode**: `AppStateMachine` SPI + assembly API for custom nodes; publishable modules.
4. Reuse the **same peer connections** for L1 and app protocols; no second port, no separate process.
5. **Deterministic, rollback-safe** consumption of L1-derived data in app logic.
6. Harden the appmsg transport (auth enforcement, TTL, size caps, dedup eviction, subscription filtering, group identity) — in the Yaci repo, coordinated with this work.
7. Verifiable **inclusion proofs** against anchored roots (off-chain verification always; on-chain via the existing Aiken MPF validator when the script-anchor mode is used).

### Non-Goals (this ADR / first shipping cycle)
- Full BFT with view change (SPI accommodates it; implementation deferred).
- Fraud proofs / optimistic-rollup dispute games, ZK proofs.
- EVM/WASM execution environments.
- Public trustless operation, token economics, slashing.
- Upstreaming protocol numbers or handshake versions to the Cardano protocol registry (private Yano-to-Yano overlay is acceptable; CIP alignment is a later option).

---

## 4. Architecture

### 4.1 Layered view

```
┌────────────────────────────────────────────────────────────────────┐
│ Application (developer-supplied or built-in)                       │
│   AppStateMachine: validate(msg) / apply(block) / query()          │
│   Typed codecs (optional) — the only layer that parses the blob    │
├────────────────────────────────────────────────────────────────────┤
│ App-Chain Subsystem (new module: app-chain)                        │
│  ┌──────────────┐ ┌──────────────┐ ┌─────────────────────────────┐ │
│  │ AppMsgPool   │ │ AppSequencer │ │ AppLedger                   │ │
│  │ admission,   │ │ ordering,    │ │ ordered blocks (CBOR),      │ │
│  │ TTL, dedup,  │ │ proposer     │ │ hash-linked, atomic commit  │ │
│  │ auth verify  │ │ schedule,    │ │ + StateCommitment (MPF/JMT) │ │
│  │              │ │ finality cert│ │ + replay/crash recovery     │ │
│  └──────────────┘ └──────────────┘ └─────────────────────────────┘ │
│  ┌──────────────┐ ┌──────────────┐ ┌─────────────────────────────┐ │
│  │ Membership & │ │ L1View       │ │ AnchorService               │ │
│  │ Identity     │ │ stable-depth │ │ build+submit anchor tx via  │ │
│  │ registry     │ │ L1 reads,    │ │ own TxGateway; observe      │ │
│  │ (static→L1)  │ │ rollback-safe│ │ confirmations via own sync  │ │
│  └──────────────┘ └──────────────┘ └─────────────────────────────┘ │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ AppChainSync (catch-up): serve/fetch historical app blocks   │  │
│  └──────────────────────────────────────────────────────────────┘  │
├────────────────────────────────────────────────────────────────────┤
│ Transport (yaci, exists — to be hardened)                          │
│   Protocol 100 AppMsgSubmission (N2N pull gossip)                  │
│   Protocol 103 AppChainSync (N2N range fetch — new)                │
│   Protocols 101/102 (N2C submit/notify — optional, REST preferred) │
│   V100 handshake capability + app-group identity                   │
├────────────────────────────────────────────────────────────────────┤
│ Yano runtime (exists)                                              │
│   NodeKernel/Subsystem · EventBus/VetoableEvent · PeerGovernor     │
│   ChainState/LedgerState · TxSubsystem (anchor path) · REST (app)  │
└────────────────────────────────────────────────────────────────────┘
```

### 4.2 Message envelope v2

The current wire envelope `[messageId, messageBody, authMethod, authProof, topicId, expiresAt]` carries auth as opaque bytes with no defined proof structure, no sender identity, and no replay ordering hint. Envelope v2 (CBOR, one canonical CDDL):

```cddl
app-message = [
    version     : uint,              ; envelope version = 2
    message-id  : bstr .size 32,     ; blake2b-256(cbor(signed-body))  — content-derived
    chain-id    : tstr,              ; app-chain identity (group-scoped namespace)
    topic       : tstr,              ; sub-stream within the chain ("" = default)
    sender      : bstr .size 32,     ; member public key (or key hash) — identity
    sender-seq  : uint,              ; per-sender sequence number (gap detection, replay)
    expires-at  : uint,              ; unix seconds; enforced at admission and relay
    body        : bstr,              ; OPAQUE application payload — never parsed below the app
    auth        : auth-proof
]

auth-proof = [ scheme : uint, proof : bstr ]
  ; scheme 0 = ed25519      proof = signature by `sender` over signed-body
  ; scheme 1 = spo-kes      proof = cbor([kes-sig, opcert, cold-vk])   (CIP-137 compat, future)

signed-body = [ chain-id, topic, sender, sender-seq, expires-at, body ]
```

Rules learned from CIP-137 and the PoC:
- `message-id` is **recomputed by every receiver** and compared; mismatch = protocol violation.
- `expires-at` is **enforced** (admission + relay + mempool sweep), with a per-chain max-TTL parameter.
- Max message size is a per-chain parameter **enforced at the agent** (reject, don't just configure).
- `sender-seq` enables per-sender gap detection and replay rejection; it does **not** define global order — the sequencer does.
- Signature verification is mandatory at admission; membership check (`sender ∈ registry`) is the authorization gate.

### 4.3 App block format

```cddl
app-block = [
    version      : uint,
    chain-id     : tstr,
    height       : uint,              ; global sequence, per chain (not per topic)
    prev-hash    : bstr .size 32,     ; hash of previous app-block
    l1-ref       : [ slot : uint, block-hash : bstr .size 32 ],
                                       ; stable L1 point observed by proposer (see D5)
    timestamp    : uint,
    messages-root: bstr .size 32,     ; merkle root over ordered message-ids
    state-root   : bstr .size 32,     ; MPF (or JMT) root AFTER applying this block
    messages     : [ * app-message ],
    proposer     : bstr .size 32,
    cert         : finality-cert
]

finality-cert = [ scheme : uint, [ * [ signer : bstr .size 32, sig : bstr ] ] ]
  ; threshold signatures over block-hash; scheme selects sequencer mode semantics

block-hash = blake2b-256(cbor([version, chain-id, height, prev-hash, l1-ref,
                               timestamp, messages-root, state-root]))
```

Deliberate changes vs the PoC: one **global height per chain** (topics are sub-streams inside blocks, not separate chains — see D7); a real **merkle root over messages** (inclusion proofs for individual messages); the **post-state root inside the block** (followers verify state transition byte-for-byte, and the anchor commits to a block hash that already binds the state root); an **L1 reference** making L1-derived reads deterministic; and a **finality certificate** as a first-class, cryptographically verified object.

### 4.4 Flow: submit → sequence → apply → anchor

```
Client            Node A (member)        Node B (sequencer)         Node C (member)      Cardano L1
  │ POST /submit       │                        │                        │                  │
  │───────────────────►│ verify sig+membership  │                        │                  │
  │                    │ admit to AppMsgPool    │                        │                  │
  │                    │══ gossip (P100) ══════►│═══════════════════════►│                  │
  │                    │                        │ (its turn per schedule)│                  │
  │                    │                        │ drain pool → order     │                  │
  │                    │                        │ → validate each (SPI)  │                  │
  │                    │                        │ → build app-block      │                  │
  │                    │                        │ → apply → state-root   │                  │
  │                    │◄══ proposal (P100, ════│═══ system topic) ═════►│                  │
  │                    │ verify + apply locally │                        │ verify + apply   │
  │                    │══ co-sign vote ═══════►│◄══════════════════════ │                  │
  │                    │                        │ threshold met →        │                  │
  │                    │◄══ finality cert ══════│═══════════════════════►│                  │
  │                    │ commit block+MPF batch │ commit                 │ commit           │
  │                    │                        │ every N blocks/T mins: │                  │
  │                    │                        │ anchor tx (own mempool)│─────────────────►│
  │                    │◄─── all nodes observe anchor tx via their own L1 sync ────────────│
  │                    │ mark blocks L1-ANCHORED at depth d                                 │
```

Three finality levels, exposed in APIs and events:
1. **APP_FINAL** — finality certificate threshold met; block committed on all honest members.
2. **L1_ANCHORED** — an anchor tx containing this block's range is on L1.
3. **L1_FINAL** — that anchor tx is ≥ d blocks deep (configurable; default k for paranoid mode, lower for consortium mode).

---

## 5. Design Decisions and Options

### D1. Packaging: where does the app chain live?

| Option | Assessment |
|---|---|
| (a) Runtime plugin jar (`NodePlugin`) | ✗ Plugins can't open mini-protocols, can't register REST, loose `Object`-typed services. Wrong tool. |
| (b) **New Gradle modules + kernel `Subsystem`** | ✓ `app-chain-api` (SPI, events, config — depends on `core-api`) and `app-chain` (runtime — depends on `app-chain-api`, `p2p`, `runtime` slices). Registered in `NodeKernel` by `YanoAssembly` when configured. REST resources compiled into `app` module (established pattern: `tx-services`, `epoch-export`). |
| (c) Separate process (DMQ-node style sidecar) | ✗ Contradicts the core premise (same connection, same node, one process). Revisit only if blast-radius isolation ever outweighs integration benefits. |

**Decision: (b), amended during M1 implementation.** The `Subsystem` SPI (`init(SubsystemContext)/start/stop/health`) is purpose-built for this; `SubsystemContext` already carries the EventBus, schedulers, config map and service registry.

*M1 learning (2026-07-08):* `Subsystem` lives in `runtime.kernel`, so separate `app-chain` Gradle modules would need to depend on `runtime` while `runtime` registers the subsystem — a circular dependency. Implemented instead as packages, exactly like every other subsystem: SPI/config/events in **`core-api` (`api.appchain`)**, the subsystem in **`runtime` (`runtime.appchain`)**, REST in **`app` (`api.appchain`)**. Extracting dedicated modules remains an option once the API stabilizes and an external consumer needs the slice in isolation.

*Extension SPI note:* the `AppStateMachine` implementation itself **can** be loaded from a plugin jar (it's pure logic, no protocol/REST needs) — `ServiceLoader` discovery of `AppStateMachineProvider` gives no-recompile deployment of custom chains onto a stock yano-node distribution.

### D2. Sequencing: who orders the messages?

The app chain needs total order per chain. Options, from simplest to heaviest:

| Option | Trust/fault model | Liveness | Complexity | Verdict |
|---|---|---|---|---|
| **S1 Fixed sequencer** — one configured member proposes every block; others verify, apply, co-sign (cert still required so followers never trust silently) | Trusted operator; safety = sequencer honest for ordering, members detect equivocation via co-signing | Stops if sequencer down (cold standby = ops procedure) | Low | **M2 default** |
| **S2 Rotating sequencer, L1-clocked** — proposer for window w = `members[H(chainId ‖ epochNonce ‖ w) mod n]`; window = a range of L1 slots. Missed window → next window's proposer proposes (height unchanged); n-of-m finality cert required, members sign at most one block per height | Semi-trusted consortium; safety from threshold cert (< threshold colluding can't finalize two blocks at same height); liveness from rotation | Self-healing after ≤1 window on proposer failure | Medium | **M4 target** — this is the sweet spot; uses L1 as shared clock + randomness, which every member already has |
| **S3 Raft (CFT)** — embed Apache Ratis or minimal Raft-over-appmsg; log = message batches | Crash-fault only (non-Byzantine); fine for single-org multi-node | Excellent | Medium (library) but Ratis brings its own transport/port — breaks the single-connection story unless reimplemented over appmsg | Optional later module; not core |
| **S4 BFT (HotStuff/Tendermint-style, or BFT-SMaRt)** | Byzantine f < n/3 | Good | High | SPI-compatible; deferred until a concrete user needs it |
| **S5 Leaderless deterministic merge** — per-sender ordered streams merged by rule (e.g., all messages seen by L1-slot boundary, sorted by (sender, seq)) | No leader; weak/eventual finality | n/a | Low | Not general total order; useful for attestation-aggregation (Mithril-shaped) apps — keep as a possible alternative `AppSequencer` impl, not the default |

**Decision:** define an `AppSequencer` SPI; ship **S1** first, then **S2**. All consensus traffic (proposals, votes, certs) rides protocol 100 on **reserved system topics** (`~consensus/...` — a reserved prefix, replacing the PoC's ad-hoc `topic::proposal` string-splitting), so the single-connection property holds for consensus too.

Rules fixing the PoC's liveness and safety gaps:
- A member signs **at most one block per height** (persisted vote lock — equivocation-safe across restarts).
- A timed-out round does **not** orphan the height: the same or next proposer re-proposes height h (possibly with a different message set) until a cert forms.
- Proposer identity keys are **required config** for members (no auto-generated ephemeral keys); key = membership identity.
- `verifyProof`/cert verification does real signature verification against the membership registry, always, in every mode. Adversarial tests (forged cert, replayed cert, wrong-height vote, conflicting proposals) are part of the definition of done.

### D3. State commitment: MPF, JMT, or both?

| | Merkle Patricia Forestry (`MpfTrie`) | Jellyfish Merkle Tree |
|---|---|---|
| On-chain proof verification | ✓ Aiken-compatible; proofs exportable as `PlutusData`; tested validator exists in cardano-client-lib | ✗ (not without new on-chain code) |
| Versioned/historical state | ✗ single current root (per-block root recorded in app ledger) | ✓ native versions, historical proofs |
| Ecosystem alignment | ✓ the de-facto Cardano off-chain↔on-chain structure | Diem lineage |
| Backends | RocksDB / RDBMS | RocksDB / RDBMS |

**Decision:** a small `StateCommitment` SPI (`batchUpdate(Map<bytes,bytes>) → root`, `prove(key) → proof`, `verify(root, key, value, proof)`); **MPF is the default** because L1 anchoring + on-chain verifiability is the differentiating feature, JMT offered as an alternative for apps that need versioned historical queries and don't need on-chain proofs. Both come from cardano-client-lib `verified-structures` — add `merkle-patricia-forestry-rocksdb` (and optionally `jellyfish-merkle-rocksdb`) to the version catalog; no new implementation work.

What goes in the trie is the **app state machine's key→value output** (via the `AppStateWriter` it receives in `apply()`). The built-in default app writes `key = message-scoped or KV key`, `value = value/message hash`, so even blob-only apps get provable inclusion.

### D4. L1 anchoring

**What is anchored:** `[chain-id, from-height, to-height, block-hash(to-height)]` — the block hash already binds `state-root`, `messages-root`, and the whole history via `prev-hash`. Plus optional `state-root` in clear for indexer convenience.

| Mode | Mechanism | Cost | Verifiability | Use |
|---|---|---|---|---|
| **A1 Metadata anchor** (default) | Tx with metadata label (single registered-range label for the framework; `chain-id` inside the payload) sent from the operator's anchor wallet | ~0.2 ADA/anchor | Off-chain verification (auditor recomputes); L1 gives existence + timestamp + immutability | Audit trails, attestation, compliance |
| **A2 Script anchor** | UTxO at an anchor script address; inline datum = `[chain-id, height, block-hash, state-root]`; spend-to-update, validator enforces monotonic height and (optionally) an n-of-m member multisig on updates | ~0.5 ADA + min-UTxO | **On-chain**: other validators can reference the anchor UTxO and verify MPF membership proofs against `state-root` (Aiken MPF lib) | Bridges, on-chain consumers of app state |
| A3 On-demand single-entry anchor | App explicitly anchors one entry + proof | variable | High per-entry | Niche; SPI-level policy, not core |

**Decision:** `AnchorPolicy` SPI (trigger: every N blocks / every T minutes / on-demand; mode: A1/A2). Anchor txs are built with cardano-client-lib and submitted through **Yano's own TxGateway/mempool** — they diffuse to L1 via the node's existing upstream connections. Confirmation tracking uses the node's own chain follower: the subsystem watches `BlockAppliedEvent` for the anchor tx hash, handles **L1 rollback of an anchor tx** (`RollbackEvent` → back to pending → resubmit), and only marks `L1_ANCHORED`/`L1_FINAL` at the configured depth. One anchor wallet per chain, key via config/env; multi-member anchor rotation is an M4+ option (any member may anchor; on-chain monotonic-height check makes duplicates harmless, first-in wins).

### D5. L1-derived data: determinism and rollback safety

App chains that react to L1 (bridges, registries, L1-state oracles) need every member to compute the **same** answer from L1 — while L1 can roll back. Design:

- Every app block carries `l1-ref` = an L1 point chosen by the proposer that is **at least `l1StabilityDepth` blocks deep** at proposal time (configurable per chain; default 36 for consortium chains — a bridge would choose higher, up to k=2160).
- Followers **reject** a proposal whose `l1-ref` they haven't reached (wait/retry) or that violates the depth rule or moves backwards vs the previous block's `l1-ref`.
- The framework exposes an `L1View` to the state machine: reads (utxos at address, tx metadata by label, stake/gov snapshots — backed by Yano's chain/ledger state) are **evaluated as of `l1-ref`**, never as of the live tip.
- Because `l1-ref` is below the stability depth of every honest member, an L1 rollback deeper than `l1StabilityDepth` is a catastrophic-event runbook item (halt chain, operator intervention), not a code path — same posture as exchange deposit confirmations.
- Convenience layer: the framework can optionally **inject L1 observation messages** (system topic `~l1/...`, e.g., "tx T with label L appeared at slot S ≤ l1-ref") so app logic consumes L1 facts as ordinary sequenced messages. Injection is done by the proposer and **verified by followers against their own L1 state** — an observation any follower cannot confirm at `l1-ref` invalidates the proposal. This makes L1-derived data first-class, deterministic, and auditable in the app ledger itself.

### D6. Membership, identity, auth

- **Identity** = Ed25519 public key per member (static keys, explicit config; never auto-generated for members). Same key signs envelopes (when the member is a sender), votes, and certs. TLS-level identity is out of scope (transport is the existing yaci N2N channel).
- **MembershipRegistry SPI**, three implementations over time:
  1. **Static** (M2): keys + roles (member/observer) + threshold in config. Changes = config change + rolling restart.
  2. **Chain-governed** (M4): membership changes are themselves app messages on a system topic (`~governance/membership`), approved by the existing threshold, activated at a future height — the app chain governs itself, and history explains every membership era (needed to verify old certs during catch-up).
  3. **L1-registered** (later): membership anchored/registered on L1 (metadata or NFT-based registry, or SPO-KES for CIP-137-style trustless mode).
- **Authorization**: envelope admission requires `sender ∈ registry` (or a per-chain "open submission" flag where anyone may submit but only members sequence/finalize — useful for public-facing apps with permissioned operators).
- Every verification path (envelope sig, vote sig, cert threshold, membership epoch) is real crypto with negative-path tests — the two PoC vulnerabilities (§1.2 item 3) are regression-tested against the new code.

### D7. Chains, topics, and group identity

- **`chain-id` is the unit of consensus, ledger, state root, anchoring, and membership.** One node can host multiple chains (each its own subsystem instance, RocksDB directory, sequencer config).
- **Topics are sub-streams within a chain** (routing/filtering + which state-machine handler fires), inside the same global height sequence. This fixes the PoC's per-topic block sequences (which made cross-topic ordering undefined) while keeping multi-tenant routing. Reserved prefix `~` for system topics (`~consensus`, `~governance`, `~l1`).
- **Group identity — in-protocol, not in the handshake** *(decided during M1, resolving Open Question 2)*: chain-scoping is negotiated inside protocol 100 itself — `MsgInit` carries the initiator's chain-ids and a new `MsgInitAck` (tag 6, state `StInitAck`) carries the responder's; both sides restrict the session to the intersection (client must not offer, server rejects). This avoids touching the handshake `VersionData` serializers (shared with real Cardano versions), works per-connection, and allows chain lists to change without re-handshake. V100 remains a pure capability flag.
- **Subscription filtering**: the gossip agent offers a peer only messages for chains/topics that peer declared. (CIP-137 solves this with one network per protocol; we solve it in-band since sharing the L1 connection is the point.)
- Which peers? App-chain peers are declared in config (host/port or reuse of an upstream peer entry tagged with chain membership) and are typically a **subset** of L1 peers. The peer governor treats them as pinned/local-root class — never churned out by L1 peer scoring.

  *M1 learning (2026-07-08):* while `feat/upstream_selection` is in flight, the app-chain subsystem owns **dedicated outbound connections** to its app peers (`AppPeerClient`: standard N2N handshake + keep-alive + protocol 100 on one socket; reconnect + replay queue). The mux/handshake already support carrying L1 protocols on the same socket, so unifying app diffusion with the L1 sync session (single connection per peer pair) is a planned M4/M5 refinement once upstream selection stabilizes — not a protocol change.

### D8. Catch-up and state sync (new — the PoC had none)

Protocol 100 is a diffusion channel for *recent* messages; it cannot serve history. New **protocol 103 — AppChainSync**, a deliberately boring BlockFetch analog:

```
Client: MsgRequestRange(chain-id, from-height, to-height)
Server: MsgStartBatch → MsgBlock(app-block)* → MsgBatchDone   |   MsgNoBlocks
Client: MsgRequestTip(chain-id) → Server: MsgTip(height, block-hash, cert)
```

A joining/lagging node: learns the tip via 103, fetches block ranges, **verifies each block** (prev-hash chain, cert against the membership registry as of that height, recomputed state root after local re-apply), and switches to live mode (100) when caught up. Because every block binds its post-state root, fast-sync variants (fetch state snapshot at height h + verify against `state-root`, then replay from h) are possible later without protocol changes. REST-based catch-up is explicitly rejected as the primary path (wrong trust model, doesn't reuse the connection) but remains available for ops/debugging.

### D9. Serialization

**CBOR everywhere** — wire envelopes, consensus DTOs, app blocks, and the persisted ledger rows are the *same* CBOR bytes (store what you gossip, hash what you store). One codec per type in `app-chain-api`, golden-vector tests, no `DataOutputStream` anywhere. `message-id`/`block-hash` = Blake2b-256 (aligning with Cardano and CIP-137, replacing the PoC's SHA-256).

### D10. Developer SPI and the out-of-box default

```java
public interface AppStateMachine {
    /** Called once; gives read access to committed app state and the deterministic L1 view. */
    void init(AppStateReader state, L1View l1View, AppChainInfo info);

    /** Mempool admission — stateless/fast; runs on every node. Blob is opaque unless the app decodes it. */
    AdmissionResult validate(AppMessage message);

    /** Deterministic transition. Called exactly once per finalized block, in height order, on every node.
     *  All writes go through the writer and commit atomically with the block and the MPF root. */
    void apply(AppBlock block, AppStateWriter writer);

    /** Optional read path exposed via REST /query and Java API. */
    default byte[] query(String path, byte[] params) { throw new UnsupportedOperationException(); }
}
```

Framework guarantees: `apply` is single-threaded per chain, replayed idempotently after crash from the last committed block (the block+state+MPF commit is one atomic RocksDB batch — the delta/rollback discipline from L1 state stores applies, though the app chain never rolls back after APP_FINAL, which keeps this dramatically simpler than L1 ledger state); `validate` may be called concurrently and must be side-effect free.

Optional typed layer: `MessageCodec<T>` (`decode(byte[]) → T`, `encode(T) → byte[]`) so applications register `TypedAppStateMachine<T>` and never touch raw bytes — the framework still only ever sees blobs (§1.4).

**Built-in default (`ordered-log` state machine):** append-only sequenced log of blobs; optional KV mode where the blob is interpreted (by the *default app*, not the framework) as a CBOR `[op, key, value]`; MPF over `key → blake2b(value)` (KV mode) or `height‖index → message-id` (log mode). REST: submit, get by height/range, tip, state root, **inclusion proof** (wire + PlutusData formats). This is the config-only product experience.

---

## 6. Out-of-Box Experience

### Config-only (two nodes, default app)

```yaml
# node A (sequencer)  — node B differs only in signing key and sequencer flag
yano:
  app-chain:
    enabled: true
    chains:
      - id: "acme-audit-log"
        state-machine: "ordered-log"           # built-in default
        signing-key: ${APP_CHAIN_SIGNING_KEY}  # this member's Ed25519 key (required)
        membership:
          mode: static
          members:                              # pubkey hex
            - "aa11...":  { role: member }
            - "bb22...":  { role: member }
          threshold: 2                          # finality cert = 2-of-2
        sequencer:
          mode: fixed                           # fixed | rotating (M4)
          proposer: "aa11..."
        peers:                                  # app-group peers (also serve/consume L1)
          - host: nodeB.acme.internal
            port: 13337
        block:
          max-messages: 500
          max-block-bytes: 1048576
          interval-ms: 2000                     # propose when messages pending, at most this often
        message:
          max-bytes: 65536
          max-ttl-seconds: 3600
        l1:
          stability-depth: 36
        anchor:
          enabled: true
          mode: metadata                        # metadata | script
          every-blocks: 100
          max-interval-minutes: 60
          wallet-mnemonic-env: ANCHOR_WALLET_MNEMONIC
```

Start both nodes → they handshake V101 with the shared chain-id, gossip messages, node A sequences, both commit identical ledgers and MPF roots, anchors land on L1 hourly. `POST /api/v1/app-chain/acme-audit-log/messages` to write; proofs at `/proof`.

### Library mode (custom node)

```java
Yano yano = YanoAssembly.fromConfig(yanoConfig)
    .appChain(AppChainSpec.builder("settlement-chain")
        .stateMachine(new SettlementStateMachine())        // implements AppStateMachine
        .membership(StaticMembership.threshold(2, keyA, keyB, keyC))
        .sequencer(SequencerSpec.fixed(keyA))
        .signingKey(myKey)
        .commitment(CommitmentKind.MPF)
        .anchor(AnchorPolicy.everyBlocks(100).orMinutes(30),
                AnchorMode.SCRIPT, anchorWallet)
        .build())
    .build();
yano.lifecycle().start();

AppChainHandle chain = yano.appChain("settlement-chain");  // new role interface
chain.submit(myCodec.encode(new SettlementInstruction(...)));
chain.subscribe(FinalityLevel.APP_FINAL, block -> ...);
InclusionProof p = chain.prove(key);                        // verifiable vs anchored root
```

### Events (published on the existing EventBus; consumable by plugins)

| Event | Type | When |
|---|---|---|
| `AppMessageReceivedEvent` | regular | envelope verified + admitted to pool |
| `AppMessageAdmissionEvent` | vetoable | before admission (policy plugins can reject) |
| `AppBlockProposalEvent` | vetoable | follower-side, before voting on a proposal |
| `AppBlockFinalizedEvent` | regular | finality cert formed, block committed (APP_FINAL) |
| `AppChainAnchoredEvent` | regular | anchor tx observed on L1 / reached L1_FINAL depth |
| `AppChainStalledEvent` | regular | no progress within liveness window (ops alerting) |

---

## 7. Use Cases

1. **Consortium audit / trusted message queue** (default app, zero code): multi-party tamper-evident log; neither party controls the broker; L1 anchor = regulator-grade timestamping. The Kafka-with-neutrality pitch from ADR-001, now with real proofs.
2. **Bridge / mirrored assets** (flagship for A2 script anchoring): members watch L1 lock transactions at a script address via `L1View`/`~l1` observations (deterministic at `stability-depth`); app chain mints/records claims; withdrawals back to L1 spend the anchor-verified state — an Aiken validator checks the MPF inclusion proof of the claim against the anchored `state-root`. All primitives exist: MPF PlutusData proofs + tested validator (cardano-client-lib), deterministic L1 reads (D5), script anchor (D4).
3. **x402 settlement chain** (ties into `adr/x402/001`): high-frequency micropayment receipts sequenced off-chain on an app chain between facilitator operators; periodic net-settlement anchored/settled on L1; receipts individually provable.
4. **Oracle / attestation network**: members validate submitted facts (pluggable `validate()` calls external sources); sequenced attestations with n-of-m certs; consumers verify against anchors. S5 (leaderless merge) is a natural fit here later.
5. **L1-derived registry chains**: e.g., a token/metadata registry or DID index derived deterministically from L1 events, maintained as an app chain with provable state — consumers get MPF proofs instead of trusting an indexer API.
6. **Enterprise workflow / DPP**: supply-chain document flows where each step is an app message validated by the counterparty's node; per-document inclusion proofs against public anchors.

---

## 8. Feasibility

### What exists vs what must be built

| Component | Status | Where | Effort |
|---|---|---|---|
| P100 gossip agents, P101/102 client agents, V100 handshake | ✅ Built | yaci `next` | — |
| `AgentFactory` server extension, shared-connection mux | ✅ Built & proven | yaci | — |
| Transport hardening: envelope v2, auth/TTL/size enforcement, bounded dedup, chain-scoped subscription (V101) | ❌ | **yaci** (coordinated change) | M |
| Yano wiring: `enableAppMsg` on app peers, server agent factory, peer-class pinning | ❌ | yano `p2p`/`runtime` | S |
| `AppChainSubsystem` skeleton, config (`YanoPropertyKeys.AppChain`), assembly + role handle | ❌ | yano `app-chain` | M |
| AppMsgPool (admission, verify, dedup, TTL) | ❌ (PoC reference exists) | `app-chain` | S–M |
| `AppSequencer` SPI + S1 fixed sequencer + finality certs (real verification, vote locks, re-propose) | ❌ | `app-chain` | M |
| S2 rotating sequencer (L1-clocked windows) | ❌ | `app-chain` | M–L |
| AppLedger (RocksDB, CBOR rows, atomic block+state+root commit, crash replay) | ❌ (PoC reference exists) | `app-chain` | M |
| `StateCommitment` SPI over MPF/JMT | ✅ libraries exist; SPI + integration | ccl `verified-structures` + `app-chain` | S |
| AnchorService (build/submit via own TxGateway, observe/rollback-track via own sync) | ❌; all primitives exist | `app-chain` | M |
| `L1View` (stable-depth reads over chain/ledger state) + `~l1` observation injection | ❌ | `app-chain` | M |
| Protocol 103 AppChainSync (catch-up) | ❌ | yaci (protocol) + `app-chain` (logic) | M |
| MembershipRegistry: static → chain-governed | ❌ | `app-chain` | S then M |
| REST resources + default `ordered-log` app | ❌ | `app` + `app-chain` | S–M |
| Multi-node integration tests incl. **3-node non-mesh relay** (A–B–C, A↮C) and adversarial suites | ❌ (PoC never tested multi-hop) | `app-chain` | M |

(S ≈ days, M ≈ 1–3 weeks, L ≈ 1 month+, single engineer familiar with the codebase.)

### Why this is credible now
- The riskiest layer (a multiplexed extra mini-protocol coexisting with L1 sync on one connection) is **already built and integration-tested** in yaci.
- The hardest new algorithms (authenticated state structure + on-chain proofs) are **already shipped libraries** with an existing Aiken validator.
- Anchoring needs no external infrastructure — the node **is** its own L1 gateway.
- The kernel/subsystem, event, and config machinery to host all of this exists and has established patterns to copy (tx-services, epoch-export).
- The genuinely novel engineering is the **sequencer correctness** (bounded scope: S1 is near-trivial; S2 is a well-understood rotating-leader + threshold-cert design) and **operational hardening** — which is exactly where the PoC's lessons (§1.2) give us a concrete checklist.

### Cross-repo coordination
Transport changes (envelope v2, V101 chain-scoped negotiation, enforcement, protocol 103) land in **yaci** behind the experimental `next` line; Yano pins the version. Everything else is Yano-only. Note yaci's appmsg is on `next`, not `main` — shipping the app chain publicly requires promoting those yaci changes to a release.

---

## 9. Roadmap

**M1 — Transport hardening + Yano wiring (foundation)**
Envelope v2 + enforcement + bounded dedup + V101 chain-scoped negotiation (yaci); Yano calls `enableAppMsg` for configured app peers, registers the server agent factory; `AppChainSubsystem` skeleton with config + events; 2-node gossip demo behind `yano.app-chain.enabled`.
*Exit: two Yano nodes exchange authenticated, TTL-enforced app messages for a shared chain-id on their existing connection, L1 sync unaffected (soak test).*

**M2 — Sequenced durable ledger (the "app chain" moment)**
AppMsgPool; S1 fixed sequencer with real finality certs; AppLedger with atomic commit + crash replay; MPF commitment; `AppStateMachine` SPI + built-in `ordered-log`; REST (submit/read/tip/root/proof); 2–3-node integration tests incl. restart/replay.
*Exit: config-only ordered log replicated across 3 nodes with identical roots; kill-and-restart converges; forged-cert tests fail closed.*

**M3 — L1 anchoring + proofs + L1View**
AnchorService (A1 metadata first, then A2 script reusing the existing Aiken MPF validator); anchor observation + rollback tracking + finality levels; `L1View` stable-depth reads; proof endpoints (wire + PlutusData); devnet e2e: app chain → anchor → on-chain proof verification.
*Exit: an auditor script verifies any log entry against an on-chain anchor with no access to the nodes.*

**M4 — Liveness, membership, catch-up (production shape)**
S2 rotating sequencer; chain-governed membership with epochs; protocol 103 catch-up with cert/root verification; `AppChainStalledEvent` + metrics/status-dashboard integration; adversarial + 3-node non-mesh relay test suites; ops runbook.
*Exit: sequencer kill → chain resumes within one window; a brand-new node joins, verifies history, participates.*

**M5 — Reference applications + ecosystem (exploratory)**
Bridge reference implementation (lock/mint/withdraw with on-chain proof); x402 settlement-chain prototype; `AppStateMachineProvider` plugin loading; evaluate BFT (S4) and CIP-137/DMQ wire-interop mode against real demand; yaci `next` → release promotion.

---

## 10. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| **Sequencer/consensus bugs** (the PoC shipped two real crypto-verification holes) | Small, boring designs (S1/S2, no view change); mandatory adversarial test suite per release gate; vote locks persisted; every proof verified everywhere, no trust-by-mode shortcuts |
| App layer destabilizes L1 sync on shared connections | Protocol isolation already proven by mux design; per-chain message/block size caps; app agents get bounded queues + backpressure; soak test in M1 exit criteria |
| **L1 rollback interplay** (anchor txs, L1-derived reads) | Anchors tracked to depth with resubmission; `l1-ref` stability-depth rule makes app-visible L1 data effectively immutable; deeper-than-depth reorg = documented halt runbook |
| RocksDB rollback/counter corruption class of bugs (project history) | App ledger is append-only after APP_FINAL — no rollback path by construction; single atomic batch per block (block + state + MPF root + tip); replay idempotence tested (kill −9 loops) |
| Scope creep toward a general blockchain SDK | Trust spectrum capped at semi-trusted for shipping milestones; BFT/trustless behind SPIs, not built; one built-in app only |
| yaci `next` instability / cross-repo drift | Version-pinned; transport changes batched into M1; promotion to yaci release is an explicit M5 item |
| MPF write amplification per block | Batched trie updates per block (one root computation); JMT alternative for write-heavy apps; perf test with realistic KV cardinality in M2 |
| Key management burden on operators | Explicit required keys with startup validation (fail fast on mismatch vs membership); documented rotation via chain-governed membership (M4) |
| Anchoring cost | Policy-driven batching (N blocks / T minutes); metadata mode default; cost table in docs |

---

## 11. Relationship to Prior ADRs

- **app-layer/001**: vision retained (trust spectrum, same-connection multiplexing, pluggable everything); superseded in specifics — global-height chains instead of per-topic sequences, MPF instead of ad-hoc hashing, anchoring designed-in, catch-up protocol added, security model made real.
- **app-layer/002**: superseded by Yano's `p2p`/`consensus` modules and `adr/017`; the app chain consumes that foundation (peer classes, governor) rather than re-building it.
- **app-layer/003**: implemented (yaci); this ADR specifies its v2 evolution (envelope, V101, enforcement, protocol 103).
- **app-layer/004**: replaced. Kept: VetoableEvent integration, separate RocksDB store, validation-before-consensus. Dropped: per-topic block sequences, consensus-as-event-listener as the primary mechanism (sequencing is now an explicit subsystem; events remain the *extension* surface).
- **027 (vision)**: this is the R5 concrete design; the `StateCommitment`/`AppSequencer`/`MembershipRegistry` SPIs are the "state-commitment SPIs" it deferred; R6 (full sidechain) remains out of scope but inherits these SPIs.
- **028 (decomposition)**: the app chain is a textbook new `Subsystem`; no changes to the kernel contract are expected beyond registering it in `YanoAssembly`.

---

## 12. Open Questions

1. **Metadata label**: register a CIP-10 label for framework anchors, or ship with a high-range unregistered label and register later?
2. ~~**V101 handshake payload**~~ **Resolved in M1**: chain-scoping negotiated in-protocol via `MsgInit`/`MsgInitAck` (see D7); the handshake carries only the V100 capability flag.
3. **Snapshot/fast state sync** (fetch MPF at height h instead of full replay): defer until a chain is big enough to hurt; block format already supports it.
4. **Multiple sequencer chains per node — resource isolation**: shared scheduler pools vs per-chain executors; decide with M2 benchmarks.
5. **DMQ interop mode**: is there real demand for a Yano node speaking wire-conformant CIP-137 to `dmq-node` (e.g., consuming Mithril signatures as an app chain input)? Revisit at M5.
6. **App-chain pruning/archival**: full history retained initially; anchored-range pruning (drop message bodies below last L1_FINAL anchor, keep headers+roots) is a natural later feature — needs a decision on proof availability guarantees.
7. **Observer role**: read-only members (verify + serve, never vote) — trivially supported by membership roles; confirm API surface in M2.
