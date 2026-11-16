package org.yanoproject.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.plugin.PluginTrustTier;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContributionKindRegistryTest {
    private static final String KIND = """
            {"key": "%s", "serviceInterface": "%s", "manifestRequired": true,
             "trustTier": "CONSENSUS"%s}""";

    @TempDir
    Path temporary;

    @Test
    void builtInsAreTheFourYanoKindsInStableOrder() {
        ContributionKindRegistry registry = ContributionKindRegistry.builtIns();

        assertThat(registry.kinds()).extracting(ContributionKind::manifestKey)
                .containsExactly("node-plugin", "utxo-index-contributor", "health", "metrics");
        assertThat(registry.fromManifestKey("health")).isSameAs(ContributionKind.HEALTH);
        assertThat(registry.forServiceInterface("org.yanoproject.api.plugin.NodePlugin"))
                .contains(ContributionKind.NODE_PLUGIN);
        assertThat(ContributionKind.NODE_PLUGIN.trustTier()).isEqualTo(PluginTrustTier.REQUIRED);
        assertThat(ContributionKind.UTXO_INDEX_CONTRIBUTOR.reservedSystemNames()).containsExactly("wallet");
        assertThat(registry.kinds()).noneMatch(ContributionKind::operationsTracked);
        assertThatThrownBy(() -> registry.fromManifestKey("fixture-sink"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("contribution kind is not supported");
    }

    @Test
    void readsDescriptorsFromExplodedAndJarHostArtifactsAfterBuiltIns() throws Exception {
        Path jar = jar("kinds.jar", "com.example.jar", descriptor(
                kind("zeta-kind", "com.example.api.ZetaProvider", ", \"operationsTracked\": true")));

        ContributionKindRegistry registry = ContributionKindRegistry.fromHostArtifacts(
                List.of(jar, TestKinds.HOST_ARTIFACT));

        assertThat(registry.kinds()).extracting(ContributionKind::manifestKey).containsExactly(
                "node-plugin", "utxo-index-contributor", "health", "metrics",
                "fixture-domain", "fixture-sink", "zeta-kind");
        ContributionKind zeta = registry.fromManifestKey("zeta-kind");
        assertThat(zeta.operationsTracked()).isTrue();
        assertThat(zeta.maxPerBundle()).isEqualTo(CatalogValidation.MAX_CONTRIBUTIONS);
        assertThat(zeta).isEqualTo(registry.forServiceInterface("com.example.api.ZetaProvider").orElseThrow());
        assertThat(registry.isHostArtifact(jar)).isTrue();
        assertThat(registry.isHostArtifact(temporary)).isFalse();
        assertThat(registry.isApiClass("com/example/api/Other.class")).isTrue();
        assertThat(registry.isApiClass("org/yanoproject/catalog/fixture/FixtureSinkProvider.class")).isTrue();
        assertThat(registry.isApiClass("com/example/impl/Other.class")).isFalse();
        assertThat(ContributionKindRegistry.builtIns().isApiClass("com/example/api/Other.class")).isFalse();
    }

    @Test
    void findsDescriptorsByEntryNameWithAndWithoutJarDirectoryEntries() throws Exception {
        Path bare = jar("bare.jar", "com.example.bare", descriptor(kind("bare-kind", "com.example.Bare", "")));
        Path withDirectories = jar("dirs.jar", "com.example.dirs",
                descriptor(kind("dirs-kind", "com.example.Dirs", "")), true);

        ContributionKindRegistry registry = ContributionKindRegistry.fromHostArtifacts(List.of(bare, withDirectories));
        assertThat(registry.kinds()).extracting(ContributionKind::manifestKey).endsWith("bare-kind", "dirs-kind");
        assertThat(registry.descriptorOwner(registry.fromManifestKey("dirs-kind"))).contains("com.example.dirs");
        assertThat(registry.descriptorOwner(ContributionKind.HEALTH)).isEmpty();
    }

    @Test
    void oneMergedJarKeepsTheDescriptorsOfSeveralOwners() throws Exception {
        Path merged = temporary.resolve("uber.jar");
        try (OutputStream file = Files.newOutputStream(merged); JarOutputStream output = new JarOutputStream(file)) {
            for (String owner : List.of("com.example.first", "com.example.second")) {
                output.putNextEntry(new JarEntry(ContributionKindRegistry.resourcePath(owner)));
                output.write(owned(owner, descriptor(kind(owner.substring(12) + "-kind",
                        owner + ".Provider", ""))).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }

        ContributionKindRegistry registry = ContributionKindRegistry.fromHostArtifacts(List.of(merged));
        assertThat(registry.kinds()).extracting(ContributionKind::manifestKey)
                .endsWith("first-kind", "second-kind");
        assertThat(registry.descriptorOwner(registry.fromManifestKey("second-kind"))).contains("com.example.second");
    }

    @Test
    void rejectsDuplicateKeysRedefinedBuiltInsAndSharedInterfaces() throws Exception {
        assertRejected(List.of(
                        directory("one", "com.example.one", descriptor(kind("same", "com.example.A", ""))),
                        directory("two", "com.example.two", descriptor(kind("same", "com.example.B", "")))),
                "Contribution kind 'same' is defined more than once");
        assertRejected(List.of(directory("builtin", "com.example.builtin",
                        descriptor(kind("health", "com.example.Health", "")))),
                "redefines a built-in kind");
        assertRejected(List.of(directory("interface", "com.example.interface",
                        descriptor(kind("first", "com.example.Shared", ""), kind("second", "com.example.Shared", "")))),
                "is registered under both 'first' and 'second'");
        assertRejected(List.of(directory("builtin-interface", "com.example.builtin-interface",
                        descriptor(kind("other", "org.yanoproject.api.plugin.NodePlugin", "")))),
                "is registered under both 'node-plugin' and 'other'");
        assertRejected(List.of(
                        directory("owner-a", "com.example.owner", descriptor(kind("a", "com.example.A", ""))),
                        directory("owner-b", "com.example.owner", descriptor(kind("b", "com.example.B", "")))),
                "owner 'com.example.owner' is declared by more than one host artifact");
    }

    @Test
    void rejectsConflictingRulesAndMalformedDescriptors() throws Exception {
        assertRejected(List.of(directory("conflict", "com.example.conflict",
                        descriptor(kind("k", "com.example.K",
                                ", \"nameIsBundleId\": true, \"forbiddenNameChars\": \".\"")))),
                "nameIsBundleId conflicts");
        assertRejected(List.of(directory("reserved", "com.example.reserved",
                        descriptor(kind("k", "com.example.K",
                                ", \"forbiddenNameChars\": \".\", \"reservedSystemNames\": [\"a.b\"]")))),
                "must not contain '.'");
        assertRejected(List.of(directory("limit", "com.example.limit",
                        descriptor(kind("k", "com.example.K", ", \"maxPerBundle\": 0")))),
                "maxPerBundle must be between 1 and 256");
        assertRejected(List.of(directory("key", "com.example.key",
                        descriptor(kind("Bad_Key", "com.example.K", "")))),
                "key must be a 1-64 character lowercase hyphenated identifier");
        assertRejected(List.of(directory("unknown", "com.example.unknown",
                        descriptor(kind("k", "com.example.K", ", \"mystery\": 1")))),
                "malformed descriptor JSON or unknown field");
        assertRejected(List.of(directory("schema", "com.example.schema",
                        "{\"schemaVersion\": 2, \"kinds\": []}")),
                "schemaVersion must equal 1");
        assertRejected(List.of(directory("owner", "Not An Owner", descriptor())),
                "file name must be a reverse-DNS owner id");
        assertRejected(List.of(directory("no-owner", "com.example.no-owner", null, descriptor())),
                "owner must be a 3-160 character lowercase reverse-DNS identifier");
        assertRejected(List.of(directory("mismatch", "com.example.file", "com.example.other", descriptor())),
                "owner 'com.example.other' does not match the file name");
    }

    private void assertRejected(List<Path> artifacts, String message) {
        assertThatThrownBy(() -> ContributionKindRegistry.fromHostArtifacts(artifacts))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining(message);
    }

    private static String descriptor(String... kinds) {
        return "{\"schemaVersion\": 1, \"apiPackages\": [\"com.example.api\"], \"kinds\": ["
                + String.join(",", kinds) + "]}";
    }

    private static String kind(String key, String serviceInterface, String extra) {
        return KIND.formatted(key, serviceInterface, extra);
    }

    /** Adds the owner field; a null owner leaves it out. */
    private static String owned(String owner, String descriptor) {
        return owner == null ? descriptor : descriptor.replaceFirst("\\{", "{\"owner\": \"" + owner + "\", ");
    }

    private Path directory(String name, String owner, String descriptor) throws IOException {
        return directory(name, owner, owner, descriptor);
    }

    /** {@code owner} names the file; {@code declared} is the JSON owner field, left out when null. */
    private Path directory(String name, String owner, String declared, String descriptor) throws IOException {
        Path file = temporary.resolve(name).resolve(ContributionKindRegistry.resourcePath(owner));
        Files.createDirectories(file.getParent());
        Files.writeString(file, owned(declared, descriptor), StandardCharsets.UTF_8);
        return temporary.resolve(name);
    }

    private Path jar(String name, String owner, String descriptor) throws IOException {
        return jar(name, owner, descriptor, false);
    }

    private Path jar(String name, String owner, String descriptor, boolean directoryEntries) throws IOException {
        Path jar = temporary.resolve(name);
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
            if (directoryEntries) {
                output.putNextEntry(new JarEntry("META-INF/"));
                output.putNextEntry(new JarEntry("META-INF/yano/"));
            }
            output.putNextEntry(new JarEntry(ContributionKindRegistry.resourcePath(owner)));
            output.write(owned(owner, descriptor).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
