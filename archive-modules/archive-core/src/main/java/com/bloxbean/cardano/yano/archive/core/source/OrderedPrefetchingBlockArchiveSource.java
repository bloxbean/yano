package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * ADR-038 Phase 2c: decodes canonical block bodies ahead of the consumer on a
 * bounded executor while preserving strictly sequential consumption.
 *
 * <p>Blocks are immutable once fetched and {@code YaciUtxoHistoryDecoder} is
 * resolver-independent, so decoding parallelises safely. Everything stateful —
 * the pointer resolver, undo records, cursor and coverage — stays on the
 * consuming thread in canonical order, and transaction order within a block is
 * untouched. The worker and dataset keep their existing sequencing, so
 * {@code verifyParentChain} and the pre-commit anchor recheck are unchanged.
 *
 * <h2>Conservative estimated in-flight memory budget</h2>
 *
 * <p>This is <b>not</b> an exact byte ceiling and must not be described as one.
 * {@link BlockArchiveSource#readCanonical} performs fetch and decode together, so
 * neither the raw size nor the decoded heap footprint is knowable before a task
 * runs; exposing raw bytes would still not give exact decoded-object accounting.
 * Budget is therefore a configured per-block estimate that must conservatively
 * cover raw bytes, the decoded fact graph, their temporary overlap, and
 * future/result bookkeeping.
 *
 * <p>Reservation happens on the <b>submitting thread, in canonical order, before
 * the task is registered</b>, and no task ever blocks on a memory permit. A later
 * block therefore cannot hold budget an earlier block needs — blocking occurs only
 * at submission, which the consumer drives, so the permit-ordering deadlock is
 * structurally impossible. A strict in-flight block count bound is retained
 * alongside the estimate.
 *
 * <p>When measured footprint exceeds its reservation the underestimate is
 * surfaced, further submission stops until ordered consumption reduces the debt,
 * and overshoot is bounded to already-active tasks.
 *
 * <h2>Lease safety</h2>
 *
 * <p>The underlying lease is released only once every registered task has
 * genuinely terminated. Cancelling a {@link CompletableFuture} is never treated as
 * proof that execution ended. If the drain exceeds its deadline the lease is
 * <b>not</b> released: the failure is raised loudly and a reaper retains pruning
 * protection until the tasks actually finish.
 *
 * <p>The decorator wraps the underlying source directly rather than routing
 * through {@link CycleCachingBlockArchiveSource}, whose {@code computeIfAbsent}
 * holds a bin lock across a decode and whose measured hit rate on this workload
 * is 4.1%.
 */
public final class OrderedPrefetchingBlockArchiveSource<B> implements BlockArchiveSource<B> {

    private static final System.Logger LOG =
            System.getLogger(OrderedPrefetchingBlockArchiveSource.class.getName());

    private final BlockArchiveSource<B> delegate;
    private final ExecutorService executor;
    private final int maxInFlightBlocks;
    private final long maxInFlightBytes;
    private final long estimatedBytesPerBlock;
    private final ToLongFunction<BlockSourceContext<B>> footprintEstimator;
    private final long drainTimeoutNanos;

    private PrefetchWindow window;

    private final AtomicLong peakReservedBytes = new AtomicLong();
    private final AtomicLong peakObservedBytes = new AtomicLong();
    private final AtomicLong peakInFlightBlocks = new AtomicLong();
    private final AtomicLong peakCompletedWaiting = new AtomicLong();
    private final AtomicLong largestObservedBlock = new AtomicLong();
    private final AtomicLong blocksPrefetched = new AtomicLong();
    private final AtomicLong reservationOverages = new AtomicLong();
    private final AtomicLong reorderWaitNanos = new AtomicLong();
    private final AtomicLong drainTimeouts = new AtomicLong();
    private final AtomicLong pendingReapers = new AtomicLong();

    // Lazily created, single-threaded and owned here so repeated drain timeouts
    // cannot create unbounded reaper threads.
    private volatile ExecutorService reaper;

    private synchronized ExecutorService reaperExecutor() {
        if (reaper == null) {
            reaper = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "yano-archive-prefetch-reaper");
                thread.setDaemon(true);
                return thread;
            });
        }
        return reaper;
    }

    /** Outstanding reaper tasks still holding a block-body lease. */
    public long pendingReapers() {
        return pendingReapers.get();
    }

    /** Stops the reaper thread. Any lease already handed to it is released first. */
    public void shutdownReaper() {
        ExecutorService selected;
        synchronized (this) { selected = reaper; reaper = null; }
        if (selected == null) return;
        selected.shutdown();
        try {
            if (!selected.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING,
                        "Prefetch reaper did not terminate within 30s; {0} task(s) pending", pendingReapers.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public OrderedPrefetchingBlockArchiveSource(BlockArchiveSource<B> delegate, ExecutorService executor,
                                                int maxInFlightBlocks, long maxInFlightBytes,
                                                long estimatedBytesPerBlock,
                                                ToLongFunction<BlockSourceContext<B>> footprintEstimator,
                                                java.time.Duration drainTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.footprintEstimator = Objects.requireNonNull(footprintEstimator, "footprintEstimator");
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        if (maxInFlightBlocks < 1) throw new IllegalArgumentException("maxInFlightBlocks must be positive");
        if (estimatedBytesPerBlock < 1) throw new IllegalArgumentException("estimatedBytesPerBlock must be positive");
        if (maxInFlightBytes < estimatedBytesPerBlock) {
            throw new IllegalArgumentException("maxInFlightBytes must admit at least one block: "
                    + maxInFlightBytes + " < " + estimatedBytesPerBlock);
        }
        if (drainTimeout.isNegative() || drainTimeout.isZero()) {
            throw new IllegalArgumentException("drainTimeout must be positive");
        }
        this.maxInFlightBlocks = maxInFlightBlocks;
        this.maxInFlightBytes = maxInFlightBytes;
        this.estimatedBytesPerBlock = estimatedBytesPerBlock;
        this.drainTimeoutNanos = drainTimeout.toNanos();
    }

    /** Reorder state for exactly one batch. */
    private final class PrefetchWindow {
        private final long start;
        private final long end;
        private final Object lock = new Object();
        private final NavigableMap<Long, CompletableFuture<Optional<BlockSourceContext<B>>>> pending = new TreeMap<>();
        private final Map<Long, Long> observedFootprint = new LinkedHashMap<>();

        private long nextToSubmit;
        private boolean cancelled;
        private long reservedBytes;
        private long observedBytes;
        /** Registered before scheduling; decremented only when a task body ends. */
        private int registeredTasks;

        PrefetchWindow(long start, long end) {
            this.start = start;
            this.end = end;
            this.nextToSubmit = start;
        }

        /**
         * Submits as far ahead as the count and estimate allow. Reservation and
         * registration both happen here, under {@code lock}, so cancellation cannot
         * interleave with a submission and no task is ever scheduled after a drain
         * has begun.
         */
        void fill() {
            synchronized (lock) {
                while (!cancelled && nextToSubmit <= end
                        && pending.size() < maxInFlightBlocks
                        && reservedBytes + estimatedBytesPerBlock <= maxInFlightBytes
                        // Stop submitting while measured footprint exceeds its
                        // reservation; ordered consumption must clear the debt first.
                        && observedBytes <= reservedBytes) {
                    long number = nextToSubmit;
                    var future = new CompletableFuture<Optional<BlockSourceContext<B>>>();
                    pending.put(number, future);
                    reservedBytes += estimatedBytesPerBlock;
                    registeredTasks++;
                    nextToSubmit++;
                    peakReservedBytes.accumulateAndGet(reservedBytes, Math::max);
                    peakInFlightBlocks.accumulateAndGet(pending.size(), Math::max);
                    try {
                        executor.execute(() -> runTask(number, future));
                    } catch (RejectedExecutionException e) {
                        // Reservation and registration must not leak when the
                        // executor refuses the task after we already booked them.
                        pending.remove(number);
                        reservedBytes -= estimatedBytesPerBlock;
                        registeredTasks--;
                        nextToSubmit--;
                        lock.notifyAll();
                        throw new ArchiveStoreException("prefetch executor rejected block " + number, e);
                    }
                }
            }
        }

        private void runTask(long number, CompletableFuture<Optional<BlockSourceContext<B>>> future) {
            try {
                boolean stop;
                synchronized (lock) { stop = cancelled; }
                if (stop) {
                    future.cancel(false);
                    return;
                }
                Optional<BlockSourceContext<B>> result = delegate.readCanonical(number);
                result.ifPresent(context -> recordFootprint(number, context));
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                synchronized (lock) {
                    registeredTasks--;
                    lock.notifyAll();
                }
            }
        }

        private void recordFootprint(long number, BlockSourceContext<B> context) {
            long size = Math.max(0, footprintEstimator.applyAsLong(context));
            synchronized (lock) {
                observedFootprint.put(number, size);
                observedBytes += size;
                peakObservedBytes.accumulateAndGet(observedBytes, Math::max);
                peakCompletedWaiting.accumulateAndGet(observedFootprint.size(), Math::max);
                largestObservedBlock.accumulateAndGet(size, Math::max);
                if (size > estimatedBytesPerBlock) {
                    long overages = reservationOverages.incrementAndGet();
                    if (overages == 1 || overages % 100 == 0) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Prefetch footprint estimate exceeded at block {0}: observed {1} > estimate {2}"
                                        + " (occurrences={3}); submission throttles until consumption catches up",
                                number, size, estimatedBytesPerBlock, overages);
                    }
                }
            }
        }

        Optional<BlockSourceContext<B>> take(long number) {
            CompletableFuture<Optional<BlockSourceContext<B>>> future;
            synchronized (lock) {
                future = pending.get(number);
            }
            if (future == null) {
                fill();
                synchronized (lock) { future = pending.get(number); }
            }
            if (future == null) return delegate.readCanonical(number);

            long waitStart = System.nanoTime();
            try {
                Optional<BlockSourceContext<B>> result = future.join();
                reorderWaitNanos.addAndGet(System.nanoTime() - waitStart);
                blocksPrefetched.incrementAndGet();
                return result;
            } catch (CancellationException e) {
                throw new ArchiveStoreException("prefetch cancelled for block " + number, e);
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new ArchiveStoreException("prefetch failed for block " + number, cause);
            } finally {
                synchronized (lock) {
                    pending.remove(number);
                    reservedBytes = Math.max(0, reservedBytes - estimatedBytesPerBlock);
                    Long size = observedFootprint.remove(number);
                    if (size != null) observedBytes = Math.max(0, observedBytes - size);
                }
                fill();
            }
        }

        /**
         * Stops submission, cancels outstanding futures and waits for every
         * registered task body to terminate.
         *
         * @return true when all tasks terminated within the deadline
         */
        boolean cancelAndAwait() {
            List<CompletableFuture<Optional<BlockSourceContext<B>>>> outstanding;
            synchronized (lock) {
                cancelled = true;
                outstanding = new ArrayList<>(pending.values());
            }
            for (var future : outstanding) future.cancel(false);

            long deadline = System.nanoTime() + drainTimeoutNanos;
            synchronized (lock) {
                while (registeredTasks > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    try {
                        lock.wait(Math.max(1, remaining / 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return registeredTasks == 0;
                    }
                }
                pending.clear();
                observedFootprint.clear();
                reservedBytes = 0;
                observedBytes = 0;
                return true;
            }
        }

        /** Blocks until every registered task body has terminated. */
        void awaitTerminationUninterruptibly() {
            synchronized (lock) {
                boolean interrupted = false;
                while (registeredTasks > 0) {
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        int registeredTasks() {
            synchronized (lock) { return registeredTasks; }
        }
    }

    @Override
    public Optional<BlockSourceContext<B>> readCanonical(long blockNumber) {
        PrefetchWindow active;
        synchronized (this) { active = window; }
        if (active == null || blockNumber < active.start || blockNumber > active.end) {
            return delegate.readCanonical(blockNumber);
        }
        return active.take(blockNumber);
    }

    @Override
    public Optional<CanonicalBlockReference> canonicalReference(long blockNumber) {
        return delegate.canonicalReference(blockNumber);
    }

    @Override
    public boolean extendsCanonicalParent(byte[] predecessorHash, BlockSourceContext<B> current) {
        return delegate.extendsCanonicalParent(predecessorHash, current);
    }

    @Override
    public ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt) {
        ArchiveSourceLease lease = delegate.acquire(startBlock, endBlock, expiresAt);
        PrefetchWindow opened = new PrefetchWindow(startBlock, endBlock);
        synchronized (this) {
            if (window != null) {
                lease.close();
                throw new IllegalStateException("prefetch window already active");
            }
            window = opened;
        }
        try {
            opened.fill();
        } catch (RuntimeException e) {
            closeWindow(opened, lease);
            throw e;
        }
        return new ArchiveSourceLease() {
            @Override public UUID leaseId() { return lease.leaseId(); }
            @Override public Instant expiresAt() { return lease.expiresAt(); }
            @Override public ArchiveSourceLease renew(Instant value) { return lease.renew(value); }
            @Override public void close() { closeWindow(opened, lease); }
        };
    }

    /**
     * Releases the underlying lease only once no task can still enter or execute
     * {@code delegate.readCanonical}. On drain timeout the lease is deliberately
     * <b>not</b> released — pruning protection is retained by a reaper until the
     * registered tasks terminate — and the failure is raised.
     */
    private void closeWindow(PrefetchWindow opened, ArchiveSourceLease lease) {
        boolean drained = opened.cancelAndAwait();
        synchronized (this) {
            if (window == opened) window = null;
        }
        if (drained) {
            lease.close();
            return;
        }
        drainTimeouts.incrementAndGet();
        int stuck = opened.registeredTasks();
        LOG.log(System.Logger.Level.ERROR,
                "Prefetch drain exceeded its deadline with {0} task(s) still running; retaining the block-body"
                        + " lease until they terminate rather than dropping pruning protection", stuck);
        // One shared single-thread reaper, not a thread per timeout: repeated
        // timeouts must not be able to spawn unbounded threads. Pending reaper work
        // is counted so a backlog is observable rather than silent.
        pendingReapers.incrementAndGet();
        reaperExecutor().execute(() -> {
            try {
                opened.awaitTerminationUninterruptibly();
            } finally {
                lease.close();
                long outstanding = pendingReapers.decrementAndGet();
                LOG.log(System.Logger.Level.WARNING,
                        "Prefetch reaper released the block-body lease after delayed task termination;"
                                + " {0} reaper task(s) still pending", outstanding);
            }
        });
        throw new ArchiveStoreException("prefetch tasks did not terminate within the drain deadline; "
                + stuck + " still running, block-body lease retained");
    }

    @Override
    public long earliestRetainedBody() {
        return delegate.earliestRetainedBody();
    }

    public Stats stats() {
        return new Stats(peakInFlightBlocks.get(), peakReservedBytes.get(), peakObservedBytes.get(),
                peakCompletedWaiting.get(), largestObservedBlock.get(), blocksPrefetched.get(),
                reservationOverages.get(), reorderWaitNanos.get(), drainTimeouts.get(), pendingReapers.get(),
                maxInFlightBlocks, maxInFlightBytes, estimatedBytesPerBlock);
    }

    public void resetStats() {
        peakInFlightBlocks.set(0);
        peakReservedBytes.set(0);
        peakObservedBytes.set(0);
        peakCompletedWaiting.set(0);
        largestObservedBlock.set(0);
        blocksPrefetched.set(0);
        reservationOverages.set(0);
        reorderWaitNanos.set(0);
        drainTimeouts.set(0);
    }

    /** Prefetch instrumentation for ADR-038 Phase 2c benchmarking. */
    public record Stats(long peakInFlightBlocks, long peakReservedBytes, long peakObservedBytes,
                        long peakCompletedWaiting, long largestObservedBlockBytes, long blocksPrefetched,
                        long reservationOverages, long reorderWaitNanos, long drainTimeouts, long pendingReapers,
                        int maxInFlightBlocks, long maxInFlightBytes, long estimatedBytesPerBlock) {

        /** True when measured footprint ever exceeded its conservative reservation. */
        public boolean underestimated() { return reservationOverages > 0; }

        public Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("peakInFlightBlocks", peakInFlightBlocks);
            values.put("peakReservedBytes", peakReservedBytes);
            values.put("peakObservedBytes", peakObservedBytes);
            values.put("peakCompletedWaiting", peakCompletedWaiting);
            values.put("largestObservedBlockBytes", largestObservedBlockBytes);
            values.put("blocksPrefetched", blocksPrefetched);
            values.put("reservationOverages", reservationOverages);
            values.put("reorderWaitMillis", TimeUnit.NANOSECONDS.toMillis(reorderWaitNanos));
            values.put("drainTimeouts", drainTimeouts);
            values.put("pendingReapers", pendingReapers);
            return values;
        }
    }
}
