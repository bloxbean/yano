package org.yanoproject.runtime.plugins;

import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.genesis.GenesisUtxo;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginActivationException;
import org.yanoproject.api.plugin.operations.PluginHealthCheckDescriptor;
import org.yanoproject.api.plugin.operations.PluginHealthContext;
import org.yanoproject.api.plugin.operations.PluginHealthProvider;
import org.yanoproject.api.plugin.operations.PluginHealthSnapshot;
import org.yanoproject.api.plugin.operations.PluginHealthSource;
import org.yanoproject.api.plugin.operations.PluginMetricDescriptor;
import org.yanoproject.api.plugin.operations.PluginMetricSnapshot;
import org.yanoproject.api.plugin.operations.PluginMetricsContext;
import org.yanoproject.api.plugin.operations.PluginMetricsProvider;
import org.yanoproject.api.plugin.operations.PluginMetricsSource;
import org.yanoproject.api.plugin.spi.ContributionKindBinding;
import org.yanoproject.api.plugin.spi.FacadeSupport;
import org.yanoproject.api.plugin.spi.PluginCallbackScope;
import org.yanoproject.api.utxo.index.IndexRequirements;
import org.yanoproject.api.utxo.index.IndexWriter;
import org.yanoproject.api.utxo.index.UtxoChanges;
import org.yanoproject.api.utxo.index.UtxoIndexContext;
import org.yanoproject.api.utxo.index.UtxoIndexContributor;
import org.yanoproject.api.utxo.index.UtxoIndexContributorProvider;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.runtime.util.LifecycleFailures;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Callback plumbing shared by every contribution kind, the {@link PluginCallbackScope}
 * through which kind bindings build their facades, and the bindings of the built-in kinds.
 */
final class PluginSpiFacades {
    /** Bindings of the built-in kinds, keyed by manifest key. */
    static final Map<String, ContributionKindBinding<?>> BUILT_IN_BINDINGS = Map.of(
            ContributionKind.NODE_PLUGIN.manifestKey(), new BuiltInBinding<>(
                    ContributionKind.NODE_PLUGIN, NodePlugin.class, NodePlugin::id,
                    (provider, scope) -> provider),
            ContributionKind.UTXO_INDEX_CONTRIBUTOR.manifestKey(), new BuiltInBinding<>(
                    ContributionKind.UTXO_INDEX_CONTRIBUTOR, UtxoIndexContributorProvider.class,
                    UtxoIndexContributorProvider::id, UtxoIndexProviderFacade::new),
            ContributionKind.HEALTH.manifestKey(), new BuiltInBinding<>(
                    ContributionKind.HEALTH, PluginHealthProvider.class, PluginHealthProvider::id,
                    HealthProviderFacade::new),
            ContributionKind.METRICS.manifestKey(), new BuiltInBinding<>(
                    ContributionKind.METRICS, PluginMetricsProvider.class, PluginMetricsProvider::id,
                    MetricsProviderFacade::new));

    private PluginSpiFacades() {
    }

    private static <T, X extends Throwable> T pluginCall(
            CallbackTracker callbacks,
            ClassLoader loader,
            PluginThreadContext.ThrowingSupplier<T, X> callback
    ) throws X {
        return callbacks.call(() -> PluginThreadContext.call(loader, callback));
    }

    private static <X extends Throwable> void pluginRun(
            CallbackTracker callbacks,
            ClassLoader loader,
            PluginThreadContext.ThrowingRunnable<X> callback
    ) throws X {
        callbacks.run(() -> PluginThreadContext.run(loader, callback));
    }

    /**
     * Run terminal product cleanup after ordinary callback admission has been
     * sealed. Cleanup remains tracked and admitted until every registered
     * product owner reaches its terminal lifetime barrier.
     */
    private static <X extends Throwable> void pluginCleanupRun(
            CallbackTracker callbacks,
            ClassLoader loader,
            PluginThreadContext.ThrowingRunnable<X> callback
    ) throws X {
        callbacks.runCleanup(() -> PluginThreadContext.run(loader, callback));
    }

