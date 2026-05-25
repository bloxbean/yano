# ADR-NET-006: Yaci-Yano Sync, Rollback, and Disconnect Flow

## Status

Proposed

## Date

2026-05-21

## Problem

Yaci delivers ChainSync, BlockFetch, keepalive, and disconnect callbacks from
the active peer session. Yano must keep Netty responsive while still applying
blocks, rollbacks, and recovery in an order that cannot skip blocks or advance
from an unsafe cursor.

The key rule is:

```text
Rollback is a ledger event.
Reconnect is a session-health event.
```

A successful RollBackward message must not by itself replace the peer session.
The session is replaced only when the connection or local apply path is no
longer trustworthy.

## Components

```text
Cardano relay
  |
  v
Yaci PeerClient / Netty channel
  |
  +-- ChainsyncAgent  -> PipelineDataListener.rollforward / rollback / disconnect
  +-- BlockfetchAgent -> PipelineDataListener.onBlock / batch markers
  +-- KeepAliveAgent  -> PeerHealth keepalive timestamps
  |
  v
Yano runtime
  |
  +-- HeaderSyncManager        stores headers into ChainState.header_tip
  +-- BodyFetchManager         fetches and stores block bodies
  +-- LedgerApplyProcessor     single ordered worker for body apply and rollback
  +-- PeerSessionSupervisor    replaces the peer on session/apply failure
  |
  v
ChainState + derived stores
```

## Regular Sync

```text
1. ChainsyncAgent receives RollForward(header Hn).
2. HeaderSyncManager stores Hn and advances header_tip.
3. BodyFetchManager observes header_tip ahead of body tip.
4. BodyFetchManager asks Yaci BlockFetch for the missing body range.
5. BlockfetchAgent delivers block body Bn.
6. PipelineDataListener records body-received health and enqueues ApplyBlock(Bn).
7. LedgerApplyProcessor applies Bn on the YanoLedgerApply worker:
   - stale/continuity checks
   - ChainState.storeBlock(...)
   - epoch-boundary events if needed
   - BlockAppliedEvent
   - derived store updates
8. On success, body tip and lastSuccessfulBodyTip advance.
```

Netty does not run the heavy body apply work. If apply fails, the generation is
failed and the supervisor recovers from the last safe Yano cursor.

## In-Session Rollback

Rollback can happen at startup after intersection or during a real chain
reorganization near tip. Both are handled as ordered ledger work in the same
peer session.

```text
1. ChainsyncAgent receives RollBackward(point R).
2. Yaci updates its in-memory ChainSync cursor to R.
3. PipelineDataListener enqueues Rollback(R) into LedgerApplyProcessor.
4. The ChainSync callback waits for Rollback(R) to complete before later
   headers are allowed to mutate HeaderSyncManager.
5. LedgerApplyProcessor runs rollback in FIFO order with already accepted body
   work for the generation.
6. BodyFetchManager clears transient batch state and stale counters.
7. Yano rolls ChainState and derived stores back to R.
8. BodyFetchManager resumes gap detection.
9. ChainSync continues on the same PeerClient from R.
```

In-flight BlockFetch responses from before the rollback may still arrive after
step 9. Those bodies are treated as `SKIPPED_STALE`, not as apply failures. They
do not advance body-applied health, do not notify the server, and do not close
the generation. The next valid block after the rolled-back tip applies normally.

Real body rollbacks are classified by comparing `R.slot` with the pre-rollback
durable body tip. If the body tip moves backward and Yano is in steady state,
downstream server agents are notified.

## Disconnect And Recovery

Disconnect is different from rollback. A disconnect means the active session is
not trustworthy and must be replaced.

There is one startup carve-out: Yaci may emit a reset/disconnect before any
header, body, or apply progress exists during the first handshake. Yano ignores
that startup reset and keeps the generation open because there is no accepted
ledger work to recover.

```text
1. Yaci reports channel close, broken pipe, failed write, stale keepalive, or no
   progress.
2. PipelineDataListener closes the active generation.
3. Queued data for that generation is cancelled, except an already accepted
   rollback marker is promoted ahead of recovery so the cursor read sees that
   rollback.
4. PeerSessionSupervisor waits for a ledger safe point.
5. The recovery start point is read from durable Yano ChainState through the
   LedgerApplyProcessor barrier.
6. The old PeerClient is discarded.
7. A new PeerClient starts ChainSync from the durable body/header cursor.
8. Any blocks not durably applied are fetched again from the network.
```

Recovery does not trust Yaci's old in-memory cursor. Yaci cursors belong only to
the active TCP session; Yano's durable body tip is the recovery authority.

## Failure Rules

```text
Successful RollBackward        -> same session continues
Rollback apply failure         -> APPLY_FAILED recovery
Block apply failure            -> APPLY_FAILED recovery
Disconnect / broken pipe       -> peer-session recovery
Keepalive stale / no progress  -> peer-session recovery
Startup failure                -> peer-session recovery
Stale in-flight body           -> SKIPPED_STALE, same session continues
Catastrophic rollback to origin on populated chain -> process exits
```

## Invariants

- Only `LedgerApplyProcessor` mutates body apply state and rollback state for a
  generation.
- `lastSuccessfulBodyTip` advances after successful block apply and successful
  rollback.
- A rollback never closes a healthy generation.
- A reconnect never starts from Yaci's old `currentPoint`; it starts from
  durable Yano state.
- Header callbacks after RollBackward are fenced until local rollback completes.
- Stale post-rollback block bodies are skipped, not treated as missing-block or
  apply-failure conditions.
