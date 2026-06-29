# Review: ADR-NET-008 — Pluggable Upstream Selection and Chain Selection

**Reviewer:** Claude (grounded against the current `runtime/` code and ADRs 009 / 017 / network-002)
**Date:** 2026-06-28
**Reviewed doc:** `adr/network/008-pluggable-upstream-selection-and-chain-selection.md`
**Verdict:** Direction is right; accept the core decision (Option D). But the doc needs three substantive fixes before it should drive implementation: (1) resolve duplication/authority vs ADR-017/009, (2) collapse the 5-mode enum into composable policies, and (3) confront the security reality that multi-peer chain selection *without* consensus validation is weaker, not stronger, than trusted-single.

---

## TL;DR

| Area | Assessment |
|---|---|
| Core decision (UpstreamController above single-peer `PeerSession`) | ✅ Correct and well-matched to the actual code |
| Backward-compat (synthesize `trusted-single` from `yano.remote.*`) | ✅ Sound |
| "Only selected headers mutate canonical state" invariant | ✅ The right correctness property |
| 5 discrete modes + 5 controller classes | ⚠️ Over-modeled; prefer composition |
| Relationship to ADR-017 / ADR-009 | ⚠️ Duplicates & renames existing designs; authority unclear |
| Multi-peer chain selection security | ❌ Underplayed; `static-multi` + `structural` is grief-attackable |
| Chain-selection rule for a *relay* | ⚠️ `longest + keep-current` diverges from Ouroboros; relays can desync |
| Chain-selection rollback ↔ `LedgerApplyProcessor` generations | ⚠️ Not mapped; this is the hardest concurrency surface |
| Candidate store placement & bulk-sync vs tip-following | ⚠️ Unbounded growth risk; missing the bulk/tip distinction |

The architectural preconditions are genuinely in place: `PeerSession` is already a single-peer worker, and `LedgerApplyProcessor` already provides generation fencing and a single-writer apply boundary. That makes Option D realistic rather than aspirational.

---

## 1. What's right — keep these

