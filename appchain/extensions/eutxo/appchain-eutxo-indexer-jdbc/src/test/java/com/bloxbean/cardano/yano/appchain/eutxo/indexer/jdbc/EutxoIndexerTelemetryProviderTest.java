package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelContext;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginGaugeValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoIndexerTelemetryProviderTest {
    @Test
    void lifecycleOwnsBoundedHealthAndMetricsHandoff() throws Exception {
        EutxoLifecycleIndexerProvider lifecycle = new EutxoLifecycleIndexerProvider();
        LocalReadModelContext context = new LocalReadModelContext(
                "preview", Map.of(), List.of(), LocalReadModelHost.unavailable());

        try (AutoCloseable active = lifecycle.start(context);
             var health = new EutxoIndexerHealthProvider().create(
                     new PluginHealthContext(EutxoLifecycleIndexerProvider.ID, Map.of()));
             var metrics = new EutxoIndexerMetricsProvider().create(
                     new PluginMetricsContext(EutxoLifecycleIndexerProvider.ID, Map.of()))) {
            assertThat(health.snapshot().reports()).singleElement()
                    .extracting(report -> report.status())
                    .isEqualTo(PluginHealthStatus.UP);
            assertThat(metrics.descriptors()).hasSize(9);
            assertThat(metrics.snapshot().values()).hasSize(9);
            assertThat(metrics.snapshot().values().get("rebuild-progress"))
                    .isEqualTo(new PluginGaugeValue(1d));
            assertThatThrownBy(() -> lifecycle.start(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("telemetry is already active");
        }

        try (AutoCloseable restarted = lifecycle.start(context)) {
            assertThat(EutxoIndexerTelemetry.samples()).isEmpty();
        }
    }
}
