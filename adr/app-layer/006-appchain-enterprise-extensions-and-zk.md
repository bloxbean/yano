# ADR-006: App-Chain Enterprise Extensions & ZK Integration (ZeroJ)

## Status
Accepted — implementation in progress

## Date
2026-07-08

## Implementation Status

Branch strategy: `feat/app_layer_extensions` (created from `feat/app_layer`)
is the **integration branch** for all ADR-006 work. Each wave is developed,
tested and reviewed on its own working branch (`feat/wave1-extensions`, ...)
and merged into the integration branch on completion.

| Wave | Scope | Status |
|---|---|---|
| **Wave 1** | E5.2 multi-chain, E2.1 kv-registry, E2.2 approvals, E3.1 SSE/webhooks, E4.1 REST auth, E5.1 metrics, E1.2 testkit, E1.1 client SDK | **In progress** (`feat/wave1-extensions`) |
| Wave 2 | E1.3 codecs, E2.3/E2.4 stdlib completion, E3.2 Kafka bridge, E3.4 audit export, E4.2 encrypted bodies, E4.3 KMS signing, E4.4 retention, E5.3 snapshots, E1.5 scaffolds | Not started |
| Wave 3 (ZK) | E7.1 ZK verification, E7.2 BBS disclosure, then E7.3 zk-membership; E7.4/E7.5 as spikes | Not started |
| Deferred to the end | **E1.4 Spring Boot starter + Quarkus extension** — deprioritized (2026-07-08): pure sugar over the E1.1 client SDK; built once, after all waves, when the SDK surface has stabilized across them | Deferred |

## Related
- `adr/app-layer/005-yano-app-chain-framework.md` — the shipped v1 framework (M1–M6) this ADR extends
- `docs/APP_CHAIN_USER_GUIDE.md`, `docs/APP_CHAIN_TUTORIAL.md`, `docs/APP_CHAIN_USE_CASES.md`
- ZeroJ project: `/Users/satya/work/bloxbean/zeroj` (`0.1.0-pre5`), esp. `zeroj-verifier-core`, `zeroj-patterns`, `zeroj-bbs`, `zeroj-onchain-julc`, ADR-0026 (production-readiness) and ADR-0029 (blst prover)
- Deferred v1 items that remain the base roadmap: S2 rotating sequencer, chain-governed membership, script anchors (005 D2/D4/D6)

---

## 1. Context and goal

App-chain v1 ships a working core: authenticated diffusion, fixed-sequencer
finality with threshold certs, an MPF-committed ledger, L1 anchoring, catch-up,
an `AppStateMachine` SPI with plugin-jar loading, REST, docs and tooling.

The goal of this ADR is to make the **out-of-the-box experience attractive and
advanced for enterprise Java developers** — lower the time-to-first-value,
cover more use cases without custom code, meet enterprise non-functional
requirements (security, compliance, operations, integration) — and to explore
**zero-knowledge capabilities** now that ZeroJ exists.

**Hard requirement:** everything here is **optional and opt-in** — selected by
configuration, packaged as separate modules/plugin jars, or used purely
client-side. The core stays lean; a node with none of these enabled behaves
exactly like v1. Experimental features (all ZK) are labeled as such and are
never on any default code path.

## 2. Opt-in packaging model (how "pluggable" is realized)

Four delivery mechanisms, in increasing distance from the core:

| Tier | Mechanism | Used for |
|---|---|---|
| **T1 — core flag** | Config property on the existing subsystem; code ships in `runtime` but is dormant | Cheap, broadly wanted features with no new deps (multi-chain, SSE, retention, metrics) |
| **T2 — optional module in the default distribution** | Separate Gradle module compiled into `yano.jar`, activated by config (e.g. selecting a state-machine id) | Standard-library state machines, connectors with light deps |
| **T3 — plugin jar** | Jar dropped into `plugins/` (ServiceLoader SPIs), never part of the distribution | Anything with heavy or experimental dependencies — **all ZK**, Kafka bridge, KMS providers |
| **T4 — client-side library** | Published artifact used by the *application*, not the node | Client SDK, Spring/Quarkus starters, codecs, off-node proof verification |

New SPIs required to make T2/T3 clean (all additive to `core-api`):

- **`AppMessageInterceptor`** — ordered hooks around admission and finalization
  (`onAdmission(msg) → accept/reject`, `onFinalized(block)`); the mount point
  for ZK verification, encryption, quotas, and connectors. Discovered via
  ServiceLoader + registered programmatically; each interceptor declares an id
  and is only active when listed in `yano.app-chain.interceptors`.
