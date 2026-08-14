package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Map;

/** Construction context for one manifested metrics source. */
public record PluginMetricsContext(String bundleId, Map<String, Object> bundleConfig) {
    public PluginMetricsContext {
        bundleId = PluginOperationsValidation.bundleId(bundleId, "bundleId");
        if (bundleConfig != null && !bundleConfig.isEmpty()) {
            throw new IllegalArgumentException(
                    "plugin metrics configuration is unavailable in contract v1");
        }
        bundleConfig = Map.of();
    }
}
