# ADR-006: App-Chain Enterprise Extensions & ZK Integration (ZeroJ)

## Status
Accepted and implemented — scheduled waves complete; residuals tracked separately

## Date
2026-07-08

## Implementation Status

> **Current-status note (2026-07-17):** The five scheduled delivery waves are
> complete. The checklist below is the 2026-07-08 delivery snapshot; several
> listed base prerequisites (rotating sequencing, governed membership, and
> script anchors) were subsequently delivered by ADR-008. Current open work and
> revival triggers are authoritative in [open_item.md](open_item.md) and
> [pending-tasks.md](pending-tasks.md).

Branch strategy: `feat/app_layer_extensions` (created from `feat/app_layer`)
is the **integration branch** for all ADR-006 work. Each wave is developed,
tested and reviewed on its own working branch (`feat/wave1-extensions`, ...)
and merged into the integration branch on completion.

| Wave | Scope | Status |
|---|---|---|
| **Wave 1** | E5.2 multi-chain, E2.1 kv-registry, E2.2 approvals, E3.1 SSE/webhooks, E4.1 REST auth, E5.1 metrics, E1.2 testkit, E1.1 client SDK | **Done** (2026-07-08, `feat/wave1-extensions` → merged) |
| **Wave 2** | E2.3 balances, E2.4 doc-trail, E1.3 typed codec, E3.4 audit export, E4.4 retention/pruning, E4.2 encrypted bodies, E4.3 SignerProvider, E3.2 sink SPI + Kafka bridge, E5.3 snapshot/restore, E1.5 scaffolds | **Done** (2026-07-08, `feat/wave2-extensions` → merged) |
| **Wave 3 (ZK)** | E7.1 ZK verification, E7.2 BBS disclosure, E7.3 zk-membership; E7.4/E7.5 gated spikes | **E7.1–E7.3 done** (2026-07-08, `feat/wave3-extensions` → merged; open questions resolved — see §7) |
| **Wave 4 (ops)** | E5.4 admin API, E4.5 key rotation, E3.3 query surface | **Done** (2026-07-08, `feat/wave4-extensions` → merged) |
| **Wave 5 (starters)** | E1.4 Spring Boot starter (Quarkus client extension still pending) | **Done** (2026-07-08, `feat/wave5-starters`) |
| **Pending** | Everything not yet picked up — see the checklist below | Open |

### PENDING — not yet implemented (2026-07-08; pick up later)

Full E-items never scheduled into a wave:

- [x] **E3.3 Query surface** — done 2026-07-08 (Wave 4): paged
  `GET /blocks?from&limit`, `GET /messages/{id}` (position + content),
  by-topic/by-sender endpoints over a new `app_query_index` CF written in the
  block-commit atomic batch (pre-upgrade blocks not indexed).
- [x] **E4.5 Key rotation runbook + tooling** — done 2026-07-08 (Wave 4):
  runtime-mutable `MemberGroup` (subsystem + engine read the effective set),
  persisted override wins over config across restarts, add/re-threshold/retire
  admin API with guard rails, staged runbook in the user guide.
- [x] **E5.4 Admin API** — done 2026-07-08 (Wave 4): pause/resume local
  submissions, drain pool, force-anchor now.
- [x] **E1.4 Spring Boot starter** — done 2026-07-08 (Wave 5,
  `spring-starters/appchain-spring-boot-starter`): auto-configured
  `AppChainClient`/`AppChainTemplate` from `yano.appchain.client.*` and
  `@AppChainListener(topic)` over auto-reconnecting SSE. *Quarkus client
  extension remains pending* — Quarkus apps are often the node itself; CDI
  apps can use the plain SDK meanwhile.
- [ ] **E7.4 Private balances** (T3, L, gated on ZeroJ maturity): Poseidon
  commitments in MPF + `StateTransitionVerifier` proofs; needs a real transfer
  circuit, client-side proving, note-nullifier design, and the dual-commitment
  (Blake2b + optional Poseidon) structure. Value-bearing — gated on ZeroJ
  ADR-0026 production criteria.
- [ ] **E7.5 ZK-verified script anchor** (L/exploratory, needs 005 D2/D4 script
  anchors): first milestone = on-chain Groth16 (julc) verification of a
  threshold-cert proof at the anchor validator; batch-validity (zk-rollup-lite)
  remains research-grade (Poseidon-MPF circuit too large per ZeroJ status).

