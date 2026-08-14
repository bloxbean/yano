package com.bloxbean.cardano.yano.api.plugin.operations;

/** One finite, non-negative cumulative counter sample. */
public record PluginCounterValue(double total) implements PluginMetricValue {
    public PluginCounterValue {
        if (!Double.isFinite(total) || total < 0) {
            throw new IllegalArgumentException(
                    "counter total must be finite and non-negative");
        }
    }
}
