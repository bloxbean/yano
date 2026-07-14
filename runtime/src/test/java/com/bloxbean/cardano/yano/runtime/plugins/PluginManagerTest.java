package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventContext;
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
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginManagerTest {

    @TempDir
    Path temporary;

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
        assertRegisteredFilter(manager, filter);

        manager.startAll();
        manager.startAll();
        manager.stopAll();
        assertRegisteredFilter(manager, filter);
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
        assertRegisteredFilter(manager, cycleFilter);

        manager.stopAll();
        assertThat(plugin.context.getService("cycle-service", String.class)).isEmpty();
        assertThat(manager.getStorageFilters()).isEmpty();
        assertThatThrownBy(() -> plugin.context.registerService("between-cycles", "value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phase STOPPED");

        manager.startAll();
        assertThat(plugin.context.getService("cycle-service", String.class))
                .contains("value-2");
        assertRegisteredFilter(manager, cycleFilter);

        manager.close();
        assertThat(manager.getStorageFilters()).isEmpty();
    }

    @Test
    void managedSchedulerSealIsBoundedButDirectStopWaitsForAdmittedCallback()
            throws Exception {
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("blocked-task", events);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        plugin.onStart = () -> task.set(plugin.context.scheduler().schedule(() -> {
            callbackStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseCallback.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Deliberately interrupt-resistant to exercise eventual quiescence.
                }
            }
        }, 0, TimeUnit.MILLISECONDS));
        PluginManager manager = manager(options(), List.of(plugin));
        manager.discoverAndInit();
        manager.startAll();
        assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> completion = manager.sealManagedCallbacks();
        assertThat(completion).isNotDone();
        assertThat(task.get().isCancelled()).isTrue();

        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        Thread stopThread = new Thread(() -> {
            try {
                manager.stopAll();
            } catch (Throwable failure) {
                stopFailure.set(failure);
            }
        }, "managed-callback-stop-test");
        stopThread.start();
        stopThread.join(100);
        assertThat(stopThread.isAlive()).isTrue();
        assertThat(events).doesNotContain("stop:blocked-task");

        releaseCallback.countDown();
        completion.get(2, TimeUnit.SECONDS);
        stopThread.join(2_000);
        assertThat(stopThread.isAlive()).isFalse();
        assertThat(stopFailure.get()).isNull();
        assertThat(events).contains("stop:blocked-task");
        manager.close();
    }

    @Test
    void managedEventListenerIsFencedClosedExactlyOnceAndFatalErrorSurvivesRestart()
            throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        DispatchEventBus eventBus = new DispatchEventBus();
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("event-listener", events);
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicBoolean failFatally = new AtomicBoolean();
        FatalPluginError fatal = new FatalPluginError("fatal managed listener");
        plugin.onStart = () -> plugin.context.eventBus().subscribe(
                TestManagedEvent.class, ignored -> {
                    if (failFatally.get()) {
                        throw fatal;
                    }
                    listenerStarted.countDown();
                    while (true) {
                        try {
                            if (releaseListener.await(5, TimeUnit.SECONDS)) {
                                return;
                            }
                        } catch (InterruptedException ignoredInterrupt) {
                            // Exercise the admitted-callback barrier.
                        }
                    }
                }, SubscriptionOptions.builder().build());
        PluginManager manager = new PluginManager(
                eventBus, scheduler, options(),
                Thread.currentThread().getContextClassLoader(), List.of(plugin));
        manager.discoverAndInit();
        manager.startAll();

        Thread dispatch = new Thread(eventBus::publishTestEvent,
                "managed-event-dispatch-test");
        dispatch.start();
        assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> completion = manager.sealManagedCallbacks();
        assertThat(eventBus.closeCalls).hasValue(1);
        assertThat(completion).isNotDone();
        releaseListener.countDown();
        completion.get(2, TimeUnit.SECONDS);
        dispatch.join(2_000);
        manager.stopAll();

        manager.startAll();
        failFatally.set(true);
        assertThat(catchThrowable(eventBus::publishTestEvent)).isSameAs(fatal);
        manager.stopAll();
        assertThat(eventBus.closeCalls).hasValue(2);
        manager.close();
        assertThat(eventBus.closeCalls).hasValue(2);
    }

    @Test
    void managedCallbackCannotInitiateStopAndManagerRemainsStoppable() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        DispatchEventBus eventBus = new DispatchEventBus();
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("callback-stop", events);
        AtomicReference<PluginManager> managerRef = new AtomicReference<>();
        AtomicReference<Throwable> rejection = new AtomicReference<>();
        plugin.onStart = () -> plugin.context.eventBus().subscribe(
                TestManagedEvent.class,
                ignored -> rejection.set(catchThrowable(managerRef.get()::stopAll)),
                SubscriptionOptions.builder().build());
        PluginManager manager = new PluginManager(
                eventBus, scheduler, options(), getClass().getClassLoader(), List.of(plugin));
        managerRef.set(manager);
        manager.discoverAndInit();
        manager.startAll();

        eventBus.publishTestEvent();

        assertThat(rejection.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("managed plugin callback");
        assertThat(events).doesNotContain("stop:callback-stop");
        manager.stopAll();
        assertThat(events).contains("stop:callback-stop");
        manager.close();
    }

    @Test
    void runtimeStartPreflightRejectsNodePluginLifecycleReentry() {
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("lifecycle-preflight", events);
        AtomicReference<PluginManager> managerRef = new AtomicReference<>();
        AtomicReference<Throwable> rejection = new AtomicReference<>();
        plugin.onStart = () -> rejection.set(catchThrowable(() ->
                managerRef.get().requireLifecycleTeardownAllowed("start the runtime")));
        PluginManager manager = manager(options(), List.of(plugin));
        managerRef.set(manager);
        manager.discoverAndInit();

        manager.startAll();

        assertThat(rejection.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lifecycle callback/state STARTING");
        manager.stopAll();
        manager.close();
    }

    @Test
    void stopDoesNotHoldManagerMonitorWhileAdmittedCallbackFinishes() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        DispatchEventBus eventBus = new DispatchEventBus();
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("manager-read", events);
        AtomicReference<PluginManager> managerRef = new AtomicReference<>();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch allowManagerRead = new CountDownLatch(1);
        CountDownLatch managerReadFinished = new CountDownLatch(1);
        plugin.onStart = () -> plugin.context.eventBus().subscribe(
                TestManagedEvent.class, ignored -> {
                    callbackStarted.countDown();
                    allowManagerRead.await(2, TimeUnit.SECONDS);
                    managerRef.get().getStorageFilters();
                    managerReadFinished.countDown();
                }, SubscriptionOptions.builder().build());
        PluginManager manager = new PluginManager(
                eventBus, scheduler, options(), getClass().getClassLoader(), List.of(plugin));
        managerRef.set(manager);
        manager.discoverAndInit();
        manager.startAll();

        Thread dispatch = new Thread(eventBus::publishTestEvent, "manager-read-dispatch");
        dispatch.start();
        assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        Thread stop = new Thread(() -> stopFailure.set(catchThrowable(manager::stopAll)),
                "manager-read-stop");
        stop.start();
        assertThat(awaitWaiting(stop, 2, TimeUnit.SECONDS)).isTrue();
        allowManagerRead.countDown();

        assertThat(managerReadFinished.await(2, TimeUnit.SECONDS)).isTrue();
        dispatch.join(2_000);
        stop.join(2_000);
        assertThat(stop.isAlive()).isFalse();
        assertThat(stopFailure.get()).isNull();
        manager.close();
    }

    @Test
    void stopAndCloseCallbacksCanWaitForWorkerManagerReads() {
        RecordingPlugin plugin = plugin("lifecycle-worker-read", new ArrayList<>());
        AtomicReference<PluginManager> managerRef = new AtomicReference<>();
        AtomicInteger workerReads = new AtomicInteger();
        plugin.onStop = () -> requireWorkerManagerRead(
                managerRef.get(), "stop", workerReads);
        plugin.onClose = () -> requireWorkerManagerRead(
                managerRef.get(), "close", workerReads);
        PluginManager manager = manager(options(), List.of(plugin));
        managerRef.set(manager);
        manager.discoverAndInit();
        manager.startAll();

        assertThat(catchThrowable(manager::close)).isNull();
        assertThat(workerReads).hasValue(2);
    }

    @Test
    void eventSubscriptionRegistrationHandoffIsPartOfTheDrainBarrier() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        BlockingSubscribeEventBus eventBus = new BlockingSubscribeEventBus();
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("blocked-subscribe", events);
        PluginManager manager = new PluginManager(
                eventBus, scheduler, options(), getClass().getClassLoader(), List.of(plugin));
        manager.discoverAndInit();
        manager.startAll();

        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        Thread registration = new Thread(() -> registrationFailure.set(catchThrowable(() ->
                plugin.context.eventBus().subscribe(TestManagedEvent.class, ignored -> { },
                        SubscriptionOptions.builder().build()))), "blocked-subscribe-register");
        registration.start();
        assertThat(eventBus.registrationStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        Thread stop = new Thread(() -> stopFailure.set(catchThrowable(manager::stopAll)),
                "blocked-subscribe-stop");
        stop.start();
        stop.join(100);
        assertThat(stop.isAlive()).isTrue();
        assertThat(events).doesNotContain("stop:blocked-subscribe");

        eventBus.releaseRegistration.countDown();
        registration.join(2_000);
        stop.join(2_000);
        assertThat(registrationFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sealed during registration");
        assertThat(eventBus.closeCalls).hasValue(1);
        assertThat(stop.isAlive()).isFalse();
        assertThat(stopFailure.get()).isNull();
        manager.close();
    }

    @Test
    void eventPublishHandoffIsDrainedAndRetainedContextCannotPublishAfterStop()
            throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        BlockingPublishEventBus eventBus = new BlockingPublishEventBus();
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("blocked-publish", events);
        PluginManager manager = new PluginManager(
                eventBus, scheduler, options(), getClass().getClassLoader(), List.of(plugin));
        manager.discoverAndInit();
        manager.startAll();

        AtomicReference<Throwable> publishFailure = new AtomicReference<>();
        Thread publisher = new Thread(() -> publishFailure.set(catchThrowable(() ->
                plugin.context.eventBus().publish(
                        new TestManagedEvent(), EventMetadata.builder().build(),
                        PublishOptions.builder().build()))), "blocked-event-publish");
        publisher.start();
        assertThat(eventBus.publishStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        Thread stop = new Thread(() -> stopFailure.set(catchThrowable(manager::stopAll)),
                "blocked-publish-stop");
        stop.start();
        stop.join(100);
        assertThat(stop.isAlive()).isTrue();
        assertThat(events).doesNotContain("stop:blocked-publish");

        eventBus.releasePublish.countDown();
        publisher.join(2_000);
        stop.join(2_000);
        assertThat(publishFailure.get()).isNull();
        assertThat(stopFailure.get()).isNull();
        assertThat(events).contains("stop:blocked-publish");
        assertThatThrownBy(() -> plugin.context.eventBus().publish(
                new TestManagedEvent(), EventMetadata.builder().build(),
                PublishOptions.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admission is sealed");
        manager.close();
    }

    @Test
    void scheduledTaskRegistrationHandoffIsPartOfTheDrainBarrier() throws Exception {
        BlockingScheduledExecutor scheduler = new BlockingScheduledExecutor();
        schedulers.add(scheduler);
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("blocked-schedule", events);
        PluginManager manager = new PluginManager(
                new NoopEventBus(), scheduler, options(), getClass().getClassLoader(),
                List.of(plugin));
        manager.discoverAndInit();
        manager.startAll();

        AtomicReference<ScheduledFuture<?>> result = new AtomicReference<>();
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        Thread registration = new Thread(() -> {
            try {
                result.set(plugin.context.scheduler().schedule(
                        () -> { }, 1, TimeUnit.DAYS));
            } catch (Throwable failure) {
                registrationFailure.set(failure);
            }
        }, "blocked-schedule-register");
        registration.start();
        assertThat(scheduler.registrationStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        Thread stop = new Thread(() -> stopFailure.set(catchThrowable(manager::stopAll)),
                "blocked-schedule-stop");
        stop.start();
        stop.join(100);
        assertThat(stop.isAlive()).isTrue();
        assertThat(events).doesNotContain("stop:blocked-schedule");

        scheduler.releaseRegistration.countDown();
        registration.join(2_000);
        stop.join(2_000);
        assertThat(registrationFailure.get()).isNull();
        assertThat(result.get() != null).isTrue();
        assertThat(result.get().isCancelled()).isTrue();
        assertThat(stop.isAlive()).isFalse();
        assertThat(stopFailure.get()).isNull();
        manager.close();
    }

    @Test
    void stopCancelsFutureReturnedByQueuedSubmit() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        scheduler.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        RecordingPlugin plugin = plugin("queued-submit", new ArrayList<>());
        PluginManager manager = new PluginManager(
                new NoopEventBus(), scheduler, options(), getClass().getClassLoader(),
                List.of(plugin));
        manager.discoverAndInit();
        manager.startAll();

        AtomicInteger executions = new AtomicInteger();
        Future<?> submitted = plugin.context.scheduler().submit(() -> {
            executions.incrementAndGet();
        });
        manager.stopAll();

        assertThat(submitted.isDone()).isTrue();
        assertThat(submitted.isCancelled()).isTrue();
        assertThat(executions).hasValue(0);
        releaseWorker.countDown();
        manager.close();
    }

    @Test
    void failingStartDoesNotHoldManagerMonitorWhileManagedTaskFinishes()
            throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "plugin-start-read-test");
            thread.setDaemon(true);
            return thread;
        });
        schedulers.add(scheduler);
        RecordingPlugin plugin = plugin("start-read", new ArrayList<>());
        AtomicReference<PluginManager> managerRef = new AtomicReference<>();
        CountDownLatch managerReadFinished = new CountDownLatch(1);
        plugin.onStart = () -> {
            plugin.context.scheduler().execute(() -> {
                managerRef.get().getStorageFilters();
                managerReadFinished.countDown();
            });
            boolean finished;
            try {
                finished = managerReadFinished.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("plugin start was interrupted", interrupted);
            }
            if (!finished) {
                throw new IllegalStateException("managed task could not read plugin manager");
            }
            throw new IllegalArgumentException("intentional start failure");
        };
        PluginManager manager = new PluginManager(
                new NoopEventBus(), scheduler, options(), getClass().getClassLoader(),
                List.of(plugin));
        managerRef.set(manager);
        manager.discoverAndInit();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch startReturned = new CountDownLatch(1);
        Thread start = new Thread(() -> {
            try {
                manager.startAll();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                startReturned.countDown();
            }
        }, "plugin-failing-start-test");
        start.setDaemon(true);
        start.start();

        assertThat(startReturned.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(managerReadFinished.getCount()).isZero();
        assertThat(failure.get())
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .extracting(problem -> ((PluginManager.PluginManagerException) problem).phase())
                .isEqualTo(PluginManager.FailurePhase.START);
        manager.close();
    }

    @Test
    void queuedCallbackFromStoppedGenerationCannotRunAfterRestart() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        HistoricalDispatchEventBus eventBus = new HistoricalDispatchEventBus();
        List<String> events = new ArrayList<>();
        List<SubscriptionHandle> handles = new ArrayList<>();
        AtomicInteger callbacks = new AtomicInteger();
        RecordingPlugin plugin = plugin("generation", events);
        plugin.onStart = () -> handles.add(plugin.context.eventBus().subscribe(
                TestManagedEvent.class, ignored -> callbacks.incrementAndGet(),
                SubscriptionOptions.builder().build()));
        PluginManager manager = new PluginManager(
                eventBus, scheduler, options(), getClass().getClassLoader(), List.of(plugin));
        manager.discoverAndInit();
        manager.startAll();
        assertThat(handles.getFirst().isActive()).isTrue();
        manager.stopAll();
        assertThat(handles.getFirst().isActive()).isFalse();
        manager.startAll();
        assertThat(handles.get(1).isActive()).isTrue();

        eventBus.dispatchHistorical(0); // queued before the first handle was closed
        assertThat(callbacks).hasValue(0);
        // A new-generation PluginContext publish is itself a root admission.
        // The historical wrapper is nested in that root and must still retain
        // its explicit old-generation rejection.
        eventBus.dispatchOnPublish(0);
        plugin.context.eventBus().publish(
                new TestManagedEvent(), EventMetadata.builder().build(),
                PublishOptions.builder().build());
        assertThat(callbacks).hasValue(0);
        eventBus.dispatchOnPublish(1);
        plugin.context.eventBus().publish(
                new TestManagedEvent(), EventMetadata.builder().build(),
                PublishOptions.builder().build());
        assertThat(callbacks).hasValue(1);
        manager.close();
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
    void lazyMetadataCollectionsAreSnapshottedInsidePluginTccl() {
        ClassLoader pluginLoader = new ClassLoader(getClass().getClassLoader()) { };
        AtomicInteger traversals = new AtomicInteger();
        NodePlugin plugin = new NodePlugin() {
            @Override public String id() { return "lazy"; }
            @Override public String version() { return "1.0.0"; }
            @Override public Set<String> dependsOn() {
                return lazySet(Set.of(), pluginLoader, traversals);
            }
            @Override public Set<PluginCapability> capabilities() {
                return lazySet(Set.of(PluginCapability.EVENT_CONSUMER),
                        pluginLoader, traversals);
            }
            @Override public void init(PluginContext ctx) { }
            @Override public void start() { }
            @Override public void stop() { }
            @Override public void close() { }
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        PluginManager manager = new PluginManager(new NoopEventBus(), scheduler, options(),
                pluginLoader, List.of(plugin));

        manager.discoverAndInit();
        manager.close();

        assertThat(traversals).hasValue(2);
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
    void deepDependencyChainOrdersWithoutUsingTheJvmStack() {
        int pluginCount = 4_096;
        List<String> events = new ArrayList<>();
        PluginManager manager = manager(
                options(), deepPlugins(pluginCount, false, events));
        withoutPluginManagerInfoLogs(() -> {
            try {
                manager.discoverAndInit();

                assertThat(events).hasSize(pluginCount);
                assertThat(events.getFirst()).isEqualTo(
                        "init:" + deepPluginId(pluginCount - 1));
                assertThat(events.getLast()).isEqualTo("init:" + deepPluginId(0));
            } finally {
                manager.close();
            }
        });
    }

    @Test
    void deepDependencyCycleHasDeterministicDiagnosticWithoutStackOverflow() {
        int pluginCount = 4_096;
        List<String> events = new ArrayList<>();
        PluginManager manager = manager(
                options(), deepPlugins(pluginCount, true, events));
        withoutPluginManagerInfoLogs(() -> {
            try {
                Throwable failure = catchThrowable(manager::discoverAndInit);

                assertThat(failure)
                        .isInstanceOf(PluginManager.PluginManagerException.class)
                        .isNotInstanceOf(StackOverflowError.class);
                assertThat(failure.getMessage())
                        .startsWith("Plugin dependency cycle: ["
                                + deepPluginId(0) + ", " + deepPluginId(1))
                        .contains("<4092 nodes omitted>")
                        .contains(deepPluginId(pluginCount - 2))
                        .contains(deepPluginId(pluginCount - 1))
                        .endsWith(deepPluginId(0) + "]");
                assertThat(failure.getMessage().length())
                        .isLessThanOrEqualTo(PluginManager.MAX_CYCLE_DIAGNOSTIC_LENGTH);
                assertThat(events).isEmpty();
            } finally {
                manager.close();
            }
        });
    }

    @Test
    void metadataAssertionIsConvertedToASecretSafeManagerFailure() {
        String secret = "metadata-secret-must-not-enter-manager-diagnostic";
        AssertionError assertion = new AssertionError(secret);
        PluginManager manager = manager(
                options(), List.of(metadataFailingPlugin(assertion)));

        assertThatThrownBy(manager::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageNotContaining(secret)
                .hasRootCauseMessage(secret)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.DISCOVERY);

        manager.close();
    }

    @Test
    void processFatalMetadataFailureStillEscapesUnwrapped() {
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal metadata");
        PluginManager manager = manager(
                options(), List.of(metadataFailingPlugin(fatal)));

        assertThatThrownBy(manager::discoverAndInit).isSameAs(fatal);

        manager.close();
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
    void standaloneServiceLoaderDiscoveryIsBoundedAndClosesConstructedPrefixInReverse() {
        List<String> events = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        ServiceLoader.Provider<NodePlugin> provider = new ServiceLoader.Provider<>() {
            @Override public Class<? extends NodePlugin> type() { return NodePlugin.class; }
            @Override public NodePlugin get() {
                return plugin("raw-" + sequence.incrementAndGet(), events);
            }
        };
        Iterator<ServiceLoader.Provider<NodePlugin>> infinite = new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public ServiceLoader.Provider<NodePlugin> next() { return provider; }
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        PluginManager manager = new PluginManager(
                new NoopEventBus(), scheduler, Map.of(), getClass().getClassLoader());

        assertThatThrownBy(() -> manager.discoverCandidatesForTesting(infinite, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("global limit of 2");
        manager.close();

        assertThat(sequence).hasValue(2);
        assertThat(events).containsExactly("close:raw-2", "close:raw-1");
    }

    @Test
    void standaloneServiceLoaderPathOwnsFilteringFailuresAndSuccessInReverse() throws Exception {
        // Policy filtering closes the rejected instance immediately; selected
        // ownership remains with normal lifecycle shutdown.
        StandalonePluginProbe.reset(StandalonePluginProbe.Mode.NORMAL);
        try (StandaloneManager opened = standaloneManager(
                new PluginsOptions(true, false, Set.of(), Set.of("standalone-b"), Map.of()),
                StandaloneAPlugin.class, StandaloneBPlugin.class)) {
            opened.manager().discoverAndInit();
            opened.manager().startAll();
        }
        assertThat(StandalonePluginProbe.events).containsExactly(
                "close:standalone-b", "init:standalone-a", "start:standalone-a",
                "stop:standalone-a", "close:standalone-a");

        StandalonePluginProbe.reset(StandalonePluginProbe.Mode.METADATA_FAILURE);
        try (StandaloneManager opened = standaloneManager(options(),
                StandaloneAPlugin.class, StandaloneBPlugin.class,
                StandaloneCPlugin.class)) {
            assertThatThrownBy(opened.manager()::discoverAndInit)
                    .isInstanceOf(PluginManager.PluginManagerException.class)
                    .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                    .isEqualTo(PluginManager.FailurePhase.DISCOVERY);
        }
        assertThat(StandalonePluginProbe.events).containsExactly(
                "close:standalone-c", "close:standalone-b", "close:standalone-a");

        StandalonePluginProbe.reset(StandalonePluginProbe.Mode.INIT_FAILURE);
        try (StandaloneManager opened = standaloneManager(options(),
                StandaloneAPlugin.class, StandaloneBPlugin.class,
                StandaloneCPlugin.class)) {
            assertThatThrownBy(opened.manager()::discoverAndInit)
                    .isInstanceOf(PluginManager.PluginManagerException.class)
                    .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                    .isEqualTo(PluginManager.FailurePhase.INITIALIZATION);
        }
        assertThat(StandalonePluginProbe.events).containsExactly(
                "init:standalone-a", "init:standalone-b",
                "close:standalone-c", "close:standalone-b", "close:standalone-a");

        StandalonePluginProbe.reset(StandalonePluginProbe.Mode.NORMAL);
        try (StandaloneManager opened = standaloneManager(options(),
                StandaloneAPlugin.class, StandaloneBPlugin.class,
                StandaloneCPlugin.class)) {
            opened.manager().discoverAndInit();
            opened.manager().startAll();
        }
        assertThat(StandalonePluginProbe.events).containsExactly(
                "init:standalone-a", "init:standalone-b", "init:standalone-c",
                "start:standalone-a", "start:standalone-b", "start:standalone-c",
                "stop:standalone-c", "stop:standalone-b", "stop:standalone-a",
                "close:standalone-c", "close:standalone-b", "close:standalone-a");
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
    void initAssertionAndSneakyCheckedStartUseSafeDiagnosticsAfterRollback() {
        String initSecret = "secret initialization assertion";
        List<String> initEvents = new ArrayList<>();
        RecordingPlugin initA = plugin("a", initEvents);
        RecordingPlugin initB = plugin("b", initEvents, "a");
        initB.onInit = ignored -> {
            throw new AssertionError(initSecret);
        };
        PluginManager initManager = manager(options(), List.of(initB, initA));

        assertThatThrownBy(initManager::discoverAndInit)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageNotContaining(initSecret)
                .hasRootCauseMessage(initSecret)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.INITIALIZATION);
        assertThat(initEvents).containsExactly("init:a", "init:b", "close:b", "close:a");
        initManager.close();

        String startSecret = "secret checked start failure";
        Exception checkedStart = new Exception(startSecret);
        List<String> startEvents = new ArrayList<>();
        RecordingPlugin startA = plugin("a", startEvents);
        RecordingPlugin startB = plugin("b", startEvents, "a");
        startB.onStart = () -> sneakyThrow(checkedStart);
        PluginManager startManager = manager(options(), List.of(startB, startA));
        startManager.discoverAndInit();

        assertThatThrownBy(startManager::startAll)
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageNotContaining(startSecret)
                .hasRootCauseMessage(startSecret)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.START);
        assertThat(startEvents).containsExactly(
                "init:a", "init:b", "start:a", "start:b",
                "stop:b", "stop:a", "close:b", "close:a");
        startManager.close();
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
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getCause().getSuppressed()).hasSize(1);
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
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getCause().getSuppressed()).hasSize(1);
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
        assertRegisteredFilter(closedManager, closeFilter);
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
    void nonProcessFatalStopAndCloseErrorsUseSafeDiagnosticsAfterReverseCleanup() {
        List<String> stopEvents = new ArrayList<>();
        RecordingPlugin stopA = plugin("a", stopEvents);
        RecordingPlugin stopB = plugin("b", stopEvents, "a");
        FatalPluginError fatalStop = new FatalPluginError("fatal stop");
        stopB.stopError = fatalStop;
        RecordingPlugin stopC = plugin("c", stopEvents, "b");
        PluginManager stopManager = manager(options(), List.of(stopC, stopB, stopA));

        stopManager.discoverAndInit();
        stopManager.startAll();
        assertThat(catchThrowable(stopManager::stopAll))
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageNotContaining("fatal stop")
                .hasCause(fatalStop)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.STOP);
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
        assertThat(catchThrowable(closeManager::close))
                .isInstanceOf(PluginManager.PluginManagerException.class)
                .hasMessageNotContaining("fatal close")
                .hasCause(fatalClose)
                .extracting(failure -> ((PluginManager.PluginManagerException) failure).phase())
                .isEqualTo(PluginManager.FailurePhase.CLOSE);
        assertThat(closeEvents).containsExactly(
                "init:a", "init:b", "init:c", "close:c", "close:b", "close:a");
        assertThat(closeManager.getStorageFilters()).isEmpty();
        closeManager.close();
    }

    @Test
    void processFatalCleanupOutranksAnEarlierAssertionError() {
        List<String> stopEvents = new ArrayList<>();
        RecordingPlugin stopA = plugin("a", stopEvents);
        RecordingPlugin stopB = plugin("b", stopEvents, "a");
        TestVirtualMachineError fatalStop = new TestVirtualMachineError("fatal stop");
        stopB.stopError = fatalStop;
        RecordingPlugin stopC = plugin("c", stopEvents, "b");
        AssertionError assertionStop = new AssertionError("assertion stop");
        stopC.stopError = assertionStop;
        PluginManager stopManager = manager(options(), List.of(stopC, stopB, stopA));
        stopManager.discoverAndInit();
        stopManager.startAll();

        assertThat(catchThrowable(stopManager::stopAll)).isSameAs(fatalStop);
        assertThat(fatalStop.getSuppressed()).contains(assertionStop);
        stopManager.close();

        List<String> closeEvents = new ArrayList<>();
        RecordingPlugin closeA = plugin("a", closeEvents);
        RecordingPlugin closeB = plugin("b", closeEvents, "a");
        TestVirtualMachineError fatalClose = new TestVirtualMachineError("fatal close");
        closeB.closeError = fatalClose;
        RecordingPlugin closeC = plugin("c", closeEvents, "b");
        AssertionError assertionClose = new AssertionError("assertion close");
        closeC.closeError = assertionClose;
        PluginManager closeManager = manager(options(), List.of(closeC, closeB, closeA));
        closeManager.discoverAndInit();

        assertThat(catchThrowable(closeManager::close)).isSameAs(fatalClose);
        assertThat(fatalClose.getSuppressed()).contains(assertionClose);
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

    @Test
    void catalogSealRejectsOrdinaryWorkButStillRunsNodePluginStopAndClose() {
        List<String> events = new ArrayList<>();
        RecordingPlugin plugin = plugin("sealed-lifecycle", events);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        PluginSpiFacades.CallbackTracker callbacks =
                new PluginSpiFacades.CallbackTracker();
        PluginManager manager = PluginManager.fromCatalog(
                new NoopEventBus(),
                scheduler,
                options(),
                Thread.currentThread().getContextClassLoader(),
                List.of(plugin),
                Set.of("sealed-lifecycle"),
                Map.of(),
                ignored -> { },
                ignored -> { },
                callbacks);

        manager.discoverAndInit();
        manager.startAll();
        callbacks.seal();

        assertThatThrownBy(() -> callbacks.call(() -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admission is sealed");
        manager.stopAll();
        manager.close();

        assertThat(events).containsExactly(
                "init:sealed-lifecycle",
                "start:sealed-lifecycle",
                "stop:sealed-lifecycle",
                "close:sealed-lifecycle");
        assertThat(callbacks.hasPending()).isFalse();
    }

    private PluginManager manager(PluginsOptions options, List<NodePlugin> plugins) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        return new PluginManager(new NoopEventBus(), scheduler, options,
                Thread.currentThread().getContextClassLoader(), plugins);
    }

    private static void assertRegisteredFilter(
            PluginManager manager,
            StorageFilter expected) {
        assertThat(manager.getStorageFilters()).singleElement().satisfies(filter ->
                assertThat(PluginContextFacades.storageFilterDelegate(filter))
                        .isSameAs(expected));
    }

    private static PluginsOptions options() {
        return new PluginsOptions(true, false, Set.of(), Set.of(), Map.of());
    }

    @SafeVarargs
    private final StandaloneManager standaloneManager(
            PluginsOptions options,
            Class<? extends NodePlugin>... providers
    ) throws Exception {
        Path jar = temporary.resolve("node-plugin-services-"
                + System.nanoTime() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(
                    "META-INF/services/" + NodePlugin.class.getName()));
            String services = java.util.Arrays.stream(providers)
                    .map(Class::getName)
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            output.write(services.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        URLClassLoader loader = new NodePluginServiceClassLoader(
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        PluginManager manager = PluginManager.withOptions(
                new NoopEventBus(), scheduler, options, loader);
        return new StandaloneManager(manager, loader);
    }

    private record StandaloneManager(
            PluginManager manager,
            URLClassLoader loader
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                manager.close();
            } finally {
                loader.close();
            }
        }
    }

    private static final class NodePluginServiceClassLoader extends URLClassLoader {
        private static final String SERVICE =
                "META-INF/services/" + NodePlugin.class.getName();

        private NodePluginServiceClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public java.util.Enumeration<URL> getResources(String name)
                throws java.io.IOException {
            if (!SERVICE.equals(name)) {
                return super.getResources(name);
            }
            URL local = findResource(name);
            return java.util.Collections.enumeration(
                    local != null ? List.of(local) : List.of());
        }
    }

    private static final class StandalonePluginProbe {
        private enum Mode { NORMAL, METADATA_FAILURE, INIT_FAILURE }
        private static final List<String> events = new ArrayList<>();
        private static Mode mode = Mode.NORMAL;

        private static void reset(Mode next) {
            events.clear();
            mode = next;
        }
    }

    public static final class StandaloneAPlugin implements NodePlugin {
        @Override public String id() { return "standalone-a"; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext context) {
            StandalonePluginProbe.events.add("init:" + id());
        }
        @Override public void start() {
            StandalonePluginProbe.events.add("start:" + id());
        }
        @Override public void stop() {
            StandalonePluginProbe.events.add("stop:" + id());
        }
        @Override public void close() {
            StandalonePluginProbe.events.add("close:" + id());
        }
    }

    public static final class StandaloneBPlugin implements NodePlugin {
        @Override public String id() { return "standalone-b"; }
        @Override public String version() {
            if (StandalonePluginProbe.mode
                    == StandalonePluginProbe.Mode.METADATA_FAILURE) {
                throw new IllegalStateException("metadata failed");
            }
            return "1.0.0";
        }
        @Override public Set<String> dependsOn() { return Set.of("standalone-a"); }
        @Override public void init(PluginContext context) {
            StandalonePluginProbe.events.add("init:" + id());
            if (StandalonePluginProbe.mode == StandalonePluginProbe.Mode.INIT_FAILURE) {
                throw new IllegalStateException("init failed");
            }
        }
        @Override public void start() {
            StandalonePluginProbe.events.add("start:" + id());
        }
        @Override public void stop() {
            StandalonePluginProbe.events.add("stop:" + id());
        }
        @Override public void close() {
            StandalonePluginProbe.events.add("close:" + id());
        }
    }

    public static final class StandaloneCPlugin implements NodePlugin {
        @Override public String id() { return "standalone-c"; }
        @Override public String version() { return "1.0.0"; }
        @Override public Set<String> dependsOn() { return Set.of("standalone-b"); }
        @Override public void init(PluginContext context) {
            StandalonePluginProbe.events.add("init:" + id());
        }
        @Override public void start() {
            StandalonePluginProbe.events.add("start:" + id());
        }
        @Override public void stop() {
            StandalonePluginProbe.events.add("stop:" + id());
        }
        @Override public void close() {
            StandalonePluginProbe.events.add("close:" + id());
        }
    }

    private static RecordingPlugin plugin(String id, List<String> events, String... dependencies) {
        return new RecordingPlugin(id, Set.of(dependencies), events);
    }

    private static List<NodePlugin> deepPlugins(
            int pluginCount,
            boolean cyclic,
            List<String> events
    ) {
        List<NodePlugin> plugins = new ArrayList<>(pluginCount);
        for (int index = 0; index < pluginCount; index++) {
            Set<String> dependencies;
            if (index + 1 < pluginCount) {
                dependencies = Set.of(deepPluginId(index + 1));
            } else if (cyclic) {
                dependencies = Set.of(deepPluginId(0));
            } else {
                dependencies = Set.of();
            }
            plugins.add(new RecordingPlugin(
                    deepPluginId(index), dependencies, events));
        }
        return List.copyOf(plugins);
    }

    private static String deepPluginId(int index) {
        return "deep-plugin-%04d".formatted(index);
    }

    private static void withoutPluginManagerInfoLogs(Runnable action) {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(
                PluginManager.class);
        org.apache.log4j.Level previous = logger.getLevel();
        logger.setLevel(org.apache.log4j.Level.WARN);
        try {
            action.run();
        } finally {
            logger.setLevel(previous);
        }
    }

    private static NodePlugin metadataFailingPlugin(Error failure) {
        return new NodePlugin() {
            @Override
            public String id() {
                throw failure;
            }

            @Override public String version() { return "1.0.0"; }
            @Override public void init(PluginContext ctx) { }
            @Override public void start() { }
            @Override public void stop() { }
            @Override public void close() { }
        };
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void sneakyThrow(Throwable failure) throws X {
        throw (X) failure;
    }

    private static <T> Set<T> lazySet(
            Set<T> values, ClassLoader expectedLoader, AtomicInteger traversals) {
        return new java.util.AbstractSet<>() {
            @Override
            public java.util.Iterator<T> iterator() {
                assertThat(Thread.currentThread().getContextClassLoader())
                        .isSameAs(expectedLoader);
                traversals.incrementAndGet();
                return values.iterator();
            }

            @Override
            public int size() {
                return values.size();
            }
        };
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
        private Runnable onStop = () -> { };
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
            onStop.run();
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

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }

    private record TestManagedEvent() implements Event { }

    private static final class DispatchEventBus implements EventBus {
        private final AtomicInteger closeCalls = new AtomicInteger();
        private Registration current;

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <E extends Event> SubscriptionHandle subscribe(
                Class<E> type, EventListener<E> listener, SubscriptionOptions options) {
            if (type != TestManagedEvent.class) {
                throw new IllegalArgumentException("Unexpected event type: " + type.getName());
            }
            Registration registration = new Registration(
                    (EventListener<TestManagedEvent>) listener, options);
            current = registration;
            return new SubscriptionHandle() {
                @Override
                public void close() {
                    if (registration.active.compareAndSet(true, false)) {
                        closeCalls.incrementAndGet();
                        synchronized (DispatchEventBus.this) {
                            if (current == registration) {
                                current = null;
                            }
                        }
                    }
                }

                @Override
                public boolean isActive() {
                    return registration.active.get();
                }
            };
        }

        @Override
        public <E extends Event> void publish(
                E event, EventMetadata metadata, PublishOptions options) {
            if (event instanceof TestManagedEvent managedEvent) {
                publish(managedEvent, metadata);
            }
        }

        void publishTestEvent() {
            publish(new TestManagedEvent(), EventMetadata.builder().build());
        }

        private void publish(TestManagedEvent event, EventMetadata metadata) {
            Registration registration;
            synchronized (this) {
                registration = current;
            }
            if (registration == null || !registration.active.get()) {
                return;
            }
            if (registration.options.<TestManagedEvent>filter() != null
                    && !registration.options.<TestManagedEvent>filter().test(event, metadata)) {
                return;
            }
            try {
                registration.listener.onEvent(new EventContext<>() {
                    @Override
                    public TestManagedEvent event() {
                        return event;
                    }

                    @Override
                    public EventMetadata metadata() {
                        return metadata;
                    }
                });
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Listener failed", failure);
            }
        }

        @Override
        public void close() {
            // The PluginManager owns subscriptions, not the EventBus lifecycle.
        }

        private record Registration(
                EventListener<TestManagedEvent> listener,
                SubscriptionOptions options,
                AtomicBoolean active) {
            private Registration(
                    EventListener<TestManagedEvent> listener,
                    SubscriptionOptions options) {
                this(listener, options, new AtomicBoolean(true));
            }
        }
    }

    private static final class BlockingSubscribeEventBus implements EventBus {
        private final CountDownLatch registrationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRegistration = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> type, EventListener<E> listener, SubscriptionOptions options) {
            registrationStarted.countDown();
            awaitUninterruptibly(releaseRegistration);
            AtomicBoolean active = new AtomicBoolean(true);
            return new SubscriptionHandle() {
                @Override
                public void close() {
                    if (active.compareAndSet(true, false)) {
                        closeCalls.incrementAndGet();
                    }
                }

                @Override
                public boolean isActive() {
                    return active.get();
                }
            };
        }

        @Override
        public <E extends Event> void publish(
                E event, EventMetadata metadata, PublishOptions options) {
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingPublishEventBus implements EventBus {
        private final CountDownLatch publishStarted = new CountDownLatch(1);
        private final CountDownLatch releasePublish = new CountDownLatch(1);

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> type, EventListener<E> listener, SubscriptionOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E extends Event> void publish(
                E event, EventMetadata metadata, PublishOptions options) {
            publishStarted.countDown();
            awaitUninterruptibly(releasePublish);
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingScheduledExecutor extends ScheduledThreadPoolExecutor {
        private final CountDownLatch registrationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRegistration = new CountDownLatch(1);

        private BlockingScheduledExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            registrationStarted.countDown();
            awaitUninterruptibly(releaseRegistration);
            return super.schedule(command, delay, unit);
        }
    }

    private static final class HistoricalDispatchEventBus implements EventBus {
        private final List<HistoricalRegistration> registrations = new ArrayList<>();
        private int publishIndex = -1;

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <E extends Event> SubscriptionHandle subscribe(
                Class<E> type, EventListener<E> listener, SubscriptionOptions options) {
            if (type != TestManagedEvent.class) {
                throw new IllegalArgumentException("Unexpected event type: " + type.getName());
            }
            HistoricalRegistration registration = new HistoricalRegistration(
                    (EventListener<TestManagedEvent>) listener);
            registrations.add(registration);
            return new SubscriptionHandle() {
                @Override
                public void close() {
                    registration.active.set(false);
                }

                @Override
                public boolean isActive() {
                    return registration.active.get();
                }
            };
        }

        void dispatchHistorical(int index) throws Exception {
            HistoricalRegistration registration;
            synchronized (this) {
                registration = registrations.get(index);
            }
            // Deliberately ignore active: this models a platform executor that
            // queued the wrapper just before handle.close() took effect.
            registration.listener.onEvent(testEventContext());
        }

        synchronized void dispatchOnPublish(int index) {
            publishIndex = index;
        }

        @Override
        public <E extends Event> void publish(
                E event, EventMetadata metadata, PublishOptions options) {
            HistoricalRegistration registration;
            synchronized (this) {
                if (publishIndex < 0) {
                    return;
                }
                registration = registrations.get(publishIndex);
            }
            try {
                // Deliberately ignore active, as dispatchHistorical does.
                registration.listener.onEvent(testEventContext());
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Historical listener failed", failure);
            }
        }

        @Override
        public void close() {
        }

        private record HistoricalRegistration(
                EventListener<TestManagedEvent> listener,
                AtomicBoolean active) {
            private HistoricalRegistration(EventListener<TestManagedEvent> listener) {
                this(listener, new AtomicBoolean(true));
            }
        }
    }

    private static EventContext<TestManagedEvent> testEventContext() {
        return new EventContext<>() {
            @Override
            public TestManagedEvent event() {
                return new TestManagedEvent();
            }

            @Override
            public EventMetadata metadata() {
                return EventMetadata.builder().build();
            }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitWaiting(Thread thread, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static void requireWorkerManagerRead(
            PluginManager manager,
            String phase,
            AtomicInteger reads
    ) {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                manager.getStorageFilters();
                reads.incrementAndGet();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                finished.countDown();
            }
        }, "plugin-" + phase + "-manager-read");
        worker.setDaemon(true);
        worker.start();
        try {
            if (!finished.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Plugin " + phase + " worker could not read plugin manager");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Plugin " + phase + " callback was interrupted", interrupted);
        }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() instanceof RuntimeException runtime) throw runtime;
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
