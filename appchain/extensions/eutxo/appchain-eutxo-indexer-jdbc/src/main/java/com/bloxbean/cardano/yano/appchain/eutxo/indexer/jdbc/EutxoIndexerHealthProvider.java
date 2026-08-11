package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthReport;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexHealth;

import java.util.List;

/** Cached host-operations health projection for the optional JDBC indexer. */
public final class EutxoIndexerHealthProvider implements PluginHealthProvider {
    private static final String CHECK_ID = "indexer";
    private static final PluginHealthCheckDescriptor CHECK =
            new PluginHealthCheckDescriptor(
                    CHECK_ID, "Aggregate EUTxO local index health");

    @Override
    public String id() {
        return EutxoLifecycleIndexerProvider.ID;
    }

    @Override
    public PluginHealthSource create(PluginHealthContext context) {
        return new PluginHealthSource() {
            @Override
            public List<PluginHealthCheckDescriptor> checks() {
                return List.of(CHECK);
            }

            @Override
            public PluginHealthSnapshot snapshot() {
                PluginHealthStatus status = EutxoIndexerTelemetry.samples().stream()
                        .map(sample -> status(sample.coordinator().health()))
                        .reduce(PluginHealthStatus.UP, EutxoIndexerHealthProvider::worse);
                return new PluginHealthSnapshot(List.of(
                        new PluginHealthReport(CHECK_ID, status)));
            }

            @Override
            public void close() {
            }
        };
    }

    private static PluginHealthStatus status(IndexHealth health) {
        return switch (health.status()) {
            case READY -> PluginHealthStatus.UP;
            case CATCHING_UP, REBUILDING -> PluginHealthStatus.DEGRADED;
            case FAILED, IDENTITY_MISMATCH -> PluginHealthStatus.DOWN;
        };
    }

    private static PluginHealthStatus worse(
            PluginHealthStatus left,
            PluginHealthStatus right
    ) {
        return severity(left) >= severity(right) ? left : right;
    }

    private static int severity(PluginHealthStatus status) {
        return switch (status) {
            case UP -> 0;
            case UNKNOWN -> 1;
            case DEGRADED -> 2;
            case DOWN -> 3;
        };
    }
}
