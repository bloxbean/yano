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

1. **Own position.** Each app chain persists its own L1 position: the bounded
   window of recent L1 points it has fully processed. The newest point is the
   cursor. (D2)
2. **Pull, in order.** A single delivery loop per chain reads the next
   canonical block from chain state with the existing `ChainBlockReader`, up to
   the node's body tip. It hands the block to the **existing** per-block
   handler, and advances the cursor only after every phase succeeded. (D1, D3,
   D4, D5)
3. **Rollbacks from chain state.** The loop detects that a recorded point left
   the canonical chain, and runs the **existing** rollback handler to the newest
   recorded point that is still canonical. It does not depend on receiving a
   `RollbackEvent`. A divergence deeper than the window fails closed. (D6)
4. **Events become wake-ups.** The app chain keeps subscribing to the node's
   events, but only to wake the loop. A periodic poll backs them up. Losing an
   event costs latency, never correctness. (D3)
5. **Bodies stay until processed.** The chain registers its cursor as a
   `BlockBodyRetentionBoundary`. Chain storage changes from one boundary slot to
   the minimum across registered consumers. A missing body fails closed. (D7)

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
  For example, a transient failure in anchor confirmation loses that
  confirmation for good. Only block observations have a retry barrier.

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

- Every canonical L1 block between the chain's starting point and the node's
  body tip is delivered to the app chain's per-block handler, in canonical
  order.
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
- No historical backfill before the chain's starting point. Late observer
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
  plugin observer or anchor phase that throws; concurrent node rollbacks and
  pruning; several app chains in one node.
- **Not covered:** corruption of chain state or the app ledger; an L1 rollback
  deeper than the recorded window (detected, fails closed; see D6); a
  nondeterministic observer (already a consensus halt under ADR-036 §5.6).
- **Secrets:** none are introduced.

## 5. Invariants

Each invariant names the decisions it constrains and is testable (§11).

- **I1 Completeness (D1, D3, D5).** For every canonical block `B` with
  `cursor₀ < B.number ≤ bodyTip`, where `cursor₀` is the chain's starting
  point, the app chain runs every per-block phase for `B` before running any
  phase for `B`'s canonical successor.
- **I2 Order and continuity (D1, D6).** Blocks are delivered in strictly
  increasing block number. Before delivering block `n`, the canonical reference
  of `n - 1` equals the cursor (number, slot and hash).
- **I3 Rollback before new branch (D6).** If any recorded point is no longer
  canonical, the rollback handler runs with the newest recorded point that is
  still canonical as its target, before any block of the new branch is
  delivered.
- **I4 No skip on failure (D4).** The cursor never moves past a block whose
  phases did not all succeed. The loop retries that block.
- **I5 Durable after effects (D5).** The cursor is advanced only after every
  phase for the block has succeeded and made its own writes durable. After a
  crash, the block is delivered again, and every phase handles a repeated
  `(slot, hash)` without changing its result.
- **I6 Events are hints (D3).** With a `NoopEventBus`, or with every event
  dropped, the app chain still delivers every block, within the poll interval.
- **I7 Retention (D7).** While a chain is registered, no body with
  `number > cursor.number` is pruned. If a needed body is missing, the chain
  reports `L1_BODY_UNAVAILABLE` and delivers nothing further; it never skips.
- **I8 Deep divergence fails closed (D6).** If no recorded point is canonical,
  the chain reports `L1_DIVERGENCE_BEYOND_WINDOW`, stops delivering, and the
  stable L1 point is unavailable (so the F7 frontier is 0).
- **I9 Window reflects delivery (D2, D9).** The stable L1 point and
  `checkL1Ref` read only points that have been delivered. After a restart they
  are loaded from the durable window, and are used only after the I3 check has
  run.
- **I10 Body-tip bound (D1).** The loop never delivers a block above
  `getLocalTip()`.

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

### D2. A durable per-chain L1 window; its newest point is the cursor

The existing `recentL1Points` window (capacity `max(depth, 1) + 64`) becomes
durable. Each chain stores it in its own `AppLedgerStore` meta as an ordered
list of `(blockNumber, slot, blockHash)`, and keeps the in-memory copy as a
cache under the existing monitor. The newest entry is the cursor. The window
also provides the history needed to find a fork point after a restart (D6).

*Alternatives.* Persist only the cursor and walk parent hashes back through
headers: possible, but it needs header decoding and adds a code path. The
bounded window is the data the subsystem already maintains. Persist one cursor
per phase (as ADR-039 does per contributor): see D4 and open question Q1.

### D3. One delivery loop per chain; events only wake it

