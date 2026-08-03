# ADR-024 (Report): Yano App Chain — Full Implementation Review, Readiness Report & Roadmap

## Status

Informational report — full-surface review of app-chain support: core SPI, runtime host,
state machines, plugins, optional extensions, products, deployment tooling, and the ADR record.
This document records findings and proposals; it does not itself make an architectural decision.

## Date

2026-08-01

## Scope and method

**Scope.** Everything that makes up "app-chain support" on the working tree of branch
`feat/showcase_demo` (including uncommitted changes):

- App-chain public SPI and config — `core-api/.../api/appchain/**` (69 files), `appchain/appchain-config`
- Runtime host — `runtime/.../runtime/appchain/**` (40 files, ~5 000-line `AppChainSubsystem`)
- Reusable state machines — `appchain-stdlib`(+contracts), `appchain-composite`(+contracts/client), `appchain-integration-contracts`
- Role/evidence products — `appchain-role-workflow`(+contracts), `products/appchain-evidence-{contracts,registry,profile,client}`
- Optional extensions — `extensions/eutxo` (149 files), `extensions/eutxo-zk` (87), `appchain-kafka`, `appchain-ipfs`, `appchain-objectstore-s3`, `appchain-zk`, `appchain-effects-cardano`
- Plugin infrastructure — `plugin-catalog`, `fixtures/appchain-plugin-conformance`, `scaffolds/plugin-template`
- Developer surface — `appchain-client`, `appchain-devtools`, `appchain-testkit`, `appchain-studio`, `spring-starters/appchain-spring-boot-starter`
- Deployment — `app/` Quarkus profile, `app/appchain-cluster`, `app/appchain-effects-demo`, `appchain/onchain` (Aiken + julc anchor validator), `console-ui`, `docker/compose`
- ADR record — `adr/app-layer/**` (41 documents) versus the code that claims to implement it

**Size of the reviewed surface.** ~50 Gradle modules; ~110 000 lines of main Java in the
app-chain tree; ~108 000 lines of test Java; 421 test classes.

**Method.** A 21-agent orchestrated review: nine independent subsystem reviewers reading the
actual code (not names or docs), then ten adversarial verifiers each instructed to *refute*
the most severe findings by hunting for an upstream guard, and two design agents producing the
enhancement and new-component roadmaps. Verifiers were required to cite a concrete
`file:line` execution path before confirming. Every verifier verdict below is CONFIRMED —
zero refutations, which is itself a signal that the reviewers were reading real paths rather
than pattern-matching.

## Related

- [ADR-005](005-yano-app-chain-framework.md), [ADR-006](006-appchain-enterprise-extensions-and-zk.md), [ADR-008 series](008-appchain-next-iteration-plan.md)
- [ADR-010](010-deterministic-effect-system.md)/[010.1](010.1-emission-versioning.md), [ADR-011 series](011-plugin-architecture.md), [ADR-013 series](013-first-party-integration-connectors-and-effect-demo.md)
- [ADR-014 (prior external review)](014-appchain-adr013-external-review-readiness-and-feasibility-fable.md), [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md), [ADR-019](019-reusable-domain-actor-registry-and-role-aware-approvals.md), [ADR-022](022-out-of-box-appchain-capabilities-and-extensible-product-catalog.md), [ADR-023](023-unified-appchain-showcase-distribution.md)
- Trackers: [open_item.md](open_item.md), [pending-tasks.md](pending-tasks.md)

---

## 1. Executive summary

**Overall verdict: strong, unusually well-engineered beta.** All nine subsystems independently
landed on the same readiness level — `beta` — for the same structural reason: the *mechanical*
foundations are production-grade, and what is missing is a small set of adversarial-safety and
operational-recovery properties.

What is genuinely excellent, and rare at this stage:

- **Canonical-encoding discipline is systemic, not spotty.** Nearly every consensus-visible codec
  decodes *and re-encodes*, rejecting on byte inequality (`AppBlockCodec.deserializeCanonical`,
  `EffectRecord.decode`, `AnchorDatumV1.decode`, `RoleWorkflowCbor`, `CompositeProfileCodec`).
  Malleability is closed by construction, not by convention.
- **Untrusted CBOR never reaches a recursive decoder unscreened.** `CborStructurePreflight` is a
  dependency-free, non-recursive, budget-bounded scanner with per-contract frozen limits.
- **Complete signature verification on every path.** Proposals, per-message member signatures at
  height, votes, finality certs, catch-up blocks (re-executed and re-verified), snapshot manifests.
  Nothing is trusted because of the peer's role.
- **Atomic commits with the WriteBatch visibility trap explicitly avoided.** Block, trie, indexes,
  sender-seq floors, fx outbox and governance meta all land in one fsynced batch, with apply-time
  reads cleanly separated from commit-time pure writes.
- **Config-mismatch fail-stop instead of silent fork** (ADR-016 consensus-profile marker committed
  into the trie at height 1 and re-verified every block) — the right instinct, applied to the
  framework's own settings.
- **Honest ADR record.** Every "implemented" claim spot-checked across ADR-005…022 maps to real,
  tested code; deferred work is explicitly fenced with revival triggers rather than quietly dropped.

The problems cluster into five themes, in priority order:

| # | Theme | Why it matters |
|---|-------|----------------|
| T1 | **Apply-time throws are reachable from admitted messages** | A crafted zero-fee message deterministically stalls block production chain-wide (1 critical + 3 high/medium instances). This is the single largest class. |
| T2 | **Approval/authorization scope gaps in the evidence + role layer** | Approved operations aren't bound to an executor; unapproved fields ride along with the approval. Defeats the dual-control property the product sells. |
| T3 | **Consensus-affecting settings that the ADR-016 marker does not cover** | Governance approval window, membership mode, per-machine settings (minter, value-format, effect config) drift silently and fork state roots instead of failing fast. |
| T4 | **Durability / retained-state hazards** | Vote locks are not fsynced (power-loss double-vote fork); the uncommitted storage-root relocation silently abandons existing ledgers with no migration guard. |
| T5 | **Failure surfaces that hide failures** | SSE drops messages silently, Kafka sink head-of-line-blocks forever without a log, typed verified reads throw false tamper errors for absent keys. |

**Finding volume:** 65 findings — **1 critical, 14 high, 26 medium, 24 low**. The top 10 by
severity were adversarially verified: **10 CONFIRMED, 0 REFUTED** (three had their *mechanism*
corrected — see §3 — which sharpens the fixes rather than weakening them).

**Bottom line by deployment posture:**

| Posture | Verdict |
|---------|---------|
| Devnet demos, showcase, internal evaluation | **Ready now.** This is what the tooling is built for and it is well tested. |
| Consortium pilot, trusted members, no real value | **Ready after Wave 1** (§5) — mainly T1 + T4 + the console/cluster key hygiene items. |
| Public/adversarial exposure (open submit endpoint) | **Not ready.** T1 gives any authenticated submitter a cheap, renewable chain halt. |
| Real funds / production settlement | **Not ready.** Blocked on FX-002 (ADR-010.2 transaction safety), T1, T2, and APP-009 CI certification. |

---

## 2. Architecture map (what implements what)

| Layer | Modules | ADR |
|-------|---------|-----|
| SPI + config | `core-api/.../api/appchain/**`, `appchain-config` | 005, 010, 011, 016 |
| Consensus host | `runtime/.../runtime/appchain/**` (`AppChainSubsystem`, `AppChainEngine`, `AppLedgerStore`, `FxKernel`, `GovernedMembership`, `ScriptAnchorService`, `ConsensusProfileGuard`) | 005, 008.1–008.4, 010 |
| Reusable machines | `appchain-stdlib` (kv-registry, balances, approvals, doc-trail), `appchain-composite` (+governed profile epochs) | 021, 013.2, 015 |
| Domain layer | `appchain-role-workflow` (actor registry, role-aware approvals) | 019 |
| Products | `products/appchain-evidence-*` | 018, 020, 021, 022 |
| Optional extensions | `extensions/eutxo`, `eutxo-zk`, `appchain-kafka`, `appchain-ipfs`, `appchain-objectstore-s3`, `appchain-zk`, `appchain-effects-cardano` | 006, 013, utxo/002–005 |
| Plugin infra | `plugin-catalog`, `plugin-conformance`, `plugin-template` | 011, 011.1–011.4 |
| Dev surface | `appchain-client`, `appchain-devtools`, `appchain-testkit`, `appchain-studio`, Spring starter | 022, dx/0001 |
| Deployment | `app/`, `app/appchain-cluster`, `app/appchain-effects-demo`, `appchain/onchain`, `console-ui` | 023, 008.4 |

