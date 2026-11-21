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
import java.util.function.Supplier;
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
            Path jar = kindsJar(temporary.resolve("kinds-" + directoryEntries + ".jar"), directoryEntries,
                    List.of(JarBinding.class), true);
            try (URLClassLoader loader = loader(jar)) {
                PluginKinds kinds = PluginKinds.load(loader);

                ContributionKind jarKind = kinds.registry().fromManifestKey(JarBinding.KIND);
                assertThat(kinds.binding(jarKind)).isInstanceOf(JarBinding.class);
                assertThat(kinds.registry().kinds()).extracting(ContributionKind::manifestKey)
                        .containsExactly("node-plugin", "utxo-index-contributor", "health", "metrics",
                                "fixture-product", JarBinding.KIND);
            }
        }
    }

    /** A Quarkus uber-JAR keeps one copy per ordinary path and concatenates only META-INF/services. */
    @Test
    void oneMergedJarWithTwoOwnersDescriptorsAndOneServicesFileLoadsBothKinds(@TempDir Path temporary)
            throws Exception {
        Path uber = kindsJar(temporary.resolve("uber.jar"), false,
                List.of(JarBinding.class, SecondJarBinding.class), true);
        try (URLClassLoader loader = loader(uber)) {
            PluginKinds kinds = PluginKinds.load(loader);

            assertThat(kinds.binding(kinds.registry().fromManifestKey(JarBinding.KIND)))
                    .isInstanceOf(JarBinding.class);
            assertThat(kinds.binding(kinds.registry().fromManifestKey(SecondJarBinding.KIND)))
                    .isInstanceOf(SecondJarBinding.class);
            assertThat(kinds.registry().descriptorOwner(kinds.registry().fromManifestKey(SecondJarBinding.KIND)))
                    .contains(SecondJarBinding.OWNER);
        }
    }

    @Test
    void aBindingWhoseOwnerHasNoDescriptorFailsLoading(@TempDir Path temporary) throws Exception {
        Path jar = kindsJar(temporary.resolve("no-descriptor.jar"), false, List.of(JarBinding.class), false);
        try (URLClassLoader loader = loader(jar)) {
            assertThatThrownBy(() -> PluginKinds.load(loader))
                    .hasMessageContaining("descriptor owner '" + JarBinding.OWNER + "' but the host class path has no "
                            + ContributionKindRegistry.resourcePath(JarBinding.OWNER));
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
        assertThatThrownBy(() -> PluginKinds.of(REGISTRY,
                        List.of(binding("com.example.other", "fixture-product", FixtureProvider.class))))
                .hasMessageContaining("'fixture-product' names descriptor owner 'com.example.other' but the kind "
                        + "is declared by 'org.yanoproject.runtime.fixture'");
    }

    private static <P> ContributionKindBinding<P> binding(String key, Class<P> type) {
        return binding("org.yanoproject.runtime.fixture", key, type);
    }

    private static <P> ContributionKindBinding<P> binding(String owner, String key, Class<P> type) {
        return new ContributionKindBinding<>() {
            @Override public Class<P> serviceType() { return type; }
            @Override public String descriptorOwner() { return owner; }
            @Override public String manifestKey() { return key; }
            @Override public String selector(P provider) { return key; }
            @Override public P facade(P provider, PluginCallbackScope scope) { return provider; }
        };
    }

    private static URLClassLoader loader(Path jar) throws IOException {
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, PluginKindsTest.class.getClassLoader());
    }

    /** One JAR with each binding's owner descriptor (unless left out) and one services file naming them all. */
    private static Path kindsJar(Path jar, boolean directoryEntries,
                                 List<Class<? extends ContributionKindBinding<?>>> bindings,
                                 boolean withDescriptors) throws Exception {
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
            if (directoryEntries) {
                for (String directory : List.of("META-INF/", "META-INF/yano/", "META-INF/yano/contribution-kinds/",
                        "META-INF/services/")) {
                    output.putNextEntry(new JarEntry(directory));
                }
            }
            StringBuilder services = new StringBuilder();
            for (Class<? extends ContributionKindBinding<?>> type : bindings) {
                ContributionKindBinding<?> binding = type.getConstructor().newInstance();
                services.append(type.getName()).append('\n');
                if (withDescriptors) {
                    output.putNextEntry(new JarEntry(ContributionKindRegistry.resourcePath(binding.descriptorOwner())));
                    output.write("""
                            {"schemaVersion": 1, "owner": "%s", "kinds": [{"key": "%s",
                             "serviceInterface": "%s", "manifestRequired": true, "trustTier": "CONSENSUS"}]}
                            """.formatted(binding.descriptorOwner(), binding.manifestKey(),
                            binding.serviceType().getName()).getBytes(StandardCharsets.UTF_8));
                }
            }
            output.putNextEntry(new JarEntry("META-INF/services/" + ContributionKindBinding.class.getName()));
            output.write(services.toString().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    /** Binding a test JAR registers through its services file. */
    public static final class JarBinding implements ContributionKindBinding<Runnable> {
        static final String KIND = "jar-product";
        static final String OWNER = "com.example.jarkinds";

        @Override public Class<Runnable> serviceType() { return Runnable.class; }
        @Override public String descriptorOwner() { return OWNER; }
        @Override public String manifestKey() { return KIND; }
        @Override public String selector(Runnable provider) { return KIND; }
        @Override public Runnable facade(Runnable provider, PluginCallbackScope scope) { return provider; }
    }

    /** A second owner's binding, merged into the same services file. */
    public static final class SecondJarBinding implements ContributionKindBinding<Supplier> {
        static final String KIND = "second-product";
        static final String OWNER = "com.example.secondkinds";

        @Override public Class<Supplier> serviceType() { return Supplier.class; }
        @Override public String descriptorOwner() { return OWNER; }
        @Override public String manifestKey() { return KIND; }
        @Override public String selector(Supplier provider) { return KIND; }
        @Override public Supplier facade(Supplier provider, PluginCallbackScope scope) { return provider; }
    }
}
