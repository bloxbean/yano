package com.bloxbean.cardano.yano.runtime.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeKernelTest {
    @Test
    void startsStopsAndClosesSubsystemsInDeclaredOrder() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);
        var second = new RecordingSubsystem("second", calls);

        NodeKernel kernel = new NodeKernel(List.of(first, second), context());

        kernel.start();
        assertEquals(KernelState.RUNNING, kernel.state());
        assertEquals(List.of("first:init", "second:init", "first:start", "second:start"), calls);

        kernel.stop();
        assertEquals(KernelState.STOPPED, kernel.state());
        assertEquals(List.of(
                "first:init", "second:init", "first:start", "second:start",
                "second:stop", "first:stop"), calls);

        kernel.close();
        assertEquals(List.of(
                "first:init", "second:init", "first:start", "second:start",
                "second:stop", "first:stop",
                "second:close", "first:close"), calls);
    }

    @Test
    void startupFailureStopsStartedAndClosesInitializedInReverseOrder() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);
        var second = new RecordingSubsystem("second", calls);
        second.failStart = true;
        var third = new RecordingSubsystem("third", calls);

        NodeKernel kernel = new NodeKernel(List.of(first, second, third), context());

        KernelLifecycleException failure = assertThrows(KernelLifecycleException.class, kernel::start);

        assertEquals(KernelState.FAILED, kernel.state());
        assertEquals("Kernel startup failed", failure.getMessage());
        assertEquals(List.of(
                "first:init", "second:init", "third:init",
                "first:start", "second:start",
                "first:stop",
                "third:close", "second:close", "first:close"), calls);

        kernel.close();
        assertEquals(List.of(
                "first:init", "second:init", "third:init",
                "first:start", "second:start",
                "first:stop",
                "third:close", "second:close", "first:close"), calls);
    }

    @Test
    void initializationFailureClosesTheAttemptedSubsystemAndEarlierOwners() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);
        var failing = new RecordingSubsystem("failing", calls);
        failing.failInit = true;
        var untouched = new RecordingSubsystem("untouched", calls);
        NodeKernel kernel = new NodeKernel(List.of(first, failing, untouched), context());

        assertThrows(KernelLifecycleException.class, kernel::start);

        assertEquals(KernelState.FAILED, kernel.state());
        assertEquals(List.of(
                "first:init", "failing:init",
                "failing:close", "first:close"), calls);
        kernel.close();
        assertEquals(List.of(
                "first:init", "failing:init",
                "failing:close", "first:close"), calls);
    }

    @Test
    void stopCannotTurnFailedStartupIntoAnEmptyRestartableKernel() {
        List<String> calls = new ArrayList<>();
        var failing = new RecordingSubsystem("failing", calls);
        failing.failStart = true;
        NodeKernel kernel = new NodeKernel(List.of(failing), context());

        assertThrows(KernelLifecycleException.class, kernel::start);
        kernel.stop();

        assertEquals(KernelState.FAILED, kernel.state());
        assertThrows(IllegalStateException.class, kernel::start);
        assertEquals(List.of(
                "failing:init", "failing:start", "failing:close"), calls);
        kernel.close();
    }

    @Test
    void restartAfterStopDoesNotInitializeSubsystemsAgain() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);
        var second = new RecordingSubsystem("second", calls);

        NodeKernel kernel = new NodeKernel(List.of(first, second), context());

        kernel.start();
        kernel.stop();
        kernel.start();

        assertEquals(KernelState.RUNNING, kernel.state());
        assertEquals(List.of(
                "first:init", "second:init",
                "first:start", "second:start",
                "second:stop", "first:stop",
                "first:start", "second:start"), calls);
    }

    @Test
    void closeIsTerminal() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);

        NodeKernel kernel = new NodeKernel(List.of(first), context());

        kernel.start();
        kernel.close();

        assertThrows(IllegalStateException.class, kernel::start);
    }

    @Test
    void closeBeforeStartClosesOwnedSubsystems() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);

        NodeKernel kernel = new NodeKernel(List.of(first), context());

        kernel.close();

        assertEquals(List.of("first:close"), calls);
    }

    @Test
    void closeIsIdempotent() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);

        NodeKernel kernel = new NodeKernel(List.of(first), context());

        kernel.close();
        kernel.close();

        assertEquals(List.of("first:close"), calls);
    }

    @Test
    void lifecycleCallbacksCanWaitForWorkerReadingKernelState() {
        AtomicReference<NodeKernel> kernelReference = new AtomicReference<>();
        var subsystem = new StateReadingSubsystem(kernelReference);
        NodeKernel kernel = new NodeKernel(List.of(subsystem), context());
        kernelReference.set(kernel);

        kernel.start();
        kernel.stop();
        kernel.close();

        assertEquals(List.of("init", "start", "stop", "close"), subsystem.callbacks);
        assertEquals(List.of(
                KernelState.CREATED,
                KernelState.STARTING,
                KernelState.STOPPING,
                KernelState.STOPPED), subsystem.observedStates);
    }

    @Test
    void stopContinuesCleanupAndPromotesFatalFailure() {
        List<String> calls = new ArrayList<>();
        var fatalSubsystem = new RecordingSubsystem("fatal", calls);
        var ordinarySubsystem = new RecordingSubsystem("ordinary", calls);
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal stop");
        fatalSubsystem.stopFailure = fatal;
        ordinarySubsystem.stopFailure = new IllegalStateException("ordinary stop");
        NodeKernel kernel = new NodeKernel(List.of(fatalSubsystem, ordinarySubsystem), context());
        kernel.start();

        TestVirtualMachineError thrown = assertThrows(TestVirtualMachineError.class, kernel::stop);

        assertSame(fatal, thrown);
        assertEquals(List.of(
                "fatal:init", "ordinary:init", "fatal:start", "ordinary:start",
                "ordinary:stop", "fatal:stop"), calls);
        assertEquals(KernelState.FAILED, kernel.state());
        assertTrue(List.of(thrown.getSuppressed()).stream()
                .anyMatch(KernelLifecycleException.class::isInstance));
        kernel.close();
    }

    @Test
    void closeContinuesCleanupAndPromotesFatalFailure() {
        List<String> calls = new ArrayList<>();
        var fatalSubsystem = new RecordingSubsystem("fatal", calls);
        var ordinarySubsystem = new RecordingSubsystem("ordinary", calls);
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal close");
        fatalSubsystem.closeFailure = fatal;
        ordinarySubsystem.closeFailure = new IllegalStateException("ordinary close");
        NodeKernel kernel = new NodeKernel(List.of(fatalSubsystem, ordinarySubsystem), context());

        TestVirtualMachineError thrown = assertThrows(TestVirtualMachineError.class, kernel::close);

        assertSame(fatal, thrown);
        assertEquals(List.of("ordinary:close", "fatal:close"), calls);
        assertEquals(KernelState.FAILED, kernel.state());
        assertTrue(List.of(thrown.getSuppressed()).stream()
                .anyMatch(KernelLifecycleException.class::isInstance));
        kernel.close();
        assertEquals(List.of("ordinary:close", "fatal:close"), calls);
    }

    @Test
    void concurrentCloseWaitsForTheActiveCloseTransition() throws InterruptedException {
        var subsystem = new BlockingCloseSubsystem();
        NodeKernel kernel = new NodeKernel(List.of(subsystem), context());
        CountDownLatch firstReturned = new CountDownLatch(1);
        CountDownLatch secondInvoked = new CountDownLatch(1);
        CountDownLatch secondReturned = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread first = Thread.ofPlatform().name("kernel-first-close").start(() -> {
            try {
                kernel.close();
            } catch (Throwable failure) {
                closeFailure.compareAndSet(null, failure);
            } finally {
                firstReturned.countDown();
            }
        });
        assertTrue(subsystem.closeEntered.await(1, TimeUnit.SECONDS));
        Thread second = Thread.ofPlatform().name("kernel-second-close").start(() -> {
            secondInvoked.countDown();
            try {
                kernel.close();
            } catch (Throwable failure) {
                closeFailure.compareAndSet(null, failure);
            } finally {
                secondReturned.countDown();
            }
        });

        try {
            assertTrue(secondInvoked.await(1, TimeUnit.SECONDS));
            assertFalse(secondReturned.await(100, TimeUnit.MILLISECONDS),
                    "concurrent close returned before the active close completed");
        } finally {
            subsystem.allowClose.countDown();
        }

        assertTrue(firstReturned.await(1, TimeUnit.SECONDS));
        assertTrue(secondReturned.await(1, TimeUnit.SECONDS));
        first.join();
        second.join();
        assertEquals(1, subsystem.closeCalls);
        assertNull(closeFailure.get());
    }

    @Test
    void healthFallsBackToDownWhenSubsystemHealthThrows() {
        List<String> calls = new ArrayList<>();
        var first = new RecordingSubsystem("first", calls);
        var second = new RecordingSubsystem("second", calls);
        second.failHealth = true;

        NodeKernel kernel = new NodeKernel(List.of(first, second), context());

        List<SubsystemHealth> health = kernel.health();

        assertEquals(2, health.size());
        assertEquals(SubsystemHealth.Status.UP, health.get(0).status());
        assertEquals(SubsystemHealth.Status.DOWN, health.get(1).status());
        assertEquals(IllegalStateException.class.getName(), health.get(1).message());
    }

    @Test
    void healthDoesNotSwallowProcessFatalErrors() {
        List<String> calls = new ArrayList<>();
        var subsystem = new RecordingSubsystem("fatal-health", calls);
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal health");
        subsystem.healthFailure = fatal;
        NodeKernel kernel = new NodeKernel(List.of(subsystem), context());

        assertSame(fatal, assertThrows(TestVirtualMachineError.class, kernel::health));
        kernel.close();
    }

    @Test
    void serviceRegistryReturnsTypedServices() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(CharSequence.class, "registered");

        assertEquals("registered", registry.require(CharSequence.class));
        assertTrue(registry.get(Number.class).isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.require(Number.class));
    }

    @Test
    void subsystemContextNormalizesNullOptionalValues() {
        SubsystemContext context = new SubsystemContext(null, new Schedulers(), null, null);
        try {
            assertNotNull(context.schedulers());
            assertEquals(Map.of(), context.config());
            assertNotNull(context.services());
        } finally {
            context.schedulers().close();
        }
    }

    private static SubsystemContext context() {
        return new SubsystemContext(null, new Schedulers(), Map.of("test", true), new ServiceRegistry());
    }

    private static final class RecordingSubsystem implements Subsystem {
        private final String name;
        private final List<String> calls;
        private boolean failInit;
        private boolean failStart;
        private boolean failHealth;
        private Throwable healthFailure;
        private Throwable stopFailure;
        private Throwable closeFailure;

        private RecordingSubsystem(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void init(SubsystemContext ctx) {
            calls.add(name + ":init");
            if (failInit) {
                throw new IllegalStateException(name + " init failed");
            }
        }

        @Override
        public void start() {
            calls.add(name + ":start");
            if (failStart) {
                throw new IllegalStateException(name + " start failed");
            }
        }

        @Override
        public void stop() {
            calls.add(name + ":stop");
            throwUnchecked(stopFailure);
        }

        @Override
        public void close() {
            calls.add(name + ":close");
            throwUnchecked(closeFailure);
        }

        @Override
        public SubsystemHealth health() {
            throwUnchecked(healthFailure);
            if (failHealth) {
                throw new IllegalStateException(name + " health failed");
            }
            return Subsystem.super.health();
        }
    }

    private static final class StateReadingSubsystem implements Subsystem {
        private final AtomicReference<NodeKernel> kernelReference;
        private final List<String> callbacks = new ArrayList<>();
        private final List<KernelState> observedStates = new ArrayList<>();

        private StateReadingSubsystem(AtomicReference<NodeKernel> kernelReference) {
            this.kernelReference = kernelReference;
        }

        @Override
        public String name() {
            return "state-reader";
        }

        @Override
        public void init(SubsystemContext ctx) {
            readStateFromWorker("init");
        }

        @Override
        public void start() {
            readStateFromWorker("start");
        }

        @Override
        public void stop() {
            readStateFromWorker("stop");
        }

        @Override
        public void close() {
            readStateFromWorker("close");
        }

        private void readStateFromWorker(String callback) {
            callbacks.add(callback);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<KernelState> observed = new AtomicReference<>();
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = Thread.ofPlatform().name("kernel-state-reader").start(() -> {
                try {
                    observed.set(kernelReference.get().state());
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                } finally {
                    completed.countDown();
                }
            });
            try {
                assertTrue(completed.await(1, TimeUnit.SECONDS),
                        "worker state read blocked behind the lifecycle callback");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for worker state read", e);
            } finally {
                if (completed.getCount() != 0) {
                    worker.interrupt();
                }
            }
            if (workerFailure.get() != null) {
                throw new AssertionError("Worker state read failed", workerFailure.get());
            }
            observedStates.add(observed.get());
        }
    }

    private static final class BlockingCloseSubsystem implements Subsystem {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private int closeCalls;

        @Override
        public String name() {
            return "blocking-close";
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            closeCalls++;
            closeEntered.countDown();
            try {
                if (!allowClose.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release close callback");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while blocking close callback", e);
            }
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
