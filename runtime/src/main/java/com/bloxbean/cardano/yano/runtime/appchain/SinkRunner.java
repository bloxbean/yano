package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives a {@link FinalizedStreamSink} with a persisted per-sink cursor
 * (ADR app-layer/006 E3.2): delivers finalized blocks strictly in height order,
 * at-least-once, advancing the cursor only on success and resuming across
 * restarts. Sinks implement only the write; ordering/durability live here.
 */
final class SinkRunner implements AutoCloseable {

    private static final AtomicLong CLOSE_THREAD_SEQUENCE = new AtomicLong();

    private final FinalizedStreamSink sink;
    private final String id;
    private final AppLedgerStore ledger;
    private final String cursorKey;
    private final Logger log;
    private final ThreadFactory closeThreadFactory;
    private final Object lifecycleLock = new Object();
    private final CompletableFuture<Void> deliveryQuiescent = new CompletableFuture<>();
    private final CompletableFuture<Void> productCloseComplete = new CompletableFuture<>();
    private final AtomicBoolean productCloseClaimed = new AtomicBoolean();
    private final AtomicReference<Throwable> closeHandoffFailure = new AtomicReference<>();

    private volatile long deliveredCount;
    private final AtomicLong failureCount = new AtomicLong();
    private volatile String lastErrorType;
    private volatile String closeErrorType;
    private volatile long lastKnownCursor = Long.MIN_VALUE;
    private boolean deliveryActive;
    private boolean shutdownRequested;
    private boolean closeScheduled;

    SinkRunner(FinalizedStreamSink sink, String id, AppLedgerStore ledger, Logger log) {
        this(sink, id, ledger, log, closeTask -> Thread.ofPlatform()
                .daemon(true)
                .name("app-chain-sink-close-" + CLOSE_THREAD_SEQUENCE.incrementAndGet())
                .unstarted(closeTask));
    }

    /** Test seam for observing close-worker termination semantics. */
    SinkRunner(
            FinalizedStreamSink sink,
            String id,
            AppLedgerStore ledger,
            Logger log,
            ThreadFactory closeThreadFactory
    ) {
        this.sink = sink;
        this.id = id;
        this.ledger = ledger;
        this.log = log;
        this.closeThreadFactory = Objects.requireNonNull(
                closeThreadFactory, "closeThreadFactory");
        this.cursorKey = cursorKeyFor(id);
    }