Key structural facts worth carrying into any design work:

- **All consensus state lives in the MPF trie**, committed atomically — so rollback safety is
  *inherited from the trie root* rather than reimplemented per data structure. This is the correct
  answer to the project's known `PREFIX_POOL_BLOCK_COUNT`-class hazard, and it holds across stdlib,
  composite, role-workflow, evidence, and EUTxO. **Exception:** governance/membership meta
  (`gov_pending`, `member_epochs`, vote locks) lives in the RocksDB meta column family, *not* the
  trie — which is exactly why T3 and T4 findings are invisible to state-root comparison.
- **Effects execute outside consensus** with durable queue + submitted-ref reconciliation; executors
  are told to *reconcile, never re-mutate*. The connectors honor this well.
- **The composite machine is the composition primitive**: length-prefixed domain-separated key
  namespaces, capability-limited state views, per-generation effect quotas, effect-owner records for
  late results. Sub-machine isolation is genuinely enforced, not documented-and-hoped.

---

## 3. Verified issue register (adversarially confirmed)

Ten findings were put through an independent refutation pass. All survived. IDs are assigned here
for tracking.

### AC-01 — [CRITICAL] Duplicate transaction input: conservation soundness gap + chain-halt DoS

**Where:** `appchain/extensions/eutxo/appchain-eutxo-ledger/.../KeyPaymentTransitionEngine.java:595`
(`conserve`), with the crash at `EutxoStateMachine.java:583-591`.

Neither `validateShape` nor `transition()` enforces input distinctness. CCL's `TransactionBody`
deserializes inputs into a plain `ArrayList` (no dedup) and re-serializes duplicates byte-identically,
so the `NON_CANONICAL_CBOR` check passes. `conserve()` sums input coin over the **multiset**, counting
a repeated input twice; `authorize()` dedups credentials into a `LinkedHashSet`, so one witness covers
both copies. The transaction is **ACCEPTED**. Then `applyAccepted` iterates `consumed = [A, A]`: the
first delete removes UTxO A, the second `writer.get(A)` is empty and `orElseThrow` raises
`IllegalStateException("validated EUTxO input disappeared before mutation")` **inside `apply()`**.

**Verifier's mechanism correction (important for the fix):** this is a *proposer-side* liveness halt,
not a fork. Voters re-execute `applyBlock` during vote verification (`AppChainEngine.java:821`) and
throw, so the block never certifies. The real damage is that `AppMsgPool.drainCandidates` is a
**non-destructive snapshot** (`AppMsgPool.java:60-76`) and the propose-failure catch
(`AppChainEngine.java:575-580`) does not remove the message — so every subsequent propose tick
re-selects the same poison message and fails again. One zero-fee message from any user owning a
single UTxO permanently stalls the chain until TTL expiry (default 600 s) or an operator pool drain,
and the attacker simply resubmits.

Underneath the crash sits a **genuine inflation bug**: if the apply-time throw were removed, the
multiset conservation check would let an attacker mint value.

**Fix:** reject non-pairwise-distinct input outpoints in `validateShape`/`transition()` (build a
`Set<EutxoOutpoint>`, compare size to input count) **before** `conserve`. Never rely on `apply()`
throwing.

### AC-02 — [HIGH] Vote locks are not fsynced — power-loss double vote can fork the chain

**Where:** `runtime/.../AppLedgerStore.java:478` (`putVoteLock`), `:491` (`putVoteLockEnvelope`).

Both use `db.put` with default `WriteOptions` (WAL append, no fsync) while `commitBlock` (`:573`) and
three other finality-critical writes deliberately use `setSync(true)` — so this is an omission, not a
policy. `AppChainEngine` broadcasts the signed vote *immediately* after the unsynced put
(`handleProposal:834→843`, `doProposeTick:552→557`), and the persisted lock is the **only**
double-vote guard (the pending round is in-memory). The class doc's "at-most-one vote per height
across restarts" holds for process crashes (page cache survives) but not OS crash/power loss.

**Failure:** 3 members, threshold 2. A votes b1 at height H, power is lost before WAL flush. A
restarts with no lock at H, receives competing proposal b2 (rotating mode, or the proposer's own
restart-and-repropose path at `:485`), and votes b2. b1 holds {A,B}, b2 holds {A,C} — two conflicting
threshold-certified blocks at the same height. Permanent fork.

**Fix:** write lock + envelope in one `WriteBatch` with `setSync(true)` (which also closes the
lock-without-envelope window), and broadcast only after that write returns.

### AC-03 — [HIGH] GovernedMembership silently drops concurrent same-block activations

**Where:** `runtime/.../GovernedMembership.java:189`, `MemberGroup.java:105`.

`activate()` builds the new set from `group.membersAt(height)`, but an epoch activated earlier in the
**same block** takes effect at `height + lag > height`, so a second activation never sees the first —
the in-code comment claiming same-block visibility is wrong (`epochAt` only matches
`fromHeight <= height`). Worse, `appendEpoch` *removes* trailing epochs with
`fromHeight >= the new one`, so with equal lags the second activation **deletes** the first.

**Failure:** operators approve `add-member(A)` and `add-member(B)`, both lag 10; both
threshold-crossing approvals land in block H (normal — approvals pool up, and there is no per-topic
cap in `drainCandidates`). `activate(A)` appends `epoch(H+10, base+A)`; `activate(B)` computes
`membersAt(H) = base` and appends `epoch(H+10, base+B)`, deleting A's epoch. **A is never added
despite threshold approval** — silently, identically on every node, so nothing flags it. With
unequal lags (5 vs 10) it is worse: A is a member for 5 blocks, then **implicitly removed** with no
remove command ever issued.

**Fix:** compute activations against the latest appended epoch (working-epoch accumulator through the
block), and make `appendEpoch` merge rather than blind-replace same-height epochs. Add a
property test: any permutation/batching of approved commands yields the same final member set.

### AC-04 — [HIGH] Governance approval window is consensus-critical but outside the profile commitment

**Where:** `runtime/.../GovernedMembership.java:140`; `AppChainConsensusProfile.java:22-38`.

`processBlock` expires pending approvals using the node-local `membership.approval-window-blocks`
(built from plugin settings at `AppChainSubsystem.java:3144-3148`, no cross-node validation).
Activation outcomes feed `MemberGroup` epochs, which gate vote counting (`AppChainEngine:1023`),
cert verification (`:1078`), catch-up proposer acceptance (`:400`) and sender membership (`:1216`).
Unlike `effects.*` and block limits, this value is **not** in `AppChainConsensusProfile`, so the
height-1 marker cannot catch a mismatch. And because `gov_pending`/`member_epochs` live in the meta
column family rather than the trie, **state roots stay equal while member sets diverge** — the split
is completely silent.

**Failure:** node A at 600, node B at 300; a command's second approval lands 400 blocks after the
first. A activates, B expired it. From `height+lag` their member sets and thresholds differ: A
accepts certs B rejects. Silent split or wedge with no diagnostic.

**Fix:** add `membership.approval-window-blocks` and `membership.mode` to `AppChainConsensusProfile`
(v2 schema, §5 W2-1), or hard-code the window as a chain constant.

### AC-05 — [HIGH] Approvals on-approved effect emission can exceed the effect cap and abort block production

**Where:** `appchain/appchain-stdlib/.../ApprovalsStateMachine.java:211` (`emitOnApprovedEffect`).

`effects.emit()` is called once per item reaching final approval, uncounted and uncaught.
`FxKernel.BlockEmitter.emit:344` throws `EffectLimitExceededException` past `effects.max-per-block`
(default 256; `block.max-messages` default 5000). Nothing between the machine and
`AppChainEngine.applyBlock:1195` catches it, so the round aborts — and because the pool drain is a
snapshot, the same messages are re-selected every tick.

