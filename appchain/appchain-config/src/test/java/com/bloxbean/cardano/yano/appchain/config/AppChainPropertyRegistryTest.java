package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainPropertyRegistryTest {
    @Test
    void frameworkRegistryCoversEveryPublicRuntimeKey() {
        Set<String> registered = AppChainPropertyRegistry.framework().definitions().stream()
                .map(AppChainPropertyDefinition::key)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(registered).containsAll(AppChainPropertyRegistry.runtimeKeyConstants());
        assertThat(registered).contains(
                "yano.app-chain.effects.enabled",
                "yano.app-chain.effects.default-gate",
                "yano.app-chain.effects.result.signers");
    }

    @Test
    void runtimeBackedDefaultsBoundsAndCoverageRemainExplicit() {
        AppChainPropertyRegistry registry = AppChainPropertyRegistry.framework();

        AppChainPropertyDefinition blockBytes = registry
                .find(YanoPropertyKeys.AppChain.BLOCK_MAX_BYTES).orElseThrow().definition();
        assertThat(blockBytes.defaultValue())
                .isEqualTo(Long.toString(AppChainConfig.DEFAULT_BLOCK_MAX_BYTES));
        assertThat(blockBytes.maximum()).isEqualTo(AppChainConfig.MAX_BLOCK_BYTES);
        assertThat(blockBytes.constraintProvenance())
                .isEqualTo(ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION);
        assertThat(blockBytes.coverage()).isEqualTo(ValidationCoverage.PARTIAL);

        AppChainPropertyDefinition chainId = registry
                .find(YanoPropertyKeys.AppChain.CHAIN_ID).orElseThrow().definition();
        assertThat(chainId.minimumUtf8Bytes()).isOne();
        assertThat(chainId.maximumUtf8Bytes()).isEqualTo(AppChainConfig.MAX_CHAIN_ID_BYTES);

        assertThat(registry.dynamicNamespaces()).extracting(DynamicNamespaceDefinition::prefix)
                .containsExactly("effects.", "effects.result.", "machines.", "membership.",
                        "observers.", "sequencer.", "sinks.", "transport.", "zk.");
        assertThat(registry.dynamicNamespace("effects.result.unknown"))
                .get().extracting(DynamicNamespaceDefinition::coverage)
                .isEqualTo(ValidationCoverage.FULL);
        assertThat(registry.dynamicNamespace("effects.executors.custom.option"))
                .get().extracting(DynamicNamespaceDefinition::coverage)
                .isEqualTo(ValidationCoverage.PARTIAL);
        assertThat(registry.dynamicNamespaces().stream()
                .filter(namespace -> namespace.coverage() == ValidationCoverage.FULL)
                .map(DynamicNamespaceDefinition::prefix))
                .containsExactlyInAnyOrderElementsOf(
                        AppChainConfigParser.strictOwnershipDomains());
        assertThat(registry.definitions().stream()
                .map(AppChainPropertyDefinition::suffix)
                .filter(suffix -> AppChainConfigParser.strictOwnershipDomains().stream()
                        .anyMatch(suffix::startsWith)))
                .containsExactlyInAnyOrderElementsOf(AppChainConfigParser.strictProperties());

        AppChainPropertyDefinition effects = registry
                .find("effects.max-per-block").orElseThrow().definition();
        assertThat(effects.coverage()).isEqualTo(ValidationCoverage.FULL);
        assertThat(effects.constraintProvenance())
                .isEqualTo(ConstraintProvenance.RUNTIME_PARSER_TEST);
    }

    @Test
    void declarativeComponentMetadataExtendsExactPropertiesWithoutCliChanges() {
        AppChainPropertyDefinition custom = customProperty("custom-effects");
        DynamicNamespaceDefinition customNamespace = new DynamicNamespaceDefinition(
                "effects.executors.custom.", "custom-effects", ValidationCoverage.PARTIAL,
                "Custom effect executor settings");
        AppChainPropertyRegistry registry = AppChainPropertyRegistry.withSources(List.of(
                new AppChainMetadataSource(
                        "custom-effects", List.of(custom), List.of(customNamespace))));

        assertThat(registry.find(custom.key())).isPresent()
                .get().extracting(match -> match.definition().owner())
                .isEqualTo("custom-effects");
        assertThat(registry.sources()).extracting(AppChainMetadataSource::id)
                .containsExactly(AppChainPropertyRegistry.OWNER_CORE, "custom-effects");
        assertThat(registry.dynamicNamespace(
                        "yano.app-chain.effects.executors.custom.batch-size"))
                .isPresent().get().extracting(DynamicNamespaceDefinition::owner)
                .isEqualTo("custom-effects");
    }

    @Test
    void conflictingComponentClaimsFailClosed() {
        AppChainPropertyDefinition custom = new AppChainPropertyDefinition(
                YanoPropertyKeys.AppChain.BLOCK_MAX_BYTES, AppChainPropertyRegistry.OWNER_CORE,
                PropertyType.LONG, null, null, null, null, null, null, Set.of(),
                PropertyScope.CONSENSUS_SHARED, ChangePolicy.NEW_CHAIN_REQUIRED, false, true,
                ConstraintProvenance.NOT_APPLICABLE, ValidationCoverage.PARTIAL,
                "Conflicting definition");
        assertThatThrownBy(() -> AppChainPropertyRegistry.withSources(List.of(
                new AppChainMetadataSource("duplicate", List.of(custom), List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate property definition");

        DynamicNamespaceDefinition duplicate = new DynamicNamespaceDefinition(
                "effects.", "custom-effects", ValidationCoverage.PARTIAL, "Duplicate claim");
        assertThatThrownBy(() -> AppChainPropertyRegistry.withSources(List.of(
                new AppChainMetadataSource("custom-effects", List.of(), List.of(duplicate)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claimed by both");

        assertThatThrownBy(() -> AppChainPropertyRegistry.withSources(List.of(
                new AppChainMetadataSource("same-id", List.of(), List.of()),
                new AppChainMetadataSource("same-id", List.of(), List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate metadata source id");

        DynamicNamespaceDefinition strictChild = new DynamicNamespaceDefinition(
                "effects.result.custom.", "custom-effects", ValidationCoverage.PARTIAL,
                "Invalid strict-domain extension");
        assertThatThrownBy(() -> AppChainPropertyRegistry.withSources(List.of(
                new AppChainMetadataSource(
                        "strict-child", List.of(), List.of(strictChild)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be extended");
    }

    private static AppChainPropertyDefinition customProperty(String owner) {
        return new AppChainPropertyDefinition(
                "yano.app-chain.effects.executors.custom.timeout-ms", owner,
                PropertyType.LONG, "1000", 1L, 60_000L,
                null, null, null, Set.of(), PropertyScope.NODE_LOCAL,
                ChangePolicy.RESTART_REQUIRED, false, true,
                ConstraintProvenance.DOCUMENTED_UNVERIFIED,
                ValidationCoverage.PARTIAL, "Custom effect timeout");
    }
}
