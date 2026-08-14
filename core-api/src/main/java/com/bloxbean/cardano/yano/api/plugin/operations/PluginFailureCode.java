package com.bloxbean.cardano.yano.api.plugin.operations;

/** Stable host-owned failure categories safe for operations surfaces. */
public enum PluginFailureCode {
    NONE,
    ACTIVATION_FAILED,
    START_FAILED,
    CALLBACK_FAILED,
    CHECK_TIMEOUT,
    CHECK_FAILED,
    INVALID_HEALTH_SNAPSHOT,
    METRICS_TIMEOUT,
    METRICS_FAILED,
    INVALID_METRIC_SNAPSHOT,
    STOP_FAILED,
    CLOSE_FAILED
}
