package com.bloxbean.cardano.yano.api.plugin.operations;

/** Closed set of custom metric values accepted by the host cache. */
public sealed interface PluginMetricValue
        permits PluginGaugeValue, PluginCounterValue, PluginTimerValue {
}
