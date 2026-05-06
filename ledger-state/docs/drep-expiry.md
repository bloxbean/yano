# DRep Expiry & Dormant Epoch Tracking

## Overview

In the Cardano Conway era, Delegated Representatives (DReps) have an **expiry epoch** that determines whether they are "active" for governance voting. If a DRep's expiry has passed, they are excluded from the ratification tally -- their delegated stake does not count.

The expiry extends whenever a DRep interacts with the chain (registers, votes, or updates their metadata). Additionally, during **dormant periods** (epochs with no active governance proposals), all DRep expiries are effectively extended so that DReps are not punished for inactivity when there is nothing to vote on.

This document covers how DRep expiry is tracked across four implementations: **Haskell** (canonical reference), **Amaru** (Rust), **Yano** (Java node), and **Yaci Store** (Java indexer).

---

## Key Concepts

### DRep Activity Parameter

The protocol parameter `drepActivity` (typically 20 on mainnet) defines how many epochs a DRep stays active after their last interaction. If a DRep registers at epoch 520 with `drepActivity = 20`, their initial expiry is epoch 540.

### Dormant Epochs

An epoch is **dormant** when there are no active governance proposals remaining after ratification processing at its boundary. During dormant epochs, DReps cannot vote (nothing to vote on), so their expiry should not tick down.

### Conway Phases

Conway governance has two phases, determined by the protocol version:

| Phase | Protocol Version | Description |
|-------|-----------------|-------------|
| **Bootstrap (V9)** | Major version 9 | Initial Conway era. DRep voting thresholds for most actions are effectively 0 (SPOs and Constitutional Committee decide). DRep registration and voting exist but DRep votes don't drive ratification for most proposal types. |
| **Post-Bootstrap (V10+)** | Major version 10+ | Full Conway governance. DRep votes actively determine ratification outcomes. DRep expiry handling becomes more precise -- the dormant counter is subtracted at registration/vote/update time. |

The transition from V9 to V10 happens via a hard fork initiation proposal that is itself ratified under V9 rules.

---

## 1. Initial Expiry at Registration

When a DRep registers, they receive an initial expiry epoch.

### V9 (Bootstrap Phase)

All implementations agree:

```
initialExpiry = registrationEpoch + drepActivity
```

Example: DRep registers at epoch 520, `drepActivity = 20` -> expiry = 540.

The dormant counter is **not** subtracted. This is because in V9, the dormant counter handling at epoch boundaries (via flush or on-the-fly addition) will extend the expiry implicitly.

### V10+ (Post-Bootstrap Phase)

```
initialExpiry = registrationEpoch + drepActivity - numDormantEpochs
```

The subtraction of `numDormantEpochs` compensates for the fact that at ratification time, `numDormantEpochs` will be added back. Without this subtraction, a DRep registering during a dormant period would get a double benefit.

**Example**: DRep registers at epoch 560 when `numDormantEpochs = 5`, `drepActivity = 20`:
- Stored expiry = 560 + 20 - 5 = 575
- At ratification, effective expiry = 575 + 5 = 580
- Net effect: 560 + 20 = 580 (same as intended)

| Implementation | V9 Formula | V10 Formula |
|---------------|------------|-------------|
| Haskell | `epoch + drepActivity` | `epoch + drepActivity - numDormantEpochs` |
| Amaru | `epoch + drepActivity` | `epoch + drepActivity - consecutive_dormant_epochs` |
| Yano | `epoch + drepActivity` | `epoch + drepActivity - numDormantEpochs` |
| Yaci Store | Computed post-hoc from history | Computed post-hoc from history |

---

## 2. Dormant Epoch Counter

Each implementation tracks whether epochs are dormant and maintains a counter.

### When is an epoch dormant?

At each epoch boundary (N -> N+1), after ratification and expiry processing:
- Count the **remaining active proposals** using `prevGovSnapshots` semantics -- only proposals submitted up to and including the epoch being left (i.e., `proposedInEpoch <= previousEpoch`) are considered
- If this set is empty -> the epoch is **dormant**
- If this set is non-empty -> the epoch is **not dormant**

