# Yano App Chain — Use Cases & Practical Examples

What you can build **today** with the app-chain framework, organized by how
much you need to bring:

- **Part A** — default distribution, out of the box (stock machines and
  composites, config only)
- **Part B** — default distribution + a custom state machine plugin jar
- **Part C** — Yano as a library (embed the runtime in your own application)

Each example lists the problem, why an app chain fits better than the usual
alternatives, a concrete setup sketch, and the verification story (what a
third party can prove without trusting one node). The current supported pilot
posture is a **trusted-member permissioned group** with registered keys. A
semi-trusted-member deployment additionally needs outcome co-attestation and
continuous independent auditing; see "What this is *not* for" at the end.

The common backbone every use case inherits:

| Property | Mechanism |
|---|---|
| Total order of records | Sequencer + hash-linked app blocks |
| Multi-party agreement | n-of-m Ed25519 finality certificates, verified by every member |
| Tamper evidence | Content-derived message ids, merkle message roots, MPF state root |
| Independent verifiability | MPF inclusion proofs against a state root **anchored to Cardano L1** |
| Neutral custody | Every member holds the full ledger; no single broker |
| L1 without extra infra | The node is its own Cardano gateway (submit + observe anchors) |

---

## Part A — Out of the box (default distribution)

Everything in this part is configuration only: `yano.jar`, member keys, REST,
and any selected first-party connector bundles. `ordered-log` keeps opaque
bodies; the standard library and `evidence-v1-gated` composite add typed state
and coordinated publication without a custom state-machine implementation.

### A1. Multi-party audit / compliance log

**Problem.** Two or more organizations (or departments) must keep a shared,
append-only record of events — approvals, access grants, config changes,
regulatory filings — such that no party can later deny, reorder, or rewrite
an entry.

**Why app chain.** A database owned by one party proves nothing to the other.
A blockchain L1 is overkill (cost, latency, data publicity). An `ordered-log`
chain between the parties gives co-signed ordering, and the periodic L1 anchor
gives regulator-grade, publicly verifiable timestamps for pennies.

**Setup sketch.** One node per organization; members = each org's key;
threshold = all (2-of-2, 3-of-3) for "everyone vouched for every entry", or
n-of-m for availability. Anchor every N blocks. Suggested body: JSON
`{"event":"access-granted","actor":"...","object":"...","ts":"..."}`.

**Verification.** Auditor takes any entry, asks any node for
`GET /proof/{messageId}`, fetches the anchor tx from Cardano, checks the MPF
proof against the anchored `state_root`. Neither org — nor Yano — is trusted.

### A2. Neutral consortium message queue ("Kafka with neutrality")

**Problem.** Banks/partners exchange settlement instructions or business
messages over a broker today (Kafka, MQ) — but the broker is operated by one
party, and message order/delivery disputes are unresolvable.

**Why app chain.** Same submission ergonomics (`POST /messages`, poll
`GET /messages`), but the "broker" is jointly operated: order and content are
finalized by threshold signature, both parties store everything, and disputes
reduce to a proof check.

**Setup sketch.** Node per participant (+ optionally one for an auditor as a
member whose key is in `members` but who never submits). Topics per flow:
`settlement`, `reconciliation`, `dispute`. Consumers poll
`GET /messages?topic=settlement` or subscribe to `AppMessageReceivedEvent` /
`AppBlockFinalizedEvent` via a small plugin.

**Verification.** "You never sent instruction X / you sent it after Y" is
settled by `finalizedAtHeight` + block contents + the anchor.

### A3. Supply-chain / Digital Product Passport (DPP) trail

**Problem.** Manufacturer, logistics and certifier must attach a growing,
non-repudiable event trail to products (origin, handling, certification), and
downstream buyers must be able to verify single entries cheaply.

**Why app chain.** Each step is a signed message from a known member.
Documents stay off-chain (S3/IPFS/internal); the chain records
`{productId, step, documentHash, actor}`. The envelope proves which chain member
submitted it, but durable manufacturer/shipper/device identity still requires
a domain-signed actor envelope and registry. Buyers verify one entry with
one MPF proof against a public anchor, without seeing the rest of the trail.

