# ADR-008: App-Chain Next-Iteration Plan — Review Consolidation, Fixes, Gaps, and Roadmap

## Status
Accepted — Iterations 1-3 implemented; residual roadmap tracked separately

## Date
2026-07-10

## Sub-ADRs and tracking

Detailed designs are written **just-in-time** as each iteration starts, as
sub-ADRs. Branch pattern: integration branch `feat/app_layer_adr008`; one
feature branch per iteration (e.g. `feat/adr008-iteration1`), merged to the
integration branch at iteration end.

| Sub-ADR | Scope | Branch | Status |
|---|---|---|---|
| `008.1-iteration1-correctness-operator-safety.md` | Iteration 1 (I1.1–I1.9 incl. status page) | `feat/adr008-iteration1` | **Merged** (2026-07-10, `2c444b5`) — devnet regressions + 300/1000-msg load test passed |
| `008.2-rotating-sequencer.md` | S2 rotating sequencer (I2.1) + dedup hardening (I2.3) | `feat/adr008-iteration2` | **Merged** (2026-07-10) — devnet gate passed (rotation across windows on live devnet) |
| `008.3-chain-governed-membership.md` | Chain-governed membership, 005 D6 (I2.2) | `feat/adr008-iteration2` | **Merged** (2026-07-10) — devnet gate passed (governed add activated identically on both nodes) |
| `008.4-script-anchors-l1view.md` | Script anchors + L1View (005 D4-A2/D5) — julc-default validator, Aiken opt-in, one shared ABI | `feat/adr008-iteration3` | **Devnet-gated** (2026-07-10) — 26 conformance vectors × 2 impls; live gate: bootstrap + 2 co-signed advances on L1, zero-config follower adoption, observations finalized on both nodes |
| 008.5 (planned) | DX track (typed queries, SDK verification loop, security scopes, packaging) | — | Not started |
| _(robustness/tooling)_ | Cluster toolkit (`app/appchain-cluster/`: n-node start/submit/loadtest) + **block-bytes fix** — `block.max-bytes` proposal cap, leader trim-to-fit, verifier reject, topic-aware inbound size cap (proposals sized by `block.max-bytes`, user messages by `max-message-bytes`) | `feat/adr008-block-bytes` | **Devnet-gated** (2026-07-11) — toolkit found a multi-chain consensus stall (proposals capped at `max-message-bytes`); fixed; 3-node cluster now finalizes 2000/1500-msg loads with no stall, blocks up to 1300 msgs, identical state roots. See struck entry in `pending-tasks.md` |
| _(transport)_ | **Shared app transport** (owner-requested 2026-07-12) — app protocols 100/103 ride the L1 upstream peer session instead of a second dedicated dial (ADR 005 M1 unification; one TCP connection per peer pair). `yano.app-chain.transport.mode: shared` (default) \| `dedicated`; per-peer grace-then-fallback to a dedicated `AppPeerClient` when the shared session is down; catch-up single-owner routed across chains. Companion yaci change: `feat/yaci_103_enhancement` (0.5.0-pre12: `AppProtocolManager` carries the 103 agent, `enableAppChainSync()`, `isAppLayerNegotiated()`) | `feat/appchain-shared-transport` (yano), `feat/yaci_103_enhancement` (yaci) | **Devnet-gated** (2026-07-12, 12/12) — single TCP connection follower→producer; diffusion, catch-up (103), fallback engage/retire on producer outage, and dedicated-mode control all pass. The gate caught 3 real bugs unit tests missed: (1) peer links built in the subsystem constructor before transport wiring (fix: wiring rebuilds links), (2) `TrackingPeerClient` not forwarding `enableAppMsg(config)`/`enableAppChainSync` — app-layer setup split across wrapper/delegate, (3) `yano.app-chain.transport.` prefix not forwarded into runtime globals — the mode flag was silently ignored. See struck entry in `pending-tasks.md` |

Deferred-with-intent items across all iterations are collected in
**`adr/app-layer/pending-tasks.md`** (with ADR references and revive
triggers) — update it whenever an iteration defers something.

## Inputs
- `adr/app-layer/005-yano-app-chain-framework.md` — the shipped v1 framework (M1–M6)
- `adr/app-layer/006-appchain-enterprise-extensions-and-zk.md` — Waves 1–5 + PENDING list
- `adr/app-layer/007-yano-appchain-implementation-codex-report.md` — external review
- Independent code audit (2026-07-10, this ADR) on `feat/app_layer_extensions`, which
  re-verified every ADR-007 claim against the source with file-level evidence and
  found additional defects ADR-007 missed.

