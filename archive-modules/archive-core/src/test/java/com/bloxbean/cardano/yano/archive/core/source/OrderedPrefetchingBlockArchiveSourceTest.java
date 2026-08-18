package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-038 Phase 2c: the ordered prefetching decorator must be observationally
 * identical to serial reading regardless of interleaving or failure, and must
 * never release the underlying pruning lease while a task can still execute
 * {@code delegate.readCanonical}.
 */
class OrderedPrefetchingBlockArchiveSourceTest {

    private ExecutorService executor;

    @AfterEach
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static BlockSourceContext<String> block(long number) {
        byte[] hash = {(byte) number, (byte) (number >>> 8)};
        byte[] parent = {(byte) (number - 1), (byte) ((number - 1) >>> 8)};
        return new BlockSourceContext<>(number, number * 10, 0, Instant.EPOCH, hash, parent, "block-" + number);
    }

    private static final ToLongFunction<BlockSourceContext<String>> FIXED = ignored -> 512L;

    private static final class FakeSource implements BlockArchiveSource<String> {
        private final LongFunction<Optional<BlockSourceContext<String>>> behaviour;
        final java.util.Set<Long> reads = ConcurrentHashMap.newKeySet();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger peakActive = new AtomicInteger();
        final AtomicInteger leasesOpen = new AtomicInteger();
        final AtomicInteger started = new AtomicInteger();

        FakeSource(LongFunction<Optional<BlockSourceContext<String>>> behaviour) { this.behaviour = behaviour; }

        @Override public Optional<BlockSourceContext<String>> readCanonical(long number) {
            reads.add(number);
            started.incrementAndGet();
            int now = active.incrementAndGet();
            peakActive.accumulateAndGet(now, Math::max);
            try { return behaviour.apply(number); } finally { active.decrementAndGet(); }
        }

        @Override public ArchiveSourceLease acquire(long start, long end, Instant expiry) {
            leasesOpen.incrementAndGet();
            return new ArchiveSourceLease() {
                public UUID leaseId() { return UUID.randomUUID(); }
                public Instant expiresAt() { return expiry; }
                public ArchiveSourceLease renew(Instant value) { return this; }
                public void close() { leasesOpen.decrementAndGet(); }
            };
        }

        @Override public long earliestRetainedBody() { return 0; }
    }

    private OrderedPrefetchingBlockArchiveSource<String> source(FakeSource delegate, int parallelism,
                                                               int maxBlocks, long maxBytes) {
        return source(delegate, parallelism, maxBlocks, maxBytes, FIXED, Duration.ofSeconds(30));
    }

    private OrderedPrefetchingBlockArchiveSource<String> source(FakeSource delegate, int parallelism,
                                                               int maxBlocks, long maxBytes,
                                                               ToLongFunction<BlockSourceContext<String>> estimator,
                                                               Duration drain) {
        executor = Executors.newFixedThreadPool(parallelism);
        return new OrderedPrefetchingBlockArchiveSource<>(delegate, executor, maxBlocks, maxBytes, 512,
                estimator, drain);
    }

    private static List<Long> consume(BlockArchiveSource<String> source, long start, long end) {
        List<Long> seen = new ArrayList<>();
        try (var lease = source.acquire(start, end, Instant.now().plusSeconds(60))) {
            for (long n = start; n <= end; n++) seen.add(source.readCanonical(n).orElseThrow().blockNumber());
        }
        return seen;
    }

    // ---------- ordering and equivalence ----------

    @Test
    void prefetchedOrderMatchesSerialOrderExactly() {
        List<Long> serial = consume(new FakeSource(n -> Optional.of(block(n))), 100, 140);
        List<Long> parallel = consume(source(new FakeSource(n -> Optional.of(block(n))), 4, 8, 64 * 1024), 100, 140);
        assertThat(parallel).isEqualTo(serial).isSorted();
    }

