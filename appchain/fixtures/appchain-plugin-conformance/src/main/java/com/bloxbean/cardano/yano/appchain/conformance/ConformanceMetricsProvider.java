package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricType;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded custom-metrics fixture used by packaged JVM and native smokes. */
public final class ConformanceMetricsProvider implements PluginMetricsProvider {
    public static final String ID = NativePluginConformanceVerifier.BUNDLE_ID;
    public static final String METRIC_ID = "samples";

    @Override
    public String id() {
        ConformanceTcclProbe.requireCatalogFacade("metrics provider identity");
        return ID;
    }

    @Override
    public PluginMetricsSource create(PluginMetricsContext context) {
        if (!ID.equals(context.bundleId()) || !context.bundleConfig().isEmpty()) {
            throw new IllegalArgumentException("unexpected conformance metrics context");
        }
        PluginMetricsSource source = new PluginMetricsSource() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);
            private final AtomicLong samples = new AtomicLong();

            @Override
            public List<PluginMetricDescriptor> descriptors() {
                ConformanceTcclProbe.productCallback(
                        firstCallback, "metric descriptor publication");
                return List.of(new PluginMetricDescriptor(
                        METRIC_ID, "samples", PluginMetricType.COUNTER,
                        "Conformance metric samples", "samples"));
            }

            @Override
            public PluginMetricSnapshot snapshot() {
                ConformanceTcclProbe.productCallback(firstCallback, "metrics sample");
                return new PluginMetricSnapshot(Map.of(
                        METRIC_ID, new PluginCounterValue(samples.incrementAndGet())));
            }

            @Override
            public void close() {
                ConformanceTcclProbe.productCallback(firstCallback, "metrics close");
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return source;
    }
}