## Goal restated

Make Yano's app chain **the simplest, most solid Java framework for building
permissioned, L1-anchored application ledgers** across the use-case spectrum in
`docs/APP_CHAIN_USE_CASES.md` — config-only products (Part A), plugin state
machines (Part B), and library embedding (Part C). "Simplest" means
time-to-first-value stays measured in minutes and failure modes are honest and
observable; "solid" means the trust claims we make are enforced by code, not
convention.

---

## 1. Verification of ADR-007's claims

Every load-bearing claim in ADR-007 was checked against the code. **All confirmed.**

| # | ADR-007 claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Pool backpressure not surfaced; submit can return an id for a dropped message | **Confirmed** | `AppChainSubsystem.submit()` discards `pool.add()`'s boolean (`AppChainSubsystem.java:470-474`); inbound `route()` too (`:376`); no drop counter anywhere; REST maps nothing → caller gets `202` |
| 2 | `sender-seq` carried but never enforced | **Confirmed** | `verifyEnvelope()` checks scheme, membership, signature only (`AppChainSubsystem.java:225-245`); `senderSeq` has zero read-for-validation uses; dedup = message-id only (100k sliding window + finalized-index) |
| 3 | Followers don't verify `l1-ref` | **Confirmed** | `handleProposal()` (`AppChainEngine.java:317-386`) never inspects `l1Slot`/`l1BlockHash`; `applyBlock()` copies them through (`:522-524`); catch-up doesn't check either. A proposer can embed a fabricated L1 ref and honest members co-sign it |
| 4 | Fixed sequencer only; proposer downtime halts the chain | **Confirmed** | `isProposer` is a final boolean (`AppChainEngine.java:44,87`); only the same proposer re-proposes on timeout; `AppChainStalledEvent` fires only when a peer is *observed ahead* (`AppChainSubsystem.java:317-346`) |
| 5 | Membership changes are node-local admin, not consensus | **Confirmed** | `MemberGroup` is height-versioned + persisted (`member_epochs` meta) but mutated via per-node admin calls; the gateway javadoc requires operators to repeat steps on every node (`AppChainGateway.java:126-131`) |
| 6 | Snapshot trust needs manifests | **Confirmed** | `verifyIntegrity()` only compares the tip block's stored `stateRoot` to the stored `state_root` meta (`AppLedgerStore.java:154-163`) — two values from the same untrusted directory; no manifest, no signature, no binding to cert/epoch/anchor |
| 7 | Anchor tx construction is basic | **Confirmed** | Fixed fee 300_000 (`AnchorService.java:39`), first-fit single input, `change = input − fee` with **no min-UTxO guard**, no validity interval, rollback rewinds by a fixed `everyBlocks` step (`:282`) which can under-rewind multi-interval ranges |
| 8 | Plugin determinism is convention-based | **Confirmed** | Only a javadoc contract on `AppStateMachine`; no conformance harness, no forbidden-API list; state-root mismatch → follower refuses to vote → liveness halt with only the generic stalled event |

## 2. Additional findings (not in ADR-007)

The audit surfaced defects and unmet ADR promises the codex report missed:

1. **`balances` minter authorization is dead config (real bug).**
   `BalancesStateMachine(String minterHex)` exists, but `BalancesProvider.create()`
   calls the no-arg constructor — the configured minter is never wired, so **any
   member can mint**. Root cause: stdlib providers don't override
   `create(AppStateMachineContext)`, so no stdlib machine can receive settings
   (only the ZK providers use the context seam).
2. **`AppStateMachine.query()` is dead code.** No stdlib/ZK machine overrides it,
   nothing calls it, and there is no REST `/query` route. ADR-006 §E2's "each
   exposing typed REST sub-resources" was not delivered — reads are generic
   proof/blocks endpoints plus static decode helpers.
3. **Client SDK's "don't trust, verify" is half-wired.** `ProofVerifier` verifies
   against the node's own claimed root by default; the
   `verify(proof, expectedStateRoot)` overload exists but the SDK has no way to
   obtain the anchored root from L1 — no anchor endpoint, no L1 adapter.
   Similarly, `EvidenceVerifier` lives in `core-api`, which the SDK deliberately
   doesn't depend on — an SDK-only auditor cannot verify evidence bundles.
