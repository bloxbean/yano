# ADR-NET-005: Yaci-Yano Boundary Data Flow and Ordered Block Apply

## Status

Proposed

## Date

2026-05-19

## Problem Statement

Yano currently processes block bodies, epoch-boundary work, and derived
ledger-state updates synchronously inside the Yaci/Netty callback path. Mainnet
sync logs show this can block a Netty I/O event-loop thread for more than one
minute at an epoch boundary while reward, AdaPot, snapshot, and governance work
runs. During that time the channel cannot reliably flush keepalive messages or
process keepalive responses. Public relays may close the connection, after which
Yano has to recover the peer session.

The immediate resilience goal is:

- keep network I/O and keepalive responsive even when Yano ledger work is slow;
- preserve the exact block application order used today;
- never skip a block if persistence or derived-state processing fails;
- make recovery deterministic from durable Yano state rather than transient
  Yaci in-memory cursors.

A naive async implementation is not acceptable. If Yano simply enqueues
`onBlock(...)` work and immediately returns to Yaci, Yaci may advance its
in-memory chain-sync cursor before Yano has durably applied the block. That is
safe only if Yano treats Yaci's cursor as disposable and recovers from its own
durable cursors.

There is a second failure mode to make explicit. Today `ChainState.storeBlock`
and `EventBus.publish(BlockAppliedEvent)` happen in one callback path, but
derived-state listeners keep their own durable cursors and some event-bus
failures are not propagated to the caller. The ordered apply processor must
therefore distinguish between:

- the durable network/body cursor (`ChainState` body tip);
- derived-store cursors that may need reconcile from local block bodies;
- fatal apply failures that require generation failure and peer recovery.

## Current End-to-End Flow

Yano uses Yaci `PeerClient`, which wraps `N2NPeerFetcher`, `ChainsyncAgent`,
`BlockfetchAgent`, and `KeepAliveAgent`.

In pipelined mode Yano starts:

- chain-sync in header-only mode;
- a `HeaderSyncManager` that stores headers into `header_tip`;
- a `BodyFetchManager` that detects the header/body gap and fetches body ranges;
- a `PipelineDataListener` that adapts Yaci listener callbacks to Yano managers.

### Current Component Diagram

```text
Cardano relay
    |
    | node-to-node mini-protocols
    v
Yaci TCPNodeClient / Netty channel
    |
    v
MiniProtoClientInboundHandler
    |
    +-- protocol 2: ChainsyncAgent
    |       |
    |       +-- N2NPeerFetcher internal listener
    |       |       confirms/requests next header
    |       |
    |       +-- ChainSyncListenerAdapter
    |               |
    |               v
    |          PipelineDataListener.rollforward(...)
    |               |
    |               v
    |          HeaderSyncManager.storeBlockHeader(...)
    |               |
    |               v
    |          ChainState.header_tip
    |
    +-- protocol 3: BlockfetchAgent
    |       |
    |       +-- N2NPeerFetcher internal listener
    |       |       confirms block point and sends next chain-sync message
    |       |
    |       +-- BlockFetchAgentListenerAdapter
    |               |
    |               v
    |          PipelineDataListener.onBlock(...)
    |               |
    |               v
    |          BodyFetchManager.onBlock(...)
    |               |
    |               +-- ChainState.storeBlock(...)
    |               +-- publish epoch-boundary events
    |               +-- publish BlockAppliedEvent
    |               |
    |               v
    |          derived ledger stores
    |
    +-- protocol 8: KeepAliveAgent
            |
            v
       keepalive response timestamp
```

### Current Sequence Flow

```text
1. Yaci ChainsyncAgent receives RollForward(header Hn).
2. N2NPeerFetcher internal chain-sync listener may confirm/request next.
3. Yano HeaderSyncManager stores Hn and advances header_tip.
4. BodyFetchManager sees header_tip ahead of body tip.
5. BodyFetchManager requests a body range with PeerClient.fetch(from, to).
6. Yaci BlockfetchAgent receives MsgBlock(Bn).
7. N2NPeerFetcher internal block-fetch listener calls confirmBlock(point Bn)
   and sends another chain-sync message.
8. Yano PipelineDataListener receives Bn.
9. BodyFetchManager:
   - validates continuity/staleness;
   - writes the raw block body to ChainState;
   - advances body tip;
   - publishes epoch-boundary events before the first block of a new epoch;
   - publishes BlockAppliedEvent.
10. UTXO/account/account-history/nonce listeners update derived state.
11. The callback returns to Netty.
```

