# ADR-NET-007: Push-Driven Body Fetch and Sync Phase Transition

## Status

Proposed

## Date

2026-05-24

## Context

Yano pipelined sync stores headers through ChainSync and fetches block bodies
through BlockFetch. The current `BodyFetchManager` is primarily polling based:
it periodically checks the gap between `header_tip` and the durable body tip and
then decides whether to fetch bodies.

This works for bulk sync, but it has poor near-tip behavior if the process is
logically near the network tip while the internal `syncPhase` flag still says
`INITIAL_SYNC`. In that state the body fetch rule remains the bulk rule:

```text
INITIAL_SYNC: fetch bodies only when header/body slot gap >= gapThreshold
STEADY_STATE: fetch bodies when header/body slot gap >= 1
```

With a `gapThreshold` of 100 slots, a near-tip node can wait roughly 100 seconds
before fetching the next confirming block body. This was visible during N2N
transaction submission: the upstream node acknowledged the transaction quickly,
but Yano did not observe the confirming body until the next bulk body-fetch
batch.

The old phase transition also had a state-accounting hole: body blocks were
applied, but the `INITIAL_SYNC -> STEADY_STATE` transition was not reliably
derived from the applied body slot. A dead `checkSyncProgress()` method existed,
but it was not part of the active apply path.

## Decision Drivers

- Keep ChainSync/HeaderSync callback latency low.
- Never run BlockFetch or body gap calculation on the ChainSync/Netty callback
  thread.
- Preserve current bulk-sync batching behavior.
- Make near-tip behavior responsive even if global `syncPhase` is late.
- Avoid extra RocksDB metadata reads on every successful block apply where the
  applied slot is already known.
- Keep fallback polling so a missed signal cannot stall body fetch forever.
- Use virtual threads for Yano-owned waiting/background loops where useful.
- Add an explicit header-applied event for observability, without making body
  fetch scheduling depend on the public event bus.
- Keep this behavioral change separate from event renames.

## Decision

Adopt two separate mechanisms:

1. **Progress accounting by value**: pass the applied block slot/block number
   from `PipelineDataListener` to `Yano.updateSyncProgress(...)` after a body is
   successfully applied. Do not read `chainState.getTip()` in the normal path.

2. **Internal non-blocking header wakeup**: after a header is stored,
   `HeaderSyncManager` signals `BodyFetchManager` through a narrow internal
   interface. The signal only updates atomics and unparks BodyFetchManager's own
   virtual monitor thread. It must not calculate gaps, read RocksDB, call
   `PeerClient.fetch(...)`, publish to the public event bus, or wait for body
   work.

Public events are useful for observers, but Yano's core body-fetch scheduling
must not depend on those public events. The internal wakeup path remains active
even when the external event bus is disabled or a plugin listener is slow.

Do not rename `BlockAppliedEvent` in this change. It is already the ordered
post-body-store event consumed by derived stores. A future `BodyAppliedEvent`
rename or alias can be done as a separate naming-only change.

## Updated State Flow

```text
ChainSync RollForward(header Hn)
  -> HeaderSyncManager updates SyncTipContext from the ChainSync tip
  -> HeaderSyncManager stores Hn and advances header_tip
  -> HeaderSyncManager optionally queues HeaderAppliedEvent for async observers
  -> HeaderSyncManager sends non-blocking signal to BodyFetchManager
  -> ChainSync callback returns

BodyFetchManager virtual thread wakes
  -> checkAndFetchBodies()
  -> if STEADY_STATE or near observed network tip: fetch when gap >= 1
  -> otherwise INITIAL_SYNC bulk rule: fetch when gap >= gapThreshold

BlockFetch delivers body Bn
  -> PipelineDataListener enqueues apply work
  -> BodyFetchManager applies Bn on YanoLedgerApply
  -> publish existing BlockAppliedEvent after durable body store succeeds
  -> callbacks.updateSyncProgress(slot, blockNumber)
  -> possible INITIAL_SYNC -> STEADY_STATE transition
```

