package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionOutputProjectorTest {
    private static final String ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

    @Test
    void preservesAssetsInlineDatumAndReferenceScript() throws Exception {
        String policyId = "ab".repeat(28);
        MultiAsset multiAsset = MultiAsset.builder()
                .policyId(policyId)
                .assets(List.of(Asset.builder()
                        .name("token")
                        .value(BigInteger.valueOf(42))
                        .build()))
                .build();
        PlutusData datum = PlutusData.unit();
        PlutusV2Script script = PlutusV2Script.builder()
                .cborHex("49480100002221200101")
                .build();
        TransactionOutput output = TransactionOutput.builder()
                .address(ADDRESS)
                .value(new Value(BigInteger.valueOf(3_000_000), List.of(multiAsset)))
                .inlineDatum(datum)
                .scriptRef(script)
                .build();

        var projected = TransactionOutputProjector.project("11".repeat(32), 3, output);

        assertThat(projected.outpoint().index()).isEqualTo(3);
        assertThat(projected.lovelace()).isEqualTo(BigInteger.valueOf(3_000_000));
        assertThat(projected.assets()).containsExactly(
                new com.bloxbean.cardano.yano.api.utxo.model.AssetAmount(
                        policyId, "746f6b656e", BigInteger.valueOf(42)));
        assertThat(projected.inlineDatum()).isEqualTo(datum.serializeToBytes());
        assertThat(projected.scriptRef()).isEqualTo(HexUtil.encodeHexString(output.getScriptRef()));
        assertThat(projected.referenceScriptHash())
                .isEqualTo(HexUtil.encodeHexString(script.getScriptHash()));
        assertThat(projected.collateralReturn()).isFalse();
    }

    @Test
    void preservesDatumHash() {
        byte[] datumHash = new byte[32];
        datumHash[31] = 7;
        TransactionOutput output = TransactionOutput.builder()
                .address(ADDRESS)
                .value(new Value(BigInteger.ONE, null))
                .datumHash(datumHash)
                .build();

        var projected = TransactionOutputProjector.project("22".repeat(32), 0, output);

        assertThat(projected.datumHash()).isEqualTo(HexUtil.encodeHexString(datumHash));
        assertThat(projected.inlineDatum()).isNull();
    }
}
