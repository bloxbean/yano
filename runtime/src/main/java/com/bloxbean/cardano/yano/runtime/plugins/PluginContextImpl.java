package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import org.slf4j.Logger;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

final class PluginContextImpl implements PluginContext {
    private final String pluginId;
    private final EventBus eventBus;
    private final Logger logger;
    private final Map<String, Object> config;
    private final Map<String, Object> bundleConfig;
    private final ScheduledExecutorService scheduler;
    private final Optional<ClassLoader> classLoader;
    private final ClassLoader callbackClassLoader;
    private final PluginContextFacades.ManagedCallbackResources managedCallbacks;
    private final SharedServiceRegistry registry;
    private final SharedStorageFilterRegistry storageFilters;
    private LifecyclePhase phase = LifecyclePhase.INITIALIZING;

    PluginContextImpl(String pluginId, EventBus eventBus, Logger logger, Map<String, Object> config,
                      ScheduledExecutorService scheduler, Optional<ClassLoader> classLoader,
                      ClassLoader platformClassLoader,
                      SharedStorageFilterRegistry storageFilters, SharedServiceRegistry registry,
                      PluginContextFacades.ManagedCallbackResources managedCallbacks) {
        this(pluginId, eventBus, logger, config, config, scheduler, classLoader,
                platformClassLoader, storageFilters, registry, managedCallbacks);
    }

