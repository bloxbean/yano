package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledgerrules.SlotConfigSupplier;

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

    static scalus.cardano.ledger.SlotConfig toScalus(SlotConfigSupplier slotConfigSupplier) {
        SlotConfig slotConfig = slotConfigSupplier.getSlotConfig();
        if (slotConfig == null) {
            throw new IllegalStateException("SlotConfig not available");
        }

        EpochSlotCalc epochSlotCalc = slotConfigSupplier.getEpochSlotCalc();
        if (epochSlotCalc == null) {
            return toScalus(slotConfig);
        }

        return new scalus.cardano.ledger.SlotConfig(
                slotConfig.getZeroTime(),
                slotConfig.getZeroSlot(),
                slotConfig.getSlotLength(),
                epochSlotCalc.shelleyEpochLength(),
                epochSlotCalc.firstNonByronEpoch());
    }
}
