package com.bloxbean.cardano.yano.runtime.ledger;

import com.bloxbean.cardano.yano.ledgerstate.EpochBoundaryTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EpochCalcStatusMapperTest {

    @Test
    void exposesPhasePathsHeapRssAndRocksDbAttribution() {
        var before = snapshot(100, 200, 300, -1, -1);
        var after = snapshot(150, 220, 350, -1, -1);
        var phase = new EpochBoundaryTelemetry.PhaseSummary(
                "snapshot", "ordered-stake-index", 4_000_000, 3_000_000,
                2_000_000, -1, -1, before, after);
        var boundary = new EpochBoundaryTelemetry.BoundarySummary(
                651, 652, true, 5_000_000, before, after, after, List.of(phase));

        Map<String, Object> status = EpochCalcStatusMapper.map(null, boundary);

        assertThat(status.get("status")).isEqualTo("OK");
        Map<String, Object> lastBoundary = castMap(status.get("lastBoundary"));
        assertThat(lastBoundary)
                .containsEntry("newEpoch", 652)
                .containsEntry("wallMillis", 5L)
                .containsEntry("gcMetricsAvailable", false);

        List<Map<String, Object>> phases = castList(lastBoundary.get("phases"));
        assertThat(phases).singleElement().satisfies(value -> {
            assertThat(value)
                    .containsEntry("phase", "snapshot")
                    .containsEntry("path", "ordered-stake-index")
                    .containsEntry("wallMillis", 4L)
                    .doesNotContainKeys("gcCountDelta", "gcTimeMillisDelta");
            assertThat(castMap(value.get("after")))
                    .containsEntry("heapUsedBytes", 150L)
                    .containsEntry("rssBytes", 350L);
            assertThat(castMap(castMap(value.get("after")).get("rocksDb")))
                    .containsEntry("sstFileCount", 7L);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static EpochBoundaryTelemetry.ResourceSnapshot snapshot(long heapUsed,
                                                                    long heapCommitted,
                                                                    long rss,
                                                                    long gcCount,
                                                                    long gcTime) {
        return new EpochBoundaryTelemetry.ResourceSnapshot(
                heapUsed, heapCommitted, 1_000, rss, 10, 5, gcCount, gcTime,
                new EpochBoundaryTelemetry.RocksDbMemory(1, 2, 3, 4, 5, 7));
    }
}
