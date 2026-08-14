package com.bloxbean.cardano.yano.api.plugin.operations;

/** Manifest-only ServiceLoader factory for one bundle-owned metrics source. */
public interface PluginMetricsProvider {
    /** Stable id equal to the manifest contribution name and owning bundle id. */
    String id();

    /** Creates one lifecycle-owned metrics source. */
    PluginMetricsSource create(PluginMetricsContext context);
}
