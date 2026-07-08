# DRep Distribution Bug Investigation: Haskell Ledger History & Cross-Implementation Analysis

## Timeline of Haskell DRep Distribution Bug Fixes

### The Three Progressive Fixes

| PR | Date | conway version | What it fixed |
|----|------|---------------|---------------|
| **#3676** | Aug 24, 2023 | conway-1.8.0.0 | Used IncrementalStake instead of Mark SnapShot. Fixed: credentials with UTxO stake but no pool delegation were excluded from DRep distribution. |
| **#4116** | Feb 28, 2024 | conway-1.13.0.0 | Added reward balances to DRep voting power. Fixed: credentials with rewards but no UTxO were excluded. |
| **#4273** | Apr 26, 2024 | conway-1.14.0.0 | Switched iteration from StakeDistr to UMap. The definitive fix: ALL credentials with a DRep delegation are included, regardless of pool delegation or UTxO presence. |

### Original Bug (pre-PR #3676)

The original `drepDistr` iterated over the **Mark SnapShot's stake distribution** (`ssStakeDistrL`). This snapshot only contained credentials that were **delegated to a stake pool**. Credentials delegated to a DRep but NOT to a pool were invisible.

```haskell
-- BUGGY (original): iterates pool-delegated stake only
freshDRepPulser n es =
    startDRepDistr n (es ^. epochStateUMapL) (es ^. epochStateRegDrepL)
        (es ^. epochStateStakeDistrL)  -- Mark SnapShot (pool-only)
```

### Fix #1: IncrementalStake (PR #3676)

Changed the iteration source from Mark SnapShot to IncrementalStake:

```haskell
-- FIXED: uses IncrementalStake (all UTxO holders, regardless of pool delegation)
freshDRepPulser n es =
    startDRepDistr n (es ^. epochStateUMapL) (es ^. epochStateRegDrepL)
        (VMap.fromMap (compactCoinOrError <$> (es ^. epochStateIncrStakeDistrL)))
```

This fixed credentials with UTxO stake but no pool delegation. However, credentials with only rewards (no UTxOs) were still excluded because IncrementalStake only tracks UTxO-based stake.

### Fix #2: Add Rewards (PR #4116)

Modified `accumDRepDistr` to look up the UMElem for each credential and ADD the reward balance:

```haskell
stakeWithRewards = case umElemRDPair umElem of
    Nothing -> stake
    Just rdPair | CompactCoin compactReward <- rdReward rdPair ->
        CompactCoin (compactReward + compactStake)
```

### Fix #3: UMap Iteration (PR #4273)

The definitive fix. Changed the primary iteration source from StakeDistr (UTxO-based) to the UMap (all credentials):

> "The DRepDistr was being calculated by iterating over the StakeDistr, which meant that delegations that have only rewards and no UTxOs would be left out. This commit changes the calculation to iterate over the UMap in chunks instead."

After this fix, `computeDRepDistr` iterates ALL registered accounts. For each account with a DRep delegation, it adds: UTxO stake (from IncrementalStake) + reward balance + proposal deposits.

## Deployment on Mainnet

### Node Version to Ledger Version Mapping

| cardano-node | Release Date | conway version | All 3 DRep fixes? |
|-------------|-------------|---------------|-------------------|
| 9.0.0 | 2024-07-03 | conway-1.16.0.0 | **YES** |
| 9.1.0 | 2024-07-24 | conway-1.16.0.0 | **YES** |
| 9.1.1 | 2024-09-02 | conway-1.16.1.0 | **YES** |
| 9.2.0 | 2024-09-18 | conway-1.16.1.0 | **YES** |
| 9.2.1 | 2024-09-24 | conway-1.16.2.0 | **YES** |
| 10.1.1 | 2024-10-24 | conway-1.17.2.0 | **YES** |
| 10.1.4 | 2025-01-06 | conway-1.17.4.0 | **YES** |

### Key Finding: No "buggy bootstrap phase" on mainnet

Conway bootstrap started at epoch 507 (~Sep 2, 2024) with **cardano-node 9.1.0** using **conway-1.16.0.0**. All three DRep distribution fixes had been included since conway-1.14.0.0 (May 2024). The DRep distribution algorithm was **identical and correct** from the very first Conway epoch on mainnet through all subsequent versions.

**There is no need to reproduce a buggy bootstrap behavior** — the bugs were fixed months before Conway launched on mainnet. The correct behavior (including non-pool-delegated credentials) was active from epoch 507 onward.

## How DBSync Gets DRep Distribution

**DBSync reads DRep distribution directly from the Haskell node's ledger state.** It does NOT compute it independently.

The data flow:
1. At epoch boundaries, DBSync calls `finishDRepPulser` on the `DRepPulsingState` from the Haskell `NewEpochState`
2. This returns a `PulsingSnapshot` containing `psDRepDistr` — the DRep stake distribution map
3. DBSync bulk-inserts each entry into the `drep_distr` table — no transformation, no independent calculation

**Implications**:
- `drep_distr` values ARE what Haskell computed at each epoch boundary
- On resync, the Haskell ledger library recomputes the distribution (replaying blocks), so the values match the ledger library version used at sync time
- Since all DRep dist fixes were present in the running nodes, DBSync's `drep_distr` includes non-pool-delegated credentials

## Yaci Store DRep Distribution

Yaci Store's `DRepDistService.java` computes DRep distribution independently (not from Haskell ledger state). It:
1. Creates `ss_drep_ranked_delegations` from the `delegation_vote` table (DRep delegations, no pool filter)
2. Joins with `stake_address_balance`, `reward`, `reward_rest` tables for stake amounts
3. Groups by DRep and sums

Yaci Store also includes non-pool-delegated credentials (no pool delegation filter in the SQL).

## Revised Understanding of the 18 Mismatches

Since ALL four implementations (Haskell/DBSync, Yaci Store, Yano, Amaru) should include non-pool-delegated credentials, the 18 mismatches at epoch 522 must have a different root cause:

- **All diffs are positive** (Yano > DBSync) — Yano over-counts by +1.2T total
- **Zero negative diffs** — no DRep has less stake in Yano
- **Growing mismatch count** (3 at epoch 508 -> 18 at epoch 522)
- **Some diffs are stable** (~14.9B for one DRep) suggesting one-time events
- **Some diffs are growing** suggesting reward accumulation drift

Possible remaining root causes:
1. **Reward crediting timing**: Yano credits rewards at `PostEpochTransition`, then DRep distribution reads the credited balance. If the crediting includes rewards that aren't yet spendable, Yano would over-count.
2. **UTXO balance aggregation**: Yano's `UtxoBalanceAggregator` scans all UTxOs. If it includes UTxOs that the IncrementalStake would exclude (e.g., from transactions in the boundary block itself), it would over-count.
3. **Spendable reward_rest timing**: Though only 2 reward_rest entries exist in the epoch range, the crediting timing could cause small differences.

## Open Question

The root cause of the +1.2T excess needs further investigation. It is NOT about which credentials are included (all implementations include non-pool-delegated). It's likely about the AMOUNT attributed to specific credentials — either reward balances or UTxO balances being slightly different.