    @Test
    void outOfOrderCompletionStillYieldsCanonicalOrder() {
        CountDownLatch laterDone = new CountDownLatch(1);
        var delegate = new FakeSource(n -> {
            if (n == 100) {
                try { laterDone.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else if (n == 104) {
                laterDone.countDown();
            }
            return Optional.of(block(n));
        });
        List<Long> seen = consume(source(delegate, 4, 8, 64 * 1024), 100, 110);
        assertThat(seen).containsExactly(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L, 110L);
        assertThat(delegate.peakActive.get()).as("decoding really overlapped").isGreaterThan(1);
    }

    @Test
    void parallelismOneMatchesParallelismFour() {
        List<Long> one = consume(source(new FakeSource(n -> Optional.of(block(n))), 1, 2, 64 * 1024), 100, 130);
        shutdown();
        List<Long> four = consume(source(new FakeSource(n -> Optional.of(block(n))), 4, 8, 64 * 1024), 100, 130);
        assertThat(one).isEqualTo(four);
    }

    @Test
    void readsOutsideTheWindowFallBackToTheDelegate() {
        var delegate = new FakeSource(n -> Optional.of(block(n)));
        var source = source(delegate, 2, 4, 64 * 1024);
        try (var lease = source.acquire(100, 110, Instant.now().plusSeconds(60))) {
            assertThat(source.readCanonical(50).orElseThrow().blockNumber()).isEqualTo(50L);
            assertThat(source.readCanonical(999).orElseThrow().blockNumber()).isEqualTo(999L);
        }
        assertThat(delegate.reads).contains(50L, 999L);
    }

    // ---------- failure ordering ----------

    @Test
    void decodeFailureIsSurfacedForFirstMiddleAndFinalTask() {
        for (long failing : new long[]{100, 105, 110}) {
            var delegate = new FakeSource(n -> {
                if (n == failing) throw new ArchiveStoreException("decode failed at " + n);
                return Optional.of(block(n));
            });
            var src = source(delegate, 4, 8, 64 * 1024);
            assertThatThrownBy(() -> consume(src, 100, 110))
                    .isInstanceOf(ArchiveStoreException.class).hasMessageContaining("decode failed at " + failing);
            assertThat(delegate.leasesOpen.get()).as("lease released after clean failure").isZero();
            shutdown();
        }
    }

    @Test
    void multipleFailuresSurfaceTheEarliestCanonicalOne() {
        var delegate = new FakeSource(n -> {
            if (n == 103 || n == 107) throw new ArchiveStoreException("decode failed at " + n);
            return Optional.of(block(n));
        });
        assertThatThrownBy(() -> consume(source(delegate, 4, 8, 64 * 1024), 100, 110))
                .isInstanceOf(ArchiveStoreException.class).hasMessageContaining("decode failed at 103");
    }

    @Test
    void missingBodyPropagatesAsEmptyWithoutCorruptingOrder() {
        var delegate = new FakeSource(n -> n == 105 ? Optional.empty() : Optional.of(block(n)));
        var src = source(delegate, 4, 8, 64 * 1024);
        List<Long> seen = new ArrayList<>();
        try (var lease = src.acquire(100, 110, Instant.now().plusSeconds(60))) {
            for (long n = 100; n <= 110; n++) {
                var got = src.readCanonical(n);
                if (got.isEmpty()) { assertThat(n).isEqualTo(105L); break; }
                seen.add(got.orElseThrow().blockNumber());
            }
        }
        assertThat(seen).containsExactly(100L, 101L, 102L, 103L, 104L);
        assertThat(delegate.leasesOpen.get()).isZero();
    }

    // ---------- lease safety ----------

    @Test
    void leaseIsReleasedOnlyAfterEveryTaskBodyTerminates() {
        AtomicInteger running = new AtomicInteger();
        var delegate = new FakeSource(n -> {
            running.incrementAndGet();
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            running.decrementAndGet();
            return Optional.of(block(n));
        });
        var src = source(delegate, 4, 8, 64 * 1024);
        var lease = src.acquire(100, 200, Instant.now().plusSeconds(60));
        src.readCanonical(100);
        lease.close();
        assertThat(running.get()).as("no task decoding when the lease is released").isZero();
        assertThat(delegate.leasesOpen.get()).isZero();
    }

    /**
     * The gate that matters most: an uninterruptible read outlasting the drain
     * deadline must fail loudly while <b>retaining</b> pruning protection. Failing
     * loudly is not sufficient if the lease was already dropped.
     */
    @Test
    void drainTimeoutRetainsThePruningLeaseUntilTasksActuallyTerminate() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean stillDecoding = new AtomicBoolean(true);
        var delegate = new FakeSource(n -> {
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            stillDecoding.set(false);
            return Optional.of(block(n));
        });
        var src = source(delegate, 2, 4, 64 * 1024, FIXED, Duration.ofMillis(200));

        var lease = src.acquire(100, 130, Instant.now().plusSeconds(60));
        Thread.sleep(100); // let a task enter the delegate read

        assertThatThrownBy(lease::close)
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("did not terminate within the drain deadline")
                .hasMessageContaining("lease retained");

        assertThat(stillDecoding).as("task genuinely still inside the delegate read").isTrue();
        assertThat(delegate.leasesOpen.get())
                .as("pruning protection MUST still be held while decoding continues").isEqualTo(1);
        assertThat(src.stats().drainTimeouts()).isEqualTo(1);

        // Once the tasks finish, the reaper releases the lease.
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (delegate.leasesOpen.get() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(delegate.leasesOpen.get()).as("reaper released the lease after termination").isZero();
    }

    @Test
    void queuedButUnstartedWorkIsCancelledAndDrains() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        var delegate = new FakeSource(n -> {
            if (n == 100) {
                firstEntered.countDown();
                try { releaseFirst.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Optional.of(block(n));
        });
        // Single thread: blocks 101+ are queued but never started while 100 blocks.
        var src = source(delegate, 1, 8, 64 * 1024);
        var lease = src.acquire(100, 130, Instant.now().plusSeconds(60));
        assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
        int startedBeforeClose = delegate.started.get();

        releaseFirst.countDown();
        lease.close();

        assertThat(delegate.leasesOpen.get()).isZero();
        assertThat(startedBeforeClose).as("later blocks were still queued, not started").isLessThan(8);
    }

    @Test
    void noNewSubmissionOnceCloseBegins() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var delegate = new FakeSource(n -> {
            if (n == 100) {
                entered.countDown();
                try { proceed.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Optional.of(block(n));
        });
        var src = source(delegate, 2, 3, 64 * 1024);
        var lease = src.acquire(100, 500, Instant.now().plusSeconds(60));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        Thread closer = new Thread(() -> { proceed.countDown(); lease.close(); });
        closer.start();
        closer.join(20_000);

        assertThat(delegate.reads.stream().max(Long::compareTo).orElseThrow())
                .as("submission stopped at close; the whole 100..500 range was never scheduled")
                .isLessThan(200L);
        assertThat(delegate.leasesOpen.get()).isZero();
    }

    @Test
    void executorRejectionReleasesReservationAndTaskCount() {
        var delegate = new FakeSource(n -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Optional.of(block(n));
        });
        // Zero-capacity queue with one worker: the second concurrent submission is rejected.
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        var src = new OrderedPrefetchingBlockArchiveSource<>(delegate, executor, 8, 64 * 1024, 512,
                FIXED, Duration.ofSeconds(30));

        assertThatThrownBy(() -> consume(src, 100, 130))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("executor rejected");

        // Reservation and registration were released, so a later batch still runs.
        assertThat(delegate.leasesOpen.get()).as("lease not leaked after rejection").isZero();
    }

    @Test
    void overlappingWindowsAreRejectedWithoutLeakingALease() {
        var delegate = new FakeSource(n -> Optional.of(block(n)));
        var src = source(delegate, 2, 4, 64 * 1024);
        try (var first = src.acquire(100, 110, Instant.now().plusSeconds(60))) {
            assertThatThrownBy(() -> src.acquire(200, 210, Instant.now().plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already active");
        }
        assertThat(delegate.leasesOpen.get()).isZero();
    }

    @Test
    void retryAfterCancellationReusesTheExecutorWithoutLeakingTasks() {
        var delegate = new FakeSource(n -> Optional.of(block(n)));
        var src = source(delegate, 2, 4, 64 * 1024);
        var lease = src.acquire(100, 200, Instant.now().plusSeconds(60));
        src.readCanonical(100);
        lease.close();
        assertThat(consume(src, 100, 120)).hasSize(21).isSorted();
        assertThat(delegate.leasesOpen.get()).isZero();
    }

    // ---------- bounded memory ----------

    @Test
    void inFlightWorkIsBoundedByBlockCount() {
        var delegate = new FakeSource(n -> {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Optional.of(block(n));
        });
        var src = source(delegate, 4, 3, 64 * 1024);
        consume(src, 100, 130);
        var stats = src.stats();
        assertThat(stats.peakInFlightBlocks()).isLessThanOrEqualTo(3);
        assertThat(stats.blocksPrefetched()).isEqualTo(31);
        assertThat(stats.underestimated()).isFalse();
    }

    @Test
    void estimatedByteBudgetCanBindBeforeTheCountBound() {
        var delegate = new FakeSource(n -> Optional.of(block(n)));
        // 16 by count, but the estimate admits only 2 (1024/512).
        var src = source(delegate, 4, 16, 1024);
        consume(src, 100, 120);
        assertThat(src.stats().peakInFlightBlocks()).isLessThanOrEqualTo(2);
        assertThat(src.stats().peakReservedBytes()).isLessThanOrEqualTo(1024L);
    }

    @Test
    void observedFootprintExceedingItsReservationIsSurfacedAndThrottles() {
        var delegate = new FakeSource(n -> Optional.of(block(n)));
        // Estimator reports 8x the configured 512-byte estimate: a deliberate underestimate.
        var src = source(delegate, 4, 8, 8 * 1024, ignored -> 4096L, Duration.ofSeconds(30));

        consume(src, 100, 140);

        var stats = src.stats();
        assertThat(stats.underestimated()).as("underestimate surfaced, not hidden").isTrue();
        assertThat(stats.reservationOverages()).isPositive();
        assertThat(stats.largestObservedBlockBytes()).isEqualTo(4096L);
        assertThat(stats.peakObservedBytes()).as("overshoot bounded to active tasks")
                .isLessThanOrEqualTo(stats.maxInFlightBlocks() * 4096L);
    }

    @Test
    void completedButWaitingEntriesAreTracked() {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        var delegate = new FakeSource(n -> {
            if (n == 100) {
                try { releaseFirst.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Optional.of(block(n));
        });
        var src = source(delegate, 4, 6, 64 * 1024);
        var lease = src.acquire(100, 130, Instant.now().plusSeconds(60));
        try {
            // Later blocks complete and wait while block 100 is still decoding.
            Thread.sleep(300);
            releaseFirst.countDown();
            for (long n = 100; n <= 130; n++) src.readCanonical(n);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lease.close();
        }
        assertThat(src.stats().peakCompletedWaiting()).as("completed-but-waiting entries observed").isPositive();
    }

    @Test
    void rejectsInvalidConfiguration() {
        executor = Executors.newFixedThreadPool(1);
        var delegate = new FakeSource(n -> Optional.of(block(n)));
        assertThatThrownBy(() -> new OrderedPrefetchingBlockArchiveSource<>(delegate, executor, 0, 4096, 512, FIXED, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxInFlightBlocks");
        assertThatThrownBy(() -> new OrderedPrefetchingBlockArchiveSource<>(delegate, executor, 4, 4096, 0, FIXED, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("estimatedBytesPerBlock");
        assertThatThrownBy(() -> new OrderedPrefetchingBlockArchiveSource<>(delegate, executor, 4, 256, 512, FIXED, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one block");
        assertThatThrownBy(() -> new OrderedPrefetchingBlockArchiveSource<>(delegate, executor, 4, 4096, 512, FIXED, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drainTimeout");
    }
}