- **`StateQueryIndex`** — optional secondary-index SPI fed from `apply()`
  writes (indexes are derived, rebuildable, never part of consensus state).
- **`SignerProvider`** — abstracts member/anchor signing so KMS/HSM plugins
  can hold keys (`sign(bytes)`, `publicKey()`); default = in-config seed
  (current behavior).
- **`FinalizedStreamSink`** — push-consumer SPI (at-least-once, per-sink
  cursor persisted in the app ledger meta CF).

## 3. Enhancement catalog

Ranked within each group by attractiveness-per-effort. Effort: S (days),
M (1–3 weeks), L (1 month+).

### E1 — Enterprise Java developer on-ramp

| # | Enhancement | Tier | Effort | Why it matters |
|---|---|---|---|---|
| E1.1 | **`yano-appchain-client`** Java SDK: typed submit/poll/subscribe over REST+SSE, **client-side MPF proof verification** (verify a record against an anchored root without trusting the node — the MPF verify code already exists in ccl), key/identity helpers | T4 | M | The single biggest on-ramp improvement: enterprise devs integrate via a library, not curl. Client-side proof verification is the "don't trust, verify" demo in five lines of Java |
| E1.2 | **Testkit JUnit 5 extension** (`@AppChainCluster(nodes = 3)`): embedded multi-node chain with temp ledgers for application tests | T4 | S–M | Enterprise adoption dies without CI-friendly testing; the integration-test harness already written for M2–M4 is 80% of this — it just needs packaging (aligns with ADR-027 R1 testkit) |
| E1.3 | **Typed message codec layer**: `MessageCodec<T>` SPI + Jackson-CBOR default, versioned type tags, delivered in the client SDK and usable in state machines (`TypedAppStateMachine<T>`) | T4 | S | Removes the "everything is byte[]" friction while keeping the framework blob-first (codec is strictly at the edges) |
| E1.4 | **Spring Boot starter + Quarkus extension** for the client SDK (`@AppChainListener(topic="orders")`, auto-configured `AppChainTemplate`) | T4 | M | Meets enterprise devs in their frameworks; pure sugar over E1.1 |
| E1.5 | **Scaffold**: `docker-compose` cluster generator + a Maven archetype / Gradle template for a state-machine plugin project | T4 | S | Converts the tutorial into `init` commands; big first-impression win |

### E2 — Standard library of state machines (out-of-the-box use cases)

A `yano-appchain-stdlib` module (T2) with ready state machines selected by id,
each configurable and each exposing typed REST sub-resources. Turns the four
most common enterprise patterns into config-only products:

| # | Machine id | What it gives out of the box | Effort |
|---|---|---|---|
| E2.1 | `kv-registry` | Replicated registry with **per-key ownership** (only creator/owner key may update), optional schema check, per-key proofs. Use: token registries, DID docs, allow-lists, config | S–M |
| E2.2 | `approvals` | k-of-n approval workflows: propose/approve/reject commands, per-item state, deadline expiry (block-timestamp based, deterministic), full provable decision trail. Use: release gates, payment authorization | M |
| E2.3 | `balances` | Account balances with transfer/mint commands, per-sender authorization, non-negativity enforced in `apply()`, provable balances. Use: netting, loyalty points, internal credits (x402 building block) | M |
| E2.4 | `doc-trail` | Document/event trails keyed by external id (`productId`, `caseId`): append-only per-entity history, per-entity proof bundle endpoint. Use: DPP, case management, evidence chains | S–M |

These four cover the majority of Part A/B use cases in
`docs/APP_CHAIN_USE_CASES.md` with zero application code, and each doubles as
a reference implementation for custom machines.

### E3 — Consumption, querying, integration

| # | Enhancement | Tier | Effort |
|---|---|---|---|
| E3.1 | **Push consumption**: SSE/WebSocket stream of finalized messages (`GET /app-chain/stream?topic=...&fromHeight=...`) + webhook sink with per-consumer cursor and at-least-once redelivery | T1 | M |
| E3.2 | **`FinalizedStreamSink` SPI + Kafka bridge plugin** (finalized blocks → topics, exactly-once per height, optional proof attached per record) | T3 | M |
| E3.3 | **Query surface**: paged block range API, message lookup by id (height + block position), sender/topic secondary indexes via `StateQueryIndex` | T1/T2 | M |
| E3.4 | **Signed audit export**: portable evidence bundle per record or range — block header, finality cert, MPF proof, anchor tx reference — as a single JSON/CBOR file an auditor can verify offline with the client SDK | T1 | S–M |

### E4 — Enterprise security & compliance

