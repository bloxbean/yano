package com.bloxbean.cardano.yano.archive.core.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveSubsystemTest {
    @Test
    void disabledSubsystemDoesNotStartOrInvokeWork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var subsystem = new ArchiveSubsystem(false, Duration.ofMillis(10), calls::incrementAndGet)) {
            subsystem.start();
            Thread.sleep(40);
            assertThat(subsystem.started()).isFalse();
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void enabledSubsystemContainsWorkerFailuresAndKeepsPolling() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var subsystem = new ArchiveSubsystem(true, Duration.ofMillis(10), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("fixture");
        })) {
            subsystem.start();
            Thread.sleep(55);
            assertThat(calls.get()).isGreaterThan(1);
        }
    }

    @Test
    void closeJoinsInFlightWorkBeforeReturning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        var subsystem = new ArchiveSubsystem(true, Duration.ofMillis(10), () -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Model bounded native work that cannot stop at the first interrupt.
                }
            }
        });
        subsystem.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        Thread.ofVirtual().start(() -> {
            subsystem.close();
            closed.countDown();
        });
        assertThat(closed.await(50, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aCycleFailureIsReportedInsteadOfBeingSwallowedSilently() throws Exception {
        List<Throwable> observed = new CopyOnWriteArrayList<>();
        try (var subsystem = new ArchiveSubsystem(true, Duration.ofMillis(10),
                () -> { throw new IllegalStateException("cycle blew up"); }, observed::add)) {
            subsystem.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (observed.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(observed).isNotEmpty();
            assertThat(observed.getFirst()).hasMessage("cycle blew up");
        }
    }

    @Test
    void stopReportsNonTerminationSoDependenciesAreNotDestroyedUnderALiveTask() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        var subsystem = new ArchiveSubsystem(true, Duration.ofMillis(10), () -> {
            entered.countDown();
            // Model a native call that ignores interruption past the join deadline.
            while (!release.get()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    // keep running
                }
            }
        }, failure -> { }, Duration.ofMillis(200));
        subsystem.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        // stop() must report the truth rather than throwing, because the caller
        // uses it to decide whether destroying the hot store is safe yet.
        assertThat(subsystem.stop())
                .as("a worker still inside a native call must not be reported as stopped").isFalse();
        assertThat(subsystem.terminated())
                .as("a still-running worker must never look terminated").isFalse();

        release.set(true);

        // Once the task exits, a retry terminates cleanly and is then idempotent.
        assertThat(subsystem.stop()).isTrue();
        assertThat(subsystem.terminated()).isTrue();
        assertThat(subsystem.stop()).isTrue();
        subsystem.close();
    }

    @Test
    void stopIsTrueAndCloseIsQuietWhenTheWorkerExitsNormally() {
        var subsystem = new ArchiveSubsystem(true, Duration.ofMillis(10), () -> { });
        subsystem.start();
        assertThat(subsystem.stop()).isTrue();
        assertThat(subsystem.terminated()).isTrue();
        subsystem.close();
        subsystem.close();
    }
}
