# Yano App Chain — Consensus & Internals Guide

How the app chain works end to end, at the level a developer needs to extend
it, debug it, or audit it. Companion docs: the
[user guide](APP_CHAIN_USER_GUIDE.md) (configuration, REST, operations), the
[tutorial](APP_CHAIN_TUTORIAL.md) (hands-on walkthrough), and the ADRs under
`adr/app-layer/` (design history: 005 framework, 006 extensions,
008.x consensus/anchoring iterations).

Code pointers use class names — start at
`runtime/.../appchain/AppChainEngine.java` (consensus core),
`core-api/.../appchain/` (SPI + codecs), and
`appchain/appchain-stdlib/` (standard-library state machines).

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
| `version` | block format version (1) |
| `chainId` | chain identity |
| `height` | genesis = 1 |
| `prevHash` | 32 zero bytes at height 1 |
| `l1Slot`, `l1BlockHash` | the stable L1 reference (§6), 0/empty when disabled |
| `timestamp` | proposer wall-clock millis — the ONLY time a state machine may use |
| `messagesRoot` | binary blake2b-256 merkle root over the ordered **message ids** (odd width duplicates the last node; empty list = 32 zero bytes) |
| `stateRoot` | MPF root AFTER applying this block (§7) |
| `messages` | the full ordered message envelopes |
| `proposer` | Ed25519 public key |
| `cert` | finality certificate: `(scheme=ed25519, [(signer, signature)...])` |

**The block hash covers the header only** — blake2b-256 over
`[version, chainId, height, prevHash, l1Slot, l1BlockHash, timestamp,
messagesRoot, stateRoot]`. Messages are bound via `messagesRoot`, history via
`prevHash`; the proposer and cert are *outside* the hash. That is deliberate:
votes are signatures over a stable target, and the finality cert can be
attached afterwards (`withCert`) without changing the hash the members
signed.

## 4. The consensus round

Consensus messages are ordinary signed app-message envelopes on three
reserved topics: `~consensus/propose`, `~consensus/vote`, `~consensus/cert`
(`ConsensusCodec`). A proposal body is the full serialized block; a vote is
CBOR `[height, block-hash, signature-over-block-hash]`; a cert notice is
`[height, block-hash, cert-cbor]`.

### 4.1 Proposing

Every member ticks every `block.interval-ms`; the tick self-gates through
the sequencer mode, so only the scheduled proposer proceeds:

1. An in-flight round blocks a new one until `round-timeout`
   (`max(5 × interval, 10s)`); on timeout the round is discarded but its
   **vote lock stays** (§5).
2. If a vote lock exists at `tip+1`: re-gossip the locked proposal (partial
   round recovery) — or, if the locked proposal is unrecoverable, propose
   around it (§5).
3. `sequencerMode.shouldProposeNow(height)` — is it my turn (§6)?
4. Select messages from the pool: cap by `block.max-bytes` (primary; the
   serialized block is trimmed to fit) and `block.max-messages` (backstop);
   drop already-finalized ids, stale per-sender seqs, and messages the state
   machine's `validate()` rejects. Reserved `~` topics bypass application
   admission — a state machine cannot veto governance or consensus traffic.
5. Build the candidate, **apply it locally** to compute the real
   `stateRoot`, persist own vote lock + self-vote, broadcast the proposal,
   and store the proposal envelope for later re-gossip.

No pending messages → no block. An idle chain produces nothing.

### 4.2 Follower validation — every check, in order

A member votes for a proposal only after ALL of:

1. decodes as a block; height not already finalized;
2. height is exactly `tip+1` (a block from the future is *deferred* and
   retried, not dropped — the transport never re-delivers);
3. `block.proposer == envelope sender` (proposer authenticity);
4. the sequencer mode accepts this proposer for this height/window (§6);
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
12. no conflicting vote lock at this height (a different locked hash means
    a split — counted, refused);
13. **independent re-execution**: the follower applies the block itself and
    requires byte-identical `stateRoot`.

Then and only then: persist the vote lock, sign the block hash, broadcast
the vote. Check 13 is the heart of the system — *state is never trusted,
always recomputed*. A nondeterministic state machine stalls its chain right
here.

### 4.3 Finality

Votes are aggregated by **any member holding the round** (a dead proposer
cannot sink collected votes). Before counting, votes are re-filtered by
membership-at-height — a member removed mid-round loses its vote. At
`threshold` distinct valid signatures, the holder assembles the
`FinalityCert`, commits the block, and broadcasts `~consensus/cert` so
others can finalize immediately.

Cert verification never trusts the sender: scheme must be Ed25519, each
signer must be a member at that height, duplicates are ignored, every
signature is verified over the block hash, and the count must reach the
threshold at that height.