Today step 9 and step 10 run on the Netty event-loop thread. At a mainnet epoch
boundary that can take tens of seconds. Keepalive has a virtual sender thread in
Yaci, but `channel.writeAndFlush(...)` and response processing still depend on
the channel's event loop. If the event loop is busy running Yano ledger code,
keepalive cannot protect the connection.

## Durable Cursors and Ownership

The recovery design depends on separating transient Yaci cursors from durable
Yano cursors.

| Cursor | Owner | Durable | Purpose |
|---|---|---:|---|
| `ChainsyncAgent.currentPoint` | Yaci | No | In-memory session point for the active connection |
| `BlockfetchAgent.from/to` | Yaci | No | In-memory body range cursor for the active connection |
| `header_tip` | Yano ChainState | Yes | Last stored header |
| `tip` / body tip | Yano ChainState | Yes | Last stored block body |
| account-state `meta.last_block` / `meta.last_applied_slot` | Yano ledger-state | Yes | Last block applied to account state |
| account-history last-applied metadata | Yano ledger-state | Yes | Last block indexed by account history |
| UTXO last-applied metadata | Yano runtime UTXO store | Yes | Last block applied to UTXO state |
| epoch-boundary `meta.boundary_step` | Yano ledger-state | Yes | Restart marker for epoch-boundary phases |

Yano recovery must treat Yaci's `currentPoint` as disposable. On a failed or
stale session Yano discards the old `PeerClient`, creates a new one, and starts
from durable ChainState.

There are two important failure cases:

```text
Case A: failure before ChainState.storeBlock(B2) commits

body tip = B1
queued or in-memory B2/B3/B4 are discarded with the old session
new session starts from durable body/header state
BodyFetchManager fetches B2 again from stored headers

Result: no missing block
```

```text
Case B: ChainState.storeBlock(B2) commits, but derived ledger-state does not
finish before process crash

body tip = B2
account-state/history/UTXO cursors may still be at B1
on restart, reconcile() replays B2 from local ChainState block bodies

Result: no network redelivery required; derived stores catch up locally
```

This means ChainState's body tip is the durable network/body cursor, while
ledger-state stores are derived state with their own reconcile paths.

## Decision

Introduce a Yano-owned ordered ledger apply processor between Yaci callbacks and
heavy block application work.

The processor must be:

- single-threaded for block application and rollback ordering;
- bounded, so a public relay cannot make Yano retain unbounded decoded blocks;
- generation-aware, so queued work from a discarded peer session cannot apply
  after recovery starts;
- integrated with BodyFetchManager batch state, so Yano does not request the
  next body range until the current batch is durably applied;
- recovery-aware, so peer restart uses durable ChainState after in-flight apply
  work reaches a defined safe point;
- explicit about rollback, disconnect, and generation-close ordering.

Do not change chain selection in this ADR. The active architecture remains one
upstream peer session. Later multi-peer failover can reuse the same durable
cursor and generation model.

## Proposed Architecture

### New Runtime Boundary

Add a runtime component tentatively named `LedgerApplyProcessor`.

```text
Yaci/Netty callback thread
    |
    | lightweight enqueue only
    v
LedgerApplyProcessor bounded FIFO queue
    |
    | one worker thread
    v
BodyFetchManager / ChainState / EventBus / ledger-state
```

The processor should own a monotonically increasing `sessionGeneration`.
`PeerSession` passes the generation into `PipelineDataListener`. Every queued
event carries that generation. When a peer session is stopped or replaced, Yano
closes the generation and drops queued events for that generation that have not
started yet.

Generation close is authoritative. After a generation is closed:

- not-started block events for that generation are discarded;
- stale `BatchDone` and `NoBlockFound` markers for that generation are
  discarded and must not clear `BodyFetchManager.batchInProgress`;
