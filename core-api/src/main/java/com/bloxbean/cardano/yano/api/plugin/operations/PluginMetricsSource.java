package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.List;

/** Lifecycle-owned source whose callbacks run only behind the runtime sampler fence. */
public interface PluginMetricsSource extends AutoCloseable {
    /** Complete fixed metric schema, frozen by the runtime on activation. */
    List<PluginMetricDescriptor> descriptors();

    /** Current values; the runtime validates identity, type, and monotonicity. */
    PluginMetricSnapshot snapshot();

    @Override
    void close();
}