A block is **APP_FINAL** once committed with a threshold cert — via own
votes, a received cert notice, or catch-up (§8.1). The ledger is
append-only; there is no rollback path below finality.

### 4.4 Timeouts and partial rounds

A round that doesn't reach threshold within `round-timeout` is discarded —
but the persisted vote lock and the stored proposer-signed envelope remain.
The next tick re-gossips the original proposal and this member's vote
(rate-limited), so a partial round converges as soon as enough members are
reachable. A timed-out round never orphans a height.

## 5. Vote locks — the safety core

When a member votes (or proposes, which self-votes), it first persists
`vote_lock_<height> → blockHash` plus the original proposal envelope. This
enforces **at most one vote per member per height, across crashes and
restarts** — the property that makes threshold certs unforgeable without
`threshold` distinct colluding members.

The recovery ladder when a locked height wedges:

1. **Recoverable lock** (stored envelope still TTL-valid): re-gossip it;
   the round completes with other members' votes.
2. **Unrecoverable lock** (envelope missing or its messages expired):
   *propose-around* — the member proposes a FRESH block at that height
   **without self-voting** (its vote at that height is spent). The round
   then needs `threshold` votes from OTHER members, which self-heals for
   thresholds ≤ n−1. Surfaced in status as `staleLockedHeight`.
3. **Operator escape hatch**: `POST .../admin/unlock-stale-round` removes an
   unrecoverable lock, consciously trading at-most-one-vote for liveness.
   Only valid when the round is provably unrecoverable.

## 6. Sequencer modes and membership

### 6.1 Fixed

`sequencer.proposer` names one member; it alone proposes, everyone else
validates and votes. Proposer death is an operational event (restart it, or
reconfigure). Simplest to reason about; the demo cluster's `orders-chain`
uses this.

### 6.2 Rotating (L1-slot-clocked)

No fixed proposer — proposership rotates over **L1 slot windows**, using
Cardano's clock as the shared scheduler:

```
window(slot)   = slot / window-slots            (default 60 slots)
proposer(w, h) = sortedMembersAt(h)[ blake2b256(chainId ‖ " " ‖ w) mod n ]
```

Every member computes the same function against its own stable L1 view, so
"whose turn is it" needs no extra protocol. Consequences:

- **No L1 clock yet → nobody proposes** and incoming proposals are deferred;
  rotating mode refuses to start without an L1 event feed.
- **Scheduled proposer offline → that window is simply empty.** The next
  window (≤ `window-slots` L1 slots later) selects a different member. There
  is no intra-window failover by design — the clock IS the failover.
- Proposals from the last `lookback-windows` (default 64) windows are still
  accepted, so a partial round from an earlier window can finalize late.
