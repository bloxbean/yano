package org.yanoproject.ledger.conformance.engines;

import com.bloxbean.cardano.client.common.model.SlotConfig;

import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.EraSummary;

import java.util.List;

/** Slot timing and epoch geometry of a case, as the legacy validators and the Scalus engine take them. */
final class CaseTiming {

    private CaseTiming() {
    }

    /** @return the case's slot config with the epoch geometry of its era history */
    static SlotConfigSupplier slotConfig(ConformanceCase testCase) {
        SlotConfig slotConfig = testCase.env().slotConfig();
        EpochSlotCalc geometry = geometry(testCase.network().eras());
        return new SlotConfigSupplier() {
            @Override
            public SlotConfig getSlotConfig() {
                return slotConfig;
            }

            @Override
            public EpochSlotCalc getEpochSlotCalc() {
                return geometry;
            }
        };
    }

    /**
     * The geometry of an era history: the first era's epoch size for Byron when the history starts in Byron,
     * the first non-Byron era's start slot, and the last era's epoch size.
     */
    static EpochSlotCalc geometry(List<EraSummary> eras) {
        EraSummary first = eras.getFirst();
        EraSummary last = eras.getLast();
        boolean byron = first.eraTag() == 1;
        long byronEpoch = byron ? first.epochSizeSlots() : last.epochSizeSlots();
        long firstNonByronSlot = 0;
        if (byron) {
            firstNonByronSlot = eras.stream().filter(e -> e.eraTag() != 1).findFirst()
                    .map(e -> e.start().slot()).orElse(0L);
        }
        return new EpochSlotCalc(last.epochSizeSlots(), byronEpoch, firstNonByronSlot);
    }
}
