# ADR-014 (Report): Yano App Chain — External Architecture, Readiness & Feasibility Review

## Status

Informational report — external review of the ADR-013 series and its implementation.
This document records findings; it does not itself make an architectural decision.

## Date

2026-07-17

## Author

Claude (Fable 5), acting as external architect/reviewer/auditor

## Scope and method

**Scope:** ADR-013 + sub-ADRs 013.1/013.2 (design), the appchain framework/runtime,
the three first-party connector bundles, the composite state machine, the effects
demo and deployment tooling, and the test/CI evidence — all reviewed on the working
tree of branch `feat/adr013-m1-p1-7-release-closure` (HEAD `e4fcf36`).

**Method:** firsthand read of the ADR trilogy and core docs, plus five independent
specialized audit passes (design review, framework/runtime code, connectors,
demo/operations, QA/CI evidence). Every framework module's unit suite was executed
during the audit and passed on the working tree.

## Related

- [ADR-013](013-first-party-integration-connectors-and-effect-demo.md)
- [ADR-013.1](013.1-effect-runtime-framework-closure.md)
- [ADR-013.2](013.2-deterministic-composite-state-machine.md)
- Prior external reports in this series: [007](007-yano-appchain-implementation-codex-report.md), [009](009-yano-appchain-support-codex-review.md)

---

## Executive verdict

**This is genuinely production-shaped engineering with a pre-release process
state.** The wire-contract discipline, determinism hygiene, adversarial test depth,
and operational tooling are well above what "demo milestone" implies — several
parts are exemplary. But the "all three milestones COMPLETE" status is ahead of
the repository: the entire release closure (CI gates, composite modules, both
sub-ADRs, the E2E suites) exists only as **uncommitted working-tree changes**, so
no acceptance gate has ever actually run in CI. Beyond that, one **major
consensus-path defect** (stdlib CBOR decode) and a handful of concentrated
design-level risks (single-executor trust/liveness, genesis-frozen composite
profiles, documentation-enforced safety invariants) separate the current state
from a production posture.

Readiness in one line: **ready today for trusted-membership consortium pilots on
devnet/private networks (after committing the tree and fixing the stdlib decode
issue); not yet ready for semi-trusted memberships, regulated-data workloads, or
Cardano value movement.**

---

## 1. ADR-013 series — design review

### 1.1 What's genuinely strong

- **Frozen byte-level contracts.** Canonical CBOR with fixed arrays (no maps →
  duplicate/unknown fields structurally impossible), explicit schema versions with
  definitive unknown-version rejection, iterative preflight bounds (512 items /
  8 depth) *before* any third-party decoder, BLAKE2b-256 domain-separated
  fingerprints, published CDDL + golden vectors in the artifact. This is spec
  discipline most production systems never reach.
- **Honest non-claims.** "Exactly-once incorporation with at-least-once external
  execution" is stated as the ceiling everywhere; receipts explicitly don't prove
  business truth; IPFS pin ≠ availability; no GDPR-erasure claim. The Kafka
  duplicate window after crash-before-CONFIRMED is documented rather than papered
  over.
- **Boundary correctness.** The deterministic-plane/execution-plane split (§4) is
  clean and consistently enforced; contracts relocation for drop-in bundles
  prevents parent-first classloader contamination; secrets are structurally
  excluded from payloads, receipts, metrics, and consensus state.
- **Staged delivery with recorded evidence** — milestones accepted independently,
  each with acceptance criteria and disclosed caveats (e.g., preview/preprod
  smokes not run; one flaky rotation test disclosed).

### 1.2 Design findings

