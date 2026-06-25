package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.config.BootstrapOutpointConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoDevnetTestKitTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsOnToolkitAssembly() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(devnetConfig("toolkit"))) {
            assertNotNull(kit.devnet());
            assertNotNull(kit.lifecycle());
            assertNotNull(kit.chain());
            assertNotNull(kit.ledger());
            assertNotNull(kit.txGateway());
            assertNotNull(kit.txEvaluationGateway());
            assertNotNull(kit.producerControl());
            assertNotNull(kit.queries());
            assertNotNull(kit.await());
            assertNotNull(kit.wallets());
            assertNotNull(kit.faucet());
            assertNotNull(kit.snapshots());
            assertNotNull(kit.time());
            assertNotNull(kit.transactions());
            assertNotNull(kit.assertions());
        }
    }

    @Test
    void defaultTestConfigUsesTemporaryRocksDbStorageAndCleansUp() {
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder().build();
        Path storageRoot = config.storageRoot().orElseThrow();

        assertTrue(config.yanoConfig().isUseRocksDB());
        assertTrue(config.yanoConfig().getRocksDBPath().startsWith(storageRoot.toString()));
        assertTrue((Boolean) config.runtimeOptions().globals().get(YanoPropertyKeys.Utxo.ENABLED));
        assertTrue(config.yanoConfig().getServerPort() > 0);
        assertTrue(Files.exists(storageRoot));

        try (YanoDevnetTestKit ignored = YanoDevnetTestKit.devnet(config)) {
            assertTrue(Files.exists(storageRoot));
        }

        assertFalse(Files.exists(storageRoot));
    }

    @Test
    void inMemoryStorageIsExplicitOptIn() {
        try (YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
                .inMemoryStorage()
                .build()) {
            assertFalse(config.yanoConfig().isUseRocksDB());
            assertFalse(config.storageRoot().isPresent());
        }
    }

    @Test
    void builderDoesNotMutateCallerConfigOrPreviouslyBuiltConfigs() {
        YanoConfig callerConfig = YanoConfig.devnetDefault(12345);
        callerConfig.setRocksDBPath(tempDir.resolve("caller").toString());

        YanoDevnetTestConfig.Builder builder = YanoDevnetTestConfig.builder()
                .yanoConfig(callerConfig)
                .temporaryRocksDbStorage();

        YanoDevnetTestConfig first = builder.build();
        Path firstStorageRoot = first.storageRoot().orElseThrow();
        YanoDevnetTestConfig second = builder.inMemoryStorage().build();

        try {
            assertTrue(first.yanoConfig().isUseRocksDB());
            assertFalse(second.yanoConfig().isUseRocksDB());
            assertTrue(first.yanoConfig().getRocksDBPath().startsWith(firstStorageRoot.toString()));
            assertTrue(callerConfig.isUseRocksDB());
            assertTrue(callerConfig.getRocksDBPath().endsWith("caller"));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void builderCopiesBootstrapCollectionsAndElements() {
        YanoConfig callerConfig = YanoConfig.devnetDefault(12345);
        List<String> addresses = new ArrayList<>(List.of("addr1"));
        BootstrapOutpointConfig outpoint = BootstrapOutpointConfig.builder()
                .txHash("tx1")
                .outputIndex(0)
                .build();
        callerConfig.setBootstrapAddresses(addresses);
        callerConfig.setBootstrapUtxos(new ArrayList<>(List.of(outpoint)));

        YanoDevnetTestConfig built = YanoDevnetTestConfig.builder()
                .yanoConfig(callerConfig)
                .build();

        addresses.set(0, "changed");
        outpoint.setTxHash("changed");

        try {
            assertTrue(built.yanoConfig().getBootstrapAddresses().contains("addr1"));
            assertTrue(built.yanoConfig().getBootstrapUtxos().stream()
                    .anyMatch(config -> "tx1".equals(config.getTxHash())));
        } finally {
            built.close();
        }
    }

    @Test
    void cleansTemporaryStorageWhenAssemblyFails() {
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder().build();
        Path storageRoot = config.storageRoot().orElseThrow();
        config.yanoConfig().setDevMode(false);

        assertThrows(IllegalStateException.class, () -> YanoDevnetTestKit.devnet(config));
        assertFalse(Files.exists(storageRoot));
    }

    @Test
    void rejectsRuntimeOnlyNodeWithoutDevnetControl() {
        var node = YanoAssembly.devnet(devnetConfig("runtime-only")).build();
        try {
            assertThrows(IllegalArgumentException.class, () -> YanoDevnetTestKit.from(node));
        } finally {
            node.close();
        }
    }

    private YanoConfig devnetConfig(String name) {
        YanoConfig config = YanoConfig.devnetDefault(0);
        config.setRocksDBPath(tempDir.resolve(name).toString());
        return config;
    }
}
