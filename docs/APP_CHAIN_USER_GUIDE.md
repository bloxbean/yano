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

Design references: `adr/app-layer/005-yano-app-chain-framework.md` (core framework)
and `adr/app-layer/006-appchain-enterprise-extensions-and-zk.md` (extensions & ZK).
Wire format specs (for building compatible implementations in other languages):
yaci `core/src/main/cddl/appmsg/` and yano `core-api/src/main/cddl/appchain/`.

## Published modules

The app-chain core is part of the default distribution (`yano.jar`). Everything
else is opt-in — a plugin jar on the node or a library in your application
(group id `com.bloxbean.cardano`):

| Artifact | Repo path | Purpose |
|---|---|---|
| `yano-appchain-stdlib` | `appchain/appchain-stdlib` | Ready state machines, selected by id (§9); ships in the distribution |
| `yano-appchain-client` | `appchain/appchain-client` | Java client SDK: REST + SSE + client-side proof verification (§16) |
| `yano-appchain-testkit` | `appchain/appchain-testkit` | JUnit 5 `@AppChainCluster` embedded clusters for tests (§16) |
| `yano-appchain-kafka-sink` | `appchain/extensions/appchain-kafka-sink` | Node plugin: finalized blocks → Kafka topics (§10) |
| `yano-appchain-zk` | `appchain/extensions/appchain-zk` | Node plugin, EXPERIMENTAL: ZK state machines & verification (§17) |
| `yano-appchain-spring-boot-starter` | `spring-starters/appchain-spring-boot-starter` | Spring Boot auto-config for the client SDK (§16) |

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
- **A standard library of further state machines** — registry, approvals,
  balances, document trails — selected purely by config (section 9).
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
| **Chain id** | Name of your app chain (`yano.app-chain.chain-id`). One group = one chain id. A node can host several chains (section 8). |
| **Member** | A participant identified by an Ed25519 public key. Only members' messages are accepted; members co-sign blocks. |
| **Sequencer / proposer** | The one member (by public key) that orders messages into blocks. Fixed, configured. |
| **Threshold** | How many member signatures a finality certificate needs (e.g. 2 of 2). |
| **App message** | Envelope with an **opaque body** (your bytes — CBOR/JSON/protobuf/anything), signed by the sender. The framework never parses the body. |
| **Topic** | Optional sub-stream label inside a chain (routing/filtering). Names starting with `~` are reserved. |
| **App block** | Ordered batch of messages + post-state MPF root + finality certificate, hash-linked to the previous block. |
| **State root** | MPF (Aiken-compatible Merkle Patricia Forestry) root after applying a block. Identical on every member, anchorable to L1, provable. |
| **State machine** | The only component that interprets message bodies. Built-in: `ordered-log` + the standard library (section 9). Custom ones plug in (section 6). |

Trust model: **fail closed**. Envelope signatures, membership, vote
signatures, and certificate thresholds are cryptographically verified on every
node, always. A non-member's messages are dropped; a non-sequencer's blocks
are never finalized; a tampered block fails the state-root re-execution check.

---

## 3. Quick start: two-node cluster with the default distribution

This walkthrough uses `java -jar` with `-D` flags (built from source). The
**official distributions** work the same way — only where config lives differs:
the release zip (`yano-<ver>.zip`) has a `./yano.sh start:<profile>` launcher
plus `config/application.yml`; the Docker bundle (`yano-docker-<ver>.zip`)
mounts `config/application.yml` and a `plugins/` directory into the
`bloxbean/yano` image; native binaries mirror the zip layout (but cannot load
plugin jars). See the tutorial's Part 0 for per-distribution instructions;
releases: https://github.com/bloxbean/yano/releases

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

Ready-made end-to-end regressions of this flow (on devnet, with anchoring) are
the `test-app-chain-cluster` and `test-app-chain-extensions` skills under
`.claude/skills/`.

> **Devnet tip:** when node B follows a devnet producer for L1, start B with
> `-Dyano.dev-mode=false -Dyano.block-producer.enabled=false` and give it a
> copy of A's shelley genesis taken **after** A started (A rewrites
> `systemStart` into the file it loads).

---

## 4. REST API

Base path: `${yano.api-prefix}/app-chain` (default `/api/v1/app-chain`).

Every chain endpoint below is also available **chain-scoped** as
`/app-chain/chains/{chainId}/...` on a multi-chain node (section 8). The
chain-less form keeps working while exactly one chain is configured; with
several chains it returns `400` (ambiguous), and `503` when no chain is
enabled. When API-key auth is on (section 12), every request needs `X-API-Key`.