Each chain owns one delivery loop on a dedicated worker, started and stopped
with the subsystem's generation. The `BlockAppliedEvent` and `RollbackEvent`
subscriptions remain, but their callbacks only signal the loop with a coalesced
wake-up, following `L1EpochObservationCoordinator.onBlockApplied`. A periodic
poll (default 1 s, node-local) also wakes the loop. The loop is the only writer
of L1-derived state. Readers of the window keep using the monitor introduced in
`0dfdd48af`.

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

### D5. Crash boundary: effects first, cursor second

Each phase writes its own durable state (the observation journal, the anchor
confirmation journal, the epoch spool). The loop then writes the new window and
cursor. A crash between the two delivers the block again on restart (I5). This
requires every phase to be idempotent for a repeated `(slot, hash)`:

| Phase | Required behaviour on redelivery |
|---|---|
| Window append | No duplicate entry for an equal point. |
| Block observation | ADR-036 §5.5 already requires idempotent journaling. |
| Epoch-observation wake-up | A repeated wake-up is harmless (atomics and a coalesced wake-up). |
| Metadata and script anchor confirmation | Confirming an already-confirmed anchor must not add a journal entry or publish a second `AppChainAnchoredEvent`. To be verified in M2. |

One atomic batch across all phases is not proposed. The phases write to
different column families and services; idempotent redelivery is the smaller
change.

### D6. Rollbacks are derived from chain state

Before reading block `cursor.number + 1`, the loop checks that the cursor is
still canonical, with the same comparison as
`WalletQueryService.requireCanonicalPoint`. If it is not, the loop walks the
durable window from newest to oldest. The first point whose canonical reference
matches becomes the rollback target, and the loop runs the existing rollback
handler with it before reading forward (I3).

If no window point matches, the divergence is deeper than the window. The chain
fails closed with `L1_DIVERGENCE_BEYOND_WINDOW` (I8). When finalized
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
Registration returns a handle that unregisters on close. Each app chain
registers `cursor.number + 1` while it is running.

If the loop needs a body that is missing (for example after a snapshot or
bootstrap start, or because pruning ran before registration), it reports
`L1_BODY_UNAVAILABLE` with the gap from `getEarliestRetainedBodyBlockNumber()`
and stops (I7). A stuck chain then holds bodies back; readiness reports that
lag so operators can act.

### D8. Starting position

A chain without a durable window starts at the node's body tip. This matches
today's behaviour, where a fresh window fills from live blocks. An existing
chain upgraded to this design seeds its window from the last `capacity`
canonical points at the body tip, without running handlers for them, because
the previous code delivered those blocks through events. See Q3.

### D9. The window and frontier after a restart

Because the window is durable, the stable L1 point is available after a restart
once D6's check has run. This replaces ADR-010 F7's "after a restart it stays 0
until the node has seen `l1.stability-depth + 1` L1 blocks" with "after a
restart it is available once the durable window has been checked against chain
state". The F7 text is amended when M3 lands. The fail-closed cases (depth 0, no
journal, I7, I8) are unchanged.

### D10. Observability

Status adds, per chain: the cursor (number, slot, hash), the body tip, lag in
blocks, the loop state (`RUNNING`, `RETRYING_BLOCK`, `L1_BODY_UNAVAILABLE`,
`L1_DIVERGENCE_BEYOND_WINDOW`), and the last failure's phase. Readiness degrades
when the loop is not `RUNNING` or the lag exceeds a node-local threshold.

### D11. Scope: app chains first, extract later

The loop is written as a small component behind one interface: read the next
canonical block from a cursor, detect divergence, and call
`apply(block)`/`rollback(point)`. It lives in `runtime/appchain` for now. It is
moved to a shared place only when a second consumer needs it (for example an
archive or history projection that cannot survive a missed block), following
the repository's simplicity rules.

## 7. Delivery loop (illustrative)

Illustrative pseudocode only; not an implementation.

```text
loop until stopped:
    wait for a wake-up or the poll interval
    if state is a fail-closed state: continue
    if window not empty and window.newest is not canonical:
        target = newest window point that is still canonical
        if none: state = L1_DIVERGENCE_BEYOND_WINDOW; continue
        rollbackHandler(target)                // existing handler, all phases
        window.truncateAfter(target); persist(window)
    while cursor.number < bodyTip.number and not stopped:
        n = cursor.number + 1
        ref = canonicalReference(n)            // index only
        if ref is empty or (n - 1) is no longer the cursor: break   // re-check divergence
        body = blockByNumber(n)
        if body missing: state = L1_BODY_UNAVAILABLE; break
        event = BlockAppliedEvent(era, ref.slot, n, ref.hash, decoded body)
        if not blockHandler(event):            // existing handler; true only if all phases succeeded
            state = RETRYING_BLOCK; backoff; break
        window.append(ref); persist(window)    // cursor advances after the effects
        retentionBoundary = n + 1
```