- **Option D is the correct choice.** Putting the strategy boundary *above* `PeerSession` and keeping `PeerSession` / Yaci `PeerClient` strictly single-peer matches the existing code exactly (`SyncSubsystem` owns one `PeerSession`, supervised by `PeerSessionSupervisor`). Options A–C are correctly rejected.
- **The canonical-writer invariant is the heart of correctness.** "Unselected peer headers must never touch `ChainState`" is exactly the property that keeps multi-peer safe, and the doc names it repeatedly. Good.
- **Validation as a deferred seam, not a Phase-1 claim.** Honest. The explicit `none | structural | consensus-lite | full` ladder and "fail fast on unsupported full" is the right posture.
- **Compatibility story is concrete.** Mapping `yano.remote.*` → synthetic `trusted-single` peer, and not requiring candidate/peer stores in `trusted-single`, is the correct migration shape. (Verified: config is bound via `@ConfigProperty` in `app/.../YanoProducer.java`, so Phase 1's "extend binding in YanoProducer" is accurate.)
- **Phasing is incremental and each phase is gated on "trusted-single still works."** Right discipline for a node whose existing reward/ledger correctness must not regress.

---

## 2. Significant concerns (prioritized)

### 2.1 Resolve duplication and authority vs ADR-017 / ADR-009 (governance, do this first)

ADR-017 and ADR-009 **already specify** most of what NET-008 introduces, under different names:

| NET-008 | Already in ADR-017 / 009 |
|---|---|
| `CanonicalSyncWriter` | `CanonicalApplier` (017) |
| "Header Intake" / `CandidateHeaderStore` | `HeaderFanIn` (009, 017) |
| `BodyFetchScheduler` | `BodyFetchScheduler` (009, 017) — same name |
| `PeerGovernor`, `PeerStore`, `PeerPool` | same names (009, 017) |
| `ChainSelectionStrategy` + `LongestCandidateWithinRollbackWindow` | same strategy + same name (017) |

NET-008 says it "narrows the decision to the runtime boundary," but in practice it **re-specifies the whole architecture** with new names. That will produce two failure modes during implementation: (a) reviewers won't know which ADR is authoritative, and (b) the code will mix `CanonicalApplier` vs `CanonicalSyncWriter`, `HeaderFanIn` vs "Header Intake," etc.

**Recommendation — pick one:**
- **(A) Make NET-008 authoritative for networking** and mark the networking sections of ADR-017/009 as *Superseded by NET-008*, with a one-line pointer. Then standardize names *once* in NET-008.
- **(B) Make NET-008 genuinely thin**: define only the runtime integration boundary (the `UpstreamController` interface, `SyncSubsystem` delegation, config schema, and compatibility rules) and **defer component design to ADR-017** by reference, deleting the duplicated `ChainSelectionStrategy`/`CandidateHeaderStore`/governor specs from this doc.

I lean (A): NET-008 is the more concrete and better-scoped of the three. Either way, do a single naming pass and reconcile `CanonicalApplier`/`CanonicalSyncWriter` and `HeaderFanIn`/"Header Intake."

### 2.2 Collapse the 5 modes into composable axes (the "keep many options" goal argues *against* a mode enum)

The five modes (`trusted-single`, `trusted-failover`, `static-multi`, `rooted-relay`, `p2p-relay`) and five controllers (`TrustedSingle…`→`P2pRelay…UpstreamController`) are not five independent things — they are points on three orthogonal axes:

- **Peer source:** static list ↔ roots + discovery
- **Active peers / chain comparison:** single-active (failover) ↔ multi-candidate (selection)
- **Validation level:** none ↔ structural ↔ consensus-lite ↔ full

Five discrete modes create a combinatorial straitjacket and five controller classes that will share most of their code. The stated goal — "keeping many options" — is *better* served by composition than by a fixed enum. Concretely:

- Implement **two** controllers: `SingleActiveUpstreamController` (covers `trusted-single` and `trusted-failover`) and `MultiCandidateUpstreamController` (covers `static-multi`, `rooted-relay`, `p2p-relay`), each parameterized by `PeerSource`, `ChainSelectionStrategy`, `ValidationLevel`, and an optional `Governor`.
- Keep `mode:` in config as a **preset** that expands into a policy bundle (great UX, prevents nonsensical combos), but don't let it fan out into five class hierarchies internally.

This keeps the user-facing simplicity the doc wants while avoiding five near-duplicate implementations. `trusted-single` is then literally "static source, 1 active peer, trust-first selection, no governor, validation=none."

### 2.3 Multi-peer chain selection *without* validation is weaker than trusted-single — say so loudly

This is the most important technical point and the doc currently soft-pedals it.

In `trusted-single`, a bad chain is impossible by assumption (one trusted source). The moment you enter `static-multi` with `selection.policy: longest-within-rollback-window` and `validation.level: structural`, **a single malicious or buggy peer can advertise a structurally-valid-but-bogus longer chain** (fake headers with no real VRF/KES/opcert — `structural` doesn't check those) and the longest-chain rule will *adopt it*, forcing a canonical rollback that evicts the honest chain. The "must intersect within rollback window" rule only bounds the *depth* of the damage (≤ k); it does not prevent poisoning or rollback-churn griefing.

So the security ordering is **not** monotonic in "number of peers." `static-multi + structural` is arguably *less* safe than `trusted-single`. The doc lists "policy requiring agreement from multiple peers" as a *future* strategy — for untrusted multi-peer it should be a **precondition**, not a future nicety.

**Recommendations:**
- Make the doc state explicitly: *chain selection across untrusted peers requires at least `consensus-lite` validation, or a quorum/agreement rule before any fork that rolls back the current selected chain.*
- Gate `static-multi`+ on `validation.level >= consensus-lite` **OR** on `tie-break`/adoption requiring N-peer agreement for rollbacks.
- Specify how per-peer `trust:` interacts with selection. Right now `trust: trusted|...` exists in the peer config but the longest-chain rule ignores it. Does a `trusted` peer outvote an untrusted one? Can an untrusted peer ever trigger a rollback of a chain a trusted peer served? This must be answered.

### 2.4 `longest + keep-current` is wrong for a *relay* that must track the network's canonical chain

Ouroboros chain selection is **not** pure longest-chain. Honest nodes break length ties deterministically (by block number, then operational-certificate issue number / VRF leader value), which is what makes all honest nodes *converge* on one chain. The doc's `LongestCandidateWithinRollbackWindow` with `tie-break: keep-current` means:

- Two relays that start on genuinely equal-length competing forks will **keep their respective current forks indefinitely** → they diverge and serve *different* chains to their downstream consumers.

For HA failover (`trusted-failover`, one active peer, no cross-peer comparison) `keep-current` is fine. For a *relay* whose whole job is to faithfully follow and re-serve the network's canonical chain, `keep-current` can leave it stuck on a minority fork.

**Recommendation:** Document that the selection rule is a *trust-assumption simplification* of Ouroboros, acceptable only when peers are trusted/agree. For any mode intended to track the public network, the tie-break must move toward the consensus rule (block number → opcert issue number → VRF), not `keep-current`. At minimum, flag this as a correctness limitation, not just a "churn" knob.

### 2.5 Map chain-selection rollback onto the existing `LedgerApplyProcessor` generation model

The current `LedgerApplyProcessor` already enforces ordered apply + generation fencing (active/closed/failed, `SKIPPED_STALE`, `closeGenerationAndReadRecoveryPoint`). This is the hardest concurrency surface and the doc's `CanonicalSyncWriter` description doesn't connect to it. When chain selection adopts a different candidate and triggers a canonical rollback + replay, in-flight body applies from the *old* selected chain must be fenced — that is *exactly* what generations do today.

**Recommendation:** Add an explicit invariant: *a chain-selection-driven rollback closes the current apply generation, drains in-flight work, reads the recovery point, then opens a new generation for the adopted chain — using the same mechanism as peer-driven rollback recovery.* This turns Phase 5's "one canonical rollback and replay" from a wish into a concrete, already-supported operation, and prevents a whole class of races.

### 2.6 Candidate store: bound it, separate it, and distinguish bulk-sync from tip-following

Two gaps:

1. **Placement (the doc's own open question).** Strong recommendation: keep `CandidateHeaderStore` in a **separate, ideally ephemeral, peer-state store — not the canonical chain RocksDB.** This project treats canonical-DB rollback-safety as sacred (see CLAUDE.md). Mixing non-canonical candidate rows into the canonical DB invites accidental reads treating candidate data as canonical and complicates the delta/rollback audit. Physical separation is cheap insurance.
2. **Bulk-sync vs tip-following is missing entirely.** Multi-peer candidate comparison is only meaningful near the tip. During initial sync / long catch-up, the "rollback horizon" is far behind the streaming tip, so per-peer candidate headers can accumulate to ≫ k×N before pruning. Real nodes bulk-sync bodies from one fast peer and only switch to multi-peer candidate comparison once "caught up" (within ~k of wall-clock). The doc should say: *bulk catch-up uses a single selected peer; candidate fan-in/selection engages only near the tip.* This also sidesteps the candidate-store growth problem.

---

## 3. Smaller gaps and corrections

- **Tx forwarding in multi-peer is undefined.** `UpstreamController.submitTxBytes(...)` makes sense for a single active peer; in `static-multi` there is no single active peer. Specify the target (broadcast to hot peers? forward along the selected-chain peer?). Tx *gossip* can stay deferred, but tx *forwarding* needs a defined target in every mode that's shippable.
- **Server side isn't mentioned.** A relay both ingests upstream and serves downstream. Add a one-line invariant: *the server always serves the selected/canonical chain and never candidate data.* (It does today because the server reads canonical `ChainState`; just state it so it stays true.)
- **`mode()` returns `String`.** Use an enum on the interface; keep strings only at the config edge.
- **Body-hash check is integrity, not security.** The risk table frames "wrong body from non-selected peer → hash check" as if it closes a security hole. It only proves the body matches the *already-selected* header; if the header is from a bad chain (see 2.3), the hash check doesn't help. Reword to "data integrity," and keep the security argument in the validation/selection section.
- **`header_tip` is a metadata *key*, not a column family.** Minor: the doc lists `header_tip` alongside CFs (`headers`, `slot_to_hash`, `number_by_slot`). In `DirectRocksDBChainState` it's a key in the `metadata` CF. Doesn't change the argument, but worth getting right since the doc enumerates exact structures.
- **Default rollback window (open question).** Use `k` slots scaled by active-slot coefficient `f` per network (preprod/preview/mainnet `k=2160`, so ~`2160/f` slots ≈ 43200 on mainnet `f=0.05`). The doc's example `rollback-window-slots: 4320` is ~2× k-in-blocks, not k-in-slots — pin down whether the unit is slots or blocks and derive per-network defaults from genesis rather than hardcoding.

---

## 4. Answers to the ADR's open questions

1. **CandidateHeaderStore DB location?** Separate peer-state store, ideally ephemeral/in-memory with a hard size bound. Never the canonical chain DB. (See 2.6.)
2. **Default rollback window per network?** Derive from genesis: `k` (security param) in blocks, or `~k/f` in slots. Don't hardcode; read from the already-loaded genesis config. (See §3.)
3. **Failover standby: cold or warm?** Cold by default (matches ADR-network-002's preference and avoids idle connection cost); offer warm keepalive as an opt-in for latency-sensitive indexers. Don't make warm the default.
4. **Body fan-out eligibility?** Any peer may serve a body **iff** the body hash matches the selected header (integrity is guaranteed by the hash). Restricting to selected-chain peers is an optimization, not a correctness requirement.
5. **Public API for custom `UpstreamController`?** Expose a small SPI: `UpstreamControllerFactory` resolved via `ServiceLoader` (consistent with the existing `PluginManager`/`ServiceLoader.load(NodePlugin.class, …)` pattern), keyed by mode/name, receiving a context with `ChainState`, event bus, config, and the `CanonicalSyncWriter`. Keep `CanonicalSyncWriter` itself *not* publicly extensible (single-writer invariant must hold).

---

## 5. Suggested minimal edits to the ADR

1. Add a **"Relationship to ADR-017 / ADR-009"** subsection that states authority explicitly (supersede their networking sections, or defer to them) and reconciles names in one pass.
2. Add a **"Security model"** subsection making 2.3 explicit: multi-peer + `structural` is not trustless; rollbacks across untrusted peers require `consensus-lite` or quorum.
3. Add a **"Chain selection vs Ouroboros"** note (2.4): the rule is a trust simplification; relays tracking public networks need the consensus tie-break.
4. Add a **"Sync phases"** note (2.6): single-peer bulk catch-up; candidate fan-in/selection only near tip.
5. Add a **generation-fencing invariant** for chain-selection rollback (2.5).
6. Reframe modes as presets over composable policy axes (2.2), or at least state that the five controllers share one multi-candidate core.
7. Fix the small items in §3.

---

## 6. Bottom line

The ADR makes sense and the core decision is sound — and notably, the code is already shaped to support it (single-peer `PeerSession`, single-writer fenced apply). Ship **`trusted-failover` first** (Phase 3): it delivers most of the practical relay/indexer value (HA) at the lowest risk and makes *no* chain-selection claim. Treat `static-multi` and beyond as experimental until `consensus-lite` validation (or a quorum rule) exists, because that is the line where "more peers" stops meaning "more safety." Before any of that, resolve the ADR-017/009 authority-and-naming overlap so there is one source of truth for the relay architecture.
