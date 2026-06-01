package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.common.model.SlotConfig;

final class SlotConfigAdapters {

    private SlotConfigAdapters() {
    }

    static scalus.cardano.ledger.SlotConfig toScalus(SlotConfig slotConfig) {
        if (slotConfig == null) {
            throw new IllegalStateException("SlotConfig not available");
        }
        return new scalus.cardano.ledger.SlotConfig(
                slotConfig.getZeroTime(),
                slotConfig.getZeroSlot(),
                slotConfig.getSlotLength());
    }
}
