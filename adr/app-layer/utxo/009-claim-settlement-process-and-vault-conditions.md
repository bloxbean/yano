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

## 6. Open questions (to finalize)

- Fee model end-state: receiver input (A1) vs explicit L2 withdrawal fee
  field in the claim (works for A2/A3 too) — the fee field also gives the
  operator/cranker a declared incentive.
- Reservation owner: operator service vs node build-endpoint, and TTL.
- Whether A1 lands in the showcase (receiver builds + `co-sign` client
  command; the deposit assemble machinery is most of the plumbing) or waits
  for V1.
- Batch size / min-ADA interactions for A2; nullifier state layout for A3.

## 7. Decision

Deferred — this ADR is the discussion record; finalize option selection and
milestones after review.
