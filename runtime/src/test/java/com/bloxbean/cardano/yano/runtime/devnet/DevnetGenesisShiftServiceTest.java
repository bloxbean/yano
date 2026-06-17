package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;
import com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevnetGenesisShiftServiceTest {
    @Test
    void devnetTimeTravelShiftMutatesGenesisAndStartsDevnetProducer() {
        FakeActions actions = new FakeActions(genesisConfig(shelleyGenesis(10, 2.0)), true);

        long shiftMillis = service(
                true,
                new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true),
                false,
                101_234,
                actions)
                .shiftGenesisAndStartProducer(2);

        assertEquals(40_000, shiftMillis);
        assertEquals(61_000, actions.configGenesisTimestamp);
        assertEquals("1970-01-01T00:01:01Z", actions.genesisConfig.getSystemStart());
        assertEquals(61_000, actions.resolvedGenesisTimestamp);
        assertTrue(actions.slotTimeInitialized);
        assertTrue(actions.conwayEraSetForFreshStart);
        assertTrue(actions.genesisUtxosStoredForFreshStart);
        assertTrue(actions.devnetTimeTravelStarted);
        assertFalse(actions.slotLeaderTimeTravelStarted);
    }

    @Test
    void slotLeaderTimeTravelShiftStartsSlotLeaderProducer() {
        FakeActions actions = new FakeActions(genesisConfig(shelleyGenesis(5, 1.0)), false);

        long shiftMillis = service(
                true,
                new ProducerStartupPlan(ProducerMode.SLOT_LEADER_TIME_TRAVEL, true),
                false,
                50_000,
                actions)
                .shiftGenesisAndStartProducer(3);

        assertEquals(15_000, shiftMillis);
        assertEquals(35_000, actions.configGenesisTimestamp);
        assertEquals(35_000, actions.resolvedGenesisTimestamp);
        assertFalse(actions.conwayEraSetForFreshStart);
        assertFalse(actions.genesisUtxosStoredForFreshStart);
        assertTrue(actions.slotLeaderTimeTravelStarted);
        assertFalse(actions.devnetTimeTravelStarted);
    }

    @Test
    void preservesValidationOrderAndMessages() {
        FakeActions actions = new FakeActions(genesisConfig(shelleyGenesis(10, 1.0)), true);

        assertEquals("shiftGenesisAndStartProducer requires past-time-travel-mode=true",
                assertThrows(IllegalStateException.class,
                        () -> service(false,
                                new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true),
                                false,
                                100_000,
                                actions).shiftGenesisAndStartProducer(1)).getMessage());

        assertEquals("shiftGenesisAndStartProducer requires a deferred past-time-travel producer plan",
                assertThrows(IllegalStateException.class,
                        () -> service(true,
                                new ProducerStartupPlan(ProducerMode.SLOT_LEADER, false),
                                false,
                                100_000,
                                actions).shiftGenesisAndStartProducer(1)).getMessage());

        assertEquals("Block producer already started — shift can only be called once",
                assertThrows(IllegalStateException.class,
                        () -> service(true,
                                new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true),
                                true,
                                100_000,
                                actions).shiftGenesisAndStartProducer(1)).getMessage());

        assertEquals("Epochs must be positive, got: 0",
                assertThrows(IllegalArgumentException.class,
                        () -> service(true,
                                new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true),
                                false,
                                100_000,
                                actions).shiftGenesisAndStartProducer(0)).getMessage());

        FakeActions missingShelley = new FakeActions(GenesisConfig.load(null, null, null), true);
        assertEquals("Shelley genesis data required for epoch shift",
                assertThrows(IllegalStateException.class,
                        () -> service(true,
                                new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true),
                                false,
                                100_000,
                        missingShelley).shiftGenesisAndStartProducer(1)).getMessage());
    }

    @Test
    void failureAfterShiftMutationReportsMaintenanceDegraded() {
        FakeActions actions = new FakeActions(genesisConfig(shelleyGenesis(10, 2.0)), true);
        actions.startFailure = new IllegalStateException("producer failed");
        RecordingReporter reporter = new RecordingReporter();

        DevnetGenesisShiftService service = new DevnetGenesisShiftService(
                () -> true,
                () -> new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true),
                () -> false,
                () -> 101_234,
                actions,
                reporter);

        assertEquals("producer failed",
                assertThrows(IllegalStateException.class, () -> service.shiftGenesisAndStartProducer(2)).getMessage());
        assertEquals(1, reporter.count);
        assertEquals("devnet genesis shift", reporter.operation);
        assertTrue(reporter.message.contains("restart required"));
        assertEquals(61_000, actions.configGenesisTimestamp);
    }

    @Test
    void validationFailureDoesNotReportMaintenanceDegraded() {
        FakeActions actions = new FakeActions(genesisConfig(shelleyGenesis(10, 2.0)), true);
        RecordingReporter reporter = new RecordingReporter();

        DevnetGenesisShiftService service = new DevnetGenesisShiftService(
                () -> false,
                () -> new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true),
                () -> false,
                () -> 101_234,
                actions,
                reporter);

        assertThrows(IllegalStateException.class, () -> service.shiftGenesisAndStartProducer(2));
        assertEquals(0, reporter.count);
    }

    private static DevnetGenesisShiftService service(boolean pastTimeTravelMode,
                                                    ProducerStartupPlan startupPlan,
                                                    boolean productionInstalled,
                                                    long currentTimeMillis,
                                                    FakeActions actions) {
        return new DevnetGenesisShiftService(
                () -> pastTimeTravelMode,
                () -> startupPlan,
                () -> productionInstalled,
                () -> currentTimeMillis,
                actions);
    }

    private static GenesisConfig genesisConfig(ShelleyGenesisData shelley) {
        return GenesisConfig.fromInMemory(shelley, null, null);
    }

    private static ShelleyGenesisData shelleyGenesis(long epochLength, double slotLength) {
        return new ShelleyGenesisData(
                Map.of(),
                42,
                epochLength,
                slotLength,
                "2020-01-01T00:00:00Z",
                45_000_000_000_000_000L,
                1.0,
                10,
                62,
                100,
                5,
                8,
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
                null);
    }

    private static final class FakeActions implements DevnetGenesisShiftService.Actions {
        private GenesisConfig genesisConfig;
        private final boolean freshStart;
        private long configGenesisTimestamp = -1;
        private long resolvedGenesisTimestamp = -1;
        private boolean slotTimeInitialized;
        private boolean conwayEraSetForFreshStart;
        private boolean genesisUtxosStoredForFreshStart;
        private boolean slotLeaderTimeTravelStarted;
        private boolean devnetTimeTravelStarted;
        private RuntimeException startFailure;

        private FakeActions(GenesisConfig genesisConfig, boolean freshStart) {
            this.genesisConfig = genesisConfig;
            this.freshStart = freshStart;
        }

        @Override
        public GenesisConfig genesisConfig() {
            return genesisConfig;
        }

        @Override
        public void setConfigGenesisTimestamp(long timestampMillis) {
            configGenesisTimestamp = timestampMillis;
        }

        @Override
        public String shelleyGenesisFile() {
            return null;
        }

        @Override
        public void applyShiftedGenesis(GenesisConfig genesisConfig) {
            this.genesisConfig = genesisConfig;
        }

        @Override
        public boolean isFreshStart() {
            return freshStart;
        }

        @Override
        public void setResolvedGenesisTimestamp(long timestampMillis) {
            resolvedGenesisTimestamp = timestampMillis;
        }

        @Override
        public void initSlotTimeCalculator() {
            slotTimeInitialized = true;
        }

        @Override
        public void setConwayEraStartIfFreshStart(boolean freshStart) {
            conwayEraSetForFreshStart = freshStart;
        }

        @Override
        public void storeGenesisUtxosIfNeeded(boolean freshStart) {
            genesisUtxosStoredForFreshStart = freshStart;
        }

        @Override
        public void startSlotLeaderTimeTravel(boolean freshStart) {
            if (startFailure != null) {
                throw startFailure;
            }
            slotLeaderTimeTravelStarted = true;
        }

        @Override
        public void startDevnetTimeTravel(boolean freshStart) {
            if (startFailure != null) {
                throw startFailure;
            }
            devnetTimeTravelStarted = true;
        }
    }

    private static final class RecordingReporter implements MaintenanceFailureReporter {
        private int count;
        private String operation;
        private String message;

        @Override
        public void markDegraded(String operation, String message, Throwable cause) {
            count++;
            this.operation = operation;
            this.message = message;
        }
    }
}
