package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainMetadataDescriptor;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyDefinition;
import com.bloxbean.cardano.yano.appchain.config.ChangePolicy;
import com.bloxbean.cardano.yano.appchain.config.ConstraintProvenance;
import com.bloxbean.cardano.yano.appchain.config.PropertyScope;
import com.bloxbean.cardano.yano.appchain.config.PropertyType;
import com.bloxbean.cardano.yano.appchain.config.ValidationCoverage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainDevtoolsCliTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();
    private final AppChainDevtoolsCli cli = new AppChainDevtoolsCli();

    @Test
    void maintainedClusterTemplateValidatesAgainstBuiltInContract() throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        Path config = repository.resolve("app/config/application-appchain.yml");

        Result result = run("config", "validate", "--mode", "template",
                "--template-contract", "builtin:cluster", config.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out())
                .contains("VALID_TEMPLATE mode=template")
                .contains("contract=yano-appchain-cluster-v1")
                .contains("UNRESOLVED_TEMPLATE_OVERLAY")
                .doesNotContain(repository.toString());
    }

    @Test
    void validationJsonIsMachineReadableAndNeverContainsValues() throws Exception {
        Path config = write("application-secret.yml", """
                yano:
                  app-chain:
                    chain-id: orders
                    signing-key: super-secret-value
                """);

        Result result = run("appchain", "config", "validate", "--mode", "template",
                "--format", "json", config.toString());

        assertThat(result.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(result.out()).doesNotContain("super-secret-value");
        JsonNode output = json.readTree(result.out());
        assertThat(output.path("status").asText()).isEqualTo("INVALID_TEMPLATE");
        assertThat(output.path("errorCount").asInt()).isOne();
        assertThat(output.path("diagnostics").get(0).path("code").asText())
                .isEqualTo("DX_CONFIG_SECRET_IN_TEMPLATE");
    }

    @Test
    void capabilitiesUseAWrappedAsciiTableWhileJsonRemainsStructured() throws Exception {
        Result text = run("appchain", "capabilities");
        Result structured = run("appchain", "capabilities", "--format", "json");

        assertThat(text.exit()).isZero();
        assertThat(text.err()).isEmpty();
        assertThat(text.out())
                .contains("| CAPABILITY")
                .contains("| CATEGORY")
                .contains("| AVAILABILITY")
                .contains("| Description: Append-only ordered application messages.")
                .contains("FIRST_PARTY_OPTIONAL")
                .contains("state:credential-registry");
        assertThat(text.out().lines().toList())
                .allMatch(line -> line.length() == 114);
        assertThat(text.out().lines()
                .filter(line -> line.startsWith("| Description: ")).count()).isEqualTo(32);

        assertThat(structured.exit()).isZero();
        assertThat(structured.err()).isEmpty();
        JsonNode output = json.readTree(structured.out());
        assertThat(output.path("status").asText()).isEqualTo("CAPABILITY_CATALOG");
        assertThat(output.path("capabilities")).hasSize(32);
    }

    @Test
    void explainSupportsExactPropertiesAndPartialNamespaces() throws Exception {
        Result property = run("config", "explain", "--format", "json", "block.max-bytes");
        Result firstEnum = run("config", "explain", "--format", "json", "anchor.mode");
        Result secondEnum = run("config", "explain", "--format", "json", "anchor.mode");
        Result namespace = run("config", "explain", "effects.default-gate");
        Result firstParty = run("config", "explain",
                "machines.kv-registry.value-format");
        Result unknown = run("config", "explain", "block.max-bytez");

        assertThat(property.exit()).isZero();
        JsonNode propertyJson = json.readTree(property.out());
        assertThat(propertyJson.path("property").path("key").asText())
                .isEqualTo("yano.app-chain.block.max-bytes");
        assertThat(propertyJson.path("property").path("maximum").asLong()).isPositive();
        assertThat(firstEnum.out()).isEqualTo(secondEnum.out());
        assertThat(json.readTree(firstEnum.out()).path("property").path("allowedValues"))
                .extracting(JsonNode::asText).containsExactly("metadata", "script");
        assertThat(namespace.exit()).isZero();
        assertThat(namespace.out()).contains("PROPERTY\tyano.app-chain.effects.default-gate")
                .contains("COVERAGE\tFULL")
                .contains("PROVENANCE\tRUNTIME_PARSER_TEST");
        assertThat(firstParty.exit()).isZero();
        assertThat(firstParty.out())
                .contains("OWNER\tyano-first-party/appchain-stdlib")
                .contains("BOUNDS\tallowed=[cbor, raw, utf8]")
                .contains("COVERAGE\tPARTIAL");
        assertThat(unknown.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(unknown.out()).contains("did you mean");
    }

    @Test
    void customPluginJarContributesDataOnlyMetadataAndAdvisoryBounds() throws Exception {
        Path plugin = pluginMetadataJar(ConstraintProvenance.DOCUMENTED_UNVERIFIED);
        Path config = write("custom.yml", """
                yano:
                  app-chain:
                    chain-id: orders
                    effects:
                      executors:
                        custom:
                          timeout-ms: 70000
                """);

        Result validate = run("config", "validate", "--mode", "template",
                "--metadata", plugin.toString(), config.toString());
        Result explain = run("config", "explain", "--metadata", plugin.toString(),
                "effects.executors.custom.timeout-ms");

        assertThat(validate.exit()).isZero();
        assertThat(validate.out()).contains("DX_CONFIG_UNVERIFIED_CONSTRAINT")
                .contains("provenance=DOCUMENTED_UNVERIFIED");
        assertThat(explain.exit()).isZero();
        assertThat(explain.out()).contains("OWNER\tcom.example.custom-effects")
                .contains("BOUNDS\tmin=1 max=60000");
    }

    @Test
    void externalMetadataCannotSelfAssertRuntimeVerifiedBounds() throws Exception {
        Path plugin = pluginMetadataJar(ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION);

        Result result = run("config", "explain", "--metadata", plugin.toString(),
                "effects.executors.custom.timeout-ms");

        assertThat(result.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(result.err()).contains("cannot claim runtime-verified constraint provenance");
    }

    @Test
    void externalMetadataCannotSelfAssertFullCoverage() throws Exception {
        Path plugin = pluginMetadataJar(
                ConstraintProvenance.DOCUMENTED_UNVERIFIED, ValidationCoverage.FULL);

        Result result = run("config", "explain", "--metadata", plugin.toString(),
                "effects.executors.custom.timeout-ms");

        assertThat(result.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(result.err()).contains("cannot claim FULL validation coverage");
    }

    @Test
    void usageAndIoFailuresHaveStableDistinctExitCodes() {
        Result usage = run("config", "validate", "missing.yml");
        Result io = run("config", "validate", "--mode", "template",
                temporary.resolve("private").resolve("missing.yml").toString());

        assertThat(usage.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(usage.err()).contains("validate requires --mode")
                .contains("Usage: ./yano.sh appchain");
        assertThat(io.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_IO);
        assertThat(io.err()).contains("could not be read")
                .doesNotContain(temporary.toString())
                .doesNotContain("private");
    }

    @Test
    void helpIsAvailableAtEachDispatchDepthWithoutRequiringAFile() {
        Result root = run("--help");
        Result config = run("config", "--help");
        Result command = run("config", "validate", "--help");

        assertThat(root.exit()).isZero();
        assertThat(config.exit()).isZero();
        assertThat(command.exit()).isZero();
        assertThat(root.out()).isEqualTo(config.out()).isEqualTo(command.out())
                .contains("Usage: ./yano.sh appchain config validate");
    }

    @Test
    void roleSigningCommandsAreOfflineAndAcceptSecretsOnlyByFile() throws Exception {
        Path seed = temporary.resolve("actor.seed");
        String secret = "11".repeat(32);
        Files.writeString(seed, secret);

        Result publicKey = run("appchain", "role", "public-key",
                "--seed-file", seed.toString());
        Result inline = run("appchain", "role", "public-key", "--seed", secret);

        assertThat(publicKey.exit()).isZero();
        assertThat(publicKey.err()).isEmpty();
        assertThat(publicKey.out().trim()).matches("[0-9a-f]{64}")
                .doesNotContain(secret);
        assertThat(inline.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(inline.err()).doesNotContain(secret);
    }

    @Test
    void resolvedValidationUsesRuntimeSemanticsWithoutPrintingSecrets() throws Exception {
        String member = "a".repeat(64);
        String secret = "b".repeat(64);
        Path config = write("resolved.yml", """
                yano:
                  app-chain:
                    enabled: true
                    chain-id: orders
                    signing-key: %s
                    members: %s
                    threshold: 1
                    effects:
                      enabled: true
                      max-per-block: 4
                """.formatted(secret, member));

        Result result = run("config", "validate", "--mode", "resolved",
                "--format", "json", "--config", config.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).doesNotContain(secret).doesNotContain(member);
        JsonNode output = json.readTree(result.out());
        assertThat(output.path("status").asText()).isEqualTo("VALID_RESOLVED");
        assertThat(output.path("chainCount").asInt()).isOne();
        assertThat(output.path("environmentIncluded").asBoolean()).isFalse();
    }

    @Test
    void resolvedValidationReportsTheSharedEffectsParserFailure() throws Exception {
        Path config = write("invalid-effects.yml", """
                yano:
                  app-chain:
                    enabled: true
                    chain-id: orders
                    signing-key: %s
                    members: %s
                    effects:
                      enabled: true
                      max-payload-bytes: 16777217
                """.formatted("b".repeat(64), "a".repeat(64)));

        Result result = run("config", "validate", "--mode", "resolved",
                "--config", config.toString());

        assertThat(result.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(result.out()).contains("DX_CONFIG_RUNTIME_SEMANTICS")
                .contains("effects.max-payload-bytes must be <= 16777216")
                .contains("INVALID_RESOLVED");
    }

    @Test
    void effectiveOutputRedactsSecretsAndShowsSafeProvenance() throws Exception {
        String secret = "b".repeat(64);
        Path config = write("private-node.yml", """
                yano:
                  app-chain:
                    enabled: true
                    chain-id: orders
                    signing-key: %s
                    members: %s
                """.formatted(secret, "a".repeat(64)));

        Result result = run("config", "effective", "--mode", "resolved",
                "--format", "json", "--show-sources", "--config", config.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).doesNotContain(secret).doesNotContain(temporary.toString());
        JsonNode output = json.readTree(result.out());
        JsonNode signingKey = output.path("values").path("yano.app-chain.signing-key");
        assertThat(signingKey.path("value").asText()).isEqualTo("<redacted>");
        assertThat(signingKey.path("source").asText()).contains("private-node.yml");
        assertThat(output.path("values").path("yano.app-chain.block.max-bytes")
                .path("explicit").asBoolean()).isFalse();
    }

    private Path pluginMetadataJar(ConstraintProvenance provenance) throws Exception {
        return pluginMetadataJar(provenance, ValidationCoverage.PARTIAL);
    }

    private Path pluginMetadataJar(
            ConstraintProvenance provenance,
            ValidationCoverage coverage) throws Exception {
        String owner = "com.example.custom-effects";
        AppChainPropertyDefinition property = new AppChainPropertyDefinition(
                "yano.app-chain.effects.executors.custom.timeout-ms", owner,
                PropertyType.LONG, "1000", 1L, 60_000L,
                null, null, null, Set.of(), PropertyScope.NODE_LOCAL,
                ChangePolicy.RESTART_REQUIRED, false, true,
                provenance,
                coverage, "Custom effect timeout");
        AppChainMetadataDescriptor descriptor = new AppChainMetadataDescriptor(
                1, owner, List.of(property), List.of());
        Path artifact = temporary.resolve("custom-plugin.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
            zip.putNextEntry(new ZipEntry(AppChainMetadataDescriptor.RESOURCE_PATH));
            zip.write(json.writeValueAsBytes(descriptor));
            zip.closeEntry();
        }
        return artifact;
    }

    private Path write(String name, String value) throws Exception {
        Path target = temporary.resolve(name);
        Files.writeString(target, value, StandardCharsets.UTF_8);
        return target;
    }

    private Result run(String... args) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exit = cli.run(args, new PrintWriter(output), new PrintWriter(error));
        return new Result(exit, output.toString(), error.toString());
    }

    private record Result(int exit, String out, String err) {
    }
}