**Failure:** one member submits 257 `PROPOSE(required=1)` + 257 `APPROVE` messages (self-approval is
permitted). All pass admission (`validateForBlock` checks only decode + payload size). The 257th
emit throws; block production stalls chain-wide, renewably. It also fires **without malice** on any
honest burst of >256 same-block final approvals.

**Scope (verifier refinement):** requires the opt-in `effects.enabled=true` +
`machines.approvals.on-approved-effect.enabled=true` configuration. Both in-repo composite presets
(showcase, evidence) hard-disable it, so exposure is limited to standalone `state-machine=approvals`
chains with the effect on (a documented, tested configuration per ADR-006 E2.2) and custom
composites. Recovery is possible via admin pool drain without waiting for TTL.

**Fix:** deterministic skip/defer instead of throw — track emitted-this-block count or catch the
limit exception, leaving the staged payload for a later block. The composite `OwnedEmitter`
(`CompositeStateMachine:638-641`) needs the same treatment. `EvidenceWorkflowCapacityV1` is the
existing in-repo precedent for a capacity contract.

### AC-06 — [HIGH] Approved release not bound to an executor: first includer captures evidence ownership

**Where:** `products/appchain-evidence-profile/.../RoleEvidenceReleaseWorkflow.java:105`
(and `EvidenceReleaseWorkflow.java:123-146`).

The role approval binds `payloadHash` to the release command bytes, but **nothing binds who may
execute it**. `EvidenceRegistryStateMachine.applySubmit:247` sets
`EvidenceHeadV1.ownerPublicKey = message.getSender()` (transport sender — not covered by the hash),
and `canApplyStorage` only requires `config.isIssuer(sender)`, which returns true for **every**
authenticated member when `machines.evidence-registry.issuers` is empty (the documented default).

**Failure:** release command R for `audit-2026` passes role approval. Member M (not the intended
releaser) copies the byte-identical command onto the topic and gets sequenced first. Ownership is
now M's. The legitimate submitter's message returns `EXACT_REPLAY` and silently no-ops. Ownership
gates all future republish and notify rights, and heads are **never reassignable** — the legitimate
organization is permanently locked out of its own approved evidence.

**Scope:** the attacker must be an authenticated member and the release must already have passed
quorum — arbitrary evidence cannot be minted. But the dual-control guarantee is defeated: one rogue
member acting alone captures the approved release.

**Fix:** add an explicit executor/owner public key to `EvidenceReleaseCommandV1` (covered by
`commandHash`, therefore by the approval) and require
`MessageDigest.isEqual(source.getSender(), command.executor())` before `claim`; or derive the head
owner from the command rather than the transport sender.

### AC-07 — [HIGH] Stock release approval covers only the evidence command hash — doc-trail rides unapproved

**Where:** `products/appchain-evidence-profile/.../EvidenceReleaseWorkflow.java:110`.

The workflow matches `item.payloadHash` against `command.evidenceCommandHash()` — the blake2b of the
*nested evidence command only*. So `documentEntityId`, `documentHash`, `documentRef`, `registryKey`
and `releaseId` are **never authorized**, yet the workflow appends the doc-trail entry (`:131`) and
claims the releaseId as if the whole operation had been approved. The sibling
`RoleEvidenceReleaseWorkflow` binds the **full** `commandHash()` — proving the narrow binding is a
defect, not a design choice, and the showcase variant derives its audit hash from the approved order
value.

**Failure:** approvers sign off on evidence command E expecting release R1 with document entry D1.
An issuer-eligible member submits R2 with the same nested E but fabricated
`documentHash`/`documentRef`. `payloadHash` matches, validate passes, R2 lands first: the forged
document hash is permanently recorded as the release's authoritative provenance, and the head slot
for E's evidenceId is consumed so the legitimate R1 can never apply.

**Fix:** bind to `command.commandHash()` exactly as the role variant does; bump
`EvidenceReleaseWorkflow.PRODUCT_VERSION` (consensus-affecting) and update the approval-payload
construction convention in lockstep.

### AC-08 — [HIGH] Per-address UTxO cap enforced only by throwing in apply() — zero-fee chain halt

**Where:** `extensions/eutxo/appchain-eutxo-ledger/.../EutxoStateMachine.java:1003-1012` (`putRecord`).

`profile.maxAddressUtxos()` (1024) is enforced by throwing `IllegalStateException` in the *mutation*
phase. Admission is stateless (`preflight` = parse only), `validateForBlock` **discards the
`committedState` it is handed** (`:147-153`), and `transition()` never reads the address index.

**Failure:** `validateShape` requires `fee == 0`, outputs need only `coin > 0`, and `maxOutputs = 64`
— so ~17 transactions push **any address, including a third party's**, past 1024. Submission is open
via the REST gateway, which signs with the node's own member key. The next output to that address
throws in `apply()`. Same proposer-stall mechanism as AC-01: the message is re-selected every tick;
the chain stops advancing entirely. Worse, **a saturated address is a permanent trap** — any honest
payment or `~l1` bridge deposit to it (`importDeposit:802`) re-triggers the halt.

**Fix:** compute the resulting per-address count inside `transition()` from the supplied
`AppStateReader` (and in the deposit path) and return a deterministic `ADDRESS_UTXO_BOUND` rejection.
`putRecord`'s throw becomes an unreachable invariant.

### AC-09 — [HIGH] zk-membership nullifier dedup bypass via non-canonical bytes

**Where:** `extensions/appchain-zk/.../ZkMembershipStateMachine.java:99`, `MembershipProofBody.java:51-56`.

The nullifier is bound to the proof **numerically** (`new BigInteger(1, body.nullifier())` checked
against `publicInputs`), but the dedup state key and record key are built from the **raw bytes**.
`MembershipProofBody.decode` rejects only an empty nullifier — no fixed-width or minimal-encoding
check. `ZkVerificationService.verify` builds its envelope from `publicInputs` (as `BigInteger`s) and
does not touch the raw nullifier field at all.

**Failure:** an attacker submits one valid proof twice, with nullifier bytes `0xAB` and `0x00AB`.
Both satisfy the numeric bind check, **the proof bytes and public inputs are byte-identical so no
re-proving is needed**, and the two raw byte strings map to different MPF keys — the dedup misses and
both actions are recorded. Two anonymous actions from one nullifier: double vote, double sealed bid.
Deterministic on all nodes, so this is a security flaw, not a divergence.

**Verifier refinement:** amplification is **unbounded**, not ×2 — any number of leading `0x00` bytes
(up to the message-size bound) yields a distinct key while preserving `BigInteger` equality.
Engine-level dedup cannot help: `messageId` is body-derived, so each padding variant is a distinct
message.

**Scope:** the machine is EXPERIMENTAL and opt-in (`state-machine=zk-membership` + pinned circuits);
no default deployment is affected.

**Fix:** canonicalize at decode time — require the circuit's fixed field-element width (or reject
`nullifier[0] == 0`) so equal field values map to exactly one key. Note `encode()` also passes the
nullifier through raw, so the canonicalization must make encode/decode round-trip canonical.

### AC-10 — [HIGH] Typed verified reads throw a false tamper error for every absent key

**Where:** `appchain-client/.../StdlibAppChainClient.java:92`; identical root cause in
`spring-starters/.../AppChainTemplate.java:60`.

The server returns **200 with an exclusion proof** for an absent key (`AppChainProofResourceTest`
asserts exactly this; `MpfTrie.getProofWire` always returns a wire). The facade calls
`ProofVerifier.verify(proof)`, which returns `false` whenever `valueHex == null`
(`ProofVerifier.java:52`), so every exclusion proof throws
`IllegalStateException("MPF proof verification failed…")`. Line 96
(`if (proof.valueHex() == null) return Optional.empty();`) is **provably unreachable dead code** —
proof that `Optional.empty()` was the intended behavior. `verifyExclusion` exists but is never called
from either facade, so there is no proven-absence path at all.

**Failure:** `stdlib.balance("new-account")` before the first mint, `kvEntry(key)` before put or after
delete, `approval("unknown-item")`, or the Spring `verifiedProof` for a message still in the mempool
— all crash instead of returning empty. Applications polling for a value to appear (the natural
usage) fail on the first poll, and Spring users get false *tamper alerts*.

