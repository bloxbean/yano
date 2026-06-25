package com.bloxbean.cardano.yano.tx;

import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisParser;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
                        DefaultTransactionServicesFactory::create)
                .build();

        try {
            assertTrue(node.txEvaluationGateway().isTransactionEvaluationAvailable());
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

    private static Path testPath(String path) {
        Path rootPath = Path.of(path);
        if (Files.exists(rootPath)) {
            return rootPath;
        }
        return Path.of("..").resolve(path);
    }
}
