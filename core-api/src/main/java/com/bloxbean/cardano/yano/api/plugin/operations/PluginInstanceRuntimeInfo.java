package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Objects;

/** Runtime status for one host-owned scope such as {@code node} or {@code chain:<id>}. */
public record PluginInstanceRuntimeInfo(
        String scope,
        PluginLifecycleState lifecycle,
        PluginHealthStatus health,
        PluginFailure failure,
        boolean stale
) {
    public PluginInstanceRuntimeInfo {
        scope = PluginOperationsValidation.boundedAscii(scope, "scope", 192);
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        health = Objects.requireNonNull(health, "health");
        failure = Objects.requireNonNull(failure, "failure");
    }
}
