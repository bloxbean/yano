package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Off-chain anchor-datum codec vs the on-chain ABI (anchor-v1.cddl): wire
 * format is Constr(0, [7 fields]) — the same shape the julc conformance
 * vectors pin down on-chain.
 */
class AnchorDatumCodecTest {

    private static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    private AnchorDatumCodec.AnchorDatum datum() {
        return new AnchorDatumCodec.AnchorDatum(1, "orders-chain", 42,
                fill(32, 0xB0), fill(32, 0x50),
                List.of(fill(32, 3), fill(32, 1), fill(32, 2)), 2);
    }

    @Test
    void roundTrip_preservesAllFields() {
        ConstrPlutusData encoded = AnchorDatumCodec.encode(datum());
        AnchorDatumCodec.AnchorDatum decoded = AnchorDatumCodec.decode(encoded);

        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.chainId()).isEqualTo("orders-chain");
        assertThat(decoded.height()).isEqualTo(42);
        assertThat(decoded.blockHash()).isEqualTo(fill(32, 0xB0));
        assertThat(decoded.stateRoot()).isEqualTo(fill(32, 0x50));
        assertThat(decoded.threshold()).isEqualTo(2);
        // Canonical ABI order: bytewise ascending, regardless of input order
        assertThat(decoded.memberKeys()).containsExactly(fill(32, 1), fill(32, 2), fill(32, 3));
    }

    @Test
    void wireFormat_isConstrZeroWithSevenFields() throws Exception {
        ConstrPlutusData encoded = AnchorDatumCodec.encode(datum());
        assertThat(encoded.getAlternative()).isEqualTo(0);
        assertThat(encoded.getData().getPlutusDataList()).hasSize(7);

        // Survives CBOR serialization (inline-datum wire bytes)
        byte[] cbor = encoded.serializeToBytes();
        AnchorDatumCodec.AnchorDatum decoded = AnchorDatumCodec.decode(PlutusData.deserialize(cbor));
        assertThat(decoded.height()).isEqualTo(42);
        assertThat(decoded.memberKeys()).hasSize(3);
    }

    @Test
    void decode_rejectsWrongShape() {
        assertThatThrownBy(() -> AnchorDatumCodec.decode(
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Constr");
        assertThatThrownBy(() -> AnchorDatumCodec.decode(ConstrPlutusData.builder()
                .alternative(0)
                .data(com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(
                        com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(1)))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7 fields");
    }
}
