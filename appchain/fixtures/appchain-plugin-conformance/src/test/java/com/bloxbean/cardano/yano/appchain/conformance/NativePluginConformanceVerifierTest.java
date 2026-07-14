package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.catalog.BundleManifestParser;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativePluginConformanceVerifierTest {
    private static final String MANIFEST_PATH = "META-INF/yano/plugins/"
            + NativePluginConformanceVerifier.BUNDLE_ID + ".json";

    @Test
    void nodePluginDoesNotReachTypedProvidersOutsideTheCatalogRegistry()
            throws Exception {
        String verifierResource = NativePluginConformanceVerifier.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(verifierResource)) {
            assertThat(input).as("compiled native verifier").isNotNull();
            String constantPool = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String provider : List.of(
                    ConformanceStateMachineProvider.class.getName(),
                    ConformanceSequencerModeProvider.class.getName(),
                    ConformanceL1ObserverProvider.class.getName(),
                    ConformanceSignerProviderFactory.class.getName(),
                    ConformanceEffectExecutorFactory.class.getName(),
                    ConformanceFinalizedSinkFactory.class.getName(),
                    ConformanceDomainApiProvider.class.getName())) {
                assertThat(constantPool).doesNotContain(provider.replace('.', '/'));
            }
        }
    }

    @Test
    void serviceDescriptorsExposeEveryManifestedProviderExactlyOnce() {
        ClassLoader loader = getClass().getClassLoader();

        assertExactService(loader, NodePlugin.class, NativePluginConformanceVerifier.class);
        assertExactService(loader, AppStateMachineProvider.class,
                ConformanceStateMachineProvider.class);
        assertExactService(loader, SequencerModeProvider.class,
                ConformanceSequencerModeProvider.class);
        assertExactService(loader, L1ObserverProvider.class,
                ConformanceL1ObserverProvider.class);
        assertExactService(loader, SignerProviderFactory.class,
                ConformanceSignerProviderFactory.class);
        assertExactService(loader, AppEffectExecutorFactory.class,
                ConformanceEffectExecutorFactory.class);
        assertExactService(loader, FinalizedStreamSinkFactory.class,
                ConformanceFinalizedSinkFactory.class);
        assertExactService(loader, DomainApiProvider.class,
                ConformanceDomainApiProvider.class);
    }

    @Test
    void catalogRegistryResolvesAndConstructsAllSevenTypedProviders(
            @TempDir Path pluginDirectory) throws Exception {
        String fixtureJarProperty = System.getProperty(
                "yano.plugin.conformance.fixture.jar");
        assertThat(fixtureJarProperty).isNotBlank();
        Path fixtureJar = Path.of(fixtureJarProperty);
        Files.copy(fixtureJar, pluginDirectory.resolve(fixtureJar.getFileName()));
        PluginsOptions onlyFixture = new PluginsOptions(
                true, false, Set.of(NativePluginConformanceVerifier.BUNDLE_ID),
                Set.of(), Map.of());

        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                onlyFixture, PluginLoaderHandle.directory(
                        pluginDirectory, isolatedPluginParent(getClass().getClassLoader())))) {
            assertThat(environment.selectedBundleIds())
                    .containsExactly(NativePluginConformanceVerifier.BUNDLE_ID);
            assertCatalogProvider(environment, AppStateMachineProvider.class,
                    ConformanceStateMachineProvider.ID);
            assertCatalogProvider(environment, SequencerModeProvider.class,
                    ConformanceSequencerModeProvider.ID);
            assertCatalogProvider(environment, L1ObserverProvider.class,
                    ConformanceL1ObserverProvider.TYPE);
            assertCatalogProvider(environment, SignerProviderFactory.class,
                    ConformanceSignerProviderFactory.SCHEME);
            assertCatalogProvider(environment, AppEffectExecutorFactory.class,
                    ConformanceEffectExecutorFactory.SCHEME);
            assertCatalogProvider(environment, FinalizedStreamSinkFactory.class,
                    ConformanceFinalizedSinkFactory.SCHEME);
            assertCatalogProvider(environment, DomainApiProvider.class,
                    ConformanceDomainApiProvider.ID);
            exerciseCatalogFacades(environment);
        }
    }

    @Test
    void strictManifestDeclaresNodeVerifierAndAllSevenTypedKinds() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        try (InputStream input = loader.getResourceAsStream(MANIFEST_PATH)) {
            assertThat(input).as("fixture manifest").isNotNull();
            var manifest = new BundleManifestParser().parse(MANIFEST_PATH, input);
            assertThat(manifest.id()).isEqualTo(NativePluginConformanceVerifier.BUNDLE_ID);
            assertThat(manifest.version().toString())
                    .isEqualTo(NativePluginConformanceVerifier.VERSION);
            assertThat(manifest.dependencies()).isEmpty();
            assertThat(manifest.contributions())
                    .extracting(contribution -> contribution.kind())
                    .containsExactlyInAnyOrderElementsOf(Set.of(
                            ContributionKind.NODE_PLUGIN,
                            ContributionKind.APP_STATE_MACHINE,
                            ContributionKind.SEQUENCER_MODE,
                            ContributionKind.L1_OBSERVER,
                            ContributionKind.SIGNER_PROVIDER,
                            ContributionKind.EFFECT_EXECUTOR,
                            ContributionKind.FINALIZED_SINK,
                            ContributionKind.DOMAIN_API));
        }
    }

    @Test
    void nodePluginStartupRegistersOnlyItsOwnImmutableLifecycleReport() {
        RecordingContext context = new RecordingContext(getClass().getClassLoader());

        new NativePluginConformanceVerifier().init(context);

        assertThat(context.services).containsOnlyKeys(
                NativePluginConformanceVerifier.REPORT_SERVICE);
        assertThat(context.services.get(NativePluginConformanceVerifier.REPORT_SERVICE))
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> report = (Map<String, String>) context.services.get(
                NativePluginConformanceVerifier.REPORT_SERVICE);
        assertThat(report).containsExactly(
                Map.entry("node-plugin", NativePluginConformanceVerifier.BUNDLE_ID));
        assertThatThrownBy(() -> report.put("unexpected", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void exerciseCatalogFacades(PluginRuntimeEnvironment environment)
            throws Exception {
        AppStateMachine machine = environment.providers().require(
                AppStateMachineProvider.class, ConformanceStateMachineProvider.ID).create();
        assertThat(machine.id()).isEqualTo(ConformanceStateMachineProvider.ID);

        SequencerContext sequencerContext = new SequencerContext() {
            @Override public String chainId() { return "conformance-chain"; }
            @Override public String selfKeyHex() { return "00".repeat(32); }
            @Override public List<String> membersAt(long height) { return List.of(selfKeyHex()); }
            @Override public long currentL1Slot() { return 0; }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
        SequencerMode sequencer = environment.providers().require(
                SequencerModeProvider.class, ConformanceSequencerModeProvider.ID)
                .create(sequencerContext);
        sequencer.init(sequencerContext);
        assertThat(sequencer.id()).isEqualTo(ConformanceSequencerModeProvider.ID);
        assertThat(sequencer.shouldProposeNow(1)).isFalse();

        L1Observer observer = environment.providers().require(
                L1ObserverProvider.class, ConformanceL1ObserverProvider.TYPE)
                .create("audit", Map.of());
        assertThat(observer.observerId()).isEqualTo("audit");
        assertThat(observer.observe(0, new byte[32], null)).isEmpty();

        SignerProvider signer = environment.providers().require(
                SignerProviderFactory.class, ConformanceSignerProviderFactory.SCHEME)
                .create("key");
        assertThat(signer.publicKey()).hasSize(32);
        assertThat(signer.sign(new byte[]{1})).hasSize(64);

        List<AppEffectExecutor> executors = environment.providers().require(
                AppEffectExecutorFactory.class, ConformanceEffectExecutorFactory.SCHEME)
                .create("conformance-chain", Map.of());
        try {
            assertThat(executors).hasSize(1);
            AppEffectExecutor executor = executors.getFirst();
            assertThat(executor.id()).isEqualTo("conformance-effect-executor");
            EffectRecord effect = new EffectRecord(
                    EffectRecord.RECORD_VERSION, "conformance-chain", 1, 0,
                    "probe", new byte[0], "", FinalityGate.APP_FINAL,
                    ResultPolicy.NONE, 0, null);
            EffectExecution execution = executor.execute(new EffectExecutionContext() {
                @Override public String chainId() { return "conformance-chain"; }
                @Override public long tipHeight() { return 1; }
                @Override public long anchoredHeight() { return 0; }
                @Override public int attempt() { return 1; }
                @Override public Map<String, String> settings() { return Map.of(); }
            }, PendingEffect.of(effect));
            assertThat(execution).isInstanceOf(EffectExecution.Confirmed.class);
        } finally {
            executors.forEach(AppEffectExecutor::close);
        }

        List<FinalizedStreamSink> sinks = environment.providers().require(
                FinalizedStreamSinkFactory.class, ConformanceFinalizedSinkFactory.SCHEME)
                .create("conformance-chain", Map.of());
        try {
            assertThat(sinks).hasSize(1);
            assertThat(sinks.getFirst().id()).isEqualTo("conformance-finalized-sink");
            assertThat(sinks.getFirst().deliver(null)).isTrue();
        } finally {
            sinks.forEach(FinalizedStreamSink::close);
        }

        DomainQueryService queryService = new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of("conformance-chain"); }
            @Override public AppQueryResult query(String chainId, String path, byte[] params) {
                return new AppQueryResult("unsafe\"\n", ConformanceStateMachineProvider.ID,
                        0, new byte[32], params);
            }
        };
        try (DomainApi domainApi = environment.providers().require(
                DomainApiProvider.class, ConformanceDomainApiProvider.ID)
                .create(new DomainApiContext(Map.of(), queryService))) {
            assertThat(domainApi.routes()).hasSize(4);
            assertThat(domainApi.handle(new DomainApiRequest(
                    "status", DomainHttpMethod.GET, "status",
                    Map.of(), Map.of(), new byte[0])).status()).isEqualTo(200);
            String queryJson = new String(domainApi.handle(new DomainApiRequest(
                    "query", DomainHttpMethod.POST, "query/echo",
                    Map.of("path", "echo"), Map.of(), new byte[]{1, 2}))
                    .body(), StandardCharsets.UTF_8);
            assertThat(queryJson)
                    .contains("\"chainId\":\"unsafe\\\"\\n\"")
                    .doesNotContain("unsafe\"\n");
        }
    }

    private static <P> void assertExactService(ClassLoader loader,
                                               Class<P> service,
                                               Class<? extends P> implementation) {
        List<ServiceLoader.Provider<P>> matches = ServiceLoader.load(service, loader).stream()
                .filter(provider -> provider.type().equals(implementation))
                .toList();
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().get()).isExactlyInstanceOf(implementation);
    }

    private static <P> void assertCatalogProvider(PluginRuntimeEnvironment environment,
                                                  Class<P> service,
                                                  String selector) {
        assertThat(environment.providers().names(service)).containsExactly(selector);
        assertThat(environment.providers().find(service, selector)).isPresent();
    }

    private static ClassLoader isolatedPluginParent(ClassLoader delegate) {
        return new ClassLoader(delegate) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("com.bloxbean.cardano.yano.appchain.conformance.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }

            @Override
            public URL getResource(String name) {
                return isFixtureDiscoveryResource(name) ? null : super.getResource(name);
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return isFixtureDiscoveryResource(name)
                        ? Collections.emptyEnumeration() : super.getResources(name);
            }

            private boolean isFixtureDiscoveryResource(String name) {
                return name.startsWith("META-INF/services/") || MANIFEST_PATH.equals(name);
            }
        };
    }

    private static final class RecordingContext implements PluginContext {
        private final ClassLoader loader;
        private final Map<String, Object> services = new LinkedHashMap<>();

        private RecordingContext(ClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException("not used by conformance fixture");
        }

        @Override
        public Logger logger() {
            return LoggerFactory.getLogger(NativePluginConformanceVerifier.class);
        }

        @Override
        public Map<String, Object> config() {
            return Map.of();
        }

        @Override
        public ScheduledExecutorService scheduler() {
            throw new UnsupportedOperationException("not used by conformance fixture");
        }

        @Override
        public Optional<ClassLoader> pluginClassLoader() {
            return Optional.of(loader);
        }

        @Override
        public void registerService(String key, Object service) {
            Object previous = services.putIfAbsent(key, service);
            if (previous != null) {
                throw new IllegalStateException("duplicate service " + key);
            }
        }

        @Override
        public void registerStorageFilter(StorageFilter filter) {
            throw new UnsupportedOperationException("not used by conformance fixture");
        }

        @Override
        public <T> Optional<T> getService(String key, Class<T> type) {
            return Optional.ofNullable(services.get(key)).filter(type::isInstance).map(type::cast);
        }
    }
}
