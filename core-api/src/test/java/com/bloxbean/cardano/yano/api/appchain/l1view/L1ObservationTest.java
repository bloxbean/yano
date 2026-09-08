package com.bloxbean.cardano.yano.api.appchain.l1view;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L1ObservationTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Properties VECTORS = vectors();

    @Test
    void transactionAnchorMatchesGoldenVectorAndIsDefensive() {
        byte[] transactionHash = filled(0x11);
        byte[] blockHash = filled(0x22);
        byte[] claim = HEX.parseHex("820102");
        L1Observation observation = L1Observation.transaction(
                "deposits", transactionHash, 42, blockHash, claim);

        assertThat(HEX.formatHex(observation.encode()))
                .isEqualTo(VECTORS.getProperty("transaction"));
        assertThat(L1Observation.decode(observation.encode())).isEqualTo(observation);
        assertThat(observation.key()).isEqualTo(
                "deposits/tx:" + "11".repeat(32) + "/0/42");
        assertThat(observation.transactionAnchor().transactionHash())
                .isEqualTo(filled(0x11));
        assertThatThrownBy(observation::epochAnchor)
                .isInstanceOf(IllegalStateException.class);

        transactionHash[0] = 0;
        blockHash[0] = 0;
        claim[0] = 0;
        byte[] returned = observation.transactionAnchor().transactionHash();
        returned[0] = 0;
        assertThat(observation.transactionAnchor().transactionHash()[0]).isEqualTo((byte) 0x11);
        assertThat(observation.blockHash()[0]).isEqualTo((byte) 0x22);
        assertThat(observation.claim()[0]).isEqualTo((byte) 0x82);
    }

    @Test
    void epochAnchorMatchesGoldenVector() {
        L1Observation observation = L1Observation.epoch(
                "epoch-params", 170, 5_000, filled(0x33), HEX.parseHex("a10102"));

        assertThat(HEX.formatHex(observation.encode()))
                .isEqualTo(VECTORS.getProperty("epoch"));
        assertThat(L1Observation.decode(observation.encode())).isEqualTo(observation);
        assertThat(observation.key()).isEqualTo("epoch-params/epoch:170/0/5000");
        assertThat(observation.epochAnchor().newEpoch()).isEqualTo(170);
        assertThatThrownBy(observation::transactionAnchor)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTransactionOnlyPreviewAndMalformedAnchors() {
        assertThat(L1Observation.decode(
                HEX.parseHex(VECTORS.getProperty("legacy_transaction_preview")))).isNull();
        assertThat(L1Observation.decode(tagged(2, new UnsignedInteger(3)))).isNull();
        assertThat(L1Observation.decode(tagged(
                L1Observation.TRANSACTION_ANCHOR_TAG, new UnsignedInteger(3)))).isNull();
        assertThat(L1Observation.decode(tagged(
                L1Observation.EPOCH_ANCHOR_TAG, new ByteString(filled(0x11))))).isNull();
        assertThat(L1Observation.decode(tagged(
                L1Observation.TRANSACTION_ANCHOR_TAG, new ByteString(new byte[31])))).isNull();
    }

    @Test
    void rejectsNonCanonicalAndInvalidConstruction() {
        byte[] canonical = HEX.parseHex(VECTORS.getProperty("transaction"));
        byte[] nonCanonicalVersion = new byte[canonical.length + 1];
        nonCanonicalVersion[0] = canonical[0];
        nonCanonicalVersion[1] = 0x18;
        nonCanonicalVersion[2] = 0x01;
        System.arraycopy(canonical, 2, nonCanonicalVersion, 3, canonical.length - 2);
        assertThat(L1Observation.decode(nonCanonicalVersion)).isNull();

        assertThatThrownBy(() -> L1Observation.transaction(
                "bad/id", filled(1), 1, filled(2), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> L1Observation.transaction(
                "observer", new byte[31], 1, filled(2), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> L1Observation.epoch(
                "observer", -1, 1, filled(2), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> L1Observation.epoch(
                "observer", 1, -1, filled(2), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] tagged(long tag, DataItem value) {
        try {
            Array anchor = new Array();
            anchor.add(new UnsignedInteger(tag));
            anchor.add(value);
            Array observation = new Array();
            observation.add(new UnsignedInteger(1));
            observation.add(new UnicodeString("observer"));
            observation.add(anchor);
            observation.add(new UnsignedInteger(0));
            observation.add(new UnsignedInteger(1));
            observation.add(new ByteString(filled(2)));
            observation.add(new ByteString(new byte[0]));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new CborEncoder(output).encode(observation);
            return output.toByteArray();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static Properties vectors() {
        try (InputStream input = L1ObservationTest.class.getResourceAsStream(
                "/META-INF/yano/contracts/l1-observation/v1/golden-vectors.properties")) {
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
