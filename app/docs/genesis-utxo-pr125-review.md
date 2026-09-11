# PR #125: genesis UTXO bootstrap review

Reviewed against main after #126 on 2026-09-11. The maintainer branch preserves
the contributor's commit and resolves the overlapping producer tests and the
new three-argument `produceToSlot` API. No genesis-state redesign is included yet.

## Assessment

The PR addresses a real fresh slot-leader startup failure, but its proposed
ordering is a workaround for a storage-coordinate requirement. Genesis funds
are initial ledger state, not outputs created by the first eligible block.

1. `forgeFirstBlockNow()` uses normal transaction selection and publishes
   `BlockAppliedEvent` before `DevnetGenesisShiftService` installs genesis UTXOs.
   Thus first-block validation and synchronous observers cannot rely on the
   complete initial UTXO state. A first-block genesis spend and observer-state
   regression are needed.
2. `RuntimeNode.storeGenesisUtxosIfNeeded` stamps genesis records with the
   current tip's slot/hash, while passing block number zero. For a first eligible
   block after slot zero, this falsely attributes initial funds to that block.
   `DefaultUtxoStore` writes genesis records without an ordinary block delta,
   so they are not simply deleted with that block on rollback; however its
   pointer-marker rollback logic only preserves a genesis marker at block zero,
   slot zero. A later-slot marker is cleared on rollback to origin. The current
   model therefore does not cleanly represent genesis independently of blocks.
3. The producer scheduler starts before synchronous forging and UTXO bootstrap
   finish. It can race to advance the tip before genesis storage reads it.
   Genesis storage still supplies block number zero, so its slot/hash can refer
   to a later block while its block number remains zero. Start scheduled
   production only after bootstrap completes.

The contributor's new producer test checks the no-eligible-slot scan, not a
successful first block or genesis storage. It is useful but does not establish
the genesis-state invariants above.

## Recommended follow-up

- Represent genesis as an explicit origin/bootstrap coordinate for UTXO data
  and readiness proofs, rather than borrowing a produced block hash. Inspect
  existing `ChainPoint.ORIGIN` and genesis index-contributor contracts first.
- Materialize initial funds and indexes idempotently before transaction selection
  or first-block application. Do not replace the missing hash with an arbitrary
  fake block hash just to satisfy validation.
- Keep first eligible block production separate from genesis initialization;
  start the scheduler only after initialization succeeds.
- Test initial-state queries, first-block spending, a first eligible slot above
  zero, rollback to origin and replacement of the first block, restart, and
  pointer/stake-balance index readiness.
- Then run fresh slot-leader backfill and downstream Haskell synchronization.

## Validation so far

`SlotLeaderTimeTravelBlockProducerTest` and `DevnetGenesisShiftServiceTest` pass
after conflict resolution. This establishes compatibility with current main;
it does not establish that the proposed genesis model is correct. Keep the
maintainer PR in draft until the semantic follow-up is complete.
