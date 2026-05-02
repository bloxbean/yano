package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yano.app.bootstrap.BootstrapConfigParser;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yano.runtime.YaciNode;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.ledgerrules.impl.AikenTxEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.impl.JulcTxEvaluator;
import com.bloxbean.cardano.yano.runtime.blockproducer.EffectiveProtocolParamsSupplier;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper;
import com.bloxbean.cardano.yano.runtime.config.DefaultEpochParamProvider;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import com.bloxbean.cardano.yano.ledgerrules.impl.YaciScriptSupplier;
import com.bloxbean.cardano.yano.scalusbridge.ScalusTransactionFactory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

@ApplicationScoped
public class YaciNodeProducer {

    private static final Logger log = LoggerFactory.getLogger(YaciNodeProducer.class);
    private static final String ROLLBACK_RETENTION_EPOCHS = "yaci.node.rollback-retention-epochs";
    private static final String UTXO_ROLLBACK_WINDOW = "yaci.node.utxo.rollbackWindow";
    private static final String ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG =
            "yaci.node.account-state.epoch-block-data-retention-lag";
    private static final String ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS =
            "yaci.node.account-state.snapshot-retention-epochs";
    private static final String ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS =
            "yaci.node.account-history.rollback-safety-slots";
    private static final String BLOCK_BODY_PRUNE_DEPTH =
            "yaci.node.chain.block-body-prune-depth";

    @Inject
    Config appConfig;

    @ConfigProperty(name = "yaci.node.network", defaultValue = "mainnet")
    String network;

    @ConfigProperty(name = "yaci.node.remote.host", defaultValue = "backbone.cardano.iog.io")
    String remoteHost;

    @ConfigProperty(name = "yaci.node.remote.port", defaultValue = "3001")
    int remotePort;

    @ConfigProperty(name = "yaci.node.remote.protocol-magic", defaultValue = "764824073")
    long protocolMagic;

    @ConfigProperty(name = "yaci.node.server.port", defaultValue = "13337")
    int serverPort;

    @ConfigProperty(name = "yaci.node.client.enabled", defaultValue = "true")
    boolean clientEnabled;

    @ConfigProperty(name = "yaci.node.server.enabled", defaultValue = "true")
    boolean serverEnabled;

    @ConfigProperty(name = "yaci.node.storage.rocksdb", defaultValue = "true")
    boolean useRocksDB;

    @ConfigProperty(name = "yaci.node.storage.path", defaultValue = "./chainstate")
    String storagePath;

    @ConfigProperty(name = "yaci.node.auto-sync-start", defaultValue = "false")
    boolean autoSyncStart;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @ConfigProperty(name = "yaci.node.api-prefix", defaultValue = "/api/v1")
    String apiPrefix;

    @ConfigProperty(name = "yaci.events.enabled", defaultValue = "true")
    boolean eventsEnabled;

    @ConfigProperty(name = "yaci.plugins.enabled", defaultValue = "true")
    boolean pluginsEnabled;

    @ConfigProperty(name = "yaci.plugins.logging.enabled", defaultValue = "false")
    boolean pluginsLoggingEnabled;

    // UTXO config
    @ConfigProperty(name = "yaci.node.utxo.enabled", defaultValue = "true")
    boolean utxoEnabled;
    @ConfigProperty(name = "yaci.node.utxo.pruneDepth", defaultValue = "2160")
    int utxoPruneDepth;
    @ConfigProperty(name = "yaci.node.utxo.rollbackWindow", defaultValue = "4320")
    int utxoRollbackWindow;
    @ConfigProperty(name = "yaci.node.utxo.pruneBatchSize", defaultValue = "500")
    int utxoPruneBatchSize;
    @ConfigProperty(name = "yaci.node.utxo.index.address_hash", defaultValue = "true")
    boolean utxoIndexAddressHash;
    @ConfigProperty(name = "yaci.node.utxo.index.payment_credential", defaultValue = "true")
    boolean utxoIndexPaymentCred;
    @ConfigProperty(name = "yaci.node.utxo.indexingStrategy", defaultValue = "both")
    String utxoIndexingStrategy;
    @ConfigProperty(name = "yaci.node.utxo.delta.selfContained", defaultValue = "false")
    boolean utxoDeltaSelfContained;
    @ConfigProperty(name = "yaci.node.utxo.applyAsync", defaultValue = "false")
    boolean utxoApplyAsync;

    @ConfigProperty(name = ROLLBACK_RETENTION_EPOCHS)
    java.util.Optional<Integer> rollbackRetentionEpochs;

    // Account state config
    @ConfigProperty(name = "yaci.node.account-state.enabled", defaultValue = "false")
    boolean accountStateEnabled;
    @ConfigProperty(name = "yaci.node.account-state.epoch-block-data-retention-lag", defaultValue = "5")
    int accountStateEpochBlockDataRetentionLag;
    @ConfigProperty(name = "yaci.node.account-state.snapshot-retention-epochs", defaultValue = "50")
    int accountStateSnapshotRetentionEpochs;
    @ConfigProperty(name = "yaci.node.account.stake-balance-index-enabled", defaultValue = "true")
    boolean stakeBalanceIndexEnabled;
    @ConfigProperty(name = "yaci.node.account-history.enabled", defaultValue = "false")
    boolean accountHistoryEnabled;
    @ConfigProperty(name = "yaci.node.account-history.tx-events-enabled", defaultValue = "true")
    boolean accountHistoryTxEventsEnabled;
    @ConfigProperty(name = "yaci.node.account-history.rewards-enabled", defaultValue = "false")
    boolean accountHistoryRewardsEnabled;
    @ConfigProperty(name = "yaci.node.account-history.retention-epochs", defaultValue = "0")
    int accountHistoryRetentionEpochs;
    @ConfigProperty(name = "yaci.node.account-history.prune-interval-seconds", defaultValue = "300")
    long accountHistoryPruneIntervalSeconds;
    @ConfigProperty(name = "yaci.node.account-history.prune-batch-size", defaultValue = "50000")
    int accountHistoryPruneBatchSize;
    @ConfigProperty(name = "yaci.node.account-history.rollback-safety-slots")
    java.util.Optional<Long> accountHistoryRollbackSafetySlots;