Partial gaps inside shipped items:

- [ ] E4.1: only API-key auth shipped; **mTLS / OIDC** via Quarkus security not
  wired.
- [ ] E4.3: the `SignerProvider` SPI + in-config default shipped; **actual
  KMS/HSM/Vault plugin jars** (PKCS#11, AWS KMS, Vault) not built.
- [ ] E5.1: metrics shipped; the **Grafana dashboard template** was not made.
- [ ] E3.2: Kafka bridge ships block JSON; the **optional per-record MPF proof
  attachment** was not implemented.
- [ ] E1.5: docker-compose + Gradle plugin template shipped; a **Maven
  archetype** was not made.

Follow-ups recorded in delivery notes:

- [ ] Split proving/disclosure helpers (`BbsCredentials`) into a slim
  **`yano-appchain-client-zk`** so clients don't pull the node-side plugin.
- [ ] **Fully anonymous transport (`authScheme=2`)** for E7.3: today the
  logical author is anonymous but the transport envelope still carries the
  relaying node's scheme-0 signature; promoting anonymity to the transport
  touches the core yaci auth path.
- [ ] VK / membership-root distribution moves from config into **chain-governed
  parameter blocks** when 005 D6 lands (per §7 resolutions).

Base-roadmap prerequisites (ADR-005 scope, tracked there, blocking items above):

- [ ] **S2 rotating sequencer** (liveness beyond the fixed proposer).
- [ ] **Chain-governed membership (005 D6)** — supersedes E4.5, unblocks
  governed VK distribution.
- [ ] **Script anchors (005 D2/D4)** — prerequisite for E7.5.

### Wave 1 delivery notes (2026-07-08)

- **E5.2 multi-chain**: `AppChainManager` hosts one subsystem per chain behind
  ONE shared gossip + ONE catch-up server agent per inbound session (protocol
  ids are shared — per-chain agents would collide in the mux); dispatch by
  envelope chain-id, per-chain auth via the owning subsystem. Config:
  `yano.app-chain.chains[i].<suffix>` (auto-enables) or the flat keys for a
  single chain. `Yano.appChains()`/`AppChainGateways` registry; REST gains
  `/app-chain/chains/{chainId}/...` with legacy paths intact for single-chain.
- **E2.1/E2.2 stdlib** (`appchain-stdlib`, in the default distribution,
  selected by machine id): `kv-registry` (first-writer ownership, provable
  `[owner, value]` entries) and `approvals` (k-of-n, dedup'd approvers,
  single-reject, deterministic block-timestamp deadlines). CBOR command
  formats + client-side encoders documented in the classes.
- **E3.1 push**: `AppChainGateway.subscribeFinalized`; SSE
  `/stream?fromHeight&topic` (replay → live with ledger gap backfill,
  heartbeats, virtual threads); `WebhookStreamSink` with per-sink cursor in
  the ledger meta CF (ordered, at-least-once, halts on failure, resumes).
- **E4.1 auth**: opt-in `X-API-Key` filter for `/app-chain/*`;
  `key=topicA|topicB` entries restrict submissions per key (body-sniffing
  in the filter, entity stream restored). QuarkusTest-covered.
- **E5.1 metrics**: Micrometer via `quarkus-micrometer-registry-prometheus`
  (`/q/metrics`): per-chain tip/pool/peers gauges, finalized blocks/messages
  counters, block-interval timer.
- **E1.2 testkit** (`appchain-testkit`): `@AppChainCluster(nodes=N)` JUnit 5
  extension — generated keys, full-mesh sockets, temp ledgers, injected
  `AppChainClusterHandle` with await helpers.
- **E1.1 client SDK** (`appchain-client`, dependency-light): typed
  REST + reconnecting SSE + `ProofVerifier` for client-side MPF verification
  against (anchored) roots — fails closed on tampering.
- *Learning*: `AppChainConfig` grew a fluent `Builder` after three rounds of
  constructor churn; new fields go through the builder from now on.
- **Post-review hardening (Wave 1)** (high-effort multi-agent review, 9 confirmed
  defects fixed before merge): auth topic-ACL now scopes by matched
  `ResourceInfo` (not URL substring — closes trailing-slash/matrix-param
  bypass); the shared multi-chain inbound agent re-enforces each chain's own
  size/TTL in `verifyByChain` (union limits were too permissive); SSE JSON is
  built with Jackson (unescaped topic no longer wedges subscribers); explicit
  `enabled=false` is honored over `chains[i]` presence (`Optional<Boolean>`);
  `stop()` clears webhook sinks bound to the closing ledger; the SDK dedups on
  SSE reconnect by `(height,index)`; `AppChainManager.start()` rolls back
  already-started chains on partial failure; disabled/ambiguous REST responses
  keep the `{"error":...}` JSON body; the client SDK dropped its dependency on
  the MPF library's internal `TestNodeStore`.

### Wave 2 delivery notes (2026-07-08)

- **E2.3/E2.4 stdlib**: `balances` (mint/transfer, a member spends only its own
  account, non-negativity enforced in `apply()` as a deterministic no-op, every
  balance provable) and `doc-trail` (append-only per-entity trails; a chained
  head `blake2b(prevHead ‖ entryHash ‖ author)` proves the whole ordered trail
  against the anchored root, with a `computeHead` verifier). Registered via the
  ServiceLoader.
- **E1.3 typed codec**: `MessageCodec<T>` SPI + `TypedAppStateMachine<T>` base +
  `JacksonCborCodec` default (core-api); the client SDK's `submitTyped`/
  `subscribeTyped` take encode/decode functions (keeps the SDK decoupled from
  core-api) with a wire-compatible `CborCodec`. Framework stays blob-first.
- **E3.4 audit export**: `EvidenceBundle`/`EvidenceVerifier`/`EvidenceBundleCodec`
  — portable proof material (block(s) + bundle-claimed members + L1 anchor ref).
  The verifier recomputes block hashes and messages-roots, verifies certificates
  against an independently pinned chain/member/threshold context, confirms
  message-id inclusion, and checks the prev-hash chain to the claimed anchor.
  Completing the L1 proof additionally requires fetching the transaction output
  and matching its exact metadata payload or script state-thread inline datum.
  The portable segment is capped at 4,096 blocks and at one configured
  block-byte budget (never above the framework 16 MiB maximum); if the anchor
  segment exceeds either bound, the export remains a one-block finalized
  proof without an anchor reference. This bounds CBOR plus its hex/JSON
  expansion. State-value MPF proofs remain a separate endpoint/artifact.
  `AnchorService` persists its confirmed-anchor record. REST
  `/evidence/{messageId}`.
- **E4.4 retention**: `pruneBodiesBelow` strips message bodies below the
  L1_FINAL anchor while keeping headers, ids, roots and certs. Evidence still
  proves the retained message-id inclusion, but no longer authenticates the
  original body/envelope content (with encrypted bodies, crypto-shredding).
  Companion to E5.3 for onboarding past the prune horizon.
- **E4.2 encrypted bodies**: `BodyCipher` (core-api) + `GroupCipher` (SDK),
  identical AES-256-GCM wire format with the topic as AAD; chain carries
  ciphertext, never the plaintext or key. Encrypt/decrypt at the edges.
- **E4.3 SignerProvider**: sign-only SPI + ServiceLoader factory; a bare hex
  seed uses the default in-config signer, a `scheme:reference` spec routes to a
  KMS/HSM/Vault plugin (the node never holds the key). Cloud backends are
  intended plugins; none ship in the distro.
- **E3.2 sinks**: `FinalizedStreamSink` SPI + `SinkRunner` (ordering + persisted
  cursor + at-least-once); webhook is now a sink; `appchain-kafka-sink` T3
  plugin produces blocks keyed by height (partition-stable), sync-acked. Config
  `yano.app-chain.sinks.<scheme>.*`.
- **E5.3 snapshot**: RocksDB Checkpoint export for fast member onboarding
  (restore = drop the dir, no replay), `verifyIntegrity()` on start, REST
  `POST /snapshot`.
- **E1.5 scaffolds**: docker-compose 3-node cluster + a custom-state-machine
  plugin Gradle template.
- *Learning*: the SDK's zero-coupling-to-core-api rule shaped E1.3 (functions,
  not the `MessageCodec` type) and E4.2 (a duplicated `GroupCipher` with a
  shared wire format) — worth keeping as the SDK grows.
