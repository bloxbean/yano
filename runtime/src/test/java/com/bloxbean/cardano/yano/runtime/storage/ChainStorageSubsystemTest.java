package com.bloxbean.cardano.yano.runtime.storage;

import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateSnapshots;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainStorageSubsystemTest {
    @TempDir
    Path tempDir;

    @Test
    void createsInMemoryStorageWhenRocksDbDisabled() {
        ChainStorageSubsystem storage = new ChainStorageSubsystem(
                YanoConfig.serverOnly(0),
                RuntimeOptions.defaults(),
                LoggerFactory.getLogger(getClass()));

        try {
            assertThat(storage.chainState()).isInstanceOf(InMemoryChainState.class);
            assertThat(storage.rocksDbSupplierOrNull()).isNull();
            assertThat(storage.rocksDbAccessOrNull()).isNull();
            assertThat(storage.isBlockPruneServiceRunning()).isFalse();
        } finally {
            storage.close();
        }
    }

    @Test
    void createsRocksDbStorageAndExposesCapabilities() {
        ChainStorageSubsystem storage = new ChainStorageSubsystem(
                rocksConfig(tempDir.resolve("chainstate")),
                RuntimeOptions.defaults(),
                LoggerFactory.getLogger(getClass()));

        try {
            assertThat(storage.chainState()).isInstanceOf(DirectRocksDBChainState.class);
            assertThat(storage.rocksDbSupplierOrNull()).isInstanceOf(RocksDbSupplier.class);
            assertThat(storage.rocksDbAccessOrNull()).isNotNull();
            assertThat(storage.snapshotsOrThrow()).isInstanceOf(ChainStateSnapshots.class);
        } finally {
            storage.close();
        }
    }

    @Test
    void blockPruneLifecycleIsIdempotent() {
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.chain.block-body-prune-depth", 10,
                "yano.chain.block-prune-batch-size", 5,
                "yano.chain.block-prune-interval-seconds", 60));
        ChainStorageSubsystem storage = new ChainStorageSubsystem(
                rocksConfig(tempDir.resolve("prune-chainstate")),
                options,
                LoggerFactory.getLogger(getClass()));

        try {
            storage.startBlockPruneService();
            storage.startBlockPruneService();
            assertThat(storage.isBlockPruneServiceRunning()).isTrue();

            storage.stopBlockPruneService();
            storage.stopBlockPruneService();
            assertThat(storage.isBlockPruneServiceRunning()).isFalse();
        } finally {
            storage.close();
        }
    }

    @Test
    void healthIsDegradedWhenMaintenanceGateIsDegraded() {
        ChainStorageSubsystem storage = new ChainStorageSubsystem(
                YanoConfig.serverOnly(0),
                RuntimeOptions.defaults(),
                LoggerFactory.getLogger(getClass()));

        try {
            storage.maintenanceGate().markDegraded("restore", "runtime restart required", null);

            assertThat(storage.health().status()).isEqualTo(SubsystemHealth.Status.DEGRADED);
            assertThat(storage.health().message()).isEqualTo("runtime restart required");
        } finally {
            storage.close();
        }
    }

    private static YanoConfig rocksConfig(Path path) {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(path.toString());
        return config;
    }
}
