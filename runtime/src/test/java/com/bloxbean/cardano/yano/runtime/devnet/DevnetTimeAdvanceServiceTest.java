package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.producer.BlockProduction;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;
import com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevnetTimeAdvanceServiceTest {
    @Test
    void advanceBySlotsStopsProducerProducesToTargetAndRestarts() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeTip(chainState, 10, 3);
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeDevnetProduction production = new FakeDevnetProduction(chainState, 2, 1000);
        producerSubsystem.install(production);
        producerSubsystem.start();

        DevnetTimeAdvanceService service = service(chainState, producerSubsystem, 100);
        var result = service.advanceBySlots(5);

        assertEquals(15, production.targetSlot);
        assertEquals(15, result.newSlot());
        assertEquals(5, result.newBlockNumber());
        assertEquals(2, result.blocksProduced());
        assertTrue(producerSubsystem.isRunning());
        assertEquals(1, production.stops);
        assertEquals(2, production.starts);
    }

    @Test
    void advanceBySecondsConvertsToSlotsUsingProducerSlotLength() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeTip(chainState, 10, 3);
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeDevnetProduction production = new FakeDevnetProduction(chainState, 1, 2000);
        producerSubsystem.install(production);

        DevnetTimeAdvanceService service = service(chainState, producerSubsystem, 100);
        var result = service.advanceBySeconds(5);

        assertEquals(12, production.targetSlot);
        assertEquals(12, result.newSlot());
    }

    @Test
    void advanceBySecondsRoundsUpToOneSlot() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeTip(chainState, 10, 3);
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeDevnetProduction production = new FakeDevnetProduction(chainState, 1, 2000);
        producerSubsystem.install(production);

        DevnetTimeAdvanceService service = service(chainState, producerSubsystem, 100);
        service.advanceBySeconds(1);

        assertEquals(11, production.targetSlot);
    }

    @Test
    void requiresDevModeAndDevnetProduction() {
        InMemoryChainState chainState = new InMemoryChainState();
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();

        assertEquals("Time advance requires dev mode (yano.dev-mode=true)",
                assertThrows(IllegalStateException.class,
                        () -> new DevnetTimeAdvanceService(
                                () -> false,
                                () -> true,
                                chainState,
                                producerSubsystem,
                                100).advanceBySlots(1)).getMessage());
        assertEquals("Time advance requires block producer to be running",
                assertThrows(IllegalStateException.class,
                        () -> new DevnetTimeAdvanceService(
                                () -> true,
                                () -> false,
                                chainState,
                                producerSubsystem,
                                100).advanceBySlots(1)).getMessage());
    }

    @Test
    void rejectsInvalidSlotAndSecondInputs() {
        InMemoryChainState chainState = new InMemoryChainState();
        ProducerSubsystem producerSubsystem = devnetProducer(chainState, 1000);
        DevnetTimeAdvanceService service = service(chainState, producerSubsystem, 5);

        assertEquals("Slots must be positive, got: 0",
                assertThrows(IllegalArgumentException.class, () -> service.advanceBySlots(0)).getMessage());
        assertEquals("Cannot advance more than 5 slots per request",
                assertThrows(IllegalArgumentException.class, () -> service.advanceBySlots(6)).getMessage());
        assertEquals("Seconds must be positive, got: 0",
                assertThrows(IllegalArgumentException.class, () -> service.advanceBySeconds(0)).getMessage());

        ProducerSubsystem fastSlotProducer = devnetProducer(chainState, 1);
        DevnetTimeAdvanceService fastSlotService = service(chainState, fastSlotProducer, 5);
        assertEquals("Cannot advance more than 5 slots per request",
                assertThrows(IllegalArgumentException.class,
                        () -> fastSlotService.advanceBySeconds(Integer.MAX_VALUE)).getMessage());
    }

    @Test
    void rejectsInvalidSlotLength() {
        InMemoryChainState chainState = new InMemoryChainState();
        ProducerSubsystem producerSubsystem = devnetProducer(chainState, 0);
        DevnetTimeAdvanceService service = service(chainState, producerSubsystem, 100);

        assertEquals("Slot length is not configured",
                assertThrows(IllegalStateException.class, () -> service.advanceBySeconds(1)).getMessage());
    }

    @Test
    void invalidInputDoesNotReportMaintenanceDegraded() {
        InMemoryChainState chainState = new InMemoryChainState();
        ProducerSubsystem producerSubsystem = devnetProducer(chainState, 1000);
        RecordingReporter reporter = new RecordingReporter();
        DevnetTimeAdvanceService service = service(chainState, producerSubsystem, 5, reporter);

        assertThrows(IllegalArgumentException.class, () -> service.advanceBySlots(0));

        assertEquals(0, reporter.count);
    }

    @Test
    void failureAfterMutationStartReportsMaintenanceDegraded() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeTip(chainState, 10, 3);
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeDevnetProduction production = new FakeDevnetProduction(chainState, 2, 1000);
        production.produceFailure = new IllegalStateException("produce failed");
        producerSubsystem.install(production);
        producerSubsystem.start();
        RecordingReporter reporter = new RecordingReporter();

        DevnetTimeAdvanceService service = service(chainState, producerSubsystem, 100, reporter);

        assertEquals("produce failed",
                assertThrows(IllegalStateException.class, () -> service.advanceBySlots(5)).getMessage());
        assertEquals(1, reporter.count);
        assertEquals("devnet time advance", reporter.operation);
        assertTrue(reporter.message.contains("restart required"));
        assertTrue(producerSubsystem.isRunning());
    }

    private static DevnetTimeAdvanceService service(InMemoryChainState chainState,
                                                    ProducerSubsystem producerSubsystem,
                                                    int maxAdvanceSlots) {
        return service(chainState, producerSubsystem, maxAdvanceSlots, MaintenanceFailureReporter.noop());
    }

    private static DevnetTimeAdvanceService service(InMemoryChainState chainState,
                                                    ProducerSubsystem producerSubsystem,
                                                    int maxAdvanceSlots,
                                                    MaintenanceFailureReporter reporter) {
        return new DevnetTimeAdvanceService(
                () -> true,
                producerSubsystem::hasDevnetProduction,
                chainState,
                producerSubsystem,
                maxAdvanceSlots,
                reporter);
    }

    private static ProducerSubsystem devnetProducer(InMemoryChainState chainState, int slotLengthMillis) {
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        producerSubsystem.install(new FakeDevnetProduction(chainState, 1, slotLengthMillis));
        return producerSubsystem;
    }

    private static void storeTip(InMemoryChainState chainState, long slot, long blockNumber) {
        chainState.storeBlock(hash(slot), blockNumber, slot, new byte[]{1});
    }

    private static byte[] hash(long value) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) value);
        return hash;
    }

    private static final class FakeDevnetProduction implements BlockProduction {
        private final InMemoryChainState chainState;
        private final int blocksProduced;
        private final int slotLengthMillis;
        private boolean running;
        private int starts;
        private int stops;
        private long targetSlot;
        private RuntimeException produceFailure;

        private FakeDevnetProduction(InMemoryChainState chainState, int blocksProduced, int slotLengthMillis) {
            this.chainState = chainState;
            this.blocksProduced = blocksProduced;
            this.slotLengthMillis = slotLengthMillis;
        }

        @Override
        public ProducerMode mode() {
            return ProducerMode.DEVNET;
        }

        @Override
        public void start() {
            starts++;
            running = true;
        }

        @Override
        public void stop() {
            stops++;
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void resetToChainTip() {
        }

        @Override
        public boolean supportsEmptyBlockProduction() {
            return true;
        }

        @Override
        public int produceEmptyBlocksToSlot(long targetSlot) {
            if (produceFailure != null) {
                throw produceFailure;
            }
            this.targetSlot = targetSlot;
            long blockNumber = chainState.getTip() != null ? chainState.getTip().getBlockNumber() : 0;
            storeTip(chainState, targetSlot, blockNumber + blocksProduced);
            return blocksProduced;
        }

        @Override
        public int slotLengthMillis() {
            return slotLengthMillis;
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
