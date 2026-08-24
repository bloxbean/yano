package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Guarantees a sink is never closed while it is being used.
 *
 * <p>The obvious shutdown - stop the loop, interrupt the thread, close the sink - is wrong in a
 * way that is easy to miss and hard to reproduce: interrupting does not abort a JDBC call, so the
 * close can land while a commit is still in flight and tear the connection down mid transaction.
 * Waiting first and then closing anyway is <em>also</em> wrong; it narrows the race without
 * removing it, and a log line saying the wait expired is not a substitute for not doing it.
 *
 * <p>So the sink is only ever touched while this lock is held, and shutdown closes it only if it
 * can take that same lock. If it cannot, the sink stays open and the process exits with it open,
 * which the operating system reclaims. An incomplete shutdown is a visible, recoverable event -
 * the outbox is durable and an unacknowledged commit replays - whereas concurrent close is
 * undefined behaviour inside the database driver.
 *
 * <p><strong>The wait will sometimes expire, by design.</strong> A bounded maintenance pass may
 * legitimately run for the housekeeping budget plus the compaction budget - 30 s + 300 s at the
 * defaults - which is far longer than any sensible shutdown wait. Shutdown does not try to
 * outlast it; it declines to close instead.
 */
public final class ProjectionSinkLifecycle implements AutoCloseable {

    private final ProjectionSink sink;
    private final ReentrantLock inUse = new ReentrantLock();
    private volatile boolean closed;

    public ProjectionSinkLifecycle(ProjectionSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Run one operation against the sink, holding the lock for its duration.
     *
     * @throws IllegalStateException if the sink has already been closed, which would otherwise
     *                               surface as a driver-level error at an arbitrary later point
     */
    public <T> T use(Function<ProjectionSink, T> operation) {
        inUse.lock();
        try {
            if (closed) throw new IllegalStateException("projection sink is closed");
            return operation.apply(sink);
        } finally {
            inUse.unlock();
        }
    }

    /** Whether an operation is in flight; for diagnostics and tests, never for control flow. */
    public boolean busy() {
        return inUse.isLocked();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Close the sink, but only once nothing is using it.
     *
     * @param wait how long to wait for the in-flight operation to finish
     * @return true if the sink was closed; false if it is still in use and was deliberately left
     *         open, which the caller should report as an incomplete shutdown
     */
    public boolean closeWhenIdle(Duration wait) {
        boolean acquired = false;
        try {
            acquired = inUse.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) return false;
        try {
            if (!closed) {
                closed = true;
                sink.close();
            }
            return true;
        } finally {
            inUse.unlock();
        }
    }

    /** Unconditional close, for callers that already know nothing is using the sink. */
    @Override
    public void close() {
        closeWhenIdle(Duration.ZERO);
    }
}
