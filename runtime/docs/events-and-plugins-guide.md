# Yano Events & Plugins Guide

This guide explains how to use the Yano event system and plugin SPI, both for developers embedding Yano in their apps and for contributors writing default plugins or internal listeners.

## Overview

- Module overview
  - `events-core`: event SPI (`Event`, `EventBus`, `EventListener`, `EventContext`, `EventMetadata`, `SubscriptionOptions`, `PublishOptions`, `@DomainEventListener`), `SimpleEventBus`, `NoopEventBus`, registrar, `support.DomainEventBindings` SPI.
  - `events-processor`: JSR 269 annotation processor generating build-time bindings for `@DomainEventListener` (GraalVM friendly).
  - `core-api`: plugin SPI (`NodePlugin`, `PluginContext`, `PluginCapability`, `Notifier`, `Notification`, `StorageAdapter`, `NodePolicy`), config (`RuntimeOptions`, `PluginsOptions`).
  - `runtime`: wiring, publication points, example `LoggingPlugin` (ServiceLoader-based).

- Event taxonomy (initial)
  - Data plane: `BlockReceivedEvent`, `BlockAppliedEvent`, `MemPoolTransactionReceivedEvent`, `RollbackEvent`.
  - Control plane: `SyncStatusChangedEvent`, `TipChangedEvent`.

- Delivery and semantics
  - At-least-once, per-type ordering. Async offload when `@DomainEventListener(async=true)` (uses default virtual-thread executor unless an executor is provided via defaults).
  - Backpressure via bounded queues and overflow strategies.

## Architecture Diagram

```text
                    +-------------------+                 +-------------------------+
                    |   RuntimeOptions  |                 |     PluginsOptions      |
                    |  (events, plugins)|                 | (policy + config map)   |
                    +---------+---------+                 +------------+------------+
                              |                                         |
                              v                                         v
                      +-------+-------------------------------+   +-----+--------------------+
                      |             Yano                  |   |       PluginManager     |
                      |  - selects EventBus (Simple/Noop)     |   |  - ServiceLoader        |
                      |  - constructs PluginManager           |   |  - validate/order first |
                      +-------+---------------+---------------+   +-----+-----------+-------+
                              |               |                               |
                              |               |                               |
   Publications (runtime)|               | PluginContext                 |  NodePlugin.init(ctx)
   ---------------------------+               |  - eventBus                   +---------------------+
         BlockReceivedEvent   |               |  - logger                     |   NodePlugin.start()|
         BlockAppliedEvent    |               |  - config (namespaced map)    |   (optional)
         RollbackEvent        |               |  - scheduler                  |
         SyncStatusChanged    |               |  - plugin classloader         |
         TipChangedEvent      v               v                               v
                      +-------+---------------+---------------+       +-------+---------------+
                      |               EventBus               |       |   AnnotationListener |
                      |     (SimpleEventBus / NoopEventBus)  |       |       Registrar       |
                      +-------+---------------+---------------+       +-----+-----------------+
                              |               ^                         ^
                              |               |                         |
                              v               |                         |
                    +---------+---------------+---------+               |
                    |   Generated Bindings (SPI)         |<-------------+
                    |  DomainEventBindings via            |   Fallback: reflectively scans
                    |  ServiceLoader (events-processor)   |   @DomainEventListener methods
                    +-------------------------------------+

Flow summary:
- Yano takes RuntimeOptions to build/select EventBus and initialize PluginManager.
- PluginManager discovers `NodePlugin` via ServiceLoader, snapshots metadata,
  applies policy, validates the dependency graph, and only then calls
  `init(ctx)` / `start()` in deterministic dependency order.
- Plugins register listeners using AnnotationListenerRegistrar:
  - If build-time bindings exist (events-processor), registrar uses ServiceLoader to find DomainEventBindings and registers without reflection.
  - Otherwise registrar reflects over @DomainEventListener methods and subscribes them.
- runtime publishes events (BlockReceived/Applied, Rollback, SyncStatusChanged, TipChanged) to EventBus; subscribers receive them.
```

## Configuring Events and Plugins

Use explicit options (records) — do not rely on `System.setProperty`.

- `EventsOptions` (events-core)
  - `enabled` (boolean): enable/disable events (uses `SimpleEventBus` vs `NoopEventBus`).
  - `bufferSize` (int): queue capacity for async offload.
  - `overflow` (enum): `BLOCK | DROP_LATEST | DROP_OLDEST | ERROR`.

