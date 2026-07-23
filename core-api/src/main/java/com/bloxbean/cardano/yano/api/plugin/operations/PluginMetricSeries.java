package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Objects;

/** One cached, bundle-owned custom metric series in an operations snapshot. */
public record PluginMetricSeries(
        String bundleId,
        PluginMetricDescriptor descriptor,
        PluginMetricValue value,
        boolean stale
) {
    public PluginMetricSeries {
        bundleId = PluginOperationsValidation.bundleId(bundleId, "bundleId");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        value = Objects.requireNonNull(value, "value");
        boolean compatible = switch (descriptor.type()) {
            case GAUGE -> value instanceof PluginGaugeValue;
            case COUNTER -> value instanceof PluginCounterValue;
            case TIMER -> value instanceof PluginTimerValue;
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "metric value type does not match its descriptor");
        }
    }
}
