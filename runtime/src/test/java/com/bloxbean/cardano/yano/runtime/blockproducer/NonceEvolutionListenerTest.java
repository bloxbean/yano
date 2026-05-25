package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NonceEvolutionListenerTest {

    private static final byte[] GENESIS_HASH = bytes(32, 1);

    @Test
    void storesEpochNonceOnEpochTransitionAndPrunesOnRollback() {
        EpochNonceState state = new EpochNonceState(10, 1, 1.0);
        state.initFromGenesisHash(GENESIS_HASH);
        TestNonceStore store = new TestNonceStore();
        NonceEvolutionListener listener = new NonceEvolutionListener(state, store, null,
                null, false, 0L,
                (slot, hashHex, serialized) -> NonceStateSnapshot.of(slot, 1L, hashHex, serialized),
                null);

        listener.onBlockApplied(blockEvent(0, null, 2));
        listener.onBlockApplied(blockEvent(10, "aa".repeat(32), 3));

        assertThat(state.getCurrentEpoch()).isEqualTo(1);
        assertThat(store.getEpochNonce(1)).isEqualTo(state.getEpochNonce());

        listener.onRollback(new RollbackEvent(new Point(0, "bb".repeat(32)), true));

        assertThat(state.getCurrentEpoch()).isEqualTo(0);
        assertThat(store.getEpochNonce(1)).isNull();
    }

    @Test
    void rollbackWithoutInMemoryCheckpointRepairsFromDurableEpochCheckpoint() {
        InMemoryChainState chain = new InMemoryChainState();
        var blocks = buildBlocks(0, 1);
        blocks.forEach(block -> chain.storeBlock(block.blockHash(), block.blockNumber(), block.slot(), block.blockCbor()));

        EpochNonceState durableState = new EpochNonceState(10, 1, 1.0);
        durableState.initFromGenesisHash(GENESIS_HASH);
        NonceStateSnapshot block0Snapshot = evolveAndSnapshot(durableState, blocks.get(0));
        chain.storeEpochNonceCheckpoint(0, block0Snapshot);
        NonceStateSnapshot block1Snapshot = evolveAndSnapshot(durableState, blocks.get(1));
        chain.storeLatestNonceSnapshot(block1Snapshot);

        chain.rollbackTo(0L);

        EpochNonceState listenerState = new EpochNonceState(10, 1, 1.0);
        NonceReplayService replayService = new NonceReplayService(
                chain, chain, new EpochNonceEvolver(null, true, 0L), GENESIS_HASH);
        NonceEvolutionListener listener = new NonceEvolutionListener(
                listenerState, chain, null, null, true, 0L,
                (slot, hashHex, serialized) -> new NonceStateSnapshot(
                        chain.getTip().getSlot(),
                        chain.getTip().getBlockNumber(),
                        chain.getTip().getBlockHash(),
                        serialized),
                replayService);

        listener.onRollback(new RollbackEvent(new Point(0L, block0Snapshot.blockHashHex()), true));

        assertThat(listenerState.serialize()).isEqualTo(block0Snapshot.nonceState());
        assertThat(chain.getLatestNonceSnapshot().orElseThrow().blockNumber()).isEqualTo(0L);
    }

    @Test
    void observedBlockDoesNotCheckpointStaleProducerPendingMarker() {
        EpochNonceState state = new EpochNonceState(10, 1, 1.0);
        state.initFromGenesisHash(GENESIS_HASH);
        TestNonceStore store = new TestNonceStore();
        NonceEvolutionListener listener = new NonceEvolutionListener(state, store, null,
                null, false, 0L,
                (slot, hashHex, serialized) -> NonceStateSnapshot.of(slot, slot + 1, hashHex, serialized),
                null);

        // Simulates an older producer leader-check path that advanced the shared nonce state
        // at an epoch boundary but did not produce a block. Body replay must clear this marker
        // without storing a durable epoch checkpoint at the old-epoch block cursor.
        state.advanceEpochIfNeeded(10);

        listener.onBlockApplied(blockEvent(9, "aa".repeat(32), 4));

        assertThat(store.checkpointWrites).isZero();
        assertThat(store.getEpochNonce(1)).isNull();
    }

    private static BlockAppliedEvent blockEvent(long slot, String prevHash, int vrfSeed) {
        var vrf = VrfCert.builder()
                ._1(HexUtil.encodeHexString(bytes(64, vrfSeed)))
                .build();
        var headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(slot + 1)
                .prevHash(prevHash)
                .vrfResult(vrf)
                .build();
        var header = BlockHeader.builder()
                .headerBody(headerBody)
                .build();
        var block = Block.builder()
                .era(Era.Babbage)
                .header(header)
                .build();
        return new BlockAppliedEvent(Era.Babbage, slot, slot + 1, "cc".repeat(32), block);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private static List<DevnetBlockBuilder.BlockBuildResult> buildBlocks(long... blockNumbers) {
        DevnetBlockBuilder builder = new DevnetBlockBuilder();
        java.util.ArrayList<DevnetBlockBuilder.BlockBuildResult> blocks = new java.util.ArrayList<>();
        byte[] prevHash = null;
        for (long blockNumber : blockNumbers) {
            var block = builder.buildBlock(blockNumber, blockNumber, prevHash, List.of());
            blocks.add(block);
            prevHash = block.blockHash();
        }
        return blocks;
    }

    private static NonceStateSnapshot evolveAndSnapshot(EpochNonceState state,
                                                        DevnetBlockBuilder.BlockBuildResult result) {
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        HeaderBody hb = block.getHeader().getHeaderBody();
        var applied = new EpochNonceEvolver(null, true, 0L).evolve(state, Era.Conway, hb.getSlot(), hb);
        assertThat(applied.applied()).isTrue();
        byte[] serialized = state.serialize();
        state.saveCheckpoint(hb.getSlot(), serialized);
        return NonceStateSnapshot.of(hb.getSlot(), hb.getBlockNumber(), hb.getBlockHash(), serialized);
    }

    private static final class TestNonceStore implements NonceStateStore {
        private byte[] state;
        private final Map<Integer, byte[]> nonces = new HashMap<>();

        @Override
        public void storeEpochNonceState(byte[] serialized) {
            this.state = serialized;
        }

        @Override
        public byte[] getEpochNonceState() {
            return state;
        }

        @Override
        public void storeEpochNonce(int epoch, byte[] nonce) {
            nonces.put(epoch, nonce != null ? nonce.clone() : null);
        }

        @Override
        public byte[] getEpochNonce(int epoch) {
            byte[] nonce = nonces.get(epoch);
            return nonce != null ? nonce.clone() : null;
        }

        int checkpointWrites;

        @Override
        public void storeEpochNonceCheckpoint(int epoch, NonceStateSnapshot snapshot) {
            checkpointWrites++;
        }

        @Override
        public void pruneEpochNoncesAfter(int epoch) {
            nonces.keySet().removeIf(storedEpoch -> storedEpoch > epoch);
        }
    }
}
