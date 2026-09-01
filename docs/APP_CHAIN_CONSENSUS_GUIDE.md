# Yano App Chain — Consensus & Internals Guide

How the app chain works end to end, at the level a developer needs to extend
it, debug it, or audit it. Companion docs are the
[core app-chain guide](appchain/README.md), the
[plugin query/domain API contract](APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md),
and the core ADRs under `adr/app-layer/`. Extension tutorials and product ADRs
live in [Yano X](https://github.com/bloxbean/yano-x).

Code pointers use class names — start at
`runtime/.../appchain/AppChainEngine.java` (consensus core),
`core-api/.../appchain/` (SPI + codecs), and `runtime/.../OrderedLog.java`
(the one built-in state machine).

---

## 1. Architecture map

```
                    one Yano node
┌───────────────────────────────────────────────────────┐
│  Cardano L1 (sync / relay / block production)         │
│      │  L1 events: blocks, slots, rollbacks           │
│      ▼                                                │
│  AppChainManager  ── one per node ────────────────────│
│   │  shared INBOUND transport (appmsg, protocol 100)  │
│   │  shared catch-up server  (protocol 103)           │
│   │  per-chain dispatch by chain-id                   │
│   ▼                                                   │
│  AppChainSubsystem ── one per chain ──────────────────│
│   ├─ message pool (admission, backpressure)           │
│   ├─ AppChainEngine (consensus, single-threaded)      │
│   ├─ AppLedgerStore (RocksDB: blocks, MPF, indexes)   │
│   ├─ AppStateMachine (your application logic)         │
│   ├─ MemberGroup (static or governed membership)      │
│   └─ anchor services, observers, sinks (optional)     │
└───────────────────────────────────────────────────────┘
        ▲ outbound app-peer connections: per chain
          (transport.mode=shared rides the L1 upstream session — one TCP
           connection per peer pair — with dedicated dials as fallback)
```

- **One inbound front, many chains.** All app-message agents share protocol
  id 100, so a session cannot carry one agent per chain. The manager installs
  ONE gossip agent scoped to the union of hosted chain ids and dispatches
  every verified envelope to the owning chain. Outbound connections are
  per-chain (each chain has its own peer list). The union transport is
  permissive (max of all chains' size/TTL limits); each chain's own limits
  are re-applied per message — with one carve-out: bodies on reserved `~`
  topics may be as large as `block.max-bytes`, because a consensus proposal
  body IS a whole serialized block.
- **Everything per-chain is independent**: ledger, state machine, member
  set, sequencer mode, block cadence, anchor policy. Chains share only the
  node's networking and its L1 view.
- **The engine is a serial event loop.** All consensus entry points — the
  propose tick, inbound `~consensus/*` messages, catch-up batches — hop onto
  one single-threaded executor per chain. There is no locking inside the
  consensus logic; ordering is the concurrency model.

## 2. Message lifecycle

```
submit (REST / SDK / gossip)
  → envelope auth: member Ed25519 signature, message-id integrity
  → transport limits: size (chain max-message-bytes), TTL cap
  → state machine validate()      ← application admission (reject = 400)
  → pool (backpressure: full pool = 429 + counted gossip drops)
  → gossip to app peers (dedup by message-id)
  → proposer selects into a block  (drops: finalized dupes, stale
     sender-seqs, machine-rejected; ~system topics bypass validate)
  → consensus round (§4)          ← the only place messages become canonical
  → finalized: indexed by id/topic/sender, applied to state, streamed to
     SSE/webhooks/Kafka, provable via MPF, eventually anchored to L1
```

Two distinct rejection tiers exist by design (§8.2): *admission* keeps junk
out of blocks (node-local, fast, can be re-tried), while *apply* is
consensus-enforced and never rejects — a finalized message that violates a
business rule is a **deterministic no-op** on every member.

## 3. Block anatomy

`AppBlock` fields (CBOR, `AppBlockCodec`):

| Field | Notes |
|---|---|
| `version` | block format version (3) |
| `chainId` | chain identity |
| `height` | genesis = 1 |
| `consensusContextDigest` | commits genesis, membership, quorum, and consensus/observer profiles |
| `view` | certified-consensus view at this height |
| `prevHash` | 32 zero bytes at height 1 |
| `l1Slot`, `l1BlockHash` | the stable L1 reference (§6), 0/empty when disabled |
| `timestamp` | proposer wall-clock millis — the ONLY time a state machine may use |
| `messagesRoot` | binary blake2b-256 merkle root over the ordered **message ids** (odd width duplicates the last node; empty list = 32 zero bytes) |
| `stateRoot` | MPF root AFTER applying this block (§7) |
| `messages` | the full ordered message envelopes |
| `proposer` | Ed25519 public key |
| `justification` | empty in view 0; canonical `NewViewCertificate` in higher views |
| `cert` | finality certificate: `(scheme=ed25519, [(signer, signature)...])` |

**The block hash covers the header only** — blake2b-256 over
the v3 consensus identity, value fields, proposer, and justification digest.
Messages are bound via `messagesRoot`, history via `prevHash`; only the cert is
outside the hash, so it can be attached with `withCert`.

## 4. The consensus round

Consensus messages are ordinary signed envelopes on `propose`, `prepare`,
`prepared`, `commit`, `timeout`, `new-view`, and `cert` topics under
`~consensus/`. Votes and certificates use canonical, bounded CBOR and
domain-separated signatures.

### 4.1 Proposing

Every member ticks every `block.interval-ms`; the framework computes the same
leader from committed context on every node, so only that member proceeds:

1. If a round times out, broadcast a durable signed timeout for the next view.
2. In view 0, the configured policy selects the initial leader. In higher
   views, the framework selects one deterministic leader from the certified
   context.
3. Select the mandatory durable L1 prefix, then ordinary messages: cap by
   `block.max-bytes` (primary; the
   serialized block is trimmed to fit) and `block.max-messages` (backstop);
   drop already-finalized ids, stale per-sender seqs, and messages the state
   machine's `validate()` rejects. Reserved `~` topics bypass application
   admission — a state machine cannot veto governance or consensus traffic.
4. Build and **apply locally** to compute the real `stateRoot`, persist the
   `(height, view, blockHash)` prepare lock, broadcast the proposal and PREPARE.

No pending messages → no block. An idle chain produces nothing.

### 4.2 Follower validation — every check, in order

A member votes for a proposal only after ALL of:

1. decodes as a block; height not already finalized;
2. height is exactly `tip+1` (a block from the future is *deferred* and
   retried, not dropped — the transport never re-delivers);
3. `block.proposer == envelope sender` (proposer authenticity);
4. the framework accepts this leader for the block's height and view;
5. `prevHash` equals the local tip hash;
6. `messagesRoot` recomputes from the message list;
7. serialized size ≤ `block.max-bytes` (counted + rejected loudly);
8. the L1 ref is monotonic and consistent with the node's OWN L1 view
   (§6.3) — mismatch rejects, "proposer slightly ahead" defers;
9. every `~l1/*` observation message re-derives from the node's own L1
   stream (fail-closed);
10. every message: valid message-id, not expired, member-signed
    (membership evaluated **at this height**), body ≤ `max-message-bytes`;
11. per-sender seqs strictly increasing above the finalized floor (when
    `message.enforce-sender-seq` is on);
12. no conflicting prepare lock in this view and no newer durable lock;
13. **independent re-execution**: the follower applies the block itself and
    requires byte-identical `stateRoot`.

Then and only then: persist the prepare lock and broadcast a domain-separated
PREPARE. Check 13 is the heart of the system — *state is never trusted,
always recomputed*. A nondeterministic state machine stalls its chain right
here.

### 4.3 Finality

Any member holding the round aggregates PREPARE votes. At `threshold`, it
persists and broadcasts a `PreparedQC`; members then sign COMMIT. At a COMMIT
quorum, the holder assembles `FinalityCert`, commits, and broadcasts `cert`.

Cert verification never trusts the sender: scheme must be Ed25519, each
signer must be a member at that height, duplicates are ignored, every
signature is verified over the commit-domain digest, and the count must reach the
threshold at that height.

A block is **APP_FINAL** once committed with a threshold cert — via own
votes, a received cert notice, or catch-up (§8.1). The ledger is
append-only; there is no rollback path below finality.

### 4.4 Timeouts and partial rounds

A timeout never deletes a lock. A quorum of signed timeout records forms a
`NewViewCertificate`. The next leader must carry the highest valid PreparedQC
value byte-for-byte; without one it may build a fresh deterministic value.
Timeouts back off exponentially within configured bounds.

## 5. Vote locks — the safety core

Before PREPARE, a member persists `(height, view, blockHash)`. It never prepares
two values in one view or moves a lock backwards. PreparedQCs and their complete
values are also durable across restart and must cross every higher view.

There is no stale-lock deletion endpoint. Expired proposals recover only via a
quorum-certified higher view; L1-invalidated prepared values quarantine rather
than unlock.

## 6. Sequencer modes and membership

### 6.1 Fixed

`sequencer.proposer` selects the view-0 leader. If it cannot complete the
round, a quorum-certified timeout advances the view and the next member in
canonical member order becomes leader. No configuration change or lock
deletion is needed.

### 6.2 Rotating

The view-0 leader is derived from the committed consensus context and parent
block hash. Higher views advance through the canonically sorted member list.
This gives every member the same leader even when their newest L1 tips differ.
The L1 reference remains proposal data and is verified independently (§6.3);
it is not the leader clock.

Both fixed and rotating policies require a quorum to advance a failed round.
Choose a threshold that matches the stated fault model (§4), not merely the
number of normally-online nodes.

### 6.3 The L1 reference (both modes)

With `l1.stability-depth > 0`, every block carries an `(l1Slot, l1BlockHash)`
reference at least that many blocks below the L1 tip. Followers verify it
against their **own** L1 view: a fabricated ref is rejected fail-closed, a
ref slightly ahead of the local view is deferred and retried, and slots must
be monotonic across blocks. This pins app-chain history to L1 time — it is
what makes observation stability (user guide §5.5) and
anchor recency meaningful.

### 6.4 Proposer vs anchor leader (don't conflate them)

Rotation applies to the **proposer** only. The **anchor leader** — the node
with `anchor.enabled` that builds and pays for L1 anchor txs — is a separate,
fixed role that does not rotate (one thread UTxO, one fee wallet), and it is
not a trust point: script-mode advances require threshold member
co-signatures verified against each member's own ledger (user guide §5.1).
A rotating chain with anchoring therefore has a rotating proposer AND a
fixed anchor leader at the same time.

### 6.5 Membership epochs

Membership is either static config or **governed** (ADR 008.3): membership
changes are themselves finalized chain transactions requiring
threshold-many identical member commands. Internally the group keeps
*member epochs* — every consensus check above says "member **at height h**",
so blocks from before a rotation verify against the membership that was
current then. Governance commands ride reserved `~governance/*` topics
(which bypass state-machine admission) and their effects persist atomically
with the block that finalizes them.

## 7. State, the MPF trie, and proofs

Each chain's ledger is one RocksDB instance
(`<resolved yano.app-chain.storage.path>/<chain-id>/`) holding column families for
blocks, framework metadata, the message index, query indexes, and the **MPF
trie nodes** (Merkle Patricia Forestry — the same construction as Aiken's
`merkle-patricia-forestry`, so proofs are on-chain-verifiable).

The split of responsibilities:

- **The state machine and framework-authenticated keys share the trie.** Every
  `writer.put(key, value)` /
  `delete(key)` in `apply()` is an MPF entry — individually provable against
  the resulting root. The framework additionally writes reserved observation
  cursor keys before commit, so delivery progress is covered by the same root.
- **The framework owns everything else**: tip metadata, per-message and
  topic/sender indexes, per-sender replay floors, vote locks, governance
  epochs — all in RocksDB CFs *outside* the state commitment.
- **One atomic batch per block.** `apply()` stages trie writes into a
  RocksDB WriteBatch; `commitBlock` writes block + tip + indexes + trie
  nodes + new root in a single atomic commit. A crash mid-block leaves the
  previous height fully intact.

`stateRoot` is therefore a pure, reproducible commitment to the state
machine's data — recomputed and byte-compared by every member on every
block (§4.2 check 13), committed on-chain by anchoring (user guide §5), and
queryable per key: `GET .../state/proof/{keyHex}` returns the value and an MPF
inclusion proof any independent implementation can verify against an
anchored root.

## 8. Catch-up, restart, snapshots

### 8.1 Catch-up (sync protocol 103)

Gossip never re-delivers: a missed proposal is gone from the transport. A
lagging member recovers through the dedicated block-range sync protocol —
every 5s it asks one connected peer for `tip+1 .. tip+50` and applies what
comes back through the same verification gauntlet as live consensus, minus
what no longer applies:

- height/prev-hash chain intact, messages-root recomputed;
- proposer must have been the deterministic built-in leader at that height and
  view (custom extension modes retain their own view-0 validation);
- L1 ref monotonic + consistent with the local L1 window (a ref ahead of the
  local view pauses the batch, it does not fail it);
- observations re-verified fail-closed; message signatures re-verified
  (expiry is NOT re-checked — the messages were finalized before expiry);
- **the threshold cert is fully verified**, and the block is re-applied with
  a required byte-identical state root.

A certified block at a height with a local in-flight round supersedes the
round. If a peer is ahead but local progress stalls, an
`AppChainStalledEvent` fires (visible in status as `stalled`).

### 8.2 Restart semantics

Persisted (RocksDB, all atomic with their block): blocks + certs, tip
metadata, MPF trie + root, message/query indexes, per-sender floors, per-view
prepare locks, prepared certificates + canonical proposal envelopes,
membership epochs and pending governance. In-memory (lost on restart, by
design): the pending pool, active round aggregation (reconstructed from
persisted evidence), gossip
dedup/counters, pending anchor rounds.

Startup re-verifies rather than trusts:

1. snapshot manifest signatures/hashes (if restoring) — before RocksDB
   touches anything;
2. ledger integrity: committed root equals the tip block's recorded root;
3. persisted membership epochs override static config;
4. **tip-cert verification**: the tip block re-hashes to the stored tip hash
   AND its cert carries ≥ threshold valid member-at-height signatures — a
   snapshot whose contents merely self-agree is rejected;
5. own sender-seq floor restored so a restarted member never reuses a
   finalized sequence number.

### 8.3 Snapshots

`GET .../snapshot` produces a RocksDB checkpoint (hard links — cheap) with a
member-signed manifest binding tip height/hash, state root and member
epochs. New-member onboarding = copy checkpoint, verify manifest, start,
catch up the delta (user guide §14.3).

## 9. State machines — the SPI

`com.bloxbean.cardano.yano.api.appchain.AppStateMachine`:

| Method | When | Contract |
|---|---|---|
| `id()` | — | stable identifier, matched against `state-machine` config |
| `init(reader, info)` | once at start | read-only warm-up; `info` = (chainId, own member key, member count) |
| `validate(msg)` | admission (pool + block selection) | fast, side-effect-free, MAY run concurrently; envelope auth already done; reject keeps the message out of blocks. `~` system topics bypass it |
| `apply(block, writer)` | exactly once per finalized block, in height order, on EVERY member | the deterministic transition; all writes via `writer.put/delete` (= MPF entries) |
| `query(path, params)` | committed reads | invoked through the chain-scoped REST `/query/{path}` route; state proofs remain available through `state/proof/{keyHex}` |

**Determinism is the contract.** Inside `apply()`: no wall clock (use
`block.timestamp()`), no randomness, no I/O, no environment reads, no
iteration over unordered collections, no locale-dependent serialization.
Violations don't corrupt anything — they *stall the chain*, because
followers reject the proposer's state root (§4.2). Test with
`StateMachineConformance` (runtime testkit): it applies an identical seeded
block corpus in N independent runs plus a kill-and-reopen replay and asserts
byte-identical roots at every height.

**The two-tier rule** (worth internalizing): `validate` rejects; `apply`
never does. Anything that reaches `apply` is already consensus-final, so a
business-rule violation there must be a silent, deterministic no-op —
re-check every rule you checked at admission. Every conforming state machine
must follow this pattern.

Convenience: `TypedAppStateMachine<T>` + `MessageCodec<T>` decode bodies at
the edge (undecodable → reject at admission / skip at apply);
`JacksonCborCodec.of(Class)` is the batteries-included codec.

## 10. Built-in state machine

| id | Module | One-liner |
|---|---|---|
| `ordered-log` | runtime (built-in) | append-only log of opaque messages; per-message inclusion proofs |

### 10.1 `ordered-log`

The default. Bodies are fully opaque — nothing is validated, everything
finalizes. Per message it writes
`sha256("~yano/finalized-message/v1/" || messageId) →
cbor([schemaVersion, height, index, topic, sender])` and maintains a namespaced
tip record. Resolve the public message ID through the `finalized-message-v1`
typed proof subject; `state/proof/{keyHex}` is the lower-level route for the
resolved physical key. The record format is shared (`FinalizedMessageIndex` in
core-api) with opt-in indexes so proofs never diverge.

Additional state machines are ServiceLoader plugins maintained in
[Yano X](https://github.com/bloxbean/yano-x/tree/main/state-machines). Their
wire formats, typed clients, proofs, and operational guidance live with those
plugins so Yano's core documentation does not imply that they are built in.

## 11. Writing your own state machine

1. Implement `AppStateMachine` (or extend `TypedAppStateMachine<T>`), obey
   §9's determinism rules and the two-tier validate/no-op pattern.
2. Implement `AppStateMachineProvider` — `id()` matches the `state-machine`
   config value; override `create(AppStateMachineContext)` if you need
   settings (`context.settings()` is the `yano.app-chain.*` map with the
   stem stripped, e.g. `machines.my-machine.foo`).
3. **Plugin mode** (default distribution, no rebuild): register the provider
   in `META-INF/services/com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider`,
   and add `META-INF/yano/plugins/<bundle-id>.json`. The manifest declares an
   `app-state-machine` contribution whose `name` equals the provider `id()`
   and whose `provider` is the same fully-qualified class as the ServiceLoader
   entry. For example:

   ```json
   {
     "schemaVersion": 1,
     "id": "com.example.my-machine",
     "version": "1.0.0",
     "yanoApi": { "min": 1, "max": 1, "minLevel": 1 },
     "dependencies": [],
     "contributions": [
       {
         "kind": "app-state-machine",
         "name": "my-machine",
         "provider": "com.example.MyMachineProvider"
       }
     ]
   }
   ```

   Package one self-contained bundle JAR, drop it into the JVM node's
   `plugins/` directory, and set `state-machine: my-machine`. An unknown id
   fails fast listing available ids. Native images cannot load directory JARs;
   include and map the manifested bundle at application build time so catalog
   and reflection metadata are generated before the native executable.
4. **Library mode**: pass the machine instance straight to the
   `AppChainSubsystem` constructor — no provider or services file needed.
5. Start from `scaffolds/plugin-template/` (a complete counter machine +
   provider + ServiceLoader entry + bundle manifest), and gate your machine with
   `StateMachineConformance` before trusting it with a multi-node chain.
   The full walkthrough is user guide §6 / tutorial Part 2.

## 12. Where to go deeper

- **Anchoring internals** (thread NFT, co-sign rounds, on-chain validator,
  independent verification): user guide §5 and `adr/app-layer/008.4-*`.
- **Rotation & governed membership design**: `adr/app-layer/008.2-*`,
  `008.3-*`.
- **Wire ABIs** (anchor datum, evidence bundle, observations):
  `core-api/src/main/cddl/appchain/*.cddl`.
- **Live regressions** that exercise everything in this guide on a real
  devnet: the `test-app-chain-*` skills under `.claude/skills/`.