    static String cursorKeyFor(String id) {
        return "sink_cursor_" + HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(id.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Validate and initialize a complete sink batch without leaving cursor
     * state for an inactive partial batch. All plugin callbacks and reads run
     * before the single atomic metadata write.
     */
    static void initializeCursors(AppLedgerStore ledger, List<SinkRunner> runners) {
        Map<String, Long> initialCursors = new LinkedHashMap<>();
        Map<SinkRunner, Long> preparedCursors = new LinkedHashMap<>();
        for (SinkRunner runner : runners) {
            if (runner.ledger != ledger) {
                throw new IllegalArgumentException(
                        "Cannot initialize sink runners belonging to a different ledger");
            }
            CursorInitialization initialization = runner.prepareCursorInitialization();
            preparedCursors.put(runner, initialization.value());
            if (initialization.writeRequired()) {
                initialCursors.put(initialization.key(), initialization.value());
            }
        }
        ledger.metaPutLongs(initialCursors);
        preparedCursors.forEach(SinkRunner::recordInitializedCursor);
    }

    private CursorInitialization prepareCursorInitialization() {
        long current = ledger.metaLong(cursorKey, -1L);
        if (current < 0) {
            // No cursor yet. Migrate a previous implementation's cursor if this
            // sink names one (in-place upgrade keeps at-least-once); otherwise
            // start at the current tip — sinks receive NEW blocks (history is
            // available via catch-up/REST if a consumer needs it).
            String legacyKey = sink.legacyCursorKey();
            if (legacyKey != null && !validLegacyCursorKey(legacyKey)) {
                throw new IllegalArgumentException(
                        "FinalizedStreamSink.legacyCursorKey returned an invalid metadata key");
            }
            long legacy = legacyKey != null ? ledger.metaLong(legacyKey, -1L) : -1L;
            return new CursorInitialization(
                    cursorKey, legacy >= 0 ? legacy : ledger.tipHeight(), true);
        }
        return new CursorInitialization(cursorKey, current, false);
    }

    private static boolean validLegacyCursorKey(String key) {
        if (key.isEmpty() || key.length() > 160) {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            boolean alphaNumeric = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9';
            if (!alphaNumeric && character != '.' && character != '_'
                    && character != '-' && character != ':') {
                return false;
            }
        }
        return true;
    }

    private void recordInitializedCursor(long cursor) {
        synchronized (lifecycleLock) {
            lastKnownCursor = cursor;
        }
    }

    private record CursorInitialization(String key, long value, boolean writeRequired) { }

    String id() {
        return id;
    }

    long cursor() {
        synchronized (lifecycleLock) {
            if (lastKnownCursor != Long.MIN_VALUE) {
                return lastKnownCursor;
            }
            // Production runners are initialized before publication. This
            // fallback keeps direct/library construction safe and linearizes
            // the read against requestShutdown() and deferred ledger close.
            if (shutdownRequested) {
                return 0L;
            }
            lastKnownCursor = ledger.metaLong(cursorKey, 0L);
            return lastKnownCursor;
        }
    }

    long deliveredCount() {
        return deliveredCount;
    }

    long failureCount() {
        return failureCount.get();
    }

    String lastErrorType() {
        return lastErrorType;
    }

    /** Deliver pending blocks up to the tip; stops at the first failure. */
    void deliveryTick() {
        if (!beginDelivery()) {
            return;
        }
        try {
            long tip = ledger.tipHeight();
            long cursor = cursor();
            while (cursor < tip) {
                long next = cursor + 1;
                AppBlock block = ledger.block(next).orElse(null);
                if (block == null || !beginCallback()) {
                    return;
                }
                boolean ok;
                try {
                    ok = sink.deliver(block);
                } catch (Throwable failure) {
                    // The outer scheduled-task boundary records and rethrows
                    // VM-fatal conditions exactly once. Assertion/linkage
                    // failures remain isolated to this sink.
                    rethrowIfFatal(failure);
                    recordFailure("delivery", failure);
                    return;
                }
                if (!ok) {
                    return; // retry the same block next tick (at-least-once)
                }
                if (!commitCursorIfOpen(next)) {
                    return;
                }
                lastErrorType = null;
                cursor = next;
                deliveredCount++;
            }
        } finally {
            endDelivery();
        }
    }

    /** Last-resort isolation for a platform-side tick failure around this runner. */
    void recordTickFailure(Throwable failure) {
        recordFailure("tick", failure);
    }

    /** Preserve JVM termination semantics after the scheduler records them. */
    @SuppressWarnings("removal")
    static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private boolean beginDelivery() {
        synchronized (lifecycleLock) {
            if (shutdownRequested || deliveryActive) {
                return false;
            }
            deliveryActive = true;
            return true;
        }
    }

    private boolean beginCallback() {
        synchronized (lifecycleLock) {
            if (shutdownRequested) {
                return false;
            }
            // The callback is admitted while holding the same lock used by
            // requestShutdown(). Once shutdown returns no later callback can
            // be admitted; an already-admitted callback may finish normally.
            return true;
        }
    }

    private boolean commitCursorIfOpen(long height) {
        synchronized (lifecycleLock) {
            if (shutdownRequested) {
                return false;
            }
            // Keep the write inside the shutdown lock. requestShutdown() can
            // only return before this write begins or after it completes, so
            // no cursor mutation can occur after the shutdown boundary.
            ledger.metaPutLong(cursorKey, height);
            lastKnownCursor = height;
            return true;
        }
    }

    private void endDelivery() {
        boolean completeQuiescence;
        synchronized (lifecycleLock) {
            deliveryActive = false;
            completeQuiescence = shutdownRequested;
        }
        if (completeQuiescence) {
            deliveryQuiescent.complete(null);
        }
    }

    private void recordFailure(String phase, Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String errorType = failure.getClass().getName();
        lastErrorType = errorType;
        failureCount.incrementAndGet();
        // Plugin exception messages may contain credentials/configuration.
        // The stable sink id and exception class are sufficient diagnostics.
        log.warn("Sink '{}' {} failed (errorType={})", id, phase, errorType);
    }

    /** Close admission immediately without waiting for a blocking callback. */
    void requestShutdown() {
        boolean completeQuiescence;
        synchronized (lifecycleLock) {
            shutdownRequested = true;
            completeQuiescence = !deliveryActive;
        }
        if (completeQuiescence) {
            deliveryQuiescent.complete(null);
        }
    }

    CompletableFuture<Void> deliveryQuiescence() {
        return deliveryQuiescent;
    }

    /** Terminal lifetime signal for the plugin product callback owner. */
    CompletableFuture<Void> closeCompletion() {
        return productCloseComplete;
    }

    /**
     * Request exact-once product close after delivery quiesces. The close runs
     * on a dedicated daemon so an uncooperative plugin cannot make subsystem
     * stop unbounded.
     */
    CompletableFuture<Void> closeAsync() {
        requestShutdown();
        boolean schedule;
        synchronized (lifecycleLock) {
            schedule = !closeScheduled;
            closeScheduled = true;
        }
        if (schedule) {
            startAsyncProductClose();
        }
        return productCloseComplete;
    }

    private void startAsyncProductClose() {
        Throwable handoffFailure;
        try {
            Thread closeThread = Objects.requireNonNull(
                    closeThreadFactory.newThread(this::runAsyncProductClose),
                    "closeThreadFactory returned null");
            closeThread.start();
            return;
        } catch (Throwable startFailure) {
            handoffFailure = recordCloseTaskStartFailure(startFailure, false);
            recordCloseHandoffFailure(handoffFailure);
        }

        if (LifecycleFailures.isProcessFatal(handoffFailure)
                && deliveryQuiescent.isDone()) {
            // Preserve the fatal on this thread, but only after the actual
            // product callback has ended and the lifetime is truthful.
            closeProduct(true);
            awaitProductCloseCompletion();
            LifecycleFailures.rethrowIfProcessFatal(handoffFailure);
            return; // unreachable for a process-fatal hand-off
        }

        // A failed hand-off does not terminate the sink product. Use a fresh
        // platform daemon so a blocked delivery still keeps closeAsync()
        // bounded. If that last hand-off also fails, the caller must wait for
        // delivery quiescence and own the actual close callback itself.
        try {
            Thread fallback = Thread.ofPlatform()
                    .daemon(true)
                    .name("app-chain-sink-close-fallback-"
                            + CLOSE_THREAD_SEQUENCE.incrementAndGet())
                    .unstarted(this::runAsyncProductClose);
            fallback.start();
        } catch (Throwable fallbackFailure) {
            recordCloseHandoffFailure(
                    recordCloseTaskStartFailure(fallbackFailure, true));
            deliveryQuiescent.join();
            closeProduct(true);
            awaitProductCloseCompletion();
            throw propagateLifecycleFailure(
                    closeHandoffFailure.get(), "Sink close task failed to start");
        }

        // Ordinary lifecycle failures remain visible immediately. A process-
        // fatal failure is rethrown by the fallback worker only after the real
        // sink close callback has ended and its lifetime future is terminal.
        if (!LifecycleFailures.isProcessFatal(handoffFailure)) {
            throw propagateLifecycleFailure(
                    handoffFailure, "Sink close task failed to start");
        }
    }

    private void runAsyncProductClose() {
        deliveryQuiescent.join();
        closeProduct(false);
    }

    private void awaitProductCloseCompletion() {
        productCloseComplete.handle((ignored, failure) -> null).join();
    }

    private void recordCloseHandoffFailure(Throwable failure) {
        mergeFailure(closeHandoffFailure, failure);
    }

    private Throwable recordCloseTaskStartFailure(Throwable failure, boolean fallback) {
        Throwable outcome = failure;
        closeErrorType = failure.getClass().getName();
        try {
            if (fallback) {
                log.warn("Sink '{}' fallback close task failed to start (errorType={})",
                        id, closeErrorType);
            } else {
                log.warn("Sink '{}' close task failed to start (errorType={})",
                        id, closeErrorType);
            }
        } catch (Throwable diagnosticFailure) {
            outcome = LifecycleFailures.merge(outcome, diagnosticFailure);
        }
        return outcome;
    }

    private static RuntimeException propagateLifecycleFailure(
            Throwable failure,
            String message
    ) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(message, failure);
    }

    private void closeProduct(boolean propagateFailure) {
        if (!productCloseClaimed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = closeHandoffFailure.get();
        try {
            sink.close();
        } catch (Throwable closeFailure) {
            Throwable reportedFailure = closeFailure;
            closeErrorType = closeFailure.getClass().getName();
            try {
                log.warn("Sink '{}' close failed (errorType={})", id, closeErrorType);
            } catch (Throwable diagnosticFailure) {
                reportedFailure = LifecycleFailures.merge(
                        reportedFailure, diagnosticFailure);
            }
            failure = LifecycleFailures.merge(failure, reportedFailure);
        } finally {
            if (failure == null) {
                productCloseComplete.complete(null);
            } else {
                productCloseComplete.completeExceptionally(failure);
            }
        }
        if (failure != null) {
            // Complete the contribution lifetime before preserving actual JVM
            // termination semantics on either the synchronous caller or the
            // dedicated asynchronous close worker.
            LifecycleFailures.rethrowIfProcessFatal(failure);
        }
        if (propagateFailure && failure != null) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Sink close failed", failure);
        }
    }

    private static void mergeFailure(
            AtomicReference<Throwable> target,
            Throwable failure
    ) {
        while (true) {
            Throwable current = target.get();
            Throwable merged = LifecycleFailures.merge(current, failure);
            if (target.compareAndSet(current, merged)) {
                return;
            }
        }
    }

    @Override
    public void close() {
        requestShutdown();
        boolean closeSynchronously;
        boolean scheduleAsynchronously;
        synchronized (lifecycleLock) {
            closeSynchronously = !deliveryActive && !closeScheduled;
            scheduleAsynchronously = deliveryActive && !closeScheduled;
            if (closeSynchronously || scheduleAsynchronously) {
                closeScheduled = true;
            }
        }
        if (closeSynchronously) {
            // Startup rollback has no delivery thread, so retain its historic
            // failure aggregation/precedence while still enforcing the fence.
            closeProduct(true);
        } else if (scheduleAsynchronously) {
            startAsyncProductClose();
        }
    }
}
