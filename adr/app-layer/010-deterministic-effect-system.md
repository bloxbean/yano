# ADR-010: Deterministic Effect System (Emit → Execute → Result) for Yano App Chains

## Status
Accepted — implemented (FX-M1…FX-M6B, 2026-07-13) on `feat/app_layer_effect`

## Implementation Status

| Phase | Scope | Status |
|---|---|---|
| FX-M1 | Emission SPI, `app_fx_records` outbox CF, `effectsRoot` leaf, expiry sweep, reserved `~fx/` prefix, 010.1 upgrade replay-matrix, REST reads | **Done** (reviewed 6-angle: sound effectsRoot merkle, genesis-reserved prefix, apply-time staging) |
| FX-M2 | `EffectRuntime` (intake cursor, gating, per-effect backoff, PARKED/QUARANTINED), executor SPI + ServiceLoader, webhook executor, fx retention, manifest CF list | **Done** (reviewed: prune safety, starvation, shutdown races fixed) |
| FX-M3 | `~fx/result` interpreter (fail-closed, first-result-wins, consensus result window), member-signed injection, operator cancel, approvals payments (§8.1) | **Done** (reviewed: mandatory CHAIN expiry ≤ result window) |
| FX-M4 | External claim/report mode (leases + fencing), `appchain-effects-cardano` payment executor, `AppChainClient` effects SDK | **Done** (reviewed: steal fencing, SUBMITTED claims, tx false-confirmation fixed) |
| FX-M5 | Result signer policy (`effects.result.signers`), `FinalityGate.ZK_SETTLED` (gated on `zk_settled_height`), zeroj 0.1.0-pre9, final regression | **Done** |
| FX-M6A | Replay/recovery hardening: strict missing-activation safety, startup activation diagnostics, A±k and `onEffectResult()` upgrade coverage, executor-owned runtime reset/quarantine, bounded worker admission, pre-fire revalidation, type-partition fencing and ledger-safe shutdown | **Done** |
| FX-M6B | Historical composed-effect proof + client verifier with precise 404/410 retention semantics; non-truncated bounded-cardinality metrics; durable fair work indexes/cursors | **Done** |

Open questions resolved: Q1 per-machine gate override → yes (e.g.
`machines.approvals.payment-gate`); Q2 signer default → any-member with the
designated-signer list as the production recommendation; Q3 `pendingCount()`
→ shipped; Q4 approvals payments → stdlib behind config; Q5 per-type
outcome-commitment → deferred (per-chain granularity in v1). Deferred to
future iterations: on-chain claim mode, k-of-n result attestation,
`effectsRoot` block-field promotion (next wire bump).

The consensus/execution loop, proposal-time result-signer admission, composed
proof API/client verifier, and dedicated effect observability described here
are implemented for the v1 consensus format. Framework-level setting/codec
epochs remain pending under ADR-010.1 D5, so the v1 `effects.*` consensus
settings and algorithms are immutable for the chain lifetime. The remaining
production-funds blocker is the Cardano executor safety design in §15: its
metadata breadcrumb does not close the crash window before local `SUBMITTED`
persistence. Optional quorum attestation, attempt history and alternative
transports remain demand-driven extensions, not reasons to redesign the
two-plane model.

## Date
2026-07-12 (accepted 2026-07-13)

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
- ADR-010.1 (emission versioning and replay compatibility)
- ADR-011 (plugin bundles, lifecycle and domain APIs; this ADR's executor
  factory is one typed contribution shape)

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

### 0.1 Concepts and consistency model

- An **Effect** is immutable, deterministic intent emitted by a state
  transition and stored as an `EffectRecord`. It says what is authorized; it
  is not an execution or a claim that the outside world changed.
- An **Execution Attempt** is one node-local, non-deterministic effort to carry
  out an effect. Attempts may differ between nodes and are never authenticated
  chain state.
- An **Outcome Attestation** is a terminal `~fx/result` message, or the
  deterministic EXPIRED transition, incorporated by consensus. A signed
  result proves who attested to an outcome and that the chain recorded it; in
  general it does **not** independently prove that the external action
  happened.
- The **Effect Runtime** is the execution-plane dispatcher and retry loop. It
  may run in the Yano process, on a dedicated Yano member, or behind an
  external-worker REST boundary. "Outside deterministic execution" means
  outside `AppStateMachine.apply()`; it does not necessarily mean outside
  Yano.

