package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionActivationException;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
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