4. **Two of ADR-006 §2's four SPIs were never built.** `AppMessageInterceptor`
   and `StateQueryIndex` don't exist; interception folded into
   `validate()`/`apply()`, and query indexes were hardcoded as an
   `app_query_index` CF in `AppLedgerStore`. Needs a formal decision (§6.1).
5. **Pool capacity hardcoded** at `new AppMsgPool(10_000)`
   (`AppChainSubsystem.java:123`) — not configurable, unrelated to block limits.
   Also, `submit()` relays to peers *before* the local pool add, so a message
   dropped locally may still be sequenced remotely — silent inconsistency.
6. **Unfinalized-id replay window.** Dedup for not-yet-finalized ids is a 100k
   sliding set; an id that ages out un-finalized can be re-diffused.
7. **L1 refs silently no-op** when `eventBus == null` (library/test mode) or
   `l1StabilityDepth == 0` (the default) — every block gets `l1Slot = 0` with no
   warning, so anchor/L1 linkage can be off without the operator knowing.
8. **`removeMember` skips full key normalization** (`trim().toLowerCase()` at
   `AppChainSubsystem.java:616` vs `normalizeMemberKeys` in `addMember` `:601`).
9. **No state-format versioning for state machines.** State entries are untagged
   CBOR; an encoding change across releases silently diverges on re-execution.
   There is no `stateVersion` recorded, no migration hook.
10. **Kafka sink is at-least-once, not the "exactly-once per height"** wording in
    ADR-006 E3.2 (correct behavior, wrong claim — fix the words, not the code).
11. **Metrics scrape inefficiency (minor):** the pool/peers gauges call
    `gateway.status()` (which builds the full status map) on every Prometheus
    scrape.
12. **Health is shallow:** app-chain readiness = `running` flag only. Stall,
    sequencing role loss, anchor failure, and sink lag are invisible to `/q/health`.

Confirmed-pending items from ADR-006's own list (still open): mTLS/OIDC,
KMS/HSM/Vault provider jars, Grafana dashboard, Kafka per-record proofs, Maven
archetype, `yano-appchain-client-zk` split, `authScheme=2` anonymous transport,
Quarkus client extension, kv-registry schema check, S2 rotation, chain-governed
membership (D6), script anchors (D2/D4).

## 3. Assessment

The v1 core is genuinely strong — the propose/verify/re-execute/threshold-cert
path, atomic ledger commits, verified catch-up, and MPF proofs all hold up under
audit, and the extension surface (Waves 1–5) is unusually complete. The honest
current label remains ADR-007's: **developer preview / permissioned pilot**.

The remaining work sorts into four kinds:
- **Broken promises** — code that contradicts what docs/ADRs claim (backpressure,
  sender-seq, balances minter, typed queries, SDK anchor verification). These are
  cheap and must come first: a "simple and solid" framework cannot ship claims
  its code doesn't keep.
- **The architectural line** — rotating sequencer, chain-governed membership,
  follower L1-ref verification, script anchors. These are what separate "anchored
  replicated log" from "robust app chain" (ADR-007 said this correctly).
- **Trust hardening** — snapshot manifests, determinism conformance, anchor tx
  production-grade construction, admin auth scopes.
- **Experience** — status page UI, typed query surface, SDK completion, starters,
  dashboards. This is where "simplest and best" is won.

---

## 4. Plan of record

Four iterations. Each is independently shippable; Iteration 1 items are small
and unblock trust in existing claims; Iteration 2 is the protocol centerpiece.
Effort: S (days), M (1–3 weeks), L (1 month+).

### Iteration 1 — Correctness, honesty, and visibility (P0)

**I1.1 Pool backpressure surfaced end-to-end (S).**
- Make pool capacity config (`yano.app-chain.pool.max-messages`, default 10_000).
- `submit()`: admit to the local pool **before** relaying; on `add() == false`
  throw a typed `PoolFullException` → REST maps to **429** with a JSON error
  (keep 503 for not-sequencing/paused). No more fake `202`s.
- `route()` (inbound): count drops per reason
  (`pool_full`, `duplicate`, `expired`, `bad_auth`) — expose in `status()` and as
  `yano.appchain.messages.dropped_total{chain,reason}`.
- Acceptance: a full-pool submit returns 429 and the message is not relayed;
  drop counters visible in `/q/metrics` and the status page.

