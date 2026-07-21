package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Objects;

/** One message-free health result for a declared check id. */
public record PluginHealthReport(String checkId, PluginHealthStatus status) {
    public PluginHealthReport {
        checkId = PluginOperationsValidation.identifier(checkId, "health report checkId");
        status = Objects.requireNonNull(status, "status");
    }
}
