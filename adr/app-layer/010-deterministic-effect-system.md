# ADR-010: Deterministic Effect System (Emit → Execute → Result) for Yano App Chains

## Status
Proposed — design for review before implementation

## Date
2026-07-12

## Authors
BloxBean Team

## Parent / References
- ADR-005 (D10 developer SPI, §4.4 determinism & atomic commit) — the transition
  contract this ADR must not break
- ADR-006 (E2.2 approvals machine, E3.2 finalized-stream sinks, E3.3 query index,
  E4.2 encrypted bodies / E4.4 retention, E7.x ZK settlement)
- ADR-008.3 (`~governance/membership` — sequenced, framework-interpreted system
  messages with atomic meta writes)
- ADR-008.4 (I3.2 `~l1/*` observations — member-signed reserved-topic injection,
  follower verification of externally-derived facts)

This ADR was produced from a multi-track design exploration: a codebase
architecture survey, an external prior-art survey, two competing storage-design
studies (effects **inside** the authenticated state vs an **outbox outside** the
state root), and an adversarial failure-mode review. The verdicts of those
tracks are folded into §6 (storage decision), §9 (failure modes) and §10 (hard
requirements).

---

## 0. In plain words

An app-chain state machine today is a perfect bookkeeper: every node applies
the same messages in the same order and writes identical entries in an
identical, provable ledger. But a bookkeeper who can only write in the ledger
cannot *do* anything — cannot pay an invoice, release a shipment, pin a
document, or call the ERP. The moment a state machine tried to do any of those
directly, the chain would break: an HTTP call succeeds on one node, times out
on another, and now the nodes disagree about what happened.

The effect system splits the job in two. The bookkeeper (deterministic state
machine) writes an **instruction slip** into the ledger — "pay 100 ADA to X",
"release shipment 42" — as pure data, identically on every node. A separate
**clerk** (the Effect Runtime, running on one designated node or outside the
cluster entirely) reads finalized instruction slips, performs the real-world
action, and then files a **signed receipt** back into the ledger as an ordinary
chain message. The bookkeeper records the receipt like any other entry.
Instructions and receipts are ordered, replicated and provable; the messy,
retryable, failure-prone act of *doing* stays outside the deterministic world,
where it can be retried without ever corrupting the books.

---

## 1. Context

Yano app chains (ADR-005/006/008) provide deterministic replicated state
machines: application messages are ordered by a sequencer, finalized by
threshold certificates, applied through `AppStateMachine.apply(block, writer)`,
committed atomically with an MPF state commitment, and periodically anchored to
Cardano L1. The stdlib ships kv-registry, approvals, balances and doc-trail
machines; the framework supplies networking, ordering, persistence, state
proofs and anchoring.

This model covers state that is *internal* to the app chain: registry entries,
balances, document history, approval records. Real enterprise applications
additionally need actions **outside** the chain, triggered by state
transitions:

- submit a Cardano L1 transaction (payment, token mint, metadata write)
- call an ERP/warehouse API, trigger a webhook, send an email
- pin a document to IPFS, issue a Verifiable Credential
- dispatch a CI/CD deployment

Today the framework has three adjacent mechanisms, none of which is an effect
system:

| Existing mechanism | What it gives | Why it is not enough |
|---|---|---|
| `FinalizedStreamSink` + `SinkRunner` (ADR-006 E3.2) | at-least-once, height-ordered delivery of whole finalized blocks to webhook/Kafka, persisted cursor | fire-and-forget block export; no per-action addressing, no result feedback, one failing delivery blocks the stream |
| SSE / `subscribeFinalized` (E3.1) | live push of finalized blocks/messages | in-process/transient; no durability, no retry, no audit |
| `~l1/*` observations (008.4) | external L1 facts sequenced *into* the chain | inbound only, and consensus-critical (follower recompute) |

What is missing is the outbound half with a feedback loop: a **deterministic,
auditable contract between state transitions and external actions** — emitted
identically on every node, executed exactly-once-ish in the real world, with
the outcome recorded back into deterministic state.

## 2. Problem

Executing external actions inside `apply()` is structurally unsafe:

```java
public void apply(AppBlock block, AppStateWriter writer) {
    writer.put(...);
    httpClient.post(...);   // ❌ nondeterministic: timeout on node A, 200 on node B
}
```

Every member re-executes `apply()` and the resulting state root is verified
byte-for-byte before voting; any node-dependent behavior stalls the chain
permanently (the ledger is append-only after APP_FINAL — there is no rollback
path by construction). The determinism contract in the `AppStateMachine`
javadoc (no wall clock, no randomness, no I/O) already forbids this; what the
framework lacks is the *safe alternative*.

The theory names this precisely. It is the **output commit problem** of the
fault-tolerance literature (Elnozahy et al. 2002): messages to the "outside
world" — anything that cannot be rolled back — may be released only from state
that is already recoverable/final. And the guarantee ceiling is well
established: **exactly-once external delivery is impossible** (Two Generals);
the strongest achievable contract is

> **exactly-once incorporation, at-least-once execution** — the external action
> may run more than once (mitigated by idempotency keys), but its *outcome* is
> incorporated into chain state exactly once, deterministically.

Every serious system surveyed (§4) lands on this exact split.

## 3. Goals and non-goals

### Goals
1. State machines **emit effects as data**, never perform them — emission is
   part of the deterministic transition, identical on every node, covered by
   the conformance harness.
2. Deterministic effect identity — derived from chain position, never UUID /
   random / wall clock — usable as the universal idempotency key.
3. **Auditable/provable trail**: a third party can prove "effect E was
   authorized at height H" and "outcome O was recorded at height H′" against
   threshold-signed, L1-anchored commitments.
4. At-least-once execution with retries, backoff, expiry and a poison lane;
   pluggable executors (webhook, Cardano tx, IPFS, VC, ERP, custom).
5. Configurable **finality gating** per effect (app-final vs L1-anchored).
6. Embedded, external and dedicated-executor-node deployment models.
7. Results re-enter the chain as ordinary sequenced messages; app state
   transitions on results are deterministic and replay-safe.
8. Compatible with the future ZK settlement mode (ADR-006 E7.x) without
   redesign.

### Non-goals
- Not a workflow/BPM engine, scheduler, or saga/compensation framework.
- Not a cross-chain bridge or smart-contract interop protocol.
- Not exactly-once external delivery (impossible; see §2).
- No automatic undo of executed effects (CANCELLED never "unsends").

## 4. Prior art (condensed)

A full survey was run across eight system families; the mechanisms that shaped
this design:

| System | Mechanism adopted / lesson |
|---|---|
| **Transactional outbox** (microservices.io, Debezium) | effect records committed atomically with the state change; dispatcher tails committed records; outbox id doubles as consumer idempotency key. Type-level emission (machine *returns* records) removes the "developer forgot to publish" failure class. |
| **Temporal / Cadence / SWF** | deterministic workflow core + activities as effects; retry policy **as data** on the record; timeouts (not leases) as the liveness mechanism; results re-enter as ordered history events; `getVersion` markers = versioned emission logic activated at a point in history; late completions fenced. |
| **Cosmos IBC (ICS-004)** | the best on-chain effect lifecycle: packet **commitment in state** (hash, not payload), relayers as external executors, ack and timeout as two distinct deterministic escape hatches, commitment **deleted on terminal** (bounded pending set), "exactly-once effect = at-least-once relay + deterministic idempotent receive". Ordered channels die on one timeout → default **unordered**. |
| **Ethereum keepers** (Chainlink Automation, Maker) | executor selection is an *efficiency* choice, never a correctness mechanism — the target/state machine must re-validate and dedup regardless; v1 rotation → v2 quorum shows the escalation path. |
| **Corda external operations** | `deduplicationId` derived deterministically from (flow id, suspend count) — the exact analog of our (chainId, height, ordinal); "no inbuilt deduplication — external systems must dedup on the id". |
| **Internet Computer HTTPS outcalls** | the cautionary contrast: every replica executes the call → N duplicate POSTs, secrets on all nodes, consensus over responses. ICP itself retreated (`is_replicated=false`). Replicated execution is the wrong tool for *writes*. |
| **Event-sourcing** (Axon, EventStoreDB, NServiceBus) | park-don't-skip poison handling with replay endpoint; "retries reorder — ordering and retry are enemies"; saga purity rule ("no I/O in the saga, effects are handlers that reply as messages"). |
| **zk-rollup L2→L1 messaging** (zkSync, Scroll) | per-batch Merkle root over outbound messages inside the state commitment; consumption gated on proof finality; permissionless relay with on-chain dedup — the exact shape of our ZK future (§7 F4, §13). |

