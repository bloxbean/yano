package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
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

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainCustomComponentCatalogTest {
    private static final String SEED = "11".repeat(32);

    @TempDir
    Path temporary;

    @Test
    void signedCatalogInitializesPinsAndRevalidatesAProjectWithoutLoadingPluginCode()
            throws Exception {
        Fixture fixture = fixture("custom-sample", null);
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();

        AppChainComponentCatalogLoader.Loaded loaded = loader.loadJar(
                fixture.jar(), Map.of("test-publisher", fixture.publicKey()));
        assertThat(loaded.catalog().capabilities()).extracting(
                AppChainProjectModel.Capability::id).containsExactly("state:custom-sample");
        assertThat(loaded.snapshot().artifactSha256())
                .isEqualTo(AppChainProjectCatalog.sha256(Files.readAllBytes(fixture.jar())));

        Path project = temporary.resolve("project");
        StringWriter output = new StringWriter();
        AppChainProjectCli cli = new AppChainProjectCli(
                new BufferedReader(new StringReader("")), new PrintWriter(output, true),
                AppChainPropertyRegistry.framework(), new ObjectMapper());
        int exit = cli.run(new String[]{"appchain", "init", "--non-interactive",
                "--recipe", "custom-plugin", "--network", "devnet", "--members", "1",
                "--runtime", "jvm", "--capability", "state:custom-sample",
                "--plugin-jar", fixture.jar().toString(), "--trust-key",
                "test-publisher=" + fixture.publicKey(), "--output", project.toString()});

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("PROJECT_INITIALIZED");
        AppChainProjectModel.ProjectValidation validation = AppChainProjectLifecycle
                .forProject(AppChainPropertyRegistry.framework(), project).validate(project);
        assertThat(validation.lock().selectedCapabilities()).contains("state:custom-sample");
        assertThat(validation.lock().artifacts()).contains("plugin.custom-sample");
        assertThat(validation.lock().catalogDigests()).containsKeys(
                "external.custom-sample-plugin.snapshot",
                "external.custom-sample-plugin.catalog",
                "external.custom-sample-plugin.runtimeManifest",
                "external.custom-sample-plugin.configurationMetadata",
                "external.custom-sample-plugin.artifact");

        Path snapshot = project.resolve("component-catalogs/custom-sample-plugin.json");
        byte[] original = Files.readAllBytes(snapshot);
        JsonNode tampered = new ObjectMapper().readTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered)
                .put("artifactSha256", "00".repeat(32));
        Files.write(snapshot, new ObjectMapper().writeValueAsBytes(tampered));
        assertThatThrownBy(() -> AppChainProjectLifecycle.forProject(
                AppChainPropertyRegistry.framework(), project).validate(project))
                .hasMessageContaining("catalog digests");
    }

    @Test
    void trustTamperingCollisionsAndUnsupportedClaimsFailClosed() throws Exception {
        Fixture valid = fixture("custom-sample", null);
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        assertThatThrownBy(() -> loader.loadJar(valid.jar(), Map.of()))
                .hasMessageContaining("trusted");
        assertThatThrownBy(() -> loader.loadJar(valid.jar(),
                Map.of("test-publisher", "22".repeat(32))))
                .hasMessageContaining("verification failed");

        Fixture collision = fixture("collision", catalog -> {
            catalog.withArray("capabilities").get(0).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)
                    catalog.withArray("capabilities").get(0)).put("id", "state:ordered-log");
        });
        AppChainComponentCatalogLoader.Loaded collisionLoaded = loader.loadJar(
                collision.jar(), Map.of("test-publisher", collision.publicKey()));
        assertThatThrownBy(() -> new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework(), List.of(collisionLoaded)))
                .hasMessageContaining("Duplicate capability id");

        Fixture elevated = fixture("elevated", catalog -> {
            var capability = (com.fasterxml.jackson.databind.node.ObjectNode)
                    catalog.withArray("capabilities").get(0);
            capability.put("availability", "BUNDLED");
            capability.put("maturity", "stable");
        });
        AppChainComponentCatalogLoader.Loaded elevatedLoaded = loader.loadJar(
                elevated.jar(), Map.of("test-publisher", elevated.publicKey()));
        assertThatThrownBy(() -> new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework(), List.of(elevatedLoaded)))
                .hasMessageContaining("cannot claim bundled");
    }

    @Test
    void doctorFindsPinnedJvmJarAndExplainsNativeIncompatibility() throws Exception {
        Fixture fixture = fixture("custom-sample", null);
        Path project = initializeProject(fixture);
        AppChainProjectLifecycle lifecycle = AppChainProjectLifecycle.forProject(
                AppChainPropertyRegistry.framework(), project);
        AppChainProjectCatalog builtIn = new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework());

        Path jvm = distribution("jvm", builtIn.releaseIndexBytes());
        Files.createDirectories(jvm.resolve("plugins"));
        Files.copy(fixture.jar(), jvm.resolve(fixture.jar().getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        // The plugins directory is the explicit installation boundary.
        Files.move(jvm.resolve(fixture.jar().getFileName()),
                jvm.resolve("plugins").resolve(fixture.jar().getFileName()));
        AppChainProjectModel.DoctorReport jvmReport = lifecycle.doctor(project, jvm);
        assertThat(jvmReport.checks()).anySatisfy(check -> {
            if ("artifact:plugin.custom-sample".equals(check.id())) {
                assertThat(check.status()).isEqualTo("PASS");
                assertThat(check.detail()).contains("pinned plugin digest");
            }
        });

        Path nativeDistribution = distribution("native", builtIn.releaseIndexBytes());
        Files.createDirectories(nativeDistribution.resolve("plugins"));
        Files.copy(fixture.jar(), nativeDistribution.resolve("plugins").resolve(
                fixture.jar().getFileName()));
        AppChainProjectModel.DoctorReport nativeReport = lifecycle.doctor(
                project, nativeDistribution);
        assertThat(nativeReport.checks()).anySatisfy(check -> {
            if ("artifact:plugin.custom-sample".equals(check.id())) {
                assertThat(check.status()).isEqualTo("FAIL");
                assertThat(check.detail()).contains("cannot be loaded by a native executable");
            }
        });
    }

    @Test
    void scaffoldModesAreBoundedBuildableStartingPointsAndNeverOverwrite() throws Exception {
        AppChainPluginScaffolder scaffolder = new AppChainPluginScaffolder();
        for (String mode : List.of("state-machine", "composite-role", "effect-executor", "sink")) {
            Path output = temporary.resolve(mode);
            AppChainPluginScaffolder.Result result = scaffolder.scaffold(
                    mode, "sample-" + mode, "example." + mode.replace('-', '_'),
                    "0.1.0-test", output);
            assertThat(result.mode()).isEqualTo(mode);
            assertThat(output.resolve("build.gradle")).isRegularFile();
            assertThat(output.resolve("README.md")).content().contains("plugin sign");
            assertThat(output.resolve("src/main/resources/"
                    + AppChainComponentCatalogLoader.CATALOG_PATH)).isRegularFile();
            assertThat(Files.walk(output.resolve("src/main/resources/META-INF/services"))
                    .filter(Files::isRegularFile).count()).isEqualTo(1);
            assertThatThrownBy(() -> scaffolder.scaffold(
                    mode, "sample-" + mode, "example." + mode.replace('-', '_'),
                    "0.1.0-test", output)).hasMessageContaining("not empty");
        }
    }

    @Test
    void reviewedJvmArtifactIsVisibleThroughItsServiceLoaderBoundary() throws Exception {
        Fixture fixture = fixture("custom-sample", null);
        try (URLClassLoader pluginLoader = new URLClassLoader(
                new URL[]{fixture.jar().toUri().toURL()}, getClass().getClassLoader())) {
            assertThat(ServiceLoader.load(AppStateMachineProvider.class, pluginLoader))
                    .extracting(AppStateMachineProvider::id).contains("custom-sample");
        }
    }

    @Test
    void signedConfigurationMetadataExtendsRegistryAndOwnershipCollisionsFailClosed()
            throws Exception {
        String owner = "plugin-bundle.metadata";
        byte[] metadata = metadata(owner,
                "yano.app-chain.machines.metadata.limit");
        Fixture fixture = fixture("metadata", null, metadata);
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        var loaded = loader.loadJar(fixture.jar(),
                Map.of("test-publisher", fixture.publicKey()));
        AppChainPropertyRegistry extended = loader.extendRegistry(
                AppChainPropertyRegistry.framework(), List.of(loaded));
        assertThat(extended.find("machines.metadata.limit")).isPresent();

        Fixture collision = fixture("metadata-collision", null,
                metadata("plugin-bundle.metadata-collision",
                        "yano.app-chain.state-machine"));
        var collisionLoaded = loader.loadJar(collision.jar(),
                Map.of("test-publisher", collision.publicKey()));
        assertThatThrownBy(() -> loader.extendRegistry(
                AppChainPropertyRegistry.framework(), List.of(collisionLoaded)))
                .hasMessageContaining("Duplicate property definition");
    }

    @Test
    void publicPluginValidateExportsAReverifiableSnapshot() throws Exception {
        Fixture fixture = fixture("cli-sample", null);
        Path snapshot = temporary.resolve("cli-sample-catalog.json");
        StringWriter output = new StringWriter();
        AppChainProjectCli cli = new AppChainProjectCli(
                new BufferedReader(new StringReader("")), new PrintWriter(output, true),
                AppChainPropertyRegistry.framework(), new ObjectMapper());

        Path scaffold = temporary.resolve("scaffold-cli-sample");
        Path signedCopy = temporary.resolve("detached-signature.json");
        int signExit = cli.run(new String[]{"appchain", "plugin", "sign", "--catalog",
                scaffold.resolve("src/main/resources/")
                        .resolve(AppChainComponentCatalogLoader.CATALOG_PATH).toString(),
                "--runtime-manifest", scaffold.resolve("src/main/resources/META-INF/yano/plugins/"
                        + "plugin-bundle.cli-sample.json").toString(),
                "--seed-file", scaffold.resolve("publisher.seed").toString(),
                "--key-id", "test-publisher", "--output", signedCopy.toString()});
        assertThat(signExit).isZero();
        assertThat(signedCopy).isRegularFile();

        int exit = cli.run(new String[]{"appchain", "plugin", "validate",
                fixture.jar().toString(), "--trust-key",
                "test-publisher=" + fixture.publicKey(), "--output", snapshot.toString()});

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("PLUGIN_CATALOG_VALID", "SNAPSHOT_WRITTEN");
        var loaded = new AppChainComponentCatalogLoader().loadSnapshot(
                snapshot, Map.of("test-publisher", fixture.publicKey()));
        assertThat(loaded.catalog().catalogId()).isEqualTo("cli-sample-plugin");

        Path project = temporary.resolve("snapshot-project");
        int initExit = cli.run(new String[]{"appchain", "init", "--non-interactive",
                "--recipe", "custom-plugin", "--network", "devnet", "--members", "1",
                "--runtime", "jvm", "--capability", "state:cli-sample",
                "--component-catalog", snapshot.toString(), "--trust-key",
                "test-publisher=" + fixture.publicKey(), "--output", project.toString()});
        assertThat(initExit).isZero();
        assertThat(AppChainProjectLifecycle.forProject(
                AppChainPropertyRegistry.framework(), project).validate(project)
                .lock().selectedCapabilities()).contains("state:cli-sample");
    }

    private Path initializeProject(Fixture fixture) throws Exception {
        Path project = temporary.resolve("doctor-project");
        AppChainProjectCli cli = new AppChainProjectCli(
                new BufferedReader(new StringReader("")),
                new PrintWriter(new StringWriter(), true),
                AppChainPropertyRegistry.framework(), new ObjectMapper());
        cli.run(new String[]{"appchain", "init", "--non-interactive",
                "--recipe", "custom-plugin", "--network", "devnet", "--members", "1",
                "--runtime", "jvm", "--capability", "state:custom-sample",
                "--plugin-jar", fixture.jar().toString(), "--trust-key",
                "test-publisher=" + fixture.publicKey(), "--output", project.toString()});
        return project;
    }

    private Path distribution(String runtime, byte[] releaseIndex) throws Exception {
        Path root = temporary.resolve("distribution-" + runtime);
        Files.createDirectories(root.resolve("config/schema"));
        Files.write(root.resolve("config/schema/appchain-release-capability-index.json"),
                releaseIndex);
        Files.write(root.resolve("jvm".equals(runtime) ? "yano.jar" : "yano"),
                new byte[]{1});
        return root;
    }

    private Fixture fixture(
            String id,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutate)
            throws Exception {
        return fixture(id, mutate, null);
    }

    private Fixture fixture(
            String id,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutate,
            byte[] configurationMetadata) throws Exception {
        Path scaffold = temporary.resolve("scaffold-" + id);
        new AppChainPluginScaffolder().scaffold(
                "state-machine", id, "example." + id.replace('-', '_'),
                "0.1.0-test", scaffold);
        Path resources = scaffold.resolve("src/main/resources");
        Path catalog = resources.resolve(AppChainComponentCatalogLoader.CATALOG_PATH);
        if (mutate != null) {
            ObjectMapper mapper = new ObjectMapper();
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(Files.readAllBytes(catalog));
            mutate.accept(tree);
            Files.write(catalog, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree));
        }
        Path manifest;
        try (var files = Files.list(resources.resolve("META-INF/yano/plugins"))) {
            manifest = files.findFirst().orElseThrow();
        }
        Path seed = scaffold.resolve("publisher.seed");
        Files.writeString(seed, SEED, StandardCharsets.US_ASCII);
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        Path metadata = null;
        if (configurationMetadata != null) {
            metadata = resources.resolve(AppChainMetadataDescriptor.RESOURCE_PATH);
            Files.createDirectories(metadata.getParent());
            Files.write(metadata, configurationMetadata);
        }
        AppChainComponentCatalogLoader.SignedCatalog signed = loader.sign(
                catalog, manifest, metadata, "test-publisher", seed);
        Path signature = resources.resolve(AppChainComponentCatalogLoader.SIGNATURE_PATH);
        loader.writeSignature(signature, signed);

        Path service = resources.resolve("META-INF/services/"
                + AppStateMachineProvider.class.getName());
        Files.writeString(service, CustomCatalogFixtureProvider.class.getName() + "\n");
        Path jar = temporary.resolve(id + ".jar");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(resources)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    add(archive, resources.relativize(path).toString().replace('\\', '/'),
                            Files.readAllBytes(path));
                }
            }
            String classEntry = CustomCatalogFixtureProvider.class.getName()
                    .replace('.', '/') + ".class";
            try (var input = getClass().getClassLoader().getResourceAsStream(classEntry)) {
                add(archive, classEntry, input.readAllBytes());
            }
            add(archive, "untrusted/NotLoaded.class", HexFormat.of().parseHex("cafebabe"));
        }
        return new Fixture(jar, signed.publicKeyHex());
    }

    private static byte[] metadata(String owner, String key) throws Exception {
        AppChainPropertyDefinition property = new AppChainPropertyDefinition(
                key, owner, PropertyType.INTEGER, "10", 1L, 100L,
                null, null, null, Set.of(), PropertyScope.NODE_LOCAL,
                ChangePolicy.RESTART_REQUIRED, false, true,
                ConstraintProvenance.DOCUMENTED_UNVERIFIED,
                ValidationCoverage.PARTIAL, "Custom bounded setting");
        return new ObjectMapper().writeValueAsBytes(new AppChainMetadataDescriptor(
                1, owner, List.of(property), List.of()));
    }

    private static void add(ZipOutputStream archive, String name, byte[] bytes) throws Exception {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(bytes);
        archive.closeEntry();
    }

    private record Fixture(Path jar, String publicKey) {
    }
}