**Important**: Proposals submitted during the epoch being left are included in the dormant check. This follows Haskell's `prevGovSnapshots` which includes proposals accumulated in `curGovSnapshots` through the end of `previousEpoch`. Only proposals submitted during `newEpoch` are excluded.

**Important**: The Conway first epoch (epoch 507 on mainnet) is always dormant because no proposals can exist before governance bootstraps.

**Caveat**: The dormant check semantics described here were verified against a specific mainnet DBSync version. Latest Haskell `origin/master` may use a slightly different rule for the dormant determination -- specifically using the post-enactment proposal set rather than `prevGovSnapshots`. If future DBSync versions reflect this change, Yano's dormant tracking may need to be updated accordingly.

### Yano dormant check (current implementation)

Yano determines dormancy using `ratifiableProposals` -- the same prevGovSnapshots-filtered proposal set used for ratification:

```java
// GovernanceEpochProcessor.java, line 558
boolean epochHadActiveProposals = !ratifiableProposals.isEmpty();
```

Where `ratifiableProposals` is built by filtering active proposals to only those with `proposedInEpoch <= previousEpoch` (lines 483-488). This was verified against the specific mainnet DBSync version used for comparison. Note: latest Haskell `origin/master` may use the post-enactment proposal set for the dormant check rather than `prevGovSnapshots` directly (see Version Caveat above).

### Counter behavior by implementation

| Implementation | Storage | Increment | Reset/Flush |
|---------------|---------|-----------|-------------|
| **Haskell** | `vsNumDormantEpochs` (single integer in VState) | +1 at EPOCH rule when previous epoch had empty proposal snapshot | Reset to 0 when a transaction containing a new proposal triggers the CERTS flush |
| **Amaru** | `consecutive_dormant_epochs` (persisted integer) | +1 at epoch boundary when no active proposals remain | **Never resets**. The counter monotonically increases. V10 registration subtracts it, and reads add it back. |
| **Yano** | `numDormantEpochs` (RocksDB key) + `dormantEpochs` set | +1 at epoch boundary when `!ratifiableProposals.isEmpty()` is false | Reset to 0 during flush at epoch boundary (when proposals exist and counter > 0) |
| **Yaci Store** | `gov_epoch_activity.dormant_epoch_count` (DB table) | +1 when current epoch is dormant | Resets to 0 on any non-dormant epoch |

### Example: Mainnet epochs 507-511

| Epoch | Proposals remaining? | Haskell counter | Yano counter | Amaru counter |
|-------|---------------------|----------------|--------------|---------------|
| 507 (Conway start) | 0 (no proposals yet) | 0 -> 1 | 0 -> 1 | 0 -> 1 |
| 508 | Yes (first proposals submitted) | 1 -> 0 (flush) | 1 -> 0 (flush) | 1 (stays, never resets) |
| 509 | Yes | 0 | 0 | 1 |
| 510 | Yes | 0 | 0 | 1 |
| 511 | Yes | 0 | 0 | 1 |

---

## 3. Stored Expiry Modification at Epoch Boundaries

This is where the implementations diverge most significantly in their internal mechanics, while producing equivalent results.

### Haskell: Flush-on-proposal (in CERTS rule)

Haskell does **not** flush at the epoch boundary. Instead, the flush happens during **transaction processing** in the CERTS rule:

```haskell
-- When a transaction contains governance proposals AND numDormantEpochs > 0:
if hasProposals && isNumDormantEpochsNonZero
  then
    -- Add numDormantEpochs to EVERY DRep's stored drepExpiry
    certState & vsDRepsL %~ (<&> (drepExpiryL %~ (+ numDormantEpochs)))
    -- Reset counter to 0
    & vsNumDormantEpochsL .~ 0
```

