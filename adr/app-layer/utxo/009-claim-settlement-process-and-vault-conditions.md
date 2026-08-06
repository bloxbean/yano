# ADR-UTXO-009: Claim Settlement Process and Vault Spend Conditions

- Status: Accepted (design) — implementation planned (§11)
- Version: v1
- Date: 2026-08-05
- Owners: App-chain / EUTxO / Bridge
- Related: ADR-UTXO-008 (showcase bridge chain — the live baseline this ADR
  critiques), ADR-UTXO-006 (public-funds safety), ADR-UTXO-007
  (custody-separated settlement and permissionless fallback), the
  `eutxo-cardano-bridge` recipe's signer-mode prerequisites,
  `appchain-eutxo-bridge-onchain` (existing, unshipped Plutus validators)

## 1. Problem

Withdrawal claims are chain-committed and irrevocable, but their settlement
on the L1 is where custody, fees, concurrency, and enforcement actually
live. The live ADR-008 baseline (first walked end-to-end on preprod on
2026-08-05 with a real user wallet) surfaced concrete deficiencies worth a
deliberate design pass before hardening. This ADR captures the OPTIONS —
current and future — for (A) who builds/funds/authorizes the settlement
transaction and (B) what the vault contract itself enforces. Nothing here
changes v1 behavior yet.

## 2. Baseline today (Option A0 + V0) — what ships

**A0 — operator-builds settlement.** The operator's tooling selects vault
UTxOs (aggregating deposits as needed), pays exactly the claim amount to
the claim's committed payout address, returns the remainder to the vault
with the settlement marker datum, and pays the L1 fee from the operator's
own wallet (fixed 2026-08-05: an earlier flat 500k skim paid the fee from
VAULT funds and tipped the surplus to the operator's change — and let the
physical vault drift below the chain's ledger reserve; the vault must move
exactly the claim amount). The L2 confirmation observer reconciles the
claim to CONFIRMED after stability depth.

**V0 — native-script vault.** The vault's only on-chain rule is "the
operator signed." All amount/claim/remainder checks live off-chain in
tooling and the L2 observers — they DETECT drift (and can halt) but cannot
PREVENT a malicious or compromised operator from sweeping the vault. This
is the documented demo trust boundary: the operator key IS custody.

Properties: simplest possible flow; operator is a natural serializer (no
UTxO contention); operator needs a funded fee float; receiver pays nothing;
custody is pure operator trust.

Fee-model note: the receiver cannot simply pay the fee out of the payout,
because the claim commits an EXACT amount that reconciliation matches on
the L1 output. Receiver-paid fees require either a separate receiver input
(Option A1) or an explicit L2-side withdrawal fee field in the claim so the
committed payout already nets the fee.

## 3. Settlement process options (A)

### A1 — receiver-builds, operator co-signs (REJECTED 2026-08-06)

Decision: skip this tier and go straight to A2+A3 on V1. Its motivation
(receiver-funded fees) is subsumed by the claim-creation bounty (§6), and
its wallet-in-the-loop contention window is the worst of the options.
Retained below for the record.

The receiver constructs the settlement: vault UTxOs covering the claim PLUS
one of their own UTxOs for the fee; outputs = exact claim amount to the
payout address, continuing vault output with the marker datum, receiver's
change minus fee. Receiver signs their input (CIP-30 partial sign works);
the operator verifies and counter-signs the vault spend. Matches the bridge
recipe's "external threshold/HSM settlement signer" posture — the operator
key becomes an authorizer, never a builder, and needs no fee float.

Requirements identified in review:
- **Operator verification before co-signing** (the actual engineering): the
  tx settles a real PENDING claim, pays exactly the committed amount to the
  committed address, the continuing output returns ALL remaining vault
  value, no other vault leakage, claim not already settled.
