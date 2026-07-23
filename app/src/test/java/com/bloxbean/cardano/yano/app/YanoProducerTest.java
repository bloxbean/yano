package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.UpstreamPreset;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import com.bloxbean.cardano.yano.runtime.config.RollbackRetentionGenesisValues;
import com.bloxbean.cardano.yano.runtime.config.RollbackRetentionPlanner;
import com.bloxbean.cardano.yano.runtime.plugins.PluginCatalogActivationException;
import com.bloxbean.cardano.yano.runtime.plugins.PluginManager;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
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

class YanoProducerTest {

    @Test
    void pluginPolicyIsMappedWithoutDroppingAllowDenyOrReservedFlag() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.pluginsEnabled = true;
        producer.pluginsLoggingEnabled = true;
        producer.appConfig = new PresentConfig(Map.of(
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
        producer.pluginsEnabled = true;
        producer.pluginsLoggingEnabled = false;
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
    void smallRyeReversibleEnvironmentBundleNameIsRetainedForExactCatalogResolution() {
        String environmentName =
                "YANO_PLUGINS_BUNDLE_HEX__636F6D2E6578616D706C652E70726F647563742D70617373706F7274__ENDPOINT_URL";
        Config environmentConfig = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(
                        Map.of(environmentName, "https://environment.example"), 300))
                .build();
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.pluginsEnabled = true;
        producer.pluginsLoggingEnabled = false;
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
    void effectSettingsAreForwardedForFlatAndIndexedAppChains() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                "yano.app-chain.effects.enabled", "true",
                "yano.app-chain.effects.metrics.types", "cardano.payment,webhook",
                "yano.app-chain.chains[0].chain-id", "payments",
                "yano.app-chain.chains[0].effects.enabled", "true",
                "yano.app-chain.chains[0].effects.executor.enabled", "true",
                "yano.app-chain.chains[0].unrelated.value", "ignored"));

        Map<String, Object> globals = new java.util.LinkedHashMap<>();
        producer.forwardAppChainDynamicKeys(globals);
        assertEquals("true", globals.get("yano.app-chain.effects.enabled"));
        assertEquals("cardano.payment,webhook",
                globals.get("yano.app-chain.effects.metrics.types"));

        var chain = producer.parseAppChainChains().getFirst();
        assertEquals("payments", chain.get("chain-id"));
        assertEquals("true", chain.get("effects.enabled"));
        assertEquals("true", chain.get("effects.executor.enabled"));
        assertFalse(chain.containsKey("unrelated.value"));
    }

    @Test
    void rollbackRetentionAdapterHonorsConfigPresenceAndPopulatesGlobals() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                RollbackRetentionPlanner.UTXO_ROLLBACK_WINDOW, "4320",
                RollbackRetentionPlanner.ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS, "10",
                RollbackRetentionPlanner.ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS, "123"));
        producer.rollbackRetentionEpochs = Optional.of(20);
        producer.utxoRollbackWindow = 4320;
        producer.accountStateEpochBlockDataRetentionLag = 5;
        producer.accountStateSnapshotRetentionEpochs = 10;
        producer.accountHistoryRollbackSafetySlots = Optional.of(123L);
        producer.blockBodyPruneDepth = 2160;

        var settings = producer.resolveRollbackRetentionSettings(
                new RollbackRetentionGenesisValues(432_000, 0.05));

        assertEquals(4320, settings.utxoRollbackWindow());
        assertEquals(21, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(10, settings.accountStateSnapshotRetentionEpochs());
        assertEquals(123L, settings.accountHistoryRollbackSafetySlots().orElseThrow());
        assertEquals(864_000, settings.blockBodyPruneDepth());

        var globals = new java.util.HashMap<String, Object>();
        producer.putRollbackRetentionGlobals(globals, settings);

        assertEquals(4320, globals.get(RollbackRetentionPlanner.UTXO_ROLLBACK_WINDOW));
        assertEquals(21, globals.get(RollbackRetentionPlanner.ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG));
        assertEquals(10, globals.get(RollbackRetentionPlanner.ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS));
        assertEquals(123L, globals.get(RollbackRetentionPlanner.ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS));
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
        private int ensureCalls;

        private StartupProbeProducer(Throwable startupFailure) {
            super(Thread.currentThread().getContextClassLoader());
            this.startupFailure = startupFailure;
        }

        @Override
        Yano ensureYano() {
            ensureCalls++;
            if (startupFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (startupFailure instanceof Error error) {
                throw error;
            }
            return null;
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

    private static String renderStackTrace(Throwable failure) {
        StringWriter rendered = new StringWriter();
        failure.printStackTrace(new PrintWriter(rendered));
        return rendered.toString();
    }
}