**I1.2 Sender-seq: enforce or retract (S–M).** Decision proposed in §6.2 —
enforce the light form:
- Persist `last finalized seq` per sender in ledger meta, written inside the
  block-commit `WriteBatch` (rollback-safe by construction — the app ledger never
  rolls back after APP_FINAL).
- At admission and in-block verification: reject a message whose
  `senderSeq ≤ lastFinalizedSeq(sender)`. Gaps are allowed (out-of-order
  diffusion is normal); replays are not.
- Initialize the local `AtomicLong` from the persisted value at startup —
  today a restart resets it to 0, which would break under enforcement.
- Document precisely: sender-seq = replay protection + gap observability, not
  ordering (the sequencer orders).

**I1.3 Follower verification of `l1-ref` (M).** Closes the worst safety gap.
- If a proposal carries `l1Slot > 0`: the follower must (a) find that
  `(slot, hash)` in its own L1 view at ≥ `l1.stability-depth` (wait/retry with a
  bounded deferral before rejecting, since the follower may briefly lag),
  (b) check monotonicity vs the previous block's `l1-ref`.
- Same checks in catch-up when local L1 history covers the ref (tolerate pruned
  history explicitly).
- Fix the silent no-op (finding 7): if anchoring or `l1.stability-depth > 0` is
  configured but the L1 point feed is absent, **fail startup**, don't emit
  `l1Slot = 0` quietly. Log a clear warning in library mode when refs are off.

**I1.4 stdlib config wiring + balances fix (S).**
- All stdlib providers implement `create(AppStateMachineContext)` and read
  machine-scoped settings (`machines.balances.minter`,
  `machines.kv-registry.schema`, …).
- Wire the `balances` minter (fixes the mint-authorization bug); add the
  kv-registry optional schema check while in there (closes an ADR-006 pending).
- Normalize keys consistently in `removeMember` (finding 8).

**I1.5 Anchor tx production-grade construction (M).**
- Fee from protocol parameters (the node has them) by tx size, not a constant.
- Min-UTxO guard on change; multi-input selection when the first-fit input can't
  cover fee + min-UTxO.
- Validity interval (TTL) on every anchor tx so a resubmission can never race a
  late-landing original.
- Rollback rewind: record the last *confirmed* anchored height and rewind to it,
  instead of stepping back a fixed `everyBlocks`.

**I1.6 Determinism conformance harness (M).** In `appchain-testkit`:
- `StateMachineConformance.run(provider, corpus)` — apply the same generated
  block corpus in two (or N) fresh ledgers, assert identical state roots at every
  height; kill-and-replay mid-corpus; concurrent `validate()` smoke.
- A JUnit 5 `@AppStateMachineConformanceTest` wrapper so plugin authors get this
  in one annotation; the scaffold template includes it pre-wired.
- Document the forbidden-API list (wall clock, randomness, I/O, unordered
  iteration, locale/default-charset encoding) in the user guide and the SPI
  javadoc. Runtime enforcement (agent/classfile scanning) is explicitly out of
  scope — the harness + docs are the certification model.

**I1.7 Snapshot manifests (M).**
- `createSnapshot()` additionally writes `snapshot-manifest.json`:
  `{chainId, height, blockHashHex, stateRootHex, memberEpochsHash,`
  `lastAnchor{txHash,toHeight}, files:{relPath → sha256}, createdAt}` and an
  Ed25519 signature by the node's member key.
- Restore path verifies: manifest signature against a key the operator names
  (any current member), per-file hashes, then post-open: tip block hash, state
  root, and member-epoch hash all match the manifest; if an anchor ref is
  present, offer L1 cross-check.
- Strengthen `verifyIntegrity()`: recompute the tip block hash from bytes and
  verify its cert against membership-at-height (not just root == root).

**I1.8 Observability depth (S).**
- Add to `status()`: last block timestamp, rolling block interval, drop counters,
  stall flag (no progress + peer ahead), anchor lag (`tip − lastAnchoredHeight`),
  per-sink lag (`tip − cursor`).
- New metrics: `yano.appchain.stalled{chain}` (0/1 gauge),
  `yano.appchain.anchor.lag_blocks`, `yano.appchain.sink.lag_blocks{sink}`,
  drop counters (I1.1). Cache one `status()` per scrape (finding 11).
- Health: keep readiness = running (bootstrap-deadlock rationale stands), but add
  a Quarkus health *group* (`/q/health/appchain`) reporting stall/anchor/sink
  degradation for operators who want to alert on it.
