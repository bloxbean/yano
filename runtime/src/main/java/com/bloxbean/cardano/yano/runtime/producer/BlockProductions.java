package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerService;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducer;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducer;

import java.util.Objects;

/**
 * Adapters from legacy producer implementations to the strategy contract.
 */
public final class BlockProductions {
    private BlockProductions() {
    }

    public static BlockProduction devnet(DevnetBlockProducer producer, ProducerMode mode) {
        if (mode != ProducerMode.DEVNET && mode != ProducerMode.DEVNET_TIME_TRAVEL) {
            throw new IllegalArgumentException("Devnet producer cannot use mode " + mode);
        }
        return new DevnetProduction(producer, mode);
    }

    public static BlockProduction slotLeader(BlockProducerService producer) {
        return new DelegatingProduction(ProducerMode.SLOT_LEADER, producer);
    }

    public static BlockProduction slotLeaderTimeTravel(SlotLeaderTimeTravelBlockProducer producer) {
        return new SlotLeaderTimeTravelProduction(producer);
    }

    /**
     * Generic production adapter for producers that already expose the legacy
     * service contract.
     */
    private record DelegatingProduction(ProducerMode mode, BlockProducerService delegate)
            implements BlockProduction {
        private DelegatingProduction {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public void resetToChainTip() {
            delegate.resetToChainTip();
        }
    }

    /**
     * Production adapter for devnet producers that can synthesize empty blocks.
     */
    private record DevnetProduction(DevnetBlockProducer delegate, ProducerMode mode)
            implements BlockProduction {
        private DevnetProduction {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(mode, "mode");
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public void resetToChainTip() {
            delegate.resetToChainTip();
        }

        @Override
        public boolean supportsEmptyBlockProduction() {
            return true;
        }

        @Override
        public int produceEmptyBlocksToSlot(long targetSlot) {
            return delegate.produceEmptyBlocksToSlot(targetSlot);
        }

        @Override
        public int slotLengthMillis() {
            return delegate.getSlotLengthMillis();
        }

        @Override
        public void setForceSequentialSlots(boolean forceSequentialSlots) {
            delegate.setForceSequentialSlots(forceSequentialSlots);
        }
    }

    /**
     * Production adapter for slot-leader past-time-travel operation.
     */
    private record SlotLeaderTimeTravelProduction(SlotLeaderTimeTravelBlockProducer delegate)
            implements BlockProduction {
        private SlotLeaderTimeTravelProduction {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public ProducerMode mode() {
            return ProducerMode.SLOT_LEADER_TIME_TRAVEL;
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public void resetToChainTip() {
            delegate.resetToChainTip();
        }

        @Override
        public boolean supportsLeaderTimeTravel() {
            return true;
        }

        @Override
        public int produceLeaderBlocksToSlot(long targetSlot) {
            return delegate.produceToSlot(targetSlot);
        }

        @Override
        public long lastCheckedSlot() {
            return delegate.getLastCheckedSlot();
        }

        @Override
        public void setForceSequentialSlots(boolean forceSequentialSlots) {
            delegate.setForceSequentialSlots(forceSequentialSlots);
        }
    }
}
