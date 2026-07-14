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
| `yano-appchain-effects-cardano` | `appchain/extensions/appchain-effects-cardano` | Node plugin: Cardano payment executor for the effect system (§18) |
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
- **Effects** (optional): a finalized state transition can trigger an action
  *outside* the chain — a Cardano payment, webhook, ERP call — safely, by
  emitting a provable record that a separate runtime executes and reports back
  on-chain (section 18).

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
| **Anchor leader** | The one node with `anchor.enabled` — it drives L1 anchoring (builds/pays/submits anchor txs). A coordination + fee-paying role, orthogonal to the proposer and NOT a trust point (§5.1); it does not rotate. |

Trust model: **fail closed**. Envelope signatures, membership, vote
signatures, and certificate thresholds are cryptographically verified on every
node, always. A non-member's messages are dropped; a non-sequencer's blocks
are never finalized; a tampered block fails the state-root re-execution check.

For the full developer-level mechanics — the consensus round check by check,
vote locks, rotation math, catch-up/restart semantics, and every built-in
state machine's exact wire format — see the
[consensus & internals guide](APP_CHAIN_CONSENSUS_GUIDE.md).

---

## 3. Quick start: two-node cluster with the default distribution

This walkthrough uses `java -jar` with `-D` flags (built from source). The
**official distributions** work the same way — only where ordinary runtime
config lives differs:
the release zip (`yano-<ver>.zip`) has a `./yano.sh start:<profile>` launcher
plus `config/application.yml`; the Docker bundle (`yano-docker-<ver>.zip`)
mounts `config/application.yml` and a `plugins/` directory into the
`bloxbean/yano` image; native binaries mirror the zip layout (but cannot load
directory plugin JARs after build; manifested bundles may be included when the
native application is built). See the tutorial's Part 0 for per-distribution
instructions; releases: https://github.com/bloxbean/yano/releases

The public REST prefix is the exception: it is an immutable artifact input,
not a `-D` or YAML launch setting. Build it with `-PyanoApiPrefix` as described
in section 4 and the distribution guide.

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

### 3.5 Or: the one-command demo cluster

The distribution zips ship a ready-made multi-node launcher under
`appchain-cluster/` — the fastest way to evaluate everything in this guide
without writing any config:

```bash
unzip yano-<version>.zip && cd yano-<version>
./appchain-cluster/cluster.sh start 3        # 3-node devnet: L1 producer + 2 followers,
                                             # 2 demo chains (ordered-log + kv-registry)
./appchain-cluster/cluster.sh status         # tips + state-root agreement across nodes
./appchain-cluster/cluster.sh submit orders-chain demo "hello"
./appchain-cluster/cluster.sh kv registry-chain set color blue
./appchain-cluster/loadtest.sh orders-chain -n 2000 -c 20     # throughput test
./appchain-cluster/loadtest.sh registry-chain --kv -n 2000    # kv chains need --kv
./appchain-cluster/cluster.sh clean          # stop + wipe
```

Highlights (full reference: `appchain-cluster/README.md`):

- **Anchoring demo**: `start 3 --anchor` (script mode) then
  `anchor-bootstrap orders-chain` — funds via the devnet faucet and mints the
  thread NFT. `--anchor-mode metadata` is the simplest possible anchoring.
- **Public networks**: `start 3 --network preprod --anchor-mode script
  --anchor-key $(openssl rand -hex 32)` — every node relays preprod, and you
  fund the printed wallet address yourself (§5.1). The launcher refuses the
  built-in demo anchor key on public networks (it is publicly known).
- Chains come from `config/application-appchain.yml` — add/remove chains
  there and the launcher auto-discovers them; it injects the per-node keys,
  members, threshold and peers, so the same file runs at any cluster size.
- Works against the jar or the native binary (auto-detected), and from a
  source checkout (`app/appchain-cluster/`) or any unzipped release tree.

---

## 4. REST API

