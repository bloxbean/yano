# ADR-058: AdaPot Deposit Alignment with the Haskell Obligations

## Status

Accepted (pending verification), 2026-09-30.

- An independent review approved the design with changes and found no blocker. It confirmed the design against
  cardano-ledger `f649f975`, including:
  - timing and epoch labelling;
  - retiring pools and re-registration;
  - DRep refunds and proposals;
  - rollback safety.
- The review's changes are applied in this ADR and in the implementation.
- A second independent review of the implementation approved it with minor findings only. It confirmed that
  treasury, reserves, fees, rewards and the DRep distribution are unchanged on public networks, and that every
  rollback, resume and crash path is safe. The minor findings are applied.
- Implemented on branch `feat/adapot-deposit-alignment`, stacked on PR #155 (`feat/conway-ledger-rules` @ `1ddedb6b3`).
- It waits for the preprod and preview resync with the extended Koios comparison (see "Verification plan").

## Date

2026-09-30

## Related decisions and evidence

- [Issue #49](https://github.com/bloxbean/yano/issues/49) asks what the AdaPot `deposits` field means and whether it
  should be split by category. This ADR answers it for deposits. Issue #49's reward-aggregate question is out of scope.
- [ADR-050](050-complete-live-stake-pool-retirement.md), section 7, makes the per-pool lifecycle deposit
  authoritative. It leaves the aggregate to #49, and says the pool component must consume the POOLREAP lifecycle.
- [ADR-056](056-conway-java-ledger-rules-with-state-overlays.md), Phase 7c and "Follow-ups": "Align the AdaPot
  `deposits` (`total_dep`) with Haskell's `utxosDeposited`, in a separate PR".
  - ADR-056 fixed the phantom stake and DRep registrations. Chainstates synced before that fix already need a resync
    from genesis.
  - Its Koios deposit check was a residue: `(yano − deposits_stake − deposits_drep) mod pool_deposit`. The DRep
    deposit and the pool deposit are both 500 ADA on mainnet, preprod and preview. So that check cannot see an error
    in the DRep sum, and it proves the key deposits only modulo 250 keys.
- [ADR-048](048-bounded-memory-epoch-boundary-processing.md) makes boundary time and memory first-class metrics.
- Pinned sources:
  - Haskell: cardano-ledger `f649f975`, as in ADR-056. `CL/` means `cardano-ledger@f649f975`.
  - db-sync: read at `01bc85a1` (2026-04-02).
  - Koios `koios-artifacts`: `main` on 2026-09-30.

## Context

### What Haskell counts

`utxosDeposited` holds every deposit the ledger will one day pay back:

- `CL/libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs:427-438` defines
  `Obligations {oblStake, oblPool, oblDRep, oblProposal}` and `sumObligation`. Lines 383-388 state the invariant
  that the sum equals `utxosDeposited`.
- `CL/eras/shelley/impl/src/Cardano/Ledger/Shelley/State/CertState.hs:77-84`:
  - `oblStake` is `sumDepositsAccounts` over the registered accounts, using the deposit stored per account.
  - `oblPool` is the sum of `spsDeposit` over `psStakePools`. Pools that are retiring stay in `psStakePools` until
    POOLREAP removes them.
- `CL/eras/conway/impl/src/Cardano/Ledger/Conway/State/CertState.hs:111-116`: `oblDRep` is the sum of `drepDeposit`
  over `vsDReps`. An expired DRep stays registered and keeps its deposit.
- `CL/eras/conway/impl/src/Cardano/Ledger/Conway/Governance.hs:414-420`: `oblProposal` is the sum of `gasDeposit` over
  every proposal in `cgsProposals`.
  - Haskell has no "ratified but not yet enacted" state at a boundary. A proposal stays in `cgsProposals` until the
    pulse that ratified it completes at the next boundary.
  - Yano's pending enactments (`PREFIX_RATIFIED_IN_EPOCH`) likewise stay in `PREFIX_GOV_ACTION` at boundary N.
    Governance Phase 1 of boundary N+1 removes them.
- `CL/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/Types.hs:663-679` defines `potEqualsObligation`,
  `allObligations` and `totalObligation`.

At the epoch boundary, Haskell recomputes the pot from state instead of updating it incrementally:

- Conway `EPOCH` (`CL/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Epoch.hs`):
  1. runs `POOLREAP` (288-290);
  2. `proposalsApplyEnactment` removes the enacted and expired proposals, and those an enactment invalidates (315);
  3. `returnProposalDeposits` credits their deposits, and the unclaimed deposits go to the treasury (327-329);
  4. then sets `utxosDepositedL .~ totalObligation certState2 govState1` (353).
- Shelley to Babbage `EPOCH` does the same, after `POOLREAP` and `UPEC`
  (`CL/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Epoch.hs:172-178`).
- `POOLREAP` removes the retired pools from `psStakePools` and subtracts their refunds from the pot
  (`CL/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/PoolReap.hs:172, 216, 222`).
- The `NEWEPOCH` event carries `totalAdaPotsES es2`, computed after `EPOCH`
  (`CL/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs:175-177`). Its `obligationsPot` is
  `obligationCertState <> obligationGovState` (`CL/eras/shelley/impl/src/Cardano/Ledger/Shelley/AdaPots.hs:65`).

A boundary changes the pot only through POOLREAP and the proposal removals. Stake-key and DRep deposits change only
through certificates.

### What db-sync and Koios expose

- db-sync writes the `NEWEPOCH` `AdaPots` event into `ada_pots`
  (`cardano-db-sync/src/Cardano/DbSync/Default.hs:116-117`, `Ledger/State.hs:247, 904-908`).
  `Era/Universal/Insert/Certificate.hs:372-374` maps the obligations:
  - `deposits_stake` = `oblStake + oblPool` (stake-key **and** pool deposits);
  - `deposits_drep` = `oblDRep`;
  - `deposits_proposal` = `oblProposal`.
- Koios `/totals` returns `ada_pots` directly (`files/grest/rpc/01_blockchain/totals.sql`). Its schema describes
  `deposits_stake` as "stake key and pool deposits" (`specs/fragments/schemas/network.yaml:150-161`).
- Koios `totals` at epoch N is the pot after the N−1 → N boundary. Yano uses the same label
  (`qa/tools/koios_compare.py`).

### What Yano stored before this change

- **`total_dep`** (`META_TOTAL_DEPOSITED`) is a running counter of stake-key and DRep deposits.
  - `applyBlock` adds the delta `processCertificate` returns, and journals the previous value in the block delta.
    Rollback is therefore safe, and no boundary code writes the counter.
  - Stake keys: `registerStake` adds the deposit, and `deregisterStake` subtracts the stored deposit.
  - DReps: `RegDrepCert` adds the deposit, and `UnregDrepCert` subtracts the stored deposit.
  - Pool registration returns no delta, and proposals are never counted.
  - Two defects:
    - The Shelley-genesis staking bootstrap added the genesis pool deposits to `total_dep`, and never removed them.
      This affected only devnets with genesis pools.
    - A negative total was silently clamped to 0.
- **Pools**: `PREFIX_POOL_DEPOSIT` holds one record per registered or retiring pool, with its lifecycle deposit.
  POOLREAP deletes it and journals the delete (`PoolReapProcessor`, `PHASE_POOLREAP`). The set of keys is Haskell's
  `psStakePools`.
- **DReps**: `PREFIX_DREP_REG` holds the deposit. The same certificate branches that move `total_dep` write and
  delete it, and both are journalled.
- **Proposals**: `GovActionRecord.deposit` under `PREFIX_GOV_ACTION`. Governance Phase 1 removes the enacted,
  expired and dropped proposals (`GovernanceEpochProcessor.refundAndRemove`), and `removeProposal` journals it.
- **AdaPot**:
  - The rewards step writes it with `deposits = total_dep`, journalled in `PHASE_REWARDS`.
  - Governance Phase 2 rewrites the treasury, journalled in `PHASE_GOV_RATIFY`.
  - The "artifact-finalize" step verifies treasury and reserves.
- **Consumers of `deposits`** only report it:
  - REST (`AdaPotDto`);
  - the debug AdaPot endpoints;
  - the ADA_POT archive dataset;
  - the testkit comparator.
- Nothing computes with it:
  - rewards take treasury, reserves, fees and parameters;
  - `verifyAdaPot` and `expected_ada_pots_*.json` check only treasury and reserves;
  - `getTotalDeposited()` reaches Scalus as `State.deposited` (`CertStateBridge.scala:153`), which Scalus uses only
    for internal assertions.

The gap: Yano's `deposits` was stake-key + DRep, and Haskell's is stake-key + pool + DRep + proposal. At mainnet
epoch 656, before the ADR-056 fixes, Yano reported 3,461,916 ADA against Koios's 5,308,404 ADA (issue #49).

## Decision

Keep `total_dep` as the stake-key and DRep counter. At the end of each epoch boundary, **derive** the four categories
from the existing records, and store them in the AdaPot. No new incrementally maintained counter is added.

### Per-category derivation (end of boundary N)

| Category | Source | Records (mainnet / preview) | Haskell |
|---|---|---|---|
| stake keys | `total_dep − Σ PREFIX_DREP_REG.deposit` | — | `oblStake` |
| pools | `Σ PREFIX_POOL_DEPOSIT.deposit` | ≈3,000 / 727 | `oblPool` |
| DReps | `Σ PREFIX_DREP_REG.deposit` | ≈1,050 / 9,164 | `oblDRep` |
| proposals | `Σ GovActionRecord.deposit` over `getAllActiveProposals()` | tens | `oblProposal` |
| **total** (`deposits`) | the sum of the four | — | `utxosDeposited` |

- **Stake keys by subtraction.** The code shows that the subtraction is exact:
  - The only certificate branches that move the DRep share of `total_dep` also write and delete `PREFIX_DREP_REG`,
    in the same batch (`DefaultAccountStateStore.processCertificate`, `RegDrepCert` / `UnregDrepCert`).
  - So `total_dep − Σ PREFIX_DREP_REG` is the sum of the stake-account deposits, without scanning ≈1.3M accounts.
  - Because ADR-056's residue check cannot see DRep-sum errors, the new exact per-epoch Koios checks are the first
    external proof. The tip audit adds a Yano-internal one.
  - `depositObligations()` fails if the difference is negative.
- **Cost** (estimated): three small prefix scans plus the active proposals, well under 100 ms. The `artifact-finalize`
  telemetry phase measures it during the resync.
- **Shared helper.** One helper, `sumDeposits`, counts and sums the records under a key prefix. It serves pools, DReps
  and the tip audit's account scan. The proposal sum reuses `GovernanceStateStore.getAllActiveProposals()`.
- **Fix at the source: genesis staking.** The Shelley-genesis staking bootstrap stops adding pool deposits to
  `total_dep`. The genesis AdaPot gets the same four categories: keys from the genesis delegations, and pools from the
  genesis pools.
- **Fix at the source: the key deposit.** A legacy `StakeRegistration` was charged `getKeyDeposit(0)`. It is now
  charged the epoch-effective `keyDeposit`:
  - `effectiveParams()` returns the `EpochParamTracker` when it is enabled, and the base provider otherwise.
  - The tracker's `resolve` does an exact lookup, or a floor lookup in dev mode.
  - A new pool's lifecycle deposit uses the same helper, instead of the base provider.
  - `InMemoryAccountStateStore` now uses the current epoch too.
  - Public networks see no change: `keyDeposit` and `poolDeposit` have never changed there.
- **No silent clamp.** `applyBlock` throws when `total_dep` would become negative. Every refund subtracts a deposit
  that the counter added, so a negative total means corrupt state.
  - Rollback restores the journalled previous value, and replay applies the same deltas, so replay cannot reach a
    negative total.
  - A deregistration of an account Yano never registered refunds 0.

### Where it is computed

`EpochBoundaryProcessor` step 7 ("artifact-finalize") is the only place the categories are computed
(`finalizeDeposits`).

- It runs after rewards, SNAP, POOLREAP and both governance phases. Each of those phases has committed its own batch,
  so step 7 has no pending batch. No block of epoch N has been applied yet. The state it reads is therefore Haskell's
  post-`EPOCH` state, which db-sync records for epoch N.
- Step 7 does three things:
  1. reads the stored pot and calls `DefaultAccountStateStore.depositObligations()`;
  2. writes the pot with `deposits = obligations.total()` and the categories, through an explicit
     `adaPotTracker.storeAdaPot(newEpoch, finalPot)`;
  3. then `verifyAdaPot`, `contributeAdaPotArtifact` and the staging writer use that final pot.
- The explicit write matters: `contributeAdaPotArtifact` returns without writing when the ADA_POT artifact is not
  captured.
- Deposit finalisation runs for every stored pot. Verification and artifacts keep their `newEpoch >= 2`
  condition.
- Earlier steps no longer compute deposits:
  - The rewards step carries the previous pot's `deposits` forward as a placeholder, with no categories. Its
    `getTotalDeposited()` read is removed.
  - The Shelley-start bootstrap pot starts with zero obligations, because no Shelley certificate precedes the
    Shelley start. On a devnet whose first boundary jumps past epoch 0, this is the epoch-0 pot, which step 7 does not
    revisit. A devnet with genesis staking already has its genesis pot.
  - Governance Phase 2's treasury adjustment carries the categories through.

### Rollback safety

- **No new mutable counter.** Every input already has a journal:
  - `total_dep`: the block delta;
  - `PREFIX_DREP_REG`: block deltas;
  - `PREFIX_POOL_DEPOSIT`: block deltas and `PHASE_POOLREAP`;
  - `PREFIX_GOV_ACTION`: block deltas and `PHASE_GOV_ENACT`.
- **The step-7 write is unjournaled**, and two mechanisms undo it:
  1. The rewards phase journalled `adaPotKey(N)` with its pre-boundary value, normally absent.
     `undoBoundaryDeltaSlotBounded` applies the phases in reverse, and the ops within a phase in reverse. Undoing
     boundary N therefore restores that value, whatever step 7 wrote.
  2. `rollbackInternal` explicitly deletes every AdaPot for epochs beyond the rollback target's epoch. This also
     covers the unjournaled Shelley-start bootstrap pot.
- **Rollback inside epoch N** (not across its boundary) keeps `adaPot(N)`. That is correct, because the pot describes
  the boundary.
- **Resume.** Step 7 has no step gate, and re-runs on every resume before `STEP_COMPLETE`. It reads only committed
  state, so recomputing gives the same pot.
  - One caveat: the "previous boundary incomplete, re-processing first" path (`EpochBoundaryProcessor`,
    `processEpochBoundary`) can run boundary N−1 after an epoch of blocks. Step 7 then sums the current sets.
  - The other steps it re-runs have the same exposure; the rewards step's `getTotalDeposited()` read had it before
    this change.
  - No change is made for this path; it is documented in `finalizeDeposits`.
- **Tests** (`ledger-state`):
  - `BoundaryRecoveryIdempotencyTest.adaPot_rewardsThenGovernance_rollbackRestoresPreRewardValue` adds the plain
    step-7 `storeAdaPot` before the rollback. The pot is still removed.
  - `adaPot_finalisedDeposits_keptByRollbackInsideItsEpoch_laterPotDeleted` rolls back to a slot inside epoch N:
    - the block is undone;
    - the finalised pot is kept byte for byte;
    - a later epoch's pot is deleted.
  - `DepositObligationsTest.obligationsFollowEveryCategoryAndItsRollback` applies:
    - a key deregistration;
    - a DRep deregistration;
    - a proposal.
    It then rolls back, and checks that every category returns to its earlier value.

### Storage, API and DTO

- `LedgerStateProvider` (core-api) defines
  `record DepositObligations(BigInteger stakeKeys, BigInteger pools, BigInteger dreps, BigInteger proposals)` with
  `total()`. Both `AdaPotSnapshot` and `AccountStateCborCodec.AdaPot` carry it as one nullable
  `depositObligations` field. `AdaPot.withDepositObligations` sets the categories and their total together.
- CBOR: AdaPot map key `8` holds `[stakeKeys, pools, dreps, proposals]`. When key 8 is absent, the pot was written
  before this ADR and decodes as `null`. Keys 0–7 are unchanged.
- `AdaPotDto` (`/api/v1/epochs/{n}/adapot`, `/latest/adapot`, `/adapots`):
  - `deposits` keeps its name, with its ledger meaning: the full obligation total.
  - It adds `deposits_key`, `deposits_pool`, `deposits_drep` and `deposits_proposal`, as lovelace strings.
  - `null` categories mark a pot written before this ADR. There is no version flag.
  - `deposits_drep` and `deposits_proposal` mean what they mean in Koios, and Koios's `deposits_stake` is
    `deposits_key + deposits_pool`. The name `deposits_stake` is not reused.
- `GET /api/debug/deposits` (admin group) is a read-only audit at the tip, taken under one RocksDB snapshot. It
  returns `total_dep`, and counts and sums of the stake accounts, DReps and pools. It also returns
  `stake_keys_consistent`, which is true when `Σ PREFIX_ACCT.deposit == total_dep − Σ PREFIX_DREP_REG.deposit`. It
  scans every account, so it is meant for operators.
- Archive: the ADA_POT dataset keeps its eight columns, and its `deposits` column carries the new total. The
  categories are not archived, because that needs an archive schema and projection version bump. It is a follow-up,
  only if a consumer needs it. See `docs/archive/DUCKLAKE_PROJECTION_SCHEMA.md`.

### Migration and resync

There is no backfill: the pool and proposal sets of past epochs are not kept in a summable form.

- Pots written before the upgrade keep the old key + DRep `deposits`, with `null` categories.
- Every boundary after the upgrade writes the Haskell total.
- A full aligned history needs a sync from genesis. This PR is stacked on #155, whose fixes already require one.
- Devnets with Shelley-genesis pools should be recreated, because their `total_dep` included the genesis pool
  deposits.

## Release note

The operator-facing note is in [docs/UPGRADING.md](../docs/UPGRADING.md) ("ADR-058 AdaPot deposits").

## Verification plan

Scope: **preprod and preview now. A mainnet resync is recommended as a follow-up**, because mainnet is the only
network with proposal enactments, sibling drops and DRep churn at scale.

1. **Unit and boundary tests** (`ledger-state`, done). Each asserts the categories, and the total where relevant.
   - `DepositObligationsTest`:
     - legacy and Conway key registration and deregistration;
     - DRep registration and deregistration;
     - pool registration;
     - proposal submission;
     - rollback;
     - the tip audit;
     - the epoch-effective key deposit;
     - the thrown negative total.
   - `DefaultAccountStateStorePoolLifecycleTest`:
     - a retiring pool keeps its obligation until POOLREAP, then it drops to 0;
     - an update keeps the one lifecycle deposit.
   - `EpochBoundaryProcessorTest.depositFinalisationStoresTheObligationsWithoutAnArtifactCapture`.
   - The rollback tests above.
   - `DefaultAccountStateStoreGenesisBootstrapTest` checks the genesis categories.
   - `EpochResourceAdaPotTest` covers the DTO fields and the `null` legacy categories.
2. **`qa/tools/koios_compare.py`**, extended:
   - Every epoch whose Yano pot has categories is compared exactly:
     - `deposits_key + deposits_pool` against `deposits_stake`;
     - `deposits_drep` against `deposits_drep`;
     - `deposits_proposal` against `deposits_proposal`;
     - `deposits` against the sum of all three Koios fields.
   - Legacy pots are listed, not compared. The residue check is removed.
   - The tip audit is counted as Yano-internal checks:
     - `account_deposits == total_deposited − drep_deposits`;
     - `pool_deposits == pool_count × pool_deposit`.
   - The pool count is also shown next to Koios `pool_list`, for information only. Koios lists pools registered and
     retired in one transaction as still registered.
3. **Fresh sync from genesis on preprod and preview**, then `koios_compare.py` at the tip. Expected:
   - zero deposit mismatches at every epoch;
   - `deposits_drep = deposits_proposal = 0` before Conway;
   - both tip audit checks pass.
4. **No monetary change**:
   - the built-in AdaPot verification passes at every boundary;
   - treasury, reserves and fees stay at 0 Koios mismatches;
   - on each network, `/epochs/adapots` treasury, reserves, fees and the four reward fields equal the ADR-056
     Phase 7c run's, epoch by epoch.
5. **Boundary time**: the `artifact-finalize` phase grows by less than 100 ms on preview, which has the largest DRep
   set.
6. **Follow-up: a mainnet resync**, then `koios_compare.py` for epochs 209 to the tip.

## Risks

| Risk | Mitigation |
|---|---|
| The stake-key category inherits any `total_dep` or DRep-sum error; ADR-056's residue check could not see a DRep-sum error | Code inspection (same branches, same batch); exact per-epoch Koios checks for each category; the tip audit's full account scan |
| A chainstate mixes legacy and new `deposits` values | `null` categories mark legacy rows; release note; a sync from genesis is already required by #155 |
| The archive `deposits` column changes meaning without a schema bump | Archives are rebuilt from the resynced chainstate; documented in the schema and the release note |
| A reader sees the placeholder pot between the rewards step and step 7 | Visible only while the boundary runs; the categories stay `null` until step 7 |
| The "re-processing first" path sums state after an epoch of blocks | The same exposure as the other steps it re-runs; documented in `finalizeDeposits` |
| Devnet `total_dep` changes (genesis pool deposits removed), and this reaches Scalus `State.deposited` | Review confirmed there is no Scalus side effect: it uses the value only for assertions. Devnets are recreated |
| `applyBlock` now throws on a negative total, where it used to clamp | Only corrupt state can reach it. Failing is the point, and the message names the block and delta |

## Alternatives considered

- **Running counters for pools and proposals**: deltas in `processCertificate`, POOLREAP and governance Phase 1.
  Rejected: four new journalled write sites, a counter that must be backfilled, and `total_dep` would change
  meaning for Scalus.
- **Scanning `PREFIX_ACCT` at every boundary for the stake-key category.** Rejected: it would take seconds over
  ≈1.3M records when the subtraction is exact. The full scan is kept for the operator audit.
- **Total only, no categories.** Rejected: the categories cost the same scans. Without them, `koios_compare.py`
  could check only the total.
- **Keep the existing implementation, and document `deposits` as key + DRep.** Rejected: alignment is simple and
  rollback-safe, and the old value matched no ledger or db-sync quantity.

## Implementation

Changed files:

- `core-api/.../api/account/LedgerStateProvider.java`: `DepositObligations`, and `AdaPotSnapshot.depositObligations`.
- `ledger-state/.../AccountStateCborCodec.java`: `AdaPot.depositObligations`, CBOR key 8, and
  `withDepositObligations`.
- `ledger-state/.../DefaultAccountStateStore.java`:
  - `depositObligations()`, `auditDeposits()` and the `sumDeposits` helper;
  - the genesis staking fix;
  - `effectiveParams()` for the key and pool deposits;
  - the negative-total failure;
  - the snapshot mapping.
- `ledger-state/.../InMemoryAccountStateStore.java`: the key deposit of the current epoch.
- `ledger-state/.../EpochBoundaryProcessor.java`: `finalizeDeposits` at step 7, the rewards-step placeholder, the
  bootstrap pot, and the governance adjuster carrying the categories.
- `app/.../epochs/dto/AdaPotDto.java`: the `deposits_*` fields.
- `app/.../accounts/DebugSnapshotResource.java`: `GET /api/debug/deposits`.
- `qa/tools/koios_compare.py` and `qa/README.md`: exact per-category checks and the tip audit.
- `docs/UPGRADING.md` and `docs/archive/DUCKLAKE_PROJECTION_SCHEMA.md`.
- Tests:
  - `DepositObligationsTest` (new);
  - `BoundaryRecoveryIdempotencyTest`;
  - `EpochBoundaryProcessorTest`;
  - `DefaultAccountStateStorePoolLifecycleTest`;
  - `DefaultAccountStateStoreGenesisBootstrapTest`;
  - `EpochResourceAdaPotTest`;
  - `NetworkResourceTest`.
