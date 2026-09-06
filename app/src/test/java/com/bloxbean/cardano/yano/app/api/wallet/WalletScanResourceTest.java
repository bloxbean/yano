package com.bloxbean.cardano.yano.app.api.wallet;

import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.api.wallet.WalletScan;
import com.bloxbean.cardano.yano.api.wallet.WalletScanEvent;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRequest;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRollbackException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletScanResourceTest {
    private final WalletScanRequest request = new WalletScanRequest(1,
            List.of(new WalletCredential("stake", "key", "01".repeat(28))), WalletChainPoint.ORIGIN, null, null);

    @Test void emitsNdjsonCompletionAndClosesBothSessions() throws Exception {
        WalletScan preflight = mock(WalletScan.class);
        WalletScan streaming = mock(WalletScan.class);
        when(streaming.finished()).thenReturn(false, true);
        when(streaming.next()).thenReturn(List.of(WalletScanEvent.progress("done", WalletChainPoint.ORIGIN)));
        WalletScanResource resource = resource(preflight, streaming);
        var response = resource.scan(request);
        assertThat(response.getStatus()).isEqualTo(200);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ((StreamingOutput) response.getEntity()).write(bytes);
        assertThat(bytes.toString()).endsWith("\n").contains("\"type\":\"done\"");
        verify(preflight).close();
        verify(streaming).close();
    }

    @Test void rollbackStreamContainsNoDone() throws Exception {
        WalletScan streaming = mock(WalletScan.class);
        when(streaming.next()).thenThrow(new WalletScanRollbackException("reorg"));
        WalletScanResource resource = resource(mock(WalletScan.class), streaming);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ((StreamingOutput) resource.scan(request).getEntity()).write(bytes);
        assertThat(bytes.toString()).contains("\"type\":\"rollback\"").doesNotContain("\"type\":\"done\"");
        verify(streaming).close();
    }

    @Test void disconnectClosesSession() {
        WalletScan streaming = mock(WalletScan.class);
        when(streaming.next()).thenReturn(List.of(WalletScanEvent.progress("progress", WalletChainPoint.ORIGIN)));
        WalletScanResource resource = resource(mock(WalletScan.class), streaming);
        StreamingOutput stream = (StreamingOutput) resource.scan(request).getEntity();
        assertThatThrownBy(() -> stream.write(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("disconnected"); }
        })).isInstanceOf(IOException.class);
        verify(streaming).close();
    }

    @Test void staleCursorReturnsConflictBeforeStreaming() {
        UtxoState state = mock(UtxoState.class);
        when(state.isEnabled()).thenReturn(true);
        when(state.openWalletScan(request)).thenThrow(new WalletScanRollbackException("stale cursor"));
        WalletScanResource resource = new WalletScanResource();
        resource.ledgerQuery = mock(LedgerQuery.class);
        when(resource.ledgerQuery.getUtxoState()).thenReturn(state);
        assertThat(resource.scan(request).getStatus()).isEqualTo(409);
    }

    private WalletScanResource resource(WalletScan preflight, WalletScan streaming) {
        UtxoState state = mock(UtxoState.class);
        when(state.isEnabled()).thenReturn(true);
        when(state.openWalletScan(request)).thenReturn(preflight, streaming);
        WalletScanResource resource = new WalletScanResource();
        resource.ledgerQuery = mock(LedgerQuery.class);
        when(resource.ledgerQuery.getUtxoState()).thenReturn(state);
        resource.mapper = new ObjectMapper();
        resource.initialize();
        return resource;
    }
}
