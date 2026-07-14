package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * One-time compatibility scan used by direct AppChainSubsystem constructors.
 *
 * <p>This adapter deliberately remains eager for compatibility with the old
 * direct-constructor behavior, but it owns every provider it constructs. A
 * failed scan rolls the constructed prefix back and {@link #close()} releases
 * the successful set in reverse construction order.</p>
 */
public final class LegacyServiceLoaderProviderRegistry
        implements PluginProviderRegistry, AutoCloseable {
    /** Keep raw ServiceLoader traversal within the aggregate-index provider bound. */
    static final int MAX_DISCOVERED_PROVIDERS = PluginIndex.MAX_LEGACY_PROVIDERS;
    private static final int MAX_LEGACY_SELECTOR_LENGTH = 128;

    private final Map<Class<?>, Map<String, Object>> providers;
    private final List<Object> constructedProviders;
    private final ClassLoader pluginClassLoader;
    private final PluginSpiFacades.CallbackTracker callbackTracker;
    private final List<CompletableFuture<Void>> contributionCleanup = new ArrayList<>();
    private RegistryState state = RegistryState.OPEN;

    public LegacyServiceLoaderProviderRegistry(ClassLoader classLoader) {
        this(classLoader, LegacyServiceLoaderProviderRegistry::serviceProviders,
                MAX_DISCOVERED_PROVIDERS);
    }

    /** Package-private discovery seam for bounded/adversarial lifecycle tests. */
    LegacyServiceLoaderProviderRegistry(ClassLoader classLoader,
                                        ProviderSource providerSource,
                                        int maximumProviders) {
        if (maximumProviders < 1) {
            throw new IllegalArgumentException("maximumProviders must be positive");
        }
        this.pluginClassLoader = PluginThreadContext.effective(classLoader);
        this.callbackTracker = new PluginSpiFacades.CallbackTracker();
        Objects.requireNonNull(providerSource, "providerSource");

        Map<Class<?>, Map<String, Object>> discovered = new LinkedHashMap<>();
        List<Object> constructed = new ArrayList<>();
        PluginSpiFacades.ProductReservations productReservations =
                new PluginSpiFacades.ProductReservations();
        DiscoveryBudget discoveryBudget = new DiscoveryBudget(maximumProviders);
        try {
            scan(discovered, constructed, ContributionKind.APP_STATE_MACHINE,
                    AppStateMachineProvider.class, AppStateMachineProvider::id,
                    providerSource, discoveryBudget, productReservations);
            scan(discovered, constructed, ContributionKind.SEQUENCER_MODE,
                    SequencerModeProvider.class, SequencerModeProvider::id,
                    providerSource, discoveryBudget, productReservations);
            scan(discovered, constructed, ContributionKind.L1_OBSERVER,
                    L1ObserverProvider.class, L1ObserverProvider::type,
                    providerSource, discoveryBudget, productReservations);
            scan(discovered, constructed, ContributionKind.SIGNER_PROVIDER,
                    SignerProviderFactory.class, SignerProviderFactory::scheme,
                    providerSource, discoveryBudget, productReservations);
            scan(discovered, constructed, ContributionKind.EFFECT_EXECUTOR,
                    AppEffectExecutorFactory.class, AppEffectExecutorFactory::scheme,
                    providerSource, discoveryBudget, productReservations);
            scan(discovered, constructed, ContributionKind.FINALIZED_SINK,
                    FinalizedStreamSinkFactory.class, FinalizedStreamSinkFactory::scheme,
                    providerSource, discoveryBudget, productReservations);
        } catch (Throwable discoveryFailure) {
            Throwable outcome = closeReverse(constructed, discoveryFailure,
                    pluginClassLoader, callbackTracker);
            throw initializationFailure(outcome);
        }
        this.providers = Collections.unmodifiableMap(discovered);
        this.constructedProviders = List.copyOf(constructed);
    }

    private <P> void scan(Map<Class<?>, Map<String, Object>> destination,
                          List<Object> constructed,
                          ContributionKind kind,
                          Class<P> type,
                          Function<P, String> selector,
                          ProviderSource providerSource,
                          DiscoveryBudget discoveryBudget,
                          PluginSpiFacades.ProductReservations productReservations) {
        TreeMap<String, Object> byName = new TreeMap<>();
        Map<String, Class<?>> providerTypes = new TreeMap<>();
        try {
            Iterator<? extends ServiceLoader.Provider<?>> handles =
                    PluginThreadContext.call(pluginClassLoader,
                            () -> providerSource.providers(type, pluginClassLoader));
            while (PluginThreadContext.call(pluginClassLoader, handles::hasNext)) {
                discoveryBudget.claim(type);
                ServiceLoader.Provider<?> handle = Objects.requireNonNull(
                        PluginThreadContext.call(pluginClassLoader, handles::next),
                        "ServiceLoader returned a null provider handle");
                Object rawProvider = Objects.requireNonNull(
                        callbackTracker.call(() -> PluginThreadContext.call(
                                pluginClassLoader, handle::get)),
                        "ServiceLoader returned a null provider");
                constructed.add(rawProvider);
                productReservations.claimProvider(rawProvider);
                P provider = type.cast(rawProvider);
                String name = requireSelector(type, callbackTracker.call(() ->
                        PluginThreadContext.call(pluginClassLoader,
                                () -> selector.apply(provider))));
                Object facade = PluginSpiFacades.provider(
                        kind, provider, pluginClassLoader,
                        "legacy:" + provider.getClass().getName(),
                        name, provider.getClass().getName(), productReservations,
                        callbackTracker);
                Object prior = byName.putIfAbsent(name, facade);
                Class<?> priorType = providerTypes.putIfAbsent(name, provider.getClass());
                if (prior != null && priorType != provider.getClass()) {
                    throw new IllegalStateException("Duplicate " + type.getSimpleName()
                            + " selector '" + name + "': " + priorType.getName()
                            + " and " + provider.getClass().getName());
                }
            }
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatal(failure);
            throw new IllegalStateException(type.getSimpleName() + " discovery failed", failure);
        }
        destination.put(type, Collections.unmodifiableMap(new LinkedHashMap<>(byName)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Iterator<? extends ServiceLoader.Provider<?>> serviceProviders(
            Class<?> type,
            ClassLoader loader
    ) {
        return (Iterator) ServiceLoader.load((Class) type, loader).stream().iterator();
    }

    private static String requireSelector(Class<?> type, String selector) {
        if (selector == null || selector.isBlank() || !selector.equals(selector.trim())
                || selector.length() > MAX_LEGACY_SELECTOR_LENGTH) {
            throw new IllegalStateException(type.getSimpleName()
                    + " returned an invalid selector; expected non-blank, trimmed text of at most "
                    + MAX_LEGACY_SELECTOR_LENGTH + " characters");
        }
        return selector;
    }

    @Override
    public synchronized <P> Optional<P> find(Class<P> providerType, String selector) {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(selector, "selector");
        ensureOpen();
        Object value = providers.getOrDefault(providerType, Map.of()).get(selector);
        return value == null ? Optional.empty() : Optional.of(providerType.cast(value));
    }

    @Override
    public synchronized <P> List<String> names(Class<P> providerType) {
        Objects.requireNonNull(providerType, "providerType");
        ensureOpen();
        return List.copyOf(new ArrayList<>(
                providers.getOrDefault(providerType, Map.of()).keySet()));
    }

    @Override
    public synchronized void registerContributionCleanup(
            CompletableFuture<Void> completion
    ) {
        Objects.requireNonNull(completion, "completion");
        ensureOpen();
        contributionCleanup.removeIf(CompletableFuture::isDone);
        contributionCleanup.add(completion);
    }

    @Override
    public synchronized boolean hasPendingContributionCleanup() {
        return contributionCleanup.stream().anyMatch(completion -> !completion.isDone());
    }

    @Override
    public void requireContributionTeardownAllowed(String action) {
        callbackTracker.requireNotInCallback(action);
    }

    @Override
    public boolean hasPendingContributionCallbacks() {
        return callbackTracker.hasPending();
    }

    @Override
    public void awaitContributionCallbacks() {
        callbackTracker.awaitQuiescence();
    }

    @Override
    public void close() {
        callbackTracker.requireNotInCallback("close the legacy plugin provider registry");
        synchronized (this) {
            boolean interrupted = false;
            while (state == RegistryState.CLOSING) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (state == RegistryState.CLOSED) {
                return;
            }
            callbackTracker.seal();
            state = RegistryState.CLOSING;
        }

        Throwable failure = null;
        try {
            callbackTracker.awaitQuiescence();
            List<CompletableFuture<Void>> cleanup;
            synchronized (this) {
                cleanup = List.copyOf(contributionCleanup);
            }
            for (CompletableFuture<Void> completion : cleanup) {
                // Completion is a lifetime signal. Its exceptional value is
                // reported by the owning subsystem, not by registry teardown.
                completion.handle((ignored, completionFailure) -> null).join();
            }
            callbackTracker.sealCleanup();
            callbackTracker.awaitQuiescence();
            failure = closeReverse(constructedProviders, null,
                    pluginClassLoader, callbackTracker);
        } catch (Throwable closeFailure) {
            failure = LifecycleFailures.merge(failure, closeFailure);
        } finally {
            synchronized (this) {
                state = RegistryState.CLOSED;
                notifyAll();
            }
        }
        rethrowCloseFailure(failure);
    }

    private synchronized void ensureOpen() {
        if (state != RegistryState.OPEN) {
            throw new IllegalStateException("Plugin provider registry is closing or closed");
        }
    }

    private static Throwable closeReverse(List<Object> constructed,
                                          Throwable primary,
                                          ClassLoader loader,
                                          PluginSpiFacades.CallbackTracker callbackTracker) {
        Throwable outcome = primary;
        Set<Object> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = constructed.size() - 1; index >= 0; index--) {
            Object provider = constructed.get(index);
            if (!(provider instanceof AutoCloseable closeable) || !closed.add(provider)) {
                continue;
            }
            try {
                callbackTracker.runProviderTeardown(() ->
                        PluginThreadContext.run(loader, closeable::close));
            } catch (Throwable cleanupFailure) {
                outcome = LifecycleFailures.merge(outcome, cleanupFailure);
            }
        }
        return outcome;
    }

    private static IllegalStateException initializationFailure(Throwable failure) {
        LifecycleFailures.rethrowIfProcessFatal(failure);
        return new IllegalStateException(
                "Legacy plugin provider registry initialization failed", failure);
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        LifecycleFailures.rethrowIfProcessFatal(failure);
        throw new IllegalStateException("Legacy plugin provider close failed", failure);
    }

    @FunctionalInterface
    interface ProviderSource {
        Iterator<? extends ServiceLoader.Provider<?>> providers(
                Class<?> type,
                ClassLoader loader
        );
    }

    private static final class DiscoveryBudget {
        private final int maximum;
        private int discovered;

        private DiscoveryBudget(int maximum) {
            this.maximum = maximum;
        }

        private void claim(Class<?> type) {
            if (discovered == maximum) {
                throw new IllegalStateException("ServiceLoader provider discovery exceeds the "
                        + "global limit of " + maximum + " while scanning " + type.getName());
            }
            discovered++;
        }
    }

    private enum RegistryState {
        OPEN,
        CLOSING,
        CLOSED
    }
}
