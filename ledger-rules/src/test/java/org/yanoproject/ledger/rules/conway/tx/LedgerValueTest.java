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

    /**
     * {@code maxValSize} is 5000 on the public networks. One policy with 310 assets: Haskell's {@code encodeMap}
     * writes a map above 23 entries indefinite-length (2 bytes of framing), so the value is 5000 bytes, while the
     * definite-length encoding (CCL's) has a 3-byte map head and is 5001. One more byte of quantity makes Haskell's
     * encoding 5001.
     */
    @Test
    void mapsAboveTwentyThreeEntriesAreMeasuredIndefiniteLength() throws Exception {
        Value atLimit = manyAssets(BigInteger.valueOf(100));
        assertThat(LedgerValue.of(atLimit).serializedSize()).isEqualTo(5000);
        assertThat(CborSerializationUtil.serialize(atLimit.serialize()).length).isEqualTo(5001);

        assertThat(LedgerValue.of(manyAssets(BigInteger.valueOf(1000))).serializedSize()).isEqualTo(5001);
    }

    /** 2 ADA and 310 assets of one policy with 14-byte names, quantity 1 except the first one's. */
    private static Value manyAssets(BigInteger firstQuantity) {
        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < 310; i++) {
            assets.add(new Asset("0x" + "61".repeat(12) + String.format("%04x", i),
                    i == 0 ? firstQuantity : BigInteger.ONE));
        }
        return Value.builder().coin(BigInteger.valueOf(2_000_000)).multiAssets(new ArrayList<>(List.of(
                MultiAsset.builder().policyId("ab".repeat(28)).assets(assets).build()))).build();
    }

    // encodeMap's variableMapLenEncoding (cardano-ledger-binary Encoder.hs:432-443, lengthThreshold = 23): a map of
    // at most 23 entries has a definite head (1 byte), a larger one is indefinite (bf ... ff, 2 bytes). Against a
    // definite-length encoding (CCL's): equal up to 255 entries (a definite b8 NN head is also 2 bytes), one byte
    // less from 256 (a definite b9 NNNN head is 3). These guard against "fixing" the size back to canonical CBOR.

    @Test
    void assetMapOfTwentyThreeEntriesHasADefiniteHead() throws Exception {
        assertThat(savingOverDefinite(1, 23)).isZero();
    }

    @Test
    void assetMapAboveTwentyThreeEntriesIsIndefiniteAndTheSameSizeUpTo255() throws Exception {
        assertThat(savingOverDefinite(1, 24)).isZero();
        assertThat(savingOverDefinite(1, 255)).isZero();
    }

    @Test
    void assetMapOf256EntriesIsIndefiniteAndOneByteSmallerThanDefinite() throws Exception {
        assertThat(savingOverDefinite(1, 256)).isEqualTo(1);
    }

    @Test
    void policyMapOfTwentyThreeEntriesHasADefiniteHead() throws Exception {
        assertThat(savingOverDefinite(23, 1)).isZero();
    }

    @Test
    void policyMapAboveTwentyThreeEntriesIsIndefiniteAndTheSameSizeUpTo255() throws Exception {
        assertThat(savingOverDefinite(24, 1)).isZero();
        assertThat(savingOverDefinite(255, 1)).isZero();
    }

    @Test
    void policyMapOf256EntriesIsIndefiniteAndOneByteSmallerThanDefinite() throws Exception {
        assertThat(savingOverDefinite(256, 1)).isEqualTo(1);
        assertThat(savingOverDefinite(256, 256)).isEqualTo(257);
    }

    /**
     * @return the definite-length encoding's size (CCL) minus {@link LedgerValue#serializedSize()}, for a value of
     *         {@code policies} policies with {@code assetsPerPolicy} assets each
     */
    private static int savingOverDefinite(int policies, int assetsPerPolicy) throws Exception {
        List<MultiAsset> multiAssets = new ArrayList<>();
        for (int p = 0; p < policies; p++) {
            List<Asset> assets = new ArrayList<>();
            for (int a = 0; a < assetsPerPolicy; a++) {
                assets.add(new Asset("0x" + String.format("%04x", a), BigInteger.valueOf(a + 1)));
            }
            multiAssets.add(MultiAsset.builder().policyId(String.format("%056x", p)).assets(assets).build());
        }
        Value value = Value.builder().coin(BigInteger.valueOf(2_000_000)).multiAssets(multiAssets).build();
        return CborSerializationUtil.serialize(value.serialize()).length - LedgerValue.of(value).serializedSize();
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
