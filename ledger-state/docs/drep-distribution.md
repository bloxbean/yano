# DRep Distribution Calculation

## Overview

DRep (Delegated Representative) distribution is the mapping from each registered DRep to the total ADA voting power delegated to them. It is computed at every epoch boundary and is the primary input to governance ratification: each active proposal's vote tally is weighted by this distribution. If DRep distribution is wrong, proposals can be ratified (or rejected) incorrectly, making it a consensus-critical calculation.

The distribution assigns a `BigInteger` (lovelace) to each DRep key, including the two virtual DReps `ALWAYS_ABSTAIN` and `ALWAYS_NO_CONFIDENCE`. Only delegations to *registered* (non-deregistered) DReps are counted. A credential's voting power is the sum of its UTXO balance, unwithdraw rewards, spendable `reward_rest`, and proposal deposits.

### Key Files

| File | Role |
|------|------|
| `DRepDistributionCalculator.java` | Core distribution calculation |
| `DefaultAccountStateStore.java` | Delegation storage, reverse index, PV10 rebuild |
| `EpochBoundaryProcessor.java` | Orchestrates the rebuild at epoch boundary |
| `GovernanceEpochProcessor.java` | Calls distribution calculator during governance processing |


## Haskell Reference Model

The canonical behavior is defined by the Haskell cardano-ledger, specifically `computeDRepDistr` in `Conway.Governance.DRepPulser` (file: `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/DRepPulser.hs`). Understanding the Haskell model is essential because Yano must produce byte-identical results.

### `computeDRepDistr`

Haskell iterates all stake accounts and checks whether each credential's DRep delegation target is in the set of registered DReps:

```haskell
computeDRepDistr ::
  Map (Credential 'Staking) (DRep) ->   -- forward delegations (from UMap)
  Map (Credential 'DRepRole) (DRepState) ->   -- registered DReps
  Map (Credential 'Staking) (CompactForm Coin) ->   -- UTXO stake
  Map (Credential 'Staking) (CompactForm Coin) ->   -- rewards
  ...
  Map (DRep) (CompactForm Coin)
```

For each credential, if its delegation target is a registered DRep (checked via `Map.member cred regDReps`), the credential's stake (UTXO + rewards + proposal deposits) is accumulated to that DRep.

### `drepDelegs` (Reverse Delegation Set)

Inside the UMap, Haskell maintains `drepDelegs`: a reverse index mapping each DRep to the set of credentials delegated to it. This reverse index is used by `ConwayUnRegDRep` (DRep deregistration) to find and clear all forward delegations pointing to the deregistered DRep.

The critical subtlety is that `drepDelegs` is an internal UMap data structure, not a separately maintained map. Its correctness depends on UMap's internal invariants being upheld by all operations that modify delegations.

### `ConwayUnRegDRep` Cleanup

When a DRep deregisters, Haskell's `ConwayUnRegDRep` handler:

1. Looks up the DRep's entry in `drepDelegs` to find all delegators
2. For each delegator, deletes their forward DRep delegation
3. Removes the DRep from the registration map

This cleanup is essential: without it, credentials would remain delegated to a non-existent DRep, and their voting power would be silently lost (the distribution calculator skips unregistered DReps).


## PV9 Bug-Compat Behavior

Protocol version 9 (Conway launch) has a known bug in the Haskell UMap that causes `drepDelegs` to accumulate stale entries. Yano must replicate this bug exactly to produce matching DRep distributions.

### The Bug: Stale Reverse Entries on Re-Delegation

When a credential re-delegates from DRep A to DRep B, the Haskell UMap:

1. Updates the forward delegation: `credential -> DRep B` (correct)
2. Adds a reverse entry: `DRep B -> {credential}` (correct)
3. **Does NOT remove** the old reverse entry: `DRep A -> {credential}` (BUG)

This means `drepDelegs` for DRep A still contains `credential` even though the credential is now delegated to DRep B.

### Consequence: Over-Clearing on DRep Deregistration

If DRep A later deregisters:

1. `ConwayUnRegDRep` looks up `drepDelegs[DRep A]` and finds `{credential}` (stale)
2. It clears `credential`'s forward delegation, even though `credential` is now delegated to DRep B
3. Result: `credential`'s voting power is lost entirely

This is observable on mainnet: credentials that re-delegated and whose old DRep later deregistered have their delegation cleared, reducing their DRep's voting power.

