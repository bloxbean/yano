package com.bloxbean.cardano.yano.runtime.chronology;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.runtime.SlotTimeCalculator;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.chain.EraMetadataStore;
import lombok.extern.slf4j.Slf4j;

import java.util.OptionalLong;

/**
 * Owns runtime slot/time conversion state.
 */
@Slf4j
public final class ChronologyService {
    private final EraMetadataStore eraMetadataStore;
    private volatile SlotTimeCalculator slotTimeCalculator;

    public ChronologyService(ChainState chainState) {
        this.eraMetadataStore = chainState instanceof EraMetadataStore store ? store : null;
    }

    public boolean initialize(GenesisConfig genesisConfig, long resolvedGenesisTimestampMillis) {
        if (genesisConfig == null) {
            return false;
        }

        long networkStartTimeSec = resolvedGenesisTimestampMillis > 0
                ? resolvedGenesisTimestampMillis / 1000
                : genesisConfig.getNetworkStartTimeSeconds();
        long byronSlotDurationSec = genesisConfig.getByronSlotDurationSeconds();
        double shelleySlotLengthSec = genesisConfig.getShelleySlotLengthSeconds();

        if (networkStartTimeSec <= 0) {
            return false;
        }

        slotTimeCalculator = new SlotTimeCalculator(
                networkStartTimeSec, byronSlotDurationSec, shelleySlotLengthSec, eraMetadataStore);
        log.info("SlotTimeCalculator initialized: networkStart={}, byronSlotDuration={}s, shelleySlotLength={}s",
                networkStartTimeSec, byronSlotDurationSec, shelleySlotLengthSec);
        return true;
    }

    public OptionalLong slotToUnixTime(long slot) {
        SlotTimeCalculator calculator = slotTimeCalculator;
        return calculator != null ? OptionalLong.of(calculator.slotToUnixTime(slot)) : OptionalLong.empty();
    }

    public void invalidateSlotTimeCache() {
        SlotTimeCalculator calculator = slotTimeCalculator;
        if (calculator != null) {
            calculator.invalidateCache();
        }
    }

    public boolean isSlotTimeAvailable() {
        return slotTimeCalculator != null;
    }
}