**Setup sketch.** Topic per product line or a `productId` field in the body;
anchor hourly. Keep bodies small (hashes + metadata, not documents).

### A4. Notarization / proof-of-existence service

**Problem.** Prove "this content existed by time T" — contracts, model
weights, datasets, research results — without publishing the content.

**Why app chain.** Submit `{"sha256":"...","label":"..."}`; the entry is
sequenced and the root anchored to Cardano. You get L1-grade timestamping at
app-chain throughput and cost, batched into one L1 tx per anchor interval.
A single-organization deployment works too: 2–3 nodes across departments/
regions, threshold 2 — protects against unilateral rewriting even inside one
company.

**Verification.** Reveal the content later; anyone recomputes the hash, takes
the proof, checks the anchor. The anchor tx's L1 block time bounds T.

### A5. Cross-organization SLA / integration evidence

**Problem.** Two systems integrate over APIs/webhooks and disagreements arise:
"we called you at 12:01 and you didn't respond", "you never sent the webhook".

**Why app chain.** Both sides log request/response digests
(`{direction, endpoint, payloadHash, status}`) to a shared chain as they
happen. The sequenced, co-signed log is the single source of truth for SLA
disputes, with per-sender sequence numbers exposing gaps.

### A6. Game / loyalty event feeds settled on Cardano

**Problem.** High-frequency application events (scores, points, achievements)
are too chatty for L1, but rewards must eventually settle on-chain credibly.

**Why app chain.** The operator's nodes (or operator + platform + guild as
members) finalize the event stream off-chain; the anchored roots make the
feed auditable. Settlement jobs read finalized blocks (`GET /blocks/{h}`)
and pay out on L1, able to cite a proof for every reward decision.

---

## Part B — Custom state machine as a plugin jar (still the default distribution)

Everything in Part A, plus your own **interpretation** of messages: the state
machine turns the log into typed, validated, queryable state — and every
key you write becomes individually provable. Deployment = drop a jar into
`plugins/` (see `docs/APP_CHAIN_TUTORIAL.md`, Part 2).

### B1. Replicated registry (the KV pattern)

**Problem.** A consortium needs one authoritative registry — token metadata,
DID documents, allow/deny lists, service endpoints, schema versions — that no
single member can edit unilaterally, and that external parties can query with
proofs.

**How.** State machine interprets `set:`/`del:` commands (tutorial example),
or richer CBOR commands with per-key ownership rules in `apply()` (e.g. only
the key's original creator may update it — sender identity is in the
envelope). Reads: `GET /proof/{keyHex}` returns value + proof in one call.

**This is the closest thing to "smart-contract state" the framework offers
today** — deterministic multi-party state transitions with provable results,
in plain Java.

### B2. Business ledgers with admission rules (orders, inventory, quotas)

**Problem.** Partners share a business ledger — purchase orders, inventory
movements, quota consumption — where entries must satisfy business rules
(no negative stock, quota not exceeded, valid state transitions).

**How.** `validate()` rejects malformed commands at admission; `apply()`
enforces stateful rules deterministically (read current state through the
writer, reject-by-ignoring or record a rejection entry). Because every member
re-executes `apply()`, a member cannot be fed a different ledger than its
peers — state roots would diverge and blocks would be rejected.

### B3. Micropayment / receipt netting (x402-style settlement chain)

**Problem.** High-frequency micro-receipts (API calls, content access, agent
payments à la x402) are individually too small for L1 fees, but the parties
need credible accounting and periodic on-chain settlement.

**How.** State machine tracks per-party balances from signed receipt
messages; every balance is a provable state key. A settlement job nets
balances every anchor interval and pays on L1, citing the anchored root the
balances came from. Script anchoring can enforce the monotonic threshold-signed
root chain, but production payment/reconciliation and any domain-specific
withdrawal validator still require dedicated hardening (ties into
`adr/x402/001` and app-layer `FX-002`).