### Yano's PV9 Replication

Yano replicates this bug using `PREFIX_DREP_DELEG_REVERSE` (byte `0x04`), a RocksDB key family that mirrors `drepDelegs`. In `delegateToDRep`:

```
// PV9 bug-compat: on re-delegation from credential DRep A to credential DRep C,
// do NOT remove A's reverse entry when protocolMajor < 10.
boolean removeOldReverse = (protocolMajor >= 10) || !isCredentialDRep(newDrepType);
```

In PV9 (protocol major < 10):
- Re-delegation from one credential DRep to another credential DRep: **old reverse entry preserved** (stale)
- Re-delegation from credential DRep to virtual DRep (ABSTAIN/NO_CONFIDENCE): **old reverse entry removed** (Haskell removes it because virtual DReps have no reverse entries)

This ensures that when `clearDRepDelegationsForDeregisteredDRep` scans the reverse index during DRep deregistration, it finds the same set of delegators that Haskell's `ConwayUnRegDRep` finds -- including stale entries.

### Reverse Index Key Layout

```
PREFIX_DREP_DELEG_REVERSE (0x04) | drepType (1 byte) | drepHash (28 bytes) | delegatorCredType (1 byte) | delegatorHash (28 bytes)
```

This layout allows efficient prefix-scan of all delegators for a given DRep using `drepDelegReverseSeekPrefix(drepType, drepHash)`.


## PV10 Hardfork Reverse-Index Rebuild

Protocol version 10 fixes the stale `drepDelegs` bug. At the PV10 hardfork boundary, Haskell runs `updateDRepDelegations` (in `HardFork.hs`) which rebuilds `drepDelegs` from scratch using only current forward delegations.

### Haskell's `updateDRepDelegations`

```haskell
updateDRepDelegations ::
  Map (Credential 'DRepRole) (DRepState) ->  -- registered DReps
  UMap ->
  UMap
```

This function:

1. Resets all `drepDelegs` entries to empty
2. Iterates all forward delegations and rebuilds reverse entries
3. Removes dangling forward delegations (delegations to DReps that no longer exist)

After this rebuild, `drepDelegs` is clean: every reverse entry corresponds to an actual current forward delegation, and no stale entries remain.

### Yano's PV10 Rebuild

Yano implements this in `DefaultAccountStateStore.rebuildDRepDelegReverseIndexIfNeeded()`, called from `EpochBoundaryProcessor` at the epoch boundary:

```java
// EpochBoundaryProcessor.java (step 4c)
Set<String> registeredDRepIds = governanceEpochProcessor.getRegisteredDRepIds();
snapshotCreator.rebuildDRepDelegReverseIndexIfNeeded(newEpoch, registeredDRepIds, ep);
```

The rebuild executes in three steps:

**Step 1: Delete all existing reverse entries**

```java
byte[] seekPrefix = new byte[]{PREFIX_DREP_DELEG_REVERSE};
// iterate and delete all keys with this prefix
```

**Step 2: Iterate forward delegations and rebuild**

For each `PREFIX_DREP_DELEG` entry:
- If the DRep is a credential DRep AND is registered: create a new `PREFIX_DREP_DELEG_REVERSE` entry
- If the DRep is a credential DRep AND is NOT registered: delete the dangling forward delegation
- If the DRep is virtual (ABSTAIN/NO_CONFIDENCE): no reverse entry needed

**Step 3: Atomic commit with marker**

The entire rebuild runs in a single `WriteBatch`, including a marker key (`meta.pv10_drep_reverse_rebuild`) that prevents the rebuild from running again on restart.

### Restart Safety

The marker-gated approach ensures idempotent behavior:

```java
if (db.get(cfState, MARKER_PV10_REVERSE_REBUILD) != null) return;  // already done
```

- First PV10 epoch: marker absent, rebuild runs, marker written atomically with rebuild
- Restart during same epoch: marker present, rebuild skipped
- Subsequent epochs: marker present, rebuild skipped

The rebuild is also guarded by protocol version check (`newMajor < 10` returns early), so pre-PV10 epochs never trigger it.

### Ordering in Epoch Boundary

The rebuild runs at step 4c in `EpochBoundaryProcessor.processEpochBoundary()`, **before** governance processing (step 5). This ordering is critical:

