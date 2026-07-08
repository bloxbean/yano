# Yano App Chain — Developer & User Guide

Yano can run an **app chain** next to Cardano L1: a sequenced, replicated,
application-specific ledger maintained by a trusted or semi-trusted group of
Yano nodes. The same node keeps syncing/serving Cardano L1; the app chain runs
in parallel over the same node-to-node protocol stack (a CIP-137-derived
"appmsg" mini-protocol family), commits its state into a Merkle Patricia
Forestry (MPF) trie, and periodically **anchors the state root to Cardano L1**.

```
        One Yano node, two ledgers
┌──────────────────────────────────────────────┐
│ Cardano L1                 App chain         │
│  chain-sync / block-fetch   protocol 100     │  ← same TCP connection family,
│  tx-submission              protocol 103     │    same handshake
│  RocksDB chain state        RocksDB + MPF    │
│           └────── anchor tx ──────┘          │
│        (state root → L1 metadata)            │
└──────────────────────────────────────────────┘
```

Design/status reference: `adr/app-layer/005-yano-app-chain-framework.md`.
Wire format specs (for building compatible implementations in other languages):
yaci `core/src/main/cddl/appmsg/` and yano `core-api/src/main/cddl/appchain/`.

---

## 1. What you get out of the box

**Yes — the default Yano distribution (`yano.jar`) is all you need.** Enable
the app chain with configuration flags only; no code required:

- **Built-in state machine: `ordered-log`** (the default). Every submitted
  message is an opaque blob; the chain gives you a tamper-evident, totally
  ordered, replicated log of those blobs. For each finalized message it writes
  `message-id → (height, index, topic, sender)` into the MPF trie, so anyone
  can obtain an **inclusion proof** that a message was finalized at a given
  position — verifiable against the (anchorable) state root without trusting
  any node.
- **REST endpoints** to submit and read messages, browse blocks, and fetch
  proofs (section 4).
- **Sequencing with real finality**: one configured node (the *sequencer*)
  batches messages into app blocks; every member re-executes the block,
  verifies the state root byte-for-byte and co-signs. A block is final only
  with a threshold **finality certificate** (n-of-members Ed25519 signatures,
  all verified).
- **L1 anchoring** (optional): the node itself builds, signs and submits a
  Cardano transaction embedding the app-chain state root as metadata, and
  confirms it through its own L1 sync. No external API/provider involved.
- **Catch-up**: a member that joins late or restarts behind fetches finalized
  blocks from peers (protocol 103) and verifies everything (hash chain,
  certificates, re-executed state roots) before committing.

Typical uses with zero code: multi-party audit/compliance logs, consortium
message queues with neutral custody, attestation feeds, document/DPP trails —
anything that needs "we all agree on this exact sequence of records, and we
can prove any record against a public Cardano anchor."

---

## 2. Concepts in two minutes

| Term | Meaning |
|---|---|
| **Chain id** | Name of your app chain (`yano.app-chain.chain-id`). One group = one chain id. |
| **Member** | A participant identified by an Ed25519 public key. Only members' messages are accepted; members co-sign blocks. |
| **Sequencer / proposer** | The one member (by public key) that orders messages into blocks. Fixed, configured. |
| **Threshold** | How many member signatures a finality certificate needs (e.g. 2 of 2). |
| **App message** | Envelope with an **opaque body** (your bytes — CBOR/JSON/protobuf/anything), signed by the sender. The framework never parses the body. |
| **Topic** | Optional sub-stream label inside a chain (routing/filtering). Names starting with `~` are reserved. |
| **App block** | Ordered batch of messages + post-state MPF root + finality certificate, hash-linked to the previous block. |
| **State root** | MPF (Aiken-compatible Merkle Patricia Forestry) root after applying a block. Identical on every member, anchorable to L1, provable. |
| **State machine** | The only component that interprets message bodies. Built-in: `ordered-log`. Custom ones plug in (section 6). |

Trust model: **fail closed**. Envelope signatures, membership, vote
signatures, and certificate thresholds are cryptographically verified on every
node, always. A non-member's messages are dropped; a non-sequencer's blocks
are never finalized; a tampered block fails the state-root re-execution check.

