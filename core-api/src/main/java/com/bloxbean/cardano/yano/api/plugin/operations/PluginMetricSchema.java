package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared fixed-schema validator used by providers and the runtime cache. */
public final class PluginMetricSchema {
    private PluginMetricSchema() {
    }

    public static List<PluginMetricDescriptor> validateAndOrder(
            List<PluginMetricDescriptor> descriptors
    ) {
        Objects.requireNonNull(descriptors, "descriptors");
        if (descriptors.size() > PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE) {
            throw new IllegalArgumentException(
                    "metric descriptors must contain at most 64 entries");
        }
        List<PluginMetricDescriptor> ordered = descriptors.stream()
                .map(descriptor -> Objects.requireNonNull(
                        descriptor, "metric descriptors must not contain null"))
                .sorted(Comparator.comparing(PluginMetricDescriptor::id))
                .toList();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (PluginMetricDescriptor descriptor : ordered) {
            if (!ids.add(descriptor.id())) {
                throw new IllegalArgumentException(
                        "metric descriptors must not contain duplicate ids");
            }
            if (!names.add(descriptor.name())) {
                throw new IllegalArgumentException(
                        "metric descriptors must not contain duplicate names");
            }
        }
        return ordered;
    }
}
