# DRep Expiry Off-by-One Investigation (TODO)

## Observed

At epoch 526 (synced from epoch-525 snapshot), 53 DReps have `effective_expiry` = 528 in Yano but `active_until` = 527 in DBSync. The remaining 523 DReps match exactly.

- All 53 mismatches are exactly +1 (Yano higher)
- All 53 have stored_expiry=528, num_dormant=0
- Other DReps with stored_expiry=528 match DBSync at 528 — so this is NOT a naming convention difference
- The difference likely depends on when/how the DRep was last active (registered, voted, updated)

## Not Related To

- Part E/F DRep distribution fixes (delegation cleanup removal, timing guard removal)
- DRep distribution amounts (perfect match at epoch 526)

## To Investigate

- What distinguishes the 53 mismatched DReps from those that match at 528?
- Check their registration/vote/update history
- Compare Yano's `updateDRepExpiry` logic against Haskell's expiry calculation
- Check if Haskell's `reCurrentEpoch = eNo - 1` offset affects the stored expiry value vs just the active flag