- `Disconnect` is treated as a fence/close signal, not as normal block work;
- a terminal control event such as `Rollback` may still be processed only if it
  was accepted as the generation-closing event.

### Event Types

The queue should contain explicit typed work items rather than anonymous
`Runnable`s. This makes failure handling and tests clear.

Initial event set:

```text
ApplyBlock(era, block, transactions, generation)
ApplyByronBlock(block, generation)
ApplyByronEbBlock(block, generation)
BatchStarted(generation)
BatchDone(generation)
NoBlockFound(from, to, generation)
Rollback(point, generation)
Disconnect(generation)
RecoveryBarrier(generation)
```

Header storage can remain synchronous for the first version because it is not
the long-running path. However, rollback is not body-only: it can mutate both
header and body state. If header rollforward remains synchronous, rollback must
act as a session/generation boundary so post-rollback headers from the old
session cannot be stored ahead of the queued rollback.

Two safe options are allowed:

1. Queue header rollforward and rollback in the same ordered processor.
2. Keep header rollforward synchronous, but on rollback immediately close the
   generation, drop subsequent old-generation header/body callbacks, process
   the rollback as a terminal apply event, then let `PeerSessionSupervisor`
   create a fresh peer session from durable ChainState.

The first implementation should prefer option 2 because it is smaller and keeps
the header fast path unchanged.

Phase one therefore makes a concrete decision: rollback closes the active
generation and the active peer session is replaced after the rollback is applied
locally. Same-session rollback continuation is future work and requires either
queued header events or a Yaci hook that can pause request-next before Yano's
rollback coordination runs.

### Safe-Point Primitive

The safe point is not a boolean checked from another thread. It is an ordered
barrier in the apply processor.

The processor exposes a method like:

```text
CompletableFuture<RecoveryPoint> closeGenerationAndReadRecoveryPoint(generation)
```

The method must:

```text
1. Mark the generation closing/closed.
2. Drop not-started queued work for that generation, except an accepted terminal
   Rollback event.
3. Wait for the currently running apply item to finish or fail.
4. Execute RecoveryBarrier on the worker thread.
5. Read ChainState body tip and header_tip on the worker thread.
6. Complete the future with the recovery point, or fail/timeout if the worker is
   stuck.
```

Routing the recovery-start read through the worker queue gives ordering for
free: recovery cannot observe ChainState while the worker is mutating it.

### Ordered Batch Completion

`batchDone` cannot clear `BodyFetchManager.batchInProgress` as soon as Netty
receives the Yaci `BatchDone` message. If block events in that batch are only
queued, the batch is not actually applied yet.

Required sequence:

```text
Netty receives MsgBlock B1  -> enqueue ApplyBlock(B1)
Netty receives MsgBlock B2  -> enqueue ApplyBlock(B2)
Netty receives BatchDone    -> enqueue BatchDone marker

Ledger worker applies B1
Ledger worker applies B2
Ledger worker runs BatchDone marker
BodyFetchManager.batchDone() clears batchInProgress
BodyFetchManager monitor may now request the next range
```

This keeps at most one body range in the apply queue under normal operation and
prevents Yano from fetching ahead of durable apply progress.

`BatchDone` is generation-scoped. If it belongs to a closed or failed
generation, it is dropped and must not clear current `BodyFetchManager` state.

### Backpressure

The queue must be bounded by memory, not only by item count. A decoded mainnet
block object can be much larger than one queue slot suggests, and current bulk
body batches can contain thousands of blocks.

The first implementation should use both limits:

```text
maxQueuedItems
maxQueuedDecodedBytes
reservedControlSlots
```

`maxQueuedItems` should be at least one body batch plus control headroom.
`maxQueuedDecodedBytes` should be configurable and validated under mainnet
initial sync. The processor can estimate bytes from block CBOR length where
available, plus a conservative multiplier for decoded object overhead.

Control events must not starve behind data events. `Rollback`, `Disconnect`,
and `RecoveryBarrier` must use reserved capacity or a separate control lane so a
full block queue cannot prevent recovery.

If the queue is full:

- pause body fetch for the current peer;
- record degraded peer health;
- retry offer for a short bounded interval;
- if enqueue still cannot proceed, request peer recovery and discard the active
  generation.