- `PluginsOptions` (core-api)
  - `enabled` (boolean): enable/disable plugin system.
  - `autoRegisterAnnotated` (boolean): reserved and currently inactive; plugins
    should own their listener handles explicitly.
  - `allowList`/`denyList` (Set<String>): exact, case-sensitive controls. Empty
    allow means all discovered plugins; deny wins. A missing allow-listed id,
    duplicate id, missing selected dependency, or cycle fails startup before
    any plugin initializes.
  - `config` (Map<String,Object>): namespaced plugin settings (e.g., `plugins.logging.enabled`).

- `RuntimeOptions` (core-api)
  - Wraps `EventsOptions`, `PluginsOptions`, and a `globals` map.

### Embedding in plain Java

```java
import com.bloxbean.cardano.yano.api.config.*;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;

EventsOptions ev = new EventsOptions(true, 8192, SubscriptionOptions.Overflow.BLOCK);
PluginsOptions pl = new PluginsOptions(true, false, java.util.Set.of(), java.util.Set.of(), java.util.Map.of());
RuntimeOptions rt = new RuntimeOptions(ev, pl, java.util.Map.of());

Yano node = YanoAssembly.fromConfig(myNodeConfig)
        .runtimeOptions(rt)
        .build();
node.lifecycle().start();
```

### Quarkus app

- `app` maps `application.yml` → options in `YanoProducer`, then builds a `Yano`:
  - `yaci.events.enabled`, `yaci.plugins.enabled`,
    `yaci.plugins.allow-list`, `yaci.plugins.deny-list`,
    `yaci.plugins.auto-register-annotated`, and
    `yaci.plugins.logging.enabled`.

## Writing a Plugin (ServiceLoader)

1) Implement `NodePlugin` in your module:

```java
public final class MyPlugin implements NodePlugin {
  private List<SubscriptionHandle> handles = java.util.List.of();
  private Logger log;
  private EventBus eventBus;

  @Override public String id() { return "com.example.myplugin"; }
  @Override public String version() { return "1.0.0"; }
  @Override public Set<String> dependsOn() { return Set.of(); }

  @Override public void init(PluginContext ctx) {
    this.log = ctx.logger();
    this.eventBus = ctx.eventBus();
  }

  @Override public synchronized void start() {
    if (!handles.isEmpty()) return;
    // Register annotated methods (build-time bindings if processor is on the classpath)
    SubscriptionOptions defaults = SubscriptionOptions.builder().build();
    this.handles = com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar
        .register(eventBus, this, defaults);
  }

  @Override public synchronized void stop() {
    handles.forEach(h -> { try { h.close(); } catch (Exception ignored) {} });
    handles = java.util.List.of();
  }
  @Override public void close() { stop(); }
}
```

All selected plugins are currently required. Initialization or startup failure
rolls back the selected set in reverse dependency order and fails node startup.
`stop()` and `close()` also run in reverse order. Optional/degraded trust tiers
belong to the later manifested bundle catalog.

Every context sees the same service registry. A dependency may register a
service during `init()` and its dependent may retrieve it during its own
`init()`; duplicate service keys are rejected rather than overwritten. The
configuration map remains the existing global compatibility map in this
release, so plugins must continue to use namespaced keys. Prefix-stripped
per-bundle configuration is deferred to the manifested bundle work.

Services and storage filters registered during `init()` are stable until
close. Contributions registered synchronously during `start()` belong to that
start cycle: the manager removes them after the owner's `stop()` callback so
the same keys and filters can be registered once on restart. Storage filters
must be registered before `start()` returns because the runtime freezes the
filter snapshot immediately after all plugins start. New contributions are
rejected between cycles and after close.

2) Add ServiceLoader descriptor in your resources:
- `META-INF/services/com.bloxbean.cardano.yano.api.plugin.NodePlugin`:
```
com.example.MyPlugin
```

### Using `@DomainEventListener`

You can annotate methods on your plugin (or any object you register) to receive events:

```java
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yano.api.events.*;

public final class MyPlugin implements NodePlugin {
  @DomainEventListener(order = 0)
  public void onBlockApplied(BlockAppliedEvent e) {
    // process e.block(), e.blockNumber(), e.slot(), e.blockHash()
  }

  @DomainEventListener(order = 1)
  public void onRollback(com.bloxbean.cardano.yaci.events.api.EventContext<RollbackEvent> ctx) {
    var meta = ctx.metadata(); // timestamp/origin/chain position
    var evt = ctx.event();
  }
}
```