Cross-cutting invariant found everywhere: **whoever executes, the
deterministic core enforces idempotency at result re-entry.**

## 5. Design overview — two planes

The design separates two planes with different consistency requirements:

```
        CONSENSUS PLANE (deterministic, replicated, provable)
        ───────────────────────────────────────────────────────
        app message ──▶ apply() ──▶ state writes ──▶ state root
                          │
                          └─▶ effects.emit(spec)          [F1]
                                │  ordered effect list, deterministic ids
                                ▼
                        fx_records CF (atomic with block)  [F2,F3]
                        effectsRoot leaf in MPF trie       [F4]
                                                │
   ┌────────────────────────────────────────────┘
   ▼
        EXECUTION PLANE (node-local, at-least-once, disposable)
        ───────────────────────────────────────────────────────
        EffectRuntime tick: intake → finality gate → dispatch [F5,F6,F7]
                                │
                        AppEffectExecutor.execute()   ← plugins
                                │  webhook / cardano-tx / ipfs / custom
                                ▼
                        external system (idempotency key = effectId)
                                │
   ┌────────────────────────────────────────────┐
   ▼                                            │
        CONSENSUS PLANE again                   │
        ─────────────────────                   │
        member-signed ~fx/result message ◀──────┘             [F8]
                │ sequenced into a later block
                ▼
        framework interpreter: first result wins, ~fx/done leaf
                │
                └─▶ machine.onEffectResult() → app state writes → root
```

Everything on the consensus plane is a pure function of the block stream —
replayable, conformance-testable, provable. Everything on the execution plane
is per-node bookkeeping that two nodes may legitimately disagree about
(attempt counts, leases, last errors) and that can be discarded and rebuilt.

Effect lifecycle across the two planes:

```
 consensus:  EMITTED ──────────────────────────────▶ CONFIRMED | FAILED | CANCELLED | EXPIRED
             (record exists at height H,             (terminal, recorded once, at height H′>H)
              bound into effectsRoot/stateRoot)
 runtime:              PENDING → ELIGIBLE → LEASED → EXECUTING → { done | RETRY → … | PARKED }
             (node-local; never replicated; never in any root)
```

## 6. Storage decision: where do effect records live?

This was the central contested question of the exploration. Three candidates:

**Option A — effects as authenticated state.** Every effect record is an MPF
trie leaf (`~fx/e/<effectId>`), status transitions rewrite the leaf, terminal
records are GC'd after a retention window via height-bucketed deletion keys.
Strengths: one-step MPF proof per effect; the full lifecycle is inside the
proven transition (strong ZK story for per-effect facts); the pending set is
replicated state. Costs (decisive, verified against the codebase):

1. **MPF has no prefix scan** — the runtime cannot discover pending effects
   from the trie; Option A still needs a RocksDB pending index to function, so
   it is Option B's storage *plus* trie costs, not an alternative to it.
2. The MPF node store is **append-only** — deleted leaves persist in
   historical nodes forever; effect payloads (payment details, ERP commands)
   would be un-shreddable in snapshots and evidence bundles, conflicting with
   the existing retention/crypto-shredding posture (E4.4).
3. Per-effect trie writes bill O(log N) hashing into every `apply` on every
   node (proposer *and* verifying followers), and every emission-logic or
   record-layout change becomes a consensus hard fork.
4. Execution progress (attempts, leases) can never live in the root anyway —
   consensus-per-retry is absurd — so Option A degenerates into a hybrid the
   moment status handling is designed.

**Option B — deterministic outbox outside the root.** Effect records live in a
dedicated RocksDB column family, written **in the same atomic WriteBatch as
the block commit** — the established pattern already used by the query index
(E3.3) and `GovernedMembership` meta writes (008.3). Deterministically derived
from the block stream (rebuildable by replay), natively scannable, prunable
with a range delete. Cost: on its own, no cryptographic commitment — a node
could suppress or fabricate an effect record without detection.

**Option B+ (adopted) — outbox + per-block `effectsRoot` commitment.** Option
B storage, plus one 32-byte Merkle root over the block's ordered effect list
written as a single framework-owned trie leaf. The existing threshold-signed,
L1-anchored `stateRoot` then transitively commits to every effect, divergent
emission is caught by the existing byte-for-byte root check, and completeness
("these are ALL the effects of block H") is natively provable — while the
authenticated state grows by at most 32 bytes per effectful block and payloads
stay out of the un-prunable trie.

Scorecard (summary of the full comparison):

| Dimension | A (per-effect trie leaves) | B+ (outbox + effectsRoot leaf) |
|---|---|---|
| apply/verify hot-path cost | N leaf updates per block, every node | ≤ 1 leaf per effectful block ✅ |
| authenticated-state growth | payload-sized, effectively permanent | 32 B per effectful block ✅ |
| pending-set discovery | impossible in MPF → needs CF anyway | native CF iteration ✅ |
| per-effect existence proof | 1-step MPF proof ✅ | 2-step (MPF + list path) |
| completeness proof (all effects of H) | hard (non-inclusion sweep) | native from list root ✅ |
| ZK circuit cost | N payloads in trie-update witness | one 32-B leaf; list hash separable ✅ |
| payload privacy | in authenticated state forever | hash-only outside the CF ✅ |
| runtime status handling | must be hybrid anyway | designed two-tier ✅ |
| rebuild after corruption | trie authoritative | replay + chain receipts ✅ (verifiable vs effectsRoot) |

**Decision: adopt B+.** Both independent design tracks and the adversarial
review converged here. A per-effect trie-leaf mode is *not* adopted even as an
option in v1; if a concrete integration ever demands single-proof ergonomics,
it can be added later as a per-type opt-in without unwinding anything.

## 7. Design (F-items)

### F1 — Emission SPI

A third-argument default-method overload on `AppStateMachine` — the codebase's
established SPI-evolution pattern (`AppStateMachineProvider.create(context)`,
`validate()`, `query()` are all optional defaults). Existing machines compile
and run unchanged; the engine always calls the 3-arg form.

```java
// core-api — AppStateMachine additions
/**
 * Deterministic transition with effect emission (ADR-010 F1). The engine
 * always invokes this overload; the default delegates to the 2-arg form.
 * Everything forbidden in apply() is forbidden here — emit() records
 * intent, it never performs I/O.
 */
default void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
    apply(block, writer);
}

/**
 * Deterministic callback when a framework-processed effect outcome commits
 * (CONFIRMED/FAILED/CANCELLED via a sequenced ~fx/result message, EXPIRED
 * via the height-based sweep). Runs inside block application, before this
 * block's app messages are applied; writes join the same atomic commit.
 * Same determinism contract as apply(). Default: no-op.
 */
default void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
}
```

```java
// core-api — com.bloxbean.cardano.yano.api.appchain.effects
public interface AppEffectEmitter {
    /**
     * Record one effect intent; returns its deterministic id. Throws
     * EffectLimitExceededException — deterministically, on every node —
     * when the per-block count or payload-size cap is exceeded.
     */
    EffectId emit(EffectIntent intent);

    /** Pending-effect count (consensus-derived) — deterministic backpressure signal. */
    long pendingCount();
}

public record EffectIntent(
        String type,             // executor routing key: "cardano.payment", "webhook.post", ...
        byte[] payload,          // opaque canonical CBOR command for the executor
        String scope,            // app-level idempotency scope, e.g. "approvals/rel-42"
        FinalityGate gate,       // APP_FINAL | L1_ANCHORED | CHAIN_DEFAULT
        ResultPolicy result,     // CHAIN (outcome feeds back on-chain) | NONE (operator-only)
        long expiryBlocks,       // 0 = no expiry; else EXPIRED at createdHeight + expiryBlocks
        byte[] sourceMessageId) {// optional provenance link (nullable)
    public static Builder of(String type, byte[] payload) { ... }
    // builder defaults: scope="", gate=CHAIN_DEFAULT, result=NONE, expiryBlocks=0
}

public enum FinalityGate  { CHAIN_DEFAULT, APP_FINAL, L1_ANCHORED }
public enum ResultPolicy  { NONE, CHAIN }
public enum EffectOutcome { CONFIRMED, FAILED, CANCELLED, EXPIRED }
```

