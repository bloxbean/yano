package com.example.appchain;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricType;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Example bounded custom metric source for the counter plugin. */
public final class CounterMetricsProvider implements PluginMetricsProvider {
    public static final String BUNDLE_ID = "com.example.appchain.counter";
    private static final String METRIC_ID = "samples";

    @Override
    public String id() {
        return BUNDLE_ID;
    }

    @Override
    public PluginMetricsSource create(PluginMetricsContext context) {
        if (!BUNDLE_ID.equals(context.bundleId())) {
            throw new IllegalArgumentException("unexpected plugin bundle identity");
        }
        return new PluginMetricsSource() {
            private final AtomicLong samples = new AtomicLong();

            @Override
            public List<PluginMetricDescriptor> descriptors() {
                return List.of(new PluginMetricDescriptor(
                        METRIC_ID, "samples", PluginMetricType.COUNTER,
                        "Counter plugin metric samples", "samples"));
            }

            @Override
            public PluginMetricSnapshot snapshot() {
                return new PluginMetricSnapshot(Map.of(
                        METRIC_ID, new PluginCounterValue(samples.incrementAndGet())));
            }

            @Override
            public void close() {
            }
        };
    }
}