| Method & path | Purpose |
|---|---|
| `GET /chains` | Hosted chains: `[{chainId, tipHeight, stateRoot}]`. |
| `POST /messages` | Submit a message. Body: `{"topic": "...", "body": "<text>"}` or `{"topic": "...", "bodyHex": "<hex bytes>"}`. Returns `202` with the content-derived `messageId`. |
| `GET /messages?limit=100&topic=...` | Recently accepted messages (local + peer), with sender, sequence, body hex, source. |
| `GET /messages/{messageIdHex}` | One finalized message: position (`height`, `index`) + full content (§15). |
| `GET /messages/by-topic/{topic}?fromHeight=&limit=` | Finalized message refs on a topic, ascending (§15). |
| `GET /messages/by-sender/{senderHex}?fromHeight=&limit=` | Finalized message refs from a member key, ascending (§15). |
| `GET /status` | Chain status: role, tip height, state root, pool size, peer connectivity, counters, anchor + sink progress. |
| `GET /tip` | `{chainId, height, stateRoot}` of the last finalized block. |
| `GET /blocks/{height}` | Finalized block: hashes, roots, proposer, cert signature count, full message list. |
| `GET /blocks?from=&limit=` | Paged block summaries, ascending (default: window ending at the tip) (§15). |
| `GET /proof/{keyHex}` | MPF inclusion proof (wire format) for a state key against the committed root. For `ordered-log` the key **is** the message id; the response includes the value and `finalizedAtHeight`. |
| `GET /evidence/{messageIdHex}` | Portable, offline-verifiable evidence bundle for a finalized message (§13). |
| `GET /stream?fromHeight=&topic=` | SSE stream of finalized messages: replay, then live (§10). |
| `POST /snapshot` | Atomic ledger snapshot for fast member onboarding (§14). Body: `{"path": "<fresh dir>"}`. |
| `POST /admin/pause` / `POST /admin/resume` | Pause/resume local submissions (§14). |
| `POST /admin/drain-pool` | Drop all pending (unfinalized) messages (§14). |
| `POST /admin/force-anchor` | Anchor the current tip now (§14). |
| `GET /admin/members` | Effective member set + threshold (§14). |
| `POST /admin/members/add` / `.../remove` | Stage a member key in/out for rotation (§14). Body: `{"publicKey": "..."}`. |
| `POST /admin/members/reset` | Drop the persisted member override; back to the configured list (§14). |
| `POST /admin/threshold` | Set the finality threshold (§14). Body: `{"threshold": N}`. |

Submission notes:
- The node signs your submission with **its own** member key (the REST caller
  is trusted local input — same model as a wallet talking to its own node).
- The body is opaque: `body` (UTF-8 text) or `bodyHex` (arbitrary bytes).
- Limits: `max-message-bytes` (default 64 KB), TTL `default-ttl-seconds`
  (default 600) — an unfinalized message expires out of the pool after that.