- **Post-review hardening (Wave 2)** — high-effort multi-agent review, 9
  confirmed defects fixed before merge: `EvidenceVerifier` now enforces the
  chain's m-of-n threshold (was accepting a single member signature —
  forgeable evidence); retention never prunes ahead of the slowest sink cursor
  (was stripping bodies a lagging sink would later deliver); `SinkRunner`
  migrates a sink's legacy cursor key on upgrade (was orphaning webhook
  progress); anchor-record meta is cleared on L1 rollback (was emitting an
  anchor ref that failed offline verification); sink delivery runs on its own
  executor and the Kafka producer bounds send/ack (a broker outage was stalling
  proposeTick/catch-up/anchor on the single scheduler thread); node-app forwards
  the dynamic `yano.app-chain.sinks.*` config (the Kafka bridge was dead via the
  normal deployment path); the evidence bundle caps its anchor chain length
  (old messages under a far anchor could OOM); webhook HTTP non-2xx surfaces as
  `lastError`; `status()` keeps a back-compat `webhooks` map.

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
| E3.4 | **Signed audit export**: portable evidence material per record or range — block header, finality cert, message-id inclusion, and anchor tx reference — as JSON/CBOR verified against an independently trusted chain profile. MPF state proofs and the Cardano output/datum lookup are separate verification inputs. | T1 | S–M |

