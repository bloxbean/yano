package com.bloxbean.cardano.yano.runtime.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(health.get(1).message().contains("health failed"));
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
        private boolean failStart;
        private boolean failHealth;

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
        }

        @Override
        public void close() {
            calls.add(name + ":close");
        }

        @Override
        public SubsystemHealth health() {
            if (failHealth) {
                throw new IllegalStateException(name + " health failed");
            }
            return Subsystem.super.health();
        }
    }
}
