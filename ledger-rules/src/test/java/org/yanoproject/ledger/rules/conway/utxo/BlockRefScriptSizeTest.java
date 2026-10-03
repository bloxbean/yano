package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.Lookup;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockRefScriptSizeTest {

    private static final String ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
    private static final String HOLDER = "cc".repeat(32);
    private static final String TX_A = "aa".repeat(32);

    private static TransactionOutput withScript() throws Exception {
        PlutusV2Script script = PlutusV2Script.builder().cborHex("49480100002221200101").build();
        return TransactionOutput.builder().address(ADDRESS)
                .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build()).scriptRef(script).build();
    }

    private static TransactionOutput plain() {
        return TransactionOutput.builder().address(ADDRESS)
                .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build()).build();
    }

    private static BlockRefScriptSize preBlock(Map<Outpoint, byte[]> scriptRefs, int protocolMajor) {
        return new BlockRefScriptSize(in -> Lookup.ofNullable(scriptRefs.get(in)), protocolMajor);
    }

    private static long measure(BlockRefScriptSize size, TransactionBody body) {
        return ((Lookup.Present<Long>) size.measure(body)).value();
    }

    @Test
    void anInputBothSpentAndReferencedCountsOnce() throws Exception {
        long scriptSize = MinFee.scriptOriginalSize(withScript().getScriptRef());
        BlockRefScriptSize size = preBlock(Map.of(new Outpoint(HOLDER, 0), withScript().getScriptRef()), 10);
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(HOLDER, 0)))
                .referenceInputs(List.of(new TransactionInput(HOLDER, 0)))
                .outputs(List.of(plain())).build();

        assertThat(measure(size, body)).isEqualTo(scriptSize);
    }

    @Test
    void fromPv11APhase2InvalidTransactionContributesItsCollateralReturnOnly() throws Exception {
        long scriptSize = MinFee.scriptOriginalSize(withScript().getScriptRef());
        BlockRefScriptSize size = preBlock(Map.of(), 11);
        TransactionBody invalid = TransactionBody.builder()
                .outputs(List.of(withScript())).collateralReturn(withScript()).build();
        size.add(TX_A, invalid, false, 0);

        TransactionBody reader = TransactionBody.builder()
                .referenceInputs(List.of(new TransactionInput(TX_A, 0), new TransactionInput(TX_A, 1)))
                .outputs(List.of(plain())).build();

        assertThat(measure(size, reader)).as("collOuts sits at index |outputs|; txouts do not exist")
                .isEqualTo(scriptSize);
        size.add("bb".repeat(32), reader, true, scriptSize);
        assertThat(size.total()).isEqualTo(scriptSize);
    }

    @Test
    void anUnresolvableInputMakesTheMeasureUnavailable() {
        BlockRefScriptSize size = new BlockRefScriptSize(in -> Lookup.unavailable("store not ready"), 10);
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(HOLDER, 0))).outputs(List.of(plain())).build();

        assertThat(size.measure(body)).isEqualTo(Lookup.unavailable("store not ready"));
    }
}
