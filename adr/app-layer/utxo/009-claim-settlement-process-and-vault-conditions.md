# ADR-UTXO-009: Claim Settlement Process and Vault Spend Conditions

- Status: Proposed — discussion (finalize after review)
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

### A1 — receiver-builds, operator co-signs (proposed next tier)

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

**Charge the fee at CLAIM CREATION on the L2, not at settlement.** The
claim ABI (v2) carries `{requestedLovelace, withdrawalFee}` and commits a
payout of `requested - fee`; the fee is credited inside the L2 ledger to an
operator/cranker fee account (itself withdrawable via a normal claim).
Because the fee never appears on the L1 side, the settlement invariant
stays "exact committed amount leaves the vault" — the V1 vault script and
the confirmation observer are untouched by fee changes, and the vault
script hash (= vault address = chain identity) never churns over fee
policy. This also gives A3 crankers a declared incentive for free.

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
  `claimId mod k`. Datum = `{shardIndex, nullifierRoot}` (an MPF/JMT root
  over settled claim ids). Spending a shard requires, in the same tx, a
  paired vault spend plus a NON-membership proof of each settled claimId
  and the datum's root updated by inserting them. One shard per tx.
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

Pros: O(1) signature verification regardless of batch size → large batches
(ex-unit budget spent on output/datum checks only); no UTxO contention (the
federation serializes itself); prompt latency under the N-or-T trigger;
fee-amortized. Cons: liveness and censorship rest on the federation (why A3
must exist); federation signing infrastructure (threshold/HSM) is the
operational cost; batch ABI + observer change required.

### 7.3 A3 — permissionless proof exit, fallback path

Redeemer: `Exit { claimIds[], claimProofs[], nonMembershipProofs[] }`.
Script checks: root thread referenced and STALE — fallback is armed only
when `now − updatedAtSlot > fallbackDelay` (the ADR-007 trigger: the
federation stopped rooting/settling); each claim proven present under the
accepted `stateRoot` at its claims key; nullifier shard non-membership +
insert; exact payouts and remainder preservation as in A2. Anyone may
build and submit ("cranking"); the claim-creation fee (§6) pays the
cranker, so third parties are incentivized to batch strangers' exits.

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

## 9. Open questions (narrowed)

- Governed-parameter transport: reuse the existing governed-admin message
  path vs a dedicated `bridge.params.v1` topic (leaning: reuse).
- Shard count k and fallbackDelay defaults; rooting cadence economics.
- Fee shape: flat vs basis-points vs `max(flat, bps)` (leaning: max(flat,
  bps) with both bounded in tier 1).
- Whether A1 (receiver-builds co-sign) is still worth shipping en route,
  or the effort goes straight to A2+A3 on V1 (leaning: straight to A2+A3;
  A1's fee motivation is subsumed by the claim-creation fee).

## 10. Decision

Deferred — finalize after review of §6–§8 directions.
