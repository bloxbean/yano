package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yano.runtime.blockproducer.NonceStateSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DirectRocksDBChainStateRollbackTest {

    @TempDir
    Path tempDir;

    private DirectRocksDBChainState chainState;

    @BeforeEach
    void setUp() {
        chainState = new DirectRocksDBChainState(tempDir.resolve("testdb").toString());
    }

    @AfterEach
    void tearDown() {
        if (chainState != null) {
            chainState.close();
        }
    }

    @Test
    void rollbackToOriginOnEmptyChainStateIsNoop() {
        assertThatCode(() -> chainState.rollbackTo(0L)).doesNotThrowAnyException();
    }

    @Test
    void rollbackToOriginClearsStoredTipsAndIndexes() {
        byte[] hash = hash(1);
        chainState.storeBlock(hash, 1L, 10L, new byte[]{1});
        chainState.storeBlockHeader(hash, 1L, 10L, new byte[]{1});
        chainState.storeEpochNonceState(new byte[]{9});
        chainState.storeEpochNonce(1, new byte[]{8});
        chainState.storeEpochNonceCheckpoint(1, new NonceStateSnapshot(10L, 1L, hash, new byte[]{7}));
        chainState.setEraStartSlot(2, 10L);

        chainState.rollbackToOrigin();

        assertThat(chainState.getTip()).isNull();
        assertThat(chainState.getHeaderTip()).isNull();
        assertThat(chainState.getBlock(hash)).isNull();
        assertThat(chainState.getBlockHeader(hash)).isNull();
        assertThat(chainState.getBlockByNumber(1L)).isNull();
        assertThat(chainState.getBlockHeaderByNumber(1L)).isNull();
        assertThat(chainState.getBlockNumberBySlot(10L)).isNull();
        assertThat(chainState.getSlotByBlockNumber(1L)).isNull();
        assertThat(chainState.getEpochNonceState()).isNull();
        assertThat(chainState.getEpochNonce(1)).isNull();
        assertThat(chainState.getEpochNonceCheckpointsAtOrBeforeSlot(10L)).isEmpty();
        assertThat(chainState.getEraStartSlot(2)).isEmpty();
    }

    @Test
    void epochNonceCheckpointsRoundTripOrderAndPrune() {
        NonceStateSnapshot checkpoint1 = new NonceStateSnapshot(10L, 1L, hash(1), new byte[]{1});
        NonceStateSnapshot checkpoint2 = new NonceStateSnapshot(20L, 2L, hash(2), new byte[]{2});
        NonceStateSnapshot checkpoint3 = new NonceStateSnapshot(30L, 3L, hash(3), new byte[]{3});

        chainState.storeLatestNonceSnapshot(checkpoint3);
        chainState.storeEpochNonceCheckpoint(1, checkpoint1);
        chainState.storeEpochNonceCheckpoint(2, checkpoint2);
        chainState.storeEpochNonceCheckpoint(3, checkpoint3);

        assertThat(chainState.getLatestNonceSnapshot().orElseThrow().blockNumber()).isEqualTo(3L);
        assertThat(chainState.getLatestNonceSnapshot().orElseThrow().nonceState()).isEqualTo(new byte[]{3});
        assertThat(chainState.getEpochNonceCheckpointsAtOrBeforeSlot(25L))
                .extracting(NonceStateSnapshot::blockNumber)
                .containsExactly(2L, 1L);

        chainState.close();
        chainState = new DirectRocksDBChainState(tempDir.resolve("testdb").toString());

        assertThat(chainState.getLatestNonceSnapshot().orElseThrow().blockNumber()).isEqualTo(3L);
        assertThat(chainState.getLatestNonceSnapshot().orElseThrow().nonceState()).isEqualTo(new byte[]{3});
        assertThat(chainState.getEpochNonceCheckpointsAtOrBeforeSlot(30L))
                .extracting(NonceStateSnapshot::blockNumber)
                .containsExactly(3L, 2L, 1L);

        chainState.pruneEpochNonceCheckpointsAfter(1);

        assertThat(chainState.getEpochNonceCheckpointsAtOrBeforeSlot(30L))
                .extracting(NonceStateSnapshot::blockNumber)
                .containsExactly(1L);
    }

    @Test
    void rollbackToBodyTipDiscardsHeaderOnlyCacheAndAllowsRefetch() {
        byte[] hash1 = hash(1);
        byte[] hash2 = hash(2);
        byte[] hash3 = hash(3);

        chainState.storeBlockHeader(hash1, 1L, 10L, new byte[]{1});
        chainState.storeBlock(hash1, 1L, 10L, new byte[]{1});
        chainState.storeBlockHeader(hash2, 2L, 20L, new byte[]{2});
        chainState.storeBlockHeader(hash3, 3L, 30L, new byte[]{3});

        chainState.rollbackTo(10L);

        assertThat(chainState.getTip().getBlockNumber()).isEqualTo(1L);
        assertThat(chainState.getHeaderTip().getBlockNumber()).isEqualTo(1L);
        assertThat(chainState.getBlockHeaderByNumber(2L)).isNull();
        assertThat(chainState.getBlockHeaderByNumber(3L)).isNull();

        assertThatCode(() -> chainState.storeBlockHeader(hash2, 2L, 20L, new byte[]{2}))
                .doesNotThrowAnyException();
        assertThat(chainState.getHeaderTip().getBlockNumber()).isEqualTo(2L);
    }

    private byte[] hash(int suffix) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) suffix;
        return bytes;
    }
}
