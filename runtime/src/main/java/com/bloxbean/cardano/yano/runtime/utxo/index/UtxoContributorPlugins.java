package com.bloxbean.cardano.yano.runtime.utxo.index;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.utxo.index.IndexRequirements;
import com.bloxbean.cardano.yano.api.utxo.index.IndexWriter;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoChanges;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContext;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributor;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributorProvider;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStore;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Startup configuration and plugin product lifetime, separate from transaction dispatch. */
public final class UtxoContributorPlugins implements AutoCloseable {
    public static final String CONFIG_KEY = YanoPropertyKeys.Utxo.INDEX_CONTRIBUTORS;
    private final List<Product> products = new ArrayList<>();
    private final Object lock;

    public UtxoContributorPlugins(DefaultUtxoStore store, Map<String, Object> globals, PluginProviderRegistry providers) {
        lock = store == null ? this : store;
        var seen = new HashSet<String>();
        var resolved = new ArrayList<Resolved>();
        for (Registration registration : registrations(globals).stream().sorted(Comparator.comparing(Registration::type)).toList()) {
            if (!seen.add(registration.type())) throw new IllegalArgumentException("Duplicate contributor: " + registration.type());
            if (!registration.enabled()) continue;
            if (store == null) throw new IllegalArgumentException("UTxO contributor configured without a supported UTxO store");
            UtxoIndexContributorProvider provider = providers.require(UtxoIndexContributorProvider.class, registration.type());
            if (!registration.type().equals(provider.id())) throw new IllegalArgumentException("Contributor selector/identity mismatch");
            store.validateIndexContributor(provider);
            resolved.add(new Resolved(registration, provider));
        }
        // Missing/duplicate/invalid selectors must not leave an earlier participant registered.
        for (Resolved entry : resolved) {
            Product product = new Product(entry.provider(), providers);
            store.registerIndexContributor(product, entry.registration().config());
            products.add(product);
        }
    }

    private record Resolved(Registration registration, UtxoIndexContributorProvider provider) { }

