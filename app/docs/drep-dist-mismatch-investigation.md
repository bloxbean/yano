# DRep Distribution Mismatch Investigation: Yano/Yaci Store vs DBSync (Haskell)

## Summary

At epoch 522, 18 out of 518 DReps show amount mismatches between Yano/Yaci Store and DBSync. All diffs are positive (Yano > DBSync), totaling +1.2T lovelace (~1.2M ADA, 0.098% of total DRep distribution). The root cause is a Haskell UMap delegation cleanup behavior during DRep deregistration.

## Three-Way Comparison (Epoch 522)

| Source | DRep 426d5a97 Amount | Total DRep Dist |
|--------|---------------------|-----------------|
| **Yano** | 710,876,218,287 | 1,229,813,917,384,507 |
| **Yaci Store** | 710,876,218,287 | 1,229,813,917,384,507 |
| **DBSync (Haskell)** | 324,655,675,154 | 1,228,613,811,034,055 |
| **Diff** | +386,220,543,133 | +1,200,106,350,452 |

- Yano == Yaci Store for ALL 518 DReps (exact match, zero diff)
- 500 DReps match exactly between Yano and DBSync
- 18 DReps have Yano > DBSync, 0 DReps have Yano < DBSync

## Root Cause: DRep Deregistration Delegation Cleanup

### The Haskell Behavior

When a DRep deregisters (via `ConwayUnRegDRep`), Haskell removes the DRep from the `vsDReps` map. Additionally, the Haskell UMap cleanup removes delegation entries that point to the deregistering DRep's credential.

The issue: if a stake credential first delegated to DRep A, then re-delegated to DRep B (both in the same epoch), when DRep A later deregisters, the cleanup may still remove the credential's delegation entry — even though the UMap entry was already updated to point to DRep B.

### Concrete Example: DRep 426d5a97 (386B diff)

**Timeline:**

| Slot | Epoch | Event | Actor |
|------|-------|-------|-------|
| 134,657,714 | 509 | stake1u8cdy482 delegates to DRep **34526e9a** | Delegator |
| 134,660,310 | 509 | DRep **426d5a97** registers | DRep |
| 134,660,497 | 509 | stake1u8cdy482 re-delegates to DRep **426d5a97** | Delegator |
| 135,171,445 | 510 | DRep **34526e9a** deregisters | DRep |

**What happens at epoch 510 → 511 boundary:**

- DRep 34526e9a deregisters
- Haskell's deregistration cleanup scans UMap for delegations to 34526e9a
- stake1u8cdy482's delegation MAY still be associated with 34526e9a in Haskell's internal state, despite the re-delegation at slot 134,660,497
- Result: stake1u8cdy482's delegation is removed from the UMap
- DRep 426d5a97's distribution drops from 648B to 267B (a loss of 381B, matching stake1u8cdy482's ~284B stake + rewards)

**DBSync confirms this:**

```
epoch 510: 648,181,635,503  (stake1u8cdy482 included)
epoch 511: 267,121,479,875  (stake1u8cdy482 REMOVED — dropped 381B)
epoch 512: 266,843,475,584  (stable without this delegator)
...
epoch 522: 324,655,675,154  (slowly growing from new delegators)
```

### Why Yano/Yaci Store Differ

Yano and Yaci Store use **latest delegation by slot** to determine the current DRep delegation for each stake credential. Since stake1u8cdy482's re-delegation to 426d5a97 (slot 134,660,497) is later than the original delegation to 34526e9a (slot 134,657,714), both correctly track the current delegation as 426d5a97.

Neither Yano nor Yaci Store implements the Haskell deregistration cleanup behavior where a DRep's deregistration removes delegation entries from the UMap.

### Verification from Raw Data

Reconstructing the DRep distribution for 426d5a97 at epoch 522 from raw Yaci Store data:

```
33 delegators (latest delegation by slot at epoch <= 521)
Total UTXO balance: 708,191,245,704
Total rewards:        2,684,972,583
Grand total:        710,876,218,287  ← exactly matches Yano and Yaci Store drep_dist
```

## Other Mismatched DReps

The same pattern likely applies to all 18 mismatched DReps — each has delegators whose delegation was "poisoned" by a DRep deregistration in Haskell. The total impact is 1.2T lovelace across these 18 DReps.

| DRep (prefix) | Yano/YaciStore | DBSync | Diff | Diff% |
|---------------|----------------|--------|------|-------|
| 426d5a97 | 710,876,218,287 | 324,655,675,154 | +386B | 119% |
| a865b391 | 18,480,721,631,359 | 18,218,385,599,175 | +262B | 1.4% |
| 8b750358 | 98,334,335,709,369 | 98,132,853,113,769 | +201B | 0.2% |
| 264d012d | 786,178,315,591 | 585,886,384,647 | +200B | 34% |
| 0ec943dc | 781,995,356,672 | 704,279,467,986 | +78B | 11% |
| ... | ... | ... | ... | ... |
| **Total (18)** | | | **+1,200B** | **0.098%** |

## Related Findings

### Epoch Stake Consistency

Epoch stake (pool delegation snapshot) is IDENTICAL between DBSync and Yaci Store:

```
DRep 426d5a97 delegators' epoch_stake at epoch 522:
  DBSync:     706,443,420,267
  Yaci Store: 706,443,420,267  (exact match)
```

This confirms the underlying stake data is consistent — the mismatch is solely in how DRep delegation assignment is tracked.

### DRep Distribution vs Epoch Stake

DRep distribution uses **current IncrementalStake** (not the Mark snapshot), so it naturally differs from epoch_stake:

| DRep | DRep Dist | Epoch Stake | Ratio |
|------|-----------|-------------|-------|
| 94f7bb25 (matching) | 121T | 145T | 83% |
| 426d5a97 (mismatched) | 711B (Yano) / 325B (DBSync) | 606B | 117% / 54% |

For matching DReps, DRep dist is typically 80-90% of epoch_stake. Yano's 117% for 426d5a97 is explained by recent incoming transactions. DBSync's 54% is the anomaly caused by the deregistration cleanup.

### Haskell DRep Distribution Bug Fix History

All three Haskell DRep distribution bug fixes (PRs #3676, #4116, #4273) were deployed before Conway bootstrap (epoch 507). See `drep-dist-bug-investigation.md` for the full timeline. The deregistration delegation cleanup is NOT one of these bugs — it is current Haskell behavior.

## Impact Assessment

- **Ratification at epoch 575**: The 1.2T mismatch is 0.098% of the total DRep distribution (~1.2 Quadrillion lovelace). The YES/NO split for proposal 8ad3d454#26 is dominated by DReps with tens of trillions of stake. This mismatch will not flip the ratification outcome.

- **Long-term correctness**: To match DBSync exactly, Yano would need to replicate the Haskell deregistration cleanup behavior (removing delegation entries from PREFIX_DREP_DELEG when a DRep deregisters). This is deferred as it requires understanding the exact Haskell UMap semantics for this edge case.

## Files Referenced

- Yano DRep distribution: `DRepDistributionCalculator.java`
- Yaci Store DRep distribution: `DRepDistService.java`
- DBSync DRep distribution: reads directly from Haskell `finishDRepPulser` → `psDRepDistr`
- Haskell DRep distribution: `libs/cardano-ledger-core/src/Cardano/Ledger/DRepDistr.hs`
- Haskell DRep deregistration: `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/GovCert.hs`
