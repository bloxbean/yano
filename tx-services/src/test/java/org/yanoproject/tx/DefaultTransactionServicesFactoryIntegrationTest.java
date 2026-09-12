package org.yanoproject.tx;

import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.ledgerrules.SlotConfigSupplier;
import org.yanoproject.runtime.assembly.YanoAssembly;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.config.InMemoryDevnetGenesis;
import org.yanoproject.runtime.genesis.ShelleyGenesisParser;
import org.yanoproject.runtime.tx.TransactionServices;
import org.yanoproject.runtime.tx.TransactionBootstrapOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scalus.cardano.ledger.SlotConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTransactionServicesFactoryIntegrationTest {

    @Test
    void assemblyWithRealBootstrapperInstallsScriptEvaluatorFromStaticProtocolParams(@TempDir Path tempDir) {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(tempDir.resolve("chainstate").toString());
        config.setProtocolMagic(42);
        config.setShelleyGenesisFile(testPath("app/config/network/devnet/shelley-genesis.json").toString());
        config.setProtocolParametersFile(testPath("app/config/network/devnet/protocol-param.json").toString());

        RuntimeOptions runtimeOptions = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.enabled", true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.metrics.sample.rocksdb.seconds", 0,
                "yano.validation.default-validator-enabled", false));

        Yano node = YanoAssembly.relay(config)
                .runtimeOptions(runtimeOptions)
                .transactionBootstrap(
                        TransactionBootstrapOptions.enabled(false, false, "aiken"),
                        DefaultTransactionServicesFactory::create)
                .build();

        try {
            assertTrue(node.txEvaluationGateway().isTransactionEvaluationAvailable());
        } finally {
            node.close();
        }
    }

    @Test
    void inMemoryDevnetAssemblyWithRealBootstrapperDoesNotRequireGenesisFiles(@TempDir Path tempDir) throws Exception {
        YanoConfig config = YanoConfig.devnetDefault(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(tempDir.resolve("chainstate").toString());
        config.setShelleyGenesisFile(null);
        config.setByronGenesisFile(null);
        config.setAlonzoGenesisFile(null);
        config.setConwayGenesisFile(null);
        config.setProtocolParametersFile(null);

        var shelley = ShelleyGenesisParser.parse(
                testPath("app/config/network/devnet/shelley-genesis.json").toFile());
        var protocolParameters = Files.readString(testPath("app/config/network/devnet/protocol-param.json"));
        var inMemoryGenesis = new InMemoryDevnetGenesis(shelley, null, null, protocolParameters);
        var capturedServices = new AtomicReference<TransactionServices>();

        RuntimeOptions runtimeOptions = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.enabled", true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.metrics.sample.rocksdb.seconds", 0,
                "yano.validation.default-validator-enabled", false));

        Yano node = YanoAssembly.devnet(config)
                .inMemoryGenesis(inMemoryGenesis)
                .runtimeOptions(runtimeOptions)
                .transactionBootstrap(
                        TransactionBootstrapOptions.enabled(false, false, "aiken"),
                        (context, options) -> {
                            var services = DefaultTransactionServicesFactory.create(context, options);
                            services.ifPresent(capturedServices::set);
                            return services;
                        })
                .build();

        try {
            assertTrue(node.txEvaluationGateway().isTransactionEvaluationAvailable());
            assertNotNull(capturedServices.get());
            assertNotNull(capturedServices.get().validator());

            var field = capturedServices.get().validator().getClass().getDeclaredField("slotConfigSupplier");
            field.setAccessible(true);
            var slotConfigSupplier = (SlotConfigSupplier) field.get(capturedServices.get().validator());

            assertEquals(shelley.epochLength(), slotConfigSupplier.getEpochSlotCalc().shelleyEpochLength());
            assertEquals(0, slotConfigSupplier.getEpochSlotCalc().firstNonByronEpoch());
            assertEquals(0, slotConfigSupplier.getSlotConfig().getZeroSlot());
            assertEquals(Instant.parse(shelley.systemStart()).toEpochMilli(),
                    slotConfigSupplier.getSlotConfig().getZeroTime());
        } finally {
            node.close();
        }
    }

    @Test
    void invalidBootstrapSlotConfigDoesNotInstallTransactionServices(@TempDir Path tempDir) {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(tempDir.resolve("chainstate").toString());
        config.setProtocolMagic(42);
        config.setShelleyGenesisFile(testPath("app/config/network/devnet/shelley-genesis.json").toString());
        config.setProtocolParametersFile(testPath("app/config/network/devnet/protocol-param.json").toString());
        config.setGenesisTimestamp(1_780_000_000L);

        RuntimeOptions runtimeOptions = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.enabled", true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.metrics.sample.rocksdb.seconds", 0,
                "yano.validation.default-validator-enabled", false));

        Yano node = YanoAssembly.relay(config)
                .runtimeOptions(runtimeOptions)
                .transactionBootstrap(
                        TransactionBootstrapOptions.enabled(false, false, "aiken"),
                        DefaultTransactionServicesFactory::create)
                .build();

        try {
            assertFalse(node.txEvaluationGateway().isTransactionEvaluationAvailable());
        } finally {
            node.close();
        }
    }

    @Test
    void publicNetworkFactoryWiresTransitionTimeIntoValidatorAndEveryEvaluator(@TempDir Path tempDir)
            throws Exception {
        for (String network : new String[]{"mainnet", "preprod"}) {
            boolean mainnet = network.equals("mainnet");
            long expectedTime = mainnet ? 1_596_059_091_000L : 1_655_769_600_000L;
            long expectedSlot = mainnet ? 4_492_800 : 86_400;
            long expectedEpoch = mainnet ? 208 : 4;
            for (String evaluator : new String[]{"scalus", "aiken", "julc"}) {
                var config = YanoConfig.serverOnly(0);
                config.setUseRocksDB(true);
                config.setRocksDBPath(tempDir.resolve(network + "-" + evaluator).toString());
                config.setProtocolMagic(mainnet ? 764_824_073 : 1);
                String genesisDirectory = "app/config/network/" + network + "/";
                config.setShelleyGenesisFile(testPath(genesisDirectory + "shelley-genesis.json").toString());
                config.setByronGenesisFile(testPath(genesisDirectory + "byron-genesis.json").toString());
                // Static parameters isolate slot geometry from effective-ledger availability.
                config.setProtocolParametersFile(testPath("app/config/network/devnet/protocol-param.json").toString());
                var captured = new AtomicReference<TransactionServices>();
                var node = YanoAssembly.relay(config)
                        .runtimeOptions(new RuntimeOptions(null, null, Map.of(
                                "yano.utxo.enabled", true,
                                "yano.metrics.sample.rocksdb.seconds", 0,
                                "yano.validation.default-validator-enabled", false)))
                        .transactionBootstrap(TransactionBootstrapOptions.enabled(false, false, evaluator),
                                (context, options) -> {
                                    var services = DefaultTransactionServicesFactory.create(context, options);
                                    services.ifPresent(captured::set);
                                    return services;
                                }).build();
                try {
                    var services = captured.get();
                    assertNotNull(services, network + "/" + evaluator);
                    assertNotNull(services.validator());
                    assertNotNull(services.scriptEvaluator());
                    // Inspect the actual configuration handed to LedgerBridge, after conversion.
                    var resolve = services.validator().getClass().getDeclaredMethod("resolveScalusSlotConfig");
                    resolve.setAccessible(true);
                    var scalusConfig = (SlotConfig) resolve.invoke(services.validator());
                    assertEquals(expectedEpoch, scalusConfig.epochOf(expectedSlot));
                    assertEquals(expectedTime, scalusConfig.slotToTime(expectedSlot));
                    assertEquals(expectedTime + 123_000, scalusConfig.slotToTime(expectedSlot + 123));

                    var field = services.scriptEvaluator().getClass().getDeclaredField("slotConfigSupplier");
                    field.setAccessible(true);
                    var supplier = (SlotConfigSupplier) field.get(services.scriptEvaluator());
                    assertEquals(expectedSlot, supplier.getSlotConfig().getZeroSlot());
                    assertEquals(expectedTime, supplier.getSlotConfig().getZeroTime());
                    assertEquals(1_000, supplier.getSlotConfig().getSlotLength());
                } finally {
                    node.close();
                }
            }
        }
    }

    private static Path testPath(String path) {
        Path rootPath = Path.of(path);
        if (Files.exists(rootPath)) {
            return rootPath;
        }
        return Path.of("..").resolve(path);
    }
}
