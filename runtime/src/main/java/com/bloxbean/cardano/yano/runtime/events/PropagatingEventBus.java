package com.bloxbean.cardano.yano.runtime.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventContext;
import com.bloxbean.cardano.yaci.events.api.EventFilter;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Event bus variant used by Yano's ledger apply path.
 *
 * <p>Yaci's SimpleEventBus logs and swallows synchronous listener failures.
 * That is useful for general notifications, but it is unsafe for Yano's
 * correctness-critical ledger listeners: a failed BlockAppliedEvent listener
 * must fail the current apply generation instead of silently advancing sync.</p>
 *
 * <p>This implementation preserves priority ordering for synchronous listeners
 * and propagates listener failures to the publisher as
 * {@link EventDeliveryException}. Async subscriptions are also fail-closed: the
 * first async listener failure disables that subscription, records the failure,
 * and causes later publications to fail until the bus is replaced.</p>
 */
@Slf4j
public final class PropagatingEventBus implements EventBus {
    private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Sub<?>>> subs = new ConcurrentHashMap<>();
    private final ConcurrentMap<Sub<?>, Future<?>> workers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<EventDeliveryException> asyncFailure = new AtomicReference<>();

    /**
     * Registers a listener for the event type.
     *
     * <p>Synchronous listeners are invoked on the publishing thread in priority
     * order. If an executor is supplied in the subscription options, events are
     * queued for a worker owned by that executor.</p>
     */
    @Override
    public <E extends Event> SubscriptionHandle subscribe(Class<E> type,
                                                          EventListener<E> listener,
                                                          SubscriptionOptions options) {
        if (closed.get()) {
            throw new IllegalStateException("EventBus is closed");
        }
        CopyOnWriteArrayList<Sub<?>> list = subs.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>());
        SubscriptionOptions effective = options != null ? options : SubscriptionOptions.builder().build();
        Sub<E> sub = new Sub<>(listener, effective, sequence.incrementAndGet());
        int index = 0;
        for (; index < list.size(); index++) {
            Sub<?> existing = list.get(index);
            if (existing.priority > sub.priority) {
                break;
            }
            if (existing.priority == sub.priority && existing.registrationSeq > sub.registrationSeq) {
                break;
            }
        }
        list.add(index, sub);
        if (sub.executor != null) {
            startAsyncLoop(type, sub);
        }
        return new SubscriptionHandle() {
            @Override
            public void close() {
                sub.active.set(false);
                list.remove(sub);
            }

            @Override
            public boolean isActive() {
                return sub.active.get();
            }
        };
    }

    /**
     * Publishes an event to active subscribers.
     *
     * @throws EventDeliveryException if any listener fails, or if an earlier async
     *                                listener failure has already made the bus unsafe
     */
    @Override
    public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
        if (event == null) {
            return;
        }
        throwIfAsyncFailed();
        CopyOnWriteArrayList<Sub<?>> list = subs.get(event.getClass());
        if (list == null || list.isEmpty()) {
            return;
        }
        EventDeliveryException failure = null;
        for (Sub<?> raw : list) {
            try {
                dispatch(event, metadata, raw);
            } catch (EventDeliveryException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Stops subscriptions and waits briefly for async workers to exit.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Future<?>> futures = new ArrayList<>(workers.values());
        for (CopyOnWriteArrayList<Sub<?>> list : subs.values()) {
            list.forEach(sub -> sub.active.set(false));
        }
        for (Future<?> future : futures) {
            try {
                future.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        subs.clear();
        workers.clear();
    }

    /**
     * Returns whether an async listener has failed.
     */
    public boolean hasAsyncFailure() {
        return asyncFailure.get() != null;
    }

    /**
     * Returns the first async listener failure, if any.
     */
    public EventDeliveryException asyncFailure() {
        return asyncFailure.get();
    }

    /**
     * Throws the recorded async listener failure if one exists.
     */
    public void throwIfAsyncFailed() {
        EventDeliveryException failure = asyncFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Records an async failure reported by a component that processes events outside
     * this bus's own async subscription loop.
     *
     * <p>The first recorded failure wins so later publishes report the original
     * cause that made the event stream unsafe.</p>
     */
    public void recordAsyncFailure(Class<? extends Event> type, Throwable cause) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(cause, "cause");
        EventDeliveryException failure = cause instanceof EventDeliveryException eventFailure
                ? eventFailure
                : new EventDeliveryException(type, cause);
        asyncFailure.compareAndSet(null, failure);
    }

    private <E extends Event> void dispatch(E event, EventMetadata metadata, Sub<?> raw) {
        @SuppressWarnings("unchecked")
        Sub<E> sub = (Sub<E>) raw;
        if (!sub.active.get()) {
            return;
        }
        EventDeliveryException failed = sub.failure.get();
        if (failed != null) {
            throw failed;
        }
        EventFilter<E> filter = sub.options.filter();
        if (filter != null && !filter.test(event, metadata)) {
            return;
        }
        EventContext<E> ctx = new SimpleCtx<>(event, metadata);
        if (sub.executor == null) {
            callListener(sub, ctx);
        } else {
            offerAsync(sub, ctx);
        }
    }

    private <E extends Event> void callListener(Sub<E> sub, EventContext<E> ctx) {
        try {
            sub.listener.onEvent(ctx);
            sub.delivered.incrementAndGet();
        } catch (Throwable t) {
            log.error("Listener error for {}: {}", ctx.event().getClass().getSimpleName(), t.toString(), t);
            throw new EventDeliveryException(ctx.event().getClass(), t);
        }
    }

    private <E extends Event> void startAsyncLoop(Class<E> type, Sub<E> sub) {
        Future<?> future = ((ExecutorService) sub.executor).submit(() -> {
            while (sub.active.get()) {
                try {
                    EventContext<E> ctx = sub.queue.poll(100, TimeUnit.MILLISECONDS);
                    if (ctx == null) {
                        continue;
                    }
                    callListener(sub, ctx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    EventDeliveryException failure = t instanceof EventDeliveryException eventFailure
                            ? eventFailure
                            : new EventDeliveryException(type, t);
                    sub.failure.compareAndSet(null, failure);
                    asyncFailure.compareAndSet(null, failure);
                    sub.active.set(false);
                    log.error("Async listener error for {}; subscription disabled and future publishes will fail: {}",
                            type.getSimpleName(), t.toString(), t);
                    break;
                }
            }
        });
        workers.put(sub, future);
    }

    private <E extends Event> void offerAsync(Sub<E> sub, EventContext<E> ctx) {
        EventDeliveryException failed = sub.failure.get();
        if (failed != null) {
            throw failed;
        }
        boolean offered;
        try {
            switch (sub.options.overflow()) {
                case DROP_LATEST -> offered = sub.queue.offer(ctx);
                case DROP_OLDEST -> {
                    sub.queue.poll();
                    offered = sub.queue.offer(ctx);
                }
                case ERROR -> offered = sub.queue.offer(ctx);
                case BLOCK -> offered = sub.queue.offer(ctx, 1, TimeUnit.MINUTES);
                default -> offered = sub.queue.offer(ctx);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            offered = false;
        }
        if (!offered) {
            if (sub.options.overflow() == SubscriptionOptions.Overflow.ERROR) {
                throw new RejectedExecutionException("Event queue full for subscriber");
            }
            log.warn("Event dropped due to overflow policy: {}", sub.options.overflow());
        }
    }

    private record SimpleCtx<E extends Event>(E event, EventMetadata metadata) implements EventContext<E> {
    }

    private static final class Sub<E extends Event> {
        private final EventListener<E> listener;
        private final SubscriptionOptions options;
        private final Executor executor;
        private final BlockingQueue<EventContext<E>> queue;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicReference<EventDeliveryException> failure = new AtomicReference<>();
        private final long registrationSeq;
        private final int priority;

        private Sub(EventListener<E> listener, SubscriptionOptions options, long registrationSeq) {
            this.listener = Objects.requireNonNull(listener, "listener");
            this.options = Objects.requireNonNull(options, "options");
            this.executor = options.executor();
            this.queue = executor != null
                    ? new ArrayBlockingQueue<>(Math.max(1, options.bufferSize()))
                    : null;
            this.registrationSeq = registrationSeq;
            this.priority = options.priority();
        }
    }

    /**
     * Runtime exception used to fail the current publisher when a listener cannot
     * process an event.
     */
    public static final class EventDeliveryException extends RuntimeException {
        private EventDeliveryException(Class<?> eventType, Throwable cause) {
            super("Event listener failed for " + eventType.getSimpleName(), cause);
        }
    }
}
