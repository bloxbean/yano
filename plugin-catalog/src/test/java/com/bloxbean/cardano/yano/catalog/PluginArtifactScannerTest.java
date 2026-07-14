package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginArtifactScannerTest {
    private static final ContributionKind KIND = ContributionKind.FINALIZED_SINK;

    @TempDir
    Path temporary;

    private final PluginArtifactScanner scanner = new PluginArtifactScanner();

    @Test
    void scansAndCorrelatesOneManifestedJar() throws Exception {
        Path directory = manifestedDirectory(
                "source", "com.example.kafka", "kafka", "com.example.KafkaProvider");
        Path service = serviceFile(directory, KIND);
        Files.writeString(service, "# provider registration\n  com.example.KafkaProvider  # selected\n");
        Path jar = createJar(directory, temporary.resolve("plugin.jar"));

        PluginIndex index = scanner.scan(jar);

        assertThat(index.bundles()).hasSize(1);
        IndexedBundle bundle = index.bundles().getFirst();
        assertThat(bundle.manifest().id()).isEqualTo("com.example.kafka");
        assertThat(bundle.manifest().contributions()).containsExactly(new BundleContribution(
                KIND, "kafka", "com.example.KafkaProvider"));
        assertThat(bundle.digestMode()).isEqualTo(PluginDigestMode.JAR);
        assertThat(bundle.digest()).matches("sha256:[0-9a-f]{64}");
        assertThat(index.legacyProviders()).isEmpty();
    }

    @Test
    void rejectsMissingAndUndeclaredServiceEntries() throws Exception {
        Path missing = manifestedDirectory(
                "missing", "com.example.missing", "sink", "com.example.DeclaredProvider");
        Files.writeString(serviceFile(missing, KIND), "com.example.OtherProvider\n");

        assertThatThrownBy(() -> scanner.scan(missing))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("manifest and supported ServiceLoader entries differ")
                .hasMessageContaining("missing=[finalized-sink:com.example.DeclaredProvider]")
                .hasMessageContaining("undeclared=[finalized-sink:com.example.OtherProvider]");

        Path undeclared = emptyArtifact("undeclared");
        writeService(undeclared, KIND, "com.example.LegacyProvider");
        writeManifest(undeclared, "com.example.undeclared", List.of());
        assertThatThrownBy(() -> scanner.scan(undeclared))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("undeclared=[finalized-sink:com.example.LegacyProvider]");
    }

    @Test
    void rejectsApiClassesOnlyWhenArtifactIsAPlugin() throws Exception {
        Path plugin = manifestedDirectory(
                "shadowing", "com.example.shadowing", "sink", "com.example.Provider");
        writeBytes(plugin, "com/bloxbean/cardano/yano/api/plugin/NodePlugin.class", new byte[]{1});

        assertThatThrownBy(() -> scanner.scan(plugin))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("packages Yano API class")
                .hasMessageContaining("NodePlugin.class");

        Path coreApi = emptyArtifact("core-api");
        writeBytes(coreApi, "com/bloxbean/cardano/yano/api/plugin/NodePlugin.class", new byte[]{1});
        writeService(coreApi, KIND, "# no provider entry");
        assertThat(scanner.scan(coreApi)).isEqualTo(PluginIndex.empty());
    }

    @Test
    void detectsApiShadowingInsideMultiReleaseJar() throws Exception {
        Path plugin = manifestedDirectory(
                "multi-release", "com.example.multirelease", "sink", "com.example.Provider");
        writeBytes(plugin,
                "META-INF/versions/25/com/bloxbean/cardano/yano/api/plugin/NodePlugin.class",
                new byte[]{1});
        Path jar = createJar(plugin, temporary.resolve("multi-release.jar"));

        assertThatThrownBy(() -> scanner.scan(jar))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("packages Yano API class");
    }

    @Test
    void emitsLegacyProvidersWithCompleteArtifactDigest() throws Exception {
        Path legacy = emptyArtifact("legacy");
        writeService(legacy, KIND, "com.example.LegacyProvider");
        writeProviderClass(legacy, "com.example.LegacyProvider");

        PluginIndex first = scanner.scan(legacy);
        assertThat(first.bundles()).isEmpty();
        assertThat(first.legacyProviders()).containsExactly(new IndexedLegacyProvider(
                KIND,
                "com.example.LegacyProvider",
                first.legacyProviders().getFirst().digest(),
                PluginDigestMode.ARTIFACT_TREE));

        Files.writeString(legacy.resolve("helper-resource.txt"), "complete artifact input");
        PluginIndex changed = scanner.scan(legacy);
        assertThat(changed.legacyProviders().getFirst().digest())
                .isNotEqualTo(first.legacyProviders().getFirst().digest());
    }

    @Test
    void correlatesManifestedDomainApiServiceMetadata() throws Exception {
        ContributionKind domain = ContributionKind.DOMAIN_API;
        String id = "com.example.passport";
        String provider = "com.example.PassportDomainApiProvider";
        Path artifact = emptyArtifact("domain-api");
        writeManifest(artifact, id, List.of(new BundleContribution(domain, id, provider)));
        writeService(artifact, domain, provider);
        writeProviderClass(artifact, provider);

        PluginIndex index = scanner.scan(artifact);

        assertThat(index.legacyProviders()).isEmpty();
        assertThat(index.bundles()).singleElement().satisfies(bundle ->
                assertThat(bundle.manifest().contributions()).containsExactly(
                        new BundleContribution(domain, id, provider)));
    }

    @Test
    void rejectsUnmanifestedDomainApiInsteadOfSynthesizingLegacyEvidence() throws Exception {
        Path artifact = emptyArtifact("legacy-domain-api");
        String provider = "com.example.LegacyDomainApiProvider";
        writeService(artifact, ContributionKind.DOMAIN_API, provider);
        writeProviderClass(artifact, provider);

        assertThatThrownBy(() -> scanner.scan(artifact))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("domain-api")
                .hasMessageContaining("require a bundle manifest")
                .hasMessageContaining("cannot use legacy discovery");
    }

    @Test
    void requiresProviderClassOwnershipAndRejectsMultiReleaseOverrides() throws Exception {
        Path missingClass = emptyArtifact("missing-provider-class");
        writeService(missingClass, KIND, "com.example.ExternalProvider");
        assertThatThrownBy(() -> scanner.scan(missingClass))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("no base class entry in the same artifact");

        Path override = manifestedDirectory(
                "provider-override", "com.example.override", "sink", "com.example.Provider");
        writeBytes(override,
                "META-INF/versions/25/com/example/Provider.class",
                new byte[]{2});
        assertThatThrownBy(() -> scanner.scan(override))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("must not use a multi-release class override");
    }

    @Test
    void boundsProviderEntriesBeforeCorrelation() throws Exception {
        Path artifact = manifestedDirectory(
                "provider-count", "com.example.count", "sink", "com.example.Provider");
        StringBuilder services = new StringBuilder();
        for (int i = 0; i <= CatalogValidation.MAX_CONTRIBUTIONS; i++) {
            services.append("com.example.Provider").append(i).append('\n');
        }
        Files.writeString(serviceFile(artifact, KIND), services);

        assertThatThrownBy(() -> scanner.scan(artifact))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("more than 256 supported service providers");
    }

    @Test
    void rejectsMultipleManifestsAndDuplicateServiceProviders() throws Exception {
        Path multiple = manifestedDirectory(
                "multiple", "com.example.one", "one", "com.example.OneProvider");
        writeManifest(multiple, "com.example.two", List.of());
        assertThatThrownBy(() -> scanner.scan(multiple))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("more than one qualified plugin manifest");

        Path duplicate = emptyArtifact("duplicate-service");
        writeService(duplicate, KIND, "com.example.Provider\ncom.example.Provider");
        assertThatThrownBy(() -> scanner.scan(duplicate))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("repeats provider");

        Path nonJdkLineBreak = emptyArtifact("unicode-line-break");
        writeService(nonJdkLineBreak, KIND, "com.example.One\u2028com.example.Two");
        assertThatThrownBy(() -> scanner.scan(nonJdkLineBreak))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("invalid provider at line 1");

        Path nonUtf8 = emptyArtifact("non-utf8-service");
        writeBytes(nonUtf8, servicePath(KIND), new byte[]{(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> scanner.scan(nonUtf8))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("must be valid UTF-8");
    }

    @Test
    void rejectsSymbolicLinkArtifactsAndEntries() throws Exception {
        Path artifact = manifestedDirectory(
                "symlink-source", "com.example.symlink", "sink", "com.example.Provider");
        Path artifactLink = temporary.resolve("artifact-link");
        Files.createSymbolicLink(artifactLink, artifact.getFileName());

        assertThatThrownBy(() -> scanner.scan(artifactLink))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must not be a symbolic link");

        Path entryLink = artifact.resolve("linked-provider.class");
        Files.createSymbolicLink(entryLink, Path.of("com/example/Provider.class"));
        assertThatThrownBy(() -> scanner.scan(artifact))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("must not contain symbolic links");
    }

    @Test
    void rejectsAnInputArtifactThatAlreadyContainsAnAggregateIndex() throws Exception {
        Path directory = emptyArtifact("nested-index-directory");
        writeBytes(directory, PluginIndex.RESOURCE_PATH, "{}".getBytes());

        assertThatThrownBy(() -> scanner.scan(directory))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("already contains aggregate plugin index")
                .hasMessageContaining(PluginIndex.RESOURCE_PATH);

        Path jar = createJar(directory, temporary.resolve("nested-index.jar"));
        assertThatThrownBy(() -> scanner.scan(jar))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("already contains aggregate plugin index");

        Path multiRelease = emptyArtifact("nested-index-multi-release");
        writeBytes(multiRelease, "META-INF/versions/25/" + PluginIndex.RESOURCE_PATH,
                "{}".getBytes());
        assertThatThrownBy(() -> scanner.scan(multiRelease))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("already contains aggregate plugin index");
    }

    @Test
    void rejectsManifestClassPathForJarAndExplodedArtifacts() throws Exception {
        Path externalHelper = emptyArtifact("external-helper");
        writeBytes(externalHelper, "com/example/ExternalHelper.class", new byte[]{1});
        Path externalHelperJar = createJar(
                externalHelper, temporary.resolve("external-helper.jar"));

        Path exploded = manifestedDirectory(
                "manifest-class-path", "com.example.classpath", "sink",
                "com.example.ClassPathProvider");
        writeBytes(exploded, JarFile.MANIFEST_NAME, ("Manifest-Version: 1.0\r\n"
                + "Class-Path: " + externalHelperJar.toUri().toASCIIString() + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> scanner.scan(exploded))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("JAR manifest Class-Path is unsupported")
                .hasMessageNotContaining(externalHelperJar.toString());

        Path pluginJar = createJar(exploded, temporary.resolve("manifest-class-path.jar"));
        assertThatThrownBy(() -> scanner.scan(pluginJar))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("JAR manifest Class-Path is unsupported")
                .hasMessageNotContaining(externalHelperJar.toString());
    }

    private Path manifestedDirectory(
            String directory,
            String id,
            String selector,
            String provider
    ) throws IOException {
        Path artifact = emptyArtifact(directory);
        writeManifest(artifact, id, List.of(new BundleContribution(KIND, selector, provider)));
        writeService(artifact, KIND, provider);
        writeProviderClass(artifact, provider);
        return artifact;
    }

    private Path emptyArtifact(String name) throws IOException {
        return Files.createDirectories(temporary.resolve(name));
    }

    private static void writeManifest(
            Path root,
            String id,
            List<BundleContribution> contributions
    ) throws IOException {
        StringBuilder contributionJson = new StringBuilder();
        for (int i = 0; i < contributions.size(); i++) {
            BundleContribution contribution = contributions.get(i);
            if (i > 0) {
                contributionJson.append(',');
            }
            contributionJson.append("""
                    {"kind":"%s","name":"%s","provider":"%s"}
                    """.formatted(
                    contribution.kind().manifestKey(), contribution.name(), contribution.provider()).trim());
        }
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "version": "1.0.0",
                  "yanoApi": {"min": 1, "max": 1},
                  "dependencies": [],
                  "contributions": [%s]
                }
                """.formatted(id, contributionJson);
        writeBytes(root, BundleManifestParser.RESOURCE_DIRECTORY + id + ".json", json.getBytes());
    }

    private static void writeService(Path root, ContributionKind kind, String contents) throws IOException {
        writeBytes(root, servicePath(kind), (contents + "\n").getBytes());
    }

    private static void writeProviderClass(Path root, String provider) throws IOException {
        writeBytes(root, provider.replace('.', '/') + ".class", new byte[]{1});
    }

    private static Path serviceFile(Path root, ContributionKind kind) {
        return root.resolve(servicePath(kind));
    }

    private static String servicePath(ContributionKind kind) {
        return "META-INF/services/" + kind.serviceType().getName();
    }

    private static void writeBytes(Path root, String relative, byte[] bytes) throws IOException {
        Path output = root.resolve(relative);
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }

    private static Path createJar(Path source, Path output) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(source)) {
            files = stream.filter(Files::isRegularFile)
                    .sorted((left, right) -> source.relativize(left).toString()
                            .compareTo(source.relativize(right).toString()))
                    .toList();
        }
        try (OutputStream file = Files.newOutputStream(output);
             JarOutputStream jar = new JarOutputStream(file)) {
            for (Path path : files) {
                String name = source.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                JarEntry entry = new JarEntry(name);
                entry.setTime(0);
                jar.putNextEntry(entry);
                Files.copy(path, jar);
                jar.closeEntry();
            }
        }
        return output;
    }
}
