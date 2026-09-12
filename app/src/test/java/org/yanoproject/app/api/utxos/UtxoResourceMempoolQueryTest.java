package org.yanoproject.app.api.utxos;

import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.MempoolQueryGateway;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.app.api.utxos.dto.UtxoDto;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class UtxoResourceMempoolQueryTest {
    @Test
    void listingsUseOverlayOnlyWhenRequestedAndPreserveAllSelectors() {
        UtxoState state = mock(UtxoState.class);
        when(state.isEnabled()).thenReturn(true);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(ledger.getUtxoState()).thenReturn(state);
        MempoolQueryGateway mempool = mock(MempoolQueryGateway.class);
        UtxoResource resource = new UtxoResource();
        resource.ledgerQuery = ledger;
        resource.mempoolQueryGateway = mempool;
        assertThat(resource.getUtxosByAddress("address", 1, 101, "asc", false, true).getStatus()).isEqualTo(400);
        assertThat(resource.getUtxosByAddressAndAsset("address", "asset", 1, 101, "asc", true).getStatus()).isEqualTo(400);
        assertThat(resource.getUtxosByPaymentCredential("credential", 1, 101, "asc", false).getStatus()).isEqualTo(400);
        verifyNoInteractions(mempool);
        resource.getUtxosByAddress("address", 1, 20, "asc", false, false);
        resource.getUtxosByAddressAndAsset("address", "lovelace", 1, 20, "asc", false);
        resource.getUtxosByPaymentCredential("credential", 1, 20, "asc", false);
        verifyNoInteractions(mempool);

        resource.getUtxosByAddress("address", 2, 10, "desc", false, true);
        resource.getUtxosByAddress("credential", 2, 10, "asc", true, true);
        resource.getUtxosByAddressAndAsset("address", "asset", 2, 10, "asc", true);
        resource.getUtxosByPaymentCredential("credential", 0, 0, "asc", true);
        verify(mempool).listUtxos("address", false, null, 2, 10, true);
        verify(mempool).listUtxos("credential", true, null, 2, 10, false);
        verify(mempool).listUtxos("address", false, "asset", 2, 10, false);
        verify(mempool).listUtxos("credential", true, null, 1, 20, false);

        resource.mempoolQueryGateway = MempoolQueryGateway.UNAVAILABLE;
        assertThat(resource.getUtxosByAddress("address", 1, 20, "asc", false, true).getStatus())
                .isEqualTo(503);
    }

    @Test
    void pointLookupUsesTransientViewOnlyWhenRequested() {
        Outpoint outpoint = new Outpoint("11".repeat(32), 0);
        Utxo canonical = utxo(outpoint, 1_000_000);
        Utxo transientView = utxo(outpoint, 2_000_000);
        UtxoState state = mock(UtxoState.class);
        when(state.isEnabled()).thenReturn(true);
        when(state.getUtxo(outpoint)).thenReturn(Optional.of(canonical));
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(ledger.getUtxoState()).thenReturn(state);
        MempoolQueryGateway mempool = mock(MempoolQueryGateway.class);
        when(mempool.resolveUtxo(outpoint)).thenReturn(Optional.of(transientView));
        UtxoResource resource = new UtxoResource();
        resource.ledgerQuery = ledger;
        resource.mempoolQueryGateway = mempool;

        UtxoDto defaultBody = (UtxoDto) resource.getUtxo(outpoint.txHash(), 0, false).getEntity();
        UtxoDto transientBody = (UtxoDto) resource.getUtxo(outpoint.txHash(), 0, true).getEntity();

        // AmountDto.quantity is a string in this branch: lovelace exceeds 2^53, so a JSON
        // number loses precision in any JavaScript client. What this test checks is unchanged -
        // the default view sees the settled output and the transient view sees the mempool one.
        assertThat(defaultBody.amount().getFirst().quantity()).isEqualTo("1000000");
        assertThat(transientBody.amount().getFirst().quantity()).isEqualTo("2000000");
    }

    private static Utxo utxo(Outpoint outpoint, long lovelace) {
        return new Utxo(outpoint, "addr_test1", BigInteger.valueOf(lovelace),
                List.of(), null, null, null, null, false, 1, 1, "block");
    }
}