`TypedAppStateMachine` gains the matching `applyMessage(payload, envelope,
block, writer, effects)` default overload.

**Determinism mechanics.** `apply` is single-threaded per chain and messages
are totally ordered, so a plain in-memory per-block emission counter yields
identical ordinals on every node. Caps (`max-per-block`,
`max-payload-bytes`) are consensus parameters; exceeding them throws
deterministically inside `apply` — a diagnosable machine bug caught by the
conformance harness, never a divergence vector. `StateMachineConformance` is
extended to assert **identical ordered effect lists across replays** alongside
identical roots.

### F2 — Deterministic effect identity and record

```
effectId  = (chainId, height, ordinal)                  // ordinal = per-block emission counter
canonical = "<chainId>/<height>/<ordinal>"              // e.g. "payments/1042/0"
idHash    = blake2b-256("yano-fx-v1" || chainId || be64(height) || be32(ordinal))
```

Never UUID, random, wall clock, or auto-increment. `idHash` is the fixed-width
external key handed to every external system as the idempotency key on every
attempt (HTTP `Idempotency-Key` header, Cardano tx metadata, DB unique
constraint) — the Corda `deduplicationId` pattern.

Canonical effect record (definite-length CBOR array, fixed field order,
versioned):

```
EffectRecord = [ v(=1), chainId, height, ordinal, type, payload,
                 scope, gate(0|1), result(0|1), expiryHeight, sourceMessageId / null ]
effectHash   = blake2b-256(canonical record bytes)
```

The **id** binds position; the **hash** binds content. For sensitive payloads
the record MAY carry `payloadHash` + out-of-band body instead (F11).

### F3 — Storage: two column families, split by trust tier

Two new CFs in the app ledger RocksDB (covered by snapshot checkpoints for
free):

**`fx_records` — consensus tier.** A pure function of the block stream;
identical on every node; written in the same atomic `WriteBatch` as
`commitBlock` (block + tip + trie nodes + indexes), via the same staging
mechanism as `GovernedMembership.MetaWrite`.

| Key | Value | Written |
|---|---|---|
| `r` ‖ be64(height) ‖ be32(ordinal) | EffectRecord CBOR | at block commit (emission) |
| `n` ‖ be64(height) | count(4BE) ‖ effectsRoot(32) | at block commit when count > 0 |
| `x` ‖ be64(expiryHeight) | CBOR list of (height, ordinal) expiring there | at block commit (emission with expiry) |

**`fx_runtime` — node-local tier.** Never replicated, never proven, freely
rewritten by the Effect Runtime, disposable (rebuildable, F10).

| Key | Value |
|---|---|
| `s` ‖ be64(height) ‖ be32(ordinal) | StatusRecord: {status, attempts, nextAttemptAt, lease, executorId, lastError, submittedRef, resultHash} |
| `q` ‖ gate(1) ‖ be64(height) ‖ be32(ordinal) | (empty) pending-queue row; deleted on terminal |
| meta | `fx_intake_cursor` — last block folded into the queue |

**Why the split is the design's center of gravity:** status writes (a retry
loop, a lease refresh) are ordinary local puts — no consensus round, no root
recomputation, no gossip. Two nodes may legitimately disagree about
`attempts=3` vs `attempts=0`; only *terminal outcomes* that the application
depends on go through consensus (F8). This is the taxonomy:

| Fact | Tier |
|---|---|
| effect emitted at (H, i) with content C | consensus (record + effectsRoot) |
| pending / leased / executing / retry progress | runtime-local |
| CONFIRMED / FAILED / CANCELLED with external ref (app-visible) | consensus (`~fx/result` → `~fx/done` leaf + `onEffectResult`) |
| EXPIRED | consensus (deterministic height sweep) |
| operator-only outcome of a `ResultPolicy.NONE` effect | runtime-local |

**Pruning:** records and statuses with a terminal outcome older than
`effects.retention.keep-blocks` are range-deleted by the existing retention
tick. The `n` rows (44 bytes per effectful block) may be kept indefinitely.

### F4 — The `effectsRoot` commitment and the reserved trie namespace

```
effectsRoot(H) = merkleRoot([effectHash_0 … effectHash_{n-1}])   // ordered, blake2b-256
trie leaf:  key = "~fx/root/" ‖ be64(H)      value = effectsRoot(H)   (only when n > 0)
```

The merkleizer promotes an odd node UNCHANGED to the next level (pass-through,
never duplicated), so lists differing only by a repeated trailing leaf produce
different roots — the CVE-2012-2459 malleability class is excluded by
construction. This deliberately differs from the legacy duplicate-promotion in
`messagesRoot`, which is frozen by the shipped block format; aligning
`messagesRoot` is deferred to the next block-version bump.

`AppChainEngine.applyBlock()` inserts this leaf **after** `stateMachine.apply`
returns and **before** `trie.getRootHash()`. Consequences:

- The block's existing `stateRoot` — already threshold-signed in the finality
  cert and anchored to L1 — transitively commits to the full ordered effect
  list. **No block wire format change** (block `version` stays 1); promotion
  to a native `AppBlock.effectsRoot` field is deferred to the next wire bump,
  at which point the leaf is dropped for new blocks.
- A follower whose machine build emits differently produces a different leaf
  ⇒ different root ⇒ block rejected by the **existing** byte-for-byte check.
  Emission bugs are caught by the same mechanism that catches state bugs.
- Proof of "effect E with content C was emitted at height H, position i":
  (1) canonical record bytes ⇒ effectHash; (2) Merkle list path ⇒
  effectsRoot(H); (3) MPF inclusion proof of the `~fx/root/H` leaf against
  stateRoot(H); (4) stateRoot(H) under the threshold cert / L1 anchor.
  Additionally the list root gives **completeness**: given the record list, a
  verifier knows no effect was suppressed.

**Reserved trie namespace (required companion change).** The `~fx/` prefix is
reserved in the *trie keyspace* **from genesis, regardless of whether effects
are enabled** — mirroring the `~` topic reservation. The machine writer
deterministically rejects application keys beginning with `~fx/`;
framework-internal writes bypass the guard. Making the reservation
unconditional (decided during the FX-M1 review, before any release ships the
feature) guarantees that enabling effects later can never collide with
historical application leaves in the append-only trie. One-time audit of
stdlib machines (printable prefixes — clean) and a release note. For machines
using raw 32-byte hash keys the collision probability with the 4-byte prefix
is 2⁻³²; `effects.strict-reserved-prefix=false` is the documented escape hatch
— note it is **consensus-affecting** (it changes which apply() calls fail) and
must match on all members like every other effects setting.

### F5 — Effect Runtime and executor SPI

A dedicated `EffectRuntime` per chain — built on the *pattern* of `SinkRunner`
(persisted cursor idiom, scheduler cadence, at-least-once) but **not** on the
sink interface, for three reasons: sinks are per-block and head-of-line (one
failing delivery blocks the stream — a dead webhook must not delay an
unrelated payment); sinks deliver at APP_FINAL only (effects gate per-effect);
sinks are fire-and-forget (effects have results, retries and a poison lane).

```java
// core-api — ServiceLoader-discovered, like FinalizedStreamSinkFactory
public interface AppEffectExecutor extends AutoCloseable {
    String id();
    boolean supports(String effectType);
    /**
     * Execute one effect. At-least-once contract: MUST be idempotent keyed
     * on ctx.effectId()/idHash() — the same effect may be re-attempted after
     * a crash, restart or executor failover. Throw to retry with backoff.
     */
    EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) throws Exception;
    @Override default void close() {}
}

public sealed interface EffectExecution {
    static EffectExecution confirmed(byte[] externalRef);            // done, success
    static EffectExecution confirmed(byte[] ref, byte[] detailHash); // + off-chain detail doc
    static EffectExecution failed(String reasonCode, boolean retryable);
    static EffectExecution submitted(byte[] externalRef);  // long-running: local progress,
                                                           // re-polled next ticks (e.g. L1 tx
                                                           // awaiting confirmation depth)
    static EffectExecution retry(Duration notBefore);
}

public interface AppEffectExecutorFactory {                 // ServiceLoader
    String scheme();                                        // config ns: effects.executors.<scheme>.*
    List<AppEffectExecutor> create(String chainId, Map<String, String> config);
}
```

