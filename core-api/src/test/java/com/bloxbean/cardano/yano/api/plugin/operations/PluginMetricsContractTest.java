package com.bloxbean.cardano.yano.api.plugin.operations;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginMetricsContractTest {

    @Test
    void descriptorHasNoPluginLabelSurfaceAndEnforcesBounds() {
        PluginMetricDescriptor descriptor = new PluginMetricDescriptor(
                "requests", "requests.total", PluginMetricType.COUNTER,
                "Completed requests", "requests");

        assertThat(descriptor.id()).isEqualTo("requests");
        assertThat(descriptor.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "name", "type", "description", "baseUnit");
        assertThat(new PluginMetricDescriptor(
                "a", "a", PluginMetricType.GAUGE,
                "x".repeat(256), "").description()).hasSize(256);
        assertThat(new PluginMetricDescriptor(
                "a" + "b".repeat(63), "a" + "b".repeat(99),
                PluginMetricType.GAUGE, "safe", "a" + "b".repeat(31)).name())
                .hasSize(100);

        assertThatThrownBy(() -> new PluginMetricDescriptor(
                "requests", "yano.plugin.requests", PluginMetricType.COUNTER,
                "reserved", "requests"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
        assertThatThrownBy(() -> new PluginMetricDescriptor(
                "requests", "requests.total", PluginMetricType.COUNTER,
                "bad\ntext", "requests"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("printable ASCII");
        assertThatThrownBy(() -> new PluginMetricDescriptor(
                "requests", "requests.total", PluginMetricType.COUNTER,
                "safe", "x".repeat(33)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
        assertThatThrownBy(() -> new PluginMetricDescriptor(
                "requests", "a" + "b".repeat(100), PluginMetricType.COUNTER,
                "safe", "requests"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void metricValuesRejectNonFiniteAndNegativeValues() {
        assertThat(new PluginGaugeValue(-10.5).value()).isEqualTo(-10.5);
        assertThat(new PluginCounterValue(0).total()).isZero();
        assertThat(new PluginTimerValue(2, 99).totalNanos()).isEqualTo(99);

        assertThatThrownBy(() -> new PluginGaugeValue(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginCounterValue(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginCounterValue(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginTimerValue(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginTimerValue(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginTimerValue(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be zero");
    }

    @Test
    void schemaCanonicalizesAndRejectsDuplicateIdsOrExportedNames() {
        PluginMetricDescriptor alpha = descriptor("alpha", "shared");
        PluginMetricDescriptor zeta = descriptor("zeta", "zeta");

        assertThat(PluginMetricSchema.validateAndOrder(java.util.List.of(zeta, alpha)))
                .extracting(PluginMetricDescriptor::id)
                .containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> PluginMetricSchema.validateAndOrder(java.util.List.of(
                alpha, descriptor("alpha", "other"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate ids");
        assertThatThrownBy(() -> PluginMetricSchema.validateAndOrder(java.util.List.of(
                alpha, descriptor("other", "shared"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate names");

        java.util.List<PluginMetricDescriptor> exactLimit =
                java.util.stream.IntStream.range(0, 64)
                        .mapToObj(index -> descriptor(
                                "metric." + index, "metric." + index))
                        .toList();
        assertThat(PluginMetricSchema.validateAndOrder(exactLimit)).hasSize(64);
        java.util.List<PluginMetricDescriptor> overLimit =
                new java.util.ArrayList<>(exactLimit);
        overLimit.add(descriptor("metric.64", "metric.64"));
        assertThatThrownBy(() -> PluginMetricSchema.validateAndOrder(overLimit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void snapshotCanonicalizesDefensivelyCopiesAndEnforcesSeriesLimit() {
        Map<String, PluginMetricValue> source = new LinkedHashMap<>();
        source.put("zeta", new PluginGaugeValue(2));
        source.put("alpha", new PluginCounterValue(1));

        PluginMetricSnapshot snapshot = new PluginMetricSnapshot(source);
        source.clear();

        assertThat(snapshot.values().keySet()).containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> snapshot.values().put(
                "next", new PluginGaugeValue(1)))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, PluginMetricValue> exactLimit = new LinkedHashMap<>();
        for (int index = 0; index < 64; index++) {
            exactLimit.put("metric." + index, new PluginGaugeValue(index));
        }
        assertThat(new PluginMetricSnapshot(exactLimit).values()).hasSize(64);
        exactLimit.put("metric.64", new PluginGaugeValue(64));
        assertThatThrownBy(() -> new PluginMetricSnapshot(exactLimit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void contextIsIdentityOnlyAndSeriesRequiresDescriptorValueAgreement() {
        PluginMetricsContext context = new PluginMetricsContext(
                "com.example.metrics", Map.of());
        PluginMetricDescriptor counter = new PluginMetricDescriptor(
                "requests", "requests", PluginMetricType.COUNTER,
                "Requests", "requests");

        assertThat(context.bundleConfig()).isEmpty();
        assertThat(new PluginMetricSeries(
                context.bundleId(), counter, new PluginCounterValue(1), false).stale())
                .isFalse();
        assertThatThrownBy(() -> new PluginMetricSeries(
                context.bundleId(), counter, new PluginGaugeValue(1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> new PluginMetricsContext(
                context.bundleId(), Map.of("endpoint", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
    }

    private static PluginMetricDescriptor descriptor(String id, String name) {
        return new PluginMetricDescriptor(
                id, name, PluginMetricType.GAUGE, "Metric", "");
    }
}
