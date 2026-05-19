# ADR-NET-005: Yaci-to-Yano Data Flow and Ordered Ledger Apply

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
  work reaches a safe point.

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
```

Header storage can remain synchronous for the first version because it is not
the long-running path. If header writes later become expensive, the same design
can be extended to header events, but that requires revisiting header-tip
backpressure.

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

### Backpressure

The queue must be bounded. The bound should be at least one configured body
batch plus a small allowance for control events.

Suggested initial sizing:

```text
queueCapacity = max(bodyBatchSize * 2, 1024)
```

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
5. Stop and replace the active PeerClient.
6. After in-flight apply work is complete, compute the recovery start point
   from durable ChainState.
```

The recovery start point must be read after the current apply task has either
committed or failed. Reading `chainState.getTip()` while a block is mid-apply
can race with recovery and cause duplicate work. Duplicate work is recoverable,
but the design should avoid it.

### Rollback Ordering

Rollback is an apply-domain event. It must be ordered with block body
application.

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

## Health and Observability

The processor should expose status:

```text
state: RUNNING | PAUSED | FAILED | STOPPING
sessionGeneration
queueDepth
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

After each block, Yano can optionally compare enabled rollback-capable stores'
latest applied slot/block against ChainState. A short lag is expected if some
stores are intentionally async, but persistent lag should mark the node
degraded.

## Implementation Plan

### Phase 1: Documentation and Tests First

- Add focused tests that model `B1, B2, B3, B4` delivery where `B2` fails.
- Assert `B3/B4` do not apply after `B2` failure.
- Assert recovery start point is read from durable body tip.
- Add batch tests proving `batchDone` is delayed until queued blocks apply.

### Phase 2: Add `LedgerApplyProcessor`

- Add the processor in `runtime`.
- Use a single worker thread.
- Use a bounded queue.
- Add generation close/drop semantics.
- Add status object and simple metrics.

### Phase 3: Wrap Body Events

- Change `PipelineDataListener.onBlock`, Byron block callbacks,
  `batchStarted`, `batchDone`, `noBlockFound`, and disconnect/rollback handling
  to enqueue apply events.
- Keep header rollforward synchronous for the first version.
- Move `callbacks.updateSyncProgress()` and
  `callbacks.notifyServerNewBlockStored()` into the apply worker after
  successful body application.

### Phase 4: Recovery Coordination

- Make `PeerSession.stop()` close the active apply generation.
- Make `Yano.recoverPeerSession(...)` wait for the apply processor safe point
  before reading `chainState.getTip()` / `getHeaderTip()`.
- Ensure terminal startup failure and explicit disconnect recovery use the same
  path.

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

   For one batch, yes if bounded by batch size. If memory pressure appears in
   mainnet testing, the processor can queue compact block descriptors and
   re-read bodies from ChainState after an earlier raw-body persistence step,
   but that is a larger split-phase design.

## Review Checklist

- Does the proposal preserve the existing epoch-boundary ordering?
- Does it avoid using Yaci's transient cursor as a recovery authority?
- Does it keep Netty event-loop callbacks short during reward calculation?
- Does it avoid unbounded memory growth during initial sync?
- Does it make block failure, rollback, and disconnect behavior deterministic?
- Are all derived-state stores either synchronously applied or reconciled from
  ChainState on restart?
