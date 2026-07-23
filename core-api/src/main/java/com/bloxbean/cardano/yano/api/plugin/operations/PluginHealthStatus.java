package com.bloxbean.cardano.yano.api.plugin.operations;

/** Cached, node-local health state. It is never a consensus or readiness input. */
public enum PluginHealthStatus {
    UNKNOWN,
    UP,
    DEGRADED,
    DOWN
}
