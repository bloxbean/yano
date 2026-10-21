package org.yanoproject.ledger.amaru.wire;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scenario-independent checks of the v1 codec (the golden tests pin whole documents). */
class WireCodecTest {

    @Test
    void headsUseTheShortestForm() {
        CborWriter w = new CborWriter();
        w.uint(23).uint(24).uint(255).uint(256).uint(65535).uint(65536).uint(0xFFFF_FFFFL).uint(0x1_0000_0000L)
                .integer(-1).integer(-25).uint(new BigInteger("18446744073709551615"));
        assertThat(HexUtil.encodeHexString(w.toByteArray())).isEqualTo(
                "17" + "1818" + "18ff" + "190100" + "19ffff" + "1a00010000" + "1affffffff" + "1b0000000100000000"
                        + "20" + "3818" + "1bffffffffffffffff");
        assertThatThrownBy(() -> new CborWriter().uint(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysEnvMatchesTheReferenceLayout() {
        // {0: 1, 1: [10, 0]}
        assertThat(HexUtil.encodeHexString(AmaruRequestEncoder.keysEnv(10, 0))).isEqualTo("a2000101820a00");
    }

    @Test
    void rationalsAreShortestDecimalFractions() {
        assertThat(rational(new BigDecimal("0.0577"))).isEqualTo("d81e82190241192710");
        assertThat(rational(new BigDecimal("1.20"))).isEqualTo("d81e820c0a");
        assertThat(rational(new BigDecimal("15"))).isEqualTo("d81e820f01");
        assertThat(rational(new BigDecimal("1E+2"))).isEqualTo("d81e82186401");
        assertThat(rational(BigDecimal.ZERO)).isEqualTo("d81e820001");
        assertThatThrownBy(() -> rational(new BigDecimal("-0.1"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static String rational(BigDecimal value) {
        CborWriter w = new CborWriter();
        ProtocolParamsEncoder.rational(w, "test", value);
        return HexUtil.encodeHexString(w.toByteArray());
    }

    @Test
    void missingOrOutOfRangeParametersAreRefused() {
        assertThatThrownBy(() -> ProtocolParamsEncoder.encode(new ProtocolParams()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minFeeA");
    }

    @Test
    void responsesDecodeAndRejectUnknownShapes() {
        assertThat(AmaruResponse.decode(HexUtil.decodeHexString("a10000"))).isEqualTo(new AmaruResponse.Ok());
        byte[] invalid = new CborWriter().map(5).uint(0).uint(1).uint(1).uint(1).uint(2).text("UTXO")
                .uint(4).text("PhaseOne.X").uint(5).text("detail").toByteArray();
        assertThat(AmaruResponse.decode(invalid))
                .isEqualTo(new AmaruResponse.Invalid(1, "UTXO", null, "PhaseOne.X", "detail", null));
        byte[] error = new CborWriter().map(2).uint(0).uint(2).uint(7).text("bad").toByteArray();
        assertThat(AmaruResponse.decode(error)).isEqualTo(new AmaruResponse.Error("bad"));

        assertThatThrownBy(() -> AmaruResponse.decode(HexUtil.decodeHexString("a1000000")))
                .hasMessageContaining("trailing");
        assertThatThrownBy(() -> AmaruResponse.decode(HexUtil.decodeHexString("a2000009f6")))
                .hasMessageContaining("unknown validate response key");
        assertThatThrownBy(() -> AmaruResponse.decode(HexUtil.decodeHexString("a200000000")))
                .hasMessageContaining("duplicate");
    }

    @Test
    void requiredKeysDecode() {
        byte[] txId = new byte[32];
        byte[] hash = new byte[28];
        CborWriter w = new CborWriter().map(8).uint(0).uint(0)
                .uint(1).array(1).array(2).bytes(txId).uint(3)
                .uint(2).array(1).array(2).uint(0).bytes(hash)
                .uint(3).array(1).bytes(hash)
                .uint(4).array(0).uint(5).array(0).uint(6).array(0).uint(7).array(0);
        RequiredKeys.Result result = RequiredKeys.decode(w.toByteArray());
        assertThat(result).isInstanceOf(RequiredKeys.Keys.class);
        RequiredKeys keys = ((RequiredKeys.Keys) result).keys();
        assertThat(keys.inputs()).singleElement().satisfies(o -> assertThat(o.index()).isEqualTo(3));
        assertThat(keys.accounts()).hasSize(1);
        assertThat(keys.pools()).hasSize(1);
        assertThat(keys.dreps()).isEmpty();

        byte[] error = new CborWriter().map(2).uint(0).uint(2).uint(8).text("transaction does not decode")
                .toByteArray();
        assertThat(RequiredKeys.decode(error)).isEqualTo(new RequiredKeys.Error("transaction does not decode"));
        assertThatThrownBy(() -> RequiredKeys.decode(new CborWriter().map(1).uint(0).uint(9).toByteArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indefiniteLengthsAreAccepted() {
        // {_ 0: 0}
        assertThat(CborReader.decode(HexUtil.decodeHexString("bf0000ff"))).isEqualTo(Map.of(0L, 0L));
        assertThat(CborReader.decode(HexUtil.decodeHexString("9f0102ff"))).isEqualTo(List.of(1L, 2L));
    }
}
