package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.producer.BlockProduction;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;
import com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevnetCatchUpServiceTest {
    @Test
    void devnetCatchUpStopsProducerProducesToWallClockSlotAndRestarts() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeTip(chainState, 10, 3);
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeProduction production = new FakeProduction(chainState, ProducerMode.DEVNET, 2, 10);
        producerSubsystem.install(production);
        producerSubsystem.start();

        var result = service(chainState, producerSubsystem, false, 1_000, 1_000, 16_000)
                .catchUpToWallClock();

        assertEquals(15, production.emptyTargetSlot);
        assertEquals(15, result.newSlot());
        assertEquals(5, result.newBlockNumber());
        assertEquals(2, result.blocksProduced());
        assertFalse(production.lastForceSequentialSlots);
        assertTrue(producerSubsystem.isRunning());
        assertEquals(1, production.stops);
        assertEquals(2, production.starts);
    }

    @Test
    void devnetCatchUpReturnsCurrentTipWhenAlreadyAtWallClockSlot() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeTip(chainState, 20, 3);
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeProduction production = new FakeProduction(chainState, ProducerMode.DEVNET, 2, 20);
        producerSubsystem.install(production);

        var result = service(chainState, producerSubsystem, false, 1_000, 1_000, 16_000)
                .catchUpToWallClock();

        assertEquals(20, result.newSlot());
        assertEquals(3, result.newBlockNumber());
        assertEquals(0, result.blocksProduced());
        assertEquals(-1, production.emptyTargetSlot);
        assertEquals(0, production.stops);
    }

    @Test
    void slotLeaderCatchUpProducesLeaderBlocksToWallClockSlot() {
        InMemoryChainState chainState = new InMemoryChainState();
        storeTip(chainState, 9, 4);
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeProduction production = new FakeProduction(
                chainState,
                ProducerMode.SLOT_LEADER_TIME_TRAVEL,
                3,
                10);
        producerSubsystem.install(production);
        producerSubsystem.start();

        var result = service(chainState, producerSubsystem, true, 1_000, 1_000, 16_000)
                .catchUpToWallClock();

        assertEquals(15, production.leaderTargetSlot);
        assertEquals(15, production.lastCheckedSlot);
        assertEquals(15, result.newSlot());
        assertEquals(7, result.newBlockNumber());
        assertEquals(3, result.blocksProduced());
        assertFalse(production.lastForceSequentialSlots);
        assertTrue(producerSubsystem.isRunning());
        assertEquals(1, production.stops);
        assertEquals(2, production.starts);
    }

    @Test
    void slotLeaderCatchUpWithoutTipReturnsLastCheckedSlotWhenAlreadyCaughtUp() {
        InMemoryChainState chainState = new InMemoryChainState();
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        FakeProduction production = new FakeProduction(
                chainState,
                ProducerMode.SLOT_LEADER_TIME_TRAVEL,
                3,
                20);
        producerSubsystem.install(production);

        var result = service(chainState, producerSubsystem, true, 1_000, 1_000, 16_000)
                .catchUpToWallClock();

        assertEquals(20, result.newSlot());
        assertEquals(0, result.newBlockNumber());
        assertEquals(0, result.blocksProduced());
        assertEquals(-1, production.leaderTargetSlot);
    }

    @Test
    void requiresDevModeAndExpectedProducerMode() {
        InMemoryChainState chainState = new InMemoryChainState();
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();

        assertEquals("Catch-up requires dev mode",
                assertThrows(IllegalStateException.class,
                        () -> service(chainState, producerSubsystem, false, false, 1_000, 1_000, 16_000)
                                .catchUpToWallClock()).getMessage());

        assertEquals("Catch-up requires block producer to be running",
                assertThrows(IllegalStateException.class,
                        () -> service(chainState, producerSubsystem, false, 1_000, 1_000, 16_000)
                                .catchUpToWallClock()).getMessage());

        ProducerSubsystem wrongProducer = new ProducerSubsystem();
        wrongProducer.install(new FakeProduction(chainState, ProducerMode.DEVNET, 1, 0));
        assertEquals("Catch-up requires past-time-travel slot-leader producer to be running",
                assertThrows(IllegalStateException.class,
                        () -> service(chainState, wrongProducer, true, 1_000, 1_000, 16_000)
                                .catchUpToWallClock()).getMessage());
    }

    private static DevnetCatchUpService service(InMemoryChainState chainState,
                                                ProducerSubsystem producerSubsystem,
                                                boolean slotLeaderMode,
                                                long resolvedGenesisTimestamp,
                                                int slotLengthMillis,
                                                long currentTimeMillis) {
        return service(chainState, producerSubsystem, true, slotLeaderMode,
                resolvedGenesisTimestamp, slotLengthMillis, currentTimeMillis);
    }

    private static DevnetCatchUpService service(InMemoryChainState chainState,
                                                ProducerSubsystem producerSubsystem,
                                                boolean devMode,
                                                boolean slotLeaderMode,
                                                long resolvedGenesisTimestamp,
                                                int slotLengthMillis,
                                                long currentTimeMillis) {
        return new DevnetCatchUpService(
                () -> devMode,
                () -> slotLeaderMode,
                chainState,
                producerSubsystem,
                () -> resolvedGenesisTimestamp,
                () -> slotLengthMillis,
                () -> currentTimeMillis);
    }

    private static void storeTip(InMemoryChainState chainState, long slot, long blockNumber) {
        chainState.storeBlock(hash(slot), blockNumber, slot, new byte[]{1});
    }

    private static byte[] hash(long value) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) value);
        return hash;
    }

    private static final class FakeProduction implements BlockProduction {
        private final InMemoryChainState chainState;
        private final ProducerMode mode;
        private final int blocksProduced;
        private boolean running;
        private int starts;
        private int stops;
        private long lastCheckedSlot;
        private long emptyTargetSlot = -1;
        private long leaderTargetSlot = -1;
        private boolean lastForceSequentialSlots = true;

        private FakeProduction(InMemoryChainState chainState,
                               ProducerMode mode,
                               int blocksProduced,
                               long lastCheckedSlot) {
            this.chainState = chainState;
            this.mode = mode;
            this.blocksProduced = blocksProduced;
            this.lastCheckedSlot = lastCheckedSlot;
        }

        @Override
        public ProducerMode mode() {
            return mode;
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
            return mode == ProducerMode.DEVNET || mode == ProducerMode.DEVNET_TIME_TRAVEL;
        }

        @Override
        public int produceEmptyBlocksToSlot(long targetSlot) {
            emptyTargetSlot = targetSlot;
            long blockNumber = chainState.getTip() != null ? chainState.getTip().getBlockNumber() : 0;
            storeTip(chainState, targetSlot, blockNumber + blocksProduced);
            return blocksProduced;
        }

        @Override
        public boolean supportsLeaderTimeTravel() {
            return mode == ProducerMode.SLOT_LEADER_TIME_TRAVEL;
        }

        @Override
        public int produceLeaderBlocksToSlot(long targetSlot) {
            leaderTargetSlot = targetSlot;
            lastCheckedSlot = targetSlot;
            long blockNumber = chainState.getTip() != null ? chainState.getTip().getBlockNumber() : 0;
            storeTip(chainState, targetSlot, blockNumber + blocksProduced);
            return blocksProduced;
        }

        @Override
        public long lastCheckedSlot() {
            return lastCheckedSlot;
        }

        @Override
        public void setForceSequentialSlots(boolean forceSequentialSlots) {
            lastForceSequentialSlots = forceSequentialSlots;
        }
    }
}