```
4c. PV10 hardfork: rebuild DRep delegation reverse index
5.  Conway governance epoch processing (ratify, enact, expire, refund)
```

If a DRep deregisters during governance processing after the PV10 rebuild, `clearDRepDelegationsForDeregisteredDRep` uses the now-clean reverse index, producing correct cleanup behavior.


## PV10+ Cleanup Behavior

After the PV10 rebuild, the reverse index is clean. In PV10+ code:

```java
// delegateToDRep: PV10+ always removes old reverse entry on re-delegation
boolean removeOldReverse = (protocolMajor >= 10) || !isCredentialDRep(newDrepType);
if (removeOldReverse) {
    // delete old reverse entry
}
```

This means:
- Re-delegation from DRep A to DRep B: A's reverse entry is removed, B's is added
- DRep deregistration cleanup: finds only currently-delegated credentials
- No stale entries accumulate

The system is now in a self-maintaining state where the reverse index stays correct through normal operations.


## Distribution Calculation Flow

`DRepDistributionCalculator.calculate()` is called during Phase 2 of `GovernanceEpochProcessor.processEpochBoundaryAndCommit()`. The inputs are:

| Parameter | Source | Description |
|-----------|--------|-------------|
| `snapshotEpoch` | `previousEpoch` | Epoch N-1, whose snapshot was taken at the N-1/N boundary |
| `utxoBalances` | `UtxoBalanceAggregator` | Pre-aggregated UTXO balances per credential |
| `spendableRewardRest` | `RewardRestStore` | Proposal refunds, treasury withdrawals from Phase 1 |

### Step 1: Build Active DRep Set

Load all DRep states from `GovernanceStateStore` and filter using the tombstone rule:

```java
// Include if never deregistered OR registered after last deregistration
if (prevDeregSlot == null || rec.registeredAtSlot() > prevDeregSlot) {
    activeDReps.put(entry.getKey(), rec);
}
```

This correctly handles:
- **Never-deregistered DReps**: `previousDeregistrationSlot == null` -- included
- **Re-registered DReps**: `registeredAtSlot > previousDeregistrationSlot` -- included
- **Deregistered DReps**: `registeredAtSlot <= previousDeregistrationSlot` -- excluded
- **Expired DReps**: still included (expiry is separate from deregistration; expired DReps remain registered)

### Step 2: Compute Proposal Deposits by Credential

Iterate all active proposals and group deposits by the return address's staking credential:

```java
Map<String, BigInteger> proposalDepositsByCredential = computeProposalDepositsByCredential();
```

The return address is a reward account (1-byte header + 28-byte credential hash). Header bit 4 distinguishes key (0) from script (1) credentials.

### Step 3: Iterate Forward Delegations

Scan all `PREFIX_DREP_DELEG` (byte `0x03`) entries in RocksDB:

```
Key:   PREFIX_DREP_DELEG (0x03) | credType (1 byte) | credHash (28 bytes)
Value: CBOR-encoded { drepType, drepHash, slot, txIdx, certIdx }
```

For each delegation:

#### 3a. Resolve DRep Key

Call `resolveDRepKey(drepType, drepHash, activeDReps, delegSlot)`:

- **Virtual DReps** (ABSTAIN=2, NO_CONFIDENCE=3): always valid, return synthetic keys
- **Credential DReps** (KEY=0, SCRIPT=1):
  - Must be in `activeDReps` (registered, not deregistered)
  - Must pass the defensive timing guard (see below)
  - If either check fails, the delegation is skipped (`skippedDrep++`)

#### 3b. Check Stake Credential Registration

Look up `PREFIX_ACCT` for the delegator credential. If no account exists (the credential was deregistered), skip (`skippedAcct++`).

#### 3c. Compute Total Voting Power

Sum four components for the credential:

```
total = utxoBalance + rewards + rewardRest + proposalDeposits
```

| Component | Source | Description |
|-----------|--------|-------------|
| `utxoBalance` | `utxoBalances` map | Sum of all UTXO outputs at this credential |
| `rewards` | `PREFIX_ACCT` value | Unwithdraw staking rewards |
| `rewardRest` | `spendableRewardRest` map | Proposal refunds + treasury withdrawals from Phase 1 |
| `proposalDeposits` | `proposalDepositsByCredential` map | Deposits for active proposals with this credential as return address |

#### 3d. Accumulate

