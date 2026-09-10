# Sparse backfill validation

Tested on 2026-09-10 using the JVM application and cardano-node 11.0.1.

The automatic mode is opt-in:

```properties
yano.block-producer.backfill-block-interval-slots=0
```

The default remains `1`. Historical production does not sleep for the live block
timer; its target is calculated using the configured slot duration.

## Empty-block integration result

An isolated copy of the PV10 devnet genesis used `epochLength=1200`, `k=100`,
`f=1`, and `slotLength=0.3`. Both slot and block timers were set to 300 ms.
The epoch-shift API moved genesis back three epochs (1,080,000 ms).

After nine initial blocks, catch-up produced 15 more blocks and reached slot
3612, block 24, in 0.536 seconds including HTTP overhead. Automatic spacing was
299 slots, with additional blocks at epoch starts and the target.

The Haskell node started from an empty database and accepted slots 0, 1200,
2400, 3600, and 3612. It continued following live blocks. At slot 3657 its hash
matched Yano:

```text
f36388e97795410f43bf31c1eb4c715c1486eac52a620a76ec45475ae92fc783
```

Raw logs and isolated genesis/database files from this local run are retained
under `/tmp/yano-sparse-it.iTe5YY/`. Only test-owned processes were stopped.

## Slot-leader coverage and outstanding integration issue

The unit regression forges real signed sparse blocks across three epochs,
checks nonce epoch progression, and resumes scheduled production at the next
slot. Another regression verifies that an unsuccessful eligibility search stops
before the forecast window is exceeded.

A fresh slot-leader integration run with `f=0.2` failed at epoch shift, before
backfill: `Canonical block hash is required` while storing genesis UTXOs.
`DevnetGenesisShiftService` calls `storeGenesisUtxosIfNeeded` before
`startSlotLeaderTimeTravel`, and the latter does not synchronously create a
genesis block. This pre-existing bootstrap issue must be addressed before
claiming end-to-end support for a fresh slot-leader devnet with UTXOs enabled.
Logs are under `/tmp/yano-sparse-it.iTe5YY/leader/`.

Native-image validation and low-relative-stake relay validation remain open.

## Forecast interpretation

`k` is a block count; the forecast horizon is expressed in slots. See the
[Ouroboros consensus discussion of forecasting and stability](https://ouroboros-consensus.cardano.intersectmbo.org/docs/references/miscellaneous/hard_won_wisdom/).
Bounding individual block gaps does not establish every chain-density or
finality property. The integration result above applies to the tested genesis
and Haskell configuration.

## Local testkit failure

The reported `shelley-genesis.json` byte mismatch came from a local runtime
rewrite of `systemStart` and removal of the trailing newline, not the PR.
The modified file was preserved at
`/tmp/yano-sparse-backfill-shelley-genesis-before-fix-20260910.json` before
restoring the tracked fixture bytes. The complete testkit suite then passed.
Run devnets against copied genesis files to avoid modifying this fixture again.
