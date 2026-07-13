package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
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
import java.util.concurrent.ScheduledExecutorService;

/**
 * Lifecycle owner for the legacy {@link NodePlugin} SPI.
 *
 * <p>ADR-011.1 deliberately keeps the public plugin SPI and ServiceLoader
 * discovery mechanism, but makes activation transactional: discovery,
 * metadata snapshotting, operator policy and dependency validation all finish
 * before the first lifecycle callback. Selected plugins initialize/start in a
 * deterministic dependency order and stop/close in reverse order.</p>
 *
 * <p>This class is not a dynamic bundle loader. Manifest parsing, API-version
 * compatibility, class-loader isolation and the app-layer typed-SPI catalog
 * remain later ADR-011 work.</p>
 *
 * <p>Lifecycle methods are synchronized. Plugin contexts and their shared
 * registry remain safe for concurrent access after initialization.</p>
 */
public final class PluginManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final PluginsOptions options;
    private final ClassLoader classLoader;
    private final List<NodePlugin> suppliedPlugins;

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

    /**
     * Compatibility constructor for existing embedders. It preserves today's
     * discover-all policy while snapshotting the supplied configuration map.
     */
    public PluginManager(EventBus eventBus, ScheduledExecutorService scheduler,
                         Map<String, Object> config, ClassLoader classLoader) {
        this(eventBus, scheduler,
                new PluginsOptions(true, false, Set.of(), Set.of(), config),
                classLoader, null);
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
        return new PluginManager(eventBus, scheduler, options, classLoader, null);
    }

    /** Test/programmatic source used without changing the public discovery SPI. */
    PluginManager(EventBus eventBus, ScheduledExecutorService scheduler,
                  PluginsOptions options, ClassLoader classLoader,
                  Collection<NodePlugin> suppliedPlugins) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.options = snapshot(Objects.requireNonNull(options, "options"));
        this.classLoader = classLoader != null
                ? classLoader : Thread.currentThread().getContextClassLoader();
        this.suppliedPlugins = suppliedPlugins != null ? List.copyOf(suppliedPlugins) : null;
    }

    /** Discover, validate and initialize the selected plugin set exactly once. */
    public synchronized void discoverAndInit() {
        if (state == State.INITIALIZED || state == State.STARTED || state == State.STOPPED) {
            return;
        }
        requireState(State.NEW, "discover and initialize");
        state = State.DISCOVERING;

        if (!options.enabled()) {
            state = State.INITIALIZED;
            log.info("Plugin discovery is disabled");
            return;
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
            state = State.FAILED;
            clearPublishedState();
            throw propagate(failure);
        }

        for (PluginDescriptor descriptor : ordered) {
            pluginIds.put(descriptor.plugin(), descriptor.id());
        }

        state = State.INITIALIZING;
        for (PluginDescriptor descriptor : ordered) {
            PluginContextImpl context = new PluginContextImpl(
                    descriptor.id(), eventBus, pluginLogger(descriptor.id()), options.config(),
                    scheduler, Optional.ofNullable(classLoader), storageFilters, services);
            pluginContexts.put(descriptor.plugin(), context);
            try {
                descriptor.plugin().init(context);
                context.completeInitialization();
                plugins.add(descriptor.plugin());
            } catch (Throwable failure) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                closeOne(descriptor.plugin(), descriptor.id(), cleanupFailures);
                closeInitializedReverse(cleanupFailures);
                state = State.FAILED;
                clearPublishedState();
                throw startupFailure(FailurePhase.INITIALIZATION, descriptor.id(),
                        "Plugin initialization failed", failure, cleanupFailures);
            }
        }

        refreshHeaderValidationCustomizers();
        state = State.INITIALIZED;
        log.info("Initialized plugins in dependency order: {}",
                ordered.stream().map(PluginDescriptor::id).toList());
    }

    /** Start every initialized plugin transactionally in dependency order. */
    public synchronized void startAll() {
        if (state == State.STARTED) {
            return;
        }
        if (state != State.INITIALIZED && state != State.STOPPED) {
            throw new IllegalStateException("Cannot start plugins from state " + state);
        }

        state = State.STARTING;
        startedPlugins.clear();
        for (NodePlugin plugin : plugins) {
            String id = idOf(plugin);
            try {
                pluginContexts.get(plugin).beginStartCycle();
                plugin.start();
                pluginContexts.get(plugin).completeStartCycle();
                startedPlugins.add(plugin);
            } catch (Throwable failure) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                stopOne(plugin, id, cleanupFailures);
                stopStartedReverse(cleanupFailures);
                closeInitializedReverse(cleanupFailures);
                state = State.FAILED;
                clearPublishedState();
                throw startupFailure(FailurePhase.START, id,
                        "Plugin start failed", failure, cleanupFailures);
            }
        }
        state = State.STARTED;
    }

    /** Stop successfully started plugins in reverse dependency order. */
    public synchronized void stopAll() {
        if (state == State.STOPPED || state == State.FAILED || state == State.CLOSED) {
            return;
        }
        if (state != State.STARTED) {
            rejectTransitionalState("stop");
            return;
        }
        state = State.STOPPING;
        List<Throwable> failures = new ArrayList<>();
        stopStartedReverse(failures);
        if (failures.isEmpty()) {
            state = State.STOPPED;
            return;
        }
        state = State.FAILED;
        throw cleanupFailure(FailurePhase.STOP, "Plugin stop failed", failures);
    }

    /**
     * Terminal, idempotent close. Dependencies remain available while their
     * dependents close because owner registrations are removed afterwards.
     */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        rejectTransitionalState("close");

        List<Throwable> stopFailures = new ArrayList<>();
        if (state == State.STARTED) {
            state = State.STOPPING;
            stopStartedReverse(stopFailures);
            state = stopFailures.isEmpty() ? State.STOPPED : State.FAILED;
        }

        state = State.CLOSING;
        List<Throwable> closeFailures = new ArrayList<>();
        try {
            closeInitializedReverse(closeFailures);
        } finally {
            clearPublishedState();
            state = State.CLOSED;
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

        return topologicalOrder(selected);
    }

    private List<NodePlugin> discoverCandidates() {
        if (suppliedPlugins != null) {
            return suppliedPlugins;
        }
        try {
            List<NodePlugin> result = new ArrayList<>();
            ServiceLoader<NodePlugin> loader = ServiceLoader.load(NodePlugin.class, classLoader);
            for (ServiceLoader.Provider<NodePlugin> provider : loader.stream().toList()) {
                result.add(Objects.requireNonNull(provider.get(), "ServiceLoader returned null plugin"));
            }
            return List.copyOf(result);
        } catch (ServiceConfigurationError | RuntimeException | LinkageError failure) {
            throw problem(FailurePhase.DISCOVERY, null,
                    "NodePlugin discovery failed", failure);
        }
    }

    private PluginDescriptor snapshotMetadata(NodePlugin plugin) {
        if (plugin == null) {
            throw problem(FailurePhase.DISCOVERY, null,
                    "Discovered a null NodePlugin", null);
        }
        try {
            String id = requireIdentifier("plugin id", plugin.id());
            String version = requireIdentifier("plugin version", plugin.version());
            Set<String> dependencies = snapshotDependencies(id, plugin.dependsOn());
            Set<PluginCapability> capabilities = plugin.capabilities();
            if (capabilities == null || capabilities.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Plugin capabilities must not be null");
            }
            PluginDescriptor descriptor = new PluginDescriptor(plugin, id, version,
                    Collections.unmodifiableSet(new TreeSet<>(dependencies)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(capabilities)));
            log.info("Discovered plugin: {}:{}", id, version);
            return descriptor;
        } catch (Throwable failure) {
            if (failure instanceof PluginManagerException managerException) {
                throw managerException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw problem(FailurePhase.DISCOVERY, null,
                    "Invalid NodePlugin metadata from " + plugin.getClass().getName(), failure);
        }
    }

    private static Set<String> snapshotDependencies(String id, Set<String> dependencies) {
        if (dependencies == null) {
            throw new IllegalArgumentException("Plugin dependencies must not be null");
        }
        Set<String> snapshot = new TreeSet<>();
        for (String dependency : dependencies) {
            String value = requireIdentifier("dependency id", dependency);
            if (id.equals(value)) {
                throw new IllegalArgumentException("Plugin '" + id + "' depends on itself");
            }
            snapshot.add(value);
        }
        return snapshot;
    }

    private static String requireIdentifier(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must not have surrounding whitespace");
        }
        return value;
    }

    private List<PluginDescriptor> topologicalOrder(Map<String, PluginDescriptor> selected) {
        Map<String, Integer> indegree = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        for (String id : selected.keySet()) {
            indegree.put(id, 0);
            dependents.put(id, new TreeSet<>());
        }

        for (PluginDescriptor descriptor : selected.values()) {
            for (String dependency : descriptor.dependencies()) {
                if (!selected.containsKey(dependency)) {
                    throw problem(FailurePhase.VALIDATION, descriptor.id(),
                            "Plugin '" + descriptor.id() + "' requires unavailable plugin '"
                                    + dependency + "'", null);
                }
                indegree.compute(descriptor.id(), (ignored, value) -> value + 1);
                dependents.get(dependency).add(descriptor.id());
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
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
                    "Plugin dependency cycle: " + findCyclePath(selected), null);
        }
        return List.copyOf(ordered);
    }

    private static List<String> findCyclePath(Map<String, PluginDescriptor> selected) {
        Map<String, VisitState> states = new TreeMap<>();
        selected.keySet().forEach(id -> states.put(id, VisitState.NEW));
        Deque<String> path = new ArrayDeque<>();
        for (String id : selected.keySet()) {
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
        VisitState current = states.get(id);
        if (current == VisitState.DONE) {
            return List.of();
        }
        if (current == VisitState.VISITING) {
            List<String> active = new ArrayList<>(path);
            int start = active.indexOf(id);
            List<String> cycle = new ArrayList<>(active.subList(start, active.size()));
            cycle.add(id);
            return List.copyOf(cycle);
        }

        states.put(id, VisitState.VISITING);
        path.addLast(id);
        for (String dependency : selected.get(id).dependencies()) {
            List<String> cycle = findCycleFrom(dependency, selected, states, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.removeLast();
        states.put(id, VisitState.DONE);
        return List.of();
    }

    private void refreshHeaderValidationCustomizers() {
        headerValidationCustomizers.clear();
        for (NodePlugin plugin : plugins) {
            if (plugin instanceof HeaderValidationCustomizer customizer) {
                headerValidationCustomizers.add(customizer);
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
        PluginContextImpl context = pluginContexts.get(plugin);
        try {
            if (context != null) {
                context.beginStopCycle();
            }
        } catch (Throwable failure) {
            failures.add(failure);
            log.warn("Plugin contribution stop transition failed for '{}': {}",
                    id, failure.toString(), failure);
        }
        try {
            plugin.stop();
        } catch (Throwable failure) {
            failures.add(failure);
            log.warn("Plugin stop failed for '{}': {}", id, failure.toString(), failure);
        } finally {
            try {
                if (context != null) {
                    context.endStartCycle();
                }
            } catch (Throwable failure) {
                failures.add(failure);
                log.warn("Plugin contribution cleanup failed for '{}': {}",
                        id, failure.toString(), failure);
            }
        }
    }

    private void closeOne(NodePlugin plugin, String id, List<Throwable> failures) {
        try {
            plugin.close();
        } catch (Throwable failure) {
            failures.add(failure);
            log.warn("Plugin close failed for '{}': {}", id, failure.toString(), failure);
        } finally {
            PluginContextImpl context = pluginContexts.remove(plugin);
            if (context != null) {
                context.deactivate();
            }
            services.removeOwner(id);
            storageFilters.removeOwner(id);
        }
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
        Set<String> allow = Collections.unmodifiableSet(new LinkedHashSet<>(options.allowList()));
        Set<String> deny = Collections.unmodifiableSet(new LinkedHashSet<>(options.denyList()));
        Map<String, Object> config = Collections.unmodifiableMap(
                new LinkedHashMap<>(options.config()));
        return new PluginsOptions(options.enabled(), options.autoRegisterAnnotated(),
                allow, deny, config);
    }

    private static Logger pluginLogger(String id) {
        return LoggerFactory.getLogger("com.bloxbean.cardano.yano.plugin." + id);
    }

    private static PluginManagerException problem(FailurePhase phase, String pluginId,
                                                  String message, Throwable cause) {
        return new PluginManagerException(phase, pluginId, message, cause);
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return problem(FailurePhase.DISCOVERY, null,
                "Unexpected checked plugin failure", failure);
    }

    private static PluginManagerException startupFailure(
            FailurePhase phase, String pluginId, String message, Throwable primary,
            List<Throwable> cleanupFailures) {
        Error fatal = primary instanceof Error error ? error : cleanupFailures.stream()
                .filter(Error.class::isInstance)
                .map(Error.class::cast)
                .findFirst().orElse(null);
        if (fatal != null) {
            if (fatal != primary) {
                fatal.addSuppressed(primary);
            }
            for (Throwable cleanup : cleanupFailures) {
                if (cleanup != fatal) {
                    fatal.addSuppressed(cleanup);
                }
            }
            throw fatal;
        }

        PluginManagerException failure = problem(phase, pluginId,
                message + " for '" + pluginId + "'", primary);
        cleanupFailures.forEach(failure::addSuppressed);
        return failure;
    }

    private static PluginManagerException cleanupFailure(
            FailurePhase phase, String message, List<Throwable> failures) {
        log.warn("{} with {} callback failure(s)", message, failures.size());
        Error fatal = failures.stream()
                .filter(Error.class::isInstance)
                .map(Error.class::cast)
                .findFirst().orElse(null);
        if (fatal != null) {
            for (Throwable failure : failures) {
                if (failure != fatal) {
                    fatal.addSuppressed(failure);
                }
            }
            throw fatal;
        }
        Throwable primary = failures.getFirst();
        PluginManagerException failure = problem(phase, null, message, primary);
        failures.stream().skip(1).forEach(failure::addSuppressed);
        return failure;
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
}
