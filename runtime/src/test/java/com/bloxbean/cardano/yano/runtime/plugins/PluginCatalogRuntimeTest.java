package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSelectionStatus;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.catalog.BundleContribution;
import com.bloxbean.cardano.yano.catalog.BundleDependency;
import com.bloxbean.cardano.yano.catalog.BundleManifest;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.catalog.IndexedBundle;
import com.bloxbean.cardano.yano.catalog.IndexedLegacyProvider;
import com.bloxbean.cardano.yano.catalog.PluginArtifactScanner;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.catalog.PluginIndexCodec;
import com.bloxbean.cardano.yano.catalog.SemVersion;
import com.bloxbean.cardano.yano.catalog.YanoApiRange;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationCustomizer;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationPipeline;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginCatalogRuntimeTest {
    private static final String SECRET_CALLBACK_MESSAGE =
            "plugin-secret-must-not-enter-catalog-diagnostic";
    private static final String SINK_SERVICE = "META-INF/services/"
            + FinalizedStreamSinkFactory.class.getName();
    private static final String NODE_PLUGIN_SERVICE = "META-INF/services/" + NodePlugin.class.getName();

    @TempDir
    Path tempDirectory;

    @Test
    void selectorValidationFailureClosesConstructedProvider() {
        ClosingSinkFactory provider = new ClosingSinkFactory("actual", null);
        CatalogPluginProviderRegistry.Entry entry = new CatalogPluginProviderRegistry.Entry(
                "com.example.bundle", ContributionKind.FINALIZED_SINK, "declared",
                provider.getClass().getName(), null, () -> provider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of("com.example.bundle"), List.of());

        assertThatThrownBy(() -> registry.find(FinalizedStreamSinkFactory.class, "declared"))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("com.example.bundle")
                .hasMessageContaining("finalized-sink/declared")
                .hasRootCauseMessage("Provider '" + provider.getClass().getName()
                        + "' selector mismatch: manifest='declared', provider='actual'")
                .satisfies(failure -> {
                    PluginActivationException activation = (PluginActivationException) failure;
                    assertThat(activation.bundleId()).isEqualTo("com.example.bundle");
                    assertThat(activation.contributionKind()).isEqualTo("finalized-sink");
                    assertThat(activation.selector()).isEqualTo("declared");
                    assertThat(activation.providerClass()).isEqualTo(provider.getClass().getName());
                });

        assertThat(provider.closeCalls).hasValue(1);
        registry.close();
        assertThat(provider.closeCalls).hasValue(1);
    }

    @Test
    void environmentRegistryClosesNodePluginEvenWithoutLifecycleManager() {
        ClosingNodePlugin plugin = new ClosingNodePlugin();
        CatalogPluginProviderRegistry.Entry entry = new CatalogPluginProviderRegistry.Entry(
                plugin.id(), ContributionKind.NODE_PLUGIN, plugin.id(),
                plugin.getClass().getName(), null, () -> plugin);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of(plugin.id()), List.of(plugin));

        assertThat(registry.nodePluginInstances()).containsExactly(plugin);
        assertThatThrownBy(() -> registry.find(NodePlugin.class, plugin.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owned exclusively by PluginManager");
        assertThatThrownBy(() -> registry.names(NodePlugin.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owned exclusively by PluginManager");
        registry.close();
        registry.close();

        assertThat(plugin.closeCalls).hasValue(1);
    }

    @Test
    void providerCloseOrderIsReverseBundleOrderAcrossProviderKinds() {
        List<String> closes = new ArrayList<>();
        ClosingSinkFactory dependency = new ClosingSinkFactory("dependency", closes);
        ClosingSinkFactory dependent = new ClosingSinkFactory("dependent", closes);
        List<CatalogPluginProviderRegistry.Entry> entries = List.of(
                entry("com.example.dependency", dependency),
                entry("com.example.dependent", dependent));
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                entries, List.of("com.example.dependency", "com.example.dependent"), List.of());

        registry.find(FinalizedStreamSinkFactory.class, "dependency");
        registry.find(FinalizedStreamSinkFactory.class, "dependent");
        registry.close();

        assertThat(closes).containsExactly("dependent", "dependency");
    }

    @Test
    void providerRegistryCloseWrapsNonProcessFatalErrorAndContinuesCleanup() {
        AtomicInteger fatalCloses = new AtomicInteger();
        AtomicInteger healthyCloses = new AtomicInteger();
        FatalPluginError fatal = new FatalPluginError("fatal provider close");
        CloseProbeSinkFactory healthy = new CloseProbeSinkFactory(
                "healthy", healthyCloses, null);
        CloseProbeSinkFactory throwing = new CloseProbeSinkFactory(
                "throwing", fatalCloses, fatal);
        List<CatalogPluginProviderRegistry.Entry> entries = List.of(
                sinkEntry("com.example.healthy", healthy),
                sinkEntry("com.example.throwing", throwing));
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                entries, List.of("com.example.healthy", "com.example.throwing"), List.of());
        registry.find(FinalizedStreamSinkFactory.class, "healthy");
        registry.find(FinalizedStreamSinkFactory.class, "throwing");

        assertThatThrownBy(registry::close)
                .isInstanceOf(PluginActivationException.class)
                .hasMessage("Plugin provider close failed")
                .hasMessageNotContaining("fatal provider close")
                .hasCause(fatal);
        assertThat(fatalCloses).hasValue(1);
        assertThat(healthyCloses).hasValue(1);

        registry.close();
        assertThat(fatalCloses).hasValue(1);
        assertThat(healthyCloses).hasValue(1);
    }

    @Test
    void providerRegistryClosePromotesVmFatalAfterEarlierAssertionError() {
        AtomicInteger assertionCloses = new AtomicInteger();
        AtomicInteger fatalCloses = new AtomicInteger();
        AssertionError assertion = new AssertionError("assertion provider close");
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal provider close");
        CloseProbeSinkFactory fatalProvider = new CloseProbeSinkFactory(
                "fatal", fatalCloses, fatal);
        CloseProbeSinkFactory assertionProvider = new CloseProbeSinkFactory(
                "assertion", assertionCloses, assertion);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(
                        sinkEntry("com.example.fatal", fatalProvider),
                        sinkEntry("com.example.assertion", assertionProvider)),
                List.of("com.example.fatal", "com.example.assertion"), List.of());
        registry.find(FinalizedStreamSinkFactory.class, "fatal");
        registry.find(FinalizedStreamSinkFactory.class, "assertion");

        assertThatThrownBy(registry::close).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).contains(assertion);
        assertThat(assertionCloses).hasValue(1);
        assertThat(fatalCloses).hasValue(1);
        registry.close();
    }

    @Test
    void providerIdentityCannotAlsoBecomeFactoryProductOwner() {
        SelfReturningSinkFactory provider = new SelfReturningSinkFactory();
        CatalogPluginProviderRegistry.Entry entry = new CatalogPluginProviderRegistry.Entry(
                "com.example.self-returning", ContributionKind.FINALIZED_SINK,
                provider.scheme(), provider.getClass().getName(), null, () -> provider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of("com.example.self-returning"), List.of());

        FinalizedStreamSinkFactory exposed = registry.find(
                FinalizedStreamSinkFactory.class, provider.scheme()).orElseThrow();

        assertThatThrownBy(() -> exposed.create("chain-a", Map.of()))
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage(
                        "Factory returned a product instance already owned by a previous create invocation");
        assertThat(provider.closeCalls).hasValue(0);

        registry.close();
        registry.close();
        assertThat(provider.closeCalls).hasValue(1);
    }

    @Test
    void eagerLegacyProviderIdentityCannotBecomeAnotherFactoryProductOwner() {
        SelfReturningSinkFactory eagerProvider = new SelfReturningSinkFactory();
        FixedProductSinkFactory producer = new FixedProductSinkFactory(eagerProvider);
        CatalogPluginProviderRegistry.Entry producerEntry =
                new CatalogPluginProviderRegistry.Entry(
                        "com.example.producer", ContributionKind.FINALIZED_SINK,
                        producer.scheme(), producer.getClass().getName(), null,
                        () -> producer);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(producerEntry), List.of("com.example.producer"),
                List.of(eagerProvider));

        FinalizedStreamSinkFactory exposed = registry.find(
                FinalizedStreamSinkFactory.class, producer.scheme()).orElseThrow();

        assertThatThrownBy(() -> exposed.create("chain-a", Map.of()))
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage(
                        "Factory returned a product instance already owned by a previous create invocation");
        assertThat(eagerProvider.closeCalls).hasValue(0);

        registry.close();
        registry.close();
        assertThat(eagerProvider.closeCalls).hasValue(1);
    }

    @Test
    void terminalLegacyProviderCloseCannotReenterEnvironmentTeardown() throws Exception {
        ReentrantLegacySinkFactory.reset();
        Path jar = tempDirectory.resolve("reentrant-provider-close.jar");
        writeJar(jar, Map.of(SINK_SERVICE,
                ReentrantLegacySinkFactory.class.getName().getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader);
            ReentrantLegacySinkFactory.environment.set(environment);

            environment.close();
            environment.close();
        } finally {
            ReentrantLegacySinkFactory.environment.set(null);
        }

        assertThat(ReentrantLegacySinkFactory.closeCalls).hasValue(1);
        assertThat(ReentrantLegacySinkFactory.reentryFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot close the plugin environment")
                .hasMessageContaining("plugin contribution callback");
        ReentrantLegacySinkFactory.reset();
    }

    @Test
    void nodePluginLifecycleCannotReenterOwningEnvironmentTeardown() throws Exception {
        ReentrantCatalogNodePlugin.reset();
        Path jar = tempDirectory.resolve("reentrant-node-plugin.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                ReentrantCatalogNodePlugin.class.getName()
                        .getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             var scheduler = Executors.newSingleThreadScheduledExecutor();
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                     PluginsOptions.defaults(), loader)) {
            ReentrantCatalogNodePlugin.environment.set(environment);
            PluginManager manager = environment.createNodePluginManager(
                    new NoopEventBus(), scheduler);

            manager.discoverAndInit();
            assertThat(environment.selectedBundleIds())
                    .contains(ReentrantCatalogNodePlugin.ID);
            assertLifecycleReentryRejected(ReentrantCatalogNodePlugin.initFailure.get());

            manager.close();
            assertThat(environment.selectedBundleIds())
                    .contains(ReentrantCatalogNodePlugin.ID);
            assertLifecycleReentryRejected(ReentrantCatalogNodePlugin.closeFailure.get());
        } finally {
            ReentrantCatalogNodePlugin.environment.set(null);
        }

        assertThat(ReentrantCatalogNodePlugin.closeCalls).hasValue(1);
        ReentrantCatalogNodePlugin.reset();
    }

    private static void assertLifecycleReentryRejected(Throwable failure) {
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot close the plugin environment")
                .hasMessageContaining("plugin contribution callback");
    }

    @Test
    void pluginLoaderCleanupAggregationPromotesFatalAndAvoidsSelfSuppression() {
        IOException ordinary = new IOException("ordinary loader close");
        FatalPluginError fatal = new FatalPluginError("fatal snapshot cleanup");

        Throwable winner = PluginLoaderHandle.recordCleanupFailure(ordinary, fatal);

        assertThat(winner).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(ordinary);
        assertThat(PluginLoaderHandle.recordCleanupFailure(fatal, fatal)).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(ordinary);
    }

    @Test
    void pluginLoaderHandleCanBelongToOnlyOneRuntimeEnvironment() {
        PluginsOptions disabled = new PluginsOptions(
                false, false, Set.of(), Set.of(), Map.of());
        PluginLoaderHandle handle = PluginLoaderHandle.classpath(
                getClass().getClassLoader());
        try (PluginRuntimeEnvironment ignored =
                     PluginRuntimeEnvironment.open(disabled, handle)) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(disabled, handle))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "already belongs to a runtime environment");
        }
    }

    @Test
    void claimedDirectoryLoaderCanOnlyBeClosedByItsRuntimeEnvironment() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("claimed-loader"));
        Path jar = directory.resolve("claimed.jar");
        writeJar(jar, Map.of("claimed-marker.txt", new byte[]{1}));
        ClassLoader parent = getClass().getClassLoader();
        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                directory, parent);
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        PluginsOptions disabled = new PluginsOptions(
                false, false, Set.of(), Set.of(), Map.of());
        PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(disabled, handle);

        try {
            assertThatThrownBy(handle::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("belongs to a runtime environment");
            assertThat(handle.classLoader()).isSameAs(parent);
            assertThat(handle.classLoader().getResource("claimed-marker.txt")).isNull();
            assertThat(snapshotDirectory).exists();
        } finally {
            environment.close();
        }

        assertThat(handle.classLoader().getResource("claimed-marker.txt")).isNull();
        assertThat(snapshotDirectory).doesNotExist();
        handle.close();
    }

    @Test
    void concurrentEnvironmentCloseWaitsForTheActiveCloseTransition() throws Exception {
        PluginsOptions disabled = new PluginsOptions(
                false, false, Set.of(), Set.of(), Map.of());
        PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                disabled, PluginLoaderHandle.classpath(getClass().getClassLoader()));
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        environment.providers().registerContributionCleanup(cleanup);
        CountDownLatch firstReturned = new CountDownLatch(1);
        CountDownLatch secondReturned = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = Thread.ofPlatform().name("plugin-environment-close-1").start(() -> {
            try {
                environment.close();
            } catch (Throwable failure) {
                firstFailure.set(failure);
            } finally {
                firstReturned.countDown();
            }
        });
        Thread second = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try {
                    environment.catalog();
                } catch (IllegalStateException closing) {
                    break;
                }
                Thread.onSpinWait();
            }
            assertThatThrownBy(environment::catalog)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");

            second = Thread.ofPlatform().name("plugin-environment-close-2").start(() -> {
                try {
                    environment.close();
                } catch (Throwable failure) {
                    secondFailure.set(failure);
                } finally {
                    secondReturned.countDown();
                }
            });
            assertThat(secondReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            cleanup.complete(null);
            first.join(2_000);
            if (second != null) {
                second.join(2_000);
            }
        }

        assertThat(firstReturned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(secondReturned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(first.isAlive()).isFalse();
        assertThat(second).isNotNull();
        assertThat(second.isAlive()).isFalse();
        assertThat(firstFailure.get()).isNull();
        assertThat(secondFailure.get()).isNull();
    }

    @Test
    void concurrentAndLaterEnvironmentCloseCallersObserveTheSameTerminalFailure()
            throws Exception {
        AssertionError terminalFailure = new AssertionError("terminal provider close");
        MemoizedCloseFailureSinkFactory.reset(terminalFailure);
        Path jar = tempDirectory.resolve("memoized-close-failure.jar");
        writeJar(jar, Map.of(SINK_SERVICE,
                MemoizedCloseFailureSinkFactory.class.getName()
                        .getBytes(StandardCharsets.UTF_8)));

        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch firstReturned = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondReturned = new CountDownLatch(1);
        Thread first = null;
        Thread second = null;
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader);
            environment.providers().registerContributionCleanup(cleanup);

            first = Thread.ofPlatform().name("plugin-failing-close-1").start(() -> {
                try {
                    environment.close();
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                } finally {
                    firstReturned.countDown();
                }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < deadline) {
                    try {
                        environment.catalog();
                    } catch (IllegalStateException closing) {
                        break;
                    }
                    Thread.onSpinWait();
                }
                assertThatThrownBy(environment::catalog)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed");

                second = Thread.ofPlatform().name("plugin-failing-close-2").start(() -> {
                    secondStarted.countDown();
                    try {
                        environment.close();
                    } catch (Throwable failure) {
                        secondFailure.set(failure);
                    } finally {
                        secondReturned.countDown();
                    }
                });
                assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
                long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (second.getState() != Thread.State.WAITING
                        && System.nanoTime() < waitDeadline) {
                    Thread.onSpinWait();
                }
                assertThat(second.getState()).isEqualTo(Thread.State.WAITING);
                assertThat(secondReturned.getCount()).isOne();
            } finally {
                cleanup.complete(null);
                first.join(2_000);
                if (second != null) {
                    second.join(2_000);
                }
            }

            assertThat(firstReturned.getCount()).isZero();
            assertThat(secondReturned.getCount()).isZero();
            assertThat(firstFailure.get())
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessage("Plugin provider close failed")
                    .hasMessageNotContaining("terminal provider close")
                    .hasCause(terminalFailure);
            assertThat(secondFailure.get()).isSameAs(firstFailure.get());
            assertThat(MemoizedCloseFailureSinkFactory.closeCalls).hasValue(1);
            assertThat(catchThrowable(environment::close)).isSameAs(firstFailure.get());
        } finally {
            cleanup.complete(null);
            if (first != null) {
                first.join(2_000);
            }
            if (second != null) {
                second.join(2_000);
            }
            MemoizedCloseFailureSinkFactory.reset(null);
        }
    }

    @Test
    void environmentCannotOutliveOrDuplicateItsNodePluginManager() {
        PluginsOptions disabled = new PluginsOptions(
                false, false, Set.of(), Set.of(), Map.of());
        PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                disabled, PluginLoaderHandle.classpath(getClass().getClassLoader()));
        PluginManager manager = null;
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            manager = environment.createNodePluginManager(
                    new NoopEventBus(), scheduler);

            assertThatThrownBy(() -> environment.createNodePluginManager(
                    new NoopEventBus(), scheduler))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already owns a NodePlugin manager");
            assertThatThrownBy(environment::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Close the NodePlugin manager");

            manager.close();
            environment.close();
        } finally {
            if (manager != null) {
                manager.close();
            }
            environment.close();
        }
    }

    @Test
    void catalogFailureClosesEagerLegacyProvider() throws Exception {
        LegacyClosingPlugin.closeCalls.set(0);
        Path jar = tempDirectory.resolve("legacy.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                LegacyClosingPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));
        PluginsOptions options = new PluginsOptions(
                true, false, Set.of("com.example.missing"), Set.of(), Map.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(options, loader, List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Allow-listed plugin bundles were not discovered");
        }

        assertThat(LegacyClosingPlugin.closeCalls).hasValue(1);
    }

    @Test
    void legacyFailureCleanupPromotesProcessFatalAndContinuesInReverseOrder() throws Exception {
        LegacyCleanupProbe.reset();
        TestVirtualMachineError firstFatal =
                new TestVirtualMachineError("first fatal cleanup");
        FatalPluginError secondFatal = new FatalPluginError("second fatal cleanup");
        LegacyCleanupProbe.bCloseFailure = firstFatal;
        LegacyCleanupProbe.aCloseFailure = secondFatal;
        Path jar = tempDirectory.resolve("legacy-fatal-cleanup.jar");
        String providers = LegacyCleanupAPlugin.class.getName() + "\n"
                + LegacyCleanupBPlugin.class.getName() + "\n";
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                providers.getBytes(StandardCharsets.UTF_8)));
        PluginsOptions options = new PluginsOptions(
                true, false, Set.of("com.example.missing"), Set.of(), Map.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(options, loader, List.of()))
                    .isSameAs(firstFatal);

            assertThat(LegacyCleanupProbe.closeOrder).containsExactly("b", "a");
            assertThat(firstFatal.getSuppressed())
                    .anySatisfy(failure -> assertThat(failure)
                            .isInstanceOf(
                                    PluginCatalogBuilder.LegacyProviderCleanupError.class)
                            .hasCause(secondFatal));
            assertThat(firstFatal.getSuppressed())
                    .anyMatch(failure -> failure instanceof IllegalStateException
                            && failure.getMessage().contains(
                            "Allow-listed plugin bundles were not discovered"));
        } finally {
            LegacyCleanupProbe.reset();
        }
    }

    @Test
    void legacyCleanupRetainsEveryNonProcessErrorAfterSafeWinnerConversion() throws Exception {
        LegacyCleanupProbe.reset();
        FatalPluginError firstInReverseOrder =
                new FatalPluginError("first non-process cleanup error");
        FatalPluginError secondInReverseOrder =
                new FatalPluginError("second non-process cleanup error");
        LegacyCleanupProbe.bCloseFailure = firstInReverseOrder;
        LegacyCleanupProbe.aCloseFailure = secondInReverseOrder;
        Path jar = tempDirectory.resolve("legacy-multiple-error-cleanup.jar");
        String providers = LegacyCleanupAPlugin.class.getName() + "\n"
                + LegacyCleanupBPlugin.class.getName() + "\n";
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                providers.getBytes(StandardCharsets.UTF_8)));
        PluginsOptions options = new PluginsOptions(
                true, false, Set.of("com.example.missing"), Set.of(), Map.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    options, loader, List.of()))
                    .isInstanceOf(
                            PluginCatalogBuilder.LegacyProviderCleanupException.class)
                    .hasCause(firstInReverseOrder)
                    .satisfies(failure -> {
                        assertThat(failure.getSuppressed())
                                .anySatisfy(context -> assertThat(context)
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining(
                                                "Allow-listed plugin bundles were not discovered"));
                        assertThat(failure.getSuppressed())
                                .anySatisfy(context -> assertThat(context)
                                        .isInstanceOf(
                                                PluginCatalogBuilder.LegacyProviderCleanupError.class)
                                        .hasCause(secondInReverseOrder));
                    });
            assertThat(LegacyCleanupProbe.closeOrder).containsExactly("b", "a");
        } finally {
            LegacyCleanupProbe.reset();
        }
    }

    @Test
    void legacyFailureCleanupRetainsSafeWrapperForSharedMetadataError()
            throws Exception {
        LegacyCleanupProbe.reset();
        FatalPluginError sharedFatal = new FatalPluginError("shared metadata and cleanup fatal");
        LegacyCleanupProbe.bIdFailure = sharedFatal;
        LegacyCleanupProbe.bCloseFailure = sharedFatal;
        Path jar = tempDirectory.resolve("legacy-self-suppression.jar");
        String providers = LegacyCleanupAPlugin.class.getName() + "\n"
                + LegacyCleanupBPlugin.class.getName() + "\n";
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                providers.getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader, List.of()))
                    .isInstanceOf(PluginCatalogBuilder.LegacyProviderMetadataException.class)
                    .hasMessageContaining("provider selector")
                    .hasMessageNotContaining("shared metadata and cleanup fatal")
                    .hasCause(sharedFatal);

            assertThat(LegacyCleanupProbe.closeOrder).containsExactly("b", "a");
            assertThat(sharedFatal.getSuppressed()).isEmpty();
        } finally {
            LegacyCleanupProbe.reset();
        }
    }

    @Test
    void everyLegacyBuildCallbackGetsAnIndependentPluginTcclScope() throws Exception {
        LegacyTcclPlugin.reset();
        Path jar = tempDirectory.resolve("legacy-tccl.jar");
        String providers = LegacyTcclCompanionPlugin.class.getName() + "\n"
                + LegacyTcclPlugin.class.getName() + "\n";
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                providers.getBytes(StandardCharsets.UTF_8)));

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new ClassLoader(original) { };
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            LegacyTcclPlugin.expectedLoader.set(loader);
            LegacyTcclPlugin.rogueLoader.set(new ClassLoader(original) { });
            Thread.currentThread().setContextClassLoader(caller);

            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader, List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires unavailable selected bundle "
                            + "'com.example.absent'");

            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
            assertThat(LegacyTcclPlugin.closeCalls).hasValue(2);
            assertThat(LegacyTcclPlugin.callbackCalls.get()).isGreaterThanOrEqualTo(16);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            LegacyTcclPlugin.reset();
        }
    }

    @Test
    void legacySelectorAndMetadataFailuresKeepPluginMessagesOutOfActivationDiagnostics()
            throws Exception {
        assertSecretSafeLegacyFailure(
                SecretSelectorLegacySinkFactory.class,
                SINK_SERVICE,
                "provider selector",
                "secret-selector.jar");
        assertSecretSafeLegacyFailure(
                SecretVersionLegacyPlugin.class,
                NODE_PLUGIN_SERVICE,
                "NodePlugin.version()",
                "secret-version.jar");
        assertSecretSafeLegacyFailure(
                SecretConstructorLegacySinkFactory.class,
                SINK_SERVICE,
                "construction",
                "secret-constructor.jar");
    }

    @Test
    void statefulLegacySelectorAssertionIsSecretSafeCachedAndClosedExactlyOnce()
            throws Exception {
        StatefulSecretLegacySinkFactory.reset();
        Path jar = tempDirectory.resolve("stateful-secret-selector.jar");
        writeJar(jar, Map.of(SINK_SERVICE,
                StatefulSecretLegacySinkFactory.class.getName()
                        .getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                     PluginsOptions.defaults(), loader)) {
            Throwable first = catchThrowable(() -> environment.providers().find(
                    FinalizedStreamSinkFactory.class,
                    StatefulSecretLegacySinkFactory.SCHEME));
            Throwable second = catchThrowable(() -> environment.providers().find(
                    FinalizedStreamSinkFactory.class,
                    StatefulSecretLegacySinkFactory.SCHEME));

            assertThat(first)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining(SECRET_CALLBACK_MESSAGE)
                    .hasRootCauseMessage(SECRET_CALLBACK_MESSAGE);
            assertThat(second).isSameAs(first);
            assertThat(StatefulSecretLegacySinkFactory.schemeCalls).hasValue(2);
            assertThat(StatefulSecretLegacySinkFactory.closeCalls).hasValue(1);
        } finally {
            assertThat(StatefulSecretLegacySinkFactory.closeCalls).hasValue(1);
            StatefulSecretLegacySinkFactory.reset();
        }
    }

    @Test
    void catalogFrozenLegacyMetadataPreventsPluginManagerResnapshot()
            throws Exception {
        StatefulSecretLegacyPlugin.reset();
        Path jar = tempDirectory.resolve("stateful-secret-node-plugin.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                StatefulSecretLegacyPlugin.class.getName()
                        .getBytes(StandardCharsets.UTF_8)));
        var scheduler = Executors.newSingleThreadScheduledExecutor();

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                     PluginsOptions.defaults(), loader)) {
            PluginManager manager = environment.createNodePluginManager(
                    new NoopEventBus(), scheduler);
            try {
                manager.discoverAndInit();
                manager.startAll();
                assertThat(StatefulSecretLegacyPlugin.idCalls).hasValue(2);
                assertThat(StatefulSecretLegacyPlugin.closeCalls).hasValue(0);
            } finally {
                manager.close();
            }
        } finally {
            scheduler.shutdownNow();
            assertThat(StatefulSecretLegacyPlugin.closeCalls).hasValue(1);
            StatefulSecretLegacyPlugin.reset();
        }
    }

    @Test
    void pluginManagerUsesBoundedLegacyIdSnapshotEvenWhenRawPluginLaterChanges()
            throws Exception {
        StatefulOversizedIdLegacyPlugin.reset();
        Path jar = tempDirectory.resolve("stateful-oversized-node-plugin.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                StatefulOversizedIdLegacyPlugin.class.getName()
                        .getBytes(StandardCharsets.UTF_8)));
        var scheduler = Executors.newSingleThreadScheduledExecutor();

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                     PluginsOptions.defaults(), loader)) {
            PluginManager manager = environment.createNodePluginManager(
                    new NoopEventBus(), scheduler);
            try {
                manager.discoverAndInit();
                manager.startAll();
                assertThat(StatefulOversizedIdLegacyPlugin.idCalls).hasValue(2);
                assertThat(StatefulOversizedIdLegacyPlugin.closeCalls).hasValue(0);
            } finally {
                manager.close();
            }
        } finally {
            scheduler.shutdownNow();
            assertThat(StatefulOversizedIdLegacyPlugin.closeCalls).hasValue(1);
            StatefulOversizedIdLegacyPlugin.reset();
        }
    }

    @Test
    void legacyCleanupAssertionCannotEscapeTheSafeActivationBoundary() throws Exception {
        SecretCleanupLegacyPlugin.closeCalls.set(0);
        Path jar = tempDirectory.resolve("secret-cleanup.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                SecretCleanupLegacyPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));
        PluginsOptions options = new PluginsOptions(
                true, false, Set.of("com.example.missing"), Set.of(), Map.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.classpath(options, loader))
                    .isInstanceOf(PluginCatalogActivationException.class)
                    .hasMessageContaining("failed during cleanup")
                    .hasMessageNotContaining(SECRET_CALLBACK_MESSAGE)
                    .satisfies(failure -> {
                        Throwable structural = failure.getCause();
                        assertThat(structural.getSuppressed()).hasSize(1);
                        assertThat(structural.getSuppressed()[0])
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining(
                                        "Allow-listed plugin bundles were not discovered")
                                .hasMessageNotContaining(SECRET_CALLBACK_MESSAGE)
                                .hasMessage(
                                        "Allow-listed plugin bundles were not discovered: "
                                                + "[com.example.missing]");
                        assertThat(structural)
                                .isInstanceOf(
                                        PluginCatalogBuilder.LegacyProviderCleanupException.class)
                                .hasRootCauseMessage(SECRET_CALLBACK_MESSAGE);
                    });
        }

        assertThat(SecretCleanupLegacyPlugin.closeCalls).hasValue(1);
    }

    @Test
    void legacyMetadataRetainsCompatibilityButEnforcesManifestScaleBounds() throws Exception {
        assertBoundedLegacyFailure(
                OversizedIdLegacyPlugin.class, "legacy provider selector", "oversized-id.jar");
        assertBoundedLegacyFailure(
                OversizedVersionLegacyPlugin.class,
                "legacy NodePlugin version", "oversized-version.jar");
        assertBoundedLegacyFailure(
                OversizedDependenciesLegacyPlugin.class,
                "at most 256 entries", "oversized-dependencies.jar");
    }

    private void assertBoundedLegacyFailure(
            Class<? extends NodePlugin> provider,
            String diagnostic,
            String filename
    ) throws Exception {
        Path jar = tempDirectory.resolve(filename);
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                provider.getName().getBytes(StandardCharsets.UTF_8)));
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader))
                    .isInstanceOf(PluginCatalogActivationException.class)
                    .hasMessageContaining(diagnostic);
        }
    }

    private void assertSecretSafeLegacyFailure(
            Class<?> provider,
            String servicePath,
            String operation,
            String filename
    ) throws Exception {
        Path jar = tempDirectory.resolve(filename);
        writeJar(jar, Map.of(servicePath,
                provider.getName().getBytes(StandardCharsets.UTF_8)));
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader))
                    .isInstanceOf(PluginCatalogActivationException.class)
                    .hasMessageContaining(provider.getName())
                    .hasMessageContaining(operation)
                    .hasMessageNotContaining(SECRET_CALLBACK_MESSAGE)
                    .hasRootCauseMessage(SECRET_CALLBACK_MESSAGE);
        }
    }

    @Test
    void failedEagerLegacyActivationIsClosedExactlyOnce() throws Exception {
        FlakyLegacySinkFactory.schemeCalls.set(0);
        FlakyLegacySinkFactory.closeCalls.set(0);
        Path jar = tempDirectory.resolve("flaky-legacy-sink.jar");
        writeJar(jar, Map.of(SINK_SERVICE,
                FlakyLegacySinkFactory.class.getName().getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader);
            try {
                assertThatThrownBy(() -> environment.providers().find(
                        FinalizedStreamSinkFactory.class, FlakyLegacySinkFactory.INITIAL_SCHEME))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Failed to activate provider")
                        .hasRootCauseMessage("Provider '"
                                + FlakyLegacySinkFactory.class.getName()
                                + "' selector mismatch: manifest='initial', provider='changed'");
                assertThat(FlakyLegacySinkFactory.closeCalls).hasValue(1);

                assertThatThrownBy(() -> environment.providers().find(
                        FinalizedStreamSinkFactory.class, FlakyLegacySinkFactory.INITIAL_SCHEME))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Failed to activate provider");
                assertThat(FlakyLegacySinkFactory.closeCalls).hasValue(1);
            } finally {
                environment.close();
            }
        }

        assertThat(FlakyLegacySinkFactory.closeCalls).hasValue(1);
    }

    @Test
    void lifecycleManagerCloseAccountingKeepsNormalNodePluginCloseExactlyOnce() throws Exception {
        LegacyClosingPlugin.closeCalls.set(0);
        Path jar = tempDirectory.resolve("managed-legacy.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                LegacyClosingPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             var scheduler = Executors.newSingleThreadScheduledExecutor();
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                     PluginsOptions.defaults(), loader)) {
            PluginManager manager = environment.createNodePluginManager(
                    new NoopEventBus(), scheduler);
            manager.discoverAndInit();
            manager.close();
        }

        assertThat(LegacyClosingPlugin.closeCalls).hasValue(1);
    }

    @Test
    void partialInitializationFailureClosesTouchedAndUntouchedNodePluginsExactlyOnce() {
        TestNodePlugin first = new TestNodePlugin("com.example.a", false, false);
        TestNodePlugin failing = new TestNodePlugin("com.example.b", true, false);
        TestNodePlugin untouched = new TestNodePlugin("com.example.c", false, false);
        CatalogPluginProviderRegistry registry = nodePluginRegistry(first, failing, untouched);

        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            List<NodePlugin> plugins = registry.nodePluginInstances();
            PluginManager manager = PluginManager.fromCatalog(
                    new NoopEventBus(), scheduler, PluginsOptions.defaults(), getClass().getClassLoader(),
                    plugins, Set.of(first.id(), failing.id(), untouched.id()), Map.of(),
                    registry::markNodePluginClosed);

            assertThatThrownBy(manager::discoverAndInit)
                    .isInstanceOf(PluginManager.PluginManagerException.class)
                    .hasMessageContaining("Plugin initialization failed");
            manager.close();
        }
        registry.close();

        assertThat(first.closeCalls).hasValue(1);
        assertThat(failing.closeCalls).hasValue(1);
        assertThat(untouched.closeCalls).hasValue(1);
    }

    @Test
    void metadataValidationFailureLeavesAllNodePluginsForRegistryCloseExactlyOnce() {
        TestNodePlugin invalid = new TestNodePlugin("com.example.invalid", false, true);
        TestNodePlugin other = new TestNodePlugin("com.example.other", false, false);
        CatalogPluginProviderRegistry registry = nodePluginRegistry(invalid, other);

        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            List<NodePlugin> plugins = registry.nodePluginInstances();
            PluginManager manager = PluginManager.fromCatalog(
                    new NoopEventBus(), scheduler, PluginsOptions.defaults(), getClass().getClassLoader(),
                    plugins, Set.of(invalid.id(), other.id()), Map.of(),
                    registry::markNodePluginClosed);

            assertThatThrownBy(manager::discoverAndInit)
                    .isInstanceOf(PluginManager.PluginManagerException.class)
                    .hasRootCauseMessage("Plugin capabilities must not be null");
            manager.close();
        }
        registry.close();

        assertThat(invalid.closeCalls).hasValue(1);
        assertThat(other.closeCalls).hasValue(1);
    }

    @Test
    void catalogLifecyclePreservesOrderAcrossTypedOnlyTransitiveDependency() {
        String dependencyId = "com.example.z-node-dependency";
        String typedBridgeId = "com.example.m-typed-bridge";
        String dependentId = "com.example.a-node-dependent";
        List<String> initialized = new ArrayList<>();
        CatalogOrderNodePlugin dependency = new CatalogOrderNodePlugin(
                dependencyId, Set.of(), initialized);
        CatalogOrderNodePlugin dependent = new CatalogOrderNodePlugin(
                dependentId, Set.of(typedBridgeId), initialized);
        List<CatalogPluginProviderRegistry.Entry> entries = List.of(
                new CatalogPluginProviderRegistry.Entry(
                        dependent.id(), ContributionKind.NODE_PLUGIN, dependent.id(),
                        dependent.getClass().getName(), null, () -> dependent),
                new CatalogPluginProviderRegistry.Entry(
                        dependency.id(), ContributionKind.NODE_PLUGIN, dependency.id(),
                        dependency.getClass().getName(), null, () -> dependency));
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                entries, List.of(dependencyId, typedBridgeId, dependentId), List.of());

        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            PluginManager manager = PluginManager.fromCatalog(
                    new NoopEventBus(), scheduler, PluginsOptions.defaults(),
                    getClass().getClassLoader(), registry.nodePluginInstances(),
                    Set.of(dependencyId, typedBridgeId, dependentId), Map.of(),
                    registry::markNodePluginClosed);
            try {
                manager.discoverAndInit();

                assertThat(initialized).containsExactly(dependencyId, dependentId);
            } finally {
                manager.close();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void maximumDepthCatalogChainOrdersWithoutUsingTheJvmStack() throws Exception {
        PluginIndex index = deepCatalogIndex(false);
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader,
                    List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH)));
            try {
                assertThat(result.selectedOrder()).hasSize(PluginIndex.MAX_BUNDLES);
                assertThat(result.selectedOrder().getFirst())
                        .isEqualTo(deepCatalogBundleId(PluginIndex.MAX_BUNDLES - 1));
                assertThat(result.selectedOrder().getLast())
                        .isEqualTo(deepCatalogBundleId(0));
            } finally {
                result.registry().close();
            }
        }
    }

    @Test
    void maximumDepthCatalogCycleHasDeterministicDiagnosticWithoutStackOverflow()
            throws Exception {
        PluginIndex index = deepCatalogIndex(true);
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            Throwable failure = catchThrowable(() -> new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader,
                    List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH))));

            assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(StackOverflowError.class);
            assertThat(failure.getMessage())
                    .startsWith("Plugin bundle dependency cycle: ["
                            + deepCatalogBundleId(0) + ", " + deepCatalogBundleId(1))
                    .contains("<4092 nodes omitted>")
                    .contains(deepCatalogBundleId(PluginIndex.MAX_BUNDLES - 2))
                    .contains(deepCatalogBundleId(PluginIndex.MAX_BUNDLES - 1))
                    .endsWith(deepCatalogBundleId(0) + "]");
            assertThat(failure.getMessage().length())
                    .isLessThanOrEqualTo(PluginCatalogBuilder.MAX_CYCLE_DIAGNOSTIC_LENGTH);
        }
    }

    @Test
    void directoryProviderCannotBeSatisfiedByParentShadowClass() throws Exception {
        ParentShadowSink.constructorCalls.set(0);
        String bundleId = "com.example.parent-shadow";
        Path pluginDirectory = Files.createDirectory(tempDirectory.resolve("plugins"));
        Path jar = pluginDirectory.resolve("shadow.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(classEntry(ParentShadowSink.class), classBytes(ParentShadowSink.class));
        entries.put(SINK_SERVICE, ParentShadowSink.class.getName().getBytes(StandardCharsets.UTF_8));
        entries.put(manifestPath(bundleId), manifest(
                bundleId, "shadow", ParentShadowSink.class.getName()));
        writeJar(jar, entries);
        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                pluginDirectory, getClass().getClassLoader());

        assertThatThrownBy(() -> PluginRuntimeEnvironment.open(PluginsOptions.defaults(), handle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent-first shadowing is not allowed");

        assertThat(ParentShadowSink.constructorCalls).hasValue(0);
    }

    @Test
    void directoryArtifactDigestIsRecheckedAfterProviderTypesLoad() throws Exception {
        String bundleId = "com.example.dynamic";
        String providerName = "com.example.dynamic.DynamicSink";
        Path classes = compileDynamicSink(providerName, "digest-recheck", "original");
        Path jar = tempDirectory.resolve("dynamic.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(providerName.replace('.', '/') + ".class",
                Files.readAllBytes(classes.resolve(providerName.replace('.', '/') + ".class")));
        entries.put(SINK_SERVICE, providerName.getBytes(StandardCharsets.UTF_8));
        entries.put(manifestPath(bundleId), manifest(bundleId, "dynamic", providerName));
        entries.put("payload.txt", "before".getBytes(StandardCharsets.UTF_8));
        writeJar(jar, entries);
        PluginIndex indexed = new PluginArtifactScanner().scan(jar);

        entries.put("payload.txt", "after".getBytes(StandardCharsets.UTF_8));
        writeJar(jar, entries);
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginCatalogBuilder.CatalogInput input = new PluginCatalogBuilder.CatalogInput(
                    indexed, PluginSourceCategory.DIRECTORY, jar);
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader, List.of(input)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("artifact changed during catalog construction");
        }
    }

    @Test
    void legacyClassDigestChangesWhenProviderBytecodeChangesButIdentityDoesNot() throws Exception {
        String providerName = "com.example.dynamic.DynamicSink";
        Path firstClasses = compileDynamicSink(providerName, "legacy-one", "one");
        Path secondClasses = compileDynamicSink(providerName, "legacy-two", "two");
        Path firstJar = legacySinkJar(providerName, firstClasses, "legacy-one.jar");
        Path secondJar = legacySinkJar(providerName, secondClasses, "legacy-two.jar");

        PluginBundleInfo first = legacyBundle(firstJar);
        PluginBundleInfo second = legacyBundle(secondJar);

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(first.digestMode()).isEqualTo(PluginDigestMode.LEGACY_CLASS);
        assertThat(second.digestMode()).isEqualTo(PluginDigestMode.LEGACY_CLASS);
        assertThat(first.digest()).isNotEqualTo(second.digest());
    }

    @Test
    void inventoryExposesPolicyReasonAndDependencyFirstSelectedOrder() throws Exception {
        Path jar = tempDirectory.resolve("ordered-legacy.jar");
        String services = LegacyDependentPlugin.class.getName() + "\n"
                + LegacyDependencyPlugin.class.getName();
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE, services.getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginCatalogBuilder.BuildResult selected = new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader, List.of());
            try {
                assertThat(selected.catalog().selectedBundleOrder()).containsExactly(
                        LegacyDependencyPlugin.ID, LegacyDependentPlugin.ID);
                assertThatThrownBy(() -> selected.catalog().selectedBundleOrder().add("mutate"))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThat(selected.catalog().bundles())
                        .allMatch(bundle -> bundle.selectionStatus()
                                == PluginSelectionStatus.SELECTED);
            } finally {
                selected.registry().close();
            }
        }

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginsOptions policy = new PluginsOptions(true, false,
                    Set.of(LegacyDependencyPlugin.ID),
                    Set.of(LegacyDependencyPlugin.ID), Map.of());
            PluginCatalogBuilder.BuildResult filtered = new PluginCatalogBuilder().build(
                    policy, loader, List.of());
            try {
                Map<String, PluginSelectionStatus> statuses = new LinkedHashMap<>();
                filtered.catalog().bundles().forEach(bundle ->
                        statuses.put(bundle.id(), bundle.selectionStatus()));
                assertThat(statuses).containsEntry(
                                LegacyDependencyPlugin.ID, PluginSelectionStatus.DENIED)
                        .containsEntry(LegacyDependentPlugin.ID,
                                PluginSelectionStatus.NOT_ALLOW_LISTED);
                assertThat(filtered.catalog().selectedBundleOrder()).isEmpty();
            } finally {
                filtered.registry().close();
            }
        }
    }

    @Test
    void manifestedNodePluginsReceiveOnlyOwnImmutablePrefixStrippedBundleConfig() throws Exception {
        FirstConfigPlugin.context = null;
        SecondConfigPlugin.context = null;
        Path jar = tempDirectory.resolve("manifested-config.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                (FirstConfigPlugin.class.getName() + "\n" + SecondConfigPlugin.class.getName())
                        .getBytes(StandardCharsets.UTF_8)));
        PluginIndex index = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(FirstConfigPlugin.ID, FirstConfigPlugin.class, 'a'),
                        indexedNodeBundle(SecondConfigPlugin.ID, SecondConfigPlugin.class, 'b')),
                List.of());
        Map<String, Object> config = Map.of(
                "shared.compatibility", "visible",
                environmentBundleConfigKey(
                        FirstConfigPlugin.ID, "ENDPOINT_URL"), "https://first",
                quotedBundleConfigKey(
                        SecondConfigPlugin.ID, "credential"), "second-secret");
        PluginsOptions options = new PluginsOptions(true, false, Set.of(), Set.of(), config);

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    options, loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH)));
            try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
                PluginManager manager = PluginManager.fromCatalog(
                        new NoopEventBus(), scheduler, options, loader,
                        result.registry().nodePluginInstances(), result.selectedIds(),
                        result.bundleConfigs(), result.registry()::markNodePluginClosed);
                manager.discoverAndInit();

                assertThat(FirstConfigPlugin.context.bundleConfig())
                        .containsExactly(Map.entry("endpoint.url", "https://first"));
                assertThat(FirstConfigPlugin.context.bundleConfig())
                        .doesNotContainValue("second-secret");
                assertThat(SecondConfigPlugin.context.bundleConfig())
                        .containsExactly(Map.entry("credential", "second-secret"));
                assertThatThrownBy(() -> FirstConfigPlugin.context.bundleConfig()
                        .put("mutate", "no"))
                        .isInstanceOf(UnsupportedOperationException.class);
                // config() is deliberately retained as the immutable shared legacy view.
                assertThat(FirstConfigPlugin.context.config()).containsExactlyInAnyOrderEntriesOf(config);
                assertThatThrownBy(() -> FirstConfigPlugin.context.config().put("mutate", "no"))
                        .isInstanceOf(UnsupportedOperationException.class);
                manager.close();
            } finally {
                result.registry().close();
            }
        }
    }

    @Test
    void frozenManifestedMetadataPreservesHeaderValidationCustomizerRole() throws Exception {
        Path jar = tempDirectory.resolve("manifested-header-customizer.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                ManifestedHeaderCustomizerPlugin.class.getName()
                        .getBytes(StandardCharsets.UTF_8)));
        PluginIndex index = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(
                        ManifestedHeaderCustomizerPlugin.ID,
                        ManifestedHeaderCustomizerPlugin.class, '8')), List.of());
        var scheduler = Executors.newSingleThreadScheduledExecutor();

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader,
                    List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH)));
            try {
                PluginManager manager = PluginManager.fromCatalog(
                        new NoopEventBus(), scheduler, PluginsOptions.defaults(), loader,
                        result.registry().nodePluginInstances(), result.selectedIds(),
                        result.bundleConfigs(), result.registry()::markNodePluginClosed);
                manager.discoverAndInit();
                assertThat(manager.getHeaderValidationCustomizers()).hasSize(1);
                manager.close();
            } finally {
                result.registry().close();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void environmentBundleOwnerPreventsOverlapDenialAndRemovalFromReassigningConfig()
            throws Exception {
        ParentConfigPlugin.context = null;
        ChildConfigPlugin.context = null;
        Path jar = tempDirectory.resolve("overlapping-manifested-config.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                (ParentConfigPlugin.class.getName() + "\n" + ChildConfigPlugin.class.getName())
                        .getBytes(StandardCharsets.UTF_8)));
        PluginIndex index = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(ParentConfigPlugin.ID, ParentConfigPlugin.class, 'c'),
                        indexedNodeBundle(ChildConfigPlugin.ID, ChildConfigPlugin.class, 'd')),
                List.of());
        Map<String, Object> config = Map.of(
                environmentBundleConfigKey(ParentConfigPlugin.ID, "ENDPOINT"), "parent",
                environmentBundleConfigKey(
                        ChildConfigPlugin.ID, "CREDENTIAL"), "child-secret");

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginsOptions options = new PluginsOptions(true, false, Set.of(), Set.of(), config);
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    options, loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH)));
            try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
                PluginManager manager = PluginManager.fromCatalog(
                        new NoopEventBus(), scheduler, options, loader,
                        result.registry().nodePluginInstances(), result.selectedIds(),
                        result.bundleConfigs(), result.registry()::markNodePluginClosed);
                manager.discoverAndInit();

                assertThat(ParentConfigPlugin.context.bundleConfig())
                        .containsExactly(Map.entry("endpoint", "parent"))
                        .doesNotContainValue("child-secret");
                assertThat(ChildConfigPlugin.context.bundleConfig())
                        .containsExactly(Map.entry("credential", "child-secret"));
                manager.close();
            } finally {
                result.registry().close();
            }
        }

        // Policy exclusion must not reassign a denied child's namespace to its parent.
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginsOptions options = new PluginsOptions(true, false, Set.of(),
                    Set.of(ChildConfigPlugin.ID), config);
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    options, loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH)));
            try {
                assertThat(result.bundleConfigs().get(ParentConfigPlugin.ID))
                        .containsExactly(Map.entry("endpoint", "parent"))
                        .doesNotContainValue("child-secret");
                assertThat(result.bundleConfigs()).doesNotContainKey(ChildConfigPlugin.ID);
            } finally {
                result.registry().close();
            }
        }

        Path parentOnlyJar = tempDirectory.resolve("parent-only-manifested-config.jar");
        writeJar(parentOnlyJar, Map.of(NODE_PLUGIN_SERVICE,
                ParentConfigPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));
        PluginIndex parentOnlyIndex = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(
                        ParentConfigPlugin.ID, ParentConfigPlugin.class, 'e')),
                List.of());
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{parentOnlyJar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginsOptions options = new PluginsOptions(true, false, Set.of(), Set.of(), config);
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    options, loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            parentOnlyIndex, PluginSourceCategory.CLASSPATH)));
            try {
                assertThat(result.bundleConfigs().get(ParentConfigPlugin.ID))
                        .containsExactly(Map.entry("endpoint", "parent"))
                        .doesNotContainValue("child-secret");
            } finally {
                result.registry().close();
            }
        }
    }

    @Test
    void bracketedBundleAliasRemainsCompatibleAndConflictsFailWithoutExposingValues()
            throws Exception {
        Path jar = tempDirectory.resolve("bracketed-config-alias.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                FirstConfigPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));
        PluginIndex index = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(
                        FirstConfigPlugin.ID, FirstConfigPlugin.class, 'f')),
                List.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginsOptions compatible = new PluginsOptions(true, false, Set.of(), Set.of(),
                    Map.of(bracketedBundleConfigKey(
                            FirstConfigPlugin.ID, "endpoint"), "compatible"));
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    compatible, loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH)));
            try {
                assertThat(result.bundleConfigs().get(FirstConfigPlugin.ID))
                        .containsExactly(Map.entry("endpoint", "compatible"));
            } finally {
                result.registry().close();
            }
        }

        String firstSecret = "first-alias-secret";
        String secondSecret = "second-alias-secret";
        Map<String, Object> conflicting = Map.of(
                bracketedBundleConfigKey(FirstConfigPlugin.ID, "credential"), firstSecret,
                quotedBundleConfigKey(FirstConfigPlugin.ID, "credential"), secondSecret);
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    new PluginsOptions(true, false, Set.of(), Set.of(), conflicting),
                    loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Conflicting plugin bundle configuration aliases")
                    .hasMessageNotContaining(firstSecret)
                    .hasMessageNotContaining(secondSecret);
        }
    }

    @Test
    void exactQuotedPropertyWinsOverLossyEnvironmentPropertySuffix() throws Exception {
        Path jar = tempDirectory.resolve("exact-config-wins.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                FirstConfigPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));
        PluginIndex index = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(
                        FirstConfigPlugin.ID, FirstConfigPlugin.class, '9')), List.of());
        Map<String, Object> config = Map.of(
                quotedBundleConfigKey(FirstConfigPlugin.ID, "endpoint.url"), "exact",
                environmentBundleConfigKey(FirstConfigPlugin.ID, "ENDPOINT_URL"),
                "environment-alias");

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    new PluginsOptions(true, false, Set.of(), Set.of(), config),
                    loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH)));
            try {
                assertThat(result.bundleConfigs().get(FirstConfigPlugin.ID))
                        .containsExactly(Map.entry("endpoint.url", "exact"));
            } finally {
                result.registry().close();
            }
        }
    }

    @Test
    void lossyEnvironmentOwnerSyntaxFailsClosedEvenAfterColliderRemoval() throws Exception {
        Path jar = tempDirectory.resolve("environment-owner-collision.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                (HyphenConfigPlugin.class.getName() + "\n" + DotConfigPlugin.class.getName())
                        .getBytes(StandardCharsets.UTF_8)));
        PluginIndex index = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(
                        DotConfigPlugin.ID, DotConfigPlugin.class, '7')), List.of());
        String secret = "collision-secret-must-not-be-diagnosed";
        Map<String, Object> config = Map.of(lossyEnvironmentBundleConfigKey(
                HyphenConfigPlugin.ID, "CREDENTIAL"), secret);

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    new PluginsOptions(true, false, Set.of(), Set.of(), config),
                    loader, List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Lossy plugin bundle environment owner syntax")
                    .hasMessageNotContaining(secret);
        }
    }

    @Test
    void legacyNodePluginRetainsImmutableSharedConfigCompatibility() {
        CapturingLegacyPlugin plugin = new CapturingLegacyPlugin();
        Map<String, Object> config = Map.of("legacy.option", "still-visible");
        PluginsOptions options = new PluginsOptions(true, false, Set.of(), Set.of(), config);

        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            PluginManager manager = new PluginManager(new NoopEventBus(), scheduler,
                    options, getClass().getClassLoader(), List.of(plugin));
            manager.discoverAndInit();

            assertThat(plugin.context.config()).containsExactlyEntriesOf(config);
            assertThat(plugin.context.bundleConfig()).containsExactlyEntriesOf(config);
            assertThatThrownBy(() -> plugin.context.config().put("mutate", "no"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> plugin.context.bundleConfig().put("mutate", "no"))
                    .isInstanceOf(UnsupportedOperationException.class);
            manager.close();
        }
    }

    @Test
    void catalogLegacyNodePluginRetainsSharedBundleConfigCompatibility() throws Exception {
        CatalogLegacyConfigPlugin.context = null;
        Path jar = tempDirectory.resolve("catalog-legacy-config.jar");
        writeJar(jar, Map.of(NODE_PLUGIN_SERVICE,
                CatalogLegacyConfigPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> config = Map.of(
                "legacy.option", "still-visible",
                "yano.plugins." + CatalogLegacyConfigPlugin.ID + ".nested", "also-shared");
        PluginsOptions options = new PluginsOptions(true, false, Set.of(), Set.of(), config);

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             var scheduler = Executors.newSingleThreadScheduledExecutor();
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                     options, loader)) {
            PluginManager manager = environment.createNodePluginManager(
                    new NoopEventBus(), scheduler);
            try {
                manager.discoverAndInit();

                assertThat(CatalogLegacyConfigPlugin.context.config())
                        .containsExactlyInAnyOrderEntriesOf(config);
                assertThat(CatalogLegacyConfigPlugin.context.bundleConfig())
                        .containsExactlyInAnyOrderEntriesOf(config);
                assertThatThrownBy(() -> CatalogLegacyConfigPlugin.context.bundleConfig()
                        .put("mutate", "no"))
                        .isInstanceOf(UnsupportedOperationException.class);
            } finally {
                manager.close();
            }
        }
    }

    @Test
    void semverBoundedDependencyOnOpaqueLegacyVersionFailsWithMigrationDiagnostic()
            throws Exception {
        Path jar = tempDirectory.resolve("opaque-version.jar");
        writeJar(jar, Map.of(
                NODE_PLUGIN_SERVICE,
                OpaqueVersionLegacyPlugin.class.getName().getBytes(StandardCharsets.UTF_8),
                SINK_SERVICE,
                OpaqueVersionDependentSink.class.getName().getBytes(StandardCharsets.UTF_8)));
        BundleManifest dependent = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                OpaqueVersionDependentSink.ID,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1),
                List.of(new BundleDependency(OpaqueVersionLegacyPlugin.ID,
                        SemVersion.parse("1.0.0"), null)),
                List.of(new BundleContribution(ContributionKind.FINALIZED_SINK,
                        OpaqueVersionDependentSink.SCHEME,
                        OpaqueVersionDependentSink.class.getName())));
        PluginIndex index = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(new IndexedBundle(dependent, digest('c'), PluginDigestMode.JAR)),
                List.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader,
                    List.of(new PluginCatalogBuilder.CatalogInput(
                            index, PluginSourceCategory.CLASSPATH))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SemVer-bounded dependency")
                    .hasMessageContaining("reported version 'preview' is not valid SemVer");
        }
    }

    @Test
    void pluginLoaderRejectsSymlinkedDirectory() throws Exception {
        Path target = Files.createDirectory(tempDirectory.resolve("real-plugins"));
        Path link = tempDirectory.resolve("linked-plugins");
        createSymbolicLinkOrSkip(link, target);

        assertThatThrownBy(() -> PluginLoaderHandle.directory(link, getClass().getClassLoader()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a symbolic link");
    }

    @Test
    void pluginLoaderRejectsSymlinkedDirectoryEntry() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("plugins-with-link"));
        Path target = tempDirectory.resolve("actual.jar");
        writeJar(target, Map.of("payload.txt", new byte[]{1}));
        createSymbolicLinkOrSkip(directory.resolve("linked.jar"), target);

        assertThatThrownBy(() -> PluginLoaderHandle.directory(
                directory, getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbolic-link entries");
    }

    @Test
    void directoryCatalogRejectsManifestClassPathOutsideCapturedArtifacts()
            throws Exception {
        Path externalHelper = tempDirectory.resolve("external-helper.jar");
        writeJar(externalHelper, Map.of(
                "com/example/ExternalHelper.class", new byte[]{1}));

        String bundleId = "com.example.manifest-class-path";
        Path pluginDirectory = Files.createDirectory(
                tempDirectory.resolve("manifest-class-path-plugins"));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(JarFile.MANIFEST_NAME, ("Manifest-Version: 1.0\r\n"
                + "Class-Path: " + externalHelper.toUri().toASCIIString()
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        entries.put(classEntry(ParentShadowSink.class), classBytes(ParentShadowSink.class));
        entries.put(SINK_SERVICE,
                ParentShadowSink.class.getName().getBytes(StandardCharsets.UTF_8));
        entries.put(manifestPath(bundleId), manifest(
                bundleId, "manifest-class-path", ParentShadowSink.class.getName()));
        writeJar(pluginDirectory.resolve("manifest-class-path.jar"), entries);

        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                pluginDirectory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();

        assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                        PluginsOptions.defaults(), handle))
                .isInstanceOf(PluginCatalogActivationException.class)
                .hasMessageContaining("JAR manifest Class-Path is unsupported")
                .hasMessageNotContaining(externalHelper.toString());
        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void directoryCaptureUsesDigestOrderWithoutExposingUnclassifiedJars() throws Exception {
        String className = "com.example.dynamic.DynamicSink";
        Path firstClasses = compileDynamicSink(className, "loader-order-first", "first");
        Path secondClasses = compileDynamicSink(className, "loader-order-second", "second");
        String classEntry = className.replace('.', '/') + ".class";
        Path firstArtifact = tempDirectory.resolve("first-artifact.jar");
        Path secondArtifact = tempDirectory.resolve("second-artifact.jar");
        writeJar(firstArtifact, Map.of(classEntry,
                Files.readAllBytes(firstClasses.resolve(classEntry))));
        writeJar(secondArtifact, Map.of(classEntry,
                Files.readAllBytes(secondClasses.resolve(classEntry))));
        assertThat(artifactDigest(firstArtifact)).isNotEqualTo(artifactDigest(secondArtifact));

        Path firstDirectory = Files.createDirectory(tempDirectory.resolve("plugins-first"));
        Path swappedDirectory = Files.createDirectory(tempDirectory.resolve("plugins-swapped"));
        Files.copy(firstArtifact, firstDirectory.resolve("a.jar"));
        Files.copy(secondArtifact, firstDirectory.resolve("z.jar"));
        Files.copy(secondArtifact, swappedDirectory.resolve("a.jar"));
        Files.copy(firstArtifact, swappedDirectory.resolve("z.jar"));

        try (PluginLoaderHandle first = PluginLoaderHandle.directory(
                     firstDirectory, getClass().getClassLoader());
             PluginLoaderHandle swapped = PluginLoaderHandle.directory(
                     swappedDirectory, getClass().getClassLoader())) {
            List<String> firstOrder = first.artifacts().stream()
                    .map(PluginCatalogRuntimeTest::artifactDigest)
                    .toList();
            List<String> swappedOrder = swapped.artifacts().stream()
                    .map(PluginCatalogRuntimeTest::artifactDigest)
                    .toList();

            assertThat(firstOrder).isSorted();
            assertThat(firstOrder).containsExactlyElementsOf(swappedOrder);
            assertThat(first.classLoader()).isSameAs(getClass().getClassLoader());
            assertThat(swapped.classLoader()).isSameAs(getClass().getClassLoader());
            assertThatThrownBy(() -> first.classLoader().loadClass(className))
                    .isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(() -> swapped.classLoader().loadClass(className))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void directoryCaptureRejectsByteIdenticalDuplicateArtifacts() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("duplicate-artifacts"));
        Path first = directory.resolve("first.jar");
        writeJar(first, Map.of("payload.txt", new byte[]{1}));
        Files.copy(first, directory.resolve("second.jar"));

        assertThatThrownBy(() -> PluginLoaderHandle.directory(
                directory, getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate byte-identical plugin JAR deployments");
    }

    @Test
    void deniedDirectoryJarCannotShadowSelectedClassesResourcesOrCodeSource()
            throws Exception {
        String selectedId = "com.example.collision.selected";
        String deniedId = "com.example.collision.denied";
        String selectedProvider = "com.example.collision.SelectedSink";
        String deniedProvider = "com.example.collision.DeniedSink";
        Path deniedClinitMarker = tempDirectory.resolve(
                "collision-denied-clinit.marker");
        Path selectedClasses = compileCollisionSink(
                selectedProvider, "collision-selected", "selected", "selected", false);
        Path deniedClasses = compileCollisionSink(
                deniedProvider, "collision-denied", "denied", "denied", true);
        Path selectedJar = tempDirectory.resolve("collision-selected.jar");
        Path deniedJar = tempDirectory.resolve("collision-denied.jar");
        writeCollisionJar(selectedJar, selectedClasses, selectedId, "selected",
                selectedProvider, "selected", List.of(), 0);
        int padding = 0;
        do {
            writeCollisionJar(deniedJar, deniedClasses, deniedId, "denied",
                    deniedProvider, "denied", List.of(), padding++);
        } while (artifactDigest(deniedJar).compareTo(artifactDigest(selectedJar)) >= 0
                && padding < 1_024);
        assertThat(artifactDigest(deniedJar))
                .as("the denied artifact must precede the selected artifact in the old broad-loader order")
                .isLessThan(artifactDigest(selectedJar));

        Path selectedOnly = Files.createDirectory(
                tempDirectory.resolve("collision-selected-only"));
        Files.copy(selectedJar, selectedOnly.resolve("z-selected.jar"));
        Path allowFiltered = Files.createDirectory(
                tempDirectory.resolve("collision-allow-filtered"));
        Files.copy(deniedJar, allowFiltered.resolve("a-denied.jar"));
        Files.copy(selectedJar, allowFiltered.resolve("z-selected.jar"));
        Path denyFilteredRenamed = Files.createDirectory(
                tempDirectory.resolve("collision-deny-filtered-renamed"));
        Files.copy(selectedJar, denyFilteredRenamed.resolve("a-selected.jar"));
        Files.copy(deniedJar, denyFilteredRenamed.resolve("z-renamed-denied.jar"));

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader caller = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            thread.setContextClassLoader(caller);
            DirectoryProjection baseline = observeDirectoryProjection(
                    selectedOnly, PluginsOptions.defaults(), caller,
                    selectedId, null, null, selectedProvider, selectedJar);
            DirectoryProjection allowed = observeDirectoryProjection(
                    allowFiltered,
                    new PluginsOptions(true, false, Set.of(selectedId), Set.of(), Map.of()),
                    caller, selectedId, deniedId, PluginSelectionStatus.NOT_ALLOW_LISTED,
                    selectedProvider, selectedJar);
            DirectoryProjection denied = observeDirectoryProjection(
                    denyFilteredRenamed,
                    new PluginsOptions(true, false, Set.of(), Set.of(deniedId), Map.of()),
                    caller, selectedId, deniedId, PluginSelectionStatus.DENIED,
                    selectedProvider, selectedJar);

            assertThat(allowed).isEqualTo(baseline);
            assertThat(denied).isEqualTo(baseline);
            assertThat(thread.getContextClassLoader()).isSameAs(caller);
            assertThat(baseline.sinkId()).isEqualTo("selected:selected:"
                    + artifactDigest(selectedJar) + ".jar");
            assertThat(deniedClinitMarker).doesNotExist();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void filteredDirectoryDependencyFailureClosesSelectedProjectionAndRestoresTccl()
            throws Exception {
        String selectedId = "com.example.failure.selected";
        String deniedId = "com.example.failure.denied";
        String selectedProvider = "com.example.failure.SelectedSink";
        String deniedProvider = "com.example.failure.DeniedSink";
        Path selectedClasses = compileCollisionSink(
                selectedProvider, "failure-selected", "failure-selected", "selected", false);
        Path deniedClasses = compileCollisionSink(
                deniedProvider, "failure-denied", "failure-denied", "denied", true);
        Path directory = Files.createDirectory(
                tempDirectory.resolve("filtered-dependency-failure"));
        writeCollisionJar(directory.resolve("selected.jar"), selectedClasses,
                selectedId, "failure-selected", selectedProvider, "selected",
                List.of(deniedId), 0);
        writeCollisionJar(directory.resolve("denied.jar"), deniedClasses,
                deniedId, "failure-denied", deniedProvider, "denied", List.of(), 0);

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader caller = new ClassLoader(getClass().getClassLoader()) { };
        thread.setContextClassLoader(caller);
        PluginLoaderHandle handle = PluginLoaderHandle.directory(directory, caller);
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        try {
            assertThat(handle.classLoader()).isSameAs(caller);
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    new PluginsOptions(true, false, Set.of(selectedId), Set.of(), Map.of()),
                    handle))
                    .isInstanceOf(PluginCatalogActivationException.class)
                    .hasMessageContaining(selectedId)
                    .hasMessageContaining("requires unavailable selected bundle")
                    .hasMessageContaining(deniedId);
            assertThat(thread.getContextClassLoader()).isSameAs(caller);
            assertThat(snapshotDirectory).doesNotExist();
            assertThat(handle.classLoader().getResource("collision-marker.txt")).isNull();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void directoryLegacyBundlePolicyFailsBeforeExecutableProjection() throws Exception {
        String providerName = "com.example.dynamic.DynamicSink";
        Path classes = compileDynamicSink(providerName, "legacy-policy", "legacy-policy");
        Path directory = Files.createDirectory(tempDirectory.resolve("legacy-policy-directory"));
        Files.copy(legacySinkJar(providerName, classes, "legacy-policy-source.jar"),
                directory.resolve("legacy.jar"));
        ClassLoader parent = getClass().getClassLoader();
        PluginLoaderHandle handle = PluginLoaderHandle.directory(directory, parent);
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();

        assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                new PluginsOptions(true, false, Set.of("com.example.any"), Set.of(), Map.of()),
                handle))
                .isInstanceOf(PluginCatalogActivationException.class)
                .hasMessageContaining("legacy providers cannot be used")
                .hasMessageContaining("add ADR-011.2 manifests");
        assertThat(handle.classLoader()).isSameAs(parent);
        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void directoryTypeValidationBudgetIsSharedAcrossArtifactsAtExactBoundary()
            throws Exception {
        String firstProvider = "com.example.budget.FirstSink";
        String secondProvider = "com.example.budget.SecondSink";
        Path firstClasses = compileCollisionSink(
                firstProvider, "budget-first", "budget-first", "first", false);
        Path secondClasses = compileCollisionSink(
                secondProvider, "budget-second", "budget-second", "second", false);
        Path firstJar = tempDirectory.resolve("budget-first.jar");
        Path secondJar = tempDirectory.resolve("budget-second.jar");
        writeCollisionJar(firstJar, firstClasses, "com.example.budget.first",
                "budget-first", firstProvider, "first", List.of(), 0);
        writeCollisionJar(secondJar, secondClasses, "com.example.budget.second",
                "budget-second", secondProvider, "second", List.of(), 0);
        PluginArtifactScanner scanner = new PluginArtifactScanner();
        DirectoryPluginArtifactValidator.DeploymentValidationBudget budget =
                new DirectoryPluginArtifactValidator.DeploymentValidationBudget(1);

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader parent = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            thread.setContextClassLoader(parent);
            DirectoryPluginArtifactValidator.validate(
                    firstJar.toRealPath(), scanner.scan(firstJar), parent, budget);
            assertThat(budget.discoveredProviders()).isEqualTo(1);
            assertThat(thread.getContextClassLoader()).isSameAs(parent);

            assertThatThrownBy(() -> DirectoryPluginArtifactValidator.validate(
                    secondJar.toRealPath(), scanner.scan(secondJar), parent, budget))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deployment-wide limit of 1")
                    .hasMessageContaining("across captured artifacts")
                    .hasMessageNotContaining(secondJar.toString());
            assertThat(budget.discoveredProviders()).isEqualTo(1);
            assertThat(thread.getContextClassLoader()).isSameAs(parent);
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void preActivationDirectoryTypeValidationFailureDeletesSnapshotAndRestoresTccl()
            throws Exception {
        String bundleId = "com.example.invalid.provider-type";
        String provider = "com.example.invalid.BrokenSink";
        Path directory = Files.createDirectory(
                tempDirectory.resolve("invalid-provider-type-directory"));
        writeJar(directory.resolve("invalid-provider.jar"), Map.of(
                provider.replace('.', '/') + ".class", new byte[]{1, 2, 3},
                SINK_SERVICE, provider.getBytes(StandardCharsets.UTF_8),
                manifestPath(bundleId), manifest(bundleId, "invalid-provider", provider)));

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader parent = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            thread.setContextClassLoader(parent);
            PluginLoaderHandle handle = PluginLoaderHandle.directory(directory, parent);
            Path snapshotDirectory = handle.artifacts().getFirst().getParent();

            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), handle))
                    .isInstanceOf(PluginCatalogActivationException.class)
                    .hasMessageContaining("provider types could not be validated");
            assertThat(handle.classLoader()).isSameAs(parent);
            assertThat(snapshotDirectory).doesNotExist();
            assertThat(thread.getContextClassLoader()).isSameAs(parent);
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void directoryCatalogRetainsCapturedArtifactsAfterAtomicSourceSwap() throws Exception {
        String firstProvider = "com.example.dynamic.FirstOrderingSink";
        String secondProvider = "com.example.dynamic.SecondOrderingSink";
        String firstEntry = firstProvider.replace('.', '/') + ".class";
        String secondEntry = secondProvider.replace('.', '/') + ".class";
        Path firstClasses = compilePackagedDirectorySink(firstProvider, "ordering-first");
        Path secondClasses = compilePackagedDirectorySink(secondProvider, "ordering-second");
        Path directory = Files.createDirectory(tempDirectory.resolve("ordering-race-plugins"));
        Path firstJar = directory.resolve("first.jar");
        Path secondJar = directory.resolve("second.jar");
        writeJar(firstJar, Map.of(
                firstEntry, Files.readAllBytes(firstClasses.resolve(firstEntry)),
                SINK_SERVICE, firstProvider.getBytes(StandardCharsets.UTF_8),
                manifestPath("com.example.ordering-first"), manifest(
                        "com.example.ordering-first", "ordering-first", firstProvider)));
        writeJar(secondJar, Map.of(
                secondEntry, Files.readAllBytes(secondClasses.resolve(secondEntry)),
                SINK_SERVICE, secondProvider.getBytes(StandardCharsets.UTF_8),
                manifestPath("com.example.ordering-second"), manifest(
                        "com.example.ordering-second", "ordering-second", secondProvider)));

        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        try {
            List<String> capturedOrder = handle.artifacts().stream()
                    .map(PluginCatalogRuntimeTest::artifactDigest)
                    .toList();
            assertThat(capturedOrder).hasSize(2).isSorted();

            Path firstReplacement = directory.resolve("first.replacement");
            Path secondReplacement = directory.resolve("second.replacement");
            Files.copy(secondJar, firstReplacement);
            Files.copy(firstJar, secondReplacement);
            atomicReplace(firstReplacement, firstJar);
            atomicReplace(secondReplacement, secondJar);

            List<String> finalBytesInCapturedLoaderOrder = handle.artifacts().stream()
                    .map(PluginCatalogRuntimeTest::artifactDigest)
                    .toList();
            assertThat(finalBytesInCapturedLoaderOrder).containsExactlyElementsOf(capturedOrder);

            try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), handle)) {
                assertThat(environment.selectedBundleIds()).contains(
                        "com.example.ordering-first", "com.example.ordering-second");
            }
        } finally {
            handle.close();
        }
        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void directorySnapshotKeepsLazyClassAndResourceBytesAfterRuntimeSourceReplacement()
            throws Exception {
        String bundleId = "com.example.snapshot";
        String providerName = "com.example.snapshot.SnapshotSink";
        String providerEntry = providerName.replace('.', '/') + ".class";
        String lazyEntry = "com/example/snapshot/LazyMarker.class";
        Path firstClasses = compileLazySnapshotSink("lazy-snapshot-first", "class-v1");
        Path secondClasses = compileLazySnapshotSink("lazy-snapshot-second", "class-v2");
        Path directory = Files.createDirectory(tempDirectory.resolve("lazy-snapshot-plugins"));
        Path sourceJar = directory.resolve("snapshot.jar");
        Path replacementJar = tempDirectory.resolve("snapshot-replacement.bin");
        writeJar(sourceJar, Map.of(
                providerEntry, Files.readAllBytes(firstClasses.resolve(providerEntry)),
                lazyEntry, Files.readAllBytes(firstClasses.resolve(lazyEntry)),
                SINK_SERVICE, providerName.getBytes(StandardCharsets.UTF_8),
                manifestPath(bundleId), manifest(bundleId, "snapshot", providerName),
                "snapshot-marker.txt", "resource-v1".getBytes(StandardCharsets.UTF_8)));
        writeJar(replacementJar, Map.of(
                providerEntry, Files.readAllBytes(secondClasses.resolve(providerEntry)),
                lazyEntry, Files.readAllBytes(secondClasses.resolve(lazyEntry)),
                SINK_SERVICE, providerName.getBytes(StandardCharsets.UTF_8),
                manifestPath(bundleId), manifest(bundleId, "snapshot", providerName),
                "snapshot-marker.txt", "resource-v2".getBytes(StandardCharsets.UTF_8)));

        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotJar = handle.artifacts().getFirst();
        Path snapshotDirectory = snapshotJar.getParent();
        String capturedDigest = artifactDigest(snapshotJar);
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                PluginsOptions.defaults(), handle)) {
            Class<?> providerType = environment.classLoader().loadClass(providerName);
            Object provider = providerType.getConstructor().newInstance();

            atomicReplace(replacementJar, sourceJar);
            assertThat(artifactDigest(sourceJar)).isNotEqualTo(capturedDigest);
            assertThat(artifactDigest(snapshotJar)).isEqualTo(capturedDigest);

            assertThat(providerType.getMethod("marker").invoke(provider))
                    .isEqualTo("class-v1:resource-v1");
            assertThat(environment.catalog().bundles())
                    .filteredOn(bundle -> bundle.id().equals(bundleId))
                    .singleElement()
                    .satisfies(bundle -> assertThat(bundle.digest())
                            .isEqualTo("sha256:" + capturedDigest));
        }
        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void failedDirectoryCatalogActivationDeletesOwnedSnapshot() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("invalid-snapshot-plugins"));
        writeJar(directory.resolve("ordinary.jar"), Map.of("payload.txt", new byte[]{1}));
        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();

        assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                PluginsOptions.defaults(), handle))
                .isInstanceOf(PluginCatalogActivationException.class)
                .hasMessageContaining("ordinary.jar")
                .hasMessageContaining("no bundle manifest");

        assertThat(snapshotDirectory).doesNotExist();
        handle.close();
    }

    @Test
    void sneakyCheckedEmbeddedIndexFailureStillClosesClaimedDirectoryLoader()
            throws Exception {
        Throwable sneaky = new Exception("checked enumeration failure");
        PluginLoaderHandle handle = directoryHandleWithIndexEnumerationFailure(
                "sneaky-index-enumeration", sneaky);
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();

        assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                PluginsOptions.defaults(), handle))
                .isInstanceOf(PluginCatalogActivationException.class)
                .hasCause(sneaky);

        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void hostileFailureMessageCannotPreventClaimedLoaderCleanup() throws Exception {
        RuntimeException hostile = new RuntimeException() {
            @Override public String getMessage() {
                throw new AssertionError("hostile message accessor");
            }
        };
        PluginLoaderHandle handle = directoryHandleWithIndexEnumerationFailure(
                "hostile-message-enumeration", hostile);
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();

        assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                PluginsOptions.defaults(), handle))
                .isInstanceOf(PluginCatalogActivationException.class)
                .hasMessage("Plugin catalog activation failed")
                .hasCause(hostile);

        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void postBuildFailureClosesRegistryBeforeDeletingOwnedLoaderSnapshot() throws Exception {
        Path directory = Files.createDirectory(
                tempDirectory.resolve("post-build-cleanup-order"));
        writeJar(directory.resolve("captured.jar"),
                Map.of("marker.txt", new byte[]{1}));
        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        AtomicReference<Boolean> snapshotPresentDuringRegistryClose =
                new AtomicReference<>();
        IllegalStateException postBuildFailure =
                new IllegalStateException("expected post-build failure");
        PluginsOptions disabled = new PluginsOptions(
                false, false, Set.of(), Set.of(), Map.of());

        assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                disabled, handle, (ignoredDigest, result) -> {
                    CompletableFuture<Void> cleanup = new CompletableFuture<>();
                    result.registry().registerContributionCleanup(cleanup);
                    Thread observer = new Thread(() -> {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (System.nanoTime() < deadline) {
                            try {
                                result.registry().names(FinalizedStreamSinkFactory.class);
                                Thread.onSpinWait();
                            } catch (IllegalStateException closing) {
                                snapshotPresentDuringRegistryClose.set(
                                        Files.exists(snapshotDirectory));
                                cleanup.complete(null);
                                return;
                            }
                        }
                        cleanup.completeExceptionally(new AssertionError(
                                "Registry close was not observed before timeout"));
                    }, "plugin-registry-close-order-observer");
                    observer.setDaemon(true);
                    observer.start();
                    throw postBuildFailure;
                }))
                .isInstanceOf(PluginCatalogActivationException.class)
                .hasCause(postBuildFailure);

        assertThat(snapshotPresentDuringRegistryClose).hasValue(true);
        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void catalogActivationDiagnosticReplacesEveryIsoControlCharacter() {
        StringBuilder controls = new StringBuilder();
        for (int value = Character.MIN_VALUE; value <= Character.MAX_VALUE; value++) {
            if (Character.isISOControl(value)) {
                controls.append((char) value);
            }
        }

        PluginCatalogActivationException failure = PluginCatalogActivationException.from(
                new IllegalStateException("before" + controls + "after"));

        assertThat(failure.getMessage()).contains("before").contains("after");
        assertThat(failure.getMessage().chars()
                .noneMatch(Character::isISOControl)).isTrue();
    }

    @Test
    void legacyClassDigestRejectsOversizedAndZeroProgressResources() throws Exception {
        String providerName = "com.example.dynamic.DynamicSink";
        Path classes = compileDynamicSink(
                providerName, "hostile-legacy-class-resource", "hostile-resource");
        byte[] providerBytes = Files.readAllBytes(
                classes.resolve(providerName.replace('.', '/') + ".class"));
        Path serviceFile = tempDirectory.resolve("hostile-legacy-service.txt");
        Files.writeString(serviceFile, providerName, StandardCharsets.UTF_8);

        ClassLoader oversized = new HostileClassResourceLoader(
                getClass().getClassLoader(), providerName, providerBytes,
                serviceFile.toUri().toURL(), () -> new InputStream() {
                    private long remaining = PluginCatalogBuilder.MAX_LEGACY_CLASS_BYTES + 1L;

                    @Override
                    public int read(byte[] bytes, int offset, int length) {
                        if (remaining == 0) {
                            return -1;
                        }
                        int read = (int) Math.min(length, remaining);
                        remaining -= read;
                        return read;
                    }

                    @Override
                    public int read() {
                        if (remaining == 0) {
                            return -1;
                        }
                        remaining--;
                        return 0;
                    }
                });
        assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                PluginsOptions.defaults(), oversized, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy provider class resource exceeds ")
                .hasMessageContaining(
                        Integer.toString(PluginCatalogBuilder.MAX_LEGACY_CLASS_BYTES));

        ClassLoader zeroProgress = new HostileClassResourceLoader(
                getClass().getClassLoader(), providerName, providerBytes,
                serviceFile.toUri().toURL(), () -> new InputStream() {
                    @Override
                    public int read(byte[] bytes, int offset, int length) {
                        return 0;
                    }

                    @Override
                    public int read() {
                        return 0;
                    }
                });
        assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                PluginsOptions.defaults(), zeroProgress, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy provider class resource made no read progress");
    }

    @Test
    void directoryJarCountIsBoundedBeforeAnyPrivateSnapshotIsCreated() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("too-many-plugin-jars"));
        for (int index = 0; index <= PluginLoaderHandle.MAX_PLUGIN_JARS; index++) {
            Files.createFile(directory.resolve("%04d.jar".formatted(index)));
        }

        assertThatThrownBy(() -> PluginLoaderHandle.directory(
                directory, getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than " + PluginLoaderHandle.MAX_PLUGIN_JARS + " JARs");
    }

    @Test
    void directoryTotalEntryTraversalIsBounded() throws Exception {
        Path directory = Files.createDirectory(
                tempDirectory.resolve("too-many-plugin-directory-entries"));
        for (int index = 0;
             index <= PluginLoaderHandle.MAX_PLUGIN_DIRECTORY_ENTRIES;
             index++) {
            Files.createFile(directory.resolve("entry-%05d.txt".formatted(index)));
        }

        assertThatThrownBy(() -> PluginLoaderHandle.directory(
                directory, getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too many entries")
                .hasMessageContaining(Integer.toString(
                        PluginLoaderHandle.MAX_PLUGIN_DIRECTORY_ENTRIES));
    }

    @Test
    void aggregateDirectorySnapshotBytesAreBounded() {
        assertThatThrownBy(() -> PluginLoaderHandle.validateSnapshotBudget(
                1, PluginLoaderHandle.MAX_PLUGIN_SNAPSHOT_BYTES + 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aggregate snapshot limit")
                .hasMessageContaining(Long.toString(
                        PluginLoaderHandle.MAX_PLUGIN_SNAPSHOT_BYTES));
    }

    @Test
    void staleLeasedSnapshotIsReclaimedButYoungAndUnmarkedDirectoriesAreUntouched()
            throws Exception {
        Path orphan = Files.createTempDirectory(PluginLoaderHandle.SNAPSHOT_DIRECTORY_PREFIX);
        Path orphanLease = orphan.resolve(PluginLoaderHandle.SNAPSHOT_LEASE_FILE);
        Files.writeString(orphanLease, PluginLoaderHandle.SNAPSHOT_LEASE_MARKER,
                StandardCharsets.US_ASCII);
        Files.writeString(orphan.resolve("00000.jar"), "orphan", StandardCharsets.US_ASCII);
        Path young = Files.createTempDirectory(PluginLoaderHandle.SNAPSHOT_DIRECTORY_PREFIX);
        Path youngLease = young.resolve(PluginLoaderHandle.SNAPSHOT_LEASE_FILE);
        Files.writeString(youngLease, PluginLoaderHandle.SNAPSHOT_LEASE_MARKER,
                StandardCharsets.US_ASCII);
        Path unmarked = Files.createTempDirectory(PluginLoaderHandle.SNAPSHOT_DIRECTORY_PREFIX);
        FileTime stale = FileTime.from(Instant.now()
                .minus(PluginLoaderHandle.SNAPSHOT_ORPHAN_GRACE).minusSeconds(60));
        Files.setLastModifiedTime(orphan, stale);
        Files.setLastModifiedTime(unmarked, stale);

        try {
            assertThat(PluginLoaderHandle.cleanupOrphanedSnapshotDirectories())
                    .isGreaterThanOrEqualTo(1);
            assertThat(orphan).doesNotExist();
            assertThat(young).exists();
            assertThat(unmarked).exists();
        } finally {
            Files.deleteIfExists(youngLease);
            Files.deleteIfExists(young);
            Files.deleteIfExists(unmarked);
        }
    }

    @Test
    void activeSnapshotLeasePreventsOrphanReclamation() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("active-snapshot-plugin"));
        writeJar(directory.resolve("active.jar"), Map.of("payload.txt", new byte[]{1}));
        PluginLoaderHandle handle = PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        Files.setLastModifiedTime(snapshotDirectory, FileTime.from(Instant.now()
                .minus(PluginLoaderHandle.SNAPSHOT_ORPHAN_GRACE).minusSeconds(60)));

        try {
            PluginLoaderHandle.cleanupOrphanedSnapshotDirectories();
            assertThat(snapshotDirectory).exists();
            assertThat(handle.classLoader()).isSameAs(getClass().getClassLoader());
            assertThat(handle.classLoader().getResource("payload.txt")).isNull();
        } finally {
            handle.close();
        }
        assertThat(snapshotDirectory).doesNotExist();
    }

    @Test
    void packagedAndNativeModesRequireExactlyOneEmbeddedAggregateIndex() throws Exception {
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.packagedClasspath(loader)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PACKAGED_JVM requires exactly one")
                    .hasMessageContaining(PluginIndex.RESOURCE_PATH);
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.nativeClasspath(loader)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NATIVE requires exactly one")
                    .hasMessageContaining(PluginIndex.RESOURCE_PATH);
        }
    }

    @Test
    void multipleEmbeddedAggregateIndexesAreAlwaysRejected() throws Exception {
        byte[] emptyIndex = emptyIndexBytes();
        Path first = tempDirectory.resolve("first-index.jar");
        Path second = tempDirectory.resolve("second-index.jar");
        writeJar(first, Map.of(PluginIndex.RESOURCE_PATH, emptyIndex));
        writeJar(second, Map.of(PluginIndex.RESOURCE_PATH, emptyIndex));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{first.toUri().toURL(), second.toUri().toURL()},
                getClass().getClassLoader())) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Multiple embedded plugin aggregate indexes")
                    .hasMessageContaining("exactly one authoritative index");
        }
    }

    @Test
    void embeddedIndexDiscoveryStopsAtSecondResourceFromInfiniteEnumeration()
            throws Exception {
        URL marker = tempDirectory.resolve("never-opened-index.json").toUri().toURL();
        AtomicInteger nextCalls = new AtomicInteger();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (!PluginIndex.RESOURCE_PATH.equals(name)) {
                    return super.getResources(name);
                }
                return new Enumeration<>() {
                    @Override public boolean hasMoreElements() { return true; }
                    @Override public URL nextElement() {
                        nextCalls.incrementAndGet();
                        return marker;
                    }
                };
            }
        };

        assertThatThrownBy(() -> PluginRuntimeEnvironment.classpath(
                PluginsOptions.defaults(), loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple embedded plugin aggregate indexes");
        assertThat(nextCalls).hasValue(1);
    }

    @Test
    void authoritativeIndexNeverDowngradesUnindexedProviderToLegacy() throws Exception {
        Path jar = tempDirectory.resolve("unindexed-provider.jar");
        writeJar(jar, Map.of(
                PluginIndex.RESOURCE_PATH, emptyIndexBytes(),
                NODE_PLUGIN_SERVICE,
                LegacyClosingPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LegacyClosingPlugin.class.getName())
                    .hasMessageContaining("absent from the authoritative plugin aggregate index")
                    .hasMessageContaining("do not synthesize legacy entries");
        }
    }

    @Test
    void providerCannotBeManifestedInOneInputAndLegacyInAnother() throws Exception {
        Path jar = tempDirectory.resolve("manifested-legacy-overlap.jar");
        writeJar(jar, Map.of(SINK_SERVICE,
                OpaqueVersionDependentSink.class.getName().getBytes(StandardCharsets.UTF_8)));
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                OpaqueVersionDependentSink.ID,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1),
                List.of(),
                List.of(new BundleContribution(ContributionKind.FINALIZED_SINK,
                        OpaqueVersionDependentSink.SCHEME,
                        OpaqueVersionDependentSink.class.getName())));
        PluginIndex manifested = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(new IndexedBundle(manifest, digest('e'), PluginDigestMode.JAR)),
                List.of());
        PluginIndex legacy = new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(new IndexedLegacyProvider(
                        ContributionKind.FINALIZED_SINK,
                        OpaqueVersionDependentSink.class.getName(),
                        digest('f'), PluginDigestMode.LEGACY_CLASS)));
        List<PluginCatalogBuilder.CatalogInput> manifestedFirst = List.of(
                new PluginCatalogBuilder.CatalogInput(
                        manifested, PluginSourceCategory.CLASSPATH),
                new PluginCatalogBuilder.CatalogInput(
                        legacy, PluginSourceCategory.CLASSPATH));
        List<PluginCatalogBuilder.CatalogInput> legacyFirst = List.of(
                manifestedFirst.get(1), manifestedFirst.get(0));

        for (List<PluginCatalogBuilder.CatalogInput> inputs
                : List.of(manifestedFirst, legacyFirst)) {
            try (URLClassLoader loader = new ServiceOnlyClassLoader(
                    new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
                assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                        PluginsOptions.defaults(), loader, inputs, false))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(OpaqueVersionDependentSink.class.getName())
                        .hasMessageContaining("both manifested and legacy");
            }
        }
    }

    @Test
    void policyFilteredManifestProviderDeclarationsRemainGloballyUnique() throws Exception {
        String provider = "com.example.filtered.SharedProvider";
        String firstId = "com.example.filtered.first";
        String secondId = "com.example.filtered.second";
        Path firstArtifact = Files.writeString(tempDirectory.resolve("filtered-first.jar"), "first");
        Path secondArtifact = Files.writeString(tempDirectory.resolve("filtered-second.jar"), "second");
        PluginCatalogBuilder.CatalogInput first = new PluginCatalogBuilder.CatalogInput(
                singleSinkIndex(firstId, "first", provider, '1'),
                PluginSourceCategory.DIRECTORY, firstArtifact);
        PluginCatalogBuilder.CatalogInput second = new PluginCatalogBuilder.CatalogInput(
                singleSinkIndex(secondId, "second", provider, '2'),
                PluginSourceCategory.DIRECTORY, secondArtifact);
        PluginsOptions filtered = new PluginsOptions(
                true, false, Set.of(), Set.of(firstId, secondId), Map.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            for (List<PluginCatalogBuilder.CatalogInput> inputs
                    : List.of(List.of(first, second), List.of(second, first))) {
                assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                        filtered, loader, inputs, false))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(provider)
                        .hasMessageContaining("more than one manifest contribution");
            }
        }
    }

    @Test
    void policyFilteredManifestLegacyCollisionIsRejectedInEitherInputOrder()
            throws Exception {
        String provider = "com.example.filtered.LegacyOverlap";
        String bundleId = "com.example.filtered.manifest";
        Path artifact = Files.writeString(tempDirectory.resolve("filtered-manifest.jar"), "manifest");
        PluginCatalogBuilder.CatalogInput manifested = new PluginCatalogBuilder.CatalogInput(
                singleSinkIndex(bundleId, "manifest", provider, '3'),
                PluginSourceCategory.DIRECTORY, artifact);
        PluginIndex legacyIndex = new PluginIndex(
                PluginIndex.CURRENT_SCHEMA_VERSION, List.of(),
                List.of(new IndexedLegacyProvider(
                        ContributionKind.FINALIZED_SINK, provider,
                        digest('4'), PluginDigestMode.LEGACY_CLASS)));
        PluginCatalogBuilder.CatalogInput legacy = new PluginCatalogBuilder.CatalogInput(
                legacyIndex, PluginSourceCategory.CLASSPATH);
        PluginsOptions filtered = new PluginsOptions(
                true, false, Set.of(), Set.of(bundleId), Map.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            for (List<PluginCatalogBuilder.CatalogInput> inputs
                    : List.of(List.of(manifested, legacy), List.of(legacy, manifested))) {
                assertThatThrownBy(() -> new PluginCatalogBuilder().build(
                        filtered, loader, inputs, false))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(provider)
                        .hasMessageContaining("both manifested and legacy");
            }
        }
    }

    @Test
    void libraryCompatibilityMayCarryExplicitLegacyButStrictModesRejectIt() throws Exception {
        Path jar = tempDirectory.resolve("indexed-legacy-provider.jar");
        byte[] index = ("""
                {
                  "schemaVersion": 1,
                  "bundles": [],
                  "legacyProviders": [{
                    "kind": "node-plugin",
                    "provider": "%s",
                    "digest": "%s",
                    "digestMode": "LEGACY_CLASS"
                  }]
                }
                """.formatted(LegacyClosingPlugin.class.getName(), digest('d')))
                .getBytes(StandardCharsets.UTF_8);
        writeJar(jar, Map.of(
                PluginIndex.RESOURCE_PATH, index,
                NODE_PLUGIN_SERVICE,
                LegacyClosingPlugin.class.getName().getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.classpath(
                    PluginsOptions.defaults(), loader)) {
                assertThat(environment.catalog().bundles()).singleElement()
                        .satisfies(bundle -> {
                            assertThat(bundle.id()).isEqualTo("com.example.legacy-close");
                            assertThat(bundle.legacy()).isTrue();
                        });
            }
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.packagedClasspath(loader)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PACKAGED_JVM")
                    .hasMessageContaining("legacy providers");
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.nativeClasspath(loader)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NATIVE")
                    .hasMessageContaining("legacy providers");
        }
    }

    @Test
    void strictModesRequireArtifactClosureEvidenceForEveryManifestedBundle() throws Exception {
        String bundleId = "com.example.weak-evidence";
        Path jar = tempDirectory.resolve("weak-manifested-index.jar");
        PluginIndex weakIndex = new PluginIndex(
                PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedNodeBundle(
                        bundleId, LegacyClosingPlugin.class, 'd')),
                List.of());
        writeJar(jar, Map.of(
                PluginIndex.RESOURCE_PATH, new PluginIndexCodec().write(weakIndex)));

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.packagedClasspath(loader)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PACKAGED_JVM")
                    .hasMessageContaining("ARTIFACT_CLOSURE")
                    .hasMessageContaining(bundleId);
            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.nativeClasspath(loader)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NATIVE")
                    .hasMessageContaining("ARTIFACT_CLOSURE")
                    .hasMessageContaining(bundleId);
        }
    }

    @Test
    void packagedDirectoryStillRequiresTheAuthoritativeBaseIndex() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("packaged-plugins"));
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader())) {
            PluginLoaderHandle handle = PluginLoaderHandle.packagedDirectory(directory, loader);

            assertThatThrownBy(() -> PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), handle))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PACKAGED_JVM requires exactly one");
        }
    }

    @Test
    void strictPackagedDirectoryLoadsManifestedBundleRepeatablyFromAuthoritativeBaseIndex()
            throws Exception {
        String bundleId = "com.example.strict-directory";
        String scheme = "strict-directory";
        String providerName = "com.example.dynamic.PackagedDirectorySink";
        String providerResource = providerName.replace('.', '/') + ".class";
        Path classes = compilePackagedDirectorySink(providerName, scheme);
        Path pluginDirectory = Files.createDirectory(
                tempDirectory.resolve("strict-packaged-plugins"));
        Path pluginJar = pluginDirectory.resolve("strict-directory.jar");
        Map<String, byte[]> pluginEntries = new LinkedHashMap<>();
        pluginEntries.put(providerResource,
                Files.readAllBytes(classes.resolve(providerResource)));
        pluginEntries.put(SINK_SERVICE, providerName.getBytes(StandardCharsets.UTF_8));
        pluginEntries.put(manifestPath(bundleId), manifest(bundleId, scheme, providerName));
        pluginEntries.put("strict-directory-marker.txt", new byte[]{1});
        writeJar(pluginJar, pluginEntries);

        Path baseJar = tempDirectory.resolve("strict-authoritative-base.jar");
        writeJar(baseJar, Map.of(PluginIndex.RESOURCE_PATH, emptyIndexBytes()));
        String firstFingerprint = null;
        try (URLClassLoader baseLoader = new ServiceOnlyClassLoader(
                new URL[]{baseJar.toUri().toURL()}, getClass().getClassLoader())) {
            for (int run = 1; run <= 2; run++) {
                Path useMarker = tempDirectory.resolve("strict-use-" + run + ".txt");
                Path closeMarker = tempDirectory.resolve("strict-close-" + run + ".txt");
                PluginLoaderHandle handle = PluginLoaderHandle.packagedDirectory(
                        pluginDirectory, baseLoader);
                assertThat(handle.discoveryMode()).isEqualTo(PluginDiscoveryMode.PACKAGED_JVM);
                assertThat(handle.classLoader()).isSameAs(baseLoader);
                assertThat(handle.classLoader().getResource("strict-directory-marker.txt")).isNull();

                try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                        PluginsOptions.defaults(), handle)) {
                    assertThat(environment.classLoader()).isNotSameAs(baseLoader);
                    assertThat(environment.classLoader()
                            .getResource("strict-directory-marker.txt")).isNotNull();
                    assertThat(environment.selectedBundleIds()).containsExactly(bundleId);
                    assertThat(environment.selectedBundleOrder()).containsExactly(bundleId);
                    assertThat(environment.catalog().bundles()).singleElement()
                            .satisfies(bundle -> {
                                assertThat(bundle.id()).isEqualTo(bundleId);
                                assertThat(bundle.source())
                                        .isEqualTo(PluginSourceCategory.DIRECTORY);
                                assertThat(bundle.digestMode()).isEqualTo(PluginDigestMode.JAR);
                                assertThat(bundle.legacy()).isFalse();
                            });
                    assertThat(environment.catalog().fingerprint())
                            .matches("sha256:[0-9a-f]{64}");
                    if (firstFingerprint == null) {
                        firstFingerprint = environment.catalog().fingerprint();
                    } else {
                        assertThat(environment.catalog().fingerprint())
                                .isEqualTo(firstFingerprint);
                    }

                    FinalizedStreamSinkFactory provider = environment.providers()
                            .find(FinalizedStreamSinkFactory.class, scheme)
                            .orElseThrow();
                    assertThat(provider.scheme()).isEqualTo(scheme);
                    assertThat(provider.create("strict-chain", Map.of(
                            "use-marker", useMarker.toString(),
                            "close-marker", closeMarker.toString())))
                            .isEmpty();
                    assertThat(Files.readString(useMarker)).isEqualTo("strict-chain");
                }

                assertThat(Files.readString(closeMarker)).isEqualTo("closed");
                assertThat(handle.classLoader()
                        .getResource("strict-directory-marker.txt")).isNull();
            }
        }
    }

    @Test
    void disabledPluginsDoNotRequirePackagedIndex() throws Exception {
        PluginsOptions disabled = new PluginsOptions(
                false, false, Set.of(), Set.of(), Map.of());

        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[0], getClass().getClassLoader());
             PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                     disabled, PluginLoaderHandle.packagedClasspath(loader))) {
            assertThat(environment.catalog().bundles()).isEmpty();
        }
    }

    private static CatalogPluginProviderRegistry.Entry entry(
            String bundleId, ClosingSinkFactory provider) {
        return new CatalogPluginProviderRegistry.Entry(
                bundleId, ContributionKind.FINALIZED_SINK, provider.scheme(),
                provider.getClass().getName(), null, () -> provider);
    }

    private static CatalogPluginProviderRegistry.Entry sinkEntry(
            String bundleId,
            FinalizedStreamSinkFactory provider
    ) {
        return new CatalogPluginProviderRegistry.Entry(
                bundleId, ContributionKind.FINALIZED_SINK, provider.scheme(),
                provider.getClass().getName(), null, () -> provider);
    }

    private static CatalogPluginProviderRegistry nodePluginRegistry(TestNodePlugin... plugins) {
        List<CatalogPluginProviderRegistry.Entry> entries = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (TestNodePlugin plugin : plugins) {
            entries.add(new CatalogPluginProviderRegistry.Entry(
                    plugin.id(), ContributionKind.NODE_PLUGIN, plugin.id(),
                    plugin.getClass().getName(), null, () -> plugin));
            order.add(plugin.id());
        }
        return new CatalogPluginProviderRegistry(entries, order, List.of());
    }

    private static IndexedBundle indexedNodeBundle(
            String id, Class<? extends NodePlugin> provider, char digestCharacter) {
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                id,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1),
                List.of(),
                List.of(new BundleContribution(
                        ContributionKind.NODE_PLUGIN, id, provider.getName())));
        return new IndexedBundle(manifest, digest(digestCharacter), PluginDigestMode.JAR);
    }

    private static PluginIndex singleSinkIndex(
            String id,
            String selector,
            String provider,
            char digestCharacter
    ) {
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                id,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1),
                List.of(),
                List.of(new BundleContribution(
                        ContributionKind.FINALIZED_SINK, selector, provider)));
        return new PluginIndex(
                PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(new IndexedBundle(
                        manifest, digest(digestCharacter), PluginDigestMode.JAR)),
                List.of());
    }

    private static String digest(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private PluginLoaderHandle directoryHandleWithIndexEnumerationFailure(
            String name,
            Throwable failure
    ) throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve(name));
        writeJar(directory.resolve("captured.jar"), Map.of("marker.txt", new byte[]{1}));
        ClassLoader parent = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String resource) throws IOException {
                if (!PluginIndex.RESOURCE_PATH.equals(resource)) {
                    return super.getResources(resource);
                }
                return new Enumeration<>() {
                    @Override public boolean hasMoreElements() {
                        PluginCatalogRuntimeTest.<RuntimeException>sneakyThrow(failure);
                        return false;
                    }
                    @Override public URL nextElement() {
                        throw new NoSuchElementException();
                    }
                };
            }
        };
        return PluginLoaderHandle.directory(directory, parent);
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void sneakyThrow(Throwable failure) throws X {
        throw (X) failure;
    }

    private static String bracketedBundleConfigKey(String bundleId, String key) {
        return "yano.plugins.bundle[" + bundleId + "]." + key;
    }

    private static String quotedBundleConfigKey(String bundleId, String key) {
        return "yano.plugins.bundle.\"" + bundleId + "\"." + key;
    }

    private static String environmentBundleConfigKey(String bundleId, String key) {
        return "YANO_PLUGINS_BUNDLE_HEX__"
                + java.util.HexFormat.of().formatHex(
                        bundleId.getBytes(StandardCharsets.UTF_8)).toUpperCase(Locale.ROOT)
                + "__" + key;
    }

    private static String lossyEnvironmentBundleConfigKey(String bundleId, String key) {
        return "YANO_PLUGINS_BUNDLE__"
                + bundleId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_")
                + "__" + key;
    }

    private DirectoryProjection observeDirectoryProjection(
            Path directory,
            PluginsOptions options,
            ClassLoader parent,
            String selectedId,
            String filteredId,
            PluginSelectionStatus filteredStatus,
            String selectedProvider,
            Path selectedSource
    ) throws Exception {
        PluginLoaderHandle handle = PluginLoaderHandle.directory(directory, parent);
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        assertThat(handle.classLoader()).isSameAs(parent);
        assertThat(handle.hostClassLoader()).isSameAs(parent);
        assertThat(handle.classLoader().getResource("collision-marker.txt")).isNull();
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(options, handle)) {
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(parent);
            assertThat(environment.catalog().bundles())
                    .filteredOn(bundle -> bundle.id().equals(selectedId))
                    .singleElement()
                    .satisfies(bundle -> assertThat(bundle.selectionStatus())
                            .isEqualTo(PluginSelectionStatus.SELECTED));
            if (filteredId != null) {
                assertThat(environment.catalog().bundles())
                        .filteredOn(bundle -> bundle.id().equals(filteredId))
                        .singleElement()
                        .satisfies(bundle -> assertThat(bundle.selectionStatus())
                                .isEqualTo(filteredStatus));
            }

            URLClassLoader executable = (URLClassLoader) environment.classLoader();
            assertThat(executable.getURLs()).hasSize(1);
            Path executableArtifact = Path.of(executable.getURLs()[0].toURI()).toRealPath();
            assertThat(artifactDigest(executableArtifact)).isEqualTo(artifactDigest(selectedSource));
            assertThat(executableArtifact.getFileName().toString())
                    .isEqualTo(artifactDigest(selectedSource) + ".jar");
            Class<?> selectedType = executable.loadClass(selectedProvider);
            assertThat(Path.of(selectedType.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toRealPath()).isEqualTo(executableArtifact);
            if (filteredId != null) {
                assertThatThrownBy(() -> executable.loadClass(
                        "com.example.collision.DeniedSink"))
                        .isInstanceOf(ClassNotFoundException.class);
            }
            List<URL> ordinaryResources = Collections.list(
                    executable.getResources("collision-marker.txt"));
            assertThat(ordinaryResources).singleElement()
                    .satisfies(resource -> assertThat(resource.toExternalForm())
                            .contains(executableArtifact.getFileName().toString()));
            try (InputStream marker = ordinaryResources.getFirst().openStream()) {
                assertThat(new String(marker.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("selected");
            }
            List<URL> serviceResources = Collections.list(
                    executable.getResources(SINK_SERVICE));
            assertThat(serviceResources).singleElement()
                    .satisfies(resource -> assertThat(resource.toExternalForm())
                            .contains(executableArtifact.getFileName().toString()));
            try (InputStream service = serviceResources.getFirst().openStream()) {
                assertThat(new String(service.readAllBytes(), StandardCharsets.UTF_8).trim())
                        .isEqualTo(selectedProvider);
            }
            try (InputStream marker = executable.getResourceAsStream("collision-marker.txt")) {
                assertThat(marker).isNotNull();
                assertThat(new String(marker.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("selected");
            }

            FinalizedStreamSinkFactory factory = environment.providers()
                    .find(FinalizedStreamSinkFactory.class, "selected")
                    .orElseThrow();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(parent);
            assertThat(factory.getClass().getClassLoader()).isNotSameAs(executable);
            FinalizedStreamSink sink = factory.create("chain", Map.of()).getFirst();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(parent);
            String sinkId = sink.id();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(parent);
            assertThat(sink.deliver(null)).isTrue();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(parent);
            return new DirectoryProjection(
                    environment.catalog().fingerprint(), sinkId,
                    executableArtifact.getFileName().toString());
        } finally {
            assertThat(snapshotDirectory).doesNotExist();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(parent);
        }
    }

    private Path compileCollisionSink(
            String providerName,
            String fixture,
            String scheme,
            String helperValue,
            boolean rejectConstruction
    ) throws Exception {
        int separator = providerName.lastIndexOf('.');
        String packageName = providerName.substring(0, separator);
        String simpleName = providerName.substring(separator + 1);
        Path sourceRoot = Files.createDirectories(tempDirectory.resolve(fixture + "-source"));
        Path classes = Files.createDirectories(tempDirectory.resolve(fixture + "-classes"));
        Path providerSource = sourceRoot.resolve(providerName.replace('.', '/') + ".java");
        Path helperSource = sourceRoot.resolve(packageName.replace('.', '/') + "/Shared.java");
        Files.createDirectories(providerSource.getParent());
        Files.writeString(providerSource, """
                package %s;
                import com.bloxbean.cardano.yano.api.appchain.AppBlock;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;
                import java.util.Map;
                public final class %s implements FinalizedStreamSinkFactory {
                    %s
                    public %s() {
                        verifyTccl();
                        %s
                    }
                    public String scheme() {
                        verifyTccl();
                        return "%s";
                    }
                    public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        verifyTccl();
                        return List.of(new FinalizedStreamSink() {
                            public String id() {
                                verifyTccl();
                                return observation();
                            }
                            public boolean deliver(AppBlock block) {
                                verifyTccl();
                                return true;
                            }
                        });
                    }
                    private static void verifyTccl() {
                        if (Thread.currentThread().getContextClassLoader()
                                != %s.class.getClassLoader()) {
                            throw new AssertionError("plugin callback TCCL mismatch");
                        }
                    }
                    private static String observation() {
                        try (InputStream input = %s.class.getClassLoader()
                                .getResourceAsStream("collision-marker.txt")) {
                            if (input == null) throw new AssertionError("missing collision marker");
                            String resource = new String(
                                    input.readAllBytes(), StandardCharsets.UTF_8);
                            String codeSourceLeaf = Path.of(%s.class.getProtectionDomain()
                                    .getCodeSource().getLocation().toURI())
                                    .getFileName().toString();
                            return Shared.value() + ":" + resource + ":" + codeSourceLeaf;
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                }
                """.formatted(packageName, simpleName,
                rejectConstruction
                        ? """
                          static {
                              try {
                                  Files.writeString(Path.of("%s"), "initialized");
                              } catch (Exception failure) {
                                  throw new ExceptionInInitializerError(failure);
                              }
                          }
                          """.formatted(javaStringLiteral(tempDirectory.resolve(
                                fixture + "-clinit.marker").toString()))
                        : "",
                simpleName,
                rejectConstruction
                        ? "throw new AssertionError(\"filtered provider was constructed\");"
                        : "",
                scheme, simpleName, simpleName, simpleName));
        Files.writeString(helperSource, """
                package %s;
                public final class Shared {
                    private Shared() { }
                    public static String value() { return "%s"; }
                }
                """.formatted(packageName, helperValue));
        String apiPath = Path.of(FinalizedStreamSinkFactory.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", apiPath, "-d", classes.toString(),
                providerSource.toString(), helperSource.toString());
        assertThat(result).isZero();
        return classes;
    }

    private static String javaStringLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeCollisionJar(
            Path jar,
            Path classes,
            String bundleId,
            String scheme,
            String provider,
            String resourceValue,
            List<String> dependencies,
            int padding
    ) throws IOException {
        Map<String, byte[]> entries = classEntries(classes);
        entries.put(SINK_SERVICE, provider.getBytes(StandardCharsets.UTF_8));
        entries.put(manifestPath(bundleId), manifest(
                bundleId, scheme, provider, dependencies));
        entries.put("collision-marker.txt", resourceValue.getBytes(StandardCharsets.UTF_8));
        entries.put("padding.bin", Integer.toString(padding).getBytes(StandardCharsets.US_ASCII));
        writeJar(jar, entries);
    }

    private static Map<String, byte[]> classEntries(Path classes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<Path> classFiles;
        try (var paths = Files.walk(classes)) {
            classFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path classFile : classFiles) {
            entries.put(classes.relativize(classFile).toString().replace('\\', '/'),
                    Files.readAllBytes(classFile));
        }
        return entries;
    }

    private Path compileDynamicSink(String providerName, String fixture, String marker)
            throws Exception {
        Path sourceRoot = Files.createDirectories(tempDirectory.resolve(fixture + "-source"));
        Path classes = Files.createDirectories(tempDirectory.resolve(fixture + "-classes"));
        Path source = sourceRoot.resolve(providerName.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.dynamic;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
                import java.util.List;
                import java.util.Map;
                public final class DynamicSink implements FinalizedStreamSinkFactory {
                    public String scheme() { return "dynamic"; }
                    public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
                        return List.of();
                    }
                    public String marker() { return "%s"; }
                }
                """.formatted(marker));
        String apiPath = Path.of(FinalizedStreamSinkFactory.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", apiPath, "-d", classes.toString(), source.toString());
        assertThat(result).isZero();
        return classes;
    }

    private Path compilePackagedDirectorySink(String providerName, String scheme)
            throws Exception {
        int separator = providerName.lastIndexOf('.');
        String packageName = providerName.substring(0, separator);
        String simpleName = providerName.substring(separator + 1);
        Path sourceRoot = Files.createDirectories(
                tempDirectory.resolve("strict-directory-source"));
        Path classes = Files.createDirectories(
                tempDirectory.resolve("strict-directory-classes"));
        Path source = sourceRoot.resolve(providerName.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package %s;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
                import java.io.IOException;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;
                import java.util.Map;
                public final class %s implements FinalizedStreamSinkFactory, AutoCloseable {
                    private String closeMarker;
                    public String scheme() { return "%s"; }
                    public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        try {
                            Files.writeString(Path.of(config.get("use-marker")), chainId);
                            closeMarker = config.get("close-marker");
                            return List.of();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    public void close() {
                        if (closeMarker == null) return;
                        try {
                            Files.writeString(Path.of(closeMarker), "closed");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """.formatted(packageName, simpleName, scheme));
        String apiPath = Path.of(FinalizedStreamSinkFactory.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", apiPath, "-d", classes.toString(), source.toString());
        assertThat(result).isZero();
        return classes;
    }

    private Path compileLazySnapshotSink(String fixture, String lazyMarker)
            throws Exception {
        String providerName = "com.example.snapshot.SnapshotSink";
        Path sourceRoot = Files.createDirectories(tempDirectory.resolve(fixture + "-source"));
        Path classes = Files.createDirectories(tempDirectory.resolve(fixture + "-classes"));
        Path providerSource = sourceRoot.resolve(providerName.replace('.', '/') + ".java");
        Path markerSource = sourceRoot.resolve("com/example/snapshot/LazyMarker.java");
        Files.createDirectories(providerSource.getParent());
        Files.writeString(providerSource, """
                package com.example.snapshot;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                import java.util.List;
                import java.util.Map;
                public final class SnapshotSink implements FinalizedStreamSinkFactory {
                    public String scheme() { return "snapshot"; }
                    public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of();
                    }
                    public String marker() {
                        try (InputStream input = getClass().getClassLoader()
                                .getResourceAsStream("snapshot-marker.txt")) {
                            if (input == null) throw new IllegalStateException("missing marker");
                            return LazyMarker.value() + ":"
                                    + new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """);
        Files.writeString(markerSource, """
                package com.example.snapshot;
                public final class LazyMarker {
                    private LazyMarker() {}
                    public static String value() { return "%s"; }
                }
                """.formatted(lazyMarker));
        String apiPath = Path.of(FinalizedStreamSinkFactory.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", apiPath, "-d", classes.toString(),
                providerSource.toString(), markerSource.toString());
        assertThat(result).isZero();
        return classes;
    }

    private Path legacySinkJar(String providerName, Path classes, String filename)
            throws IOException {
        Path jar = tempDirectory.resolve(filename);
        writeJar(jar, Map.of(
                providerName.replace('.', '/') + ".class",
                Files.readAllBytes(classes.resolve(providerName.replace('.', '/') + ".class")),
                SINK_SERVICE, providerName.getBytes(StandardCharsets.UTF_8)));
        return jar;
    }

    private PluginBundleInfo legacyBundle(Path jar) throws Exception {
        try (URLClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            PluginCatalogBuilder.BuildResult result = new PluginCatalogBuilder().build(
                    PluginsOptions.defaults(), loader, List.of());
            try {
                return result.catalog().bundles().getFirst();
            } finally {
                result.registry().close();
            }
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e);
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "Symbolic links cannot be created: " + e);
        }
    }

    private static void atomicReplace(Path replacement, Path target) throws IOException {
        try {
            Files.move(replacement, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Assumptions.assumeTrue(false, "Atomic replacement is unavailable: " + e);
        }
    }

    private static byte[] manifest(String id, String name, String provider) {
        return manifest(id, name, provider, List.of());
    }

    private static byte[] manifest(
            String id,
            String name,
            String provider,
            List<String> dependencies
    ) {
        String dependencyJson = dependencies.stream()
                .map(dependency -> "{\"id\":\"" + dependency + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return ("""
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "version": "1.0.0",
                  "yanoApi": {"min": 1, "max": 1},
                  "dependencies": [%s],
                  "contributions": [
                    {"kind": "finalized-sink", "name": "%s", "provider": "%s"}
                  ]
                }
                """.formatted(id, dependencyJson, name, provider))
                .getBytes(StandardCharsets.UTF_8);
    }

    private record DirectoryProjection(
            String fingerprint,
            String sinkId,
            String snapshotFileName
    ) {
    }

    private static PluginIndex deepCatalogIndex(boolean cyclic) {
        List<IndexedBundle> bundles = new ArrayList<>(PluginIndex.MAX_BUNDLES);
        for (int index = 0; index < PluginIndex.MAX_BUNDLES; index++) {
            List<BundleDependency> dependencies;
            if (index + 1 < PluginIndex.MAX_BUNDLES) {
                dependencies = List.of(new BundleDependency(
                        deepCatalogBundleId(index + 1), null, null));
            } else if (cyclic) {
                dependencies = List.of(new BundleDependency(
                        deepCatalogBundleId(0), null, null));
            } else {
                dependencies = List.of();
            }
            BundleManifest manifest = new BundleManifest(
                    BundleManifest.CURRENT_SCHEMA_VERSION,
                    deepCatalogBundleId(index),
                    SemVersion.parse("1.0.0"),
                    new YanoApiRange(1, 1),
                    dependencies,
                    List.of());
            bundles.add(new IndexedBundle(
                    manifest, digest('a'), PluginDigestMode.JAR));
        }
        return new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION, bundles, List.of());
    }

    private static String deepCatalogBundleId(int index) {
        return "com.example.deep.bundle%04d".formatted(index);
    }

    private static byte[] emptyIndexBytes() {
        return """
                {"schemaVersion":1,"bundles":[],"legacyProviders":[]}
                """.strip().getBytes(StandardCharsets.UTF_8);
    }

    private static String manifestPath(String id) {
        return "META-INF/yano/plugins/" + id + ".json";
    }

    private static String classEntry(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        try (InputStream input = type.getClassLoader().getResourceAsStream(classEntry(type))) {
            if (input == null) {
                throw new IOException("Could not locate test provider class bytes");
            }
            return input.readAllBytes();
        }
    }

    private static String artifactDigest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            throw new IllegalStateException("Test artifact could not be digested", e);
        }
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    public static final class ParentShadowSink implements FinalizedStreamSinkFactory {
        static final AtomicInteger constructorCalls = new AtomicInteger();

        public ParentShadowSink() {
            constructorCalls.incrementAndGet();
        }

        @Override
        public String scheme() {
            return "shadow";
        }

        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return List.of();
        }
    }

    public static final class FlakyLegacySinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        static final String INITIAL_SCHEME = "initial";
        static final AtomicInteger schemeCalls = new AtomicInteger();
        static final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String scheme() {
            return schemeCalls.incrementAndGet() == 1 ? INITIAL_SCHEME : "changed";
        }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    public static final class ReentrantLegacySinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private static final AtomicReference<PluginRuntimeEnvironment> environment =
                new AtomicReference<>();
        private static final AtomicReference<Throwable> reentryFailure =
                new AtomicReference<>();
        private static final AtomicInteger closeCalls = new AtomicInteger();

        static void reset() {
            environment.set(null);
            reentryFailure.set(null);
            closeCalls.set(0);
        }

        @Override
        public String scheme() {
            return "reentrant-provider-close";
        }

        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return List.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            PluginRuntimeEnvironment current = environment.get();
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (Throwable failure) {
                reentryFailure.set(failure);
            }
        }
    }

    public static final class MemoizedCloseFailureSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private static final AtomicReference<Error> closeFailure = new AtomicReference<>();
        private static final AtomicInteger closeCalls = new AtomicInteger();

        static void reset(Error failure) {
            closeFailure.set(failure);
            closeCalls.set(0);
        }

        @Override
        public String scheme() {
            return "memoized-close-failure";
        }

        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return List.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            Error failure = closeFailure.get();
            if (failure != null) {
                throw failure;
            }
        }
    }

    public static final class ReentrantCatalogNodePlugin implements NodePlugin {
        static final String ID = "com.example.reentrant-node-plugin";
        private static final AtomicReference<PluginRuntimeEnvironment> environment =
                new AtomicReference<>();
        private static final AtomicReference<Throwable> initFailure =
                new AtomicReference<>();
        private static final AtomicReference<Throwable> closeFailure =
                new AtomicReference<>();
        private static final AtomicInteger closeCalls = new AtomicInteger();

        static void reset() {
            environment.set(null);
            initFailure.set(null);
            closeFailure.set(null);
            closeCalls.set(0);
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public void init(PluginContext context) {
            attemptEnvironmentClose(initFailure);
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            attemptEnvironmentClose(closeFailure);
        }

        private static void attemptEnvironmentClose(AtomicReference<Throwable> failure) {
            PluginRuntimeEnvironment current = environment.get();
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (Throwable reentryFailure) {
                failure.set(reentryFailure);
            }
        }
    }

    public static final class LegacyClosingPlugin implements NodePlugin {
        static final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String id() {
            return "com.example.legacy-close";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public void init(PluginContext ctx) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    public static final class SecretSelectorLegacySinkFactory
            implements FinalizedStreamSinkFactory {
        @Override
        public String scheme() {
            throw new AssertionError(SECRET_CALLBACK_MESSAGE);
        }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of();
        }
    }

    public static final class SecretConstructorLegacySinkFactory
            implements FinalizedStreamSinkFactory {
        public SecretConstructorLegacySinkFactory() {
            throw new AssertionError(SECRET_CALLBACK_MESSAGE);
        }

        @Override
        public String scheme() {
            return "unreachable";
        }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of();
        }
    }

    public static final class SecretCleanupLegacyPlugin implements NodePlugin {
        private static final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String id() {
            return "com.example.secret-cleanup";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            throw new AssertionError(SECRET_CALLBACK_MESSAGE);
        }
    }

    public static final class OversizedIdLegacyPlugin implements NodePlugin {
        @Override public String id() { return "x".repeat(161); }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class OversizedVersionLegacyPlugin implements NodePlugin {
        @Override public String id() { return "com.example.oversized-version"; }
        @Override public String version() { return "v".repeat(129); }
        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class OversizedDependenciesLegacyPlugin implements NodePlugin {
        @Override public String id() { return "com.example.oversized-dependencies"; }
        @Override public String version() { return "1.0.0"; }

        @Override
        public Set<String> dependsOn() {
            return new AbstractSet<>() {
                @Override
                public Iterator<String> iterator() {
                    return new Iterator<>() {
                        private int next;
                        @Override public boolean hasNext() { return next < 257; }
                        @Override public String next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            return "com.example.dependency-" + next++;
                        }
                    };
                }

                @Override
                public int size() {
                    return 257;
                }
            };
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class SecretVersionLegacyPlugin implements NodePlugin {
        @Override
        public String id() {
            return "com.example.secret-version";
        }

        @Override
        public String version() {
            throw new IllegalStateException(SECRET_CALLBACK_MESSAGE);
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class StatefulSecretLegacySinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private static final String SCHEME = "stateful-secret";
        private static final AtomicInteger schemeCalls = new AtomicInteger();
        private static final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String scheme() {
            if (schemeCalls.incrementAndGet() == 1) {
                return SCHEME;
            }
            throw new AssertionError(SECRET_CALLBACK_MESSAGE);
        }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private static void reset() {
            schemeCalls.set(0);
            closeCalls.set(0);
        }
    }

    public static final class StatefulSecretLegacyPlugin implements NodePlugin {
        private static final String ID = "com.example.stateful-secret-plugin";
        private static final AtomicInteger idCalls = new AtomicInteger();
        private static final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String id() {
            if (idCalls.incrementAndGet() <= 3) {
                return ID;
            }
            throw new AssertionError(SECRET_CALLBACK_MESSAGE);
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private static void reset() {
            idCalls.set(0);
            closeCalls.set(0);
        }
    }

    public static final class StatefulOversizedIdLegacyPlugin implements NodePlugin {
        private static final String ID = "com.example.stateful-oversized-plugin";
        private static final AtomicInteger idCalls = new AtomicInteger();
        private static final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String id() {
            return idCalls.incrementAndGet() <= 3 ? ID : "x".repeat(161);
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private static void reset() {
            idCalls.set(0);
            closeCalls.set(0);
        }
    }

    public static final class LegacyCleanupAPlugin implements NodePlugin {
        @Override
        public String id() {
            return "com.example.legacy-cleanup-a";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }

        @Override
        public void close() {
            LegacyCleanupProbe.close("a", LegacyCleanupProbe.aCloseFailure);
        }
    }

    public static final class LegacyCleanupBPlugin implements NodePlugin {
        @Override
        public String id() {
            Error failure = LegacyCleanupProbe.bIdFailure;
            if (failure != null) {
                throw failure;
            }
            return "com.example.legacy-cleanup-b";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }

        @Override
        public void close() {
            LegacyCleanupProbe.close("b", LegacyCleanupProbe.bCloseFailure);
        }
    }

    private static final class LegacyCleanupProbe {
        private static final List<String> closeOrder = new ArrayList<>();
        private static Error aCloseFailure;
        private static Error bCloseFailure;
        private static Error bIdFailure;

        private static void close(String provider, Error failure) {
            closeOrder.add(provider);
            if (failure != null) {
                throw failure;
            }
        }

        private static void reset() {
            closeOrder.clear();
            aCloseFailure = null;
            bCloseFailure = null;
            bIdFailure = null;
        }
    }

    public static final class LegacyTcclCompanionPlugin implements NodePlugin {
        public LegacyTcclCompanionPlugin() {
            LegacyTcclPlugin.checkAndPoison("companion constructor");
        }

        @Override
        public String id() {
            LegacyTcclPlugin.checkAndPoison("companion id");
            return "com.example.tccl-companion";
        }

        @Override
        public String version() {
            LegacyTcclPlugin.checkAndPoison("companion version");
            return "1.0.0";
        }

        @Override
        public Set<String> dependsOn() {
            LegacyTcclPlugin.checkAndPoison("companion dependencies");
            return Set.of();
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }

        @Override
        public void close() {
            LegacyTcclPlugin.checkAndPoison("companion close");
            LegacyTcclPlugin.closeCalls.incrementAndGet();
        }
    }

    public static final class LegacyTcclPlugin implements NodePlugin {
        static final AtomicReference<ClassLoader> expectedLoader = new AtomicReference<>();
        static final AtomicReference<ClassLoader> rogueLoader = new AtomicReference<>();
        static final AtomicInteger callbackCalls = new AtomicInteger();
        static final AtomicInteger closeCalls = new AtomicInteger();

        public LegacyTcclPlugin() {
            checkAndPoison("primary constructor");
        }

        @Override
        public String id() {
            checkAndPoison("primary id");
            return "com.example.tccl-primary";
        }

        @Override
        public String version() {
            checkAndPoison("primary version");
            return "1.0.0";
        }

        @Override
        public Set<String> dependsOn() {
            checkAndPoison("primary dependencies");
            return new AbstractSet<>() {
                @Override
                public Iterator<String> iterator() {
                    checkAndPoison("dependency iterator");
                    return new Iterator<>() {
                        private boolean consumed;

                        @Override
                        public boolean hasNext() {
                            checkAndPoison("dependency hasNext");
                            return !consumed;
                        }

                        @Override
                        public String next() {
                            checkAndPoison("dependency next");
                            if (consumed) {
                                throw new NoSuchElementException();
                            }
                            consumed = true;
                            return "com.example.absent";
                        }
                    };
                }

                @Override
                public int size() {
                    checkAndPoison("dependency size");
                    return 1;
                }
            };
        }

        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }

        @Override
        public void close() {
            checkAndPoison("primary close");
            closeCalls.incrementAndGet();
        }

        static void checkAndPoison(String callback) {
            ClassLoader expected = expectedLoader.get();
            ClassLoader actual = Thread.currentThread().getContextClassLoader();
            if (actual != expected) {
                throw new AssertionError(callback + " expected plugin TCCL " + expected
                        + " but observed " + actual);
            }
            callbackCalls.incrementAndGet();
            Thread.currentThread().setContextClassLoader(rogueLoader.get());
        }

        static void reset() {
            expectedLoader.set(null);
            rogueLoader.set(null);
            callbackCalls.set(0);
            closeCalls.set(0);
        }
    }

    public static final class CatalogLegacyConfigPlugin implements NodePlugin {
        static final String ID = "com.example.catalog-legacy-config";
        static volatile PluginContext context;

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { context = ctx; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class LegacyDependencyPlugin implements NodePlugin {
        static final String ID = "com.example.z-dependency";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public void init(PluginContext ctx) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }
    }

    public static final class LegacyDependentPlugin implements NodePlugin {
        static final String ID = "com.example.a-dependent";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public Set<String> dependsOn() {
            return Set.of(LegacyDependencyPlugin.ID);
        }

        @Override
        public void init(PluginContext ctx) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }
    }

    public static final class ManifestedHeaderCustomizerPlugin
            implements NodePlugin, HeaderValidationCustomizer {
        private static final String ID = "com.example.manifested-header-customizer";

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public Set<PluginCapability> capabilities() {
            return Set.of(PluginCapability.HEADER_VALIDATION);
        }
        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
        @Override public void customize(HeaderValidationPipeline.Builder builder) { }
    }

    public static final class FirstConfigPlugin implements NodePlugin {
        static final String ID = "com.example.first-config";
        static volatile PluginContext context;

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { context = ctx; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class SecondConfigPlugin implements NodePlugin {
        static final String ID = "com.example.second-config";
        static volatile PluginContext context;

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { context = ctx; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class ParentConfigPlugin implements NodePlugin {
        static final String ID = "com.example.parent";
        static volatile PluginContext context;

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { context = ctx; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class ChildConfigPlugin implements NodePlugin {
        // Consecutive dashes normalize to `__`, which also resembles the
        // quoted-owner terminator for the shorter parent id.
        static final String ID = "com.example.parent--product";
        static volatile PluginContext context;

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { context = ctx; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class HyphenConfigPlugin implements NodePlugin {
        static final String ID = "com.example.config-collision";

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class DotConfigPlugin implements NodePlugin {
        static final String ID = "com.example.config.collision";

        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class OpaqueVersionLegacyPlugin implements NodePlugin {
        static final String ID = "com.example.opaque-version";

        @Override public String id() { return ID; }
        @Override public String version() { return "preview"; }
        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    public static final class OpaqueVersionDependentSink implements FinalizedStreamSinkFactory {
        static final String ID = "com.example.version-dependent";
        static final String SCHEME = "version-dependent";

        @Override public String scheme() { return SCHEME; }
        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return List.of();
        }
    }

    private static final class CapturingLegacyPlugin implements NodePlugin {
        private PluginContext context;

        @Override public String id() { return "com.example.capturing-legacy"; }
        @Override public String version() { return "legacy-preview"; }
        @Override public void init(PluginContext ctx) { context = ctx; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    private static final class ClosingNodePlugin implements NodePlugin {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String id() {
            return "com.example.closing";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public void init(PluginContext ctx) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class TestNodePlugin implements NodePlugin {
        private final String id;
        private final boolean failInit;
        private final boolean invalidCapabilities;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TestNodePlugin(String id, boolean failInit, boolean invalidCapabilities) {
            this.id = id;
            this.failInit = failInit;
            this.invalidCapabilities = invalidCapabilities;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public Set<PluginCapability> capabilities() {
            return invalidCapabilities ? null : Set.of(PluginCapability.EVENT_CONSUMER);
        }

        @Override
        public void init(PluginContext ctx) {
            if (failInit) {
                throw new IllegalStateException("expected init failure");
            }
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class CatalogOrderNodePlugin implements NodePlugin {
        private final String id;
        private final Set<String> dependencies;
        private final List<String> initialized;

        private CatalogOrderNodePlugin(
                String id,
                Set<String> dependencies,
                List<String> initialized
        ) {
            this.id = id;
            this.dependencies = dependencies;
            this.initialized = initialized;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public Set<String> dependsOn() {
            return dependencies;
        }

        @Override
        public void init(PluginContext ctx) {
            initialized.add(id);
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }
    }

    private static final class ClosingSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private final String name;
        private final List<String> closes;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private ClosingSinkFactory(String name, List<String> closes) {
            this.name = name;
            this.closes = closes;
        }

        @Override
        public String scheme() {
            return name;
        }

        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return List.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (closes != null) {
                closes.add(name);
            }
        }
    }

    private static final class SelfReturningSinkFactory
            implements FinalizedStreamSinkFactory, FinalizedStreamSink {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public String scheme() {
            return "self-returning";
        }

        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return List.of(this);
        }

        @Override
        public String id() {
            return "self-returning-product";
        }

        @Override
        public boolean deliver(com.bloxbean.cardano.yano.api.appchain.AppBlock block) {
            return true;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class FixedProductSinkFactory
            implements FinalizedStreamSinkFactory {
        private final FinalizedStreamSink product;

        private FixedProductSinkFactory(FinalizedStreamSink product) {
            this.product = product;
        }

        @Override
        public String scheme() {
            return "fixed-product";
        }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of(product);
        }
    }

    private static final class CloseProbeSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private final String name;
        private final AtomicInteger closeCalls;
        private final Error closeFailure;

        private CloseProbeSinkFactory(
                String name,
                AtomicInteger closeCalls,
                Error closeFailure
        ) {
            this.name = name;
            this.closeCalls = closeCalls;
            this.closeFailure = closeFailure;
        }

        @Override
        public String scheme() {
            return name;
        }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class FatalPluginError extends Error {
        private FatalPluginError(String message) {
            super(message);
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }

    private static final class HostileClassResourceLoader extends ClassLoader {
        private final String providerName;
        private final String providerResource;
        private final byte[] providerBytes;
        private final URL serviceResource;
        private final java.util.function.Supplier<InputStream> classResource;

        private HostileClassResourceLoader(
                ClassLoader parent,
                String providerName,
                byte[] providerBytes,
                URL serviceResource,
                java.util.function.Supplier<InputStream> classResource
        ) {
            super(parent);
            this.providerName = providerName;
            this.providerResource = providerName.replace('.', '/') + ".class";
            this.providerBytes = providerBytes.clone();
            this.serviceResource = serviceResource;
            this.classResource = classResource;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!providerName.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, providerBytes, 0, providerBytes.length);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("META-INF/services/")) {
                return SINK_SERVICE.equals(name)
                        ? Collections.enumeration(List.of(serviceResource))
                        : Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (providerResource.equals(name)
                    || ("/" + providerResource).equals(name)) {
                return classResource.get();
            }
            return super.getResourceAsStream(name);
        }
    }

    private static final class ServiceOnlyClassLoader extends URLClassLoader {
        private ServiceOnlyClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("META-INF/services/")) {
                return findResources(name);
            }
            return super.getResources(name);
        }
    }
}
