package com.bloxbean.cardano.yano.api.appchain.anchor;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnchorDatumV1Test {
    private static final String POLICY = "aa".repeat(28);

    @Test
    void pinsGoldenAbiAndHasDefensiveContentValueSemantics() {
        AnchorDatumV1 datum = datum("evidence-chain", 12, filled(0x11),
                filled(0x22), List.of(filled(3), filled(1), filled(2)), 2);
        String encoded = HexFormat.of().formatHex(datum.encode());

        assertThat(encoded).isEqualTo("d8799f014e65766964656e63652d636861696e0c5820"
                + "1111111111111111111111111111111111111111111111111111111111111111"
                + "58202222222222222222222222222222222222222222222222222222222222222222"
                + "9f58200101010101010101010101010101010101010101010101010101010101010101"
                + "58200202020202020202020202020202020202020202020202020202020202020202"
                + "58200303030303030303030303030303030303030303030303030303030303030303"
                + "ff02ff");
        AnchorDatumV1 decoded = AnchorDatumV1.decode(datum.encode());
        assertThat(decoded).isEqualTo(datum);
        assertThat(decoded.hashCode()).isEqualTo(datum.hashCode());
        assertThat(decoded.memberKeysHex()).containsExactly(
                "01".repeat(32), "02".repeat(32), "03".repeat(32));

        byte[] leakedHash = decoded.blockHash();
        leakedHash[0] = 0;
        List<byte[]> leakedMembers = decoded.memberKeys();
        leakedMembers.getFirst()[0] = 0;
        assertThat(decoded.blockHash()).isEqualTo(filled(0x11));
        assertThat(decoded.memberKeys().getFirst()).isEqualTo(filled(1));
    }

    @Test
    void rejectsUnsupportedMalformedAndNonCanonicalDatums() {
        assertThatThrownBy(() -> new AnchorDatumV1("bad\ud800", 1,
                filled(1), filled(2), List.of(filled(3)), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnchorDatumV1("chain", -1,
                filled(1), filled(2), List.of(filled(3)), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnchorDatumV1("chain", 1,
                new byte[31], filled(2), List.of(filled(3)), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnchorDatumV1("chain", 1,
                filled(1), filled(2), List.of(filled(3), filled(3)), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnchorDatumV1("chain", 1,
                filled(1), filled(2), List.of(filled(3)), 2))
                .isInstanceOf(IllegalArgumentException.class);
        List<byte[]> oversizedMembers = new ArrayList<>();
        for (int index = 0; index <= AppChainConfig.MAX_MEMBERS; index++) {
            oversizedMembers.add(filled(index));
        }
        assertThatThrownBy(() -> new AnchorDatumV1("chain", 1,
                filled(1), filled(2), oversizedMembers, 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AnchorDatumV1.decode(rawDatum(2,
                List.of(filled(1), filled(2)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnchorDatumV1.decode(rawDatum(1,
                List.of(filled(2), filled(1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnchorDatumV1.decode(new byte[8 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactV1MemberBoundaryWithinDatumBudget() {
        List<byte[]> members = new ArrayList<>(AppChainConfig.MAX_MEMBERS);
        for (int index = 0; index < AppChainConfig.MAX_MEMBERS; index++) {
            members.add(filled(index));
        }

        AnchorDatumV1 datum = datum("x".repeat(AppChainConfig.MAX_CHAIN_ID_BYTES),
                Long.MAX_VALUE, filled(1), filled(2), members,
                AppChainConfig.MAX_MEMBERS);
        byte[] encoded = datum.encode();

        assertThat(encoded).hasSizeLessThan(AnchorDatumV1.MAX_DATUM_BYTES);
        assertThat(AnchorDatumV1.decode(encoded)).isEqualTo(datum);
    }

    @Test
    void derivesExactPolicyAndTruncatedUtf8ThreadTokenUnit() {
        assertThat(AnchorDatumV1.threadTokenUnit(POLICY, "evidence-chain"))
                .isEqualTo(POLICY + HexFormat.of().formatHex(
                        "evidence-chain".getBytes(StandardCharsets.UTF_8)));
        assertThat(AnchorDatumV1.threadTokenUnit(POLICY, "x".repeat(40)))
                .isEqualTo(POLICY + "78".repeat(32));
        assertThatThrownBy(() -> AnchorDatumV1.threadTokenUnit(
                POLICY.toUpperCase(), "chain"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDeepHostileCborBeforeRecursiveDecoderWithoutEscapingError() {
        byte[] nested = nestedIndefiniteArrays(3_000);

        assertThatThrownBy(() -> AnchorDatumV1.decode(nested))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AnchorDatumV1 datum(String chainId, long height,
                                       byte[] blockHash, byte[] stateRoot,
                                       List<byte[]> members, int threshold) {
        return new AnchorDatumV1(chainId, height, blockHash, stateRoot, members, threshold);
    }

    private static byte[] rawDatum(long version, List<byte[]> memberKeys) {
        ListPlutusData members = ListPlutusData.builder().build();
        memberKeys.forEach(key -> members.add(BytesPlutusData.of(key)));
        return ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of(
                BigIntPlutusData.of(version),
                BytesPlutusData.of("evidence-chain".getBytes(StandardCharsets.UTF_8)),
                BigIntPlutusData.of(12), BytesPlutusData.of(filled(0x11)),
                BytesPlutusData.of(filled(0x22)), members, BigIntPlutusData.of(2)))
                .build().serializeToBytes();
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] nestedIndefiniteArrays(int depth) {
        byte[] bytes = new byte[depth * 2 + 1];
        Arrays.fill(bytes, 0, depth, (byte) 0x9f);
        bytes[depth] = 0;
        Arrays.fill(bytes, depth + 1, bytes.length, (byte) 0xff);
        return bytes;
    }
}