---

## 3. Quick start: two-node cluster with the default distribution

### 3.1 Generate member keys

Each member needs an Ed25519 keypair (32-byte seed). Using the built jar:

```bash
jshell --class-path app/build/yano.jar - <<'EOF'
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
byte[] seed = new byte[32]; new java.security.SecureRandom().nextBytes(seed);
System.out.println("private (seed): " + HexUtil.encodeHexString(seed));
System.out.println("public        : " + HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed)));
EOF
```

Run once per member (and once more for the anchor wallet if you enable
anchoring). Keep private seeds secret; collect all **public** keys into the
member list. **Keys are required, explicit configuration** — the node fails
fast at startup if its own public key is not in the member list.

### 3.2 Configure node A (the sequencer)

All settings live under `yano.app-chain.*` (application.yml or `-D` flags):

```yaml
yano:
  app-chain:
    enabled: true
    chain-id: "acme-audit-log"
    signing-key: "<node A private seed hex>"
    members: "<pubA>,<pubB>"
    peers: "nodeB.example.com:13337"        # other members' N2N server ports
    sequencer:
      proposer: "<pubA>"                    # node A sequences
    threshold: 2                            # 2-of-2 finality certs
    block:
      interval-ms: 2000
      max-messages: 500
```

### 3.3 Configure node B (a member)

Identical, except `signing-key` is B's seed and `peers` points at node A.
`sequencer.proposer` stays `<pubA>` on **every** node — it names who may
sequence, not who this node is.

### 3.4 Start and verify

Start both nodes as usual (any network role — devnet producer, relay,
preprod follower; the app chain is independent of the L1 role). Then:

```bash
curl -s http://nodeA:8080/api/v1/app-chain/status
# expect: "running": true, "sequencing": true, "role": "proposer",
#         "peers": {"nodeB...:13337": true}
```

Submit a message on either node and read it (finalized) from the other:

```bash
curl -s -X POST http://nodeA:8080/api/v1/app-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"order #1 created"}'
# → {"messageId":"6d1be691...","chainId":"acme-audit-log","topic":"orders"}

curl -s http://nodeB:8080/api/v1/app-chain/tip
# → {"chainId":"acme-audit-log","height":1,"stateRoot":"95edf7..."}  (same on A!)

curl -s http://nodeB:8080/api/v1/app-chain/proof/6d1be691...
# → MPF inclusion proof for the message against the shared state root
```

A ready-made end-to-end regression of exactly this flow (on devnet, with
anchoring) is the `test-app-chain-cluster` skill under `.claude/skills/`.

> **Devnet tip:** when node B follows a devnet producer for L1, start B with
> `-Dyano.dev-mode=false -Dyano.block-producer.enabled=false` and give it a
> copy of A's shelley genesis taken **after** A started (A rewrites
> `systemStart` into the file it loads).

---

## 4. REST API

Base path: `${yano.api-prefix}/app-chain` (default `/api/v1/app-chain`).

| Method & path | Purpose |
|---|---|
| `POST /messages` | Submit a message. Body: `{"topic": "...", "body": "<text>"}` or `{"topic": "...", "bodyHex": "<hex bytes>"}`. Returns `202` with the content-derived `messageId`. |
| `GET /messages?limit=100&topic=...` | Recently accepted messages (local + peer), with sender, sequence, body hex, source. |
| `GET /status` | Chain status: role, tip height, state root, pool size, peer connectivity, counters, anchor status. |
| `GET /tip` | `{chainId, height, stateRoot}` of the last finalized block. |
| `GET /blocks/{height}` | Finalized block: hashes, roots, proposer, cert signature count, full message list. |
| `GET /proof/{keyHex}` | MPF inclusion proof (wire format) for a state key against the committed root. For `ordered-log` the key **is** the message id; the response includes the value and `finalizedAtHeight`. |

Submission notes:
- The node signs your submission with **its own** member key (the REST caller
  is trusted local input — same model as a wallet talking to its own node).