| # | Enhancement | Tier | Effort |
|---|---|---|---|
| E4.1 | **REST authn/authz**: API-key/mTLS/OIDC via standard Quarkus security for `/app-chain/*`, per-topic submit permissions | T1 (config) | S–M |
| E4.2 | **Encrypted bodies**: optional envelope encryption of message bodies with a group key (config/KMS-provided); the chain remains blob-first — ordering, proofs and anchors work unchanged over ciphertext; decryption is client/state-machine side. Per-topic keys enable need-to-know inside one chain | T2 + T4 | M |
| E4.3 | **KMS/HSM signing** via `SignerProvider` plugins (PKCS#11, AWS KMS, Vault) for member and anchor keys — removes raw seeds from config files, usually the first enterprise security review finding | T3 | M |
| E4.4 | **Retention & pruning with proof preservation**: prune message bodies below the last `L1_FINAL` anchor while keeping headers, roots and the message index — GDPR/data-minimization compatible because anchored roots stay verifiable; combined with E4.2, "crypto-shredding" (destroy the key) erases content while preserving evidence | T1 | M |
| E4.5 | **Key rotation runbook + tooling**: staged member-key rotation (add new key, re-threshold, retire old) as an admin API — interim measure until chain-governed membership (005 D6) ships | T1 | M |

### E5 — Operations

| # | Enhancement | Tier | Effort |
|---|---|---|---|
| E5.1 | **Micrometer metrics** (pool depth, block latency, cert wait, peer connectivity, anchor lag) + a Grafana dashboard template; wire into the existing status dashboard | T1 | S–M |
| E5.2 | **Multi-chain per node**: config becomes a list (`yano.app-chain.chains[i].*`); one subsystem instance per chain (already how the ledger layout works — the flat config is the only blocker) | T1 | M |
| E5.3 | **Ledger snapshot/restore + fast member onboarding**: snapshot at height h, new member restores and verifies `state-root` before joining live (block format already binds the root — designed for this in 005 D8) | T1 | M |
| E5.4 | Admin API: pause/resume submissions, drain pool, force-anchor now | T1 | S |

### E6 — Base-roadmap items (from ADR 005, unchanged priority)

S2 rotating sequencer (liveness), script anchors (on-chain proof
verification — prerequisite for enforceable bridges and a synergy point for
ZK below), chain-governed membership. These remain the highest-value
*protocol* work; E1–E5 are *product* work that can proceed in parallel.

---

## 4. ZK integration with ZeroJ (E7) — analysis

### 4.1 What ZeroJ provides today (survey, `0.1.0-pre5`)

- **Pure-Java Groth16 + PlonK on BLS12-381** — prove and verify with zero
  native dependencies (GraalVM-compatible; optional blst FFM backend ≈5×
  prover speedup). Verification is deterministic and CPU-bounded — critical,
  because it means proof verification **may run inside `apply()`** without
  breaking the app chain's determinism contract.
- **Java circuit DSL** (`@ZKCircuit` symbolic annotations / `CircuitSpec`) +
  gadget library (Poseidon, Merkle, comparators) — circuits in the same
  language as `AppStateMachine`, a unique full-Java story.
- **`zeroj-patterns`** — prebuilt verifier patterns that map 1:1 onto
  app-chain needs: `MembershipVerifier` (prove set membership without
  revealing which member), `NullifierClaimVerifier` (one-time claims /
  double-action prevention), `StateTransitionVerifier` (old-state → new-state
  validity), plus `VerificationPolicyTemplate`.
- **`zeroj-bbs`** — BBS signatures (CFRG draft): selective disclosure of
  signed attributes.
- **`zeroj-onchain-julc`** — Groth16 BLS12-381 verification in Plutus V3
  validators (proven e2e on devnet); PlonK on-chain experimental.
- **`zeroj-mpf-poseidon`** — ZK-friendly Poseidon MPF (experimental; circuit
  currently too large for practical on-chain verification).
- **Maturity**: explicitly *experimental / not for production or value-bearing
  use*; Groth16 needs an external MPC ceremony for a production setup (PlonK's
  universal setup is operationally easier for enterprises).

**Consequence for packaging:** every ZK feature is **T3 (plugin jar,
`yano-appchain-zk`)**, marked experimental, never in the default distribution,
and never on a code path unless explicitly configured. ZeroJ's maturity level
matches an experimental extension of an experimental-preview feature — the
labels must travel together.

### 4.2 The natural mount points

The v1 architecture already has exactly the seams ZK needs, which is why this
integration is cheap relative to its impact:

1. **Admission** (`AppStateMachine.validate` / `AppMessageInterceptor`) — verify
   proofs before a message enters a block.
2. **Apply** (`AppStateMachine.apply`) — verification is deterministic, so
   proof checks can be consensus-critical: a block containing an invalid proof
   fails state-root re-execution on honest members and is rejected.
3. **Envelope auth scheme** — the envelope's `auth-proof = [scheme, proof]`
   was designed extensible (scheme 0 = Ed25519, 1 = reserved SPO-KES);
   **allocate scheme 2 = zk-membership**.
4. **State commitment** — MPF roots anchored to L1 are public commitments that
   circuits can reference (via public inputs).
5. **Anchor path** — script anchors (005 A2) + Julc validators = on-chain
   verification of proofs about app-chain state.

### 4.3 ZK feature options

| # | Feature | What it enables | Builds on | Effort / risk |
|---|---|---|---|---|
| **E7.1** | **ZK admission & consensus verification** — messages carry a proof envelope `[circuitId, proof, publicInputs]` in (or alongside) the body; a `ZkMessageValidator` interceptor verifies via ZeroJ's `VerifierOrchestrator` at admission, and optionally *in `apply()`* for consensus-critical enforcement. Circuit registry = config: `circuitId → verification key` (VK files distributed with the chain config) | **Private-input business rules**: prove "amount ≤ credit limit", "age ≥ 18", "salary in band", "KYC attribute holds" — without the chain (or the other members!) ever seeing the underlying data. This is the single most enterprise-relevant ZK capability: policy compliance across organizations with data minimization by construction | zeroj-verifier-core (Beta) | **M** — verification-only; deterministic; cap proofs/block for CPU-bounding. *Lowest risk, highest generality — build first* |
| **E7.2** | **BBS selective-disclosure records** — records are BBS-signed attribute sets (issued by a member, e.g. HR, a bank, a certifier); the chain stores the signed commitment; holders later disclose *selected fields* with a derived proof, verified client-side (SDK) against the anchored record | Share one field of an audit/HR/certification record with a third party, provably from the anchored original — "verifiable credentials on an anchored registry." Pairs perfectly with E2.4 doc-trail and E1.1 client verification | zeroj-bbs (Beta) | **S–M** — mostly client SDK + stdlib machine support; no consensus change |
| **E7.3** | **ZK membership auth (envelope scheme 2)** — sender proves "I am one of the N registered members" (MembershipVerifier over a Merkle root of member keys) instead of signing with an identifiable key; `NullifierClaimVerifier` adds per-context one-time semantics | **Anonymous-but-authorized submissions**: whistleblowing channels, anonymous voting/surveys among known members, sealed bids — with double-action prevention via nullifiers. A capability essentially no enterprise MQ offers | zeroj-patterns Membership + Nullifier | **M–L** — touches the transport validator (scheme 2) and per-sender-seq semantics (anonymous senders can't have per-sender sequences → per-nullifier dedup instead); design carefully, ship behind chain-level `auth-mode` |
| **E7.4** | **Private state with public commitments** — the stdlib gains a `private-balances` machine: MPF stores Poseidon commitments to balances; transfer messages carry `StateTransitionVerifier` proofs that the hidden transition is valid (non-negative, conserved); members verify proofs in `apply()` without seeing amounts | **Confidential consortium settlement**: members agree on correctness of each other's balances without seeing them; only counterparties know amounts. The flagship "advanced" demo | zeroj-patterns StateTransition + circuit-lib Poseidon | **L** — needs a real circuit (transfer validity), proving on the client side (SDK integration), nullifier design; genuinely novel but well-trodden shape (Zerocash-lite over a permissioned ledger) |
| **E7.5** | **ZK-verified script anchor** — the anchor becomes a Julc Groth16 validator spend that verifies, on-chain, a proof about the anchored root (first step: proof of correct cert threshold over the block hash; long-term: proof that root N+1 extends root N by a valid batch — zk-rollup-lite) | On-chain *enforcement* rather than just recording; the trust-minimized end-state for bridges | zeroj-onchain-julc + 005 script anchors | **L / exploratory** — the full-validity circuit is research-grade (Poseidon-MPF circuit currently too large per ZeroJ's own status); do the threshold-cert proof first, treat batch-validity as a spike |

### 4.4 Design decisions for the ZK module

- **Verification-first, proving-client-side.** The node (and consensus) only
  ever *verifies* proofs — pure Java, deterministic, bounded. Proof
  *generation* (expensive, needs witnesses/secrets) happens in the submitting
  application via the client SDK (`yano-appchain-client-zk`), where the
  secrets live anyway. The node never holds proving keys or secrets.
- **Circuits are chain configuration.** A chain that uses ZK pins
  `circuitId → VK hash` in its config (and eventually in chain-governed
  membership/parameter blocks). All members must agree on VKs the same way
  they agree on membership — a VK mismatch is a config error, surfaced at
  startup, not a runtime divergence.
- **Prefer PlonK for enterprise deployments** (universal setup — no
  per-circuit MPC ceremony); Groth16 where verification cost/on-chain path
  matters. The ZeroJ SPI makes this a per-circuit choice.
- **Dual commitment only if/when E7.4 batch-validity is pursued**: keep the
  Blake2b MPF (Aiken-compatible, cheap) as *the* state commitment; add an
  optional Poseidon accumulator alongside it only for chains that opt into
  ZK-over-state — never replace the default (Blake2b hashing inside circuits
  is impractical; Poseidon on-chain via Aiken is impractical; the dual
  structure serves both worlds).
- **Determinism guardrails**: proof verification in `apply()` is allowed;
  proof *generation*, randomness, and clock access remain forbidden. Cap
  `zk.max-proofs-per-block` and measure verify time in CI (a Groth16 verify
  is a few pairings — low tens of ms in pure Java; a block of hundreds must
  stay within the block interval).

## 5. Recommended phasing

**Wave 1 — "enterprise-ready preview" (product, no protocol changes):**
E1.1 client SDK (with proof verification), E1.2 testkit extension,
E2.1 `kv-registry` + E2.2 `approvals`, E3.1 SSE/webhooks, E4.1 REST auth,
E5.1 metrics, E5.2 multi-chain. This is the set that changes the external
pitch from "interesting core" to "I can build my system on this this week."

**Wave 2 — compliance & integration:** E1.3 codecs + E1.4 starters,
E2.3/E2.4 stdlib completion, E3.2 Kafka bridge, E3.4 audit export,
E4.2 encrypted bodies, E4.3 KMS signing, E4.4 retention, E5.3 snapshots,
E1.5 scaffolds.

**Wave 3 — ZK preview (parallel to Wave 2, experimental label):**
E7.1 ZK admission/consensus verification + E7.2 BBS selective disclosure
(both verification-only, lowest risk), demo circuits (`age ≥ 18`,
`amount ≤ limit`) shipped in the plugin. Then E7.3 zk-membership auth.
E7.4 private balances and E7.5 zk-anchors as spikes gated on ZeroJ maturity
milestones and the script-anchor work from 005.

Base-roadmap items (S2 rotation, script anchors, chain-governed membership)
proceed independently and are prerequisites only where marked (E7.5).

## 6. Risks

| Risk | Mitigation |
|---|---|
| **ZeroJ maturity** (experimental, unaudited, trusted-setup ops) | T3 plugin only; experimental labels travel together; verification-only in consensus; PlonK-first; gate value-bearing patterns on ZeroJ's own ADR-0026 production gates |
| Scope creep across E1–E5 | Wave discipline; every item independently shippable; stdlib machines double as SPI reference tests |
| Consensus perf regression from in-`apply()` proof verification | Per-block proof caps, verify-time budgets in CI, admission-time pre-verification so invalid proofs never reach a block in honest flows |
| Interceptor SPI becomes a kitchen sink | Small contract (admission + finalized hooks only), ordered, explicit opt-in list in config |
| Encrypted bodies vs. debuggability/state machines | Encryption is per-topic opt-in; stdlib machines declare whether they operate on plaintext or ciphertext |
| Key-management plugins widen the attack surface | `SignerProvider` is sign-only (no key export); default remains unchanged |
| Client SDK drift vs. node REST | SDK and REST versioned together; contract tests in CI reuse the testkit |

## 7. Open questions

1. Should `yano-appchain-stdlib` be in the default distribution (T2, config-selected) or a separate download? *Leaning T2 — it is pure Java, small, and the out-of-box story is the point.*
2. Proof envelope placement for E7.1: inside the opaque body (application convention, zero framework change) vs. a first-class optional envelope field (framework-visible, enables generic caps/metrics). *Leaning: start as body convention in the plugin; promote to envelope field if usage proves it.*
3. zk-membership (E7.3) vs. per-sender sequence numbers: replace with per-nullifier dedup, or keep an "anonymous senders have no seq" rule? Needs a small design note before implementation.
4. VK distribution: config files first; move into chain-governed parameter blocks when 005 D6 membership governance lands?
5. Does the BBS issuer role need to be a chain member, or any configured issuer key set? (Affects E7.2 config shape.)
6. Naming: `yano-appchain-zk` as one plugin vs. split verification/BBS plugins — one jar is simpler; ZeroJ's BOM keeps the dependency tree manageable either way.