    /**
     * Materialize a plugin-owned factory result through exactly one iterator.
     * Each iterator callback gets its own TCCL boundary so a badly behaved
     * iterator cannot leak a replacement loader into the next callback.
     */
    private static <T> List<T> snapshotFactoryProducts(
            List<T> values,
            ClassLoader loader,
            CallbackTracker callbacks,
            ProductReservations products
    ) {
        List<T> snapshot = new ArrayList<>();
        try {
            Iterator<T> iterator = pluginCall(callbacks, loader, values::iterator);
            while (pluginCall(callbacks, loader, iterator::hasNext)) {
                if (snapshot.size() == FacadeSupport.MAX_FACTORY_PRODUCTS_PER_CALLBACK) {
                    throw new IllegalStateException(
                            "Plugin factory result must contain at most "
                                    + FacadeSupport.MAX_FACTORY_PRODUCTS_PER_CALLBACK + " products");
                }
                snapshot.add(pluginCall(callbacks, loader, iterator::next));
            }
            return snapshot;
        } catch (Throwable traversalFailure) {
            List<Object> terminalProducts = products.reserveCapturedAfterTraversalFailure(
                    snapshot.stream().map(value -> (Object) value).toList());
            closeTerminalProducts(traversalFailure, terminalProducts, loader, callbacks);
            throw propagate(traversalFailure,
                    "Plugin factory result traversal failed");
        }
    }

    static <P> P provider(
            ContributionKindBinding<P> binding,
            Object delegate,
            ClassLoader loader,
            String bundleId,
            String selector,
            String providerClass
    ) {
        return provider(binding, delegate, loader, bundleId, selector, providerClass,
                new ProductReservations(), new CallbackTracker(), false);
    }

