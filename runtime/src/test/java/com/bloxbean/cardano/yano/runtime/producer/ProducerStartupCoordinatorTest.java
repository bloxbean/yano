package com.bloxbean.cardano.yano.runtime.producer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProducerStartupCoordinatorTest {
    @Test
    void deferredPlanDoesNotStartProducerImmediately() {
        TestActions actions = new TestActions(new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true));
        new ProducerStartupCoordinator(actions).start();

        assertEquals("wire,defer", actions.trace());
    }

    @Test
    void nonDeferredTimeTravelModeIsRejected() {
        TestActions actions = new TestActions(new ProducerStartupPlan(ProducerMode.SLOT_LEADER_TIME_TRAVEL, false));

        assertThrows(IllegalStateException.class, () -> new ProducerStartupCoordinator(actions).start());
        assertEquals("wire", actions.trace());
    }

    private static final class TestActions implements ProducerStartupCoordinator.Actions {
        private final ProducerStartupPlan plan;
        private final AtomicReference<String> trace = new AtomicReference<>("");
        private final AtomicInteger startupPlanCalls = new AtomicInteger();

        private TestActions(ProducerStartupPlan plan) {
            this.plan = plan;
        }

        @Override
        public void wireBlockProducerHelpers() {
            append("wire");
        }

        @Override
        public ProducerStartupPlan startupPlan() {
            startupPlanCalls.incrementAndGet();
            return plan;
        }

        @Override
        public void deferPastTimeTravelBlockProducer() {
            append("defer");
        }

        private String trace() {
            assertEquals(1, startupPlanCalls.get());
            return trace.get();
        }

        private void append(String value) {
            trace.updateAndGet(current -> current.isBlank() ? value : current + "," + value);
        }
    }
}
