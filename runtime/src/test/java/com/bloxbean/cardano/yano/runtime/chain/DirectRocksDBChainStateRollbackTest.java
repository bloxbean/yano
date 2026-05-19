package com.bloxbean.cardano.yano.runtime.chain;

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
        assertThat(chainState.getEraStartSlot(2)).isEmpty();
    }

    private byte[] hash(int suffix) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) suffix;
        return bytes;
    }
}
