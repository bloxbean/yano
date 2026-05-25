package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.runtime.events.PropagatingEventBus;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional async UTXO event handler that preserves ordering using a single-thread executor.
 * Apply/rollback are offloaded off the publisher thread but executed sequentially.
 */
public final class UtxoEventHandlerAsync implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UtxoEventHandlerAsync.class);

    private final UtxoStoreWriter writer;
    private final PropagatingEventBus propagatingEventBus;
    private final ExecutorService single;
    private final List<SubscriptionHandle> handles;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    public UtxoEventHandlerAsync(EventBus bus, UtxoStoreWriter writer) {
        this.writer = writer;
        this.propagatingEventBus = bus instanceof PropagatingEventBus propagating ? propagating : null;
        this.single = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "utxo-apply-1");
            t.setDaemon(true);
            return t;
        });
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        this.handles = AnnotationListenerRegistrar.register(bus, this, defaults);
    }

    @DomainEventListener(order = 100)
    public void onBlockApplied(BlockAppliedEvent e) {
        throwIfFailed();
        if (writer == null || !writer.isEnabled()) return;
        try {
            single.execute(() -> runAsync(BlockAppliedEvent.class, "BlockAppliedEvent", () -> writer.applyBlock(e)));
        } catch (RejectedExecutionException ex) {
            throw recordFailure(BlockAppliedEvent.class, "BlockAppliedEvent enqueue", ex);
        }
    }

    @DomainEventListener(order = 100)
    public void onRollback(RollbackEvent e) {
        throwIfFailed();
        if (writer == null || !writer.isEnabled()) return;
        try {
            single.execute(() -> runAsync(RollbackEvent.class, "RollbackEvent", () -> writer.rollbackTo(e)));
        } catch (RejectedExecutionException ex) {
            throw recordFailure(RollbackEvent.class, "RollbackEvent enqueue", ex);
        }
    }

    public boolean hasFailure() {
        return failure.get() != null;
    }

    public void throwIfFailed() {
        RuntimeException failed = failure.get();
        if (failed != null) {
            throw failed;
        }
    }

    private void runAsync(Class<? extends Event> eventType, String description, Runnable work) {
        if (failure.get() != null) {
            return;
        }
        try {
            work.run();
        } catch (Throwable t) {
            recordFailure(eventType, description, t);
        }
    }

    private RuntimeException recordFailure(Class<? extends Event> eventType, String description, Throwable t) {
        RuntimeException error = t instanceof RuntimeException runtime
                ? new IllegalStateException("Async UTXO event handler failed during " + description, runtime)
                : new IllegalStateException("Async UTXO event handler failed during " + description, t);
        failure.compareAndSet(null, error);
        if (propagatingEventBus != null) {
            propagatingEventBus.recordAsyncFailure(eventType, failure.get());
        }
        log.error("Async UTXO event handler failed during {}: {}", description, t.toString(), t);
        return failure.get();
    }

    @Override
    public void close() {
        closeAndAwait(Duration.ofSeconds(5));
    }

    public boolean closeAndAwait(Duration timeout) {
        try { if (handles != null) handles.forEach(h -> { try { h.close(); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        try {
            single.shutdown();
            if (!single.awaitTermination(Math.max(1L, effectiveTimeout.toMillis()), TimeUnit.MILLISECONDS)) {
                recordFailure(BlockAppliedEvent.class, "close timeout",
                        new IllegalStateException("Timed out draining async UTXO event handler"));
                single.shutdownNow();
                return false;
            }
        } catch (InterruptedException e) {
            recordFailure(BlockAppliedEvent.class, "close interrupted", e);
            single.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
        return failure.get() == null;
    }
}