Runtime tick (default 2 s, own scheduler, sibling of `sinkTick`):

```
tick():
  # 1 INTAKE — fold newly committed blocks into the local queue
  for h in (fx_intake_cursor+1 .. tipHeight):
      for rec in fx_records.byHeight(h): queue.add(rec); status(rec)=PENDING
      fx_intake_cursor = h

  # 2 GATE + DISPATCH — per effect, no head-of-line blocking
  anchored = meta("anchor_last_height")
  for (gate, height, ordinal) in queue.scan():                # (height, ordinal) order
      if gate == L1_ANCHORED and height > anchored - anchorMargin: continue
      st = status(height, ordinal)
      if st in {LEASED, EXECUTING} and lease alive: continue
      if st == RETRY and now < nextAttemptAt: continue
      if st == PARKED: continue                               # operator lane, F9
      ex = registry.find(rec.type); if ex == null: park("no executor"); continue
      lease(); pool.submit(run(rec, ex))                      # bounded parallelism

run(rec, ex):
  outcome = ex.execute(ctx, rec)         # exceptions → attempts++, exp. backoff + jitter
  CONFIRMED/FAILED  → if rec.result == CHAIN: inject ~fx/result (F8)
                      else: local terminal (DONE/DEAD), delete queue row
  SUBMITTED(ref)    → record locally; re-poll next tick (executor checks the
                      external system by idHash/ref and returns CONFIRMED/FAILED)
  attempts ≥ max    → PARKED + alert; operator requeues/cancels via REST (F12)
```

The queue row of a `ResultPolicy.CHAIN` effect is deleted when the sequenced
terminal commits (the interpreter clears it, F8) — completion is recorded in
replicated state, not in a local high-water mark, so replay and restart can
never re-fire a chain-terminal effect.

**Deployment models** (all supported by the same runtime):

- **Embedded**: `effects.executor.enabled=true` on exactly one member node.
- **Dedicated executor node**: a member node running no app workload, fronting
  the executor fleet — recommended for production.
- **External executors** (no member key, different team/language, secrets kept
  outside Yano): claim and report over REST (F12); the fronting member node
  validates and re-signs results. Constraint "only member keys author
  messages" is never relaxed — the member key attests "I accepted this outcome
  from my configured executor".

### F6 — Executor selection and claiming

Survey verdict (§4): executor selection is an efficiency mechanism —
correctness always comes from deterministic incorporation + idempotency. The
design therefore ships, in order of escalation:

1. **Single designated executor (default).** Operators enable the runtime on
   one node (the existing anchor-leader role is the precedent). Zero
   coordination traffic. Executor down ⇒ effects wait, bounded by expiry;
   `fx_pending`/oldest-age metrics alarm.
2. **Standby stagger** (`standby-delay-blocks=k`): executor with rank r only
   touches effects pending longer than r·k blocks. Cheap HA; brief overlap
   produces duplicate *attempts*, absorbed by idempotency — never divergent
   state (the interpreter is first-result-wins).
3. **Deterministic partition**: multiple executors with disjoint
   `executor.types` lists, or assignment by `hash(idHash) mod executors`.
4. **On-chain claims (deferred, not v1).** A `~fx/claim` sequenced message
   with height-denominated leases, for effect types where even duplicate
   attempts are expensive. Deliberately deferred: it costs a consensus round
   per effect, and lease clocks are subtle — app-chain height stalls with the
   chain; if built, leases MUST be denominated in **L1 slot** (already in
   every block, monotonic) rather than app height or wall clock.

Misconfiguration (two nodes enabled for the same type) degrades to duplicate
attempts, never divergent state. This MUST be documented loudly, and the
runtime is **off by default on every node**.

### F7 — Finality gating

Per-effect `FinalityGate`, evaluated by the runtime each tick:

- **APP_FINAL** (default): eligible once the block is committed. Because the
  app ledger is append-only after the threshold cert, *every committed block
  is already irrevocable* — the output-commit condition is satisfied at
  intake. An emitted intent can never be un-emitted.
- **L1_ANCHORED**: eligible once `createdHeight ≤ anchor_last_height −
  anchor-margin-blocks` **and** the anchor tx has reached the configured L1
  stability depth (reusing the L1ObservationService stability machinery). For
  counterparties who only trust Cardano: the emission is *externally provable*
  before the action fires.

**L1 rollback semantics (precise, and pleasantly simple):** an L1 rollback
rewinds the `anchor_last_height` high-water-mark and the anchor resubmits —
but the app ledger never rolls back, so the emitted intent remains final. The
executed set is **monotonic by effectId**: a HWM rewind pauses *new*
L1_ANCHORED dispatches above the mark; it never re-opens or invalidates an
executed effect. `L1_ANCHORED` is a verifiability delay, not a rollback-safety
mechanism, and no compensation logic exists anywhere in the design — a direct
dividend of the append-only ledger. A reorg deeper than the stability depth
after execution is a documented accepted risk, identical to the chain's
existing anchor trust assumption.

### F8 — Result feedback: `~fx/result`

Terminal outcomes that the application depends on re-enter the chain as
member-signed, **sequenced** messages on a new reserved topic — exactly the
`~l1/*` internal-injection path (external `submit()` already rejects `~`
topics):

```
topic: ~fx/result
body:  [ v(=1), chainId, height, ordinal,            // the effectId
         outcome(1 CONFIRMED | 2 FAILED | 3 CANCELLED),
         externalRef(bstr ≤128),                     // canonical handle: txHash, CID, resource id
         detailHash(bstr32 | null) ]                 // commitment to off-chain detail document
```

**Canonicalized handles only — raw response bodies never go on-chain** (the
ICP transform-function lesson): the executor extracts exactly the fields the
machine needs; anything bulky hangs off `detailHash` with the document in the
executor's archive.