- The body is opaque: `body` (UTF-8 text) or `bodyHex` (arbitrary bytes).
- Limits: `max-message-bytes` (default 64 KB), TTL `default-ttl-seconds`
  (default 600) — an unfinalized message expires out of the pool after that.
- Topics starting with `~` are reserved (consensus/system traffic).

---

## 5. L1 anchoring

Anchoring commits `[chain-id, from-height, to-height, block-hash, state-root]`
as transaction metadata (label `7014` by default) to Cardano — through the
node's own mempool and tx diffusion, confirmed by the node's own L1 sync.

```yaml
yano:
  app-chain:
    anchor:
      enabled: true
      signing-key: "<anchor wallet Ed25519 seed hex>"   # separate from member keys
      every-blocks: 10                                  # anchor cadence
      max-interval-minutes: 60
      metadata-label: 7014
    l1:
      stability-depth: 36        # L1 depth of the l1-ref carried in app blocks
```

Operational notes:
- The anchor wallet is an **enterprise address derived from the configured
  seed** — the node logs it at startup (`App-chain anchor wallet address: addr...`).
  **Fund it** (a few ADA goes a long way: one small tx per anchor). On devnet:
  `POST /api/v1/devnet/fund {"address": "...", "ada": 100}`.
- Only the node that should pay for anchors needs `anchor.enabled` (typically
  the sequencer). Anchor progress appears in `/status` under `anchor`
  (`lastAnchoredHeight`, `anchoredCount`, `lastAnchorTx`, pending state).
- Unconfirmed anchors are resubmitted; an L1 rollback that undoes an anchor
  puts it back in pending automatically.
- **Auditing**: anyone can fetch the anchor tx from Cardano, read the
  `state_root` from its metadata, and verify any record with the MPF proof
  from `GET /proof/{key}` — no trust in the nodes required. (The proof wire
  format is Aiken-MPF compatible, so on-chain verification in a validator is
  also possible.)

---

## 6. Custom app chains (your own state machine)

The framework never interprets message bodies — a **state machine** does.
Implement `com.bloxbean.cardano.yano.api.appchain.AppStateMachine`:

```java
public class OrderBookStateMachine implements AppStateMachine {
    @Override
    public String id() { return "order-book"; }

    @Override
    public AdmissionResult validate(AppMessage message) {
        // fast, side-effect-free mempool admission (body is your format)
        return AdmissionResult.accept();
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        // deterministic! called once per finalized block, in order, on every node.
        // everything written here is committed atomically with the block and
        // becomes part of the MPF state root.
        for (AppMessage m : block.messages()) {
            MyOrder order = decode(m.getBody());          // your codec
            writer.put(order.idBytes(), order.toBytes());
        }
    }
}
```

Rules: `apply` must be **deterministic** (no wall clock, randomness, or
external I/O) — every member re-executes it and the resulting state root must
match the proposer's byte-for-byte, or the block is rejected.

### 6.1 Deploy as a plugin jar on the default distribution (no rebuild)

1. Implement `AppStateMachineProvider`:
   ```java
   public class OrderBookProvider implements AppStateMachineProvider {
       public String id() { return "order-book"; }
       public AppStateMachine create() { return new OrderBookStateMachine(); }
   }
   ```
2. Add the ServiceLoader manifest to your jar:
   `META-INF/services/com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider`
   containing the provider class name.
3. Drop the jar into the node's plugins directory (`yaci.plugins.directory`,
   default `plugins/`), and select it:
   ```yaml
   yano:
     app-chain:
       state-machine: "order-book"
   ```
   Unknown ids fail fast at startup with the list of available machines.
   Compile against the `yano-core-api` artifact (`AppStateMachine`,
   `AppMessage`, `AppStateWriter` live there / in `yaci-core`).

### 6.2 Embed programmatically (library mode)

For fully custom nodes, `AppChainSubsystem` (module `runtime`,
`com.bloxbean.cardano.yano.runtime.appchain`) is a public kernel `Subsystem`
whose constructor accepts your `AppStateMachine` instance directly. A
first-class `YanoAssembly.appChain(spec)` builder is planned; until then the
plugin-jar route is the recommended packaging for custom chains.