- Ship the **Grafana dashboard JSON** (ADR-006 pending) once these exist.

**I1.9 App-chain status page UI (S–M).** Full design in §5 — deliberately in
Iteration 1: it is cheap, has outsized demo/ops value, and consumes only
existing + I1.8 endpoints.

### Iteration 2 — Liveness and governance (the architectural line)

**I2.1 S2 rotating sequencer (L).** ADR-005 D2's design, refined by the audit's
seam analysis:
- `proposerFor(window)` = `members[H(chainId ‖ epochNonce ‖ w) mod n]` over the
  membership epoch active at the tip; window `w` derived from **L1 slots**
  (`w = ⌊(l1Slot − chainGenesisSlot) / windowSlots⌋`) — every member already has
  the same L1 clock; no new time source, no NTP trust.
- Code seams (verified): replace the final `isProposer` boolean
  (`AppChainEngine.java:44,87`) with the window function; generalize the three
  fixed-proposer checks (`handleProposal :332`, `applyCertifiedBlock :164`,
  proposer-tick gate `AppChainSubsystem.java:910`); every member runs
  `proposeTick` and self-gates. `verifyCert`/`maybeFinalize` already resolve
  membership by height — they generalize unchanged.
- Safety: vote locks already persist one-vote-per-height; a follower accepts a
  proposal only from the current (or grace-window previous) window's proposer.
  Missed window → next window's proposer re-proposes the same height.
- Config: `sequencer.mode: fixed | rotating`, `sequencer.window-slots`.
  **S1 stays the default**; rotation is opt-in per chain. Adversarial tests:
  proposer kill mid-window (chain resumes ≤ 1 window), double-proposal at a
  boundary, follower with lagging L1 view.
- Add proposer accountability: missed-window counter per member,
  `yano.appchain.proposer.missed_windows_total{chain,member}`.

**I2.2 Chain-governed membership — 005 D6 (L).**
- Membership/threshold changes become **app messages on the reserved
  `~governance/membership` topic**, processed by a framework-level handler (not a
  user state machine): command `[op, memberKey|threshold, activationLag]`,
  admitted only from current members.
- Approval = the existing threshold: the change activates when ≥ threshold
  distinct members' matching approval messages are finalized; the handler then
  appends the epoch (`fromHeight = h + activationLag`) inside the same commit
  batch — so `epochAt(height)` becomes a pure function of finalized history, and
  catch-up/late joiners derive identical membership with zero runbooks.
