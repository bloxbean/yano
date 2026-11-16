package org.yanoproject.runtime.plugins;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.spi.PluginProducts;
import org.yanoproject.catalog.ContributionKindRegistry;
import org.yanoproject.catalog.PluginIndex;
import org.yanoproject.runtime.host.DefaultHostLifecycleRegistry;
import org.yanoproject.runtime.host.NoopOperationsRecorder;
import org.yanoproject.runtime.plugins.fixture.FixtureProvider;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A plugin-directory bundle against the real host seams: owner identity from the catalog callback tracker,
 * {@link PluginRuntimeEnvironment#embeddedBundle} and descriptor trust in {@link PluginRuntimeEnvironment#open}.
 */
public class DirectoryBundleHostBoundaryTest {
    static final String ROOT = "com.example.owner-root";
    static final String GUEST = "com.example.guest";
    /** Run by the directory bundle's {@code NodePlugin.init}. */
    public static final AtomicReference<Runnable> GUEST_INIT = new AtomicReference<>(() -> { });
    /** Run by the directory bundle's provider callback {@code FixtureProvider.create}. */
    public static final AtomicReference<Runnable> GUEST_CALLBACK = new AtomicReference<>(() -> { });
    private static final AtomicReference<Fixture> fixture = new AtomicReference<>();
    private static final List<String> outcomes = new ArrayList<>();

    @TempDir Path temporary;

    @AfterEach
    void reset() {
        GUEST_INIT.set(() -> { });
        GUEST_CALLBACK.set(() -> { });
        fixture.set(null);
        outcomes.clear();
    }

    @Test
    void directoryAndNestedCallbacksCannotRegisterForAnEmbeddedOwnerDuringInitOrAssembly() throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        writeJar(plugins.resolve("guest.jar"), guestEntries(false));
        GUEST_INIT.set(() -> {
            attempt("guest init as root", () -> registry().registerAssembly(ROOT, () -> { }));
            attempt("guest init as itself", () -> registry().registerAssembly(GUEST, () -> { }));
        });
        GUEST_CALLBACK.set(() -> {
            attempt("nested assembly", () -> registry().registerAssembly(ROOT, () -> { }));
            attempt("nested readiness", () -> registry().registerRestartReadiness(ROOT, () -> true));
            attempt("nested products", () -> registry().registerPluginProducts(ROOT, NO_PRODUCTS));
        });
        try (URLClassLoader host = hostLoader();
             var scheduler = Executors.newSingleThreadScheduledExecutor();
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                     new PluginsOptions(true, false, Set.of(), Set.of(), Map.of()),
                     PluginLoaderHandle.directory(plugins, host));
             PluginManager manager = environment.createNodePluginManager(new NoopEventBus(), scheduler);
             var registry = new DefaultHostLifecycleRegistry(DefaultHostLifecycleRegistry.Callers.of(environment),
                     new NoopOperationsRecorder())) {
            assertThat(environment.embeddedBundle(ROOT)).isTrue();
            assertThat(environment.embeddedBundle(GUEST)).isFalse();
            fixture.set(new Fixture(registry, environment));

            manager.discoverAndInit();
            registry.runAssemblies(environment.classLoader());
        }

        String impersonation = "Plugin '" + GUEST + "' cannot register for owner '" + ROOT + "'";
        String notEmbedded = "Only embedded host-class-path bundles may register as owners; '" + GUEST + "'";
        String nested = "Owner registrations are accepted only from the owner's own NodePlugin.init or assembly "
                + "step, not from a nested plugin callback";
        assertThat(outcomes).containsExactlyInAnyOrder(
                "guest init as root: " + impersonation,
                "guest init as itself: " + notEmbedded,
                "root init: accepted",
                "nested assembly: " + nested,
                "nested readiness: " + nested,
                "nested products: " + nested,
                "root init after nested call: accepted",
                "root assembly: started",
                "nested assembly: Assembly steps cannot register further assembly steps",
                "nested readiness: " + nested,
                "nested products: " + nested,
                "root assembly after nested call: accepted");
    }

    @Test
    void directoryBundleCarryingAContributionKindDescriptorIsRejectedOnOpen() throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        writeJar(plugins.resolve("guest.jar"), guestEntries(true));
        try (URLClassLoader host = hostLoader()) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    new PluginsOptions(true, false, Set.of(), Set.of(), Map.of()),
                    PluginLoaderHandle.directory(plugins, host)))
                    .hasStackTraceContaining("contribution-kind descriptor '"
                            + ContributionKindRegistry.resourcePath("com.example.guest")
                            + "' is trusted only from host class-path artifacts");
        }
    }

    private static final PluginProducts NO_PRODUCTS = new PluginProducts() {
        @Override public void resume() { }
        @Override public void sealAndAwait() { }
    };

    private static DefaultHostLifecycleRegistry registry() {
        return fixture.get().registry();
    }

    private static FixtureProvider guestProvider() {
        return fixture.get().environment().providers().find(FixtureProvider.class, "guest").orElseThrow();
    }

    private static void attempt(String label, Runnable registration) {
        try {
            registration.run();
            outcomes.add(label + ": accepted");
        } catch (IllegalStateException rejected) {
            outcomes.add(label + ": " + rejected.getMessage().replaceFirst(" is not embedded$", ""));
        }
    }

    /** Embedded owner, loaded from the host class path. */
    public static final class OwnerRoot implements NodePlugin {
        @Override public String id() { return ROOT; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext context) {
            attempt("root init", () -> registry().registerAssembly(ROOT, () -> {
                outcomes.add("root assembly: started");
                guestProvider().create();
                attempt("root assembly after nested call", () -> registry().registerPluginProducts(ROOT, NO_PRODUCTS));
            }));
            guestProvider().create();
            attempt("root init after nested call", () -> registry().registerRestartReadiness(ROOT, () -> true));
        }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    private record Fixture(DefaultHostLifecycleRegistry registry, PluginRuntimeEnvironment environment) {
    }

    /** Host loader carrying the embedded owner; plugin manifests and services come only from its own JAR. */
    private URLClassLoader hostLoader() throws IOException {
        Path jar = temporary.resolve("embedded.jar");
        String manifest = """
                {"schemaVersion":1,"id":"%s","version":"1.0.0",
                 "yanoApi":{"min":%d,"max":%d,"minLevel":1},"dependencies":[],
                 "contributions":[{"kind":"node-plugin","name":"%s","provider":"%s"}]}
                """.formatted(ROOT, PluginCatalogBuilder.PLUGIN_API_MAJOR, PluginCatalogBuilder.PLUGIN_API_MAJOR,
                ROOT, OwnerRoot.class.getName());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/yano/plugins/" + ROOT + ".json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/" + NodePlugin.class.getName(),
                (OwnerRoot.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        writeJar(jar, entries);
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals("META-INF/services/" + NodePlugin.class.getName())
                        || name.equals("META-INF/services/" + FixtureProvider.class.getName())
                        || name.startsWith("META-INF/yano/plugins/") || name.equals(PluginIndex.RESOURCE_PATH)) {
                    return findResources(name);
                }
                return super.getResources(name);
            }
        };
    }

    /** The directory bundle: a NodePlugin and a fixture-product provider compiled outside the host class path. */
    private Map<String, byte[]> guestEntries(boolean withDescriptor) throws IOException {
        Path sources = Files.createDirectories(temporary.resolve("guest-src/com/example/guest"));
        Files.writeString(sources.resolve("GuestPlugin.java"), """
                package com.example.guest;
                import org.yanoproject.api.plugin.NodePlugin;
                import org.yanoproject.api.plugin.PluginContext;
                import org.yanoproject.runtime.plugins.DirectoryBundleHostBoundaryTest;
                public final class GuestPlugin implements NodePlugin {
                    public String id() { return "%s"; }
                    public String version() { return "1.0.0"; }
                    public void init(PluginContext context) { DirectoryBundleHostBoundaryTest.GUEST_INIT.get().run(); }
                    public void start() { }
                    public void stop() { }
                    public void close() { }
                }
                """.formatted(GUEST));
        Files.writeString(sources.resolve("GuestProvider.java"), """
                package com.example.guest;
                import org.yanoproject.runtime.plugins.DirectoryBundleHostBoundaryTest;
                import org.yanoproject.runtime.plugins.fixture.FixtureProduct;
                import org.yanoproject.runtime.plugins.fixture.FixtureProvider;
                public final class GuestProvider implements FixtureProvider {
                    public String id() { return "guest"; }
                    public FixtureProduct create() {
                        DirectoryBundleHostBoundaryTest.GUEST_CALLBACK.get().run();
                        return new GuestProduct();
                    }
                    static final class GuestProduct implements FixtureProduct { }
                }
                """);
        Path classes = Files.createDirectories(temporary.resolve("guest-classes"));
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path") + System.getProperty("path.separator")
                        + codeSource(NodePlugin.class) + System.getProperty("path.separator") + codeSource(getClass()),
                "-d", classes.toString(),
                sources.resolve("GuestPlugin.java").toString(), sources.resolve("GuestProvider.java").toString());
        assertThat(compiled).isZero();

        String manifest = """
                {"schemaVersion":1,"id":"%s","version":"1.0.0",
                 "yanoApi":{"min":%d,"max":%d,"minLevel":1},"dependencies":[],
                 "contributions":[{"kind":"node-plugin","name":"%s","provider":"com.example.guest.GuestPlugin"},
                                  {"kind":"fixture-product","name":"guest",
                                   "provider":"com.example.guest.GuestProvider"}]}
                """.formatted(GUEST, PluginCatalogBuilder.PLUGIN_API_MAJOR, PluginCatalogBuilder.PLUGIN_API_MAJOR,
                GUEST);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/yano/plugins/" + GUEST + ".json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/" + NodePlugin.class.getName(),
                "com.example.guest.GuestPlugin\n".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/" + FixtureProvider.class.getName(),
                "com.example.guest.GuestProvider\n".getBytes(StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        if (withDescriptor) {
            entries.put(ContributionKindRegistry.resourcePath("com.example.guest"), """
                    {"schemaVersion": 1, "owner": "com.example.guest", "kinds": []}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    private static String codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
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
}
