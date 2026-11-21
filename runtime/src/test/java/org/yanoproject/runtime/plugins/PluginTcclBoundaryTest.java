package org.yanoproject.runtime.plugins;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventContext;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginActivationException;
import org.yanoproject.api.plugin.PluginCapability;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.StorageFilter;
import org.yanoproject.api.plugin.UtxoFilterContext;
import org.yanoproject.catalog.BundleDependency;
import org.yanoproject.catalog.BundleManifest;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.runtime.plugins.fixture.FixtureBinding;
import org.yanoproject.runtime.plugins.fixture.FixtureProduct;
import org.yanoproject.runtime.plugins.fixture.FixtureProvider;
import org.yanoproject.catalog.SemVersion;
import org.yanoproject.catalog.YanoApiRange;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginTcclBoundaryTest {
    private static final FixtureBinding FIXTURE_BINDING = new FixtureBinding();
    @Test
    void productReservationsUseWeakIdentityAndPruneUnreachableRuntimeProducts()
            throws Exception {
        PluginSpiFacades.ProductReservations reservations =
                new PluginSpiFacades.ProductReservations();
        List<WeakReference<Object>> products = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            products.add(reserveEphemeralProduct(reservations));
        }
        assertThat(reservations.reservationCountForTesting()).isEqualTo(256);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (products.stream().anyMatch(reference -> reference.get() != null)
                && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }

        assertThat(products).allSatisfy(reference -> assertThat(reference.get()).isNull());
        assertThat(reservations.reservationCountForTesting()).isZero();
    }

    @Test
    void scopeRestoresPreviousTcclEvenWhenPluginReplacesItAndThrowsFatalError() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        try {
            Thread.currentThread().setContextClassLoader(caller);
            assertThatThrownBy(() -> PluginThreadContext.run(plugin, () -> {
                assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(plugin);
                Thread.currentThread().setContextClassLoader(rogue);
                throw new AssertionError("expected fatal failure");
            })).isInstanceOf(AssertionError.class).hasMessage("expected fatal failure");
            assertCaller(caller);

            Thread.currentThread().setContextClassLoader(plugin);
            PluginThreadContext.run(plugin,
                    () -> Thread.currentThread().setContextClassLoader(rogue));
            assertCaller(plugin);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void providerConstructionSelectorAndFailedCloseUseIndependentTcclScopes() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        FixtureProduct machine = new FixtureProduct() {
            @Override public String id() { probe.check(); return "machine"; }
        };
        FixtureProvider valid = new FixtureProvider() {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return "valid";
            }
            @Override public FixtureProduct create() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return machine;
            }
        };
        CatalogPluginProviderRegistry.Entry validEntry =
                new CatalogPluginProviderRegistry.Entry(
                        "valid-bundle", TestPluginKinds.FIXTURE,
                        "valid", valid.getClass().getName(), null, () -> {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return valid;
                });
        CatalogPluginProviderRegistry validRegistry =
                new CatalogPluginProviderRegistry(TestPluginKinds.KINDS,
                        List.of(validEntry), List.of("valid-bundle"), List.of(), plugin);

        AtomicInteger failedCloses = new AtomicInteger();
        class InvalidProvider implements FixtureProvider, AutoCloseable {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return "wrong";
            }
            @Override public FixtureProduct create() { return machine; }
            @Override public void close() {
                probe.check();
                failedCloses.incrementAndGet();
                Thread.currentThread().setContextClassLoader(rogue);
            }
        }
        InvalidProvider invalid = new InvalidProvider();
        CatalogPluginProviderRegistry.Entry invalidEntry =
                new CatalogPluginProviderRegistry.Entry(
                        "invalid-bundle", TestPluginKinds.FIXTURE,
                        "expected", invalid.getClass().getName(), null, () -> {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return invalid;
                });
        CatalogPluginProviderRegistry invalidRegistry =
                new CatalogPluginProviderRegistry(TestPluginKinds.KINDS,
                        List.of(invalidEntry), List.of("invalid-bundle"), List.of(), plugin);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            FixtureProvider exposed = validRegistry.require(
                    FixtureProvider.class, "valid");
            assertCaller(caller);
            assertThat(exposed.id()).isEqualTo("valid");
            assertThat(exposed.create().id()).isEqualTo("machine");
            assertCaller(caller);

            assertThatThrownBy(() -> invalidRegistry.require(
                    FixtureProvider.class, "expected"))
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageContaining("construct provider");
            assertThat(failedCloses).hasValue(1);
            assertCaller(caller);
            invalidRegistry.close();
            assertThat(failedCloses).hasValue(1);
        } finally {
            validRegistry.close();
            invalidRegistry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void pluginStatusIsDeeplyNormalizedAndRejectsCyclesOrArbitraryBeans() {
        AtomicReference<Map<String, Object>> rawStatus = new AtomicReference<>();
        FixtureProduct rawMode = new FixtureProduct() {
            @Override public Map<String, Object> status() { return rawStatus.get(); }
        };
        FixtureProvider rawProvider = new FixtureProvider() {
            @Override public String id() { return "status-mode"; }
            @Override public FixtureProduct create() { return rawMode; }
        };
        FixtureProvider provider = providerFacade(FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawProvider, getClass().getClassLoader(), "status-bundle", "status-mode");
        FixtureProduct mode = provider.create();

        List<Object> rawList = new ArrayList<>();
        rawList.add("first");
        rawList.add(null);
        Map<String, Object> rawNested = new java.util.LinkedHashMap<>();
        rawNested.put("items", rawList);
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("nested", rawNested);
        rawStatus.set(root);

        Map<String, Object> snapshot = mode.status();
        rawList.add("late");
        rawNested.put("late", true);
        root.put("late", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedSnapshot =
                (Map<String, Object>) snapshot.get("nested");
        @SuppressWarnings("unchecked")
        List<Object> listSnapshot = (List<Object>) nestedSnapshot.get("items");
        assertThat(snapshot).containsOnlyKeys("nested");
        assertThat(nestedSnapshot).containsOnlyKeys("items");
        assertThat(listSnapshot).containsExactly("first", null);
        assertThatThrownBy(() -> nestedSnapshot.put("mutate", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> listSnapshot.add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> cyclic = new java.util.LinkedHashMap<>();
        cyclic.put("self", cyclic);
        rawStatus.set(cyclic);
        assertThatThrownBy(mode::status)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage("Plugin status must not contain container cycles");

        rawStatus.set(Map.of("bean", new Object()));
        assertThatThrownBy(mode::status)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage(
                        "Plugin status values must be JSON primitives, maps, or lists");

        rawStatus.set(Map.of("number", new java.math.BigInteger("1".repeat(10_000))));
        assertThatThrownBy(mode::status)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage(
                        "Plugin status values must be JSON primitives, maps, or lists");
    }

    @Test
    void failedProviderCandidateSafelyWrapsSharedNonProcessFatalCloseFailure() {
        AssertionError sharedFatal = new AssertionError("shared provider fatal");
        AtomicInteger closeCalls = new AtomicInteger();
        class SameFatalProvider implements FixtureProvider, AutoCloseable {
            @Override public String id() { throw sharedFatal; }
            @Override public void close() {
                closeCalls.incrementAndGet();
                throw sharedFatal;
            }
        }
        SameFatalProvider provider = new SameFatalProvider();
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        "fatal-bundle", TestPluginKinds.FIXTURE,
                        "expected", provider.getClass().getName(), null, () -> provider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(TestPluginKinds.KINDS,
                List.of(entry), List.of("fatal-bundle"), List.of());

        assertThatThrownBy(() -> registry.require(
                FixtureProvider.class, "expected"))
                .isInstanceOf(PluginActivationException.class)
                .hasCause(sharedFatal);
        assertThat(sharedFatal.getSuppressed()).isEmpty();
        assertThat(closeCalls).hasValue(1);

        registry.close();
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void factoryProductsAreExclusiveAcrossCreateInvocationsAndRejectedBatchesAreCleaned() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        FixtureProduct single = new TrackingSink("single", probe, new ArrayList<>());
        FixtureProvider rawSingles = new FixtureProvider() {
            @Override public String id() { probe.check(); return "single"; }
            @Override public FixtureProduct create() { probe.check(); return single; }
        };

        List<String> sinkCloseOrder = new ArrayList<>();
        RuntimeException cleanupFailure = new IllegalStateException("expected cleanup failure");
        TrackingSink ownedSink = new TrackingSink("owned-sink", probe, sinkCloseOrder);
        TrackingSink freshSinkOne = new TrackingSink("fresh-sink-1", probe, sinkCloseOrder);
        TrackingSink freshSinkTwo = new TrackingSink(
                "fresh-sink-2", probe, sinkCloseOrder, cleanupFailure);
        AtomicInteger sinkCreates = new AtomicInteger();
        FixtureProvider rawSinks = new FixtureProvider() {
            @Override public String id() { probe.check(); return "sink"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                if (sinkCreates.incrementAndGet() == 1) {
                    return List.of(ownedSink, ownedSink);
                }
                return List.of(freshSinkOne, ownedSink, freshSinkTwo, freshSinkOne);
            }
        };

        FixtureProvider singles = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawSingles, plugin, "single-bundle", "single");
        FixtureProvider sinks = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawSinks, plugin, "sink-bundle", "sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            singles.create();
            assertReuseFailure(
                    singles::create, TestPluginKinds.FIXTURE,
                    "single-bundle", "single", rawSingles);

            List<FixtureProduct> firstSinks = sinks.createAll();
            assertThat(firstSinks).hasSize(2);
            assertThat(firstSinks.get(1)).isSameAs(firstSinks.getFirst());
            PluginActivationException sinkFailure = assertReuseFailure(
                    sinks::createAll, TestPluginKinds.FIXTURE,
                    "sink-bundle", "sink", rawSinks);
            assertThat(ownedSink.closeCalls).hasValue(0);
            assertThat(freshSinkOne.closeCalls).hasValue(1);
            assertThat(freshSinkTwo.closeCalls).hasValue(1);
            assertThat(sinkCloseOrder).containsExactly("fresh-sink-2", "fresh-sink-1");
            assertThat(sinkFailure.getCause().getSuppressed()).containsExactly(cleanupFailure);

            firstSinks.getFirst().close();
            assertThat(ownedSink.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void iteratorFailureTerminallyReservesAndReverseClosesOnlyCapturedFreshProducts() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        List<String> closeOrder = new ArrayList<>();
        AssertionError fatalCleanup = new AssertionError("expected traversal cleanup fatal");
        TrackingSink owned = new TrackingSink("owned", probe, closeOrder);
        TrackingSink freshOne = new TrackingSink("fresh-1", probe, closeOrder);
        TrackingSink freshTwo = new TrackingSink(
                "fresh-2", probe, closeOrder, fatalCleanup);
        RuntimeException traversalFailure =
                new IllegalStateException("expected iterator failure");
        AtomicInteger creates = new AtomicInteger();
        FixtureProvider rawFactory = new FixtureProvider() {
            @Override public String id() { probe.check(); return "failing-list"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                return switch (creates.incrementAndGet()) {
                    case 1 -> List.of(owned);
                    case 2 -> new FailingTraversalList<>(
                            probe, List.of(freshOne, owned, freshTwo), traversalFailure);
                    default -> List.of(freshOne);
                };
            }
        };
        FixtureProvider factory = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawFactory, plugin, "sink-bundle", "failing-list");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            FixtureProduct owner = factory.createAll().getFirst();
            Throwable failure = catchThrowable(
                    () -> factory.createAll());
            assertThat(failure)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining(fatalCleanup.getMessage())
                    .hasCause(fatalCleanup);
            assertThat(fatalCleanup.getSuppressed()).containsExactly(traversalFailure);
            assertThat(closeOrder).containsExactly("fresh-2", "fresh-1");
            assertThat(owned.closeCalls).hasValue(0);
            assertThat(freshOne.closeCalls).hasValue(1);
            assertThat(freshTwo.closeCalls).hasValue(1);

            assertReuseFailure(() -> factory.createAll(),
                    TestPluginKinds.FIXTURE,
                    "sink-bundle", "failing-list", rawFactory);
            assertThat(freshOne.closeCalls).hasValue(1);
            assertThat(owned.closeCalls).hasValue(0);
            owner.close();
            assertThat(owned.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void factoryProductListsAreSnapshottedOnceUnderPluginTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        List<String> sinkCloseOrder = new ArrayList<>();
        TrackingSink sinkOne = new TrackingSink("sink-1", probe, sinkCloseOrder);
        TrackingSink sinkTwo = new TrackingSink("sink-2", probe, sinkCloseOrder);
        TrackingSink unexpectedSink = new TrackingSink(
                "unexpected-sink", probe, sinkCloseOrder);
        ChangingTraversalList<FixtureProduct> sinkProducts =
                new ChangingTraversalList<>(probe,
                        List.of(sinkOne, sinkTwo), List.of(unexpectedSink));
        FixtureProvider rawSinks = new FixtureProvider() {
            @Override public String id() { probe.check(); return "changing-sink"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sinkProducts;
            }
        };

        FixtureProvider sinks = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawSinks, plugin, "sink-bundle", "changing-sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            List<FixtureProduct> activatedSinks = sinks.createAll();
            assertThat(activatedSinks).hasSize(2);
            assertThat(activatedSinks)
                    .extracting(FixtureProduct::id)
                    .containsExactly("sink-1", "sink-2");
            assertThat(sinkProducts.traversals()).isEqualTo(1);
            assertCaller(caller);

            activatedSinks.forEach(FixtureProduct::close);
            assertThat(sinkCloseOrder).containsExactly("sink-1", "sink-2");
            assertThat(unexpectedSink.closeCalls).hasValue(0);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void rejectedCompanionClosesRestorePluginTcclBetweenCallbacks() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        List<String> closeOrder = new ArrayList<>();
        AtomicInteger firstCloses = new AtomicInteger();
        AtomicInteger corruptingCloses = new AtomicInteger();

        TrackingSink owned = new TrackingSink("owned", probe, closeOrder);
        FixtureProduct firstFresh = new FixtureProduct() {
            @Override public String id() { probe.check(); return "first-fresh"; }
            @Override public void close() {
                probe.check();
                firstCloses.incrementAndGet();
                closeOrder.add("first-fresh");
            }
        };
        FixtureProduct corruptingFresh = new FixtureProduct() {
            @Override public String id() { probe.check(); return "corrupting-fresh"; }
            @Override public void close() {
                probe.check();
                corruptingCloses.incrementAndGet();
                closeOrder.add("corrupting-fresh");
                Thread.currentThread().setContextClassLoader(rogue);
            }
        };
        AtomicInteger creates = new AtomicInteger();
        FixtureProvider rawFactory = new FixtureProvider() {
            @Override public String id() { probe.check(); return "rogue-close"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                return creates.incrementAndGet() == 1
                        ? List.of(owned)
                        : List.of(firstFresh, owned, corruptingFresh);
            }
        };
        FixtureProvider factory = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawFactory, plugin, "sink-bundle", "rogue-close");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            FixtureProduct firstOwner = factory.createAll().getFirst();
            assertReuseFailure(() -> factory.createAll(),
                    TestPluginKinds.FIXTURE,
                    "sink-bundle", "rogue-close", rawFactory);

            assertThat(closeOrder).containsExactly("corrupting-fresh", "first-fresh");
            assertThat(corruptingCloses).hasValue(1);
            assertThat(firstCloses).hasValue(1);
            assertThat(owned.closeCalls).hasValue(0);
            assertCaller(caller);

            firstOwner.close();
            assertThat(owned.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void activationBoundariesWrapEveryNonProcessFatalWithoutPromotingPluginMessages() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        String assertionSecret = "secret create assertion";
        AssertionError assertion = new AssertionError(assertionSecret);
        FixtureProvider rawCreators = new FixtureProvider() {
            @Override public String id() { probe.check(); return "creator"; }
            @Override public FixtureProduct create() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw assertion;
            }
        };
        FixtureProvider creators = providerFacade(FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawCreators, plugin, "creator-bundle", "creator");

        String linkageSecret = "secret status linkage detail";
        LinkageError linkage = new LinkageError(linkageSecret);
        FixtureProduct rawStatus = new FixtureProduct() {
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw linkage;
            }
        };
        String checkedSecret = "secret interrupted identity detail";
        InterruptedException checked = new InterruptedException(checkedSecret);
        FixtureProduct rawInterrupted = new FixtureProduct() {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sneakyThrow(checked);
            }
        };
        String spoofedSecret = "secret spoofed activation diagnostic";
        PluginActivationException spoofed = new PluginActivationException(
                spoofedSecret, new IllegalStateException("nested plugin detail"));
        FixtureProduct rawSpoofed = new FixtureProduct() {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw spoofed;
            }
        };
        TestVirtualMachineError fatal = new TestVirtualMachineError("process-fatal product identity");
        FixtureProduct rawFatal = new FixtureProduct() {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw fatal;
            }
        };
        FixtureProvider rawProducts = sinkFactory(
                "products", probe, List.of(rawStatus, rawInterrupted, rawSpoofed, rawFatal));
        FixtureProvider products = providerFacade(FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawProducts, plugin, "product-bundle", "products");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            assertSecretSafeActivationFailure(
                    creators::create, assertion, assertionSecret, TestPluginKinds.FIXTURE,
                    "creator-bundle", "creator", rawCreators);
            assertCaller(caller);

            List<FixtureProduct> created = products.createAll();
            assertSecretSafeActivationFailure(
                    created.get(0)::status, linkage, linkageSecret, TestPluginKinds.FIXTURE,
                    "product-bundle", "products", rawProducts);
            assertCaller(caller);

            assertSecretSafeActivationFailure(
                    created.get(1)::id, checked, checkedSecret, TestPluginKinds.FIXTURE,
                    "product-bundle", "products", rawProducts);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertCaller(caller);
            Thread.interrupted();

            assertSecretSafeActivationFailure(
                    created.get(2)::id, spoofed, spoofedSecret, TestPluginKinds.FIXTURE,
                    "product-bundle", "products", rawProducts);
            assertCaller(caller);

            assertThatThrownBy(created.get(3)::id).isSameAs(fatal);
            assertCaller(caller);
        } finally {
            Thread.interrupted();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void factorySnapshotOverflowReverseClosesEveryCapturedProductUnderTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        List<String> sinkCloseOrder = new ArrayList<>();
        List<TrackingSink> capturedSinks = new ArrayList<>();
        InfiniteProbeList<FixtureProduct> sinkProducts = new InfiniteProbeList<>(
                probe, rogue, index -> {
                    TrackingSink sink = new TrackingSink(
                            "sink-" + index, probe, sinkCloseOrder);
                    capturedSinks.add(sink);
                    return sink;
                });
        FixtureProvider rawSinks = new FixtureProvider() {
            @Override public String id() { probe.check(); return "bounded-sink"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sinkProducts;
            }
        };
        FixtureProvider sinks = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawSinks, plugin, "sink-bundle", "bounded-sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            assertCaller(caller);

            PluginActivationException sinkFailure = assertActivationFailure(
                    () -> sinks.createAll(),
                    TestPluginKinds.FIXTURE, "sink-bundle",
                    "bounded-sink", rawSinks);
            assertThat(sinkFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Plugin factory result must contain at most 256 products");
            assertThat(sinkProducts.hasNextCalls).hasValue(257);
            assertThat(sinkProducts.nextCalls).hasValue(256);
            assertThat(capturedSinks).hasSize(256)
                    .allSatisfy(sink -> assertThat(sink.closeCalls).hasValue(1));
            assertThat(sinkCloseOrder).hasSize(256)
                    .startsWith("sink-255")
                    .endsWith("sink-0");
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void manifestedNodePluginLazyDependenciesGetIndependentTcclScopes() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        String bundleId = "com.example.lazy-node-plugin";
        String dependencyId = "com.example.dependency";

        NodePlugin plugin = new NodePlugin() {
            @Override public String id() { probe.check(); return bundleId; }
            @Override public String version() { probe.check(); return "1.0.0"; }
            @Override public Set<String> dependsOn() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new AbstractSet<>() {
                    @Override public Iterator<String> iterator() {
                        probe.check();
                        Iterator<String> delegate = Set.of(dependencyId).iterator();
                        return new Iterator<>() {
                            @Override public boolean hasNext() {
                                probe.check();
                                return delegate.hasNext();
                            }
                            @Override public String next() {
                                probe.check();
                                return delegate.next();
                            }
                        };
                    }
                    @Override public int size() { probe.check(); return 1; }
                };
            }
            @Override public void init(PluginContext context) { probe.check(); }
            @Override public void start() { probe.check(); }
            @Override public void stop() { probe.check(); }
            @Override public void close() { probe.check(); }
        };
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                bundleId,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(
                        PluginCatalogBuilder.PLUGIN_API_MAJOR,
                        PluginCatalogBuilder.PLUGIN_API_MAJOR,
                        PluginCatalogBuilder.PLUGIN_API_LEVEL),
                List.of(new BundleDependency(dependencyId, null, null)),
                List.of());
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        bundleId, ContributionKind.NODE_PLUGIN, bundleId,
                        plugin.getClass().getName(), manifest, () -> plugin);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(TestPluginKinds.KINDS,
                List.of(entry), List.of(bundleId), List.of(), pluginLoader);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            NodePlugin exposed = registry.nodePluginInstances().getFirst();
            assertThat(exposed).isNotSameAs(plugin);
            assertThat(exposed.id()).isEqualTo(bundleId);
            assertThat(exposed.version()).isEqualTo("1.0.0");
            assertThat(exposed.dependsOn()).containsExactly(dependencyId);
            assertCaller(caller);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void manifestedNodePluginDependencyTraversalIsBoundedInsidePluginTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        String bundleId = "com.example.unbounded-node-plugin";
        AtomicInteger hasNextCalls = new AtomicInteger();
        AtomicInteger nextCalls = new AtomicInteger();

        NodePlugin plugin = new NodePlugin() {
            @Override public String id() { probe.check(); return bundleId; }
            @Override public String version() { probe.check(); return "1.0.0"; }
            @Override public Set<String> dependsOn() {
                probe.check();
                return new AbstractSet<>() {
                    @Override public Iterator<String> iterator() {
                        probe.check();
                        return new Iterator<>() {
                            @Override public boolean hasNext() {
                                probe.check();
                                hasNextCalls.incrementAndGet();
                                return true;
                            }

                            @Override public String next() {
                                probe.check();
                                nextCalls.incrementAndGet();
                                return "com.example.dependency";
                            }
                        };
                    }

                    @Override public int size() {
                        throw new AssertionError("dependency size must not be consulted");
                    }
                };
            }
            @Override public void init(PluginContext context) { probe.check(); }
            @Override public void start() { probe.check(); }
            @Override public void stop() { probe.check(); }
            @Override public void close() { probe.check(); }
        };
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                bundleId,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(
                        PluginCatalogBuilder.PLUGIN_API_MAJOR,
                        PluginCatalogBuilder.PLUGIN_API_MAJOR,
                        PluginCatalogBuilder.PLUGIN_API_LEVEL),
                List.of(),
                List.of());
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        bundleId, ContributionKind.NODE_PLUGIN, bundleId,
                        plugin.getClass().getName(), manifest, () -> plugin);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(TestPluginKinds.KINDS,
                List.of(entry), List.of(bundleId), List.of(), pluginLoader);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            assertThatThrownBy(registry::nodePluginInstances)
                    .isInstanceOf(PluginActivationException.class)
                    .hasRootCauseMessage(
                            "NodePlugin dependencies must contain at most 256 entries");
            assertThat(hasNextCalls).hasValue(257);
            assertThat(nextCalls).hasValue(256);
            assertCaller(caller);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void catalogReservationsRejectSharedProductsAcrossDifferentFactories() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        List<String> sinkCloseOrder = new ArrayList<>();
        TrackingSink ownedSink = new TrackingSink("shared-sink", probe, sinkCloseOrder);
        TrackingSink freshSink = new TrackingSink("fresh-sink", probe, sinkCloseOrder);
        FixtureProvider rawSinkA = sinkFactory(
                "sink-a", probe, List.of(ownedSink));
        FixtureProvider rawSinkB = sinkFactory(
                "sink-b", probe, List.of(freshSink, ownedSink));

        List<CatalogPluginProviderRegistry.Entry> entries = List.of(
                entry("sink-a-bundle", TestPluginKinds.FIXTURE,
                        "sink-a", rawSinkA, probe),
                entry("sink-b-bundle", TestPluginKinds.FIXTURE,
                        "sink-b", rawSinkB, probe));
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(TestPluginKinds.KINDS,
                entries, entries.stream().map(
                        CatalogPluginProviderRegistry.Entry::bundleId).toList(),
                List.of(), plugin);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            FixtureProvider sinkA = registry.require(
                    FixtureProvider.class, "sink-a");
            FixtureProvider sinkB = registry.require(
                    FixtureProvider.class, "sink-b");
            FixtureProduct firstSink = sinkA.createAll().getFirst();
            assertReuseFailure(
                    () -> sinkB.createAll(),
                    TestPluginKinds.FIXTURE,
                    "sink-b-bundle", "sink-b", rawSinkB);
            assertThat(ownedSink.closeCalls).hasValue(0);
            assertThat(freshSink.closeCalls).hasValue(1);
            assertThat(sinkCloseOrder).containsExactly("fresh-sink");

            firstSink.close();
            assertThat(ownedSink.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void rejectedBatchCompanionsAreReservedBeforeBlockingCleanup() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();

        TrackingSink owned = new TrackingSink("owned", probe, new ArrayList<>());
        FixtureProduct fresh = new FixtureProduct() {
            @Override public String id() { probe.check(); return "fresh"; }
            @Override public void close() {
                probe.check();
                closeCalls.incrementAndGet();
                closeStarted.countDown();
                try {
                    if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out awaiting close release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
        };
        AtomicInteger firstCreates = new AtomicInteger();
        FixtureProvider rawFirst = new FixtureProvider() {
            @Override public String id() { probe.check(); return "first"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                return firstCreates.incrementAndGet() == 1
                        ? List.of(owned) : List.of(owned, fresh);
            }
        };
        FixtureProvider rawSecond = sinkFactory(
                "second", probe, List.of(fresh));
        PluginSpiFacades.ProductReservations reservations =
                new PluginSpiFacades.ProductReservations();
        FixtureProvider first = (FixtureProvider)
                PluginSpiFacades.provider(FIXTURE_BINDING, rawFirst,
                        plugin, "first-bundle", "first", rawFirst.getClass().getName(),
                        reservations, new PluginSpiFacades.CallbackTracker(), false);
        FixtureProvider second = (FixtureProvider)
                PluginSpiFacades.provider(FIXTURE_BINDING, rawSecond,
                        plugin, "second-bundle", "second", rawSecond.getClass().getName(),
                        reservations, new PluginSpiFacades.CallbackTracker(), false);

        Thread.currentThread().setContextClassLoader(caller);
        var cleanupExecutor = Executors.newSingleThreadExecutor();
        try {
            FixtureProduct firstOwner = first.createAll().getFirst();
            var rejected = cleanupExecutor.submit(
                    () -> catchThrowable(() -> first.createAll()));
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertReuseFailure(() -> second.createAll(),
                    TestPluginKinds.FIXTURE,
                    "second-bundle", "second", rawSecond);
            assertThat(closeCalls).hasValue(1);
            assertThat(owned.closeCalls).hasValue(0);

            releaseClose.countDown();
            assertThat(rejected.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(PluginActivationException.class);
            assertThat(closeCalls).hasValue(1);
            firstOwner.close();
            assertThat(owned.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            releaseClose.countDown();
            cleanupExecutor.shutdownNow();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void nonProcessCleanupErrorsAreWrappedAfterRemainingFreshProductsClose() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        List<String> closeOrder = new ArrayList<>();
        AssertionError fatal = new AssertionError("expected fatal cleanup failure");
        TrackingSink owned = new TrackingSink("owned", probe, closeOrder, null);
        TrackingSink freshOne = new TrackingSink("fresh-1", probe, closeOrder, null);
        TrackingSink freshTwo = new TrackingSink("fresh-2", probe, closeOrder, fatal);
        AtomicInteger creates = new AtomicInteger();
        FixtureProvider rawFactory = new FixtureProvider() {
            @Override public String id() { probe.check(); return "executor"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                if (creates.incrementAndGet() == 1) {
                    return List.of(owned);
                }
                return List.of(freshOne, owned, freshTwo);
            }
        };
        FixtureProvider factory = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawFactory, plugin, "executor-bundle", "executor");

        List<String> sinkCloseOrder = new ArrayList<>();
        AssertionError sinkFatal = new AssertionError("expected fatal sink cleanup failure");
        TrackingSink ownedSink = new TrackingSink("owned-sink", probe, sinkCloseOrder);
        TrackingSink freshSinkOne = new TrackingSink("fresh-sink-1", probe, sinkCloseOrder);
        TrackingSink freshSinkTwo = new TrackingSink(
                "fresh-sink-2", probe, sinkCloseOrder, sinkFatal);
        AtomicInteger sinkCreates = new AtomicInteger();
        FixtureProvider rawSinkFactory = new FixtureProvider() {
            @Override public String id() { probe.check(); return "sink"; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                if (sinkCreates.incrementAndGet() == 1) {
                    return List.of(ownedSink);
                }
                return List.of(freshSinkOne, ownedSink, freshSinkTwo);
            }
        };
        FixtureProvider sinkFactory = providerFacade(
                FixtureProvider.class, TestPluginKinds.FIXTURE,
                rawSinkFactory, plugin, "sink-bundle", "sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            FixtureProduct first = factory.createAll().getFirst();
            Throwable failure = catchThrowable(() -> factory.createAll());
            assertThat(failure)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining(fatal.getMessage())
                    .hasCause(fatal);
            assertThat(closeOrder).containsExactly("fresh-2", "fresh-1");
            assertThat(owned.closeCalls).hasValue(0);
            assertThat(freshOne.closeCalls).hasValue(1);
            assertThat(freshTwo.closeCalls).hasValue(1);
            assertErrorReuseCollision(fatal);

            FixtureProduct firstSink = sinkFactory.createAll().getFirst();
            Throwable sinkFailure = catchThrowable(
                    () -> sinkFactory.createAll());
            assertThat(sinkFailure)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining(sinkFatal.getMessage())
                    .hasCause(sinkFatal);
            assertThat(sinkCloseOrder).containsExactly("fresh-sink-2", "fresh-sink-1");
            assertThat(ownedSink.closeCalls).hasValue(0);
            assertThat(freshSinkOne.closeCalls).hasValue(1);
            assertThat(freshSinkTwo.closeCalls).hasValue(1);
            assertErrorReuseCollision(sinkFatal);
            assertCaller(caller);
            first.close();
            firstSink.close();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void nodePluginMetadataLifecycleRestartAndCloseUsePluginTcclAndRestoreCaller() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        AssertingNodePlugin plugin = new AssertingNodePlugin(probe);

        Thread.currentThread().setContextClassLoader(caller);
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            PluginManager manager = new PluginManager(
                    new NoopEventBus(), scheduler, PluginsOptions.defaults(),
                    pluginLoader, List.of(plugin));
            manager.discoverAndInit();
            assertCaller(caller);
            manager.startAll();
            assertCaller(caller);
            manager.stopAll();
            assertCaller(caller);
            manager.startAll();
            manager.stopAll();
            manager.close();
            assertCaller(caller);

            assertThat(plugin.initCalls).hasValue(1);
            assertThat(plugin.startCalls).hasValue(2);
            assertThat(plugin.stopCalls).hasValue(2);
            assertThat(plugin.closeCalls).hasValue(1);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void pluginContextManagedCallbacksUsePluginTcclWithoutOwningSharedResources()
            throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader platform = new MarkerClassLoader(original);
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        ContextProbe platformProbe = new ContextProbe(platform);
        CapturingEventBus eventBus = new CapturingEventBus();
        eventBus.setPlatformListener(platformProbe::check);
        ScheduledExecutorService sharedScheduler =
                Executors.newSingleThreadScheduledExecutor();
        ContextContributionPlugin plugin = new ContextContributionPlugin(probe, rogue);
        Thread.currentThread().setContextClassLoader(platform);
        PluginManager manager = new PluginManager(
                eventBus, sharedScheduler, PluginsOptions.defaults(),
                pluginLoader, List.of(plugin));

        Thread.currentThread().setContextClassLoader(caller);
        try {
            manager.discoverAndInit();
            PluginContext context = plugin.context;

            // Callback resources are deliberately start-cycle scoped. This
            // prevents work queued before stop from being admitted after a
            // later generation resumes.
            assertThatThrownBy(() -> context.eventBus().subscribe(
                    TestEvent.class, ignored -> { }, SubscriptionOptions.builder().build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only during start/running");
            assertThatThrownBy(() -> context.scheduler().execute(() -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only during start/running");

            manager.startAll();
            assertCaller(caller);
            assertThat(eventBus.completionClassLoader).isSameAs(platform);

            AtomicInteger listenerCalls = new AtomicInteger();
            AtomicInteger filterCalls = new AtomicInteger();
            context.eventBus().subscribe(TestEvent.class, event -> {
                probe.check();
                listenerCalls.incrementAndGet();
                Thread.currentThread().setContextClassLoader(rogue);
            }, SubscriptionOptions.builder().filter((TestEvent event, EventMetadata metadata) -> {
                probe.check();
                filterCalls.incrementAndGet();
                Thread.currentThread().setContextClassLoader(rogue);
                return true;
            }).build());

            assertThat(filterCalls).hasValue(0);
            assertThat(listenerCalls).hasValue(0);

            context.eventBus().publish(new TestEvent(), EventMetadata.builder().build(),
                    PublishOptions.builder().build());
            assertCaller(caller);
            assertThat(eventBus.completionClassLoader).isSameAs(platform);
            assertThat(filterCalls).hasValue(1);
            assertThat(listenerCalls).hasValue(1);
            assertThat(platformProbe.callbacks).hasValue(2);

            // A fatal failure after a platform listener corrupts its TCCL
            // must restore platform -> plugin -> caller in that exact nesting.
            eventBus.setPlatformListener(() -> {
                platformProbe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw new AssertionError("expected platform dispatch failure");
            });
            assertThatThrownBy(() -> PluginThreadContext.run(pluginLoader,
                    () -> context.eventBus().publish(
                            new TestEvent(), EventMetadata.builder().build(),
                            PublishOptions.builder().build())))
                    .isInstanceOf(AssertionError.class)
                    .hasMessage("expected platform dispatch failure");
            assertCaller(caller);
            assertThat(platformProbe.callbacks).hasValue(3);
            assertThat(filterCalls).hasValue(1);
            assertThat(listenerCalls).hasValue(1);

            assertThatThrownBy(context.eventBus()::close)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("shared EventBus");
            assertThat(eventBus.closeCalls).hasValue(0);

            ClassLoader workerContext = sharedScheduler.submit(
                    () -> Thread.currentThread().getContextClassLoader()).get();
            context.scheduler().submit(() -> {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
            }).get();
            int scheduledValue = context.scheduler().schedule(() -> {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return 42;
            }, 0, TimeUnit.MILLISECONDS).get();
            assertThat(scheduledValue).isEqualTo(42);
            assertThat(sharedScheduler.submit(
                    () -> Thread.currentThread().getContextClassLoader()).get())
                    .isSameAs(workerContext);
            assertThatThrownBy(context.scheduler()::shutdown)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(context.scheduler()::shutdownNow)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(context.scheduler()::close)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(sharedScheduler.isShutdown()).isFalse();

            StorageFilter filter = manager.getStorageFilters().getFirst();
            assertThat(PluginContextFacades.storageFilterDelegate(filter))
                    .isSameAs(plugin.filter);
            assertThat(filter.priority()).isEqualTo(17);
            assertThat(filter.acceptUtxoOutput(null, null, null)).isTrue();
            assertCaller(caller);

            manager.close();
            assertCaller(caller);
        } finally {
            try {
                manager.close();
            } finally {
                eventBus.close();
                sharedScheduler.shutdownNow();
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    private static CatalogPluginProviderRegistry.Entry entry(
            String bundleId,
            ContributionKind kind,
            String name,
            Object provider,
            ContextProbe probe) {
        return new CatalogPluginProviderRegistry.Entry(
                bundleId, kind, name, provider.getClass().getName(), null, () -> {
                    probe.check();
                    return provider;
                });
    }

    private static <T> T providerFacade(
            Class<T> providerType,
            ContributionKind kind,
            T delegate,
            ClassLoader loader,
            String bundleId,
            String selector
    ) {
        return providerType.cast(PluginSpiFacades.provider(TestPluginKinds.KINDS.binding(kind),
                delegate, loader, bundleId, selector, delegate.getClass().getName()));
    }

    private static FixtureProvider sinkFactory(
            String scheme,
            ContextProbe probe,
            List<FixtureProduct> products
    ) {
        return new FixtureProvider() {
            @Override public String id() { probe.check(); return scheme; }
            @Override public List<FixtureProduct> createAll() {
                probe.check();
                return products;
            }
        };
    }

    private static PluginActivationException assertReuseFailure(
            ThrowingCallable callback,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        PluginActivationException failure = assertActivationFailure(
                callback, kind, bundleId, selector, rawProvider);
        assertThat(failure.getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already owned by a previous create invocation");
        return failure;
    }

    private static PluginActivationException assertActivationFailure(
            ThrowingCallable callback,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        Throwable failure = catchThrowable(callback);
        assertThat(failure).isInstanceOf(PluginActivationException.class);
        PluginActivationException activation = (PluginActivationException) failure;
        assertThat(activation.bundleId()).isEqualTo(bundleId);
        assertThat(activation.contributionKind()).isEqualTo(kind.manifestKey());
        assertThat(activation.selector()).isEqualTo(selector);
        assertThat(activation.providerClass()).isEqualTo(rawProvider.getClass().getName());
        return activation;
    }

    private static void assertSecretSafeActivationFailure(
            ThrowingCallable callback,
            Throwable cause,
            String secret,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        PluginActivationException activation = assertActivationFailure(
                callback, kind, bundleId, selector, rawProvider);
        assertThat(activation)
                .hasMessageNotContaining(secret)
                .hasCause(cause);
    }

    @SuppressWarnings("unchecked")
    private static <T, X extends Throwable> T sneakyThrow(Throwable failure) throws X {
        throw (X) failure;
    }

    private static void assertErrorReuseCollision(Error error) {
        assertThat(error.getSuppressed()).hasSize(1);
        assertThat(error.getSuppressed()[0])
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already owned by a previous create invocation");
    }

    private static void assertCaller(ClassLoader caller) {
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
    }

    private static WeakReference<Object> reserveEphemeralProduct(
            PluginSpiFacades.ProductReservations reservations
    ) {
        Object product = new Object();
        WeakReference<Object> reference = new WeakReference<>(product);
        reservations.facadeForNewInvocation(product, ignored -> new Object());
        return reference;
    }

    private static final class MarkerClassLoader extends ClassLoader {
        private MarkerClassLoader(ClassLoader parent) {
            super(parent);
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }

    private static final class ContextProbe {
        private final ClassLoader expected;
        private final AtomicInteger callbacks = new AtomicInteger();

        private ContextProbe(ClassLoader expected) {
            this.expected = expected;
        }

        private void check() {
            callbacks.incrementAndGet();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(expected);
        }
    }

    /** A map whose entry traversal and every entry read require the plugin context; entries may be foreign. */
    private static final class ProbeEntries extends AbstractMap<String, Object> {
        private final ContextProbe probe;
        private final List<Map.Entry<?, ?>> entries;
        private final boolean repeat;

        private ProbeEntries(ContextProbe probe, List<Map.Entry<?, ?>> entries, boolean repeat) {
            this.probe = probe;
            this.entries = entries;
            this.repeat = repeat;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Set<Entry<String, Object>> entrySet() {
            probe.check();
            return new AbstractSet<>() {
                @Override public Iterator<Entry<String, Object>> iterator() {
                    probe.check();
                    return new Iterator<>() {
                        private int index;
                        @Override public boolean hasNext() {
                            probe.check();
                            return repeat || index < entries.size();
                        }
                        @Override public Entry<String, Object> next() {
                            probe.check();
                            Map.Entry<?, ?> entry = entries.get(repeat ? 0 : index++);
                            return entry == null ? null
                                    : (Entry) new ProbeEntry(probe, entry.getKey(), entry.getValue());
                        }
                    };
                }
                @Override public int size() {
                    probe.check();
                    return repeat ? Integer.MAX_VALUE : entries.size();
                }
            };
        }
    }

    private static final class ProbeList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final List<T> values;

        @SafeVarargs
        private ProbeList(ContextProbe probe, T... values) {
            this.probe = probe;
            this.values = List.of(values);
        }

        @Override public T get(int index) { probe.check(); return values.get(index); }
        @Override public int size() { probe.check(); return values.size(); }
    }

    private static final class ChangingTraversalList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final List<T> firstTraversal;
        private final List<T> laterTraversals;
        private final AtomicInteger traversals = new AtomicInteger();

        private ChangingTraversalList(
                ContextProbe probe,
                List<T> firstTraversal,
                List<T> laterTraversals
        ) {
            this.probe = probe;
            this.firstTraversal = firstTraversal;
            this.laterTraversals = laterTraversals;
        }

        @Override
        public Iterator<T> iterator() {
            probe.check();
            List<T> values = traversals.incrementAndGet() == 1
                    ? firstTraversal : laterTraversals;
            Iterator<T> delegate = values.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() {
                    probe.check();
                    return delegate.hasNext();
                }

                @Override public T next() {
                    probe.check();
                    return delegate.next();
                }
            };
        }

        @Override public T get(int index) { throw new UnsupportedOperationException(); }
        @Override public int size() { throw new UnsupportedOperationException(); }

        private int traversals() {
            return traversals.get();
        }
    }

    private static final class FailingTraversalList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final List<T> values;
        private final RuntimeException failure;

        private FailingTraversalList(
                ContextProbe probe,
                List<T> values,
                RuntimeException failure
        ) {
            this.probe = probe;
            this.values = values;
            this.failure = failure;
        }

        @Override
        public Iterator<T> iterator() {
            probe.check();
            Iterator<T> delegate = values.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    probe.check();
                    if (!delegate.hasNext()) {
                        throw failure;
                    }
                    return true;
                }

                @Override
                public T next() {
                    probe.check();
                    return delegate.next();
                }
            };
        }

        @Override public T get(int index) { throw new UnsupportedOperationException(); }
        @Override public int size() { throw new UnsupportedOperationException(); }
    }

    private static final class ProbeMap<K, V> extends AbstractMap<K, V> {
        private final ContextProbe probe;
        private final Map<K, V> values;

        private ProbeMap(ContextProbe probe, Map<K, V> values) {
            this.probe = probe;
            this.values = values;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            probe.check();
            return values.entrySet();
        }
    }

    private static final class InfiniteProbeList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final IntFunction<T> values;
        private final AtomicInteger hasNextCalls = new AtomicInteger();
        private final AtomicInteger nextCalls = new AtomicInteger();

        private InfiniteProbeList(
                ContextProbe probe,
                ClassLoader rogue,
                IntFunction<T> values
        ) {
            this.probe = probe;
            this.rogue = rogue;
            this.values = values;
        }

        @Override
        public Iterator<T> iterator() {
            checkAndPoison();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    checkAndPoison();
                    hasNextCalls.incrementAndGet();
                    return true;
                }

                @Override
                public T next() {
                    probe.check();
                    T value = values.apply(nextCalls.getAndIncrement());
                    Thread.currentThread().setContextClassLoader(rogue);
                    return value;
                }
            };
        }

        @Override public T get(int index) { throw new UnsupportedOperationException(); }
        @Override public int size() { throw new UnsupportedOperationException(); }

        private void checkAndPoison() {
            probe.check();
            Thread.currentThread().setContextClassLoader(rogue);
        }
    }

    private static final class InfiniteProbeMap extends AbstractMap<String, Object> {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final AtomicInteger hasNextCalls = new AtomicInteger();
        private final AtomicInteger nextCalls = new AtomicInteger();

        private InfiniteProbeMap(ContextProbe probe, ClassLoader rogue) {
            this.probe = probe;
            this.rogue = rogue;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            checkAndPoison();
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    checkAndPoison();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            checkAndPoison();
                            hasNextCalls.incrementAndGet();
                            return true;
                        }

                        @Override
                        public Entry<String, Object> next() {
                            probe.check();
                            int value = nextCalls.getAndIncrement();
                            Thread.currentThread().setContextClassLoader(rogue);
                            return new Entry<>() {
                                @Override public String getKey() {
                                    checkAndPoison();
                                    return "duplicate-key";
                                }

                                @Override public Object getValue() {
                                    checkAndPoison();
                                    return value;
                                }

                                @Override public Object setValue(Object replacement) {
                                    throw new UnsupportedOperationException();
                                }
                            };
                        }
                    };
                }

                @Override public int size() { throw new UnsupportedOperationException(); }
            };
        }

        private void checkAndPoison() {
            probe.check();
            Thread.currentThread().setContextClassLoader(rogue);
        }
    }

    private static final class CapturingEventBus implements EventBus {
        private Class<? extends Event> type;
        private EventListener<? extends Event> listener;
        private SubscriptionOptions subscriptionOptions;
        private Runnable platformListener = () -> { };
        private ClassLoader completionClassLoader;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private void setPlatformListener(Runnable platformListener) {
            this.platformListener = platformListener;
        }

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> type,
                EventListener<E> listener,
                SubscriptionOptions options) {
            this.type = type;
            this.listener = listener;
            this.subscriptionOptions = options;
            return new SubscriptionHandle() {
                private boolean active = true;

                @Override public void close() { active = false; }
                @Override public boolean isActive() { return active; }
            };
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public <E extends Event> void publish(
                E event,
                EventMetadata metadata,
                PublishOptions options) {
            platformListener.run();
            if (type == null || !type.isInstance(event)) {
                completionClassLoader = Thread.currentThread().getContextClassLoader();
                return;
            }
            if (subscriptionOptions != null && subscriptionOptions.filter() != null
                    && !subscriptionOptions.filter().test(event, metadata)) {
                return;
            }
            try {
                EventListener rawListener = listener;
                rawListener.onEvent(new EventContext<E>() {
                    @Override public E event() { return event; }
                    @Override public EventMetadata metadata() { return metadata; }
                });
                completionClassLoader = Thread.currentThread().getContextClassLoader();
            } catch (Exception e) {
                throw new IllegalStateException("Test event delivery failed", e);
            }
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class ContextContributionPlugin implements NodePlugin {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final StorageFilter filter;
        private PluginContext context;

        private ContextContributionPlugin(ContextProbe probe, ClassLoader rogue) {
            this.probe = probe;
            this.rogue = rogue;
            this.filter = new StorageFilter() {
                @Override
                public boolean acceptUtxoOutput(
                        UtxoFilterContext ctx,
                        Block block,
                        TransactionBody txBody) {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return true;
                }

                @Override
                public int priority() {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return 17;
                }
            };
        }

        @Override public String id() { probe.check(); return "com.example.context-callbacks"; }
        @Override public String version() { probe.check(); return "1.0.0"; }
        @Override public void init(PluginContext context) {
            probe.check();
            this.context = context;
            context.registerStorageFilter(filter);
        }
        @Override public void start() {
            probe.check();
            context.eventBus().publish(new TestEvent(), EventMetadata.builder().build(),
                    PublishOptions.builder().build());
            probe.check();
        }
        @Override public void stop() { probe.check(); }
        @Override public void close() { probe.check(); }
    }

    private static final class TrackingSink implements FixtureProduct {
        private final String id;
        private final ContextProbe probe;
        private final List<String> closeOrder;
        private final Throwable closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TrackingSink(String id, ContextProbe probe, List<String> closeOrder) {
            this(id, probe, closeOrder, null);
        }

        private TrackingSink(
                String id,
                ContextProbe probe,
                List<String> closeOrder,
                Throwable closeFailure
        ) {
            this.id = id;
            this.probe = probe;
            this.closeOrder = closeOrder;
            this.closeFailure = closeFailure;
        }

        @Override public String id() { probe.check(); return id; }
        @Override public void close() {
            probe.check();
            closeCalls.incrementAndGet();
            closeOrder.add(id);
            if (closeFailure instanceof Error error) {
                throw error;
            }
            if (closeFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
    }

    private static final class AssertingNodePlugin implements NodePlugin {
        private final ContextProbe probe;
        private final AtomicInteger initCalls = new AtomicInteger();
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private AssertingNodePlugin(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "com.example.tccl"; }
        @Override public String version() { probe.check(); return "1.0.0"; }
        @Override public Set<String> dependsOn() { probe.check(); return Set.of(); }
        @Override public Set<PluginCapability> capabilities() {
            probe.check();
            return Set.of(PluginCapability.EVENT_CONSUMER);
        }
        @Override public void init(PluginContext ctx) { probe.check(); initCalls.incrementAndGet(); }
        @Override public void start() { probe.check(); startCalls.incrementAndGet(); }
        @Override public void stop() { probe.check(); stopCalls.incrementAndGet(); }
        @Override public void close() { probe.check(); closeCalls.incrementAndGet(); }
    }


    private record ProbeEntry(ContextProbe probe, Object key, Object value) implements Map.Entry<Object, Object> {
        @Override public Object getKey() { probe.check(); return key; }
        @Override public Object getValue() { probe.check(); return value; }
        @Override public Object setValue(Object replacement) { throw new UnsupportedOperationException(); }
    }

    private record TestEvent() implements Event {
    }
}
