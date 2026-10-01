package org.yanoproject.tx;

import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.runtime.assembly.YanoAssembly;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.config.InMemoryDevnetGenesis;
import org.yanoproject.runtime.genesis.ShelleyGenesisParser;
import org.yanoproject.runtime.tx.TransactionServices;
import org.yanoproject.runtime.tx.TransactionBootstrapOptions;
import org.yanoproject.runtime.validation.ValidationEngineConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scalus.cardano.ledger.SlotConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTransactionServicesFactoryIntegrationTest {

    /**
     * ADR-056 Phase 7c: with transaction validation enabled, a validator that cannot be built stops startup instead
     * of leaving the node admitting every transaction unvalidated (runtime assembly lets this exception through).
     */
    @Test
    void aValidatorThatCannotBeBuiltStopsStartup() {
        IllegalStateException cause = new IllegalStateException("no validator in this image");
        ValidationEngineConfigurationException e = assertThrows(ValidationEngineConfigurationException.class,
                () -> DefaultTransactionServicesFactory.requireValidator(() -> {
                    throw cause;
                }));
        assertSame(cause, e.getCause());
        assertTrue(e.getMessage().contains("no validator in this image"));
    }

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

    /** {@code engine: scalus} (legacy): an invalid slot config leaves the node without transaction services. */
    @Test
    void invalidBootstrapSlotConfigDoesNotInstallTransactionServices(@TempDir Path tempDir) {
        Yano node = invalidSlotConfigNode(tempDir, Map.of("yano.validation.engine", "scalus"));
        try {
            assertFalse(node.txEvaluationGateway().isTransactionEvaluationAvailable());
        } finally {
            node.close();
        }
    }

    /** With the default engine ({@code java-julc}) the same configuration stops startup: no silent fallback. */
    @Test
    void invalidBootstrapSlotConfigStopsStartupWithTheDefaultEngine(@TempDir Path tempDir) {
        ValidationEngineConfigurationException error = assertThrows(ValidationEngineConfigurationException.class,
                () -> invalidSlotConfigNode(tempDir, Map.of()).close());
        assertTrue(error.getMessage().contains("transaction validation cannot be initialized"), error.getMessage());
    }

    /**
     * No protocol-parameter source (no protocol-param file, no derived ledger state, and a genesis without a valid
     * protocol version): the legacy engine runs without transaction services, the default stops startup.
     */
    @Test
    void noProtocolParamsSourceStopsStartupWithTheDefaultEngine(@TempDir Path tempDir) throws Exception {
        Path shelley = tempDir.resolve("shelley-genesis.json");
        Files.writeString(shelley, Files.readString(testPath("app/config/network/devnet/shelley-genesis.json"))
                .replaceFirst("\"major\" : 11", "\"major\" : 0"));
        Yano legacy = noProtocolParamsNode(tempDir.resolve("legacy-chainstate"), shelley,
                Map.of("yano.validation.engine", "scalus"));
        try {
            assertFalse(legacy.txEvaluationGateway().isTransactionEvaluationAvailable());
        } finally {
            legacy.close();
        }
        ValidationEngineConfigurationException error = assertThrows(ValidationEngineConfigurationException.class,
                () -> noProtocolParamsNode(tempDir.resolve("default-chainstate"), shelley, Map.of()).close());
        assertTrue(error.getMessage().contains("no protocol params source available"), error.getMessage());
    }

    /** Engine-API admission needs the UTxO store for its ledger-state mempool: startup names the ways out. */
    @Test
    void theDefaultEngineWithoutTheUtxoStoreStopsStartup(@TempDir Path tempDir) {
        YanoConfig config = devnetConfig(tempDir.resolve("chainstate"));
        ValidationEngineConfigurationException error = assertThrows(ValidationEngineConfigurationException.class,
                () -> node(config, Map.of("yano.utxo.enabled", false)).close());
        assertTrue(error.getMessage().contains("'java-julc' needs the UTxO store"), error.getMessage());
        assertTrue(error.getMessage().contains("yano.utxo.enabled=false or yano.storage.rocksdb=false"),
                error.getMessage());
        assertTrue(error.getMessage().contains("yano.validation.engine=scalus"), error.getMessage());
        assertTrue(error.getMessage().contains("yano.block-producer.tx-evaluation=false"), error.getMessage());

        Yano legacy = node(devnetConfig(tempDir.resolve("legacy-chainstate")), Map.of("yano.utxo.enabled", false,
                "yano.validation.engine", "scalus"));
        legacy.close();
    }

    private static Yano noProtocolParamsNode(Path chainstate, Path shelley, Map<String, Object> validation) {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(chainstate.toString());
        config.setProtocolMagic(42);
        config.setShelleyGenesisFile(shelley.toString());
        config.setByronGenesisFile(null);
        config.setAlonzoGenesisFile(null);
        config.setConwayGenesisFile(null);
        config.setProtocolParametersFile(null);
        return node(config, validation);
    }

    private static YanoConfig devnetConfig(Path chainstate) {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(chainstate.toString());
        config.setProtocolMagic(42);
        config.setShelleyGenesisFile(testPath("app/config/network/devnet/shelley-genesis.json").toString());
        config.setProtocolParametersFile(testPath("app/config/network/devnet/protocol-param.json").toString());
        return config;
    }

    private static Yano node(YanoConfig config, Map<String, Object> overrides) {
        Map<String, Object> globals = new HashMap<>(Map.of(
                "yano.utxo.enabled", true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.metrics.sample.rocksdb.seconds", 0,
                "yano.validation.default-validator-enabled", false));
        globals.putAll(overrides);
        return YanoAssembly.relay(config)
                .runtimeOptions(new RuntimeOptions(null, null, globals))
                .transactionBootstrap(
                        TransactionBootstrapOptions.enabled(false, false, "aiken"),
                        DefaultTransactionServicesFactory::create)
                .build();
    }

    private static Yano invalidSlotConfigNode(Path tempDir, Map<String, Object> validation) {
        YanoConfig config = devnetConfig(tempDir.resolve("chainstate"));
        config.setGenesisTimestamp(1_780_000_000L);
        return node(config, validation);
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
