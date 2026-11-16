package org.yanoproject.runtime.host;

import org.junit.jupiter.api.Test;
import org.yanoproject.runtime.kernel.NodeKernel;
import org.yanoproject.runtime.kernel.Schedulers;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.api.plugin.operations.PluginOperationsRecorder;
import org.yanoproject.api.plugin.spi.PluginProducts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultHostLifecycleRegistryTest {
    static final PluginOperationsRecorder NO_RECORDER = new NoopOperationsRecorder();

    static DefaultHostLifecycleRegistry newRegistry() {
        return new DefaultHostLifecycleRegistry(DefaultHostLifecycleRegistry.Callers.NONE, NO_RECORDER);
    }

    /** A caller seam standing in for PluginManager: the "initializing" plugin is set per test step. */
    private static final class TestCallers implements DefaultHostLifecycleRegistry.Callers {
        private final ThreadLocal<String> initializing = new ThreadLocal<>();
        private final Set<String> embedded;

        private TestCallers(String... embedded) {
            this.embedded = Set.of(embedded);
        }

        void asInit(String owner, Runnable step) {
            initializing.set(owner);
            try { step.run(); } finally { initializing.remove(); }
        }

        @Override public Optional<String> initializing() { return Optional.ofNullable(initializing.get()); }
        @Override public boolean embedded(String bundleId) { return embedded.contains(bundleId); }
    }

    @Test
    void ownerRegistrationsRunAssembliesWithOwnerTcclAndRejectLateReentrantDuplicateAndImpersonated() {
        TestCallers callers = new TestCallers("com.example.first", "com.example.second");
        var registry = new DefaultHostLifecycleRegistry(callers, NO_RECORDER);
        ClassLoader pluginLoader = new ClassLoader(getClass().getClassLoader()) { };
        List<String> calls = new ArrayList<>();
        callers.asInit("com.example.first", () -> registry.registerAssembly("com.example.first", () -> {
            calls.add("first:" + (Thread.currentThread().getContextClassLoader() == pluginLoader));
            registry.registerRestartReadiness("com.example.first", () -> true);
            assertThatThrownBy(() -> registry.registerAssembly("com.example.first", () -> { }))
                    .hasMessageContaining("cannot register further assembly steps");
            assertThatThrownBy(() -> registry.registerPluginProducts("com.example.second", products(calls, "x")))
                    .hasMessageContaining("cannot register for owner 'com.example.second'");
            assertThat(registry.restartReady()).isTrue();
            assertThatThrownBy(() -> registry.runAssemblies(pluginLoader))
                    .hasMessageContaining("already ran");
        }));
        callers.asInit("com.example.second", () -> {
            registry.registerAssembly("com.example.second", () -> calls.add("second"));
            assertThatThrownBy(() -> registry.registerAssembly("com.example.second", () -> { }))
                    .hasMessageContaining("Duplicate assembly step");
            assertThatThrownBy(() -> registry.registerAssembly("com.example.first", () -> { }))
                    .hasMessageContaining("cannot register for owner 'com.example.first'");
        });
        callers.asInit("com.example.directory", () -> assertThatThrownBy(() ->
                registry.registerAssembly("com.example.directory", () -> { }))
                .hasMessageContaining("Only embedded host-class-path bundles"));
        assertThatThrownBy(() -> registry.registerAssembly("com.example.first", () -> { }))
                .hasMessageContaining("only during the owner's NodePlugin.init or assembly step");

        ClassLoader caller = Thread.currentThread().getContextClassLoader();
        registry.runAssemblies(pluginLoader);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
        assertThat(calls).containsExactly("first:true", "second");
        callers.asInit("com.example.first", () -> assertThatThrownBy(() ->
                registry.registerAssembly("com.example.first", () -> { }))
                .hasMessageContaining("during NodePlugin.init"));
        registry.seal();
        callers.asInit("com.example.second", () -> assertThatThrownBy(() ->
                registry.registerPluginProducts("com.example.second", products(calls, "late")))
                .hasMessageContaining("before sealing"));
        registry.close();
    }

    @Test
    void assemblyFailureNamesItsOwnerAndStopsLaterSteps() {
        TestCallers callers = new TestCallers("com.example.failing", "com.example.later");
        var registry = new DefaultHostLifecycleRegistry(callers, NO_RECORDER);
        IllegalStateException cause = new IllegalStateException("assembly broke");
        List<String> calls = new ArrayList<>();
        callers.asInit("com.example.failing", () -> registry.registerAssembly("com.example.failing", () -> {
            throw cause;
        }));
        callers.asInit("com.example.later", () -> registry.registerAssembly("com.example.later",
                () -> calls.add("later")));
        assertThatThrownBy(() -> registry.runAssemblies(getClass().getClassLoader()))
                .hasMessageContaining("Plugin bundle 'com.example.failing' failed host assembly")
                .hasCause(cause);
        assertThat(calls).isEmpty();
        registry.close();
    }

    @Test
    void restartReadinessStopsAtFirstFalseAndPropagatesFailures() {
        TestCallers callers = new TestCallers("com.example.a", "com.example.b");
        var registry = new DefaultHostLifecycleRegistry(callers, NO_RECORDER);
        AtomicInteger second = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> mode =
                new java.util.concurrent.atomic.AtomicReference<>("ready");
        callers.asInit("com.example.a", () -> registry.registerRestartReadiness("com.example.a", () ->
                switch (mode.get()) {
                    case "busy" -> false;
                    case "throw" -> throw new IllegalStateException("readiness failed");
                    default -> true;
                }));
        callers.asInit("com.example.b", () -> registry.registerRestartReadiness("com.example.b", () -> {
            second.incrementAndGet();
            return true;
        }));
        assertThat(registry.restartReady()).isTrue();
        assertThat(second).hasValue(1);
        mode.set("busy");
        assertThat(registry.restartReady()).isFalse();
        assertThat(second).hasValue(1);
        mode.set("throw");
        assertThatThrownBy(registry::restartReady).hasMessage("readiness failed");
        callers.asInit("com.example.a", () -> assertThatThrownBy(() ->
                registry.registerRestartReadiness("com.example.a", () -> true))
                .hasMessageContaining("Duplicate restart readiness"));
        registry.close();
    }

    @Test
    void pluginProductsResumeInOrderSealInReverseAndReturnTheHostRecorder() {
        TestCallers callers = new TestCallers("com.example.a", "com.example.b");
        var registry = new DefaultHostLifecycleRegistry(callers, NO_RECORDER);
        List<String> calls = new ArrayList<>();
        IllegalStateException sealFailure = new IllegalStateException("seal failed");
        callers.asInit("com.example.a", () -> assertThat(
                registry.registerPluginProducts("com.example.a", products(calls, "a"))).isSameAs(NO_RECORDER));
        callers.asInit("com.example.b", () -> registry.registerPluginProducts("com.example.b",
                new PluginProducts() {
                    @Override public void resume() { calls.add("b.resume"); }
                    @Override public void sealAndAwait() { calls.add("b.seal"); throw sealFailure; }
                }));
        callers.asInit("com.example.a", () -> assertThatThrownBy(() ->
                registry.registerPluginProducts("com.example.a", products(calls, "again")))
                .hasMessageContaining("Duplicate plugin products"));
        registry.resumePluginProducts();
        assertThatThrownBy(registry::sealPluginProducts).isSameAs(sealFailure);
        assertThat(calls).containsExactly("a.resume", "b.resume", "b.seal", "a.seal");
        registry.close();
    }

    private static PluginProducts products(List<String> calls, String name) {
        return new PluginProducts() {
            @Override public void resume() { calls.add(name + ".resume"); }
            @Override public void sealAndAwait() { calls.add(name + ".seal"); }
        };
    }

    @Test
    void initializationFailureClosesAttemptedServicesAndRegistrationTails() {
        List<String> calls = new ArrayList<>();
        try (var registry = newRegistry(); var schedulers = new Schedulers()) {
            registry.registerBeforePublication(service("first", calls));
            registry.registerBeforePublication(new Subsystem() {
                public String name() { return "failing"; }
                public void init(SubsystemContext context) { throw new IllegalStateException("init failed"); }
                public void start() { throw new AssertionError("Must not start"); }
                public void stop() { throw new AssertionError("Must not stop an unstarted service"); }
                public void close() { calls.add("failing.close"); }
            });
            registry.registerBeforePublication(service("tail", calls));
            registry.seal();
            try (var kernel = new NodeKernel(registry.subsystems(),
                    new SubsystemContext(null, schedulers, Map.of(), null))) {
                assertThatThrownBy(kernel::start).hasRootCauseMessage("init failed");
            }
            assertThat(calls).containsExactly("failing.close", "first.close");
        }
        assertThat(calls).containsExactly("failing.close", "first.close", "tail.close");
    }

    @Test
    void startupErrorRetainsCleanupFailuresAndReentrantCloseCompletes() {
        var registry = newRegistry();
        var failure = new IllegalStateException("start failed");
        var stopFailure = new IllegalStateException("stop failed");
        var closeFailure = new IllegalStateException("close failed");
        registry.registerBeforePublication(new Subsystem() {
            public String name() { return "failing"; }
            public void start() { throw failure; }
            public void stop() { throw stopFailure; }
            public void close() { throw closeFailure; }
        });
        registry.seal();
        assertThatThrownBy(() -> registry.subsystems().getFirst().start()).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(stopFailure, closeFailure);
        // The registry is the sole cleanup owner here; callbacks can reenter it.
        var reentrant = newRegistry();
        var closes = new AtomicInteger();
        reentrant.registerBeforePublication(new Subsystem() {
            public String name() { return "reentrant"; }
            public void start() { }
            public void stop() { }
            public void close() { closes.incrementAndGet(); reentrant.close(); }
        });
        reentrant.close();
        reentrant.close();
        assertThat(closes).hasValue(1);
        assertThatThrownBy(registry::close).isSameAs(closeFailure);
    }

    @Test
    void existingKernelOrdersAndRestartsServicesWithoutDuplicatingClose() {
        List<String> calls = new ArrayList<>();
        try (var registry = newRegistry(); var schedulers = new Schedulers()) {
            registry.registerBeforePublication(service("ledger", calls));
            registry.seal();
            var stages = new ArrayList<Subsystem>();
            stages.add(service("host-storage", calls));
            stages.addAll(registry.subsystems());
            stages.add(service("publication", calls));
            try (var kernel = new NodeKernel(stages, new SubsystemContext(null, schedulers, Map.of(), null))) {
                kernel.start();
                kernel.stop();
                kernel.start();
            }
        }
        assertThat(calls).containsExactly(
                "host-storage.start", "ledger.start", "publication.start",
                "publication.stop", "ledger.stop", "host-storage.stop",
                "host-storage.start", "ledger.start", "publication.start",
                "publication.stop", "ledger.stop", "host-storage.stop",
                "publication.close", "ledger.close", "host-storage.close");
    }

    @Test
    void failedStartCleansFailingServiceAndAlreadyStartedPredecessor() {
        List<String> calls = new ArrayList<>();
        try (var registry = newRegistry(); var schedulers = new Schedulers()) {
            registry.registerBeforePublication(service("first", calls));
            registry.registerBeforePublication(new Subsystem() {
                public String name() { return "failing"; }
                public void start() { calls.add("failing.start"); throw new IllegalStateException("partial start"); }
                public void stop() { calls.add("failing.stop"); }
                public void close() { calls.add("failing.close"); }
            });
            registry.seal();
            try (var kernel = new NodeKernel(registry.subsystems(),
                    new SubsystemContext(null, schedulers, Map.of(), null))) {
                assertThatThrownBy(kernel::start).hasStackTraceContaining("partial start");
            }
        }
        assertThat(calls).containsExactly("first.start", "failing.start", "failing.stop",
                "failing.close", "first.stop", "first.close");
    }

    @Test
    void acceptedServicesCloseBeforeSealAndRejectedServicesRemainCallerOwned() {
        List<String> calls = new ArrayList<>();
        var registry = newRegistry();
        registry.registerBeforePublication(service("first", calls));
        registry.registerBeforePublication(service("second", calls));
        assertThatThrownBy(() -> registry.registerBeforePublication(service("first", calls)))
                .hasMessageContaining("Duplicate");
        registry.close();
        assertThatThrownBy(() -> registry.registerBeforePublication(service("late", calls)))
                .hasMessageContaining("initialization");
        assertThat(calls).containsExactly("second.close", "first.close");
    }

    @Test
    void callbacksRunOutsideRegistryMonitor() {
        var registry = newRegistry();
        registry.registerBeforePublication(new Subsystem() {
            public String name() { assertThat(Thread.holdsLock(registry)).isFalse(); return "service"; }
            public void start() { }
            public void stop() { }
            public void close() { assertThat(Thread.holdsLock(registry)).isFalse(); }
        });
        registry.close();
    }

    @Test
    void concurrentCloseWaitsForCleanupAndDoesNotRepeatIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closes = new AtomicInteger();
        var registry = newRegistry();
        registry.registerBeforePublication(new Subsystem() {
            public String name() { return "service"; }
            public void start() { }
            public void stop() { }
            public void close() {
                closes.incrementAndGet();
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(registry::close);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(registry::close);
            try {
                assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> registry.registerBeforePublication(service("late", new ArrayList<>())))
                        .hasMessageContaining("initialization");
            } finally { release.countDown(); }
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
        assertThat(closes).hasValue(1);
    }

    private static Subsystem service(String name, List<String> calls) {
        return new Subsystem() {
            public String name() { return name; }
            public void start() { calls.add(name + ".start"); }
            public void stop() { calls.add(name + ".stop"); }
            public void close() { calls.add(name + ".close"); }
        };
    }
}