At block application the **framework interpreter** processes `~fx/result`
messages *before* handing the block to the machine (they always reference
strictly older effects — ids don't exist until apply assigns ordinals):

```
for each ~fx/result in block order:                      # deterministic, fail-closed
    rec = fx_records.get(effectId)                       # consensus tier ONLY, never fx_runtime
    skip deterministically (audit no-op) if:
        unknown id | malformed | out-of-bounds sizes
        | trie has ~fx/done/<idHash>                     # ← first result wins
        | record already EXPIRED
    write trie leaf  ~fx/done/<idHash> = blake2b-256(result envelope)
    machine.onEffectResult(block, result, writer)        # app records txHash etc. — into the root
    stage: delete pending-queue row; schedule record pruning
```

Properties:

- **Exactly-once incorporation**: duplicate results (executor retry, standby
  overlap, malicious replay) hit the `~fx/done` guard and no-op identically on
  every node. A result message can **never stall the chain** — every invalid
  case is a deterministic no-op (explicit contrast with `~l1` MISMATCH, which
  stalls by design).
- **Trust model, stated plainly**: a result is a *member attestation*, not an
  independently verifiable observation. Followers verify authorship
  (membership + signature — already enforced by the envelope layer), not the
  external world. A lying member attesting a false result has the same power
  as a lying proposer and is equally attributable (signed, sequenced,
  permanent). Escalations, in order: restrict accepted signers to the
  designated executor's member key (proposal-time check); require k-of-n
  co-attestation for declared high-value types (the `~anchor/sig`
  threshold-collection precedent); and where the fact is L1-visible (payments,
  anchors), verify via the existing `L1Observer` recompute path instead of
  trusting any attestation.
- The `~fx/done` leaf doubles as the **audit anchor for the outcome**: intent
  provable against block H (effectsRoot), outcome provable against block H′
  (done leaf + the result message under H′'s messagesRoot). Symmetric proof
  story, both under threshold certs and L1 anchors.
- Executor result submission MUST survive `PoolFullException`, message TTL
  expiry and member-key rotation: results are persisted in a durable local
  queue and re-signed/resubmitted until the sequenced terminal is observed.

**Outcome commitment modes (`effects.outcome-commitment`).** How incorporated
outcomes are committed into the trie is a per-chain (consensus-affecting)
choice, sized to expected CHAIN-result volume:

- **`per-effect` (default)**: one `~fx/done/<idHash>` leaf per incorporated
  outcome, as described above. O(1) positive proofs *and* O(1) non-inclusion
  proofs ("as of root R, effect E is still open"). Trie grows O(chain-result
  effects) — fine for typical enterprise volumes, heavy at millions.
- **`per-block`**: the interpreter batches the block's **incorporated**
  outcomes (accepted results + expiry-sweep transitions, in processing order)
  into one `resultsRoot` leaf `~fx/results/<height>` — the exact mirror of
  `effectsRoot`. First-result-wins dedup switches to the consensus-tier
  record status in `fx_records` (equally deterministic — the interpreter
  never reads `fx_runtime`). Trie grows O(effectful blocks) regardless of
  effect volume.

Provability comparison — what `per-block` keeps and what it trades away:

| Proof | `per-effect` | `per-block` |
|---|---|---|
| outcome incorporated, content-bound (positive) | 1-step MPF proof | 2-step (list path → MPF leaf) — same trust chain, same shape as emission proofs |
| result was sequenced | via `messagesRoot` (both modes, always) | same |
| effect still open as of root R (negative) | O(1) MPF non-inclusion | O(blocks since emission) absence sweep — impractical for old effects |
| latest-root outcome lookup | single self-service state query | needs an (untrusted) height hint from a node, then verify |

The negative-proof gap is bounded wherever expiry is set: an effect with
`expiryBlocks=k` is provably resolved-or-expired by `createdHeight+k`, so an
openness proof never spans more than k blocks. High-volume chains choosing
`per-block` SHOULD therefore make expiry mandatory on CHAIN-result types. A
later refinement may allow per-type override (per-effect leaves only for
high-value types). Note the symmetric caveat: the alternative volume lever —
deleting done leaves after retention — also forfeits non-inclusion semantics
beyond the retention window; batching just makes the trade explicit up front.

`ResultPolicy.NONE` effects (webhooks, notifications, cache invalidation —
nothing in app state depends on them) terminate in the runtime tier only:
DONE/DEAD with evidence in `fx_runtime`, surfaced via REST/metrics, zero chain
footprint.

### F9 — Expiry, poison lane, cancellation

- **Expiry (deterministic timeout) — MANDATORY for CHAIN effects.** A result
  arriving after `result-window-blocks` is a deterministic no-op, so an
  unexpirable CHAIN effect could stay open forever (FX-M3 review finding): the
  kernel defaults `expiryBlocks=0` to `min(max-expiry-blocks,
  result-window-blocks)` and rejects values beyond the result window — every
  CHAIN effect provably closes while results are still incorporable. Emission
  with `expiryBlocks=k` registers the id in the `x` height bucket at
  `createdHeight+k`. When block `H` is applied, the interpreter reads bucket
  `x/H` (an O(1) CF lookup — no iteration) and transitions every still-open
  CHAIN effect to **EXPIRED**:
  `~fx/done` leaf written, `onEffectResult(EXPIRED)` invoked. Purely
  height-driven ⇒ deterministic without any message. Late results for expired
  effects are deterministic no-ops; executors treat approaching expiry as a
  stop sign (`expiryHeight` is visible in `PendingEffect`). This is IBC's
  ack-vs-timeout split: **FAILED = the target answered "no"; EXPIRED = nobody
  answered in time.** Two distinct, deterministic escape hatches.
- **Poison lane.** After `max-attempts`, the runtime parks the effect
  (`PARKED` + diagnostics) — it never blocks other effects (per-effect retry
  state, no head-of-line), never silently skips (the EventStoreDB/Axon
  park-and-replay lesson), and alerts. Operators requeue or cancel via REST.
- **Cancellation.** A member-signed `~fx/result` with outcome CANCELLED (or
  the admin REST wrapper around it). Effective only while no terminal exists;
  the executor re-checks status immediately before firing
  (checkUpkeep/performUpkeep discipline), but the race window is accepted and
  documented: **cancel cannot unsend** — an in-flight execution may still land
  and its late result then no-ops against the CANCELLED terminal.
- **Ordering.** Effects are **unordered by default** (retries reorder;
  ordered queues die on poison — IBC ordered-channel lesson). Applications
  needing per-key ordering encode it in `scope` and the executor serializes
  per scope.

### F10 — Replay, restart, snapshot restore, versioned emission

- **Warm restart**: cursor, statuses and leases are persisted in `fx_runtime`;
  the runtime resumes. Effects caught mid-EXECUTING sit behind an expired
  lease and re-dispatch — the at-least-once path, absorbed by idempotency.
- **Catch-up replay** (fresh follower): replaying `apply` regenerates
  `fx_records` identically — the outbox self-heals from the block stream by
  construction. Replay re-runs the *consensus plane* only; the runtime never
  fires from replay (executor disabled by default; where enabled, chain-
  terminal effects have `~fx/done` leaves and cleared queue rows).
- **Snapshot restore**: the RocksDB checkpoint covers both CFs; a restored
  executor resumes with its own honest view — genuinely in-flight effects
  re-execute (correct), completed ones don't. The state root does not depend
  on any of it.
- **Enabling the executor late / backfill quarantine**: when the runtime is
  first enabled on a node, historical open effects older than
  `executor.backfill-quarantine-blocks` are marked QUARANTINED and require
  operator acknowledgment before firing — a restored or newly promoted
  executor never blind-fires stale history.
- **Rebuild-on-corruption**: if the outbox CFs are lost or fail integrity
  checks (record hashes vs the `effectsRoot` leaves — the commitment doubles
  as a checksum), drop and rebuild by replay; then reconstruct status from the
  chain: `~fx/done` present ⇒ terminal; CHAIN effect without done-leaf ⇒
  PENDING (re-execute; idempotent); NONE effect ⇒ SKIPPED (no evidence either
  way — never guess with emails). **Consensus-recorded results double as
  durable, replicated execution receipts** — disaster recovery is a chain
  query, not guesswork.
- **Versioned emission (hard requirement).** Emission logic is part of the
  replayed transition: a machine upgrade that changes *what gets emitted for
  old blocks* changes historical effectsRoots and breaks catch-up exactly like
  any state-transition change. Emission changes MUST ship behind version
  markers activated at a block height (the `GovernedMembership`
  activation-lag pattern; Temporal `getVersion` analog); replay selects the
  emitter logic active at each block's height. The conformance harness covers
  replay-across-versions.

### F11 — Security and privacy

1. **Secrets never in effect records.** Payloads carry credential
   *references* (`credentialId`); API keys/SMTP credentials live only in
   executor configuration (and later KMS/HSM via the E4.3 SignerProvider
   pattern). Effect records are replicated to every member, exported in
   snapshots, and (via effectsRoot) committed forever.
2. **PII discipline / payload-by-hash.** For sensitive command bodies the
   record carries `payloadHash` with the body delivered out-of-band (encrypted
   message body per E4.2, or an operator side channel). Nothing PII-bearing
   goes into the trie (the `~fx` leaves carry only hashes by design); `scope`
   strings must not embed sensitive business data.
3. **Least-privilege executors.** Per-type executor allowlists; an external
   executor credential is scoped to claim/report specific types on specific
   chains. A compromised executor can fire arbitrary *supported* actions —
   scope the blast radius and alert on attempt anomalies.
4. **Emission caps** (`max-per-block`, `max-payload-bytes`) bound chain bloat
   and gossip cost deterministically; `pendingCount()` gives machines a
   deterministic backpressure signal to defer or reject new work when the
   pending set is large (e.g. executor outage).
5. **Result authorization**: accepted signer policy per chain (any member ⇒
   designated executor's member only ⇒ k-of-n) — see F8 trust model.

### F12 — Configuration, modules, REST, observability

```yaml
yano:
  app-chain:
    effects:
      enabled: false                    # CONSENSUS-AFFECTING: must match on all members;
                                        # ONE-WAY: cannot be disabled once effects are open
                                        # (the expiry sweep only runs while enabled — the
                                        # engine refuses to start otherwise)
      max-per-block: 256                # consensus parameter
      max-payload-bytes: 16384          # consensus parameter
      max-expiry-blocks: 100000         # consensus parameter; also the height-overflow guard
      default-gate: app-final           # app-final | l1-anchored
      outcome-commitment: per-effect    # per-effect | per-block (CONSENSUS-AFFECTING; F8)
      strict-reserved-prefix: true      # CONSENSUS-AFFECTING; active even when enabled=false
      retention:
        keep-blocks: 100000             # prune terminal records/statuses
      gate:
        anchor-margin-blocks: 0
        l1-stability-depth: 6
      executor:
        enabled: false                  # OFF by default on every node
        id: fx-exec-1
        types: []                       # empty = all supported; else allowlist partition
        tick-ms: 2000
        max-parallel: 4
        max-attempts: 8
        backoff-initial-ms: 2000
        backoff-max-ms: 300000
        standby-delay-blocks: 0
        backfill-quarantine-blocks: 600
      external:
        enabled: false                  # REST claim/report surface for external executors
      executors:                        # per-executor factory config (scheme-keyed)
        webhook.post:
          timeout-ms: 10000
        cardano.payment:
          wallet: default
          metadata-label: 21042
```

Consensus-affecting keys (`enabled`, `max-per-block`, `max-payload-bytes`,
`default-gate`) are chain config like `threshold` — a mismatch across members
diverges roots at the first emission; same coordination discipline as changing
the state machine version; startup cross-check recommended.

| Module | Contents |
|---|---|
| `core-api` (`api.appchain.effects`) | `AppEffectEmitter`, `EffectIntent`, `EffectId`, `EffectRecord`, `EffectResult`, `PendingEffect`, `AppEffectExecutor` (+ Factory/Context/Execution), enums, codec; the two `AppStateMachine` default methods; `TypedAppStateMachine` overload; CDDL for record/result |
| `runtime` (`runtime.appchain.effects`) | `FxKernel` (effectsRoot leaf, result interpreter, expiry sweep — inside `AppChainEngine.applyBlock`), `EffectOutboxStore` (CFs riding the commit batch), `EffectRuntime` (intake/gate/dispatch/park), `ResultInjector` (internal `~fx/result` path), reserved-prefix guard in `BatchStateWriter`; built-in `webhook.post` executor |
| `appchain/extensions/appchain-effects-cardano` | `cardano.payment` / `cardano.metadata` executors (CCL tx building; dedupe via metadata label carrying idHash — query before submit); keeps CCL deps out of runtime, mirrors `appchain-kafka-sink` |
| `app` (Quarkus) | `EffectsResource`, metrics, config mapping |
| `appchain-spring-boot-starter` | `yano.app-chain.effects.*` properties; auto-register `AppEffectExecutor` beans |
| `appchain-testkit` | emission assertions in `StateMachineConformance`; fake executor; deterministic result/expiry injection harness |

REST (chain-scoped under the existing `/api/v1/app-chain/chains/{chainId}`,
behind the existing API-key auth):

```
GET  /effects?status=&type=&fromHeight=&limit=       list (runtime view over consensus records)
GET  /effects/{height}/{ordinal}                     record + status + proof material
GET  /effects/{height}/{ordinal}/proof               effectHash → list path → MPF proof of ~fx/root/H
POST /effects/{height}/{ordinal}/requeue             operator: PARKED/QUARANTINED → PENDING
POST /effects/{height}/{ordinal}/cancel              operator: inject CANCELLED result
POST /effects/claim                                  external executor: {types,max,leaseSeconds}
POST /effects/{height}/{ordinal}/result              external executor: report outcome (lease-checked)
GET  /effects/stats                                  counters by status/type, oldest-pending age
```

Metrics from day one: `fx_pending`, `fx_parked`, `fx_executed_total`,
`fx_expired_total`, oldest-pending age, per-type execution latency, unsubmitted
result backlog. Admin actions are member-signed sequenced messages → audit
trail for free.

## 8. Use cases

### 8.1 Expense approval → Cardano payment (full worked flow)

The stdlib `ApprovalsStateMachine` (E2.2) with payments enabled
(`machines.approvals.payments=true`). PROPOSE carries the payment body
`[address, lovelace]` which the machine parks under its own `p/<itemId>` key
until approval.

```java
@Override
public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
    for (AppMessage message : block.messages()) {
        // ... existing decode / PROPOSE / deadline logic unchanged ...
        if (command.op() == OP_APPROVE) {
            // ... dedupe, add approver ...
            int status = approvers.size() >= item.required() ? STATUS_APPROVED : STATUS_PENDING;
            writer.put(itemKey, item.with(status, approvers).encode());
            if (status == STATUS_APPROVED && payments) {
                byte[] paymentKey = payloadKey(command.itemId());
                writer.get(paymentKey).ifPresent(payment -> {
                    EffectId id = effects.emit(EffectIntent.of("cardano.payment", payment)
                            .scope("approvals/" + command.itemId())   // one payment per item, ever
                            .gate(FinalityGate.L1_ANCHORED)           // provable before funds move
                            .result(ResultPolicy.CHAIN)
                            .expiryBlocks(1_000)
                            .sourceMessageId(message.getMessageId())
                            .build());
                    writer.put(fxLinkKey(command.itemId()), id.canonical().getBytes(UTF_8));
                    writer.delete(paymentKey);
                });
            }
        }
    }
}

@Override
public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
    if (!"cardano.payment".equals(result.type()) || !result.scope().startsWith("approvals/")) return;
    String itemId = result.scope().substring("approvals/".length());
    writer.get(itemKey(itemId)).ifPresent(entry -> {
        Item item = Item.decode(entry);
        if (item.status() != STATUS_APPROVED) return;              // deterministic no-op
        writer.put(itemKey(itemId), item.withStatus(
                result.outcome() == EffectOutcome.CONFIRMED ? STATUS_PAID : STATUS_PAY_FAILED)
                .encode());
        if (result.outcome() == EffectOutcome.CONFIRMED) {
            writer.put(txHashKey(itemId), result.externalRef());   // txHash into app state
        }
    });
}
```

End-to-end:

```
apply(H=1042): 2-of-3 reached → item APPROVED → emit cardano.payment
               → fx_records row (1042,0) + ~fx/root/1042 leaf   [one atomic commit; root binds intent]
cert → anchor tx → anchor_last_height ≥ 1042 (+ stability depth)
runtime tick:  gate open → CardanoPaymentExecutor: probe L1 by metadata label (idHash)
               → not found → build tx (payee, amount, metadata {label: idHash}) → submit
               → SUBMITTED(txHash) locally → later tick: tx at depth 6 → CONFIRMED(txHash)
inject ~fx/result (member-signed) → sequenced into block H′=1057
apply(H′):     interpreter: no ~fx/done yet → write ~fx/done/<idHash>
               → onEffectResult: item PAID, txHash recorded      [root binds outcome]
audit:         intent provable vs stateRoot(1042), outcome vs stateRoot(1057),
               both threshold-signed and L1-anchored; payment body verifiable vs record hash
```

A crash after tx submission but before result injection re-attempts on
restart; the metadata-label probe finds the existing tx and returns CONFIRMED
without double-paying — the reference implementation of the idempotency
contract.

### 8.2 Supply chain: shipment approval → ERP warehouse release

Approvals (or a custom machine) emits on final approval:

```java
effects.emit(EffectIntent.of("erp.command",
        cbor(Map.of("cmd", "RELEASE_SHIPMENT", "shipmentId", shipmentId, "qty", qty)))
    .scope("shipments/" + shipmentId)
    .gate(FinalityGate.APP_FINAL)          // internal system; cert finality suffices
    .result(ResultPolicy.CHAIN)            // ERP release ref recorded in chain state
    .expiryBlocks(2_880)
    .build());
```

The ERP executor POSTs with `Idempotency-Key: <idHash>`; the ERP either
dedups on the key or the integration keeps a `(idHash → releaseRef)` table
with a unique constraint. CONFIRMED carries the ERP release reference; the
machine marks the shipment RELEASED with the reference. If the ERP rejects
(insufficient stock) → FAILED with a reason code → machine marks the shipment
BLOCKED and the workflow continues on-chain (new approval round). If the ERP
is down past expiry → EXPIRED → deterministic, auditable "nobody released it
in time".

### 8.3 Document trail → IPFS pin

`DocTrailStateMachine` registers a document (content hash on-chain, E2.4) and
emits `ipfs.pin` with `{cid}` (`ResultPolicy.CHAIN`, gate APP_FINAL). The IPFS
executor pins against the cluster — pinning is naturally idempotent by CID —
and confirms with the CID as `externalRef`; `onEffectResult` marks the
document PINNED. Auditors can prove both "registration happened at H" and
"pin was confirmed at H′" from block headers alone.

### 8.4 KYC / credential issuance (VC)

A membership/credential machine approves a subject and emits `vc.issue` whose
record carries only `payloadHash` — the issuance request body (holder DID,
claims) travels out-of-band to the credential service (payload-by-hash, F11:
PII never in replicated records). The executor calls the issuer service with
idempotency key idHash; CONFIRMED returns the credential id (and a
`detailHash` committing to the signed VC document held in the issuer's
archive). The machine records `credentialId` against the subject. Revocation
later is simply another effect (`vc.revoke`).

### 8.5 DevOps: deployment approval → CI dispatch

An approvals flow gates production deployment; on approval the machine emits
`webhook.post` targeting the GitHub Actions dispatch endpoint
(`ResultPolicy.NONE` — no chain state depends on the outcome; the deployment
evidence lives in the CI system). The built-in webhook executor fires with the
idempotency header; outcome (DONE/DEAD + HTTP status) is visible in
`/effects/stats` and metrics only. Zero chain footprint beyond the emission
record — which is still provable ("deployment X was authorized at height H").

## 9. Failure modes and mitigations (from the adversarial review)

| # | Failure mode | Mitigation in this design |
|---|---|---|
| 1 | Emission-logic upgrade breaks replay (roots diverge; node can never sync) | F10 versioned emission with activation heights — hard requirement; conformance replay tests |
| 2 | N-times execution (embedded runtime on every member) | executor OFF by default; single designated executor; partitions; idempotency as last line (F5/F6) |
| 3 | Forged/false results (authority trust) | fail-closed interpreter; signer policy up to k-of-n; L1-visible facts verified via `L1Observer` instead (F8) |
| 4 | Crash between external call and result submission | at-least-once by design; durable local intent journal + probe-by-idHash before re-fire; retry-safety classes per type (F5, 8.1) |
| 5 | Lease clocks unsound (stalled chain / proposer clock skew) | no leases in v1; failover = standby stagger or explicit reassignment; future leases in L1 slots (F6) |
| 6 | Poison effect starves the pipeline | per-effect retry state, no head-of-line; attempt cap → PARKED + alert + replay endpoint (F9) |
| 7 | Result message never sequenced (pool full, TTL, key rotation) | durable result queue, re-sign + resubmit until terminal observed (F8) |
| 8 | Snapshot restore / late-enabled executor re-fires history | chain-recorded terminals + queue rows cleared at terminal commit; backfill quarantine (F10) |
| 9 | L1 rollback rewinds anchor HWM after execution | monotonic executed set; stability-depth gate; deep-reorg accepted risk (F7) |
| 10 | Reserved trie-key collision / app writes to `~fx/*` | deterministic `BatchStateWriter` guard + escape hatch (F4) |
| 11 | Pending-set growth, emission spam, oversized payloads | deterministic caps, expiry, `pendingCount()` backpressure, metrics (F11) |
| 12 | Lifecycle races (dup/late/orphan results, cancel vs in-flight) | single deterministic transition table; terminal states absorbing; every illegal transition = audit no-op (F8/F9) |
| 13 | App machine mis-parses `~` messages | framework interprets `~fx/*`; machines get the typed `onEffectResult` hook; stdlib skips `~` topics (F8) |
| 14 | PII/secrets in replicated/authenticated state | payload-by-hash, credential references, scope discipline (F11) |
| 15 | Stuck effects invisible until business impact | status surface, metrics, oldest-pending alarms, member-signed admin ops (F12) |

## 10. Hard requirements (MUST list)

1. `apply()` stays pure — emission is declarative data; no I/O on the apply
   path. The existing determinism contract extends verbatim to emission.
2. Emission logic MUST be versioned with activation heights; replay uses the
   version active at each block's height.
3. Effect discovery MUST read only the atomically-committed record set —
   never live `apply()` observation, never a tip-initialized sink cursor,
   never SinkRunner's retry-same-block ordering.
4. Executor OFF by default on every node; at most one active executor per
   effect-type partition by explicit operator decision.
5. Idempotency key = stable function of (chainId, height, ordinal), identical
   across retries/attempts/failover, passed to the external system on every
   attempt. Every effect type declares its retry-safety expectations.
6. The result interpreter is deterministic and fail-closed: terminal states
   absorbing; malformed/duplicate/late/orphan results are audit-logged no-ops;
   a result message can NEVER stall the chain.
7. Results are authority-based and the documentation says so; signer policy is
   enforceable per chain; high-value types can require k-of-n attestation.
8. Finality gating per effect; the executed set is monotonic across anchor
   HWM rewinds; L1_ANCHORED includes a stability-depth condition.
9. Payload-by-hash for sensitive bodies; secrets never in records; nothing
   PII-bearing in the MPF trie (append-only node store = permanent).
10. Bounded pending state: deterministic per-block emission cap, payload cap,
    per-effect attempt cap → PARKED, optional deterministic expiry.
11. `BatchStateWriter` rejects app writes to the reserved `~fx/` prefix,
    deterministically.
12. Executor result submission survives pool-full, message expiry and member
    key rotation (durable, re-signable result queue).
13. Status/metrics surface and member-signed admin operations
    (cancel/requeue/park) ship with v1, not later.

## 11. Deviations from the initial sketch

The initial idea document proposed the broad shape adopted here (effects as
data, emitted deterministically, executed by a pluggable runtime, results as
messages). The exploration changed these specifics, each for a
codebase-verified reason:

| Initial sketch | This ADR | Why |
|---|---|---|
| Effects stored as authenticated state (`effects/` namespace in the tree) | Outbox CF + per-block `effectsRoot` trie leaf (B+) | MPF has no prefix scan (a CF index is needed regardless); MPF node store is append-only (payloads would be permanent); per-effect leaves bill hashing into every apply on every node — see §6 |
| `PENDING → CLAIMED → SUBMITTED → CONFIRMED` all as chain state | Only EMITTED + terminal outcomes on the consensus plane; CLAIMED/SUBMITTED/attempts are runtime-local | intermediate statuses are coordination data, not audit data; each on-chain hop = a sequenced message + root recomputation on every node for information no auditor needs (the txHash still lands in CONFIRMED) |
| Generic claiming protocol with block-height leases | Default: designated executor + standby stagger + idempotency; on-chain claims deferred | every surveyed system: executor selection is efficiency, not correctness; height-denominated leases never expire on a stalled chain — if leases are ever built they must use L1 slots |
| `effectId = hash(appchainId, blockNumber, messageIndex, effectIndex)` | `(chainId, height, ordinal)` + domain-separated hash | equivalent intent; ordinal-per-block is simpler than per-message indexing and survives framework-emitted transitions |
| Execution result "submitted as another appchain message" (unspecified author) | Member-signed `~fx/result` on a reserved topic via the internal injection path; external executors report through a fronting member node | verified constraint: only current member keys can author messages; external `submit()` rejects `~` topics |
| Finality policies incl. `L1_CONFIRMED`, `ZK_SETTLED` | v1: APP_FINAL, L1_ANCHORED (with stability depth); ZK gate deferred | app finality is already irreversible (append-only after cert) — "committed" and "final" coincide; the ZK gate slot is reserved (§13) |
| — | EXPIRED as a first-class deterministic outcome, distinct from FAILED | IBC's ack-vs-timeout split: liveness failure needs an escape hatch that doesn't depend on any executor being alive |
| — | Exactly-once *incorporation* / at-least-once *execution* stated as the formal contract | the honest, provable guarantee boundary (§2) |

## 12. Drawbacks

- A new subsystem: two CFs, a runtime, an interpreter, executor plugins,
  admin surface — real implementation and operational cost.
- External execution is eventually consistent with chain state by nature;
  applications must model in-flight windows (PENDING/awaiting-result states in
  their own schemas).
- The consensus-plane changes (`effectsRoot` leaf, reserved prefix, consensus
  config caps) are chain-coordination-affecting: enabling effects on an
  existing chain requires the same discipline as a state-machine upgrade.
- The new `app_fx_records` column family makes app-ledger directories (and
  RocksDB checkpoint snapshots, which copy every CF) **unopenable by
  pre-effects builds** — RocksDB refuses to open a DB with unlisted column
  families. Downgrade after the first post-upgrade open, or restoring a
  new-build snapshot on an old build, fails at startup. FX-M2 adds the CF
  list/format version to the snapshot manifest so this fails fast with a
  manifest-level diagnostic.
- Retention (E4.4) does not yet cover effect records: payloads derived from
  since-pruned message bodies persist in `app_fx_records` until fx pruning
  ships (FX-M2 scope) — until then, chains relying on crypto-shredding should
  use payload-by-hash for sensitive effect payloads (F11).
- Result trust is membership trust (until k-of-n attestation ships); this is
  weaker than `~l1` recompute verification and must be understood by
  deployers.
- The `~fx/done` leaves and `~fx/root` leaves accrete in the trie (32 B each,
  amplified by the append-only node store); bounded and cheap per block, but
  `per-effect` outcome commitment grows O(chain-result effects) — chains
  expecting millions of CHAIN results should choose
  `outcome-commitment: per-block` (F8) and accept its negative-proof trade-off.
  The block-field promotion later removes the root leaves.

## 13. Future: ZK settlement compatibility (ADR-006 E7.x)

The design is settlement-independent by construction, and B+ is the ZK-cheap
shape (per zk-rollup L2→L1 messaging precedent):

- `effectsRoot` is exactly the "outbound message tree root" of zkSync/Scroll:
  in ZK mode it becomes a public output of the state-transition proof, and
  emission correctness is checked in a cheap side-circuit over the linear
  effect list — payloads never enter the trie-update witness.
- The finality gate tightens from "threshold cert / anchor HWM" to "validity
  proof accepted" — a new `FinalityGate.ZK_SETTLED` value slotting into the
  same runtime check; nothing else changes.
- Result incorporation is already message-based and deterministic, so it is
  inside the proven transition automatically. Proving *execution* correctness
  (not just emission/incorporation) stays explicitly out of scope.
- **Mode recommendation:** chains that seriously plan for ZK settlement SHOULD
  choose `outcome-commitment: per-block` (F8) — per-effect `~fx/done` leaves
  put one trie update per outcome into the transition witness, while
  `per-block` keeps outcome commitment at a single leaf per block regardless
  of volume.
- Honest caveat: effects inherit — but do not worsen — the base E7.x problem
  that proving Blake2b-MPF transitions is still research-grade (ZeroJ status);
  the effect machinery adds one leaf plus a linear hash per block, which is as
  cheap as commitment gets, but it cannot make the underlying circuit
  tractable on its own.

## 14. Alternatives considered (rejected)

- **Direct side effects in `apply()`** — violates the determinism contract;
  stalls the chain (§2).
- **Effects as per-effect authenticated state (Option A)** — rejected for the
  storage role after full design; see §6. Its best mechanisms (height-bucketed
  deterministic expiry, payload-by-hash, pending-index deletion at terminal
  commit) are adopted into B+.
- **Sink-only ("just use FinalizedStreamSink + webhook")** — no per-action
  addressing, no result feedback, head-of-line blocking, no idempotent
  identity, no proof of intent; the consumer must re-derive intent from block
  JSON — precisely the duplicated per-application glue this ADR removes.
- **Replicated execution (every node performs the call, ICP-style)** — N
  duplicate writes, secrets on every node, consensus over nondeterministic
  responses; ICP itself added a non-replicated mode. Reserved (as an idea)
  only for *read* oracles, which the `L1Observer` path already covers for L1.
- **App-specific callback frameworks per machine** — duplicates
  emission/retry/result logic in every application with none of the
  audit/commitment properties.
- **On-chain claim/lease protocol as the v1 concurrency mechanism** — pays a
  consensus round per effect to prevent duplicates that idempotency must
  absorb anyway (crash-window duplicates are unpreventable); deferred to an
  opt-in for genuinely expensive duplicate attempts.

## 15. Implementation plan (proposed phases)

| Phase | Scope |
|---|---|
| **FX-M1: consensus plane** | core-api SPI (F1/F2), `fx_records` CF + commit-batch staging, `effectsRoot` leaf + reserved-prefix guard (F4), expiry sweep, conformance-harness emission assertions. No runtime yet — records observable via REST list. |
| **FX-M2: runtime + webhook** | `fx_runtime` CF, `EffectRuntime` (intake/gate/dispatch/park), executor SPI + ServiceLoader, built-in `webhook.post` executor, metrics, `/effects` REST (read + requeue). |
| **FX-M3: result loop** | `~fx/result` topic + `ResultInjector` + fail-closed interpreter + `~fx/done` leaves, `onEffectResult` hook, cancellation, approvals payment example end-to-end on the devnet cluster skill. |
| **FX-M4: executors + external mode** | `appchain-effects-cardano` (payment/metadata with metadata-label idempotency), external claim/report REST, Spring starter properties, backfill quarantine. |
| **FX-M5: hardening (as needed)** | signer policy enforcement / k-of-n attestation for high-value types, on-chain claim mode (L1-slot leases), effectsRoot → block-field promotion at the next wire bump, ZK_SETTLED gate. |

### Pre-implementation design items

Acknowledged in this ADR but not fully designed; each must be resolved no
later than the phase it gates:

| Item | Gates | Notes |
|---|---|---|
| **Emission-versioning mechanism** (version markers, activation heights, replay selection, conformance coverage) | **blocks FX-M1** | the most dangerous correctness requirement (hard req. #2); deserves a just-in-time sub-ADR (010.1) before the SPI freezes |
| Result signer proposal-time enforcement (engine path parallel to `verifyProposalObservations`) | FX-M3 | intent specced in F8; code path not |
| Retention invariant: never prune `fx_records` behind the intake cursor or for open effects (analog of the slowest-sink-cursor rule) | FX-M2 | |
| Snapshot manifest explicitly names the new CFs in its verification list | FX-M2 | CFs ride the checkpoint automatically; the manifest should say so |
| Cardano executor mini-design: wallet/key management outside Yano, UTxO selection & fees, metadata-label probe source (L1 view/indexer) | FX-M4 | flagship executor, real funds — own design note |
| Per-effect retry-policy hints in `EffectIntent` (Temporal-style retry-as-data vs global executor config) | FX-M5 / optional | revisit after FX-M2 operational experience |

Open questions for review:
1. Should `default-gate` be per-machine (`machines.<id>.effects.default-gate`)
   rather than per-chain?
2. Result signer policy default: any member (simplest) vs designated executor
   member only (stricter) — proposal-time enforcement cost is small; lean
   stricter?
3. Is `pendingCount()` worth its (small) deterministic bookkeeping, or should
   backpressure be left to machine-level state?
4. Does the approvals payment example ship in stdlib behind config, or as a
   separate example machine in the scaffold?
5. Should `outcome-commitment` support per-type override (per-effect leaves
   for declared high-value types on an otherwise per-block chain), or is
   per-chain granularity enough for v1?

## 16. External references

- Elnozahy et al., *A Survey of Rollback-Recovery Protocols* (the output
  commit problem) — cs.rice.edu/~dbj/pubs/csur-rollback.pdf
- RIFL: *Implementing Linearizability at Large Scale and Low Latency* (SOSP'15,
  completion records) — web.stanford.edu/~ouster/cgi-bin/papers/rifl.pdf
- IBC ICS-004 packet lifecycle (commitment/ack/timeout, pruning on terminal) —
  github.com/cosmos/ibc
- Temporal: activities, retry policies, timeouts, `getVersion` — docs.temporal.io
- Corda flows: external operations & `deduplicationId` — docs.r3.com
- Transactional outbox / idempotent consumer — microservices.io; Debezium
  outbox event router — debezium.io
- Chainlink Automation architecture (v1 rotation → OCR3 quorum);
  "performUpkeep must be idempotent" — docs.chain.link
- Internet Computer HTTPS outcalls (replicated execution, transform functions,
  `is_replicated=false`) — docs.internetcomputer.org
- zkSync / Scroll L2→L1 messaging (message tree root in state commitment,
  proof-gated consumption) — docs.zksync.io, docs.scroll.io
- Axon streaming processors & sequenced DLQ; EventStoreDB persistent
  subscriptions (park/replay; "ordering is not guaranteed with retries") —
  docs.axoniq.io, docs.kurrent.io