- Supported method signatures:
  - `void on(T event)`
  - `void on(EventContext<T> ctx)`
- Attributes:
  - `order` (int): global per-type priority (lower runs earlier).
  - `async` (boolean): when true, the listener runs off the publisher thread. If a `SubscriptionOptions.executor` is provided via defaults, it is used; otherwise a default virtual-thread executor is used.
  - `filter` (String): optional at subscription-time via `SubscriptionOptions.filter`.

### Build-time bindings (GraalVM-ready)

Enable the annotation processor to avoid runtime reflection and support native images:

- Gradle:
```gradle
dependencies {
  annotationProcessor project(":events-processor")
  // or, when published:
  // annotationProcessor "com.bloxbean.cardano:yaci-events-processor:<version>"
}
```

The processor generates `<YourClass>_EventBindings` and a service entry under:
- `META-INF/services/com.bloxbean.cardano.yaci.events.api.support.DomainEventBindings`

At runtime, the registrar uses ServiceLoader to find generated bindings first; reflection is used only as a fallback.

### Async Execution Semantics

- Annotation-driven:
  - `@DomainEventListener(async = true)` offloads execution from the publisher thread.
  - Executor selection order: use `SubscriptionOptions.executor` from registrar defaults if provided; otherwise a shared virtual-thread executor is used.
  - `async = false` runs on the publisher thread.

- Manual subscriptions:
  - If you pass a non-null `executor` in `SubscriptionOptions`, the listener runs asynchronously; otherwise it runs synchronously.

Notes:
- Priority ordering is applied at dispatch; async listeners still start in priority order but may complete out of order after offload.
- Default offload uses Java 21 virtual threads.

## Event Publication Points (runtime)

- `BodyFetchManager`:
  - `BlockReceivedEvent` (pre-store)
  - `BlockAppliedEvent` (post-store)
  - `TipChangedEvent` (when tip advances)
- `Yano`:
  - `NodeStartedEvent` (startup)
  - `MemPoolTransactionReceivedEvent` (when a transaction is added to mempool)
  - `RollbackEvent` (on rollback)
  - `SyncStatusChangedEvent` (phase transitions)

All publications include `EventMetadata` with origin and chain coordinates where applicable.

## Manual Subscription (without annotations)

```java
EventBus bus = /* from PluginContext or your wiring */;
SubscriptionOptions opts = SubscriptionOptions.builder()
    .executor(java.util.concurrent.Executors.newSingleThreadExecutor()) // async offload regardless of annotation
    .bufferSize(8192)
    .overflow(SubscriptionOptions.Overflow.BLOCK)
    .build();

SubscriptionHandle h = bus.subscribe(
    com.bloxbean.cardano.yano.runtime.events.BlockAppliedEvent.class,
    ctx -> { /* handle ctx.event(), ctx.metadata() */ },
    opts
);
```

## Testing Plugins and Listeners

- Unit testing with `SimpleEventBus`:
  - Construct a `SimpleEventBus`, subscribe a test listener, publish events, assert effects.
- With `AnnotationListenerRegistrar`:
  - Register your plugin instance against a test bus, then publish events and verify behavior.
- With generated bindings:
  - Include the `events-processor` as `annotationProcessor` in the test module so ServiceLoader can locate generated binders.

## Tips & Best Practices

- Prefer idempotent listeners; at-least-once delivery may re-deliver on retries.
- Keep handlers fast; use async offload (executor) for long-running work.
- Use `NoopEventBus` (via `EventsOptions.enabled=false`) when events are not needed.
- Use namespaced keys in `PluginContext.config()` (e.g., `plugins.logging.enabled`).
- Start with `SimpleEventBus`; add alternative buses only when you need specialized behavior.

## FAQ

- Q: Do I need the annotation processor?
  - A: It’s recommended for GraalVM/native-image and faster startup. Without it, the registrar falls back to reflection.

- Q: Can I use `@DomainEventListener` outside plugins?
  - A: Yes. Pass any instance to `AnnotationListenerRegistrar.register(eventBus, instance, defaults)`.

- Q: How do I disable events?
  - A: Set `EventsOptions.enabled=false` (uses `NoopEventBus`).

- Q: How do I disable plugins?
  - A: Set `PluginsOptions.enabled=false` in `RuntimeOptions`.

## References

- SPI: `events-core` and `core-api` modules.
- Processor: `events-processor` module.
- Example: `runtime` `LoggingPlugin` and event publications.
