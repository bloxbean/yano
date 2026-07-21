package com.bloxbean.cardano.yano.api.plugin.operations;

/** One finite gauge sample. */
public record PluginGaugeValue(double value) implements PluginMetricValue {
    public PluginGaugeValue {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("gauge value must be finite");
        }
    }
}
