package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DirectRocksDBChainStateTuningTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(YanoPropertyKeys.RocksDb.WRITE_BUFFER_ALLOW_STALL);
        System.clearProperty(YanoPropertyKeys.RocksDb.BLOCK_CACHE_BYTES);
        System.clearProperty(YanoPropertyKeys.RocksDb.WRITE_BUFFER_BYTES);
        System.clearProperty(YanoPropertyKeys.RESOURCE_PROFILE);
    }

    @Test
    void writeBufferManagerDoesNotHardStallByDefault() {
        try (DirectRocksDBChainState chainState =
                     new DirectRocksDBChainState(tempDir.resolve("default").toString())) {
            assertThat(chainState.isWriteBufferStallEnabled()).isFalse();
        }
    }

    @Test
    void writeBufferManagerHardStallCanBeEnabledExplicitly() {
        System.setProperty(YanoPropertyKeys.RocksDb.WRITE_BUFFER_ALLOW_STALL, "true");

        try (DirectRocksDBChainState chainState =
                     new DirectRocksDBChainState(tempDir.resolve("hard-stall").toString())) {
            assertThat(chainState.isWriteBufferStallEnabled()).isTrue();
        }
    }

    @Test
    void sharedCacheIncludesBlockAndWriteBufferBudgets() {
        System.setProperty(YanoPropertyKeys.RocksDb.BLOCK_CACHE_BYTES, "8388608");
        System.setProperty(YanoPropertyKeys.RocksDb.WRITE_BUFFER_BYTES, "16777216");

        try (DirectRocksDBChainState chainState =
                     new DirectRocksDBChainState(tempDir.resolve("shared-cache").toString())) {
            assertThat(chainState.sharedBlockCacheCapacityBytes()).isEqualTo(25165824L);
        }
    }

    @Test
    void lowMemoryProfileUsesBoundedRocksDbDefaults() {
        System.setProperty(YanoPropertyKeys.RESOURCE_PROFILE, "low-memory");

        try (DirectRocksDBChainState chainState =
                     new DirectRocksDBChainState(tempDir.resolve("low-memory").toString())) {
            assertThat(chainState.sharedBlockCacheCapacityBytes()).isEqualTo(48L * 1024 * 1024);
            assertThat(chainState.isWriteBufferStallEnabled()).isTrue();
        }
    }

    @Test
    void explicitBudgetsOverrideLowMemoryProfile() {
        System.setProperty(YanoPropertyKeys.RESOURCE_PROFILE, "low-memory");
        System.setProperty(YanoPropertyKeys.RocksDb.BLOCK_CACHE_BYTES, "8388608");
        System.setProperty(YanoPropertyKeys.RocksDb.WRITE_BUFFER_BYTES, "16777216");

        try (DirectRocksDBChainState chainState =
                     new DirectRocksDBChainState(tempDir.resolve("low-memory-override").toString())) {
            assertThat(chainState.sharedBlockCacheCapacityBytes()).isEqualTo(24L * 1024 * 1024);
        }
    }
}
