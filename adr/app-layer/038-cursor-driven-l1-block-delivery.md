# ADR-038: Cursor-Driven L1 Block Delivery for App Chains

## Status

Proposed. Design only; nothing in this ADR is implemented.

The number is local to the `adr/app-layer` series. Root-level and
`adr/in-progress` ADR numbers are separate series; in particular, this is not
the in-progress ADR-038 on archive backfill throughput.

## Date

2026-10-03

## Owners

Yano app-chain host (`runtime/appchain`), with a small chain-storage change in
`runtime/storage`.

## Related decisions

- [ADR-005](005-yano-app-chain-framework.md) defines the app-chain subsystem
  and its L1 references.
- [ADR-008.1](008.1-iteration1-correctness-operator-safety.md) defines the
  follower-side L1 reference check against the node's own window of recent L1
  points (`recentL1Points`, stability depth + 64 blocks).
- [ADR-008.4](008.4-script-anchors-l1view.md) defines L1 observations and
  stability gating.
- [ADR-010](010-deterministic-effect-system.md) F7 gates `L1_ANCHORED` effects
  on anchors confirmed at or below the node's stable L1 point.
- [ADR-036](036-certified-view-change-and-durable-l1-observation-delivery.md)
  §5.5–§5.8 makes block observations durable once seen. It requires the
  observer result to be "journaled idempotently when its L1 block is applied",
  and defines rollback and deep-rollback quarantine.
- [ADR-037](037-certified-generic-observation-framework.md) defines the generic
  observation framework and its heartbeat, which reads the stable L1 point.
- Root [ADR-039](../in-progress/039-canonical-projection-outbox.md) §8 rejects a
  best-effort event-bus listener for archive projections, because a crash
  between canonical progress and the listener "would create an unrecoverable
  gap once block bodies are pruned".
