package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyServiceLoaderProviderRegistryTest {
    private static final List<String> CLOSE_ORDER = new CopyOnWriteArrayList<>();

    @AfterEach
    void reset() {
        CLOSE_ORDER.clear();
    }

    @Test
    void successfulRegistryOwnsProvidersAndClosesThemOnceInReverseOrder() {
        LegacyServiceLoaderProviderRegistry.ProviderSource source = (type, loader) ->
                type == FinalizedStreamSinkFactory.class
                        ? List.<ServiceLoader.Provider<?>>of(
                                handle(FirstSinkFactory.class, FirstSinkFactory::new),
                                handle(SecondSinkFactory.class, SecondSinkFactory::new))
                                .iterator()
                        : List.<ServiceLoader.Provider<?>>of().iterator();
        LegacyServiceLoaderProviderRegistry registry =
                new LegacyServiceLoaderProviderRegistry(
                        getClass().getClassLoader(), source, 8);
        FinalizedStreamSinkFactory first = registry.require(
                FinalizedStreamSinkFactory.class, "first");

        assertThat(registry.names(FinalizedStreamSinkFactory.class))
                .containsExactly("first", "second");
        registry.close();
        registry.close();

        assertThat(CLOSE_ORDER).containsExactly("second", "first");
        assertThatThrownBy(first::scheme)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("callback admission is sealed");
        assertThatThrownBy(() -> registry.names(FinalizedStreamSinkFactory.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closing or closed");
    }

    @Test
    void closeWaitsForRegisteredContributionLifetimeBeforeProviders() {
        LegacyServiceLoaderProviderRegistry.ProviderSource source = (type, loader) ->
                type == FinalizedStreamSinkFactory.class
                        ? List.<ServiceLoader.Provider<?>>of(
                                handle(FirstSinkFactory.class, FirstSinkFactory::new))
                                .iterator()
                        : List.<ServiceLoader.Provider<?>>of().iterator();
        LegacyServiceLoaderProviderRegistry registry =
                new LegacyServiceLoaderProviderRegistry(
                        getClass().getClassLoader(), source, 8);
        CompletableFuture<Void> contributionLifetime = new CompletableFuture<>();
        registry.registerContributionCleanup(contributionLifetime);

        CompletableFuture<Void> closed = CompletableFuture.runAsync(registry::close);
        assertThat(closed).isNotDone();
        assertThat(CLOSE_ORDER).isEmpty();

        contributionLifetime.complete(null);
        closed.join();
        assertThat(CLOSE_ORDER).containsExactly("first");
    }

    @Test
    void partialDiscoveryFailureRollsBackConstructedPrefix() {
        LegacyServiceLoaderProviderRegistry.ProviderSource source = (type, loader) ->
                type == FinalizedStreamSinkFactory.class
                        ? List.<ServiceLoader.Provider<?>>of(
                                handle(FirstSinkFactory.class, FirstSinkFactory::new),
                                handle(SecondSinkFactory.class, () -> {
                                    throw new IllegalStateException("constructor secret");
                                })).iterator()
                        : List.<ServiceLoader.Provider<?>>of().iterator();

        assertThatThrownBy(() -> new LegacyServiceLoaderProviderRegistry(
                getClass().getClassLoader(), source, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Legacy plugin provider registry initialization failed")
                .hasMessageNotContaining("constructor secret");
        assertThat(CLOSE_ORDER).containsExactly("first");
    }

    @Test
    void nestedProcessFatalDiscoveryFailureRollsBackBeforeEscapingUnwrapped() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        LegacyServiceLoaderProviderRegistry.ProviderSource source = (type, loader) ->
                type == FinalizedStreamSinkFactory.class
                        ? List.<ServiceLoader.Provider<?>>of(
                                handle(FirstSinkFactory.class, FirstSinkFactory::new),
                                handle(SecondSinkFactory.class, () -> {
                                    throw new IllegalStateException("wrapper", fatal);
                                })).iterator()
                        : List.<ServiceLoader.Provider<?>>of().iterator();

        assertThatThrownBy(() -> new LegacyServiceLoaderProviderRegistry(
                getClass().getClassLoader(), source, 8)).isSameAs(fatal);
        assertThat(CLOSE_ORDER).containsExactly("first");
    }

    @Test
    void nestedProcessFatalCloseStillClosesEveryProviderInReverseOrder() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        AssertionError wrapper = new AssertionError("wrapper");
        wrapper.addSuppressed(fatal);
        class FatalCloseFactory extends ClosingSinkFactory {
            private FatalCloseFactory() {
                super("fatal");
            }

            @Override
            public void close() {
                super.close();
                throw wrapper;
            }
        }
        LegacyServiceLoaderProviderRegistry.ProviderSource source = (type, loader) ->
                type == FinalizedStreamSinkFactory.class
                        ? List.<ServiceLoader.Provider<?>>of(
                                handle(FirstSinkFactory.class, FirstSinkFactory::new),
                                handle(FatalCloseFactory.class, FatalCloseFactory::new))
                                .iterator()
                        : List.<ServiceLoader.Provider<?>>of().iterator();
        LegacyServiceLoaderProviderRegistry registry =
                new LegacyServiceLoaderProviderRegistry(
                        getClass().getClassLoader(), source, 8);

        assertThatThrownBy(registry::close).isSameAs(fatal);
        assertThat(CLOSE_ORDER).containsExactly("fatal", "first");
        registry.close();
    }

    @Test
    void globalDiscoveryBoundRollsBackBeforeAdvancingPastLimit() {
        LegacyServiceLoaderProviderRegistry.ProviderSource source = (type, loader) ->
                type == FinalizedStreamSinkFactory.class
                        ? List.<ServiceLoader.Provider<?>>of(
                                handle(FirstSinkFactory.class, FirstSinkFactory::new),
                                handle(SecondSinkFactory.class, SecondSinkFactory::new))
                                .iterator()
                        : List.<ServiceLoader.Provider<?>>of().iterator();

        assertThatThrownBy(() -> new LegacyServiceLoaderProviderRegistry(
                getClass().getClassLoader(), source, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("ServiceLoader provider discovery exceeds the global "
                        + "limit of 1 while scanning "
                        + FinalizedStreamSinkFactory.class.getName());
        assertThat(CLOSE_ORDER).containsExactly("first");
    }

    @Test
    void oversizedLegacySelectorIsRejectedAndProviderIsClosed() {
        class OversizedSelectorFactory extends ClosingSinkFactory {
            private OversizedSelectorFactory() { super("x".repeat(129)); }
        }
        LegacyServiceLoaderProviderRegistry.ProviderSource source = (type, loader) ->
                type == FinalizedStreamSinkFactory.class
                        ? List.<ServiceLoader.Provider<?>>of(handle(
                                OversizedSelectorFactory.class,
                                OversizedSelectorFactory::new)).iterator()
                        : List.<ServiceLoader.Provider<?>>of().iterator();

        assertThatThrownBy(() -> new LegacyServiceLoaderProviderRegistry(
                getClass().getClassLoader(), source, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(FinalizedStreamSinkFactory.class.getSimpleName()
                        + " returned an invalid selector; expected non-blank, "
                        + "trimmed text of at most 128 characters");
        assertThat(CLOSE_ORDER).containsExactly("x".repeat(129));
    }

    @Test
    void catalogProviderDiscoveryBoundSpansContributionKinds() {
        PluginCatalogBuilder.ProviderSource source = (type, loader) -> {
            if (type == NodePlugin.class) {
                return List.<ServiceLoader.Provider<?>>of(
                        handle(TestNodePlugin.class, TestNodePlugin::new)).iterator();
            }
            if (type == AppStateMachineProvider.class) {
                return List.<ServiceLoader.Provider<?>>of(
                        handle(TestStateMachineProvider.class,
                                TestStateMachineProvider::new)).iterator();
            }
            return List.<ServiceLoader.Provider<?>>of().iterator();
        };

        assertThatThrownBy(() -> PluginCatalogBuilder.discoverProviderHandlesForTesting(
                getClass().getClassLoader(), source, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                        "ServiceLoader provider discovery exceeds the global limit of 1");
    }

    private static ServiceLoader.Provider<?> handle(
            Class<?> type,
            Supplier<?> supplier
    ) {
        return new ServiceLoader.Provider<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Class<? extends Object> type() {
                return (Class<? extends Object>) type;
            }

            @Override
            public Object get() {
                return supplier.get();
            }
        };
    }

    private abstract static class ClosingSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private final String id;

        private ClosingSinkFactory(String id) {
            this.id = id;
        }

        @Override
        public String scheme() {
            return id;
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
            CLOSE_ORDER.add(id);
        }
    }

    private static final class FirstSinkFactory extends ClosingSinkFactory {
        private FirstSinkFactory() {
            super("first");
        }
    }

    private static final class SecondSinkFactory extends ClosingSinkFactory {
        private SecondSinkFactory() {
            super("second");
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }

    private static final class TestNodePlugin implements NodePlugin {
        @Override public String id() { return "test.node"; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext ctx) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    private static final class TestStateMachineProvider
            implements AppStateMachineProvider {
        @Override public String id() { return "test-state"; }

        @Override
        public AppStateMachine create() {
            return new AppStateMachine() {
                @Override public String id() { return "test-state"; }
                @Override public void apply(AppBlock block, AppStateWriter writer) { }
            };
        }
    }
}
