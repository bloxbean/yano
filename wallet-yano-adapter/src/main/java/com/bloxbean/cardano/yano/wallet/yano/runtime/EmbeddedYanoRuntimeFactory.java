package com.bloxbean.cardano.yano.wallet.yano.runtime;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yano.runtime.YaciNode;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class EmbeddedYanoRuntimeFactory {
    private EmbeddedYanoRuntimeFactory() {
    }

    public static DelegatingYanoWalletNodeRuntime preprodReadOnly(Path chainstatePath, Path networkConfigDir) {
        YaciNode node = new YaciNode(
                preprodConfig(chainstatePath, networkConfigDir, false),
                walletRuntimeOptions());
        node.start();
        return new DelegatingYanoWalletNodeRuntime(node);
    }

    public static DelegatingYanoWalletNodeRuntime preprodSync(Path chainstatePath, Path networkConfigDir) {
        return new DelegatingYanoWalletNodeRuntime(new YaciNode(
                preprodConfig(chainstatePath, networkConfigDir, true),
                walletRuntimeOptions()));
    }

    private static YaciNodeConfig preprodConfig(Path chainstatePath, Path networkConfigDir, boolean sync) {
        Path configDir = networkConfigDir.toAbsolutePath().normalize();
        return YaciNodeConfig.builder()
                .network("preprod")
                .remoteHost(Constants.PREPROD_PUBLIC_RELAY_ADDR)
                .remotePort(Constants.PREPROD_PUBLIC_RELAY_PORT)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(13337)
                .enableServer(false)
                .enableClient(sync)
                .useRocksDB(true)
                .rocksDBPath(chainstatePath.toAbsolutePath().normalize().toString())
                .fullSyncThreshold(1800)
                .enablePipelinedSync(sync)
                .headerPipelineDepth(sync ? 200 : 1)
                .bodyBatchSize(sync ? 200 : 1)
                .maxParallelBodies(sync ? 50 : 1)
                .enableSelectiveBodyFetch(false)
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .enableBlockProducer(false)
                .devMode(false)
                .blockTimeMillis(0)
                .lazyBlockProduction(false)
                .genesisTimestamp(0)
                .slotLengthMillis(0)
                .shelleyGenesisHash("162d29c4e1cf6b8a84f2d692e67a3ac6bc7851bc3e6e4afe64d15778bed8bd86")
                .shelleyGenesisFile(configDir.resolve("shelley-genesis.json").toString())
                .byronGenesisFile(configDir.resolve("byron-genesis.json").toString())
                .alonzoGenesisFile(configDir.resolve("alonzo-genesis.json").toString())
                .conwayGenesisFile(configDir.resolve("conway-genesis.json").toString())
                .protocolParametersFile(configDir.resolve("protocol-param.json").toString())
                .build();
    }

    private static RuntimeOptions walletRuntimeOptions() {
        Map<String, Object> globals = Map.ofEntries(
                Map.entry("yaci.node.utxo.enabled", true),
                Map.entry("yaci.node.utxo.pruneDepth", 7_776_000),
                Map.entry("yaci.node.utxo.rollbackWindow", 7_776_000),
                Map.entry("yaci.node.utxo.pruneBatchSize", 500),
                Map.entry("yaci.node.utxo.index.address_hash", true),
                Map.entry("yaci.node.utxo.index.payment_credential", true),
                Map.entry("yaci.node.utxo.indexingStrategy", "both"),
                Map.entry("yaci.node.utxo.delta.selfContained", false),
                Map.entry("yaci.node.utxo.applyAsync", false),
                Map.entry("yaci.node.metrics.enabled", false),
                Map.entry("yaci.node.account-state.enabled", true),
                Map.entry("yaci.node.account-state.epoch-block-data-retention-lag", 40),
                Map.entry("yaci.node.account-state.snapshot-retention-epochs", 120),
                Map.entry("yaci.node.account.stake-balance-index-enabled", true),
                Map.entry("yaci.node.account-history.enabled", false),
                Map.entry("yaci.node.epoch-snapshot.amounts-enabled", true),
                Map.entry("yaci.node.epoch-snapshot.balance-mode", "full-scan"),
                Map.entry("yaci.node.adapot.enabled", true),
                Map.entry("yaci.node.rewards.enabled", true),
                Map.entry("yaci.node.epoch-params.tracking-enabled", true),
                Map.entry("yaci.node.governance.enabled", true),
                Map.entry("yaci.node.filters.utxo.enabled", false),
                Map.entry("yaci.node.filters.utxo.addresses", Set.of()),
                Map.entry("yaci.node.filters.utxo.payment-credentials", Set.of()),
                Map.entry("yaci.node.chain.block-body-prune-depth", 0),
                Map.entry("yaci.node.chain.block-prune-batch-size", 500_000),
                Map.entry("yaci.node.chain.block-prune-interval-seconds", 300)
        );

        return new RuntimeOptions(
                new EventsOptions(true, 8192, SubscriptionOptions.Overflow.BLOCK),
                new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()),
                globals);
    }
}
