package com.bloxbean.cardano.yano.app.api.addresses;

import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AddressResourceTest {
    @Test
    void unavailableProjectionDatasetIsReportedWithoutLegacyWorkerState() {
        AccountHistoryProvider provider = mock(AccountHistoryProvider.class);
        when(provider.isEnabled()).thenReturn(true);
        HistoryArchiveService history = mock(HistoryArchiveService.class);
        when(history.enabled()).thenReturn(true);
        when(history.accountHistoryProvider()).thenReturn(provider);

        var resource = new AddressResource();
        resource.historyArchive = history;

        var response = resource.getAddressTransactions("addr_test1fixture", 1, 20, "desc", false);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getEntity()).isEqualTo(
                Map.of("error", "Address transaction history is unavailable or not selected "
                        + "(set yano.history.projection.enabled=true; if "
                        + "yano.history.projection.sections is set it must include "
                        + "address-transaction:v1)"));
    }

    @Test
    void incompleteColdLiveCoverageReturnsServiceUnavailableInsteadOfInternalError() {
        AccountHistoryProvider provider = mock(AccountHistoryProvider.class);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isAddressTxEnabled()).thenReturn(true);
        when(provider.getAddressTransactionsForAddress("addr_test1fixture", false, 1, 20, "desc"))
                .thenThrow(new IllegalStateException("cold/live gap"));
        HistoryArchiveService history = mock(HistoryArchiveService.class);
        when(history.enabled()).thenReturn(true);
        when(history.accountHistoryProvider()).thenReturn(provider);

        var resource = new AddressResource();
        resource.historyArchive = history;

        var response = resource.getAddressTransactions("addr_test1fixture", 1, 20, "desc", false);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getEntity()).isEqualTo(Map.of("error", "Address history read failed"));
    }
}