    // Epoch subsystem config
    @ConfigProperty(name = "yaci.node.epoch-snapshot.amounts-enabled", defaultValue = "false")
    boolean epochSnapshotAmountsEnabled;
    @ConfigProperty(name = "yaci.node.epoch-snapshot.balance-mode", defaultValue = "full-scan")
    String balanceMode; // "full-scan" or "incremental"
    @ConfigProperty(name = "yaci.node.adapot.enabled", defaultValue = "false")
    boolean adapotEnabled;
    @ConfigProperty(name = "yaci.node.rewards.enabled", defaultValue = "false")
    boolean rewardsEnabled;
    @ConfigProperty(name = "yaci.node.epoch-params.tracking-enabled", defaultValue = "false")
    boolean epochParamsTrackingEnabled;
    @ConfigProperty(name = "yaci.node.governance.enabled", defaultValue = "false")
    boolean governanceEnabled;
    @ConfigProperty(name = "yaci.node.snapshot-export.enabled", defaultValue = "false")
    boolean snapshotExportEnabled;
    @ConfigProperty(name = "yaci.node.snapshot-export.stake", defaultValue = "false")
    boolean snapshotExportStake;
    @ConfigProperty(name = "yaci.node.snapshot-export.drep-dist", defaultValue = "true")
    boolean snapshotExportDrepDist;
    @ConfigProperty(name = "yaci.node.snapshot-export.adapot", defaultValue = "true")
    boolean snapshotExportAdaPot;
    @ConfigProperty(name = "yaci.node.snapshot-export.proposals", defaultValue = "true")
    boolean snapshotExportProposals;
    @ConfigProperty(name = "yaci.node.exit-on-epoch-calc-error", defaultValue = "false")
    boolean exitOnEpochCalcError;
    @ConfigProperty(name = "yaci.node.auto-checkpoint-interval", defaultValue = "0")
    int autoCheckpointInterval;
    @ConfigProperty(name = "yaci.node.snapshot-export.dir", defaultValue = "data")
    String snapshotExportDir;

    // Block body pruning config
    @ConfigProperty(name = "yaci.node.chain.block-body-prune-depth", defaultValue = "0")
    int blockBodyPruneDepth;
    @ConfigProperty(name = "yaci.node.chain.block-prune-batch-size", defaultValue = "500000")
    int blockPruneBatchSize;
    @ConfigProperty(name = "yaci.node.chain.block-prune-interval-seconds", defaultValue = "300")
    long blockPruneIntervalSeconds;

    // UTXO storage filter config
    @ConfigProperty(name = "yaci.node.filters.utxo.enabled", defaultValue = "false")
    boolean utxoFilterEnabled;
    @ConfigProperty(name = "yaci.node.filters.utxo.addresses")
    java.util.Optional<java.util.List<String>> utxoFilterAddresses;
    @ConfigProperty(name = "yaci.node.filters.utxo.payment-credentials")
    java.util.Optional<java.util.List<String>> utxoFilterPaymentCredentials;

    // Dev mode
    @ConfigProperty(name = "yaci.node.dev-mode", defaultValue = "false")
    boolean devMode;

    // Adhoc rollback — pass via command line, NOT application.yml (to avoid accidental re-rollback)
    // Usage: -Dyaci.node.debug.rollback-to-slot=54172800 or -Dyaci.node.debug.rollback-to-epoch=320
    @ConfigProperty(name = "yaci.node.debug.rollback-to-slot", defaultValue = "-1")
    long debugRollbackToSlot;

    @ConfigProperty(name = "yaci.node.debug.rollback-to-epoch", defaultValue = "-1")
    int debugRollbackToEpoch;

    // Block producer config
    @ConfigProperty(name = "yaci.node.block-producer.enabled", defaultValue = "false")
    boolean blockProducerEnabled;

    @ConfigProperty(name = "yaci.node.block-producer.block-time-millis", defaultValue = "2000")
    int blockTimeMillis;

    @ConfigProperty(name = "yaci.node.block-producer.lazy", defaultValue = "false")
    boolean blockProducerLazy;

    @ConfigProperty(name = "yaci.node.block-producer.genesis-timestamp", defaultValue = "0")
    long genesisTimestamp;

    @ConfigProperty(name = "yaci.node.block-producer.slot-length-millis", defaultValue = "1000")
    int slotLengthMillis;

    @ConfigProperty(name = "yaci.node.block-producer.tx-evaluation", defaultValue = "true")
    boolean txEvaluationEnabled;

    @ConfigProperty(name = "yaci.node.block-producer.script-evaluator", defaultValue = "scalus")
    String scriptEvaluator;

    @ConfigProperty(name = "yaci.node.block-producer.vrf-skey-file")
    java.util.Optional<String> vrfSkeyFile;

    @ConfigProperty(name = "yaci.node.block-producer.kes-skey-file")
    java.util.Optional<String> kesSkeyFile;

    @ConfigProperty(name = "yaci.node.block-producer.opcert-file")
    java.util.Optional<String> opCertFile;

    @ConfigProperty(name = "yaci.node.block-producer.slot-leader-mode", defaultValue = "false")
    boolean slotLeaderMode;

    @ConfigProperty(name = "yaci.node.block-producer.stake-data-provider-url")
    java.util.Optional<String> stakeDataProviderUrl;

    @ConfigProperty(name = "yaci.node.block-producer.initial-epoch-nonce")
    java.util.Optional<String> initialEpochNonce;

    @ConfigProperty(name = "yaci.node.block-producer.initial-epoch", defaultValue = "-1")
    int initialEpoch;

    @ConfigProperty(name = "yaci.node.block-producer.start-epoch", defaultValue = "0")
    int startEpoch;