---

## 7. Configuration reference

| Property (`yano.app-chain.`) | Default | Description |
|---|---|---|
| `enabled` | `false` | Master switch |
| `chain-id` | — | App-chain identity (required) |
| `signing-key` | — | This member's Ed25519 private seed, hex (required) |
| `members` | — | Comma-separated member public keys, hex (required; must include own key) |
| `peers` | — | Comma-separated app-group peers `host:port` (their N2N server ports) |
| `sequencer.proposer` | empty | Sequencer's public key. **Empty = diffusion-only mode** (messages replicate, but no blocks/ledger) |
| `threshold` | `1` | Finality certificate signatures required |
| `block.interval-ms` | `2000` | Proposer tick (blocks are only made when messages are pending) |
| `block.max-messages` | `500` | Max messages per block |
| `state-machine` | `ordered-log` | Built-in id or a plugin provider id |
| `max-message-bytes` | `65536` | Max opaque body size |
| `max-ttl-seconds` | `3600` | Max accepted message TTL |
| `default-ttl-seconds` | `600` | TTL applied to REST submissions |
| `anchor.enabled` | `false` | L1 anchoring on this node |
| `anchor.signing-key` | — | Anchor wallet seed, hex (required if anchoring) |
| `anchor.every-blocks` | `10` | Anchor after this many new app blocks |
| `anchor.max-interval-minutes` | `60` | Anchor at least this often while blocks pend |
| `anchor.metadata-label` | `7014` | Metadata label of anchor txs |
| `l1.stability-depth` | `0` | Depth of the stable L1 reference in app blocks (0 = off) |

Storage: the app ledger is a separate RocksDB at
`<yano.storage.path>/app-chain/<chain-id>/` — blocks, state trie and anchor
markers commit atomically and are independent of the L1 chain state. Back it
up like any RocksDB directory (node stopped). The app chain is append-only
after finality; there is no rollback path.

Events (for `@DomainEventListener` plugins): `AppMessageReceivedEvent`,
`AppBlockFinalizedEvent`, `AppChainAnchoredEvent`, `AppChainStalledEvent`.

---

## 7b. Enterprise extensions (ADR 006, Wave 1)

All opt-in; a node with none of these configured behaves exactly like v1.

**Multiple chains per node** — indexed config, one ledger/sequencer per chain:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: "orders-chain"
      signing-key: "..."
      members: "..."
      # ... any per-chain setting (same suffixes as the flat keys)
    chains[1]:
      chain-id: "audit-chain"
      state-machine: kv-registry
```

Chain-scoped REST: `/app-chain/chains` (list) and
`/app-chain/chains/{chainId}/...`; the chain-less paths keep working when
exactly one chain is configured.

**Standard-library state machines** (`yano.app-chain.state-machine`):
`ordered-log` (default), `kv-registry` (per-key ownership registry, provable
`[owner, value]` entries), `approvals` (k-of-n workflows with deterministic
deadlines). Command formats are documented on the classes in
`appchain-stdlib`; each also ships client-side command encoders.

**Push consumption**:
- SSE: `GET /app-chain/stream?fromHeight=&topic=` — replays finalized
  messages from a height (default: live-only), then follows; events named
  `app-message` with id `height:index`, `heartbeat` keepalives.
- Webhooks: `yano.app-chain.webhooks=https://...` (comma-separated; also per
  chain). Finalized blocks POSTed as JSON in height order, at-least-once,
  with a persisted per-sink cursor (`X-App-Chain-Id`/`-Height` headers);
  progress in `/status` under `webhooks`.

**API-key auth** (off by default):

```yaml
yano:
  app-chain:
    api:
      auth:
        enabled: true
    # full-access key + a key limited to submitting on two topics:
    # yano.app-chain.api.keys: "opsKey123,partnerKey456=orders|invoices"
```

Requests to `/app-chain/*` then require `X-API-Key`; reads stay unrestricted
per key, submissions honor the topic list.

