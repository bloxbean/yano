package org.yanoproject.app.api.addresses;

import org.yanoproject.api.account.AccountHistoryProvider;
import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.wallet.AddressFirstSeen;
import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.wallet.WalletIndexCoverage;
import org.yanoproject.api.wallet.WalletIndexUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.yanoproject.app.archive.HistoryArchiveService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AddressResourceTest {
    @Test
    void firstSeenDoesNotRequireArchiveAndPreservesNullAndZero() throws Exception {
        UtxoState state = mock(UtxoState.class);
        when(state.isEnabled()).thenReturn(true);
        var coverage = new WalletIndexCoverage(true, true, ChainPoint.ORIGIN,
                ChainPoint.ORIGIN, "test", null);
        when(state.getAddressFirstSeen("unused")).thenReturn(new AddressFirstSeen(null, coverage));
        when(state.getAddressFirstSeen("genesis")).thenReturn(new AddressFirstSeen(0L, coverage));
        var resource = firstSeenResource(state);
        assertThat(resource.getFirstSeen("unused").getStatus()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var unused = mapper.readTree(mapper.writeValueAsBytes(resource.getFirstSeen("unused").getEntity()));
        assertThat(unused.has("firstSeenSlot")).isTrue();
        assertThat(unused.get("firstSeenSlot").isNull()).isTrue();
        var genesis = mapper.readTree(mapper.writeValueAsBytes(resource.getFirstSeen("genesis").getEntity()));
        assertThat(genesis.get("firstSeenSlot").longValue()).isZero();
    }

    @Test
    void firstSeenIncompleteAndMalformedAreDistinctFromUnused() {
        UtxoState state = mock(UtxoState.class);
        when(state.isEnabled()).thenReturn(true);
        when(state.getAddressFirstSeen("incomplete")).thenThrow(new WalletIndexUnavailableException(
                new WalletIndexCoverage(true, false, null, null, null, "Fresh sync required")));
        when(state.getAddressFirstSeen("malformed")).thenThrow(new IllegalArgumentException("bad address"));
        var resource = firstSeenResource(state);
        assertThat(resource.getFirstSeen("incomplete").getStatus()).isEqualTo(503);
        assertThat(resource.getFirstSeen("malformed").getStatus()).isEqualTo(400);
    }

    private static AddressResource firstSeenResource(UtxoState state) {
        var resource = new AddressResource();
        resource.ledgerQuery = mock(LedgerQuery.class);
        when(resource.ledgerQuery.getUtxoState()).thenReturn(state);
        return resource;
    }

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
