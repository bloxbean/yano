package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Dynamic state for one bundle id from the immutable plugin catalog. */
public record PluginBundleRuntimeInfo(
        String id,
        PluginLifecycleState lifecycle,
        PluginHealthStatus health,
        PluginFailure failure,
        boolean metricsStale,
        long lastTransitionEpochMillis,
        int activeCallbacks,
        int queuedCallbacks,
        List<PluginOperationCount> operationCounts,
        List<PluginContributionRuntimeInfo> contributions
) {
    public static final int MAX_CONTRIBUTIONS = 256;

    public PluginBundleRuntimeInfo {
        id = PluginOperationsValidation.bundleId(id, "id");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        health = Objects.requireNonNull(health, "health");
        failure = Objects.requireNonNull(failure, "failure");
        PluginOperationsValidation.nonNegative(
                lastTransitionEpochMillis, "lastTransitionEpochMillis");
        PluginOperationsValidation.nonNegative(activeCallbacks, "activeCallbacks");
        PluginOperationsValidation.nonNegative(queuedCallbacks, "queuedCallbacks");
        operationCounts = operationCounts == null ? List.of() : operationCounts.stream()
                .map(count -> Objects.requireNonNull(
                        count, "operationCounts must not contain null"))
                .sorted(Comparator.comparing(PluginOperationCount::operation)
                        .thenComparing(PluginOperationCount::outcome))
                .toList();
        if (operationCounts.size() > PluginOperation.values().length
                * PluginOperationOutcome.values().length) {
            throw new IllegalArgumentException("operationCounts contains too many entries");
        }
        Set<String> operationKeys = new HashSet<>();
        for (PluginOperationCount count : operationCounts) {
            if (!operationKeys.add(count.operation() + "\u0000" + count.outcome())) {
                throw new IllegalArgumentException(
                        "operationCounts must not contain duplicate operation/outcome pairs");
            }
        }
        contributions = contributions == null ? List.of() : contributions.stream()
                .map(contribution -> Objects.requireNonNull(
                        contribution, "contributions must not contain null"))
                .sorted(Comparator.comparing(PluginContributionRuntimeInfo::kind)
                        .thenComparing(PluginContributionRuntimeInfo::name))
                .toList();
        if (contributions.size() > MAX_CONTRIBUTIONS) {
            throw new IllegalArgumentException(
                    "contributions must contain at most 256 entries");
        }
        Set<String> keys = new HashSet<>();
        for (PluginContributionRuntimeInfo contribution : contributions) {
            if (!keys.add(contribution.kind() + '\u0000' + contribution.name())) {
                throw new IllegalArgumentException(
                        "contributions must not contain duplicate kind/name pairs");
            }
        }
    }
}
