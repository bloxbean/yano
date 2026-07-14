package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginIndexGeneratorTest {
    @TempDir
    Path temporary;

    private final PluginIndexGenerator generator = new PluginIndexGenerator();
    private final PluginIndexCodec codec = new PluginIndexCodec();

    @Test
    void retainsTwoBundlesAndIsIndependentOfArtifactOrder() throws Exception {
        Path beta = manifestedArtifact(
                "beta-dir", "com.example.beta", "beta", "com.example.BetaProvider");
        Path alpha = manifestedArtifact(
                "alpha-dir", "com.example.alpha", "alpha", "com.example.AlphaProvider");

        PluginIndex forward = generator.generate(List.of(beta, alpha));
        PluginIndex reverse = generator.generate(List.of(alpha, beta));

        assertThat(forward.bundles()).extracting(bundle -> bundle.manifest().id())
                .containsExactly("com.example.alpha", "com.example.beta");
        assertThat(codec.write(forward)).isEqualTo(codec.write(reverse));

        Path output = temporary.resolve("out").resolve(PluginIndex.RESOURCE_PATH);
        generator.write(List.of(beta, alpha), output);
        assertThat(codec.read(Files.newInputStream(output))).isEqualTo(forward);
    }

    @Test
    void rejectsDuplicateArtifactAndBundleIdsButRetainsPolicyAlternatives() throws Exception {
        Path first = manifestedArtifact(
                "first", "com.example.same", "one", "com.example.OneProvider");
        assertThatThrownBy(() -> generator.generate(List.of(first, first)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("path is repeated");

        Path duplicateId = manifestedArtifact(
                "duplicate-id", "com.example.same", "two", "com.example.TwoProvider");
        assertThatThrownBy(() -> generator.generate(List.of(first, duplicateId)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("duplicate bundle id");

        Path second = manifestedArtifact(
                "second", "com.example.second", "one", "com.example.OtherProvider");
        PluginIndex alternatives = generator.generate(List.of(first, second));
        assertThat(alternatives.bundles())
                .extracting(bundle -> bundle.manifest().id())
                .containsExactly("com.example.same", "com.example.second");
        assertThat(alternatives.bundles())
                .flatExtracting(bundle -> bundle.manifest().contributions())
                .extracting(BundleContribution::name)
                .containsExactly("one", "one");
    }

    @Test
    void mergesLegacyEntriesAndIgnoresOrdinaryDependencies() throws Exception {
        Path dependency = Files.createDirectories(temporary.resolve("ordinary-dependency"));
        Path apiClass = dependency.resolve("com/bloxbean/cardano/yano/api/Node.class");
        Files.createDirectories(apiClass.getParent());
        Files.write(apiClass, new byte[]{1});

        Path legacy = Files.createDirectories(temporary.resolve("legacy"));
        Path service = legacy.resolve("META-INF/services/"
                + ContributionKind.L1_OBSERVER.serviceType().getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, "com.example.ObserverProvider\n");
        writeProviderClass(legacy, "com.example.ObserverProvider");

        PluginIndex index = generator.generate(List.of(legacy, dependency));
        assertThat(index.bundles()).isEmpty();
        assertThat(index.legacyProviders()).extracting(IndexedLegacyProvider::provider)
                .containsExactly("com.example.ObserverProvider");
    }

    @Test
    void rejectsOutputNestedInAnInputArtifact() throws Exception {
        Path artifact = manifestedArtifact(
                "nested-output", "com.example.nested", "sink", "com.example.Provider");
        Path output = artifact.resolve(PluginIndex.RESOURCE_PATH);

        assertThatThrownBy(() -> generator.write(List.of(artifact), output))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("must not be inside or replace an input artifact");
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsSymbolicLinkInputsAndOutputsWithoutReplacingTargets() throws Exception {
        Path artifact = manifestedArtifact(
                "symlink-artifact", "com.example.symlink", "sink", "com.example.Provider");
        Path artifactLink = temporary.resolve("artifact-link");
        Files.createSymbolicLink(artifactLink, artifact.getFileName());

        assertThatThrownBy(() -> generator.generate(List.of(artifactLink)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("must not be a symbolic link");

        Path target = temporary.resolve("existing-index.json");
        Files.writeString(target, "keep-me", StandardCharsets.UTF_8);
        Path outputLink = temporary.resolve("index-link.json");
        Files.createSymbolicLink(outputLink, target.getFileName());

        assertThatThrownBy(() -> generator.write(List.of(artifact), outputLink))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("output must not be a symbolic link");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("keep-me");
        assertThat(outputLink).isSymbolicLink();
    }

    @Test
    void generationFailureLeavesAnExistingOutputUntouched() throws Exception {
        Path first = manifestedArtifact(
                "atomic-first", "com.example.atomic", "one", "com.example.OneProvider");
        Path duplicate = manifestedArtifact(
                "atomic-duplicate", "com.example.atomic", "two", "com.example.TwoProvider");
        Path output = temporary.resolve("existing-output.json");
        Files.writeString(output, "previous-index", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> generator.write(List.of(first, duplicate), output))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("duplicate bundle id");
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo("previous-index");
    }

    @Test
    void recordsDeterministicPerBundleClosuresAndTracksDependencyOnlyChanges()
            throws Exception {
        Path alpha = manifestedArtifact(
                "closure-alpha", "com.example.alpha", "alpha", "com.example.AlphaProvider");
        Path beta = manifestedArtifact(
                "closure-beta", "com.example.beta", "beta", "com.example.BetaProvider");
        Path alphaDependency = Files.createDirectories(temporary.resolve("alpha-dependency"));
        Path betaDependency = Files.createDirectories(temporary.resolve("beta-dependency"));
        Files.write(alphaDependency.resolve("helper.bin"), new byte[]{1});
        Files.write(betaDependency.resolve("helper.bin"), new byte[]{2});
        List<Path> classpath = List.of(betaDependency, alpha, beta, alphaDependency);
        Map<String, List<Path>> closures = Map.of(
                "com.example.alpha", List.of(alphaDependency, alpha),
                "com.example.beta", List.of(beta, betaDependency));

        PluginIndex first = generator.generateArtifactClosures(classpath, closures);
        PluginIndex reordered = generator.generateArtifactClosures(
                classpath.reversed(),
                Map.of(
                        "com.example.beta", List.of(betaDependency, beta),
                        "com.example.alpha", List.of(alpha, alphaDependency)));

        assertThat(codec.write(reordered)).isEqualTo(codec.write(first));
        assertThat(first.bundles()).allMatch(
                bundle -> bundle.digestMode() == PluginDigestMode.ARTIFACT_CLOSURE);
        String firstAlpha = digest(first, "com.example.alpha");
        String firstBeta = digest(first, "com.example.beta");
        assertThat(firstAlpha).isNotEqualTo(firstBeta);

        Files.write(alphaDependency.resolve("helper.bin"), new byte[]{9});
        PluginIndex changed = generator.generateArtifactClosures(classpath, closures);
        assertThat(digest(changed, "com.example.alpha")).isNotEqualTo(firstAlpha);
        assertThat(digest(changed, "com.example.beta")).isEqualTo(firstBeta);
        assertThat(codec.write(changed)).isNotEqualTo(codec.write(first));
    }

    @Test
    void strictClosureModeRejectsMissingUnknownExternalAndRootlessMappings()
            throws Exception {
        Path bundle = manifestedArtifact(
                "strict-bundle", "com.example.strict", "sink", "com.example.StrictProvider");
        Path dependency = Files.createDirectories(temporary.resolve("strict-dependency"));
        Files.write(dependency.resolve("helper.bin"), new byte[]{1});
        List<Path> classpath = List.of(bundle, dependency);

        assertThatThrownBy(() -> generator.generateArtifactClosures(classpath, Map.of()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("missing=[com.example.strict]")
                .hasMessageContaining("unknown=[]");
        assertThatThrownBy(() -> generator.generateArtifactClosures(
                        classpath,
                        Map.of(
                                "com.example.strict", List.of(bundle),
                                "com.example.unknown", List.of(dependency))))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("unknown=[com.example.unknown]");
        Path external = Files.createDirectories(temporary.resolve("external-dependency"));
        assertThatThrownBy(() -> generator.generateArtifactClosures(
                        classpath, Map.of("com.example.strict", List.of(bundle, external))))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("outside the indexed runtime classpath");
        assertThatThrownBy(() -> generator.generateArtifactClosures(
                        classpath, Map.of("com.example.strict", List.of(dependency))))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("does not include its manifest/provider artifact");
    }

    @Test
    void strictClosureModeRejectsLegacyProvidersWithoutAClosureIdentity() throws Exception {
        Path legacy = Files.createDirectories(temporary.resolve("strict-legacy"));
        Path service = legacy.resolve("META-INF/services/"
                + ContributionKind.L1_OBSERVER.serviceType().getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, "com.example.LegacyObserver\n");
        writeProviderClass(legacy, "com.example.LegacyObserver");

        assertThatThrownBy(() -> generator.generateArtifactClosures(
                        List.of(legacy), Map.of()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("requires a manifest for every provider")
                .hasMessageContaining("com.example.LegacyObserver");
    }

    @Test
    void strictBuildTimeClosureRejectsManifestClassPathOutsideDeclaredArtifacts()
            throws Exception {
        String bundleId = "com.example.external-classpath";
        Path bundle = manifestedArtifact(
                "external-classpath", bundleId, "sink", "com.example.ExternalProvider");
        Path externalHelper = temporary.resolve("external-helper.jar");
        Files.write(externalHelper, new byte[]{1});
        Path jarManifest = bundle.resolve(JarFile.MANIFEST_NAME);
        Files.createDirectories(jarManifest.getParent());
        Files.writeString(jarManifest, "Manifest-Version: 1.0\r\nClass-Path: "
                + externalHelper.toUri().toASCIIString() + "\r\n\r\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> generator.generateArtifactClosures(
                        List.of(bundle), Map.of(bundleId, List.of(bundle))))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("JAR manifest Class-Path is unsupported")
                .hasMessageNotContaining(externalHelper.toString());
    }

    @Test
    void closureEvidenceExcludesFilesystemLocationAndModificationTime() throws Exception {
        Path first = Files.createDirectories(temporary.resolve("closure-location-one"));
        Path second = Files.createDirectories(temporary.resolve("closure-location-two"));
        Files.createDirectories(first.resolve("lib"));
        Files.createDirectories(second.resolve("lib"));
        Files.writeString(first.resolve("lib/helper.bin"), "same bytes", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("lib/helper.bin"), "same bytes", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(first.resolve("lib/helper.bin"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(second.resolve("lib/helper.bin"), FileTime.fromMillis(9_000));

        CatalogDigests.Digest firstArtifact = CatalogDigests.artifact(first);
        CatalogDigests.Digest secondArtifact = CatalogDigests.artifact(second);

        assertThat(firstArtifact).isEqualTo(secondArtifact);
        assertThat(CatalogDigests.artifactClosure(List.of(firstArtifact)))
                .isEqualTo(CatalogDigests.artifactClosure(List.of(secondArtifact)));
    }

    private static String digest(PluginIndex index, String id) {
        return index.bundles().stream()
                .filter(bundle -> bundle.manifest().id().equals(id))
                .findFirst()
                .orElseThrow()
                .digest();
    }

    private Path manifestedArtifact(
            String directory,
            String id,
            String name,
            String provider
    ) throws IOException {
        Path root = Files.createDirectories(temporary.resolve(directory));
        Path manifest = root.resolve(BundleManifestParser.RESOURCE_DIRECTORY + id + ".json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {
                  "schemaVersion":1,
                  "id":"%s",
                  "version":"1.0.0",
                  "yanoApi":{"min":1,"max":1},
                  "dependencies":[],
                  "contributions":[{
                    "kind":"finalized-sink",
                    "name":"%s",
                    "provider":"%s"
                  }]
                }
                """.formatted(id, name, provider), StandardCharsets.UTF_8);
        Path service = root.resolve("META-INF/services/"
                + ContributionKind.FINALIZED_SINK.serviceType().getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, provider + "\n", StandardCharsets.UTF_8);
        writeProviderClass(root, provider);
        return root;
    }

    private static void writeProviderClass(Path root, String provider) throws IOException {
        Path classFile = root.resolve(provider.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[]{1});
    }
}