**Metrics** — standard Quarkus Prometheus endpoint `/q/metrics`:
`yano_appchain_tip_height`, `yano_appchain_pool_size`,
`yano_appchain_peers_connected` (gauges, per chain),
`yano_appchain_blocks_finalized_total`,
`yano_appchain_messages_finalized_total`, `yano_appchain_block_interval`.

**Client SDK** (`com.bloxbean.cardano:yano-appchain-client`) — typed Java
access with client-side proof verification:

```java
AppChainClient client = AppChainClient.builder("http://node:8080/api/v1")
        .chainId("orders-chain").apiKey("...").build();
var result = client.submitText("orders", "order #1");
client.subscribe(-1, "orders", msg -> handle(msg));      // SSE, auto-reconnect
var proof = client.proof(Hex.decode(result.messageId())).orElseThrow();
boolean ok = ProofVerifier.verify(proof, anchoredRootHex); // don't trust, verify
```

**Testkit** (`com.bloxbean.cardano:yano-appchain-testkit`, test scope):

```java
@AppChainCluster(nodes = 3)
class OrderFlowTest {
    @Test
    void replicates(AppChainClusterHandle cluster) throws Exception {
        String id = cluster.node(1).submit("orders", body);
        cluster.awaitFinalized(id);
    }
}
```

## 7c. Enterprise extensions (ADR 006, Wave 2)

**More standard-library machines** (`yano.app-chain.state-machine`):
`balances` (mint/transfer, per-account authorization, non-negativity, provable
balances) and `doc-trail` (append-only per-entity trails with a provable chained
head — DPP, supply chain, case management).

**Typed messages** (`appchain-client` + core-api) — work with objects, not
bytes; the framework stays blob-first:

```java
record Order(String id, long qty) {}
CborCodec<Order> codec = CborCodec.of(Order.class);
client.submitTyped("orders", new Order("o-1", 5), codec::encode);
client.subscribeTyped(-1, "orders", codec::decode, (order, msg) -> handle(order));
// server side: extend TypedAppStateMachine<Order> with JacksonCborCodec
```

**Signed audit export** — a portable, offline-verifiable evidence bundle for any
finalized message (block(s) + members + L1 anchor reference):

```bash
curl localhost:8080/api/v1/app-chain/evidence/<messageIdHex> > evidence.json
# verify offline with core-api's EvidenceVerifier (no node access)
```

**Encrypted bodies** — client-side envelope encryption with a group key; the
chain carries ciphertext (ordering/proofs/anchors all work over it):

```java
byte[] key = Hex.decode(groupKeyHex);
client.submit("settlement", GroupCipher.encrypt(key, "settlement", plaintext));
byte[] plain = GroupCipher.decrypt(key, "settlement", message.body());
```

**External signer** (`yano.app-chain.signing-key`) — a `scheme:reference` value
(e.g. `kms:...`) routes to a `SignerProviderFactory` plugin so the key never
sits in config; a bare hex seed keeps the default in-config signer.

**Retention / pruning** — drop message bodies below the L1 anchor while keeping
proofs and evidence valid (data-minimization; crypto-shredding with encrypted
bodies):

```yaml
yano.app-chain.retention.enabled: true
yano.app-chain.retention.keep-blocks: 1000
```

**Kafka bridge** (plugin `appchain-kafka-sink` on `yano.plugins.directory`):

```yaml
yano.app-chain.sinks.kafka.bootstrap-servers: broker:9092
yano.app-chain.sinks.kafka.topic: my-appchain-blocks
```

**Snapshot / fast onboarding** — an atomic ledger snapshot a new member
restores without replay:

```bash
curl -XPOST localhost:8080/api/v1/app-chain/snapshot \
     -H 'content-type: application/json' -d '{"path":"/backups/snap-1"}'
# copy the directory to the new node's app-chain ledger path, then start it
```

**Scaffolds** — `scaffolds/docker-compose-cluster` (3-node cluster) and
`scaffolds/plugin-template` (custom state-machine jar).

## 7d. Zero-knowledge extensions (ADR 006, Wave 3 — EXPERIMENTAL)