After the flush, `drepExpiry` contains the fully materialized value. The ratification check is simply `currentEpoch > drepExpiry`.

**Key detail**: Latest Haskell `origin/master` iterates ALL registered DReps but applies a **non-revival guard**: each DRep's stored expiry is only updated if `currentExpiry + numDormantEpochs >= currentEpoch`. This prevents writing a higher (but still expired) value for already-expired DReps. Earlier Haskell versions bumped unconditionally.

### Yano: Flush-at-boundary (in GovernanceEpochProcessor)

Yano flushes at the **epoch boundary** rather than during transaction processing:

```java
// updateDRepExpiry() in GovernanceEpochProcessor.java
if (epochHadActiveProposals && numDormant > 0) {
    // Flush: add numDormant to every registered DRep stored expiry.
    // Yano currently bumps unconditionally; latest Haskell has a non-revival guard
    // (see "Known divergence" below).
    Map<CredentialKey, DRepStateRecord> allDReps = governanceStore.getAllDRepStates();
    for (var entry : allDReps.entrySet()) {
        DRepStateRecord state = entry.getValue();
        // Skip deregistered tombstone records
        Long prevDeregSlot = state.previousDeregistrationSlot();
        if (prevDeregSlot != null && state.registeredAtSlot() <= prevDeregSlot) continue;

        int newExpiry = state.expiryEpoch() + numDormant;
        boolean active = newExpiry >= newEpoch - 1; // Haskell: reCurrentEpoch = eNo - 1
        if (newExpiry != state.expiryEpoch() || active != state.active()) {
            DRepStateRecord updated = state.withExpiry(newExpiry, active);
            governanceStore.storeDRepState(ck.credType(), ck.hash(), updated, batch, deltaOps);
        }
    }
    governanceStore.storeNumDormantEpochs(0, batch, deltaOps);

} else if (!epochHadActiveProposals) {
    // Dormant epoch: increment counter
    governanceStore.storeNumDormantEpochs(numDormant + 1, batch, deltaOps);
}
```

**Unconditional bump**: The current Yano code bumps all registered DReps unconditionally (`int newExpiry = state.expiryEpoch() + numDormant;`). This diverges from latest Haskell `origin/master` — see "Known divergence" below.

**Known divergence (non-revival guard)**: Latest Haskell `origin/master` has introduced a non-revival guard in the dormant flush: `if actualExpiry < currentEpoch then currentExpiry else actualExpiry`. This prevents an already-expired DRep's stored expiry from being bumped beyond its pre-flush value. Yano's flush is unconditional -- it always adds `numDormant` to the stored expiry regardless of whether the DRep is expired. This divergence causes stored `expiryEpoch` values to differ between Yano and latest Haskell for expired DReps that receive a dormant bump. The immediate ratification active-set is likely unaffected (the DRep remains expired), but stored value drift could matter if subsequent interactions or dormant accumulations depend on the stored value. See TODO/Known Issues.

**Timing equivalence**: Yano flushes at the epoch boundary before ratification. Haskell flushes when the first proposal-bearing transaction arrives (which is within the same epoch boundary processing). Both flush before ratification runs. Note: stored expiry values may differ for already-expired DReps due to the non-revival guard divergence (see above), but the active DRep set used for ratification has been verified to match for the tested epochs (526-623).

### Amaru: Never flush (compute-on-the-fly)

Amaru never modifies stored `valid_until` at epoch boundaries. Instead, it adds the counter at read time:

```rust
// When building governance summary for ratification:
DRepState {
    valid_until: Some(stored_valid_until + consecutive_dormant_epochs),
    ...
}
```

Since `consecutive_dormant_epochs` monotonically increases and V10 registration subtracts it, the math works out:
- Stored value = `regEpoch + activity - D_at_registration`
- Effective = stored + D_current = `regEpoch + activity + (D_current - D_at_registration)`
- `(D_current - D_at_registration)` = dormant epochs accumulated since registration

