package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerValueTest {

    @Test
    void serializedSizeIsTheCanonicalEncodingOfTheValue() throws Exception {
        Value adaOnly = Value.builder().coin(BigInteger.valueOf(1_000_000)).build();
        assertThat(LedgerValue.of(adaOnly).serializedSize())
                .isEqualTo(CborSerializationUtil.serialize(adaOnly.serialize()).length).isEqualTo(5);

        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            assets.add(new Asset("0x" + String.format("%064x", i), BigInteger.valueOf(1_000L * i + 1)));
        }
        Value multi = Value.builder().coin(BigInteger.valueOf(2_000_000)).multiAssets(new ArrayList<>(List.of(
                MultiAsset.builder().policyId("ab".repeat(28)).assets(assets).build()))).build();
        assertThat(LedgerValue.of(multi).serializedSize())
                .isEqualTo(CborSerializationUtil.serialize(multi.serialize()).length);
    }

    @Test
    void zeroQuantitiesAreDroppedLikeHaskellsCanonicalMaps() {
        LedgerValue a = new LedgerValue(BigInteger.ONE, Map.of("aa".repeat(28), Map.of("01", BigInteger.ZERO)));
        assertThat(a).isEqualTo(LedgerValue.ofCoin(BigInteger.ONE));
        assertThat(a.isAdaOnly()).isTrue();
        LedgerValue b = new LedgerValue(BigInteger.TWO, Map.of("AA".repeat(28), Map.of("01", BigInteger.TEN)));
        assertThat(b.subtract(b)).isEqualTo(LedgerValue.ZERO);
        assertThat(b.add(b).assets().get("aa".repeat(28)).get("01")).isEqualTo(BigInteger.valueOf(20));
    }
}