    /**
     * @param manifestIdentity true only when {@code bundleId} is a validated catalog manifest's id; callback
     *                         failures then carry it as host provenance ({@link HostPluginActivationException})
     */
    static <P> P provider(
            ContributionKindBinding<P> binding,
            Object delegate,
            ClassLoader loader,
            String bundleId,
            String selector,
            String providerClass,
            ProductReservations products,
            CallbackTracker callbacks,
            boolean manifestIdentity
    ) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(products, "products");
        Objects.requireNonNull(callbacks, "callbacks");
        ClassLoader effectiveLoader = PluginThreadContext.effective(loader);
        ActivationContext activation = new ActivationContext(
                bundleId, binding.manifestKey(), selector, providerClass, manifestIdentity);
        return Objects.requireNonNull(binding.facade(binding.serviceType().cast(delegate),
                        new CatalogCallbackScope(effectiveLoader, activation, products, callbacks)),
                "Contribution kind binding returned a null facade");
    }

    /**
     * Registry-wide admission fence for callbacks into plugin-owned code.
     * Nested facade calls share their outer admission so a shutdown seal
     * cannot strand a product halfway through one logical callback.
     *
     * <p>The tracker also records which bundle's own {@code NodePlugin.init} or
     * host assembly step is the innermost plugin code on a thread. Every
     * admitted callback suspends that owner identity until it returns, so code
     * reached through a nested provider callback can never act as the
     * enclosing owner.</p>
     */
    static final class CallbackTracker {
        private final ThreadLocal<Integer> localDepth =
                ThreadLocal.withInitial(() -> 0);
        private final ThreadLocal<String> owner = new ThreadLocal<>();
        private boolean accepting = true;
        private boolean acceptingCleanup = true;
        private int active;

        <T, X extends Throwable> T call(
                PluginThreadContext.ThrowingSupplier<T, X> callback
        ) throws X {
            return call(callback, Admission.ORDINARY);
        }

        private <T, X extends Throwable> T call(
                PluginThreadContext.ThrowingSupplier<T, X> callback,
                Admission admission
        ) throws X {
            Objects.requireNonNull(callback, "callback");
            try (CallbackAdmission ignored = admit(admission)) {
                return callback.get();
            }
        }

        /**
         * Establish ordinary admission without invoking plugin code. The
         * catalog registry uses this while holding its short state monitor so
         * OPEN validation and callback admission have one ordering point with
         * CLOSING; plugin code runs only after that monitor is released.
         */
        CallbackAdmission admit() {
            return admit(Admission.ORDINARY);
        }

        /**
         * Runs {@code callback} as {@code bundleId}'s own code, restoring the
         * previous owner afterwards.
         */
        <T, X extends Throwable> T callAsOwner(
                String bundleId,
                PluginThreadContext.ThrowingSupplier<T, X> callback
        ) throws X {
            Objects.requireNonNull(bundleId, "bundleId");
            String previous = owner.get();
            owner.set(bundleId);
            try {
                return callback.get();
            } finally {
                restoreOwner(previous);
            }
        }

        /** The bundle whose own init or assembly code is innermost on this thread, if any. */
        Optional<String> owner() {
            return Optional.ofNullable(owner.get());
        }

        private void restoreOwner(String previous) {
            if (previous == null) {
                owner.remove();
            } else {
                owner.set(previous);
            }
        }

        private CallbackAdmission admit(Admission admission) {
            int depth = localDepth.get();
            boolean root = depth == 0;
            if (root) {
                synchronized (this) {
                    boolean admitted = switch (admission) {
                        case ORDINARY -> accepting;
                        case CLEANUP -> acceptingCleanup;
                        case NODE_PLUGIN_TEARDOWN, PROVIDER_TEARDOWN -> true;
                    };
                    if (!admitted) {
                        localDepth.remove();
                        throw new IllegalStateException(
                                admission == Admission.CLEANUP
                                        ? "Plugin cleanup callback admission is sealed"
                                        : "Plugin callback admission is sealed");
                    }
                    active++;
                }
            }
            localDepth.set(depth + 1);
            String previousOwner = owner.get();
            owner.remove();
            return new CallbackAdmission(this, Thread.currentThread(), previousOwner);
        }

        private void releaseAdmission(Thread thread, String previousOwner) {
            if (thread != Thread.currentThread()) {
                throw new IllegalStateException(
                        "Plugin callback admission must close on its owning thread");
            }
            restoreOwner(previousOwner);
            int remainingDepth = localDepth.get() - 1;
            if (remainingDepth < 0) {
                throw new IllegalStateException("Plugin callback admission underflow");
            }
            if (remainingDepth == 0) {
                localDepth.remove();
                synchronized (this) {
                    active--;
                    if (active == 0) {
                        notifyAll();
                    }
                }
            } else {
                localDepth.set(remainingDepth);
            }
        }

        <X extends Throwable> void run(
                PluginThreadContext.ThrowingRunnable<X> callback
        ) throws X {
            call(() -> {
                callback.run();
                return null;
            });
        }

        <X extends Throwable> void runCleanup(
                PluginThreadContext.ThrowingRunnable<X> callback
        ) throws X {
            call(() -> {
                callback.run();
                return null;
            }, Admission.CLEANUP);
        }

        /**
         * Track catalog-owned provider close callbacks after ordinary and
         * product-cleanup admission have both been sealed. The callback is
         * synchronous, but it still needs callback depth so a provider cannot
         * re-enter node/plugin teardown and wait on its own close operation.
         */
        <X extends Throwable> void runProviderTeardown(
                PluginThreadContext.ThrowingRunnable<X> callback
        ) throws X {
            call(() -> {
                callback.run();
                return null;
            }, Admission.PROVIDER_TEARDOWN);
        }

        /**
         * Track {@link NodePlugin} stop/close callbacks after ordinary
         * contribution admission has been sealed. Runtime shutdown must drain
         * ordinary product work before invoking bundle lifecycle teardown, so
         * those callbacks need a host-only admission class of their own.
         */
        <X extends Throwable> void runNodePluginTeardown(
                PluginThreadContext.ThrowingRunnable<X> callback
        ) throws X {
            call(() -> {
                callback.run();
                return null;
            }, Admission.NODE_PLUGIN_TEARDOWN);
        }

        synchronized void seal() {
            accepting = false;
        }

        synchronized void sealCleanup() {
            acceptingCleanup = false;
        }

        synchronized void resume() {
            if (active != 0) {
                throw new IllegalStateException(
                        "Cannot resume plugin callbacks while callbacks are active");
            }
            accepting = true;
            acceptingCleanup = true;
        }

        synchronized boolean hasPending() {
            return active != 0;
        }

        void requireNotInCallback(String action) {
            if (localDepth.get() != 0) {
                throw new IllegalStateException(
                        "Cannot " + action + " from a plugin contribution callback");
            }
        }

        void awaitQuiescence() {
            requireNotInCallback("await plugin callback quiescence");
            boolean interrupted = false;
            synchronized (this) {
                while (active != 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private enum Admission {
            ORDINARY,
            CLEANUP,
            NODE_PLUGIN_TEARDOWN,
            PROVIDER_TEARDOWN
        }

        static final class CallbackAdmission implements AutoCloseable {
            private final CallbackTracker tracker;
            private final Thread thread;
            private final String previousOwner;
            private boolean closed;

            private CallbackAdmission(CallbackTracker tracker, Thread thread, String previousOwner) {
                this.tracker = tracker;
                this.thread = thread;
                this.previousOwner = previousOwner;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                tracker.releaseAdmission(thread, previousOwner);
                closed = true;
            }
        }
    }

    private record ActivationContext(
            String bundleId,
            String kind,
            String selector,
            String providerClass,
            boolean manifestIdentity
    ) {
        private <T> T call(String action, Supplier<T> callback) {
            try {
                return callback.get();
            } catch (Throwable failure) {
                // Activation callbacks are non-throwing at the SPI surface,
                // but trusted in-process plugin code can still throw Errors
                // or use a sneaky checked exception. Only failures after
                // which in-process recovery is unsafe retain raw JVM
                // semantics. Everything else crosses this boundary as one
                // identity-rich, fixed platform diagnostic; in particular a
                // plugin cannot smuggle its own exception message through by
                // throwing PluginActivationException directly.
                LifecycleFailures.rethrowIfProcessFatalReachable(failure);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String message = "Plugin bundle '" + bundleId + "' failed to " + action + " for "
                        + kind + "/" + selector + " via '" + providerClass + "'";
                throw manifestIdentity
                        ? new HostPluginActivationException(message, bundleId, kind, selector,
                                providerClass, failure)
                        : new PluginActivationException(message, bundleId, kind, selector,
                                providerClass, failure);
            }
        }

        private void run(String action, Runnable callback) {
            call(action, () -> {
                callback.run();
                return null;
            });
        }
    }

    static final class ProductReservations {
        private final ReferenceQueue<Object> collectedReservations = new ReferenceQueue<>();
        private final Map<WeakIdentityReference, ReservationRole> values = new HashMap<>();

        synchronized <D, F> F facadeForNewInvocation(D delegate, Function<D, F> factory) {
            if (delegate == null) {
                return null;
            }
            expungeCollectedProducts();
            if (contains(delegate)) {
                throw CrossInvocationProductReuseException.forProduct(delegate);
            }
            F value = factory.apply(delegate);
            reserve(delegate);
            return value;
        }

        /**
         * Reserve a catalog-owned provider identity before it can invoke a
         * factory. A provider is a different ownership role from every product
         * returned by that factory; returning {@code this} must therefore fail
         * before a product facade is published or either owner closes it.
         *
         * @return {@code true} for a new provider claim, or {@code false} when
         * the same registry already owns that provider identity
         */
        synchronized void preReserveProvider(Object provider) {
            Objects.requireNonNull(provider, "provider");
            expungeCollectedProducts();
            ReservationRole existing = values.get(new WeakIdentityReference(provider));
            if (existing == ReservationRole.PRODUCT) {
                throw new ProviderIdentityReuseException();
            }
            if (existing == null) {
                reserve(provider, ReservationRole.PROVIDER_RESERVED);
            }
        }

        /**
         * Claim the close obligation for a provider being activated. Eager
         * legacy providers are reserved before any factory can return them as
         * products, but their own first activation must still own rollback
         * cleanup if selector/metadata validation fails.
         *
         * @return {@code true} when this activation acquired the provider
         * close obligation
         * @throws ProviderIdentityReuseException when another catalog entry
         * already owns the same raw provider identity
         */
        synchronized boolean claimProvider(Object provider) {
            Objects.requireNonNull(provider, "provider");
            expungeCollectedProducts();
            WeakIdentityReference lookup = new WeakIdentityReference(provider);
            ReservationRole existing = values.get(lookup);
            if (existing == ReservationRole.PRODUCT) {
                throw new ProviderIdentityReuseException();
            }
            if (existing == ReservationRole.PROVIDER_CLAIMED) {
                throw new ProviderIdentityReuseException();
            }
            if (existing == ReservationRole.PROVIDER_RESERVED) {
                values.put(lookup, ReservationRole.PROVIDER_CLAIMED);
                return true;
            }
            reserve(provider, ReservationRole.PROVIDER_CLAIMED);
            return true;
        }

        synchronized <D, F> List<F> facadesForNewInvocation(
                List<D> delegates,
                Function<D, F> factory
        ) {
            if (delegates == null) {
                return null;
            }
            expungeCollectedProducts();

            List<D> uniqueDelegates = new ArrayList<>();
            Map<D, Boolean> seen = new IdentityHashMap<>();
            for (D delegate : delegates) {
                if (delegate != null && seen.put(delegate, Boolean.TRUE) == null) {
                    uniqueDelegates.add(delegate);
                }
            }

            boolean collision = uniqueDelegates.stream().anyMatch(this::contains);
            if (collision) {
                List<D> unclaimedDelegates = uniqueDelegates.stream()
                        .filter(delegate -> !contains(delegate))
                        .toList();
                // Claim rejected companions atomically before releasing this
                // monitor. Cleanup runs outside the lock and may block in
                // plugin code; without this terminal reservation another
                // invocation could claim a companion and then have it closed
                // by the rejected invocation.
                unclaimedDelegates.forEach(this::reserve);
                throw CrossInvocationProductReuseException.forProducts(
                        unclaimedDelegates.stream().map(value -> (Object) value).toList());
            }

            Map<D, F> invocationFacades = new IdentityHashMap<>();
            for (D delegate : uniqueDelegates) {
                invocationFacades.put(delegate, factory.apply(delegate));
            }
            uniqueDelegates.forEach(this::reserve);

            List<F> result = new ArrayList<>(delegates.size());
            for (D delegate : delegates) {
                result.add(delegate == null ? null : invocationFacades.get(delegate));
            }
            return result;
        }

        synchronized List<Object> reserveCapturedAfterTraversalFailure(
                List<Object> capturedProducts
        ) {
            expungeCollectedProducts();
            List<Object> terminalProducts = new ArrayList<>();
            Map<Object, Boolean> seen = new IdentityHashMap<>();
            for (Object product : capturedProducts) {
                if (product == null || seen.put(product, Boolean.TRUE) != null
                        || contains(product)) {
                    continue;
                }
                reserve(product);
                terminalProducts.add(product);
            }
            return List.copyOf(terminalProducts);
        }

        synchronized int reservationCountForTesting() {
            expungeCollectedProducts();
            return values.size();
        }

        private boolean contains(Object product) {
            return values.containsKey(new WeakIdentityReference(product));
        }

        private void reserve(Object product) {
            reserve(product, ReservationRole.PRODUCT);
        }

        private void reserve(Object value, ReservationRole role) {
            values.put(new WeakIdentityReference(value, collectedReservations), role);
        }

        private void expungeCollectedProducts() {
            WeakIdentityReference reference;
            while ((reference = (WeakIdentityReference) collectedReservations.poll()) != null) {
                values.remove(reference);
            }
        }

        private enum ReservationRole {
            PROVIDER_RESERVED,
            PROVIDER_CLAIMED,
            PRODUCT
        }
    }

    static final class ProviderIdentityReuseException extends IllegalStateException {
        private ProviderIdentityReuseException() {
            super("Plugin provider instance is already owned by another catalog entry "
                    + "or as a factory product");
        }
    }

    private static final class WeakIdentityReference extends WeakReference<Object> {
        private final int identityHash;

        private WeakIdentityReference(Object referent) {
            super(Objects.requireNonNull(referent, "referent"));
            this.identityHash = System.identityHashCode(referent);
        }

        private WeakIdentityReference(
                Object referent,
                ReferenceQueue<Object> referenceQueue
        ) {
            super(Objects.requireNonNull(referent, "referent"), referenceQueue);
            this.identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof WeakIdentityReference other)) {
                return false;
            }
            Object referent = get();
            return referent != null && referent == other.get();
        }
    }

    private static final class CrossInvocationProductReuseException
            extends IllegalStateException {
        private final List<Object> unclaimedProducts;

        private CrossInvocationProductReuseException(List<Object> unclaimedProducts) {
            super("Factory returned a product instance already owned by a previous "
                    + "create invocation");
            this.unclaimedProducts = List.copyOf(unclaimedProducts);
        }

        private static CrossInvocationProductReuseException forProduct(Object product) {
            Objects.requireNonNull(product, "product");
            return new CrossInvocationProductReuseException(List.of());
        }

        private static CrossInvocationProductReuseException forProducts(
                List<Object> unclaimedProducts
        ) {
            return new CrossInvocationProductReuseException(unclaimedProducts);
        }

        private List<Object> unclaimedProducts() {
            return unclaimedProducts;
        }
    }

    private static void closeUnclaimedProducts(
            CrossInvocationProductReuseException primary,
            ClassLoader loader,
            CallbackTracker callbacks
    ) {
        closeTerminalProducts(primary, primary.unclaimedProducts(), loader, callbacks);
    }

    private static void closeTerminalProducts(
            Throwable primary,
            List<Object> products,
            ClassLoader loader,
            CallbackTracker callbacks
    ) {
        Throwable winner = primary;
        for (int i = products.size() - 1; i >= 0; i--) {
            Object product = products.get(i);
            if (!(product instanceof AutoCloseable closeable)) {
                continue;
            }
            try {
                // A rejected product has no facade, so establish the same
                // per-callback boundary explicitly. In particular, one close
                // callback changing TCCL must not affect the next reverse
                // cleanup callback.
                pluginRun(callbacks, loader, closeable::close);
            } catch (Throwable cleanupFailure) {
                winner = LifecycleFailures.merge(winner, cleanupFailure);
            }
        }
        if (winner != primary) {
            throw propagate(winner, "Plugin product cleanup failed");
        }
    }

    private static RuntimeException propagate(Throwable failure, String message) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(message, failure);
    }

    /** One catalog provider's view of the shared admission, TCCL, activation and reservation plumbing. */
    private record CatalogCallbackScope(
            ClassLoader loader,
            ActivationContext activationContext,
            ProductReservations reservations,
            CallbackTracker callbacks
    ) implements PluginCallbackScope {
        @Override
        public String bundleId() {
            return activationContext.bundleId();
        }

        @Override
        public String selector() {
            return activationContext.selector();
        }

        @Override
        public <T, X extends Throwable> T call(Callback<T, X> callback) throws X {
            return pluginCall(callbacks, loader, callback::get);
        }

        @Override
        public <X extends Throwable> void run(Action<X> action) throws X {
            pluginRun(callbacks, loader, action::run);
        }

        @Override
        public <X extends Throwable> void runCleanup(Action<X> action) throws X {
            pluginCleanupRun(callbacks, loader, action::run);
        }

        @Override
        public <T> T activation(String action, Supplier<T> callback) {
            return activationContext.call(action, callback);
        }

        @Override
        public <T> T product(T value, UnaryOperator<T> facade) {
            return reservations.facadeForNewInvocation(value, facade);
        }

        @Override
        public <T> List<T> products(List<T> values, UnaryOperator<T> facade) {
            if (values == null) {
                return null;
            }
            List<T> snapshot = snapshotFactoryProducts(values, loader, callbacks, reservations);
            try {
                return reservations.facadesForNewInvocation(snapshot, facade);
            } catch (CrossInvocationProductReuseException failure) {
                closeUnclaimedProducts(failure, loader, callbacks);
                throw failure;
            }
        }
    }

    private record BuiltInBinding<P>(
            ContributionKind kind,
            Class<P> serviceType,
            Function<P, String> selectorFunction,
            FacadeFactory<P> facadeFactory
    ) implements ContributionKindBinding<P> {
        /** Built-in kinds have no descriptor; their bindings are never loaded through ServiceLoader. */
        @Override
        public String descriptorOwner() {
            return "org.yanoproject";
        }

        @Override
        public String manifestKey() {
            return kind.manifestKey();
        }

        @Override
        public String selector(P provider) {
            return selectorFunction.apply(provider);
        }

        @Override
        public P facade(P provider, PluginCallbackScope scope) {
            return facadeFactory.create(provider, scope);
        }
    }

    @FunctionalInterface
    private interface FacadeFactory<P> {
        P create(P provider, PluginCallbackScope scope);
    }

    private record UtxoIndexProviderFacade(
            UtxoIndexContributorProvider delegate, PluginCallbackScope scope
    ) implements UtxoIndexContributorProvider {
        @Override public String id() { return scope.call(delegate::id); }
        @Override public int schemaVersion() { return scope.call(delegate::schemaVersion); }
        @Override public IndexRequirements requirements() { return scope.call(delegate::requirements); }
        @Override public UtxoIndexContributor create(UtxoIndexContext context) {
            return scope.activation("create UTxO index contributor", () -> scope.call(() -> scope.product(
                    delegate.create(context), product -> new UtxoIndexContributor() {
                        @Override public void stageApply(UtxoChanges changes, IndexWriter writer) {
                            scope.run(() -> product.stageApply(changes, writer));
                        }
                        @Override public void stageRollback(ChainPoint target, IndexWriter writer) {
                            scope.run(() -> product.stageRollback(target, writer));
                        }
                        @Override public void stageGenesis(
                                String identity, List<GenesisUtxo> outputs, IndexWriter writer) {
                            scope.run(() -> product.stageGenesis(identity, outputs, writer));
                        }
                        @Override public void stagePruneUndo(ChainPoint point, IndexWriter writer) {
                            scope.run(() -> product.stagePruneUndo(point, writer));
                        }
                        @Override public void reinitialize() { scope.run(product::reinitialize); }
                        @Override public void close() { scope.runCleanup(product::close); }
                    })));
        }
    }

    private record HealthProviderFacade(
            PluginHealthProvider delegate,
            PluginCallbackScope scope
    ) implements PluginHealthProvider {
        private HealthProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("read health provider identity", () -> scope.call(delegate::id));
        }

        @Override
        public PluginHealthSource create(PluginHealthContext context) {
            Objects.requireNonNull(context, "context");
            return scope.activation("create health source", () -> scope.call(() -> scope.product(
                    delegate.create(context), source -> new HealthSourceFacade(source, scope))));
        }
    }

    private record HealthSourceFacade(
            PluginHealthSource delegate,
            PluginCallbackScope scope
    ) implements PluginHealthSource {
        private HealthSourceFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<PluginHealthCheckDescriptor> checks() {
            return scope.activation("enumerate health checks", () -> scope.call(() -> {
                List<PluginHealthCheckDescriptor> values = Objects.requireNonNull(
                        delegate.checks(), "PluginHealthSource.checks() must not return null");
                return FacadeSupport.snapshotList(values, scope,
                        PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE,
                        "PluginHealthSource must declare at most 16 checks");
            }));
        }

        @Override
        public PluginHealthSnapshot snapshot() {
            return Objects.requireNonNull(scope.call(delegate::snapshot),
                    "PluginHealthSource.snapshot() must not return null");
        }

        @Override
        public void close() {
            scope.runCleanup(delegate::close);
        }
    }

    private record MetricsProviderFacade(
            PluginMetricsProvider delegate,
            PluginCallbackScope scope
    ) implements PluginMetricsProvider {
        private MetricsProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("read metrics provider identity", () -> scope.call(delegate::id));
        }

        @Override
        public PluginMetricsSource create(PluginMetricsContext context) {
            Objects.requireNonNull(context, "context");
            return scope.activation("create metrics source", () -> scope.call(() -> scope.product(
                    delegate.create(context), source -> new MetricsSourceFacade(source, scope))));
        }
    }

    private record MetricsSourceFacade(
            PluginMetricsSource delegate,
            PluginCallbackScope scope
    ) implements PluginMetricsSource {
        private MetricsSourceFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<PluginMetricDescriptor> descriptors() {
            return scope.activation("enumerate metric descriptors", () -> scope.call(() -> {
                List<PluginMetricDescriptor> values = Objects.requireNonNull(
                        delegate.descriptors(), "PluginMetricsSource.descriptors() must not return null");
                return FacadeSupport.snapshotList(values, scope,
                        PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE,
                        "PluginMetricsSource must declare at most 64 metrics");
            }));
        }

        @Override
        public PluginMetricSnapshot snapshot() {
            return Objects.requireNonNull(scope.call(delegate::snapshot),
                    "PluginMetricsSource.snapshot() must not return null");
        }

        @Override
        public void close() {
            scope.runCleanup(delegate::close);
        }
    }
}