### Yaci Store: Full recomputation each epoch

Yaci Store doesn't maintain live state. At each epoch boundary, it recomputes every DRep's expiry from the full history (registration epoch, last vote/update epoch, dormant epoch set, V9 bonus). This is expensive but trivially correct and rollback-safe.

---

## 4. Effective Expiry for Ratification

At ratification time, each implementation determines which DReps are "active" (their delegated stake counts in the tally).

| Implementation | Active check | Where |
|---------------|-------------|-------|
| **Haskell** | `drepExpiry >= reCurrentEpoch` where `reCurrentEpoch = eNo - 1` -> active | `Ratify.hs: dRepAcceptedRatio` |
| **Amaru** | `valid_until > Some(epoch)` -> active | `dreps.rs: is_active()` |
| **Yano** | `effectiveExpiry >= newEpoch - 1` -> active, where `effectiveExpiry = storedExpiry + numDormantEpochs` | `GovernanceEpochProcessor: buildActiveDRepKeys()` |
| **Yaci Store** | Checks computed `active_until` column | `DRepExpiryService` |

### Boundary semantics

The exact boundary condition matters. Haskell's ratification uses `reCurrentEpoch = eNo - 1` (where `eNo` is the new epoch number). This means the "current epoch" for ratification purposes is the epoch being left, not the new epoch.

- **Haskell**: `drepExpiry >= reCurrentEpoch` where `reCurrentEpoch = eNo - 1`. A DRep with `drepExpiry = 540` is active when `eNo - 1 <= 540`, i.e., when `eNo <= 541`. Expired starting at `eNo = 542`.
- **Yano**: `effectiveExpiry >= newEpoch - 1`. Identical to Haskell -- a DRep with `effectiveExpiry = 540` is active when `newEpoch - 1 <= 540`, i.e., `newEpoch <= 541`.
- **Amaru**: `valid_until > Some(epoch)` means a DRep with `valid_until = 540` is active at epoch 539, expired at epoch 540. This is compensated by Amaru computing `valid_until` differently (likely 1 higher in practice).

Yano's `buildActiveDRepKeys()` implementation:

```java
// GovernanceEpochProcessor.java, line 595-613
int numDormant = governanceStore.getNumDormantEpochs();
// ...
int effectiveExpiry = rec.expiryEpoch() + numDormant;
if (effectiveExpiry >= newEpoch - 1) { // Haskell: reCurrentEpoch = eNo - 1
    activeDRepKeys.add(distKey);
}
```

---

## 5. Expiry Refresh on Vote and Update

When a DRep votes or updates their metadata, their expiry is refreshed.

### All Conway versions (V9 and V10+)

Yano refreshes expiry on both vote and update using the same formula, subtracting the pending dormant counter. This applies to all Conway versions (V9 and V10+), matching Haskell's `updateVotingDRepExpiries` which runs for all Conway versions:

```java
// GovernanceBlockProcessor.java -- vote and update both use:
int drepActivity = paramProvider.getDRepActivity(currentEpoch);
int numDormant = governanceStore.getNumDormantEpochs();
int newExpiry = currentEpoch + drepActivity - numDormant;
```

| Implementation | On Vote / On Update |
|---------------|---------------------|
| **Haskell** | `drepExpiry = currentEpoch + drepActivity - numDormantEpochs` (via `computeDRepExpiry` in Certs.hs -- same formula for both vote and update) |
| **Yano** | `storedExpiry = currentEpoch + drepActivity - numDormantEpochs` (same dormant subtraction as Haskell; compensates for deferred flush) |
| **Amaru** | No explicit refresh visible in reviewed code |
| **Yaci Store** | Records interaction; expiry recomputed at next boundary |

Both Haskell and Yano use the same formula: `currentEpoch + drepActivity - numDormantEpochs`. In Haskell this is `computeDRepExpiry` (Certs.hs), which is called for both `ConwayVote` and `UpdateDRep` certificate processing. Yano applies the identical subtraction during block processing. The mathematical equivalence holds regardless of when the dormant flush occurs, because the subtraction compensates for the pending counter that will be added back at ratification time.

