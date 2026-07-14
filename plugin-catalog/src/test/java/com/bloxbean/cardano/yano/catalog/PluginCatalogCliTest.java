package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PluginCatalogCliTest {
    private static final String BUNDLE_ID = "com.example.health";
    private static final String INITIALIZED =
            "yano.test.plugin-catalog-cli.provider-initialized";
    private static final String CONSTRUCTED =
            "yano.test.plugin-catalog-cli.provider-constructed";
    private static final String PROVIDER = PluginCatalogCliTest.class.getName()
            + "$ExplodingHealthProvider";

    @TempDir
    Path temporary;

    private final PluginCatalogCli cli = new PluginCatalogCli();

    @AfterEach
    void clearProviderProbes() {
        System.clearProperty(INITIALIZED);
        System.clearProperty(CONSTRUCTED);
    }

    @Test
    void inspectTableAndJsonAreDeterministicAndPathFree() throws Exception {
        Path artifact = healthArtifact("operator-drop");

        Result firstTable = run("plugins", "inspect", artifact.toString());
        Result secondTable = run("inspect", "--format", "table", artifact.toString());
        Result firstJson = run("inspect", "--format", "json", artifact.toString());
        Result secondJson = run("inspect", "--format", "json", artifact.toString());

        assertThat(firstTable.exit()).isZero();
        assertThat(firstTable.err()).isEmpty();
        assertThat(firstTable.out()).isEqualTo(secondTable.out())
                .contains("PLUGIN_API_MAJOR\t1")
                .contains("PLUGIN_API_LEVEL\t1")
                .contains("ID\tVERSION\tSTATUS")
                .contains(BUNDLE_ID)
                .contains("health/" + BUNDLE_ID)
                .doesNotContain(temporary.toString());

        assertThat(firstJson.exit()).isZero();
        assertThat(firstJson.out()).isEqualTo(secondJson.out())
                .doesNotContain(temporary.toString());
        JsonNode json = new ObjectMapper().readTree(firstJson.out());
        assertThat(json.path("pluginApiMajor").asInt()).isEqualTo(1);
        assertThat(json.path("pluginApiLevel").asInt()).isEqualTo(1);
        assertThat(json.path("bundles").get(0).path("id").asText()).isEqualTo(BUNDLE_ID);
        assertThat(json.path("bundles").get(0).path("source").asText())
                .isEqualTo("DIRECTORY");
        assertThat(json.path("bundles").get(0).path("digestMode").asText())
                .isEqualTo("ARTIFACT_TREE");
    }

    @Test
    void validateNeverInitializesOrConstructsProviderCode() throws Exception {
        System.clearProperty(INITIALIZED);
        System.clearProperty(CONSTRUCTED);
        Path artifact = healthArtifact("no-classloading");

        Result result = run("validate", artifact.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.out())
                .startsWith("VALID apiMajor=1 apiLevel=1 bundles=1 selected=1")
                .contains("fingerprint=sha256:");
        assertThat(System.getProperty(INITIALIZED)).isNull();
        assertThat(System.getProperty(CONSTRUCTED)).isNull();
    }

    @Test
    void catalogUsageAndIoFailuresUseDistinctStableExitCodes() throws Exception {
        Path artifact = healthArtifact("valid");

        Result usage = run("inspect", artifact.toString(), "--deny", BUNDLE_ID);
        assertThat(usage.exit()).isEqualTo(PluginCatalogCli.EXIT_USAGE);
        assertThat(usage.err()).contains("Options must precede artifact paths")
                .contains("Usage: yano-plugins");

        Path missing = temporary.resolve("private-parent").resolve("missing.jar");
        Result io = run("validate", missing.toString());
        assertThat(io.exit()).isEqualTo(PluginCatalogCli.EXIT_IO);
        assertThat(io.err()).contains("Plugin catalog could not be read")
                .contains("missing.jar")
                .doesNotContain(temporary.toString())
                .doesNotContain("private-parent");
    }

    @Test
    void malformedCatalogAndApiPolicyReturnCatalogExitCode() throws Exception {
        Path unmanifested = Files.createDirectories(temporary.resolve("legacy-health"));
        write(unmanifested, servicePath(), PROVIDER + "\n");
        writeProviderBytes(unmanifested);

        Result malformed = run("validate", unmanifested.toString());
        assertThat(malformed.exit()).isEqualTo(PluginCatalogCli.EXIT_INVALID_CATALOG);
        assertThat(malformed.err()).contains("require a bundle manifest")
                .doesNotContain(temporary.toString());

        Path valid = healthArtifact("api-policy");
        Result incompatible = run(
                "inspect", "--api-major", "2", valid.toString());
        assertThat(incompatible.exit()).isEqualTo(PluginCatalogCli.EXIT_INVALID_CATALOG);
        assertThat(incompatible.err())
                .contains("does not support Yano plugin API major 2");

        Path newLevel = healthArtifact("new-level", 2);
        Result oldHost = run("validate", newLevel.toString());
        assertThat(oldHost.exit()).isEqualTo(PluginCatalogCli.EXIT_INVALID_CATALOG);
        assertThat(oldHost.err()).contains("API major 1 level 1");

        Result currentEnough = run(
                "validate", "--api-level", "2", newLevel.toString());
        assertThat(currentEnough.exit()).isZero();
        assertThat(currentEnough.out()).startsWith("VALID apiMajor=1 apiLevel=2");

        Result invalidLevel = run(
                "inspect", "--api-level", "0", valid.toString());
        assertThat(invalidLevel.exit()).isEqualTo(PluginCatalogCli.EXIT_USAGE);
        assertThat(invalidLevel.err()).contains("--api-level must be a positive integer");
    }

    private Path healthArtifact(String name) throws IOException {
        return healthArtifact(name, 1);
    }

    private Path healthArtifact(String name, int minLevel) throws IOException {
        Path root = Files.createDirectories(temporary.resolve(name));
        String manifest = """
                {
                  "schemaVersion": 1,
                  "id": "com.example.health",
                  "version": "1.0.0",
                  "yanoApi": {"min": 1, "max": 1, "minLevel": %d},
                  "dependencies": [],
                  "contributions": [{
                    "kind": "health",
                    "name": "com.example.health",
                    "provider": "%s"
                  }]
                }
                """.formatted(minLevel, PROVIDER);
        write(root, BundleManifestParser.RESOURCE_DIRECTORY + BUNDLE_ID + ".json", manifest);
        write(root, servicePath(), PROVIDER + "\n");
        writeProviderBytes(root);
        return root;
    }

    private static String servicePath() {
        return "META-INF/services/" + PluginHealthProvider.class.getName();
    }

    private static void writeProviderBytes(Path root) throws IOException {
        String classResource = PROVIDER.replace('.', '/') + ".class";
        try (InputStream input = PluginCatalogCliTest.class.getClassLoader()
                .getResourceAsStream(classResource)) {
            assertThat(input).as("compiled provider fixture bytes").isNotNull();
            Path output = root.resolve(classResource);
            Files.createDirectories(output.getParent());
            Files.copy(input, output);
        }
    }

    private static void write(Path root, String relative, String value) throws IOException {
        Path output = root.resolve(relative);
        Files.createDirectories(output.getParent());
        Files.writeString(output, value, StandardCharsets.UTF_8);
    }

    private Result run(String... args) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exit = cli.run(args, new PrintWriter(output), new PrintWriter(error));
        return new Result(exit, output.toString(), error.toString());
    }

    private record Result(int exit, String out, String err) {
    }

    public static final class ExplodingHealthProvider implements PluginHealthProvider {
        static {
            System.setProperty(INITIALIZED, "true");
        }

        public ExplodingHealthProvider() {
            System.setProperty(CONSTRUCTED, "true");
            throw new AssertionError("offline inspection must not construct providers");
        }

        @Override
        public String id() {
            throw new AssertionError("offline inspection must not invoke providers");
        }

        @Override
        public PluginHealthSource create(PluginHealthContext context) {
            throw new AssertionError("offline inspection must not invoke providers");
        }
    }
}
