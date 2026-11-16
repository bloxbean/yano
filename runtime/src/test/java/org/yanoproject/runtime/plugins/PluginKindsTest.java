package org.yanoproject.runtime.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.plugin.operations.PluginHealthProvider;
import org.yanoproject.api.plugin.spi.ContributionKindBinding;
import org.yanoproject.api.plugin.spi.PluginCallbackScope;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.catalog.ContributionKindRegistry;
import org.yanoproject.runtime.plugins.fixture.FixtureBinding;
import org.yanoproject.runtime.plugins.fixture.FixtureProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginKindsTest {
    private static final ContributionKindRegistry REGISTRY = TestPluginKinds.KINDS.registry();

    @Test
    void hostLoaderSuppliesDescriptorKindsWithTheirBindingsAfterBuiltIns() {
        assertThat(REGISTRY.kinds()).extracting(ContributionKind::manifestKey).containsExactly(
                "node-plugin", "utxo-index-contributor", "health", "metrics", "fixture-product");
        assertThat(TestPluginKinds.KINDS.binding(TestPluginKinds.FIXTURE)).isInstanceOf(FixtureBinding.class);
        assertThat(TestPluginKinds.KINDS.binding(ContributionKind.HEALTH).serviceType())
                .isEqualTo(PluginHealthProvider.class);
        assertThat(TestPluginKinds.KINDS.operationsTrackedKinds()).containsExactly("fixture-product");
        assertThat(PluginKinds.BUILT_INS.registry().kinds()).hasSize(4);
    }

    @Test
    void hostLoaderFindsDescriptorsInJarsWithAndWithoutDirectoryEntries(@TempDir Path temporary) throws Exception {
        for (boolean directoryEntries : new boolean[]{false, true}) {
            Path jar = descriptorJar(temporary.resolve("kinds-" + directoryEntries + ".jar"), directoryEntries);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    PluginKindsTest.class.getClassLoader())) {
                PluginKinds kinds = PluginKinds.load(loader);

                ContributionKind jarKind = kinds.registry().fromManifestKey(JarBinding.KIND);
                assertThat(kinds.binding(jarKind)).isInstanceOf(JarBinding.class);
                assertThat(kinds.registry().kinds()).extracting(ContributionKind::manifestKey)
                        .containsExactly("node-plugin", "utxo-index-contributor", "health", "metrics",
                                "fixture-product", JarBinding.KIND);
            }
        }
    }

    @Test
    void everyDescriptorKindNeedsExactlyOneMatchingBinding() {
        assertThatThrownBy(() -> PluginKinds.of(REGISTRY, List.of()))
                .hasMessageContaining("Contribution kind 'fixture-product' has no host binding");
        assertThatThrownBy(() -> PluginKinds.of(REGISTRY, List.of(new FixtureBinding(), new FixtureBinding())))
                .hasMessageContaining("has more than one binding");
        assertThatThrownBy(() -> PluginKinds.of(ContributionKindRegistry.builtIns(), List.of(new FixtureBinding())))
                .hasMessageContaining("'fixture-product' has no host contribution-kind descriptor");
        assertThatThrownBy(() -> PluginKinds.of(REGISTRY, List.of(binding("fixture-product", Runnable.class))))
                .hasMessageContaining("binds java.lang.Runnable but its descriptor declares "
                        + FixtureProvider.class.getName());
        assertThatThrownBy(() -> PluginKinds.of(REGISTRY, List.of(new FixtureBinding(),
                        binding("health", PluginHealthProvider.class))))
                .hasMessageContaining("'health' redefines a built-in kind");
    }

    private static <P> ContributionKindBinding<P> binding(String key, Class<P> type) {
        return new ContributionKindBinding<>() {
            @Override public Class<P> serviceType() { return type; }
            @Override public String manifestKey() { return key; }
            @Override public String selector(P provider) { return key; }
            @Override public P facade(P provider, PluginCallbackScope scope) { return provider; }
        };
    }

    private static Path descriptorJar(Path jar, boolean directoryEntries) throws IOException {
        String descriptor = """
                {"schemaVersion": 1, "owner": "com.example.jarkinds", "kinds": [{"key": "%s",
                 "serviceInterface": "%s", "manifestRequired": true, "trustTier": "CONSENSUS"}]}
                """.formatted(JarBinding.KIND, Runnable.class.getName());
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
            if (directoryEntries) {
                for (String directory : List.of("META-INF/", "META-INF/yano/", "META-INF/services/")) {
                    output.putNextEntry(new JarEntry(directory));
                }
            }
            output.putNextEntry(new JarEntry(ContributionKindRegistry.RESOURCE));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.putNextEntry(new JarEntry("META-INF/services/" + ContributionKindBinding.class.getName()));
            output.write(JarBinding.class.getName().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    /** Binding the descriptor JAR registers through its own service file. */
    public static final class JarBinding implements ContributionKindBinding<Runnable> {
        static final String KIND = "jar-product";

        @Override public Class<Runnable> serviceType() { return Runnable.class; }
        @Override public String manifestKey() { return KIND; }
        @Override public String selector(Runnable provider) { return KIND; }
        @Override public Runnable facade(Runnable provider, PluginCallbackScope scope) { return provider; }
    }
}
