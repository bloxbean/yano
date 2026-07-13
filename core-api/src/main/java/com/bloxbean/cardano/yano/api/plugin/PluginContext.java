package com.bloxbean.cardano.yano.api.plugin;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Runtime context provided to plugins during initialization.
 * 
 * PluginContext gives plugins access to core node services and facilities
 * without exposing internal implementation details. This abstraction layer
 * ensures plugins remain decoupled from the node runtime internals.
 * 
 * Available services:
 * - Event bus for publish/subscribe communication
 * - Logger for plugin-specific logging
 * - Configuration map for plugin settings
 * - Scheduler for background tasks
 * - Service registry for inter-plugin communication
 * 
 * Thread safety: All methods are thread-safe and can be called concurrently.
 * 
 * @see NodePlugin#init(PluginContext)
 */
public interface PluginContext {
    /**
     * Get the event bus for publishing and subscribing to events.
     * 
     * Plugins should use this to:
     * - Listen for blockchain events (blocks, transactions, rollbacks)
     * - Publish custom events for other plugins
     * - Implement reactive processing chains
     * 
     * @return The configured event bus instance
     */
    EventBus eventBus();
    
    /**
     * Get a logger configured for this plugin.
     * 
     * The logger will be named after the plugin ID for easy filtering
     * and debugging in production environments.
     * 
     * @return Logger instance for this plugin
     */
    Logger logger();
    
    /**
     * Get the immutable plugin compatibility configuration map.
     *
     * <p>The current runtime supplies one shared global map to every plugin;
     * callers must use namespaced keys. Prefix-stripped, per-plugin views are
     * deferred to the manifested bundle catalog.</p>
     *
     * @return Immutable shared configuration map
     */
    Map<String, Object> config();
    
    /**
     * Get a shared scheduler for background tasks.
     * 
     * Plugins should use this scheduler instead of creating their own
     * thread pools to avoid resource exhaustion. The scheduler is
     * configured with appropriate pool size for the deployment.
     * 
     * @return Shared scheduled executor service
     */
    ScheduledExecutorService scheduler();
    
    /**
     * Get the classloader used to load this plugin.
     * 
     * Useful for plugins that need to load resources or classes
     * dynamically. May be empty if default classloader is used.
     * 
     * @return Optional plugin classloader
     */
    Optional<ClassLoader> pluginClassLoader();

    /**
     * Register a service for other plugins to use.
     * 
     * Services enable inter-plugin communication without tight coupling.
     * Common use cases:
     * - Storage adapters registering data access services
     * - Notification plugins providing alert mechanisms
     * - Analytics plugins exposing metrics
     *
     * <p>A service registered synchronously during
     * {@link NodePlugin#init(PluginContext)}
     * remains until close. A service registered during a start cycle is
     * removed after that plugin's stop callback and may be registered again on
     * restart. New registrations are rejected between start cycles and after
     * close.</p>
     * 
     * @param key Unique service identifier
     * @param service The service instance to register
     * @throws IllegalStateException if any plugin already owns the key
     */
    void registerService(String key, Object service);
    
    /**
     * Register a storage filter for controlling what data gets persisted.
     * <p>
     * Filters are composed into a chain — all filters must accept for an
     * output to be stored. Multiple filters can be registered; they are
     * applied in {@link StorageFilter#priority()} order.
     *
     * <p>Filters must be registered synchronously from
     * {@link NodePlugin#init(PluginContext)} or {@link NodePlugin#start()}
     * before that callback returns. Init-scoped filters remain until close;
     * start-scoped filters are removed after stop and may be registered again
     * on restart.</p>
     *
     * @param filter the storage filter to register
     */
    void registerStorageFilter(StorageFilter filter);

    /**
     * Get a service registered by another plugin.
     * 
     * Services are looked up by key and cast to the requested type.
     * Returns empty if service not found or type mismatch.
     * A service needed during initialization should come from a plugin named
     * in the consumer's {@link NodePlugin#dependsOn()} set so dependency-first
     * initialization guarantees that it is available.
     * 
     * @param <T> Expected service type
     * @param key Service identifier
     * @param type Expected service class
     * @return Optional service instance
     */
    <T> Optional<T> getService(String key, Class<T> type);
}
