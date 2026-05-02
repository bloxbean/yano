package com.bloxbean.cardano.yano.app.api;

import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;

/**
 * Shared epoch/slot calculation utilities for REST endpoints.
 * Uses {@link EpochSlotCalc} with era-aware config from {@link NodeConfig}.
 * <p>
 * Fails fast if epoch params have not been loaded from genesis.
 */
public final class EpochUtil {
    private EpochUtil() {}

    public static long slotsPerEpoch(NodeConfig config) {
        return config.getEpochLength();
    }

    public static int slotToEpoch(long slot, NodeConfig config) {
        return buildCalc(config).slotToEpoch(slot);
    }

    public static int slotToEpochSlot(long slot, NodeConfig config) {
        return buildCalc(config).slotToEpochSlot(slot);
    }

    private static EpochSlotCalc buildCalc(NodeConfig config) {
        long epochLength = config.getEpochLength();
        long byronSlots = config.getByronSlotsPerEpoch();
        long firstNonByron = config.getFirstNonByronSlot();

        if (epochLength <= 0 || byronSlots <= 0 || firstNonByron < 0) {
            throw new IllegalStateException(
                    "Epoch params not initialized from genesis. "
                            + "epochLength=" + epochLength + ", byronSlotsPerEpoch=" + byronSlots
                            + ", firstNonByronSlot=" + firstNonByron);
        }

        return new EpochSlotCalc(epochLength, byronSlots, firstNonByron);
    }
}
