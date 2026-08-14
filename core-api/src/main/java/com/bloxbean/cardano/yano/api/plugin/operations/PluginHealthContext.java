package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Map;

/** Construction context for one manifested health source. */
public record PluginHealthContext(String bundleId, Map<String, Object> bundleConfig) {
    public PluginHealthContext {
        bundleId = PluginOperationsValidation.bundleId(bundleId, "bundleId");
        if (bundleConfig != null && !bundleConfig.isEmpty()) {
            throw new IllegalArgumentException(
                    "plugin health configuration is unavailable in contract v1");
        }
        bundleConfig = Map.of();
    }
}
