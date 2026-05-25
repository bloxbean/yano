package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonceReplayServiceTest {

    private static final byte[] GENESIS_HASH = bytes(32, 1);

    @Test
    void repairsForwardFromLatestSnapshotToBodyTip() {
        InMemoryChainState chain = new InMemoryChainState();
        var blocks = storeBlocks(chain, 0, 1);

        EpochNonceState source = newState();
        source.initFromGenesisHash(GENESIS_HASH);
        NonceStateSnapshot block0Snapshot = evolveAndSnapshot(source, blocks.get(0));
        chain.storeLatestNonceSnapshot(block0Snapshot);

        EpochNonceState repaired = newState();
        var result = newService(chain).repairToBodyTip(repaired, GENESIS_HASH, "test");

        EpochNonceState expected = newState();
        expected.initFromGenesisHash(GENESIS_HASH);
        evolveAndSnapshot(expected, blocks.get(0));
        evolveAndSnapshot(expected, blocks.get(1));

        assertThat(result.source()).isEqualTo("latest");
        assertThat(result.replayedBlocks()).isEqualTo(1);
        assertThat(repaired.serialize()).isEqualTo(expected.serialize());
        assertThat(chain.getLatestNonceSnapshot().orElseThrow().blockNumber()).isEqualTo(1L);
    }

    @Test
    void repairsFromEpochCheckpointWhenLatestSnapshotIsAheadOfBodyTip() {
        InMemoryChainState chain = new InMemoryChainState();
        var blocks = storeBlocks(chain, 0, 1);

        EpochNonceState checkpointState = newState();
        checkpointState.initFromGenesisHash(GENESIS_HASH);
        NonceStateSnapshot block0Snapshot = evolveAndSnapshot(checkpointState, blocks.get(0));
        chain.storeEpochNonceCheckpoint(0, block0Snapshot);
        chain.storeLatestNonceSnapshot(new NonceStateSnapshot(
                999L, 999L, bytes(32, 9), checkpointState.serialize()));

        EpochNonceState repaired = newState();
        var result = newService(chain).repairToBodyTip(repaired, GENESIS_HASH, "test");

        assertThat(result.source()).isEqualTo("epoch_checkpoint");
        assertThat(result.replayedBlocks()).isEqualTo(1);
        assertThat(chain.getLatestNonceSnapshot().orElseThrow().blockNumber()).isEqualTo(1L);
    }

    @Test
    void repairsFromEpochCheckpointWhenCheckpointBodyIsPrunedButHeaderRemains() {
        InMemoryChainState chain = new InMemoryChainState() {
            @Override
            public byte[] getBlockByNumber(Long number) {
                if (number == 0L) {
                    return null;
                }
                return super.getBlockByNumber(number);
            }
        };
        var blocks = storeBlocks(chain, 0, 1);

        EpochNonceState checkpointState = newState();
        checkpointState.initFromGenesisHash(GENESIS_HASH);
        NonceStateSnapshot block0Snapshot = evolveAndSnapshot(checkpointState, blocks.get(0));
        chain.storeEpochNonceCheckpoint(0, block0Snapshot);
        chain.storeLatestNonceSnapshot(new NonceStateSnapshot(
                999L, 999L, bytes(32, 9), checkpointState.serialize()));

        EpochNonceState repaired = newState();
        var result = newService(chain).repairToBodyTip(repaired, GENESIS_HASH, "test");

        assertThat(result.source()).isEqualTo("epoch_checkpoint");
        assertThat(result.replayedBlocks()).isEqualTo(1);
        assertThat(chain.getLatestNonceSnapshot().orElseThrow().blockNumber()).isEqualTo(1L);
    }

    @Test
    void missingRequiredBlockBodyFailsRepair() {
        InMemoryChainState chain = new InMemoryChainState();
        var blocks = buildBlocks(0, 2);
        storeBlock(chain, blocks.get(0));
        storeBlock(chain, blocks.get(1)); // block number 2, leaving block 1 missing

        EpochNonceState source = newState();
        source.initFromGenesisHash(GENESIS_HASH);
        chain.storeLatestNonceSnapshot(evolveAndSnapshot(source, blocks.get(0)));

        assertThatThrownBy(() -> newService(chain).repairToBodyTip(newState(), GENESIS_HASH, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing local block body 1");
    }

    @Test
    void exactBodyTipRestorePrunesFutureEpochNonceEntries() {
        InMemoryChainState chain = new InMemoryChainState();
        var blocks = storeBlocks(chain, 0);

        EpochNonceState source = newState();
        source.initFromGenesisHash(GENESIS_HASH);
        NonceStateSnapshot tipSnapshot = evolveAndSnapshot(source, blocks.getFirst());
        chain.storeLatestNonceSnapshot(tipSnapshot);
        chain.storeEpochNonce(9, bytes(32, 9));
        chain.storeEpochNonceCheckpoint(9, new NonceStateSnapshot(99L, 99L, bytes(32, 8), source.serialize()));

        EpochNonceState repaired = newState();
        var result = newService(chain).repairToBodyTip(repaired, GENESIS_HASH, "test");

        assertThat(result.source()).isEqualTo("latest");
        assertThat(chain.getEpochNonce(9)).isNull();
        assertThat(chain.getEpochNonceCheckpointsAtOrBeforeSlot(Long.MAX_VALUE))
                .noneMatch(snapshot -> snapshot.blockNumber() == 99L);
    }

    private static NonceReplayService newService(InMemoryChainState chain) {
        return new NonceReplayService(chain, chain, new EpochNonceEvolver(null, true, 0L), GENESIS_HASH);
    }

    private static EpochNonceState newState() {
        return new EpochNonceState(10, 1, 1.0);
    }

    private static List<DevnetBlockBuilder.BlockBuildResult> storeBlocks(InMemoryChainState chain,
                                                                          long... blockNumbers) {
        var blocks = buildBlocks(blockNumbers);
        blocks.forEach(block -> storeBlock(chain, block));
        return blocks;
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

    private static void storeBlock(InMemoryChainState chain, DevnetBlockBuilder.BlockBuildResult block) {
        chain.storeBlock(block.blockHash(), block.blockNumber(), block.slot(), block.blockCbor());
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

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }
}
