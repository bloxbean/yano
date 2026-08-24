# ADR-034 Phase 10 Verification Report

Date: 2026-08-14
Branch: `feat/adr-034-phase-10-integration`

## Outcome

Phase 10 composes the optional archive into the application without adding an
archive write to canonical block apply. History initialization is fail-closed
and optional; worker execution starts only after authoritative startup recovery.
When history is disabled, no backend provider, JDBC driver, Flyway lifecycle,
history RocksDB, epoch source, or archive worker is initialized.

## Runtime and recovery contract

- Every block dataset has independent backfill and live activation/cursor
  state. Bulk workers yield to core lag; live projection remains queryable while
  historical backfill proceeds.
- Durable per-dataset body low watermarks protect enable-later replay between
  worker polls. Leases still protect each active source batch. The pruner never
  waits on archive work and persists progress across already-missing bodies.
- Finalized live rows are copied from a pinned RocksDB snapshot into the
  selected backend before hot deletion. Existing pinned readers retain their
  old view. Backfill advances across the published coverage without rewriting
  it, while stateful datasets replay only their private resolver mutations.
- Exact track-scoped undo handles ordinary rollbacks. A rollback below a live
  activation anchor discards the invalid base and reactivates; an offline or
  deep canonical mismatch finds the newest retained common checkpoint,
  invalidates affected cold jobs, and conservatively rebuilds when no retained
  checkpoint exists. Undo and checkpoints are pruned together after the
  configured rollback window.
- Address input resolution uses a genesis-seeded private outpoint store.
  Live seeding materializes a detached compact UTXO snapshot and releases the
  authoritative RocksDB snapshot before writing archive state. Tip-mode cold
  projection uses an independent snapshot at the same activation invariant;
  it never attempts to resolve pre-activation spends from genesis alone.
  Byron bootstrap addresses are validated by the dedicated Byron codec and
  remain exact address subjects without fabricated Shelley credentials.
- Pointer-address registration state is archive-private, era-aware, and part of
  the same exact undo as its dataset. Missing pre-Conway mappings stop progress;
  Conway/later pointers retain coordinates but do not fabricate stake scope.

## Epoch integration

Epoch stake, DRep distribution, Ada pots, governance proposal observations,
and rewards stream into bounded durable source parts. Core RocksDB phase writes
commit before their corresponding source part. Capture failure is isolated
from the authoritative epoch transition, persisted as archive health failure,
and never publishes a partial boundary. Successfully committed phase parts
survive an interrupted later phase and become visible only after the whole
boundary completes.

Governance rows retain observation phase, lifecycle status, and structured
decision reason. Reward rows use unbounded stable source IDs, include MIR,
pool/governance refunds and treasury withdrawals, and remain archive-only—no
whole-epoch reward list, hot reward rows, or reward undo journal exists.

The preview `epoch-export` module, callback API, ServiceLoader descriptors,
native metadata, application dependency, and raw-directory rollback cleanup
are removed only after both archive backends support the replacement datasets.
Removed `yano.snapshot-export.*` and PR-only synchronous account-history
configuration fail with migration guidance. Legacy column families are never
trusted or automatically deleted; an explicit confirmation-token cleanup task
is provided.

## Wallet APIs and events

- Account event, reward, exact-address, payment-credential, and
  stake-credential transaction endpoints use one hot/cold provider with pinned
  hot and backend read snapshots, deterministic deduplication, bounded paging,
  and explicit incomplete responses.
- Transaction status uses the durable `chain_transaction` dataset, including
  phase-2-invalid transactions. DuckLake resolves point lookups through its
  derived locator and verifies the selected row; SQLite uses its indexed hash.
- Amount quantities serialize as lossless decimal strings, including values
  above JavaScript's safe integer range. The two address/account transaction
  endpoints share ascending default order and count clamping.
- SSE block events run after authoritative store listeners; non-real reconnect
  rollbacks are suppressed. Send completion is observed on the per-client
  virtual thread so dead sinks are removed without blocking core event threads.

## Core-state boundary

Authoritative UTXO, account, ledger, reward balance, and chain state remain the
source of truth. Core changes are limited to read-only block/era/tip capability
exposure, the non-blocking block-body retention boundary, optional epoch fact
stream calls, and corrected post-commit SSE ordering. Archive I/O failures
degrade history and remain outside block-apply/event threads.

## Verification

Focused tests cover durable body watermarks, covered-range cursor advancement,
pinned hot-to-cold promotion, track-independent undo, live rollback,
phase-2 collateral, genesis output identity, era-aware pointer resolution,
epoch-source interruption/restart, transaction locator generation pinning,
repeated address-dimension convergence, and lossless amount JSON.

The implementation was iterated against a packaged preprod node with SQLite
history, address transactions enabled, and a 250 ms worker poll interval. The
run found and fixed three defects before merge: runtime genesis was unavailable
before `RuntimeNode.start`, Byron genesis addresses were incorrectly sent
through the Shelley parser, and tip-mode finalized address projection lacked
an activation-point UTXO base. A later graceful-stop stress run found a native
RocksDB close race; archive shutdown now interrupts and joins in-flight work
before closing either archive or core native stores.

Final validation results:

- `:archive-modules:archive-core:test` and `:app:test` pass after the fixes;
- both archive backend suites, ledger/runtime/application suites, and the
  repository-wide `test --continue` run pass, apart from one existing
  suite-load timeout in `AppChainCatchUpIntegrationTest` that passed on its
  isolated rerun;
- `:app:quarkusBuild` produces the runnable 298 MB application archive;
- preprod live/backfill cursors advanced for account events, address
  transactions, and transactions while core sync retained priority;
- finalized hot rows promoted to SQLite and coverage revisions continued
  across graceful restart;
- epoch nonce restored at the persisted body tip, with the same epoch-44
  nonce across the graceful restart;
- `SIGKILL` recovery reopened healthy archive/core stores, preserved coverage,
  resumed all live cursors, and handled a subsequent real peer-intersection
  rollback without an archive error;
- the final graceful shutdown joined an active archive worker before stopping
  core and completed without the previously reproduced JNI crash.
