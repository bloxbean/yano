package com.bloxbean.cardano.yano.api.plugin.operations;

/** Fixed identity and static operator description for one plugin health check. */
public record PluginHealthCheckDescriptor(String id, String description) {
    public static final int MAX_CHECKS_PER_BUNDLE = 16;
    public static final int MAX_CHECKS_HOST_WIDE = 512;

    public PluginHealthCheckDescriptor {
        id = PluginOperationsValidation.identifier(id, "health check id");
        description = PluginOperationsValidation.description(
                description, "health check description");
    }
}
