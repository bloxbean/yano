package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainTemplateValidatorTest {
    private final AppChainTemplateValidator validator = new AppChainTemplateValidator();

    @Test
    void indexedChainsCanCoexistWithGlobalApiConfiguration() {
        TemplateValidationResult result = validator.validate(Map.of(
                "yano.app-chain.api.auth.enabled", true,
                "yano.app-chain.chains[0].chain-id", "orders"));

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).extracting(ValidationDiagnostic::code)
                .doesNotContain("DX_CONFIG_MIXED_CHAIN_FORMS");
    }

    @Test
    void typosMalformedTopologyAndRuntimeBoundsFailWithStableCodes() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("yano.app-chain.chains[1].chain-id", "orders");
        values.put("yano.app-chain.chains[1].block.max-bytez", 1024);
        values.put("yano.app-chain.chains[1].block.max-bytes",
                AppChainConfig.MAX_BLOCK_BYTES + 1);

        TemplateValidationResult result = validator.validate(values);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("DX_CONFIG_UNKNOWN_PROPERTY", "DX_CONFIG_NON_CONTIGUOUS_CHAINS",
                        "DX_CONFIG_CONSTRAINT");
        assertThat(result.diagnostics()).extracting(ValidationDiagnostic::message)
                .anyMatch(message -> message.contains("block.max-bytes"));
    }

    @Test
    void malformedAndOutOfAdapterRangeIndicesReturnDiagnosticsInsteadOfThrowing() {
        TemplateValidationResult malformed = validator.validate(Map.of(
                "yano.app-chain.chains[x].chain-id", "orders"));
        TemplateValidationResult tooLarge = validator.validate(Map.of(
                "yano.app-chain.chains[999999999999999999999].chain-id", "orders"));
        TemplateValidationResult adapterLimit = validator.validate(Map.of(
                "yano.app-chain.chains[50].chain-id", "orders"));

        assertThat(malformed.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("DX_CONFIG_MALFORMED_INDEXED_PATH");
        assertThat(tooLarge.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("DX_CONFIG_CHAIN_INDEX_RANGE");
        assertThat(adapterLimit.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("DX_CONFIG_CHAIN_INDEX_RANGE");
    }

    @Test
    void publicLengthCollectionAndCrossFieldRulesAreEnforced() {
        String tooLong = "x".repeat(AppChainConfig.MAX_CHAIN_ID_BYTES + 1);
        List<String> tooManyMembers = java.util.stream.IntStream
                .rangeClosed(0, AppChainConfig.MAX_MEMBERS)
                .mapToObj(Integer::toString).toList();
        Map<String, Object> values = Map.of(
                "yano.app-chain.chain-id", tooLong,
                "yano.app-chain.members", tooManyMembers,
                "yano.app-chain.block.max-messages", 100,
                "yano.app-chain.pool.max-messages", 99);

        TemplateValidationResult result = validator.validate(values);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).filteredOn(item ->
                        item.code().equals("DX_CONFIG_CONSTRAINT"))
                .hasSize(2);
        assertThat(result.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("DX_CONFIG_CROSS_FIELD");
    }

    @Test
    void integerAndStringListTypesRejectYamlDecimalAndNonStringItems() {
        TemplateValidationResult result = validator.validate(Map.of(
                "yano.app-chain.chain-id", "orders",
                "yano.app-chain.block.max-messages", 10.0,
                "yano.app-chain.members", List.of("member-a", 2)));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).filteredOn(item -> item.code().equals("DX_CONFIG_TYPE"))
                .hasSize(2);
    }

    @Test
    void sharedTemplatesRejectSecretValuesButAllowReferences() {
        TemplateValidationResult literal = validator.validate(Map.of(
                "yano.app-chain.chain-id", "orders",
                "yano.app-chain.signing-key", "deadbeef"));
        TemplateValidationResult reference = validator.validate(Map.of(
                "yano.app-chain.chain-id", "orders",
                "yano.app-chain.signing-key", "${APPCHAIN_SIGNING_KEY}"));

        assertThat(literal.valid()).isFalse();
        assertThat(literal.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("DX_CONFIG_SECRET_IN_TEMPLATE");
        assertThat(reference.valid()).isTrue();
    }

    @Test
    void templateContractTurnsMissingOverlayValuesIntoExplicitUnresolvedInfo() {
        TemplateContract contract = new TemplateContract(1, "test-launcher", List.of(
                new TemplateContractRequirement(
                        "yano.app-chain.chains[*].members", "test/topology", true,
                        "Member list supplied by test launcher")));

        TemplateValidationResult result = validator.validate(Map.of(
                "yano.app-chain.chains[0].chain-id", "orders"), contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).extracting(ValidationDiagnostic::code)
                .contains("UNRESOLVED_TEMPLATE_OVERLAY")
                .doesNotContain("UNRESOLVED_NO_TEMPLATE_CONTRACT");
        assertThat(result.infoCount()).isOne();
    }

    @Test
    void templateContractRejectsDuplicatePatterns() {
        TemplateContractRequirement requirement = new TemplateContractRequirement(
                "yano.app-chain.chains[*].members", "test/topology", true,
                "Member list supplied by test launcher");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new TemplateContract(1, "duplicate", List.of(requirement, requirement)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate template-contract propertyPattern");
    }
}
