package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Objects;

/**
 * Cached node-local state for one fixed plugin health check.
 *
 * <p>The descriptor is activation-frozen operator metadata. A stale entry
 * retains the last valid status; before the first valid sample its status is
 * {@link PluginHealthStatus#UNKNOWN}.</p>
 */
public record PluginHealthCheckRuntimeInfo(
        String bundleId,
        PluginHealthCheckDescriptor descriptor,
        PluginHealthStatus status,
        boolean stale
) {
    public PluginHealthCheckRuntimeInfo {
        bundleId = PluginOperationsValidation.bundleId(bundleId, "bundleId");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        status = Objects.requireNonNull(status, "status");
    }
}
