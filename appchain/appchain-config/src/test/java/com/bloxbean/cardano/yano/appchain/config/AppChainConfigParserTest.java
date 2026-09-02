package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainConfigParserTest {
    private static final String MEMBER = "a".repeat(64);
    private static final String SIGNING_KEY = "b".repeat(64);

    @Test
    void parsesTheRuntimeShapeAndForwardsOnlyDeclaredExtensionNamespaces() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("chain-id", "orders");
        values.put("signing-key", SIGNING_KEY);
        values.put("members", MEMBER);
        values.put("sequencer.proposer", MEMBER);
        values.put("block.max-messages", "12");
        values.put("effects.enabled", "true");
        values.put("effects.max-per-block", "42");
        values.put("observation.l1-network-genesis-id", "01".repeat(32));
        values.put("state.commitment-profile", "mpf-blake2b256-v1");
        values.put("capabilities.authenticated-snapshots.enabled", "true");
        values.put("unowned.value", "ignored");

        AppChainConfig config = AppChainConfigParser.parse(values);

        assertThat(config.chainId()).isEqualTo("orders");
        assertThat(config.maxBlockMessages()).isEqualTo(12);
        assertThat(config.blockMaxBytes()).isEqualTo(AppChainConfig.DEFAULT_BLOCK_MAX_BYTES);
        assertThat(config.pluginSettings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "sequencer.proposer", MEMBER,
                "effects.enabled", "true",
                "effects.max-per-block", "42",
                "observation.l1-network-genesis-id", "01".repeat(32),
                "state.commitment-profile", "mpf-blake2b256-v1",
                "capabilities.authenticated-snapshots.enabled", "true"));
        assertThat(AppChainConfigSemantics.validate(config)).containsExactly(MEMBER);
    }

    @Test
    void sharedFrameworkAndEffectsRulesFailBeforeSideEffects() {
        Map<String, Object> invalidMember = base();
        invalidMember.put("members", "not-hex");
        assertThatThrownBy(() -> AppChainConfigSemantics.validate(
                AppChainConfigParser.parse(invalidMember)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("member key")
                .hasMessageContaining("not-hex");

        Map<String, Object> invalidEffects = base();
        invalidEffects.put("effects.enabled", "true");
        invalidEffects.put("effects.max-payload-bytes", "16777217");
        AppChainConfig parsed = AppChainConfigParser.parse(invalidEffects);
        assertThatThrownBy(() -> AppChainEffectsConfig.from(parsed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16777216");
    }

    @Test
    void semanticFailuresIdentifyTheOffendingPublicProposer() {
        Map<String, Object> values = base();
        String proposer = "c".repeat(64);
        values.put("sequencer.proposer", proposer);

        assertThatThrownBy(() -> AppChainConfigSemantics.validate(
                AppChainConfigParser.parse(values)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(proposer);
    }

    @Test
    void strictValidationRejectsUnknownKeysOnlyInFullyOwnedDomains() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("effects.result.signers", MEMBER);
        settings.put("effects.executors.custom.endpoint", "plugin-owned");
        settings.put("machines.custom.option", "plugin-owned");

        AppChainConfigParser.validateStrict(settings);

        settings.put("effects.result.signerz", MEMBER);
        assertThatThrownBy(() -> AppChainConfigParser.validateStrict(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effects.result.signerz");
        assertThat(AppChainConfigParser.strictOwnershipDomains())
                .containsExactlyInAnyOrder("effects.result.", "state.");

        settings.remove("effects.result.signerz");
        settings.put("state.genesis-idd", "ab".repeat(32));
        assertThatThrownBy(() -> AppChainConfigParser.parse(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state.genesis-idd");
    }

    @Test
    void forwardsAndValidatesTheCompleteConsensusStateIdentity() {
        Map<String, Object> values = base();
        values.put(StateCommitmentIdentity.PROFILE_SETTING, "mpf-blake2b256-v1");
        values.put(StateCommitmentIdentity.FINGERPRINT_SETTING,
                java.util.HexFormat.of().formatHex(
                        StateCommitmentProfiles.MPF.formatFingerprint()));
        values.put(StateCommitmentIdentity.GENESIS_ID_SETTING, "ab".repeat(32));
        values.put(StateCommitmentIdentity.L1_PROOF_REQUIRED_SETTING, "true");
        values.put("state.proof-pruning.enabled", "true");
        values.put("state.proof-pruning.retain-heights", "10000");
        values.put("state.proof-pruning.interval-seconds", "3600");

        AppChainConfig config = AppChainConfigParser.parse(values);
        StateCommitmentIdentity identity = StateCommitmentIdentity.fromSettings(
                config.pluginSettings());

        assertThat(identity.profile().id()).isEqualTo("mpf-blake2b256-v1");
        assertThat(identity.genesisId()).isEqualTo(java.util.HexFormat.of()
                .parseHex("ab".repeat(32)));
        assertThat(config.pluginSettings())
                .containsEntry(StateCommitmentIdentity.L1_PROOF_REQUIRED_SETTING, "true");
        assertThat(config.pluginSettings())
                .containsEntry("state.proof-pruning.enabled", "true")
                .containsEntry("state.proof-pruning.retain-heights", "10000")
                .containsEntry("state.proof-pruning.interval-seconds", "3600");
    }

    private static Map<String, Object> base() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("chain-id", "orders");
        values.put("signing-key", SIGNING_KEY);
        values.put("members", MEMBER);
        values.put("threshold", "1");
        return values;
    }
}