The named consistency property is **eventual consistency between authorized
intent and recorded outcome**. An effect is final at height H, while its
outcome is recorded at a later height H′ or by deterministic expiry. Application
schemas must therefore model the in-flight interval. The formal delivery
contract remains **exactly-once incorporation with at-least-once execution**.

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

This is the **transactional outbox pattern** adapted to authenticated app-chain
state. `EffectRecord` rows commit atomically beside the application-state
transition but remain outside the MPF trie; the ordered per-block
`effectsRoot` leaf under `stateRoot` binds their content and completeness.
Thus the outbox payload is not itself authenticated state, yet an auditor can
prove that a particular instruction was among all effects authorized by a
specific state root.

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

public enum FinalityGate  { CHAIN_DEFAULT, APP_FINAL, L1_ANCHORED, ZK_SETTLED }
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
                 scope, gate, result(0|1), expiryHeight, sourceMessageId / null ]
effectHash   = blake2b-256(canonical record bytes)
```

The **id** binds position; the **hash** binds content. For sensitive payloads
the record MAY carry `payloadHash` + out-of-band body instead (F11).

### F3 — Storage: two column families, split by trust tier

Two new CFs in the app ledger RocksDB (covered by snapshot checkpoints for
free):

**`app_fx_records` (`fx_records` below) — consensus tier.** A pure function of the block stream;
identical on every node; written in the same atomic `WriteBatch` as
`commitBlock` (block + tip + trie nodes + indexes), via the same staging
mechanism as `GovernedMembership.MetaWrite`.

| Key | Value | Written |
|---|---|---|
| `r` ‖ be64(height) ‖ be32(ordinal) | EffectRecord CBOR | at block commit (emission) |
| `n` ‖ be64(height) | count(4BE) ‖ effectsRoot(32) | at block commit when count > 0 |
| `x` ‖ be64(expiryHeight) | CBOR list of (height, ordinal) expiring there | at block commit (emission with expiry) |
| `c` ‖ be64(height) ‖ be32(ordinal) | terminal outcome envelope | when an outcome is incorporated or expiry commits |

**`app_fx_runtime` (`fx_runtime` below) — node-local tier.** Never replicated, never proven, freely
rewritten by the Effect Runtime, disposable (rebuildable, F10).

| Key | Value |
|---|---|
| `s` ‖ be64(height) ‖ be32(ordinal) | StatusRecord: {status, attempts, nextAttemptAt, lastError/lease-holder, submittedRef, externalRef, timestamps, outcome} |
| `q` ‖ be64(height) ‖ be32(ordinal) | (empty) pending-queue row; deleted on local terminal or chain closure |
| `i` ‖ be64(height) ‖ be32(ordinal) | (empty) locally terminal CHAIN outcome awaiting incorporation |
| `o` / `v` | runtime owner/type-partition binding / runtime schema version |
| `d` / `e` / `u` | fair-scan cursors for embedded dispatch / external claims / result injection |
| app meta | `fx_intake_cursor` — last block folded into the queue |

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
rawTreeRoot(H) = merkleRoot([effectHash_0 … effectHash_{n-1}])   // ordered, blake2b-256
effectsRoot(H) = blake2b-256("yano:fx:list-root:v1" ‖ be32(n) ‖ rawTreeRoot(H))
trie leaf:  key = "~fx/root/" ‖ be64(H)      value = effectsRoot(H)   (only when n > 0)
```

The merkleizer promotes an odd node UNCHANGED to the next level (pass-through,
never duplicated), so lists differing only by a repeated trailing leaf produce
different roots — the CVE-2012-2459 malleability class is excluded by
construction. The domain-separated final hash also authenticates the exact
list count; a valid path cannot be relabelled as a different-width tree. This
deliberately differs from the legacy duplicate-promotion in
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

The count-bound form is the pre-release v1 baseline on this feature branch.
It is intentionally incompatible with prototype databases that wrote the raw
tree root directly; an already-certified chain would require the framework
activation mechanism in ADR-010.1 D5 before changing this algorithm.

In concrete terms, this proof can establish: **"this payment instruction
existed under state root X."** The composed proof endpoint and client helper
package these steps against the historical state root at H. Proof generation
requires every record in H's effect list: after any sibling record crosses the
retention horizon the endpoint returns `410 EFFECT_PROOF_PRUNED`, while the
small per-block root metadata remains. A proof archived before pruning remains
verifiable forever. Threshold-certificate/L1-anchor authentication of the
served state root is a separate finality step; the client verifier accepts an
independently obtained root.

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