- Topics starting with `~` are reserved (consensus/system traffic).
- **Backpressure**: when the pending pool (`pool.max-messages`, default
  10 000) is full, `POST /messages` returns **429** and the message is
  neither stored nor relayed — back off and retry. Inbound gossip dropped by
  a full pool is counted in `GET /status` under `drops.pool_full`
  (never an error: the sender's own node already holds the message).
- **Replay protection (sender-seq)**: every envelope carries a per-sender
  sequence number. A message whose seq is at or below the sender's last
  *finalized* seq is a replay — rejected at admission on every ledger node
  (counted as `drops.stale_seq`). Gaps are allowed and meaningless (seqs are
  wall-clock-seeded so restarts never reuse one); the seq does **not** define
  ordering — the sequencer does. With `message.enforce-sender-seq: true`
  the rule also becomes consensus-visible: followers reject any block whose
  per-sender seqs are not strictly increasing above the finalized floor
  (default off this release for compatibility; all members must agree on
  this flag, like the state machine id).

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
Before writing one, check the standard library (section 9): registry,
approvals, balances and document-trail machines ship in the distribution.
For everything else, implement
`com.bloxbean.cardano.yano.api.appchain.AppStateMachine`:

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

Rules: `apply` must be **deterministic** — every member re-executes it and
the resulting state root must match the proposer's byte-for-byte, or the block
is rejected (and your chain stalls at that height). Forbidden inside
`apply()`: wall-clock time (use `block.timestamp()`), randomness, network or
file I/O, environment reads, iteration over unordered collections
(`HashMap`/`HashSet` — use ordered ones), and locale/charset-dependent or
library-default serialization. Note that `validate()` runs at the proposer's
admission only — any rule that must hold by consensus belongs in `apply()`.

**Verify before deploying** with the conformance harness (in `yano-runtime`,
ADR 008.1 I1.6) — it applies one identical block corpus through the real
ledger commit path in N independent runs plus a kill-and-reopen replay, and
asserts byte-identical roots at every height:

```java
StateMachineConformance.builder(new MyStateMachineProvider())
        .blocks(50).messagesPerBlock(5).seed(42)
        .bodyGenerator((height, index, random) -> myRealisticCommand(random))
        .assertDeterministic();   // AssertionError with the exact divergence height
```

The plugin template ships this test pre-wired (`CounterConformanceTest`).

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
   A ready Gradle project for this is `scaffolds/plugin-template`.

### 6.2 Embed programmatically (library mode)

For fully custom nodes, `AppChainSubsystem` (module `runtime`,
`com.bloxbean.cardano.yano.runtime.appchain`) is a public kernel `Subsystem`
whose constructor accepts your `AppStateMachine` instance directly. A
first-class `YanoAssembly.appChain(spec)` builder is planned; until then the
plugin-jar route is the recommended packaging for custom chains.

---

## 7. Configuration reference

Flat (single-chain) keys. The same suffixes apply per chain under
`yano.app-chain.chains[i].` (section 8):

| Property (`yano.app-chain.`) | Default | Description |
|---|---|---|
| `enabled` | — | See "Enabling" below |
| `chain-id` | — | App-chain identity (required) |
| `signing-key` | — | This member's Ed25519 private seed, hex, or a `scheme:reference` external-signer spec (§12) |
| `members` | — | Comma-separated member public keys, hex (required; must include own key) |
| `peers` | — | Comma-separated app-group peers `host:port` (their N2N server ports) |
| `sequencer.proposer` | empty | Fixed sequencer's public key (implies `sequencer.mode: fixed`). No proposer AND no mode = diffusion-only (messages replicate, but no blocks/ledger) |
| `sequencer.mode` | `fixed` | Consensus mode: `fixed` (ADR-005 S1), `rotating` (S2 — proposership rotates over L1-slot windows; needs no fixed proposer), or a plugin `SequencerModeProvider` id. **Must match on all members.** Finality (threshold certs, one-vote-per-height) is identical in every mode |
| `sequencer.window-slots` | `60` | `rotating` only: L1 slots per proposer window. Rotating mode requires an L1 feed and prefers thresholds like 2-of-3 over all-of-n (see the rotation runbook in ADR 008.2) |
| `threshold` | `1` | Finality certificate signatures required |
| `membership.mode` | `static` | `governed` = membership changes are finalized chain transactions requiring threshold-many identical member commands (§14.2, ADR 008.3). **Must match on all members** |
| `membership.approval-window-blocks` | `600` | `governed` only: blocks a half-approved command stays pending before expiring |
| `block.interval-ms` | `2000` | Proposer tick (blocks are only made when messages are pending) |
| `block.max-messages` | `500` | Max messages per block |
| `state-machine` | `ordered-log` | Built-in id, a stdlib id (§9) or a plugin provider id |
| `max-message-bytes` | `65536` | Max opaque body size |
| `max-ttl-seconds` | `3600` | Max accepted message TTL |
| `default-ttl-seconds` | `600` | TTL applied to REST submissions |
| `anchor.enabled` | `false` | L1 anchoring on this node |
| `anchor.signing-key` | — | Anchor wallet seed, hex (required if anchoring) |
| `anchor.every-blocks` | `10` | Anchor after this many new app blocks |
| `anchor.max-interval-minutes` | `60` | Anchor at least this often while blocks pend |
| `anchor.metadata-label` | `7014` | Metadata label of anchor txs |
| `anchor.validity-slots` | `7200` | Anchor tx TTL = current L1 slot + this; a resubmitted anchor can never race a late-landing original |
| `anchor.fallback-fee-lovelace` | `300000` | Anchor tx fee when protocol parameters are unavailable; normally the fee is computed from the node's current params by tx size |
| `l1.stability-depth` | `0` | Depth of the stable L1 reference in app blocks (0 = off). When > 0, followers verify each proposal's L1 ref against their **own** L1 view (monotonic, hash-matched at depth; brief proposer lead is retried, a fabricated ref is rejected fail-closed) and the node refuses to start without an L1 event feed |
| `pool.max-messages` | `10000` | Pending-pool capacity; a full pool returns 429 on submit and drops (counted) inbound gossip (§4) |
| `message.enforce-sender-seq` | `false` | Consensus-visible sender-seq rule: followers reject blocks with stale/duplicate per-sender seqs (§4). Must match on all members |

**Enabling.** Three states, checked in this order:

- An **explicit** `yano.app-chain.enabled: false` always wins: it suppresses
  the app chain entirely, including `chains[i]` auto-enable (operator kill
  switch). Leave the key **absent** unless you mean this.
- Any `chains[i].*` configuration present → the app chain **auto-enables**;
  no `enabled` key needed (section 8).
- Flat keys + `enabled: true` → one chain. This is the classic single-chain
  config and remains fully supported.

Extension settings are documented next to the capability they configure:
`chains[i].*` (§8), `webhooks` and `sinks.*` (§10), `api.auth.enabled` and
`api.keys` (§12), `retention.*` (§13), `zk.*` (§17).

Storage: each app ledger is a separate RocksDB at
`<yano.storage.path>/app-chain/<chain-id>/` — blocks, state trie, indexes and
anchor markers commit atomically and are independent of the L1 chain state.
Back it up like any RocksDB directory (node stopped), or use snapshots (§14).
The app chain is append-only after finality; there is no rollback path.

Events (for `@DomainEventListener` plugins): `AppMessageReceivedEvent`,
`AppBlockFinalizedEvent`, `AppChainAnchoredEvent`, `AppChainStalledEvent`.

---

## 8. Multiple chains per node

One Yano node can host several independent app chains — each with its own
ledger, state machine, member set, sequencer and anchor policy — sharing the
node's networking and L1 view. Configuration is the same set of keys, indexed:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: "orders-chain"
      signing-key: "<seed hex>"
      members: "<pubA>,<pubB>"
      peers: "nodeB.example.com:13337"
      sequencer:
        proposer: "<pubA>"
      threshold: 2
      anchor:
        enabled: true
        signing-key: "<anchor seed hex>"
    chains[1]:
      chain-id: "audit-chain"
      state-machine: kv-registry
      signing-key: "<seed hex>"
      members: "<pubA>,<pubB>"
      peers: "nodeB.example.com:13337"
      sequencer:
        proposer: "<pubA>"
      threshold: 2
```

- Every per-chain key uses the same suffixes as the flat config — including
  `anchor.*`, `webhooks`, `retention.*`, `sinks.*` and `zk.*`. Anchor only
  the chains that need L1 evidence.
- Chains are indexed from 0; `chain-id` is required per entry. Presence of
  any `chains[i]` config auto-enables the app chain (section 7).
- REST: `GET /app-chain/chains` lists hosted chains; address one with
  `/app-chain/chains/{chainId}/...`. The chain-less paths keep working when
  exactly one chain is configured; with several they return `400`.
- Each chain stores its ledger under `<yano.storage.path>/app-chain/<chain-id>/`.

---

## 9. Standard-library state machines

`yano-appchain-stdlib` ships in the default distribution and is inert until a
machine is selected by id — `yano.app-chain.state-machine` (or per chain,
`chains[i].state-machine`). CBOR command formats and client-side command
encoders are documented on the classes in `appchain/appchain-stdlib`. All
machines keep the framework guarantees: deterministic apply, provable state
keys, anchorable roots. All members of a chain must run the same machine id.

**`ordered-log`** (default) — the tamper-evident ordered log of opaque blobs
described in section 1. No configuration needed.

**`kv-registry`** — a replicated registry with **per-key ownership**: the
first writer of a key becomes its owner (owner = the authenticated envelope
sender), and only the owner may update or delete it. Every entry is a provable
`[owner, value]` pair. Use for token registries, DID documents, allow-lists,
shared configuration.

```yaml
yano.app-chain.state-machine: kv-registry
# Optional structural check on PUT values (raw | cbor | utf8, default raw).
# A non-conforming PUT is rejected at admission and is a deterministic no-op
# in apply on every member.
yano.app-chain.machines.kv-registry.value-format: cbor
```

**`approvals`** — k-of-n approval workflows: `propose` / `approve` / `reject`
commands, deduplicated approvers, a single reject closes an item, deadlines
derived from the deterministic block timestamp. The full decision trail per
item is provable. Use for release gates, payment authorization, cross-org
sign-off.

```yaml
yano.app-chain.state-machine: approvals
```

**`balances`** — account balances with `mint` / `transfer` commands: a member
spends only its own account, non-negativity is enforced deterministically in
apply (an overdraft is a no-op on every node), and every balance is a provable
state key. Use for netting, loyalty points, internal credits.

```yaml
yano.app-chain.state-machine: balances
# Optional: restrict minting to one member (32-byte hex Ed25519 key).
# Unset = any member may mint (open mode) — set this for production chains.
yano.app-chain.machines.balances.minter: aa11...
```

Machine settings live under `yano.app-chain.machines.<machine-id>.*` (per
chain: `chains[i].machines.<machine-id>.*`) and must be identical on every
member — they are part of the deterministic apply logic, like the machine id
itself.

**`doc-trail`** — append-only per-entity event trails keyed by an external id
(`productId`, `caseId`, ...): each entry advances a chained head
`blake2b(prevHead ‖ entryHash ‖ author)`, so one proof of the head verifies
the whole ordered trail against the anchored root (a `computeHead` verifier is
provided). Use for DPP/supply-chain trails, case management, evidence chains.

```yaml
yano.app-chain.state-machine: doc-trail
```

Each machine also doubles as a reference implementation for custom ones
(section 6).

---

## 10. Consuming finalized messages (SSE, webhooks, Kafka)

Polling `GET /messages` works, but finalized messages are also pushed.

**SSE** — `GET /app-chain/stream?fromHeight=&topic=` streams finalized
messages: it replays history from `fromHeight` (default: live-only from the
current tip), then follows new blocks. Events are named `app-message` with id
`height:index` (usable as `Last-Event-ID` for resumption); `heartbeat` events
keep idle connections alive; `topic` filters server-side.

```bash
curl -N "http://node:8080/api/v1/app-chain/stream?fromHeight=1&topic=orders"
```

**Webhooks** — `yano.app-chain.webhooks=https://...` (comma-separated; also
per chain). Finalized blocks are POSTed as JSON in height order, at-least-once,
with a persisted per-sink cursor — a restart resumes where delivery left off,
and a failing sink halts and retries rather than skipping blocks. Requests
carry `X-App-Chain-Id` / `X-App-Chain-Height` headers; delivery progress and
the last error appear in `/status` under `sinks`.

