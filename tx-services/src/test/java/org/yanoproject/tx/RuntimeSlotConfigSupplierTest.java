package org.yanoproject.tx;

import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.runtime.blockproducer.GenesisConfig;
import org.yanoproject.runtime.genesis.ShelleyGenesisData;
import org.yanoproject.api.genesis.ShelleyGenesisBootstrap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSlotConfigSupplierTest {
    private static final EpochSlotCalc DEVNET_EPOCHS = new EpochSlotCalc(100, 100, 0);

    @Test
    void resolvesZeroTimeFromShelleySystemStartAsEpochMillis() {
        var config = YanoConfig.devnetDefault(13337);
        var systemStart = "2026-06-01T00:00:00Z";
        var supplier = new RuntimeSlotConfigSupplier(config, () -> 0L, genesis(systemStart, 2.0), DEVNET_EPOCHS);

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
                genesis("2026-06-01T00:00:00Z", 1.0), DEVNET_EPOCHS);
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
                genesis("2026-06-01T00:00:00Z", 1.0), DEVNET_EPOCHS);

        assertEquals(resolvedMillis, supplier.getSlotConfig().getZeroTime());
    }

    @Test
    void reportsWhetherZeroTimeCanBeResolvedAtBootstrap() {
        var config = YanoConfig.devnetDefault(13337);

        var deferred = new RuntimeSlotConfigSupplier(config, () -> 0L, null, DEVNET_EPOCHS);
        var fromGenesis = new RuntimeSlotConfigSupplier(
                config, () -> 0L, genesis("2026-06-01T00:00:00Z", 1.0), DEVNET_EPOCHS);

        assertEquals(false, deferred.canResolveZeroTimeNow());
        assertEquals(true, fromGenesis.canResolveZeroTimeNow());
    }

    @Test
    void rejectsSecondsLookingConfiguredGenesisTimestamp() {
        var config = YanoConfig.devnetDefault(13337);
        config.setGenesisTimestamp(1_780_000_000L);
        var supplier = new RuntimeSlotConfigSupplier(
                config, () -> 0L, genesis("2026-06-01T00:00:00Z", 1.0), DEVNET_EPOCHS);

        var error = assertThrows(IllegalStateException.class, supplier::getSlotConfig);

        assertEquals("yano.block-producer.genesis-timestamp must be epoch milliseconds, got 1780000000 "
                + "which looks like epoch seconds", error.getMessage());
    }

    @Test
    void derivesSlotLengthFromGenesisWhenConfigUsesAutoValue() {
        var config = YanoConfig.devnetDefault(13337);
        config.setSlotLengthMillis(0);
        var supplier = new RuntimeSlotConfigSupplier(
                config, () -> 0L, genesis("2026-06-01T00:00:00Z", 0.25), DEVNET_EPOCHS);

        assertEquals(250, supplier.getSlotConfig().getSlotLength());
    }

    @Test
    void usesExistingRuntimeSlotLengthFallbackWhenGenesisSlotLengthUnavailable() {
        var config = YanoConfig.devnetDefault(13337);
        config.setSlotLengthMillis(0);
        var supplier = new RuntimeSlotConfigSupplier(
                config, () -> 0L, genesis("2026-06-01T00:00:00Z", 0), DEVNET_EPOCHS);

        assertEquals(1_000, supplier.getSlotConfig().getSlotLength());
    }

    @Test
    void shippedMainnetGenesisResolvesShelleyBoundaryWithoutHistoricalChainState() {
        var config = YanoConfig.devnetDefault(13337);
        var mainnetEpochs = new EpochSlotCalc(432_000, 21_600, 4_492_800);
        long shelleyStart = Instant.parse("2020-07-29T21:44:51Z").toEpochMilli();
        var supplier = new RuntimeSlotConfigSupplier(
                config, () -> 0L, networkGenesis("mainnet"), mainnetEpochs);

        var slotConfig = supplier.getSlotConfig();

        assertEquals(4_492_800, slotConfig.getZeroSlot());
        assertEquals(shelleyStart, slotConfig.getZeroTime());
        assertSame(mainnetEpochs, supplier.getEpochSlotCalc());
    }

    @Test
    void byronNetworkPreservesTimestampOverridesAndAddsByronDuration() {
        var config = YanoConfig.devnetDefault(13337);
        config.setGenesisTimestamp(Instant.parse("2017-09-23T21:44:51Z").toEpochMilli());
        var mainnetEpochs = new EpochSlotCalc(432_000, 21_600, 4_492_800);
        long shelleyStart = Instant.parse("2020-07-29T21:44:51Z").toEpochMilli();
        var supplier = new RuntimeSlotConfigSupplier(
                config,
                () -> Instant.parse("2017-09-23T21:44:51Z").toEpochMilli(),
                networkGenesis("mainnet"),
                mainnetEpochs);

        var slotConfig = supplier.getSlotConfig();

        assertEquals(4_492_800, slotConfig.getZeroSlot());
        assertEquals(shelleyStart, slotConfig.getZeroTime());
        config.setGenesisTimestamp(config.getGenesisTimestamp() + 1_234);
        assertEquals(shelleyStart + 1_234, supplier.getSlotConfig().getZeroTime());
        config.setGenesisTimestamp(0);
        assertEquals(shelleyStart, supplier.getSlotConfig().getZeroTime());
    }

    @Test
    void shippedPreprodGenesisResolvesShelleyBoundary() {
        var supplier = new RuntimeSlotConfigSupplier(YanoConfig.devnetDefault(13337), () -> 0L,
                networkGenesis("preprod"), new EpochSlotCalc(432_000, 21_600, 86_400));

        var timing = supplier.getSlotConfig();

        assertEquals(86_400, timing.getZeroSlot());
        assertEquals(1_655_769_600_000L, timing.getZeroTime());
        assertEquals(1_000, timing.getSlotLength());
        assertEquals(4, supplier.getEpochSlotCalc().firstNonByronEpoch());
    }

    @Test
    void shippedPreviewGenesisNeedsNoByronTimeOffset() {
        var genesis = networkGenesis("preview");
        var supplier = new RuntimeSlotConfigSupplier(YanoConfig.devnetDefault(13337), () -> 0L,
                genesis, new EpochSlotCalc(86_400, 4_320, 0));

        assertEquals(0, supplier.getSlotConfig().getZeroSlot());
        assertEquals(1_666_656_000_000L, supplier.getSlotConfig().getZeroTime());
    }

    private static GenesisConfig networkGenesis(String network) {
        Path directory = Path.of("app/config/network", network);
        if (!Files.isDirectory(directory)) {
            directory = Path.of("..").resolve(directory);
        }
        var genesis = GenesisConfig.load(directory.resolve("shelley-genesis.json").toString(),
                directory.resolve("byron-genesis.json").toString(), null);
        assertNotNull(genesis.getShelleyGenesisData());
        assertNotNull(genesis.getByronGenesisData());
        return genesis;
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