    @ConfigProperty(name = "yaci.node.block-producer.past-time-travel-mode", defaultValue = "false")
    boolean pastTimeTravelMode;

    // Bootstrap config
    @ConfigProperty(name = "yaci.node.bootstrap.enabled", defaultValue = "false")
    boolean bootstrapEnabled;

    @ConfigProperty(name = "yaci.node.bootstrap.block-number", defaultValue = "-1")
    long bootstrapBlockNumber;

    @ConfigProperty(name = "yaci.node.bootstrap.provider", defaultValue = "blockfrost")
    String bootstrapProvider;

    @ConfigProperty(name = "yaci.node.bootstrap.addresses")
    java.util.Optional<java.util.List<String>> bootstrapAddresses;

    @ConfigProperty(name = "yaci.node.bootstrap.utxos")
    java.util.Optional<java.util.List<String>> bootstrapUtxos;

    @ConfigProperty(name = "yaci.node.bootstrap.blockfrost.api-key")
    java.util.Optional<String> bootstrapBlockfrostApiKey;

    @ConfigProperty(name = "yaci.node.bootstrap.blockfrost.base-url")
    java.util.Optional<String> bootstrapBlockfrostBaseUrl;

    @ConfigProperty(name = "yaci.node.bootstrap.koios.base-url")
    java.util.Optional<String> bootstrapKoiosBaseUrl;

    // Genesis config (shared between devnet and relay modes)
    @ConfigProperty(name = "yaci.node.genesis.shelley-genesis-file")
    java.util.Optional<String> shelleyGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.byron-genesis-file")
    java.util.Optional<String> byronGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.alonzo-genesis-file")
    java.util.Optional<String> alonzoGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.conway-genesis-file")
    java.util.Optional<String> conwayGenesisFile;

    @ConfigProperty(name = "yaci.node.genesis.shelley-genesis-hash")
    java.util.Optional<String> shelleyGenesisHash;

    @ConfigProperty(name = "yaci.node.genesis.protocol-parameters-file")
    java.util.Optional<String> protocolParametersFile;

    private final ClassLoader pluginClassLoader;
    private NodeAPI nodeAPI;

    public YaciNodeProducer(@Named("pluginClassLoader") ClassLoader pluginClassLoader) {
        this.pluginClassLoader = pluginClassLoader;
    }

    private boolean isBootstrapPartialStateMode() {
        return bootstrapEnabled;
    }

    private boolean effectiveAccountStateEnabled() {
        return accountStateEnabled && !isBootstrapPartialStateMode();
    }

    private boolean effectiveEpochParamsTrackingEnabled() {
        return epochParamsTrackingEnabled && !isBootstrapPartialStateMode();
    }

    private boolean effectiveDerivedLedgerStateEnabled(boolean configured) {
        return configured && !isBootstrapPartialStateMode();
    }

    record RollbackRetentionSettings(
            int utxoRollbackWindow,
            int accountStateEpochBlockDataRetentionLag,
            int accountStateSnapshotRetentionEpochs,
            java.util.Optional<Long> accountHistoryRollbackSafetySlots,
            int blockBodyPruneDepth,
            boolean umbrellaEnabled,
            int retentionEpochs,
            long slotWindow) {
    }

    static RollbackRetentionSettings resolveRollbackRetentionSettings(
            java.util.Optional<Integer> rollbackRetentionEpochs,
            long epochLength,
            int utxoRollbackWindow,
            boolean utxoRollbackWindowConfigured,
            int accountStateEpochBlockDataRetentionLag,
            boolean accountStateEpochBlockDataRetentionLagConfigured,
            int accountStateSnapshotRetentionEpochs,
            boolean accountStateSnapshotRetentionEpochsConfigured,
            java.util.Optional<Long> accountHistoryRollbackSafetySlots,
            boolean accountHistoryRollbackSafetySlotsConfigured,
            int blockBodyPruneDepth,
            boolean blockBodyPruneDepthConfigured) {

        var unchanged = new RollbackRetentionSettings(
                utxoRollbackWindow,
                accountStateEpochBlockDataRetentionLag,
                accountStateSnapshotRetentionEpochs,
                accountHistoryRollbackSafetySlots,
                blockBodyPruneDepth,
                false,
                0,
                0);

        if (rollbackRetentionEpochs == null || rollbackRetentionEpochs.isEmpty()) {
            return unchanged;
        }

        int retentionEpochs = rollbackRetentionEpochs.get();
        if (retentionEpochs < 0) {
            throw new IllegalArgumentException(ROLLBACK_RETENTION_EPOCHS + " must be >= 0");
        }
        if (retentionEpochs == 0) {
            return unchanged;
        }
        if (epochLength <= 0) {
            throw new IllegalArgumentException("Cannot apply " + ROLLBACK_RETENTION_EPOCHS
                    + " without a positive Shelley epoch length");
        }

        long slotWindow = Math.multiplyExact((long) retentionEpochs, epochLength);
        int slotWindowInt = requireIntRange(slotWindow, UTXO_ROLLBACK_WINDOW);
        int rewardInputLag = requireIntRange((long) retentionEpochs + 1,
                ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG);
        int snapshotRetention = requireIntRange((long) retentionEpochs + 4,
                ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS);

        int resolvedUtxoRollbackWindow = utxoRollbackWindowConfigured
                ? utxoRollbackWindow
                : Math.max(utxoRollbackWindow, slotWindowInt);
        int resolvedAccountStateLag = accountStateEpochBlockDataRetentionLagConfigured
                ? accountStateEpochBlockDataRetentionLag
                : Math.max(accountStateEpochBlockDataRetentionLag, rewardInputLag);
        int resolvedSnapshotRetention = accountStateSnapshotRetentionEpochsConfigured
                ? accountStateSnapshotRetentionEpochs
                : Math.max(accountStateSnapshotRetentionEpochs, snapshotRetention);
        java.util.Optional<Long> resolvedAccountHistorySafety = accountHistoryRollbackSafetySlotsConfigured
                ? accountHistoryRollbackSafetySlots
                : java.util.Optional.of(Math.max(accountHistoryRollbackSafetySlots.orElse(0L), slotWindow));

        int resolvedBlockBodyPruneDepth = blockBodyPruneDepth;
        if (!blockBodyPruneDepthConfigured && blockBodyPruneDepth > 0) {
            resolvedBlockBodyPruneDepth = Math.max(blockBodyPruneDepth, slotWindowInt);
        }

        return new RollbackRetentionSettings(
                resolvedUtxoRollbackWindow,
                resolvedAccountStateLag,
                resolvedSnapshotRetention,
                resolvedAccountHistorySafety,
                resolvedBlockBodyPruneDepth,
                true,
                retentionEpochs,
                slotWindow);
    }

