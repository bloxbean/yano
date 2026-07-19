package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
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
        values.put("unowned.value", "ignored");

        AppChainConfig config = AppChainConfigParser.parse(values);

        assertThat(config.chainId()).isEqualTo("orders");
        assertThat(config.maxBlockMessages()).isEqualTo(12);
        assertThat(config.blockMaxBytes()).isEqualTo(AppChainConfig.DEFAULT_BLOCK_MAX_BYTES);
        assertThat(config.pluginSettings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "sequencer.proposer", MEMBER,
                "effects.enabled", "true",
                "effects.max-per-block", "42"));
        assertThat(AppChainConfigSemantics.validate(config)).containsExactly(MEMBER);
    }

    @Test
    void sharedFrameworkAndEffectsRulesFailBeforeSideEffects() {
        Map<String, Object> invalidMember = base();
        invalidMember.put("members", "not-hex");
        assertThatThrownBy(() -> AppChainConfigSemantics.validate(
                AppChainConfigParser.parse(invalidMember)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("member key");

        Map<String, Object> invalidEffects = base();
        invalidEffects.put("effects.enabled", "true");
        invalidEffects.put("effects.max-payload-bytes", "16777217");
        AppChainConfig parsed = AppChainConfigParser.parse(invalidEffects);
        assertThatThrownBy(() -> AppChainEffectsConfig.from(parsed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16777216");
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
                .containsExactly("effects.result.");
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
