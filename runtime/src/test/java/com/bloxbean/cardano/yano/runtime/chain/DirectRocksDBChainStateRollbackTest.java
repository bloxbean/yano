package com.bloxbean.cardano.yano.runtime.chain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