**Fix:** dispatch on `valueHex`: null → `verifyExclusion` then return empty (throw only if the
exclusion proof itself fails); non-null → `verify`. Add a `VerifiedResult<T>` sealed type
(`VerifiedPresent` / `VerifiedAbsent`) so proven-absence becomes a first-class API.

---

## 4. Remaining findings by subsystem

65 findings total: **1 critical, 14 high, 26 medium, 24 low**. The ten above are verified; the
remainder are reviewer-reported (unverified but each carries a `file:line` and a failure scenario).

### 4.1 Additional HIGH findings (not in the verified top-10)

| ID | Subsystem | Finding | Location |
|----|-----------|---------|----------|
| AC-11 | app-deployment | **Console `?api=` exfiltrates the persisted API key to any origin.** `resolveApiBase()` gives the query param top priority and accepts absolute URLs to any host; the stored key is attached with no origin binding. A link to the operator's own console with `?api=https://attacker.example` leaks a key authorizing effect requeue/cancel ("can move real funds"), admin pause/drain/force-anchor, and key rotation. | `console-ui/frontend/src/lib/api/client.ts:34` |
| AC-12 | app-deployment | **`node join`/`resume` restarts node 0 with the publicly-known demo anchor seed.** `refresh_governed_peer_topology` relaunches every node; `anchor_signing_seed` falls back to the repo-public demo seed when the key isn't re-passed. The non-devnet guard exists only in `cmd_start`, and the identity marker check is never called on this path — both protections bypassed. On preprod this silently swaps the anchor wallet to a seed anyone can read. | `app/appchain-cluster/cluster.sh:3052` |
| AC-13 | adr-conformance | **ADR-023 claims "Accepted and implemented" but the ADR and the entire showcase module are untracked in git.** Confirmed independently: `git cat-file -e HEAD:adr/app-layer/023-…` fails. A rebase or `git clean` destroys the only copy of a deliverable the record calls complete with 15/15 criteria satisfied. | `adr/app-layer/023-…md:5` |
| AC-14 | adr-conformance / runtime | **Storage-root relocation abandons retained ledgers, with no migration guard and no owning ADR.** The working tree moves the default from `<yano.storage.path>/app-chain/<chainId>` to a cwd-relative `appchain-state/<chainId>` with no legacy detection. An upgraded member starts a **fresh empty ledger**: vote locks gone (re-vote at a height it already voted — the AC-02 fork risk), `member_epochs` rotation overrides reverted, effect dedupe journals lost (a restarted executor can **re-execute already-performed external effects** — Kafka publish, S3 put). In script-anchor mode the on-chain thread NFT already records height N, so every new advance fails `out.height > in.height` — anchoring permanently wedged. Additionally two nodes sharing a working directory now collide on one RocksDB path. | `runtime/.../internal/AppChainStoragePaths.java:14`, `RuntimeNode.java:661` |

### 4.2 MEDIUM findings by subsystem (26)

**core-spi-config (3)**
- Default `JacksonCborCodec` uses `findAndRegisterModules()` — classpath-module auto-discovery **inside the deterministic apply path**. Two members with different transitive Jackson modules can decode identical committed bytes into different payloads (or one skips via the decode-exception `continue`), forking state roots. `codec/JacksonCborCodec.java:24`
- `AppChainConfig` is a public **record** holding the Ed25519 private seed — the synthesized `toString()` prints `signingKeyHex` and the anchor payment key in cleartext. One future `log.debug("config: {}", config)` away from key compromise. `AppChainConfig.java:50`
- `EffectExecutorOperationsTracker.snapshot()` TOCTOU: reads `attempts` then outcome counters, so a concurrent completing attempt makes `successes > attempts` and the record constructor throws, aborting status/metrics sampling. `effects/EffectExecutorOperationsTracker.java:68`

**runtime-host (3)**
- Legacy-path detection missing for the storage relocation (the runtime half of AC-14). `RuntimeNode.java:661`
- **Snapshot manifest races concurrent commits**: the checkpoint is created, then tip/state-root/member-epochs are read from the **live** ledger. Any commit in between binds the manifest to a tip the checkpoint lacks — `verifyPostOpen` then fails for *every snapshot taken under load*, so members cannot be onboarded without pausing the chain. `AppChainSubsystem.java:2477`
- **Failed block commit leaves in-memory membership diverged**: `governanceWrites` mutates `GovernedMembership.pending` and appends epochs *before* `commitBlock`; a RocksDB failure is swallowed and the engine keeps running with membership the ledger never recorded — later verifying certs against a member set no other node has. `AppChainEngine.java:1100`

**state-machines (3)**
- Governed composite **epoch-bound exhaustion halts at activation** instead of rejecting at proposal: `ensureProfileForHeight` throws when `currentEpoch+1 >= maximumEpochs`, and it runs *before* `processCommands`, so no Cancel at that height can clear it. Chain wedged until membership rotation or patched code. `CompositeProfileGovernanceRuntime.java:188`
- **Stdlib machine settings have no on-chain commitment**: `machines.balances.minter`, `kv-registry.value-format`, approvals effect config and activation-schedule heights all change `apply()` outcomes but are node-local. Operator A sets a minter, B leaves it empty → different roots for the same block, chain stalls with no diagnostic. `StdlibStateMachineProviders.java:73`
- **A single member can lock composite governance indefinitely**: Approve/Ready/Cancel are ignored while a proposal is STAGING (`proposalHash` only exists after Seal), and Begin is ignored while any proposal exists. An unsealed proposal with a max-TTL expiry blocks all governance and can be renewed forever. `CompositeProfileGovernanceRuntime.java:400`

**role-workflow-evidence (2)**
- **Direct-result notification burst exceeds the component effect quota and aborts block application.** Results are packed bounded only by `block.max-messages` (5000) while the evidence component quota is 8; 24 backlogged IPFS results in one block → `OwnedEmitter` throws → block unapplyable on every node, re-failing on replay. `EvidenceRegistryStateMachine.java:194`
- **Role governance admins pinned to the genesis membership epoch.** After supported member key rotation, rotated members' governance commands are silently dropped; once fewer than threshold genesis keys remain in use, no actor, key epoch or policy can ever be changed again — **including revoking a compromised actor key**, which is precisely when rotation happens. `RoleWorkflowGovernanceConfig.java:43`

**eutxo-extension (3)**
- **Indexer projects mutable withdrawal status at the wrong height and keeps a stale CONFIRMED row after rollback.** A single tip-state snapshot is replayed across the whole drain range, so a withdrawal requested at H1 and confirmed at H2 is recorded CONFIRMED at *both*; a rollback to between them deletes the H2 row but leaves the H1 CONFIRMED row — the indexer reports a rolled-back withdrawal as settled. Also makes per-height history depend on drain batching. `EutxoIndexCoordinator.java:307`
- **Drain retries with no backoff**: the `finally` block re-invokes `schedule()` immediately on failure, pegging a CPU core indefinitely on any persistent error. `EutxoIndexCoordinator.java:127`
- **`apply()` re-decodes bridge envelopes and throws** where `validate()` catches — another apply-time halt path, asymmetric between the two. `EutxoStateMachine.java:164`

**connector-plugins (3)**
- **`KafkaStreamSink` silently and permanently head-of-line-blocks** on non-retryable broker errors: `deliver()` returns false on *any* exception with **no logging**, and the cursor never advances. Wrong topic, denied ACL or rotated credentials stalls the entire finalized stream forever, invisibly. Contrast `KafkaPublishExecutor`, which classifies definitive failures. `KafkaStreamSink.java:84`
- **Catalog authenticates plugin provenance by self-asserted bundle id only.** Digests are computed but never pinned or compared; allow/deny lists are advisory naming. A rogue JAR declaring an allow-listed bundle id with matching provider class names passes correlation and is selected. `PluginCatalogInspector.java:130`
- **No determinism/replay conformance tests for the three consensus-critical ZK machines** — the exact harness that would have caught AC-09. `appchain-zk` tests