- The admin REST endpoints become *proposers* of governance messages (they
  submit, they don't mutate); the current direct-mutation path is kept only as a
  documented break-glass flag (`--force-local-membership`).
- This supersedes the E4.5 runbook and unblocks governed VK/parameter
  distribution (ADR-006 §7.4): the same mechanism carries
  `~governance/params` blocks later (ZK VK hashes, block limits).

**I2.3 Message-id dedup hardening (S).** With rotation in play, tighten finding
6: track unfinalized ids with TTL-bounded eviction equal to the message max-TTL
(not a fixed 100k count), so an id cannot age out while still admissible.

### Iteration 3 — L1 integration depth

**I3.1 Script anchors — 005 D4/A2 (L).** *(Validator strategy decided
2026-07-10, pre-008.4: julc-default, Aiken opt-in.)*
- Plutus V3 anchor validator holding one UTxO per chain; inline datum
  `[chain-id, height, block-hash, state-root]`; spend requires monotonic height
  and an n-of-m member signature check; validator + off-chain builder shipped
  together, e2e devnet test (the MPF on-chain verifier already exists in ccl).
- **Two interchangeable implementations of ONE on-chain ABI** (datum/redeemer
  CDDL in `core-api/src/main/cddl/appchain/` — the contract of the contract):
  - **julc (Java → UPLC) is the default**: julc `0.1.0-pre14` is already in
    the version catalog (used by `ledger-rules`), it completes the Java-first
    story (state machines, ZK circuits AND the validator in Java), and
    `julc-vm-java` lets CI **execute the compiled validator in unit tests**
    (non-monotonic spend fails, sub-threshold sigs fail, happy path passes) —
    no devnet required. julc is a **build/test-time dependency only**; the
    distribution ships the compiled UPLC artifact + script hash, no new
    runtime deps (T-tier discipline holds).
  - **Aiken source lives in-repo as the opt-in alternative** — the
    auditor-familiar reference implementation and the escape hatch if julc
    codegen busts practical budgets.
- Everything downstream keys off the **configured compiled UPLC artifact**
  (bundled julc build by default; file/hex override for the Aiken build or
  any custom build) — script hash and address are always derived from it.
  A conformance test runs identical vectors against BOTH artifacts via
  `julc-vm-java`; 008.4 must include an **ex-units/size budget measurement**
  for both as an acceptance criterion (julc codegen is typically larger than
  hand-written Aiken).
- `AnchorService` gains mode A2 behind the existing `AnchorPolicy` config;
  metadata mode stays default.
- This is the prerequisite for E7.5 (ZK-verified anchors — same julc
  toolchain, which is exactly why julc-at-the-anchor converges the tracks)
  and for any on-chain-enforced bridge story; until it ships, keep
  positioning anchors as "commitment notarization" (ADR-007's language).

**I3.2 `L1View` + `~l1` observation injection — 005 D5 (M).**
- Expose the deterministic read API (`utxosAt`, `txMetadataByLabel`, evaluated
  as of the block's now-verified `l1-ref`) to state machines via
  `AppStateMachineContext`.
- Optional proposer-injected `~l1/...` observation messages verified by followers
  against their own L1 state at `l1-ref` (an unconfirmable observation
  invalidates the proposal). This turns the C2 "semi-trusted bridge pattern"
  from a discipline into a framework guarantee.

### Iteration 4 — DX and enterprise polish (parallelizable S–M items)

**I4.1 Typed query surface (M).** Bring `query()` to life:
- REST `GET/POST /app-chain/chains/{id}/query/{path}` → gateway →
  `stateMachine.query(path, params)`; JSON-in/JSON-out convention documented.
- Implement `query()` in all stdlib machines: kv get/list (paged), approvals
  item + open-items list, balances account/list, doc-trail entry list + head.
- SDK: `client.query(path, params)` + typed helpers per stdlib machine.
- This finally delivers ADR-006 E2's "typed REST sub-resources" and removes the
  biggest day-one friction after submit/read.

**I4.2 Close the verification loop in the SDK (M).**
- Node REST `GET /app-chain/chains/{id}/anchors?limit=` — confirmed anchor
  records `{txHash, fromHeight, toHeight, blockHash, stateRoot, l1Slot}`.
- SDK `L1AnchorSource` SPI with two impls: `NodeAnchorSource` (convenient, still
  trusts the node) and an independent adapter (Blockfrost/Koios/yaci-store —
  pick one reference impl) that reads the anchor tx metadata straight from L1.
  `ProofVerifier.verifyAnchored(proof, anchorSource)` completes the five-line
  "don't trust, verify" demo for real.
- Extract `EvidenceBundle`/`EvidenceVerifier`/codec into a slim, dependency-light
  `yano-appchain-evidence` module usable by both core-api and the client SDK
  (precedent: the GroupCipher wire-compat split), so an SDK-only auditor can
  verify bundles offline.

**I4.3 Security hardening (M).**
- Scoped API keys: `key = scopes + topics` with scopes `read | submit | admin`
  (admin implies per-chain restriction support); admin endpoints require the
  admin scope even when general auth is off? — no: auth stays opt-in, but the
  docs make "enable auth before exposing admin" a red-letter warning, and admin
  actions get an **audit log** (structured log events with actor key-id, action,
  chain, params).
- mTLS and OIDC/JWT via standard Quarkus security config, documented recipes
  (no custom code where Quarkus provides it).
- One reference KMS `SignerProvider` plugin jar (start with Vault transit;
  PKCS#11/AWS-KMS follow the same template).

**I4.4 Packaging & starters (S–M).**
- Split `yano-appchain-client-zk` (proving/disclosure helpers out of the node
  plugin) — ADR-006 follow-up.
- Quarkus client extension (mirror of the Spring starter).
- Maven archetype mirroring the Gradle plugin template.
- Kafka sink: optional per-record MPF proof attachment
  (`sinks.kafka.attach-proofs=true`), documented as at-least-once (fix the
  ADR-006 wording per finding 10).

**I4.5 State-format versioning (S).** Add `default int stateVersion()` to
`AppStateMachine` (see §6.3): recorded in ledger meta at first commit; on
startup a mismatch fails fast with a migration message. Document the
encoding-stability rule (canonical CBOR, no library-default serialization) next
to the determinism rules.

### Explicitly deferred (unchanged from ADR-006/007)
E7.4 private balances and E7.5 ZK anchors stay gated on ZeroJ maturity
(ADR-0026 criteria) and on I3.1; `authScheme=2` anonymous transport waits for
the yaci core auth-path work; DA sampling/public availability is out of scope
for the permissioned target.

---

## 5. App-chain status page UI (I1.9) — design

A sibling of the L1 status page, matching its architecture exactly: one
checked-in static file, no framework, no build step, no external assets.

**Location & serving.** `app/src/main/resources/META-INF/resources/ui/app-chain/index.html`
→ served at `/ui/app-chain/`. Add a small cross-link in both pages' topbars
("L1 Status ⇄ App Chains") — the L1 page currently has no nav; this is the
first, deliberately minimal, addition.

**Reuse from the L1 page** (same file conventions, copy the helpers):
- CSS token sets (light/dark via `localStorage['yano-status-theme']`,
  glassmorphism cards, `.ac-*` accent classes, pills/badges, `.mono` numerics,
  grid layout with 1180/720px breakpoints).
- JS helpers: `$`, `text()` (with value-change flash), `pill()`, `fmt`,
  `bytes`, `push()` ring buffers (72 points), the hand-rolled canvas
  `drawLines` chart kit with hover tooltip, `jsonOrNull`, `escapeHtml`,
  `?api=`/`?poll=`/`?noanim=` query params, 5s default polling.

**Data sources** (all existing today; I1.8 enriches them):
1. `GET /api/v1/app-chain/chains` → chain list `{chainId, tipHeight, stateRoot}`
   — drives a **chain selector** (tabs when ≤4 chains, dropdown beyond); the
   selected chain persists in `localStorage`.
2. `GET /api/v1/app-chain/chains/{id}/status` → the rich map (role, members,
   threshold, tip, stateRoot, stateMachine, anchor{...}, sinks{...}, pool +
   traffic counters, peers{...}, submissionsPaused).
3. `GET /api/v1/app-chain/chains/{id}/blocks?limit=15` → recent-blocks table.
4. `SSE /api/v1/app-chain/chains/{id}/stream?fromHeight=-1` → live finalized
   message feed (event `app-message`, heartbeats every 15s).

**Layout** (top to bottom):
- **Topbar**: brand "Yano App Chain", chain selector, updated-at, request-ms
  chip, health pill (`RUNNING` ok / `PAUSED` warn / `STALLED` warn-pulse /
  `STOPPED` bad — from `running`, `submissionsPaused`, stall flag), theme
  toggle, link to L1 status.
- **Hero card — "Chain"**: big tip height, role pill (`PROPOSER` violet /
  `MEMBER` blue), `m-of-n` membership stat, state machine id, state root
  (short mono + copy), block interval.
- **Section "Consensus & Traffic"** (`grid-3`): *Consensus* (tip, threshold,
  members count, last block age, cert signers of latest block); *Pool*
  (poolSize with capacity bar once I1.1 lands, paused badge, dropped-by-reason
  once available); *Traffic* (submitted / received / relayed / duplicates).
- **Section "Anchoring & Delivery"** (`grid-3`): *Anchor* (enabled badge,
  lastAnchoredHeight, **anchor lag** = tip − lastAnchored with ok/warn
  coloring, pendingTx short-hash, lastAnchorTx short-hash, lastError in red);
  *Sinks* (per-sink rows: id, cursor, lag, delivered, lastError); *Peers*
  (per-peer row with live-dot connected indicator — same pattern as the L1
  peers table, but tiny).
- **Section "Trends"** (`grid-2`, canvas charts): Tip growth (blocks/min,
  computed as per-poll delta like the L1 tx-diffusion chart), Pool size,
  Messages per block, Anchor lag.
- **Section "Live messages"**: SSE-fed rolling feed (last ~50): `height:index`,
  topic chip, sender short-hex, body size; falls back to "stream unavailable"
  chip if EventSource errors, with auto-retry.
- **Section "Recent blocks"**: table — height, age, message count, cert
  signatures, state root (short). Row flash on new block.
- **Footer**: poll interval + api prefix, mirroring the L1 page.

**Multi-chain behavior.** The selector re-targets all fetches/SSE at the scoped
paths; a small "all chains" strip under the topbar shows each chain's tip +
health dot for at-a-glance fleet state.

**Auth wrinkle (documented, resolved simply).** When
`yano.app-chain.api.auth.enabled=true`, the page needs `X-API-Key`. Fetches can
send the header from `localStorage['yano-appchain-key']` (a small key-entry
field appears when a 401 is seen). `EventSource` cannot set headers — so the
live feed switches to a `fetch()`-streaming reader (same SSE wire format,
~20 lines) instead of adding query-param keys to the filter (avoids keys in
access logs).

**Server-side additions needed: none for v1.** Everything renders from existing
endpoints; I1.8's status enrichment (block interval, stall flag, drop counters,
anchor/sink lag) upgrades the page from "computed client-side" to "reported by
the node" — ship the page first, enrich second.

**Testing.** A QuarkusTest that asserts the static file is served and a
Playwright-less smoke (fetch + regex for panel ids) in the existing devnet
extensions regression skill (`test-app-chain-extensions`).

---

## 6. Decisions to ratify

**6.1 Drop `AppMessageInterceptor` and `StateQueryIndex` from the plan (amend
ADR-006).** The shipped seams (validate/apply + `AppStateMachineContext`
settings; fixed `app_query_index` CF) cover every current consumer, including
all three ZK machines. Building the SPIs now would be speculative surface area.
Trigger to revisit: a second cross-machine concern (quotas, generic body
encryption enforcement) or a plugin needing custom secondary indexes.

**6.2 Sender-seq: enforce the light form** (I1.2) rather than deleting the
field: replay rejection below the last finalized per-sender seq, gaps allowed.
Deleting it would be an envelope change (yaci coordination) for negative value;
full monotonic-no-gaps enforcement breaks legitimate multi-path diffusion.
ADR/docs updated to state exactly what it guarantees.

**6.3 State-machine state versioning**: add `stateVersion()` (default 1) +
fail-fast mismatch (I4.5). Re-execution correctness across releases otherwise
rests on nothing.

**6.4 Positioning stays honest.** Keep "developer preview / permissioned
pilot" until Iteration 1 + 2 land; then "enterprise permissioned beta"
(ADR-007's rubric). Never market metadata anchors as settlement; script anchors
(I3.1) are the gate for any bridge/enforcement language.

## 7. Sequencing and effort summary

| Iteration | Contents | Effort | Outcome |
|---|---|---|---|
| **1** | I1.1–I1.9 (backpressure, sender-seq, l1-ref verify, stdlib config/balances fix, anchor tx hardening, determinism harness, snapshot manifests, observability, **status page**) | ~3–4 weeks | Every shipped claim is true; ops can see and trust the chain |
| **2** | S2 rotation, chain-governed membership, dedup hardening | ~4–6 weeks | Liveness without a single proposer; governance without runbooks — the "robust app chain" line |
| **3** | Script anchors, L1View + `~l1` injection | ~4 weeks | On-chain enforcement path; deterministic L1-reactive apps |
| **4** | Typed queries, SDK verification loop, security hardening, packaging/starters, state versioning | ~3–4 weeks (parallelizable) | The "simplest and best" developer experience |

Iterations 1 and 4 are largely parallel-safe with 2/3 (product vs protocol
tracks), matching ADR-006's wave discipline. Each iteration ends with the
existing regression skills (`test-app-chain-cluster`,
`test-app-chain-extensions`) extended to cover its new behavior, plus the
multi-agent review gate used for Waves 1–3.

## 8. Risks

| Risk | Mitigation |
|---|---|
| S2 window/clock edge cases (L1 view skew at window boundaries) | Grace-window acceptance of the previous proposer; adversarial boundary tests; S1 remains default until soak-tested |
| Governed membership bricking a chain (bad threshold change finalized) | Activation lag + guard rails carried over from E4.5 (can't drop below threshold, can't remove active proposer set); break-glass local override retained |
| Sender-seq enforcement rejecting legitimate traffic after restarts | Seq counter re-initialized from persisted last-finalized value (I1.2); enforcement feature-flagged for one release |
| l1-ref verification stalls chains whose followers lag L1 | Bounded wait/retry before rejection; depth check only active when `stability-depth > 0`; metrics on deferrals |
| Status page drifting from REST changes | Page consumes only documented status/blocks/stream shapes; devnet regression skill asserts panel data binds |
| Scope creep in Iteration 4 | Every item is independently shippable and cut-able; protocol iterations (2–3) never wait on it |
