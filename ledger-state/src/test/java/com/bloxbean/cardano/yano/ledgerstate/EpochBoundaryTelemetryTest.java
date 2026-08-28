package com.bloxbean.cardano.yano.ledgerstate;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EpochBoundaryTelemetryTest {

    @Test
    void recordsPhaseDeltasAndBoundaryPeaks() {
        var clock = new AtomicLong();
        var samples = new ArrayDeque<EpochBoundaryTelemetry.ResourceSnapshot>();
        samples.add(snapshot(100, 200, 300, 10, 2, 3));
        samples.add(snapshot(110, 210, 310, 20, 3, 5));
        samples.add(snapshot(180, 260, 390, 30, 5, 9));
        samples.add(snapshot(150, 240, 350, 40, 6, 11));

        var run = EpochBoundaryTelemetry.start(mock(Logger.class), 100, 101,
                clock::get, samples::removeFirst);

        clock.set(1_000_000L);
        try (var ignored = run.phase("rewards", "legacy-reward")) {
            clock.set(6_000_000L);
        }
        clock.set(10_000_000L);
        var summary = run.finish(true);

        assertThat(summary.previousEpoch()).isEqualTo(100);
        assertThat(summary.newEpoch()).isEqualTo(101);
        assertThat(summary.success()).isTrue();
        assertThat(summary.wallNanos()).isEqualTo(10_000_000L);
        assertThat(summary.phases()).hasSize(1);

        var phase = summary.phases().getFirst();
        assertThat(phase.phase()).isEqualTo("rewards");
        assertThat(phase.path()).isEqualTo("legacy-reward");
        assertThat(phase.wallNanos()).isEqualTo(5_000_000L);
        assertThat(phase.cpuNanos()).isEqualTo(10L);
        assertThat(phase.gcCountDelta()).isEqualTo(2L);
        assertThat(phase.gcTimeMillisDelta()).isEqualTo(4L);

        assertThat(summary.peak().heapUsedBytes()).isEqualTo(180L);
        assertThat(summary.peak().rssBytes()).isEqualTo(390L);
        assertThat(summary.peak().rocksDb().memtableBytes()).isEqualTo(30L);
        assertThat(summary.peak().rocksDb().pendingCompactionBytes()).isEqualTo(60L);
        assertThat(run.finish(false)).isSameAs(summary);
    }

    @Test
    void samplerFailureDoesNotHideBoundaryFailure() {
        var clock = new AtomicLong();
        var run = EpochBoundaryTelemetry.start(mock(Logger.class), 10, 11,
                clock::get, () -> {
                    throw new IllegalStateException("metrics unavailable");
                });

        clock.set(2_000_000L);
        try (var ignored = run.phase("params")) {
            clock.set(3_000_000L);
        }
        var summary = run.finish(false);

        assertThat(summary.success()).isFalse();
        assertThat(summary.start().heapUsedBytes()).isEqualTo(-1L);
        assertThat(summary.end().rocksDb().memtableBytes()).isEqualTo(-1L);
        assertThat(summary.phases()).singleElement()
                .extracting(EpochBoundaryTelemetry.PhaseSummary::phase)
                .isEqualTo("params");
    }

    private static EpochBoundaryTelemetry.ResourceSnapshot snapshot(long heapUsed,
                                                                    long heapCommitted,
                                                                    long rss,
                                                                    long cpu,
                                                                    long gcCount,
                                                                    long gcTime) {
        return new EpochBoundaryTelemetry.ResourceSnapshot(
                heapUsed,
                heapCommitted,
                1_000L,
                rss,
                cpu,
                gcCount,
                gcTime,
                new EpochBoundaryTelemetry.RocksDbMemory(
                        heapUsed / 10,
                        heapUsed / 20,
                        heapUsed / 6,
                        heapUsed / 5,
                        heapUsed / 3));
    }
}
