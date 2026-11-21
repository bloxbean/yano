package org.yanoproject.runtime.assembly;

import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.operations.PluginOperationsRecorder;
import org.yanoproject.api.plugin.spi.PluginProducts;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-064 I5: the Host API 1.2 activation order of an embedded owner, observed through a fixture
 * NodePlugin on a Cardano host and on an app-ledger host.
 */
class HostActivationOrderTest {
    static final String ID = "org.yanoproject.runtime.fixture.lifecycle";
    static final String HOLDER_KEY = "org.yanoproject.runtime.fixture.holder";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final AtomicBoolean READY = new AtomicBoolean(true);
    static final AtomicBoolean FAIL_START = new AtomicBoolean();
    static final AtomicBoolean FAIL_RESUME = new AtomicBoolean();
    static final AtomicReference<Long> APP_MAGIC = new AtomicReference<>();
    static final AtomicReference<HostApi> HOST = new AtomicReference<>();
    static final AtomicReference<ClassLoader> PLUGIN_LOADER = new AtomicReference<>();

    @TempDir
    Path temporary;

    @BeforeEach
    void reset() {
        EVENTS.clear();
        READY.set(true);
        FAIL_START.set(false);
        FAIL_RESUME.set(false);
        APP_MAGIC.set(null);
        HOST.set(null);
        PLUGIN_LOADER.set(null);
    }

    @Test
    void cardanoHostRunsAssemblyThenSealThenPrePublicationAndPositionsPluginProducts() throws Exception {
        Yano yano = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .runtimeOptions(requiredOptions())
                .pluginLoader(fixtureLoader())
                .build();
        try {
            assertThat(EVENTS).containsExactly("init", "assembly:owner-tccl", "assembly:sealed-late-n2n:false");
            assertThat(yano.service(HOLDER_KEY, String.class)).contains("holder");
            assertThat(yano.service(HostApi.SERVICE_KEY, HostApi.class))
                    .as("host services are not plugin services").isEmpty();
            assertThat(HOST.get().networkName()).isNotBlank();

            yano.lifecycle().start();
            // Cardano: products resume right after startAll(), before the pre-publication stage.
            assertThat(EVENTS.subList(3, EVENTS.size())).containsExactly(
                    "start", "products.resume", "pre-publication.start", "late-registration-rejected");
            assertThat(EVENTS).contains("late-registration-rejected");

            EVENTS.clear();
            yano.lifecycle().stop();
            // Reverse kernel stop, then the post-drain plugin stop seals products before NodePlugin.stop.
            assertThat(EVENTS).containsExactly("pre-publication.stop", "products.seal", "stop");

            READY.set(false);
            EVENTS.clear();
            assertThatThrownBy(() -> yano.lifecycle().start()).hasMessageContaining("still draining");
            assertThat(EVENTS).containsExactly("readiness:false");
            assertThat(yano.lifecycle().isRunning()).isFalse();

            READY.set(true);
            yano.lifecycle().start();
            assertThat(yano.lifecycle().isRunning()).isTrue();
            yano.lifecycle().stop();
        } finally {
            yano.close();
        }
        assertThat(EVENTS).contains("close");
        assertThat(yano.service(HOLDER_KEY, String.class)).isEmpty();
    }

    @Test
    void startupRollbackSealsPluginProductsBeforeStoppingPlugins() throws Exception {
        FAIL_START.set(true);
        Yano yano = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .runtimeOptions(requiredOptions())
                .pluginLoader(fixtureLoader())
                .build();
        try {
            assertThatThrownBy(() -> yano.lifecycle().start()).hasStackTraceContaining("fixture start failed");
            assertThat(EVENTS).contains("products.seal").doesNotContain("products.resume");
        } finally {
            yano.close();
        }
    }

    @Test
    void appLedgerHostTakesItsNetworkFromThePluginAndPositionsProductsInTheOperationsStage() throws Exception {
        APP_MAGIC.set(77_001L);
        Yano yano = YanoAssembly.fromConfig(appLedgerConfig())
                .runtimeOptions(requiredOptions())
                .pluginLoader(fixtureLoader())
                .build();
        try {
            assertThat(EVENTS).startsWith("init", "assembly:owner-tccl", "assembly:sealed-late-n2n:false");
            assertThat(HOST.get().networkName()).isEqualTo("app-77001");
            assertThat(yano.service(HOLDER_KEY, String.class)).contains("holder");
            yano.lifecycle().start();
            assertThat(EVENTS).containsSubsequence("start", "pre-publication.start", "products.resume");
            EVENTS.clear();
            yano.lifecycle().stop();
            assertThat(EVENTS).containsSubsequence("products.seal", "pre-publication.stop", "stop");
        } finally {
            yano.close();
        }
    }

    @Test
    void appLedgerStartupRollbackAfterAFailedResumeSealsPluginProducts() throws Exception {
        APP_MAGIC.set(77_002L);
        FAIL_RESUME.set(true);
        Yano yano = YanoAssembly.fromConfig(appLedgerConfig())
                .runtimeOptions(requiredOptions())
                .pluginLoader(fixtureLoader())
                .build();
        try {
            EVENTS.clear();
            assertThatThrownBy(() -> yano.lifecycle().start()).hasStackTraceContaining("fixture resume failed");
            // The failed stage seals its own products, then the kernel stops and closes the earlier stages.
            assertThat(EVENTS).containsExactly("start", "pre-publication.start", "late-registration-rejected",
                    "products.resume", "products.seal", "pre-publication.stop", "stop", "close");
        } finally {
            yano.close();
        }
    }

