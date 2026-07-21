package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode.ProposalEligibility;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsSource;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Typed facades that establish the plugin TCCL for every provider/product callback. */
final class PluginSpiFacades {

    /** Status is diagnostic metadata, not an unbounded plugin data plane. */
    static final int MAX_STATUS_ENTRIES = 256;
    static final int MAX_STATUS_NODES = 4_096;
    static final int MAX_STATUS_DEPTH = 8;
    static final int MAX_STATUS_STRING_LENGTH = 4_096;
    static final int MAX_STATUS_TOTAL_CHARACTERS = 65_536;
    /** One observer callback may expose many block observations, but remains bounded. */
    static final int MAX_OBSERVATIONS_PER_CALLBACK = 4_096;
    static final int MAX_OBSERVATION_CLAIM_BYTES = 1_048_576;
    static final int MAX_OBSERVATION_AGGREGATE_CLAIM_BYTES = 4 * 1_048_576;
    static final int MAX_OBSERVER_ID_LENGTH = 160;
    /** A factory activation is intentionally limited to a small, operable product set. */
    static final int MAX_FACTORY_PRODUCTS_PER_CALLBACK = 256;

    private PluginSpiFacades() {
    }

    private static <T> List<T> snapshotList(
            List<T> values,
            ClassLoader loader,
            CallbackTracker callbacks,
            int maximumSize,
            String overflowMessage
    ) {
        if (values == null) {
            return null;
        }
        List<T> snapshot = new ArrayList<>();
        Iterator<T> iterator = pluginCall(callbacks, loader, values::iterator);
        while (pluginCall(callbacks, loader, iterator::hasNext)) {
            if (snapshot.size() == maximumSize) {
                throw new IllegalStateException(overflowMessage);
            }
            snapshot.add(pluginCall(callbacks, loader, iterator::next));
        }
        return Collections.unmodifiableList(snapshot);
    }

    private static Map<String, Object> snapshotMap(
            Map<String, Object> values,
            ClassLoader loader,
            CallbackTracker callbacks
    ) {
        if (values == null) {
            throw new IllegalStateException("Plugin status must not be null");
        }
        StatusBudget budget = new StatusBudget();
        Object normalized = normalizeStatusValue(
                values, loader, callbacks, budget, new IdentityHashMap<>(), 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) normalized;
        return snapshot;
    }

