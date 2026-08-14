package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical, bounded values returned by one metrics source sample. */
public record PluginMetricSnapshot(Map<String, PluginMetricValue> values) {
    public PluginMetricSnapshot {
        Map<String, PluginMetricValue> sorted = new TreeMap<>();
        if (values != null) {
            if (values.size() > PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE) {
                throw new IllegalArgumentException(
                        "metric values must contain at most 64 entries");
            }
            values.forEach((id, value) -> {
                String validated = PluginOperationsValidation.identifier(
                        id, "metric snapshot id");
                if (sorted.put(validated, Objects.requireNonNull(
                        value, "metric values must not contain null")) != null) {
                    throw new IllegalArgumentException(
                            "metric values must not contain duplicate ids");
                }
            });
        }
        values = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
