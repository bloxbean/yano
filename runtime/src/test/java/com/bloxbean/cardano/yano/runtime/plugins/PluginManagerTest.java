package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginManagerTest {

    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        schedulers.forEach(ScheduledExecutorService::shutdownNow);
    }

    @Test
    void dependencyOrder_sharedServices_reverseLifecycle_andRestartAreDeterministic() {
        List<String> events = new ArrayList<>();
        RecordingPlugin a = plugin("a", events);
        StorageFilter filter = new StorageFilter() { };
        a.onInit = context -> {
            context.registerService("answer", 42);
            context.registerStorageFilter(filter);
        };
        RecordingPlugin b = plugin("b", events, "a");
        b.onInit = context -> {
            assertThat(context.getService("answer", Integer.class)).contains(42);
            assertThat(context.getService("answer", String.class)).isEmpty();
            assertThat(context.getService("missing", Integer.class)).isEmpty();
        };
        b.onClose = () -> assertThat(b.context.getService("answer", Integer.class))
                .contains(42);
        RecordingPlugin c = plugin("c", events, "b");

        PluginManager manager = manager(options(), List.of(c, b, a));
        manager.discoverAndInit();
        manager.discoverAndInit();

        assertThat(events).containsExactly("init:a", "init:b", "init:c");
        assertThat(manager.getStorageFilters()).containsExactly(filter);

        manager.startAll();
        manager.startAll();
        manager.stopAll();
        assertThat(manager.getStorageFilters()).containsExactly(filter);
        manager.stopAll();
        manager.startAll();
        manager.stopAll();
        manager.close();
        manager.close();

        assertThat(events).containsExactly(
                "init:a", "init:b", "init:c",
                "start:a", "start:b", "start:c",
                "stop:c", "stop:b", "stop:a",
                "start:a", "start:b", "start:c",
                "stop:c", "stop:b", "stop:a",
                "close:c", "close:b", "close:a");
        assertThat(manager.getStorageFilters()).isEmpty();
        for (RecordingPlugin plugin : List.of(a, b, c)) {
            assertThat(plugin.idCalls).isOne();
            assertThat(plugin.versionCalls).isOne();
            assertThat(plugin.dependenciesCalls).isOne();
            assertThat(plugin.capabilitiesCalls).isOne();
        }
        assertThatThrownBy(manager::startAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void startCycleContributionsAreRemovedOnStopAndCanBeRegisteredOnRestart() {
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("cycle", events);
        StorageFilter cycleFilter = new StorageFilter() { };
        AtomicInteger cycle = new AtomicInteger();
        plugin.onStart = () -> {
            int current = cycle.incrementAndGet();
            plugin.context.registerService("cycle-service", "value-" + current);
            plugin.context.registerStorageFilter(cycleFilter);
        };

        PluginManager manager = manager(options(), List.of(plugin));
        manager.discoverAndInit();

        manager.startAll();
        assertThat(plugin.context.getService("cycle-service", String.class))
                .contains("value-1");
        assertThat(manager.getStorageFilters()).containsExactly(cycleFilter);

        manager.stopAll();
        assertThat(plugin.context.getService("cycle-service", String.class)).isEmpty();
        assertThat(manager.getStorageFilters()).isEmpty();
        assertThatThrownBy(() -> plugin.context.registerService("between-cycles", "value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phase STOPPED");

        manager.startAll();
        assertThat(plugin.context.getService("cycle-service", String.class))
                .contains("value-2");
        assertThat(manager.getStorageFilters()).containsExactly(cycleFilter);

        manager.close();
        assertThat(manager.getStorageFilters()).isEmpty();
    }

    @Test
    void independentPluginsUseLexicalTieBreakRegardlessOfDiscoveryOrder() {
        List<String> events = new ArrayList<>();
        PluginManager manager = manager(options(), List.of(
                plugin("zeta", events),
                plugin("beta", events),
                plugin("alpha", events),
                plugin("mu", events)));

        manager.discoverAndInit();
        manager.startAll();
        manager.close();

        assertThat(events).containsExactly(
                "init:alpha", "init:beta", "init:mu", "init:zeta",
                "start:alpha", "start:beta", "start:mu", "start:zeta",
                "stop:zeta", "stop:mu", "stop:beta", "stop:alpha",
                "close:zeta", "close:mu", "close:beta", "close:alpha");
    }

    @Test
    void legacyMapConstructorDiscoversBuiltInPlugin_andRestartRestoresItsListeners() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        TrackingEventBus eventBus = new TrackingEventBus();
        PluginManager manager = new PluginManager(
                eventBus,
                scheduler,
                Map.of("plugins.logging.enabled", true),
                LoggingPlugin.class.getClassLoader());

        manager.discoverAndInit();
        manager.startAll();
        int builtInListenerCount = eventBus.activeSubscriptionCount();
        assertThat(builtInListenerCount).isGreaterThanOrEqualTo(4);

        manager.stopAll();
        assertThat(eventBus.activeSubscriptionCount()).isZero();

        manager.startAll();
        assertThat(eventBus.activeSubscriptionCount()).isEqualTo(builtInListenerCount);

        manager.close();
        assertThat(eventBus.activeSubscriptionCount()).isZero();
    }

    @Test
    void duplicateMissingDependencyAndCycleFailBeforeInitialization() {
        List<String> duplicateEvents = new ArrayList<>();
        PluginManager duplicate = manager(options(), List.of(
                plugin("same", duplicateEvents), plugin("same", duplicateEvents)));
        assertThatThrownBy(duplicate::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.VALIDATION);
        assertThat(duplicateEvents).isEmpty();

        List<String> missingEvents = new ArrayList<>();
        PluginManager missing = manager(options(), List.of(
                plugin("dependent", missingEvents, "absent")));
        assertThatThrownBy(missing::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageContaining("dependent")
                .hasMessageContaining("absent");
        assertThat(missingEvents).isEmpty();

        List<String> cycleEvents = new ArrayList<>();
        PluginManager cycle = manager(options(), List.of(
                plugin("b", cycleEvents, "a"), plugin("a", cycleEvents, "b")));
        assertThatThrownBy(cycle::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("[a, b, a]");
        assertThat(cycleEvents).isEmpty();
    }

    @Test
    void policyUsesDenyOverAllow_andDoesNotAutoEnableDependencies() {
        List<String> events = new ArrayList<>();
        RecordingPlugin a = plugin("a", events);
        RecordingPlugin b = plugin("b", events);
        RecordingPlugin c = plugin("c", events);
        PluginManager manager = manager(
                new PluginsOptions(true, false, Set.of("a", "b", "denied-missing"),
                        Set.of("b", "denied-missing"), Map.of()),
                List.of(c, b, a));

        manager.discoverAndInit();
        manager.startAll();
        manager.close();

        assertThat(events).containsExactly("init:a", "start:a", "stop:a", "close:a");

        List<String> unknownEvents = new ArrayList<>();
        PluginManager unknown = manager(
                new PluginsOptions(true, false, Set.of("not-installed"), Set.of(), Map.of()),
                List.of(plugin("a", unknownEvents)));
        assertThatThrownBy(unknown::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.POLICY);
        assertThat(unknownEvents).isEmpty();

        List<String> filteredDependencyEvents = new ArrayList<>();
        PluginManager filteredDependency = manager(
                new PluginsOptions(true, false, Set.of(), Set.of("dependency"), Map.of()),
                List.of(plugin("dependency", filteredDependencyEvents),
                        plugin("consumer", filteredDependencyEvents, "dependency")));
        assertThatThrownBy(filteredDependency::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageContaining("consumer")
                .hasMessageContaining("dependency");
        assertThat(filteredDependencyEvents).isEmpty();
    }

    @Test
    void disabledPolicySkipsMetadataAndEveryLifecycleCallback() {
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("a", events);
        PluginManager manager = manager(
                new PluginsOptions(false, false, Set.of("missing"), Set.of(), Map.of()),
                List.of(plugin));

        manager.discoverAndInit();
        manager.startAll();
        manager.stopAll();
        manager.close();

        assertThat(events).isEmpty();
        assertThat(plugin.idCalls).isZero();
        assertThat(plugin.versionCalls).isZero();
        assertThat(plugin.dependenciesCalls).isZero();
        assertThat(plugin.capabilitiesCalls).isZero();
    }

    @Test
    void duplicateServiceFailsInitialization_andRollsBackInReverseOrder() {
        List<String> events = new ArrayList<>();
        RecordingPlugin a = plugin("a", events);
        a.onInit = context -> context.registerService("shared", "a");
        RecordingPlugin b = plugin("b", events, "a");
        b.onInit = context -> context.registerService("shared", "b");

        PluginManager manager = manager(options(), List.of(b, a));
        Throwable failure = catchThrowable(manager::discoverAndInit);
        assertThat(failure)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(failure.getCause())
                .hasMessageContaining("shared")
                .hasMessageContaining("a")
                .hasMessageContaining("b");

        assertThat(events).containsExactly("init:a", "init:b", "close:b", "close:a");
        assertThat(manager.getStorageFilters()).isEmpty();
        manager.close();
        assertThatThrownBy(manager::discoverAndInit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void startFailureStopsPartialPluginAndStartedDependencies_thenClosesAll() {
        List<String> events = new ArrayList<>();
        RecordingPlugin a = plugin("a", events);
        RecordingPlugin b = plugin("b", events, "a");
        b.failStart = true;
        RecordingPlugin c = plugin("c", events, "b");
        PluginManager manager = manager(options(), List.of(c, b, a));

        manager.discoverAndInit();
        assertThatThrownBy(manager::startAll)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.START);

        assertThat(events).containsExactly(
                "init:a", "init:b", "init:c",
                "start:a", "start:b",
                "stop:b", "stop:a",
                "close:c", "close:b", "close:a");
        manager.close();
    }

    @Test
    void closeContinuesAfterPluginCleanupFailure() {
        List<String> events = new ArrayList<>();
        RecordingPlugin a = plugin("a", events);
        RecordingPlugin b = plugin("b", events, "a");
        b.failStop = true;
        b.failClose = true;
        PluginManager manager = manager(options(), List.of(b, a));

        manager.discoverAndInit();
        manager.startAll();
        Throwable failure = catchThrowable(manager::close);

        assertThat(events).containsExactly(
                "init:a", "init:b", "start:a", "start:b",
                "stop:b", "stop:a", "close:b", "close:a");
        assertThat(failure)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .extracting(problem -> ((PluginManager.PluginManagerException) problem).phase())
                .isEqualTo(PluginManager.FailurePhase.STOP);
        assertThat(failure.getSuppressed()).hasSize(1);
        manager.close();
    }

    @Test
    void stopFailureIsAggregated_terminal_andDoesNotSkipRemainingCleanup() {
        List<String> events = new ArrayList<>();
        RecordingPlugin a = plugin("a", events);
        a.failStop = true;
        RecordingPlugin b = plugin("b", events, "a");
        b.failStop = true;
        PluginManager manager = manager(options(), List.of(b, a));

        manager.discoverAndInit();
        manager.startAll();

        Throwable failure = catchThrowable(manager::stopAll);
        assertThat(failure)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .extracting(problem -> ((PluginManager.PluginManagerException) problem).phase())
                .isEqualTo(PluginManager.FailurePhase.STOP);
        assertThat(failure).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(events).containsExactly(
                "init:a", "init:b", "start:a", "start:b", "stop:b", "stop:a");

        assertThatThrownBy(manager::startAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");

        manager.close();
        manager.close();
        assertThat(events).containsExactly(
                "init:a", "init:b", "start:a", "start:b", "stop:b", "stop:a",
                "close:b", "close:a");
    }

    @Test
    void reentrantCloseFromLifecycleCallbackFailsStartup_andCompletesRollback() {
        List<String> events = new ArrayList<>();
        StorageFilter filter = new StorageFilter() { };
        RecordingPlugin a = plugin("a", events);
        a.onInit = context -> {
            context.registerService("owned", "value");
            context.registerStorageFilter(filter);
        };
        RecordingPlugin b = plugin("b", events, "a");
        PluginManager manager = manager(options(), List.of(b, a));
        b.onStart = manager::close;

        manager.discoverAndInit();
        Throwable failure = catchThrowable(manager::startAll);

        assertThat(failure)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasRootCauseMessage("Cannot close plugins during lifecycle callback/state STARTING")
                .extracting(problem -> ((PluginManager.PluginManagerException) problem).phase())
                .isEqualTo(PluginManager.FailurePhase.START);
        assertThat(events).containsExactly(
                "init:a", "init:b", "start:a", "start:b",
                "stop:b", "stop:a", "close:b", "close:a");
        assertThat(manager.getStorageFilters()).isEmpty();
        assertThat(a.context.getService("owned", String.class)).isEmpty();
        assertThatThrownBy(manager::startAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");

        manager.close();
        manager.close();
        assertThatThrownBy(manager::startAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void retainedContextsCannotPublishContributionsAfterCloseOrRollback() {
        List<String> closeEvents = new ArrayList<>();
        StorageFilter closeFilter = new StorageFilter() { };
        RecordingPlugin closedPlugin = plugin("closed", closeEvents);
        closedPlugin.onInit = context -> {
            context.registerService("closed-service", "value");
            context.registerStorageFilter(closeFilter);
        };
        PluginManager closedManager = manager(options(), List.of(closedPlugin));

        closedManager.discoverAndInit();
        assertThat(closedManager.getStorageFilters()).containsExactly(closeFilter);
        closedManager.close();

        assertContextCannotPublish(closedPlugin.context);
        assertThat(closedPlugin.context.getService("closed-service", String.class)).isEmpty();
        assertThat(closedManager.getStorageFilters()).isEmpty();

        List<String> rollbackEvents = new ArrayList<>();
        StorageFilter rollbackFilter = new StorageFilter() { };
        RecordingPlugin initialized = plugin("initialized", rollbackEvents);
        initialized.onInit = context -> {
            context.registerService("rollback-service", "value");
            context.registerStorageFilter(rollbackFilter);
        };
        RecordingPlugin failing = plugin("failing", rollbackEvents, "initialized");
        failing.onInit = ignored -> {
            throw new IllegalStateException("force initialization rollback");
        };
        PluginManager rollbackManager = manager(options(), List.of(failing, initialized));

        assertThatThrownBy(rollbackManager::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .extracting(problem -> ((PluginManager.PluginManagerException) problem).phase())
                .isEqualTo(PluginManager.FailurePhase.INITIALIZATION);

        assertContextCannotPublish(initialized.context);
        assertContextCannotPublish(failing.context);
        assertThat(initialized.context.getService("rollback-service", String.class)).isEmpty();
        assertThat(rollbackManager.getStorageFilters()).isEmpty();
        rollbackManager.close();
    }

    @Test
    void fatalStopAndCloseErrorsAreRethrownOnlyAfterReverseCleanupCompletes() {
        List<String> stopEvents = new ArrayList<>();
        RecordingPlugin stopA = plugin("a", stopEvents);
        RecordingPlugin stopB = plugin("b", stopEvents, "a");
        FatalPluginError fatalStop = new FatalPluginError("fatal stop");
        stopB.stopError = fatalStop;
        RecordingPlugin stopC = plugin("c", stopEvents, "b");
        PluginManager stopManager = manager(options(), List.of(stopC, stopB, stopA));

        stopManager.discoverAndInit();
        stopManager.startAll();
        assertThat(catchThrowable(stopManager::stopAll)).isSameAs(fatalStop);
        assertThat(stopEvents).containsExactly(
                "init:a", "init:b", "init:c",
                "start:a", "start:b", "start:c",
                "stop:c", "stop:b", "stop:a");
        stopManager.close();
        assertThat(stopEvents).endsWith("close:c", "close:b", "close:a");

        List<String> closeEvents = new ArrayList<>();
        RecordingPlugin closeA = plugin("a", closeEvents);
        RecordingPlugin closeB = plugin("b", closeEvents, "a");
        FatalPluginError fatalClose = new FatalPluginError("fatal close");
        closeB.closeError = fatalClose;
        RecordingPlugin closeC = plugin("c", closeEvents, "b");
        PluginManager closeManager = manager(options(), List.of(closeC, closeB, closeA));

        closeManager.discoverAndInit();
        assertThat(catchThrowable(closeManager::close)).isSameAs(fatalClose);
        assertThat(closeEvents).containsExactly(
                "init:a", "init:b", "init:c", "close:c", "close:b", "close:a");
        assertThat(closeManager.getStorageFilters()).isEmpty();
        closeManager.close();
    }

    @Test
    void optionsAndContextConfigurationAreDefensiveSnapshots() {
        Set<String> allow = new LinkedHashSet<>(Set.of("a"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("value", "before");
        PluginsOptions options = new PluginsOptions(true, false, allow, Set.of(), config);
        allow.clear();
        config.put("value", "after");

        List<String> events = new ArrayList<>();
        RecordingPlugin a = plugin("a", events);
        a.onInit = context -> {
            assertThat(context.config()).containsEntry("value", "before");
            assertThatThrownBy(() -> context.config().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        };
        PluginManager manager = manager(options, List.of(a));

        manager.discoverAndInit();
        manager.close();
        assertThat(events).containsExactly("init:a", "close:a");
    }

    private PluginManager manager(PluginsOptions options, List<NodePlugin> plugins) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        return new PluginManager(new NoopEventBus(), scheduler, options,
                Thread.currentThread().getContextClassLoader(), plugins);
    }

    private static PluginsOptions options() {
        return new PluginsOptions(true, false, Set.of(), Set.of(), Map.of());
    }

    private static RecordingPlugin plugin(String id, List<String> events, String... dependencies) {
        return new RecordingPlugin(id, Set.of(dependencies), events);
    }

    private static void assertContextCannotPublish(PluginContext context) {
        assertThatThrownBy(() -> context.registerService("late-service", "value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phase INACTIVE");
        assertThatThrownBy(() -> context.registerStorageFilter(new StorageFilter() { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phase INACTIVE");
    }

    private static final class RecordingPlugin implements NodePlugin {
        private final String id;
        private final Set<String> dependencies;
        private final List<String> events;
        private Consumer<PluginContext> onInit = ignored -> { };
        private Runnable onStart = () -> { };
        private Runnable onClose = () -> { };
        private PluginContext context;
        private boolean failStart;
        private boolean failStop;
        private boolean failClose;
        private Error stopError;
        private Error closeError;
        private int idCalls;
        private int versionCalls;
        private int dependenciesCalls;
        private int capabilitiesCalls;

        private RecordingPlugin(String id, Set<String> dependencies, List<String> events) {
            this.id = id;
            this.dependencies = dependencies;
            this.events = events;
        }

        @Override
        public String id() {
            idCalls++;
            return id;
        }

        @Override
        public String version() {
            versionCalls++;
            return "1.0.0";
        }

        @Override
        public Set<String> dependsOn() {
            dependenciesCalls++;
            return dependencies;
        }

        @Override
        public Set<PluginCapability> capabilities() {
            capabilitiesCalls++;
            return Set.of(PluginCapability.EVENT_CONSUMER);
        }

        @Override
        public void init(PluginContext context) {
            this.context = context;
            events.add("init:" + id);
            onInit.accept(context);
        }

        @Override
        public void start() {
            events.add("start:" + id);
            onStart.run();
            if (failStart) {
                throw new IllegalStateException("start failure: " + id);
            }
        }

        @Override
        public void stop() {
            events.add("stop:" + id);
            if (stopError != null) {
                throw stopError;
            }
            if (failStop) {
                throw new IllegalStateException("stop failure: " + id);
            }
        }

        @Override
        public void close() {
            events.add("close:" + id);
            onClose.run();
            if (closeError != null) {
                throw closeError;
            }
            if (failClose) {
                throw new IllegalStateException("close failure: " + id);
            }
        }
    }

    private static final class FatalPluginError extends Error {
        private FatalPluginError(String message) {
            super(message);
        }
    }

    private static final class TrackingEventBus implements EventBus {
        private final AtomicInteger activeSubscriptions = new AtomicInteger();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> type, EventListener<E> listener, SubscriptionOptions options) {
            activeSubscriptions.incrementAndGet();
            return new TrackingSubscription(activeSubscriptions);
        }

        @Override
        public <E extends Event> void publish(
                E event, EventMetadata metadata, PublishOptions options) {
            // Publishing is not needed for lifecycle registration coverage.
        }

        @Override
        public void close() {
            // The PluginManager owns subscriptions, not the EventBus lifecycle.
        }

        int activeSubscriptionCount() {
            return activeSubscriptions.get();
        }
    }

    private static final class TrackingSubscription implements SubscriptionHandle {
        private final AtomicInteger activeSubscriptions;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private TrackingSubscription(AtomicInteger activeSubscriptions) {
            this.activeSubscriptions = activeSubscriptions;
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                activeSubscriptions.decrementAndGet();
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }
}