    private static Object normalizeStatusValue(
            Object value,
            ClassLoader loader,
            CallbackTracker callbacks,
            StatusBudget budget,
            IdentityHashMap<Object, Boolean> activeContainers,
            int depth
    ) {
        budget.addNode();
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            budget.addText(text);
            return text;
        }
        if (isPlatformNumber(value)) {
            if ((value instanceof Double doubleValue && !Double.isFinite(doubleValue))
                    || (value instanceof Float floatValue && !Float.isFinite(floatValue))) {
                throw new IllegalArgumentException(
                        "Plugin status numbers must be finite");
            }
            return value;
        }
        if (depth >= MAX_STATUS_DEPTH) {
            throw new IllegalArgumentException(
                    "Plugin status nesting exceeds " + MAX_STATUS_DEPTH);
        }
        if (value instanceof Map<?, ?> map) {
            requireAcyclicContainer(value, activeContainers);
            try {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                Object entries = pluginCall(callbacks, loader, map::entrySet);
                @SuppressWarnings("unchecked")
                Iterator<Map.Entry<?, ?>> iterator = (Iterator<Map.Entry<?, ?>>) pluginCall(
                        callbacks, loader, () -> ((Set<?>) entries).iterator());
                int traversedEntries = 0;
                while (pluginCall(callbacks, loader, iterator::hasNext)) {
                    if (traversedEntries++ == MAX_STATUS_ENTRIES) {
                        throw new IllegalStateException(
                                "Plugin status must contain at most "
                                        + MAX_STATUS_ENTRIES + " entries");
                    }
                    Map.Entry<?, ?> entry = Objects.requireNonNull(
                            pluginCall(callbacks, loader, iterator::next),
                            "Plugin status map must not contain null entries");
                    Object rawKey = pluginCall(callbacks, loader, entry::getKey);
                    if (!(rawKey instanceof String key)) {
                        throw new IllegalArgumentException(
                                "Plugin status map keys must be strings");
                    }
                    budget.addText(key);
                    Object rawValue = pluginCall(callbacks, loader, entry::getValue);
                    snapshot.put(key, normalizeStatusValue(
                            rawValue, loader, callbacks, budget,
                            activeContainers, depth + 1));
                }
                return Collections.unmodifiableMap(snapshot);
            } finally {
                activeContainers.remove(value);
            }
        }
        if (value instanceof List<?> list) {
            requireAcyclicContainer(value, activeContainers);
            try {
                List<Object> snapshot = new ArrayList<>();
                Iterator<?> iterator = pluginCall(callbacks, loader, list::iterator);
                while (pluginCall(callbacks, loader, iterator::hasNext)) {
                    if (snapshot.size() == MAX_STATUS_ENTRIES) {
                        throw new IllegalArgumentException(
                                "Plugin status list must contain at most "
                                        + MAX_STATUS_ENTRIES + " entries");
                    }
                    Object item = pluginCall(callbacks, loader, iterator::next);
                    snapshot.add(normalizeStatusValue(
                            item, loader, callbacks, budget,
                            activeContainers, depth + 1));
                }
                return Collections.unmodifiableList(new ArrayList<>(snapshot));
            } finally {
                activeContainers.remove(value);
            }
        }
        throw new IllegalArgumentException(
                "Plugin status values must be JSON primitives, maps, or lists");
    }

    private static void requireAcyclicContainer(
            Object value,
            IdentityHashMap<Object, Boolean> activeContainers
    ) {
        if (activeContainers.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "Plugin status must not contain container cycles");
        }
    }

    private static boolean isPlatformNumber(Object value) {
        Class<?> type = value.getClass();
        return type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class;
    }

    private static final class StatusBudget {
        private int nodes;
        private int characters;

        private void addNode() {
            if (++nodes > MAX_STATUS_NODES) {
                throw new IllegalArgumentException(
                        "Plugin status exceeds " + MAX_STATUS_NODES + " values");
            }
        }

        private void addText(String text) {
            if (text.length() > MAX_STATUS_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "Plugin status text exceeds "
                                + MAX_STATUS_STRING_LENGTH + " characters");
            }
            if (characters > MAX_STATUS_TOTAL_CHARACTERS - text.length()) {
                throw new IllegalArgumentException(
                        "Plugin status text exceeds aggregate character limit");
            }
            characters += text.length();
        }
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
                if (snapshot.size() == MAX_FACTORY_PRODUCTS_PER_CALLBACK) {
                    throw new IllegalStateException(
                            "Plugin factory result must contain at most "
                                    + MAX_FACTORY_PRODUCTS_PER_CALLBACK + " products");
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

    static Object provider(
            ContributionKind kind,
            Object delegate,
            ClassLoader loader,
            String bundleId,
            String selector,
            String providerClass
    ) {
        return provider(kind, delegate, loader, bundleId, selector, providerClass,
                new ProductReservations(), new CallbackTracker());
    }

    static Object provider(
            ContributionKind kind,
            Object delegate,
            ClassLoader loader,
            String bundleId,
            String selector,
            String providerClass,
            ProductReservations products
    ) {
        return provider(kind, delegate, loader, bundleId, selector, providerClass,
                products, new CallbackTracker());
    }

    static Object provider(
            ContributionKind kind,
            Object delegate,
            ClassLoader loader,
            String bundleId,
            String selector,
            String providerClass,
            ProductReservations products,
            CallbackTracker callbacks
    ) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(products, "products");
        Objects.requireNonNull(callbacks, "callbacks");
        ClassLoader effectiveLoader = PluginThreadContext.effective(loader);
        ActivationContext activation = new ActivationContext(
                bundleId, kind.manifestKey(), selector, providerClass);
        return switch (kind) {
            case NODE_PLUGIN -> delegate;
            case APP_STATE_MACHINE -> new StateMachineProviderFacade(
                    (AppStateMachineProvider) delegate, effectiveLoader, activation,
                    products, callbacks);
            case SEQUENCER_MODE -> new SequencerProviderFacade(
                    (SequencerModeProvider) delegate, effectiveLoader, activation,
                    products, callbacks);
            case L1_OBSERVER -> new ObserverProviderFacade(
                    (L1ObserverProvider) delegate, effectiveLoader, activation,
                    products, callbacks);
            case SIGNER_PROVIDER -> new SignerFactoryFacade(
                    (SignerProviderFactory) delegate, effectiveLoader, activation,
                    products, callbacks);
            case EFFECT_EXECUTOR -> new EffectExecutorFactoryFacade(
                    (AppEffectExecutorFactory) delegate, effectiveLoader, activation,
                    products, callbacks);
            case FINALIZED_SINK -> new SinkFactoryFacade(
                    (FinalizedStreamSinkFactory) delegate, effectiveLoader, activation,
                    products, callbacks);
            case DOMAIN_API -> new DomainApiProviderFacade(
                    (DomainApiProvider) delegate, effectiveLoader, activation,
                    products, callbacks);
            case HEALTH -> new HealthProviderFacade(
                    (PluginHealthProvider) delegate, effectiveLoader, activation,
                    products, callbacks);
            case METRICS -> new MetricsProviderFacade(
                    (PluginMetricsProvider) delegate, effectiveLoader, activation,
                    products, callbacks);
        };
    }

    /**
     * Registry-wide admission fence for callbacks into plugin-owned code.
     * Nested facade calls share their outer admission so a shutdown seal
     * cannot strand a product halfway through one logical callback.
     */
    static final class CallbackTracker {
        private final ThreadLocal<Integer> localDepth =
                ThreadLocal.withInitial(() -> 0);
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
            return new CallbackAdmission(this, Thread.currentThread());
        }

        private void releaseAdmission(Thread owner) {
            if (owner != Thread.currentThread()) {
                throw new IllegalStateException(
                        "Plugin callback admission must close on its owning thread");
            }
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
            private final Thread owner;
            private boolean closed;

            private CallbackAdmission(CallbackTracker tracker, Thread owner) {
                this.tracker = tracker;
                this.owner = owner;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                tracker.releaseAdmission(owner);
                closed = true;
            }
        }
    }

    private record ActivationContext(
            String bundleId,
            String kind,
            String selector,
            String providerClass
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
                throw new PluginActivationException(
                        "Plugin bundle '" + bundleId + "' failed to " + action + " for "
                                + kind + "/" + selector + " via '" + providerClass + "'",
                        bundleId, kind, selector, providerClass, failure);
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

    private record DomainApiProviderFacade(
            DomainApiProvider delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements DomainApiProvider {
        private DomainApiProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("read domain-api provider identity",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public DomainApi create(DomainApiContext context) {
            Objects.requireNonNull(context, "context");
            return activation.call("create domain-api product", () -> callbacks.call(() -> {
                DomainApi value = PluginThreadContext.call(
                        loader, () -> delegate.create(context));
                return products.facadeForNewInvocation(
                        value, api -> new DomainApiFacade(
                                api, loader, activation, callbacks));
            }));
        }
    }

    private record HealthProviderFacade(
            PluginHealthProvider delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements PluginHealthProvider {
        private HealthProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("read health provider identity",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public PluginHealthSource create(PluginHealthContext context) {
            Objects.requireNonNull(context, "context");
            return activation.call("create health source", () -> callbacks.call(() -> {
                PluginHealthSource value = PluginThreadContext.call(
                        loader, () -> delegate.create(context));
                return products.facadeForNewInvocation(
                        value, source -> new HealthSourceFacade(
                                source, loader, activation, callbacks));
            }));
        }
    }

    private record HealthSourceFacade(
            PluginHealthSource delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements PluginHealthSource {
        private HealthSourceFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<PluginHealthCheckDescriptor> checks() {
            return activation.call("enumerate health checks", () -> callbacks.call(() -> {
                List<PluginHealthCheckDescriptor> values = Objects.requireNonNull(
                        PluginThreadContext.call(loader, delegate::checks),
                        "PluginHealthSource.checks() must not return null");
                return snapshotList(values, loader, callbacks,
                        PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE,
                        "PluginHealthSource must declare at most 16 checks");
            }));
        }

        @Override
        public PluginHealthSnapshot snapshot() {
            return Objects.requireNonNull(
                    pluginCall(callbacks, loader, delegate::snapshot),
                    "PluginHealthSource.snapshot() must not return null");
        }

        @Override
        public void close() {
            pluginCleanupRun(callbacks, loader, delegate::close);
        }
    }

    private record MetricsProviderFacade(
            PluginMetricsProvider delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements PluginMetricsProvider {
        private MetricsProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("read metrics provider identity",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public PluginMetricsSource create(PluginMetricsContext context) {
            Objects.requireNonNull(context, "context");
            return activation.call("create metrics source", () -> callbacks.call(() -> {
                PluginMetricsSource value = PluginThreadContext.call(
                        loader, () -> delegate.create(context));
                return products.facadeForNewInvocation(
                        value, source -> new MetricsSourceFacade(
                                source, loader, activation, callbacks));
            }));
        }
    }

    private record MetricsSourceFacade(
            PluginMetricsSource delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements PluginMetricsSource {
        private MetricsSourceFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<PluginMetricDescriptor> descriptors() {
            return activation.call("enumerate metric descriptors", () -> callbacks.call(() -> {
                List<PluginMetricDescriptor> values = Objects.requireNonNull(
                        PluginThreadContext.call(loader, delegate::descriptors),
                        "PluginMetricsSource.descriptors() must not return null");
                return snapshotList(values, loader, callbacks,
                        PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE,
                        "PluginMetricsSource must declare at most 64 metrics");
            }));
        }

        @Override
        public PluginMetricSnapshot snapshot() {
            return Objects.requireNonNull(
                    pluginCall(callbacks, loader, delegate::snapshot),
                    "PluginMetricsSource.snapshot() must not return null");
        }

        @Override
        public void close() {
            pluginCleanupRun(callbacks, loader, delegate::close);
        }
    }

    private record DomainApiFacade(
            DomainApi delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements DomainApi {
        private DomainApiFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<DomainApiRoute> routes() {
            return activation.call("enumerate domain-api routes", () -> {
                List<DomainApiRoute> values = Objects.requireNonNull(
                        pluginCall(callbacks, loader, delegate::routes),
                        "DomainApi.routes() must not return null");
                Iterator<DomainApiRoute> iterator = Objects.requireNonNull(
                        pluginCall(callbacks, loader, values::iterator),
                        "DomainApi route iterator must not be null");
                List<DomainApiRoute> snapshot = new ArrayList<>();
                while (pluginCall(callbacks, loader, iterator::hasNext)) {
                    if (snapshot.size() == DomainApi.MAX_ROUTES) {
                        throw new IllegalStateException(
                                "DomainApi must declare at most "
                                        + DomainApi.MAX_ROUTES + " routes");
                    }
                    snapshot.add(Objects.requireNonNull(
                            pluginCall(callbacks, loader, iterator::next),
                            "DomainApi routes must not contain null entries"));
                }
                return List.copyOf(snapshot);
            });
        }

        @Override
        public DomainApiResponse handle(DomainApiRequest request) throws Exception {
            Objects.requireNonNull(request, "request");
            return Objects.requireNonNull(
                    pluginCall(callbacks, loader, () -> delegate.handle(request)),
                    "DomainApi.handle() must not return null");
        }

        @Override
        public void close() {
            pluginCleanupRun(callbacks, loader, delegate::close);
        }
    }

    private record StateMachineProviderFacade(
            AppStateMachineProvider delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements AppStateMachineProvider {
        private StateMachineProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("read state-machine provider identity",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public AppStateMachine create() {
            return activation.call("create state-machine product", () -> callbacks.call(() -> {
                AppStateMachine value = PluginThreadContext.call(
                        loader, delegate::create);
                return products.facadeForNewInvocation(
                        value, machine -> new StateMachineFacade(
                                machine, loader, activation, callbacks));
            }));
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            return activation.call("create state-machine product", () -> callbacks.call(() -> {
                AppStateMachine value = PluginThreadContext.call(
                        loader, () -> delegate.create(context));
                return products.facadeForNewInvocation(
                        value, machine -> new StateMachineFacade(
                                machine, loader, activation, callbacks));
            }));
        }
    }

    private record StateMachineFacade(
            AppStateMachine delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements AppStateMachine {
        private StateMachineFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("identify state-machine product",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public void init(AppStateReader state, AppChainInfo info) {
            activation.run("initialize state-machine product",
                    () -> pluginRun(callbacks, loader,
                            () -> delegate.init(state, info)));
        }

        @Override
        public AdmissionResult validate(AppMessage message) {
            return pluginCall(callbacks, loader,
                    () -> delegate.validate(message));
        }

        @Override
        public AdmissionResult validateForBlock(
                AppMessage message,
                long candidateHeight,
                AppStateReader committedState
        ) {
            return pluginCall(callbacks, loader,
                    () -> delegate.validateForBlock(
                            message, candidateHeight, committedState));
        }

        @Override
        public AdmissionResult validatePrivilegedSystemSubmission(
                String topic,
                byte[] body
        ) {
            byte[] input = body != null ? body.clone() : null;
            return pluginCall(callbacks, loader,
                    () -> delegate.validatePrivilegedSystemSubmission(topic, input));
        }

        @Override
        public Map<String, Object> operationalStatus() {
            Map<String, Object> values = pluginCall(callbacks, loader,
                    delegate::operationalStatus);
            return snapshotMap(values, loader, callbacks);
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
            pluginRun(callbacks, loader,
                    () -> delegate.apply(block, writer));
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
            pluginRun(callbacks, loader,
                    () -> delegate.apply(block, writer, effects));
        }

        @Override
        public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
            pluginRun(callbacks, loader,
                    () -> delegate.onEffectResult(block, result, writer));
        }

        @Override
        public void onEffectResult(
                AppBlock block,
                EffectResult result,
                AppStateWriter writer,
                AppEffectEmitter effects
        ) {
            pluginRun(callbacks, loader,
                    () -> delegate.onEffectResult(block, result, writer, effects));
        }

        @Override
        public byte[] query(String path, byte[] params) {
            byte[] input = params != null ? params.clone() : null;
            byte[] response = pluginCall(callbacks, loader,
                    () -> delegate.query(path, input));
            return response != null ? response.clone() : null;
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext state) {
            byte[] input = params != null ? params.clone() : null;
            byte[] response = pluginCall(callbacks, loader,
                    () -> delegate.query(path, input, state));
            return response != null ? response.clone() : null;
        }
    }

    private record SequencerProviderFacade(
            SequencerModeProvider delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements SequencerModeProvider {
        private SequencerProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return pluginCall(callbacks, loader, delegate::id);
        }

        @Override
        public SequencerMode create(SequencerContext context) {
            return activation.call("create sequencer product", () -> callbacks.call(() -> {
                SequencerMode value = PluginThreadContext.call(
                        loader, () -> delegate.create(context));
                return products.facadeForNewInvocation(
                        value, mode -> new SequencerModeFacade(
                                mode, loader, activation, callbacks));
            }));
        }
    }

    private record SequencerModeFacade(
            SequencerMode delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements SequencerMode {
        private SequencerModeFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("identify sequencer product",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public void init(SequencerContext context) {
            activation.run("initialize sequencer product",
                    () -> pluginRun(callbacks, loader,
                            () -> delegate.init(context)));
        }

        @Override
        public boolean shouldProposeNow(long height) {
            return pluginCall(callbacks, loader,
                    () -> delegate.shouldProposeNow(height));
        }

        @Override
        public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            byte[] input = Objects.requireNonNull(proposerKey, "proposerKey").clone();
            return pluginCall(callbacks, loader,
                    () -> delegate.checkProposal(input, height));
        }

        @Override
        public Map<String, Object> status() {
            return activation.call("read sequencer product status",
                    () -> callbacks.call(() -> {
                        Map<String, Object> values = PluginThreadContext.call(
                                loader, delegate::status);
                        return snapshotMap(values, loader, callbacks);
                    }));
        }
    }

    private record ObserverProviderFacade(
            L1ObserverProvider delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements L1ObserverProvider {
        private ObserverProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String type() {
            return pluginCall(callbacks, loader, delegate::type);
        }

        @Override
        public L1Observer create(String observerId, Map<String, String> settings) {
            return activation.call("create L1-observer product", () -> callbacks.call(() -> {
                L1Observer value = PluginThreadContext.call(
                        loader, () -> delegate.create(observerId, settings));
                return products.facadeForNewInvocation(
                        value, observer -> new ObserverFacade(
                                observer, observerId, loader, activation, callbacks));
            }));
        }
    }

    private record ObserverFacade(
            L1Observer delegate,
            String configuredObserverId,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements L1Observer {
        private ObserverFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String observerId() {
            return activation.call("identify L1-observer product",
                    () -> {
                        String actual = pluginCall(callbacks, loader,
                                delegate::observerId);
                        if (!Objects.equals(configuredObserverId, actual)) {
                            throw new IllegalStateException(
                                    "L1-observer product id does not match configured id");
                        }
                        return actual;
                    });
        }

        @Override
        public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            byte[] expectedBlockHash = Objects.requireNonNull(
                    blockHash, "blockHash").clone();
            if (expectedBlockHash.length != 32) {
                throw new IllegalArgumentException("L1 callback block hash must be 32 bytes");
            }
            return callbacks.call(() -> {
                List<L1Observation> values = PluginThreadContext.call(
                        loader, () -> delegate.observe(
                                slot, expectedBlockHash.clone(), block));
                if (values == null) {
                    throw new IllegalStateException("L1 observer result must not be null");
                }
                List<L1Observation> snapshots = new ArrayList<>();
                long aggregateClaimBytes = 0;
                Iterator<L1Observation> iterator = pluginCall(
                        callbacks, loader, values::iterator);
                while (pluginCall(callbacks, loader, iterator::hasNext)) {
                    if (snapshots.size() == MAX_OBSERVATIONS_PER_CALLBACK) {
                        throw new IllegalStateException(
                                "L1 observer result must contain at most "
                                        + MAX_OBSERVATIONS_PER_CALLBACK + " observations");
                    }
                    ObservationSnapshot snapshot = snapshotObservation(
                            pluginCall(callbacks, loader, iterator::next),
                            configuredObserverId, slot, expectedBlockHash);
                    int claimBytes = snapshot.claimBytes();
                    if (aggregateClaimBytes
                            > MAX_OBSERVATION_AGGREGATE_CLAIM_BYTES - claimBytes) {
                        throw new IllegalArgumentException(
                                "L1 observer aggregate claims exceed "
                                        + MAX_OBSERVATION_AGGREGATE_CLAIM_BYTES + " bytes");
                    }
                    aggregateClaimBytes += claimBytes;
                    snapshots.add(snapshot.observation());
                }
                return List.copyOf(snapshots);
            });
        }

        private static ObservationSnapshot snapshotObservation(
                L1Observation observation,
                String configuredObserverId,
                long callbackSlot,
                byte[] callbackBlockHash
        ) {
            Objects.requireNonNull(observation,
                    "L1 observer result must not contain null observations");
            String observerId = observation.observerId();
            if (observerId == null || observerId.isBlank()
                    || observerId.length() > MAX_OBSERVER_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "L1 observation id must be non-blank and at most "
                                + MAX_OBSERVER_ID_LENGTH + " characters");
            }
            if (!observerId.equals(configuredObserverId)) {
                throw new IllegalArgumentException(
                        "L1 observation id does not match configured observer");
            }
            if (observation.slot() != callbackSlot) {
                throw new IllegalArgumentException(
                        "L1 observation slot does not match callback slot");
            }
            if (callbackBlockHash == null || callbackBlockHash.length != 32
                    || !java.util.Arrays.equals(
                            observation.blockHash(), callbackBlockHash)) {
                throw new IllegalArgumentException(
                        "L1 observation block hash does not match callback block hash");
            }
            byte[] claim = observation.claim();
            if (claim.length > MAX_OBSERVATION_CLAIM_BYTES) {
                throw new IllegalArgumentException(
                        "L1 observation claim exceeds "
                                + MAX_OBSERVATION_CLAIM_BYTES + " bytes");
            }
            return new ObservationSnapshot(
                    new L1Observation(observerId, observation.txHash(),
                            observation.slot(), observation.blockHash(), claim),
                    claim.length);
        }

        private record ObservationSnapshot(
                L1Observation observation,
                int claimBytes
        ) {
        }

        @Override
        public Map<String, Object> status() {
            return activation.call("read L1-observer product status",
                    () -> callbacks.call(() -> {
                        Map<String, Object> values = PluginThreadContext.call(
                                loader, delegate::status);
                        return snapshotMap(values, loader, callbacks);
                    }));
        }
    }

    private record SignerFactoryFacade(
            SignerProviderFactory delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements SignerProviderFactory {
        private SignerFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String scheme() {
            return pluginCall(callbacks, loader, delegate::scheme);
        }

        @Override
        public SignerProvider create(String keyReference) {
            return activation.call("create signer product", () -> callbacks.call(() -> {
                SignerProvider value = PluginThreadContext.call(
                        loader, () -> delegate.create(keyReference));
                return products.facadeForNewInvocation(
                        value, signer -> new SignerFacade(
                                signer, loader, activation, callbacks));
            }));
        }
    }

    private record SignerFacade(
            SignerProvider delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements SignerProvider {
        private SignerFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public byte[] sign(byte[] message) {
            byte[] input = Objects.requireNonNull(message, "message").clone();
            return activation.call("sign an app-chain message", () -> {
                byte[] signature = pluginCall(callbacks, loader,
                        () -> delegate.sign(input));
                if (signature == null || signature.length != 64) {
                    throw new IllegalStateException(
                            "Signer product must return a 64-byte Ed25519 signature");
                }
                return signature.clone();
            });
        }

        @Override
        public byte[] publicKey() {
            return activation.call("initialize signer product",
                    () -> {
                        byte[] publicKey = pluginCall(callbacks, loader,
                                delegate::publicKey);
                        if (publicKey == null || publicKey.length != 32) {
                            throw new IllegalStateException(
                                    "Signer product must return a 32-byte Ed25519 public key");
                        }
                        return publicKey.clone();
                    });
        }

        @Override
        public String publicKeyHex() {
            return com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(publicKey());
        }
    }

    private record EffectExecutorFactoryFacade(
            AppEffectExecutorFactory delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements AppEffectExecutorFactory {
        private EffectExecutorFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String scheme() {
            return pluginCall(callbacks, loader, delegate::scheme);
        }

        @Override
        public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
            return activation.call("create effect-executor products", () -> callbacks.call(() -> {
                List<AppEffectExecutor> values = PluginThreadContext.call(
                        loader, () -> delegate.create(chainId, config));
                if (values == null) {
                    return null;
                }
                List<AppEffectExecutor> snapshot = snapshotFactoryProducts(
                        values, loader, callbacks, products);
                try {
                    return products.facadesForNewInvocation(
                            snapshot, executor -> new EffectExecutorFacade(
                                    executor, loader, activation, callbacks));
                } catch (CrossInvocationProductReuseException failure) {
                    closeUnclaimedProducts(failure, loader, callbacks);
                    throw failure;
                }
            }));
        }
    }

    private record EffectExecutorFacade(
            AppEffectExecutor delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements AppEffectExecutor {
        private EffectExecutorFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("identify effect-executor product",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public boolean supports(String effectType) {
            return pluginCall(callbacks, loader,
                    () -> delegate.supports(effectType));
        }

        @Override
        public Set<String> effectTypes() {
            return activation.call("declare effect-executor types",
                    () -> pluginCall(callbacks, loader, () -> {
                        Set<String> declared = delegate.effectTypes();
                        return declared != null ? Set.copyOf(declared) : null;
                    }));
        }

        @Override
        public EffectExecutorOperationalSnapshot operationalSnapshot() {
            return pluginCall(callbacks, loader, delegate::operationalSnapshot);
        }

        @Override
        public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect)
                throws Exception {
            return pluginCall(callbacks, loader,
                    () -> delegate.execute(ctx, effect));
        }

        @Override
        public void close() {
            pluginCleanupRun(callbacks, loader, delegate::close);
        }
    }

    private record SinkFactoryFacade(
            FinalizedStreamSinkFactory delegate,
            ClassLoader loader,
            ActivationContext activation,
            ProductReservations products,
            CallbackTracker callbacks
    ) implements FinalizedStreamSinkFactory {
        private SinkFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String scheme() {
            return pluginCall(callbacks, loader, delegate::scheme);
        }

        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return activation.call("create finalized-sink products", () -> callbacks.call(() -> {
                List<FinalizedStreamSink> values = PluginThreadContext.call(
                        loader, () -> delegate.create(chainId, config));
                if (values == null) {
                    return null;
                }
                List<FinalizedStreamSink> snapshot = snapshotFactoryProducts(
                        values, loader, callbacks, products);
                try {
                    return products.facadesForNewInvocation(
                            snapshot, sink -> new SinkFacade(
                                    sink, loader, activation, callbacks));
                } catch (CrossInvocationProductReuseException failure) {
                    closeUnclaimedProducts(failure, loader, callbacks);
                    throw failure;
                }
            }));
        }
    }

    private record SinkFacade(
            FinalizedStreamSink delegate,
            ClassLoader loader,
            ActivationContext activation,
            CallbackTracker callbacks
    ) implements FinalizedStreamSink {
        private SinkFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return activation.call("identify finalized-sink product",
                    () -> pluginCall(callbacks, loader, delegate::id));
        }

        @Override
        public boolean deliver(AppBlock block) throws Exception {
            return pluginCall(callbacks, loader,
                    () -> delegate.deliver(block));
        }

        @Override
        public String legacyCursorKey() {
            return pluginCall(callbacks, loader,
                    delegate::legacyCursorKey);
        }

        @Override
        public void close() {
            pluginCleanupRun(callbacks, loader, delegate::close);
        }
    }
}