    PluginContextImpl(String pluginId, EventBus eventBus, Logger logger, Map<String, Object> config,
                      Map<String, Object> bundleConfig,
                      ScheduledExecutorService scheduler, Optional<ClassLoader> classLoader,
                      ClassLoader platformClassLoader,
                      SharedStorageFilterRegistry storageFilters, SharedServiceRegistry registry,
                      PluginContextFacades.ManagedCallbackResources managedCallbacks) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.logger = logger;
        this.config = immutableMap(config);
        this.bundleConfig = immutableMap(bundleConfig);
        this.classLoader = classLoader != null ? classLoader : Optional.empty();
        this.callbackClassLoader = PluginThreadContext.effective(
                this.classLoader.orElse(null));
        this.managedCallbacks = Objects.requireNonNull(
                managedCallbacks, "managedCallbacks");
        this.eventBus = PluginContextFacades.eventBus(
                eventBus, callbackClassLoader,
                Objects.requireNonNull(platformClassLoader, "platformClassLoader"),
                managedCallbacks, this::callbackResourceScope);
        this.scheduler = PluginContextFacades.scheduler(
                scheduler, callbackClassLoader, platformClassLoader,
                managedCallbacks, this::callbackResourceScope);
        this.storageFilters = storageFilters;
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override public EventBus eventBus() { return eventBus; }
    @Override public Logger logger() { return logger; }
    @Override public Map<String, Object> config() { return config; }
    @Override public Map<String, Object> bundleConfig() { return bundleConfig; }
    @Override public ScheduledExecutorService scheduler() { return scheduler; }
    @Override public Optional<ClassLoader> pluginClassLoader() { return classLoader; }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values != null ? values : Map.of()));
    }

    @Override
    public synchronized void registerStorageFilter(StorageFilter filter) {
        RegistrationScope scope = filterRegistrationScope();
        storageFilters.register(pluginId, PluginContextFacades.storageFilter(
                Objects.requireNonNull(filter, "filter"), callbackClassLoader,
                managedCallbacks), scope);
    }

    @Override public synchronized void registerService(String key, Object service) {
        RegistrationScope scope = serviceRegistrationScope();
        registry.register(pluginId, key, service, scope);
    }

    @Override public <T> Optional<T> getService(String key, Class<T> type) {
        return registry.get(key, type);
    }

    synchronized void completeInitialization() {
        requirePhase(LifecyclePhase.INITIALIZING, "complete initialization");
        phase = LifecyclePhase.READY;
    }

    synchronized void beginStartCycle() {
        if (phase != LifecyclePhase.READY && phase != LifecyclePhase.STOPPED) {
            throw new IllegalStateException("Cannot start contribution cycle for plugin '"
                    + pluginId + "' from context phase " + phase);
        }
        phase = LifecyclePhase.STARTING;
    }

    synchronized void completeStartCycle() {
        requirePhase(LifecyclePhase.STARTING, "complete start");
        phase = LifecyclePhase.RUNNING;
    }

    synchronized void beginStopCycle() {
        if (phase != LifecyclePhase.STARTING && phase != LifecyclePhase.RUNNING) {
            throw new IllegalStateException("Cannot stop contribution cycle for plugin '"
                    + pluginId + "' from context phase " + phase);
        }
        phase = LifecyclePhase.STOPPING;
    }

    synchronized void endStartCycle() {
        if (phase == LifecyclePhase.STARTING || phase == LifecyclePhase.RUNNING
                || phase == LifecyclePhase.STOPPING) {
            phase = LifecyclePhase.STOPPED;
        }
        registry.removeOwnerScope(pluginId, RegistrationScope.START_CYCLE);
        storageFilters.removeOwnerScope(pluginId, RegistrationScope.START_CYCLE);
    }

    synchronized void deactivate() {
        phase = LifecyclePhase.INACTIVE;
    }

    private synchronized PluginContextFacades.ResourceScope callbackResourceScope() {
        return switch (phase) {
            case INITIALIZING, READY -> throw new IllegalStateException(
                    "Plugin context for '" + pluginId
                            + "' accepts event subscriptions and scheduled tasks only "
                            + "during start/running; recreate them on every start cycle");
            case STARTING, RUNNING -> PluginContextFacades.ResourceScope.START_CYCLE;
            case STOPPING, STOPPED, INACTIVE -> throw new IllegalStateException(
                    "Plugin context for '" + pluginId
                            + "' does not accept callback resources in phase " + phase);
        };
    }

    private RegistrationScope serviceRegistrationScope() {
        return switch (phase) {
            case INITIALIZING -> RegistrationScope.INITIALIZATION;
            case STARTING, RUNNING -> RegistrationScope.START_CYCLE;
            case READY, STOPPING, STOPPED, INACTIVE -> throw new IllegalStateException(
                    "Plugin context for '" + pluginId
                            + "' does not accept contributions in phase " + phase);
        };
    }

    private RegistrationScope filterRegistrationScope() {
        return switch (phase) {
            case INITIALIZING -> RegistrationScope.INITIALIZATION;
            case STARTING -> RegistrationScope.START_CYCLE;
            case READY, RUNNING, STOPPING, STOPPED, INACTIVE -> throw new IllegalStateException(
                    "Plugin context for '" + pluginId
                            + "' does not accept storage filters in phase " + phase);
        };
    }

    private void requirePhase(LifecyclePhase expected, String action) {
        if (phase != expected) {
            throw new IllegalStateException("Cannot " + action + " for plugin '" + pluginId
                    + "' from context phase " + phase);
        }
    }

    /** One registry is shared deliberately by every context owned by a manager. */
    static final class SharedServiceRegistry {
        private final Map<String, RegisteredService> services = new ConcurrentHashMap<>();

        void register(String owner, String key, Object service, RegistrationScope scope) {
            Objects.requireNonNull(service, "service");
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Plugin service key must not be blank");
            }
            RegisteredService candidate = new RegisteredService(owner, service, scope);
            RegisteredService existing = services.putIfAbsent(key, candidate);
            if (existing != null) {
                throw new IllegalStateException("Plugin service '" + key + "' from '" + owner
                        + "' conflicts with the service already registered by '"
                        + existing.owner() + "'");
            }
        }

        <T> Optional<T> get(String key, Class<T> type) {
            Objects.requireNonNull(type, "type");
            RegisteredService registered = services.get(key);
            if (registered == null || !type.isInstance(registered.service())) {
                return Optional.empty();
            }
            return Optional.of(type.cast(registered.service()));
        }

        void clear() {
            services.clear();
        }

        void removeOwner(String owner) {
            services.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        }

        void removeOwnerScope(String owner, RegistrationScope scope) {
            services.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner)
                    && entry.getValue().scope() == scope);
        }

        private record RegisteredService(String owner, Object service, RegistrationScope scope) {
        }
    }

    /** Storage filters use the same ownership and rollback rules as services. */
    static final class SharedStorageFilterRegistry {
        private final List<RegisteredFilter> filters = new CopyOnWriteArrayList<>();

        void register(String owner, StorageFilter filter, RegistrationScope scope) {
            filters.add(new RegisteredFilter(
                    owner, Objects.requireNonNull(filter, "filter"), scope));
        }

        List<StorageFilter> snapshot() {
            return filters.stream().map(RegisteredFilter::filter).toList();
        }

        void removeOwner(String owner) {
            filters.removeIf(entry -> entry.owner().equals(owner));
        }

        void removeOwnerScope(String owner, RegistrationScope scope) {
            filters.removeIf(entry -> entry.owner().equals(owner) && entry.scope() == scope);
        }

        void clear() {
            filters.clear();
        }

        private record RegisteredFilter(String owner, StorageFilter filter,
                                        RegistrationScope scope) {
        }
    }

    private enum LifecyclePhase {
        INITIALIZING,
        READY,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        INACTIVE
    }

    private enum RegistrationScope {
        INITIALIZATION,
        START_CYCLE
    }
}