**Kafka** — drop the `yano-appchain-kafka-sink` plugin jar into the node's
plugins directory (`yaci.plugins.directory`, default `plugins/`) and
configure:

```yaml
yano.app-chain.sinks.kafka.bootstrap-servers: broker:9092
yano.app-chain.sinks.kafka.topic: my-appchain-blocks
```

Blocks are produced as JSON keyed by height (partition-stable) with
synchronous acks, under the same ordered, at-least-once cursor semantics as
webhooks.

**Custom sinks** — implement the `FinalizedStreamSink` SPI; ordering, cursor
persistence and at-least-once redelivery come from the framework
(`yano.app-chain.sinks.<scheme>.*` config is passed through to your sink).
In-process consumers can instead subscribe to `AppBlockFinalizedEvent`
(section 7) or `AppChainGateway.subscribeFinalized`.

---

## 11. Typed messages (codec)

The framework stays blob-first — codecs live strictly at the edges, so wire
format, proofs and anchoring never depend on your object model.

Client side (`yano-appchain-client`):

```java
record Order(String id, long qty) {}
CborCodec<Order> codec = CborCodec.of(Order.class);
client.submitTyped("orders", new Order("o-1", 5), codec::encode);
client.subscribeTyped(-1, "orders", codec::decode, (order, msg) -> handle(order));
```