**client-devtools (3)**
- **Generated compose project for `settlement:zeroj-validity` is unstartable**: `${YANO_APPCHAIN_PROJECT_ROOT}` is never set in the container and the only project mount is read-only, yet the capability advertises `docker-compose` as a deployment target and the test matrix omits the recipe. `AppChainProjectRenderer.java:357`
- **SSE `subscribe` hides all failures**: cursor advances *before* `consumer.accept`, so a throwing consumer permanently loses that message on reconnect; non-200 responses retry every 2 s forever with no logging and **leak the response stream each attempt**. `AppChainClient.java:974`
- **Testkit leaks temp ledgers and races on ports** with silently swallowed bind failures — parallel CI produces unattributable 30 s "cluster connectivity" timeouts and gigabytes of orphaned RocksDB dirs. `AppChainClusterExtension.java:149`

**app-deployment (3)**
- Legacy app-chain state invisible to `appchain_state_present()` (the launcher half of AC-14). `cluster.sh:1136`
- **Shell YAML scraping maps chain ids to indices by authored order** and requires double-quoted values; an unquoted `chain-id:` or out-of-order block silently anchors the wrong chain or breaks the identity marker. `cluster.sh:1644`
- **Well-known default full API key accepted on public networks.** `cmd_start` requires an operator anchor key off devnet but applies no equivalent rule to the API key, so any local user on a shared host can cancel/requeue effects, force anchors (spending real funds) or drain the pool. `cluster.sh:2098`

**adr-conformance (3)**
- `open_item.md` — the self-declared canonical tracker — is **12 days stale**: ADR-021/022/023, dx/0001 and the whole `utxo/` series (including the safety-critical UTXO-006 public-funds hardening) are unindexed, so their deferred items exist in no tracker table.
- **APP-009, the program's own P0** ("certify the corrected tree and wire durability + CDDL evidence into CI"), has been Ready since ADR-014 while ADRs 015–023 all shipped as "accepted and implemented" on locally collected, unretained evidence.
- **ADR-DX-0001 still reads "Proposed"** although much of its M0–M5 tooling is shipped, with no per-milestone delivery record.

### 4.3 LOW findings (24) — condensed

Config/SPI hygiene: `defaultTtlSeconds > maxTtlSeconds` is unvalidated (node self-rejects its own
messages); `AppChainConfigParser` silently truncates oversized consensus-shared numerics via bare
`(int)` casts (`threshold=2^31` becomes 1); `FxResultBody.decode` omits the re-encode equality check
its siblings all have and uses non-exact integer conversions; `FinalityCert` records accept
arbitrary-length signer/signature arrays.

Liveness/poison: `kv-registry` stored entries can exceed the persisted-entry bound at extreme
`max-message-bytes`, permanently poisoning a key; composite admission calls third-party `validate()`
without exception isolation despite demanding "reject, never throw"; approvals staged payloads and
expired items are never garbage-collected (unbounded trie growth, and clients see dead proposals as
PENDING).

