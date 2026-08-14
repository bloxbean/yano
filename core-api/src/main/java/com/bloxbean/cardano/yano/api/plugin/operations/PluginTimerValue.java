package com.bloxbean.cardano.yano.api.plugin.operations;

/** One cumulative timer sample. */
public record PluginTimerValue(long count, long totalNanos) implements PluginMetricValue {
    public PluginTimerValue {
        PluginOperationsValidation.nonNegative(count, "timer count");
        PluginOperationsValidation.nonNegative(totalNanos, "timer totalNanos");
        if (count == 0 && totalNanos != 0) {
            throw new IllegalArgumentException(
                    "timer totalNanos must be zero when count is zero");
        }
    }
}