The executor SPI is intentionally source-agnostic: an executor receives a
`PendingEffect` and execution context, with no dependency on RocksDB, REST, or
the intake cursor. Kafka, gRPC or WebSocket delivery would therefore be a
dispatcher/transport adapter in front of the same executor contract, not a
new kind of effect. No generic `EffectSource` abstraction is introduced until
a concrete second delivery transport demonstrates a need for one (ADR-011
owns the wider plugin/transport roadmap).

Runtime tick (default 2 s, own scheduler, sibling of `sinkTick`):

```
tick():
  # 1 INTAKE — fold newly committed blocks into the local queue
  for h in (fx_intake_cursor+1 .. tipHeight):
      for rec in fx_records.byHeight(h): queue.add(rec); status(rec)=PENDING
      fx_intake_cursor = h

  # 2 GATE + DISPATCH — per effect, no head-of-line blocking
  anchored = meta("anchor_last_height")
  for (height, ordinal) in queue.scanFrom(persistedCursor):   # bounded, round-robin
      if gate == L1_ANCHORED and height > anchored - anchorMargin: continue
      st = status(height, ordinal)
      if st in {LEASED, EXECUTING} and lease alive: continue
      if st == RETRY and now < nextAttemptAt: continue
      if st == PARKED: continue                               # operator lane, F9
      ex = registry.find(rec.type); if ex == null: continue       # stays pending
      lease(); pool.submit(run(rec, ex))                      # bounded parallelism

run(rec, ex):
  outcome = ex.execute(ctx, rec)         # exceptions → attempts++, exp. backoff + jitter
  CONFIRMED/FAILED  → if rec.result == CHAIN: inject ~fx/result (F8)
                      else: local terminal (DONE/PARKED), delete queue row
  SUBMITTED(ref)    → record locally; re-poll next tick (executor checks the
                      external system by idHash/ref and returns CONFIRMED/FAILED)
  attempts ≥ max    → PARKED + alert; operator requeues/cancels via REST (F12)
```

For a `ResultPolicy.CHAIN` effect, the local DONE status durably drives result
reinjection until the sequenced terminal is observed; the queue row need not
remain after local completion. The consensus closure row / `~fx/done`
commitment, not a local queue high-water mark, is authoritative. Intake and
restart consult that closure before dispatch, so a chain-terminal effect is
not reopened merely because its node-local queue was rebuilt.

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
correctness always comes from deterministic incorporation + idempotency.

The implemented v1 modes are:

1. **Single designated executor (default).** Operators enable the runtime on
   one node (the existing anchor-leader role is the precedent). Zero
   coordination traffic. Executor down ⇒ effects wait, bounded by expiry;
   operators monitor the REST status surface and dedicated backlog/oldest-age
   metrics.
2. **Explicit type partitioning.** Multiple runtimes may use disjoint
   `executor.types` lists.
3. **External-worker claims.** REST claim/report uses node-local wall-clock
   leases and fencing behind a fronting member. These leases coordinate
   external workers only; they are not consensus state.