- Prefer thresholds like 2-of-3 over all-of-n in rotating mode (any single
  offline member must not block every window's round).

### 6.3 The L1 reference (both modes)

With `l1.stability-depth > 0`, every block carries an `(l1Slot, l1BlockHash)`
reference at least that many blocks below the L1 tip. Followers verify it
against their **own** L1 view: a fabricated ref is rejected fail-closed, a
ref slightly ahead of the local view is deferred and retried, and slots must
be monotonic across blocks. This pins app-chain history to L1 time — it is
what makes rotation windows, observation stability (user guide §5.5) and
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
(`<yano.storage.path>/app-chain/<chain-id>/`) holding column families for
blocks, framework metadata, the message index, query indexes, and the **MPF
trie nodes** (Merkle Patricia Forestry — the same construction as Aiken's
`merkle-patricia-forestry`, so proofs are on-chain-verifiable).

The split of responsibilities:

- **The state machine owns the trie.** Every `writer.put(key, value)` /
  `delete(key)` in `apply()` is an MPF entry — individually provable against
  the resulting root. The framework never writes into the trie.
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
queryable per key: `GET .../proof/{keyHex}` returns the value and an MPF
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
- proposer must have been a **member at that height** (mode-independent —
  a certified block's legitimacy is its cert, not the live rotation window);
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
metadata, MPF trie + root, message/query indexes, per-sender floors, **vote
locks + locked proposal envelopes**, membership epochs and pending
governance. In-memory (lost on restart, by design): the pending pool,
in-flight rounds (reconstructed from the persisted vote lock), gossip
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
| `query(path, params)` | — | **reserved/deferred**: the hook exists but the runtime does not invoke it and no REST `/query` route exists yet. Read state via `stateValue`/`proof/{keyHex}` + the machine's static decode helpers |

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
re-check every rule you checked at admission. Every stdlib machine follows
this pattern.

Convenience: `TypedAppStateMachine<T>` + `MessageCodec<T>` decode bodies at
the edge (undecodable → reject at admission / skip at apply);
`JacksonCborCodec.of(Class)` is the batteries-included codec.

## 10. Out-of-the-box state machines

| id | Module | One-liner |
|---|---|---|
| `ordered-log` | runtime (built-in) | append-only log of opaque messages; per-message inclusion proofs |
| `kv-registry` | stdlib | owner-guarded key/value registry |
| `approvals` | stdlib | k-of-n approval workflow with deadlines |
| `balances` | stdlib | minimal token/points ledger (mint + transfer) |
| `doc-trail` | stdlib | per-entity chained document/audit trail |
| `role-approvals` | role-workflow plugin | governed actors + generic role-gated payload hashes |
| `role-evidence` | evidence-profile plugin | actor registry + role policy + evidence composite |

Stdlib machines register via `ServiceLoader`
(`StdlibStateMachineProviders`); select with `state-machine: <id>` —
per-machine settings live under `machines.<id>.*`. (The ZK extension module
ships additional machines — `zk-gate`, credential registry, ZK membership —
behind the same SPI; see user guide §17.)

`role-approvals` is the application-neutral provider: it commits the actor
registry and role-approval workflow, exposes proof-oriented queries, and emits
no effects. `role-evidence` is a complete manifested composite provider rather than an
independently reorderable stdlib component. Its `evidence-role-v1` profile
commits the actor registry, role-approval workflow, evidence/doc-trail
components, routes, quotas, and governance identity. Wire, signing, proof, and
recovery details are in `docs/APP_CHAIN_DOMAIN_ROLES.md` and ADR-019.

### 10.1 `ordered-log`

The default. Bodies are fully opaque — nothing is validated, everything
finalizes. Per message it writes `messageId → cbor([height, index, topic,
sender])` and maintains `~tip → cbor(height)`. The MPF key **is the
message id**, which is why `proof/{messageIdHex}` proves a message's
finalization at `(height, index)` against an anchored root. The record
format is shared (`OrderedLog` in core-api) with the ZK gate so proofs never
diverge.

### 10.2 `kv-registry`

Command (CBOR): `[op(uint), key(bstr), value(bstr)]` — op `0` PUT
(value required), `1` DELETE. State entry:
`key → cbor([owner(bstr32), value(bstr)])`.

- **Ownership**: first writer of a key becomes its owner; only the owner
  may update or delete. Non-owner writes are deterministic no-ops.
- **Admission rejects**: non-CBOR bodies, PUT without value, PUT whose value
  violates `machines.kv-registry.value-format` (`raw` default | `cbor` —
  one well-formed CBOR item | `utf8`). All re-checked as no-ops at apply.

### 10.3 `approvals`

Commands (CBOR): PROPOSE `[0, itemId(tstr), payload(bstr), required(uint>0),
deadlineMillis(uint; 0 = none)]`, APPROVE `[1, itemId]`, REJECT
`[2, itemId]`. State entry `"i/"+itemId → cbor([status, proposer,
payloadHash(blake2b-256), required, deadline, approvers[], rejecter])`,
status ∈ {0 PENDING, 1 APPROVED, 2 REJECTED, 3 EXPIRED}.

- PROPOSE is idempotent per item id. APPROVE dedups approvers; `required`
  distinct approvals → APPROVED. Any member's REJECT → REJECTED. A command
  touching a PENDING item past its deadline (vs `block.timestamp()`) →
  EXPIRED. Terminal states are immutable.

### 10.4 `balances`

Command (CBOR): `[op(uint), to(tstr), amount(uint>0)]` — op `0` MINT, `1`
TRANSFER. State entry `"b/"+account → unsigned big-endian amount`; a zero
balance deletes the key. The sender's own account id is its member pubkey
hex — members spend only their own balance; overspend is a no-op (balances
never go negative). `machines.balances.minter` (32-byte hex key) restricts
minting to one member; unset = any member may mint (set it for production).

### 10.5 `doc-trail`

Command (CBOR): `[entityId(tstr), entryHash(bstr), ref(tstr)]` — `ref` (URL/
IPFS CID) stays in the message, not in state. State entry
`"e/"+entityId → cbor([count, headHash])` where
`head_n = blake2b256(head_{n-1} ‖ entryHash_n ‖ sender)` from a 32-zero-byte
genesis head — an append-only per-entity hash chain a verifier can recompute
independently from the ordered trail (`DocTrailStateMachine.computeHead`).

### 10.6 Reading state (all machines)

There is no typed query API yet (§9). The pattern:

1. derive the key with the machine's helper (`accountKey("alice")`,
   `itemKey("release-42")`, `entityKey("product-42")`, kv key bytes,
   message id);
2. `GET .../proof/{keyHex}` (REST) or `stateValue/stateProof` (Java) —
   returns value + MPF proof;
3. decode with the machine's static helpers (`decodeBalance`, `decodeItem`,
   `decodeOwner`/`decodeValue`, `decodeEntry`).

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
