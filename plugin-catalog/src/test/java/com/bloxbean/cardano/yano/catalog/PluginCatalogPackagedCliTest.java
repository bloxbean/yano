package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Forked-process smoke for the application-plugin CLI distribution. */
class PluginCatalogPackagedCliTest {
    private static final String INSTALL_DIRECTORY_PROPERTY =
            "yano.test.plugin-cli-install-dir";
    private static final String DISTRIBUTION_ZIP_PROPERTY =
            "yano.test.plugin-cli-dist-zip";
    private static final String INITIALIZED_SENTINEL_ENV =
            "YANO_TEST_PLUGIN_CLI_PROVIDER_INITIALIZED";
    private static final String CONSTRUCTED_SENTINEL_ENV =
            "YANO_TEST_PLUGIN_CLI_PROVIDER_CONSTRUCTED";
    private static final String BUNDLE_ID = "com.example.packaged-health";
    private static final String PROVIDER = PackagedExplodingHealthProvider.class.getName();
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path temporary;

    @Test
    void installedAndZippedLaunchersAreRunnableAndNeverLoadProviderCode() throws Exception {
        Path installDirectory = configuredPath(INSTALL_DIRECTORY_PROPERTY);
        Path launcher = launcher(installDirectory);
        Path distributionZip = configuredPath(DISTRIBUTION_ZIP_PROPERTY);
        assertDistribution(distributionZip);

        Path initialized = temporary.resolve("provider-initialized");
        Path constructed = temporary.resolve("provider-constructed");
        Path valid = pluginArtifact("valid", true);

        Result validate = run(launcher, initialized, constructed,
                "validate", valid.toString());
        assertThat(validate.exitCode()).isEqualTo(PluginCatalogCli.EXIT_OK);
        assertThat(validate.standardError()).isEmpty();
        assertThat(validate.standardOutput())
                .startsWith("VALID apiMajor=1 apiLevel=1 bundles=1 selected=1")
                .contains("fingerprint=sha256:")
                .doesNotContain(temporary.toString());

        Result inspect = run(launcher, initialized, constructed,
                "inspect", "--format", "json", valid.toString());
        assertThat(inspect.exitCode()).isEqualTo(PluginCatalogCli.EXIT_OK);
        assertThat(inspect.standardError()).isEmpty();
        JsonNode json = new ObjectMapper().readTree(inspect.standardOutput());
        assertThat(json.path("bundles").get(0).path("id").asText()).isEqualTo(BUNDLE_ID);

        Result invalid = run(launcher, initialized, constructed,
                "validate", pluginArtifact("invalid", false).toString());
        assertThat(invalid.exitCode()).isEqualTo(PluginCatalogCli.EXIT_INVALID_CATALOG);
        assertThat(invalid.standardError()).contains("Plugin catalog is invalid")
                .doesNotContain(temporary.toString());

        Result usage = run(launcher, initialized, constructed, "inspect");
        assertThat(usage.exitCode()).isEqualTo(PluginCatalogCli.EXIT_USAGE);
        assertThat(usage.standardError())
                .contains("Usage: yano-plugins validate")
                .contains("or: yano-plugins inspect");

        Path missing = temporary.resolve("private-parent").resolve("missing.jar");
        Result io = run(launcher, initialized, constructed, "validate", missing.toString());
        assertThat(io.exitCode()).isEqualTo(PluginCatalogCli.EXIT_IO);
        assertThat(io.standardError()).contains("Plugin catalog could not be read")
                .contains("missing.jar")
                .doesNotContain(temporary.toString())
                .doesNotContain("private-parent");

        assertThat(initialized).doesNotExist();
        assertThat(constructed).doesNotExist();
    }

    private static Path configuredPath(String property) {
        String value = System.getProperty(property);
        assertThat(value).as("Gradle-provided %s", property).isNotBlank();
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path launcher(Path installDirectory) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        Path launcher = installDirectory.resolve("bin")
                .resolve(windows ? "yano-plugins.bat" : "yano-plugins");
        assertThat(launcher).isRegularFile();
        if (!windows) {
            assertThat(Files.isExecutable(launcher))
                    .as("installed Unix launcher is executable").isTrue();
        }
        return launcher;
    }

    private static void assertDistribution(Path distributionZip) throws IOException {
        assertThat(distributionZip).isRegularFile();
        try (ZipFile archive = new ZipFile(distributionZip.toFile())) {
            List<String> entries = archive.stream().map(entry -> entry.getName()).toList();
            assertThat(entries).anyMatch(name -> name.endsWith("/bin/yano-plugins"));
            assertThat(entries).anyMatch(name ->
                    name.matches(".*/lib/yano-plugin-catalog-[^/]+\\.jar"));
        }
    }

    private Result run(
            Path launcher,
            Path initialized,
            Path constructed,
            String... arguments
    ) throws Exception {
        boolean windows = launcher.getFileName().toString().endsWith(".bat");
        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(launcher.toString());
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command).directory(temporary.toFile());
        builder.environment().remove("CLASSPATH");
        builder.environment().remove("JAVA_OPTS");
        builder.environment().remove("YANO_PLUGINS_OPTS");
        builder.environment().put(INITIALIZED_SENTINEL_ENV, initialized.toString());
        builder.environment().put(CONSTRUCTED_SENTINEL_ENV, constructed.toString());

        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("Packaged yano-plugins process exceeded " + PROCESS_TIMEOUT);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output, error);
    }

    private Path pluginArtifact(String name, boolean manifested) throws IOException {
        Path root = Files.createDirectories(temporary.resolve(name));
        if (manifested) {
            String manifest = """
                    {
                      "schemaVersion": 1,
                      "id": "%s",
                      "version": "1.0.0",
                      "yanoApi": {"min": 1, "max": 1, "minLevel": 1},
                      "dependencies": [],
                      "contributions": [{
                        "kind": "health",
                        "name": "%s",
                        "provider": "%s"
                      }]
                    }
                    """.formatted(BUNDLE_ID, BUNDLE_ID, PROVIDER);
            write(root, BundleManifestParser.RESOURCE_DIRECTORY + BUNDLE_ID + ".json", manifest);
        }
        write(root, "META-INF/services/" + PluginHealthProvider.class.getName(),
                PROVIDER + System.lineSeparator());
        copyProviderClass(root);
        return root;
    }

    private static void copyProviderClass(Path root) throws IOException {
        String classResource = PROVIDER.replace('.', '/') + ".class";
        try (InputStream input = PluginCatalogPackagedCliTest.class.getClassLoader()
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

    private record Result(int exitCode, String standardOutput, String standardError) {
    }

    public static final class PackagedExplodingHealthProvider implements PluginHealthProvider {
        static {
            mark(INITIALIZED_SENTINEL_ENV);
        }

        public PackagedExplodingHealthProvider() {
            mark(CONSTRUCTED_SENTINEL_ENV);
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

        private static void mark(String variable) {
            String target = System.getenv(variable);
            if (target == null || target.isBlank()) {
                return;
            }
            try {
                Files.writeString(Path.of(target), "observed", StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalStateException("provider sentinel could not be written", failure);
            }
        }
    }
}