## Detailed Design

### 1. Applied Progress Callback

Change `PeerSessionCallbacks` from a no-arg progress callback to a value-based
callback:

```java
public interface PeerSessionCallbacks {
    void updateSyncProgress(long slot, long blockNumber);
    // other callbacks unchanged
}
```

`PipelineDataListener` already extracts slot and block number for Shelley,
Byron, and Byron EBB body callbacks. After `BodyFetchManager` returns
`APPLIED`, call:

```java
callbacks.updateSyncProgress(slot, blockNumber);
```

Do not call it for `SKIPPED_STALE` blocks.

`Yano.updateSyncProgress(...)` becomes:

```java
public void updateSyncProgress(long slot, long blockNumber) {
    lastProcessedSlot = slot;
    blocksProcessed++;

    if (syncPhase == SyncPhase.INITIAL_SYNC && !isInitialSyncComplete
            && remoteTip != null && remoteTip.getPoint() != null) {
        long distance = Math.max(0L, remoteTip.getPoint().getSlot() - slot);
        if (distance <= 10) {
            isInitialSyncComplete = true;
            log.info("Initial sync complete at slot {} (distance to tip: {} slots)", slot, distance);
        }
    }

    if (syncPhase == SyncPhase.INITIAL_SYNC && isInitialSyncComplete) {
        syncPhase = SyncPhase.STEADY_STATE;
        BodyFetchManager bodyFetchManager = currentBodyFetchManager();
        if (isPipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.setSyncPhase(SyncPhase.STEADY_STATE);
            bodyFetchManager.wakeFetchLoop();
        }
    }
}
```

This removes one RocksDB metadata read per applied block on the normal path.

### 2. SyncTipContext Freshness

`SyncTipContext` is the source for the latest observed network tip slot used by
near-tip decisions. It is intentionally small and lock-free:

```java
public final class SyncTipContext {
    private final AtomicLong networkTipSlot = new AtomicLong(-1L);

    public void update(Tip tip) {
        if (tip != null && tip.getPoint() != null) {
            networkTipSlot.set(Math.max(0L, tip.getPoint().getSlot()));
        }
    }
}
```

The writer is `HeaderSyncManager`, from ChainSync callbacks that carry a `Tip`:
Shelley rollforward, Byron rollforward, Byron EBB rollforward, intersection,
intersection-not-found, and rollback callbacks. The update is a single
`AtomicLong` write and must stay on the header callback path.

If `networkTipSlot` is stale or unavailable, the design degrades to the normal
header/body gap rule and fallback polling. It must not block or fail body fetch.

### 3. Internal Header Wakeup Interface

Add a small runtime-local interface. This keeps `HeaderSyncManager` decoupled
from the concrete `BodyFetchManager` class.

```java
package com.bloxbean.cardano.yano.runtime;

public interface HeaderAppliedSignal {
    /**
     * Must be non-blocking. Implementations may update atomics and wake their
     * own worker, but must not run body fetch work on the caller thread.
     */
    void onHeaderApplied(long slot, long blockNumber, String blockHash);
}
```

`HeaderSyncManager` accepts the signal:

```java
public final class HeaderSyncManager implements ChainSyncAgentListener {
    private final HeaderAppliedSignal headerAppliedSignal;

    public HeaderSyncManager(PeerClient peerClient,
                             ChainState chainState,
                             long maxGapThreshold,
                             SyncTipContext syncTipContext,
                             HeaderAppliedSignal headerAppliedSignal) {
        this.peerClient = peerClient;
        this.chainState = chainState;
        this.maxGapThreshold = maxGapThreshold;
        this.syncTipContext = syncTipContext;
        this.headerAppliedSignal = headerAppliedSignal;
    }

    private void afterHeaderStored(long slot, long blockNumber, String blockHash) {
        HeaderAppliedSignal signal = headerAppliedSignal;
        if (signal != null) {
            signal.onHeaderApplied(slot, blockNumber, blockHash);
        }
    }
}
```

