# Global Priority Ordering via Immutable Snapshots

This document describes the plan to add global (per event-type) listener ordering to the `SimpleEventBus` using immutable snapshots. The goal is deterministic ordering across classes/plugins while keeping the publish path lock-free and fast.

## Rationale

- Current behavior:
  - `SimpleEventBus` invokes subscribers in registration order.
  - `@DomainEventListener(order=...)` is honored within a single class by the registrar (it sorts methods before subscribing), but not across classes/plugins.
- Desired behavior:
  - Global ordering per event type using a priority value, independent of registration order.
  - Reuse the existing `order` attribute from `@DomainEventListener` as the source of that priority.
  - Keep publish hot path free of sorting/locking.

## High-Level Design

- Add `priority` (int, default 0) to `SubscriptionOptions` (events-core).
- Mapping:
  - Registrar (and generated bindings via events-processor) maps `@DomainEventListener.order` → `SubscriptionOptions.priority` when subscribing.
  - Manual callers can set `priority` directly in `SubscriptionOptions`.
- `SimpleEventBus` maintains, for each event type, an immutable, pre-sorted snapshot of subscribers ordered by:
  1) `priority` ascending (lower value runs earlier)
  2) `registrationSeq` ascending (stable order for ties)
- On subscribe/unsubscribe:
  - Build a new sorted copy (snapshot) and swap it into an `AtomicReference<List<Sub>>` for that event type.
  - Maintain a monotonic `registrationSeq` on each subscription to preserve stable order for equal priorities.
- On publish:
  - Read the `AtomicReference<List<Sub>>` snapshot and iterate — no sorting, no locks.

## Detailed Changes

1) events-core: `SubscriptionOptions`
- Add field: `int priority` with builder support (default 0).
- Accessor: `public int priority()`.

2) events-core: `AnnotationListenerRegistrar`
- When building per-method `SubscriptionOptions` for annotated methods, set `priority = ann.order()`.
- Reflection path and generated-bindings path should both set the same priority.

3) events-processor (DomainEventProcessor)
- In generated code, when constructing `SubscriptionOptions` for each method, set `.executor(...).filter(...).priority(<order from annotation>)`.
- Ordering across methods is still sorted by `order` as today, but priority will enforce global cross-class ordering in the bus.

4) events-core: `SimpleEventBus`
- Internal state per event type:
  - `AtomicReference<List<Sub<?>>> snapshotRef` (immutable, sorted by (priority, registrationSeq)).
  - Maintain `CopyOnWriteArrayList<Sub<?>>` (or similar) only as backing store if needed — preferred is to use the snapshot as the canonical list.
- Subscribe:
  - Assign `sub.registrationSeq = globalSeq.incrementAndGet()`.
  - Rebuild snapshot: `new ArrayList<>(oldSnapshot + sub)`, sort by (priority asc, regSeq asc), set into `snapshotRef`.
- Unsubscribe:
  - Remove `sub` and rebuild snapshot (sorted), set into `snapshotRef`.
- Publish:
  - `List<Sub<?>> list = snapshotRef.get()`; iterate and dispatch in priority order.

## Thread-Safety & Performance

- Publish path:
  - No locks; a single `get()` of the snapshot reference and a simple for-loop.
- Subscribe/Unsubscribe path:
  - Briefly rebuild and sort a small list; expected to be rare compared to publishes.
  - Sorting cost is negligible with typical listener counts.
- Memory:
  - Snapshots are immutable lists; garbage from old snapshots is collected.

## Behavior Notes

- Scope: ordering is per event type; no cross-type ordering guarantees.
- Equal priority: order falls back to `registrationSeq` (earlier subscriptions win).
- Async offload:
  - Priority controls dispatch order only. If a listener offloads to an executor, overall completion/logs can interleave.

## Backward Compatibility

- Default `priority = 0` preserves current behavior if no priorities are set.
- `@DomainEventListener(order=...)` now maps to global priority automatically; within-class behavior remains the same.

## Test Plan

- Unit tests for `SimpleEventBus`:
  - Single class, multiple methods with different orders → verify dispatch ordering matches ascending order.
  - Multiple classes/plugins, same event type, different priorities → verify global priority is enforced independent of registration order.
  - Equal priorities across multiple subscribers → verify registration order tie-breaker.
  - Subscribe/unsubscribe dynamics → verify snapshot rebuild maintains correct order.
  - Async subscriptions with executors → verify dispatch order is correct; note potential interleaving post-offload.

- Processor/registar tests:
  - Generated bindings include `.priority(order)`.
  - Reflection registrar sets priority from annotation.

## Migration Steps (Implementation Order)

1) Add `priority` to `SubscriptionOptions` (events-core) and builder accessors.
2) Update `AnnotationListenerRegistrar` to set `.priority(ann.order())` in both reflection and generated paths.
3) Update events-processor to emit `.priority(<order>)` in generated `SubscriptionOptions` for each method.
4) Refactor `SimpleEventBus` to use immutable per-type snapshots:
   - Introduce `registrationSeq` and an `AtomicLong` for global sequence.
   - Maintain `AtomicReference<List<Sub<?>>>` per event type.
   - Rebuild snapshot on subscribe/unsubscribe; iterate snapshot on publish.
5) Update docs (guide) to state: `order` is a global per-type priority.
6) Add/adjust unit tests as specified.

## API Diff (Proposed)

- `SubscriptionOptions` (events-core):
  - New: `int priority()`; builder method `.priority(int)`; default 0.
- No changes to `@DomainEventListener` (reuse `order`).
- `SimpleEventBus` internals only; `EventBus` interface unchanged.

## Example (After Change)

```java
public final class AListeners {
  @DomainEventListener(order = 10)
  public void onAppliedLow(BlockAppliedEvent e) { /* ... */ }

  @DomainEventListener(order = 5)
  public void onAppliedHigh(BlockAppliedEvent e) { /* ... */ }
}

public final class BListeners {
  @DomainEventListener(order = 7)
  public void onAppliedMid(BlockAppliedEvent e) { /* ... */ }
}

// Registration (order of subscribe calls should not matter)
AnnotationListenerRegistrar.register(bus, new AListeners(), SubscriptionOptions.builder().build());
AnnotationListenerRegistrar.register(bus, new BListeners(), SubscriptionOptions.builder().build());

// Dispatch order for BlockAppliedEvent will be:
// order=5 (A.onAppliedHigh), then order=7 (B.onAppliedMid), then order=10 (A.onAppliedLow)
```

## Open Questions

- Do we want per-subscription override of priority that can differ from annotation order? (Supported via manual `SubscriptionOptions.priority`.)
- Should we expose a global comparator hook per event type? (Out of scope for now.)

---

This plan is ready for implementation next session. We will start by updating `SubscriptionOptions`, registrar, and the processor, then refactor `SimpleEventBus` with immutable snapshots and add tests.
