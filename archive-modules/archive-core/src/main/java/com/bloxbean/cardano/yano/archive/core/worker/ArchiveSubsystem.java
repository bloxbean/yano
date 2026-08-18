package com.bloxbean.cardano.yano.archive.core.worker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Optional lifecycle wrapper: disabled construction executes no driver or worker code. */
public final class ArchiveSubsystem implements AutoCloseable {
    private final boolean enabled;
    private final Duration pollInterval;
    private final Runnable boundedWork;
    private final Consumer<Throwable> failureObserver;
    private final Duration shutdownTimeout;
    private final AtomicBoolean started = new AtomicBoolean();
    // Read cross-thread by terminated(); a stale value would let a caller
    // destroy the hot store while the worker is still alive.
    private volatile ScheduledExecutorService executor;

    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    public ArchiveSubsystem(boolean enabled, Duration pollInterval, Runnable boundedWork) {
        this(enabled, pollInterval, boundedWork, failure -> { });
    }

    public ArchiveSubsystem(boolean enabled, Duration pollInterval, Runnable boundedWork,
                            Consumer<Throwable> failureObserver) {
        this(enabled, pollInterval, boundedWork, failureObserver, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    public ArchiveSubsystem(boolean enabled, Duration pollInterval, Runnable boundedWork,
                            Consumer<Throwable> failureObserver, Duration shutdownTimeout) {
        this.enabled = enabled;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.boundedWork = Objects.requireNonNull(boundedWork, "boundedWork");
        this.failureObserver = Objects.requireNonNull(failureObserver, "failureObserver");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (pollInterval.isZero() || pollInterval.isNegative()) throw new IllegalArgumentException("invalid poll interval");
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid shutdown timeout");
        }
    }

    public synchronized void start() {
        if (!enabled) return;
        // Checked before the CAS: a failed stop() leaves both `started` true and
        // `executor` non-null, so a CAS-first guard could never observe an
        // orphaned executor and a restart would silently do nothing.
        if (executor != null) {
            throw new IllegalStateException("archive worker is still running; stop() must terminate it first");
        }
        if (!started.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("yano-archive-worker-", 0).factory());
        executor.scheduleWithFixedDelay(this::runSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean started() { return started.get(); }

    private void runSafely() {
        try { boundedWork.run(); }
        catch (Throwable failure) {
            // The bounded worker owns per-dataset status, but a failure thrown
            // before it can record one would otherwise vanish entirely. Report
            // it here so a wedged cycle can never be silent.
            //
            // scheduleWithFixedDelay suppresses all later executions if a run
            // completes exceptionally, so the observer itself must never be able
            // to escape and cancel the archive worker permanently.
            try {
                failureObserver.accept(failure);
            } catch (Throwable observerFailure) {
                // Nothing safe is left to report through.
            }
        }
    }

    /**
     * Requests stop and joins the worker.
     *
     * <p>Returns whether every task actually terminated, rather than throwing.
     * A caller must know this: dependencies the worker touches — notably a hot
     * store whose native handles close immediately — may only be destroyed once
     * the worker is provably gone. The executor reference is retained while the
     * worker is still running so a later attempt can re-check termination.
     */
    public boolean stop() {
        ScheduledExecutorService current = executor;
        if (current == null) {
            started.set(false);
            return true;
        }
        current.shutdownNow();
        try {
            if (current.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor = null;
                // Only now is a restart safe: the previous worker is provably gone.
                started.set(false);
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** True once the worker has provably terminated. */
    public boolean terminated() {
        return executor == null;
    }

    @Override public void close() {
        if (!stop()) {
            throw new IllegalStateException("archive worker did not stop within "
                    + shutdownTimeout.toSeconds() + " seconds");
        }
    }
}
