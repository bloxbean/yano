package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.AppChainResolvedValidator;
import com.bloxbean.cardano.yano.appchain.config.ConfigSourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainResolvedConfigResolverTest {
    private static final String MEMBER = "a".repeat(64);
    private static final String SIGNING_KEY = "b".repeat(64);

    @TempDir
    Path temporary;

    private final AppChainResolvedConfigResolver resolver = new AppChainResolvedConfigResolver();

    @Test
    void smallRyeResolvesDeclaredOrderProfilesExpressionsAndRuntimeDefaults() throws Exception {
        Path base = write("application.yml", """
                yano:
                  app-chain:
                    enabled: true
                    chain-id: ${APP_CHAIN_ID:orders}
                    signing-key: %s
                    members: %s
                    block:
                      max-messages: 4
                """.formatted(SIGNING_KEY, MEMBER));
        Path overlay = write("node.yaml", """
                yano:
                  app-chain:
                    block:
                      max-messages: 7
                "%prod":
                  yano:
                    app-chain:
                      block:
                        max-messages: 9
                """);

        ResolvedAppChainConfiguration resolved = resolver.resolve(
                List.of(base, overlay), "prod", false, false,
                AppChainPropertyRegistry.framework());

        assertThat(resolved.values().get("yano.app-chain.chain-id").value()).isEqualTo("orders");
        assertThat(resolved.values().get("yano.app-chain.block.max-messages").value())
                .isEqualTo("9");
        assertThat(resolved.values().get("yano.app-chain.block.max-messages").source())
                .contains("node.yaml");
        assertThat(resolved.values().get("yano.app-chain.block.max-bytes").sourceKind())
                .isEqualTo(ConfigSourceKind.RUNTIME_DEFAULT);
        assertThat(resolved.environmentIncluded()).isFalse();
        assertThat(resolved.systemPropertiesIncluded()).isFalse();
    }

    @Test
    void ambientSystemPropertiesAreExcludedUnlessExplicitlyRequested() throws Exception {
        Path base = write("application.yml", """
                yano:
                  app-chain:
                    enabled: true
                    chain-id: orders
                    signing-key: %s
                    members: %s
                    block:
                      interval-ms: 2000
                """.formatted(SIGNING_KEY, MEMBER));
        String key = "yano.app-chain.block.interval-ms";
        String previous = System.getProperty(key);
        System.setProperty(key, "9876");
        try {
            ResolvedAppChainConfiguration reproducible = resolver.resolve(
                    List.of(base), "", false, false, AppChainPropertyRegistry.framework());
            ResolvedAppChainConfiguration parity = resolver.resolve(
                    List.of(base), "", false, true, AppChainPropertyRegistry.framework());

            assertThat(reproducible.values().get(key).value()).isEqualTo("2000");
            assertThat(parity.values().get(key).value()).isEqualTo("9876");
            assertThat(parity.values().get(key).sourceKind())
                    .isEqualTo(ConfigSourceKind.SYSTEM_PROPERTIES);
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void indexedChainsBecomeEnabledAndReceiveParserDefaults() throws Exception {
        Path source = write("chains.yml", """
                yano:
                  app-chain:
                    chains:
                      - chain-id: orders
                        signing-key: %s
                        members: %s
                """.formatted(SIGNING_KEY, MEMBER));

        ResolvedAppChainConfiguration resolved = resolver.resolve(
                List.of(source), "", false, false, AppChainPropertyRegistry.framework());

        assertThat(resolved.values().get("yano.app-chain.enabled").value()).isEqualTo("true");
        assertThat(resolved.values().get("yano.app-chain.enabled").sourceKind())
                .isEqualTo(ConfigSourceKind.RUNTIME_DERIVED);
        assertThat(resolved.values().get(
                "yano.app-chain.chains[0].block.max-bytes").value()).isEqualTo("4194304");
    }

    @Test
    void registryDefinedGlobalPropertiesDoNotCreateAMixedChainForm() throws Exception {
        Path source = write("generated.yaml", """
                yano:
                  app-chain:
                    chains:
                      - chain-id: orders
                        signing-key: %s
                        members: %s
                    validation:
                      strict: true
                    dx:
                      resolved-config-digest: %s
                      release-catalog-digest: %s
                """.formatted(SIGNING_KEY, MEMBER, "c".repeat(64), "d".repeat(64)));
        AppChainPropertyRegistry registry = AppChainPropertyRegistry.framework();

        ResolvedAppChainConfiguration resolved = resolver.resolve(
                List.of(source), "", false, false, registry);
        var validation = new AppChainResolvedValidator(registry, List.of())
                .validate(resolved.values());

        assertThat(validation.valid()).isTrue();
        assertThat(validation.diagnostics())
                .noneMatch(diagnostic -> diagnostic.code().equals("DX_CONFIG_MIXED_CHAIN_FORMS"));
    }

    @Test
    void propertiesFilesAreNotPartOfThePublicResolvedConfigurationContract() throws Exception {
        Path source = write("legacy.properties", "yano.app-chain.enabled=true\n");

        assertThatThrownBy(() -> resolver.resolve(
                List.of(source), "", false, false, AppChainPropertyRegistry.framework()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("must use .yml or .yaml");
    }

    private Path write(String name, String value) throws Exception {
        Path target = temporary.resolve(name);
        Files.writeString(target, value, StandardCharsets.UTF_8);
        return target;
    }
}