Runtime: a single member can starve consensus-relevant system messages (L1 observations dropped at
injection are **never retried** — a user's L1 deposit stays permanently unsequenced);
`AppLedgerStore.close()` leaks two `ColumnFamilyOptions` native handles per stop/start generation.

Role/evidence: pending budgets are reclaimed only by per-id poke messages (10 000 stale proposals =
weeks-long outage); one-decision-per-actor across all clauses can make a policy unsatisfiable;
evidence query path throws bare `IllegalStateException` instead of typed `AppQueryException`.

EUTxO: `validateForBlock` ignores the committed state it is handed (the enabling condition for AC-01
and AC-08); `canonicalRecords()` materializes full deposit+withdrawal history on **every** drain.

Client/devtools: metadata export uses `System.lineSeparator()` despite sha256 pinning (Windows
breaks the pins); `postForOk` collapses 401/409/500 into a bare boolean (an external executor
abandons an outcome and the effect is silently re-executed); `CborCodec` has the same
`findAndRegisterModules` nondeterminism as the server codec — the same POJO yields different message
ids from different producers; Spring listener registrations are lost across a lifecycle stop/start
(`isRunning()` true, zero subscriptions, nothing logged).

Deployment/ADR: `PaymentCommand` truncates lovelace > `Long.MAX` instead of rejecting;
mid-failure in `refresh_governed_peer_topology` leaves a bootstrap node down with **no launcher verb
to bring it back**; `cmd_threshold_set` treats a missing `threshold` field as 0, vacuously passing
its own approvals guard; `pending-tasks.md` still lists the shipped typed query surface as deferred;
ADR-023 §7 promises scoped generated demo credentials that do not exist; ADR-023 §6.5 command syntax
diverges from the implemented facade; ADR-023 criterion 3 (identical plugin digest on **every**
voting node) is only checked on node 0.

---

## 5. Enhancement plan

Twelve enhancement programs, sequenced into four waves. Waves are ordered by *what unblocks what*,
not by module.

### Wave 0 — Release integrity (do before anything else merges)

**W0-1. Commit ADR-023 and the showcase module together** (AC-13). The ADR, the entire
`appchain/examples/appchain-showcase` module, the `settings.gradle` include and the supporting
cluster/effects-demo/runtime diffs must land as one change set, and the contract gates
(`verifyShowcasePluginArtifact`, script/distribution contract tests) must be re-run *from the
committed tree* before the "implemented" status is kept. **Per project rules this requires explicit
user approval — it is not done as part of this review.**

**W0-2. Storage-root relocation: guard + ADR** (AC-14, effort S). Add legacy-path detection to
`AppChainStoragePaths.resolve` — refuse startup with a migrate-or-configure message when
`<rocksPath>/app-chain/<chainId>/CURRENT` exists and the new root is empty. Mirror it in `cluster.sh`
preflight and keep the legacy glob in `appchain_state_present()` so the retained-state marker still
fires. Write the owning ADR (or an ADR-022 §14 amendment) recording the compatibility rule. Add the
upgrade-path test that the rewritten launcher tests currently hide.

### Wave 1 — Adversarial safety (gates any non-trusted exposure)

**W1-1. Framework-wide no-throw apply contract with quota-aware emission** (effort L).
*This is the highest-value item in the report* — it closes T1 wholesale (AC-01, AC-05, AC-08, plus
the evidence notification burst, bridge-envelope throw, kv-registry poison, and composite validate
isolation).
1. Add `AppEffectEmitter.tryEmit(...)` returning `Optional<EffectId>` plus `remainingQuota()` to
   core-api, so machines implement skip/defer instead of racing the cap via exceptions. Migrate
   approvals on-approved emission and evidence direct-result notification onto it.
2. EUTxO: enforce input distinctness and compute conservation over a **set** in `transition()`;
   convert the per-address cap and bridge-envelope validity from apply-time throws into
   deterministic REJECTED results; implement `validateForBlock` as a real stateful pre-check.
3. Add a framework backstop in `FxKernel`/`AppChainEngine`: any exception escaping `apply()` for an
   admitted message becomes a deterministic per-message failure record (identical on all nodes)
   rather than an engine abort, with a loud metric. **Also remove the poison message from the pool on
   propose failure** — the non-destructive-snapshot behaviour is what turns one bad message into a
   renewable stall.
4. Extend `StateMachineConformance` with an adversarial suite: duplicate-input tx, address-bound
   overflow, effect-cap flood, oversized kv entry — asserting REJECTED, never thrown. Gate it in the
   plugin-conformance CI run.

**W1-2. Durability audit: fsync policy + vote-lock GC** (AC-02, effort M).
Inventory every `WriteOptions` site in `AppLedgerStore`; tag each key family consensus-critical
(vote locks, `member_epochs`, `gov_pending` → `setSync(true)`) or reconstructible (cursors, anchor
markers), and encode the rule in a single `writeOptionsFor(KeyFamily)` helper so future writes cannot
pick wrong. Persist lock + envelope in one synced batch that completes **before** the vote broadcast;
measure the latency cost on the serial event loop. Add vote-lock GC below the finalized tip to the
retention tick. Crash-safety test: kill -9 between lock write and broadcast, restart, assert the node
re-sends the same vote and never equivocates.

**W1-3. Evidence release owner binding and approval-scope completion** (AC-06, AC-07, effort M).
Add the executor/owner public key to the release command (frozen-codec bump with an explicit version
gate) and require `sender == bound executor` at apply time. Extend the stock release approval
preimage to the full command. Emit the intended executor and approved doc-trail binding in
`VerifiedEvidence`/domain-API JSON so off-chain auditors can detect capture attempts retroactively.
Adversarial tests: a second member front-running an approved release must reject; a mutated doc-trail
under a valid approval must reject.

**W1-4. Secret and config hygiene pass** (effort S). Redact the Ed25519 seed from
`AppChainConfig`/`AnchorConfig` `toString` (print a fingerprint) with a test asserting the hex seed
never appears; enforce `defaultTtlSeconds <= maxTtlSeconds` and `Math.toIntExact` narrowing at parse
time; add length assertions to `FinalityCert` constructors; scope
`zeroj.allowInsecureTrustedSetup` to the ceremony call; fix the
`EffectExecutorOperationsTracker.snapshot()` TOCTOU with a consistent read.

**W1-5. Deployment key hygiene** (AC-11, AC-12, effort S). Honor `?api=` only for same-origin or
relative paths and bind the stored console key to the origin it was saved with (plus a vitest pinning
that no `X-API-Key` crosses origins). On the cluster join/resume path, validate the derived anchor
fingerprint against the retained identity marker and replicate the `cmd_start` public-network guard.
Require `YANO_CLUSTER_API_KEY` off devnet, mirroring the anchor-key rule.

**W1-6. ZK canonicalization** (AC-09, effort S). Enforce fixed-width canonical encoding for all
member-supplied field elements (nullifier and public inputs) at **decode** time so numerically equal
values cannot produce distinct message bytes, and add `StateMachineConformance` determinism/replay
tests for zk-gate, zk-membership and credential-registry — including a non-canonical-encoding corpus.

### Wave 2 — Silent-fork elimination and correctness of the developer contract

**W2-1. Consensus-profile commitment v2** (AC-04 + stdlib settings drift, effort M).
Extend the height-1 marker to cover `membership.mode`, `membership.approval-window-blocks`,
`sequencer.window-slots`/`lookback-windows`, the `observers.*` topology, and a canonical digest of
every machine's consensus-affecting settings. Generalize `CompositeProfileGovernanceRuntime.verifyConfig`
into a core-api helper (`ConsensusSettingsDigest.of(settings)`) that any `AppStateMachine` can opt
into, and wire the stdlib machines through it. Promote the consensus-shared extension keys
(`sequencer.mode`, `membership.mode`, `effects.executors.*`) from PARTIAL dynamic namespaces to
declared FULL properties so the DX validator can hard-fail typos. Add the two-node
replay-divergence harness: same block sequence, different settings maps → identical roots or startup
failure.

**W2-2. Proven-absence verified reads** (AC-10, effort M). `ProofVerifier.verifyEither(Proof)`
dispatching on `valueHex`; a sealed `VerifiedResult<T>` (`VerifiedPresent`/`VerifiedAbsent`) in
`StdlibAppChainClient` and `AppChainTemplate`; tests for absent key → verified exclusion, mutated
exclusion proof → failure. While in the client, route `messages()/tip()/block()/effects()/status()`
through the hardened `sendBounded` path so bounded parsing covers every endpoint.

**W2-3. Codec determinism conformance** (effort M). Pin `JacksonCborCodec` (and the client
`CborCodec`) to an explicit module set — no classpath auto-discovery — with locked serialization
features and a documented determinism contract. Extract a shared `CanonicalDecode.strict(...)` helper
and migrate `AppBlockCodec`, `EffectRecord` and `FxResultBody` (fixing its missing equality check)
onto it. Add jqwik property tests over the frozen codecs and a two-classloader determinism test.
Extend the plugin-conformance fixture with a **deliberately nondeterministic** `apply()` and assert
the harness fails it — today the fixture proves catalog/TCCL mediation but never proves the
determinism gate works.

**W2-4. State-format versioning and migration tooling** (effort L, closes DX-006 / ADR-008 I4.5).
`AppStateMachine.stateVersion()` persisted beside the consensus-profile marker and fail-fast on
mismatch with migration diagnostics; an `appchain migrate --from <old-root> --to <new-root>` devtools
command that relocates the ledger and verifies tip height + state root before and after, emitting a
receipt. This is what makes retained-state upgrades safe in general, not just for the current
relocation.

**W2-5. Governance liveness rescue paths** (effort L). Threshold cancellation/supersession of
STAGING composite proposals (with proposal age/expiry in `operationalStatus` so a lockout is
visible); epoch-bound headroom validated at proposal admission instead of activation; role-workflow
administrators resolved from the **current** membership epoch (or a governed admin-set mutation) with
the resolution mode committed into `configurationId`; indexed deadline-bucket expiry sweeps replacing
per-id poke messages; paged queries for pending proposals and governance mutations. Fold the AC-03
same-block activation fix in here with its permutation property test.

### Wave 3 — Operability and performance

**W3-1. Consistent snapshots under load + testkit restart support** (effort M). Derive the manifest
from inside the checkpoint (open read-only) so it cannot race commits; add the
commit-while-snapshotting integration test. Add `AppChainCluster.restartNode(i)` preserving the
ledger dir, an explicit port allocator, JUnit-managed temp-dir cleanup, and propagated bind failures —
this is what finally makes restart/replay (the project's self-declared most dangerous area) directly
testable. Wire both into the APP-009 durability CI set.

**W3-2. Streaming pipeline reliability** (effort M). `SubscriptionListener` (onConnect, onError with
HTTP status, onDroppedMessage) plus configurable backoff; advance the cursor only **after** a
successful `consumer.accept`; close the response stream on non-200; treat 401/403 as terminal; emit
SSE `id:` and honor `Last-Event-ID` so the server drives resume. On the Kafka side: a per-block dedup
header (height + state root), bounded backoff with an attempt cap, and a visible FAILED health state
for definitively non-retryable errors.

**W3-3. EUTxO indexer correctness and catch-up performance** (effort M). Project withdrawal status
from the journal entry's own height rather than replaying the tip snapshot; on rollback, rewind status
transitions above the rollback point (with the post-rollback conformance test). Convert bridge-history
projection to incremental cursor-driven reads. Add bounded backoff plus a health gauge to the drain
loop. Separate or serialize the reader/writer JDBC connections and document the contract.

**W3-4. Catalog provenance enforcement** (effort S). Let `PluginCatalogInspectionPolicy` carry pinned
sha256 digests or a signature-verification hook, so the operator-facing allow-list binds identity to
bytes rather than to a self-asserted string.

### Standing program items surfaced by the ADR review

- **APP-009 (P0)** — run the CI certification pass (manual scope=all, retargeted-PR run, the four
  Phase-1.6 cluster durability suites, CDDL cross-validation) before the next "implemented" ADR
  lands. It is the single item that converts every later status claim into retained evidence.
- **NODE-002 (P1)** — the first-boot L1 chain-sync/header-continuity wedge bites every fresh follower
  join, including showcase governed-join demos.
- **CON-006 (P1)** — operator runbook for split-vote resolution, governed break-glass, and
  sub-threshold anchor pause; a stated precondition for recommending rotating/governed as defaults,
  which ADR-023 now demos to new users.
- **DX-001/DX-002 (P1)** — API-key scopes + privileged-operation audit logging, and one
  production-shaped KMS/HSM `SignerProvider`.
- **FX-002 / ADR-010.2 (P0 before funds)** — production Cardano transaction safety (reconciliation,
  network/address checks, custody, limits); gates real-value effects and the entire ADR-012 oracle
  program.
- **FX-001 (ADR-010.1 D5)** — framework effect-setting/codec epochs; today v1 `effects.*` consensus
  settings are immutable for a chain's lifetime, a hard ceiling for any long-lived deployment.
- **L1-005/L1-006** — reorg-safe durable pending-observation queue and proposer-offline injection
  retry (the two known lossy observation windows).
- **DOC-002** — refresh `open_item.md` §10 through ADR-023 + dx/0001 + the utxo series, and strike
  the shipped rows in `pending-tasks.md`.

---

## 6. Readiness report

### 6.1 Per-subsystem readiness

| Subsystem | Level | Blocking gaps |
|-----------|-------|---------------|
| Core SPI + config | **Beta** | Default-codec determinism footgun; secret-bearing config `toString`; tracker TOCTOU |
| Runtime host | **Beta** | Vote-lock durability; same-block governance composition; approval window outside profile; storage migration; snapshot-under-load |
| State machines | **Beta** | Effect-cap poison path; no on-chain commitment of machine settings; governance dead-ends; no property/fuzz tests |
| Role workflow + evidence | **Beta** | Executor binding + approval scope (both HIGH); notification quota stall; genesis-pinned admins |
| EUTxO extension | **Beta** | Duplicate inputs (CRITICAL); address-cap halt; indexer rollback divergence |
| Connector plugins | **Beta** | ZK nullifier bypass; no ZK determinism tests; catalog provenance; Kafka sink silent block |
| Client + devtools | **Beta** | Exclusion proofs in both typed facades; SSE failure hiding; broken zeroj compose recipe; testkit hygiene |
| App deployment | **Beta** | Storage migration; join-path anchor key; console key origin; public-network API key rule |
| ADR conformance | **Beta** | ADR-023 + showcase untracked; unowned storage change; stale tracker; APP-009 never run |

Every subsystem is beta, and none is alpha — the engineering discipline is uniformly high. But no
subsystem is production-ready, and the reasons rhyme: adversarial inputs that halt rather than reject,
consensus-affecting settings outside the commitment, and recovery paths that need an operator.

### 6.2 Go / no-go by posture

**Ready now — devnet, showcase, internal evaluation.** The demo and cluster tooling is engineered far
past demo grade (durable hard-link identity markers, argv-validated PID lifecycle, fail-closed API
filtering, dual-implementation conformance-tested on-chain anchor validator), the happy paths are
heavily tested, and the regression skills exercise multi-node rotation, governed membership, script
anchors and extensions end to end.

**Ready after Wave 1 — consortium pilot with trusted members, no real value at stake.**
Exit criteria: W0-2, W1-1 (at minimum the EUTxO fixes and the framework no-throw backstop), W1-2,
W1-3, W1-5, plus W1-4/W1-6 if the ZK or standalone-approvals profiles are in scope.

**Not ready — public or adversarial exposure.** T1 hands any authenticated submitter a renewable
chain halt for the cost of one zero-fee message, and the EUTxO address trap is *permanent* once set.
Exit criteria: all of Wave 1, plus W2-1 (silent forks become fail-stops), W2-3 (codec determinism),
and the adversarial conformance suite running in CI.

**Not ready — real funds.** Additional exit criteria: FX-002/ADR-010.2 transaction safety, DX-001/DX-002
(scoped keys + audit logging + a real KMS/HSM signer), CON-006 operator runbook, APP-009 certification,
and W2-4 state-format versioning so a retained-value ledger can survive an upgrade.

### 6.3 What would most change this assessment

Three measurements, in order of information value:

1. **Run the adversarial conformance suite from W1-1 against every shipped machine.** The reviews
   found four apply-time poison paths by reading; a harness will find the rest, and the absence of one
   is why they survived this long.
2. **Run APP-009.** Every "implemented" claim from ADR-015 onward rests on locally collected,
   unretained evidence. Until CI holds it, the readiness of the *record* is unverifiable independently
   of the readiness of the *code*.
3. **A power-loss test on a live cluster** (kill the VM, not the process) — the vote-lock finding is
   the only one in this report whose failure mode is a permanent safety violation rather than a
   liveness stall or an integrity gap.

---

## 7. New reusable components

Fourteen proposals, all checked for feasibility against the *actual* SPI. Where a design hits an SPI
limit, that limit is called out as a platform ask rather than hand-waved.

### 7.1 New reusable state machines

**S1. Escrow / conditional settlement** (effort M). Multi-party escrow over internal balances with
deterministic timeout and optional L1 payout. State `e/<escrowId>` plus a height-bucketed expiry index
`ex/<deadlineHeight>/<escrowId>` (needed because `AppStateReader` has no prefix scan — the machine
maintains its own due-index). Commands OPEN/RELEASE/REFUND/DISPUTE; each `apply()` first sweeps the
current height's bucket to auto-refund expired escrows. RELEASE with a payout address emits
`cardano.payment` with `ResultPolicy.CHAIN`; `onEffectResult` transitions SETTLED or
REFUNDED_ON_CHAIN_FAIL. *Value:* the canonical trust-minimized B2B primitive, and the first stdlib
machine to exercise the full ADR-010 emit → CHAIN result → compensation loop as a reference.

**S2. Sealed-bid auction** (effort M). Commit-reveal (Vickrey or first-price) settled in internal
balances, with phases gated by **block height only**. Bonds escrowed at commit, non-revealers slashed
deterministically to a configured sink, FINALIZE idempotent and callable by anyone after
`revealEndHeight`. `validateForBlock` drops stale-phase bids cheaply at admission while `apply()`
re-checks authoritatively. *Value:* commit-reveal is honest on a permissioned chain because ordering
is certified; the slashing/refund edge cases are exactly what every team gets wrong.

**S3. Weighted governance / voting** (effort M). Proposals with per-member or balance-weighted voting,
quorum, and height-bounded windows **pinned to the membership epoch at proposal creation** — which
prevents mid-vote membership-churn attacks. Tallies maintained incrementally on each VOTE (read old,
subtract, add) so no iteration is needed. EXECUTE kinds: WEBHOOK/CARDANO emit effects, PARAM writes to
a `g/cfg/` namespace sibling machines read, TEXT is record-only. *Value:* every consortium chain needs
its own decision layer without touching framework membership; complements ADR-008.3 rather than
duplicating it.

**S4. Timelock scheduler** (effort M). Cron-for-appchains: tasks fire at a future app height or L1
slot, with per-height due buckets and an explicit carry pointer for overflow
(`scheduler.max-fires-per-block` defers deterministically rather than unbounding block work). Slot
firing uses `block.l1Slot()` with a persisted `lastSlot` so the window is identical on every node;
firing order within a block is bucket-ordinal order. *Value:* escrow timeouts, recurring payouts,
credential expiry sweeps and report generation are all asked for constantly, and the tricky part
(deterministic time without clocks, bounded per-block work) is exactly what should be centralized
rather than re-derived subtly-wrong per team.

**S5. Rate-limiter / quota guard** (effort S). Token-bucket and fixed-window quotas per subject,
refilled by **block-height deltas** with lazy on-touch computation (`tokens = min(capacity, tokens +
(h - lastHeight) * refillPerBlock)`) so no sweeps are needed. Usable standalone or as a same-block
guard inside a composite. Notably, this is the first stdlib demonstration of `validateForBlock` as a
**stateful admission filter** — which is precisely what it was added for and what AC-01/AC-08 show is
currently unused. *Value:* per-use billing and per-tenant fairness need consensus-identical rate
decisions; a wall-clock limiter would fork the chain.

**S6. Supply-chain custody** (effort M). Asset custody with **two-phase handover** (OFFER/ACCEPT —
no unilateral push, which prevents custody dumping), role-gated checkpoints resolved against the
`appchain-role-workflow` actor registry, IPFS-pinned document hashes via the existing connector, and
`~l1` observation binding for on-chain events. Every event individually MPF-provable for third-party
verification via evidence bundles. *Value:* the most-requested enterprise consortium use case, and it
showcases roles + IPFS + L1 observations + proofs + evidence export in one machine.

### 7.2 New consensus / sequencer profiles

**C1. Rotating sequencer with liveness fallback** (effort S — highest value-per-effort in this
section). The existing rotating schedule plus deterministic takeover: if the scheduled primary is
silent for `fallback.grace-slots` L1 slots into its window, backup `members[(w+1) mod n]` becomes
eligible, then `(w+2)`, each after a further grace interval. `checkProposal` returns **DEFER**, not
REJECT, when the local L1 clock hasn't reached the backup's grace point — DEFER is exactly the SPI's
clock-skew escape hatch and prevents fail-closed rejects from splitting the cluster. Safety is free:
two simultaneous proposers cannot double-finalize because threshold certs and one-vote-per-height
locks are framework-enforced; the worst case is a wasted proposal. *Value:* the single biggest
operational weakness of plain rotation is a stalled chain when one member is down. Pure
`SequencerMode` plugin, zero framework changes, zero new trust assumptions.

**C2. Weight-table sequencer (stake-weighted rotation)** (effort M). Proposer windows allocated
proportionally to per-member weights via largest-remainder expansion seeded by the chain-id hash, so
the window→member map is stable and identical everywhere. **Phase 1 ships today**: weights come from
chain settings versioned with membership epochs, so a weight change follows the same governed path as
membership rotation and all members switch atomically at an epoch boundary. Document loudly that
weights shape **liveness share, not finality power** — a high-weight member still casts exactly one
vote toward certs. **Phase 2 is a platform ask** (see §7.4).

**C3. L1-inbox based sequencing** (effort L). Censorship resistance: users force-include commands via
an L1 address/metadata label, and the app-chain must incorporate them in `(slot, txIndex)` order,
stability-gated with the same k-confirmation rule as script anchors. Two of the three pieces work on
today's SPI — an `inbox` L1Observer whose claims followers already recompute and verify fail-closed,
and a composite decorator that applies `~l1/inbox` observations into the target machine's command
stream. **The honest gap is omission**: verification proves included observations are correct, but
nothing proves a proposer didn't skip inbox transactions. Ship an off-consensus completeness watchdog
first (compare local L1 view against included observations, expose lag/omission metrics), and file the
mandatory-completeness follower hook as a sub-ADR. *Value:* "what if the sequencers ignore me?" is the
defining credibility question for any L2 pitch, and this answers it with Cardano-grade guarantees
while making the anchoring story bidirectional.

**C4. Reputation-aware rotation** (effort L). Deterministically demote chronically offline proposers
using data every node already has — the finalized chain itself. Whether the scheduled primary actually
proposed is computable from `block.proposer` versus the schedule, identically everywhere, with no
gossip. The mode keeps a chain-derived tally rebuilt on start (a pure function of the finalized
prefix, so restart-safe) and demotes to backup-only for `reputation.penalty-windows`, then
auto-recovers. Schedule at height h must depend only on blocks `<= h - lag` so all nodes agree before
proposing. Members are never *removed* — that stays governance's job. *Value:* fills the gap between
"fixed" and "governance-managed": a member flaky for a weekend shouldn't need a governance action to
stop stalling 1-of-n windows.

### 7.3 New connector / infrastructure plugins

**P1. Postgres projection sink (CDC-style)** (effort M — highest integration leverage). A
`FinalizedStreamSink` projecting finalized blocks into Postgres in the yaci-store mold: core tables
(app_block, app_message, effect_record, anchor) plus machine-aware projection tables decoded via the
stdlib contracts through a pluggable `ProjectionHandler` registry, so custom machines ship their own
mappers. One transaction per block with `ON CONFLICT DO NOTHING` keyed by `(chain_id, height,
ordinal)` makes it idempotent under the framework's at-least-once redelivery; the cursor advances only
on commit success. Optional `NOTIFY` for downstream CDC, plus `--from-height` backfill using
`legacyCursorKey()` for in-place migration from an earlier sink. *Value:* every enterprise integration
conversation ends at "can I query it in SQL?" — this turns app-chains into ordinary relational data
sitting next to the MPF proofs.

**P2. REST oracle effect executor** (effort M). Turn the ADR-010 CHAIN loop into a general HTTPS
oracle: a machine emits `oracle.fetch` with `ResultPolicy.CHAIN`; the executor (post-finality,
outside determinism — exactly where I/O belongs) calls an **allow-listed** endpoint, canonicalizes the
JSON response to CBOR per a declared schema, and submits the attested `~fx/result`. Timeouts degrade
to the deterministic EXPIRED transition, so the machine always closes. Hardening that matters:
payloads select a `sourceId`, never a URL (prevents SSRF via chain data); API keys live only in local
executor config, never in payloads that replicate to every member; response-size caps enforced before
submission. *Value:* external data is the #1 blocker for real app logic, and this makes "oracle" a
config entry. Flag the M-of-N independent-fetch quorum as a v2 platform question if the current
interpreter accepts a single member's attestation.

**P3. Webhook sink v2 — signed, filtered, transformed, DLQ** (effort S). HMAC-SHA256 signatures over
the canonical body with key-id header and rotation; declarative filtering (topics, machine ids, key
prefixes) and payload shaping so receivers get decoded events rather than raw CBOR; bounded backoff
then dead-letter with an explicit strict-ordering (default) versus skip-with-DLQ mode; per-endpoint
fan-out via distinct sink ids; `legacyCursorKey()` upgrade from v1 without replaying history.
Idempotency key `(chainId, height, ordinal)`. *Value:* webhooks are how most business systems will
consume app-chain events, and unsigned unfiltered block dumps do not pass an enterprise security
review.

**P4. Cold archival + pruning connector** (effort L). Three parts: an **Archiver** sink writing
canonical-CBOR block segments plus periodic state-proof snapshots to S3, each sealed with a manifest
(chainId, height range, segment hash, first/last block hash, state roots) so a cold segment is
independently verifiable against the L1 anchor — the same verification story as evidence bundles; a
**Verifier** CLI cross-checking manifests against anchors; and a **Pruner**. Ship parts 1–2 now (pure
sink, immediately useful for DR and compliance) with the manifest format fixed; **part 3 is a platform
ask** — pruning needs a store API and archive-aware catch-up, with the never-prune-above-anchor and
never-prune-before-verified rules spelled out. *Value:* long-running chains grow unboundedly, and
verifiable cold segments let an auditor check two-year-old history against the L1 anchor without a
node.

### 7.4 SPI gaps these designs would hit (platform asks)

Worth filing as sub-ADRs, since three separate designs converge on the first one:

1. **`SequencerContext` has no committed-state reader.** Both C2 phase 2 (true stake-weighting from
   the balances machine) and C4 (health scores in app state) need a deterministic read of committed
   state at a defined height (e.g. last anchored height, or `H - k`). Today `SequencerContext` exposes
   only `membersAt`, settings and `currentL1Slot`. Proposed:
   `SequencerContext.committedState(long height)` with an explicit snapshot-height rule.
2. **No mandatory-observer completeness check in follower proposal verification.** C3 needs a rule
   like "a proposal whose `l1Slot` advances past stable slot *s* must include all observations from
   observers marked `mandatory=true` up to *s*", with DoS bounds (max inbox messages per block,
   carry-over rule).
3. **No prefix/range scan on `AppStateReader`.** S1, S4 and S6 all work around it by maintaining
   explicit bucket indexes. That is the right answer for determinism and bounded work, but it should be
   a documented *pattern* (with a stdlib helper for bucket-index maintenance) rather than something
   each machine re-invents.
4. **No pruning API or archive-backed catch-up block source.** Blocks P4 part 3.
5. **No quota-aware emission API.** W1-1's `tryEmit`/`remainingQuota` is a prerequisite for any machine
   that emits effects proportional to input volume — S1, S3, S4 and P2 all would.

---

## 8. Appendix — method and confidence

**Agents:** 21 total. Nine subsystem reviewers (read-only, each scoped to a module set with a shared
review-dimension brief covering correctness, determinism, rollback/replay safety, concurrency,
resource leaks, security, SPI design, error handling, and test coverage). Ten adversarial verifiers,
each given one finding and instructed to refute it by tracing callers, upstream validation, locks,
default-disabled configuration and existing tests. Two design agents for the enhancement and
new-component roadmaps, both grounded by reading the actual SPI first.

**Confidence.** The ten verified findings (§3) are high-confidence: each names a concrete execution
path with file and line, and the verifier explicitly searched for a guard and reported not finding
one. Three verifiers *corrected the mechanism* while confirming the substance — AC-01 and AC-08 are
proposer-side stalls rather than certified-block forks (which changes the fix: the pool's
non-destructive drain is part of the bug), and AC-09's amplification is unbounded rather than ×2.
That kind of correction is the signal that the refutation pass did real work.

The 55 unverified findings (§4) are reviewer-reported with file, line and failure scenario, but have
not been through refutation — treat MEDIUM and LOW entries as leads to confirm during fix work rather
than as established defects. Notably, several LOW findings are *enabling conditions* for the HIGH
ones (e.g. `validateForBlock` ignoring committed state enables both EUTxO halts), so they are worth
more than their severity label suggests.

**What this review did not do:** it did not execute the test suites, run a cluster, or attempt any of
the exploits. Every conclusion is from static reading of the working tree. The three measurements in
§6.3 are the recommended empirical follow-up.
