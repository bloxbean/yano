package com.bloxbean.cardano.yano.archive.core.worker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional lifecycle wrapper: disabled construction executes no driver or worker code. */
public final class ArchiveSubsystem implements AutoCloseable {
    private final boolean enabled;
    private final Duration pollInterval;
    private final Runnable boundedWork;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public ArchiveSubsystem(boolean enabled, Duration pollInterval, Runnable boundedWork) {
        this.enabled = enabled;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.boundedWork = Objects.requireNonNull(boundedWork, "boundedWork");
        if (pollInterval.isZero() || pollInterval.isNegative()) throw new IllegalArgumentException("invalid poll interval");
    }

    public void start() {
        if (!enabled || !started.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("yano-archive-worker-", 0).factory());
        executor.scheduleWithFixedDelay(this::runSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean started() { return started.get(); }

    private void runSafely() {
        try { boundedWork.run(); }
        catch (Throwable ignored) { /* status/health is owned by the bounded worker */ }
    }

    @Override public void close() {
        started.set(false);
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) return;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("archive worker did not stop within 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping archive worker", e);
        }
    }
}