This is intentionally conservative. Blocking the Netty event loop indefinitely
would reintroduce the keepalive starvation problem.

### Apply Failure Handling

Any exception escaping block application should fail the current generation.

Required handling:

```text
1. Mark generation failed.
2. Pause body fetch.
3. Drop queued work for the failed generation.
4. Notify PeerSessionSupervisor.
5. Request `PeerSessionSupervisor` recovery with an apply-specific reason.
6. After in-flight apply work is complete, compute the recovery start point
   through the apply-processor safe-point barrier.
```

The recovery start point must be read after the current apply task has either
committed or failed. Reading `chainState.getTip()` while a block is mid-apply
can race with recovery and cause duplicate work. Duplicate work is recoverable,
but the design should avoid it.

Yano owns peer lifecycle. The apply processor must not directly stop and replace
the active `PeerClient`; it reports failure to Yano or the supervisor. Add a
dedicated `APPLY_FAILED` reason to the recovery reason set and route this
through the same supervisor path as stale disconnect and startup failure.

Each block apply should produce an explicit outcome:

```text
APPLIED
SKIPPED_STALE
SKIPPED_DUPLICATE
FAILED
```

`SKIPPED_STALE` is acceptable only when the block is provably stale relative to
rollback/generation state. Continuity violations, consensus rejections, parse
failures, and unexpected no-store paths must fail the generation unless a
specific recovery rule says otherwise.

`SKIPPED_DUPLICATE` is an idempotent success only when the durable ChainState tip
or stored body already matches the same block number and hash. It is not a
generic stale/fork outcome.

Derived-state listener failures need a visible outcome. If the event bus does
not propagate listener exceptions, the implementation must add a fail-fast path
for correctness-critical derived stores. Degraded-and-reconcile is acceptable
only for a derived store whose reconcile path is covered by automated tests and
known to converge from local ChainState block bodies before the node is reported
healthy. Network recovery should still start from ChainState, not from Yaci's
transient cursor.

### Rollback Ordering

Rollback is an apply-domain event. It must be ordered with block body
application and coordinated with synchronous header storage.

Recommended behavior:

```text
Netty receives body B100 from old fork      -> enqueue ApplyBlock(B100)
Netty receives rollback to B95             -> enqueue Rollback(B95)
Netty receives stale body B101 after that  -> enqueue ApplyBlock(B101)

Ledger worker applies B100
Ledger worker applies Rollback(B95)
Ledger worker sees B101 after rollback; BodyFetchManager stale checks drop it
```

For large queued batches this may do extra work before rollback, but it
preserves causal ordering and relies on existing rollback/stale-block guards.
If this becomes too expensive, a later optimization can remove queued block
events beyond the rollback point before they are applied.

Thread ownership:

- Netty/Yaci callback thread performs lightweight coordination only: mark the
  current generation closing, stop accepting old-generation callbacks, notify
  peer health, and enqueue the terminal `Rollback` event.
- Apply worker calls `BodyFetchManager.onRollback(point)` and
  `Yano.handleChainSyncRollback(point)` in order with prior accepted apply
  events.
- Recovery thread waits on the processor safe-point barrier before reading
  `ChainState`.

`Yano.handleChainSyncRollback(point)` invoked from the apply worker must not
synchronously wait on `RecoveryBarrier`; that would deadlock the worker against
itself. Any recovery request caused by rollback handling is scheduled from a
non-worker thread through `PeerSessionSupervisor`.

Because Yaci's internal `N2NPeerFetcher` may send the next chain-sync message
before Yano's external rollback listener runs, phase one must not keep using the
same peer session after a rollback unless Yaci exposes a pause-before-request
hook. The safe phase-one behavior is: treat rollback as closing the active
generation, process rollback locally, and recover with a fresh `PeerClient`.

### Disconnect and Recovery Ordering

Disconnect should not directly mutate body state on the Netty thread. It should
close the current generation and notify the supervisor.

Recovery should wait for the apply processor to reach a safe point:

```text
disconnect detected
generation closed
queued not-started events for generation are dropped
currently running event is allowed to finish/fail
Yano reads durable ChainState tip/header_tip
new PeerClient starts from that point
```

