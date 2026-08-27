package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shutdown must never close a sink that is in use.
 *
 * <p>Interrupting a thread does not abort a JDBC call, so "stop the loop, interrupt, close" can
 * land a close in the middle of a commit. Waiting and then closing anyway narrows that race
 * without removing it — a log line saying the wait expired is not the same as not doing it. These
 * tests pin the difference, with a fake sink that blocks inside a commit until released.
 */
class ProjectionSinkLifecycleTest {

    private static final ProjectionIdentity IDENTITY = new ProjectionIdentity(
            new ArchiveNetworkIdentity(1, "fixture"), "fake", 1,
            Set.of(ProjectionSectionType.TRANSACTION));

    /** A sink whose commit blocks until released, so the race is deterministic rather than timed. */
    private static final class BlockingSink implements com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicBoolean closedWhileBusy = new AtomicBoolean();
        private volatile boolean inCommit;

        void beginCommit() {
            inCommit = true;
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inCommit = false;
        }

        @Override
        public void close() {
            if (inCommit) closedWhileBusy.set(true);
            closeCalls.incrementAndGet();
        }

        @Override public String engine() { return "fake"; }
        @Override public void initialize(com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity e) { }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate coordinate() {
            return com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate.NONE;
        }
        @Override public java.util.Optional<com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt>
                receiptFor(long firstBlock) { return java.util.Optional.empty(); }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt append(
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionRowBatch batch,
                com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader artifacts) {
            throw new UnsupportedOperationException("not used by these tests");
        }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance.Result maintain(
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance.Budget budget) {
            return com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance.Result
                    .unsupported("fake");
        }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkHealth health() {
            return com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkHealth.ready();
        }
    }

    @Test
    void shutdownDoesNotCloseTheSinkWhileACommitIsInFlight() throws Exception {
        var sink = new BlockingSink();
        var lifecycle = new ProjectionSinkLifecycle(sink);

        var drain = new Thread(() -> lifecycle.use(s -> { sink.beginCommit(); return null; }), "drain");
        drain.start();
        assertThat(sink.entered.await(5, TimeUnit.SECONDS)).as("commit started").isTrue();

        // Shutdown runs concurrently while the commit is blocked.
        AtomicReference<Boolean> closedIt = new AtomicReference<>();
        var shutdown = new Thread(() -> closedIt.set(lifecycle.closeWhenIdle(Duration.ofMillis(300))), "shutdown");
        shutdown.start();
        shutdown.join(5_000);

        assertThat(closedIt.get()).as("must decline to close a sink that is in use").isFalse();
        assertThat(sink.closeCalls.get()).as("close must not have been called at all").isZero();
        assertThat(sink.closedWhileBusy.get()).isFalse();
        assertThat(lifecycle.isClosed()).isFalse();

        // Release: the commit finishes normally, and only then can the sink close.
        sink.release.countDown();
        drain.join(5_000);
        assertThat(lifecycle.closeWhenIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(sink.closeCalls.get()).as("closed exactly once").isEqualTo(1);
        assertThat(sink.closedWhileBusy.get()).as("never closed underneath the commit").isFalse();
    }

    @Test
    void theCommitCompletesNormallyDespiteAConcurrentShutdownAttempt() throws Exception {
        var sink = new BlockingSink();
        var lifecycle = new ProjectionSinkLifecycle(sink);
        var completed = new AtomicBoolean();

        var drain = new Thread(() -> lifecycle.use(s -> {
            sink.beginCommit();
            completed.set(true);
            return null;
        }), "drain");
        drain.start();
        assertThat(sink.entered.await(5, TimeUnit.SECONDS)).isTrue();

        var shutdown = new Thread(() -> lifecycle.closeWhenIdle(Duration.ofMillis(200)), "shutdown");
        shutdown.start();
        shutdown.join(5_000);

        sink.release.countDown();
        drain.join(5_000);
        assertThat(completed.get()).as("the in-flight commit must run to completion").isTrue();
    }

    @Test
    void closingTwiceClosesTheSinkOnlyOnce() {
        var sink = new BlockingSink();
        var lifecycle = new ProjectionSinkLifecycle(sink);

        assertThat(lifecycle.closeWhenIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(lifecycle.closeWhenIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(sink.closeCalls.get()).isEqualTo(1);
    }

    @Test
    void useAfterCloseIsRefusedRatherThanReachingTheDriver() {
        var sink = new BlockingSink();
        var lifecycle = new ProjectionSinkLifecycle(sink);
        lifecycle.closeWhenIdle(Duration.ofSeconds(1));

        // Without this the call would reach a closed connection and fail somewhere inside the
        // driver, at a point with no useful context.
        assertThatThrownBy(() -> lifecycle.use(s -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void aLongMaintenancePassOutlastsTheShutdownWaitAndIsStillNotInterruptedByAClose() throws Exception {
        // The default budgets allow housekeeping (30 s) plus compaction (300 s) in one pass, so a
        // maintenance pass can legitimately run far longer than any sane shutdown wait. The
        // shutdown must decline rather than try to outlast it.
        var sink = new BlockingSink();
        var lifecycle = new ProjectionSinkLifecycle(sink);

        var maintenance = new Thread(() -> lifecycle.use(s -> { sink.beginCommit(); return null; }), "maintenance");
        maintenance.start();
        assertThat(sink.entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(lifecycle.closeWhenIdle(Duration.ofMillis(100)))
                .as("a short wait against a long pass declines, it does not force")
                .isFalse();
        assertThat(sink.closeCalls.get()).isZero();
        assertThat(lifecycle.busy()).isTrue();

        sink.release.countDown();
        maintenance.join(5_000);
        assertThat(sink.closedWhileBusy.get()).isFalse();
    }
}
