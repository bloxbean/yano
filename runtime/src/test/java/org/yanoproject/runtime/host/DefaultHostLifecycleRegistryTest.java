package org.yanoproject.runtime.host;

import org.junit.jupiter.api.Test;
import org.yanoproject.runtime.kernel.NodeKernel;
import org.yanoproject.runtime.kernel.Schedulers;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultHostLifecycleRegistryTest {
    @Test
    void compatibilityConstructorCannotAcceptUnmanagedServices() {
        var host = new DefaultHostApi(new DefaultN2NExtensionRegistry(), PluginProviderRegistry.empty());
        assertThatThrownBy(() -> host.lifecycle().registerBeforePublication(service("orphan", new ArrayList<>())))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("assembly-owned");
    }

    @Test
    void initializationFailureClosesAttemptedServicesAndRegistrationTails() {
        List<String> calls = new ArrayList<>();
        try (var registry = new DefaultHostLifecycleRegistry(); var schedulers = new Schedulers()) {
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
        var registry = new DefaultHostLifecycleRegistry();
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
        var reentrant = new DefaultHostLifecycleRegistry();
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
        try (var registry = new DefaultHostLifecycleRegistry(); var schedulers = new Schedulers()) {
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
        try (var registry = new DefaultHostLifecycleRegistry(); var schedulers = new Schedulers()) {
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
        var registry = new DefaultHostLifecycleRegistry();
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
        var registry = new DefaultHostLifecycleRegistry();
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
        var registry = new DefaultHostLifecycleRegistry();
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
