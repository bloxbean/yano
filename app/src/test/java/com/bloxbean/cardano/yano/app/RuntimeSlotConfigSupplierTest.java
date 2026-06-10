package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import com.bloxbean.cardano.yano.api.genesis.ShelleyGenesisBootstrap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSlotConfigSupplierTest {

    @Test
    void resolvesZeroTimeFromShelleySystemStartAsEpochMillis() {
        var config = YanoConfig.devnetDefault(13337);
        var systemStart = "2026-06-01T00:00:00Z";
        var supplier = new RuntimeSlotConfigSupplier(config, () -> 0L, genesis(systemStart, 2.0));

        var slotConfig = supplier.getSlotConfig();

        assertEquals(Instant.parse(systemStart).toEpochMilli(), slotConfig.getZeroTime());
        assertEquals(2_000, slotConfig.getSlotLength());
        assertEquals(0, slotConfig.getZeroSlot());
    }

    @Test
    void configuredGenesisTimestampOverridesCapturedGenesisAfterRuntimeShift() {
        var config = YanoConfig.devnetDefault(13337);
        var supplier = new RuntimeSlotConfigSupplier(
                config,
                () -> Instant.parse("2026-06-01T00:00:00Z").toEpochMilli(),
                genesis("2026-06-01T00:00:00Z", 1.0));
        long shiftedMillis = Instant.parse("2026-05-31T00:00:00Z").toEpochMilli();

        config.setGenesisTimestamp(shiftedMillis);

        assertEquals(shiftedMillis, supplier.getSlotConfig().getZeroTime());
    }

    @Test
    void resolvedGenesisTimestampOverridesLoadedShelleySystemStart() {
        var config = YanoConfig.devnetDefault(13337);
        long resolvedMillis = Instant.parse("2026-05-31T00:00:00Z").toEpochMilli();
        var supplier = new RuntimeSlotConfigSupplier(
                config,
                () -> resolvedMillis,
                genesis("2026-06-01T00:00:00Z", 1.0));

        assertEquals(resolvedMillis, supplier.getSlotConfig().getZeroTime());
    }

    @Test
    void rejectsSecondsLookingConfiguredGenesisTimestamp() {
        var config = YanoConfig.devnetDefault(13337);
        config.setGenesisTimestamp(1_780_000_000L);
        var supplier = new RuntimeSlotConfigSupplier(config, () -> 0L, genesis("2026-06-01T00:00:00Z", 1.0));

        var error = assertThrows(IllegalStateException.class, supplier::getSlotConfig);

        assertEquals("yano.block-producer.genesis-timestamp must be epoch milliseconds, got 1780000000 "
                + "which looks like epoch seconds", error.getMessage());
    }

    @Test
    void derivesSlotLengthFromGenesisWhenConfigUsesAutoValue() {
        var config = YanoConfig.devnetDefault(13337);
        config.setSlotLengthMillis(0);
        var supplier = new RuntimeSlotConfigSupplier(config, () -> 0L, genesis("2026-06-01T00:00:00Z", 0.25));

        assertEquals(250, supplier.getSlotConfig().getSlotLength());
    }

    @Test
    void usesExistingRuntimeSlotLengthFallbackWhenGenesisSlotLengthUnavailable() {
        var config = YanoConfig.devnetDefault(13337);
        config.setSlotLengthMillis(0);
        var supplier = new RuntimeSlotConfigSupplier(config, () -> 0L, genesis("2026-06-01T00:00:00Z", 0));

        assertEquals(1_000, supplier.getSlotConfig().getSlotLength());
    }

    private static GenesisConfig genesis(String systemStart, double slotLengthSeconds) {
        return GenesisConfig.fromInMemory(
                new ShelleyGenesisData(
                        Map.of(),
                        42,
                        100,
                        slotLengthSeconds,
                        systemStart,
                        45_000_000_000_000_000L,
                        1.0,
                        10,
                        10,
                        10,
                        1,
                        10,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        1,
                        0,
                        0,
                        0,
                        BigDecimal.ZERO,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        null,
                        0,
                        ShelleyGenesisBootstrap.empty()),
                null,
                null);
    }
}
