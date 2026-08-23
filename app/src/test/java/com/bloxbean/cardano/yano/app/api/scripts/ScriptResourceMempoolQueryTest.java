package com.bloxbean.cardano.yano.app.api.scripts;

import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.MempoolQueryGateway;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.app.api.scripts.dto.ScriptCborDto;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptResourceMempoolQueryTest {
    @Test
    void referenceScriptLookupUsesTransientViewOnlyWhenRequested() throws Exception {
        PlutusV2Script script = PlutusV2Script.builder()
                .cborHex("49480100002221200101")
                .build();
        TransactionOutput output = TransactionOutput.builder()
                .address("addr_test1")
                .value(new Value(BigInteger.ONE, null))
                .scriptRef(script)
                .build();
        String scriptHash = HexUtil.encodeHexString(script.getScriptHash());
        UtxoState state = mock(UtxoState.class);
        when(state.isEnabled()).thenReturn(true);
        when(state.getScriptRefBytesByHash(scriptHash)).thenReturn(Optional.empty());
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(ledger.getUtxoState()).thenReturn(state);
        MempoolQueryGateway mempool = mock(MempoolQueryGateway.class);
        when(mempool.getScriptRefBytesByHash(scriptHash))
                .thenReturn(Optional.of(output.getScriptRef()));
        ScriptResource resource = new ScriptResource();
        resource.ledgerQuery = ledger;
        resource.mempoolQueryGateway = mempool;

        assertThat(resource.getScriptCbor(scriptHash, false).getStatus()).isEqualTo(404);
        var response = resource.getScriptCbor(scriptHash, true);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((ScriptCborDto) response.getEntity()).cbor())
                .isEqualTo(HexUtil.encodeHexString(script.serializeScriptBody()));
    }
}
