package com.bloxbean.cardano.yano.archive.core.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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
}
