package com.bloxbean.cardano.yano.archive.core.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
}
