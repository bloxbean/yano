package com.bloxbean.cardano.yano.app.api.utxos;

import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.MempoolQueryGateway;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.app.api.utxos.dto.UtxoDto;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UtxoResourceMempoolQueryTest {
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
