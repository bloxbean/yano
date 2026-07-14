package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.config.PluginConfigValues;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationCustomizer;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * Lifecycle owner for the legacy {@link NodePlugin} SPI.
 *
 * <p>ADR-011.1 deliberately keeps the public plugin SPI and ServiceLoader
 * discovery mechanism, but makes activation transactional: discovery,
 * metadata snapshotting, operator policy and dependency validation all finish
 * before the first lifecycle callback. Selected plugins initialize/start in a
 * deterministic dependency order and stop/close in reverse order.</p>
 *
 * <p>This class is not a dynamic bundle loader. ADR-011.2 catalog parsing,
 * compatibility, policy and provider ownership are supplied by
 * {@link PluginRuntimeEnvironment}; this class remains the transactional
 * lifecycle owner for selected {@code NodePlugin} instances.</p>
 *
 * <p>Lifecycle transitions are serialized. Callback-drain waits deliberately
 * release the manager monitor so an admitted callback can finish a concurrent
 * read without deadlocking shutdown. Plugin contexts and their shared registry
 * remain safe for concurrent access after initialization.</p>
 */
public final class PluginManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    static final int MAX_CYCLE_DIAGNOSTIC_LENGTH = 512;
    private static final int MAX_CYCLE_ID_DISPLAY_LENGTH = 64;
    private static final String DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX =
            "Plugin dependency cycle: ";
    private static final int MAX_PLUGIN_ID_LENGTH = 160;
    private static final int MAX_PLUGIN_VERSION_LENGTH = 128;
    private static final int MAX_PLUGIN_DEPENDENCIES = 256;
    private static final int MAX_PLUGIN_CAPABILITIES = PluginCapability.values().length;
    private static final int MAX_DISCOVERED_NODE_PLUGINS = PluginIndex.MAX_PROVIDERS;

    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final PluginsOptions options;
    private final ClassLoader classLoader;
    private final ClassLoader platformClassLoader;
    private final List<NodePlugin> suppliedPlugins;
    /** Instances constructed by the standalone public ServiceLoader path. */
    private final List<NodePlugin> internallyDiscoveredOrder = new ArrayList<>();
    private final Set<NodePlugin> internallyOwned =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> externallySatisfiedDependencies;
    private final Map<String, Map<String, Object>> bundleConfigs;
    private final boolean preserveSuppliedOrder;
    private final Consumer<NodePlugin> terminalCloseObserver;
    private final PluginContextFacades.ManagedCallbackResources managedCallbacks;
    /** Shared catalog admission fence; null for standalone legacy managers. */
    private final PluginSpiFacades.CallbackTracker catalogCallbacks;
    private final NodePluginLifecycleObserver lifecycleObserver;

    /** Successfully initialized plugins, always in dependency order. */
    private final List<NodePlugin> plugins = new ArrayList<>();
    /** Plugins whose most recent start callback completed successfully. */
    private final List<NodePlugin> startedPlugins = new ArrayList<>();
    private final Map<NodePlugin, String> pluginIds = new IdentityHashMap<>();
    private final Map<NodePlugin, PluginContextImpl> pluginContexts = new IdentityHashMap<>();
    private final PluginContextImpl.SharedServiceRegistry services =
            new PluginContextImpl.SharedServiceRegistry();
    private final PluginContextImpl.SharedStorageFilterRegistry storageFilters =
            new PluginContextImpl.SharedStorageFilterRegistry();
    private final List<HeaderValidationCustomizer> headerValidationCustomizers =
            new CopyOnWriteArrayList<>();

    private State state = State.NEW;
    private Thread closingThread;

    /**
     * Compatibility constructor for existing embedders. It preserves today's
     * discover-all policy while snapshotting the supplied configuration map.
     */
    public PluginManager(EventBus eventBus, ScheduledExecutorService scheduler,
                         Map<String, Object> config, ClassLoader classLoader) {
        this(eventBus, scheduler,
                new PluginsOptions(true, false, Set.of(), Set.of(), config),
                classLoader, null, Set.of(), Map.of(), false,
                ignored -> { }, ignored -> { }, null,
                NodePluginLifecycleObserver.NOOP);
    }

    /**
     * Construct a manager with the complete existing plugin policy. A named
     * factory avoids making legacy source calls whose config argument is
     * {@code null} ambiguous with the retained Map constructor.
     */
    public static PluginManager withOptions(EventBus eventBus,
                                            ScheduledExecutorService scheduler,
                                            PluginsOptions options,
                                            ClassLoader classLoader) {
        return new PluginManager(eventBus, scheduler, options, classLoader,
                null, Set.of(), Map.of(), false,
                ignored -> { }, ignored -> { }, null,
                NodePluginLifecycleObserver.NOOP);
    }

    /**
     * Construct lifecycle management from an already policy-selected,
     * structurally validated ADR-011.2 catalog. Dependencies supplied only as
     * typed bundles satisfy the bundle graph but do not create lifecycle
     * callbacks. The selected-plugin iteration order must be the projection of
     * the full catalog order and is preserved for otherwise independent
     * lifecycle plugins.
     */
    static PluginManager fromCatalog(EventBus eventBus,
                                     ScheduledExecutorService scheduler,
                                     PluginsOptions options,
                                     ClassLoader classLoader,
                                     Collection<NodePlugin> selectedPlugins,
                                     Set<String> selectedBundleIds,
                                     Map<String, Map<String, Object>> bundleConfigs) {
        PluginsOptions catalogPolicy = new PluginsOptions(true,
                options != null && options.autoRegisterAnnotated(),
                Set.of(), Set.of(), options != null ? options.config() : Map.of());
        return new PluginManager(eventBus, scheduler, catalogPolicy, classLoader,
                Objects.requireNonNull(selectedPlugins, "selectedPlugins"),
                selectedBundleIds, bundleConfigs, true,
                ignored -> { }, ignored -> { }, null,
                NodePluginLifecycleObserver.NOOP);
    }

    /** Catalog lifecycle construction with exact terminal-close accounting. */
    static PluginManager fromCatalog(EventBus eventBus,
                                     ScheduledExecutorService scheduler,
                                     PluginsOptions options,
                                     ClassLoader classLoader,
                                     Collection<NodePlugin> selectedPlugins,
                                     Set<String> selectedBundleIds,
                                     Map<String, Map<String, Object>> bundleConfigs,
                                     Consumer<NodePlugin> terminalCloseObserver) {
        PluginsOptions catalogPolicy = new PluginsOptions(true,
                options != null && options.autoRegisterAnnotated(),
                Set.of(), Set.of(), options != null ? options.config() : Map.of());
        return new PluginManager(eventBus, scheduler, catalogPolicy, classLoader,
                Objects.requireNonNull(selectedPlugins, "selectedPlugins"),
                selectedBundleIds, bundleConfigs, true,
                Objects.requireNonNull(terminalCloseObserver, "terminalCloseObserver"),
                ignored -> { }, null, NodePluginLifecycleObserver.NOOP);
    }

    /** Catalog lifecycle construction with callback-lifetime registration. */
    static PluginManager fromCatalog(EventBus eventBus,
                                     ScheduledExecutorService scheduler,
                                     PluginsOptions options,
                                     ClassLoader classLoader,
                                     Collection<NodePlugin> selectedPlugins,
                                     Set<String> selectedBundleIds,
                                     Map<String, Map<String, Object>> bundleConfigs,
                                     Consumer<NodePlugin> terminalCloseObserver,
                                     Consumer<CompletableFuture<Void>> callbackRegistrar) {
        return fromCatalog(eventBus, scheduler, options, classLoader, selectedPlugins,
                selectedBundleIds, bundleConfigs, terminalCloseObserver,
                callbackRegistrar, null);
    }

    /** Catalog lifecycle construction sharing typed-provider callback admission. */
    static PluginManager fromCatalog(EventBus eventBus,
                                     ScheduledExecutorService scheduler,
                                     PluginsOptions options,
                                     ClassLoader classLoader,
                                     Collection<NodePlugin> selectedPlugins,
                                     Set<String> selectedBundleIds,
                                     Map<String, Map<String, Object>> bundleConfigs,
                                     Consumer<NodePlugin> terminalCloseObserver,
                                     Consumer<CompletableFuture<Void>> callbackRegistrar,
                                     PluginSpiFacades.CallbackTracker catalogCallbacks) {
        return fromCatalog(eventBus, scheduler, options, classLoader, selectedPlugins,
                selectedBundleIds, bundleConfigs, terminalCloseObserver,
                callbackRegistrar, catalogCallbacks, NodePluginLifecycleObserver.NOOP);
    }

    /** Catalog lifecycle construction with host-owned lifecycle observation. */
    static PluginManager fromCatalog(EventBus eventBus,
                                     ScheduledExecutorService scheduler,
                                     PluginsOptions options,
                                     ClassLoader classLoader,
                                     Collection<NodePlugin> selectedPlugins,
                                     Set<String> selectedBundleIds,
                                     Map<String, Map<String, Object>> bundleConfigs,
                                     Consumer<NodePlugin> terminalCloseObserver,
                                     Consumer<CompletableFuture<Void>> callbackRegistrar,
                                     PluginSpiFacades.CallbackTracker catalogCallbacks,
                                     NodePluginLifecycleObserver lifecycleObserver) {
        PluginsOptions catalogPolicy = new PluginsOptions(true,
                options != null && options.autoRegisterAnnotated(),
                Set.of(), Set.of(), options != null ? options.config() : Map.of());
        return new PluginManager(eventBus, scheduler, catalogPolicy, classLoader,
                Objects.requireNonNull(selectedPlugins, "selectedPlugins"),
                selectedBundleIds, bundleConfigs, true,
                Objects.requireNonNull(terminalCloseObserver, "terminalCloseObserver"),
                Objects.requireNonNull(callbackRegistrar, "callbackRegistrar"),
                catalogCallbacks,
                Objects.requireNonNull(lifecycleObserver, "lifecycleObserver"));
    }

    /** Test/programmatic source used without changing the public discovery SPI. */
    PluginManager(EventBus eventBus, ScheduledExecutorService scheduler,
                  PluginsOptions options, ClassLoader classLoader,
                  Collection<NodePlugin> suppliedPlugins) {
        this(eventBus, scheduler, options, classLoader, suppliedPlugins, Set.of(), Map.of());
    }

    private PluginManager(EventBus eventBus, ScheduledExecutorService scheduler,
                          PluginsOptions options, ClassLoader classLoader,
                          Collection<NodePlugin> suppliedPlugins,
                          Set<String> externallySatisfiedDependencies,
                          Map<String, Map<String, Object>> bundleConfigs) {
        this(eventBus, scheduler, options, classLoader, suppliedPlugins,
                externallySatisfiedDependencies, bundleConfigs, false,
                ignored -> { }, ignored -> { }, null,
                NodePluginLifecycleObserver.NOOP);
    }

    private PluginManager(EventBus eventBus, ScheduledExecutorService scheduler,
                          PluginsOptions options, ClassLoader classLoader,
                          Collection<NodePlugin> suppliedPlugins,
                          Set<String> externallySatisfiedDependencies,
                          Map<String, Map<String, Object>> bundleConfigs,
                          boolean preserveSuppliedOrder,
                          Consumer<NodePlugin> terminalCloseObserver,
                          Consumer<CompletableFuture<Void>> callbackRegistrar,
                          PluginSpiFacades.CallbackTracker catalogCallbacks,
                          NodePluginLifecycleObserver lifecycleObserver) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.options = snapshot(Objects.requireNonNull(options, "options"));
        this.platformClassLoader = PluginThreadContext.effective(
                Thread.currentThread().getContextClassLoader());
        this.classLoader = PluginThreadContext.effective(classLoader);
        this.suppliedPlugins = suppliedPlugins != null ? List.copyOf(suppliedPlugins) : null;
        this.externallySatisfiedDependencies = externallySatisfiedDependencies == null
                ? Set.of() : Set.copyOf(externallySatisfiedDependencies);
        this.preserveSuppliedOrder = preserveSuppliedOrder;
        this.terminalCloseObserver = Objects.requireNonNull(
                terminalCloseObserver, "terminalCloseObserver");
        this.catalogCallbacks = catalogCallbacks;
        this.lifecycleObserver = Objects.requireNonNull(
                lifecycleObserver, "lifecycleObserver");
        this.managedCallbacks = new PluginContextFacades.ManagedCallbackResources(
                Objects.requireNonNull(callbackRegistrar, "callbackRegistrar"));
        if (bundleConfigs == null || bundleConfigs.isEmpty()) {
            this.bundleConfigs = Map.of();
        } else {
            Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
            bundleConfigs.forEach((id, values) -> copy.put(id,
                    PluginConfigValues.immutableCopy(values)));
            this.bundleConfigs = Collections.unmodifiableMap(copy);
        }
    }

    /** Discover, validate and initialize the selected plugin set exactly once. */
    public void discoverAndInit() {
        requireNotInCatalogCallback("discover and initialize plugins");
        synchronized (this) {
            if (state == State.INITIALIZED || state == State.STARTED
                    || state == State.STOPPED) {
                return;
            }
            requireState(State.NEW, "discover and initialize");
            state = State.DISCOVERING;

            if (!options.enabled()) {
                state = State.INITIALIZED;
                log.info("Plugin discovery is disabled");
                return;
            }
        }
        if (options.autoRegisterAnnotated()) {
            // This flag existed before ADR-011.1 but has never owned listener
            // handles. Acting on it now could double-register plugins that
            // already use AnnotationListenerRegistrar themselves.
            log.warn("plugins.autoRegisterAnnotated is reserved and remains inactive");
        }

        List<PluginDescriptor> ordered;
        try {
            ordered = discoverValidateAndOrder();
        } catch (Throwable failure) {
            CompletableFuture<Void> callbackCleanup = managedCallbacks.sealTerminal();
            managedCallbacks.awaitCompletion(callbackCleanup);
            List<Throwable> cleanupFailures = new ArrayList<>(
                    managedCallbacks.drainCleanupFailures());
            closeInternallyDiscoveredReverse(cleanupFailures);
            synchronized (this) {
                state = State.FAILED;
                clearPublishedState();
            }
            throw propagate(mergeFailures(failure, cleanupFailures));
        }

        for (PluginDescriptor descriptor : ordered) {
            pluginIds.put(descriptor.plugin(), descriptor.id());
        }

        synchronized (this) {
            state = State.INITIALIZING;
        }
        for (PluginDescriptor descriptor : ordered) {
            PluginContextImpl context = new PluginContextImpl(
                    descriptor.id(), eventBus, pluginLogger(descriptor.id()), options.config(),
                    bundleConfig(descriptor.id()),
                    scheduler, Optional.ofNullable(classLoader), platformClassLoader,
                    storageFilters, services, managedCallbacks);
            pluginContexts.put(descriptor.plugin(), context);
            try {
                runPluginCallback(() -> descriptor.plugin().init(context));
                context.completeInitialization();
                plugins.add(descriptor.plugin());
            } catch (Throwable failure) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                CompletableFuture<Void> callbackCleanup =
                        managedCallbacks.sealTerminal();
                managedCallbacks.awaitCompletion(callbackCleanup);
                cleanupFailures.addAll(managedCallbacks.drainCleanupFailures());
                if (suppliedPlugins == null) {
                    // Standalone ServiceLoader construction owns the complete
                    // discovered set, including not-yet-initialized tails.
                    // Release that ownership in exact reverse construction
                    // order so partial initialization cannot reorder closes.
                    closeInternallyDiscoveredReverse(cleanupFailures);
                } else {
                    closeOne(descriptor.plugin(), descriptor.id(), cleanupFailures);
                    closeInitializedReverse(cleanupFailures);
                }
                synchronized (this) {
                    state = State.FAILED;
                    clearPublishedState();
                }
                throw startupFailure(FailurePhase.INITIALIZATION, descriptor.id(),
                        "Plugin initialization failed", failure, cleanupFailures);
            }
        }

        synchronized (this) {
            refreshHeaderValidationCustomizers();
            state = State.INITIALIZED;
        }
        log.info("Initialized plugins in dependency order: {}",
                ordered.stream().map(PluginDescriptor::id).toList());
    }

    private Map<String, Object> bundleConfig(String pluginId) {
        // Manifested owners have an entry even when their scoped map is empty.
        // Legacy plugins retain PluginContext's shared-config compatibility.
        return bundleConfigs.containsKey(pluginId)
                ? bundleConfigs.get(pluginId) : options.config();
    }

    /** Run all catalog-owned NodePlugin code inside the provider lifetime fence. */
    private <T, X extends Throwable> T callPluginCallback(
            PluginThreadContext.ThrowingSupplier<T, X> callback
    ) throws X {
        PluginThreadContext.ThrowingSupplier<T, X> tcclCall =
                () -> PluginThreadContext.call(classLoader, callback);
        return catalogCallbacks != null
                ? catalogCallbacks.call(tcclCall)
                : tcclCall.get();
    }

    private <X extends Throwable> void runPluginCallback(
            PluginThreadContext.ThrowingRunnable<X> callback
    ) throws X {
        callPluginCallback(() -> {
            callback.run();
            return null;
        });
    }

    /** Run host-owned NodePlugin teardown after ordinary catalog admission seals. */
    private <X extends Throwable> void runPluginTeardownCallback(
            PluginThreadContext.ThrowingRunnable<X> callback
    ) throws X {
        PluginThreadContext.ThrowingRunnable<X> tcclRun =
                () -> PluginThreadContext.run(classLoader, callback);
        if (catalogCallbacks != null) {
            catalogCallbacks.runNodePluginTeardown(tcclRun);
        } else {
            tcclRun.run();
        }
    }

    /** Start every initialized plugin transactionally in dependency order. */
    public void startAll() {
        requireNotInCatalogCallback("start plugins");
        synchronized (this) {
            if (state == State.STARTED) {
                return;
            }
            if (state != State.INITIALIZED && state != State.STOPPED) {
                throw new IllegalStateException("Cannot start plugins from state " + state);
            }

            if (state == State.STOPPED) {
                managedCallbacks.resumeNewGeneration();
            }

            state = State.STARTING;
            startedPlugins.clear();
        }
        for (NodePlugin plugin : plugins) {
            String id = idOf(plugin);
            try {
                observeLifecycle("starting", id, () -> lifecycleObserver.starting(id));
                pluginContexts.get(plugin).beginStartCycle();
                runPluginCallback(plugin::start);
                pluginContexts.get(plugin).completeStartCycle();
                synchronized (this) {
                    startedPlugins.add(plugin);
                }
                observeLifecycle("started", id, () -> lifecycleObserver.started(id));
            } catch (Throwable failure) {
                Throwable startupCause = failure;
                try {
                    observeLifecycle("start-failed", id,
                            () -> lifecycleObserver.startFailed(id));
                } catch (Throwable fatalObservation) {
                    startupCause = LifecycleFailures.merge(
                            startupCause, fatalObservation);
                }
                List<Throwable> cleanupFailures = new ArrayList<>();
                CompletableFuture<Void> callbackCleanup =
                        managedCallbacks.sealTerminal();
                managedCallbacks.awaitCompletion(callbackCleanup);
                cleanupFailures.addAll(managedCallbacks.drainCleanupFailures());
                stopOne(plugin, id, cleanupFailures);
                stopStartedReverse(cleanupFailures);
                closeInitializedReverse(cleanupFailures);
                synchronized (this) {
                    state = State.FAILED;
                    clearPublishedState();
                }
                throw startupFailure(FailurePhase.START, id,
                        "Plugin start failed", startupCause, cleanupFailures);
            }
        }
        synchronized (this) {
            state = State.STARTED;
        }
    }

    /** Stop successfully started plugins in reverse dependency order. */
    public void stopAll() {
        // Check before changing state or sealing. A callback cannot wait for
        // its own generation completion, and leaving STOPPING behind after
        // that rejection would make a later host-thread cleanup impossible.
        requireLifecycleTeardownAllowed("stop plugins");

        CompletableFuture<Void> callbackCleanup;
        synchronized (this) {
            if (state == State.STOPPED || state == State.FAILED || state == State.CLOSED) {
                return;
            }
            if (state != State.STARTED) {
                rejectTransitionalState("stop");
                return;
            }
            state = State.STOPPING;
            callbackCleanup = managedCallbacks.sealStartCycle();
        }

        // Do not hold the manager monitor here. An admitted callback may make
        // a synchronized read of the public manager while it finishes.
        managedCallbacks.awaitCompletion(callbackCleanup);
        List<Throwable> failures = new ArrayList<>(
                managedCallbacks.drainCleanupFailures());

        // STOPPING serializes lifecycle ownership; do not hold the monitor
        // across plugin code because stop() may wait for a worker that performs
        // a synchronized manager read.
        stopStartedReverse(failures);
        synchronized (this) {
            state = failures.isEmpty() ? State.STOPPED : State.FAILED;
        }
        if (!failures.isEmpty()) {
            throw cleanupFailure(FailurePhase.STOP, "Plugin stop failed", failures);
        }
    }

    /**
     * Stop admitting managed EventBus/filter/scheduler callbacks and cancel
     * resources owned by this start cycle. The returned signal completes only
     * after callbacks admitted before the seal return; sealing itself is
     * bounded and does not wait for interrupt-resistant plugin code.
     */
    public synchronized CompletableFuture<Void> sealManagedCallbacks() {
        return managedCallbacks.sealStartCycle();
    }

    /** Current managed-callback generation lifetime signal. */
    public synchronized CompletableFuture<Void> managedCallbacksCompletion() {
        return managedCallbacks.completion();
    }

    /**
     * Terminal, idempotent close. Dependencies remain available while their
     * dependents close because owner registrations are removed afterwards.
     */
    @Override
    public void close() {
        // Concurrent host closes are idempotent and wait below; only callback
        // self-wait must be rejected before entering that close protocol.
        managedCallbacks.requireNotInCallback("close plugins");
        requireNotInCatalogCallback("close plugins");

        boolean stoppingStartedPlugins;
        CompletableFuture<Void> callbackCleanup;
        synchronized (this) {
            boolean interrupted = false;
            while (state == State.CLOSING) {
                if (closingThread == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "Cannot close plugins recursively from a plugin close callback");
                }
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (state == State.CLOSED) {
                return;
            }
            rejectTransitionalState("close");
            stoppingStartedPlugins = state == State.STARTED;
            state = State.CLOSING;
            closingThread = Thread.currentThread();
            callbackCleanup = managedCallbacks.sealTerminal();
        }

        // As in stopAll(), release the manager monitor while callbacks drain.
        managedCallbacks.awaitCompletion(callbackCleanup);
        List<Throwable> managedFailures = managedCallbacks.drainCleanupFailures();

        List<Throwable> stopFailures = new ArrayList<>();
        if (stoppingStartedPlugins) {
            stopFailures.addAll(managedFailures);
            stopStartedReverse(stopFailures);
        }

        List<Throwable> closeFailures = new ArrayList<>();
        if (!stoppingStartedPlugins) {
            closeFailures.addAll(managedFailures);
        }
        try {
            // CLOSING excludes another lifecycle owner while allowing plugin
            // close callbacks and their workers to inspect manager snapshots.
            closeInitializedReverse(closeFailures);
            closeInternallyDiscoveredReverse(closeFailures);
        } finally {
            synchronized (this) {
                clearPublishedState();
                state = State.CLOSED;
                closingThread = null;
                notifyAll();
            }
        }

        if (!stopFailures.isEmpty()) {
            stopFailures.addAll(closeFailures);
            throw cleanupFailure(FailurePhase.STOP,
                    "Plugin close completed after stop failure", stopFailures);
        }
        if (!closeFailures.isEmpty()) {
            throw cleanupFailure(FailurePhase.CLOSE, "Plugin close failed", closeFailures);
        }
    }

    /** Reject teardown that would synchronously wait for the initiating callback. */
    public void requireLifecycleTeardownAllowed(String action) {
        managedCallbacks.requireNotInCallback(action);
        requireNotInCatalogCallback(action);
        synchronized (this) {
            if (state == State.DISCOVERING || state == State.INITIALIZING
                    || state == State.STARTING || state == State.STOPPING
                    || state == State.CLOSING) {
                throw new IllegalStateException("Cannot " + action
                        + " during plugin lifecycle callback/state " + state);
            }
        }
    }

    private void requireNotInCatalogCallback(String action) {
        if (catalogCallbacks != null) {
            catalogCallbacks.requireNotInCallback(action);
        }
    }

    synchronized boolean isTerminallyClosed() {
        return state == State.CLOSED;
    }

    /** Immutable snapshot of filters from the successfully initialized set. */
    public synchronized List<StorageFilter> getStorageFilters() {
        return contributionsPublished() ? storageFilters.snapshot() : List.of();
    }

    /** Immutable snapshot of initialized header-validation customizers. */
    public synchronized List<HeaderValidationCustomizer> getHeaderValidationCustomizers() {
        return contributionsPublished() ? List.copyOf(headerValidationCustomizers) : List.of();
    }

    private List<PluginDescriptor> discoverValidateAndOrder() {
        List<NodePlugin> candidates = discoverCandidates();
        List<PluginDescriptor> descriptors = new ArrayList<>(candidates.size());
        for (NodePlugin plugin : candidates) {
            descriptors.add(snapshotMetadata(plugin));
        }

        // Duplicate identity is a discovery defect. Policy cannot safely pick
        // between two implementations claiming the same id.
        Map<String, PluginDescriptor> discovered = new TreeMap<>();
        for (PluginDescriptor descriptor : descriptors) {
            PluginDescriptor previous = discovered.putIfAbsent(descriptor.id(), descriptor);
            if (previous != null) {
                throw problem(FailurePhase.VALIDATION, descriptor.id(),
                        "Duplicate plugin id '" + descriptor.id() + "'", null);
            }
        }

        Set<String> missingAllowed = new TreeSet<>(options.allowList());
        // Deny is authoritative even when an id also appears in allow.
        missingAllowed.removeAll(options.denyList());
        missingAllowed.removeAll(discovered.keySet());
        if (!missingAllowed.isEmpty()) {
            throw problem(FailurePhase.POLICY, null,
                    "Allow-listed plugins were not discovered: " + missingAllowed, null);
        }

        Map<String, PluginDescriptor> selected = new TreeMap<>();
        for (PluginDescriptor descriptor : discovered.values()) {
            String id = descriptor.id();
            if (options.denyList().contains(id)) {
                log.info("Plugin '{}' filtered by deny policy", id);
            } else if (!options.allowList().isEmpty() && !options.allowList().contains(id)) {
                log.info("Plugin '{}' filtered because it is not allow-listed", id);
            } else {
                selected.put(id, descriptor);
            }
        }

        Map<String, Integer> preferredOrder = new LinkedHashMap<>();
        if (preserveSuppliedOrder) {
            for (int i = 0; i < descriptors.size(); i++) {
                preferredOrder.put(descriptors.get(i).id(), i);
            }
        }
        List<PluginDescriptor> ordered = topologicalOrder(selected, preferredOrder);
        if (suppliedPlugins == null) {
            Set<NodePlugin> selectedInstances = Collections.newSetFromMap(
                    new IdentityHashMap<>());
            ordered.forEach(descriptor -> selectedInstances.add(descriptor.plugin()));
            Map<NodePlugin, String> discoveredIds = new IdentityHashMap<>();
            descriptors.forEach(descriptor -> discoveredIds.put(
                    descriptor.plugin(), descriptor.id()));
            List<Throwable> closeFailures = new ArrayList<>();
            for (int index = internallyDiscoveredOrder.size() - 1; index >= 0; index--) {
                NodePlugin plugin = internallyDiscoveredOrder.get(index);
                if (!selectedInstances.contains(plugin) && internallyOwned.contains(plugin)) {
                    closeOne(plugin, discoveredIds.getOrDefault(
                            plugin, plugin.getClass().getName()), closeFailures);
                }
            }
            internallyDiscoveredOrder.removeIf(
                    plugin -> !internallyOwned.contains(plugin));
            if (!closeFailures.isEmpty()) {
                throw cleanupFailure(FailurePhase.DISCOVERY,
                        "Filtered NodePlugin cleanup failed", closeFailures);
            }
        }
        return ordered;
    }

    private List<NodePlugin> discoverCandidates() {
        if (suppliedPlugins != null) {
            return suppliedPlugins;
        }
        try {
            ServiceLoader<NodePlugin> loader = PluginThreadContext.call(
                    classLoader, () -> ServiceLoader.load(NodePlugin.class, classLoader));
            Iterator<ServiceLoader.Provider<NodePlugin>> providers =
                    PluginThreadContext.call(classLoader,
                            () -> loader.stream().iterator());
            return discoverCandidatesForTesting(
                    providers, MAX_DISCOVERED_NODE_PLUGINS);
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            throw problem(FailurePhase.DISCOVERY, null,
                    "NodePlugin discovery failed", failure);
        }
    }

    /** Package-private iterator seam for the public compatibility-path bound. */
    List<NodePlugin> discoverCandidatesForTesting(
            Iterator<ServiceLoader.Provider<NodePlugin>> providers,
            int maximumCandidates
    ) {
        Objects.requireNonNull(providers, "providers");
        if (maximumCandidates < 1) {
            throw new IllegalArgumentException("maximumCandidates must be positive");
        }
        List<NodePlugin> result = new ArrayList<>();
        while (PluginThreadContext.call(classLoader, providers::hasNext)) {
            if (result.size() == maximumCandidates) {
                throw new IllegalStateException(
                        "NodePlugin ServiceLoader discovery exceeds the global limit of "
                                + maximumCandidates);
            }
            ServiceLoader.Provider<NodePlugin> provider = Objects.requireNonNull(
                    PluginThreadContext.call(classLoader, providers::next),
                    "ServiceLoader returned a null provider handle");
            NodePlugin plugin = Objects.requireNonNull(
                    PluginThreadContext.call(classLoader, provider::get),
                    "ServiceLoader returned null plugin");
            result.add(plugin);
            internallyDiscoveredOrder.add(plugin);
            internallyOwned.add(plugin);
        }
        return List.copyOf(result);
    }

    private PluginDescriptor snapshotMetadata(NodePlugin plugin) {
        if (plugin == null) {
            throw problem(FailurePhase.DISCOVERY, null,
                    "Discovered a null NodePlugin", null);
        }
        try {
            String id = requireIdentifier("plugin id",
                    callPluginCallback(plugin::id), MAX_PLUGIN_ID_LENGTH);
            String version = requireIdentifier("plugin version",
                    callPluginCallback(plugin::version), MAX_PLUGIN_VERSION_LENGTH);
            Set<String> rawDependencies = callPluginCallback(plugin::dependsOn);
            Set<String> dependencies = snapshotDependencies(
                    id, rawDependencies);
            Set<PluginCapability> rawCapabilities = callPluginCallback(plugin::capabilities);
            Set<PluginCapability> capabilities = snapshotCapabilities(rawCapabilities);
            PluginDescriptor descriptor = new PluginDescriptor(plugin, id, version,
                    dependencies, capabilities);
            log.info("Discovered plugin: {}:{}", id, version);
            return descriptor;
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            throw problem(FailurePhase.DISCOVERY, null,
                    "Invalid NodePlugin metadata from " + plugin.getClass().getName(), failure);
        }
    }

    private Set<String> snapshotDependencies(
            String id,
            Set<String> dependencies
    ) {
        if (dependencies == null) {
            throw new IllegalArgumentException("Plugin dependencies must not be null");
        }
        Set<String> snapshot = new TreeSet<>();
        Iterator<String> iterator = callPluginCallback(dependencies::iterator);
        int traversed = 0;
        while (callPluginCallback(iterator::hasNext)) {
            if (++traversed > MAX_PLUGIN_DEPENDENCIES) {
                throw new IllegalArgumentException("Plugin dependencies must contain at most "
                        + MAX_PLUGIN_DEPENDENCIES + " entries");
            }
            String dependency = callPluginCallback(iterator::next);
            String value = requireIdentifier(
                    "dependency id", dependency, MAX_PLUGIN_ID_LENGTH);
            if (id.equals(value)) {
                throw new IllegalArgumentException("Plugin '" + id + "' depends on itself");
            }
            snapshot.add(value);
        }
        return Collections.unmodifiableSet(snapshot);
    }

    private Set<PluginCapability> snapshotCapabilities(
            Set<PluginCapability> capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("Plugin capabilities must not be null");
        }
        Set<PluginCapability> snapshot = new LinkedHashSet<>();
        Iterator<PluginCapability> iterator = callPluginCallback(capabilities::iterator);
        int traversed = 0;
        while (callPluginCallback(iterator::hasNext)) {
            if (++traversed > MAX_PLUGIN_CAPABILITIES) {
                throw new IllegalArgumentException("Plugin capabilities must contain at most "
                        + MAX_PLUGIN_CAPABILITIES + " entries");
            }
            PluginCapability capability = callPluginCallback(iterator::next);
            snapshot.add(Objects.requireNonNull(
                    capability, "Plugin capabilities must not contain null"));
        }
        return Collections.unmodifiableSet(snapshot);
    }

    private static String requireIdentifier(String label, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must not have surrounding whitespace");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(label + " must not exceed "
                    + maxLength + " characters");
        }
        return value;
    }

    private List<PluginDescriptor> topologicalOrder(
            Map<String, PluginDescriptor> selected,
            Map<String, Integer> preferredOrder
    ) {
        Map<String, Integer> indegree = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        for (String id : selected.keySet()) {
            indegree.put(id, 0);
            dependents.put(id, new TreeSet<>());
        }

        for (PluginDescriptor descriptor : selected.values()) {
            for (String dependency : descriptor.dependencies()) {
                if (!selected.containsKey(dependency)
                        && !externallySatisfiedDependencies.contains(dependency)) {
                    throw problem(FailurePhase.VALIDATION, descriptor.id(),
                            "Plugin '" + descriptor.id() + "' requires unavailable plugin '"
                                    + dependency + "'", null);
                }
                if (selected.containsKey(dependency)) {
                    indegree.compute(descriptor.id(), (ignored, value) -> value + 1);
                    dependents.get(dependency).add(descriptor.id());
                }
            }
        }

        Comparator<String> readyOrder = preferredOrder.isEmpty()
                ? Comparator.naturalOrder()
                : Comparator.comparingInt(
                                (String id) -> preferredOrder.getOrDefault(id, Integer.MAX_VALUE))
                        .thenComparing(Comparator.naturalOrder());
        PriorityQueue<String> ready = new PriorityQueue<>(readyOrder);
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });

        List<PluginDescriptor> ordered = new ArrayList<>(selected.size());
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(selected.get(id));
            for (String dependent : dependents.get(id)) {
                int degree = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (degree == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (ordered.size() != selected.size()) {
            throw problem(FailurePhase.VALIDATION, null,
                    boundedCycleDiagnostic(findCyclePath(selected)), null);
        }
        return List.copyOf(ordered);
    }

    private static String boundedCycleDiagnostic(List<String> cycle) {
        long renderedLength = DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX.length() + 2L;
        for (int index = 0; index < cycle.size(); index++) {
            renderedLength += cycle.get(index).length() + (index == 0 ? 0 : 2);
            if (renderedLength > MAX_CYCLE_DIAGNOSTIC_LENGTH) {
                return compactCycleDiagnostic(cycle);
            }
        }
        return DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX + cycle;
    }

    private static String compactCycleDiagnostic(List<String> cycle) {
        int traversedNodes = Math.max(0, cycle.size() - 1);
        if (traversedNodes == 0) {
            return DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX + "[]";
        }

        int headCount = Math.min(2, traversedNodes);
        int tailCount = Math.min(2, traversedNodes - headCount);
        int omittedCount = traversedNodes - headCount - tailCount;
        List<String> displayed = new ArrayList<>(headCount + tailCount + 2);
        for (int index = 0; index < headCount; index++) {
            displayed.add(abbreviateCycleId(cycle.get(index)));
        }
        if (omittedCount > 0) {
            displayed.add("... <" + omittedCount + " nodes omitted> ...");
        }
        for (int index = traversedNodes - tailCount; index < traversedNodes; index++) {
            displayed.add(abbreviateCycleId(cycle.get(index)));
        }
        // findCyclePath always repeats the first node last; retain that closure
        // in the compact diagnostic so operators can still recognize a cycle.
        displayed.add(abbreviateCycleId(cycle.getLast()));
        return DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX + displayed;
    }

    private static String abbreviateCycleId(String id) {
        if (id.length() <= MAX_CYCLE_ID_DISPLAY_LENGTH) {
            return id;
        }
        int prefixLength = (MAX_CYCLE_ID_DISPLAY_LENGTH - 3) / 2;
        int suffixLength = MAX_CYCLE_ID_DISPLAY_LENGTH - 3 - prefixLength;
        return id.substring(0, prefixLength) + "..."
                + id.substring(id.length() - suffixLength);
    }

    private static List<String> findCyclePath(Map<String, PluginDescriptor> selected) {
        Map<String, VisitState> states = new TreeMap<>();
        selected.keySet().forEach(id -> states.put(id, VisitState.NEW));
        Deque<String> path = new ArrayDeque<>();
        for (String id : selected.keySet()) {
            if (states.get(id) != VisitState.NEW) {
                continue;
            }
            List<String> cycle = findCycleFrom(id, selected, states, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of(); // Kahn detected a cycle; this is only a defensive fallback.
    }

    private static List<String> findCycleFrom(String id,
                                              Map<String, PluginDescriptor> selected,
                                              Map<String, VisitState> states,
                                              Deque<String> path) {
        states.put(id, VisitState.VISITING);
        path.addLast(id);
        Deque<ManagerCycleFrame> frames = new ArrayDeque<>();
        frames.addLast(managerCycleFrame(id, selected));

        while (!frames.isEmpty()) {
            ManagerCycleFrame frame = frames.getLast();
            if (!frame.dependencies().hasNext()) {
                frames.removeLast();
                path.removeLast();
                states.put(frame.id(), VisitState.DONE);
                continue;
            }

            String dependency = frame.dependencies().next();
            VisitState dependencyState = states.get(dependency);
            if (dependencyState == null || dependencyState == VisitState.DONE) {
                continue;
            }
            if (dependencyState == VisitState.VISITING) {
                List<String> active = new ArrayList<>(path);
                int start = active.indexOf(dependency);
                List<String> cycle = new ArrayList<>(active.subList(start, active.size()));
                cycle.add(dependency);
                return List.copyOf(cycle);
            }

            states.put(dependency, VisitState.VISITING);
            path.addLast(dependency);
            frames.addLast(managerCycleFrame(dependency, selected));
        }
        return List.of();
    }

    private static ManagerCycleFrame managerCycleFrame(
            String id,
            Map<String, PluginDescriptor> selected
    ) {
        Iterator<String> dependencies = selected.get(id).dependencies().stream()
                .filter(selected::containsKey)
                .sorted()
                .iterator();
        return new ManagerCycleFrame(id, dependencies);
    }

    private void refreshHeaderValidationCustomizers() {
        headerValidationCustomizers.clear();
        for (NodePlugin plugin : plugins) {
            if (plugin instanceof HeaderValidationCustomizer customizer) {
                headerValidationCustomizers.add(builder -> managedCallbacks.runOrSkip(
                        PluginContextFacades.CURRENT_GENERATION,
                        () -> runPluginCallback(() -> customizer.customize(builder))));
            }
        }
    }

    private void stopStartedReverse(List<Throwable> failures) {
        for (int i = startedPlugins.size() - 1; i >= 0; i--) {
            NodePlugin plugin = startedPlugins.get(i);
            stopOne(plugin, idOf(plugin), failures);
        }
        startedPlugins.clear();
    }

    private void closeInitializedReverse(List<Throwable> failures) {
        for (int i = plugins.size() - 1; i >= 0; i--) {
            NodePlugin plugin = plugins.get(i);
            closeOne(plugin, idOf(plugin), failures);
        }
        plugins.clear();
    }

    private void stopOne(NodePlugin plugin, String id, List<Throwable> failures) {
        int failuresBefore = failures.size();
        PluginContextImpl context = pluginContexts.get(plugin);
        try {
            if (context != null) {
                context.beginStopCycle();
            }
        } catch (Throwable failure) {
            failures.add(failure);
            log.warn("Plugin contribution stop transition failed for '{}' (errorType={})",
                    id, failure.getClass().getName());
        }
        try {
            runPluginTeardownCallback(plugin::stop);
        } catch (Throwable failure) {
            failures.add(failure);
            log.warn("Plugin stop failed for '{}' (errorType={})",
                    id, failure.getClass().getName());
        } finally {
            try {
                if (context != null) {
                    context.endStartCycle();
                }
            } catch (Throwable failure) {
                failures.add(failure);
                log.warn("Plugin contribution cleanup failed for '{}' (errorType={})",
                        id, failure.getClass().getName());
            }
            boolean succeeded = failures.size() == failuresBefore;
            try {
                observeLifecycle("stopped", id,
                        () -> lifecycleObserver.stopped(id, succeeded));
            } catch (Throwable observationFailure) {
                // Preserve process-fatal precedence without abandoning the
                // remaining reverse-order plugin teardown.
                failures.add(observationFailure);
            }
        }
    }

    private void closeOne(NodePlugin plugin, String id, List<Throwable> failures) {
        int failuresBefore = failures.size();
        try {
            runPluginTeardownCallback(plugin::close);
        } catch (Throwable failure) {
            failures.add(failure);
            log.warn("Plugin close failed for '{}' (errorType={})",
                    id, failure.getClass().getName());
        } finally {
            try {
                terminalCloseObserver.accept(plugin);
            } catch (Throwable failure) {
                failures.add(failure);
                log.warn("Plugin terminal-close observer failed for '{}' (errorType={})",
                        id, failure.getClass().getName());
            }
            PluginContextImpl context = pluginContexts.remove(plugin);
            if (context != null) {
                context.deactivate();
            }
            services.removeOwner(id);
            storageFilters.removeOwner(id);
            internallyOwned.remove(plugin);
            boolean succeeded = failures.size() == failuresBefore;
            try {
                observeLifecycle("closed", id,
                        () -> lifecycleObserver.closed(id, succeeded));
            } catch (Throwable observationFailure) {
                // closeInitializedReverse must still attempt every owner. The
                // aggregate promotes this fatal observation after cleanup.
                failures.add(observationFailure);
            }
        }
    }

    private void observeLifecycle(String transition, String id, Runnable observation) {
        try {
            observation.run();
        } catch (Throwable observationFailure) {
            LifecycleFailures.rethrowIfProcessFatalReachable(observationFailure);
            log.warn("NodePlugin lifecycle observation failed "
                            + "(plugin={}, transition={}, errorType={})",
                    id, transition, observationFailure.getClass().getName());
        }
    }

    private void closeInternallyDiscoveredReverse(List<Throwable> failures) {
        for (int index = internallyDiscoveredOrder.size() - 1; index >= 0; index--) {
            NodePlugin plugin = internallyDiscoveredOrder.get(index);
            if (internallyOwned.contains(plugin)) {
                closeOne(plugin, idOf(plugin), failures);
            }
        }
        internallyDiscoveredOrder.clear();
    }

    private String idOf(NodePlugin plugin) {
        return pluginIds.getOrDefault(plugin, plugin.getClass().getName());
    }

    private void clearPublishedState() {
        pluginContexts.values().forEach(PluginContextImpl::deactivate);
        pluginContexts.clear();
        startedPlugins.clear();
        plugins.clear();
        pluginIds.clear();
        headerValidationCustomizers.clear();
        services.clear();
        storageFilters.clear();
    }

    private void requireState(State expected, String action) {
        if (state != expected) {
            throw new IllegalStateException("Cannot " + action + " plugins from state " + state);
        }
    }

    private void rejectTransitionalState(String action) {
        if (state == State.DISCOVERING || state == State.INITIALIZING
                || state == State.STARTING || state == State.STOPPING
                || state == State.CLOSING) {
            throw new IllegalStateException("Cannot " + action
                    + " plugins during lifecycle callback/state " + state);
        }
    }

    private boolean contributionsPublished() {
        return state == State.INITIALIZED || state == State.STARTED || state == State.STOPPED;
    }

    private static PluginsOptions snapshot(PluginsOptions options) {
        return new PluginsOptions(options.enabled(), options.autoRegisterAnnotated(),
                options.allowList(), options.denyList(), options.config());
    }

    private static Logger pluginLogger(String id) {
        return LoggerFactory.getLogger("com.bloxbean.cardano.yano.plugin." + id);
    }

    private static PluginManagerException problem(FailurePhase phase, String pluginId,
                                                  String message, Throwable cause) {
        return new PluginManagerException(phase, pluginId, message, cause);
    }

    private static RuntimeException propagate(Throwable failure) {
        LifecycleFailures.rethrowIfProcessFatalReachable(failure);
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return problem(FailurePhase.DISCOVERY, null,
                "Unexpected plugin discovery failure", failure);
    }

    private static PluginManagerException startupFailure(
            FailurePhase phase, String pluginId, String message, Throwable primary,
            List<Throwable> cleanupFailures) {
        Throwable winner = mergeFailures(primary, cleanupFailures);
        LifecycleFailures.rethrowIfProcessFatalReachable(winner);
        return problem(phase, pluginId,
                message + " for '" + pluginId + "'", winner);
    }

    private static PluginManagerException cleanupFailure(
            FailurePhase phase, String message, List<Throwable> failures) {
        log.warn("{} with {} callback failure(s)", message, failures.size());
        Throwable winner = mergeFailures(null, failures);
        LifecycleFailures.rethrowIfProcessFatalReachable(winner);
        return problem(phase, null, message, winner);
    }

    private static Throwable mergeFailures(
            Throwable initial,
            List<Throwable> additional
    ) {
        Throwable winner = initial;
        for (Throwable failure : additional) {
            winner = LifecycleFailures.merge(winner, failure);
        }
        return winner;
    }

    private enum State {
        NEW,
        DISCOVERING,
        INITIALIZING,
        INITIALIZED,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        CLOSING,
        FAILED,
        CLOSED
    }

    private enum VisitState {
        NEW,
        VISITING,
        DONE
    }

    public enum FailurePhase {
        DISCOVERY,
        POLICY,
        VALIDATION,
        INITIALIZATION,
        START,
        STOP,
        CLOSE
    }

    /** Stable phase/id diagnostic for startup and catalog validation errors. */
    public static final class PluginManagerException extends IllegalStateException {
        private final FailurePhase phase;
        private final String pluginId;

        public PluginManagerException(FailurePhase phase, String pluginId,
                                      String message, Throwable cause) {
            super(message, cause);
            this.phase = Objects.requireNonNull(phase, "phase");
            this.pluginId = pluginId;
        }

        public FailurePhase phase() {
            return phase;
        }

        public Optional<String> pluginId() {
            return Optional.ofNullable(pluginId);
        }
    }

    private record PluginDescriptor(NodePlugin plugin,
                                    String id,
                                    String version,
                                    Set<String> dependencies,
                                    Set<PluginCapability> capabilities) {
    }

    private record ManagerCycleFrame(String id, Iterator<String> dependencies) {
    }
}