### E4 — Enterprise security & compliance

| # | Enhancement | Tier | Effort |
|---|---|---|---|
| E4.1 | **REST authn/authz**: API-key/mTLS/OIDC via standard Quarkus security for `/app-chain/*`, per-topic submit permissions | T1 (config) | S–M |
| E4.2 | **Encrypted bodies**: optional envelope encryption of message bodies with a group key (config/KMS-provided); the chain remains blob-first — ordering, proofs and anchors work unchanged over ciphertext; decryption is client/state-machine side. Per-topic keys enable need-to-know inside one chain | T2 + T4 | M |
| E4.3 | **KMS/HSM signing** via `SignerProvider` plugins (PKCS#11, AWS KMS, Vault) for member and anchor keys — removes raw seeds from config files, usually the first enterprise security review finding | T3 | M |
| E4.4 | **Retention & pruning with proof preservation**: prune message bodies below the last `L1_FINAL` anchor while keeping headers, roots and the message index — anchored message-id inclusion stays verifiable, while the original body is explicitly no longer content-verified; combined with E4.2, "crypto-shredding" (destroy the key) erases content while preserving the commitment | T1 | M |
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

## 7. Open questions — resolved (2026-07-08, pre-Wave-3)

1. **stdlib distribution → T2 (confirmed, already shipped).** `yano-appchain-stdlib`
   ships in the default distribution, ServiceLoader-discovered, inert unless
   `yano.app-chain.state-machine` selects a machine. Pure Java (cbor +
   cardano-client-crypto), so it doesn't compromise the clean core.
   *Refinement:* ZK-flavored stdlib machines (E7.4 `private-balances`) do NOT go
   in the T2 jar — they carry heavy zeroj deps. Rule: **non-ZK stdlib = T2;
   ZK stdlib machines = T3 plugin.**
2. **E7.1 proof placement → body convention.** The `[circuitId, proof,
   publicInputs]` envelope lives inside the opaque body, parsed by the plugin's
   `ZkMessageValidator`; ship a CDDL for it in the plugin. Rationale: a
   first-class envelope field is a protocol change (yaci core, CDDL, all peers)
   and must not be forced by an experimental T3 feature; caps/metrics are done
   plugin-side; in-body proofs are already covered by the message-id hash and
   MPF/anchoring. The envelope's `authScheme`/`authProof` are for *authorization*
   proofs (E7.3) — E7.1 proves message *content*, which belongs with the payload.
   **Promotion trigger:** only promote to an optional envelope field if multiple
   plugins must share proof semantics, or the framework must gate admission on
   proof presence generically.
3. **E7.3 anonymity → no seq + per-nullifier dedup applied in `apply()`.**
   Anonymous senders (`authScheme=2`, MembershipVerifier over a member-key Merkle
   root) have no per-sender sequence. Dedup moves to nullifiers, and the
   authoritative consumed-nullifier set lives in the **MPF state** (committed,
   rollback-safe, agreed via re-execution) — admission-time checks are only an
   optimization. Nullifier **context is a per-topic policy knob**:
   `hash(chainId, topic, epoch)` = one action per member per epoch (voting,
   sealed bids); per-message tag = many anonymous messages, replay-only. Envelope:
   `authScheme=2`, `authProof` = membership proof + nullifier + public inputs,
   `sender` blinded/empty, message-id hashing unchanged. Mixed-mode per topic via
   `auth-mode`. Membership root static in config now; D6-governed later with a
   grace window.
4. **VK distribution → config now (hash-pinned), governed later.** Pin
   `circuitId → VK hash` in config; load the VK file; verify loaded-hash ==
   pinned-hash at startup, fail-closed (VK mismatch is a config error, not a
   runtime divergence). Decouple the agreed hash (small → config/governance) from
   the VK bytes (file/URL, verified against the hash). Migrates unchanged into
   the D6 governed parameter block.
5. **BBS issuer → any configured issuer key set (decoupled from membership).**
   Issuers (HR, bank, certifier) are credential trust anchors, not consensus
   participants, and often aren't chain members. Config: `issuers = { id → BBS
   public key }` independent of the member set; records reference an issuer key;
   disclosure verified client-side. Issuer-is-member is just a coinciding key.
6. **Naming → one node plugin `yano-appchain-zk`** (E7.1 verify + E7.3
   membership/nullifier + E7.2 BBS node-side) via `zeroj-bom-all`; experimental
   labels travel together. Proving lives client-side, so a companion
   **`yano-appchain-client-zk`** carries proving helpers. The meaningful split is
   node/verify vs client/prove — not verify vs BBS; split BBS out only if its
   maturity/footprint diverges (the BOM makes that cheap).

**Build order:** E7.1 → E7.2 → E7.3; E7.4/E7.5 as gated spikes. E7.1 is
verification-only, deterministic, lowest-risk, and exercises the VK-registry /
plugin seam that E7.3/E7.4 reuse.

### Wave 3 delivery notes (2026-07-08)

All ZK ships as ONE experimental T3 plugin `yano-appchain-zk` (ZeroJ
0.1.0-pre5), verification-only in consensus; proving is client-side.

- **Config-injection seam** (reused by all three): `AppStateMachineContext` +
  `AppStateMachineProvider.create(ctx)`; `AppChainConfig.sinkSettings`
  generalized to `pluginSettings` (carries `sinks.*` + `zk.*`), forwarded by
  node-app (`forwardDynamicKeys`) and RuntimeNode (union prefix collector,
  single + multi-chain).
- **E7.1 `zk-gate`**: in-body `ZkProofBody` `[circuitId, proofSystem, curve,
  proof, publicInputs]` (CDDL); `ConfigVkRegistry` pins `circuitId → VK hash`
  and loads fail-closed; `ZkVerificationService` over ZeroJ's
  `VerifierOrchestrator` (backends via ServiceLoader). Proofs are verified by
  **every member in `apply()`** (mandatory consensus enforcement — `validate()`
  is proposer-only); verified messages recorded via the shared `OrderedLog`.
- **E7.2 `credential-registry`**: `CredentialRegistryStateMachine` admits an
  issuer-BBS-signed `CredentialBody` only if the signature verifies against a
  configured issuer key (issuers decoupled from membership), records a
  provable commitment; `BbsCredentials` wraps `zeroj-bbs` for issue / selective
  disclosure / verify. No trusted setup — full round-trip is hermetically
  tested.
- **E7.3 `zk-membership`**: `ZkMembershipStateMachine` — membership proof
  instead of an identifiable signature, no per-sender seq; per-nullifier dedup
  applied deterministically in `apply()` against MPF state (rollback-safe);
  nullifier context is an app policy knob. `MembershipProofBody` + CDDL.
- *Scope carried forward*: E7.3's transport envelope still uses the relaying
  node's scheme-0 signature — the logical author is anonymous within the member
  set, but a fully anonymous transport `authScheme=2` touches the core auth path
  and is a follow-up. E7.4 (private-balances) / E7.5 (zk-anchor) remain gated
  spikes; `BbsCredentials` will split into a slim `yano-appchain-client-zk`.
- **Post-review hardening (Wave 3)** — high-effort review, 8 findings fixed
  before merge. The critical class: **`validate()` runs only on the proposer;
  followers re-run only `apply()`** — so a security check placed only in
  `validate()` is not consensus-enforced. All three machines now verify in
  `apply()`: credential-registry re-verifies the issuer BBS signature; zk-gate
  makes apply-time verification mandatory (dropped the optional flag);
  zk-membership binds the nullifier to a proof public input (blocking
  replay-with-fresh-nullifier). Config loops tolerate index gaps; the ordered-log
  record format is shared (`OrderedLog`); dead code removed.
