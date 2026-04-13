# DRep Mainnet Behavior Notes for Yaci Store Verification

This note summarizes two separate mainnet issues found while comparing Yano DRep state against DBSync after the Conway era start. They are easy to mix up, but they affect different parts of governance:

- DRep expiry / dormant tracker affects the active DRep set used during ratification.
- DRep distribution delegation cleanup affects how much voting power each DRep receives.

DBSync is the source of truth for the historical comparison because it follows the Haskell node behavior for the chain history being indexed.

## 1. DRep Expiry And Dormant Counter

This issue was primarily about ratification, not DRep distribution amounts.

### What Went Wrong

Yano initially had DReps whose stored expiry did not advance correctly through dormant-counter handling. Some registered DReps were treated as expired too early. Since active non-voting DRep stake is part of the ratification denominator, wrongly expiring DReps removed NO/non-voting stake from the tally and inflated YES ratios.

That caused proposals to ratify too early and later created AdaPot treasury mismatches.

### Behaviors Required

- Registered DReps should be considered for expiry processing even if they are currently expired. Do not process only `active=true` records.
- DRep vote/update refresh should apply to registered DReps without requiring `active=true`; a registered DRep that votes or updates can refresh its expiry.
- The ratification active check uses the epoch being left, not the new epoch:

```text
effectiveExpiry >= newEpoch - 1
```

This matches Haskell ratification’s `reCurrentEpoch = eNo - 1`.

### Example Symptom

One observed DRep was:

```text
b102197e...d7e6
```

Yano had this DRep stuck with a low stored expiry, e.g. `stored_expiry=528`, while DBSync/Haskell showed a later effective `active_until`. Around epoch 574/575 this contributed to a large active-DRep-set mismatch: many DReps were excluded by Yano but active in DBSync.

The result was early ratification because too much non-voting stake was removed from the denominator.

### Yaci Store Check

For Yaci Store, verify expiry and active status separately from DRep distribution amount:

- Does `active_until` / effective expiry match DBSync for registered DReps?
- Are registered but currently expired DReps still tracked for future refresh/update behavior?
- Does vote/update refresh use the same dormant-counter formula as the Haskell version being matched?
- Does the active check use the epoch-being-left convention?

## 2. DRep Distribution: PV9 Stale Reverse Index And PV10 Rebuild

This issue caused DRep distribution amount mismatches.

The key point: Haskell maintains `drepDelegs`, an internal reverse index from DRep to delegators. In PV9 this reverse index could contain stale entries. Since blockchain history is immutable, other node/indexer implementations must reproduce that historical behavior for PV9, even if it looks like a bug.

### PV9 Behavior: Stale Reverse Entries

In PV9, a stake credential could re-delegate from one DRep to another, but the old DRep’s reverse set could still remember that credential.

Timeline:

```text
1. Credential B delegates to DRep A.
2. Credential B re-delegates to DRep C.
3. Forward delegation is now B -> C.
4. Haskell PV9 stale reverse index may still contain A -> {B}.
5. DRep A deregisters.
6. ConwayUnRegDRep scans drepDelegs[A] and clears B's forward DRep delegation.
7. Result: B no longer counts for C.
```

A simple “latest delegation by slot wins” implementation would keep `B -> C`, which is logically cleaner but historically wrong for PV9 mainnet behavior.

### Concrete PV9 Example

From the investigation:

```text
slot 134,657,714, epoch 509: stake1u8cdy482 delegates to DRep 34526e9a
slot 134,660,310, epoch 509: DRep 426d5a97 registers
slot 134,660,497, epoch 509: stake1u8cdy482 re-delegates to DRep 426d5a97
later: old DRep 34526e9a deregisters
```

Haskell/DBSync clears `stake1u8cdy482`'s DRep delegation because stale `drepDelegs[34526e9a]` still contained that credential.

Effect observed in the comparison:

```text
DRep 426d5a97
Yano/Yaci-style latest-delegation recomputation: ~711B
DBSync/Haskell: ~325B
```

The difference came from stake that a latest-by-slot implementation would still count under `426d5a97`, but Haskell PV9 had cleared.

### PV10 Behavior: Hardfork Rebuild Fix

At the PV10 hardfork, Haskell runs `updateDRepDelegations` in `HardFork.hs`.

That rule:

```text
1. Resets all drepDelegs reverse entries to empty.
2. Rebuilds reverse entries from current forward delegations.
3. Removes dangling forward delegations to non-existent DReps.
```

After PV10, the reverse index is clean. DRep deregistration should clear only credentials currently delegated to the deregistering DRep.

### PV10 Mismatch Pattern

After implementing PV9 bug compatibility, we saw a second class of mismatches in PV10. The cause was stale PV9 reverse-index behavior being applied after PV10. That over-cleared delegations that should have survived after the hardfork rebuild.

Observed examples included:

```text
Target DRep 1c12...381e
Missing credential df74e353...
Old DRep deregistered around epoch 561
Missing stake about 1.7T lovelace

Target DRep 008e...92ab
Missing credential f1b6bf5a...
Old DRep deregistered around epoch 591
Missing stake about 861B lovelace

Target DRep b102...d7e6
Missing credential 695e3da3...
Old DRep deregistered around epoch 550
Missing stake about 667B lovelace
```

The fix was to apply a PV10 hardfork rebuild of the reverse index, then keep it clean for PV10+.

## Yaci Store Verification Checklist

If Yaci Store recomputes DRep distribution from chain events, it should verify all of the following:

- PV9 must preserve stale credential-DRep reverse entries when a credential re-delegates from one credential DRep to another credential DRep.
- PV9 DRep deregistration must clear delegators found through the stale reverse index, even if the forward delegation currently points to another DRep.
- Re-delegation from credential DRep to virtual DRep (`AlwaysAbstain` / `NoConfidence`) should remove the old credential-DRep reverse entry because virtual DReps do not have reverse entries.
- At the PV10 hardfork boundary, rebuild the reverse index from current forward delegations.
- During the PV10 rebuild, remove dangling forward delegations to non-existent DReps.
- After PV10, re-delegation should remove the old reverse entry and add the new reverse entry.
- After PV10, DRep deregistration should clear only credentials currently delegated to the deregistering DRep.
- DRep distribution should count virtual DReps directly and credential DReps only if the DRep is currently registered.

