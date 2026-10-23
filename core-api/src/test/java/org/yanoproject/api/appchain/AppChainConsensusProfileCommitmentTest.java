package org.yanoproject.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainConsensusProfileCommitmentTest {
    private static final String SIGNER_1 = "11".repeat(32);
    private static final String SIGNER_2 = "22".repeat(32);
    // Derived outside the code under test (a one-off Python encoder of the field layout + SHA-256 over the
    // v2 domain; not kept as a script), ADR app-layer/039 D12.
    private static final String GOLDEN_BYTES = "0000000200010000000000400000000000400000"
            + "0000000500000005" + "0000000000001388"
            + "07000000800000400000000000000186a000000000000186a001000002"
            + SIGNER_1 + SIGNER_2;
    private static final String GOLDEN_DIGEST =
            "23f28f835fde84322f122ac49debacd5bea9d22cf2c03c1f6068a82be6620c05";

    @Test
    void freezesCanonicalKeyBytesAndDigest() {
        AppChainConsensusProfile profile = enabledProfile(List.of(SIGNER_2, SIGNER_1));

        byte[] encoded = AppChainConsensusProfileCommitment.encode(profile);

        assertThat(new String(AppChainConsensusProfileCommitment.markerKey(),
                java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("~yano/consensus-profile/v2");
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
        unknownFlags[36] |= (byte) 0x80;
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(unknownFlags))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown flag");

        byte[] reversedSigners = encoded.clone();
        System.arraycopy(encoded, 65 + 32, reversedSigners, 65, 32);
        System.arraycopy(encoded, 65, reversedSigners, 65 + 32, 32);
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
                AppChainConsensusProfile.SCHEMA_VERSION,
                65_536, 64, 4_194_304, 0, 0, 5_000, false,
                false, 0, 0, 0, 0,
                FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                true, List.of());

        assertThat(AppChainConsensusProfileCommitment.decode(
                AppChainConsensusProfileCommitment.encode(disabled))).isEqualTo(disabled);
        assertThatThrownBy(() -> new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                65_536, 64, 4_194_304, 0, 0, 5_000, false,
                false, 1, 0, 0, 0,
                FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled effect profile");
    }

    @Test
    void boundsAndParsesBlockMaxForwardSkew() {
        assertThat(AppChainConsensusProfile.blockMaxForwardSkewMs(Map.of())).isEqualTo(5_000);
        assertThat(AppChainConsensusProfile.blockMaxForwardSkewMs(
                Map.of("block-time.max-forward-skew-ms", " 750 "))).isEqualTo(750);
        assertThatThrownBy(() -> AppChainConsensusProfile.blockMaxForwardSkewMs(
                Map.of("block-time.max-forward-skew-ms", "fast")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block-time.max-forward-skew-ms");
        for (long invalid : new long[] {0, -1, 600_001}) {
            assertThatThrownBy(() -> new AppChainConsensusProfile(
                    AppChainConsensusProfile.SCHEMA_VERSION,
                    65_536, 64, 4_194_304, 0, 0, invalid, false,
                    false, 0, 0, 0, 0,
                    FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                    true, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blockMaxForwardSkewMs");
        }
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
                AppChainConsensusProfile.SCHEMA_VERSION,
                65_536,
                64,
                4_194_304,
                5,
                5,
                5_000,
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
