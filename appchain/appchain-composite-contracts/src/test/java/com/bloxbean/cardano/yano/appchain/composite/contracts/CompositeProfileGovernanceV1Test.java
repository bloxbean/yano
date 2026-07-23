package com.bloxbean.cardano.yano.appchain.composite.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HexFormat;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeProfileGovernanceV1Test {
    private static final byte[] ID = bytes(1);
    private static final byte[] BASE = bytes(2);
    private static final byte[] MEMBERS = bytes(3);
    private static final byte[] TARGET = bytes(4);

    @Test
    void commandsHaveFrozenCanonicalVectorsAndRejectTrailingOrIndefiniteForms() {
        CompositeProfileGovernanceV1.Begin begin = new CompositeProfileGovernanceV1.Begin(
                ID, BASE, MEMBERS, TARGET, 1024, 2, 50, 100);
        assertVector("command.begin", begin.encode());
        assertThat(CompositeProfileGovernanceV1.decode(begin.encode()).encode())
                .containsExactly(begin.encode());

        java.util.Map<String, CompositeProfileGovernanceV1.Command> commands =
                java.util.Map.of(
                        "command.chunk", new CompositeProfileGovernanceV1.Chunk(
                                ID, 1, new byte[]{9, 8}),
                        "command.seal", new CompositeProfileGovernanceV1.Seal(ID),
                        "command.approve", new CompositeProfileGovernanceV1.Approve(BASE),
                        "command.ready", new CompositeProfileGovernanceV1.Ready(BASE, TARGET),
                        "command.cancel", new CompositeProfileGovernanceV1.Cancel(BASE));
        for (var entry : commands.entrySet()) {
            CompositeProfileGovernanceV1.Command command = entry.getValue();
            assertVector(entry.getKey(), command.encode());
            assertThat(CompositeProfileGovernanceV1.decode(command.encode()).encode())
                    .containsExactly(command.encode());
            assertThatThrownBy(() -> CompositeProfileGovernanceV1.decode(
                    java.util.Arrays.copyOf(command.encode(), command.encode().length + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CompositeProfileGovernanceV1.decode(
                HexFormat.of().parseHex("9f01035f5820" + "01".repeat(32) + "ffff")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proposalHashIsChainAndCompleteIntentBound() {
        CompositeProfileGovernanceV1.Begin begin = new CompositeProfileGovernanceV1.Begin(
                ID, BASE, MEMBERS, TARGET, 1024, 2, 50, 100);
        byte[] hash = CompositeProfileGovernanceV1.proposalHash("chain-a", begin);

        assertThat(HexFormat.of().formatHex(hash)).isEqualTo(
                "0a20f6b0edbb94139eaf12a0cc91aaed70975ab0faeffe9d42a4ffdcdc556c25");
        assertThat(CompositeProfileGovernanceV1.proposalHash("chain-b", begin))
                .isNotEqualTo(hash);
        assertThat(CompositeProfileGovernanceV1.proposalHash("chain-a",
                new CompositeProfileGovernanceV1.Begin(
                        ID, BASE, MEMBERS, TARGET, 1024, 2, 51, 100)))
                .isNotEqualTo(hash);
        assertThat(CompositeProfileGovernanceV1.proposalHash("chain-a",
                new CompositeProfileGovernanceV1.Begin(
                        bytes(9), BASE, MEMBERS, TARGET, 1024, 2, 50, 100)))
                .isNotEqualTo(hash);
    }

    @Test
    void epochRoundTripsAndLinksProfileDigest() {
        byte[] profile = new byte[]{1, 2, 3};
        CompositeProfileEpochV1 epoch = new CompositeProfileEpochV1(
                1, 0, 1, new byte[32], profile, new byte[32]);

        assertVector("epoch.zero", epoch.encode());
        assertThat(CompositeProfileEpochV1.decode(epoch.encode()).encode())
                .containsExactly(epoch.encode());
        assertThat(epoch.profileDigest())
                .isEqualTo(CompositeCommitmentV1.profileDigest(profile));
        assertThatThrownBy(() -> CompositeProfileEpochV1.decode(
                java.util.Arrays.copyOf(epoch.encode(), epoch.encode().length + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void assertVector(String name, byte[] encoded) {
        assertThat(HexFormat.of().formatHex(encoded)).isEqualTo(vectors().getProperty(name));
    }

    private static Properties vectors() {
        Properties result = new Properties();
        try (var input = CompositeProfileGovernanceV1Test.class.getResourceAsStream(
                "/cddl/composite-profile-governance-v1-golden-vectors.properties")) {
            if (input == null) throw new IllegalStateException("golden vectors are absent");
            result.load(input);
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read golden vectors", failure);
        }
    }
}
