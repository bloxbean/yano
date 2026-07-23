package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainConsensusProfileCommitmentTest {
    private static final String SIGNER_1 = "11".repeat(32);
    private static final String SIGNER_2 = "22".repeat(32);
    private static final String GOLDEN_BYTES = "0000000100010000000000400000000000400000"
            + "0000000507000000800000400000000000000186a000000000000186a001000002"
            + SIGNER_1 + SIGNER_2;
    private static final String GOLDEN_DIGEST =
            "fe3f0092d18c6f95e662f558084b56dac8350516c88c9e6ab0fcf51343d00f95";

    @Test
    void freezesCanonicalKeyBytesAndDigest() {
        AppChainConsensusProfile profile = enabledProfile(List.of(SIGNER_2, SIGNER_1));

        byte[] encoded = AppChainConsensusProfileCommitment.encode(profile);

        assertThat(new String(AppChainConsensusProfileCommitment.markerKey(),
                java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("~yano/consensus-profile/v1");
        assertThat(HexUtil.encodeHexString(encoded)).isEqualTo(GOLDEN_BYTES);
        assertThat(HexUtil.encodeHexString(
                AppChainConsensusProfileCommitment.digest(profile))).isEqualTo(GOLDEN_DIGEST);
        assertThat(AppChainConsensusProfileCommitment.decode(encoded)).isEqualTo(profile);
        assertThat(profile.effectResultSigners()).containsExactly(SIGNER_1, SIGNER_2);
    }

    @Test
    void rejectsNonCanonicalOrMalformedBytes() {
        byte[] encoded = AppChainConsensusProfileCommitment.encode(
                enabledProfile(List.of(SIGNER_1, SIGNER_2)));

        byte[] unknownFlags = encoded.clone();
        unknownFlags[24] |= (byte) 0x80;
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(unknownFlags))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown flag");

        byte[] reversedSigners = encoded.clone();
        System.arraycopy(encoded, 53 + 32, reversedSigners, 53, 32);
        System.arraycopy(encoded, 53, reversedSigners, 53 + 32, 32);
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(reversedSigners))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonically encoded");

        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte length");
    }

    @Test
    void enforcesCanonicalDisabledEffectProfile() {
        AppChainConsensusProfile disabled = new AppChainConsensusProfile(
                1, 65_536, 64, 4_194_304, 0, false,
                false, 0, 0, 0, 0,
                FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                true, List.of());

        assertThat(AppChainConsensusProfileCommitment.decode(
                AppChainConsensusProfileCommitment.encode(disabled))).isEqualTo(disabled);
        assertThatThrownBy(() -> new AppChainConsensusProfile(
                1, 65_536, 64, 4_194_304, 0, false,
                false, 1, 0, 0, 0,
                FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled effect profile");
    }

    @Test
    void contextDefaultRemainsCompatibleAndReservedPrefixIsExact() {
        AppStateMachineContext oldStyle = new AppStateMachineContext() {
            @Override
            public String chainId() {
                return "chain";
            }

            @Override
            public Map<String, String> settings() {
                return Map.of();
            }
        };

        assertThat(oldStyle.consensusProfile()).isEmpty();
        assertThat(AppChainConsensusProfileCommitment.isReserved(
                "~yano/anything".getBytes(java.nio.charset.StandardCharsets.US_ASCII))).isTrue();
        assertThat(AppChainConsensusProfileCommitment.isReserved(
                "~yan/anything".getBytes(java.nio.charset.StandardCharsets.US_ASCII))).isFalse();
    }

    private static AppChainConsensusProfile enabledProfile(List<String> signers) {
        return new AppChainConsensusProfile(
                1,
                65_536,
                64,
                4_194_304,
                5,
                true,
                true,
                128,
                16_384,
                100_000,
                100_000,
                FinalityGate.L1_ANCHORED,
                EffectOutcomeCommitment.PER_EFFECT,
                true,
                signers);
    }
}
