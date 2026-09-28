# ADR-056 Phase 1 research: ticked view, snapshots and the canonical write gate

Date: 2026-09-28. Read-only research at `feat/conway-ledger-rules` (after Phase 0).
Paths are relative to the repository root:

| Short name | Path |
|---|---|
| `LS` | `ledger-state/src/main/java/org/yanoproject/ledgerstate` |
| `RT` | `runtime/src/main/java/org/yanoproject/runtime` |
| `DASS` | `DefaultAccountStateStore.java` |
| `GEP` | `governance/epoch/GovernanceEpochProcessor.java` |
| `EBP` | `EpochBoundaryProcessor.java` |

This is the input for ADR-056 §3 (`TickedLedgerView`, the canonical snapshot
contract) and the Phase 1 gates. Line numbers are as of this commit.

## 1. Where each boundary effect is computed and persisted

All boundary work runs inside `PreEpochTransitionEvent` → `DASS.handleEpochTransition`
(`AccountStateEventHandler:38-42`, `DASS:2952`) → `EBP.processEpochBoundary`
(`EBP:276-603`). The exception is the reward_rest credit, which runs in
`handlePostEpochTransition` (`DASS:3001-3014`).

| Effect | Where it is computed | Known before the boundary? |
|---|---|---|
| Protocol params | `paramTracker.finalizeEpoch(newEpoch)` (`EBP:372`, `EpochParamTracker:303-318`, with a direct `db.put` at :311/1066). Conway changes come from `EnactmentProcessor.enact` → `applyEnactedParamChange` (`EnactmentProcessor:58-81`, `EpochParamTracker:281-296`). | Yes. Pre-Conway pending updates are 'P' keys in `epoch_params` (`EpochParamTracker:207-237`). Conway changes come only from the pending-enactment list. |
| Proposals enacted or expired, new roots, committee, constitution, withdrawals, deposit refunds | GEP Phase 1 `processEnactmentPhase` (`GEP:301-527`). It enacts `getPendingEnactments()`, removes `getPendingDrops()` plus siblings and descendants (`GEP:337-497`), and calls `storeLastEnactedAction` (`EnactmentProcessor:133-137`). Committed at `GEP:246-253`. | Yes, stored one boundary earlier. Ratification runs in Phase 2 of E-1→E and is stored as pending (`GEP:744-806`; prefixes 0x6A/0x6C at `GovernanceStateStore:42,44`). It is enacted at E→E+1. |
| Reward balances | `calculateAndStoreRewards` → `EpochRewardCalculator.calculateAndDistribute` (`EBP:647-715`, `EpochRewardCalculator:408-470`) | No. It is computed inside the boundary only and depends on the whole tip epoch. |
| Treasury / reserves | AdaPot from the reward result (`EBP:690-708`) plus the governance delta and donations (`EBP:134-144`, `GEP:273-277`); `PREFIX_ADAPOT` 0x52 | No, because it depends on the rewards. |
| Pool retirements | POOLREAP `processPoolReap` (`EBP:488-502`, `PoolReapProcessor:68-115`) | Yes. Driven by `PREFIX_POOL_RETIRE` rows for newEpoch (`PoolReapProcessor.buildPlan:319-354`). |
| reward_rest credit | `creditAndRemoveSpendableRewardRest(newEpoch)` in `PostEpochTransition` (`DASS:3009-3014`) | Derivable from the pending lists. |
| DRep expiry, dormant epochs, DRep distribution, new ratification | GEP Phase 2 (`GEP:662-840`; `updateDRepExpiry` :827) | At the boundary only. Believed not validation-visible; to be confirmed against Haskell. |
| PV10 DRep delegation cleanup | `rebuildDRepDelegReverseIndexIfNeeded` (`EBP:504-521`) | Only when a HardFork to PV10 is enacted. |
| Stake snapshot (SNAP) | `createAndCommitDelegationSnapshot` (`EBP:465-476`) | Not validation-visible. |

## 2. Can each be dry-run?

No boundary step is dry-runnable today. All of them read live `db.get` and write
through `WriteBatch`/delta ops, some with commits in the middle.

| Step | Refactor needed | Estimate |
|---|---|---|
| Governance Phase 1 | Only writes to the batch (apart from in-memory flags `genesisBootstrapped`/`conwayFirstEpoch`, `GEP:317-330`). Make `GovernanceStateStore`'s 49 reads bindable to `ReadOptions`; give `processEnactmentPhase` a throwaway batch plus an overlay. | 3–5 days |
| Params | Add a pure `preview(epoch, pending, enacted)` over an immutable copy; `materializeEffectiveParams`/`mergeUpdates` are private and read the mutable map (`EpochParamTracker:353-385`). | 1–2 days |
| POOLREAP plan | `buildPlan` is read-only (`PoolReapProcessor:319-354`) and only needs snapshot `ReadOptions`. | ~1 day |
| Rewards (riskiest) | Flushes and commits chunks mid-calculation (`EpochRewardCalculator:310-330`), keeps a `REWARD_PROGRESS` marker, writes scratch into `epoch_deleg_snapshot` in streaming mode, reads live throughout. Needs a snapshot reader, a credit sink and relocated scratch. Also has mainnet-scale memory and time costs. | 1.5–3 weeks |
| Treasury/reserves | Inherits the reward cost. | — |

## 3. Reusable pieces