- **Vault UTxO reservation.** The contention window spans build → human
  wallet signature → co-sign → submit (minutes). Without coordination,
  concurrent settlements race over vault inputs and force rebuild+re-sign
  loops. The co-signing operator is already the serialization point, so it
  (or the node's build endpoint) should RESERVE selected vault UTxOs per
  pending settlement with a TTL matching the signing window. Vault sharding
  (deposits staying as separate UTxOs) keeps disjoint selection possible;
  never consolidate the vault into one UTxO.

### A2 — batched settlement (operator-driven scale tier)

One settlement transaction pays SEVERAL pending claims at once. If the
operator-driven model (A0) is retained, this is its natural evolution and
resolves contention structurally: the operator drains a queue instead of
racing per-claim transactions.

Proposed mechanics:
- **Trigger policy**: settle when N claims are pending OR T seconds after
  the oldest unsettled claim (N, T operator-configured; N bounded by
  maxTxSize and outputs' min-ADA), so single withdrawals still settle
  promptly.
- **Transaction shape**: inputs = vault UTxOs covering the batch total
  (greedy aggregation) [+ one operator fee input]; outputs = one payout
  output PER claim (exact committed amount and address) + ONE continuing
  vault output carrying a batch settlement marker.
- **Batch marker / reconciliation**: today's marker datum names a single
  claim id and the observer reconciles by exact (address, amount) match —
  ambiguous when a batch contains two identical claims. The batch marker
  must carry the ORDERED list of settled claim ids, and the confirmation
  observer maps claim[i] to payout output[i] positionally. This is the one
  consensus-side change A2 needs (observer + marker ABI bump); everything
  else is tooling.
- **Atomicity**: a batch settles all-or-nothing, so the builder must
  pre-validate every claim (pending, unexpired reservation-free, within
  caps) — one bad claim must be dropped from the batch, not sink it.
- **Fees**: one L1 fee per batch, amortized. Either the operator pays it
  (simplest; float refilled from an explicit L2-side withdrawal fee field
  in claims — the fee field is the clean receiver-pays answer here, since
  per-receiver fee inputs do not compose in a shared batch), or the batch
  skims the DECLARED fee total only — never an undeclared buffer (the
  ADR-008 skim lesson, contract-enforced under V1 rule 3).
- **Showcase path**: a `settle-batch` client command (gather all PENDING
  claims → one transaction) can demonstrate the shape immediately with the
  positional marker; the observer change gates full correctness for
  duplicate (address, amount) claims and should land with it.

### A3 — permissionless proof exit (ADR-UTXO-007 end state)

No operator signature at all: the vault spend proves the claim against a
threshold-accepted L2 state root and consumes a nullifier. Settlement
becomes anyone-can-crank. Inherits the same contention shape as A1/A2
(exits still spend vault UTxOs), so reservation/batching remains relevant.

## 4. Vault spend-condition options (B)

### V0 — native script (today)

Operator signature only. Detection without prevention; demo amounts only.

### V1 — Plutus vault with enforced settlement invariants

The validators already exist unshipped in `appchain-eutxo-bridge-onchain`
(`VaultValidator`, `FederatedRootValidator`, `NullifierStateValidator`,
`ProofVaultValidator`, `DepositStagingValidator`). The vault spend must
prove ON-CHAIN:
1. **Claim authorization** — proof against a threshold-accepted state root
   (proof mode) or a federation threshold authorization (signer mode) —
   never a single key;
2. **Exact outflow** — the payout output equals the committed claim amount
   and address;
3. **Remainder preservation** — the continuing vault output keeps
   everything else (contract-enforced version of the fee-skim bug);
4. **Single settlement** — a nullifier is consumed so a claim settles
   exactly once.
With V1, the operator signature (where still present) is liveness, not
custody. Prerequisites per the recipe: reviewed contract identities,
custody review, stable L1 feed.

## 5. Composition and migration path

A0+V0 (today, demo) → A1+V0 (co-sign + reservation; custody unchanged but
fee/role model fixed) → A1/A2+V1 (contract-enforced invariants; operator =
liveness) → A3+V1 (permissionless exits). Each step is independently
useful; A1 and V1 are orthogonal and can land in either order. Vault
identity changes (V0→V1 script hash/address) are chain-config migrations
per chain, not key rollovers.

## 6. Fee model in detail (Q1 resolved direction)

**Charge the fee at CLAIM CREATION on the L2, commit it in the claim as
the EXECUTOR BOUNTY.** The claim ABI (v2) commits `{payout, bounty}`: the
withdrawer requests X, pays the governed fee f at creation, and the claim
commits payout = X − f and bounty = f. The L2 reserve decreases by payout
PLUS bounty at creation, so the physical vault and the ledger reserve stay
equal permanently. On L1, the vault script's conservation rule is: every
payout output exact; the batch's TOTAL bounty may go to one output of the
settlement executor's choosing; remainder back to the vault. One rule
serves both paths — the federation's executor collects bounties in A2,
whoever cranks collects them in A3 — so settlement incentives are uniform
and pre-funded by the withdrawer, never by the vault's other depositors.

**Schedule shape (decided): flat first, bps-ready.** The governed schedule
is `{flat, bps}` with `bps = 0` at genesis (flat 2 ADA for public demos, 0
allowed for closed demos). Claims always commit the RESOLVED lovelace
amount, so activating basis-points later is a tier-2 parameter change with
no state-machine logic change and no ABI churn.

**Genesis vs governance — both, in tiers:**
- The fee FIELD (ABI) and the fee BOUNDS (min 0, hard max — e.g. a small
  absolute cap or basis-point ceiling) are consensus-frozen in the
  immutable profile: governance can never rug withdrawals by fee.
- The EFFECTIVE fee rate is a governed L2 parameter: genesis sets the
  initial value; later changes ride a membership-threshold governance
  message (the same governed-admin machinery membership/threshold changes
  already use), take effect at a recorded height, and need no restart and
  no config migration. Static per-node config for a consensus-relevant fee
  is explicitly rejected — divergent node configs would fork validation.

## 7. A2 and A3 as contract-enforced paths — detailed exploration (Q2)

Direction: **one V1 vault, two authorization paths** on the same script and
the same nullifier machinery — batched federation settlement (A2) as the
fast path, permissionless proof exit (A3) as the fallback. This is
ADR-UTXO-007's architecture made concrete; the paths share everything
except WHO authorizes the spend.

### 7.1 Shared machinery

- **Accepted-root thread** (`FederatedRootValidator`): one UTxO holding
  `{height, stateRoot, memberSetHash, membershipEpoch, updatedAtSlot}`.
  Updates require the L2 membership threshold signature; membership
  rotation is honored via the governed epoch already tracked on-chain.
  Exits consume it as a REFERENCE INPUT — reference inputs do not consume
  UTxOs, so the root is never a contention point.
- **Nullifier shards** (`NullifierStateValidator`): k thread UTxOs, shard =
  `claimId mod k`. Datum = `{shardIndex, nullifierRoot}` — an **MPF root**
  (`mpf-blake2b256-v1`; decided 2026-08-06: MPF over JMT for the nullifier
  tries). Rationale: MPF is the proof system with a maintained,
  cost-profiled Cardano ON-CHAIN verifier (the merkle-patricia-forestry
  lineage already used by the julc anchor validators), its insertion
  proofs let the script compute post-insert roots cheaply, and the eutxo
  machine's state/claim proofs are ALREADY MPF — so claim-inclusion and
  nullifier non-membership verify through ONE verifier in the vault
  script: smaller script, one budget profile, one audit surface. JMT has
  no maintained Cardano on-chain verifier and stays an L2-side commitment
  option only. Spending a shard requires, in the same tx, a
  paired vault spend plus a NON-membership proof of each settled claimId
  and the datum's root updated by inserting them. One shard per tx.
  **No check/update lag exists**: the non-membership check and the root
  insert are one atomic L1 transaction, and two settlements of the same
  claim need the SAME shard UTxO — the ledger lets exactly one spend it,
  and the loser's rebuilt proof fails against the inserted id. Double
  settlement is prevented by UTxO exclusivity, not timeliness. (The
  accepted STATE root is the only lagging artifact; its staleness merely
  delays when a young claim becomes provable — it can never enable
  double-pay.)
  **Trie maintenance:** L1 holds ONLY the per-shard root; the script needs
  proofs, not the tree — an MPF non-membership path also lets it
  COMPUTE the post-insert root, so batch inserts verify as a proof chain
  (R0→R1→…). The full trie data is maintained off-chain: L2 nodes mirror
  it deterministically from the same L1 observations that drive claim
  status (each shard spend names its inserted ids; the shard's UTxO chain
  is a linear history, so mirrors cannot diverge), and serve proofs to the
  settlement-effect executor and to crankers via a domain route. The
  mirror is a cache, not a trust dependency: anyone can reconstruct any
  shard trie from the L1 spend history alone and verify it against the
  on-chain root — essential for A3 when no L2 node survives. Shard
  threads are created at vault deployment with the empty-trie root, and
  the validator admits no transition except paired with a valid vault
  spend.
- **Batch settlement marker**: the continuing vault output's datum carries
  the ORDERED claim-id list; payout output[i] pays claim[i] exactly
  (positional matching for the confirmation observer, per §3-A2).

### 7.2 A2 — batched settlement, signer path

Redeemer: `Settle { claimIds[], federationSig }` where the threshold
signature covers `digest(claimIds ‖ vaultInputs ‖ outputs ‖ shardRoot')`.
Script checks: threshold signature against the CURRENT member set (via the
root thread reference input's memberSetHash), positional exact payouts,
continuing output preserves `inputs − Σ amounts`, nullifier shard insert
for every claim id, batch size ≤ the profile's hard cap.

**The signer is NOT today's single operator wallet.** The settle path
demands the federation threshold (member-set keys verified against the
root thread's memberSetHash). Compromise analysis under V1: stolen keys —
even a full quorum — can only produce valid settlements of real pending
claims to their committed addresses; the script forbids redirection, so
key compromise buys CENSORSHIP at worst, and censorship arms A3 after
fallbackDelay. Keys degrade from custody to liveness; a compromised fee
wallet loses only its float.

**Execution via the effect system (proposed, replacing an external
scheduler).** The batch trigger (N pending claims or T elapsed) is
state-machine logic, so the bridge machine EMITS an `l1.settlement` effect
— journaled, exactly-once, owner-assigned like the showcase outbox and the
evidence sinks. The owning executor builds the settlement transaction;
member nodes contribute partial threshold signatures (as effect results or
app messages); the owner assembles and submits; the EXISTING withdrawal
confirmation observer closes the loop to CONFIRMED. Single effect
ownership prevents duplicate submissions; effect retries handle L1
hiccups; the executor's collected bounty (§6) funds its fee wallet.

Pros: O(1) signature verification regardless of batch size → large batches
(ex-unit budget spent on output/datum checks only); no UTxO contention (the
federation serializes itself); prompt latency under the N-or-T trigger;
fee-amortized; chain-scheduled execution reuses shipped effect machinery.
Cons: liveness and censorship rest on the federation (why A3 must exist);
threshold signature collection across members is the new engineering;
batch ABI + observer change required.

### 7.3 A3 — permissionless proof exit, fallback path

Redeemer: `Exit { claimIds[], claimProofs[], nonMembershipProofs[] }`.
Script checks: root thread referenced and STALE — fallback is armed only
when `now − updatedAtSlot > fallbackDelay` (the ADR-007 trigger: the
federation stopped rooting/settling); each claim proven present under the
accepted `stateRoot` at its claims key; nullifier shard non-membership +
insert; exact payouts and remainder preservation as in A2. Anyone may
build and submit ("cranking"), and the cranker is paid ON L1 from the
claims themselves: each claim's committed bounty (§6) is spendable to one
cranker-chosen output in the settling transaction — pre-funded by the
withdrawer at creation, bounded by the frozen fee cap, identical to how
the A2 executor is paid. A flat 2 ADA bounty makes batch-cranking
strangers' exits profitable after the L1 fee; a 0 bounty (demo) means
operator-run cranking only.

Pros: trustless escape hatch — funds recoverable without any operator,
which is the entire point of the tier; same vault, same invariants, no
second custody surface. Cons: per-claim proof verification is ex-unit
expensive → small batches (single-digit claims per tx with MPF proofs);
UTxO contention is real and must be engineered (below); exits only as
fresh as the last accepted root (claims made after the federation died
need the root thread's final update — bounded loss window = rooting
cadence, a parameter worth governing tightly).

**Contention handling in A3** (the hard part, honestly):
1. **Reference-input root** removes the biggest global choke point.
2. **Sharded nullifiers (k threads)** divide nullifier contention by k;
   k is a genesis-time structural choice (changing it later is a
   migration), sized generously (e.g. 16) since idle shards cost only
   min-ADA.
3. **Vault sharding is natural** — deposits stay as many UTxOs; exit
   builders select disjoint vault inputs. First-seen wins; losers rebuild.
4. **Cranker batching absorbs races**: in a fallback scenario the rational
   pattern is a few crankers each draining a shard queue for fees, not
   thousands of users racing — economically A3 converges to
   permissionless A2.
5. **Accepted residual**: fallback mode is an emergency exit, not a
   throughput product; occasional rebuild-on-conflict is acceptable there
   in a way it is not for A1's wallet-signing loop (no human re-signing —
   crankers rebuild mechanically).
An intent-queue two-phase design (post exit-intent, complete later) was
considered and rejected for v1: it adds latency and a second contended
structure for marginal gain over shards + cranker batching.

### 7.4 Comparison

| | A2 signer path | A3 proof path |
|---|---|---|
| Trust for liveness | federation | none (cranker economics) |
| Trust for safety | V1 script (same) | V1 script (same) |
| Ex-unit profile | O(1) sig + O(n) outputs → big batches | O(n) proofs → small batches |
| Contention | none (self-serialized) | shard-managed, cranker-absorbed |
| Latency | trigger-bounded (seconds–minutes) | fallbackDelay-gated (hours) |
| Censorship resistance | none alone | full, once armed |

**Recommendation:** build them together on the shared vault — A2 without
A3 is custodial liveness; A3 without A2 is a poor everyday product.

## 8. Parameter tiers and changeability (Q3 resolved direction)

Three tiers, stated per parameter so "can it change later?" always has one
answer:

1. **Immutable (profile-frozen at genesis):** claim/settlement ABIs, fee
   hard bounds, max claims per settlement tx (sized to worst-case
   ex-units), nullifier shard count k, fallbackDelay bounds. Changing any
   of these is a new profile digest — a chain migration, deliberately.
2. **Governed L2 parameters (genesis initial value; changed by
   membership-threshold governance messages at a recorded height; no
   restart):** effective withdrawal fee, minimum withdrawal, operational
   (soft) batch cap ≤ tier-1 hard cap, rooting cadence, fallbackDelay
   within its bounds.
3. **Operator policy (freely changeable, zero consensus impact):** batch
   trigger N and T, reservation TTL (A1), cranker scheduling.

**min-ADA is not ours to set** — it is a Cardano L1 protocol parameter that
can move under Cardano governance. Every payout output must satisfy it, so
the tier-2 "minimum withdrawal" is enforced at claim creation as
`max(governedMinimum, live L1 minUTxO + margin)`, read from the node's
tracked protocol parameters. A batch is additionally bounded by
`maxTxSize`/ex-units at build time against LIVE L1 parameters — tier-1
caps are ceilings, the builder computes the real bound per transaction.

## 9. Resolved defaults (ratified 2026-08-06)

- **Parameter transport:** reuse the governed-admin message path (no new
  topic); bridge parameters become one governed record with recorded
  effective heights.
- **Shard count k = 16** (structural, tier-1; idle shards cost min-ADA).
- **fallbackDelay default 86,400 L1 slots (~24h)**, governed within
  tier-1 bounds [6h, 30d]. **Rooting cadence:** every 100 L2 blocks or 1
  hour, whichever first (governed) — the bounded-loss window for
  post-federation-death claims.
- **Partial-signature collection: app messages** on
  `bridge.settlement.sig.v1` — consensus-ordered, replayable, and visible
  to every member; the `l1.settlement` effect carries the batch digest to
  sign, the owning executor assembles the threshold from finalized
  messages. (Effect-result transport rejected: results are per-executor,
  and signatures must be collected ACROSS members.)
- **Bounty default: flat 2 ADA** (`{flat: 2_000_000, bps: 0}`) for public
  demos; 0 permitted for closed demos. Tier-1 cap: 5 ADA or 100 bps,
  whichever is greater.

## 10. Decisions (final)

- One V1 vault, two authorization paths: A2 federation-threshold batched
  settlement (fast path) + A3 fallbackDelay-armed permissionless proof
  exit. A1 rejected.
- Fee = per-claim committed executor bounty charged at claim creation;
  reserve decreases by payout+bounty; one L1 conservation rule pays A2
  executor and A3 cranker identically.
- Settlement execution rides the effect system; signatures ride app
  messages; the existing confirmation observer closes the loop.
- Nullifiers: k=16 L1 shard threads holding roots only; atomic
  check+insert per settlement; L2 mirrors the tries from L1 observations
  and serves proofs; anyone can reconstruct from L1 history.
- Parameters in three tiers (immutable profile / governed L2 / operator
  policy); min-ADA folded live from L1 params into the governed minimum
  withdrawal.

## 11. Implementation plan

New machine profile version (the L2 validation changes are
consensus-relevant): `yano-eutxo-v3-bridge-settlement`, new digest;
existing bridge chains migrate by chain-config migration (new vault
address = new chain identity fields), or new chains adopt it directly —
the showcase will add a v2 bridge chain rather than mutate
`payment-chain-l1bridge` history.

**SP-M1 — L2 machinery: claim ABI v2 + governed bridge parameters.**
Claim `{payout, bounty}` (ABI v2), fee resolved at creation from the
governed `{flat, bps}` schedule, reserve accounting payout+bounty,
governed parameter record (fee schedule, min withdrawal =
max(governed, live L1 minUTxO + margin), soft batch cap, rooting cadence,
fallbackDelay) updated via governed-admin messages at recorded heights.
Tests: replay/restart determinism of parameter changes; claim-creation
validation golden vectors; reserve == vault invariant property test.

**SP-M2 — V1 on-chain scripts + budgets.** Adapt
`appchain-eutxo-bridge-onchain` (julc) to this spec: VaultValidator with
Settle (threshold over batch digest vs root-thread memberSetHash) and
Exit (stale-root arming, per-claim MPF inclusion under stateRoot) paths;
NullifierStateValidator (MPF non-membership + computed post-insert root,
proof-chained for batches — same MPF verifier as the Exit path's claim
inclusion, per §7.1); FederatedRootValidator (threshold root
updates, membership-epoch aware). Deliverables: measured ex-unit budgets
→ tier-1 max batch sizes for BOTH paths; golden vectors shared with SP-M1;
deploy tooling (V1 vault address derivation, shard-thread bootstrap
transaction builder). Gate: property tests incl. adversarial (redirected
payout, skimmed remainder, replayed claim, forged threshold).

**SP-M3 — A2 settlement effect.** `l1.settlement` effect emitted on the
N-or-T trigger (machine logic), owner-assigned executor builds the batch
(positional payouts, batch marker with ordered claim ids, bounty output),
partial signatures via `bridge.settlement.sig.v1` app messages, assemble
+ submit, batch-aware confirmation observer (positional matching, marker
ABI bump). Devnet E2E gate: multi-claim batch (incl. duplicate
address+amount claims) settles to CONFIRMED end-to-end through the effect
path with a node restart mid-flight (exactly-once proof).

**SP-M4 — nullifier mirror + proof serving.** Shard-trie mirror in bridge
state driven by the same L1 observations as claim status; domain route
`bridge/nullifier/{shard}/proof`; standalone reconstruction CLI (rebuild
any shard from L1 spend history, verify against on-chain root). Gate:
mirror root == on-chain root after randomized settlement sequences +
restart.

**SP-M5 — A3 cranker path.** Permissionless `crank` client command (scan
provable pending claims for a shard, build exit with proofs from SP-M4 or
self-reconstruction, collect bounties). Devnet E2E gate: stop the
federation (no rooting/settling), advance past fallbackDelay, crank
strangers' claims to payout — the no-surviving-L2 variant uses the
reconstruction CLI only.

**SP-M6 — migration, showcase, console.** Showcase adds the v3-profile
bridge chain (deterministic demo federation), `chain add` tier, console:
fee/bounty display at claim creation, settlement/batch status, cranker
guidance in `bridge info`; docs (BRIDGE_CHAIN.md v2 section); ADR-008
cross-references. Public-network posture per recipe gates: custody review
before any non-demo funds; ex-unit budget checks wired into CI.

Dependencies: SP-M1 ∥ SP-M2 (shared golden vectors) → SP-M3 → SP-M4 →
SP-M5 → SP-M6. Each milestone lands on a feature branch with its
implementation-log entry here, mirroring the ADR-008 campaign process.

## 12. Implementation log

### SP-M1 — claim ABI v2 + governed parameters (2026-08-06, feat/adr009-sp-m1)

Delivered: EutxoProfile V3 `yano-eutxo-v3-bridge-settlement` (digest
`da8643db…`, tier-1 constants digest-bound; batch caps join in SP-M2);
claim ABI v2 `{payout, bounty}` (v1 bytes/ids frozen — golden-tested;
bounty in v2 identity; `totalLovelace()`); `EutxoBridgeParams` (+`{flat,
bps}` schedule, `resolveBounty`) and `EutxoBridgeParamsGovernanceV1`
(`~governance/eutxo-bridge-params`, exact-bytes approval accumulation);
machine: genesis params init, per-block activation sweep, membership-gated
threshold approvals with recorded activation heights, v3 fee split at
claim creation (BRIDGE_WITHDRAWAL_MINIMUM guard), reserve release by
payout+bounty; privileged-submission override; provider v3 selection +
`machines.eutxo.bridge.params.*` genesis settings; capability
`profile:eutxo-bridge-settlement`, first-party metadata (ledger-owned),
acceptance evidence, golden digests, indexer JSON bounty.

Tests: 4 codec goldens (incl. v1-id freeze + bounty-in-id), 6 machine tests
(bounty lifecycle with total-reserve reconciliation, governed change
end-to-end incl. outsider/duplicate rejection and post-activation fee, min
guard, privileged admission, from-scratch replay equality, conformance
restart+snapshot determinism over a governance corpus). Full consumer
sweep green (contracts, ledger, indexer, bridge-cardano, demo, client,
devtools, app).

Learned: metadata entries are owner-descriptor-scoped (params keys live in
the ledger group); the release acceptance index demands evidence per
capability; catalog/metadata/acceptance digests are golden-pinned in
metadata.sha256 and the zk preview release contract.

### SP-M2 — V1 on-chain validators + ex-unit budgets (2026-08-06, feat/adr009-sp-m2)

Three validators, all julc-VM conformance-tested against real off-chain
`MpfTrie` fixtures:

- **MPF library** copied from julc-examples as a repackaged `@OnchainLibrary`
  (`mpf/MerklePatriciaForestry`,`ProofStep`,`Neighbor`) — the maintained,
  tested `including`/`excluding`/`has`/`miss`; the same proof serves
  exclusion, post-insert root, and inclusion (§7.1). Replaces an initial
  hand-port.
- **SettlementVaultValidator**: dual `Settle`/`Exit` redeemer. Settle checks
  the federation threshold against member keys carried on the root-thread
  reference input; Exit is armed only when `now − updatedAtSlot >
  fallbackDelay` and proves each claim's v2 commitment present under the
  accepted state root. Both: positional payouts (output[i]=claim[i]), paired
  nullifier-shard spend, remainder conservation (continuing =
  Σinputs − Σ(payout+bounty)), batch marker with ordered claim ids.
- **NullifierShardValidator**: k=16 shards (shard = claim id's last nibble),
  chained non-membership+insert per claim, paired vault spend.
- **SettlementRootValidator**: FederatedRoot + `updatedAtSlot` bound to the
  validity range + governed `fallbackDelaySlots`.
- **Commitment ABI v2**: `EutxoWithdrawalCommitment` binds the bounty into
  its digest preimage (review fix — claimId-only binding let a cranker
  inflate the bounty and skim the remainder).

Measured budgets (10B cpu / 14M mem tx limits) → tier-1 caps frozen in the
V3 profile digest (recomputed `71d7d744…`; goldens re-pinned):
`V3_MAX_SETTLE_BATCH=16` (settle 8 claims = 487M cpu / 1.73M mem; O(1)
threshold amortizes), `V3_MAX_EXIT_BATCH=6` (exit ~80M cpu per MPF
inclusion; 3 claims = 336M cpu). The vault enforces both as deploy `@Param`
byte caps. Full consumer sweep green (contracts, ledger, bridge-cardano,
demo, devtools, app).

julc constraints learned and recorded for the campaign: mixed-type
while-loop accumulators holding a list — or a record with a list field —
emit an ill-typed `MkCons`; use recursion or index iteration with
primitive/byte[] state (member keys are carried as a concatenated byte[],
not a `list<data>` field). Sealed-variant and nested-`JulcList` redeemers
must be record-wrapped. Prefer stdlib (`ByteStringLib`/`CryptoLib`) over
`Builtins` byte ops — `Builtins.sliceByteString` mistyped a sliced key and
`ByteStringLib.slice` fixed it. No cross-class statics — shared code is an
`@OnchainLibrary` or inlined.

Deferred into SP-M3 (where they are first exercised end to end on a devnet):
checked-in `META-INF/plutus/*.plutus.json` artifacts + address resolver and
the 16-shard/root/vault bootstrap transaction builder. The validators,
their budgets, and the frozen caps are complete and tested here.

### SP-M3 — effect emission (consensus core) (2026-08-06, feat/adr009-sp-m3)

Consensus half delivered and tested: `EutxoStateMachine` now overrides the
3-arg `apply(block, writer, effects)` (2-arg delegates to a rejecting
emitter) and emits one `l1.settlement` CHAIN effect per batch when
`softBatchCap` claims are unsettled OR `rootingBlocks` have elapsed since
the window opened. A monotone per-epoch settlement cursor
(`bridge/{epoch}/settlement/cursor`) batches each claim exactly once; the
payload is an `EutxoSettlementBatch` range `[from,to)` (O(1), executor
resolves claims via the query API). `onEffectResult` rewinds the cursor to
the persisted batch start on terminal FAILED/EXPIRED (re-batch) and is a
no-op on CONFIRMED (the confirmation observer closes each claim). Emission
is a pure function of committed state; tests cover cap-trigger + exactly-once
advance, elapsed-rooting-blocks trigger, and terminal-failure rewind.

Safe to merge inert: only `settlementProfile()` (v3) chains emit, and none
exist in any deployment until the SP-M6 showcase v3 chain — so no running
chain sees an unresolved effect.

REMAINING in SP-M3 (execution plane, next session): `BatchSettlementTransactionBuilder`
matching the SP-M2 vault Settle ABI (positional payouts + bounty + batch
marker + paired shard spend + root reference input + threshold witness);
`BatchSettlementExecutor` + `AppEffectExecutorFactory` (scheme
`eutxo-settlement`) reusing `SettlementJournal`/`CardanoSettlementBackend`,
keyed on `effect.idHash()`; `SettlementCosignService` cloning
`ScriptAnchorService`'s `~anchor/sign`/`~anchor/sig` round on a new
`~bridge/settlement/sig` diffusion prefix for partial threshold signatures;
batch-aware `WithdrawalConfirmationObserver` + `EutxoWithdrawalConfirmation`
ABI v2 (positional claim[i]->output[i], batch handle); the deferred SP-M2
deploy artifacts (checked-in `META-INF/plutus`, address resolver, 16-shard
bootstrap builder); and the devnet E2E gate (multi-claim batch incl.
duplicate address+amount + mid-flight restart proving single-owner +
idHash idempotency + first-`~fx/result`-wins). The one genuine design point
to resolve there: the framework has NO deterministic per-effect owner
election — the settlement executor + cosign leader must be pinned to a
single owner node (config-designated like `anchor.enabled`), which the
exactly-once E2E gate exists to prove.

#### SP-M3 execution plane — progress (2026-08-06)

Two foundational libraries built and unit-tested:
- `EutxoBatchSettlementMarker` (contracts) — the continuing-vault datum,
  byte-exact twin of the SP-M2 on-chain marker.
- `BatchSettlementTransactionBuilder` (bridge-cardano) — the unsigned Settle
  transaction the SP-M2 vault accepts (positional payouts, marker,
  bounty-to-executor, fee-from-executor, root reference input, shard spend,
  threshold-witness slot). Tested: exact outputs, marker round-trip,
  remainder/bounty math, unfunded/mixed-epoch rejection.

The executor and the L1→L2 confirmation loop are now built and unit-tested:

- `BatchSettlementExecutor` + `BatchSettlementJournal` (bridge-cardano) — the
  owner node's body for one `l1.settlement` effect: decode
  `EutxoSettlementBatch` → resolve claims/vault/inputs (injected
  `BatchResolver`) → build the unsigned Settle tx → federation-threshold
  co-sign (injected `ThresholdCosigner`) → submit via
  `CardanoSettlementBackend`. Idempotent on `effect.idHash()` via a WAL keyed
  by that hash: once a batch reaches the L1 the executor only re-probes its
  status, never rebuilds/resubmits. Maps L1 status to
  Confirmed/Submitted/Failed; empty resolved range short-circuits to
  Confirmed. Five conformance tests (each status, idempotent re-run, empty
  range). Collaborators are injected so the ServiceLoader factory + host
  wiring can land with the SP-M6 devnet.

- **Batch confirmation loop.** The framework keys observations by
  `observerId/txHash/slot` (`L1Observation.key()`), so N observations from
  one settlement transaction would collide and only the last would survive
  follower verification. The plan's "emit N positional confirmations" is
  therefore unsafe as stated; the correct shape is **one** observation per
  settlement tx carrying a batch payload. Built:
  `EutxoBatchWithdrawalConfirmation` (contracts) — shared L1 identity + an
  ordered, dense-positional list of `{claimId, payoutIndex, destination,
  lovelace}` entries mirroring the positional payouts; expands to per-claim
  `EutxoWithdrawalConfirmation`s so the ledger reuses `confirmWithdrawal`
  unchanged. `BatchWithdrawalConfirmationObserver` + provider decodes the
  `EutxoBatchSettlementMarker` on the continuing vault output, asserts the
  vault sits at the payout boundary (index == count), reads each positional
  payout, and emits that single observation. `EutxoStateMachine` branches on
  `settlementProfile()` (v3): the confirmation topic decodes as a batch and
  loops `confirmWithdrawal` per entry (each clears its claim + decrements the
  reserve/pending count). v3 is now batch-only — the pre-existing v3
  single-claim confirmation test was migrated to a size-1 batch, and a new
  test clears three claims with one batch confirmation and reconciles the
  reserve to zero.

The federation-threshold co-signer's verification/assembly heart is also
built and unit-tested: `SettlementCosigner` (bridge-cardano) implements
`ThresholdCosigner`. The SP-M2 vault counts approving members via
`ContextsLib.signedBy` over the root-thread member keys, so the built body
already lists exactly those members in `required_signers`; the cosigner
attaches one **verified** Ed25519 vkey witness per required signer and refuses
a partially-witnessed transaction — every required signer must witness (a
ledger invariant), the count must meet the governed threshold, non-member /
malformed / forged signatures are dropped, and a post-assembly txid check
guards against body drift (witnesses sign the body hash, so attaching them
must not change it). The p2p round that gathers those signatures over the app
channel is the injected `PartialSignatureCollector` seam. Four tests: happy
assembly + witness verification + hash preservation, extra non-member sig
ignored, missing required signer fails closed, forged signature rejected.

Still remaining (needs a running v3 devnet to validate end to end, so it
lands with SP-M6's showcase v3 chain): the `AppEffectExecutorFactory` (scheme
eutxo-settlement) + host wiring of the executor's real collaborators;
`SettlementCosignService` — the concrete `PartialSignatureCollector` cloning
ScriptAnchorService's ~anchor/sign|sig round onto a new ~bridge/settlement/sig
diffusion prefix (leader builds/requests, members reply, leader collects);
the SP-M2-deferred deploy artifacts; the single-owner pinning
(effects.result.signers + config-designated executor/cosign leader); and the
multi-claim + mid-flight-restart E2E gate.

### SP-M4 — nullifier mirror + proof serving + reconstruction CLI (2026-08-06, feat/adr009-sp-m4)

`NullifierShardMirror` (appchain-eutxo-client) — the off-chain nullifier
mirror: k=16 `MpfTrie` shards (`cardano-client-merkle-patricia-forestry`,
reached transitively through `appchain-client`), one per nibble
(`claimId[31] & 0x0F`), value = the claim id itself, so each shard root is
byte-equal to the on-chain `NullifierShardValidator` root by construction (the
SP-M2 conformance test already pins the validator to `trie.getRootHash()`).
API: `insert`, `root(shard)`, `contains`, `proofWire`, `verifyMembership`/
`verifyAbsence`, `reconstructShardRoot(ids)`, and `planInserts(shard, newIds)`
— the batch-insert proof chain that mirrors the on-chain `foldInserts`
exactly (the non-membership proof at the running root, then the insert). This
`planInserts` output is what the SP-M6 builder feeds into the continuing
shard output + `InsertBatch` redeemer (which the SP-M3 body builder currently
only *spends* the shard for).

Reconstruction is the trust anchor: the MPF root is a pure function of the
settled-id set, so a cranker with no surviving L2 node rebuilds any shard from
L1 spend history and gets the same root. The standalone CLI (`EutxoCli`,
node-free) delivers this: `nullifier reconstruct --ids <file> [--shard N]
[--expected-root <hex>]` rebuilds and (optionally) compares to the on-chain
root; `nullifier proof <claim-id> --ids <file>` emits and self-verifies the
MPF wire proof (membership if settled, non-membership otherwise).

Gate met (11 tests): shard routing + isolation, order-independent
reconstruction equal to the live mirror, restart reproduces every root,
per-shard reconstruction equals the live roots, membership/non-membership
proofs verify against the shard root, `planInserts` fold replay reproduces the
next root, adversarial wrong-shard/already-settled rejection; CLI match/
mismatch/usage exit codes and verified proof emission.

Deferred to SP-M6 (needs the live v3 settlement flow to feed the mirror, so it
lands with the devnet wiring): the node-side `bridge/nullifier/{shard}/proof`
domain route (a `DomainApiProvider` route backed by a mirror read model fed by
confirmed settlements), and the builder integration that emits the continuing
shard output(s) + `InsertBatch` redeemer from `planInserts` (one shard input/
output per distinct nibble in a batch — the vault requires only that *a* shard
is spent, each shard validates its own nibble's inserts).

### SP-M5 — permissionless crank (A3), client plane (2026-08-06, feat/adr009-sp-m5)

`ExitTransactionBuilder` (appchain-eutxo-client) — the unsigned A3 Exit body
the SP-M2 vault Exit path accepts: Settle's exact shape (positional payouts,
continuing vault `Σinputs − Σ(payout+bounty)` under the batch marker, shard
spend, root reference input) minus required signers, with Σbounty paid to the
CRANKER's own output and the L1 fee from the cranker's inputs. Refuses to
build unless armed: `currentSlot − rootUpdatedAtSlot > fallbackDelaySlots`
must hold strictly (mirroring the on-chain `finiteLowerBound(validRange)`
check), the batch must drain a SINGLE nullifier shard (claim-id last nibble),
respect the governed `maxExitBatch`, and not mix bridge epochs.

`CrankPlanner` — deterministic claim selection for one shard: armed-gate,
shard filter, skip-already-nullified (an injected predicate the cranker backs
with a `NullifierShardMirror` it maintains or reconstructs from L1 — SP-M4),
governed batch cap with a `capped` flag for remaining work, total-bounty
report. `plan.claims()` feeds `ExitTransactionBuilder.build` directly.

Tests (8): armed exit with positional payouts + marker + cranker bounty +
no-required-signers + arming interval encoded; boundary-slot refusal (not
strictly past the delay); mixed-shard and oversize batch refusal; unfunded
vault refusal; planner armed-gate, shard/nullified filtering + bounty sum,
governed cap with capped-flag, and planner→builder hand-off equality. Claim
fixtures derive real claim ids (hash-derived), grouped by their actual shard
nibble.

Devnet-gated (lands with SP-M6's v3 chain, per the SP-M5 gate): the live
`crank` command (node queries for owed claims + root staleness, Plutus witness
assembly with Exit/InsertBatch redeemers + ex-units, submission), and the E2E:
stop the federation, advance past fallbackDelay, crank strangers' claims to
payout — including the no-surviving-L2 variant driven by the SP-M4
reconstruction CLI alone.

### SP-M6 — hardening + deploy plane (in progress, 2026-08-06, feat/adr009-sp-m6)

**Adversarial-review fixes (0127f6b2).** An independent review of SP-M3/M4
found the batch confirmation observer trusted output shape alone — anyone
paying the vault address a well-formed marker datum could (a) DoS the
observer (a structural throw dropped the whole block's observations) and (b)
fabricate a confirmation for a real pending claim by paying its public
destination/amount out of pocket, tricking the ledger into CONFIRMED +
releasing the reserve while real vault funds sat orphaned. Fix — custody
binding across all three layers: the confirmation now carries the settlement
transaction's `spentOutpoints`; the observer fills them from the tx inputs
and SKIPS structurally invalid marker transactions deterministically; the
ledger tracks LIVE vault custody (`bridge/vault-utxo/` keys — deposits add
the accepted outpoint) and accepts a batch confirmation only if it SPENT a
tracked outpoint (only the on-chain validator authorizes vault spends),
rotating custody to the continuing outpoint; otherwise
`WITHDRAWAL_CONFIRMATION_UNPROVEN` halt with the reserve untouched. Also:
the executor records `FAILED` on a REJECTED submission (submit and probe
paths) so retries REBUILD with fresh inputs instead of probing a dead txid
until effect expiry.

**Deploy-ordering fix (e351b740).** The vault took `shardScriptHash` and the
shard took `vaultScriptHash` — circular: the V1 pair could never actually be
parameterized. The vault now pairs the shard spend by its THREAD TOKEN
(`ValuesLib.containsPolicy`; param → `shardThreadPolicyId`); thread tokens
are one-shot and the shard validator keeps them at the script forever, so a
token input necessarily invokes the shard validator. Deploy order is linear:
policies → vault → shard. Budgets unchanged (settle 8 = 493M cpu); new
adversarial vector: fully-signed settle with a tokenless "shard" input fails.

**ShardThreadPolicy (1d1eb214).** One-shot policy minting exactly the 16
shard thread tokens {0x00…0x0F} (+1 each) while consuming a seed UTxO; a
value map cannot duplicate names, so count==16 + single-byte + <16 + amount 1
IS the exact set. VM-tested (215M cpu mint; six adversarial vectors).
Distinct from the root policy so root-update spends can't impersonate shards.
julc pre14 constraints discovered and recorded: `serialiseData` is emitted
without its required force (unusable), and raw-PlutusData map-cursor
while/recursion in minting-validator helpers miscompiles — the working shape
is a typed for-each over `ValuesLib.flattenTyped` with BigInteger/boolean
accumulators (the AnchorThreadPolicy idiom); `Failure.builtinTrace` in the
testkit is the debugging tool.

**Deploy artifacts (66a4d169).** Four unparameterized julc templates checked
into bridge-onchain `META-INF/plutus` with a source-compile drift pin
(regenerate via `-Dyano.regenerate.plutus=true`); the root thread reuses the
audited AnchorThreadPolicy artifact.

**Shard continuation in the settle body (8e27e4b9, 35aa2cbb).**
`EutxoShardDatum` (contracts) is the byte-exact off-chain twin of the shard
thread datum; `EutxoShardContinuation` packages address + thread-token
identity + min-ADA + post-insert datum; the batch builder's SP-M6 overload
appends the continuing shard output (token at +1, inline datum with
`planInserts`' next root) and refuses claims outside the continued shard —
single-shard batches; the multi-shard settle (one shard input/output per
distinct nibble) remains devnet-gated.

**Deploy plane (0f9e9679).** `SettlementScriptArtifacts` (demo module)
resolves the deploy identity from the checked-in templates via julc param
application, and `SettlementBootstrapPlan` is the deterministic pure function
from (two one-shot seeds, bridge config) to every script/hash/address plus
the genesis root datum and 16 empty-root shard datums — with config
validation (sorted distinct members, threshold, tier-1 fallback-delay
bounds). Surfaced fix: `MpfTrie.getRootHash()` reports an empty trie as
`null` while the on-chain null hash is 32 zero bytes — the mirror now
normalizes every root read to the on-chain convention.
`BRIDGE_CHAIN.md` gained the §8 v2-settlement preview section.

**Devnet E2E — bootstrap + live A2 settle PASSING (0bbc78c3, 1e7fd791).**
`EutxoSettlementBootstrapDevnetE2ETest` (app, `:app:e2eTest`) runs on a
disposable devnet: (1) both one-shot Plutus mints execute on-chain
(AnchorThreadPolicy root NFT + ShardThreadPolicy's exact 16 tokens — first
live execution), the threads land at the resolved addresses with the plan's
genesis datums byte-for-byte; (2) a REAL A2 batch settle spends the vault +
shard 0 in one transaction — SettlementVaultValidator phase-2 (positional
payouts, remainder conservation under the marker, federation threshold via
required signers against the root-thread reference input, thread-token
pairing) and NullifierShardValidator phase-2 (chained InsertBatch with the
SP-M4 mirror's non-membership proofs) — advancing the on-chain shard root
FROM THE EMPTY ROOT to exactly the mirror's post-insert root. That closes
the core of the SP-M4 gate (mirror == on-chain after settlement) and proves
the deploy plane + both validators + marker + empty-root convention live.
Learned: CCL `PlutusData.deserialize` normalizes MPF wire tags 121/122/123
to constr 0/1/2 (proof wires are directly redeemer-usable); quicktx
preserves output declaration order; the bounty floats to change.

**Live A3 permissionless exit PASSING (393b877b).** Devnet gate step 3 — the
SP-M5 A3 gate live: a second identity bootstrapped with a REAL accepted state
root (MPF of the claims' v2 commitment digests; `Config.initialStateRoot`),
then a cranker with NO federation signature exits both claims — arming via
the validity lower bound (the context maps slots to POSIX time, so a fresh
devnet arms immediately), per-claim MPF inclusion against the off-chain
`claimDigestV2` replica, shard InsertBatch nullification, positional payouts,
Σbounty to the cranker. Pitfall recorded: quicktx skips
validityStartInterval when validFrom == 0 → lower bound NegInf →
`finiteLowerBound` = −1 → disarmed at evaluation; floor the slot at 1.
All three devnet gates green in one ~19s `:app:e2eTest` run.

**Co-sign host wiring (9ac6c63f).** New `~bridge/*` diffusion-only channel
(core-api `BridgeDiffusionHandler` SPI; the subsystem routes first-sighting
envelopes to the handler registered per chain — the anchor route pattern,
extension-registerable). `SettlementCosignService` (bridge-cardano)
implements the executor's `ThresholdCosigner` over it: the owner broadcasts
the unsigned body on `~bridge/settlement/sign`, members verify the body
against their own view (injected custody gate) and reply signatures over the
canonical body hash on `~bridge/settlement/sig`; forged/non-member replies
drop; assembly delegates to `SettlementCosigner`'s fail-closed core. Four
round tests (witnessed assembly, rejecting member fails closed, forged reply
tolerated, non-leader refuses rounds).

**Construction-site wiring COMPLETE (52e83692).** The settlement stack now
self-assembles per chain from `effects.executors.eutxo-settlement.*`:
`AppChainEffectContext` (core-api) exposes the node-coupled surface —
chain-scoped diffusion, member signer/set/threshold, committed-state
queries, L1 UTxO view, protocol params, the node's phase-2 evaluator
(`TxEvaluationGateway`, newly wired RuntimeNode → subsystem), tx submission,
`~bridge/*` registration — and `AppEffectExecutorFactory` gained a default
context-aware overload the subsystem invokes for every configured scheme.
`EutxoSettlementExecutorFactory` (ServiceLoader + manifest): every member
registers the co-sign service with the committed-state custody gate
(`verifyProposedBody`: marker claims must be our own PENDING claims with
exact positional payouts); the `owner=true` node additionally gets the
executor. `QuickTxSettlePipeline` is the live engine assembling the exact
devnet-gate transaction shape against the node's own surfaces
(`CclNodeAdapters`), per shard group with mirror reconstruction verified
against the on-chain shard datum; multi-shard batches settle as sequential
transactions in one execution. `PipelinedSettlementExecutor` judges
completion solely by "no pending claims left in the range" — a confirmed tx
with a pending remainder records FAILED and the retry settles the rest
(nullifier prevents double-settlement). Goldens re-pinned; bundle boundary
checks pass.

REMAINING for SP-M6: the showcase v3 chain (bootstrap emits the wiring
config) + console fee/bounty, and the SP-M3 effect-path gate — the wired
stack on a live multi-member devnet chain with a mid-flight restart
(exactly-once through the effect path);
`AppEffectExecutorFactory` (scheme eutxo-settlement) + host wiring of the
executor collaborators; `SettlementCosignService` on ~bridge/settlement/sig;
single-owner pinning; showcase v3 bridge chain + console fee/bounty +
BRIDGE_CHAIN.md v2; and the devnet E2E gates (SP-M3 multi-claim + restart
exactly-once, SP-M4 mirror==on-chain, SP-M5 stopped-federation crank).