```java
distribution.merge(drepKey, total, BigInteger::add);
```

Zero-amount entries are included to match DBSync's `drep_distr` table.

### Step 4: Return Distribution

The result is `Map<DRepDistKey, BigInteger>` where `DRepDistKey` is a `record(int drepType, String drepHash)`. This map is passed to the ratification engine for vote tallying.


## Defensive Timing Guard

`resolveDRepKey` includes a check that is not part of the Haskell model but compensates for Yano's tombstone-based DRep state representation:

```java
Long prevDeregSlot = drepState.previousDeregistrationSlot();
if (prevDeregSlot != null && delegSlot <= prevDeregSlot) {
    yield null;
}
```

### Why It Exists

Yano stores DRep state using a tombstone pattern: deregistered DReps retain their record with `previousDeregistrationSlot` set rather than being deleted. When a DRep re-registers, `registeredAtSlot` is updated but `previousDeregistrationSlot` remains.

Consider this scenario:

1. DRep A registers at slot 100
2. Credential X delegates to DRep A at slot 150
3. DRep A deregisters at slot 200 -- `clearDRepDelegationsForDeregisteredDRep` clears X's delegation
4. DRep A re-registers at slot 300
5. Credential Y delegates to DRep A at slot 350

At distribution time, DRep A is in `activeDReps` (registered at 300 > deregistered at 200). If a stale delegation for X at slot 150 somehow survived (e.g., due to a bug or edge case in the cleanup), the timing guard catches it: `delegSlot(150) <= prevDeregSlot(200)` returns null, excluding X.

### Relationship to PV10 Rebuild

The primary correctness mechanism is the PV10 reverse-index rebuild combined with unconditional cleanup on DRep deregistration. The timing guard is a secondary defense-in-depth check. After PV10, the clean reverse index means stale delegations are removed at the source, so the timing guard rarely activates. Before PV10, the stale reverse entries cause the correct (bug-compatible) over-clearing behavior, so the timing guard acts as a safety net for edge cases.


## Verification

**Completed:** The DRep distribution calculation has been verified against DBSync data for **98 consecutive mainnet epochs** (526 through 623), covering:

- The full PV9 era (stale `drepDelegs` bug active)
- The PV10 hardfork boundary (reverse-index rebuild)
- The PV10+ era (clean reverse index, correct cleanup)

All 98 epochs produce byte-identical DRep distribution values compared to DBSync's `drep_distr` table, validating:

- Correct PV9 bug-compatible over-clearing
- Correct PV10 hardfork reverse-index rebuild
- Correct PV10+ cleanup behavior
- Correct stake component aggregation (UTXO + rewards + reward_rest + proposal deposits)
- Correct active DRep filtering (tombstone rule, timing guard)
- Correct virtual DRep handling (ABSTAIN, NO_CONFIDENCE)


## Data Flow Diagram

```
Epoch N-1 / N boundary
         |
         v
  EpochBoundaryProcessor.processEpochBoundary()
         |
         |-- step 4c: rebuildDRepDelegReverseIndexIfNeeded()  [PV10 only, once]
         |      |
         |      |-- Delete all PREFIX_DREP_DELEG_REVERSE entries
         |      |-- Rebuild from PREFIX_DREP_DELEG (forward delegations)
         |      |-- Delete dangling delegations to non-existent DReps
         |      |-- Write MARKER_PV10_REVERSE_REBUILD
         |
         |-- step 5: GovernanceEpochProcessor.processEpochBoundaryAndCommit()
                |
                |-- Phase 1: Enact pending proposals, create reward_rest
                |     |-- commit to RocksDB (treasury withdrawals visible)
                |
                |-- Phase 2: DRep distribution + Ratification
                      |
                      |-- DRepDistributionCalculator.calculate()
                      |     |
                      |     |-- Build activeDReps (tombstone rule)
                      |     |-- Compute proposalDepositsByCredential
                      |     |-- Iterate PREFIX_DREP_DELEG
                      |     |     |-- resolveDRepKey (active + timing guard)
                      |     |     |-- Check PREFIX_ACCT (registered?)
                      |     |     |-- Sum: UTXO + rewards + reward_rest + deposits
                      |     |     |-- Accumulate to DRep
                      |     |
                      |     |-- Return Map<DRepDistKey, BigInteger>
                      |
                      |-- RatificationEngine (uses distribution for vote tallying)
```
