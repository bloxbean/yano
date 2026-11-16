package org.yanoproject.app;

import org.yanoproject.api.config.UpstreamPreset;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.db.IncompatibleChainStateException;
import org.yanoproject.api.plugin.PluginActivationException;
import org.yanoproject.app.archive.HistoryArchiveService;
import org.yanoproject.app.archive.ProjectionHistoryService;
import org.yanoproject.app.spi.RuntimeGlobalsContributor;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.config.RollbackRetentionGenesisValues;
import org.yanoproject.runtime.config.RollbackRetentionPlanner;
import org.yanoproject.runtime.plugins.HostPluginActivationException;
import org.yanoproject.runtime.plugins.PluginCatalogActivationException;
import org.yanoproject.runtime.plugins.PluginManager;
import org.yanoproject.runtime.plugins.PluginRuntimeEnvironment;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.yanoproject.runtime.utxo.index.UtxoContributorPlugins;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YanoProducerTest {

    @Test
    void pluginPolicyIsMappedWithoutDroppingAllowDenyOrReservedFlag() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Plugins.ENABLED, "true",
                YanoPropertyKeys.Plugins.LOGGING_ENABLED, "true",
                YanoPropertyKeys.Plugins.ALLOW_LIST, "com.example.a, com.example.b",
                YanoPropertyKeys.Plugins.DENY_LIST, "com.example.b",
                YanoPropertyKeys.Plugins.AUTO_REGISTER_ANNOTATED, "true"));

        var options = producer.pluginOptions();

        assertEquals(Set.of("com.example.a", "com.example.b"), options.allowList());
        assertEquals(Set.of("com.example.b"), options.denyList());
        assertEquals(true, options.autoRegisterAnnotated());
        assertEquals(true, options.config().get("plugins.logging.enabled"));
    }

    @Test
    void pluginBundleNamespaceIsForwardedWithoutLeakingValuesInDiagnostics() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                "yano.plugins.bundle.\"com.example.product-passport\".endpoint",
                "https://example.test",
                "yano.plugins.bundle.\"com.example.product-passport\".api-key",
                "top-secret-value",
                "yano.unrelated.secret", "must-not-be-forwarded",
                YanoPropertyKeys.Plugins.ALLOW_LIST, "com.example.product-passport"));

        var options = producer.pluginOptions();

        assertEquals("https://example.test", options.config().get(
                "yano.plugins.bundle.\"com.example.product-passport\".endpoint"));
        assertEquals("top-secret-value", options.config().get(
                "yano.plugins.bundle.\"com.example.product-passport\".api-key"));
        assertFalse(options.config().containsKey("yano.unrelated.secret"));
        assertEquals(false, options.config().get("plugins.logging.enabled"));
        assertTrue(options.toString().contains("configEntries=3"));
        assertFalse(options.toString().contains(
                "yano.plugins.bundle.\"com.example.product-passport\".api-key"));
        assertFalse(options.toString().contains("endpoint"));
        assertFalse(options.toString().contains("top-secret-value"));
    }

    @Test
    void yamlContributorListIsForwardedInTheShapeTheRuntimeParses() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new SmallRyeConfigBuilder().withSources(new YamlConfigSource("contributors", """
                yano:
                  utxo:
                    index-contributors:
                      - type: example.output-index
                        enabled: true
                        config:
                          label: demo
                """)).build();
        Map<String, Object> globals = new HashMap<>();
        producer.forwardUtxoContributorKeys(globals);
        var registration = UtxoContributorPlugins.registrations(globals).getFirst();
        assertEquals("example.output-index", registration.type());
        assertTrue(registration.enabled());
        assertEquals(Map.of("label", "demo"), registration.config());
    }

    @Test
    void shadowSyncMaxInFlightIsForwardedAndTheRemovedThreadsKeyIsNot() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_IN_FLIGHT, "6",
                "yano.validation.shadow-sync-threads", "3"));
        Map<String, Object> globals = new HashMap<>();
        producer.forwardValidationEngineKeys(globals);
        assertEquals(Map.of(YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_IN_FLIGHT, "6"), globals);
    }

    @Test
    void smallRyeReversibleEnvironmentBundleNameIsRetainedForExactCatalogResolution() {
        String environmentName =
                "YANO_PLUGINS_BUNDLE_HEX__636F6D2E6578616D706C652E70726F647563742D70617373706F7274__ENDPOINT_URL";
        Config environmentConfig = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(
                        Map.of(environmentName, "https://environment.example"), 300))
                .build();
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = environmentConfig;

        var options = producer.pluginOptions();

        assertEquals("https://environment.example", options.config().get(environmentName));
        assertTrue(options.config().keySet().stream()
                .anyMatch(key -> key.equals(environmentName)));
    }

    @Test
    void pluginStartupFailureIsRecognizedThroughWrapperCauses() {
        var pluginFailure = new PluginManager.PluginManagerException(
                PluginManager.FailurePhase.START,
                "com.example.plugin", "failed", null);
        var catalogFailure = new PluginCatalogActivationException(
                "catalog failed", new IllegalStateException("invalid catalog"));
        var activationFailure = new PluginActivationException(
                "provider product failed", "com.example.bundle", "signer-provider",
                "kms", "com.example.KmsProvider", new IllegalStateException("offline"));

        assertEquals(true, YanoProducer.isPluginStartupFailure(
                new RuntimeException("wrapper", pluginFailure)));
        assertTrue(YanoProducer.isPluginStartupFailure(
                new RuntimeException("wrapper", catalogFailure)));
        assertTrue(YanoProducer.isPluginStartupFailure(
                new RuntimeException("wrapper", activationFailure)));
        RuntimeException suppressedOnly = new RuntimeException("wrapper");
        suppressedOnly.addSuppressed(activationFailure);
        assertTrue(YanoProducer.isPluginStartupFailure(suppressedOnly));
        assertFalse(YanoProducer.isPluginStartupFailure(
                new RuntimeException("unrelated")));
    }

    @Test
    void startupBuildsPluginRuntimeEvenWhenAutoSyncIsDisabled() {
        var producer = new StartupProbeProducer(null);
        producer.autoSyncStart = false;

        producer.onStart(null);

        assertEquals(1, producer.ensureCalls);
    }

    @Test
    void startupRejectsWrappedIncompatibleChainStateWhenBootstrapIsDisabled() {
        var incompatible = new IncompatibleChainStateException(
                "Existing chainstate is not compatible; resync is required.");
        var producer = new StartupProbeProducer(
                new IllegalStateException("Failed to initialize ledger-state subsystem", incompatible));
        producer.autoSyncStart = false;
        producer.bootstrapEnabled = false;

        IncompatibleChainStateException thrown = assertThrows(
                IncompatibleChainStateException.class, () -> producer.onStart(null));

        assertSame(incompatible, thrown);
        assertEquals(1, producer.ensureCalls);
    }

    @Test
    void startupKeepsOrdinaryRuntimeFailureRecoverableWhenBootstrapIsDisabled() {
        var producer = new StartupProbeProducer(
                new IllegalStateException("Temporary runtime initialization failure"));
        producer.autoSyncStart = false;
        producer.bootstrapEnabled = false;

        producer.onStart(null);

        assertEquals(1, producer.ensureCalls);
    }

    @Test
    void startupReplacesRequiredPluginFailuresWithCauseFreeApplicationBoundary() {
        String sentinel = "STARTUP-SECRET-7d9b2f";
        RuntimeException catalogFailure = new PluginCatalogActivationException(
                "catalog failed " + sentinel,
                new IllegalStateException("catalog cause " + sentinel));
        RuntimeException activationFailure = new PluginActivationException(
                "provider product failed " + sentinel,
                "com.example.bundle." + sentinel, "effect-executor." + sentinel,
                "payments." + sentinel, "com.example." + sentinel,
                new IllegalStateException("credentials unavailable " + sentinel));
        RuntimeException managerFailure = new PluginManager.PluginManagerException(
                PluginManager.FailurePhase.INITIALIZATION,
                "com.example.plugin." + sentinel,
                "initialization failed " + sentinel,
                new IllegalStateException("lifecycle cause " + sentinel));

        Map<RuntimeException, String> failures = Map.of(
                catalogFailure, PluginCatalogActivationException.class.getName(),
                activationFailure, PluginActivationException.class.getName(),
                managerFailure, PluginManager.PluginManagerException.class.getName());
        for (Map.Entry<RuntimeException, String> entry : failures.entrySet()) {
            RuntimeException wrapper = new RuntimeException(
                    "application wrapper " + sentinel, entry.getKey());
            wrapper.addSuppressed(new IllegalStateException(
                    "wrapper cleanup " + sentinel));
            var producer = new StartupProbeProducer(wrapper);
            producer.autoSyncStart = false;

            PluginStartupException thrown = assertThrows(PluginStartupException.class,
                    () -> producer.onStart(null));

            assertEquals(entry.getValue(), thrown.sourceFailureType());
            assertNull(thrown.getCause());
            assertEquals(0, thrown.getSuppressed().length);
            assertTrue(thrown.getMessage().contains(entry.getValue()));
            assertFalse(renderStackTrace(thrown).contains(sentinel));
            assertEquals(1, producer.ensureCalls);
        }

        var lifecycleProducer = new StartupProbeProducer(managerFailure);
        PluginStartupException lifecycleFailure = assertThrows(
                PluginStartupException.class,
                () -> lifecycleProducer.onStart(null));
        assertEquals(Optional.of("INITIALIZATION"), lifecycleFailure.failurePhase());

        RuntimeException suppressedOnly = new RuntimeException(
                "suppressed-only wrapper " + sentinel);
        suppressedOnly.addSuppressed(activationFailure);
        var suppressedProducer = new StartupProbeProducer(suppressedOnly);
        PluginStartupException suppressedFailure = assertThrows(
                PluginStartupException.class,
                () -> suppressedProducer.onStart(null));
        assertEquals(PluginActivationException.class.getName(),
                suppressedFailure.sourceFailureType());
        assertNull(suppressedFailure.getCause());
        assertFalse(renderStackTrace(suppressedFailure).contains(sentinel));

        RuntimeException oversizedGraph = new RuntimeException(
                "oversized wrapper " + sentinel);
        for (int i = 0; i < 300; i++) {
            oversizedGraph.addSuppressed(new IllegalStateException(
                    "non-plugin failure " + i));
        }
        // This marker is deliberately beyond the inspection bound. Truncation
        // must fail safe instead of rendering the uninspected graph.
        oversizedGraph.addSuppressed(activationFailure);
        var oversizedProducer = new StartupProbeProducer(oversizedGraph);
        PluginStartupException oversizedFailure = assertThrows(
                PluginStartupException.class,
                () -> oversizedProducer.onStart(null));
        assertNull(oversizedFailure.getCause());
        assertFalse(renderStackTrace(oversizedFailure).contains(sentinel));

        RuntimeException hostileGraph = new RuntimeException(
                "hostile graph " + sentinel) {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("graph inspection " + sentinel);
            }
        };
        var hostileProducer = new StartupProbeProducer(hostileGraph);
        PluginStartupException hostileFailure = assertThrows(
                PluginStartupException.class,
                () -> hostileProducer.onStart(null));
        assertNull(hostileFailure.getCause());
        assertFalse(renderStackTrace(hostileFailure).contains(sentinel));

        OutOfMemoryError fatal = new OutOfMemoryError("fatal " + sentinel);
        fatal.addSuppressed(activationFailure);
        var fatalProducer = new StartupProbeProducer(fatal);
        assertSame(fatal, assertThrows(OutOfMemoryError.class,
                () -> fatalProducer.onStart(null)));

        OutOfMemoryError wrappedFatal = new OutOfMemoryError(
                "wrapped fatal " + sentinel);
        var wrappedFatalProducer = new StartupProbeProducer(
                new RuntimeException("ordinary wrapper " + sentinel, wrappedFatal));
        assertSame(wrappedFatal, assertThrows(OutOfMemoryError.class,
                () -> wrappedFatalProducer.onStart(null)));

        OutOfMemoryError fatalSibling = new OutOfMemoryError(
                "fatal sibling " + sentinel);
        RuntimeException siblingGraph = new RuntimeException(
                "sibling wrapper " + sentinel);
        // The plugin failure is deliberately visited first. Classification
        // must continue until the later process-fatal sibling is promoted.
        siblingGraph.addSuppressed(activationFailure);
        siblingGraph.addSuppressed(fatalSibling);
        var fatalSiblingProducer = new StartupProbeProducer(siblingGraph);
        assertSame(fatalSibling, assertThrows(OutOfMemoryError.class,
                () -> fatalSiblingProducer.onStart(null)));
    }

    @Test
    void contributedGlobalsAreMergedAndCollisionsFailStartup() {
        RuntimeGlobalsContributor first = () -> Map.of("yano.fixture.a", "1");
        RuntimeGlobalsContributor second = () -> Map.of("yano.fixture.b", 2);
        Map<String, Object> globals = new HashMap<>(Map.of(YanoPropertyKeys.Utxo.ENABLED, true));

        YanoProducer.putContributedGlobals(globals, List.of(first, second));
        assertEquals(Map.of(YanoPropertyKeys.Utxo.ENABLED, true,
                "yano.fixture.a", "1", "yano.fixture.b", 2), globals);

        Map<String, Object> duplicate = new HashMap<>();
        assertThrows(IllegalStateException.class,
                () -> YanoProducer.putContributedGlobals(duplicate, List.of(first, first)));
        assertTrue(duplicate.isEmpty());

        Map<String, Object> hostKey = new HashMap<>(Map.of(YanoPropertyKeys.Utxo.ENABLED, true));
        RuntimeGlobalsContributor overriding = () -> Map.of(YanoPropertyKeys.Utxo.ENABLED, false);
        assertThrows(IllegalStateException.class,
                () -> YanoProducer.putContributedGlobals(hostKey, List.of(overriding)));
        assertEquals(Map.of(YanoPropertyKeys.Utxo.ENABLED, true), hostKey);

        RuntimeGlobalsContributor absent = () -> null;
        assertTrue(assertThrows(IllegalStateException.class,
                () -> YanoProducer.putContributedGlobals(new HashMap<>(), List.of(absent))).getMessage()
                .contains("returned null"));
    }

    @Test
    void requiredPluginsReachTheRuntimeGlobalsButNotTheBundleConfiguration() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Plugins.ENABLED, "false",
                YanoPropertyKeys.Plugins.REQUIRED, " com.example.a, ,com.example.b "));
        Map<String, Object> globals = new HashMap<>();

        producer.putRequiredPlugins(globals);

        assertEquals(Set.of("com.example.a", "com.example.b"), PluginRuntimeEnvironment.requiredBundles(globals));
        assertFalse(producer.pluginOptions().config().containsKey(YanoPropertyKeys.Plugins.REQUIRED));

        producer.appConfig = new PresentConfig(Map.of());
        Map<String, Object> none = new HashMap<>();
        producer.putRequiredPlugins(none);
        assertTrue(none.isEmpty());
    }

    @Test
    void rollbackRetentionAdapterHonorsConfigPresenceAndPopulatesGlobals() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                RollbackRetentionPlanner.UTXO_ROLLBACK_WINDOW, "4320",
                RollbackRetentionPlanner.ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS, "10"));
        producer.rollbackRetentionEpochs = Optional.of(20);
        producer.utxoRollbackWindow = 4320;
        producer.accountStateEpochBlockDataRetentionLag = 5;
        producer.accountStateSnapshotRetentionEpochs = 10;
        producer.blockBodyPruneDepth = 2160;

        var settings = producer.resolveRollbackRetentionSettings(
                new RollbackRetentionGenesisValues(432_000, 0.05));

        assertEquals(4320, settings.utxoRollbackWindow());
        assertEquals(21, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(10, settings.accountStateSnapshotRetentionEpochs());
        assertEquals(864_000, settings.blockBodyPruneDepth());

        var globals = new java.util.HashMap<String, Object>();
        producer.putRollbackRetentionGlobals(globals, settings);

        assertEquals(4320, globals.get(RollbackRetentionPlanner.UTXO_ROLLBACK_WINDOW));
        assertEquals(21, globals.get(RollbackRetentionPlanner.ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG));
        assertEquals(10, globals.get(RollbackRetentionPlanner.ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS));
        assertEquals(864_000, globals.get(RollbackRetentionPlanner.BLOCK_BODY_PRUNE_DEPTH));
    }

    @Test
    void upstreamValidationOverrideIsHonoredWithLegacyRemoteConfig() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.VALIDATION_LEVEL, "header-signature",
                YanoPropertyKeys.Upstream.VALIDATION_BODY_LEVEL, "none"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals(UpstreamPreset.TRUSTED_SINGLE, upstream.getMode());
        assertEquals("header-signature", upstream.getValidation().normalizedLevel());
        assertEquals("none", upstream.getValidation().normalizedBodyLevel());
    }

    @Test
    void upstreamSelectionRollbackWindowDefaultsToGenesisDerivedSentinel() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "trusted-single"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals(0L, upstream.getSelection().getRollbackWindowSlots());
    }

    @Test
    void upstreamSelectionRollbackWindowOverrideIsHonored() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "trusted-single",
                YanoPropertyKeys.Upstream.SELECTION_ROLLBACK_WINDOW_SLOTS, "123"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals(123L, upstream.getSelection().getRollbackWindowSlots());
    }

    @Test
    void txDiffusionDisabledOverridesLegacyUpstreamForwarding() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "trusted-single",
                YanoPropertyKeys.Upstream.TX_FORWARDING, "active-selected",
                YanoPropertyKeys.Tx.DIFFUSION_MODE, "disabled"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals("disabled", upstream.getTx().normalizedForwarding());
    }

    @Test
    void relayTxDiffusionDefaultsToAllHotForwarding() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "p2p-relay",
                YanoPropertyKeys.Tx.DIFFUSION_ENABLED, "true"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals("all-hot", upstream.getTx().normalizedForwarding());
    }

    @Test
    void allHotDiffusionModeUsesAllHotForwarding() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "p2p-relay",
                YanoPropertyKeys.Tx.DIFFUSION_MODE, "all-hot"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals("all-hot", upstream.getTx().normalizedForwarding());
    }

    @Test
    void localSubmitOnlyModeKeepsLegacyTrustedHotForwardingTarget() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "p2p-relay",
                YanoPropertyKeys.Upstream.TX_FORWARDING, "all-hot-trusted",
                YanoPropertyKeys.Tx.DIFFUSION_MODE, "local-submit-only"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals("all-hot-trusted", upstream.getTx().normalizedForwarding());
    }

    @jakarta.enterprise.inject.Vetoed
    private static final class StartupProbeProducer extends YanoProducer {
        private final Throwable startupFailure;
        private final Yano assembledYano;
        private int ensureCalls;

        private StartupProbeProducer(Throwable startupFailure) {
            super(Thread.currentThread().getContextClassLoader());
            this.startupFailure = startupFailure;
            historyArchive = mock(HistoryArchiveService.class);
            projectionHistory = mock(ProjectionHistoryService.class);
            assembledYano = mock(Yano.class, RETURNS_DEEP_STUBS);
            when(assembledYano.lifecycle().getConfig()).thenReturn(YanoConfig.serverOnly(0));
        }

        @Override
        public Yano ensureYano() {
            ensureCalls++;
            if (startupFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (startupFailure instanceof Error error) {
                throw error;
            }
            return assembledYano;
        }
    }

    private record PresentConfig(Map<String, String> values) implements Config {
        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType)
                    .orElseThrow(() -> new IllegalArgumentException("Missing config property: " + propertyName));
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String value = values.get(propertyName);
            if (value == null) {
                return Optional.empty();
            }
            if (propertyType == String.class) {
                return Optional.of(propertyType.cast(value));
            }
            if (propertyType == Long.class) {
                return Optional.of(propertyType.cast(Long.valueOf(value)));
            }
            if (propertyType == Boolean.class) {
                return Optional.of(propertyType.cast(Boolean.valueOf(value)));
            }
            if (propertyType == String[].class) {
                return Optional.of(propertyType.cast(value.split(",")));
            }
            throw new UnsupportedOperationException("Unsupported config type: " + propertyType.getName());
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return java.util.List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new IllegalArgumentException("Cannot unwrap to " + type.getName());
        }
    }

    @Test
    void startupFailureNamesOnlyCatalogIdentityFromTheHostRegistry() throws Exception {
        String sentinel = "startup-secret-7d9b2f";
        RuntimeException hostFailure = hostActivationFailure(
                "provider failed", "org.example.ledger", "app-state-machine",
                new IllegalStateException("credentials unavailable " + sentinel));
        PluginStartupException named = assertThrows(PluginStartupException.class,
                () -> new StartupProbeProducer(new RuntimeException("wrapper", hostFailure)).onStart(null));
        assertEquals(Optional.of("org.example.ledger"), named.bundleId());
        assertEquals(Optional.of("app-state-machine"), named.contributionKind());
        assertTrue(named.getMessage().contains("bundle=org.example.ledger, contribution=app-state-machine"));
        assertFalse(renderStackTrace(named).contains(sentinel));

        // A plugin can throw the public API type with values that look like catalog identity (#179 review F1).
        RuntimeException pluginBuilt = new PluginActivationException(
                "plugin failed", "abc123.deadbeef", "private-password", "x", "org.example.P", null);
        RuntimeException pluginSubclass = new PluginActivationException(
                "plugin failed", "org.example.ledger", "app-state-machine", "x", "org.example.P", null) { };
        for (RuntimeException plugin : List.of(pluginBuilt, pluginSubclass)) {
            PluginStartupException unnamed = assertThrows(PluginStartupException.class,
                    () -> new StartupProbeProducer(plugin).onStart(null));
            assertEquals(Optional.empty(), unnamed.bundleId());
            assertEquals(Optional.empty(), unnamed.contributionKind());
            assertFalse(unnamed.getMessage().contains("abc123.deadbeef"));
            assertFalse(unnamed.getMessage().contains("private-password"));
        }

        // A lifecycle failure's plugin id comes from the plugin's own id(), so only the phase is shown.
        RuntimeException lifecycle = new PluginManager.PluginManagerException(
                PluginManager.FailurePhase.START, "abc123.deadbeef", "start failed " + sentinel, null);
        PluginStartupException started = assertThrows(PluginStartupException.class,
                () -> new StartupProbeProducer(lifecycle).onStart(null));
        assertTrue(started.getMessage().endsWith("phase=START)"));
        assertFalse(renderStackTrace(started).contains("abc123.deadbeef"));
        assertFalse(renderStackTrace(started).contains(sentinel));
    }

    /** Only the runtime's catalog registry can build this type; the test reaches its constructor reflectively. */
    private static RuntimeException hostActivationFailure(String message, String bundleId, String kind,
                                                          Throwable cause) throws Exception {
        Constructor<HostPluginActivationException> constructor =
                HostPluginActivationException.class.getDeclaredConstructor(String.class, String.class,
                        String.class, String.class, String.class, Throwable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(message, bundleId, kind, "selector", "org.example.Provider", cause);
    }

    @Test
    void optInDiagnosticsListABoundedSingleLineCauseChain() {
        Throwable hostile = new IllegalStateException("hostile") {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("graph inspection");
            }
        };
        Throwable failure = new RuntimeException("outer\nsecond\u2028third\u2029line",
                new PluginActivationException("x".repeat(600), hostile));

        var lines = YanoProducer.fullPluginDiagnostics(failure);

        assertEquals(3, lines.size());
        assertEquals("cause[0] java.lang.RuntimeException: outer second third line", lines.get(0));
        assertTrue(lines.get(1).endsWith("x".repeat(512) + "..."));
        assertTrue(lines.get(2).endsWith(": <diagnostic unavailable>"));
    }

    private static String renderStackTrace(Throwable failure) {
        StringWriter rendered = new StringWriter();
        failure.printStackTrace(new PrintWriter(rendered));
        return rendered.toString();
    }
}
