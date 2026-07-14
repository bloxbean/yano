package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
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
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.runtime.plugins.PluginDiscoveryMode;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCatalogPackagingTest {

    private static final String GENERATED_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/yano-plugin-catalog/reflect-config.json";
    private static final String CODEC_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog-codec/reflect-config.json";

    private static final Set<String> STOCK_BUNDLES = Set.of(
            "com.bloxbean.cardano.yaci.plugins.logging",
            "com.bloxbean.cardano.yano.appchain.stdlib");
    private static final Set<String> OPTIONAL_BUNDLES = Set.of(
            "com.bloxbean.cardano.yano.appchain.kafka",
            "com.bloxbean.cardano.yano.appchain.effects.cardano",
            "com.bloxbean.cardano.yano.appchain.zk");
    private static final String CONFORMANCE_BUNDLE =
            "com.bloxbean.cardano.yano.fixture.plugin-conformance";
    private static final String EXPECT_FIRST_PARTY =
            "yano.test.include-first-party-plugin-bundles";
    private static final String EXPECT_CONFORMANCE =
            "yano.test.include-native-plugin-conformance-fixture";

    @Test
    void generatedIndexRetainsAndCorrelatesSelectedBuildTimeBundlesWithoutChangingTccl()
            throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        String firstFingerprint;
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), before)) {
            Set<String> bundleIds = environment.catalog().bundles().stream()
                    .map(bundle -> bundle.id())
                    .collect(Collectors.toSet());
            long optionalCount = bundleIds.stream().filter(OPTIONAL_BUNDLES::contains).count();
            assertTrue(optionalCount == 0 || optionalCount == OPTIONAL_BUNDLES.size(),
                    () -> "optional first-party bundle inclusion must be all-or-none: " + bundleIds);
            boolean optionalIncluded = optionalCount == OPTIONAL_BUNDLES.size();
            boolean conformanceIncluded = bundleIds.contains(CONFORMANCE_BUNDLE);
            assertEquals(Boolean.getBoolean(EXPECT_FIRST_PARTY), optionalIncluded,
                    "first-party bundle presence must match its build property");
            assertEquals(Boolean.getBoolean(EXPECT_CONFORMANCE), conformanceIncluded,
                    "conformance bundle presence must match its build property");
            Set<String> expectedBundles = new java.util.HashSet<>(STOCK_BUNDLES);
            if (optionalIncluded) expectedBundles.addAll(OPTIONAL_BUNDLES);
            if (conformanceIncluded) expectedBundles.add(CONFORMANCE_BUNDLE);
            assertEquals(expectedBundles, bundleIds,
                    "only selected stock, first-party, and conformance bundles may be packaged");
            assertTrue(environment.catalog().bundles().stream()
                    .allMatch(bundle -> bundle.selected()
                            && !bundle.legacy()
                            && bundle.digestMode() == PluginDigestMode.ARTIFACT_CLOSURE));
            assertTrue(environment.providers().names(AppStateMachineProvider.class)
                    .containsAll(Set.of("approvals", "balances", "doc-trail", "kv-registry")));
            assertEquals(optionalIncluded
                            ? Set.of("credential-registry", "zk-gate", "zk-membership") : Set.of(),
                    environment.providers().names(AppStateMachineProvider.class).stream()
                            .filter(Set.of("credential-registry", "zk-gate", "zk-membership")::contains)
                            .collect(Collectors.toSet()));
            Set<String> expectedSinks = new java.util.HashSet<>();
            if (optionalIncluded) expectedSinks.add("kafka");
            if (conformanceIncluded) expectedSinks.add("conformance-sink");
            assertEquals(expectedSinks, Set.copyOf(
                    environment.providers().names(FinalizedStreamSinkFactory.class)));
            Set<String> expectedExecutors = new java.util.HashSet<>();
            if (optionalIncluded) expectedExecutors.add("cardano");
            if (conformanceIncluded) expectedExecutors.add("conformance-effect");
            assertEquals(expectedExecutors, Set.copyOf(
                    environment.providers().names(AppEffectExecutorFactory.class)));
            assertEquals(conformanceIncluded ? Set.of("conformance-mode") : Set.of(), Set.copyOf(
                    environment.providers().names(SequencerModeProvider.class)));
            assertEquals(conformanceIncluded ? Set.of("conformance-observer") : Set.of(), Set.copyOf(
                    environment.providers().names(L1ObserverProvider.class)));
            assertEquals(conformanceIncluded ? Set.of("conformance-signer") : Set.of(), Set.copyOf(
                    environment.providers().names(SignerProviderFactory.class)));
            assertEquals(conformanceIncluded, environment.providers().find(
                    AppStateMachineProvider.class, "conformance-machine").isPresent());
            assertEquals(conformanceIncluded, environment.providers().find(
                    SequencerModeProvider.class, "conformance-mode").isPresent());
            assertEquals(conformanceIncluded, environment.providers().find(
                    L1ObserverProvider.class, "conformance-observer").isPresent());
            assertEquals(conformanceIncluded, environment.providers().find(
                    SignerProviderFactory.class, "conformance-signer").isPresent());
            assertEquals(conformanceIncluded, environment.providers().find(
                    AppEffectExecutorFactory.class, "conformance-effect").isPresent());
            assertEquals(conformanceIncluded, environment.providers().find(
                    FinalizedStreamSinkFactory.class, "conformance-sink").isPresent());
            if (conformanceIncluded) {
                exerciseConformanceProviderFacades(environment);
            }
            firstFingerprint = environment.catalog().fingerprint();
            assertTrue(firstFingerprint.matches("sha256:[0-9a-f]{64}"));
        }
        assertSame(before, thread.getContextClassLoader());

        try (PluginRuntimeEnvironment second = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), before)) {
            assertEquals(firstFingerprint, second.catalog().fingerprint());
        }
        assertSame(before, thread.getContextClassLoader());
    }

    private static void exerciseConformanceProviderFacades(
            PluginRuntimeEnvironment environment) throws Exception {
        AppStateMachine machine = environment.providers().require(
                AppStateMachineProvider.class, "conformance-machine").create();
        assertEquals("conformance-machine", machine.id());
        machine.init(null, null);
        machine.apply(null, null);

        SequencerContext sequencerContext = new SequencerContext() {
            @Override public String chainId() { return "conformance-chain"; }
            @Override public String selfKeyHex() { return "00".repeat(32); }
            @Override public List<String> membersAt(long height) { return List.of(selfKeyHex()); }
            @Override public long currentL1Slot() { return 0; }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
        SequencerMode sequencer = environment.providers().require(
                SequencerModeProvider.class, "conformance-mode").create(sequencerContext);
        sequencer.init(sequencerContext);
        assertEquals("conformance-mode", sequencer.id());
        assertFalse(sequencer.shouldProposeNow(1));
        assertEquals(SequencerMode.ProposalEligibility.REJECT,
                sequencer.checkProposal(new byte[32], 1));
        assertEquals(Map.of("mode", "conformance-mode"), sequencer.status());

        L1Observer observer = environment.providers().require(
                L1ObserverProvider.class, "conformance-observer")
                .create("conformance-observer-instance", Map.of());
        assertEquals("conformance-observer-instance", observer.observerId());
        assertTrue(observer.observe(0, new byte[32], null).isEmpty());
        assertTrue(observer.status().isEmpty());

        SignerProvider signer = environment.providers().require(
                SignerProviderFactory.class, "conformance-signer")
                .create("conformance-key");
        assertEquals(32, signer.publicKey().length);
        assertEquals(64, signer.sign(new byte[]{1}).length);
        assertEquals("00".repeat(32), signer.publicKeyHex());

        List<AppEffectExecutor> executors = environment.providers().require(
                AppEffectExecutorFactory.class, "conformance-effect")
                .create("conformance-chain", Map.of());
        try {
            assertEquals(1, executors.size());
            AppEffectExecutor executor = executors.getFirst();
            assertEquals("conformance-effect-executor", executor.id());
            assertFalse(executor.supports("probe"));
            EffectRecord record = new EffectRecord(
                    EffectRecord.RECORD_VERSION, "conformance-chain", 1, 0,
                    "probe", new byte[0], "", FinalityGate.APP_FINAL,
                    ResultPolicy.NONE, 0, null);
            EffectExecution execution = executor.execute(new EffectExecutionContext() {
                @Override public String chainId() { return "conformance-chain"; }
                @Override public long tipHeight() { return 1; }
                @Override public long anchoredHeight() { return 0; }
                @Override public int attempt() { return 1; }
                @Override public Map<String, String> settings() { return Map.of(); }
            }, PendingEffect.of(record));
            assertTrue(execution instanceof EffectExecution.Confirmed);
        } finally {
            executors.forEach(AppEffectExecutor::close);
        }

        List<FinalizedStreamSink> sinks = environment.providers().require(
                FinalizedStreamSinkFactory.class, "conformance-sink")
                .create("conformance-chain", Map.of());
        try {
            assertEquals(1, sinks.size());
            FinalizedStreamSink sink = sinks.getFirst();
            assertEquals("conformance-finalized-sink", sink.id());
            assertTrue(sink.deliver(null));
            assertEquals(null, sink.legacyCursorKey());
        } finally {
            sinks.forEach(FinalizedStreamSink::close);
        }
    }

    @Test
    void allowPolicySelectsWholeBundleAcrossEveryContributionKind() {
        PluginsOptions onlyStdlib = new PluginsOptions(true, false,
                Set.of("com.bloxbean.cardano.yano.appchain.stdlib"), Set.of(), Map.of());
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.packagedClasspath(
                onlyStdlib, Thread.currentThread().getContextClassLoader())) {
            assertEquals(Set.of("com.bloxbean.cardano.yano.appchain.stdlib"),
                    environment.selectedBundleIds());
            assertEquals(Set.of("approvals", "balances", "doc-trail", "kv-registry"),
                    Set.copyOf(environment.providers().names(AppStateMachineProvider.class)));
            assertTrue(environment.providers().names(FinalizedStreamSinkFactory.class).isEmpty());
            assertTrue(environment.providers().names(AppEffectExecutorFactory.class).isEmpty());
            assertFalse(environment.catalog().bundles().isEmpty());
        }
    }

    @Test
    void generatedNativeReflectionConfigCoversEveryCatalogProviderConstructor() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        String reflectionJson;
        try (InputStream input = loader.getResourceAsStream(GENERATED_REFLECTION_CONFIG)) {
            assertTrue(input != null, "generated native reflection config is missing");
            reflectionJson = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), loader)) {
            environment.catalog().bundles().stream()
                    .flatMap(bundle -> bundle.contributions().stream())
                    .forEach(contribution -> assertTrue(
                            reflectionJson.contains("\"name\":\"" + contribution.providerClass() + "\""),
                            () -> "native reflection config misses " + contribution.providerClass()));
        }
    }

    @Test
    void packagedApplicationRetainsCatalogCodecNativeReflectionConfig() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(CODEC_REFLECTION_CONFIG)) {
            assertTrue(input != null, "catalog codec native reflection config is missing");
            String reflectionJson = new String(
                    input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(reflectionJson.contains(
                    "\"name\": \"com.bloxbean.cardano.yano.catalog.PluginIndexCodec$RawIndex\""));
            assertTrue(reflectionJson.contains(
                    "\"name\": \"com.bloxbean.cardano.yano.catalog.BundleManifestParser$RawManifest\""));
        }
    }

    @Test
    void cdiDisposerClosesAnUnclaimedDirectoryLoaderIdempotently(@TempDir Path directory)
            throws IOException {
        Path jar = directory.resolve("orphan.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("orphan-marker.txt"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        var handle = com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        assertSame(getClass().getClassLoader(), handle.classLoader());
        assertSame(getClass().getClassLoader(), handle.hostClassLoader());
        assertTrue(handle.classLoader().getResource("orphan-marker.txt") == null);
        assertTrue(Files.exists(snapshotDirectory));

        var producer = new PluginClassLoaderProducer();
        producer.disposePluginClassLoader(handle);
        producer.disposePluginClassLoader(handle);

        assertTrue(handle.classLoader().getResource("orphan-marker.txt") == null);
        assertTrue(Files.notExists(snapshotDirectory));
    }

    @Test
    void cdiDisposerDoesNotCloseLoaderClaimedByRuntimeEnvironment(@TempDir Path directory)
            throws IOException {
        Path jar = directory.resolve("claimed.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("claimed-marker.txt"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        var handle = com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        var disabled = new PluginsOptions(false, false, Set.of(), Set.of(), Map.of());
        var environment = PluginRuntimeEnvironment.open(disabled, handle);
        var producer = new PluginClassLoaderProducer();
        try {
            producer.disposePluginClassLoader(handle);
            assertSame(getClass().getClassLoader(), handle.classLoader());
            assertTrue(handle.classLoader().getResource("claimed-marker.txt") == null);
            assertTrue(Files.exists(snapshotDirectory));
        } finally {
            environment.close();
        }

        assertTrue(handle.classLoader().getResource("claimed-marker.txt") == null);
        assertTrue(Files.notExists(snapshotDirectory));
    }

    @Test
    void genesisResolutionUsesTrustedHostAndNeverPluginTccl(@TempDir Path directory)
            throws Exception {
        String hostResource = "genesis/preprod/host-only-test.json";
        String pluginOnlyResource = "genesis/preprod/plugin-only-test.json";
        Path hostJar = directory.resolve("trusted-host.jar");
        Path pluginJar = directory.resolve("plugin-context.jar");
        writeResourceJar(hostJar, Map.of(
                hostResource, "trusted-host".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        writeResourceJar(pluginJar, Map.of(
                hostResource, "malicious-shadow".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                pluginOnlyResource, "plugin-only".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        String resolvedHost = null;
        try (URLClassLoader host = new URLClassLoader(
                     new URL[]{hostJar.toUri().toURL()}, null);
             URLClassLoader plugin = new URLClassLoader(
                     new URL[]{pluginJar.toUri().toURL()}, null)) {
            var handle = com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle
                    .classpath(host);
            try {
                thread.setContextClassLoader(plugin);
                YanoProducer producer = new YanoProducer(handle);
                resolvedHost = producer.resolveGenesisFile(
                        null, 1, "host-only-test.json");
                assertEquals("trusted-host", Files.readString(Path.of(resolvedHost)));
                assertNull(producer.resolveGenesisFile(
                        null, 1, "plugin-only-test.json"));
                assertSame(plugin, thread.getContextClassLoader());
            } finally {
                thread.setContextClassLoader(original);
                handle.close();
            }
        } finally {
            if (resolvedHost != null) {
                Files.deleteIfExists(Path.of(resolvedHost));
            }
        }
    }

    @Test
    void productionClassLoaderProducerSelectsStrictPackagedJvmDiscovery() {
        var producer = new PluginClassLoaderProducer();
        var handle = producer.createPluginClassLoader();
        try {
            assertEquals(PluginDiscoveryMode.PACKAGED_JVM, handle.discoveryMode());
        } finally {
            producer.disposePluginClassLoader(handle);
        }
    }

    private static void writeResourceJar(Path jar, Map<String, byte[]> entries)
            throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    @Test
    void pluginDirectoryCaptureFailureCannotExposeConfiguredPathOrCause() {
        String sentinel = "plugin-directory-secret-path";
        var producer = new PluginClassLoaderProducer();

        PluginStartupException failure = assertThrows(
                PluginStartupException.class,
                () -> producer.createPluginClassLoader(
                        sentinel + "\0invalid", true,
                        getClass().getClassLoader(), false));

        assertEquals(PluginStartupException.DIRECTORY_CAPTURE_FAILURE,
                failure.sourceFailureType());
        assertEquals(null, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        java.io.StringWriter rendered = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(rendered));
        assertFalse(rendered.toString().contains(sentinel));
    }

    @Test
    void pluginDirectoryBoundaryPromotesWrappedAndSuppressedProcessFatalErrors() {
        OutOfMemoryError wrappedFatal = new OutOfMemoryError("wrapped fatal");
        assertSame(wrappedFatal, assertThrows(OutOfMemoryError.class,
                () -> PluginClassLoaderProducer.throwSecretSafeDirectoryFailure(
                        new RuntimeException("wrapper", wrappedFatal))));

        OutOfMemoryError suppressedFatal = new OutOfMemoryError("suppressed fatal");
        RuntimeException wrapper = new RuntimeException("wrapper");
        wrapper.addSuppressed(suppressedFatal);
        assertSame(suppressedFatal, assertThrows(OutOfMemoryError.class,
                () -> PluginClassLoaderProducer.throwSecretSafeDirectoryFailure(wrapper)));
    }

    @Test
    void nativePluginDirectoryInspectionStopsAtItsEntryBound(@TempDir Path directory)
            throws IOException {
        for (int index = 0; index < 300; index++) {
            Files.write(directory.resolve("entry-" + index + ".txt"), new byte[]{1});
        }

        PluginClassLoaderProducer.NativeDirectoryInspection inspection =
                PluginClassLoaderProducer.inspectNativeDirectory(directory);

        assertEquals(256, inspection.inspectedEntries());
        assertEquals(0, inspection.jarCount());
        assertTrue(inspection.truncated());
    }
}
