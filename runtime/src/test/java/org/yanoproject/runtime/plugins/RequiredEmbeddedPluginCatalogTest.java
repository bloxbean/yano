package org.yanoproject.runtime.plugins;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.PluginDigestMode;
import org.yanoproject.api.plugin.PluginSourceCategory;
import org.yanoproject.catalog.BundleContribution;
import org.yanoproject.catalog.BundleDependency;
import org.yanoproject.catalog.BundleManifest;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.catalog.IndexedBundle;
import org.yanoproject.catalog.PluginIndex;
import org.yanoproject.catalog.PluginIndexCodec;
import org.yanoproject.catalog.SemVersion;
import org.yanoproject.catalog.YanoApiRange;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredEmbeddedPluginCatalogTest {
    private static final String ROOT = "com.example.required-framework";
    private static final String DEPENDENCY = "com.example.required-library";
    private static final String OPTIONAL = "com.example.optional";
    private static final String SERVICE = "META-INF/services/" + NodePlugin.class.getName();
    private static final List<String> calls = new ArrayList<>();

    @TempDir Path directory;

    @BeforeEach
    void resetCalls() {
        calls.clear();
    }

    @Test
    void disabledPluginsActivateOnlyEmbeddedClosureWithoutLegacyOrDirectoryDiscovery() throws Exception {
        PluginsOptions disabled = new PluginsOptions(false, false,
                Set.of("com.example.absent-optional"), Set.of(ROOT, DEPENDENCY), Map.of());
        Path pluginDirectory = Files.createDirectory(directory.resolve("plugins"));
        // A directory artifact with no manifest/provider would fail catalog discovery.
        writeJar(pluginDirectory.resolve("not-a-plugin.jar"), Map.of("marker.txt", new byte[]{1}));
        try (URLClassLoader loader = loader(true);
             var scheduler = Executors.newSingleThreadScheduledExecutor();
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(disabled,
                     PluginLoaderHandle.packagedDirectory(pluginDirectory, loader), Set.of(ROOT))) {
            assertThat(environment.classLoader()).isSameAs(loader);
            assertThat(environment.classLoader().getResource("marker.txt")).isNull();
            assertThat(environment.selectedBundleOrder()).containsExactly(DEPENDENCY, ROOT);
            assertThat(environment.catalog().bundles()).extracting(bundle -> bundle.id())
                    .containsExactlyInAnyOrder(ROOT, DEPENDENCY);
            try (PluginManager manager = environment.createNodePluginManager(new NoopEventBus(), scheduler)) {
                manager.discoverAndInit();
                assertThat(manager.getService("framework", String.class)).contains("ready");
                assertThat(manager.getService("framework", Integer.class)).isEmpty();
                manager.startAll();
                manager.stopAll();
                assertThat(manager.getService("framework", String.class)).contains("ready");
                assertThat(manager.getService("cycle", String.class)).isEmpty();
                manager.startAll();
                assertThat(manager.getService("cycle", String.class)).contains("running");
            }
            assertThat(calls).doesNotContain("optional.construct", "legacy.construct");
            assertThat(calls).containsSubsequence("dependency.init", "root.init", "dependency.start", "root.start");
            assertThat(calls).containsSubsequence("root.close", "dependency.close");
        }
    }

    @Test
    void nativeCatalogUsesTheSameRequiredSelection() throws Exception {
        try (URLClassLoader loader = loader(true);
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                     new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                     PluginLoaderHandle.nativeClasspath(loader), Set.of(ROOT))) {
            assertThat(environment.selectedBundleOrder()).containsExactly(DEPENDENCY, ROOT);
            assertThat(environment.catalog().bundles()).allSatisfy(bundle ->
                    assertThat(bundle.source()).isEqualTo(PluginSourceCategory.NATIVE));
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void disabledPluginsDoNotResolveMissingOrMalformedOptionalProviders() throws Exception {
        for (String badProvider : List.of("com.example.optional.DoesNotExist", "malformed provider name!")) {
            // Each scenario owns immutable bytes: jar: URLs can retain a cached
            // ZipFile after their short-lived URLClassLoader has been closed.
            Path jar = Files.createTempFile(directory, "bad-optional-", ".jar");
            String descriptors = badProvider + "\n" + RootPlugin.class.getName() + "\n"
                    + DependencyPlugin.class.getName() + "\n";
            writeJar(jar, Map.of(PluginIndex.RESOURCE_PATH, new PluginIndexCodec().write(index()),
                    SERVICE, descriptors.getBytes(StandardCharsets.UTF_8)));
            try (URLClassLoader loader = isolatedLoader(new URL[]{jar.toUri().toURL()});
                 var scheduler = Executors.newSingleThreadScheduledExecutor();
                 PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                         new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                         PluginLoaderHandle.packagedClasspath(loader), Set.of(ROOT))) {
                try (PluginManager manager = environment.createNodePluginManager(new NoopEventBus(), scheduler)) {
                    manager.discoverAndInit();
                    assertThat(manager.getService("framework", String.class)).contains("ready");
                }
            }
        }
        assertThat(calls).doesNotContain("optional.construct", "legacy.construct");
    }

    @Test
    void requiredProviderStillNeedsItsMatchingSpiRegistration() throws Exception {
        Path jar = directory.resolve("missing-required-registration.jar");
        writeJar(jar, Map.of(PluginIndex.RESOURCE_PATH, new PluginIndexCodec().write(index()),
                SERVICE, (DependencyPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8)));
        try (URLClassLoader loader = isolatedLoader(new URL[]{jar.toUri().toURL()})) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                    PluginLoaderHandle.packagedClasspath(loader), Set.of(ROOT)))
                    .hasMessageContaining("has no matching ServiceLoader entry");
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void requiredSelectionDoesNotChangeOptionalAllowAndDenyPolicy() throws Exception {
        try (URLClassLoader loader = loader(false);
             var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            for (boolean denyOptional : List.of(false, true)) {
                PluginsOptions options = new PluginsOptions(true, false, Set.of(OPTIONAL),
                        denyOptional ? Set.of(ROOT, OPTIONAL) : Set.of(ROOT), Map.of());
                try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(options,
                        PluginLoaderHandle.packagedClasspath(loader), Set.of(ROOT))) {
                    assertThat(environment.selectedBundleIds()).isEqualTo(denyOptional
                            ? Set.of(ROOT, DEPENDENCY) : Set.of(ROOT, DEPENDENCY, OPTIONAL));
                    try (PluginManager manager = environment.createNodePluginManager(new NoopEventBus(), scheduler)) {
                        manager.discoverAndInit();
                    }
                }
            }
            assertThat(calls.stream().filter("optional.construct"::equals).count()).isEqualTo(1);
        }
    }

    @Test
    void emptyRequiredSelectionPreservesDisabledDiscovery() throws Exception {
        ClassLoader hostile = new ClassLoader(getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) {
                throw new AssertionError("Disabled discovery must not read resources");
            }
        };
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                PluginLoaderHandle.packagedClasspath(hostile), Set.of())) {
            assertThat(environment.selectedBundleIds()).isEmpty();
        }
    }

    @Test
    void directoryBundleCannotSatisfyRequiredRootOrDependency() throws IOException {
        PluginIndex index = index();
        Path artifact = Files.write(directory.resolve("external.jar"), new byte[]{1});
        var directoryInput = new PluginCatalogBuilder.CatalogInput(index,
                PluginSourceCategory.DIRECTORY, artifact);
        assertThatThrownBy(() -> new PluginCatalogBuilder().build(PluginsOptions.defaults(),
                getClass().getClassLoader(), List.of(directoryInput), false, Set.of(ROOT)))
                .hasMessageContaining("Host-required embedded").hasMessageContaining(ROOT);
        PluginIndex rootOnly = new PluginIndex(1,
                index.bundles().stream().filter(bundle -> bundle.manifest().id().equals(ROOT)).toList(), List.of());
        var embeddedInput = new PluginCatalogBuilder.CatalogInput(rootOnly, PluginSourceCategory.CLASSPATH);
        assertThatThrownBy(() -> new PluginCatalogBuilder().build(PluginsOptions.defaults(),
                getClass().getClassLoader(), List.of(embeddedInput, directoryInput), false, Set.of(ROOT)))
                .hasMessageContaining("Host-required embedded").hasMessageContaining(DEPENDENCY);
        assertThat(calls).isEmpty();
    }

    @Test
    void requiredSelectionStillEnforcesDependencyVersionRanges() throws Exception {
        IndexedBundle root = bundle(ROOT, RootPlugin.class,
                List.of(new BundleDependency(DEPENDENCY, SemVersion.parse("2.0.0"), null)));
        PluginIndex incompatible = new PluginIndex(1,
                List.of(root, bundle(DEPENDENCY, DependencyPlugin.class, List.of())), List.of());
        try (URLClassLoader loader = loader(false)) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()), loader,
                    List.of(new PluginCatalogBuilder.CatalogInput(incompatible, PluginSourceCategory.CLASSPATH)),
                    false, Set.of(ROOT)))
                    .hasMessageContaining("incompatible version");
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void libraryEmbeddingFindsRequiredJarAndExplodedDependencyWithoutAggregateIndex() throws Exception {
        Path rootJar = directory.resolve("framework.jar");
        writeJar(rootJar, manifestEntries(ROOT, RootPlugin.class, DEPENDENCY));
        Path dependencyTree = Files.createDirectory(directory.resolve("library-classes"));
        for (var entry : manifestEntries(DEPENDENCY, DependencyPlugin.class, null).entrySet()) {
            Path output = dependencyTree.resolve(entry.getKey());
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue());
        }
        try (URLClassLoader loader = isolatedLoader(new URL[]{rootJar.toUri().toURL(), dependencyTree.toUri().toURL()});
             var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            for (boolean optionalEnabled : List.of(false, true)) {
                try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                        new PluginsOptions(optionalEnabled, false, Set.of(), Set.of(ROOT), Map.of()),
                        PluginLoaderHandle.classpath(loader), Set.of(ROOT))) {
                    assertThat(environment.selectedBundleOrder()).containsExactly(DEPENDENCY, ROOT);
                    assertThat(environment.catalog().bundles()).extracting(bundle -> bundle.digestMode())
                            .containsExactlyInAnyOrder(PluginDigestMode.JAR, PluginDigestMode.ARTIFACT_TREE);
                    try (PluginManager manager = environment.createNodePluginManager(new NoopEventBus(), scheduler)) {
                        manager.discoverAndInit();
                        assertThat(manager.getService("framework", String.class)).contains("ready");
                    }
                }
            }
            // Authoring manifests never substitute for the authoritative packaged/native index.
            PluginsOptions disabled = new PluginsOptions(false, false, Set.of(), Set.of(), Map.of());
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(disabled,
                    PluginLoaderHandle.packagedClasspath(loader), Set.of(ROOT)))
                    .hasMessageContaining("requires exactly one embedded aggregate index");
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(disabled,
                    PluginLoaderHandle.nativeClasspath(loader), Set.of(ROOT)))
                    .hasMessageContaining("requires exactly one embedded aggregate index");
        }
    }

    @Test
    void libraryEmbeddingRejectsDuplicateQualifiedManifests() throws Exception {
        Path first = directory.resolve("first.jar");
        Path second = directory.resolve("second.jar");
        writeJar(first, manifestEntries(ROOT, RootPlugin.class, DEPENDENCY));
        Files.copy(first, second);
        try (URLClassLoader loader = isolatedLoader(new URL[]{first.toUri().toURL(), second.toUri().toURL()})) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                    PluginLoaderHandle.classpath(loader), Set.of(ROOT)))
                    .hasMessageContaining("Multiple classpath manifests");
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void libraryFallbackNeverTakesRequiredManifestsFromPluginDirectory() throws Exception {
        Path pluginDirectory = Files.createDirectory(directory.resolve("directory-only"));
        writeJar(pluginDirectory.resolve("framework.jar"), manifestEntries(ROOT, RootPlugin.class, DEPENDENCY));
        try (URLClassLoader loader = isolatedLoader(new URL[0])) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                    PluginLoaderHandle.directory(pluginDirectory, loader), Set.of(ROOT)))
                    .hasMessageContaining("has no classpath manifest");
        }
        assertThat(calls).isEmpty();
    }

    private Map<String, byte[]> manifestEntries(String id, Class<? extends NodePlugin> provider,
                                               String dependency) throws IOException {
        String manifest = """
                {"schemaVersion":1,"id":"%s","version":"1.0.0",
                 "yanoApi":{"min":%d,"max":%d,"minLevel":1},"dependencies":%s,
                 "contributions":[{"kind":"node-plugin","name":"%s","provider":"%s"}]}
                """.formatted(id, PluginCatalogBuilder.PLUGIN_API_MAJOR, PluginCatalogBuilder.PLUGIN_API_MAJOR,
                dependency == null ? "[]" : "[{\"id\":\"" + dependency + "\"}]", id, provider.getName());
        String classResource = provider.getName().replace('.', '/') + ".class";
        try (var bytes = getClass().getClassLoader().getResourceAsStream(classResource)) {
            return Map.of("META-INF/yano/plugins/" + id + ".json", manifest.getBytes(StandardCharsets.UTF_8),
                    SERVICE, (provider.getName() + "\n").getBytes(StandardCharsets.UTF_8),
                    classResource, bytes.readAllBytes());
        }
    }

    private URLClassLoader loader(boolean includeLegacy) throws IOException {
        Path jar = directory.resolve(includeLegacy ? "with-legacy.jar" : "embedded.jar");
        String providers = RootPlugin.class.getName() + "\n" + DependencyPlugin.class.getName()
                + "\n" + OptionalPlugin.class.getName() + "\n"
                + (includeLegacy ? ExplosiveLegacyPlugin.class.getName() + "\n" : "");
        writeJar(jar, Map.of(PluginIndex.RESOURCE_PATH, new PluginIndexCodec().write(index()),
                SERVICE, providers.getBytes(StandardCharsets.UTF_8)));
        return isolatedLoader(new URL[]{jar.toUri().toURL()});
    }

    private URLClassLoader isolatedLoader(URL[] urls) {
        return new URLClassLoader(urls, getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                if (name.startsWith("META-INF/services/") || name.startsWith("META-INF/yano/plugins/")
                        || name.equals(PluginIndex.RESOURCE_PATH)) {
                    return findResources(name);
                }
                return super.getResources(name);
            }
        };
    }

    private static PluginIndex index() {
        return new PluginIndex(1, List.of(
                bundle(ROOT, RootPlugin.class, List.of(new BundleDependency(DEPENDENCY, null, null))),
                bundle(DEPENDENCY, DependencyPlugin.class, List.of()),
                bundle(OPTIONAL, OptionalPlugin.class, List.of())), List.of());
    }

    private static IndexedBundle bundle(String id, Class<? extends NodePlugin> provider,
                                        List<BundleDependency> dependencies) {
        return new IndexedBundle(new BundleManifest(1, id, SemVersion.parse("1.0.0"),
                new YanoApiRange(PluginCatalogBuilder.PLUGIN_API_MAJOR, PluginCatalogBuilder.PLUGIN_API_MAJOR, 1),
                dependencies, List.of(new BundleContribution(ContributionKind.NODE_PLUGIN, id, provider.getName()))),
                "sha256:" + "a".repeat(64), PluginDigestMode.ARTIFACT_CLOSURE);
    }

    private static void writeJar(Path path, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    public static class DependencyPlugin implements NodePlugin {
        public String id() { return DEPENDENCY; }
        public String version() { return "1.0.0"; }
        public void init(PluginContext context) { calls.add("dependency.init"); }
        public void start() { calls.add("dependency.start"); }
        public void stop() { calls.add("dependency.stop"); }
        public void close() { calls.add("dependency.close"); }
    }

    public static class RootPlugin implements NodePlugin {
        private PluginContext context;
        public String id() { return ROOT; }
        public String version() { return "1.0.0"; }
        public Set<String> dependsOn() { return Set.of(DEPENDENCY); }
        public void init(PluginContext context) {
            this.context = context;
            calls.add("root.init");
            context.registerService("framework", "ready");
        }
        public void start() { calls.add("root.start"); context.registerService("cycle", "running"); }
        public void stop() { calls.add("root.stop"); }
        public void close() { calls.add("root.close"); }
    }

    public static class OptionalPlugin extends DependencyPlugin {
        public OptionalPlugin() { calls.add("optional.construct"); }
        @Override public String id() { return OPTIONAL; }
    }

    public static class ExplosiveLegacyPlugin extends DependencyPlugin {
        public ExplosiveLegacyPlugin() {
            calls.add("legacy.construct");
            throw new AssertionError("Unrelated legacy provider must never be constructed");
        }
    }
}
