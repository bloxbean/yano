package com.bloxbean.cardano.yano.api.plugin.operations;

import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Dynamic state joined to one immutable catalog contribution. */
public record PluginContributionRuntimeInfo(
        String kind,
        String name,
        PluginTrustTier trustTier,
        boolean lifecycleObserved,
        PluginLifecycleState lifecycle,
        PluginHealthStatus health,
        PluginFailure failure,
        boolean stale,
        List<PluginInstanceRuntimeInfo> instances
) {
    public static final int MAX_INSTANCES = 256;

    public PluginContributionRuntimeInfo {
        kind = PluginOperationsValidation.boundedAscii(kind, "kind", 64);
        name = PluginOperationsValidation.boundedAscii(name, "name", 160);
        trustTier = Objects.requireNonNull(trustTier, "trustTier");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        health = Objects.requireNonNull(health, "health");
        failure = Objects.requireNonNull(failure, "failure");
        instances = instances == null ? List.of() : instances.stream()
                .map(instance -> Objects.requireNonNull(
                        instance, "instances must not contain null"))
                .sorted(Comparator.comparing(PluginInstanceRuntimeInfo::scope))
                .toList();
        if (instances.size() > MAX_INSTANCES) {
            throw new IllegalArgumentException("instances must contain at most 256 entries");
        }
        Set<String> scopes = new HashSet<>();
        for (PluginInstanceRuntimeInfo instance : instances) {
            if (!scopes.add(instance.scope())) {
                throw new IllegalArgumentException("instances must not contain duplicate scopes");
            }
        }
        if (!lifecycleObserved) {
            if (lifecycle != PluginLifecycleState.VALIDATED
                    && lifecycle != PluginLifecycleState.NOT_SELECTED) {
                throw new IllegalArgumentException(
                        "unobserved contribution lifecycle must be VALIDATED or NOT_SELECTED");
            }
            if (health != PluginHealthStatus.UNKNOWN
                    || failure.code() != PluginFailureCode.NONE
                    || stale
                    || !instances.isEmpty()) {
                throw new IllegalArgumentException(
                        "unobserved contribution must not claim dynamic runtime state");
            }
        }
        for (PluginInstanceRuntimeInfo instance : instances) {
            if (instance.lifecycle() != lifecycle
                    || instance.health() != health
                    || !instance.failure().equals(failure)
                    || instance.stale() != stale) {
                throw new IllegalArgumentException(
                        "instance runtime state must match its contribution summary in API v1");
            }
        }
    }
}