Node side (custom state machines): extend `TypedAppStateMachine<T>` with a
`MessageCodec<T>` — default `JacksonCborCodec`, in core-api — and implement
typed validate/apply instead of parsing bytes yourself. The client `CborCodec`
and the node `JacksonCborCodec` are wire-compatible.

---

## 12. Security (API-key auth, encrypted bodies, external signers/KMS)

**API-key auth** (off by default) protects the whole `/app-chain/*` REST
surface, admin endpoints included:

```yaml
yano:
  app-chain:
    api:
      auth:
        enabled: true
    # full-access key + a key limited to submitting on two topics:
    # yano.app-chain.api.keys: "opsKey123,partnerKey456=orders|invoices"
```

Requests then require the `X-API-Key` header. A key entry of the form
`key=topicA|topicB` restricts *submissions* to the listed topics; reads stay
unrestricted per key. This is the only built-in REST auth today — for
mTLS/OIDC put the API behind your standard gateway/reverse-proxy.

**Encrypted bodies** — client-side envelope encryption with a group key; the
chain carries ciphertext only (ordering, proofs and anchors all work over it),
and per-topic keys give need-to-know inside one chain. AES-256-GCM with the
topic as associated data; `GroupCipher` (client SDK) and `BodyCipher`
(core-api, for state machines) share the wire format:

```java
byte[] key = Hex.decode(groupKeyHex);
client.submit("settlement", GroupCipher.encrypt(key, "settlement", plaintext));
byte[] plain = GroupCipher.decrypt(key, "settlement", message.body());
```

**External signers / KMS** — `yano.app-chain.signing-key` accepts a
`scheme:reference` value (e.g. `kms:...`) that routes signing to a
`SignerProviderFactory` plugin, so the member key never sits in a config file;
a bare hex seed keeps the default in-config signer. The SPI is sign-only (no
key export). No KMS/HSM/Vault plugins ship in the distribution yet — the SPI
is the supported integration point.

---

## 13. Compliance (audit/evidence export, retention & pruning)

**Evidence export** — one call produces a portable, **offline-verifiable**
evidence bundle for any finalized message:

```bash
curl localhost:8080/api/v1/app-chain/evidence/<messageIdHex> > evidence.json
```