Possible later escalations are a standby stagger or deterministic hash
assignment, and **on-chain claims**. An on-chain claim would be a
`~fx/claim` sequenced message with L1-slot-denominated leases, for effect types
where even duplicate attempts are expensive. Deliberately deferred: it costs
a consensus round per effect, and lease clocks are subtle — app-chain height
stalls with the chain; if built, leases MUST be denominated in **L1 slot**
(already in every block, monotonic) rather than app height or wall clock.

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
- **ZK_SETTLED**: eligible once `createdHeight ≤ zk_settled_height`. The
  runtime reads only this verified settlement high-water mark; the future ZK
  settlement subsystem is responsible for advancing it after proof acceptance
  (§13).

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
body:  [ v(=1), height, ordinal,                     // chainId is bound by the verified envelope
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
        | consensus closure row already exists           # ← first result wins
    write trie leaf  ~fx/done/<idHash> = blake2b-256(result envelope)  # per-effect mode
    machine.onEffectResult(block, result, writer)        # app records txHash etc. — into the root
    stage consensus closure row; runtime observes closure and drops any local queue row
```

Properties:

- **Exactly-once incorporation**: duplicate results (executor retry, standby
  overlap, malicious replay) hit the consensus closure guard and no-op
  identically on every node (`~fx/done` is additionally written in per-effect
  commitment mode). A result message can **never stall the chain** — every invalid
  case is a deterministic no-op (explicit contrast with `~l1` MISMATCH, which
  stalls by design).
- **Trust model, stated plainly**: a result is a *member attestation*, not an
  independently verifiable observation. Followers verify authorship
  (membership + signature — already enforced by the envelope layer), not the
  external world. A lying member attesting a false result has the same power
  as a lying proposer and is equally attributable (signed, sequenced,
  permanent). The implemented mitigation is to restrict accepted signers to the
  designated executor's member key (implemented at proposal admission with
  deterministic incorporation as the replay-safe backstop). A future k-of-n
  co-attestation mode may follow the `~anchor/sig` threshold-collection
  precedent. Where the fact is L1-visible (payments,
  anchors), verify via the existing `L1Observer` recompute path instead of
  trusting any attestation.
- Honest proposers drop unauthorized results from their pools and followers
  reject live proposals containing them. Incorporation still treats them as
  deterministic no-ops so already-certified catch-up history remains
  replayable and policy validation stays consensus-safe.
- In per-effect mode, the `~fx/done` leaf doubles as the **audit anchor for the
  outcome**. In per-block mode the `~fx/results/H′` list root plays that role.
  In both modes the sequenced result is also under H′'s `messagesRoot`.
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
DONE/PARKED with evidence in `fx_runtime`, surfaced via REST/metrics, zero chain
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

- **Warm restart**: cursor and statuses are persisted in `fx_runtime`.
  Embedded in-flight work is not assumed complete and may dispatch again;
  external-worker claims wait for their persisted node-local lease to expire.
  Both are the intended at-least-once path, absorbed by idempotency.
- **Catch-up replay** (fresh follower): replaying `apply` regenerates
  `fx_records` identically — the outbox self-heals from the block stream by
  construction. Replay re-runs the *consensus plane* only; the runtime never
  fires from replay (executor disabled by default; where enabled, chain-
  terminal effects have consensus closure rows/commitments and are not
  re-enqueued).
- **Snapshot restore**: the RocksDB checkpoint covers both CFs. Same-node
  recovery can resume its runtime view: genuinely in-flight effects may
  re-execute (the intended at-least-once path), while completed effects stay
  closed. Runtime state is bound to a canonical fingerprint of executor
  identity plus its sorted type partition. A checkpoint copied to a different
  owner (or legacy unowned state) atomically discards only
  statuses/leases/queue/cursor and quarantines
  historical open effects. The state root never depends on this tier.
- **Enabling the executor late / backfill quarantine**: on first enable with
  no intake cursor, the implementation quarantines all historical open
  effects and advances the cursor to the current tip. Operators explicitly
  requeue selected work; a newly promoted executor never blind-fires stale
  history.
- **Rebuild-on-corruption**: if the outbox CFs are lost or fail integrity
  checks (record hashes vs the `effectsRoot` leaves — the commitment doubles
  as a checksum), drop and rebuild by replay; then reconstruct status from the
  chain: consensus closure row present ⇒ terminal; open CHAIN effect ⇒
  PENDING (re-execute; idempotent); NONE effect ⇒ SKIPPED (no evidence
  either way — never guess with emails). **Consensus-recorded results double
  as durable, replicated outcome attestations** — disaster recovery is a
  chain query, not guesswork.
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
   designated executor's member only via `effects.result.signers` ⇒ k-of-n) —
   see F8 trust model.
6. **REST authorization (final review)**: the effect operations
   requeue/cancel/claim/report move real funds or change consensus-visible
   state. They are gated by the app-chain API-key filter as **privileged
   operations**: a topic-restricted (submit-only) key may read and submit to
   its topics but may NOT call them — only a full key can. Because API auth is
   opt-in, a node running the executor or external mode with auth disabled
   exposes these unauthenticated; the runtime logs a loud warning, and
   operators MUST enable `yano.app-chain.api.auth` and restrict network access
   to the executor/operator network. The external-executor fence is the
   `executorId` string within one trust domain (not a per-worker secret); the
   status surface exposes only that a lease is held, never the id. A payment
   executor SHOULD set `effects.executors.cardano.max-lovelace-per-tx` and be
   funded conservatively so a buggy/compromised machine cannot drain the hot
   wallet in one payment.

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
      result-window-blocks: 100000       # consensus result-incorporation horizon
      default-gate: app-final           # app-final | l1-anchored | zk-settled
      outcome-commitment: per-effect    # per-effect | per-block (CONSENSUS-AFFECTING; F8)
      strict-reserved-prefix: true      # CONSENSUS-AFFECTING; active even when enabled=false
      result:
        signers: ""                     # comma-separated member keys; empty = any member
      retention:
        keep-blocks: 100000             # prune terminal records/statuses
      gate:
        anchor-margin-blocks: 0
      executor:
        enabled: false                  # OFF by default on every node
        identity: ""                    # default: node-local sidecar outside checkpoints
        types: ""                       # comma-separated; empty = all supported
        tick-ms: 2000
        max-parallel: 4
        max-batch: 256
        max-attempts: 8
        backoff-initial-ms: 2000
        backoff-max-ms: 300000
      metrics:
        types: ""                      # max 32 explicit type tags; empty = aggregate only
      external:
        enabled: false                  # REST claim/report surface for external executors
      executors:                        # per-executor factory config (scheme-keyed)
        webhook:
          url: https://example.invalid/hook
          timeout-ms: 10000
        cardano:
          backend-url: https://cardano-backend.example
          signing-mnemonic: ${CARDANO_PAYMENT_MNEMONIC}
          network: preprod
          metadata-label: 21042
          max-lovelace-per-tx: 100000000
```

Consensus-affecting keys (`enabled`, `max-per-block`, `max-payload-bytes`,
`max-expiry-blocks`, `result-window-blocks`, `default-gate`,
`outcome-commitment`, `strict-reserved-prefix`, `result.signers`) are chain
config like `threshold` — a mismatch across members may diverge roots or
validity decisions; use the same coordination discipline as a state-machine
upgrade.

| Module | Contents |
|---|---|
| `core-api` (`api.appchain.effects`) | `AppEffectEmitter`, `EffectIntent`, `EffectId`, `EffectRecord`, `EffectResult`, `PendingEffect`, `AppEffectExecutor` (+ Factory/Context/Execution), enums, codec; the two `AppStateMachine` default methods; `TypedAppStateMachine` overload; CDDL for record/result |
| `runtime` (`runtime.appchain`) | `AppLedgerStore` owns the two CFs and atomic staging; `FxKernel` owns commitments/result interpretation/expiry; `EffectRuntime` owns intake/gating/dispatch/retry; `AppChainSubsystem` owns result injection and executor discovery; `BatchStateWriter` guards the prefix; built-in `webhook.post` executor |
| `appchain/extensions/appchain-effects-cardano` | `cardano.payment` executor (CCL transaction building and confirmation polling; idHash metadata is a reconciliation breadcrumb, not automatic pre-submit dedupe) |
| `app` (Quarkus) | effect routes on the existing `AppChainResource` |
| `appchain-spring-boot-starter` | app-chain configuration integration; no separate effect-executor bean SPI is promised by this ADR |
| `appchain-testkit` | emission assertions in `StateMachineConformance`; fake executor; deterministic result/expiry injection harness |

REST (chain-scoped under the existing `/api/v1/app-chain/chains/{chainId}`,
behind the existing API-key auth):

```
GET  /effects?fromHeight=&limit=                     list consensus records
GET  /effects/{height}/{ordinal}                     record + this node's runtime status
GET  /effects/{height}/{ordinal}/proof               record → list path → historical-root proof
POST /effects/{height}/{ordinal}/requeue             operator: PARKED/QUARANTINED → PENDING
POST /effects/{height}/{ordinal}/cancel?reason=      operator: inject CANCELLED result
POST /effects/claim                                  external executor: {types,max,leaseSeconds}
POST /effects/{height}/{ordinal}/report              external executor: report outcome (lease-checked)
GET  /effects/stats                                  full-scan status/backlog/age + cumulative totals
```

The composed route returns canonical record CBOR, the exact ordered Merkle
path (including odd-node pass-through steps), and an MPF proof of the
`~fx/root/<height>` leaf against that block's historical root. The dependency-
light client verifies all three layers and can bind them to an independently
trusted state root and requested effect identity.

Micrometer exports non-truncated, memoized current open/queue/status/result-
backlog and oldest-pending gauges, process-lifetime monotonic confirmed/failed/
parked counters, committed
expiry totals, and emission-to-terminal latency. Per-type backlog/latency tags
come only from the explicit bounded `effects.metrics.types` allowlist (maximum
32, with `other`); effect ids, scopes, errors and arbitrary workload types are
never tags. These are operational snapshots, not a transactionally consistent
multi-CF read while transitions are concurrent. Dedicated durable indexes and
round-robin cursors prevent retained/ineligible prefixes from starving embedded
dispatch, external claims, or CHAIN-result injection. Optional type/status REST
filters remain demand-driven. Admin cancellation is member-signed and
sequenced; requeue is node-local operational control.

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
            if (status == STATUS_APPROVED && paymentsActiveAt(block.height())) {
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
runtime tick:  gate open → CardanoPaymentExecutor builds tx
               (payee, amount, metadata breadcrumb {label: idHash}) → submit and wait
               → if confirmed: CONFIRMED(txHash)
               → otherwise persist SUBMITTED(txHash); later ticks re-poll that hash
inject ~fx/result (member-signed) → sequenced into block H′=1057
apply(H′):     interpreter: no ~fx/done yet → write ~fx/done/<idHash>
               → onEffectResult: item PAID, txHash recorded      [root binds outcome]
audit:         intent provable vs stateRoot(1042), outcome vs stateRoot(1057),
               both threshold-signed and L1-anchored; payment body verifiable vs record hash
```

A crash after `SUBMITTED(txHash)` is persisted is safe to resume by polling the
same transaction. There is still a duplicate-payment window if the process
crashes after submission but before that local status is durable: metadata
supports manual reconciliation, but the current executor does not search by
metadata before its first submission. The formal contract therefore remains
at-least-once execution, not exactly-once payment. Production funding requires
conservative hot-wallet limits and a dedicated Cardano executor safety design
(§15) before relying on automatic retries for material value.

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
idempotency header; outcome (DONE/PARKED + HTTP status) is visible in
`/effects/stats` and metrics only. Zero chain footprint beyond the emission
record — which is still provable ("deployment X was authorized at height H").

## 9. Failure modes and mitigations (from the adversarial review)

| # | Failure mode | Mitigation in this design |
|---|---|---|
| 1 | Emission-logic upgrade breaks replay (roots diverge; node can never sync) | F10 versioned emission with activation heights — hard requirement; conformance replay tests |
| 2 | N-times execution (embedded runtime on every member) | executor OFF by default; single designated executor; partitions; idempotency as last line (F5/F6) |
| 3 | Forged/false results (authority trust) | fail-closed interpreter; designated-signer policy; L1-visible facts verified via `L1Observer`; k-of-n remains a future extension (F8, §15) |
| 4 | Crash between external call and result submission | at-least-once by design; durable local `SUBMITTED` state when available; external idempotency required; Cardano's pre-persist crash gap is explicitly unresolved (F5, §8.1, §15) |
| 5 | Lease clocks unsound (stalled chain / proposer clock skew) | external REST leases are node-local efficiency/fencing only; any future consensus claim lease uses L1 slots (F6) |
| 6 | Poison effect starves the pipeline | per-effect retry state, no head-of-line; attempt cap → PARKED + alert + replay endpoint (F9) |
| 7 | Result message never sequenced (pool full, TTL, key rotation) | durable result-ready index, re-sign + resubmit until terminal observed; index backfills on upgrade and cannot be starved by old statuses (F8) |
| 8 | Snapshot restore / late-enabled executor re-fires history | consensus closure rows prevent re-enqueue; first-enable quarantine; foreign owner/type-partition reset discards only runtime-local state (F10) |
| 9 | L1 rollback rewinds anchor HWM after execution | monotonic executed set; stability-depth gate; deep-reorg accepted risk (F7) |
| 10 | Reserved trie-key collision / app writes to `~fx/*` | deterministic `BatchStateWriter` guard + escape hatch (F4) |
| 11 | Pending-set growth, emission spam, oversized payloads | deterministic caps, expiry, `pendingCount()` backpressure, metrics (F11) |
| 12 | Lifecycle races (dup/late/orphan results, cancel vs in-flight) | single deterministic transition table; terminal states absorbing; every illegal transition = audit no-op (F8/F9) |
| 13 | App machine mis-parses `~` messages | framework interprets `~fx/*`; machines get the typed `onEffectResult` hook; stdlib skips `~` topics (F8) |
| 14 | PII/secrets in replicated/authenticated state | payload-by-hash, credential references, scope discipline (F11) |
| 15 | Stuck effects invisible until business impact | REST status/stats, non-truncated backlog/oldest-age gauges, bounded per-type metrics and admin ops (F12) |

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
7. Results are authority-based and the documentation says so; designated-
   signer policy is enforceable per chain. k-of-n attestation for high-value
   types is deferred and MUST NOT be implied by v1 configuration.
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
13. A status/stats surface, dedicated bounded-cardinality metrics and
    privileged admin operations ship with v1; operators must configure alerts
    appropriate to their effect SLAs (§F12).

## 11. Deviations from the initial sketch

The initial idea document proposed the broad shape adopted here (effects as
data, emitted deterministically, executed by a pluggable runtime, results as
messages). The exploration changed these specifics, each for a
codebase-verified reason:

| Initial sketch | This ADR | Why |
|---|---|---|
| Effects stored as authenticated state (`effects/` namespace in the tree) | Outbox CF + per-block `effectsRoot` trie leaf (B+) | MPF has no prefix scan (a CF index is needed regardless); MPF node store is append-only (payloads would be permanent); per-effect leaves bill hashing into every apply on every node — see §6 |
| `PENDING → CLAIMED → SUBMITTED → CONFIRMED` all as chain state | Only EMITTED + terminal outcomes on the consensus plane; CLAIMED/SUBMITTED/attempts are runtime-local | intermediate statuses are coordination data, not audit data; each on-chain hop = a sequenced message + root recomputation on every node for information no auditor needs (the txHash still lands in CONFIRMED) |
| Generic claiming protocol with block-height leases | Designated executor, explicit type partitions, optional node-local external-worker claims; on-chain claims deferred | executor selection is efficiency, not correctness; node-local leases fence REST workers, while any future consensus lease must use L1 slots |
| `effectId = hash(appchainId, blockNumber, messageIndex, effectIndex)` | `(chainId, height, ordinal)` + domain-separated hash | equivalent intent; ordinal-per-block is simpler than per-message indexing and survives framework-emitted transitions |
| Execution result "submitted as another appchain message" (unspecified author) | Member-signed `~fx/result` on a reserved topic via the internal injection path; external executors report through a fronting member node | verified constraint: only current member keys can author messages; external `submit()` rejects `~` topics |
| Finality policies incl. `L1_CONFIRMED`, `ZK_SETTLED` | APP_FINAL, L1_ANCHORED and ZK_SETTLED | app finality is already irreversible (append-only after cert); ZK settlement changes the eligibility high-water mark, not the effect model (§13) |
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
- Retention prunes resolved effect records after the configured horizon while
  preserving records needed by the consensus result window. Until that
  horizon passes, payloads remain in checkpoints and replicated storage;
  chains relying on crypto-shredding must use payload-by-hash for sensitive
  effect bodies (F11).
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

```
effect list → effectsRoot → stateRoot → validity proof → settlement acceptance
                                                           │
                                                           └→ zk_settled_height
                                                                      │
                                                                      └→ execution eligibility
```

- `effectsRoot` is exactly the "outbound message tree root" of zkSync/Scroll:
  in ZK mode it becomes a public output of the state-transition proof, and
  emission correctness is checked in a cheap side-circuit over the linear
  effect list — payloads never enter the trie-update witness.
- The finality gate tightens from "threshold cert / anchor HWM" to "validity
  proof accepted". `FinalityGate.ZK_SETTLED` and its
  `zk_settled_height` runtime check already ship; a settlement subsystem must
  advance that authenticated/verified high-water mark. The effect model stays
  unchanged as the proof mechanism evolves.
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

## 15. Implementation history and follow-ups

| Phase | Shipped scope |
|---|---|
| **FX-M1: consensus plane** | core-api emission SPI, `app_fx_records`, atomic staging, `effectsRoot`, reserved prefix, expiry and conformance coverage |
| **FX-M2: runtime + webhook** | `app_fx_runtime`, intake/gating/dispatch/retry/park, executor SPI + ServiceLoader, webhook executor, retention, REST reads/requeue and snapshot CF manifest entries |
| **FX-M3: result loop** | `~fx/result`, fail-closed first-result-wins interpretation, outcome commitments, `onEffectResult`, cancellation, mandatory CHAIN expiry and approvals integration |
| **FX-M4: external execution** | REST claim/report with node-local leases/fencing, `cardano.payment`, `AppChainClient` effect operations and first-enable quarantine |
| **FX-M5: hardening** | incorporation-time result-signer policy, `ZK_SETTLED` gate, security review and regression coverage |
| **FX-M6A: replay/recovery safety** | strict approvals activation, activation startup/status diagnostics, upgrade restarts before and after A, topic-aware result-handler replay coverage, runtime owner/type-partition binding with foreign/legacy reset and quarantine, globally bounded worker admission, last-moment state/expiry/gate revalidation, and ledger-safe shutdown barriers/context snapshots |
| **FX-M6B: proof + observability** | historical count-bound composed-effect proof route, bounded client verifier, precise 404-vs-410 classification from retained count metadata, non-truncated effect gauges plus monotonic counters/timers, bounded type tags, node-app config plumbing, and persisted fair cursors for dispatch/claim/result injection |

### Resolved during implementation

- Emission-version selection and replay compatibility are specified by
  ADR-010.1 and covered by the replay matrix.
- Retention never prunes open effects or records still needed inside the
  consensus result window.
- Checkpoint manifests enumerate both effect column families; checkpoints
  include them automatically.
- CHAIN effects always receive a bounded deterministic expiry within the
  result window.

### Post-M5 follow-ups

| Priority | Item | Boundary / recommendation |
|---|---|---|
| Completed (FX-M6B) | **Composed proof and observability** | Historical proof route/client verification compose record hash, count-bound ordered list path and MPF inclusion; Micrometer exposes non-truncated backlog/status/oldest-age and bounded per-type latency/backlog. Node-local indexes plus persisted cursors remove bounded-prefix starvation from dispatch, claims and result injection. |
| Before changing v1 consensus settings/codecs | **Framework effect epochs (ADR-010.1 D5)** | Define a height-indexed settings/codec timeline and replay matrix for signer rotation, result-window/cap changes, commitment-mode changes and future record/root versions. Until then these values are immutable for a chain's lifetime. |
| Before production funds | **Cardano executor safety design** | Specify automatic pre-submit reconciliation, network/address validation, UTxO/fee policy, key custody, funding and transaction limits. The current metadata breadcrumb is not automatic dedup. Prefer ADR-010.2 before material-value deployment. |
| Completed (FX-M6A) | **Proposal-time signer filtering** | Honest proposers remove unauthorized `~fx/result` messages from their pool and followers reject proposals containing them; incorporation-time validation remains the consensus-safe backstop and certified catch-up history remains replayable. |
| High-value trust | **k-of-n outcome attestation** | Optional per-type/member policy; do not conflate attestation quorum with independent proof of execution. |
| Completed (FX-M6A) | **Portable runtime reset/quarantine** | Node-local status/queue/cursor state is bound to executor identity + sorted type partition. Missing/mismatching ownership atomically resets only the disposable tier, then quarantines only historical effects owned by that partition; consensus records and roots are preserved. Dispatch, claims, reports and requeue enforce the same partition. Worker admission is globally bounded, and queued work revalidates current status, chain closure, expiry and finality immediately before external execution. Shutdown fences scheduler/store/API transitions before RocksDB teardown; executor contexts carry immutable height/config/reference snapshots. Plugin capability calls cannot hold the ledger barrier, stale callbacks cannot admit work after close, and independent bounded cleanup prevents one non-cooperative executor from delaying shutdown or another executor's cleanup. |
| Optional operations | **Append-only attempt diagnostics** | If per-attempt history is needed, keep it node-local, bounded, sanitized and retention-controlled, with node/executor identity. It cannot authenticate external-worker internals and must never enter consensus state. Structured logs may be sufficient. |
| Demand-driven extension | **Alternative execution transport** | Add a dispatcher/transport adapter only with a concrete second transport (Kafka/gRPC/WS). Keep `AppEffectExecutor` source-agnostic; do not introduce an abstract `EffectSource` pre-emptively. Coordinate this with ADR-011. |
| Future protocol | **On-chain claims, k-way assignment and block-field promotion** | Use L1-slot leases if claims become necessary; consider standby/hash assignment for HA; promote `effectsRoot` at the next wire version if block-level ergonomics justify it. |
| Optional policy | **Retry hints / per-type outcome commitment** | Revisit retry-as-data and per-type commitment modes after operational evidence, without changing v1 defaults. |

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