    @Test
    void appLedgerHostWithoutAnAppOnlyNetworkOrWithACardanoMagicFailsStartup() throws Exception {
        assertThatThrownBy(() -> YanoAssembly.fromConfig(appLedgerConfig())
                .runtimeOptions(RuntimeOptions.defaults())
                .build())
                .hasMessageContaining("app-ledger mode requires a plugin that provides an app-only network");

        APP_MAGIC.set(764_824_073L);
        assertThatThrownBy(() -> YanoAssembly.fromConfig(appLedgerConfig())
                .runtimeOptions(requiredOptions())
                .pluginLoader(fixtureLoader())
                .build())
                .hasMessageContaining("failed host assembly")
                .hasRootCauseMessage("App-only network magic 764824073 is a Cardano network magic; "
                        + "use a distinct app network identity");
        assertThat(EVENTS).contains("close");
    }

    private YanoConfig appLedgerConfig() {
        return YanoConfig.serverOnly(0).toBuilder().mode(RuntimeMode.APP_LEDGER).enableServer(false).build();
    }

    private static RuntimeOptions requiredOptions() {
        return new RuntimeOptions(EventsOptions.defaults(),
                new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                Map.of(YanoPropertyKeys.Plugins.REQUIRED, ID));
    }

    /** A host class path holding only the fixture bundle: parent plugin metadata stays hidden. */
    private PluginLoaderHandle fixtureLoader() throws IOException {
        Path jar = temporary.resolve("fixture-" + System.nanoTime() + ".jar");
        String classEntry = LifecycleFixturePlugin.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file);
             InputStream classBytes = getClass().getClassLoader().getResourceAsStream(classEntry)) {
            write(output, "META-INF/yano/plugins/" + ID + ".json", """
                    {"schemaVersion": 1, "id": "%s", "version": "1.0.0",
                     "yanoApi": {"min": 3, "max": 3, "minLevel": 14},
                     "contributions": [{"kind": "node-plugin", "name": "%s", "provider": "%s"}]}
                    """.formatted(ID, ID, LifecycleFixturePlugin.class.getName()).getBytes(StandardCharsets.UTF_8));
            write(output, "META-INF/services/" + NodePlugin.class.getName(),
                    LifecycleFixturePlugin.class.getName().getBytes(StandardCharsets.UTF_8));
            write(output, classEntry, classBytes.readAllBytes());
        }
        ClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.startsWith("META-INF/services/") || name.startsWith("META-INF/yano")
                        ? findResources(name) : super.getResources(name);
            }
        };
        PLUGIN_LOADER.set(loader);
        return PluginLoaderHandle.classpath(loader);
    }

    private static void write(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    /** Embedded owner that registers every Host API 1.2 hook and records when the host calls it. */
    public static final class LifecycleFixturePlugin implements NodePlugin {
        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }

        @Override
        public void init(PluginContext ctx) {
            EVENTS.add("init");
            HostApi host = ctx.getService(HostApi.SERVICE_KEY, HostApi.class).orElseThrow();
            HOST.set(host);
            HostLifecycleRegistry lifecycle = host.lifecycle();
            ctx.registerService(HOLDER_KEY, "holder");
            lifecycle.registerAssembly(ID, () -> {
                EVENTS.add(Thread.currentThread().getContextClassLoader() == PLUGIN_LOADER.get()
                        ? "assembly:owner-tccl" : "assembly:caller-tccl");
                if (APP_MAGIC.get() != null) {
                    host.n2n().requireAppOnlyNetwork(APP_MAGIC.get());
                    host.n2n().registerInbound(32_000, () -> {
                        throw new UnsupportedOperationException("no sessions in this test");
                    });
                }
                lifecycle.registerBeforePublication(new Subsystem() {
                    @Override public String name() { return "fixture-pre-publication"; }
                    @Override public void start() {
                        EVENTS.add("pre-publication.start");
                        try {
                            lifecycle.registerBeforePublication(this);
                        } catch (IllegalStateException expected) {
                            EVENTS.add("late-registration-rejected");
                        }
                    }
                    @Override public void stop() { EVENTS.add("pre-publication.stop"); }
                });
                PluginOperationsRecorder recorder = lifecycle.registerPluginProducts(ID, new PluginProducts() {
                    @Override public void resume() {
                        EVENTS.add("products.resume");
                        if (FAIL_RESUME.get()) {
                            throw new IllegalStateException("fixture resume failed");
                        }
                    }
                    @Override public void sealAndAwait() { EVENTS.add("products.seal"); }
                });
                assertThat(recorder).isNotNull();
                lifecycle.registerRestartReadiness(ID, () -> {
                    if (!READY.get()) {
                        EVENTS.add("readiness:false");
                    }
                    return READY.get();
                });
                EVENTS.add("assembly:sealed-late-n2n:" + rejectsLateN2n(host));
            });
        }

        private static boolean rejectsLateN2n(HostApi host) {
            try {
                host.n2n().registerInbound(32_001, () -> {
                    throw new UnsupportedOperationException("unused");
                });
                return false;
            } catch (IllegalStateException sealed) {
                return true;
            }
        }

        @Override
        public void start() {
            EVENTS.add("start");
            if (FAIL_START.get()) {
                throw new IllegalStateException("fixture start failed");
            }
        }

        @Override public void stop() { EVENTS.add("stop"); }
        @Override public void close() { EVENTS.add("close"); }
    }
}