### B4. Approval workflows / multi-party sign-off

**Problem.** Cross-org processes need k-of-n human/system approvals (release
gates, payment authorizations, credential issuance) with a non-repudiable
record of who approved what, in which order.

**How.** Messages are `propose`/`approve`/`reject` commands; `apply()`
tracks workflow state per item and flips it to `APPROVED` when the required
approver set is reached (approver identity = envelope sender, already
authenticated). The full decision trail is provable per item.

### B5. Member-attested oracle feeds

**Problem.** A consortium wants an agreed data feed (prices, weather, sports
results) where each member independently observes and the group publishes an
agreed value.

**How.** Each member's gateway submits its signed observation
(`observe:<round>:<value>`); `apply()` aggregates deterministically once all
(or a quorum of) member observations for a round are present — e.g. median —
and writes `round → agreedValue` as provable state. Note: fetch external data
*outside* the node (a small submitter script/service per member); `apply()`
itself must stay deterministic and I/O-free.

---

## Part C — Yano as a library

Yano is a set of published artifacts (`yano-core-api`, `yano-runtime`, ...);
the Quarkus app is just one packaging. Library mode is for teams building
**their own node/product** around the app chain.

### C1. Embedded "chain-backed" enterprise service

**Problem.** You want your existing Java service (Spring/Quarkus/plain) to
*be* a chain member — submit and consume app-chain records in-process, expose
your own domain REST/gRPC, keep your own database — without operating a
separate node process.

**How.** Depend on `yano-runtime`, assemble a node via `YanoAssembly` (client
or relay role), construct `AppChainSubsystem` with your `AppStateMachine`
instance directly (no ServiceLoader needed in library mode), and register it
in the kernel. Your service calls `subsystem.submit(...)` and subscribes to
`AppBlockFinalizedEvent` on the shared EventBus to update its own read models
exactly once per finalized block — an event-sourcing backbone whose event log
happens to be multi-party and anchored.

**Fits.** Core banking adapters, ERP connectors, marketplace backends that
must share state with partners but want everything in one JVM and one
deployment unit.

### C2. L1-aware application logic (registries and mirrors derived from Cardano)

**Problem.** Your application state should *react to Cardano itself* —
track deposits to an address, mirror an on-chain registry, maintain an index
that partners agree on.

**How.** In library or plugin mode, use an `L1ObserverProvider` to turn stable
address-deposit, metadata-label, or domain observations into reserved app-chain
messages. With `l1.stability-depth > 0`, every member verifies each block's
`l1-ref` against its own canonical L1 view before co-signing, and replay derives
the same deterministic observation state.

**Caveat (important).** With `l1.stability-depth > 0`, followers now verify
each block's `l1-ref` against their own L1 view (ADR 008.1 I1.3) — a
fabricated or rolled-back reference is rejected fail-closed. The framework
The observer contract is intentionally narrower than arbitrary state-machine
reads over the live L1 database: deterministic `apply()` remains I/O-free.
Domain observers must validate the exact observed fact, and a script anchor
enforces only the anchor-chain contract—not arbitrary bridge withdrawals.
Treat this as trusted-member integration infrastructure, not an adversarial
bridge, unless a separately audited domain protocol supplies the missing
proofs, attestation policy, and on-chain enforcement.

### C3. Custom distributions / appliances

**Problem.** You're shipping a product: a "consortium ledger appliance", a
sector-specific node (energy trading, healthcare data exchange), or a SaaS
where each tenant group gets a chain.

**How.** Build your own launcher on `yano-runtime`: preconfigure roles,
bundle your state machines, add your REST surface, brand it. The kernel
`Subsystem` SPI lets you add sidecar subsystems (schedulers, exporters,
notification bridges) with the same lifecycle/health treatment as the
built-ins. The default Quarkus app is the reference implementation to copy.

### C4. Integration testing and CI for chain-based workflows

**Problem.** Teams building on the app chain need fast, deterministic tests —
no public networks, no fixtures drift.