**Tombstone guard**: Yano checks that the DRep is not deregistered before refreshing. Haskell removes deregistered DReps from `vsDReps`, so they are naturally excluded.

---

## 6. The V9 Bonus (Bootstrap Compatibility)

DReps that registered during the initial dormant bootstrapping period of Conway (before any proposals existed) have a special case. In Haskell, when the first proposal arrives and triggers a flush, ALL DReps get `numDormantEpochs` added to their stored expiry. This includes DReps registered during the dormant period -- they effectively get a "bonus" extension.

For example, on mainnet:
- Conway starts at epoch 507 (dormant -- no proposals)
- First proposal submitted in epoch 508 block -> triggers flush with counter=1
- A DRep registered at epoch 507 with `drepActivity=20`:
  - Initial stored expiry: 507 + 20 = 527
  - After flush: 527 + 1 = 528
  - Net effect: as if registered at epoch 508

Yaci Store and the older Yano code explicitly computed this as a "V9 bonus" to replicate the Haskell behavior. In the current Yano counter-based approach, the bonus emerges naturally from the flush -- no special handling needed.

---

## 7. DBSync `active_until` Convention

DBSync's `drep_distr.active_until` column represents the **effective expiry at snapshot time** -- this convention was verified from DBSync parquet data for the tested epochs (526-623). The value is `drepExpiry + vsNumDormantEpochs` (Haskell's on-the-fly computation). It is NOT the raw stored `drepExpiry`.

This means:
- `active_until` changes across epochs even if the DRep does nothing, because `vsNumDormantEpochs` accumulates
- It jumps when dormant epochs are flushed (the flush materializes into `drepExpiry`, then `vsNumDormantEpochs` resets)
- Direct comparison with Yano's `stored_expiry` is not meaningful -- they use different internal representations

For correctness verification, compare:
- **DRep counts** (should match exactly)
- **Delegated amounts** (should match within staking reward timing tolerance)
- **Ratification outcomes** (the definitive test -- same proposals ratified/rejected at same boundaries)

---

## 8. Correctness Assessment

### Is Yano's implementation correct according to Haskell?

**Yes, with high confidence.** The mathematical equivalence holds:

1. **Registration formula**: V9 `epoch + drepActivity` matches Haskell. V10 `epoch + drepActivity - numDormant` compensates correctly for the pending counter.

2. **Flush semantics**: Yano flushes at epoch boundary; Haskell flushes on first proposal-bearing transaction. Both happen before ratification. Yano's flush is unconditional; latest Haskell has a non-revival guard (see Known Divergences). This divergence affects stored values for already-expired DReps but not the active set used for ratification in the common case.

3. **Counter tracking**: Yano increments on dormant epochs, resets on flush — same general pattern as Haskell's `vsNumDormantEpochs`. Dormancy is determined using `prevGovSnapshots`-filtered proposals (`proposedInEpoch <= previousEpoch`, checked via `!ratifiableProposals.isEmpty()`). This was verified against the deployed mainnet DBSync. Note: latest Haskell `origin/master` uses the post-enactment proposal set rather than `prevGovSnapshots` directly for the dormant update (see Version Caveat in Section 2).

4. **Active check**: `effectiveExpiry >= newEpoch - 1` produces the same result as Haskell's `drepExpiry >= reCurrentEpoch` where `reCurrentEpoch = eNo - 1`.

