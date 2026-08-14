package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared fixed-schema validator used by providers and the runtime cache. */
public final class PluginHealthSchema {
    private PluginHealthSchema() {
    }

    public static List<PluginHealthCheckDescriptor> validateAndOrder(
            List<PluginHealthCheckDescriptor> descriptors
    ) {
        Objects.requireNonNull(descriptors, "descriptors");
        if (descriptors.size() > PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE) {
            throw new IllegalArgumentException(
                    "health check descriptors must contain at most 16 entries");
        }
        List<PluginHealthCheckDescriptor> ordered = descriptors.stream()
                .map(descriptor -> Objects.requireNonNull(
                        descriptor, "health check descriptors must not contain null"))
                .sorted(Comparator.comparing(PluginHealthCheckDescriptor::id))
                .toList();
        Set<String> ids = new HashSet<>();
        for (PluginHealthCheckDescriptor descriptor : ordered) {
            if (!ids.add(descriptor.id())) {
                throw new IllegalArgumentException(
                        "health check descriptors must not contain duplicate ids");
            }
        }
        return ordered;
    }
}