The bundle carries the containing block(s), the member set and threshold in
effect at that height, and the L1 anchor reference. An auditor verifies it
with core-api's `EvidenceVerifier` — no node access: block hashes and
messages-root are recomputed, certificates are checked against the members at
the chain's full m-of-n threshold, inclusion is confirmed, and the hash chain
is linked to the anchored block. A message newer than the last confirmed
anchor yields a finality-only bundle (`anchor: null`) until the next anchor.

**Retention & pruning** — data minimization with proofs preserved:

```yaml
yano.app-chain.retention.enabled: true
yano.app-chain.retention.keep-blocks: 1000
```

Message **bodies** older than `keep-blocks` and below the last confirmed L1
anchor are stripped; headers, message ids, roots and certificates remain, so
existing proofs and evidence bundles stay valid. Pruning never runs ahead of
the slowest configured sink (§10), so at-least-once delivery is unaffected.
Combined with encrypted bodies (§12), destroying a topic key is
**crypto-shredding**: content becomes unreadable everywhere while the anchored
evidence trail survives.

---

## 14. Operations (admin API, key rotation runbook, snapshots & onboarding, metrics)

### 14.1 Admin API

`POST /app-chain[/chains/{id}]/admin/...` — covered by API-key auth when
enabled (§12):

- `pause` / `resume` — stop/allow **local** REST submissions (peers and
  finalized replication are unaffected).
- `drain-pool` — drop all pending (unfinalized) messages; returns the count.
- `force-anchor` — anchor the current tip now instead of waiting for cadence.

### 14.2 Membership changes

**Governed mode (recommended, ADR 008.3)** — with
`yano.app-chain.membership.mode: governed`, membership changes are **chain
transactions**: the same admin endpoints *submit* a governance command on the
reserved `~governance/membership` topic instead of mutating the local node. A
change activates once **threshold-many members submit the identical command**
(each operator runs ONE call on their own node) and takes effect a few blocks
later, identically on every node. No runbook ordering, no way to be
inconsistent — late joiners and catch-up derive the full membership history
from replay. A half-approved command quietly expires after
`membership.approval-window-blocks` (default 600). Guard rails (below-threshold
removals, invalid thresholds, removing a fixed proposer) void deterministically.
The config member list is the **genesis epoch only**; a member added later
starts with the original genesis list and derives its own membership from the
chain. `POST /admin/members/reset` remains the break-glass local override
(loudly logged as a trust-model deviation).

**Static mode runbook (default)** — staged and operator-coordinated: run the
SAME steps on EVERY node, in this order. The rotated state persists and
overrides config across restarts (`POST /admin/members/reset` drops the
override and returns to the configured list).

1. **Add the new key everywhere** — on each node:
   `POST /app-chain/admin/members/add {"publicKey":"<newPub>"}`.
   Both old and new keys are now accepted; nothing breaks if nodes are
   momentarily out of step, because the union is accepted during this stage.
2. **Switch the rotating member's signer** — update that node's
   `yano.app-chain.signing-key` to the new seed and restart it. It now signs
   with the new key, which every node already accepts.
3. **(Optional) re-threshold** — `POST /app-chain/admin/threshold
   {"threshold":N}` on every node if the effective member count changed.
4. **Retire the old key everywhere** —
   `POST /app-chain/admin/members/remove {"publicKey":"<oldPub>"}` on each
   node. Guard rails: the configured proposer can't be removed (rotate the
   proposer via config + restart until rotating sequencing ships) and the set
   can't drop below the threshold.

`GET /app-chain/admin/members` shows the effective set + threshold. This is
an interim, operator-coordinated mechanism until chain-governed membership
(ADR 005 D6) makes rotation itself an on-chain action.

### 14.3 Snapshots & member onboarding

An atomic ledger snapshot lets a new member start without replaying history:

```bash
curl -XPOST localhost:8080/api/v1/app-chain/snapshot \
     -H 'content-type: application/json' -d '{"path":"/backups/snap-1"}'
# copy the directory to the new node's app-chain ledger path
# (<yano.storage.path>/app-chain/<chain-id>/), then start it
```

Every snapshot carries a **signed manifest** (`snapshot-manifest.json` +
`.sig`): the snapshotting member signs the chain id, height, tip block hash,
state root, membership-epoch hash, last confirmed anchor and a sha256 of every
file. On first start after a restore the node verifies — *before opening the
database* — that the manifest is signed by a configured member key and that
every file hash matches, then binds the opened ledger to the attested tip
(height, block hash, state root, membership). Any mismatch refuses to start.
A `.manifest-verified` marker skips re-verification on later restarts; legacy
snapshots without a manifest still restore (integrity checks only).

Independently of manifests, **every** start now recomputes the tip block hash
and verifies the tip's finality certificate against membership-at-height — a
ledger whose stored values merely agree with each other no longer passes.

The restored node catches up from the snapshot height over protocol 103.
Snapshots are also the onboarding path past a pruning horizon (§13).

