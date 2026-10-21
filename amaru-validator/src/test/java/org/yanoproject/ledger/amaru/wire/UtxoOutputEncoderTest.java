package org.yanoproject.ledger.amaru.wire;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UtxoOutputEncoderTest {

    private static final String ADDRESS_HEX = "60" + "22".repeat(28);
    private static final String ADDRESS = new Address(HexUtil.decodeHexString(ADDRESS_HEX)).toBech32();
    private static final String POLICY = "ab".repeat(28);

    private static UtxoEntry entry(TransactionOutput output, byte[] inlineDatum) {
        return new UtxoEntry(Outpoints.of("01".repeat(32), 0), output, inlineDatum);
    }

    @Test
    void coinOnlyOutputIsAMapWithAddressAndCoin() {
        byte[] cbor = UtxoOutputEncoder.encode(entry(new TransactionOutput(ADDRESS,
                Value.builder().coin(BigInteger.valueOf(5_000_000)).build()), null));
        assertThat(HexUtil.encodeHexString(cbor)).isEqualTo("a200581d" + ADDRESS_HEX + "011a004c4b40");
    }

    @Test
    void multiAssetsDatumHashAndReferenceScriptAreKept() {
        MultiAsset assets = new MultiAsset(POLICY, List.of(new Asset("0x746f6b656e", BigInteger.TEN)));
        byte[] script = HexUtil.decodeHexString("82025820" + "cd".repeat(32));
        TransactionOutput output = TransactionOutput.builder()
                .address(ADDRESS)
                .value(Value.builder().coin(BigInteger.TWO).multiAssets(List.of(assets)).build())
                .datumHash(HexUtil.decodeHexString("ef".repeat(32)))
                .build();
        output.setScriptRef(script);

        Map<?, ?> decoded = (Map<?, ?>) CborReader.decode(UtxoOutputEncoder.encode(entry(output, null)));
        assertThat(decoded.get(0L)).isEqualTo(HexUtil.decodeHexString(ADDRESS_HEX));
        List<?> value = (List<?>) decoded.get(1L);
        assertThat(value.get(0)).isEqualTo(2L);
        assertThat(((Map<?, ?>) value.get(1))).hasSize(1);
        List<?> datum = (List<?>) decoded.get(2L);
        assertThat(datum.get(0)).isEqualTo(0L);
        assertThat(datum.get(1)).isEqualTo(HexUtil.decodeHexString("ef".repeat(32)));
        CborReader.Tagged scriptRef = (CborReader.Tagged) decoded.get(3L);
        assertThat(scriptRef.tag()).isEqualTo(24);
        assertThat(scriptRef.value()).isEqualTo(script);
    }

    @Test
    void storedInlineDatumBytesAreSentExactly() {
        // The stored datum differs from what CCL would write for the decoded value: the stored bytes win.
        byte[] stored = HexUtil.decodeHexString("9f182aff"); // an indefinite list [42]
        TransactionOutput output = TransactionOutput.builder()
                .address(ADDRESS)
                .value(Value.builder().coin(BigInteger.ONE).build())
                .inlineDatum(BigIntPlutusData.of(42))
                .build();

        Map<?, ?> decoded = (Map<?, ?>) CborReader.decode(UtxoOutputEncoder.encode(entry(output, stored)));
        List<?> datum = (List<?>) decoded.get(2L);
        assertThat(datum.get(0)).isEqualTo(1L);
        CborReader.Tagged inline = (CborReader.Tagged) datum.get(1);
        assertThat(inline.tag()).isEqualTo(24);
        assertThat(inline.value()).isEqualTo(stored);

        // Without stored bytes, CCL's encoding of the decoded datum is used.
        Map<?, ?> reencoded = (Map<?, ?>) CborReader.decode(UtxoOutputEncoder.encode(entry(output, null)));
        assertThat(((CborReader.Tagged) ((List<?>) reencoded.get(2L)).get(1)).value())
                .isEqualTo(HexUtil.decodeHexString("182a"));
    }
}