- `HistoricalEpochStateView` (`HistoricalEpochStateView.java:22-37`): the `db.getSnapshot()` + `ReadOptions` pattern.
- `DASS.OrderedAccountLookup` (`DASS:4861-4866`): snapshot iterators.
- `openBoundaryStakeBalanceView` / `BoundaryStakeInput` (`DASS:532, 4302`).
- `RocksUtxoReadView.java:54`: snapshot-backed UTxO reads.
- `BatchStateOverlay` (`DASS:272-290`): read-your-writes over a pending batch, already used by rewards. This is the natural dry-run hook.

## 4. Storage

- **One RocksDB instance.** Every validation-visible column family is in the single
  `RocksDB.open` at `RT/chain/DirectRocksDBChainState.java:295` (descriptors :225-281):
  - UTxO column families and `script_ref`;
  - `acct_state` (accounts, pools, DReps, committee, proposals and votes, constitution, roots, pending lists, AdaPot, all by prefix);
  - `acct_delta`, `acct_boundary_delta`, `epoch_deleg_snapshot`, `epoch_params`.
- **In-memory state a snapshot would miss.**
  - `EpochParamTracker.epochParams`/`pendingUpdates` (:85, 96) are mutated **before**
    the RocksDB commit (:229 vs `DASS:3138`; :294 vs `GEP:252`), and are reloaded
    only after a rollback's write lands (`DASS:5667-5671`, `EpochParamTracker:721`).
    They must be copied under the gate, or re-read from `epoch_params` through the
    snapshot.
  - `GEP.genesisBootstrapped`/`conwayFirstEpoch` (:106-107).
- **Governance off by default.** `yano.ledger.governance.enabled` defaults to
  false (`RT/ledger/LedgerStateSubsystem:621`).

## 5. Block application, rollback and gate placement

- **Follower.** The single-threaded `LedgerApplyProcessor`
  (`RT/apply/LedgerApplyProcessor.java:24-40`) runs `BodyFetchManager.applyBlock`
  and rollbacks. Order:
  1. `chainState.storeBlock` (:735); the chain tip moves before the ledger;
  2. `publishEpochTransitionEventsIfNeeded` (:759);
  3. `BlockAppliedEvent` (:762), with UTxO at order 100 and account state at 110, delivered synchronously.
- **Producer.** `DevnetBlockProducer.produceBlock` (:278-324) applies the
  boundary **before** selecting and building (`BlockProducerHelper:209-250`); the
  same holds for `SlotLeaderBlockProducer:278-290`,
  `SlotLeaderTimeTravelBlockProducer:373-384` and `ProducerStartupCoordinator:215-260`.
  Ledger state can therefore be at epoch N while the tip is still in N-1. The
  ticked view must decide from `getBoundaryStep(newEpoch) == STEP_COMPLETE`
  (`DASS:5708`), not from the tip epoch, or it will tick twice.
- **Rollback entry points:**
  - `SyncSubsystem:1058-1061`
  - `RuntimeNode:3196`
  - `BodyFetchManager.compensateFailedPostStoreApply` (:1212-1240)
  - `DefaultUtxoStore:2711`
  - `recoverInterruptedBoundary` (`EBP:222`)

  `DASS.rollbackInternal` commits one chunk per block (`:5523-5526`) before its
  final write (:5667).
- **Gate placement:**
  - around the `LedgerApplyProcessor` work runner (the `work.run()` sites at :431/455/719), or around `BodyFetchManager.applyBlock` lines 733-762;
  - in the producers, as two sections: first the boundary, then store and apply. Block selection acquires its snapshot between them.
- **Hazards:**
  - **Async UTxO apply:** with `yano.utxo.applyAsync=true`, the UTxO is written on a separate thread after account state (`UtxoEventHandlerAsync:70-81`). It is off by default and must be forbidden, or awaited, under the gate.
  - **Multiple commits per boundary:** reward chunks, POOLREAP chunks, governance phases, spendable-rest.
  - **Swallowed producer failures:** `BlockProducerHelper.publishEvent` swallows listener failures at debug level (`:144-148`).

## 6. Minimum viable TickedLedgerView

- **Tick purely over the snapshot:**
  - **Params:** carry forward, merge pre-Conway pending updates, then merge pending-enactment ParameterChange/HardFork updates in order.
  - **Governance Phase 1:** removed proposals (pending enactments, pending drops, siblings and descendants), new roots, committee changes, constitution, and refund/withdrawal credits to registered reward accounts.
  - **POOLREAP plan:** retired pools and their deposit refunds.
- **Fail closed** (reject with a retryable status until the boundary block lands):
  - **Reward balances:** any transaction with withdrawals, because Conway requires withdrawing the exact balance.
  - **Treasury:** any transaction carrying `currentTreasuryValue`.
  - **Pending HardForkInitiation:** the whole tick, or at least withdrawals, delegation and votes.
  - **Governance tracking disabled:** governance and certificate checks the boundary could change.
- **Skip:** DRep expiry, DRep distribution and SNAP, pending confirmation against Haskell.

## Not verified in this research

- That Conway transaction rules never consult DRep expiry, the dormant-epoch count or the DRep distribution.
- Whether the block producer and follower sync can apply blocks concurrently in slot-leader mode with peers.
- That `ProposalDropService.findSiblings`/`findDescendants` are side-effect free.
- That `DefaultUtxoStore.applyBlock` commits a block's UTxO in one `db.write`.
- The refactor estimates, which are judgement calls.