### 14.4 Metrics & health

Standard Quarkus Prometheus endpoint `/q/metrics`, per-chain `chain` tag:
`yano_appchain_tip_height`, `yano_appchain_pool_size`,
`yano_appchain_peers_connected`, `yano_appchain_stalled` (0/1),
`yano_appchain_anchor_lag_blocks`, `yano_appchain_sink_lag_blocks{sink}`
(gauges), `yano_appchain_messages_dropped_total{reason}`,
`yano_appchain_blocks_finalized_total`,
`yano_appchain_messages_finalized_total` (counters),
`yano_appchain_block_interval` (timer).

A ready-made Grafana dashboard ships at `docs/grafana/appchain-dashboard.json`.

**Status page**: a built-in dashboard is served at **`/ui/app-chain/`**
(sibling of the L1 node page at `/ui/status/`, cross-linked): chain selector
(multi-chain), role/health pills, tip + membership hero, consensus/pool/
traffic/anchor/sinks/peers panels, four trend charts, a live SSE message feed
and a recent-blocks table. Query params: `?api=` (API prefix), `?poll=` (ms),
`?noanim=1`. When API-key auth is enabled, set the key via the key button
(stored in the browser; the live feed uses fetch-streaming so the key applies
there too).

`GET /status` also reports `lastBlockAtMillis`, `blockIntervalMs` (rolling
average), `stalled`, `drops` (by reason), `anchor.lagBlocks` and per-sink
`lagBlocks`.

**Health**: `/q/health/ready` gates only on the subsystem running (peer
connectivity is deliberately excluded — a two-node bootstrap would deadlock).
For operational alerting use the **health group**
`GET /q/health/group/appchain`: DOWN on stall, anchor error, sink delivery
error, or paused submissions, with per-chain data fields.

---

## 15. Query surface

Beyond `/proof` and single-block reads, the finalized ledger is directly
queryable:

- `GET /blocks?from=&limit=` — paged block summaries (height, timestamp,
  state root, message count, cert signatures), ascending; default page is the
  window ending at the tip.
- `GET /messages/{messageIdHex}` — one finalized message: `height`, `index`,
  topic, sender and body.
- `GET /messages/by-topic/{topic}?fromHeight=&limit=` — finalized message
  refs on a topic, ascending `(height, index)`.
- `GET /messages/by-sender/{senderHex}?fromHeight=&limit=` — finalized
  message refs from a member public key, ascending.

Topic and sender indexes are written atomically with each block commit;
blocks finalized before a node upgraded to this feature are not retro-indexed.

---

## 16. Client libraries (Java SDK, Spring Boot starter, testkit)

**Java SDK** (`com.bloxbean.cardano:yano-appchain-client`) — typed access
with client-side proof verification, dependency-light:

```java
AppChainClient client = AppChainClient.builder("http://node:8080/api/v1")
        .chainId("orders-chain").apiKey("...").build();
var result = client.submitText("orders", "order #1");
client.subscribe(-1, "orders", msg -> handle(msg));      // SSE, auto-reconnect
var proof = client.proof(Hex.decode(result.messageId())).orElseThrow();
boolean ok = ProofVerifier.verify(proof, anchoredRootHex); // don't trust, verify
```

The SDK reconnects SSE automatically and dedups replays by `(height, index)`.
`ProofVerifier` checks an MPF proof against an (anchored) root locally —
tampered proofs fail closed.

**Spring Boot starter** (`yano-appchain-spring-boot-starter`) — the SDK,
Spring-shaped:

```yaml
yano.appchain.client.base-url: http://node:8080/api/v1
yano.appchain.client.chain-id: my-chain     # optional (single-chain node)
yano.appchain.client.api-key: ...           # when the node enables auth
```

```java
@Component
class Orders {
    private final AppChainTemplate appChain;
    Orders(AppChainTemplate appChain) { this.appChain = appChain; }

    void place(String order) {
        String id = appChain.send("orders", order);
        appChain.verifiedMessageProof(id);   // fetch + verify MPF proof locally
    }

    @AppChainListener(topic = "orders")     // finalized messages, SSE, auto-reconnect
    void onOrder(String body) { ... }
}
```

Listener methods take `StreamedMessage`, `byte[]` or `String`; all beans are
`@ConditionalOnMissingBean`, so anything can be overridden.

**Testkit** (`com.bloxbean.cardano:yano-appchain-testkit`, test scope) —
embedded multi-node chains for CI: generated keys, real sockets, temp ledgers:

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

**Scaffolds** — `scaffolds/docker-compose-cluster` (ready 3-node cluster) and
`scaffolds/plugin-template` (custom state-machine plugin Gradle project).

---

## 17. Zero-knowledge extensions (EXPERIMENTAL)