    private static int requireIntRange(long value, String propertyName) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(propertyName + " exceeds integer range: " + value);
        }
        return (int) value;
    }

    @Produces
    @ApplicationScoped
    public NodeAPI createNodeAPI() {
        if (nodeAPI != null) {
            return nodeAPI;
        }

        log.info("Creating Yaci Node with network: {}", network);

        YaciNodeConfig yaciConfig;
        switch (network.toLowerCase()) {
            case "mainnet":
                yaciConfig = YaciNodeConfig.mainnetDefault();
                break;
            case "preview":
                yaciConfig = YaciNodeConfig.previewDefault();
                break;
            case "sanchonet":
                yaciConfig = YaciNodeConfig.sanchonetDefault();
                break;
            case "preprod":
            default:
                yaciConfig = YaciNodeConfig.preprodDefault();
                break;
        }

        // Resolve genesis files: user config takes precedence, then auto-resolve from bundled classpath resources
        String resolvedShelleyGenesis = resolveGenesisFile(shelleyGenesisFile.orElse(null), protocolMagic, "shelley-genesis.json");
        String resolvedByronGenesis = resolveGenesisFile(byronGenesisFile.orElse(null), protocolMagic, "byron-genesis.json");
        String resolvedAlonzoGenesis = resolveGenesisFile(alonzoGenesisFile.orElse(null), protocolMagic, "alonzo-genesis.json");
        String resolvedConwayGenesis = resolveGenesisFile(conwayGenesisFile.orElse(null), protocolMagic, "conway-genesis.json");

        // Override with configuration properties
        yaciConfig = YaciNodeConfig.builder()
                .enableClient(clientEnabled)
                .enableServer(serverEnabled)
                .remoteHost(remoteHost)
                .remotePort(remotePort)
                .serverPort(serverPort)
                .protocolMagic(protocolMagic)
                .useRocksDB(useRocksDB)
                .rocksDBPath(storagePath)
                .fullSyncThreshold(yaciConfig.getFullSyncThreshold())
                .enablePipelinedSync(yaciConfig.isEnablePipelinedSync())
                .headerPipelineDepth(yaciConfig.getHeaderPipelineDepth())
                .bodyBatchSize(yaciConfig.getBodyBatchSize())
                .maxParallelBodies(yaciConfig.getMaxParallelBodies())
                .enableSelectiveBodyFetch(yaciConfig.isEnableSelectiveBodyFetch())
                .selectiveBodyFetchRatio(yaciConfig.getSelectiveBodyFetchRatio())
                .enableMonitoring(yaciConfig.isEnableMonitoring())
                .monitoringPort(yaciConfig.getMonitoringPort())
                .enableBlockProducer(blockProducerEnabled)
                .devMode(devMode)
                .blockTimeMillis(blockTimeMillis)
                .lazyBlockProduction(blockProducerLazy)
                .genesisTimestamp(genesisTimestamp)
                .slotLengthMillis(slotLengthMillis)
                .vrfSkeyFile(vrfSkeyFile.orElse(null))
                .kesSkeyFile(kesSkeyFile.orElse(null))
                .opCertFile(opCertFile.orElse(null))
                .slotLeaderMode(slotLeaderMode)
                .stakeDataProviderUrl(stakeDataProviderUrl.orElse(null))
                .initialEpochNonce(initialEpochNonce.orElse(null))
                .initialEpoch(initialEpoch)
                .startEpoch(startEpoch)
                .pastTimeTravelMode(pastTimeTravelMode)
                .shelleyGenesisHash(shelleyGenesisHash.orElse(null))
                .shelleyGenesisFile(resolvedShelleyGenesis)
                .byronGenesisFile(resolvedByronGenesis)
                .alonzoGenesisFile(resolvedAlonzoGenesis)
                .conwayGenesisFile(resolvedConwayGenesis)
                .protocolParametersFile(protocolParametersFile.orElse(null))
                .enableBootstrap(bootstrapEnabled)
                .bootstrapBlockNumber(bootstrapBlockNumber)
                .bootstrapProvider(bootstrapProvider)
                .bootstrapAddresses(bootstrapAddresses.orElse(null))
                .bootstrapUtxos(BootstrapConfigParser.parseUtxoRefs(bootstrapUtxos.orElse(null)))
                .bootstrapBlockfrostApiKey(bootstrapBlockfrostApiKey.orElse(null))
                .bootstrapBlockfrostBaseUrl(bootstrapBlockfrostBaseUrl.orElse(null))
                .bootstrapKoiosBaseUrl(bootstrapKoiosBaseUrl.orElse(null))
                .network(network)
                // Epoch/slot values are NOT set here — they must come from genesis
                // at runtime via propagateGenesisToConfig(). Setting preset values here
                // would mask misconfiguration for custom/unknown networks.
                .build();

        // Validate configuration
        yaciConfig.validate();

        // Build runtime options
        EventsOptions eventsOptions = new EventsOptions(
                eventsEnabled, 8192, SubscriptionOptions.Overflow.BLOCK);

        Map<String, Object> pluginConfigMap = new HashMap<>();
        pluginConfigMap.put("plugins.logging.enabled", pluginsLoggingEnabled);
        PluginsOptions pluginsOptions = new PluginsOptions(
                pluginsEnabled, false, Set.of(), Set.of(), pluginConfigMap);

        boolean effectiveAccountStateEnabled = effectiveAccountStateEnabled();
        boolean effectiveEpochParamsTrackingEnabled = effectiveEpochParamsTrackingEnabled();
        boolean effectiveAccountHistoryEnabled = effectiveDerivedLedgerStateEnabled(accountHistoryEnabled);
        boolean effectiveAccountHistoryTxEventsEnabled = effectiveDerivedLedgerStateEnabled(accountHistoryTxEventsEnabled);
        boolean effectiveAccountHistoryRewardsEnabled = effectiveDerivedLedgerStateEnabled(accountHistoryRewardsEnabled);
        boolean effectiveStakeBalanceIndexEnabled = effectiveDerivedLedgerStateEnabled(stakeBalanceIndexEnabled);
        boolean effectiveEpochSnapshotAmountsEnabled = effectiveDerivedLedgerStateEnabled(epochSnapshotAmountsEnabled);
        boolean effectiveAdaPotEnabled = effectiveDerivedLedgerStateEnabled(adapotEnabled);
        boolean effectiveRewardsEnabled = effectiveDerivedLedgerStateEnabled(rewardsEnabled);
        boolean effectiveGovernanceEnabled = effectiveDerivedLedgerStateEnabled(governanceEnabled);
        boolean effectiveSnapshotExportEnabled = effectiveDerivedLedgerStateEnabled(snapshotExportEnabled);

        if (isBootstrapPartialStateMode()) {
            log.info("Bootstrap mode enabled: disabling derived ledger-state subsystems "
                    + "(account-state, stake-balance-index, account-history, epoch-params, rewards, adapot, governance, snapshots). "
                    + "UTXO bootstrap remains enabled; transaction evaluation may use protocol-param.json if configured.");
        }

        RollbackRetentionSettings rollbackRetentionSettings = resolveRollbackRetentionSettings(
                rollbackRetentionEpochs,
                rollbackRetentionEpochs.orElse(0) > 0
                        ? resolveRollbackRetentionEpochLength(resolvedShelleyGenesis)
                        : 0,
                utxoRollbackWindow,
                isConfigPropertyPresent(UTXO_ROLLBACK_WINDOW),
                accountStateEpochBlockDataRetentionLag,
                isConfigPropertyPresent(ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG),
                accountStateSnapshotRetentionEpochs,
                isConfigPropertyPresent(ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS),
                accountHistoryRollbackSafetySlots,
                isConfigPropertyPresent(ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS),
                blockBodyPruneDepth,
                isConfigPropertyPresent(BLOCK_BODY_PRUNE_DEPTH));

        if (rollbackRetentionSettings.umbrellaEnabled()) {
            log.info("Rollback retention umbrella configured: epochs={}, slotWindow={}. "
                            + "Effective retention: utxo.rollbackWindow={}, "
                            + "account-state.epoch-block-data-retention-lag={}, "
                            + "account-state.snapshot-retention-epochs={}, "
                            + "account-history.rollback-safety-slots={}, "
                            + "chain.block-body-prune-depth={}",
                    rollbackRetentionSettings.retentionEpochs(),
                    rollbackRetentionSettings.slotWindow(),
                    rollbackRetentionSettings.utxoRollbackWindow(),
                    rollbackRetentionSettings.accountStateEpochBlockDataRetentionLag(),
                    rollbackRetentionSettings.accountStateSnapshotRetentionEpochs(),
                    rollbackRetentionSettings.accountHistoryRollbackSafetySlots().orElse(null),
                    rollbackRetentionSettings.blockBodyPruneDepth());
        }

        // Globals: UTXO options
        Map<String, Object> globals = new HashMap<>();
        globals.put("yaci.node.utxo.enabled", utxoEnabled);
        globals.put("yaci.node.utxo.pruneDepth", utxoPruneDepth);
        globals.put(UTXO_ROLLBACK_WINDOW, rollbackRetentionSettings.utxoRollbackWindow());
        globals.put("yaci.node.utxo.pruneBatchSize", utxoPruneBatchSize);
        globals.put("yaci.node.utxo.index.address_hash", utxoIndexAddressHash);
        globals.put("yaci.node.utxo.index.payment_credential", utxoIndexPaymentCred);
        globals.put("yaci.node.utxo.indexingStrategy", utxoIndexingStrategy);
        globals.put("yaci.node.utxo.delta.selfContained", utxoDeltaSelfContained);
        globals.put("yaci.node.utxo.applyAsync", utxoApplyAsync);
        globals.put("yaci.node.tx-evaluation.enabled", txEvaluationEnabled);

        // Account state
        globals.put("yaci.node.account-state.enabled", effectiveAccountStateEnabled);
        globals.put(ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG,
                rollbackRetentionSettings.accountStateEpochBlockDataRetentionLag());
        globals.put(ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS,
                rollbackRetentionSettings.accountStateSnapshotRetentionEpochs());
        globals.put("yaci.node.account.stake-balance-index-enabled", effectiveStakeBalanceIndexEnabled);
        globals.put("yaci.node.account-history.enabled", effectiveAccountHistoryEnabled);
        globals.put("yaci.node.account-history.tx-events-enabled", effectiveAccountHistoryTxEventsEnabled);
        globals.put("yaci.node.account-history.rewards-enabled", effectiveAccountHistoryRewardsEnabled);
        globals.put("yaci.node.account-history.retention-epochs", accountHistoryRetentionEpochs);
        globals.put("yaci.node.account-history.prune-interval-seconds", accountHistoryPruneIntervalSeconds);
        globals.put("yaci.node.account-history.prune-batch-size", accountHistoryPruneBatchSize);
        rollbackRetentionSettings.accountHistoryRollbackSafetySlots().ifPresent(v ->
                globals.put(ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS, v));

        // Epoch subsystems
        globals.put("yaci.node.epoch-snapshot.amounts-enabled", effectiveEpochSnapshotAmountsEnabled);
        globals.put("yaci.node.epoch-snapshot.balance-mode", balanceMode);
        globals.put("yaci.node.adapot.enabled", effectiveAdaPotEnabled);
        globals.put("yaci.node.rewards.enabled", effectiveRewardsEnabled);
        globals.put("yaci.node.epoch-params.tracking-enabled", effectiveEpochParamsTrackingEnabled);
        globals.put("yaci.node.governance.enabled", effectiveGovernanceEnabled);
        globals.put("yaci.node.snapshot-export.enabled", effectiveSnapshotExportEnabled);
        globals.put("yaci.node.snapshot-export.dir", snapshotExportDir);
        globals.put("yaci.node.snapshot-export.stake", snapshotExportStake);
        globals.put("yaci.node.snapshot-export.drep-dist", snapshotExportDrepDist);
        globals.put("yaci.node.snapshot-export.adapot", snapshotExportAdaPot);
        globals.put("yaci.node.snapshot-export.proposals", snapshotExportProposals);
        globals.put("yaci.node.exit-on-epoch-calc-error", exitOnEpochCalcError);
        globals.put("yaci.node.auto-checkpoint-interval", autoCheckpointInterval);

        // Block pruning
        globals.put(BLOCK_BODY_PRUNE_DEPTH, rollbackRetentionSettings.blockBodyPruneDepth());
        globals.put("yaci.node.chain.block-prune-batch-size", blockPruneBatchSize);
        globals.put("yaci.node.chain.block-prune-interval-seconds", blockPruneIntervalSeconds);

        // UTXO filters
        globals.put("yaci.node.filters.utxo.enabled", utxoFilterEnabled);
        globals.put("yaci.node.filters.utxo.addresses", utxoFilterAddresses.orElse(java.util.List.of()));
        globals.put("yaci.node.filters.utxo.payment-credentials", utxoFilterPaymentCredentials.orElse(java.util.List.of()));

        RuntimeOptions runtimeOptions = new RuntimeOptions(eventsOptions, pluginsOptions, globals);

        // Set plugin classloader on thread context so PluginManager picks it up
        Thread.currentThread().setContextClassLoader(pluginClassLoader);

        nodeAPI = new YaciNode(yaciConfig, runtimeOptions);
        log.info("Yaci Node created successfully");

        // Configure adhoc rollback if requested via command line
        if (debugRollbackToSlot >= 0 || debugRollbackToEpoch >= 0) {
            ((YaciNode) nodeAPI).setAdhocRollback(debugRollbackToSlot, debugRollbackToEpoch);
            log.info("Adhoc rollback configured: slot={}, epoch={}", debugRollbackToSlot, debugRollbackToEpoch);
        }

        // Wire bootstrap data provider if bootstrap is enabled
        if (bootstrapEnabled) {
            wireBootstrapProvider((YaciNode) nodeAPI, yaciConfig);
        }

        // Initialize transaction evaluator if enabled
        if (txEvaluationEnabled) {
            initTransactionEvaluator((YaciNode) nodeAPI, yaciConfig);
        }

        return nodeAPI;
    }

    void onStart(@Observes StartupEvent event) {
        log.info("Yaci Node Application starting up...");
        log.info("Auto-sync-start enabled: {}", autoSyncStart);

        if (autoSyncStart) {
            try {
                log.info("Auto-starting Yaci Node synchronization...");
                NodeAPI node = createNodeAPI();
                node.start();
                log.info("Yaci Node started automatically and syncing with {} network", network);
                log.info("REST API available at {}/ for manual control", nodeApiBaseUrl());
            } catch (Exception e) {
                log.error("Failed to auto-start Yaci Node: {}", e.getMessage(), e);
                if (bootstrapEnabled) {
                    log.error("Bootstrap mode is enabled but failed. "
                            + "The node cannot start without bootstrap state. Shutting down.");
                    throw new RuntimeException("Bootstrap failed, cannot start node", e);
                }
                log.info("You can still start manually via: curl -X POST {}/start", nodeApiBaseUrl());
            }
        } else {
            log.info("Auto-sync is disabled. Start manually via: curl -X POST {}/start", nodeApiBaseUrl());
            log.info("REST API available at {}/", nodeApiBaseUrl());
        }
    }

    private String nodeApiBaseUrl() {
        return "http://localhost:" + httpPort + normalizedApiPrefix() + "/node";
    }

    private String normalizedApiPrefix() {
        if (apiPrefix == null || apiPrefix.isBlank() || "/".equals(apiPrefix.trim())) {
            return "";
        }
        String prefix = apiPrefix.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/") && prefix.length() > 1) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /**
     * Initialize the Scalus-based transaction evaluator and inject it into the node.
     */
    private void initTransactionEvaluator(YaciNode yaciNode, YaciNodeConfig yaciConfig) {
        boolean effectiveEpochParamsTrackingEnabled = effectiveEpochParamsTrackingEnabled();
        LedgerStateProvider ledgerStateProvider = yaciNode.getLedgerStateProvider();
        if (effectiveEpochParamsTrackingEnabled && ledgerStateProvider == null) {
            log.info("Transaction validation/evaluation not initialized: ledger-state provider is unavailable");
            return;
        }

        // Load genesis config for slot math. Protocol params come from ledger-state when
        // epoch-param tracking is enabled; otherwise protocol-param.json is an explicit
        // static source for devnet/custom quick setups.
        GenesisConfig genesis;
        SlotConfig slotConfig;
        int networkId;
        EpochProtocolParamsSupplier protocolParamsSupplier;
        LongSupplier currentSlotSupplier;
        EpochSlotCalc epochSlotCalc;
        String protocolParamsSource;
        ProtocolParams staticProtocolParams = null;
        try {
            genesis = GenesisConfig.load(
                    yaciConfig.getShelleyGenesisFile(),
                    yaciConfig.getByronGenesisFile(),
                    effectiveEpochParamsTrackingEnabled ? null : yaciConfig.getProtocolParametersFile());

            epochSlotCalc = resolveEpochSlotCalc(yaciNode, yaciConfig, genesis);
            if (effectiveEpochParamsTrackingEnabled) {
                protocolParamsSupplier = new EffectiveProtocolParamsSupplier(
                        ledgerStateProvider,
                        epochSlotCalc);
                protocolParamsSource = "effective-ledger";
            } else {
                if (genesis == null || !genesis.hasProtocolParameters()) {
                    log.info("Transaction validation/evaluation not initialized: epoch-param tracking disabled and no protocol-param.json configured");
                    return;
                }
                ProtocolParams staticParams = ProtocolParamsMapper.fromNodeProtocolParam(genesis.getProtocolParameters());
                staticProtocolParams = staticParams;
                protocolParamsSupplier = slot -> staticParams;
                protocolParamsSource = "static-protocol-param-json";
            }
            currentSlotSupplier = () -> {
                var tip = yaciNode.getLocalTip();
                return tip != null ? tip.getSlot() : -1L;
            };

            long magic = yaciConfig.getProtocolMagic();

            long genesisTs = yaciConfig.getGenesisTimestamp() > 0
                    ? yaciConfig.getGenesisTimestamp()
                    : genesis.getSystemStartEpochMillis() > 0
                    ? genesis.getSystemStartEpochMillis()
                    : System.currentTimeMillis();
            slotConfig = new SlotConfig(yaciConfig.getSlotLengthMillis(), 0, genesisTs);

            log.info("Yaci Node slot config: {}", slotConfig);

            networkId = magic == Constants.MAINNET_PROTOCOL_MAGIC ? 1 : 0;
        } catch (Exception e) {
            if (effectiveEpochParamsTrackingEnabled) {
                log.error("Transaction validation/evaluation not initialized for ledger-derived protocol params: {}",
                        e.getMessage(), e);
            } else {
                log.warn("Transaction validation/evaluation not initialized for static protocol params: {}",
                        e.getMessage(), e);
            }
            return;
        }

        // Initialize TransactionValidator (Scalus) — validates transactions on submission
        boolean validatorInitialized = false;
        try {
            TransactionValidator evaluator = staticProtocolParams != null
                    ? ScalusTransactionFactory.createValidator(staticProtocolParams,
                            new YaciScriptSupplier(yaciNode.getUtxoState()), slotConfig, networkId,
                            ledgerStateProvider)
                    : ScalusTransactionFactory.createValidator(protocolParamsSupplier,
                            new YaciScriptSupplier(yaciNode.getUtxoState()), slotConfig, networkId,
                            ledgerStateProvider, currentSlotSupplier,
                            epochSlotCalc::slotToEpoch);
            yaciNode.setTransactionEvaluator(evaluator);
            validatorInitialized = true;
            log.info("Transaction validator initialized (networkId={}, protocolParams={})", networkId, protocolParamsSource);
        } catch (Exception e) {
            log.error("Failed to initialize transaction validator (Scalus). "
                    + "Transactions will NOT be validated on submission! Error: {}", e.getMessage(), e);
        }

        // Initialize TransactionEvaluator (Aiken/Scalus) — powers /utils/txs/evaluate endpoint
        boolean evaluatorInitialized = false;
        try {
            TransactionEvaluator transactionEvaluator;
            if ("scalus".equalsIgnoreCase(scriptEvaluator)) {
                transactionEvaluator = staticProtocolParams != null
                        ? ScalusTransactionFactory.createEvaluator(staticProtocolParams,
                                new YaciScriptSupplier(yaciNode.getUtxoState()), slotConfig, networkId)
                        : ScalusTransactionFactory.createEvaluator(protocolParamsSupplier,
                                new YaciScriptSupplier(yaciNode.getUtxoState()), slotConfig, networkId, currentSlotSupplier);
            } else if ("julc".equalsIgnoreCase(scriptEvaluator)) {
                transactionEvaluator = new JulcTxEvaluator(
                        () -> protocolParamsSupplier.getProtocolParams(resolveRuntimeCurrentSlot(currentSlotSupplier)),
                        new YaciScriptSupplier(yaciNode.getUtxoState()), slotConfig);
            } else {
                transactionEvaluator = new AikenTxEvaluator(
                        () -> protocolParamsSupplier.getProtocolParams(resolveRuntimeCurrentSlot(currentSlotSupplier)),
                        new YaciScriptSupplier(yaciNode.getUtxoState()), slotConfig);
            }
            yaciNode.setScriptEvaluator(transactionEvaluator);
            evaluatorInitialized = true;
            log.info("Script evaluator initialized (networkId={}, evaluator={})", networkId, scriptEvaluator);
        } catch (Exception e) {
            log.error("Failed to initialize script evaluator ({}). "
                    + "The /utils/txs/evaluate endpoint will not work. Error: {}", scriptEvaluator, e.getMessage(), e);
        }

        if (!validatorInitialized && !evaluatorInitialized) {
            log.error("Neither transaction validator nor script evaluator could be initialized. "
                    + "Plutus script transactions will not be validated!");
        }
    }

    static long resolveRuntimeCurrentSlot(LongSupplier currentSlotSupplier) {
        if (currentSlotSupplier == null) {
            throw new IllegalStateException("Failed to resolve current slot from runtime");
        }

        try {
            long slot = currentSlotSupplier.getAsLong();
            if (slot >= 0) return slot;
            throw new IllegalStateException("current slot supplier returned " + slot);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve current slot from runtime", e);
        }
    }

    private EpochSlotCalc resolveEpochSlotCalc(YaciNode yaciNode, YaciNodeConfig yaciConfig, GenesisConfig genesis) {
        var provider = yaciNode.getEpochParamProvider();
        if (provider != null) {
            return provider.getEpochSlotCalc();
        }

        if (yaciConfig.isEpochParamsInitialized()) {
            return new EpochSlotCalc(yaciConfig.getEpochLength(),
                    yaciConfig.getByronSlotsPerEpoch(),
                    yaciConfig.getFirstNonByronSlot());
        }

        var shelley = genesis.getShelleyGenesisData();
        if (shelley == null) {
            throw new IllegalStateException("Cannot resolve epoch slot math without Shelley genesis");
        }

        boolean hasByron = genesis.getByronGenesisData() != null;
        long byronSlotsPerEpoch = hasByron
                ? genesis.getByronGenesisData().epochLength()
                : shelley.securityParam() * 10;
        long firstNonByronSlot = DefaultEpochParamProvider
                .resolveFirstNonByronSlot(yaciConfig.getProtocolMagic(), hasByron);
        return new EpochSlotCalc(shelley.epochLength(), byronSlotsPerEpoch, firstNonByronSlot);
    }

    /**
     * Wire the bootstrap data provider into the YaciNode based on configuration.
     */
    private void wireBootstrapProvider(YaciNode yaciNode, YaciNodeConfig yaciConfig) {
        try {
            String providerType = yaciConfig.getBootstrapProvider() != null
                    ? yaciConfig.getBootstrapProvider().toLowerCase() : "blockfrost";
            String net = yaciConfig.getNetwork() != null ? yaciConfig.getNetwork() : "preprod";

            com.bloxbean.cardano.yano.api.bootstrap.BootstrapDataProvider provider;
            switch (providerType) {
                case "koios" -> {
                    if (yaciConfig.getBootstrapKoiosBaseUrl() != null
                            && !yaciConfig.getBootstrapKoiosBaseUrl().isBlank()) {
                        provider = new com.bloxbean.cardano.yano.bootstrap.providers.KoiosBootstrapProvider(
                                yaciConfig.getBootstrapKoiosBaseUrl());
                    } else {
                        provider = com.bloxbean.cardano.yano.bootstrap.providers.KoiosBootstrapProvider
                                .forNetwork(net);
                    }
                }
                default -> { // blockfrost
                    String apiKey = yaciConfig.getBootstrapBlockfrostApiKey();
                    if (apiKey == null || apiKey.isBlank()) {
                        log.warn("Bootstrap enabled but no Blockfrost API key configured. "
                                + "Set yaci.node.bootstrap.blockfrost.api-key");
                        return;
                    }
                    if (yaciConfig.getBootstrapBlockfrostBaseUrl() != null
                            && !yaciConfig.getBootstrapBlockfrostBaseUrl().isBlank()) {
                        provider = new com.bloxbean.cardano.yano.bootstrap.providers.BlockfrostBootstrapProvider(
                                yaciConfig.getBootstrapBlockfrostBaseUrl(), apiKey);
                    } else {
                        provider = com.bloxbean.cardano.yano.bootstrap.providers.BlockfrostBootstrapProvider
                                .forNetwork(net, apiKey);
                    }
                }
            }
            yaciNode.setBootstrapDataProvider(provider);
            log.info("Bootstrap data provider configured: {}", providerType);
        } catch (Exception e) {
            log.error("Failed to configure bootstrap provider: {}", e.getMessage());
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        log.info("Yaci Node Application shutting down...");
        if (nodeAPI != null && nodeAPI.isRunning()) {
            log.info("Stopping Yaci Node...");
            nodeAPI.stop();
            log.info("Yaci Node stopped");
        }
    }

    private long resolveRollbackRetentionEpochLength(String resolvedShelleyGenesis) {
        try {
            return NetworkGenesisConfig.load(resolvedShelleyGenesis, null, null, null).getEpochLength();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve Shelley epoch length for "
                    + ROLLBACK_RETENTION_EPOCHS + " from " + resolvedShelleyGenesis, e);
        }
    }

    private boolean isConfigPropertyPresent(String propertyName) {
        if (appConfig == null) {
            return false;
        }
        try {
            return appConfig.getOptionalValue(propertyName, String.class).isPresent();
        } catch (Exception e) {
            log.debug("Failed to inspect config property {}: {}", propertyName, e.getMessage());
            return false;
        }
    }

    /**
     * Resolve a genesis file path. If the user has provided an explicit path and the file exists,
     * use it. Otherwise, for known networks (mainnet/preprod/preview), extract the bundled
     * classpath resource to a temp file.
     */
    private String resolveGenesisFile(String userPath, long magic, String filename) {
        // If user provided an explicit path and file exists, use it
        if (userPath != null && !userPath.isBlank()) {
            if (new java.io.File(userPath).exists()) {
                return userPath;
            }
            log.debug("User-configured genesis file not found: {}, trying bundled resource", userPath);
        }

        // Auto-resolve from bundled classpath resources based on protocol magic
        String networkDir = networkDirForMagic(magic);
        if (networkDir == null) {
            return userPath; // Unknown network, can't auto-resolve
        }

        String classpathResource = "genesis/" + networkDir + "/" + filename;
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                log.debug("Bundled genesis resource not found: {}", classpathResource);
                return userPath;
            }
            // Extract to temp file
            Path tempFile = Files.createTempFile("yaci-" + networkDir + "-", "-" + filename);
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Auto-resolved {} from bundled resource for {} network", filename, networkDir);
            return tempFile.toString();
        } catch (IOException e) {
            log.warn("Failed to extract bundled genesis resource {}: {}", classpathResource, e.getMessage());
            return userPath;
        }
    }

    private static final long SANCHONET_PROTOCOL_MAGIC = 4;

    private static String networkDirForMagic(long magic) {
        if (magic == Constants.MAINNET_PROTOCOL_MAGIC) return "mainnet";
        if (magic == Constants.PREPROD_PROTOCOL_MAGIC) return "preprod";
        if (magic == Constants.PREVIEW_PROTOCOL_MAGIC) return "preview";
        if (magic == SANCHONET_PROTOCOL_MAGIC) return "sanchonet";
        return null;
    }
}