After `chainState.storeBlockHeader(...)` succeeds, call `afterHeaderStored(...)`.

The contract is explicit: this call must remain cheap and non-blocking.

### 4. BodyFetchManager Signal Handling

`BodyFetchManager` implements `HeaderAppliedSignal`. It records only atomic
state and unparks its own monitor virtual thread.

```java
public final class BodyFetchManager implements BlockChainDataListener,
        Runnable, HeaderAppliedSignal {

    private final AtomicLong lastAppliedBodySlot = new AtomicLong(-1L);
    private volatile Thread monitoringThread;

    @Override
    public void onHeaderApplied(long slot, long blockNumber, String blockHash) {
        if (shouldWakeFromHeader(slot)) {
            wakeFetchLoop();
        }
    }

    public void recordBodyApplied(long slot) {
        lastAppliedBodySlot.accumulateAndGet(slot, Math::max);
    }

    public void wakeFetchLoop() {
        Thread thread = monitoringThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }
}
```

`lastAppliedBodySlot` must be updated with `accumulateAndGet(slot, Math::max)`
after successful body apply and rollback recovery points. This keeps the
in-memory wake heuristic monotonic under normal roll-forward apply. Rollback
must explicitly reset it to the post-rollback durable body slot.

`shouldWakeFromHeader(...)` must not do RocksDB reads. It can use volatile or
atomic state already available in memory:

```java
private boolean shouldWakeFromHeader(long headerSlot) {
    SyncPhase phase = syncPhase;
    if (phase == SyncPhase.STEADY_STATE) {
        return true;
    }

    long bodySlot = lastAppliedBodySlot.get();
    long networkTipSlot = syncTipContext != null ? syncTipContext.getNetworkTipSlot() : -1L;
    if (networkTipSlot > 0 && bodySlot >= 0
            && Math.max(0L, networkTipSlot - bodySlot) <= tipProximityThreshold) {
        return true;
    }

    if (bodySlot < 0) {
        return headerSlot >= gapThreshold;
    }

    return headerSlot - bodySlot >= gapThreshold;
}
```

The actual fetch decision remains inside `checkAndFetchBodies()`. The signal
only changes when that method gets called.

### 5. Virtual Thread Monitor Loop

Keep `BodyFetchManager`'s monitor as a virtual thread, but replace frequent
sleep polling with push wakeups plus a phase-aware fallback timeout.

```java
monitoringThread = Thread.ofVirtual()
        .name("BodyFetchManager-Monitor")
        .start(this);
```

The loop uses `LockSupport.parkNanos(...)` so a header signal does not need a
synchronized monitor and a signal arriving before park is retained as a permit.

```java
@Override
public void run() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
        try {
            if (!paused.get()) {
                checkAndFetchBodies();
            }

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(currentFallbackPollMs()));

            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
        } catch (Exception e) {
            log.error("Error in BodyFetchManager monitoring loop", e);
        }
    }
}
```

Fallback polling stays enabled. Since push is always enabled, use a longer
fallback only in real-time mode:

```text
INITIAL_SYNC fallback: 500ms to 1000ms
STEADY_STATE or near observed network tip fallback: 5000ms
```

This avoids a bulk-sync throughput regression if a wake is missed while still
reducing near-tip polling pressure.

`batchDone()`, rollback resume, and `setSyncPhase(...)` should also call
`wakeFetchLoop()`. This avoids waiting for the fallback timer when a batch
completes and more headers are already available. `noBlockFound(...)` should not
wake immediately because it can otherwise refetch the same empty range in a
tight loop.

### 6. Bulk Sync Coalescing

During genesis/full sync, headers can arrive much faster than body fetches. The
wake path must not turn every header into a body gap check.

Coalescing rules:

- In `STEADY_STATE`, wake on every applied header.
- Near observed network tip, wake on every applied header.
- In `INITIAL_SYNC`, wake only when the in-memory header/body slot gap is at
  least `gapThreshold`.
- Repeated `LockSupport.unpark(...)` calls coalesce naturally while the monitor
  thread is parked.

This preserves bulk batching and avoids tight coupling between header rate and
body gap checks.

### 7. Public Events

Add `HeaderAppliedEvent` for observability after a header is durably stored.

```java
public record HeaderAppliedEvent(long slot, long blockNumber, String blockHash)
        implements Event {
}
```

Keep the existing `BlockAppliedEvent` unchanged in this behavioral change. It
is already the ordered post-body-store event consumed by account, UTXO, mempool,
nonce, and plugin listeners. A future `BodyAppliedEvent` rename or alias can be
landed separately as a naming-only change.

Important distinction:

```text
HeaderAppliedEvent: notification/observability; must not block ChainSync.
BlockAppliedEvent: ordered body-apply event; failures must fail apply.
```

Header event publication should be off the ChainSync callback path. Use a small
runtime publisher backed by a single virtual thread:

```java
final class HeaderAppliedEventPublisher implements AutoCloseable {
    private final BlockingQueue<HeaderAppliedEvent> queue = new ArrayBlockingQueue<>(8192);
    private final Thread worker;

    HeaderAppliedEventPublisher(EventBus eventBus) {
        this.worker = Thread.ofVirtual()
                .name("HeaderAppliedEventPublisher")
                .start(() -> run(eventBus));
    }

    void publishLater(HeaderAppliedEvent event) {
        if (!queue.offer(event)) {
            log.warn("Dropping HeaderAppliedEvent because queue is full: slot={}", event.slot());
        }
    }
}
```

The internal body-fetch wakeup must not depend on this publisher. If the event
queue is full, body fetching must still proceed normally.

### 8. Sync Phase Demotion Scope

This ADR fixes promotion into `STEADY_STATE` and near-tip body-fetch latency. It
does not add a new general lag-based `STEADY_STATE -> INITIAL_SYNC` demotion.

Current Yano already re-enters `INITIAL_SYNC` in these paths:

- peer session start/recovery resets the phase to `INITIAL_SYNC`;
- rollback reconciliation demotes from `STEADY_STATE` to `INITIAL_SYNC` if the
  post-rollback body tip is more than 1000 slots behind remote tip.

A future general demotion rule can be added if a non-rollback steady-state stall
creates a large header/body backlog. It should be handled separately so this
change stays focused on wakeup latency and phase promotion.

### 9. Wiring

`PeerSession.initializePipelineManagers(...)` wires the managers in two steps:

```java
SyncTipContext syncTipContext = new SyncTipContext();

bodyFetchManager = new BodyFetchManager(
        peerClient,
        chainState,
        eventBus,
        gapThreshold,
        maxBatchSize,
        initialSyncFallbackPollMs,
        tipProximityThreshold,
        syncTipContext
);

headerSyncManager = new HeaderSyncManager(
        peerClient,
        chainState,
        50000,
        syncTipContext,
        bodyFetchManager
);
```

This introduces no concrete dependency from `HeaderSyncManager` to
`BodyFetchManager`; only `PeerSession` knows both concrete components.

## Non-Blocking Guarantee

The header callback path may do only the following after successful header
storage for the new wake/event behavior:

```text
- update SyncTipContext from ChainSync tip
- update HeaderSyncManager counters/backpressure state
- invoke HeaderAppliedSignal.onHeaderApplied(...)
- optionally enqueue HeaderAppliedEvent for async publication
```

It must not:

```text
- call BodyFetchManager.checkAndFetchBodies()
- call PeerClient.fetch(...)
- read ChainState body/header tips for body-fetch decisions
- publish HeaderAppliedEvent synchronously to arbitrary listeners
- wait for BodyFetchManager to acknowledge the signal
```

