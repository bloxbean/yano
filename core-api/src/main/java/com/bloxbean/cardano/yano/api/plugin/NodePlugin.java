package com.bloxbean.cardano.yano.api.plugin;

import java.util.Set;

/**
 * Base interface for Yano plugins.
 * 
 * Plugins extend the functionality of Yano by:
 * - Listening to blockchain events (blocks, transactions, rollbacks)
 * - Providing custom storage implementations
 * - Adding notification mechanisms
 * - Implementing policy decisions
 * 
 * Plugin lifecycle:
 * 1. Discovery - The runtime catalog correlates the bundle manifest with its
 *    ServiceLoader registration before constructing the provider
 * 2. init() - Receive context and register stable services/contributions
 * 3. start() - Register restartable listeners and begin active processing
 * 4. stop() - Graceful shutdown of processing
 * 5. close() - Release all resources
 * 
 * Plugin discovery:
 * - JVM directory deployment: package a self-contained JAR with
 *   META-INF/yano/plugins/&lt;bundle-id&gt;.json and the NodePlugin
 *   META-INF/services entry, then place it in the configured plugin directory
 * - Packaged JVM/native deployment: include the manifested bundle at build
 *   time so catalog-index and native reflection metadata are generated
 * - Unmanifested ServiceLoader providers are a temporary compatibility path
 *   for JVM directory and explicit library-compatibility modes only
 * 
 * Best practices:
 * - Use unique reverse-DNS naming for plugin IDs (e.g., "com.example.myplugin")
 * - Handle exceptions gracefully to avoid affecting node stability
 * - Clean up resources properly in stop() and close()
 * - Use provided ScheduledExecutorService for background tasks
 * - Respect dependency ordering via dependsOn()
 * 
 * @see PluginContext for available services
 * @see PluginCapability for declaring plugin features
 */
public interface NodePlugin extends AutoCloseable {
    /**
     * Unique identifier for this plugin.
     * Should use reverse-DNS naming convention (e.g., "com.example.analytics").
     * 
     * @return Plugin identifier
     */
    String id();
    
    /**
     * Version of this plugin.
     * Recommended to use semantic versioning (e.g., "1.0.0").
     * 
     * @return Plugin version string
     */
    String version();

    /**
     * Declare dependencies on other plugins.
     * The plugin manager validates the complete selected graph before any
     * lifecycle callback and initializes dependencies first. A missing
     * selected dependency or dependency cycle prevents startup.
     * 
     * @return Set of plugin IDs this plugin depends on
     */
    default Set<String> dependsOn() { return Set.of(); }
    
    /**
     * Declare the capabilities this plugin provides.
     * Used for documentation and future capability-based filtering.
     * 
     * @return Set of capabilities this plugin provides
     */
    default Set<PluginCapability> capabilities() { return Set.of(PluginCapability.EVENT_CONSUMER); }

    /**
     * Initialize the plugin with runtime context.
     * Called once after discovery, operator policy and dependency validation
     * have succeeded for the complete selected plugin set.
     * Use this to:
     * - Access configuration
     * - Register services for other plugins
     * - Register stable contributions such as storage filters
     * 
     * @param ctx Plugin context providing access to event bus, config, etc.
     */
    void init(PluginContext ctx);
    
    /**
     * Start active plugin processing.
     * Called after all plugins are initialized.
     * Use this to register event listeners and begin background tasks or
     * active monitoring. Services and storage filters registered synchronously
     * in this callback are scoped to the current start cycle and removed after
     * {@link #stop()}; they may be registered again on the next start. Work
     * removed by {@code stop()} must be restored by a later call to this method
     * because a clean runtime stop may be followed by another start on the
     * same plugin instance. Event subscriptions and tasks submitted through
     * {@link PluginContext} are generation-scoped: creating them during
     * {@link #init(PluginContext)} or between start cycles is rejected, stop
     * cancels them, and this method must recreate them after restart.
     */
    void start();
    
    /**
     * Stop active plugin processing.
     * Called during graceful shutdown.
     * Should remove event listeners, stop background tasks and leave the
     * initialized plugin ready for either a later start or close.
     */
    void stop();

    /**
     * Release all plugin resources.
     * Called as final cleanup step.
     * Must be idempotent (safe to call multiple times).
     */
    @Override
    void close();
}