Base path: `<artifact-api-prefix>/app-chain` (default
`/api/v1/app-chain`). The prefix is fixed into each JVM/native/container
artifact with strict `-PyanoApiPrefix=<path>`; it is not editable launch
configuration. `/` and canonical paths up to 256 characters are supported.
Changing it requires a rebuild; see
[`BUILD_DISTRIBUTIONS.md`](BUILD_DISTRIBUTIONS.md#artifact-api-prefix).

Every chain endpoint below is also available **chain-scoped** as
`/app-chain/chains/{chainId}/...` on a multi-chain node (section 8). The
chain-less form keeps working while exactly one chain is configured; with
several chains it returns `400` (ambiguous), and `503` when no chain is
enabled. When API-key auth is on (section 12), every request needs `X-API-Key`.

**Swagger UI** (`/q/swagger-ui/`) documents the chain-scoped surface only —
the chain-less aliases are deliberately hidden from the OpenAPI document
(they can only answer 400 on a multi-chain node). Prefer the
`chains/{chainId}` paths in new integrations; the chain-less forms remain as
a single-chain convenience.

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

Anchoring periodically commits the app chain's position — height, block hash
and **state root** — onto Cardano, through the node's own mempool and tx
diffusion, confirmed by the node's own L1 sync. Two modes
(`anchor.mode: metadata | script`):

| | `metadata` (default) | `script` (ADR 008.4) |
|---|---|---|
| Anchor tx | plain tx, anchor payload in tx metadata | Plutus V3 **thread-NFT** UTxO; a validator enforces the datum chain on-chain |
| What L1 enforces | nothing (data-only commitment) | monotonic height, stable chain-id, **m-of-n member signatures** on every advance |
| Signing | anchor wallet only | wallet + threshold co-signed member witnesses |
| Setup | fund the wallet — done | fund + one-time `admin/anchor/bootstrap` per chain |
| Cost | ~0.17–0.2 ADA per anchor | ~0.35 ADA per anchor (script execution) |

Both modes work on the devnet and on public networks (script anchors are
proven on preprod: real Plutus V3 mint + validator-enforced co-signed
advances).

### 5.1 The anchor wallet (both modes)

The anchor wallet is a raw **32-byte Ed25519 seed** (`anchor.signing-key`,
hex); the node derives its **enterprise address** and logs it at startup
(`anchor wallet address: addr...`) — it is also in `/status` under
`anchor.walletAddress`. **Fund that address**; on devnet use
`POST /api/v1/devnet/fund {"address": "...", "ada": 100}`, on a testnet the
[official faucet](https://docs.cardano.org/cardano-testnets/tools/faucet)
can send straight to it.

- A CIP-1852 wallet mnemonic (Eternl/Lace/devkit) can **not** be converted
  into this seed — HD payment keys are extended keys, not seeds. Generate a
  dedicated seed (`openssl rand -hex 32`) and fund its address instead. This
  is good ops hygiene anyway: the key sits in plain config on the node box,
  so treat it as a hot wallet holding fee money only.
- Only the anchor **leader** needs `anchor.*` config (typically node 0 / the
  sequencer host). Script-mode members co-sign and adopt the on-chain
  identity with **zero anchor config** — they verify each advance against
  their own ledger and L1 view before signing.

**The anchor leader role, precisely.** The leader is the node that DRIVES
anchoring: it watches finalized progress, builds the anchor tx when
`every-blocks` accumulate, pays fees/collateral from the anchor wallet,
submits through the node's own tx path and tracks L1 confirmation. It is a
different axis from the sequencer/proposer (which orders app blocks and MAY
rotate) — anchor leadership is fixed to the `anchor.enabled` node and does
not rotate, because there is exactly one thread UTxO to spend and one wallet
paying fees (concurrent leaders would merely race on the same UTxO).

It is **not a trust point** in script mode: every advance needs `threshold`
member co-signatures, each member verifies the proposed range against its
OWN ledger and L1 view before signing, and the on-chain validator re-enforces
the member threshold and monotonic height independently. The worst a dead or
compromised leader can do is *stop anchoring* — a liveness issue, never a
safety one: the app chain keeps finalizing and `lagBlocks` climbs visibly.
Recovery is operational: enable `anchor.*` (with the wallet key) on another
member and restart it — the on-chain identity is persisted on L1 and members
adopt it from sign requests, so the new leader resumes where the old one
stopped. (Metadata mode is the same minus co-signing; its commitment is
data-only, so the leader alone signs — the trust statement is accordingly
weaker.)

### 5.2 Metadata anchors

```yaml
yano:
  app-chain:
    anchor:
      enabled: true
      mode: metadata                                    # default
      signing-key: "<anchor wallet Ed25519 seed hex>"   # separate from member keys
      every-blocks: 10                                  # anchor cadence
      max-interval-minutes: 60
      metadata-label: 7014
    l1:
      stability-depth: 36        # L1 depth of the l1-ref carried in app blocks
```

Commits `[chain-id, from-height, to-height, block-hash, state-root]` as tx
metadata (label `7014` by default). Anchors start automatically once the
wallet holds funds. Unconfirmed anchors are resubmitted; an L1 rollback that
undoes an anchor puts it back in pending automatically.

### 5.3 Script anchors (thread NFT + on-chain validator)

```yaml
yano:
  app-chain:
    anchor:
      enabled: true
      mode: script
      signing-key: "<anchor wallet Ed25519 seed hex>"
      every-blocks: 30
      # script.validator / script.thread-policy default to the bundled julc
      # artifacts; an Aiken twin ships in-repo (same ABI, interchangeable):
      #   script:
      #     validator: file:/path/to/anchor-validator.plutus.json
      #     thread-policy: file:/path/to/thread-policy.plutus.json
    l1:
      stability-depth: 36
```

One-time **bootstrap** per chain (leader only, wallet must be funded):

```bash
curl -X POST localhost:7070/api/v1/app-chain/chains/<chain-id>/admin/anchor/bootstrap
```

The bootstrap consumes a seed UTxO from the wallet and mints a **one-shot
thread NFT** into the anchor validator's script address with the genesis
datum. That mint defines the chain's permanent on-chain identity:

- **thread policy id** — unique per chain (derived from the consumed seed
  UTxO; can never be minted again). Burning is forbidden by the policy.
- **script address** — the validator is parameterized by the policy id, so
  every chain gets its own address.
- The NFT's **asset name is the chain-id** (UTF-8, ≤ 32 bytes), so explorers
  like Cardanoscan show a readable label. Identity and uniqueness come from
  the policy id, never the name.

From then on the leader runs threshold **co-sign rounds**: it builds the
advance tx (spending the thread UTxO, writing the next datum), members verify
the proposed range against their OWN ledger + L1 view and return Ed25519
witnesses, and at `threshold` signatures the tx is submitted. The on-chain
validator independently enforces: exactly one continuing thread output,
monotonic height, unchanged chain-id, ≥ threshold signatures of the datum's
member set, and no value drain. Membership changes (§14.2) flow into the
datum, so the on-chain member set tracks the chain's governance.

Watch progress in `/status` under `anchor` (`bootstrapped`, `threadPolicyId`,
`scriptAddress`, `walletAddress`, `cosignPending`, `lastAnchorTx`,
`lagBlocks`), or on the `/ui/app-chain/` page's L1 Anchor card. Anchors fire
when at least one NEW block exists and `every-blocks` accumulated since the
last anchor (or `max-interval-minutes` elapsed) — an idle chain anchors
nothing and costs nothing.

### 5.4 Independent verification (auditors, third parties)

The design goal: **prove a record's inclusion without trusting any Yano
node.** What lives on L1 (readable from any Cardano source — an explorer,
Koios, db-sync, your own node): the thread UTxO's inline datum with
`(version, chain-id, height, block-hash, state-root, member-keys,
threshold)`, and the validator-enforced chain of every advance back to the
one-shot mint.

**Any message (e.g. an order on an ordered-log chain):**

1. `GET /api/v1/app-chain/chains/{chainId}/evidence/{messageIdHex}` — a
   self-contained **evidence bundle**: the message's block, every block up to
   the next anchored one, finality-cert signatures, member set, anchor tx ref.
2. Verify **offline** with `EvidenceVerifier.verify(bundle)` from
   `yano-core-api` (no node needed). It checks: message ∈ block via the
   recomputed messages-root; prev-hash chain intact; every block carries
   ≥ threshold valid member signatures (m-of-n — unforgeable by one member);
   the last block hash equals the anchored block hash.
3. Close the loop on L1: confirm the bundle's anchor tx exists on Cardano and
   that the bundle's **member set + threshold match the on-chain datum**
   (take those from L1, not from the bundle).

**Any state entry (e.g. a kv-registry key) against the anchored root:**

`GET /api/v1/app-chain/chains/{chainId}/proof/{keyHex}` returns an **MPF
inclusion proof** verifiable against the `state-root` in the L1 datum with
any independent MPF implementation (the `vds-mpf` Java library or Aiken's
`merkle-patricia-forestry` — same construction, so on-chain verification in
a validator is also possible).

### 5.5 L1 observations (reacting to L1 events)

The reverse direction — the app chain consuming L1 facts — is handled by
**observers** (ADR 008.4 §3): each member watches its own L1 view, and an
observed fact is injected as a framework message on the reserved
`~l1/<observer-id>` topic only after it is `l1.stability-depth` blocks deep.
Followers re-derive the claim from their OWN L1 stream before accepting it,
so a compromised proposer cannot fabricate L1 facts.

```yaml
yano:
  app-chain:
    l1:
      stability-depth: 36          # observers REQUIRE stability-depth > 0
    observers:
      deposits:
        type: address-deposit      # built-in: watch an address for deposits
        address: "addr_test1..."
      papers:
        type: metadata-label       # built-in: watch a tx metadata label
        label: "20250712"
```

Observer config must be **identical on every member** (it is
consensus-critical). Custom observers implement `L1ObserverProvider`
(ServiceLoader, same plugin pattern as state machines). Read observations
like any messages: `GET .../messages/by-topic/~l1%2Fdeposits`.

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
2. Add the ServiceLoader entry to your jar:
   `META-INF/services/com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider`
   containing the provider class name.
3. Add the bundle manifest
   `META-INF/yano/plugins/<bundle-id>.json`; its contribution kind, name and
   provider class must exactly match the ServiceLoader entry. Package any
   non-host runtime dependencies into the same reproducible bundle JAR; do not
   deploy adjacent thin dependency JARs as one catalog-v1 bundle.
4. Drop the jar into the JVM node's plugins directory (`yaci.plugins.directory`,
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

ServiceLoader-only legacy providers remain a temporary compatibility path for
self-contained JARs loaded from the JVM plugin directory (and for explicit
library compatibility mode). Packaged JVM/native build-time inclusion requires
the bundle manifest: strict index generation cannot safely assign an
unmanifested provider's external dependencies to a bundle closure and tells
the developer to add a manifest or use the JVM directory bundle.

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
| `transport.mode` | `shared` | Outbound app transport (node-global). `shared`: when the L1 upstream is also an app-group peer, protocols 100/103 ride its session — one TCP connection per peer pair, with an automatic dedicated-dial fallback if the session stays down (~15s grace). `dedicated`: always dial a separate app connection (bandwidth isolation from L1 sync). Peers that are not the upstream always use dedicated dials; inbound is unaffected (the server port multiplexes both since v1). Per-peer transport is visible in `status` under `peerTransports` |
| `block.interval-ms` | `2000` | Proposer tick (blocks are only made when messages are pending) |
| `block.max-bytes` | `4194304` (4 MiB) | Primary block-size cap: the proposer trims each block to fit the serialized size; members reject oversized proposals. Tune for throughput |
| `block.max-messages` | `5000` | Safety backstop against tiny-message floods (the byte cap above is the primary limit) |
| `state-machine` | `ordered-log` | Built-in id, a stdlib id (§9) or a plugin provider id |
| `max-message-bytes` | `65536` | Max opaque body size |
| `max-ttl-seconds` | `3600` | Max accepted message TTL |
| `default-ttl-seconds` | `600` | TTL applied to REST submissions |
| `anchor.enabled` | `false` | L1 anchoring on this node (the anchor leader; script-mode members need NO anchor config) |
| `anchor.mode` | `metadata` | `metadata` (data-only tx) or `script` (thread-NFT + on-chain validator, §5.3) |
| `anchor.signing-key` | — | Anchor wallet seed, hex, 32 bytes (required if anchoring; §5.1) |
| `anchor.every-blocks` | `10` | Anchor after this many new app blocks |
| `anchor.max-interval-minutes` | `60` | Anchor at least this often while blocks pend |
| `anchor.metadata-label` | `7014` | Metadata label of anchor txs (metadata mode) |
| `anchor.script.validator` | `builtin:julc` | Script mode: anchor validator artifact — `builtin:julc`, `file:/path` (blueprint or raw hex) or `hex:...` |
| `anchor.script.thread-policy` | `builtin:julc` | Script mode: thread-policy artifact (same forms) |
| `observers.<id>.type` | — | L1 observer instance (§5.5): `address-deposit`, `metadata-label`, or a plugin provider id. Further `observers.<id>.*` keys are provider settings (e.g. `address`, `label`). Must be identical on all members; requires `l1.stability-depth > 0` |
| `anchor.validity-slots` | `7200` | Anchor tx TTL = current L1 slot + this; a resubmitted anchor can never race a late-landing original |
| `anchor.fallback-fee-lovelace` | `300000` | Anchor tx fee when protocol parameters are unavailable; normally the fee is computed from the node's current params by tx size |
| `l1.stability-depth` | `0` | Depth of the stable L1 reference in app blocks (0 = off). When > 0, followers verify each proposal's L1 ref against their **own** L1 view (monotonic, hash-matched at depth; brief proposer lead is retried, a fabricated ref is rejected fail-closed) and the node refuses to start without an L1 event feed |
| `pool.max-messages` | `10000` | Pending-pool capacity; a full pool returns 429 on submit and drops (counted) inbound gossip (§4) |
| `message.enforce-sender-seq` | `false` | Consensus-visible sender-seq rule: followers reject blocks with stale/duplicate per-sender seqs (§4). Must match on all members |
| `effects.enabled` | `false` | Enable the effect system (§18). **Consensus-affecting — must match on all members**; also all `effects.*` caps and gate/commitment keys below |
| `effects.max-per-block` / `max-payload-bytes` / `max-expiry-blocks` / `result-window-blocks` | `256` / `16384` / `100000` / `100000` | Deterministic emission caps and result-incorporation window (§18.1); payload bytes are capped at 16 MiB—use payload-by-hash for larger bodies |
| `effects.default-gate` | `app-final` | `app-final` \| `l1-anchored` \| `zk-settled` — when emitted effects become executable (§18.4) |
| `effects.outcome-commitment` | `per-effect` | `per-effect` (O(1) proofs) \| `per-block` (trie growth O(effectful blocks); ZK-friendly) |
| `effects.strict-reserved-prefix` | `true` | Reject app writes to the `~fx/` trie prefix (consensus-affecting; active even when effects are off) |
| `effects.result.signers` | empty | Restrict who may attest `~fx/result` to these member keys (default: any member; §18.5) |
| `effects.executor.enabled` | `false` | This node RUNS effects (execution plane, §18.3). Node-local — not consensus. When explicitly enabled, invalid settings, missing executors or runtime initialization failure make startup fail; the node never silently drops this role |
| `effects.executor.types` / `tick-ms` / `max-parallel` / `max-attempts` / `backoff-initial-ms` / `backoff-max-ms` | ` ` / `2000` / `4` / `8` / `2000` / `300000` | Executor tuning (§18.3) |
| `effects.executor.identity` | generated node-local sidecar | Stable identity for this node's disposable execution progress. The generated file lives beside the checkpointed chain directory, so member-key rotation preserves work but restoring onto another executor resets/quarantines it. Set an explicit unique value when storage is relocated; never clone it across physical executors (§18.3) |
| `effects.external.enabled` | `false` | Expose the external-executor claim/report REST surface (§18.6) |
| `effects.executors.<scheme>.*` | — | Executor plugin config (built-in `webhook`; `cardano` via the plugin jar) — passed to the executor with the prefix stripped (§18.3) |
| `effects.retention.keep-blocks` | `100000` | Prune resolved effect records older than this behind the tip |
| `machines.approvals.payments` (+ `payment-type` / `payment-gate` / `payment-expiry-blocks`) | `false` | Stdlib approvals→payment effect (§18.3); also requires `machines.approvals.activations.payments=<height>` |

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
`api.keys` (§12), `retention.*` (§13), `zk.*` (§17), `effects.*` (§18).

Current v1 `effects.*` consensus settings—including `result.signers`, caps,
commitment mode and root/record algorithms—are immutable after chain launch.
Machine activation is implemented, but framework-level effect-setting epochs
from ADR-010.1 D5 are still pending; changing these values on an existing
history can make replay diverge.

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
encoders are documented on the classes in `appchain/appchain-stdlib` and,
field by field, in the
[consensus & internals guide §10](APP_CHAIN_CONSENSUS_GUIDE.md). All
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

**Kafka** — the stock application omits this T3 integration. For a JVM node,
run `./gradlew :appchain-kafka-sink:shadowJar` and copy the resulting
`yano-appchain-kafka-sink-<version>-bundle.jar` into
`yaci.plugins.directory`. For a native application, build it in with
`-PincludeFirstPartyPluginBundles=true`; native binaries cannot load the
directory bundle.

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

Sinks *observe* finalized state. To *act on the outside world* from a finalized
transition (submit a payment, call an API) with a result recorded back
on-chain, use **effects** (section 18) — the outbound counterpart to sinks.

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

Effects add `yano_appchain_effects_open`, `queue_depth`, `in_flight`,
`runtime_status{status}`, `result_backlog`,
`result_backlog_by_type{type}`, `oldest_pending_age_blocks`, and
`oldest_pending_age` gauges; `execution_total{outcome}` and `expired_total`
counters; and `execution_latency_seconds{type}`. Status/outcome tags are fixed.
Type tags come only from the bounded `effects.metrics.types` allowlist (plus
`other`), never from arbitrary workload values. Gauges come from a memoized,
non-truncated operational scan and may span a concurrent transition; counters
and timers are normalized to remain monotonic for the life of the node process.

A ready-made Grafana dashboard ships at `docs/grafana/appchain-dashboard.json`.

**Status page**: a built-in dashboard is served at **`/ui/app-chain/`**
(sibling of the L1 node page at `/ui/status/`, cross-linked): chain selector
(multi-chain), role/health pills, tip + membership hero, consensus/pool/
traffic/anchor/sinks/peers panels, four trend charts, a live SSE message feed
and a recent-blocks table. Query params: `?api=` (API prefix), `?poll=` (ms),
`?noanim=1`. When API-key auth is enabled, set the key via the key button
(stored in the browser; the live feed uses fetch-streaming so the key applies
there too). The `?api=` value only tells this app-chain page where to call; it
does not reconfigure server routing and must match the prefix baked into the
artifact. The privileged plugin dashboard at `/ui/plugins/` does not accept
such an override: it uses immutable `/ui/plugins/api-prefix.json` and fails
closed if discovery is missing or invalid. See
[`PLUGIN_OPERATIONS.md`](PLUGIN_OPERATIONS.md).

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
ZeroJ). The stock application omits it. A JVM node can deploy the self-contained
`yano-appchain-zk-<version>-bundle.jar` produced by
`:appchain-zk:shadowJar`; no adjacent ZeroJ JARs are needed or accepted as the
same catalog-v1 bundle. Native builds opt in before catalog/reflection
generation with `-PincludeFirstPartyPluginBundles=true`. The node only
**verifies** proofs — proving happens
client-side, where the secrets live. Circuits are
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

## 18. Effects — acting on the outside world

Everything so far keeps state *inside* the chain. **Effects** (ADR-010) let a
finalized state transition trigger an action *outside* it — submit a Cardano
payment, call an ERP/webhook, pin to IPFS, issue a credential — without
breaking determinism.

The rule that makes it safe: **a state machine never performs the action, it
emits a record describing it.** Emission is part of deterministic `apply()`,
identical on every member and transitively committed through the count-bound
`effectsRoot` leaf into the state root. A separate
**Effect Runtime** (outside consensus, on one designated node or an external
worker) executes finalized effects, and the outcome comes back as an ordinary
sequenced message that the machine records. The guarantee is **exactly-once
incorporation, at-least-once execution** — the external action may run more
than once (so executors must be idempotent), but its outcome lands in chain
state exactly once.

```
apply()  ── emit ──▶  effect record (outbox + effectsRoot, provable, anchored)
                          │  finalized
                          ▼
                 Effect Runtime ── execute ──▶  external system
                          │
        ~fx/result (member-signed, sequenced) ──▶ apply() records the outcome
```

### 18.1 Enable effects

Consensus-affecting — **every member must set the same values** (a mismatch
diverges the state root, exactly like a mismatched state machine). Effects are
off by default.

```yaml
yano.app-chain.effects.enabled: true
# Deterministic caps (consensus parameters):
yano.app-chain.effects.max-per-block: 256          # effects one block may emit
yano.app-chain.effects.max-payload-bytes: 16384    # per-effect payload cap
yano.app-chain.effects.max-expiry-blocks: 100000
yano.app-chain.effects.result-window-blocks: 100000 # results incorporable within this window
# Where an effect's outcome is committed in the trie:
yano.app-chain.effects.outcome-commitment: per-effect  # per-effect | per-block
# Default finality gate for emitted effects (see §18.4):
yano.app-chain.effects.default-gate: app-final     # app-final | l1-anchored | zk-settled
```

Enabling effects reserves the `~fx/` key prefix in the state trie (a state
machine may not write keys starting with `~fx/`). This reservation holds from
genesis regardless of the flag, so effects can be turned on later without
colliding with historical state.

### 18.2 Emit effects from a state machine

Override the three-argument `apply` and call `effects.emit(...)`. The emitter
records intent — it performs no I/O, and everything forbidden in `apply()`
(wall clock, randomness, network) stays forbidden.

```java
public class OrderStateMachine implements AppStateMachine {
    @Override public String id() { return "orders"; }

    @Override
    public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
        for (AppMessage m : block.messages()) {
            Order o = decode(m.getBody());
            writer.put(key(o.id()), o.toBytes());          // ordinary state
            if (o.isApproved()) {
                effects.emit(EffectIntent.of("webhook.post", o.fulfilmentJson())
                        .scope("orders/" + o.id())         // app-level idempotency scope
                        .result(ResultPolicy.CHAIN)        // outcome comes back on-chain
                        .gate(FinalityGate.CHAIN_DEFAULT)  // use the chain's default gate
                        .expiryBlocks(1000)                // deterministic timeout (see §18.4)
                        .sourceMessageId(m.getMessageId())
                        .build());
            }
        }
    }

    // Called deterministically when a CHAIN effect's outcome is incorporated.
    @Override
    public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
        if (!result.scope().startsWith("orders/")) return;
        String id = result.scope().substring("orders/".length());
        // result.confirmed(), result.outcome() (CONFIRMED/FAILED/CANCELLED/EXPIRED),
        // result.externalRef() (e.g. a tx hash) — committed transitively by the
        // configured outcome root; values written here also enter application state.
        writer.put(fulfilledKey(id), result.externalRef());
    }
}
```

`EffectIntent` fields: **type** (routes to an executor), **payload** (opaque
bytes — never put secrets or PII here; records are replicated and committed),
**scope** (your idempotency handle), **gate** (§18.4), **result**
(`CHAIN` = outcome fed back and delivered to `onEffectResult`; `NONE` =
fire-and-forget, operator-visible only), **expiryBlocks**. Effect ids are
deterministic — `chainId/height/ordinal`, hashed into the idempotency key
handed to every external system.

> **Changing emission logic on a live chain is a hard fork** unless gated. Ship
> emission changes behind a height-activated flag
> (`ActivationSchedule`, ADR-010.1): `yano.app-chain.machines.<id>.activations.<name>=<height>`.
> Verify with the conformance harness's upgrade replay-matrix
> (`StateMachineConformance.upgrade(old, new)`).

### 18.3 Run an executor

The Effect Runtime is **off by default** — turn it on where you want effects
to actually run (typically one node, or a dedicated executor node). Everywhere
else, effects are still emitted and provable; they just wait.

```yaml
yano.app-chain.effects.executor.enabled: true      # this node executes effects
yano.app-chain.effects.executor.types: ""          # empty = all; or "cardano.payment,webhook.post"
# Optional stable override; must be unique per physical executor and survive key rotation:
# yano.app-chain.effects.executor.identity: payments-executor-sg-1
yano.app-chain.effects.executor.tick-ms: 2000
yano.app-chain.effects.executor.max-parallel: 4
yano.app-chain.effects.executor.max-attempts: 8    # then PARKED for operator review
yano.app-chain.effects.executor.backoff-initial-ms: 2000
yano.app-chain.effects.executor.backoff-max-ms: 300000
yano.app-chain.effects.metrics.types: "cardano.payment,webhook.post" # max 32; empty = aggregate only
yano.app-chain.effects.retention.keep-blocks: 100000   # prune resolved effect records
```

Run it on **exactly one node** (or partition types across nodes with
`executor.types`) — the runtime provides no cross-node mutual exclusion by
design; overlap degrades to duplicate *attempts*, which idempotency absorbs.
A late-enabled executor **quarantines** historical open effects rather than
blind-firing them; requeue via REST. Failed effects retry with backoff, then
**park** (never block other effects) for an operator to requeue or cancel.

Runtime attempts, leases, submitted references and the intake cursor are bound
to a fixed-size canonical fingerprint of the executor identity and sorted type
partition. Moving a checkpoint to a different executor, changing
`executor.identity`, changing `executor.types`, or upgrading a legacy
checkpoint with no owner binding discards only that node-local runtime state
and quarantines every historical open effect. Consensus effect records,
closures and state roots are preserved. Review and explicitly requeue each
obligation; an already-submitted external action may otherwise be attempted
again, so executor-side idempotency remains mandatory.

**Built-in `webhook.post` executor** — POSTs the payload with an
`Idempotency-Key` header (the effect's id hash); the target URL lives in
config, not the payload:

```yaml
yano.app-chain.effects.executors.webhook.url: https://erp.example/hooks/yano
yano.app-chain.effects.executors.webhook.timeout-ms: 10000
```

**Cardano payment executor** — the stock application omits this privileged T3
integration. A JVM node can run `:appchain-effects-cardano:shadowJar` and copy
`yano-appchain-effects-cardano-<version>-bundle.jar` into the plugins
directory. Native builds opt in with
`-PincludeFirstPartyPluginBundles=true`. It handles `cardano.payment` effects
with a
`{"to": <bech32>, "lovelace": <n>, "memo"?: <str>}` payload, stamps the effect
id into tx metadata, and confirms by tx hash. Secrets live here, never in
payloads:

```yaml
yano.app-chain.effects.executors.cardano.backend-url: http://localhost:8080/api/v1/    # Blockfrost-compatible
yano.app-chain.effects.executors.cardano.signing-mnemonic: "<payer wallet mnemonic>"   # or signing-account-key
yano.app-chain.effects.executors.cardano.network: preprod                              # mainnet|preprod|preview
yano.app-chain.effects.executors.cardano.metadata-label: 21042
yano.app-chain.effects.executors.cardano.max-lovelace-per-tx: 500000000               # blast-radius cap (0 = uncapped)
```

> **Fund the payer wallet conservatively** and set `max-lovelace-per-tx`: a
> buggy or compromised machine that emits an oversized payment cannot then
> drain the hot wallet in one tx.

**Stdlib approvals → payment** — no code: the `approvals` machine emits a
`cardano.payment` on final approval, and records `STATUS_PAID` with the tx hash
when it confirms.

```yaml
yano.app-chain.state-machine: approvals
yano.app-chain.machines.approvals.payments: true
yano.app-chain.machines.approvals.activations.payments: 1       # new chain: active from genesis
yano.app-chain.machines.approvals.payment-type: cardano.payment
yano.app-chain.machines.approvals.payment-gate: l1-anchored     # provable before funds move
yano.app-chain.machines.approvals.payment-expiry-blocks: 1000
# Live chain: replace 1 with a future height deployed identically to every member (ADR-010.1).
```

> **Legacy migration:** if this chain already ran a binary where
> `payments=true` worked without an activation key, first deploy
> `activations.payments=1` identically to every member, then deploy this binary
> and validate full replay or snapshot restoration. Do **not** choose a future
> height for that migration: doing so would change historical payload parking
> and emissions. A future height is only for enabling payments for the first
> time on a live chain.

### 18.4 Finality gates and expiry

A **gate** decides when an emitted effect becomes eligible to execute:

- **`app-final`** — as soon as the block is committed (the chain is
  append-only after finality, so the emission is already irrevocable).
- **`l1-anchored`** — once the effect's height is covered by an L1-confirmed,
  stability-deep anchor (the emission is provable against Cardano before you
  act). A verifiability delay, not a rollback safeguard.
- **`zk-settled`** — once covered by an accepted validity proof (reserved for
  the ZK settlement roadmap, §17; waits until expiry on non-ZK chains).

`l1-anchored` needs anchoring enabled (§5) and `l1.stability-depth` set.
`effects.gate.anchor-margin-blocks` adds a safety margin above the anchor
high-water-mark.

**Expiry is mandatory for CHAIN effects** — a result arriving after the result
window is a deterministic no-op, so every CHAIN effect must provably close. If
you pass `expiryBlocks(0)` the framework defaults it to the result window. When
the expiry height passes with no incorporated result, the effect deterministically
becomes **EXPIRED** (delivered to `onEffectResult`) — the "nobody answered in
time" escape hatch, distinct from **FAILED** ("the target answered no").

### 18.5 The result path and trust

Executed `CHAIN` outcomes re-enter as member-signed `~fx/result` messages,
sequenced like any other. The framework interpreter is fail-closed and
first-result-wins: duplicate, late, malformed, unknown, or out-of-window
results are deterministic no-ops — **a result can never stall the chain.** A
result is a *member attestation*, not an independently verified fact (followers
check the signature and membership, not the external world). Narrow who may
attest with a designated-signer list:

```yaml
# Only these member keys' ~fx/result messages are accepted (default: any member):
yano.app-chain.effects.result.signers: "<hex pubkey of the executor node>"
```

For L1-visible facts (a payment landing), prefer verifying via an L1 observer
(§ config `observers`) over trusting an attestation. `k`-of-`n` result
attestation for high-value effects is designed, not yet shipped.

### 18.6 External executors (claim / report)

To run execution *outside* Yano — a different team, language, or a worker that
holds secrets Yano shouldn't — enable external mode and drive it over REST or
the Java SDK. The node leases effects to a named worker; the worker executes
and reports the outcome, which the node re-signs into a `~fx/result`.

```yaml
yano.app-chain.effects.executor.enabled: true
yano.app-chain.effects.external.enabled: true
```

```java
AppChainClient client = ...;
JsonNode claimed = client.claimEffects("worker-1", List.of("cardano.payment"), 16, 60);
for (JsonNode e : claimed.get("effects")) {
    long height = e.get("height").asLong();
    int ordinal = e.get("ordinal").asInt();
    byte[] idKey = HexUtil.decodeHexString(e.get("idempotencyKey").asText());
    byte[] txHash = executeExternally(e.get("payloadHex").asText(), idKey);  // pass idKey to the target
    client.reportEffect("worker-1", height, ordinal, /*success*/ true, txHash, null);
}
```

Leases are wall-clock and node-local; an expired lease re-opens the effect for
failover. Reports are fenced to the lease holder.

> **Security**: `requeue`/`cancel`/`claim`/`report` move funds or change
> consensus-visible state — they are **privileged**: a submit-only
> (topic-restricted) API key may not call them; only a full key can (§12).
> Enable `yano.app-chain.api.auth` and keep the executor/operator REST surface
> on a trusted network — external mode logs a warning if auth is off.

### 18.7 Write a custom executor

Any effect type your machines emit can be handled by a plugin implementing two
interfaces — the same ServiceLoader pattern as custom sinks (§10):

```java
public class IpfsPinExecutor implements AppEffectExecutor {
    private final String apiUrl;
    IpfsPinExecutor(Map<String, String> config) { this.apiUrl = config.get("api-url"); }

    @Override public String id() { return "ipfs"; }
    @Override public boolean supports(String type) { return "ipfs.pin".equals(type); }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) throws Exception {
        String cid = decodeCid(effect.payload());
        // MUST be idempotent on effect.idHash() — the same effect may re-run.
        // ctx.submittedRef() is non-empty on a re-poll of a prior Submitted;
        // probe by it instead of acting again.
        pin(cid, effect.idHash());
        return EffectExecution.confirmed(cid.getBytes());        // or .failed(reason, retryable),
                                                                 // .submitted(ref), .retry(duration)
    }
}

public class IpfsExecutorFactory implements AppEffectExecutorFactory {
    @Override public String scheme() { return "ipfs"; }         // config ns: effects.executors.ipfs.*
    @Override public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        return config.containsKey("api-url")
                ? List.of(new IpfsPinExecutor(config)) : List.of();
    }
}
```

Register it via
`META-INF/services/com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory`
(one line: the factory's fully-qualified name), and add
`META-INF/yano/plugins/com.example.ipfs.json`:

```json
{
  "schemaVersion": 1,
  "id": "com.example.ipfs",
  "version": "1.0.0",
  "yanoApi": { "min": 1, "max": 1, "minLevel": 1 },
  "dependencies": [],
  "contributions": [
    {
      "kind": "effect-executor",
      "name": "ipfs",
      "provider": "com.example.ipfs.IpfsExecutorFactory"
    }
  ]
}
```

The manifest name must equal its bundle id, and its contribution must match
the ServiceLoader entry and the factory's `scheme()` exactly. For JVM
directory deployment, package the provider and all non-host runtime
dependencies in one self-contained bundle JAR, drop that JAR in the plugin
directory, and configure
`yano.app-chain.effects.executors.ipfs.api-url=...`. Adjacent thin dependency
JARs are separate catalog artifacts, not one bundle. A native deployment must
include and map the manifested bundle at application build time; an existing
native executable cannot load it from the plugin directory.

The framework supplies discovery, finality gating, retries, backoff, the
poison lane, and the result loop — you implement only the one attempt.
Contract: **idempotent on `idHash`**, secrets from config not payloads,
`Confirmed`/`Failed` are definitive, `Submitted` re-polls, throwing means
retry.

Factory schemes are exact and case-sensitive. Sink/effect schemes cannot
contain `.` because the first dot separates the scheme from its configuration
key; signer schemes cannot contain `:` because it separates the key reference.

### 18.8 REST surface

Chain-scoped under `/app-chain/chains/{chainId}/...` (single-chain alias
`/app-chain/...`):

| Endpoint | Purpose |
|---|---|
| `GET effects?fromHeight=&limit=` | emitted effect records (consensus view) |
| `GET effects/{height}/{ordinal}` | one effect + this node's execution status |
| `GET effects/{height}/{ordinal}/proof` | composed record → effects root → historical state-root proof |
| `GET effects/stats` | non-truncated memoized backlog/status/oldest-age plus execution, expiry and latency totals |
| `POST effects/{height}/{ordinal}/requeue` | operator: PARKED/QUARANTINED → PENDING |
| `POST effects/{height}/{ordinal}/cancel?reason=` | operator: cancel an open CHAIN effect |
| `POST effects/claim` | external executor: lease effects |
| `POST effects/{height}/{ordinal}/report` | external executor: report an outcome |

The proof endpoint returns the canonical record CBOR, its ordered Merkle path,
and an MPF proof of `~fx/root/<height>` against that block's historical state
root. Verify it locally with `EffectProofVerifier`, preferably supplying a
state root independently obtained from the certified block at exactly that
emission height:

```java
var lookup = client.effectProof(height, ordinal);
if (lookup.available()) {
    boolean valid = EffectProofVerifier.verifyFor(
            lookup.proof(), stateRootAtEmissionHeight, chainId, height, ordinal);
}
```

An L1 anchor root can be supplied directly only when it anchors that exact
height. For a later anchor, authenticate block H through its threshold
certificate and the certified hash chain to the anchored descendant first;
the MPF proof itself is deliberately against `stateRoot(H)`.

`404` means no such effect/ordinal according to the retained index. `410
EFFECT_PROOF_PRUNED` is an availability signal from this node: its compact
metadata says one or more records needed to reconstruct the path passed
`effects.retention.keep-blocks`. A 410 is not itself a portable proof that the
ordinal existed; only a successfully verified composed proof establishes that.
Archive proofs before that horizon when long-lived portable evidence is
required; a proof already obtained remains verifiable forever.

---

## 19. Troubleshooting

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

## 20. Current limitations

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
