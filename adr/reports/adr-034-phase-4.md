# ADR-034 Phase 4 Verification Report

Date: 2026-08-14
Branch: `feat/adr-034-phase-4-worker`

Phase 4 adds the backend-neutral asynchronous worker and isolated hot-history
foundation. No archive write occurs on block apply.

Implemented:

- optional single-purpose lifecycle executor; disabled mode starts no thread or
  driver;
- per-dataset live/backfill progress, health/lag status, and atomic
  receipt-plus-cursor persistence;
- bounded block worker with core-lag yielding, durable source lease, canonical
  start/end rechecks, row limits, and idempotent backend jobs;
- dedicated history RocksDB with pinned snapshots, exact per-block undo,
  fail-closed missing undo, restartable cursor state, and bounded undo pruning;
- durable expiring block-body leases in history RocksDB;
- a narrow `ChainBlockReader` adapter that copies body/reference data before
  projection and never exposes a mutable core store;
- a core API low-watermark capability and a minimal `BlockPruner` change that
  prevents pruning bodies covered by an active consumer lease.

Tests cover disabled lifecycle, contained worker failures, core-lag pause,
bounded commits, anchor change abort, cursor/lease ordering, exact rollback,
pinned hot snapshots, missing/pruned undo, lease restart behavior, and block
pruner low-watermark enforcement. Archive-core and targeted runtime pruner
tests pass. Core ledger/account/UTXO state logic is unchanged.
