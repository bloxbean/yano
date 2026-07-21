package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.BlockReceivedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.events.SyncStatusChangedEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * Built-in plugin that logs blockchain events for debugging and monitoring.
 * 
 * This plugin demonstrates the event-driven plugin architecture by subscribing
 * to all major blockchain events and logging them with consistent formatting.
 * It uses the annotation-based listener registration for clean, declarative code.
 * 
 * Features:
 * - Logs all block received/applied events with chain coordinates
 * - Tracks sync status changes (initial sync, live, catching up)
 * - Records rollback events with classification (real vs expected)
 * - Can be enabled/disabled through plugin configuration
 * 
 * Configuration:
 * - Context key: plugins.logging.enabled=true|false
 * - The packaged Quarkus node maps yaci.plugins.logging.enabled to that key.
 * - Library embedders set the context key in PluginsOptions.config().
 * 
 * Log format:
 * All events are prefixed with [EVT] for easy filtering in log aggregators.
 * 
 * Example usage:
 * This plugin serves as a reference implementation for custom plugins.
 * Copy this pattern to create plugins that:
 * - Index blocks to databases
 * - Send notifications on specific events
 * - Collect metrics and statistics
 * - Implement custom validation logic
 * 
 * @see DomainEventListener for annotation-based event handling
 * @see AnnotationListenerRegistrar for automatic registration
 */
public final class LoggingPlugin implements NodePlugin {
    private Logger log;
    private EventBus eventBus;
    private boolean enabled;
    private List<SubscriptionHandle> handles = List.of();

    @Override public String id() { return "com.bloxbean.cardano.yaci.plugins.logging"; }
    @Override public String version() { return "1.0.0"; }
    @Override public Set<PluginCapability> capabilities() { return Set.of(PluginCapability.EVENT_CONSUMER); }

    @Override
    public void init(PluginContext ctx) {
        this.log = ctx.logger();
        this.eventBus = ctx.eventBus();
        Object val = ctx.config() != null ? ctx.config().get("plugins.logging.enabled") : null;
        enabled = val instanceof Boolean b ? b
                : val instanceof String s && Boolean.parseBoolean(s);
        if (!enabled) {
            log.info("LoggingPlugin disabled via yaci.plugins.logging.enabled=false");
        }
    }

    @Override
    public synchronized void start() {
        if (!enabled || !handles.isEmpty()) {
            return;
        }
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        handles = AnnotationListenerRegistrar.register(eventBus, this, defaults);
        log.info("LoggingPlugin started; registered {} listeners", handles.size());
    }

    @Override
    public synchronized void stop() {
        handles.forEach(handle -> {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
        });
        handles = List.of();
    }

    @Override public void close() { stop(); }

    @DomainEventListener(order = 0)
    public void onBlockReceived(BlockReceivedEvent e) {
        log.info("[EVT] BlockReceived era={} slot={} no={} hash={}", e.era(), e.slot(), e.blockNumber(), e.blockHash());
    }

    @DomainEventListener(order = 1)
    public void onBlockApplied(BlockAppliedEvent e) {
        log.info("[EVT] BlockApplied era={} slot={} no={} hash={}", e.era(), e.slot(), e.blockNumber(), e.blockHash());
    }

    @DomainEventListener(order = 2)
    public void onRollback(RollbackEvent e) {
        log.info("[EVT] Rollback target={} realReorg={}", e.target(), e.realReorg());
    }

    @DomainEventListener(order = 3)
    public void onSyncStatus(SyncStatusChangedEvent e) {
        log.info("[EVT] SyncStatus {} -> {}", e.previous(), e.current());
    }
}
