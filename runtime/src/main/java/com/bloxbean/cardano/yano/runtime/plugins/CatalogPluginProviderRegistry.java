package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.catalog.BundleManifest;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationCustomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Lazy, catalog-owned provider handles keyed by semantic selector. */
final class CatalogPluginProviderRegistry implements PluginProviderRegistry, AutoCloseable {
    private static final int MAX_NODE_PLUGIN_DEPENDENCIES = 256;

    private final Map<Class<?>, Map<String, Entry>> entries;
    private final List<Entry> nodePlugins;
    private final List<Entry> closeOrder;
    private final List<Object> eagerLegacyInstances;
    private final ClassLoader pluginClassLoader;
    private final PluginSpiFacades.ProductReservations productReservations =
            new PluginSpiFacades.ProductReservations();
    private final PluginSpiFacades.CallbackTracker callbackTracker =
            new PluginSpiFacades.CallbackTracker();
    private final Set<Object> terminallyClosedInstances =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(new IdentityHashMap<>()));
    private final List<CompletableFuture<Void>> contributionCleanup = new ArrayList<>();
    /** Closed while one runtime generation is draining; reopened only by resume(). */
    private boolean cleanupRegistrationOpen = true;
    /** Initial admission starts open; only a real seal requires a resume barrier. */
    private boolean normalAdmissionSealed;
    private RegistryState state = RegistryState.OPEN;

    CatalogPluginProviderRegistry(List<Entry> entries,
                                  List<String> bundleOrder,
                                  List<Object> eagerLegacyInstances) {
        this(entries, bundleOrder, eagerLegacyInstances,
                Thread.currentThread().getContextClassLoader());
    }

    CatalogPluginProviderRegistry(List<Entry> entries,
                                  List<String> bundleOrder,
                                  List<Object> eagerLegacyInstances,
                                  ClassLoader pluginClassLoader) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < bundleOrder.size(); i++) {
            order.put(bundleOrder.get(i), i);
        }
        Map<Class<?>, TreeMap<String, Entry>> mutable = new LinkedHashMap<>();
        for (Entry entry : entries) {
            TreeMap<String, Entry> byName = mutable.computeIfAbsent(
                    entry.kind().serviceType(), ignored -> new TreeMap<>());
            Entry previous = byName.putIfAbsent(entry.name(), entry);
            if (previous != null) {
                throw new IllegalStateException("Duplicate selected plugin contribution "
                        + entry.kind().manifestKey() + "/" + entry.name() + " from bundles '"
                        + previous.bundleId() + "' and '" + entry.bundleId() + "'");
            }
        }
        Map<Class<?>, Map<String, Entry>> frozen = new LinkedHashMap<>();
        mutable.forEach((type, values) -> frozen.put(type,
                Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        this.entries = Collections.unmodifiableMap(frozen);
        this.nodePlugins = entries.stream()
                .filter(entry -> entry.kind() == ContributionKind.NODE_PLUGIN)
                .sorted(Comparator.comparingInt((Entry entry) -> order.getOrDefault(
                                entry.bundleId(), Integer.MAX_VALUE))
                        .thenComparing(Entry::bundleId))
                .toList();
        this.closeOrder = List.copyOf(entries);
        this.eagerLegacyInstances = List.copyOf(eagerLegacyInstances);
        this.eagerLegacyInstances.forEach(productReservations::preReserveProvider);
        this.pluginClassLoader = PluginThreadContext.effective(pluginClassLoader);
    }

    @Override
    public <P> Optional<P> find(Class<P> providerType, String selector) {
        Objects.requireNonNull(providerType, "providerType");
        requirePublicProviderType(providerType);
        Entry entry;
        PluginSpiFacades.CallbackTracker.CallbackAdmission admission;
        synchronized (this) {
            ensureOpen();
            entry = entries.getOrDefault(providerType, Map.of()).get(selector);
            if (entry == null) {
                return Optional.empty();
            }
            admission = callbackTracker.admit();
        }
        try (admission) {
            return Optional.of(providerType.cast(
                    entry.get(
                            pluginClassLoader,
                            terminallyClosedInstances,
                            productReservations,
                            callbackTracker)));
        }
    }

    @Override
    public <P> List<String> names(Class<P> providerType) {
        Objects.requireNonNull(providerType, "providerType");
        requirePublicProviderType(providerType);
        synchronized (this) {
            ensureOpen();
            return List.copyOf(entries.getOrDefault(providerType, Map.of()).keySet());
        }
    }

    @Override
    public synchronized <P> Optional<String> contributionOwner(
            Class<P> providerType,
            String selector
    ) {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(selector, "selector");
        requirePublicProviderType(providerType);
        ensureOpen();
        Entry entry = entries.getOrDefault(providerType, Map.of()).get(selector);
        return entry != null ? Optional.of(entry.bundleId()) : Optional.empty();
    }

    private static void requirePublicProviderType(Class<?> providerType) {
        if (providerType == NodePlugin.class) {
            throw new IllegalArgumentException(
                    "NodePlugin lifecycle contributions are owned exclusively by PluginManager");
        }
    }

    List<NodePlugin> nodePluginInstances() {
        PluginSpiFacades.CallbackTracker.CallbackAdmission admission;
        synchronized (this) {
            ensureOpen();
            admission = callbackTracker.admit();
        }
        // Keep the complete ordered snapshot under one admission. Otherwise a
        // close could begin between two entries and publish only a prefix to
        // the lifecycle manager.
        try (admission) {
            List<NodePlugin> result = new ArrayList<>(nodePlugins.size());
            for (Entry entry : nodePlugins) {
                result.add(NodePlugin.class.cast(
                        entry.get(
                                pluginClassLoader,
                                terminallyClosedInstances,
                                productReservations,
                                callbackTracker)));
            }
            return List.copyOf(result);
        }
    }

    synchronized void markNodePluginClosed(NodePlugin plugin) {
        ensureOpen();
        Object ownedInstance = null;
        for (Entry entry : nodePlugins) {
            Object instance = entry.instanceOrNull();
            if (instance == plugin || entry.exposedInstanceOrNull() == plugin) {
                ownedInstance = instance;
                break;
            }
        }
        if (ownedInstance == null) {
            throw new IllegalArgumentException(
                    "Closed NodePlugin instance does not belong to the provider registry");
        }
        terminallyClosedInstances.add(ownedInstance);
    }

    @Override
    public synchronized void registerContributionCleanup(CompletableFuture<Void> completion) {
        Objects.requireNonNull(completion, "completion");
        if (state != RegistryState.OPEN) {
            throw new IllegalStateException("Plugin provider registry is closing or closed");
        }
        if (!cleanupRegistrationOpen) {
            throw new IllegalStateException(
                    "Plugin contribution cleanup registration is sealed");
        }
        contributionCleanup.removeIf(CompletableFuture::isDone);
        contributionCleanup.add(completion);
    }

    @Override
    public synchronized boolean hasPendingContributionCleanup() {
        return contributionCleanup.stream().anyMatch(completion -> !completion.isDone());
    }

    synchronized int contributionCleanupCountForTesting() {
        return contributionCleanup.size();
    }

    @Override
    public synchronized void resumeContributionCallbacks() {
        ensureOpen();
        if (!normalAdmissionSealed) {
            return;
        }
        if (callbackTracker.hasPending()) {
            throw new IllegalStateException(
                    "Cannot resume plugin callbacks while callbacks are active");
        }
        if (hasPendingContributionCleanup()) {
            throw new IllegalStateException(
                    "Cannot resume plugin callbacks while contribution cleanup is pending");
        }
        contributionCleanup.removeIf(CompletableFuture::isDone);
        cleanupRegistrationOpen = true;
        callbackTracker.resume();
        normalAdmissionSealed = false;
    }

    @Override
    public synchronized void sealContributionCallbacks() {
        ensureOpen();
        callbackTracker.seal();
        normalAdmissionSealed = true;
    }

    @Override
    public void requireContributionTeardownAllowed(String action) {
        callbackTracker.requireNotInCallback(action);
    }

    /** Shared admission boundary for catalog-owned NodePlugin lifecycle code. */
    PluginSpiFacades.CallbackTracker callbackTracker() {
        return callbackTracker;
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
    public void awaitContributionCleanup() {
        synchronized (this) {
            if (!normalAdmissionSealed) {
                throw new IllegalStateException(
                        "Seal plugin contribution callbacks before awaiting cleanup");
            }
        }
        // Do not freeze cleanup registration until every ordinary callback
        // admitted before the seal has returned. This makes the public
        // seal/await protocol safe even without a separate explicit await call.
        callbackTracker.awaitQuiescence();
        List<CompletableFuture<Void>> snapshot;
        synchronized (this) {
            if (!normalAdmissionSealed) {
                throw new IllegalStateException(
                        "Plugin contribution callbacks resumed while awaiting cleanup");
            }
            // Freeze the generation before taking the snapshot. Ordinary
            // callback admission has already been sealed, so no legitimate
            // new product owner can appear after this point. Terminal product
            // close callbacks remain admitted until all captured owners end.
            cleanupRegistrationOpen = false;
            snapshot = List.copyOf(contributionCleanup);
        }
        // Completion is a lifetime signal, not an error channel. Even an
        // exceptionally completed signal proves that its callback owner has
        // terminated, so wait for every entry before provider/loader teardown.
        for (CompletableFuture<Void> completion : snapshot) {
            completion.handle((ignored, failure) -> null).join();
        }
        // No registered owner can now start another terminal close. Seal that
        // distinct admission class and await any callback whose completion
        // signal raced just ahead of its tracker epilogue.
        callbackTracker.sealCleanup();
        callbackTracker.awaitQuiescence();
    }

    @Override
    public void close() {
        // A provider callback cannot wait for the admission that currently
        // owns it. Fail before publishing CLOSING so another thread never
        // observes a registry stranded by recursive teardown.
        callbackTracker.requireNotInCallback("close the plugin provider registry");
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
            // CLOSING is established before the final cleanup snapshot. A
            // concurrent registration therefore cannot be omitted from the
            // provider/class-loader lifetime fence.
            callbackTracker.seal();
            state = RegistryState.CLOSING;
            normalAdmissionSealed = true;
        }

        Throwable failure = null;
        try {
            callbackTracker.awaitQuiescence();
        } catch (Throwable callbackFailure) {
            synchronized (this) {
                state = RegistryState.OPEN;
                notifyAll();
            }
            rethrowCloseFailure(callbackFailure);
            return;
        }
        try {
            awaitContributionCleanup();
        } catch (Throwable contributionFailure) {
            failure = recordCloseFailure(failure, contributionFailure);
        }
        try {
            Throwable providerFailure = closeProviders();
            if (providerFailure != null) {
                failure = recordCloseFailure(failure, providerFailure);
            }
        } catch (Throwable providerFailure) {
            failure = recordCloseFailure(failure, providerFailure);
        } finally {
            synchronized (this) {
                state = RegistryState.CLOSED;
                notifyAll();
            }
        }
        rethrowCloseFailure(failure);
    }

    private Throwable closeProviders() {
        Set<Object> closedInstances = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Entry> reverse = new ArrayList<>(closeOrder);
        Collections.reverse(reverse);
        Throwable failure = null;
        for (Entry entry : reverse) {
            Object instance = entry.instanceOrNull();
            if (instance == null || !(instance instanceof AutoCloseable closeable)
                    || terminallyClosedInstances.contains(instance)
                    || !closedInstances.add(instance)) {
                continue;
            }
            try {
                callbackTracker.runProviderTeardown(() ->
                        PluginThreadContext.run(pluginClassLoader, closeable::close));
            } catch (Throwable e) {
                failure = recordCloseFailure(failure, e);
            }
        }
        for (int i = eagerLegacyInstances.size() - 1; i >= 0; i--) {
            Object instance = eagerLegacyInstances.get(i);
            if (!(instance instanceof AutoCloseable closeable)
                    || terminallyClosedInstances.contains(instance)
                    || !closedInstances.add(instance)) {
                continue;
            }
            try {
                callbackTracker.runProviderTeardown(() ->
                        PluginThreadContext.run(pluginClassLoader, closeable::close));
            } catch (Throwable e) {
                failure = recordCloseFailure(failure, e);
            }
        }
        return failure;
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        LifecycleFailures.rethrowIfProcessFatalReachable(failure);
        throw new PluginActivationException("Plugin provider close failed", failure);
    }

    private synchronized void ensureOpen() {
        if (state != RegistryState.OPEN) {
            throw new IllegalStateException("Plugin provider registry is closing or closed");
        }
    }

    private enum RegistryState {
        OPEN,
        CLOSING,
        CLOSED
    }

    private static Throwable recordCloseFailure(Throwable current, Throwable next) {
        return LifecycleFailures.merge(current, next);
    }

    static final class Entry {
        private final String bundleId;
        private final ContributionKind kind;
        private final String name;
        private final String providerClass;
        private final BundleManifest manifest;
        private final Supplier<?> supplier;
        private final ImmutableNodePluginMetadata nodePluginMetadata;
        private Object instance;
        private Object exposedInstance;
        private RuntimeException constructionFailure;
        private ActivationState activationState = ActivationState.INACTIVE;
        private Thread activationOwner;

        Entry(String bundleId,
              ContributionKind kind,
              String name,
              String providerClass,
              BundleManifest manifest,
              Supplier<?> supplier) {
            this(bundleId, kind, name, providerClass, manifest, supplier, null);
        }

        Entry(String bundleId,
              ContributionKind kind,
              String name,
              String providerClass,
              BundleManifest manifest,
              Supplier<?> supplier,
              ImmutableNodePluginMetadata nodePluginMetadata) {
            this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.name = Objects.requireNonNull(name, "name");
            this.providerClass = Objects.requireNonNull(providerClass, "providerClass");
            this.manifest = manifest;
            this.supplier = Objects.requireNonNull(supplier, "supplier");
            this.nodePluginMetadata = nodePluginMetadata;
        }

        String bundleId() {
            return bundleId;
        }

        ContributionKind kind() {
            return kind;
        }

        String name() {
            return name;
        }

        Object get(
                ClassLoader pluginClassLoader,
                Set<Object> terminallyClosedInstances,
                PluginSpiFacades.ProductReservations productReservations,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            // One logical admission owns construction through validation and
            // rollback, while every plugin callback below gets an independent
            // TCCL scope. A callback that corrupts TCCL therefore cannot poison
            // the next metadata callback.
            return callbackTracker.call(() -> getUnderAdmission(
                    pluginClassLoader, terminallyClosedInstances,
                    productReservations, callbackTracker));
        }

        private Object getUnderAdmission(
                ClassLoader pluginClassLoader,
                Set<Object> terminallyClosedInstances,
                PluginSpiFacades.ProductReservations productReservations,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            ActivationDecision decision = awaitActivationDecision();
            if (decision.state() == ActivationState.ACTIVE) {
                return decision.exposedInstance();
            }
            if (decision.state() == ActivationState.FAILED) {
                throw decision.failure();
            }

            try {
                ConstructedProvider constructed = construct(
                        pluginClassLoader, terminallyClosedInstances,
                        productReservations, callbackTracker);
                synchronized (this) {
                    instance = constructed.instance();
                    exposedInstance = constructed.exposedInstance();
                    activationState = ActivationState.ACTIVE;
                    activationOwner = null;
                    notifyAll();
                }
                return constructed.exposedInstance();
            } catch (Throwable failure) {
                // Only failures after which in-process recovery is unsafe may
                // escape the catalog boundary as an Error. AssertionError and
                // sneaky checked failures are plugin activation failures: cache
                // one fixed platform diagnostic so every waiter observes the
                // same terminal result and plugin messages are not promoted.
                Error fatal = LifecycleFailures.findProcessFatalReachable(failure);
                if (fatal != null) {
                    synchronized (this) {
                        activationState = ActivationState.INACTIVE;
                        activationOwner = null;
                        notifyAll();
                    }
                    throw fatal;
                }
                RuntimeException activationFailure = failure instanceof PluginActivationException
                        ? (PluginActivationException) failure
                        : activationFailure("construct provider", failure);
                synchronized (this) {
                    constructionFailure = activationFailure;
                    activationState = ActivationState.FAILED;
                    activationOwner = null;
                    notifyAll();
                }
                throw activationFailure;
            }
        }

        private ActivationDecision awaitActivationDecision() {
            boolean interrupted = false;
            try {
                synchronized (this) {
                    while (activationState == ActivationState.ACTIVATING) {
                        if (activationOwner == Thread.currentThread()) {
                            throw activationFailure(
                                    "resolve a recursively requested provider",
                                    new IllegalStateException(
                                            "Recursive activation of provider '"
                                                    + providerClass + "'"));
                        }
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    if (activationState == ActivationState.ACTIVE) {
                        return new ActivationDecision(
                                ActivationState.ACTIVE, exposedInstance, null);
                    }
                    if (activationState == ActivationState.FAILED) {
                        return new ActivationDecision(
                                ActivationState.FAILED, null, constructionFailure);
                    }
                    activationState = ActivationState.ACTIVATING;
                    activationOwner = Thread.currentThread();
                    return new ActivationDecision(
                            ActivationState.ACTIVATING, null, null);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private ConstructedProvider construct(
                ClassLoader pluginClassLoader,
                Set<Object> terminallyClosedInstances,
                PluginSpiFacades.ProductReservations productReservations,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            Object candidate = null;
            boolean ownsProviderIdentity = false;
            try {
                candidate = Objects.requireNonNull(pluginCallback(
                                callbackTracker, pluginClassLoader, supplier::get),
                        "ServiceLoader returned a null provider");
                productReservations.claimProvider(candidate);
                ownsProviderIdentity = true;
                if (!kind.serviceType().isInstance(candidate)) {
                    throw new IllegalStateException("Provider '" + providerClass
                            + "' is not a " + kind.serviceType().getName());
                }
                String actual = nodePluginMetadata != null
                        ? nodePluginMetadata.id()
                        : selector(kind, candidate, pluginClassLoader, callbackTracker);
                if (!name.equals(actual)) {
                    throw new IllegalStateException("Provider '" + providerClass
                            + "' selector mismatch: manifest='" + name
                            + "', provider='" + actual + "'");
                }
                ImmutableNodePluginMetadata immutableMetadata = nodePluginMetadata;
                if (candidate instanceof NodePlugin plugin && manifest != null) {
                    immutableMetadata = verifyNodePlugin(
                            plugin, manifest, pluginClassLoader, callbackTracker);
                }
                Object exposed = immutableMetadata == null
                        ? PluginSpiFacades.provider(
                                kind, candidate, pluginClassLoader, bundleId, name,
                                providerClass, productReservations, callbackTracker)
                        : immutableNodePluginFacade(
                                (NodePlugin) candidate, immutableMetadata,
                                pluginClassLoader, callbackTracker);
                return new ConstructedProvider(candidate, exposed);
            } catch (Throwable failure) {
                Throwable outcome = failure;
                if (ownsProviderIdentity
                        && !(failure instanceof PluginSpiFacades.ProviderIdentityReuseException)) {
                    outcome = closeFailedCandidate(candidate, outcome, terminallyClosedInstances,
                            pluginClassLoader, callbackTracker);
                }
                LifecycleFailures.rethrowIfProcessFatalReachable(outcome);
                throw activationFailure("construct provider", outcome);
            }
        }

        private PluginActivationException activationFailure(
                String action,
                Throwable failure
        ) {
            return new PluginActivationException(
                    "Failed to activate provider '" + providerClass + "' for "
                            + kind.manifestKey() + "/" + name + " in plugin bundle '"
                            + bundleId + "' while attempting to " + action,
                    bundleId, kind.manifestKey(), name, providerClass, failure);
        }

        private static Throwable closeFailedCandidate(
                Object candidate,
                Throwable primary,
                Set<Object> terminallyClosedInstances,
                ClassLoader pluginClassLoader,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            if (!(candidate instanceof AutoCloseable closeable)) {
                return primary;
            }
            // A close callback is terminal even when it throws. Record the
            // attempt before invoking it so eager legacy teardown cannot
            // invoke the same candidate twice.
            if (!terminallyClosedInstances.add(candidate)) {
                return primary;
            }
            try {
                callbackTracker.run(() -> PluginThreadContext.run(
                        pluginClassLoader, closeable::close));
            } catch (Throwable cleanupFailure) {
                return LifecycleFailures.merge(primary, cleanupFailure);
            }
            return primary;
        }

        synchronized Object instanceOrNull() {
            return instance;
        }

        synchronized Object exposedInstanceOrNull() {
            return exposedInstance;
        }

        private enum ActivationState {
            INACTIVE,
            ACTIVATING,
            ACTIVE,
            FAILED
        }

        private record ActivationDecision(
                ActivationState state,
                Object exposedInstance,
                RuntimeException failure
        ) {
        }

        private record ConstructedProvider(Object instance, Object exposedInstance) {
        }

        private static ImmutableNodePluginMetadata verifyNodePlugin(
                NodePlugin plugin,
                BundleManifest manifest,
                ClassLoader pluginClassLoader,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            String id = pluginCallback(
                    callbackTracker, pluginClassLoader, plugin::id);
            if (!manifest.id().equals(id)) {
                throw new IllegalStateException("NodePlugin id does not match bundle id");
            }
            String version = pluginCallback(
                    callbackTracker, pluginClassLoader, plugin::version);
            if (!manifest.version().toString().equals(version)) {
                throw new IllegalStateException("NodePlugin version does not match bundle version");
            }
            Set<String> declared = Set.copyOf(manifest.dependencies().stream()
                    .map(dependency -> dependency.id())
                    .collect(java.util.stream.Collectors.toSet()));
            Set<String> actual = pluginCallback(
                    callbackTracker, pluginClassLoader, plugin::dependsOn);
            Set<String> dependencies = actual == null ? null : snapshotDependencies(
                    actual, pluginClassLoader, callbackTracker);
            if (!declared.equals(dependencies)) {
                throw new IllegalStateException("NodePlugin dependencies do not match bundle dependencies");
            }
            Set<PluginCapability> capabilities = snapshotCapabilities(
                    pluginCallback(callbackTracker, pluginClassLoader, plugin::capabilities),
                    pluginClassLoader, callbackTracker);
            return new ImmutableNodePluginMetadata(
                    manifest.id(), manifest.version().toString(), declared, capabilities);
        }

        private static Set<String> snapshotDependencies(
                Set<String> dependencies,
                ClassLoader pluginClassLoader,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            Iterator<String> iterator = Objects.requireNonNull(pluginCallback(
                    callbackTracker, pluginClassLoader, dependencies::iterator),
                    "NodePlugin dependency iterator must not be null");
            Set<String> snapshot = new HashSet<>();
            int traversed = 0;
            while (pluginCallback(
                    callbackTracker, pluginClassLoader, iterator::hasNext)) {
                if (++traversed > MAX_NODE_PLUGIN_DEPENDENCIES) {
                    throw new IllegalArgumentException(
                            "NodePlugin dependencies must contain at most "
                                    + MAX_NODE_PLUGIN_DEPENDENCIES + " entries");
                }
                snapshot.add(Objects.requireNonNull(pluginCallback(
                                callbackTracker, pluginClassLoader, iterator::next),
                        "NodePlugin dependencies must not contain null"));
            }
            return Set.copyOf(snapshot);
        }

        private static Set<PluginCapability> snapshotCapabilities(
                Set<PluginCapability> capabilities,
                ClassLoader pluginClassLoader,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            if (capabilities == null) {
                throw new IllegalStateException("NodePlugin capabilities must not be null");
            }
            Iterator<PluginCapability> iterator = Objects.requireNonNull(pluginCallback(
                    callbackTracker, pluginClassLoader, capabilities::iterator),
                    "NodePlugin capability iterator must not be null");
            Set<PluginCapability> snapshot = new HashSet<>();
            int traversed = 0;
            while (pluginCallback(callbackTracker, pluginClassLoader, iterator::hasNext)) {
                if (++traversed > PluginCapability.values().length) {
                    throw new IllegalArgumentException(
                            "NodePlugin capabilities contain too many entries");
                }
                snapshot.add(Objects.requireNonNull(pluginCallback(
                                callbackTracker, pluginClassLoader, iterator::next),
                        "NodePlugin capabilities must not contain null"));
            }
            return Set.copyOf(snapshot);
        }

        private static String selector(
                ContributionKind kind,
                Object provider,
                ClassLoader pluginClassLoader,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            String selector = switch (kind) {
                case NODE_PLUGIN -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((NodePlugin) provider)::id);
                case APP_STATE_MACHINE -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((AppStateMachineProvider) provider)::id);
                case SEQUENCER_MODE -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((SequencerModeProvider) provider)::id);
                case L1_OBSERVER -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((L1ObserverProvider) provider)::type);
                case SIGNER_PROVIDER -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((SignerProviderFactory) provider)::scheme);
                case EFFECT_EXECUTOR -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((AppEffectExecutorFactory) provider)::scheme);
                case FINALIZED_SINK -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((FinalizedStreamSinkFactory) provider)::scheme);
                case DOMAIN_API -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((DomainApiProvider) provider)::id);
                case HEALTH -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((PluginHealthProvider) provider)::id);
                case METRICS -> pluginCallback(
                        callbackTracker, pluginClassLoader,
                        ((PluginMetricsProvider) provider)::id);
            };
            if (selector == null || selector.isBlank() || !selector.equals(selector.trim())) {
                throw new IllegalStateException("Provider returned an invalid selector");
            }
            return selector;
        }

        private static <T> T pluginCallback(
                PluginSpiFacades.CallbackTracker callbackTracker,
                ClassLoader pluginClassLoader,
                Supplier<T> callback
        ) {
            return callbackTracker.call(() -> PluginThreadContext.call(
                    pluginClassLoader, callback::get));
        }
    }

    /** Host-owned immutable metadata with lifecycle calls retained behind the catalog fence. */
    private static NodePlugin immutableNodePluginFacade(
            NodePlugin delegate,
            ImmutableNodePluginMetadata metadata,
            ClassLoader pluginClassLoader,
            PluginSpiFacades.CallbackTracker callbackTracker
    ) {
        if (delegate instanceof HeaderValidationCustomizer customizer) {
            return new ManifestedHeaderValidationNodePluginFacade(
                    delegate, customizer, metadata, pluginClassLoader, callbackTracker);
        }
        return new ManifestedNodePluginFacade(
                delegate, metadata, pluginClassLoader, callbackTracker);
    }

    private static class ManifestedNodePluginFacade implements NodePlugin {
        protected final NodePlugin delegate;
        protected final ClassLoader pluginClassLoader;
        protected final PluginSpiFacades.CallbackTracker callbackTracker;
        private final ImmutableNodePluginMetadata metadata;

        private ManifestedNodePluginFacade(
                NodePlugin delegate,
                ImmutableNodePluginMetadata metadata,
                ClassLoader pluginClassLoader,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            this.delegate = delegate;
            this.metadata = metadata;
            this.pluginClassLoader = pluginClassLoader;
            this.callbackTracker = callbackTracker;
        }

        @Override
        public String id() {
            return metadata.id();
        }

        @Override
        public String version() {
            return metadata.version();
        }

        @Override
        public Set<String> dependsOn() {
            return metadata.dependencies();
        }

        @Override
        public Set<PluginCapability> capabilities() {
            return metadata.capabilities();
        }

        @Override
        public void init(PluginContext ctx) {
            callbackTracker.run(() -> PluginThreadContext.run(
                    pluginClassLoader, () -> delegate.init(ctx)));
        }

        @Override
        public void start() {
            callbackTracker.run(() -> PluginThreadContext.run(
                    pluginClassLoader, delegate::start));
        }

        @Override
        public void stop() {
            callbackTracker.runNodePluginTeardown(() -> PluginThreadContext.run(
                    pluginClassLoader, delegate::stop));
        }

        @Override
        public void close() {
            callbackTracker.runNodePluginTeardown(() -> PluginThreadContext.run(
                    pluginClassLoader, delegate::close));
        }
    }

    /** Preserve the one additional runtime role consumed via instanceof by PluginManager. */
    private static final class ManifestedHeaderValidationNodePluginFacade
            extends ManifestedNodePluginFacade implements HeaderValidationCustomizer {
        private final HeaderValidationCustomizer customizer;

        private ManifestedHeaderValidationNodePluginFacade(
                NodePlugin delegate,
                HeaderValidationCustomizer customizer,
                ImmutableNodePluginMetadata metadata,
                ClassLoader pluginClassLoader,
                PluginSpiFacades.CallbackTracker callbackTracker
        ) {
            super(delegate, metadata, pluginClassLoader, callbackTracker);
            this.customizer = customizer;
        }

        @Override
        public void customize(
                com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationPipeline.Builder builder
        ) {
            callbackTracker.run(() -> PluginThreadContext.run(
                    pluginClassLoader, () -> customizer.customize(builder)));
        }
    }

    record ImmutableNodePluginMetadata(
            String id,
            String version,
            Set<String> dependencies,
            Set<PluginCapability> capabilities
    ) {
        ImmutableNodePluginMetadata {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            dependencies = Set.copyOf(dependencies);
            capabilities = Set.copyOf(capabilities);
        }
    }
}