This rule prevents a new peer session from starting based on stale ChainState
while the old worker is still committing a block.

The actual recovery start read must use `RecoveryBarrier`; it must not be a
spin loop over an `applyInProgress` flag.

### BodyFetchManager Monitor

`BodyFetchManager` may keep its existing monitor thread. The monitor remains the
component that decides when to fetch the next body range, but it must observe
apply-worker-owned batch completion:

- `batchInProgress` remains `volatile`;
- `BatchDone` clears it only on the apply worker after prior block events have
  applied;
- queue backpressure can pause/resume body fetch;
- monitor reads of `header_tip`, body tip, and batch state must use existing
  thread-safe/volatile boundaries.

This avoids moving fetch scheduling into the worker while still preventing
fetch-ahead.

### Header Tip Interaction

Header storage can race ahead of body apply. That is already true in pipelined
sync and is not itself a bug.

Requirements:

- body range calculation reads both `header_tip` and body tip with normal
  ChainState visibility;
- header rollforward does not need to wait for in-flight block apply;
- rollback/generation close is the exception and must prevent old-generation
  post-rollback headers from mutating ChainState.

### Server Notification

`callbacks.notifyServerNewBlockStored()` should move to the apply worker after
successful body application. `NodeServer.notifyNewDataAvailable()` is callable
from non-Netty threads today, but this must remain true. If server agents later
gain event-loop affinity, this notification should be marshalled onto the server
channel's event loop.

### Block Producer Scope

This ADR targets upstream sync through Yaci `PeerClient`.

Block-producer paths currently write directly through `BlockProducerHelper`.
For phase one, block producer mode is out of scope and must not run concurrently
with client sync ordered apply unless those writes are routed through the same
processor or protected by an equivalent ChainState apply lock. A follow-up ADR
should decide whether locally produced blocks become `ApplyBlock` events.

## Data Safety Invariants

The implementation must preserve these invariants:

1. A block body is considered durably available only after
   `ChainState.storeBlock(...)` commits and body tip advances.
2. Network recovery starts from ChainState body/header state, never from a stale
   Yaci `ChainsyncAgent.currentPoint`.
3. Derived stores may lag ChainState only if their reconcile path can replay
   from local block bodies.
4. `BlockAppliedEvent` remains ordered after epoch-boundary events for the same
   block.
5. Blocks from one body batch are applied in the order Yaci delivered them.
6. `batchDone` is processed after all prior queued blocks for that batch.
7. On apply failure, no later queued block from the same generation may apply.
8. Peer recovery may not compute its start point while an apply item is
   currently mutating ChainState.
9. A closed generation cannot clear batch state for a newer generation.
10. Control events cannot be rejected merely because data-event capacity is full.

## Health and Observability

The processor should expose status:

```text
state: UNSTARTED | RUNNING | PAUSED | DEGRADED | FAILED | STOPPING | STOPPED
sessionGeneration
queueDepth
queuedDecodedBytes
currentItem
currentItemStartedAt
lastAppliedSlot
lastAppliedBlock
lastFailure
```

Yano should periodically log:

- queue depth when non-zero;
- current apply item if it runs longer than a threshold;
- derived-store lag compared with ChainState tip;
- generation failures and dropped queued items.
- safe-point wait time during recovery.

After each block, Yano can optionally compare enabled rollback-capable stores'
latest applied slot/block against ChainState. A short lag is expected if some
stores are intentionally async, but persistent lag should mark the node
degraded.

## Implementation Plan

### Phase 0: Baseline Measurement

- Add timing around the current body callback path, especially
  `BodyFetchManager.onBlock(...)` and epoch-boundary processing.
- Log when Netty callback processing exceeds a threshold.
- Capture a mainnet epoch-boundary baseline so Phase 5 can prove the Netty
  event-loop blockage was removed.

### Phase 1: Documentation and Tests First

