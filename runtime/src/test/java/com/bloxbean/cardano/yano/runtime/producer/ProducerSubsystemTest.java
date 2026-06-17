package com.bloxbean.cardano.yano.runtime.producer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProducerSubsystemTest {
    @Test
    void startStopAndResetDelegateToInstalledProduction() {
        ProducerSubsystem subsystem = new ProducerSubsystem();
        FakeProduction production = new FakeProduction(ProducerMode.DEVNET);

        subsystem.install(production);

        subsystem.start();
        subsystem.start();
        assertThat(production.starts).isEqualTo(1);
        assertThat(subsystem.isRunning()).isTrue();

        subsystem.resetToChainTip();
        assertThat(production.resets).isEqualTo(1);

        subsystem.stop();
        subsystem.stop();
        assertThat(production.stops).isEqualTo(1);
        assertThat(subsystem.isRunning()).isFalse();
    }

    @Test
    void installRejectsReplacingRunningProduction() {
        ProducerSubsystem subsystem = new ProducerSubsystem();

        subsystem.install(new FakeProduction(ProducerMode.DEVNET));
        subsystem.start();

        assertThatThrownBy(() -> subsystem.install(new FakeProduction(ProducerMode.SLOT_LEADER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot replace running producer");
    }

    @Test
    void installCanReplaceStoppedProductionForRestartableNodeLifecycle() {
        ProducerSubsystem subsystem = new ProducerSubsystem();
        FakeProduction first = new FakeProduction(ProducerMode.DEVNET);
        FakeProduction second = new FakeProduction(ProducerMode.SLOT_LEADER);

        subsystem.install(first);
        subsystem.start();
        subsystem.stop();
        subsystem.install(second);
        subsystem.start();

        assertThat(first.stops).isEqualTo(1);
        assertThat(second.starts).isEqualTo(1);
        assertThat(subsystem.modeOrNull()).isEqualTo(ProducerMode.SLOT_LEADER);
    }

    @Test
    void genericControlsFailWhenNoProductionInstalled() {
        ProducerSubsystem subsystem = new ProducerSubsystem();

        assertThatThrownBy(() -> subsystem.startOrThrow("Producer control"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Producer control requires an installed block producer");
        assertThat(subsystem.serviceOrNull()).isNull();
        assertThat(subsystem.health().healthy()).isTrue();
    }

    @Test
    void devnetCapabilityDelegatesAndReportsMode() {
        ProducerSubsystem subsystem = new ProducerSubsystem();
        FakeDevnetProduction production = new FakeDevnetProduction();

        subsystem.install(production);

        assertThat(subsystem.hasProduction()).isTrue();
        assertThat(subsystem.hasDevnetProduction()).isTrue();
        assertThat(subsystem.modeOrNull()).isEqualTo(ProducerMode.DEVNET);
        assertThat(subsystem.produceEmptyBlocksToSlot(42, "Time advance")).isEqualTo(7);
        assertThat(production.targetSlot).isEqualTo(42);
        assertThat(subsystem.slotLengthMillis("Time advance")).isEqualTo(1000);

        subsystem.setForceSequentialSlots(true, "Time advance");

        assertThat(production.forceSequentialSlots).isTrue();
        assertThat(subsystem.health().details())
                .containsEntry("mode", "DEVNET")
                .containsEntry("running", false);
    }

    @Test
    void unsupportedCapabilityFailsWithProducerMode() {
        ProducerSubsystem subsystem = new ProducerSubsystem();
        subsystem.install(new FakeProduction(ProducerMode.SLOT_LEADER));

        assertThatThrownBy(() -> subsystem.produceEmptyBlocksToSlot(42, "Time advance"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("empty block production is not supported by SLOT_LEADER producer");
    }

    private static class FakeProduction implements BlockProduction {
        private final ProducerMode mode;
        private boolean running;
        private int starts;
        private int stops;
        private int resets;

        FakeProduction(ProducerMode mode) {
            this.mode = mode;
        }

        @Override
        public ProducerMode mode() {
            return mode;
        }

        @Override
        public void start() {
            starts++;
            running = true;
        }

        @Override
        public void stop() {
            stops++;
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void resetToChainTip() {
            resets++;
        }
    }

    private static final class FakeDevnetProduction extends FakeProduction {
        private long targetSlot;
        private boolean forceSequentialSlots;

        private FakeDevnetProduction() {
            super(ProducerMode.DEVNET);
        }

        @Override
        public boolean supportsEmptyBlockProduction() {
            return true;
        }

        @Override
        public int produceEmptyBlocksToSlot(long targetSlot) {
            this.targetSlot = targetSlot;
            return 7;
        }

        @Override
        public int slotLengthMillis() {
            return 1000;
        }

        @Override
        public void setForceSequentialSlots(boolean forceSequentialSlots) {
            this.forceSequentialSlots = forceSequentialSlots;
        }
    }
}