1. **The single-executor model is both a forgery point and a liveness point.**
   All receipt fingerprints are computable from non-secret configuration, so a
   compromised *authorized* executor member can fabricate a perfectly well-formed
   `CONFIRMED` receipt without performing any external operation — the chain has
   no in-protocol cross-check (the demo's out-of-band verifier does, but the
   chain doesn't). Meanwhile ADR-013 §11.4 pre-authorizes exactly two result
   signers in an **immutable** chain policy; lose both keys and no pending
   `CHAIN` result can ever be incorporated again — every open effect expires and
   the chain can never execute effects again. Neither consequence is stated, and
   no recovery path exists or is deferred in §21.
2. **Genesis-frozen composite profiles (013.2 §7) make the deferred
   governed-profile ADR effectively mandatory.** Every component addition, quota
   rebalance, or unscheduled version replacement requires a new chain plus
   migration. Combined with permanent quota reservation for retired generations
   (013.2 §11), profile authors must forecast the chain's whole evolution at
   genesis. For a product pitched at long-lived evidence/DPP chains, this is the
   most consequential deferral — and it isn't listed in §21.
3. **Load-bearing invariants live in documentation, not the runtime.** The S3
   no-resurrection guarantee rests on an IAM/lifecycle deployment invariant the
   executor cannot verify; cluster-wide single-executor-per-type is convention
   (013.1 validates only node-locally); the
   `block.max-messages <= floor(effects.max-per-block / 2)` **consensus-liveness**
   bound is enforced by the demo launcher because the state-machine context can't
   see it. Each is disclosed individually; collectively, ordinary operator error
   degrades headline safety properties silently.
4. **The stock `evidence-v1` preset's flagship guarantee has a designed bypass.**
   The direct `evidence.command.v1` topic allows publication without the
   registry/approval prerequisites the release workflow showcases (013.2 §13,
   honestly disclosed). Adopters copying the demo will believe approvals are
   enforced when they are not. A preset variant that closes the direct topic
   should exist.
5. **Freeze-before-implement isn't evidenced by the record.** 013.1 and 013.2 are
   dated, implemented, and verified on the same day (2026-07-17), and
   "independent reviews" are asserted throughout with no reviewer identity or
   findings ledger. The evidence is self-reported narrative in the ADRs; an
   external auditor cannot trace claims without repository archaeology.
   Extracting the frozen v1 wire contract into a versioned spec document (the
   ADR itself admits conformance = CDDL + prose + vectors + negative tests
   jointly, §5.1) would fix both auditability and the spec-sprawl risk.
6. **Smaller items:** dual continuation semantics (`evidence.notify` + direct
   emission) retained forever doubles the replay test surface — the pre-release
   argument used to justify the Kafka rename would equally have justified
   shipping only the emitter path; three canonical-encoding/hash regimes coexist
   (CBOR+BLAKE2b for connectors, colon-domain BLAKE2b for evidence ids, bespoke
   binary+SHA-256 for composite profiles) with no stated rationale;
   detail-archive replication/DR, total-signer-loss recovery, and
   expiry-vs-outage sizing guidance are unspecified.

---

## 2. Implementation review

### 2.1 Framework & runtime — production-shaped, one major defect

Conformance to 013.1/013.2 is **confirmed against code** essentially everywhere
it was claimed: declarative ownership validated before runtime publication with
deterministic duplicate rejection (`EffectRuntime.java:507-545`), single
block-scoped emitter ordinal across results → expiries → apply
(`FxKernel.java:122-175`), height-gated activation with no wall-clock,
observability served from a sampled cache that never touches a plugin on scrape
(frozen bounds match 013.1 §5.3), composite namespace/routing/quota/
result-ownership invariants all enforced at construction and covered by genuinely
adversarial tests.

The one major finding:

- **C1 (major): stdlib machines decode member-supplied CBOR with an unbounded
  recursive decoder** (`ApprovalsStateMachine.java:330`,
  `KvRegistryStateMachine.java:94`, `DocTrailStateMachine.java:55`,
  `BalancesStateMachine.java:78`) and catch only `Exception`. A deeply nested
  body within the message size limit can throw `StackOverflowError` during
  `apply()` on **every node**, deterministically stalling the chain. The codebase
  itself treats this as a real threat — `FxKernel` caps `~fx/result` bodies at
  512 B explicitly to prevent this, and the contracts library has an iterative
  preflight — but the stdlib (and therefore the stock composite preset, which
  routes registry/approvals/doc-trail through these machines) lacks the same
  defense. Cheap fix: reuse `CanonicalCbor`'s preflight or add depth/size checks.
  **Fix before any deployment where a member key could be hostile or
  compromised.**

Lesser items: `appchain-composite` publishes with dependencies on
`appchain/examples/*` modules (support-status confusion, C3);
`AppChainSubsystem` (4,773 lines) and `EffectRuntime` (2,187 lines) are god
classes (C4); composite re-parses `effects.max-per-block` with its own default
of 256 — drift risk (C5); a test-only fault seam ships in production sources
(well-guarded, but excludable, C7); composite genesis-only marker adoption means
switching an existing chain's machine to `composite` stalls it at the next block
rather than failing at startup (C2).

### 2.2 Connectors — S3 and IPFS production-shaped; Kafka executor yes, sink no

Contract conformance is confirmed line-by-line across all three connectors:
bounds, canonical decode, reserved-header protection, probe-before-mutate,
conditional create with `If-None-Match: *`, `Submitted(receipt)` + archival-only
retry that never re-mutates, ≤128-byte receipts with correct fingerprint domains
matching golden vectors, §5.6-only error codes with aggressive vendor-text
redaction, fenced lifecycle with bounded close, non-consuming `Retry(100ms)`
single-flight per target. No secret leakage found anywhere. Five defects:

| ID | Severity | Finding |
|---|---|---|
| D1 | **Medium (correctness)** | S3 exact-key version-history probe uses `prefix(exactKey)` and counts sibling keys (`evidence/doc-1x` vs `evidence/doc-1`) toward its 64-entry cap; ≥64 prefix-sharing versions → definitive `POLICY_DENIED` for a **valid** effect, permanently failed on-chain (`AwsS3ObjectStoreClient.java:109-147`). Fix: early-exit on first non-matching key; classify exhaustion retryable. |
| D4 | **Medium (posture)** | The finalized Kafka **sink** has no TLS/SASL support at all — arbitrary bootstrap servers, plaintext only — while the same bundle's executor enforces fail-closed TLS profiles (`KafkaSinkFactory.java:36-100`). |
| D3 | Low | Kafka local-demo accepts any dotless hostname / `*.local` / `*.internal` (`KafkaEffectConfig.java:418-451`) — laxer than S3/IPFS's numeric-literal-only rule; `.internal` acceptance is SSRF-adjacent. |
| D2 | Low | Unversioned-PUT provider drift classified as definitive `DESTINATION_CONFLICT` instead of park-for-operator `TARGET_CHANGED`. |
| D5 | Low | IPFS detail document embeds observed pin state → same success can yield different `detailHash` after out-of-band pin upgrade, contradicting §5.4 byte-stability. |

`appchain-effects-cardano` is correctly quarantined pre-ADR-010.2 (free-text
errors, hot in-process wallet, documented duplicate window) — demo-shaped, and
rightly so. `appchain-zk` is a deterministic-plane experiment, not a connector;
appropriately labeled experimental.

Connector test gaps, ranked: (1) no test populates >64 versions under
prefix-sharing sibling keys (would have caught D1); (2) no node-crash test in
the documented Kafka duplicate window; (3) TLS/SASL and bearer-tls paths are
config-parser-tested only — all real-service runs use plaintext local-demo
profiles; (4) no disk-full test for `DETAIL_ARCHIVE_FAILED`; (5) no
property-based fuzzing of `CanonicalCbor`/`KuboJsonResponseParser` (strong
handcrafted negative suites exist).

### 2.3 Demo & deployment — the claims are real

- **"No-code demo" is real**: `./demo.sh up` + `./demo.sh run` produces a
  19-check report where MPF proofs are re-verified client-side, finality is
  ed25519-verified against launcher-pinned keys, the Kafka record is consumed
  back at the acknowledged offset, S3/IPFS state is independently re-read, and
  the Cardano anchor datum is decoded and matched on all three members. Success
  is never inferred from HTTP 2xx.
- **Host deployment is real, not aspirational** — own template pair, a
  2,872-line cluster launcher, paranoid process supervision (PID + kernel
  start-token + argv binding), and a CI job that runs the identical scenario
  through actual host processes, feeding a fail-closed acceptance gate.
- **C6/C7 exceed the ADR's letter**: byte-exact identity markers covering
  genesis fingerprints, membership, connector targets and anchor policy;
  journalled crash-resumable retirement with quarantine-rename-then-delete;
  public-L1 deletion double-gated. The MinIO → RustFS migration replaced static
  committed policies with a generated, per-instance, secret-root IAM spec bound
  into the identity marker — an improvement, not a regression.
- Hazards: the anchor leg's "independence" is the cluster's own L1 view (a
  collusive cluster could fabricate anchor evidence — recommend an optional
  external L1 source for public profiles); `require-anchor=false` yields an
  ambiguous unqualified PASS; `stateProofsVerified` is a synthetic constant
  (`EvidenceScenario.java:298`); the §0 "retries visible" promise is only met in
  the node UI, not the evidence report; unauthenticated demo endpoints (node API
  auth off, plaintext Kafka, open Kubo RPC) are documented but are exactly the
  patterns that get copied to production.

### 2.4 Verification & CI — strong substance, incomplete process

~880 JUnit test methods across appchain + runtime appchain code; real-service
(not testcontainers, not mocks) connector fault matrices with
outage/restart/reconcile phases; a genuine three-node fenced-failover E2E with
duplicate-delivery and retained-restart assertions; golden vectors with dual
Java+CDDL validation; native-image packaging smoke per PR; a
`milestone-1-release-acceptance` job that refuses success unless all four
pillars pass. Two smoke runs during this audit passed cleanly.

But: **236 dirty / 111 untracked files.** The CI workflow, `appchain-composite*`
(all of Milestone 3), both sub-ADR files, the failover/parity/fault-matrix
suites, and the MinIO→RustFS migration are not committed, and `feat/*` pushes
don't trigger the workflow anyway. Additionally: the four Phase-1.6 cluster
durability suites (`app/appchain-cluster/test-*.sh`) run nowhere automatically;
CDDL cross-validation needs a manual external `cddl-cli` run; deployment-parity
CI covers one of ≥4 matrix cells; Julc source-compile conformance stays manual
until julc > 0.1.0-pre14.

---

## 3. Readiness assessment

| Deployment tier | Verdict | Gating items |
|---|---|---|
| Devnet evaluation / demo | **Ready** | None — this is the best-supported path in the codebase. |
| Private consortium, fully trusted members, non-regulated data | **Ready after short list** | Commit the tree + one green CI run of the acceptance gate; fix stdlib decode (C1); fix S3 D1; decide sink-TLS stance (D4). |
| Consortium with semi-trusted/byzantine-capable members | **Not yet** | C1 is disqualifying until fixed; receipt-forgery trust model needs at least documented compensating controls (external verification like the demo runner's, run continuously); executor HA is a manual pager runbook. |
| Regulated / erasure-regime data | **Not yet** | No sanctioned deletion path anywhere (immutable objects, no unpin, WORM, permanent on-chain digests); encrypt-before-staging is not a mandated profile; detail archive is single-node with no DR story. |
| Public networks (preview/preprod) | **Opt-in, honestly labeled** | Guards verified in code; live public smokes explicitly not yet run. |
| Mainnet / value movement | **Blocked by design** | ADR-010.2 (production Cardano tx preparation) doesn't exist yet; `cardano.payment` correctly quarantined; mainnet anchoring structurally disabled. |
| Performance envelope | **Unvalidated** | No load/soak evidence anywhere in scope; the overview doc itself lists this as pre-production work. |

---

## 4. Independent feasibility study — what can be built on this?

Grounded in the actual stock surface: five config-selectable machines
(`ordered-log`, `kv-registry`, `approvals`, `balances`, `doc-trail`), the
`composite/evidence-v1` preset, five effect types (`kafka.publish`,
`object.put`, `ipfs.pin`, `webhook.post`, `cardano.payment`-preview), the
finalized Kafka sink, MPF state/effect proofs, threshold finality, and Plutus V3
script anchoring.

### 4.1 Tier 1 — out of the box (configuration only, no Java)

| Use case | Fit | Notes |
|---|---|---|
| Consortium audit/event log with independently verifiable proofs and periodic Cardano anchoring | **Strong** | `ordered-log` + proofs + anchor. The single best-supported non-demo use case; the client SDK verifies proofs client-side. |
| Shared registry / allow-list across organizations | **Strong** | `kv-registry`. Domain validation is whatever members submit — add a plugin when "valid entry" has rules. |
| Multi-party k-of-n approvals with audit trail | **Strong** | `approvals`; payment emission exists but should stay off pre-ADR-010.2. |
| Document/case chain-of-custody heads | **Strong** | `doc-trail` commits append-only entity heads. |
| Compliance-evidence publication (S3 + IPFS + Kafka ack, proof + anchor) | **Good, with a caveat** | `composite/evidence-v1`. The approval workflow is opt-in orchestration — the direct topic bypasses it. Fine for "evidence with audit trail"; not sufficient alone for "approval-gated publication." |
| Internal credits/netting between members | **Adequate** | `balances` is deterministic and rollback-safe, but settlement to Cardano is blocked on ADR-010.2 — treat it as a book-entry ledger only. |

Structural constraints applying to all of Tier 1: closed, identified membership
(this is a permissioned consortium platform, not a public chain); one active
executor member with manual fenced failover; per-network data isolation is
excellent but topology in the packaged scenario tooling is pinned to 3
members/threshold 2 (the platform itself is not).

### 4.2 Tier 2 — out of the box plus connector configuration (no Java, real ops work)

- **Enterprise bus integration**: finalized Kafka sink for bulk
  projection/indexing/replay + `kafka.publish` for individually acknowledged
  business notifications — the sink/effect split is well designed and correctly
  documented (dedupe by `(chainId, height)` vs effect id). Downstream consumers
  must dedupe by effect id; budget for the plaintext-sink limitation (D4) if the
  broker isn't network-isolated.
- **WORM/regulated archival with on-chain commitments**: `object.put` against
  versioned, Object-Lock-enabled S3 — the strongest connector; the IAM
  deployment invariant (create-only writer, no delete, no lifecycle rules) must
  be provisioned and *kept* true, because the runtime cannot detect drift.
- **Public content distribution**: `ipfs.pin` for known CIDs (pin-only; no
  add/unpin). Never for confidential plaintext; note the operator hosts whatever
  authorized members commit — a content-policy question the design doesn't
  answer.
- **Webhook-driven side effects** into existing systems (`webhook.post`).
- Operational prerequisites this tier inherits: expiry sizing vs realistic
  outage windows (a too-short `storage-expiry-blocks` converts an S3 maintenance
  window into terminal business failures requiring owner republish),
  detail-archive provisioning/monitoring/DR, and the executor failover runbook.

### 4.3 Tier 3 — with plugins (Java, using the SPIs as designed)

The plugin path is real and well-paved: manifested bundles, relocation packaging
discipline enforced by build gates, a conformance testkit
(`EffectExecutorConformance`), scaffolds, native build-time inclusion, and the
composite library for assembling custom profiles from stock components.

- **Digital Product Passport** — the explicitly designed-for consumer: reuse
  registry/approvals/doc-trail/evidence components in a custom composite profile
  that **closes the direct evidence topic**, add actor/credential models and
  product schemas. The foundation genuinely fits; profile immutability means the
  DPP schema roadmap must be forecast at genesis or planned as chain migrations
  until a governed-profile ADR exists.
- **Oracle observation ledger** (ADR-012 shape) — ordered observations +
  approvals/aggregation + proofs today; hardened Cardano datum publication waits
  on ADR-010.2.
- **Supply-chain traceability, trade-finance document workflows, lab/clinical
  data integrity, insurance claims evidence** — all are variations of registry +
  trail + evidence + connectors with domain validation plugins; the platform's
  honest claim ("proves who finalized what and what publication was authorized,
  not that the physical-world claim is true") is exactly right for these.
- **Custom effect executors** for other external systems (ERP, e-signature,
  ticketing): the connector contract is an excellent template, but expect to
  hand-copy ~350 lines of lifecycle/config boilerplate per connector — a shared
  connector-support module inside the contracts artifact is worth building
  before a fourth connector.
- **Custom composite profiles** combining stdlib components with domain
  components — the isolation/quota/result-ownership machinery is solid and
  adversarially tested.

### 4.4 Not feasible today (and honest about why)

1. **Any production Cardano value movement** — payments, native-asset
   settlement, oracle datum publication to mainnet. Blocked on unwritten
   ADR-010.2; correctly guarded everywhere.
2. **Public/permissionless participation** — membership, finality, and
   result-signer policy are all closed-set by design.
3. **Erasure-regime personal data** (GDPR Art. 17) — no deletion path exists by
   design; until an encrypt-before-staging profile is mandated, keep personal
   data off this platform.
4. **Chains needing runtime evolution** — component add/upgrade/quota change
   after genesis means a new chain today.
5. **Exactly-once external delivery** — at-least-once with consumer dedupe is
   the ceiling; systems that can't tolerate duplicates need the deferred
   Kafka-transactions design.
6. **Throughput-sensitive workloads** — not disproven, just unvalidated; no load
   evidence exists.

---

## 5. Priority recommendations

1. **Commit the release closure and open the PR** so
   `milestone-1-release-acceptance` runs once for real — until then, every
   "COMPLETE" claim is unverifiable and a lost working tree loses the release.
2. **Fix stdlib CBOR decode (C1)** — cheap, and it's the one consensus-path hole
   in an otherwise disciplined byzantine-input story.
3. **Fix S3 D1** (prefix-sibling version listing → false definitive failure)
   before use against busy archives; decide the sink-TLS stance (D4).
4. **Ship an `evidence-v1-gated` preset variant** that omits the direct topic,
   so the approval workflow the demo showcases can actually be enforced by
   configuration.
5. **Write down the two unwritten failure modes**: total result-signer loss (and
   whether a chain-migration escape hatch exists) and detail-archive
   DR/replication. Add both to ADR-013 §21 so they're tracked.
6. **Schedule the governed-profile ADR** — it's the difference between
   "composite chains are viable long-term products" and "composite chains are
   disposable."
7. Wire the cluster durability suites and CDDL validation into a pipeline (or
   record them as accepted manual gates); consider an external L1 source option
   for anchor verification on public profiles.
8. Longer-term: extract the frozen v1 wire contract into a standalone versioned
   spec; converge on one canonical encoding/hash regime for future contracts;
   name reviewers and link findings ledgers in acceptance evidence.

## Overall assessment

As an external reviewer I'd characterize this as a rigorously engineered
permissioned-consortium platform whose documentation is more honest about its
limits than almost any comparable project — and whose main risks are exactly the
ones its own ADRs flag but defer: executor trust/liveness, profile
upgradeability, and invariants that live in prose. The distance to a credible v1
production posture is short and well-defined; it's process closure and a handful
of targeted fixes, not architectural rework.

---

## 6. Reviewer response after repository verification (2026-07-17)

This section preserves the external reviewer's follow-up after the maintainers
provided repository evidence. It updates the review record without rewriting
the original findings above.

**Authorship:** external Claude reviewer; the maintainers only condensed the
response into the status table below.

| Original finding | Reviewer update after verification | Remaining concern |
|---|---|---|
| Executor outcomes were described as trusted without stating the trust conclusion | **Documentation claim conceded.** ADR-010 explicitly states that a result is a member attestation, not an independently verifiable external observation, and names threshold co-attestation as a future option. | The combined limitation remains: one immutable result-signer policy plus no continuous independent outcome auditor does not support a semi-trusted-member deployment tier. |
| Kafka crash-after-ack duplicate coverage was missing | **Withdrawn.** `effect-failover-e2e.sh` exercises the post-ack crash boundary and proves two physical records bind to one logical effect id. | None for this finding. The acknowledged delivery contract remains at-least-once by design. |
| ADR-013 release closure was uncommitted | **Withdrawn.** The closure commit and merge are present. | The acceptance workflow still needs one green manual `scope=all` run, and another run on the final retargeted PR. |
| Composite retained-state attachment check was absent | **Downgraded.** Startup now rejects non-empty retained state without the expected profile marker. | A nonzero-height ledger whose state root is exactly the empty root remains a narrow edge case. |
| Broad Kafka `local-demo` hostname allowance was a P2 payload-security issue | **Downgraded to P3.** The values come from privileged operator configuration, not an untrusted effect payload. | Tighten the rule opportunistically in the Kafka sink security change because the implementation cost is small. |

The reviewer endorsed the maintainer remediation order and added the execution
constraints incorporated below.

## 7. Final maintainer disposition and execution plan (2026-07-17)

The review does not justify an effect-system or plugin re-architecture. The
accepted response is a bounded correctness and release-readiness pass, followed
by the already-proposed governed-profile work.

### 7.1 P0 — close before the next release-acceptance run

#### P0.1 — deterministic app-chain CBOR preflight

Add one dependency-free, iterative raw-byte scanner in `core-api` and invoke it
before `CborSerializationUtil` at every untrusted app-chain CBOR boundary. The
minimum audited set is app-block/consensus wire decoding, effect results,
governed membership, script-anchor messages, all stdlib command decoders, the
`kv-registry` nested CBOR value, and the first-party ZK proof/credential
machines. This broadens the original stdlib finding only where the repository
uses the same vulnerable decoding pattern; it does not include trusted internal
persistence codecs without an untrusted or corrupt-state path.

The scanner must bound bytes, nesting depth, items, container/string lengths,
and both definite and indefinite CBOR forms without constructing a CBOR object
tree. Callers use immutable contract-specific bounds, not node-local settings;
consensus-path constants and their boundary vectors are frozen beside the
owning wire/command contract. Protocol-level rejection drops/rejects the
malformed message in its existing controlled path. State-machine rejection is
an ordinary deterministic invalid-command outcome: admission rejects it and
replay/apply treats it as the existing deterministic no-op. No scanner
exception or `Error` may escape and abort a block batch or networking loop.

Deep nesting, indefinite containers, oversize lengths, malformed breaks,
trailing data, validate/apply parity, wire rejection, replay, composite
adapters, ZK adapters, and identical state-root tests are required. A call-site
inventory must classify every app-chain use of `deserializeOne`/`deserialize`
as guarded, trusted persisted bytes, or separately bounded by an equivalent
decoder.

#### P0.2 — typed and committed consensus limits

Before code, freeze this framework-wide root/identity contract in a focused
app-layer ADR (proposed ADR-016, authenticated app-chain consensus profile and
typed runtime limits). ADR-014 is an informational review/disposition and
ADR-015 governs composite-profile epochs; neither should silently become the
normative owner of a general chain-profile wire/state contract.

Introduce an immutable public value such as `AppChainConsensusLimits` and add a
source/binary-compatible default accessor to `AppStateMachineContext`. This
uses the default-method compatibility technique already used by other public
SPIs; `effectTypes()` is an analogous precedent on `AppEffectExecutor`, not a
method currently present on the context itself.

The runtime supplies values from its single resolved `AppChainConfig` and
`EffectsSettings`; first-party evidence/composite machines stop parsing their
own copies and fail startup when the required typed view is unavailable.
Consensus-relevant values must also have canonical bytes/digest in a
framework-owned, authenticated chain-profile identity. They must not be merely
node-local defaults exposed through a typed wrapper. Freeze the encoding and
golden vectors, exclude secrets and executor-local settings, verify the
identity on retained state/replay, and fail before participation on mismatch.
This preview release may make the corrected identity the v1 baseline because
prototype histories are explicitly disposable.

At minimum, the committed view must cover every value consumed by deterministic
block admission/application or cross-limit validation, including block message
and byte bounds plus effect enablement, per-block/payload/expiry/result-window
and outcome-commitment limits. This item retires duplicate
`effects.max-per-block` parsing and lets the evidence provider enforce
`block.max-messages * 2 <= effects.max-per-block` itself. Machine-specific
configuration identities remain separately owned; this change must not claim
to authenticate arbitrary plugin configuration.

#### P0.3 — exact-key S3 history semantics

Repair the version-history probe so only versions/delete markers for the exact
key count against its bound. A matching exact-key entry proves prior history
immediately. While proving absence, continue across exact-key pages and stop
when the returned key lexicographically passes the requested key. A truncated,
inaccessible, malformed, or otherwise inconclusive absence probe maps to the
existing retryable `ACK_UNKNOWN`; it must never become definitive absence or a
new `INTERNAL_ERROR`/policy classification.

Add unit and real-service cases for 64+ prefix-sharing siblings, exact versions,
delete markers, multiple pages, and inconclusive pagination. Clarify ADR-013
§7.6 in the same change; receipt and wire formats do not change.

#### P0.4 — certify the fixed tree

Only after P0.1–P0.3 land, run the manual release workflow with `scope=all` and
retain its evidence. Run it again on the final retargeted PR. Wire the four
Phase-1.6 durability suites and CDDL validation into required CI, or record a
reviewed manual exception for any gate that cannot run reliably in CI.

### 7.2 P1 — production-shape improvements

1. Add `evidence-v1-gated` as a distinct committed preset that removes the
   direct-publication path. Make it the default for **new** no-code demo chains;
   retain `evidence-v1` as an explicitly selected direct-publication profile.
   Under the current frozen model this is new-chains-only, not a config toggle
   for an existing chain. ADR-015 later permits a governed activation. Update
   profile digests, identity fixtures, parity tests, docs, and full demo E2E.
2. Add TLS/SASL support and fail-closed production configuration to the
   finalized Kafka sink. Tighten `local-demo` hostname policy in the same change
   even though that residual is P3 when considered alone.
3. Implement ADR-015 in its own staged workstream. Its accepted scope includes
   activation-safe evolution of the authenticated `~composite/profile/v1`
   marker through append-only profile epochs, and deterministic reclamation of
   retired-generation quota after the result-drain window. Those requirements
   are already recorded in ADR-015 §§6, 8, 10, and 15–16.

### 7.3 P2, with an automatic semi-trusted escalation

Keep the following as P2 while deployments are explicitly trusted-member
developer previews or permissioned pilots:

- effect attestation-policy epochs: result-signer rotation and recovery plus an
  optional effect-type-scoped k-of-n result policy, following ADR-010's
  `~anchor/sig` threshold-collection precedent and ADR-010.1 activation rules;
- a continuous, off-consensus independent outcome auditor, seeded from the demo
  runner's existing S3/IPFS/Kafka/anchor re-read logic, with discrepancy
  evidence and alerts; and
- the remaining connector/detail-archive operations hardening and load/soak
  envelope.

The first two items automatically become P1 before planning any deployment with
semi-trusted members. Until they are closed, product documentation must say
that an effect receipt proves a finalized member attestation; it is not an
independently verified proof that the external action or real-world claim is
true. An independent auditor is a compensating operational control and does not
change consensus truth.

### 7.4 Downgraded and future-contract items

- Treat the exact-empty-root retained-composite attachment case as P3 unless a
  reproducible deployment path makes it material.
- Apply one documented canonical encoding/domain-hash policy to future wire
  contracts. Do not rewrite frozen v1 encodings merely to make three valid hash
  domains look uniform.
- Reclassify unversioned object-store provider drift, IPFS detail-byte
  stability, and the other surviving connector findings independently in the
  live tracker; do not fold them into the P0 S3 absence fix.

### 7.5 Branch and merge order

1. Freeze P0.2's canonical profile in ADR-016, then implement P0.1 and P0.2 on
   `fix/appchain-consensus-input-safety`, with focused API compatibility,
   replay, retained-state, and root-parity review.
2. `fix/appchain-s3-history-probe`: P0.3 plus the normative ADR clarification.
   This branch is independent of step 1 and may be reviewed in parallel.
3. Merge both P0 fix branches, then run `chore/appchain-release-acceptance` for
   P0.4 so the evidence certifies the corrected tree.
4. Implement the gated preset and Kafka production transport as separately
   reviewable P1 changes; rerun their affected parity/real-service/demo gates
   before merge.
5. Implement ADR-015 through its own 15.0–15.4 phases. Do not hide it inside the
   connector-remediation PR.
6. Start the effect-attestation/auditor ADR work when the deployment trust model
   or roadmap triggers the P2-to-P1 escalation.

Each branch updates `open_item.md` and its owning ADR in the same PR. No
acceptance rerun may be used as evidence for code that landed after that run.

## 8. P0/P1 implementation closure checkpoint (2026-07-17)

This table records the disposition after implementation and local verification.
It does not rewrite the original review or the reviewer's corrections in §6.
`APP-009` remains open until GitHub has run `scope=all` on the committed tree and
again on the final retargeted PR; local success is not represented as external
CI evidence.

| Review/disposition item | Status | Closure evidence or remaining owner |
|---|---|---|
| C1 / P0.1 unbounded app-chain CBOR | **Fixed locally.** | `CborStructurePreflight` is a dependency-free iterative scanner used at the audited untrusted protocol, command, nested-CBOR, effect, anchor, observation, stdlib, ZK, and webhook boundaries. Definite/indefinite, depth/item/length, trailing-data, validate/apply, no-op, wire, replay, and root-parity tests pass. |
| C5 / P0.2 duplicated node-local consensus limits | **Fixed locally.** | [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md) freezes and implements the typed immutable `AppChainConsensusProfile`, authenticated marker, compatible context accessor, retained-state checks, reserved namespace, and shared first-party limit validation. |
| D1 / P0.3 S3 prefix-sibling false failure | **Fixed locally.** | The exact-key history probe ignores prefix siblings, scans versions/delete markers across pages while exact-key evidence can continue, proves absence only after lexical progress, and otherwise returns `ACK_UNKNOWN`. Unit and real-service matrices cover the boundary. ADR-013 §7.6 is aligned. |
| P0.4 release/durability certification | **Partially closed; `APP-009` remains open.** | The four cluster-safety suites, composite CDDL validation, release contracts, JVM packaging smoke, native packaging smoke, connector fault matrix, Kafka TLS/SASL integration, effect failover, and Compose/host retained-replay parity are green locally and wired in `build.yml`. The two external GitHub acceptance runs remain the release/process gate. |
| Evidence workflow bypass / P1 gated preset | **Fixed locally.** | `evidence-v1-gated` is a distinct committed profile and the no-code demo default for new chains. Direct evidence publication is absent, while explicit `evidence-v1` remains available. Digest, route, identity, launcher, release-contract, and full deployment-parity tests pass. |
| D4 and downgraded D3 / P1 Kafka transport | **Fixed locally.** | The finalized sink has typed TLS/SASL settings and fail-closed production validation; `local-demo` is restricted to explicit loopback aliases. Negative configuration tests and a real TLS/SASL broker integration pass. |
| Genesis-frozen composite profile / P1 ADR-015 | **Fixed locally.** | [ADR-015](015-governed-composite-profile-evolution.md) implements authenticated profile epochs, threshold approval, all-member readiness, future-height activation, epoch-aware proofs, exact retired-generation callbacks through the real effect runtime, deterministic quota reclamation, protected operations, a full finality/MPF/authorization client, signed three-member snapshot restore and empty-ledger late join, and exact JVM/native governed-profile parity. Independent closure review has no unresolved Critical/High/Medium implementation finding. |
| Missing Kafka crash-after-ack coverage | **Withdrawn by reviewer.** | Existing `effect-failover-e2e.sh` coverage remains green and proves two physical records bind to the same logical effect id at the acknowledged duplicate boundary. |
| Uncommitted ADR-013 release closure | **Withdrawn by reviewer.** | The cited closure commit and merge are present. `APP-009` tracks only the separate external acceptance-run residue. |
| Claim that executor trust was undocumented | **Conceded by reviewer.** | ADR-010 already states that results are member attestations, not independently verified external truth. `APP-006` and `APP-011` remain P2 and automatically become P1 before a semi-trusted-member deployment. |
| Exact-empty-root retained attachment residual | **Deferred/P3.** | `APP-008` owns the narrow residual; startup already rejects retained non-empty state with a missing or mismatching marker. |
| Remaining connector, archive, audit, load, protocol-publication, and code-structure findings | **Deferred with explicit triggers.** | [ADR-017](017-fable-review-closure.md) and `open_item.md` retain `APP-006/010/011`, `REV-002` through `REV-012`, and their escalation/closure rules. |

The supported posture after this checkpoint is still a trusted-member developer
preview or permissioned pilot. Semi-trusted membership, independently verified
external outcomes, production Cardano funds, regulated-data erasure, and a
published soak envelope retain their explicit gates in `open_item.md`.
