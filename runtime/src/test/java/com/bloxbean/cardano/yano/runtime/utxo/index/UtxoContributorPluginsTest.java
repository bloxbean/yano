package com.bloxbean.cardano.yano.runtime.utxo.index;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.utxo.index.IndexWriter;
import com.bloxbean.cardano.yano.api.utxo.index.IndexRequirements;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.runtime.utxo.StorageFilterChain;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoChanges;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContext;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributor;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributorProvider;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtxoContributorPluginsTest {
    @TempDir Path directory;

    @Test void indexedYamlAndLibraryConfigurationAreEquivalent() {
        var flat = UtxoContributorPlugins.registrations(Map.of(
                UtxoContributorPlugins.CONFIG_KEY + "[0].type", "example.index",
                UtxoContributorPlugins.CONFIG_KEY + "[0].enabled", "true",
                UtxoContributorPlugins.CONFIG_KEY + "[0].config.label", "demo"));
        var list = UtxoContributorPlugins.registrations(Map.of(UtxoContributorPlugins.CONFIG_KEY,
                List.of(Map.of("type", "example.index", "enabled", true, "config", Map.of("label", "demo")))));
        assertThat(flat).isEqualTo(list);
        assertThat(UtxoContributorPlugins.registrations(Map.of(UtxoContributorPlugins.CONFIG_KEY,
                List.of(Map.of("type", "example.index")))).getFirst().enabled()).isFalse();
    }

    @Test void invalidAndReservedConfigurationFailsRatherThanSilentlyDisablingAnIndex() {
        for (Map<String, Object> entry : List.<Map<String, Object>>of(Map.of("type", "wallet"),
                Map.of("type", "../bad"), Map.of("type", "ok", "enabled", "tru"),
                Map.of("type", "ok", "config", "bad"), Map.of("type", "ok", "enabeld", true))) {
            assertThatThrownBy(() -> UtxoContributorPlugins.registrations(Map.of(UtxoContributorPlugins.CONFIG_KEY,
                    List.of(entry)))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void missingEnabledProviderFailsButDiscoveryAloneDoesNotEnableIt() {
        assertThatThrownBy(() -> new UtxoContributorPlugins(null, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                List.of(Map.of("type", "example.index", "enabled", true))), PluginProviderRegistry.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        try (var ignored = new UtxoContributorPlugins(null, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                List.of(Map.of("type", "example.index"))), PluginProviderRegistry.empty())) {
            ignored.start();
        }
    }

    @Test void allProvidersArePreflightedBeforeAnyParticipantIsRegistered() {
        UtxoIndexContributorProvider provider = new UtxoIndexContributorProvider() {
            @Override public String id() { return "a.valid"; }
            @Override public IndexRequirements requirements() { return new IndexRequirements(false, false, true); }
            @Override public UtxoIndexContributor create(UtxoIndexContext context) {
                return new UtxoIndexContributor() {
                    @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { }
                    @Override public void stageRollback(ChainPoint target, IndexWriter writer) { }
                };
            }
        };
        PluginProviderRegistry providers = new PluginProviderRegistry() {
            @Override public <P> Optional<P> find(Class<P> type, String selector) {
                return selector.equals(provider.id()) ? Optional.of(type.cast(provider)) : Optional.empty();
            }
            @Override public <P> List<String> names(Class<P> type) { return List.of(provider.id()); }
        };
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false))) {
            Map<String, Object> valid = Map.of("type", provider.id(), "enabled", true);
            assertThatThrownBy(() -> new UtxoContributorPlugins(store, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                    List.of(valid, Map.of("type", "z.missing", "enabled", true))), providers));
            assertThatThrownBy(() -> new UtxoContributorPlugins(store, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                    List.of(valid, valid)), providers)).hasMessageContaining("Duplicate");
            store.setFilterChain(new StorageFilterChain(List.of(new StorageFilter() { })));
            assertThatThrownBy(() -> new UtxoContributorPlugins(store, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                    List.of(valid)), providers)).hasMessageContaining("unfiltered");
            store.setFilterChain(null);
            try (var plugins = new UtxoContributorPlugins(store, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                    List.of(valid)), providers)) {
                plugins.start(); // No earlier failed preflight left a duplicate participant.
            }
        }
    }

    @Test void closeFailureStillClosesOtherProductsAndCompletesCleanupFuture() {
        AssertionError failure = new AssertionError("plugin close");
        List<String> closed = new ArrayList<>();
        List<CompletableFuture<Void>> cleanup = new ArrayList<>();
        PluginProviderRegistry providers = new PluginProviderRegistry() {
            @Override public <P> Optional<P> find(Class<P> type, String selector) {
                UtxoIndexContributorProvider provider = new UtxoIndexContributorProvider() {
                    @Override public String id() { return selector; }
                    @Override public UtxoIndexContributor create(UtxoIndexContext context) {
                        return new UtxoIndexContributor() {
                            @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { }
                            @Override public void stageRollback(ChainPoint target, IndexWriter writer) { }
                            @Override public void close() { closed.add(selector); throw failure; }
                        };
                    }
                };
                return Optional.of(type.cast(provider));
            }
            @Override public <P> List<String> names(Class<P> type) { return List.of("a", "b"); }
            @Override public void registerContributionCleanup(CompletableFuture<Void> completion) { cleanup.add(completion); }
        };
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false));
             var plugins = new UtxoContributorPlugins(store, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                     List.of(Map.of("type", "a", "enabled", true), Map.of("type", "b", "enabled", true))), providers)) {
            plugins.start();
            assertThatThrownBy(plugins::close).isSameAs(failure);
            assertThat(closed).containsExactly("b", "a");
            assertThat(cleanup).allMatch(CompletableFuture::isDone);
            assertThat(failure.getSuppressed()).isEmpty();
        }
    }

    @Test void stopExpiresContextAndCompletesCleanupRestartCreatesNewProduct() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicReference<UtxoIndexContext> current = new AtomicReference<>();
        UtxoIndexContributorProvider provider = new UtxoIndexContributorProvider() {
            @Override public String id() { return "example.index"; }
            @Override public UtxoIndexContributor create(UtxoIndexContext context) {
                created.incrementAndGet(); current.set(context);
                return new UtxoIndexContributor() {
                    @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { }
                    @Override public void stageRollback(ChainPoint target, IndexWriter writer) { }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        };
        List<CompletableFuture<Void>> cleanup = new ArrayList<>();
        PluginProviderRegistry providers = new PluginProviderRegistry() {
            @Override public <P> Optional<P> find(Class<P> type, String selector) {
                return selector.equals(provider.id()) && type.isInstance(provider) ? Optional.of(type.cast(provider)) : Optional.empty();
            }
            @Override public <P> List<String> names(Class<P> type) { return List.of(provider.id()); }
            @Override public void registerContributionCleanup(CompletableFuture<Void> completion) { cleanup.add(completion); }
        };
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false));
             var plugins = new UtxoContributorPlugins(store, Map.of(UtxoContributorPlugins.CONFIG_KEY,
                     List.of(Map.of("type", provider.id(), "enabled", true))), providers)) {
            assertThat(created).hasValue(0);
            plugins.start();
            plugins.start();
            assertThat(created).hasValue(1);
            UtxoIndexContext previous = current.get();
            assertThat(previous.configuration()).isEmpty();
            store.wireAllegraBootstrapRemoval(chain);
            store.initializeFreshFullStateGenesis(Map.of(), 42, Map.of(), Map.of(), 0, 0, "00".repeat(32));
            plugins.close();
            assertThat(closed).hasValue(1);
            assertThat(cleanup.getFirst()).isCompleted();
            store.reinitialize(); // No product handles exist while stopped; restart will bind fresh ones.
            assertThatThrownBy(() -> previous.read(scope -> scope.generation())).isInstanceOf(IllegalStateException.class);
            Block block = Block.builder().transactionBodies(List.of()).invalidTransactions(List.of()).build();
            assertThatThrownBy(() -> store.applyBlock(new BlockAppliedEvent(Era.Babbage, 10, 1, "11".repeat(32), block)))
                    .isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(IndexLifecycleException.class);
            plugins.start();
            assertThat(created).hasValue(2);
            store.applyBlock(new BlockAppliedEvent(Era.Babbage, 10, 1, "11".repeat(32), block));
            boolean available = current.get().read(UtxoIndexContext.ReadScope::available);
            assertThat(available).isTrue();
            assertThatThrownBy(() -> previous.read(scope -> scope.generation())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(closed).hasValue(2);
        assertThat(cleanup).allMatch(CompletableFuture::isDone);
    }
}