- [Yano issue #166](https://github.com/bloxbean/yano/issues/166) reports the
  defect this ADR fixes.

## 0. Decision summary

An app chain must never miss an L1 block or an L1 rollback. Today it learns
about L1 only from synchronous `BlockAppliedEvent` and `RollbackEvent`
deliveries. An event published while the app chain is not subscribed is
dropped, and nothing replays it.

This ADR makes the node's chain state the source of truth for app-chain L1
input:

1. **Own position.** Each app chain persists a delivery record: a baseline
   history, the bounded window of L1 points it has fully delivered (the newest
   is the cursor), and at most one pending intent. Every rollback target is
   one of these recorded points. (D2, D5a, D8)
2. **Pull, in order.** A single delivery loop per chain reads the next
   canonical block from chain state with the existing `ChainBlockReader`, up to
   the node's body tip. Before running any phase it records an `APPLY` intent;
   it commits the block to the window and clears the intent in one write only
   after every phase reported durable success. (D1, D3, D4, D4a, D5, D5a)
3. **Rollbacks from chain state, with a crash boundary.** The loop detects that
   a delivered or partly applied point is no longer canonical, records a
   `ROLLBACK` intent, and runs the **existing** rollback phases until all of
   them succeed. Only then does it shorten the window. It does not depend on
   receiving a `RollbackEvent`. A divergence deeper than the window fails
   closed. (D5a, D6)
4. **Explicit phase outcomes.** Every forward and rollback phase, through both
   anchor services, reports no-op, durable success, retryable failure or
   quarantine. A swallowed failure can no longer advance the cursor. (D4a)
5. **Fenced readers.** Every safety-sensitive reader of the stable L1 point gets
   "unavailable" unless the delivery record is clean and its newest point is
   still canonical at read time. A stale cached view cannot release work. (D9a)
6. **Events become wake-ups.** The app chain keeps subscribing to the node's
   events, but only to wake the loop. A periodic poll backs them up. Losing an
   event costs latency, never correctness. (D3)
7. **Bodies stay until processed, across stop and start.** Each configured
   chain owns a `BlockBodyRetentionBoundary` from node construction until it is
   removed from configuration. Chain storage keeps the minimum across
   consumers, and the pruner and registrations share one lock. A missing body
   fails closed. (D7, D7a)
8. **Unverified state is not trusted.** An upgraded or re-baselined chain
   starts from a baseline history with no delivered points, does not count
   legacy anchor confirmations toward the F7 frontier, and checks every
   retained L1-derived record, including finalized evidence, against chain
   state. (D8, D8a, D8b)

No new event type is added and nothing is re-published on the event bus. The
loop calls the handlers that already exist. The mechanism is written so that it
can later become a shared node facility for other derived processing that
cannot survive a missed block (for example archive projections), but this ADR
implements it for app chains only. (D11)

## 1. Context and problem

### 1.1 How the app chain receives L1 today

All references are to `main` at `1564e112d`.

- The node stores a block body in chain state and then publishes
  `BlockAppliedEvent` (`runtime/.../BodyFetchManager.java:762`). Chain state is
  rolled back before `RollbackEvent` is published
  (`runtime/.../sync/SyncSubsystem.java:1058-1063`).
- `AppChainSubsystem.start()` subscribes to both events as a pair
  (`subscribeL1Events`, `AppChainSubsystem.java:5370`; `acquireL1Subscriptions`,
  `:5403`) with default options. `PropagatingEventBus` therefore delivers them
  synchronously on the publishing thread. `stop()` closes the subscriptions
  (`:6592`).
- `PropagatingEventBus.publish` returns immediately when an event type has no
  subscriber. Nothing is buffered or replayed.
- The per-block handler runs five isolated phases (`:5461-5527`): retry a failed
  observation, append the block to the in-memory window `recentL1Points`
  (`:181`), observe, wake the epoch-observation coordinator, and confirm
  anchors. `runL1Phase` (`:5611`) logs and swallows a phase failure so the other
  phases still run.
- The rollback handler (`:5570-5609`) rewinds the window, metadata anchors,
  script anchors, block observations and the epoch coordinator.
- The window feeds the stable L1 point (`stableL1Ref`, `:5689`), the follower
  verdict on proposed L1 references (`checkL1Ref`, `:5645`), the proposer's L1
  reference, the ADR-037 heartbeat, and the ADR-010 F7 frontier
  (`stableAnchoredHeight`, `:5718`).

### 1.2 Confirmed defects

**P1. A rollback missed while stopped makes a dead-fork anchor stable.** An
anchor in block 101 confirms; block 102 lands; the frontier is 1. The app chain
is stopped, a rollback to slot 100 is published, the app chain is started, and
blocks 101–103 of a new fork arrive. The frontier is still 1. With the same
rollback delivered while running, it is 0. This was reproduced with the
`AppChainL1CallbackIsolationTest` harness and is recorded in #166. The anchor
journal and the window both keep dead-fork points, so an `L1_ANCHORED` effect
could be released for an anchor that is no longer on L1.

**P2. Blocks that arrive during an observer failure are never observed.** While
a failed slot is pending, `L1ObservationService.onL1Block` rejects every other
slot with `L1_OBSERVER_REPLAY_REQUIRED_AT_SLOT_<n>`
(`L1ObservationService.java:302-305`). Recovery replays only the failed slot
(`AppChainSubsystem.java:5533-5550`). The existing test
`failedSlotMustReplaySuccessfullyBeforeObservationHorizonRecovers`
(`L1ObservationServiceTest.java:191-214`) shows slot 11 rejected, and
`newestSlot()` still 10 after recovery: slot 11 is never observed.

**P3. Other event-loss paths.**

- With events disabled the node uses `NoopEventBus`
  (`RuntimeNode.java:401`), so an app chain receives no L1 blocks at all.
- The block producer catches a publish failure at debug level and continues,
  except for block 0 (`BlockProducerHelper.java:139-149`).
- A phase failure swallowed by `runL1Phase` loses that block for that phase.
  Only block observations have a retry barrier.
- The anchor services also swallow their own failures.
  `AnchorService.onL1Block` and `completeObservedConfirmation`
  (`AnchorService.java:378-443`) catch every `Throwable` and return `null`,
  which also means "no matching anchor". The observed-but-uncompleted
  confirmation lives only in memory (`observedConfirmation`), and
  `onL1Rollback` (`:463-480`) swallows failures too. `ScriptAnchorService` has
  the same pattern (`onL1Block`, `:1010-1043`; confirmation writes, `:1088`,
  `:1170`). A failed `metaPutAll` therefore looks like "nothing to do", and a
  crash loses the in-memory retry state. `tick()` (`:201-205`) and
  `forceAnchorNow()` (`:172-176`) also complete observed confirmations.
- `AnchorService.Confirmation.blockHash` is the **app** block hash
  (`completeObservedConfirmation`, `:411-423`), not the L1 inclusion block's
  hash. Only the inclusion slot is recorded, so a confirmation cannot be
  checked against chain state by `(slot, hash)` today.

### 1.3 Probable defect: process-restart ordering

The app chain starts after the sync stage. `RuntimeKernelStages` adds
`RuntimeSyncSubsystem` (`RuntimeKernelStages.java:71`) before the
pre-publication extensions, which include the app-chain manager (`:72-74`).
`startSync` starts client sync immediately (`RuntimeNode.java:1364-1369`). After
a process restart, the node can therefore apply blocks, or roll back after a
fork that happened while it was down, before the app chain subscribes. This is
inferred from the start order and has not been reproduced; milestone M0 adds
the reproduction.

### 1.4 Why a start-time patch is not enough

Reconciling the window and anchor journal at start would fix P1 for the
in-process case only. It would not cover P2, P3, events lost while running, or
blocks applied before the first subscription. The underlying problem is that
the app chain's L1 input has no durable position of its own.

## 2. Goals and non-goals

### 2.1 Goals

- While a chain stays configured, every canonical L1 block between the chain's
  baseline and the node's body tip is delivered to the app chain's per-block
  handler, in canonical order. Removing a chain from configuration, restoring a
  snapshot or a bootstrap start can break that guarantee; D7a defines the
  operator recovery for those cases.
- Every L1 rollback that removes a delivered block is applied before any block
  of the new branch is delivered.
- Correctness does not depend on any event being delivered, on start order, on
  restarts, or on crashes.
- Reuse the existing per-block and rollback handlers, `ChainBlockReader`,
  `BlockBodyRetentionBoundary` and the ADR-036 journal semantics.

### 2.2 Non-goals

- No change to app-chain consensus, wire formats, or the meaning of the stable
  L1 point.
- No shared node-wide follower framework yet (D11).
- No change to how the node itself syncs, stores or prunes, beyond making the
  body-retention boundary composable (D7).
- No historical backfill before the chain's baseline (D8). Late observer
  activation and bounded backfill are separate topics.

## 3. Existing building blocks (reuse)

| Need | Existing code | Notes |
|---|---|---|
| Canonical coordinate for a block number | `ChainBlockReader.getCanonicalBlockReference(long)` (`core-api/.../ChainBlockReader.java`), returning `CanonicalBlockReference(blockNumber, slot, blockHash)` | Index-only, no body decode. `RuntimeNode` implements `ChainBlockReader` through `ChainQuery`. `DirectRocksDBChainState.getCanonicalBlockReference` is at `:769-794`. |
| Read a block body | `ChainBlockReader.getBlockByNumber`, `getBlockEra` | The checks in `RuntimeNode.retainedL1Block` (`:901-927`) are the model: canonical slot, header slot/number/hash match, Byron handling. |
| Body tip | `ChainBlockReader.getLocalTip()` (`RuntimeNode.java:4126`, `chainState.getTip()`) | Indexes are written from headers (`DirectRocksDBChainState.java:656-659`), while `storeBlock` writes only the body and the tip (`:578-592`). A canonical reference can exist above the body tip without a body, so the loop must cap at the body tip. |
| Lowest retained body | `ChainBlockReader.getEarliestRetainedBodyBlockNumber()` | Used to report a missing-body gap precisely. |
| "Is my cursor still canonical?" | `WalletQueryService.requireCanonicalPoint` (`runtime/.../wallet/WalletQueryService.java:150`) | Compares number, slot and hash with the canonical reference. |
| Body retention clamp | `BlockBodyRetentionBoundary` (`core-api`), honoured by `BlockPruner` (`cutoff = min(tip - depth, oldest - 1)`, `BlockPruner.java:73-77`) | One delegate slot today (`ChainStorageSubsystem.java:241-264`), and no production caller on `main`. |
| Coalesced wake-up on a worker thread | `L1EpochObservationCoordinator.onBlockApplied` (`:172`) | "Publisher-thread path: arithmetic, atomics, and one coalesced wake-up only". The same pattern drives the delivery loop. |
| Durable, idempotent observation journal and deep-rollback quarantine | `L1ObservationJournal` (`rollback`, `:353`; failed-callback marker, `:412-425`), ADR-036 §5.5 and §5.8 | The loop drives it through the existing handler. |
| Prior art: a cursor-based block follower | The legacy `BlockArchiveWorker` and `ChainBlockArchiveSource`, removed in `1a5dd24d0` (2026-08-22): canonical-tip reconciliation by hash, parent-chain checks, a minimum boundary across datasets | A design reference only; that code is not restored. |

Two existing reconcilers compare block numbers only: `DefaultUtxoStore.reconcile`
(`:2663`) and `DefaultAccountStateStore.reconcile`. A fork at the same height is
invisible to such a check. The loop in this ADR compares hashes.

## 4. Trust and fault model

- **Trusted:** the local node's chain state is the canonical L1 view, as it
  already is for UTxO and ledger state. The app chain does not validate L1
  blocks itself.
- **Faults covered:** process crash at any instruction; in-process stop and
  start; events lost, delayed, duplicated or reordered; events disabled; a
  plugin observer or anchor phase that throws; a storage write that fails
  inside a phase or a rollback phase; a reader running while the cached view is
  stale; concurrent node rollbacks and pruning; several app chains in one node;
  an upgrade from code that may have missed rollbacks.
- **Not covered:** corruption of chain state or the app ledger; an L1 rollback
  deeper than the recorded window (detected, fails closed; see D6); a
  nondeterministic observer (already a consensus halt under ADR-036 §5.6).
- **Secrets:** none are introduced.

## 5. Invariants

Each invariant names the decisions it constrains and is testable (§11).

- **I1 Completeness (D1, D3, D5).** For every canonical block `B` with
  `baseline.number < B.number ≤ bodyTip`, the app chain runs every per-block
  phase for `B` before running any phase for `B`'s canonical successor.
- **I2 Order and continuity (D1, D6).** Blocks are delivered in strictly
  increasing block number. Before delivering block `n`, the canonical reference
  of `n - 1` equals the cursor (number, slot and hash).
- **I3 Rollback before new branch (D5a, D6).** If any delivered point, or the
  block named by a pending `APPLY` intent, is no longer canonical, every
  rollback phase completes with the newest delivered point that is still
  canonical as its target, before any block of the new branch is delivered.
  (r2: also covers partly applied blocks.)
- **I4 No skip on failure (D4, D4a).** The cursor never moves past a block
  whose phases did not all report durable success or no-op. The loop retries
  that block.
- **I5 Durable after effects (D5, D5a).** The window gains a block, and its
  `APPLY` intent is cleared, in one write that happens only after every phase
  for the block reported durable success or no-op. After a failure or a crash,
  the same block is delivered again, and every phase handles a repeated
  `(slot, hash)` without changing its result.
- **I6 Events are hints (D3).** With a `NoopEventBus`, or with every event
  dropped, the app chain still delivers every block, within the poll interval.
- **I7 Retention (D7, D7a).** From node construction until a chain is removed
  from configuration, and whether or not the chain is running, no body with
  `number > cursor.number` is pruned (or above the rollback target while a
  `ROLLBACK` intent is pending). If a needed body is missing anyway, the chain
  reports `L1_BODY_UNAVAILABLE` and delivers nothing further; it never skips.
- **I8 Deep divergence fails closed (D6, D8).** If no window point and no
  baseline-history point is canonical, the chain reports
  `L1_DIVERGENCE_BEYOND_WINDOW`, stops delivering, and the stable L1 point is
  unavailable (so the F7 frontier is 0). This holds whether or not the window
  is empty (r3: the r2 automatic re-baseline is withdrawn).
- **I9 Window reflects delivery (D2, D5a, D8, D9).** The published window
  contains only committed, delivered points: never a baseline-history point, a block
  with a pending `APPLY` intent, or a point a pending `ROLLBACK` would remove.
  The stable L1 point needs at least `depth + 1` delivered points.
- **I10 Body-tip bound (D1).** The loop never delivers a block above
  `getLocalTip()`.
- **I11 Rollback intent blocks progress (D5a, D6).** While a `ROLLBACK` intent
  is pending, the loop delivers no forward block, publishes no shortened
  window, and fenced readers get "unavailable". The intent survives restart and
  is replayed before anything else. A terminal quarantine outcome is persisted
  and is never cleared by a restart.
- **I12 Partial apply is rolled back before a replacement (D5a).** If a pending
  `APPLY(p)` names a block that is not the canonical block at `p.number`, or
  the cursor is no longer canonical, the loop replaces the intent with
  `ROLLBACK(target)` and completes it before delivering any block at
  `p.number`. This holds after a phase failure without a crash, after a
  restart, and whether the replacement has the same slot or a different one.
  While `APPLY(p)` is pending, phases run only on the block whose decoded
  header equals `p`, and the commit publishes only `p`. A reference or body
  that changes at any step routes the attempt back to the pre-attempt check;
  it is never substituted (r3).
- **I13 Freshness fence (D9a).** A safety-sensitive reader (the F7 frontier
  for dispatch, claims and the pre-execution recheck; `checkL1Ref`; the
  proposer's L1 reference; the observation drain; the ADR-037 heartbeat) gets
  a stable point only if, in one published snapshot, the delivery state is
  `RUNNING`, no intent is pending, and the record has been reconciled since
  start, and if the window's newest point equals
  `getCanonicalBlockReference(newest.number)` at read time. Otherwise it gets
  "unavailable". While the fence is closed, the voting-health supplier is
  false, so the node proposes nothing and votes on no live proposal at any view
  (r3).
- **I14 Unverified L1 state is not trusted (D8a, D8b).** After an upgrade or
  an operator re-baseline, and before the first delivery:
  - the F7 frontier counts only confirmations that record an L1 inclusion
    point;
  - every retained L1-derived record carrying an L1 point (any schema; any
    journal state; finalized cursors; the L1 observations in committed app
    blocks) has been checked with the slot-based canonical lookup;
  - a dead non-finalized record has been invalidated, and a dead finalized or
    prepared observation has produced the terminal ADR-036 quarantine;
  - missing evidence has produced `L1_EVIDENCE_UNAVAILABLE`.

  Neither path clears a quarantine (r3).
- **I15 One-lock prune handshake (D7a).** Once a registration or a lowering to
  boundary `b` returns, no prune batch deletes any body with block number
  `≥ b`, including `b` itself (r3: r2 stated the inequality backwards).
- **I16 Rollback targets are recorded points (D5a, D6, D8; r3, F7).** Every
  `ROLLBACK` target is `ORIGIN`, or a window or baseline-history point that is
  canonical when the intent is written. No target is synthesized from the new
  branch.

## 6. Decisions

### D1. Chain state is the source of L1 input

The app chain reads L1 blocks through `ChainBlockReader`, not from event
payloads. For block `n` it reads `getCanonicalBlockReference(n)`,
`getBlockByNumber(n)` and `getBlockEra(n)`, and applies the same checks as
`RuntimeNode.retainedL1Block` (canonical slot; header slot, number and hash
match). It builds a `BlockAppliedEvent` value from them, so the existing handler
signature is unchanged. Byron blocks are delivered with a null `Block`, as the
node's own Byron events are today (`BodyFetchManager.java:960`, `:1103`). The
loop stops at `getLocalTip()` (I10).

`RuntimeNode` already implements `ChainBlockReader`. It is wired into the
subsystem the way `wireL1BlockReplay` is today (`AppChainSubsystem.java:1234`).
The single-slot replay function becomes unnecessary (D4).

*Alternatives.* Keep event payloads as the source and add start-time
reconciliation: rejected (§1.4). Read the node's UTxO-applied point instead of
the body tip: not needed, because observers and anchor confirmation read only
the block (`L1Observer.observe(slot, blockHash, block)` is documented as
"deterministic and side-effect free").

### D2. A durable per-chain delivery record; the window's newest point is the cursor

Each chain stores one delivery record in its own `AppLedgerStore` meta,
written atomically as a unit:

- `baseline`: a short history of canonical `(blockNumber, slot, blockHash)`
  points recorded at first start or re-baseline (D8). Delivery starts after
  its newest point. These are proven rollback targets (I16), not delivered
  points.
- `window`: the existing `recentL1Points` contents (capacity
  `max(depth, 1) + 64`), now durable: an ordered list of delivered
  `(blockNumber, slot, blockHash)` points. The newest is the cursor; with an
  empty window, the baseline is the cursor.
- `pending`: none, `APPLY(point)` or `ROLLBACK(target)` (D5a).

The in-memory `recentL1Points` becomes a published copy of the committed
window. It is replaced only after the record is durably written (r2, F4). The
window also provides the history needed to find a fork point after a restart
(D6).

*Alternatives.* Persist only the cursor and walk parent hashes back through
headers: possible, but it needs header decoding and adds a code path. The
bounded window is the data the subsystem already maintains. Persist one cursor
per phase (as ADR-039 does per contributor): see D4 and open question Q1.

### D3. One delivery loop per chain; events only wake it

Each chain owns one delivery loop on a dedicated worker, started and stopped
with the subsystem's generation. The `BlockAppliedEvent` and `RollbackEvent`
subscriptions remain, but their callbacks only signal the loop with a coalesced
wake-up, following `L1EpochObservationCoordinator.onBlockApplied`. A periodic
poll (default 1 s, node-local) also wakes the loop. Readers of the window keep
using the monitor introduced in `0dfdd48af`, behind the freshness fence (D9a).

The loop is the only writer of **L1-derived facts**: the delivery record, the
observation journal's L1 inputs, and anchor confirmation facts (an anchor tx
seen in an L1 block at a given point). Other paths may write only local state
keyed by those facts. For example, `tick()` and `forceAnchorNow()` may finish
the local part of a confirmation the loop recorded (D4a). They take the same
`anchorLock` as the rollback phase, so a rollback cannot race a completion.

The per-block handler no longer appends to the window. Its "reference
tracking" phase moves into the loop's commit step (D5a), so the published
window holds only blocks whose every phase succeeded (r2, F4).

The loop calls the existing `onL1BlockAppliedWithinGeneration` and
`onL1RollbackWithinGeneration` directly. No `AppChainL1BlockEvent` type is
added and nothing is re-published on the bus. A bus hop would add a public
event type, would need a chain id and filtering for several chains, and would
reintroduce "dropped when nobody is subscribed" without decoupling anything.

A side effect: per-block app-chain work moves off the node's sync thread.
Today a slow phase delays L1 sync, because delivery is synchronous.

### D4. The cursor advances only when every phase succeeded

`runL1Phase` keeps isolating phases within one attempt, so one failing phase
does not prevent the others from running in that attempt. The loop advances
the cursor only when all phases succeeded. Otherwise it retries the same block
with bounded backoff (I4). This replaces the observation-only retry barrier:
the block that follows a failed one is no longer rejected and lost (P2),
because the loop never reaches it until the failed block succeeds. The
persisted failed-callback marker stays as ADR-036's health signal.

Under ADR-036 §5.5, an observer failure already stops voting until the failed
slot replays. A single cursor that waits on the failed block therefore costs no
availability that the chain has today.

*Alternative.* One cursor per phase, with the window advancing on its own
cursor: open question Q1. The lean is a single cursor (KISS), because the phases
have no independent consumers.

### D4a. Explicit phase outcomes (r2, F2)

Every forward phase and every rollback phase returns one of:

| Outcome | Meaning | Loop action |
|---|---|---|
| `NO_OP` | Nothing in this block (or rollback) concerns the phase. | Counts as success. |
| `DURABLE` | The phase's writes for this block are durable. | Counts as success. |
| `RETRYABLE` | A failure that may succeed later (for example storage unavailable). | Keep the intent and retry with backoff. |
| `QUARANTINED(reason)` | A terminal safety state (for example ADR-036 `DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION` or `L1_INVALIDATED_PREPARED_VALUE`). | Persist it; the loop enters a fail-closed state (I11). |

The contract runs through the anchor services themselves, not only through
`runL1Phase`. On the loop's path, `AnchorService` and `ScriptAnchorService` stop
returning `null` for failures. A storage failure becomes `RETRYABLE`, and "no
matching anchor" becomes `NO_OP`. The same applies to their `onL1Rollback`.

Anchor confirmation splits into two steps:

1. **L1 fact (the loop's phase).** "Anchor tx `T` appears as a valid
   transaction in L1 block `(number, slot, hash)`" is written durably, keyed by
   the pending anchor. This replaces the in-memory `observedConfirmation`. The
   phase reports `DURABLE` only after that write.
2. **Local completion (any path).** Resolving the anchored app block and
   appending the `Confirmation` (now also recording the L1 inclusion point,
   D8a) can be done by the loop or by `tick()`/`forceAnchorNow()`. It is
   idempotent and keyed by the durable fact. If the app block is temporarily
   unavailable, completion waits without stalling L1 delivery.

The rollback phase removes both the durable facts and the completed
confirmations above the target, under `anchorLock`.

`AppChainAnchoredEvent` stays a best-effort notification. It is published after
local completion, can be lost on a crash or a delivery failure, and is not
implied by the cursor advancing. Consumers that need completeness read the
status or the confirmation journal.

### D5. Crash boundary: effects first, cursor second

Each phase writes its own durable state (the observation journal, the anchor
confirmation facts, the epoch spool). The loop then commits the block (D5a). A
failure or crash between the two delivers the same block again (I5). This
requires every phase to be idempotent for a repeated `(slot, hash)`:

| Phase | Required behaviour on redelivery |
|---|---|
| Window commit (in the loop, D5a) | One record write; committing an equal point again is a no-op. |
| Block observation | ADR-036 §5.5 already requires idempotent journaling. |
| Epoch-observation wake-up | A repeated wake-up is harmless (atomics and a coalesced wake-up). |
| Metadata and script anchor confirmation | Recording an already-recorded `(anchor, L1 point)` fact is a no-op, and completion is idempotent (D4a). No second `Confirmation` entry; a repeated best-effort `AppChainAnchoredEvent` is tolerated. To be verified in M2. |

One atomic batch across all phases is not proposed. The phases write to
different column families and services; idempotent redelivery is the smaller
change. Same-block idempotence does not cover a *different* block at the same
height after a fork. D5a handles that case.

### D5a. Durable delivery intent (r2, F1, F3)

The delivery record (D2) holds at most one pending intent. It is the crash
boundary for both directions.

**Forward.** Before running any phase for block `n`, the loop writes
`pending = APPLY(n, slot, hash)`. When every phase reports `DURABLE` or
`NO_OP`, one atomic write appends the point to the window and clears
`pending`. Only then is the published window replaced.

**Retry or restart with `APPLY(p)` pending.** Before each attempt, including the
first after a restart, the loop checks two things: the cursor is still
canonical, and `getCanonicalBlockReference(p.number)` equals `p`.

- If both hold, it delivers `p` again (D5 idempotence).
- If either fails, it atomically replaces the intent with `ROLLBACK(target)`,
  where `target` is the newest recorded point (window, then baseline history;
  D8) that is still canonical. That is the cursor itself when only `p`
  changed. It runs that rollback before delivering any block at `p.number`
  (I12, I16).

**An intent fixes the attempt's identity (r3, F1).** While `APPLY(p)` is
pending, every step of the attempt is bound to `p`:

- The loop reads the body for `p.number`, and its decoded header must equal
  `p` (number, slot, hash). Only then do phases run, and only on that block.
- Before the commit, `getCanonicalBlockReference(p.number)` must still equal
  `p`. The commit may publish only `p`.
- A changed reference or body at any step, including a fork that lands after
  the pre-attempt check, between phases, or before the commit, never
  substitutes another block under the intent. The attempt stops and goes back
  to the pre-attempt check, which turns the intent into a rollback.

So `p` stays recorded, as the pending intent or, if a fork lands after the
commit, as the window's newest point, until a rollback has removed its
effects. Without a pending intent, the loop writes `APPLY` for the reference it
just read, and the same binding applies from then on.

This covers a phase failure without a crash, a crash, and a replacement block
with the same slot or a different one. A failed-callback marker left at `p`'s
slot is removed by the observation rollback, because its slot is above the
target: the journal deletes the durable marker (`L1ObservationJournal.java:391-392`)
and the service resets its copy (`L1ObservationService.java:443-444`). So the
replacement block is not rejected forever.

**Rollback.** The loop writes `pending = ROLLBACK(target)`, leaving the
committed window unchanged, and runs every rollback phase with `target`. Each
phase is idempotent ("remove everything above `target`"). Removing by slot is
safe because `target` is always a point the loop recorded on the branch it
delivered (I16): every effect written after `target` on that branch belongs
to a later block and so has a higher slot. A point merely found on the new
branch would carry no such guarantee (r3, F7). The loop repeats the whole
rollback until every phase reports `DURABLE` or `NO_OP`. Only then does
one atomic write truncate the window to `target` and clear `pending`. A crash
anywhere in between replays the same rollback on restart. A `QUARANTINED`
outcome is persisted with the intent and is terminal (I11).

**Why a partly applied block cannot be finalized.** The stable L1 point is
computed only from the committed window (I9). The block named by `APPLY` is
above the cursor, so its observations cannot become stable, be proposed,
prepared or finalized before the block commits. A rollback to the cursor
therefore orphans only `SEEN_UNSTABLE` entries. If the journal reports a
quarantine anyway, it is preserved as a terminal outcome.

### D6. Rollbacks are derived from chain state

Before reading block `cursor.number + 1`, the loop checks that the cursor is
still canonical, with the same comparison as
`WalletQueryService.requireCanonicalPoint`. With an `APPLY` intent pending, it
also checks the intent's block (D5a). If a check fails, the loop walks the
durable window from newest to oldest. The first point whose canonical reference
matches becomes the rollback target. The loop records `ROLLBACK(target)` and
completes the existing rollback phases under the D5a rules before reading
forward (I3, I11). The handler's "reference rollback" phase becomes the window
truncation in D5a's final write, so the published window never shrinks before
the other phases succeed.

If no window point matches, the baseline history is searched next, newest
first. If nothing recorded matches, the divergence is deeper than anything
recorded, and the chain fails closed with `L1_DIVERGENCE_BEYOND_WINDOW` (I8). A
target is never derived from the new branch (I16; r3, F7). When finalized
observations are affected, this maps onto ADR-036's `DEEP_L1_ROLLBACK`
quarantine. Leaving the state requires operator action; it is not cleared by a
restart.

A `RollbackEvent` payload is never used as the target. The event only wakes the
loop.

*Alternative.* Fall back to rolling back to the oldest window slot minus one:
rejected, because it cannot be proven sufficient.

### D7. A composable body-retention boundary

`ChainStorageSubsystem`'s single delegate becomes a registry of boundaries, and
the pruner uses the minimum of their `oldestRequiredBlockNumber()`. An empty
value from one consumer means "no requirement" from that consumer.
Registration returns a handle that unregisters on close. Each configured app
chain owns one registration (D7a). Its value is `cursor.number + 1`, or
`target.number + 1` while a `ROLLBACK` intent is pending.

If the loop needs a body that is missing anyway, it reports
`L1_BODY_UNAVAILABLE` with the gap from `getEarliestRetainedBodyBlockNumber()`
and stops (I7). A stuck chain then holds bodies back; readiness reports that
lag so operators can act.

### D7a. Retention ownership and the prune handshake (r2, F6)

**Ownership.** The boundary belongs to the chain's durable delivery record, not
to its running state:

- The app-chain manager is built during `RuntimeNode` construction
  (`RuntimeNode.java:468`), before the kernel starts the prune stage. It
  registers one boundary per configured chain at that point.
- Until a chain's record is loaded, its boundary reports `0`. The pruner treats
  `0` as "retain everything" (`BlockPruner.java:76`), so startup pruning cannot
  run ahead of a stopped or not-yet-started chain.
- The registration survives `stop()` and `start()`. It is removed only when
  the chain is permanently closed or removed from configuration.

**Handshake.** `BlockPruner.pruneOnce` reads the boundary once and then deletes
a batch (`BlockPruner.java:73-126`), so a consumer that registers mid-pass is
not honoured. The registry therefore owns one lock:

- The pruner takes it to read the composite boundary and holds it while it
  computes and writes each batch.
- Registration, unregistration and lowering take the same lock.

Once `register` or a lowering to boundary `b` returns, no later batch deletes
any body with block number `≥ b`, including `b` itself (I15). Deleting below
`b` stays allowed.
Lowering happens before the loop records a `ROLLBACK` intent. Any remaining
race costs availability, never correctness, because a missing body fails closed
(I7).

**Operator recovery.** Removing a chain from configuration, restoring a
snapshot, a bootstrap start, or a fork deeper than the recorded history (I8)
can leave a chain unable to continue (`L1_BODY_UNAVAILABLE` or
`L1_DIVERGENCE_BEYOND_WINDOW`). It stays there until an operator runs an
explicit re-baseline (Q7). That action:

- refuses to run while a terminal quarantine is persisted, and never clears
  one;
- runs D8b over **all** retained L1-derived state, whatever its schema, so a
  new-schema confirmation or fact from a dead fork is invalidated rather than
  kept (r3, F5);
- then records a fresh baseline history at the current body tip (D8), with an
  empty window.

L1 input in the gap is never observed. A chain
that needs those observations must reconcile them through ADR-036 §6
historical verification. The completeness goal (§2.1) is scoped accordingly.

### D8. Starting position

The record separates a **baseline history** from **delivered** points (D2):

- **Baseline history.** At first start, the loop records the last `capacity`
  canonical points ending at the node's body tip, or `ORIGIN` when chain state
  holds no block (for example a fresh devnet, where delivery then starts at
  block 0). These points are recorded, not delivered (I9). They serve two
  purposes: delivery starts after the newest of them, and they are proven
  rollback targets for anything written later (I16).
  - Recording them is one atomic write that happens before any `APPLY`. A
    crash before it leaves nothing recorded and nothing processed, so the next
    start records afresh.
  - `ORIGIN` is always canonical. A rollback to `ORIGIN` removes all
    L1-derived state, and every rollback phase accepts it.
- **New chain:** the window is empty, so the stable point is unavailable until
  `depth + 1` blocks have been delivered (I9). This matches today's behaviour
  after a restart.
- **Upgraded chain** (journals exist, no delivery record): the same baseline
  history, plus D8a. The window is **not** seeded. Seeding would claim delivery
  without evidence that legacy journals match it (r2, F5).
- **Baseline history orphaned:** if no window point and no baseline-history
  point is canonical, the chain fails closed (I8). This happens only after a
  fork deeper than `capacity` blocks, and recovery is the operator re-baseline
  (D7a). The r2 rule that synthesized `canonical(baseline.number - 1)` as a
  target is withdrawn (r3, F7). A block found on the new branch is not a
  proven ancestor, and its slot can be higher than old-branch effects.

### D8a. Legacy reconciliation on upgrade (r2, F5; revised r3)

Existing L1-derived state may come from a fork the old code never rolled back
(#166). On the first start with this design, before any delivery:

- **Anchor confirmations.** Legacy `Confirmation` entries record only the
  inclusion slot; `blockHash` is the app block hash. They stay in the history
  for anchoring bookkeeping (the next range start), but the F7 frontier does
  not count them. Only confirmations completed under D4a, which record the L1
  inclusion point, count. The frontier therefore stays 0 until the first new
  anchor confirms and becomes stable. See Q3 for the alternative of
  re-verifying legacy entries against retained bodies.
- **Everything else L1-derived:** D8b.
- **Failed-callback marker.** It records a slot only. If a canonical block has
  that slot, the baseline history ends at that block's canonical predecessor,
  so the loop delivers from the failed block forward. Re-delivered blocks are
  handled idempotently (D5); missing bodies fail closed (I7). If no canonical
  block has that slot, the marker is deleted as orphaned (ADR-036 §5.5).

The upgrade counterexample (the #166 old-fork anchor at slot 101, the rollback
missed, the upgrade at new-fork tip 103) must leave the frontier at 0 (M3).

### D8b. L1 evidence reconciliation (r3, F5)

One procedure, used on upgrade (D8a) and by the operator re-baseline (D7a), runs
before any delivery. It applies to every retained L1-derived record, whatever
its schema version:

1. **Quarantine stays.** If a terminal quarantine is persisted, the procedure
   refuses to run. It never clears a quarantine.
2. **Slot-based lookup.** `ChainBlockReader.getCanonicalBlockReference` takes a
   block number, while records store `(slot, hash)`. The check is: the block
   number for the slot, then the canonical reference for that number, which
   must equal `(slot, hash)`. This is the check `RuntimeNode.retainedL1Block`
   already makes (`:902-905`). It is exposed to the app chain as one new
   `ChainBlockReader` method (empty by default; `RuntimeNode` implements it
   from chain state).
3. **What is checked:**
   - durable anchor facts, and confirmations that record an L1 inclusion
     point;
   - every observation-journal record, in any state (its key holds
     `(slot, blockHash)`, `L1ObservationJournal.java:552-572`);
   - every observer's finalized cursor (its value holds the finalized
     record's key, `:545-550`).
4. **Finalized evidence older than the cursors.** Once a cursor passes a
   finalized record, `acknowledge` deletes the record and keeps only a
   digest tombstone (`:253-256`). A digest cannot be checked. The evidence
   that remains is the committed app blocks, whose L1 observations carry
   `(slot, blockHash)`; `stageFinalized` reads them from there (`:265-288`).
   The procedure checks every L1 observation in committed app blocks. It only
   reads; committed app state is never modified.
5. **Outcomes:**
   - A dead non-finalized record is invalidated individually: removed, or for
     a confirmation, excluded from the frontier. A slot-range rollback is not
     used here, because state written before the delivery record existed has
     no proven ancestor (I16, F7).
   - A dead finalized or prepared observation produces the ADR-036 §5.8
     quarantine (`DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION` or
     `L1_INVALIDATED_PREPARED_VALUE`), which is terminal.
6. **Missing evidence fails closed.** If needed evidence is unavailable, for
   example committed app blocks missing after a snapshot restore without
   history, the chain enters `L1_EVIDENCE_UNAVAILABLE`. Leaving it is an
   explicit operator or governance decision outside this ADR, as for ADR-036's
   deep-rollback quarantine.

Cost (estimate): about two index reads per checked observation, once per
upgrade or re-baseline.

### D9. The window and frontier after a restart

Because the window is durable, the stable L1 point is available after a restart
once pending intents are recovered (D5a) and D6's check has run, subject to the
D9a fence. This replaces ADR-010 F7's "after a restart it stays 0 until the node
has seen `l1.stability-depth + 1` L1 blocks" with "after a restart it is
available once the durable delivery record has been recovered and checked
against chain state". An upgraded chain still waits for `depth + 1` delivered
blocks (D8). The F7 text is amended when M3 lands. The fail-closed cases (depth
0, no journal, I7, I8, I11) are unchanged.

### D9a. Freshness fence for safety-sensitive readers (r2, F4)

The monitor makes the window internally consistent. It does not make it
fresh: between a node rollback and the loop's next reconcile, a reader could
use a cached stable point that is no longer safe. Every safety-sensitive
reader therefore uses one accessor (I13), which returns "unavailable" unless:

1. the loop state is `RUNNING` and no intent is pending;
2. the record has been recovered and checked at least once since start; and
3. at read time, `getCanonicalBlockReference(newest.number)` equals the
   window's newest point.

The readers are the F7 frontier (effect dispatch, external claims and the
pre-execution recheck), `checkL1Ref`, the proposer's L1 reference, the
observation drain's stable slot, and the ADR-037 heartbeat.

Check 3 needs no event. Chain state rewrites its indexes during a rollback,
before any `RollbackEvent` is published (`SyncSubsystem.java:1058-1063`;
`DirectRocksDBChainState` `stageDeletesAfter`). So once a delivered block is
rolled back, the next read fails the check, even with every event dropped
(I6).

If the newest delivered point is still canonical, the canonical chain extends
it. The stable point, `depth` blocks below it, then has at least `depth`
canonical successors.

A rollback that lands after the check is the same as a reorg just after the
decision, which ADR-010 F7 already accepts. On "unavailable", the effect gate
reads 0.

**One coherent snapshot (r3, F4).** The loop publishes an immutable snapshot
(committed window, pending intent, loop state, reconciled flag) through one
reference, replaced on each commit. The accessor reads that one reference, so
checks 1–3 see a single coherent state rather than separately sampled fields.

**Voting is fenced by delivery health, not by `UNKNOWN` (r3, F4).**
`checkL1Ref` returning `UNKNOWN` does not stop every live vote.
`AppChainEngine.verifyProposalL1Ref` rejects `UNKNOWN` only at view 0
(`AppChainEngine.java:2466-2476`); a higher-view recovery proposal proceeds on
its prepared certificate. Instead, delivery health joins the existing
voting-health supplier. Today `setVotingHealth` is wired to
`l1ObservationInputsHealthy` (`AppChainSubsystem.java:4845`, `:5725`); r3 adds
"the D9a fence is open" to it. The engine already consults that supplier before
proposing (`doProposeTick`, `AppChainEngine.java:707`) and before voting on any
proposal at any view (`handleProposal`, `:1117`, `:1254`). So while the fence is
closed (before reconciliation, during an intent retry, or in a terminal state),
the node neither proposes nor votes, including on prepared higher-view recovery
proposals.

`UNKNOWN` keeps its meaning (a reference outside the window). ADR-036's
certified historical and catch-up rules for already finalized blocks are
unchanged.

### D10. Observability

Status adds, per chain: the baseline and cursor (number, slot, hash), the
pending intent, the body tip, lag in blocks, the loop state (`RUNNING`,
`RETRYING_BLOCK`, `RETRYING_ROLLBACK`, `L1_BODY_UNAVAILABLE`,
`L1_DIVERGENCE_BEYOND_WINDOW`, `L1_EVIDENCE_UNAVAILABLE`, `QUARANTINED`), the
last failure's phase and
outcome, and whether the D9a fence is open. Readiness degrades when the loop is
not `RUNNING` or the lag exceeds a node-local threshold. Readiness is a signal
only; safety comes from D9a.

### D11. Scope: app chains first, extract later

The loop is written as a small component behind one interface: read the next
canonical block from a cursor, detect divergence, and call
`apply(block)`/`rollback(point)`. It lives in `runtime/appchain` for now. It is
moved to a shared place only when a second consumer needs it (for example an
archive or history projection that cannot survive a missed block), following
the repository's simplicity rules.

## 7. Delivery loop (illustrative)

Illustrative pseudocode only; not an implementation. `commit(...)` is one atomic
write of the delivery record (D2), followed by replacing the published window.

```text
loop until stopped:
    wait for a wake-up or the poll interval
    if state is fail-closed (L1_BODY_UNAVAILABLE, L1_DIVERGENCE_BEYOND_WINDOW,
                             L1_EVIDENCE_UNAVAILABLE, QUARANTINED): continue
    # pre-attempt check: recover or derive a rollback (D5a, D6)
    if (pending is APPLY(p) and (cursor not canonical or canonical(p.number) != p))
       or (pending is none and cursor not canonical):
        target = newest recorded point (window, then baseline history) still canonical
        if target is none: state = L1_DIVERGENCE_BEYOND_WINDOW; continue   # I8, I16
        boundary.lower(target.number + 1)       # D7a, before the intent
        commit(pending = ROLLBACK(target))
    if pending is ROLLBACK(t):
        outcomes = every rollback phase(t)       # D4a outcomes, idempotent
        if any QUARANTINED: persist; state = QUARANTINED; continue
        if any RETRYABLE: state = RETRYING_ROLLBACK; backoff; continue
        commit(window = window.truncateAfter(t), pending = none)
    # forward delivery, bound to the intent (D1, D4, D5, D5a)
    while cursor.number < bodyTip.number and not stopped:
        if pending is none:
            ref = canonicalReference(cursor.number + 1)          # index only
            if ref is empty or canonical(cursor.number) != cursor: break
            commit(pending = APPLY(ref))
        p = pending.point                                         # the attempt's identity
        body = blockByNumber(p.number)
        if body missing: state = L1_BODY_UNAVAILABLE; break
        if decodedHeader(body) != p: break                       # changed: back to pre-attempt check
        outcomes = every forward phase(BlockAppliedEvent(era, p.slot, p.number, p.hash, body))
        if any QUARANTINED: persist; state = QUARANTINED; break
        if any RETRYABLE: state = RETRYING_BLOCK; backoff; break  # APPLY(p) stays pending
        if canonical(p.number) != p: break                       # fork during attempt: recover
        commit(window = window + p, pending = none)              # publishes exactly p
        boundary.set(p.number + 1)
```

## 8. Compatibility and migration

- **Consensus and wire:** unchanged. L1 references, observations and anchors
  carry the same values. Only the timing of local processing changes.
- **Storage:** node-local only, with no change to replicated state:
  - the per-chain delivery record (D2) in `AppLedgerStore` meta;
  - durable anchor confirmation facts that replace the in-memory
    `observedConfirmation` (D4a);
  - new `Confirmation` entries that also record the L1 inclusion point. Legacy
    entries without it still decode, and are excluded from the F7 frontier
    (D8a).
- **Upgrade:** D8 and D8a. The window is not seeded; the frontier waits for a
  new-schema confirmation; journaled observations are checked against chain
  state. Blocks applied while the old code was not subscribed remain missed, as
  they are today; the design prevents new gaps.
- **API:** `BlockBodyRetentionBoundary` keeps its contract. The single
  `setBlockBodyRetentionBoundary` either becomes a registration call or is
  implemented through one. It has no production caller on `main`. The anchor
  services' L1 methods change their result types (D4a); they are internal to
  `runtime/appchain`.
- **Events:** the node's events and their subscribers are unchanged. The app
  chain still subscribes; its callbacks only signal. `AppChainAnchoredEvent`
  stays best-effort (D4a).

## 9. Alternatives considered

- **Reconcile on start only** (the first proposal in #166): fixes P1 for
  in-process restarts only (§1.4).
- **Start the app chain before sync:** fixes the process-restart ordering only,
  and contradicts the staging in `RuntimeKernelStages`.
- **Keep the subscription alive while stopped:** fixes in-process restarts only,
  and breaks the generation fence that keeps a retired callback out of a new
  generation.
- **Re-publish blocks as a new `AppChainL1BlockEvent`:** rejected in D3.
- **A transactional outbox written inside the UTxO apply batch, as ADR-039
  does:** strong for archive projections, but it would tie the app chain to UTxO
  apply internals. The app chain needs only blocks, which chain state already
  has.
- **A shared follower framework now:** deferred by D11 until a second consumer
  exists.
- **Rollback through the existing handler with no intent** (r1): withdrawn in
  r2. A failed or interrupted rollback phase was lost (F3).
- **Seeding the window from chain state on upgrade** (r1 D8): withdrawn in r2.
  A canonical seed says nothing about legacy journals and can make a dead-fork
  anchor stable (F5).
- **Automatic re-baseline to `canonical(baseline.number - 1)`** (r2 D8):
  withdrawn in r3. A block found on the new branch is not a proven ancestor,
  so slot-range rollback to it can leave old-branch effects behind (F7).
- **Slot-range rollback to repair legacy state** (r2 D8a): withdrawn in r3 in
  favour of per-record invalidation (D8b), for the same reason.
- **One atomic batch for all phases plus the record:** not proposed. The phases
  span several stores; the single intent plus idempotent phases gives the same
  guarantees with less coupling.

## 10. Consequences

- **Positive:**
  - No missed blocks or rollbacks from any cause in §1.2–§1.3 while the chain
    stays configured.
  - A partly applied block or rollback is always finished or undone before
    anything else happens (D5a).
  - P2 is fixed by construction.
  - Anchor storage failures can no longer be mistaken for "nothing to do"
    (D4a).
  - Readers cannot act on a rolled-back stable point (D9a).
  - App-chain work leaves the sync thread.
  - The frontier is available after an ordinary restart.
  - A reusable pattern for other derived processing.
- **Negative:**
  - Each block body is read and decoded once more per chain (today the node
    hands over the parsed block). This is negligible at L1 block rates; catch-up
    after a long stop is the main cost.
  - Each safety-sensitive read adds one index lookup (D9a).
  - A stuck chain holds body pruning back (visible through D10).
  - Every phase must be idempotent (D5), and the anchor services need the D4a
    outcome contract.
  - After an upgrade, `L1_ANCHORED` effects wait for the next anchor
    confirmation to become stable.
  - Upgrade and re-baseline scan the L1 observations in committed app blocks
    once (D8b).
  - A fork deeper than the recorded history (window plus baseline history)
    needs an operator re-baseline.

## 11. Milestones, gates and verification

### M0. Executable reproductions (entry: this ADR accepted)

Failing tests on `main` that pass at the end of M3:

- the #166 in-process stop/start scenario (P1);
- a rollback published before the app chain subscribes (the §1.3 restart
  ordering);
- every event dropped (`NoopEventBus`): blocks still delivered (I6);
- an observer failing at slot 10 while slot 11 arrives: slot 11 is observed
  after recovery (P2);
- an anchor-confirmation storage failure (`metaPutAll` throws), followed by a
  crash, in metadata mode and in script mode: the confirmation is recorded
  exactly once after restart, and the cursor never passes the block (F2);
- **F1:** old-fork block 101 partly applied (one phase fails, and separately a
  crash before the commit). L1 replaces 101, once at the same slot and once at
  a different slot. No old-fork effect survives, and the replacement is
  delivered.
- **F3:** a rollback phase fails, and separately a crash between rollback
  phases and before the window write. No new-branch block is delivered and the
  window does not shrink until every phase succeeds. A repeated failure keeps
  retrying across restarts.
- **F4:** an anchor stable in the cached window; L1 rolls it back with every
  event dropped; an effect tick runs before the next poll. The frontier is 0.
- **F5:** the #166 old-fork anchor at slot 101, the rollback missed, then the
  upgrade at new-fork tip 103: the frontier stays 0.
- **F6:** cursor 100; stop the chain; let the pruner pass 101; restart: the
  bodies are still present. A boundary of 101 protects 101 itself.
- **F1 (r3):** with `APPLY(old101)` pending over cursor 100, L1 replaces 101
  between the pre-attempt check and the body or reference read, then again
  between phases and the commit. `new101` is never run or committed under the
  old intent, and old101's effects are removed before `new101` is delivered.
- **F4 (r3):** before reconciliation, during an intent retry and in a terminal
  state, the node neither proposes nor votes, both on a view-0 proposal and on
  a prepared higher-view recovery proposal.
- **F5 (r3):**
  - a legacy journal holding only a finalized observation from old-fork 101,
    with the rollback missed before the upgrade: the terminal quarantine is
    raised and committed app state is untouched;
  - a new-schema confirmation from a dead fork, kept across a
    removed-configuration interval: the operator re-baseline invalidates it.
- **F7 (r3):**
  - baseline old100 at slot 100 with `APPLY(old101)` having written a fact at
    slot 105, then a fork below 99 where new99 is at slot 108 and new100 at
    slot 110: the target is a recorded point, and no slot-105 fact survives;
  - a rollback to an empty chain (`ORIGIN`);
  - a crash during baseline-history recording, and during an operator
    re-baseline.

Exit: the tests exist and fail for the stated reason.

### M1. Composable retention boundary (D7, D7a)

Entry: M0. Registry with minimum semantics, registration handles, `0` meaning
"retain everything", registration at construction, survival across stop and
start, and the one-lock handshake. Tests: several consumers, an empty
requirement, unregistering, stop/prune/restart, and a deterministic
interleaving of registration or lowering against an in-flight prune batch (I7,
I15). Exit: `BlockPrunerTest` and the new tests pass.

### M2. Delivery record, outcomes and idempotent phases (D2, D4a, D5, D5a)

Entry: M1. The delivery record with its intent; the D4a outcome contract through
`runL1Phase`, both anchor services and every rollback phase; durable anchor
facts with split completion; the L1 inclusion point in new confirmations. Audit
and test each phase in the D5 table for redelivery of the same `(slot, hash)`,
and each rollback phase for repetition. Exit: one redelivery test per phase and
per rollback phase passes; the F2 tests pass; the existing app-chain tests are
unchanged.

### M3. Delivery loop, fence and upgrade (D1, D3, D4, D6, D8, D8a, D8b, D9, D9a)

Entry: M2. The loop, the wake-up and poll, intent recovery and binding,
divergence detection over recorded points, fail-closed states, the baseline
history, legacy and re-baseline reconciliation (D8b, including the slot-based
lookup and the committed-app-block evidence scan), and the D9a fence for every
listed reader and the voting-health supplier. Event callbacks only signal. The single-slot
observation replay is removed. Exit: the I1–I16 tests and every M0 test pass,
and the ADR-010 F7 text is amended.

### M4. Observability and qualification (D10)

Entry: M3. Status and readiness fields; the release-QA app-chain categories
pass, including script-anchor and rotation-governance. Exit: all tests green.

### Verification strategy

- **Differential model test:** generate random schedules of block applies,
  rollbacks of random depth, replacements at the same and different slots,
  restarts, phase failures and storage failures, crashes at every crash point,
  dropped events and pruning against an in-memory chain state. After each step,
  compare the sequence of blocks delivered to the handler, and the surviving
  L1-derived state, with the canonical chain read independently from chain
  state. The expected values come from chain state, not from the loop under
  test.
- **Crash points:** a kill switch before and after each forward phase, each
  rollback phase, each intent write and each commit. Each must end with I1,
  I3, I5, I11 and I12 holding after restart.
- **Interleavings:** deterministic schedules in which a fenced reader (the
  effect gate, `checkL1Ref`, the proposer, the heartbeat, the voting-health
  supplier) runs before, during and after reconciliation, with a pending
  `APPLY`, a pending `ROLLBACK`, a retry and a terminal state, with every event
  dropped (I13). Forks are injected between every pair of loop steps, so the
  attempt-binding (I12) and recorded-target (I16) rules are exercised.
- **Negative cases:** a missing body (I7), divergence beyond the window (I8), a
  body tip below the header tip (I10), Byron blocks, quarantine outcomes from
  the observation journal (I11), several chains with different cursors sharing
  one pruner (D7).
- **End-to-end:** the existing two-node script-anchor and rotation release-QA
  runs, plus a restart of one node during an L1 rollback on devnet.

## 12. Risks

- **A phase that cannot become idempotent** would make recovery double-apply.
  M2 audits every forward and rollback phase before the loop lands.
- **Anchor-service refactor:** D4a changes code that gates effects. The F2
  crash tests in metadata and script modes are the gate.
- **Lag under load:** the loop runs behind the node by design. Lag delays
  proposals, observations and the frontier. It is safe only because of the
  D9a fence (r2: the r1 claim that lag alone is safe was wrong). D10 surfaces
  lag.
- **A rollback that keeps failing** stops all delivery for the chain
  (`RETRYING_ROLLBACK`). This is visible, and fail-closed by design.
- **Retention held by a stuck chain:** disk use grows until the chain recovers
  or an operator acts. This is visible, never silent.
- **Window size versus rollback depth:** a rollback deeper than
  `stability depth + 64` blocks fails closed (I8). Mainnet allows rollbacks up
  to k = 2160 blocks, but such rollbacks do not happen in practice; see Q2.

## 13. Open questions

- **Q1. One cursor or one per phase.** (a) A single cursor (D4): simplest, and
  an observer failure already stops voting. (b) One cursor per phase, with
  retention at their minimum: anchors and the window keep advancing while an
  observer is retried. Lean (a).
- **Q2. Window size.** (a) Keep `max(depth, 1) + 64`. (b) Make it configurable
  for chains that want to survive deeper rollbacks without operator action.
  Lean (a), adding (b) only on demand.
- **Q3. Legacy anchor confirmations on upgrade** (r2, replaces r1's seeding
  question).
  - (a) Keep them for bookkeeping but exclude them from the F7 frontier until a
    new-schema confirmation exists (D8a).
  - (b) Re-verify each legacy entry: the canonical block at its slot must have a
    retained body containing its tx as a valid transaction. Keep it, with the
    L1 point backfilled, if so; drop it from the frontier otherwise.
  - (c) Seed the window as in r1 (withdrawn).

  Lean (a): no new verification code, and the cost is waiting for one anchor
  after the upgrade. (b) is a later improvement if that wait matters.
- **Q4. Using the event's parsed block.** (a) Always read from storage. (b) Use
  the event's `Block` when its number, slot and hash equal the next canonical
  reference. Lean (a) first, then (b) only if profiling shows decode cost.
- **Q5. Shared facility.** Whether to place the loop in a shared module now so
  that archive or history work can adopt it. Lean: not now (D11).
- **Q6. Anchor completion paths** (r2). (a) The loop records the L1 fact; the
  loop, `tick()` or `forceAnchorNow()` may complete it locally (D4a). (b) Only
  the loop completes, retrying the L1 block until the app block is available.
  Lean (a): (b) would stall all L1 delivery while a lagging member catches up
  on app blocks.
- **Q7. Operator re-baseline** (r2). (a) An admin endpoint, like the existing
  app-chain admin operations. (b) A one-shot configuration flag. Lean (a): it
  is explicit, audited and needs no restart. Either way it runs D8b, refuses
  while a terminal quarantine is persisted, and never clears one (r3).
- **Q8. Extent of the committed-app-block evidence scan** (r3). (a) Check every
  L1 observation in committed app blocks (D8b). (b) Check only observations
  newer than a configured horizon, and treat older ones as immutable. Lean (a):
  it needs no new trust assumption, and the cost is one-time.

## 14. Related findings (out of scope)

- Root ADR-039 §8 states that projection replay is protected by a
  `BlockBodyRetentionBoundary` clamp, but no production code on `main` calls
  `setBlockBodyRetentionBoundary`. D7's registry is the natural place to
  install that clamp.
- `DefaultUtxoStore.reconcile` and `DefaultAccountStateStore.reconcile` compare
  block numbers only. A fork at the same height after a restart relies on the
  live `RollbackEvent`.
- The block producer swallows non-genesis publish failures at debug level
  (`BlockProducerHelper.java:144-149`). Subscribers other than the app chain
  can still miss a produced block.
- Issue #166's "possible directions" suggests dropping confirmations whose
  `(l1Slot, blockHash)` is no longer on the chain. That cannot work as written,
  because `blockHash` is the app block hash (§1.2). D4a and D8a replace that
  suggestion.

## Revision history

- **r1** (2026-10-03): initial proposal.
- **r2** (2026-10-03; responds to the review of `4555ea681`):
  - F1 → new D5a (a durable `APPLY`/`ROLLBACK` intent) and I12; partly applied
    blocks are rolled back to the cursor before a replacement, at the same or
    a different slot; an orphaned failed-callback marker is cleared by that
    rollback.
  - F2 → new D4a: explicit outcomes through both anchor services and every
    rollback phase; durable anchor facts with split completion;
    `AppChainAnchoredEvent` stated as best-effort.
  - F3 → D5a and D6 now commit a shortened window only after every rollback
    phase succeeds, and replay a pending rollback after restart; new I11.
  - F4 → new D9a freshness fence and I13; the per-block handler no longer
    appends to the window; the §12 lag claim corrected.
  - F5 → D8 rewritten (baseline separate from delivered points, no seeding) and
    new D8a and I14; Q3 replaced; the #166 suggestion corrected in §14.
  - F6 → new D7a and I15: retention owned by configured chains from node
    construction, across stop and start; a one-lock prune handshake; operator
    re-baseline; §2.1 completeness scoped; new Q7.
  - M0–M3 and the verification strategy extended with every counterexample.
  - Author-found: D8 now re-chooses a baseline that is orphaned before anything
    was delivered, instead of failing closed; I8, D6 and §7 updated.
- **r3** (2026-10-04; responds to the review of `e3da784e6`):
  - F1 → D5a, I12 and §7: an `APPLY(p)` binds the attempt; the body header
    and the pre-commit reference must equal `p`; a change routes back to the
    pre-attempt check and is never substituted.
  - F4 → D9a and I13: one immutable published snapshot; delivery health joins
    the existing voting-health supplier, fencing proposing and voting at any
    view, including prepared recovery; `UNKNOWN` unchanged.
  - F5 → new D8b and I14: one reconciliation for upgrade and re-baseline, over
    all schemas; per-record invalidation; finalized evidence from journal
    cursors and committed app blocks; terminal quarantine never cleared;
    `L1_EVIDENCE_UNAVAILABLE` fail-closed; slot-based canonical lookup stated;
    D7a operator recovery runs D8b; new Q8.
  - F6 → I15 and D7a: the inequality corrected (`≥ b`, including `b`).
  - F7 → D8 baseline history (recorded, not delivered) replaces the r2
    automatic re-baseline (withdrawn); new I16 (targets are recorded points
    only); D6, I8 and §7 updated.
  - M0, M3 and the interleaving strategy extended with the round-2
    counterexamples (F1, F4, F5, F7).
