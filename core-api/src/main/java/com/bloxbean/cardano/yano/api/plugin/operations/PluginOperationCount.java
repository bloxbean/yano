package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Objects;

/** One bounded per-bundle platform telemetry counter. */
public record PluginOperationCount(
        PluginOperation operation,
        PluginOperationOutcome outcome,
        long total
) {
    public PluginOperationCount {
        operation = Objects.requireNonNull(operation, "operation");
        outcome = Objects.requireNonNull(outcome, "outcome");
        PluginOperationsValidation.nonNegative(total, "total");
    }
}
