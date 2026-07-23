package com.bloxbean.cardano.yano.appchain.composite.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeGovernanceStatusV1Test {
    @Test
    void statusStrictlyRoundTripsAndRejectsTrailingAndNonCanonicalMembers() {
        CompositeGovernanceStatusV1 value = new CompositeGovernanceStatusV1(1, 2, 40,
                bytes(1), new CompositeGovernanceStatusV1.Proposal(2, bytes(2), bytes(3),
                bytes(4), bytes(5), 80, 120, List.of(hex(6)), List.of(hex(6)), List.of()),
                List.of(new CompositeGovernanceStatusV1.Drain("evidence", "1.0.0", 1, 2, 100)));

        assertThat(HexFormat.of().formatHex(value.encode())).isEqualTo(
                vectors().getProperty("status.scheduled"));
        assertThat(CompositeGovernanceStatusV1.decode(value.encode()).encode())
                .containsExactly(value.encode());
        assertThatThrownBy(() -> CompositeGovernanceStatusV1.decode(
                Arrays.copyOf(value.encode(), value.encode().length + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompositeGovernanceStatusV1.Proposal(1, bytes(2), bytes(3),
                bytes(4), bytes(5), 80, 120, List.of(hex(7), hex(6)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String hex(int value) {
        return java.util.HexFormat.of().formatHex(bytes(value));
    }

    private static Properties vectors() {
        Properties result = new Properties();
        try (var input = CompositeGovernanceStatusV1Test.class.getResourceAsStream(
                "/cddl/composite-profile-governance-v1-golden-vectors.properties")) {
            if (input == null) throw new IllegalStateException("golden vectors are absent");
            result.load(input);
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read golden vectors", failure);
        }
    }
}
