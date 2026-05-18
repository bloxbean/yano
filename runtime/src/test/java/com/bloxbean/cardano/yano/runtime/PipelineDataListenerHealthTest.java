package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.peer.PeerHealth;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionCallbacks;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionStatus;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PipelineDataListenerHealthTest {
    private InMemoryChainState chainState;
    private PeerHealth peerHealth;
    private PipelineDataListener listener;

    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();
        MockPeerClient peerClient = new MockPeerClient();
        SyncTipContext syncTipContext = new SyncTipContext();
        HeaderSyncManager headerSyncManager = new HeaderSyncManager(peerClient, chainState, 50000, syncTipContext);
        peerHealth = new PeerHealth("relay-1", System.currentTimeMillis());
        BodyFetchManager bodyFetchManager = new BodyFetchManager(
                peerClient,
                chainState,
                new SimpleEventBus(),
                3,
                5,
                100,
                1000,
                syncTipContext);
        bodyFetchManager.setPeerHealth(peerHealth);
        listener = new PipelineDataListener(headerSyncManager, bodyFetchManager, new NoopCallbacks(), peerHealth);
    }

    @Test
    void rollforwardRecordsHeaderProgress() {
        listener.rollforward(
                new Tip(new Point(1000, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"), 500L),
                createSimpleShelleyHeader(1000L, 500L, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"),
                "header".getBytes());

        PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
        assertEquals(1000L, status.lastHeaderSlot());
        assertEquals(500L, status.lastHeaderBlockNumber());
        assertTrue(status.lastHeaderReceivedAtMillis() > 0);
    }

    @Test
    void blockProcessingRecordsBodyReceivedAndAppliedProgress() {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );

        Block block = createTestBlock(
                1001L,
                501L,
                "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        List<Transaction> transactions = Collections.emptyList();

        listener.onBlock(Era.Shelley, block, transactions);

        PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
        assertEquals(1001L, status.lastBodyReceivedSlot());
        assertEquals(501L, status.lastBodyReceivedBlockNumber());
        assertEquals(1001L, status.lastBodyAppliedSlot());
        assertEquals(501L, status.lastBodyAppliedBlockNumber());
        assertTrue(status.lastBodyReceivedAtMillis() > 0);
        assertTrue(status.lastBodyAppliedAtMillis() > 0);

        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        assertEquals(1001L, tip.getSlot());
        assertEquals(501L, tip.getBlockNumber());
    }

    @Test
    void failedBlockApplicationDoesNotRecordBodyAppliedProgress() {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );

        Block block = createTestBlockWithoutCbor(
                1001L,
                501L,
                "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

        assertThrows(RuntimeException.class,
                () -> listener.onBlock(Era.Shelley, block, Collections.emptyList()));

        PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
        assertEquals(1001L, status.lastBodyReceivedSlot());
        assertEquals(501L, status.lastBodyReceivedBlockNumber());
        assertEquals(-1L, status.lastBodyAppliedSlot());
        assertEquals(-1L, status.lastBodyAppliedBlockNumber());
        assertTrue(status.lastBodyReceivedAtMillis() > 0);
        assertEquals(0L, status.lastBodyAppliedAtMillis());
    }

    @Test
    void batchAndDisconnectUpdateHealth() {
        listener.batchStarted();

        PeerSessionStatus started = peerHealth.snapshot(System.currentTimeMillis());
        assertTrue(started.bodyFetchInProgress());
        assertTrue(started.bodyFetchStartedAtMillis() > 0);

        listener.noBlockFound(new Point(10, "from"), new Point(11, "to"));
        PeerSessionStatus noBlockFound = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(noBlockFound.bodyFetchInProgress());

        listener.batchStarted();
        listener.batchDone();
        PeerSessionStatus done = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(done.bodyFetchInProgress());

        listener.batchStarted();
        listener.onRollback(new Point(12, "rollback"));
        PeerSessionStatus rolledBack = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(rolledBack.bodyFetchInProgress());

        listener.batchStarted();
        listener.onDisconnect();
        PeerSessionStatus disconnected = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(disconnected.bodyFetchInProgress());
        assertTrue(disconnected.lastDisconnectAtMillis() > 0);
    }

    private BlockHeader createSimpleShelleyHeader(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(blockNumber)
                .blockHash(hash)
                .build();

        return BlockHeader.builder()
                .headerBody(headerBody)
                .build();
    }

    private Block createTestBlock(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(blockNumber)
                .blockHash(hash)
                .build();

        BlockHeader header = BlockHeader.builder()
                .headerBody(headerBody)
                .build();

        return Block.builder()
                .header(header)
                .cbor("deadbeefabcd1234")
                .build();
    }

    private Block createTestBlockWithoutCbor(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(blockNumber)
                .blockHash(hash)
                .build();

        BlockHeader header = BlockHeader.builder()
                .headerBody(headerBody)
                .build();

        return Block.builder()
                .header(header)
                .build();
    }

    private byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static class NoopCallbacks implements PeerSessionCallbacks {
        @Override
        public void resumeBodyFetchOnHeaderFlow() {
        }

        @Override
        public void updateSyncProgress() {
        }

        @Override
        public void notifyServerNewBlockStored() {
        }

        @Override
        public void onIntersectionFound() {
        }

        @Override
        public void maybeFastTransitionToSteadyState(Tip remoteTip) {
        }

        @Override
        public void handleChainSyncRollback(Point point) {
        }
    }

    private static class MockPeerClient extends PeerClient {
        MockPeerClient() {
            super("mock-host", 3001, 1, null);
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
