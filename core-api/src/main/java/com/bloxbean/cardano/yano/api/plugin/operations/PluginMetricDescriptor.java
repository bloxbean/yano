package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Objects;

/**
 * Fixed custom metric schema. Plugins cannot contribute labels in v1; the
 * host owns the bundle label and namespace.
 */
public record PluginMetricDescriptor(
        String id,
        String name,
        PluginMetricType type,
        String description,
        String baseUnit
) {
    public static final int MAX_SERIES_PER_BUNDLE = 64;
    public static final int MAX_SERIES_HOST_WIDE = 4_096;

    public PluginMetricDescriptor {
        id = PluginOperationsValidation.identifier(id, "metric id");
        name = PluginOperationsValidation.metricName(name);
        type = Objects.requireNonNull(type, "type");
        description = PluginOperationsValidation.description(
                description, "metric description");
        baseUnit = PluginOperationsValidation.metricUnit(baseUnit);
    }
}