All ZK ships as one **experimental** T3 plugin `yano-appchain-zk` (depends on
ZeroJ). Drop it on `yano.plugins.directory`. The node only **verifies** proofs —
proving happens client-side, where the secrets live. Circuits are chain config:
each `circuitId → VK hash` is pinned and loaded fail-closed at startup.

**E7.1 — private-predicate admission (`state-machine=zk-gate`).** Messages carry
an in-body proof; the node admits only if it verifies (and re-verifies in
`apply()` for consensus enforcement). The predicate — "amount ≤ limit",
"age ≥ 18", "KYC holds" — lives entirely in the proof; the chain never sees the
data.

```yaml
yano.app-chain.state-machine: zk-gate
yano.app-chain.zk.circuits[0].id: credit-limit
yano.app-chain.zk.circuits[0].vk-file: /etc/yano/credit-limit.vk
yano.app-chain.zk.circuits[0].vk-hash: <blake2b-256 hex of the vk file>
yano.app-chain.zk.circuits[0].proof-system: groth16     # or plonk
yano.app-chain.zk.circuits[0].curve: bls12_381
yano.app-chain.zk.verify-in-apply: true                 # consensus enforcement
```

**E7.2 — anchored verifiable credentials (`state-machine=credential-registry`).**
Records are issuer-BBS-signed attribute sets; the chain admits one only if the
issuer signature verifies (issuers configured, decoupled from membership) and
records a provable commitment. A holder later discloses selected fields with a
derived proof, verified against the issuer key and the anchored record.

```yaml
yano.app-chain.state-machine: credential-registry
yano.app-chain.zk.bbs.issuers[0].id: hr-dept
yano.app-chain.zk.bbs.issuers[0].public-key: <BBS G2 public-key hex>
```

**E7.3 — anonymous-but-authorized submissions (`state-machine=zk-membership`).**
The author proves membership in the registered set instead of signing with an
identifiable key; a one-time nullifier prevents double-action (deterministic
dedup in `apply()`). Anonymous voting, sealed bids, whistleblowing among known
members. Uses the same `zk.circuits[...]` config as E7.1 for the membership
circuit.

> Experimental: ZeroJ is unaudited with trusted-setup operational needs; use
> `plonk` (universal setup) for enterprise deployments. Proving helpers
> (`BbsCredentials`, proof generation) currently live in the plugin.

## 8. Operations & troubleshooting

- **`/status` is your dashboard**: `role`, `tipHeight`, `stateRoot`,
  `poolSize` (pending messages), per-peer connectivity, `anchor` progress.
- **Node won't start**: "public key ... not in the configured member list" —
  fix `members`/`signing-key`; "Unknown app-chain state machine" — wrong
  `state-machine` id or plugin jar not in the plugins directory.
- **Messages accepted but no blocks**: check that exactly one node has the
  `sequencer.proposer` key **and is running**, that `threshold` ≤ member
  count, and that enough members are connected to co-sign. A
  `AppChainStalledEvent` (and warning log) fires when a peer's tip is ahead
  with no local progress for 60s.
- **Member behind / fresh member joining**: automatic — it catches up over
  protocol 103 from any connected peer, verifying certificates and state
  roots block by block.
- **Sequencer down**: submissions still replicate, but no new blocks finalize
  until it returns (fixed-sequencer v1; rotating sequencer is on the roadmap
  — ADR 005 D2/S2). Restarting the sequencer is safe: vote locks are
  persisted and finalized history is immutable.
- **L1 impact**: none — the app protocols are invisible to non-Yano peers
  (capability negotiated in the handshake; a Haskell node simply never
  selects it). Run the `test-haskell-sync` skill for the standard L1
  compatibility regression.

## 9. Current limitations (v1)

- Fixed sequencer (S1); rotating L1-clocked sequencing is designed (ADR 005)
  but not yet implemented — sequencer availability is an ops concern.
- Static membership from config; changes need a coordinated config update and
  restart across members.
- One chain per node today (`chain-id` is singular in config); the wire
  protocol and ledger layout already support multiple chains.
- Anchoring is metadata-mode; script-anchor (on-chain proof verification
  against an anchor UTxO) is designed but not yet shipped.
