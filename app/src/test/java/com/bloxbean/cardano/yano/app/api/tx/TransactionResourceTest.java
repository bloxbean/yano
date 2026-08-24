package com.bloxbean.cardano.yano.app.api.tx;

import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.app.api.tx.dto.TxStatusDto;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionResourceTest {
    @Test
    void pendingStatusDoesNotQueryColdArchive() {
        String hash = "ab".repeat(32);
        TxGateway gateway = mock(TxGateway.class);
        HistoryArchiveService history = mock(HistoryArchiveService.class);
        when(gateway.isTransactionInMemPool(hash)).thenReturn(true);

        var resource = new TransactionResource();
        resource.txGateway = gateway;
        resource.historyArchive = history;

        var response = resource.getTxStatus(hash);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isInstanceOf(TxStatusDto.class);
        assertThat(((TxStatusDto) response.getEntity()).status()).isEqualTo("pending");
        verifyNoInteractions(history);
    }
}