- Add focused tests that model `B1, B2, B3, B4` delivery where `B2` fails.
- Assert `B3/B4` do not apply after `B2` failure.
- Assert recovery start point is read from durable body tip.
- Add batch tests proving `batchDone` is delayed until queued blocks apply.
- Add tests for stale `BatchDone` after generation close.
- Add tests proving control events are accepted when data capacity is full.
- Add rollback tests for the chosen phase-one strategy: rollback closes the
  generation, old-generation post-rollback headers are ignored, and fresh peer
  recovery starts from durable ChainState.

### Phase 2: Add `LedgerApplyProcessor`

- Add the processor in `runtime`.
- Use a single worker thread.
- Use a bounded queue.
- Add generation close/drop semantics.
- Add safe-point barrier API.
- Add status object and simple metrics.
- Add memory accounting and reserved control capacity.

### Phase 3: Wrap Body Events

- Change `PipelineDataListener.onBlock`, Byron block callbacks,
  `batchStarted`, `batchDone`, `noBlockFound`, and disconnect/rollback handling
  to enqueue apply events.
- Keep header rollforward synchronous for the first version.
- Make header callbacks generation-aware so callbacks from a closed generation
  cannot write header state.
- Move `callbacks.updateSyncProgress()` and
  `callbacks.notifyServerNewBlockStored()` into the apply worker after
  successful body application.

### Phase 4: Recovery Coordination

- Make `PeerSession.stop()` close the active apply generation.
- Make `Yano.recoverPeerSession(...)` wait for the apply processor safe point
  before reading `chainState.getTip()` / `getHeaderTip()`.
- Ensure terminal startup failure and explicit disconnect recovery use the same
  path.
- Add `APPLY_FAILED` to `PeerRecoveryReason` and route apply-processor failures
  through supervisor recovery.

### Phase 5: Fault Validation

Run real network and injected fault tests:

- mainnet epoch-boundary sync: verify Netty thread is not blocked for reward
  calculation duration;
- forced disconnect during epoch boundary: verify keepalive remains responsive
  or recovery starts after apply safe point;
- forced failure on block `B2`: verify no `B3/B4` application and recovery from
  durable `B1`;
- process crash after `storeBlock(B2)` but before derived-state completion:
  verify startup reconcile replays derived state from local ChainState;
- rollback while queued body events exist: verify no missing block and no
  stale-fork body persists past rollback.
- queue full during block delivery: verify rollback/disconnect/recovery control
  events are still accepted.
- measure queued decoded bytes under mainnet initial sync and tune defaults.

## Non-Goals

- No chain selection.
- No multiple active upstream peers.
- No Yaci API change in this ADR.
- No rewrite of ledger-state recovery.
- No attempt to make every derived store fully transactional with ChainState in
  one RocksDB batch.

## Open Questions

1. Should header storage eventually move to the same processor?

   Not initially. Header storage is much lighter than epoch-boundary body
   application. Moving headers would require careful redesign of header/body
   gap backpressure.

2. Should apply failure always trigger peer recovery?

   Yes for the first implementation. A failed apply means the active generation
   may contain stale or unsafe queued work. Clean session replacement from
   durable state is simpler and safer.

3. Should derived-store lag stop the node?

   Not initially. Mark degraded and rely on existing reconcile. Later we can add
   configurable fail-fast thresholds.

4. Can the queue hold decoded `Block` objects safely?

   Only if bounded by both item count and byte estimate. If memory pressure
   appears in mainnet testing, the processor can queue compact block descriptors
   and re-read bodies from ChainState after an earlier raw-body persistence
   step, but that is a larger split-phase design.

5. Can same-session rollback continuation be restored later?

   Yes, but not in phase one. It needs either queued header events or a Yaci
   hook that pauses request-next before Yano rollback coordination runs.

6. Should block-producer mode use the same processor?

   Not in phase one. It must remain mutually exclusive with client sync ordered
   apply or be protected by a follow-up design.

## Review Checklist

- Does the proposal preserve the existing epoch-boundary ordering?
- Does it avoid using Yaci's transient cursor as a recovery authority?
- Does it keep Netty event-loop callbacks short during reward calculation?
- Does it avoid unbounded memory growth during initial sync?
- Does it make block failure, rollback, and disconnect behavior deterministic?
- Are all derived-state stores either synchronously applied or reconciled from
  ChainState on restart?