All ZK ships as one **experimental** plugin, `yano-appchain-zk` (depends on
ZeroJ). Drop it on `yaci.plugins.directory`. The node only **verifies**
proofs — proving happens client-side, where the secrets live. Circuits are
chain configuration: each `circuitId → VK hash` is pinned and loaded
fail-closed at startup, and every member enforces verification in `apply()`
(consensus-critical, not just admission).

**Private-predicate admission (`state-machine=zk-gate`).** Messages carry an
in-body proof; the chain finalizes a message only if it verifies. The
predicate — "amount ≤ limit", "age ≥ 18", "KYC holds" — lives entirely in the
proof; the chain (and the other members) never see the underlying data.

```yaml
yano.app-chain.state-machine: zk-gate
yano.app-chain.zk.circuits[0].id: credit-limit
yano.app-chain.zk.circuits[0].vk-file: /etc/yano/credit-limit.vk
yano.app-chain.zk.circuits[0].vk-hash: <blake2b-256 hex of the vk file>
yano.app-chain.zk.circuits[0].proof-system: groth16     # or plonk
yano.app-chain.zk.circuits[0].curve: bls12381
```

**Anchored verifiable credentials (`state-machine=credential-registry`).**
Records are issuer-BBS-signed attribute sets; the chain admits one only if the
issuer signature verifies (issuers are configured trust anchors, decoupled
from membership) and records a provable commitment. A holder later discloses
*selected fields* with a derived proof, verified against the issuer key and
the anchored record.

```yaml
yano.app-chain.state-machine: credential-registry
yano.app-chain.zk.bbs.issuers[0].id: hr-dept
yano.app-chain.zk.bbs.issuers[0].public-key: <BBS G2 public-key hex>
```

**Anonymous-but-authorized submissions (`state-machine=zk-membership`).**
The author proves membership in the registered set instead of signing with an
identifiable key; a one-time nullifier prevents double-action (deterministic
dedup in `apply()`, committed in MPF state). Anonymous voting, sealed bids,
whistleblowing among known members. Uses the same `zk.circuits[...]` config
for the membership circuit. Note: the transport envelope still carries the
relaying node's signature — the logical author is anonymous within the member
set; a fully anonymous transport scheme is a planned follow-up.

> Experimental: ZeroJ is unaudited and Groth16 has trusted-setup operational
> needs; prefer `plonk` (universal setup) for enterprise deployments. Proving
> and disclosure helpers (`BbsCredentials`, proof generation) currently live
> in the plugin.

---

## 18. Troubleshooting

- **`/status` is your dashboard**: `role`, `tipHeight`, `stateRoot`,
  `poolSize` (pending messages), per-peer connectivity, `anchor` progress,
  sink cursors.
- **Node won't start**: "public key ... not in the configured member list" —
  fix `members`/`signing-key`; "Unknown app-chain state machine" — wrong
  `state-machine` id or plugin jar not in the plugins directory.
- **Chain-less REST returns 400**: the node hosts more than one chain — use
  `/app-chain/chains/{chainId}/...` (section 8).
- **Messages accepted but no blocks**: check that exactly one node has the
  `sequencer.proposer` key **and is running**, that `threshold` ≤ member
  count, and that enough members are connected to co-sign. A
  `AppChainStalledEvent` (and warning log) fires when a peer's tip is ahead
  with no local progress for 60s.
- **Submissions return "paused"**: an operator called `/admin/pause` —
  `/admin/resume` re-opens local submissions (section 14).
- **Member behind / fresh member joining**: automatic — it catches up over
  protocol 103 from any connected peer, verifying certificates and state
  roots block by block. For long histories, restore a snapshot first (§14.3).
- **Sequencer down**: submissions still replicate, but no new blocks finalize
  until it returns (fixed-sequencer v1; rotating sequencer is on the roadmap
  — ADR 005 D2/S2). Restarting the sequencer is safe: vote locks are
  persisted and finalized history is immutable.
- **L1 impact**: none — the app protocols are invisible to non-Yano peers
  (capability negotiated in the handshake; a Haskell node simply never
  selects it). Run the `test-haskell-sync` skill for the standard L1
  compatibility regression.

## 19. Current limitations

- Fixed sequencer (S1); rotating L1-clocked sequencing is designed (ADR 005)
  but not yet implemented — sequencer availability is an ops concern.
- Membership changes are operator-coordinated: the admin rotation API
  (section 14) stages key changes at runtime, but every node must be driven
  through the same steps. Chain-governed membership (ADR 005 D6) is designed,
  not shipped.
- Anchoring is metadata-mode; script-anchor (on-chain proof verification
  against an anchor UTxO) is designed but not yet shipped.
- REST protection is API-key only (section 12); use standard gateway
  infrastructure for mTLS/OIDC.
- All ZK extensions are experimental (section 17) and never on a default code
  path.
