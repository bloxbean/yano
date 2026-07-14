package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    void snapshotByteBudgetAcceptsExactLimitAndRejectsNextByte() throws Exception {
        Path artifact = temporary.resolve("boundary.jar");
        assertThat(PluginArtifactSnapshot.advanceCapturedBytes(
                artifact, PluginArtifactScanner.MAX_ARTIFACT_BYTES - 1, 1))
                .isEqualTo(PluginArtifactScanner.MAX_ARTIFACT_BYTES);

        assertThatThrownBy(() -> PluginArtifactSnapshot.advanceCapturedBytes(
                artifact, PluginArtifactScanner.MAX_ARTIFACT_BYTES, 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable snapshot byte limit")
                .hasMessageContaining(Long.toString(
                        PluginArtifactScanner.MAX_ARTIFACT_BYTES));
    }

    @Test
    void rejectsOversizedSparseArtifactBeforeCreatingPrivateSnapshot() throws Exception {
        Path artifact = sparseFile(
                temporary.resolve("oversized.jar"),
                PluginArtifactScanner.MAX_ARTIFACT_BYTES + 1);
        AtomicBoolean captureStarted = new AtomicBoolean();
        PluginArtifactScanner boundedScanner = scanner(
                new PluginArtifactScanner.ScanObserver() {
                    @Override
                    public void beforeSnapshotCapture(Path source) {
                        captureStarted.set(true);
                    }
                });

        assertThatThrownBy(() -> boundedScanner.scan(artifact))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable snapshot byte limit")
                .hasMessageContaining(Long.toString(
                        PluginArtifactScanner.MAX_ARTIFACT_BYTES));
        assertThat(captureStarted).isFalse();
    }

    @Test
    void rejectsExplodedArtifactAboveAggregateByteLimitBeforeCapture() throws Exception {
        Path artifact = emptyArtifact("oversized-tree");
        long half = PluginArtifactScanner.MAX_ARTIFACT_BYTES / 2;
        sparseFile(artifact.resolve("first.bin"), half);
        sparseFile(artifact.resolve("second.bin"), half + 1);
        AtomicBoolean captureStarted = new AtomicBoolean();
        PluginArtifactScanner boundedScanner = scanner(
                new PluginArtifactScanner.ScanObserver() {
                    @Override
                    public void beforeSnapshotCapture(Path source) {
                        captureStarted.set(true);
                    }
                });

        assertThatThrownBy(() -> boundedScanner.scan(artifact))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable snapshot byte limit");
        assertThat(captureStarted).isFalse();
    }

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
    void correlatesManifestedHealthAndMetricsServiceMetadata() throws Exception {
        String id = "com.example.observability";
        String healthProvider = "com.example.ObservabilityHealthProvider";
        String metricsProvider = "com.example.ObservabilityMetricsProvider";
        Path artifact = emptyArtifact("observability");
        writeManifest(artifact, id, List.of(
                new BundleContribution(ContributionKind.HEALTH, id, healthProvider),
                new BundleContribution(ContributionKind.METRICS, id, metricsProvider)));
        writeService(artifact, ContributionKind.HEALTH, healthProvider);
        writeService(artifact, ContributionKind.METRICS, metricsProvider);
        writeProviderClass(artifact, healthProvider);
        writeProviderClass(artifact, metricsProvider);

        PluginIndex index = scanner.scan(artifact);

        assertThat(index.legacyProviders()).isEmpty();
        assertThat(index.bundles()).singleElement().satisfies(bundle ->
                assertThat(bundle.manifest().contributions()).containsExactly(
                        new BundleContribution(ContributionKind.HEALTH, id, healthProvider),
                        new BundleContribution(ContributionKind.METRICS, id, metricsProvider)));
    }

    @Test
    void rejectsUnmanifestedHealthAndMetricsProviders() throws Exception {
        Path artifact = emptyArtifact("unmanifested-observability");
        writeService(artifact, ContributionKind.HEALTH, "com.example.LegacyHealth");
        writeService(artifact, ContributionKind.METRICS, "com.example.LegacyMetrics");
        writeProviderClass(artifact, "com.example.LegacyHealth");
        writeProviderClass(artifact, "com.example.LegacyMetrics");

        assertThatThrownBy(() -> scanner.scan(artifact))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("[health, metrics]")
                .hasMessageContaining("require a bundle manifest")
                .hasMessageContaining("cannot use legacy discovery");
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
    void rejectsAnOrdinaryArtifactThatBecomesAPluginAfterTheProbe() throws Exception {
        Path artifact = emptyArtifact("ordinary-becomes-plugin");
        String id = "com.example.late";
        String provider = "com.example.LateProvider";
        PluginArtifactScanner racingScanner = scanner(new PluginArtifactScanner.ScanObserver() {
            @Override
            public void afterProbe(Path source, boolean pluginArtifact) throws IOException {
                assertThat(pluginArtifact).isFalse();
                writeManifest(source, id,
                        List.of(new BundleContribution(KIND, "late", provider)));
                writeService(source, KIND, provider);
                writeProviderClass(source, provider);
            }
        });

        assertThatThrownBy(() -> racingScanner.scanClasspathArtifact(artifact, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("changed while it was being inspected");
    }

    @Test
    void strictExplicitGenerationClassifiesOnlyTheCapturedBytes() throws Exception {
        String id = "com.example.strict-snapshot";
        String provider = "com.example.StrictSnapshotProvider";
        Path pluginDirectory = manifestedDirectory(
                "strict-plugin", id, "strict", provider);
        byte[] pluginBytes = Files.readAllBytes(createJar(
                pluginDirectory, temporary.resolve("strict-plugin.jar")));
        byte[] ordinaryBytes = pluginBytes.clone();
        replaceAscii(ordinaryBytes,
                BundleManifestParser.RESOURCE_DIRECTORY,
                "META-INF/yano/xlugins/");
        replaceAscii(ordinaryBytes, "META-INF/services/", "META-INF/xervices/");

        Path artifact = temporary.resolve("strict-race.jar");
        Files.write(artifact, ordinaryBytes);
        assertThat(scanner.scan(artifact)).isEqualTo(PluginIndex.empty());
        BasicFileAttributes original = Files.readAttributes(
                artifact, BasicFileAttributes.class);
        FileTime originalModified = original.lastModifiedTime();

        PluginArtifactScanner racingScanner = scanner(new PluginArtifactScanner.ScanObserver() {
            @Override
            public void beforeSnapshotCapture(Path source) throws IOException {
                Files.write(source, pluginBytes,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                Files.setLastModifiedTime(source, originalModified);
                BasicFileAttributes changed = Files.readAttributes(
                        source, BasicFileAttributes.class);
                assertThat(changed.fileKey()).isEqualTo(original.fileKey());
                assertThat(changed.size()).isEqualTo(original.size());
                assertThat(changed.lastModifiedTime()).isEqualTo(originalModified);
                assertThat(changed.creationTime()).isEqualTo(original.creationTime());
            }
        });

        PluginIndex index = new PluginIndexGenerator(
                racingScanner, new PluginIndexCodec()).generate(List.of(artifact));

        assertThat(index.bundles()).singleElement().satisfies(bundle ->
                assertThat(bundle.manifest().id()).isEqualTo(id));
    }

    @Test
    void immutableSnapshotPreventsAbaMetadataDigestPairing() throws Exception {
        String firstId = "com.example.aba-first";
        String firstProvider = "com.example.FirstProvider";
        Path firstDirectory = manifestedDirectory(
                "aba-first", firstId, "first", firstProvider);
        Path artifact = createJar(firstDirectory, temporary.resolve("aba.jar"));
        String expectedDigest = CatalogDigests.artifact(artifact).value();

        String secondId = "com.example.aba-second";
        String secondProvider = "com.example.OtherProvider";
        Path secondDirectory = manifestedDirectory(
                "aba-second", secondId, "second", secondProvider);
        Path replacement = createJar(
                secondDirectory, temporary.resolve("aba-replacement.jar"));
        Path saved = temporary.resolve("aba-original.jar");
        AtomicReference<Path> snapshotRoot = new AtomicReference<>();
        PluginArtifactScanner racingScanner = scanner(new PluginArtifactScanner.ScanObserver() {
            @Override
            public void beforeSnapshotInspection(Path source, Path snapshot)
                    throws IOException {
                snapshotRoot.set(snapshot.getParent());
                Files.move(source, saved, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(replacement, source, StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void afterSnapshotInspection(Path source, Path snapshot)
                    throws IOException {
                Files.deleteIfExists(source);
                Files.move(saved, source, StandardCopyOption.REPLACE_EXISTING);
            }
        });

        PluginIndex index = racingScanner.scan(artifact);

        assertThat(index.bundles()).singleElement().satisfies(bundle -> {
            assertThat(bundle.manifest().id()).isEqualTo(firstId);
            assertThat(bundle.manifest().contributions()).containsExactly(
                    new BundleContribution(KIND, "first", firstProvider));
            assertThat(bundle.digest()).isEqualTo(expectedDigest);
        });
        assertThat(CatalogDigests.artifact(artifact).value()).isEqualTo(expectedDigest);
        assertThat(snapshotRoot.get()).isNotNull().doesNotExist();
    }

    @Test
    void rejectsSameSizeRootReplacementDuringSnapshotCaptureAndCleansUp()
            throws Exception {
        Path directory = manifestedDirectory(
                "same-size-source", "com.example.same-size", "sink",
                "com.example.SameSizeProvider");
        Path artifact = createJar(directory, temporary.resolve("same-size.jar"));
        byte[] replacementBytes = Files.readAllBytes(artifact);
        replacementBytes[replacementBytes.length / 2] ^= 1;
        Path replacement = temporary.resolve("same-size-replacement.jar");
        Files.write(replacement, replacementBytes);
        assertThat(Files.size(replacement)).isEqualTo(Files.size(artifact));

        AtomicReference<Path> snapshotRoot = new AtomicReference<>();
        PluginArtifactScanner racingScanner = scanner(new PluginArtifactScanner.ScanObserver() {
            @Override
            public void afterSnapshotCopy(Path source, Path snapshot) throws IOException {
                snapshotRoot.set(snapshot.getParent());
                Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
            }
        });

        assertThatThrownBy(() -> racingScanner.scan(artifact))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable scan snapshot was captured");
        assertThat(snapshotRoot.get()).isNotNull().doesNotExist();
    }

    @Test
    void rejectsRootSymlinkReplacementDuringSnapshotCaptureAndCleansUp()
            throws Exception {
        Path directory = manifestedDirectory(
                "symlink-race-source", "com.example.symlink-race", "sink",
                "com.example.SymlinkRaceProvider");
        Path artifact = createJar(directory, temporary.resolve("symlink-race.jar"));
        Path saved = temporary.resolve("symlink-race-original.jar");
        AtomicReference<Path> snapshotRoot = new AtomicReference<>();
        PluginArtifactScanner racingScanner = scanner(new PluginArtifactScanner.ScanObserver() {
            @Override
            public void afterSnapshotCopy(Path source, Path snapshot) throws IOException {
                snapshotRoot.set(snapshot.getParent());
                Files.move(source, saved, StandardCopyOption.REPLACE_EXISTING);
                Files.createSymbolicLink(source, saved.getFileName());
            }
        });

        assertThatThrownBy(() -> racingScanner.scan(artifact))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must not be a symbolic link");
        assertThat(snapshotRoot.get()).isNotNull().doesNotExist();
    }

    @Test
    void removesPrivateSnapshotAfterCorrelationFailure() throws Exception {
        String provider = "com.example.CleanupProvider";
        Path artifact = manifestedDirectory(
                "cleanup-failure", "com.example.cleanup", "sink", provider);
        AtomicReference<Path> snapshotRoot = new AtomicReference<>();
        PluginArtifactScanner corruptingScanner = scanner(
                new PluginArtifactScanner.ScanObserver() {
                    @Override
                    public void beforeSnapshotInspection(Path source, Path snapshot)
                            throws IOException {
                        snapshotRoot.set(snapshot.getParent());
                        Files.delete(snapshot.resolve(
                                provider.replace('.', '/') + ".class"));
                    }
                });

        assertThatThrownBy(() -> corruptingScanner.scan(artifact))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("no base class entry in the same artifact");
        assertThat(snapshotRoot.get()).isNotNull().doesNotExist();
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

    private static PluginArtifactScanner scanner(
            PluginArtifactScanner.ScanObserver observer
    ) {
        return new PluginArtifactScanner(new BundleManifestParser(), observer);
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
                  "yanoApi": {"min": 1, "max": 1, "minLevel": 1},
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

    private static Path sparseFile(Path output, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(output,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }
        return output;
    }

    private static void replaceAscii(byte[] bytes, String expected, String replacement) {
        byte[] from = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] to = replacement.getBytes(StandardCharsets.US_ASCII);
        assertThat(to).hasSameSizeAs(from);
        int replacements = 0;
        for (int index = 0; index <= bytes.length - from.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < from.length; offset++) {
                if (bytes[index + offset] != from[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(to, 0, bytes, index, to.length);
                replacements++;
                index += from.length - 1;
            }
        }
        assertThat(replacements).isPositive();
    }
}