**How.** In tests, spin up 2–3 `AppChainSubsystem` instances in one JVM with
in-memory/temp-dir ledgers and real sockets (see
`runtime/src/test/java/.../appchain/*IntegrationTest.java` for ready-made
patterns), drive scenarios (submit → finalize → proof → restart → catch-up),
and assert on state roots. Combined with the devnet toolkit you get full
L1 + app-chain e2e in CI (`test-app-chain-cluster` skill is the template).

### C5. Event-sourcing backbone with external replication targets

**Problem.** You like the app chain as the agreed source of truth but need
the data in Kafka/Postgres/Elastic for the rest of your stack.

**How.** A small library-mode process can subscribe to
`AppBlockFinalizedEvent`, or an operator can configure a first-party finalized
sink, and forward finalized messages to the surrounding infrastructure.
Delivery is ordered and cursor-tracked but remains at-least-once across
acknowledgement crashes; consumers deduplicate by chain/block/message identity.
The chain stays the neutral system of record; the analytics stack stays as-is.

---

## Choosing your entry point

| You need | Use |
|---|---|
| Shared tamper-evident log, provable records, minimal effort | **Part A** — config only |
| Typed/validated shared state, per-key proofs, business rules | **Part B** — plugin jar |
| Your own node/product, in-process integration, L1-reactive logic | **Part C** — library |

## What this is *not* (yet) for

Be honest with stakeholders about v1 boundaries (roadmap in ADR 005):

- **Trustless/public validator sets** — membership is a configured key list;
  there is no stake, slashing, or open participation.
- **General BFT view change** — fixed and deterministic L1-window rotating
  proposer modes are implemented, but there is no arbitrary view-change
  protocol when the scheduled proposer is unavailable.
- **Domain-enforced bridges/withdrawals** — metadata and threshold-co-signed
  script anchors are implemented, but the stock anchor validator does not
  validate a domain withdrawal or payment policy.
- **Semi-trusted effect outcomes** — receipts are member attestations. A
  k-of-n outcome policy and continuous independent auditor are required before
  treating executors or members as semi-trusted.
- **Large payloads** — 64 KB default body cap; store blobs elsewhere, chain
  the hashes.
- **Public data distribution** — the chain replicates to members only;
  publish proofs + roots, not the ledger, to outsiders.
- **Sub-second global finality guarantees** — finality needs a network
  round-trip to threshold members; tune `block.interval-ms` and threshold to
  your latency budget.

---

## Extension capabilities → use cases

The enterprise extensions (`docs/APP_CHAIN_USER_GUIDE.md` sections 8–17) turn
several Part B patterns into config-only Part A deployments — `kv-registry`
and `approvals` cover B1 and B4 without a plugin jar — and add capabilities
this document's parts don't cover:

| Capability | Use cases it unlocks |
|---|---|
| `balances` stdlib machine (guide §9) | B3 micropayment/receipt netting, loyalty points and internal-credit ledgers with zero custom code |
| `doc-trail` stdlib machine (guide §9) | A3 DPP/supply-chain trails: one provable chained head per product/case verifies the whole trail |
| `composite/evidence-v1-gated` (guide §18) | Approval-coordinated S3/IPFS publication followed by acknowledged Kafka notification, all under one root |
| Governed composite profiles (profile-governance runbook) | Deploy reviewed dormant component/profile generations first, then threshold-authorize deterministic activation at a future height |
| `credential-registry` (BBS, guide §17, experimental) | Verifiable credentials on an anchored registry: issuer-signed attribute sets, selective field disclosure |
| `zk-gate` (guide §17, experimental) | Private policy compliance: prove "amount ≤ limit" / "KYC holds" across orgs without revealing the data |
| `zk-membership` (guide §17, experimental) | Anonymous-but-authorized submissions: voting, sealed bids, whistleblowing among known members |
| Evidence export (guide §13) | A1/A5-style audits: one offline-verifiable JSON bundle per record for regulators and counterparties |
