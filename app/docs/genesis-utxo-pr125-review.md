# PR #125: genesis UTXO bootstrap review

Reviewed against main after #126 on 2026-09-11. The maintainer branch preserves
the contributor's commit and resolves the overlapping producer tests and the
new three-argument `produceToSlot` API. The follow-up now removes the block-first
workaround; see the implementation notes below.

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

## Initial conflict-resolution validation (before the follow-up)

`SlotLeaderTimeTravelBlockProducerTest` and `DevnetGenesisShiftServiceTest` pass
after conflict resolution. This establishes compatibility with current main;
it does not establish that the proposed genesis model is correct. Keep the
maintainer PR in draft until the semantic follow-up is complete.

## Maintainer implementation

DevKit #189 enables VRF checks when Haskell consumes an f<1 chain. That exposes
the genuine bootstrap failure fixed tactically by #125. DevKit submits its
governance transactions *after* the genesis shift completes, so the first-block
transaction hazard above is a general runtime issue, not a demonstrated failure
of that specific DevKit workflow. DevKit #191 reports successful downstream sync
and cost-model enactment with the contributor's workaround.

The replacement initializes Shelley genesis funds at origin (slot/block zero,
empty block hash), before starting either deferred producer. Ordinary devnet
startup now follows the same ordering, including before start-epoch catch-up.
No synchronous first-block forge is needed to initialize funds.

Pointer index **contents** are populated with genesis funds. A separate durable
genesis-initialized flag permits the first successful block application to write
the canonical pointer checkpoint atomically with its UTXO delta and cursor.
Origin itself has no canonical block checkpoint. Rolling back to origin restores
the genesis outputs, removes the checkpoint, and allows a replacement first block
to establish it again. Existing block-bound genesis initialization remains
supported for historical-sync/legacy callers; canonical block hash validation
is not relaxed for block application.

This matches the separation in Haskell's
[registerInitialFunds](https://github.com/IntersectMBO/cardano-ledger/blob/master/eras/shelley/impl/src/Cardano/Ledger/Shelley/Transition.hs):
initial funds are injected into the ledger's UTXO state, with initial stake updated,
not represented as outputs of a newly forged block.

The pointer index remains maintained for pre-Conway/historical consumers. This
change does not remove it or pretend that origin is a canonical block.

## Regression commands

Focused runtime suite (92 tests) and full testkit suite (80 tests) passed on the
replacement implementation, followed by a successful `:app:quarkusBuild`.

```sh
bash scripts/sparse-backfill/run-regular-sync-test.sh
bash scripts/sparse-backfill/run-sparse-backfill-test.sh \
  --cases dense,auto,slot-leader-dense,slot-leader --skip-build
```

The regular runner applies the `test-haskell-sync` skill with isolated directories
and ports, 200 ms slots, 600-slot epochs, and k=50 (so genesis bounds remain valid).
It does not shift genesis or invoke catch-up, and checks matching downstream hashes
and at most two slots of lag after both epoch boundaries. The backfill runner uses
300 ms slots and 1200-slot epochs, with f=0.5 for its slot-leader cases.

## Downstream verification — 2026-09-11

All runs used the final rebuilt JVM JAR and cardano-node 11.0.1 with the compatible
PV10 genesis fixtures, copied into isolated directories. Genesis UTXOs remained enabled.

| Scenario | Result | Evidence |
| --- | --- | --- |
| Regular production, no shift/catch-up | PASS | Haskell reached slot 1233 in epoch 2; hashes matched and sampled lag was at most two slots at both epoch boundaries |
| Dense backfill, interval 1 | PASS | 3603 catch-up blocks, 4234 ms; full Haskell history and live hash checks |
| Automatic sparse backfill, interval 0 | PASS | 15 catch-up blocks, 499 ms; history, live following, and restart following |
| VRF slot-leader dense, interval 1, f=0.5 | PASS | 1823 catch-up blocks; full history and live hash checks |
| VRF slot-leader sparse, interval 0, f=0.5 | PASS | 24 catch-up blocks; full history and live hash checks |

Artifacts (repository-relative, gitignored):

- `test-data-dir/regular-genesis-sync.GaBGKX/`
- `test-data-dir/sparse-backfill/20260911-221707/` — auto and both slot-leader cases
- `test-data-dir/sparse-backfill/20260911-221836/` — successful dense rerun

The first final-build dense run passed downstream validation but failed the verifier's
assumption that the service's pre-stop tip log equals the producer's actual starting tip.
A scheduled block completed between the log and stopping the producer. The verifier now
uses response arithmetic, checks the actual dense prefix, requires the pre-stop log not
to be ahead of the actual start, and still checks the sparse producer's own start log
exactly. The dense rerun passed. The original report is retained as FAIL, not rewritten.

Tracked genesis fixtures were unchanged. Only test-owned processes were stopped.

The final full `:runtime:test` run completed successfully in 8m 58s:
1660 tests discovered, 1655 passed, 5 skipped, zero failures/errors. The earlier
interrupted run was invalidated by an overlapping rebuild and is not counted.
Full `:testkit:test`: 80 passed, zero skipped/failures/errors.

## Review follow-up: origin restart gate

The pointer-checkpoint applicability check now recognizes initialized origin state
using the existing genesis flag, absent applied-block metadata, and an empty delta
store. It does not require a checkpoint until a block has been applied. This fixes
startup after rollback to origin without inventing another marker or block hash.
`preparePointerIndex` now checks for a marker before reading its coordinate and
returns unavailable when there is none.

A RocksDB-backed regression exercises first apply, rollback to origin, close/reopen,
the actual ledger startup gate, replacement first apply, and missing-checkpoint
rejection after that apply. No producer or network configuration changes are involved.

Validation: `:runtime:test --tests '*LedgerStateSubsystemTest' --tests '*DefaultUtxoStoreTest'`
passed. The downstream Haskell runs above predate this follow-up; they were not rerun.