## 8. Compatibility and migration

- **Consensus and wire:** unchanged. L1 references, observations and anchors
  carry the same values. Only the timing of local processing changes.
- **Storage:** new node-local `AppLedgerStore` meta for the durable window.
  There is no change to replicated state.
- **Upgrade:** D8 seeds the window for existing chains. Blocks applied while the
  old code was not subscribed remain missed, as they are today; the design
  prevents new gaps.
- **API:** `BlockBodyRetentionBoundary` keeps its contract. The single
  `setBlockBodyRetentionBoundary` either becomes a registration call or is
  implemented through one. It has no production caller on `main`.
- **Events:** the node's events and their subscribers are unchanged. The app
  chain still subscribes; its callbacks only signal.

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

## 10. Consequences

- **Positive:** no missed blocks or rollbacks from any cause in §1.2–§1.3; P2 is
  fixed by construction; app-chain work leaves the sync thread; the frontier is
  available after a restart; a reusable pattern for other derived processing.
- **Negative:** each block body is read and decoded once more per chain (today
  the node hands over the parsed block). This is negligible at L1 block rates,
  and catch-up after a long stop is the main cost. A stuck chain holds body
  pruning back (visible through D10). Every phase must be idempotent (D5).

## 11. Milestones, gates and verification

### M0. Executable reproductions (entry: this ADR accepted)

Failing tests on `main` that pass at the end of M3:

- the #166 in-process stop/start scenario (P1);
- a rollback published before the app chain subscribes (the §1.3 restart
  ordering);
- every event dropped (`NoopEventBus`): blocks still delivered (I6);
- an observer failing at slot 10 while slot 11 arrives: slot 11 is observed
  after recovery (P2);
- an anchor-confirmation phase failing once: the confirmation is recorded on
  retry.

Exit: the tests exist and fail for the stated reason.

### M1. Composable retention boundary (D7)

Entry: M0. Registry with minimum semantics, registration handles, and tests for
several consumers, an empty requirement, unregistering, and `0` meaning
"retain everything". Exit: `BlockPrunerTest` and the new tests pass.

### M2. Durable window and idempotent phases (D2, D5)

Entry: M1. Persist and load the window; audit and test each phase in the D5
table for redelivery of the same `(slot, hash)`. Exit: one redelivery test per
phase passes, and the existing app-chain tests are unchanged.

### M3. Delivery loop (D1, D3, D4, D6, D8, D9)

Entry: M2. The loop, the wake-up and poll, divergence detection, fail-closed
states, starting position and upgrade seeding. Event callbacks only signal. The
single-slot observation replay is removed. Exit: I1–I10 tests and the M0 tests
pass, and the ADR-010 F7 text is amended.

### M4. Observability and qualification (D10)

Entry: M3. Status and readiness fields; the release-QA app-chain categories
pass, including script-anchor and rotation-governance. Exit: all tests green.

### Verification strategy

- **Differential model test:** generate random schedules of block applies,
  rollbacks of random depth, restarts, crashes between phases and the cursor
  write, dropped events and pruning against an in-memory chain state. After
  each step, compare the sequence of blocks delivered to the handler with the
  canonical chain read independently from chain state. The expected values come
  from chain state, not from the loop under test.
- **Crash points:** a kill switch before and after each phase, and before and
  after the window write. Each must end with I1 and I5 holding after restart.
- **Negative cases:** a missing body (I7), divergence beyond the window (I8), a
  body tip below the header tip (I10), Byron blocks, several chains with
  different cursors sharing one pruner (D7).
- **End-to-end:** the existing two-node script-anchor and rotation release-QA
  runs, plus a restart of one node during an L1 rollback on devnet.

## 12. Risks

- **A phase that cannot become idempotent** would make crash recovery
  double-apply. M2 audits every phase before the loop lands.
- **Lag under load:** the loop runs behind the node by design. The stable point
  then moves more slowly, which delays proposals and the frontier but is safe.
  D10 surfaces it.
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
- **Q3. Upgrade seeding.** (a) Seed the window from chain state at the body tip
  (D8). (b) Start empty, as a fresh chain does. Lean (a): the frontier stays
  available across the upgrade restart.
- **Q4. Using the event's parsed block.** (a) Always read from storage. (b) Use
  the event's `Block` when its number, slot and hash equal the next canonical
  reference. Lean (a) first, then (b) only if profiling shows decode cost.
- **Q5. Shared facility.** Whether to place the loop in a shared module now so
  that archive or history work can adopt it. Lean: not now (D11).

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

## Revision history

- **r1** (2026-10-03): initial proposal.
