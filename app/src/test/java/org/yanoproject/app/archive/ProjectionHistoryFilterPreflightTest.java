package org.yanoproject.app.archive;

import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.archive.core.projection.ProjectionActivationException;
import org.yanoproject.runtime.assembly.Yano;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectionHistoryFilterPreflightTest {

    @Test
    void addressTransactionProjectionRefusesConfiguredBuiltInOrPluginFiltersBeforeOpeningState() {
        Config config = mock(Config.class);
        when(config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_ENABLED, Boolean.class))
                .thenReturn(Optional.of(true));
        when(config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_SECTIONS, String.class))
                .thenReturn(Optional.of("address-transaction:v1"));
        Yano yano = mock(Yano.class);
        when(yano.configuredUtxoStorageFilters())
                .thenReturn(List.of("built-in-address@100", "plugin-wallet@50"));

        assertThatThrownBy(() -> new ProjectionHistoryService(config).initialize(
                yano, mock(ChainQuery.class), mock(LedgerQuery.class), YanoConfig.serverOnly(0)))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("complete UTXO store")
                .hasMessageContaining("plugin-wallet");
    }
}