The expected hot-path cost is a few atomic writes and `LockSupport.unpark(...)`.
The pre-existing header backpressure mechanism still checks the durable body
tip to protect bulk sync from unbounded header lead; replacing that with an
in-memory progress snapshot is out of scope for this behavioral change.

## Consequences

### Positive

- Near-tip body fetch is driven by header arrival instead of a short polling
  loop.
- A stale `syncPhase` no longer causes a 100-slot wait near tip.
- Successful body apply avoids an extra RocksDB tip read.
- Header processing is not blocked by body fetch scheduling.
- Fallback polling still protects against missed wakeups.
- Bulk sync keeps a short fallback cadence and batching behavior.
- Header applied notifications become explicit for observers.

### Costs / Risks

- Additional concurrency surface in `BodyFetchManager`.
- Need careful lifecycle handling so `unpark` against a stopped/replaced manager
  is harmless.
- Header event publishing must be best-effort or isolated from core sync so slow
  plugins cannot affect ChainSync.
- Keeping `BlockAppliedEvent` avoids rename churn now, but the name remains less
  precise than `BodyAppliedEvent`.

## Test Plan

1. Unit test `Yano.updateSyncProgress(slot, blockNumber)`:
   - updates `lastProcessedSlot` without reading `chainState.getTip()`;
   - flips `INITIAL_SYNC -> STEADY_STATE` when distance to remote tip is `<= 10`;
   - does not flip when remote tip is unknown or farther away.

2. Unit test `SyncTipContext` freshness:
   - ChainSync rollforward callbacks update the network tip slot through a
     single atomic write;
   - if `networkTipSlot` is stale or unavailable, body fetch still proceeds via
     gap threshold and fallback polling.

3. Unit test `HeaderSyncManager` signaling:
   - after `storeBlockHeader(...)`, `HeaderAppliedSignal` is called once;
   - the signal path does not call `PeerClient.fetch(...)` or read body/header
     tips.

4. Unit test `BodyFetchManager.onHeaderApplied(...)`:
   - in `STEADY_STATE`, a single header signal wakes the monitor and triggers a
     fetch for gap `>= 1`;
   - in `INITIAL_SYNC`, no fetch happens until gap `>= gapThreshold`;
   - near observed network tip wakes for gap `>= 1` even before phase flip.

5. Unit test batch continuation:
   - if `batchDone()` fires while header/body gap remains positive, the monitor
     wakes immediately instead of waiting for fallback polling.

6. Bulk-sync coalescing test:
   - applying N headers in `INITIAL_SYNC` does not produce N fetch requests;
   - fetch calls remain bounded by bulk batching behavior.

7. Event tests:
   - `HeaderAppliedEvent` is enqueued asynchronously and does not block the
     header callback path;
   - existing `BlockAppliedEvent` preserves current ordered/fail-closed behavior
     for derived stores.

8. Regression test for transaction-submission observation:
   - submit a tx near tip;
   - assert upstream acknowledgement remains fast;
   - assert the confirming body fetch is triggered by header signal rather than
     waiting for `gapThreshold` slots.

## Rollout Plan

1. Implement `updateSyncProgress(slot, blockNumber)` and remove the no-arg
   callback.
2. Add `HeaderAppliedSignal` and non-blocking `BodyFetchManager` wakeup.
3. Switch the monitor loop from short sleep polling to push wakeups plus
   phase-aware fallback polling.
4. Add `HeaderAppliedEvent` asynchronous publisher.
5. Keep `BlockAppliedEvent` unchanged; defer any `BodyAppliedEvent` rename to a
   separate naming-only change.
6. Run focused runtime tests, then a live near-tip sync test with two dependent
   transactions.

## Open Questions

- Use fixed fallback values of `INITIAL_SYNC=500ms` and realtime `5000ms`, or
  make them runtime options now?
- Should a future `BodyAppliedEvent` be a direct rename of `BlockAppliedEvent`
  or an alias with a deprecation window?
