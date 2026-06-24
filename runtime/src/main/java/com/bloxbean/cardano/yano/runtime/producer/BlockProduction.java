package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerService;

/**
 * Strategy contract for a concrete block-production mode.
 */
public interface BlockProduction extends BlockProducerService {
    ProducerMode mode();

    default boolean supportsEmptyBlockProduction() {
        return false;
    }

    default int produceEmptyBlocksToSlot(long targetSlot) {
        throw unsupported("empty block production");
    }

    default boolean supportsLeaderTimeTravel() {
        return false;
    }

    default int produceLeaderBlocksToSlot(long targetSlot) {
        throw unsupported("leader-aware time-travel production");
    }

    default long lastCheckedSlot() {
        throw unsupported("last checked slot");
    }

    default int slotLengthMillis() {
        throw unsupported("slot length");
    }

    default void setForceSequentialSlots(boolean forceSequentialSlots) {
        throw unsupported("sequential slot mode");
    }

    default UnsupportedOperationException unsupported(String feature) {
        return new UnsupportedOperationException(feature + " is not supported by " + mode() + " producer");
    }
}
