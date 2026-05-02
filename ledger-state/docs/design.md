# Ledger-State Module Design

The `ledger-state` module is the RocksDB-backed persistence layer for Cardano ledger state in Yaci. It tracks stake accounts, delegations, pool registrations, DRep governance state, and epoch boundary transitions including reward calculation.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [How It Fits in the Sync Pipeline](#how-it-fits-in-the-sync-pipeline)
- [RocksDB Column Families](#rocksdb-column-families)
- [Key/Value Format Reference](#keyvalue-format-reference)
- [Block Processing Flow](#block-processing-flow)
- [Epoch Boundary Flow](#epoch-boundary-flow)
- [Delegation Snapshot](#delegation-snapshot)
- [Conway Governance](#conway-governance)
- [Two-Phase Governance Commit](#two-phase-governance-commit)
- [Delta Journal & Rollback](#delta-journal--rollback)
- [Epoch Snapshot Export](#epoch-snapshot-export)
- [Module Dependencies](#module-dependencies)
- [TODO / Known Issues](#todo--known-issues)

---

## Architecture Overview

```
                          BodyFetchManager
                                |
                       BlockAppliedEvent
                                |
                    AccountStateEventHandler
                          /         \
              applyBlock()    epoch transition events
                  |               |
    DefaultAccountStateStore   EpochBoundaryProcessor
         |          |              |          |
    certificates  governance   rewards   governance
    withdrawals   block proc   snapshot  epoch proc
         |          |              |          |
         +-------- RocksDB --------+----------+
              (4 column families)
```

**Key classes:**
- `DefaultAccountStateStore` -- block-level state mutations (certs, withdrawals, deposits)
- `EpochBoundaryProcessor` -- orchestrates reward calculation, snapshots, governance
- `GovernanceBlockProcessor` -- processes proposals, votes, DRep/committee certs per block
- `GovernanceEpochProcessor` -- ratification, enactment, DRep distribution at epoch boundaries
- `AccountStateEventHandler` -- routes EventBus events to store methods

---

## How It Fits in the Sync Pipeline

```
Cardano Node (n2n)
    |
    v
BodyFetchManager (node-runtime)
    |--- Block received, stored in ChainState
    |--- publishEpochTransitionEventsIfNeeded() [at epoch boundary]
    |
    v
EventBus publishes:
    1. PreEpochTransitionEvent    --> rewards + snapshot + governance
    2. EpochTransitionEvent       --> prune old snapshots + prune old stake events
    3. PostEpochTransitionEvent   --> credit spendable reward_rest (proposal refunds,
                                      treasury withdrawals) to PREFIX_ACCT.reward.
                                      MIR types are credited earlier in Phase 1 Step 2b.
    4. BlockAppliedEvent          --> process block certificates + governance
```

All processing is **synchronous on the Netty event loop** (single-threaded per connection). Epoch boundary events fire BEFORE the first block of the new epoch is applied.

---

## RocksDB Column Families

| Column Family | Constant | Purpose |
|--------------|----------|---------|
| `acct_state` | `ACCT_STATE` | Primary store: accounts, delegations, pools, governance state |
| `acct_delta` | `ACCT_DELTA` | Delta journal: rollback operations keyed by block number |
| `epoch_deleg_snapshot` | `EPOCH_DELEG_SNAPSHOT` | Epoch-scoped delegation snapshots (retained 50 epochs) |
| `epoch_params` | `EPOCH_PARAMS` | Protocol parameter history per epoch |

All governance state (prefixes `0x60`-`0x6D`) lives in `acct_state` alongside account data.

---

## Key/Value Format Reference

All values use **CBOR encoding** with integer-keyed maps: `{0: field0, 1: field1, ...}`.

### Account & Delegation Prefixes (in `acct_state`)

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_ACCT` | `0x01` | `credType(1) + credHash(28)` | `{0: reward, 1: deposit}` | Stake account. Deleted on deregistration. |
| `PREFIX_POOL_DELEG` | `0x02` | `credType(1) + credHash(28)` | `{0: poolHash(bstr28), 1: slot, 2: txIdx, 3: certIdx}` | Active pool delegation |
| `PREFIX_DREP_DELEG` | `0x03` | `credType(1) + credHash(28)` | `{0: drepType, 1: drepHash(bstr), 2: slot, 3: txIdx, 4: certIdx}` | DRep delegation (governance vote power) |
| `PREFIX_DREP_DELEG_REVERSE` | `0x04` | `drepType(1) + drepHash(28) + delegatorCredType(1) + delegatorHash(28)` | Marker byte `0x01` | Reverse index: DRep to its delegators. Maintained by `delegateToDRep` and `deregisterStake`. PV9: stale entries preserved (re-delegated creds not removed from old DRep). PV10: rebuilt at hardfork boundary by `rebuildDRepDelegReverseIndexIfNeeded`. Used by `clearDRepDelegationsForDeregisteredDRep` on DRep deregistration. |

**Credential types:** `0` = ADDR_KEYHASH, `1` = SCRIPT_HASH

**DRep types:** `0` = key hash, `1` = script hash, `2` = ABSTAIN, `3` = NO_CONFIDENCE

### Pool Prefixes

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_POOL_DEPOSIT` | `0x10` | `poolHash(28)` | `{0: deposit, 1: marginNum, 2: marginDen, 3: cost, 4: pledge, 5: rewardAccount(bstr), 6: owners(array)}` | Pool registration params |
| `PREFIX_POOL_RETIRE` | `0x11` | `poolHash(28)` | `{0: retireEpoch}` | Planned retirement |
| `PREFIX_POOL_PARAMS_HIST` | `0x12` | `poolHash(28) + activeEpoch(4 BE)` | Same as pool deposit | Historical params by active epoch |
| `PREFIX_POOL_REG_SLOT` | `0x13` | `poolHash(28)` | `slot(8 BE)` | Latest registration slot (stale deleg detection) |

### Stake Lifecycle Prefixes

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_ACCT_REG_SLOT` | `0x14` | `credType(1) + credHash(28)` | `slot(8 BE)` | Latest registration slot |
| `PREFIX_DREP_REG` | `0x20` | `credType(1) + credHash(28)` | `{0: deposit}` | DRep registration deposit |
| `PREFIX_STAKE_EVENT` | `0x55` | `slot(8 BE) + txIdx(2 BE) + certIdx(2 BE) + credType(1) + credHash(28)` | `{0: eventType}` | Registration (0) / deregistration (1) events |

### Epoch-Scoped Prefixes (Reward Calculation)

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_POOL_BLOCK_COUNT` | `0x50` | `epoch(4 BE) + poolHash(28)` | `{0: blockCount}` | Blocks produced per pool per epoch |
| `PREFIX_EPOCH_FEES` | `0x51` | `epoch(4 BE)` | `{0: totalFees}` | Total tx fees collected in epoch |
| `PREFIX_ADAPOT` | `0x52` | `epoch(4 BE)` | `{0: treasury, 1: reserves, 2: deposits, 3: fees, 4: distributed, 5: undistributed, 6: rewardsPot, 7: poolRewardsPot}` | Ada pot snapshot |
| `PREFIX_REWARD_REST` | `0x56` | `spendableEpoch(4 BE) + type(1) + credType(1) + credHash(28)` | `{0: amount, 1: earnedEpoch, 2: slot}` | Deferred rewards (MIR, pool refunds, proposal refunds, treasury withdrawals) |

**Reward rest types:** `0` = MIR from reserves, `1` = MIR from treasury, `2` = proposal refund, `3` = treasury withdrawal. Pool deposit refunds go directly to account reward balance (not reward_rest).

### Governance Prefixes (`0x60`-`0x6D`, in `acct_state`)

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_GOV_ACTION` | `0x60` | `txHash(32) + govIdx(2 BE)` | `{0: deposit, 1: returnAddr, 2: proposedEpoch, 3: expiresAfter, 4: actionType, 5: prevTxHash, 6: prevIdx, 7: govActionCbor, 8: slot}` | Governance proposals |
| `PREFIX_VOTE` | `0x61` | `txHash(32) + govIdx(2 BE) + voterType(1) + voterHash(28)` | Single vote byte | Votes: NO=0, YES=1, ABSTAIN=2 |
| `PREFIX_DREP_STATE` | `0x62` | `credType(1) + credHash(28)` | `{0: deposit, 1: anchorUrl, 2: anchorHash, 3: regEpoch, 4: lastInteractionEpoch, 5: expiryEpoch, 6: active, 7: regSlot, 8: protocolVersion, 9: prevDeregSlot}` | DRep state |
| `PREFIX_COMMITTEE_MEMBER` | `0x63` | `credType(1) + coldHash(28)` | `{0: hotCredType, 1: hotHash, 2: expiryEpoch, 3: resigned}` | Committee members |
| `PREFIX_CONSTITUTION` | `0x64` | (singleton) | `{0: anchorUrl, 1: anchorHash, 2: scriptHash}` | Current constitution |
| `PREFIX_DORMANT_EPOCHS` | `0x65` | (singleton) | Array of epoch integers | Dormant epochs (no active proposals) |
| `PREFIX_DREP_DIST` | `0x66` | `epoch(4 BE) + credType(1) + drepHash(28)` | `{0: stake}` | DRep distribution per epoch |
| `PREFIX_EPOCH_PROPOSALS_FLAG` | `0x67` | `epoch(4 BE)` | Marker | Epoch had active proposals |
| `PREFIX_EPOCH_DONATIONS` | `0x68` | `epoch(4 BE)` | `{0: amount}` | Treasury donations per epoch |
| `PREFIX_LAST_ENACTED` | `0x69` | `actionType(1)` | `{0: txHash(bstr32), 1: govIdx}` | Last enacted action per type |
| `PREFIX_RATIFIED_IN_EPOCH` | `0x6A` | Sequential key | Pending enactment entries | Ratified proposals awaiting enactment |
| `PREFIX_EXPIRED_IN_EPOCH` | `0x6C` | Sequential key | Pending drop entries | Expired proposals awaiting deposit refund |
| `PREFIX_PROPOSAL_SUBMISSION` | `0x6D` | `slot(8 BE)` | `{0: epoch, 1: govActionLifetime}` | Permanent proposal metadata (v9 DRep bonus) |

### Metadata Keys (string keys in `acct_state`)

| Key | Value | Description |
|-----|-------|-------------|
| `"total_dep"` | BigInteger bytes | Total deposited lovelace |
| `"meta.last_block"` | Block number bytes | Last applied block |
| `"meta.last_snapshot_epoch"` | Epoch (4 BE) | Most recent snapshot epoch |
| `"mir.to_reserves"` | BigInteger bytes | MIR transfers to reserves |
| `"mir.to_treasury"` | BigInteger bytes | MIR transfers to treasury |
| `"meta.boundary_step"` | `epoch(4 BE) + step(4 BE)` | Epoch boundary progress marker for crash recovery. Steps: 0=started, 1=rewards, 2=snapshot, 3=poolreap, 4=governance, 5=complete |
| `"meta.pv10_drep_reverse_rebuild"` | Marker byte `0x01` | Guards one-time PV10 hardfork reverse-index rebuild. Presence means rebuild already completed; the method is idempotent across restarts. |

### Epoch Delegation Snapshot (in `epoch_deleg_snapshot`)

| Key | Value | Description |
|-----|-------|-------------|
| `epoch(4 BE) + credType(1) + credHash(28)` | `{0: poolHash(bstr28), 1: amount}` | Delegation with stake amount |

Retained for last 50 epochs. Used by reward calculation (snapshot at N-4) and DRep distribution.

---

## Block Processing Flow

`DefaultAccountStateStore.applyBlock()` is called for every block:

```
For each VALID transaction (skip invalidTransactions):

1. Process Certificates
   |- StakeRegistration/RegCert      --> create PREFIX_ACCT, write PREFIX_STAKE_EVENT, PREFIX_ACCT_REG_SLOT
   |- StakeDeregistration/UnregCert  --> delete PREFIX_ACCT, write PREFIX_STAKE_EVENT, return deposit
   |- StakeDelegation               --> write PREFIX_POOL_DELEG
   |- VoteDelegCert/StakeVoteDelegCert --> write PREFIX_DREP_DELEG (+ PREFIX_POOL_DELEG if combo)
   |- PoolRegistration              --> write PREFIX_POOL_DEPOSIT, PREFIX_POOL_PARAMS_HIST
   |- PoolRetirement                --> write PREFIX_POOL_RETIRE
   |- RegDrepCert                   --> write PREFIX_DREP_REG + GovernanceBlockProcessor.processDRepRegistration()
   |- UnregDrepCert                 --> delete PREFIX_DREP_REG
                                        + GovernanceBlockProcessor.processDRepDeregistration()
                                        + clearDRepDelegationsForDeregisteredDRep()
                                          (uses PREFIX_DREP_DELEG_REVERSE to find all delegators
                                           of the deregistering DRep, clears their PREFIX_DREP_DELEG
                                           entries. Two-pass: committed RocksDB entries + pending
                                           batch overlay entries. Matches Haskell ConwayUnRegDRep.)
   |- UpdateDrepCert                --> GovernanceBlockProcessor.processDRepUpdate()
   |- AuthCommitteeHotCert          --> GovernanceBlockProcessor.processCommitteeHotKeyAuth()
   |- ResignCommitteeColdCert       --> GovernanceBlockProcessor.processCommitteeResignation()

2. Process Withdrawals
   |- For each reward withdrawal: debit PREFIX_ACCT.reward

3. Governance Block Processing (GovernanceBlockProcessor.processBlock)
   |- Process proposals   --> write PREFIX_GOV_ACTION, PREFIX_PROPOSAL_SUBMISSION
   |- Process votes       --> write PREFIX_VOTE, update PREFIX_DREP_STATE.lastInteractionEpoch
   |- Accumulate donations --> aggregate per block, write PREFIX_EPOCH_DONATIONS once

4. Track Block Count & Fees
   |- PREFIX_POOL_BLOCK_COUNT[epoch][poolHash] += 1
   |- PREFIX_EPOCH_FEES[epoch] += block fees

5. Commit
   |- Collect DeltaOps throughout processing
   |- Write delta journal to cfDelta[blockNo]
   |- Update META_LAST_APPLIED_BLOCK
   |- db.write(WriteBatch) -- atomic commit
```

---

## Epoch Boundary Flow

Three-phase transition matching the Cardano ledger EPOCH rule:

### Phase 1: PreEpochTransitionEvent

`EpochBoundaryProcessor.processEpochBoundary(previousEpoch, newEpoch)`

Each step writes a `META_BOUNDARY_STEP` marker on completion. On restart, the processor reads the marker to determine which step to resume from, skipping already-committed steps. This makes the boundary idempotent and crash-recoverable.

```
Step 1: Finalize protocol parameters
        paramTracker.finalizeEpoch(newEpoch)

Step 2: Bootstrap AdaPot (once at Shelley start)
        + Credit spendable MIR reward_rest to account balances

Step 3: Calculate & distribute rewards (epoch >= 2)
        - Stake snapshot from epoch N-4
        - Pool block counts from epoch N-2
        - Fees from epoch N-1
        - Uses cf-rewards library (EpochCalculation)
        - Distributes to PREFIX_ACCT.reward per credential
        - Updates AdaPot (treasury, reserves, distributed)
        --> STEP_REWARDS marker

Step 4: Create delegation snapshot (SNAP)
        createAndCommitDelegationSnapshot(previousEpoch)
        - Iterates PREFIX_POOL_DELEG
        - Validates: registered, not retired pool, not stale, not deregistered
        - Computes: UTXO balance + rewards + reward_rest
        - Writes to cfEpochSnapshot
        - Returns utxoBalances for governance DRep distribution
        --> STEP_SNAPSHOT marker

Step 4b: Pool deposit refunds (POOLREAP)
         - After snapshot, before governance
         - Credits to PREFIX_REWARD_REST
         --> STEP_POOLREAP marker

Step 4c: PV10 hardfork reverse-index rebuild
         rebuildDRepDelegReverseIndexIfNeeded(newEpoch, registeredDRepIds, ep)
         - Runs ONCE at the first epoch with protocolMajor >= 10
         - Matches Haskell's updateDRepDelegations (HardFork.hs):
           1. Deletes all existing PREFIX_DREP_DELEG_REVERSE entries
           2. Iterates all PREFIX_DREP_DELEG forward delegations
           3. For each delegation to a registered credential DRep:
              creates a fresh reverse entry
           4. For dangling delegations (to non-existent DReps):
              deletes the forward delegation
         - Guarded by MARKER_PV10_REVERSE_REBUILD meta key:
           if marker already present, rebuild is skipped (restart-safe)
         - Writes marker + rebuilt entries in a single WriteBatch commit
         - Must run BEFORE governance so that DRep deregistration cleanup
           (clearDRepDelegationsForDeregisteredDRep) uses the clean reverse index

Step 5: Conway governance epoch processing
        GovernanceEpochProcessor.processEpochBoundaryAndCommit()
        - Two-phase commit (see below)
        - Returns treasuryDelta + donations
        --> STEP_GOVERNANCE marker

Step 6: Apply governance treasury adjustment to AdaPot

Step 7: Verify AdaPot against expected values (JSON on classpath)
        - System.exit(1) on mismatch when exit-on-epoch-calc-error=true
        --> STEP_COMPLETE marker
```

### Phase 2: EpochTransitionEvent

`DefaultAccountStateStore.handleEpochTransitionSnapshot()`

```
- Prune old snapshots (> 50 epochs)
- Prune old stake events
```

### Phase 3: PostEpochTransitionEvent

Credit spendable reward_rest to `PREFIX_ACCT.reward`: reward_rest entries with `spendableEpoch <= newEpoch` become withdrawable. This includes proposal deposit refunds and treasury withdrawals (types 2, 3) from governance enactment. MIR reward_rest types (0, 1) are credited earlier in Phase 1 Step 2b.

---

## Delegation Snapshot

Created at each epoch boundary in `createAndCommitDelegationSnapshot()`.

**What gets included:**
- Every `PREFIX_POOL_DELEG` entry where the credential is registered (`PREFIX_ACCT` exists)
- Excluding: retired pools, stale delegations (pool re-registered after delegation), deregistered credentials
- Stake amount = UTXO balance + withdrawable rewards + spendable reward_rest

**Three layers of stale delegation detection:**
1. **Pool retirement:** `poolHash` in retired pool set (retireEpoch <= epoch)
2. **Pool re-registration:** delegation slot < `PREFIX_POOL_REG_SLOT` value
3. **Credential deregistration:** delegation slot < latest deregistration from `PREFIX_STAKE_EVENT` or `PREFIX_ACCT_REG_SLOT`

**Storage:** `cfEpochSnapshot` column family, key = `epoch(4 BE) + credType(1) + credHash(28)`.

**Retention:** 50 epochs. Pruned at each `EpochTransitionEvent`.

**Used by:**
- Reward calculation (snapshot from epoch N-4)
- DRep distribution calculation (current epoch snapshot)

---

## Conway Governance

### Block-Level Processing (`GovernanceBlockProcessor`)

For each block:
- **Proposals:** Parse `ProposalProcedure` from transactions, store as `PREFIX_GOV_ACTION`
- **Votes:** Parse `VotingProcedures`, store as `PREFIX_VOTE`, update DRep `lastInteractionEpoch`
- **DRep certs:** Registration creates `PREFIX_DREP_STATE`, deregistration marks inactive, update changes anchor
- **DRep deregistration cleanup:** `clearDRepDelegationsForDeregisteredDRep()` uses `PREFIX_DREP_DELEG_REVERSE` to find all delegators of the deregistering DRep and clears their `PREFIX_DREP_DELEG` forward delegations. Two-pass scan: committed RocksDB entries + pending batch overlay entries. Matches Haskell's `ConwayUnRegDRep` which clears via `drepDelegs`. In PV9 the reverse index contains stale entries (re-delegated creds not removed), so cleanup over-clears to match Haskell PV9 bug behavior. After the PV10 rebuild the reverse index is accurate.
- **Committee certs:** Hot key authorization stores/updates `PREFIX_COMMITTEE_MEMBER`, resignation marks resigned
- **Donations:** Accumulated per block (not per tx) to avoid WriteBatch visibility issues, stored as `PREFIX_EPOCH_DONATIONS`

### Epoch-Level Processing (`GovernanceEpochProcessor`)

At each epoch boundary (Conway era, protocol >= 9):

1. **Phase 1 -- Enact + Cleanup** (committed before Phase 2):
   - Bootstrap Conway genesis (first Conway epoch only)
   - Apply previously ratified proposals (UpdateCommittee, ParameterChange, HardFork, etc.)
   - Create treasury withdrawal reward_rest entries
   - Refund deposits for enacted proposals and remove them from active store
   - Refund deposits for expired proposals (pending drops from previous boundary)
   - **Drop siblings and descendants of enacted proposals** with de-dup `removedIds` set to prevent double-refund when a proposal appears in multiple categories (pending enactment, pending drop, sibling, descendant)
   - **Drop descendants of expired proposals**
   - Store aggregated deposit refunds as `PREFIX_REWARD_REST`

2. **Phase 2 -- DRep Distribution + Ratify** (reads Phase 1 committed state):
   - Calculate DRep distribution (includes Phase 1 reward_rest via spendableRewardRest)
   - Build active DRep key set (non-expired DReps in distribution)
   - Apply **prevGovSnapshots filter**: only proposals with `proposedInEpoch <= previousEpoch` are eligible for ratification and expiry. Proposals submitted during `previousEpoch` are included (matching Haskell's `prevGovSnapshots` semantics where `curGovSnapshots` accumulates proposals through the epoch being left). Only proposals submitted during `newEpoch` are excluded.
   - Ratify: evaluate filtered proposals against vote thresholds (committee, DRep, SPO)
   - Store newly ratified as `PREFIX_RATIFIED_IN_EPOCH` (pending for next boundary)
   - Store newly expired as `PREFIX_EXPIRED_IN_EPOCH` (pending drops for next boundary)
   - **No immediate sibling/descendant drops** -- deferred to Phase 1 at the next boundary where the full active proposal set (including proposals excluded by prevGovSnapshots) is available for sibling discovery
   - Dormant epoch tracking: `epochHadActiveProposals = !ratifiableProposals.isEmpty()` (checks the pre-ratification filtered set, not the post-ratification result)
   - DRep expiry update (flush dormant counter if proposals were active)
   - Process epoch donations

---

## Two-Phase Governance Commit

The governance epoch processor uses two separate `WriteBatch` commits to ensure enactment changes are visible to ratification:

```
Phase 1: ENACT (processEnactmentPhase)
    - Bootstrap Conway genesis (first Conway epoch only)
    - Apply pending ratified proposals:
      - UpdateCommittee: add/remove members, preserve pre-authorized hot keys
      - ParameterChange: update protocol params
      - HardForkInitiation: update protocol version
      - NoConfidence: clear all committee members
      - NewConstitution: update constitution
      - TreasuryWithdrawals: compute treasury delta + store reward_rest
    - Update PREFIX_LAST_ENACTED per action type
    - Drop expired proposals (pending drops from previous boundary)
    - Drop siblings/descendants of enacted proposals (de-dup via removedIds set)
    - Drop descendants of expired proposals
    - Refund deposits for all removed proposals → aggregated reward_rest
    - Clear processed pending enactments/drops
    --> db.write(batch1)  [COMMITTED - changes now visible]

Phase 2: RATIFY + REST (processRatificationPhase)
    - Calculate DRep distribution (spendableRewardRest includes Phase 1 amounts)
    - Build active DRep key set (non-expired DReps in distribution)
    - Read committee state [sees Phase 1 changes!]
    - Read lastEnactedActions [sees Phase 1 changes!]
    - Apply prevGovSnapshots filter: proposedInEpoch <= previousEpoch
    - Evaluate filtered proposals against thresholds
    - Store newly ratified as pending enactment (PREFIX_RATIFIED_IN_EPOCH)
    - Store newly expired as pending drop (PREFIX_EXPIRED_IN_EPOCH)
    - NO immediate sibling drops (deferred to Phase 1 next boundary)
    - Update dormant epochs (dormant = ratifiableProposals is empty)
    - Update DRep expiry, donations
    --> db.write(batch2)
```

**Why two phases?** Without Phase 1 commit, an `UpdateCommittee` enactment writes new committee members to the WriteBatch, but `getAllCommitteeMembers()` reads from committed RocksDB state and doesn't see them. A subsequent `ParameterChange` proposal that requires committee approval would fail because the new committee is invisible.

**Why sibling drops are in Phase 1 (not Phase 2)?** Phase 2 uses the prevGovSnapshots-filtered proposal set which excludes proposals submitted during `newEpoch`. A sibling of an enacted proposal might be in that excluded set and would be missed. Phase 1 operates on the full `getAllActiveProposals()` set, ensuring all siblings are discoverable.

---

## Delta Journal & Rollback

Every `applyBlock()` records a delta journal for rollback support:

```
DeltaOp = (opType, key, previousValue)
    opType: OP_PUT (0x01) or OP_DELETE (0x02)
    key: full byte key including prefix
    previousValue: value before this block's mutation (null if new)

Storage: cfDelta[blockNumber] = CBOR-encoded list of DeltaOps
```

On `rollbackTo(blockNumber)`:
1. Read all `cfDelta` entries with blockNumber >= target
2. Apply in reverse order (newest first)
3. For `OP_PUT`: restore `previousValue` (or delete if was new)
4. For `OP_DELETE`: restore `previousValue`
5. Update `META_LAST_APPLIED_BLOCK`

---

## Epoch Snapshot Export

Optional Parquet export for debugging and DBSync cross-verification.

**Architecture:** SPI interface (`EpochSnapshotExporter`) in `ledger-state`, implementation (`ParquetEpochSnapshotExporter`) in `epoch-export` module via ServiceLoader.

**Output:** Hive-partitioned Parquet files in `data/epoch=N/`:
- `epoch_stake.parquet` -- stake delegations with bech32 `stake_address` and `pool_id`
- `drep_dist.parquet` -- DRep distribution with bech32 `drep_id`
- `adapot.parquet` -- treasury, reserves, deposits, fees
- `proposal_status.parquet` -- ratification results with `gov_action_id`

**Querying:**
```sql
-- DuckDB reads Hive partitions automatically
SELECT * FROM 'data/epoch=*/drep_dist.parquet' WHERE epoch = 280;
SELECT epoch, count(*) FROM 'data/epoch=*/adapot.parquet' GROUP BY epoch;
```

**Cross-verification:** `verify.sh` compares exports against DBSync and Yaci-Store.

Enabled via `yaci.node.snapshot-export.enabled=true`. Zero overhead when disabled (NOOP pattern).

---

## Module Dependencies

```
ledger-state
    |- node-api       (AccountStateStore interface, events, EpochParamProvider)
    |- core           (Block, Transaction, Certificate, Governance models)
    |- rocksdb        (persistence)
    |- cbor           (CBOR serialization)
    |- cardano-client-core  (Credential types)
    |- cf-rewards-calculation (epoch reward calculation engine)
    |- events-processor (annotation processing for @DomainEventListener)

Dependents:
    |- node-runtime   (creates and wires DefaultAccountStateStore, Yano)
    |- node-app       (REST endpoints, configuration)
    |- epoch-export   (Parquet snapshot exporter)
```

---

## TODO / Known Issues

### Epoch boundary rollback handling

The delta journal handles block-level rollback (via `DeltaOp` entries per block in `cfDelta`). However, epoch boundary processing (rewards, snapshots, governance) uses separate `WriteBatch` commits that are NOT part of the block-level delta system.

**Restart recovery via STEP markers:** Each step in `EpochBoundaryProcessor.processEpochBoundary()` writes a `META_BOUNDARY_STEP` marker on completion. On restart, `recoverInterruptedBoundary()` reads the marker to determine which step to resume from, skipping already-committed steps. Steps with markers: STEP_STARTED (0), STEP_REWARDS (1), STEP_SNAPSHOT (2), STEP_POOLREAP (3), STEP_GOVERNANCE (4), STEP_COMPLETE (5).

**What is NOT rollback-safe:**
- Governance two-phase commit: Phase 1 and Phase 2 are separate `WriteBatch` writes with no delta tracking. If the process crashes between Phase 1 commit and Phase 2 commit, Phase 1 enactments are applied but Phase 2 ratification/DRep updates are not. The STEP_GOVERNANCE marker is only written after both phases complete, so a restart would re-run the entire governance step, which could double-enact proposals if Phase 1 already committed.
- Delegation snapshot writes to `cfEpochSnapshot` are not delta-tracked.
- AdaPot updates are not delta-tracked.
- PV10 reverse-index rebuild is restart-safe via `MARKER_PV10_REVERSE_REBUILD` (idempotent).

### Earlier reward calculation start

Currently reward calculation starts at the epoch boundary (first block of new epoch). The Cardano spec allows starting reward calculation after the stability window of the previous epoch (2k/f slots before the epoch boundary) since the snapshot is frozen at that point. Starting earlier would reduce epoch boundary processing time and the stall visible to downstream consumers.

### DRep expiry off-by-one

At epoch 526 (mainnet), 53 DReps show a mismatch: Yano computes expiryEpoch=528 vs DBSync=527. Candidate causes: (a) timing difference in dormant counter flush relative to registrations within the same epoch boundary, (b) Yano's unconditional dormant flush vs latest Haskell's non-revival guard (`if actualExpiry < currentEpoch then currentExpiry else actualExpiry` in Certs.hs). Investigate against latest Haskell ledger source.

### Defensive timing guard in `resolveDRepKey`

The `DRepDistributionCalculator.resolveDRepKey()` method includes a defensive check `delegSlot <= prevDeregSlot` that skips delegations made before the DRep's previous deregistration. This guard does not exist in the Haskell implementation, which instead relies on the UMap structure automatically cleaning delegations on DRep deregistration. After the PV10 reverse-index rebuild and the unconditional `clearDRepDelegationsForDeregisteredDRep` cleanup on deregistration, this check is less critical -- stale delegations should already be removed. The guard remains as a safety net for Yano's tombstone-based DRep state model where deregistered DReps are not fully deleted but marked with `prevDeregSlot`.

### Governance exception silently swallowed in `EpochBoundaryProcessor`

`EpochBoundaryProcessor` catches exceptions thrown during governance processing (Step 5), logs them, and continues to mark `STEP_GOVERNANCE` complete. This means a governance failure (e.g., a bug in ratification, DRep distribution, or enactment) can be silently skipped for an entire epoch. The step marker prevents it from being retried on restart. This should either re-throw the exception (failing the epoch boundary and halting sync) or not mark the step complete so that a restart retries governance processing.

### `buildActiveDRepKeys` exception swallowing

`GovernanceEpochProcessor.buildActiveDRepKeys()` catches any exception and returns a partial or empty active DRep set instead of propagating the error. If the active set is wrong (missing DReps or including expired ones), ratification vote tallies will use incorrect weights, potentially causing proposals to be ratified or rejected incorrectly. This catch-all should be removed or narrowed so that a failure in building the active set halts governance processing rather than producing a silently wrong result.
