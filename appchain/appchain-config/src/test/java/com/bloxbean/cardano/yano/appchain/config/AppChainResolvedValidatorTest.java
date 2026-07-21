package com.bloxbean.cardano.yano.appchain.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainResolvedValidatorTest {
    private static final String MEMBER = "a".repeat(64);
    private static final String SIGNING_KEY = "b".repeat(64);

    @Test
    void validatesIndexedChainsWithTheSharedRuntimeParsers() {
        Map<String, EffectiveConfigValue> values = values(Map.of(
                "yano.app-chain.enabled", "true",
                "yano.app-chain.chains[0].chain-id", "orders",
                "yano.app-chain.chains[0].signing-key", SIGNING_KEY,
                "yano.app-chain.chains[0].members", MEMBER,
                "yano.app-chain.chains[0].threshold", "1",
                "yano.app-chain.chains[0].effects.enabled", "true",
                "yano.app-chain.chains[0].effects.max-per-block", "4",
                "yano.app-chain.validation.strict", "true",
                "yano.app-chain.dx.resolved-config-digest", "c".repeat(64),
                "yano.app-chain.dx.release-catalog-digest", "d".repeat(64)));

        ResolvedValidationResult result = new AppChainResolvedValidator().validate(values);

        assertThat(result.valid()).isTrue();
        assertThat(result.chainCount()).isOne();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void semanticExtensionsAreExplicitlyRegisteredAndCannotCollideById() {
        AppChainSemanticValidator custom = new AppChainSemanticValidator() {
            @Override
            public String id() {
                return "custom-policy";
            }

            @Override
            public List<ValidationDiagnostic> validate(AppChainValidationContext context) {
                return List.of(new ValidationDiagnostic(
                        "CUSTOM_POLICY_CHECKED", ValidationSeverity.INFO,
                        context.chainPath(), "Custom policy checked"));
            }
        };
        Map<String, EffectiveConfigValue> values = values(Map.of(
                "yano.app-chain.enabled", "true",
                "yano.app-chain.chain-id", "orders",
                "yano.app-chain.signing-key", SIGNING_KEY,
                "yano.app-chain.members", MEMBER));

        ResolvedValidationResult result = new AppChainResolvedValidator(
                AppChainPropertyRegistry.framework(), List.of(custom)).validate(values);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).extracting(ValidationDiagnostic::code)
                .containsExactly("CUSTOM_POLICY_CHECKED");
    }

    @Test
    void invalidRuntimeSemanticsAndUnknownKeysUseStableDiagnostics() {
        Map<String, EffectiveConfigValue> values = values(Map.of(
                "yano.app-chain.enabled", "true",
                "yano.app-chain.chain-id", "orders",
                "yano.app-chain.signing-key", SIGNING_KEY,
                "yano.app-chain.members", MEMBER,
                "yano.app-chain.threshold", "2",
                "yano.app-chain.block.max-bytez", "10"));

        ResolvedValidationResult result = new AppChainResolvedValidator().validate(values);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("DX_CONFIG_RUNTIME_SEMANTICS", "DX_CONFIG_UNKNOWN_PROPERTY");
        assertThat(result.diagnostics().toString()).doesNotContain(SIGNING_KEY);
    }

    @Test
    void removedApprovalsPaymentConfigurationFailsThroughTheSharedRuntimeParser() {
        Map<String, EffectiveConfigValue> values = values(Map.of(
                "yano.app-chain.enabled", "true",
                "yano.app-chain.chain-id", "orders",
                "yano.app-chain.signing-key", SIGNING_KEY,
                "yano.app-chain.members", MEMBER,
                "yano.app-chain.machines.approvals.payments", "true"));

        ResolvedValidationResult result = new AppChainResolvedValidator().validate(values);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "DX_CONFIG_RUNTIME_SEMANTICS".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message())
                        .contains("Unsupported pre-release approvals setting")
                        .contains("machines.approvals.on-approved-effect"));
    }

    @Test
    void fullOwnedNamespaceRejectsTyposWhileOpenPluginNamespacesRemainCompatible() {
        Map<String, EffectiveConfigValue> values = values(Map.of(
                "yano.app-chain.enabled", "true",
                "yano.app-chain.chain-id", "orders",
                "yano.app-chain.signing-key", SIGNING_KEY,
                "yano.app-chain.members", MEMBER,
                "yano.app-chain.effects.result.signerz", MEMBER,
                "yano.app-chain.effects.executors.custom.option", "kept"));

        ResolvedValidationResult result = new AppChainResolvedValidator().validate(values);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "yano.app-chain.effects.result.signerz"
                        .equals(diagnostic.key()))
                .extracting(ValidationDiagnostic::code)
                .containsExactly("DX_CONFIG_UNKNOWN_PROPERTY");
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "yano.app-chain.effects.executors.custom.option"
                        .equals(diagnostic.key()))
                .extracting(ValidationDiagnostic::code)
                .containsExactly("DX_CONFIG_PARTIAL_NAMESPACE");
    }

    private static Map<String, EffectiveConfigValue> values(Map<String, String> input) {
        Map<String, EffectiveConfigValue> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, new EffectiveConfigValue(
                key, value, "test", ConfigSourceKind.DECLARED_FILE, 250, true, "")));
        return result;
    }
}