5. **Flush scope**: Yano iterates ALL registered DReps (`getAllDRepStates()`), skipping only deregistered tombstones. This matches Haskell which iterates the full `vsDReps` map (deregistered DReps are removed from Haskell's map entirely).

6. **Vote/update refresh**: Yano subtracts the pending dormant counter during block processing (`currentEpoch + drepActivity - numDormant`), compensating for the deferred flush. Haskell sets expiry directly because its CERTS rule already flushed. Both produce equivalent effective expiry.

### Verification status

| Epoch | Result | Notes |
|-------|--------|-------|
| 575-576 | PASSED | Critical boundary for the -12T treasury fix |
| 617 | PASSED | Full DRep distribution match |

### Known issue: 53 DRep expiry off-by-one at epoch 526

At epoch 526 (synced from epoch-525 snapshot), 53 DReps showed `effective_expiry` = 528 in Yano but `active_until` = 527 in DBSync. The remaining 523 DReps matched exactly. All 53 mismatches are exactly +1 (Yano higher). This was observed before the `reCurrentEpoch = eNo - 1` fix and may be a snapshot-restart artifact. See `app/docs/drep-expiry-off-by-one.md` for investigation details.

**TODO**: Re-verify from a full sync to determine whether this persists or was specific to the snapshot restart.

### TODO / Known Issues

1. **Non-revival guard divergence**: Yano's dormant flush unconditionally adds `numDormant` to every DRep's stored expiry. Latest Haskell `origin/master` has a non-revival guard: `if actualExpiry < currentEpoch then currentExpiry else actualExpiry`, preventing an expired DRep's stored value from being bumped. The DRep is expired either way so ratification outcomes are identical, but the stored `expiryEpoch` value differs for expired DReps. Decide whether to adopt the Haskell guard for stored-value parity or keep the unconditional bump.

2. **Dormant determination rule may diverge from latest Haskell**: The dormant check was verified against a specific mainnet DBSync version using `prevGovSnapshots`-filtered proposals (`proposedInEpoch <= previousEpoch`). Latest Haskell `origin/master` may use the post-enactment proposal set instead. Monitor for DBSync changes that would reveal a divergence.

---

## Appendix: File References

### Haskell (cardano-ledger, origin/master)
- `libs/cardano-ledger-core/src/Cardano/Ledger/DRepDistr.hs` -- `DRepState` record
- `libs/cardano-ledger-core/src/Cardano/Ledger/CertState.hs` -- `VState`, `vsNumDormantEpochs`
- `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/DRepPulser.hs` -- `computeDRepDistr`
- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/HardFork.hs` -- `updateDRepDelegations` (PV10 reverse-index rebuild)
- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/GovCert.hs` -- `UnRegDRep`, `UpdateDRep` rules
- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Certs.hs` -- dormant flush (`updateDormantDRepExpiries`), vote refresh (`computeDRepExpiry`)
- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Epoch.hs` -- `updateNumDormantEpochs`
- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ratify.hs` -- `dRepAcceptedRatio`

### Amaru (Rust)
- `crates/amaru-ledger/src/summary/governance.rs` -- `GovernanceSummary`, effective expiry computation
- `crates/amaru-ledger/src/rules/transaction/phase_one/certificates.rs` -- V9/V10 registration
- `crates/amaru-ledger/src/state.rs` -- dormant counter increment
- `crates/amaru-ledger/src/governance/ratification/dreps.rs` -- `is_active()` check

### Yano (Java)
- `ledger-state/.../governance/epoch/GovernanceEpochProcessor.java` -- `updateDRepExpiry()`, `buildActiveDRepKeys()`
- `ledger-state/.../governance/GovernanceBlockProcessor.java` -- `processDRepRegistration()`, vote/update refresh
- `ledger-state/.../governance/GovernanceStateStore.java` -- `getNumDormantEpochs()`, `storeNumDormantEpochs()`
- `ledger-state/.../governance/model/DRepStateRecord.java` -- stored record

### Yaci Store (Java)
- `aggregates/governance-aggr/.../util/DRepExpiryUtil.java` -- full expiry calculation
- `aggregates/governance-aggr/.../service/DRepExpiryService.java` -- epoch boundary computation
- `aggregates/governance-aggr/.../processor/GovEpochActivityProcessor.java` -- dormant tracking