    public void start() {
        synchronized (lock) {
            try { for (Product product : products) product.start(); }
            catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) {
                    Throwable combined = LifecycleFailures.merge(failure, cleanup);
                    if (combined instanceof Error error) throw error;
                    throw (RuntimeException) combined;
                }
                throw failure;
            }
        }
    }

    @Override public void close() {
        synchronized (lock) {
            Throwable failure = null;
            for (Product product : products.reversed()) {
                try { product.close(); }
                catch (RuntimeException | Error error) {
                    failure = LifecycleFailures.merge(failure, error);
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure != null) throw (RuntimeException) failure;
        }
    }

    public record Registration(String type, boolean enabled, Map<String, String> config) {
        public Registration {
            if (type == null || !type.matches("[a-z][a-z0-9._-]{0,127}") || type.equals("wallet")) {
                throw new IllegalArgumentException("Invalid or reserved UTxO contributor selector: " + type);
            }
            config = Map.copyOf(config);
        }
    }

    /** Accept a library list or flattened MicroProfile/YAML indexed properties. */
    public static List<Registration> registrations(Map<String, Object> globals) {
        List<Map<?, ?>> entries = new ArrayList<>();
        Object configured = globals.get(CONFIG_KEY);
        if (configured != null) {
            if (!(configured instanceof List<?> list)) throw new IllegalArgumentException(CONFIG_KEY + " must be a list");
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) throw new IllegalArgumentException("Contributor registration must be an object");
                entries.add(map);
            }
        }
        var indexed = new TreeMap<Integer, Map<String, Object>>();
        Pattern pattern = Pattern.compile(Pattern.quote(CONFIG_KEY) + "\\[(\\d+)]\\.(.+)");
        globals.forEach((key, value) -> {
            var match = pattern.matcher(key);
            if (match.matches()) indexed.computeIfAbsent(Integer.parseInt(match.group(1)), ignored -> new HashMap<>()).put(match.group(2), value);
        });
        if (!entries.isEmpty() && !indexed.isEmpty()) throw new IllegalArgumentException("Mixed contributor configuration representations");
        entries.addAll(indexed.values());
        List<Registration> result = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            Map<String, String> config = new HashMap<>();
            if (entry.containsKey("config") && !(entry.get("config") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Contributor config must be an object of scalar values");
            }
            if (entry.get("config") instanceof Map<?, ?> nested) nested.forEach((key, value) -> config.put(String.valueOf(key), scalar(value)));
            entry.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (name.startsWith("config.")) config.put(name.substring(7), scalar(value));
                else if (!List.of("type", "enabled", "config").contains(name)) throw new IllegalArgumentException("Unknown contributor option: " + name);
            });
            Object enabled = entry.get("enabled");
            if (enabled != null && !List.of("true", "false").contains(String.valueOf(enabled))) throw new IllegalArgumentException("Contributor enabled must be true or false");
            result.add(new Registration(entry.get("type") == null ? null : String.valueOf(entry.get("type")),
                    "true".equals(String.valueOf(enabled)), config));
        }
        return List.copyOf(result);
    }

    private static String scalar(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new IllegalArgumentException("Contributor configuration values must be scalar");
        return String.valueOf(value);
    }

    /** Stable registry participant, with a fresh plugin product for each stopped/started runtime cycle. */
    private static final class Product implements UtxoIndexContributorProvider, UtxoIndexContributor {
        private final UtxoIndexContributorProvider provider;
        private final PluginProviderRegistry providers;
        private UtxoIndexContext context;
        private UtxoIndexContributor delegate;
        private CompletableFuture<Void> cleanup;
        private AtomicBoolean contextActive;

        private Product(UtxoIndexContributorProvider provider, PluginProviderRegistry providers) {
            this.provider = provider;
            this.providers = providers;
        }
        @Override public String id() { return provider.id(); }
        @Override public int schemaVersion() { return provider.schemaVersion(); }
        @Override public IndexRequirements requirements() { return provider.requirements(); }
        @Override public UtxoIndexContributor create(UtxoIndexContext context) { this.context = context; return this; }
        private void start() {
            if (delegate != null) return;
            cleanup = new CompletableFuture<>();
            providers.registerContributionCleanup(cleanup);
            contextActive = new AtomicBoolean(true);
            AtomicBoolean active = contextActive;
            UtxoIndexContext scopedContext = new UtxoIndexContext() {
                private void check() { if (!active.get()) throw new IndexLifecycleException("Contributor context is stopped"); }
                @Override public String id() { check(); return context.id(); }
                @Override public String networkIdentity() { check(); return context.networkIdentity(); }
                @Override public Map<String, String> configuration() { check(); return context.configuration(); }
                @Override public <T> T read(Function<ReadScope, T> query) {
                    return context.read(scope -> { check(); return query.apply(scope); });
                }
            };
            try { delegate = Objects.requireNonNull(provider.create(scopedContext)); }
            catch (RuntimeException | Error failure) { active.set(false); cleanup.complete(null); throw failure; }
        }
        private UtxoIndexContributor active() {
            if (delegate == null) throw new IndexLifecycleException("UTxO contributor plugin is stopped");
            return delegate;
        }
        @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { active().stageApply(changes, writer); }
        @Override public void stageRollback(ChainPoint target, IndexWriter writer) { active().stageRollback(target, writer); }
        @Override public void stageGenesis(String identity, List<GenesisUtxo> outputs, IndexWriter writer) { active().stageGenesis(identity, outputs, writer); }
        @Override public void stagePruneUndo(ChainPoint point, IndexWriter writer) { active().stagePruneUndo(point, writer); }
        @Override public void reinitialize() { if (delegate != null) delegate.reinitialize(); }
        @Override public void close() {
            if (delegate == null) return;
            UtxoIndexContributor closing = delegate;
            delegate = null;
            contextActive.set(false);
            try { closing.close(); } finally { cleanup.complete(null); }
        }
    }
}
