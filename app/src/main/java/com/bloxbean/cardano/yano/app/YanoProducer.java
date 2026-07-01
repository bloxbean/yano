package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.ChainSelectionConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamFailoverConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamGovernorConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPeerConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPreset;
import com.bloxbean.cardano.yano.api.config.UpstreamSyncConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamTxConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationStartConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.app.bootstrap.BootstrapConfigParser;
import com.bloxbean.cardano.yano.bootstrap.providers.DefaultBootstrapDataProviderFactory;
import com.bloxbean.cardano.yano.devnet.YanoDevnetAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import com.bloxbean.cardano.yano.runtime.config.DnsCachePolicy;
import com.bloxbean.cardano.yano.runtime.config.GenesisFileResolver;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import com.bloxbean.cardano.yano.runtime.config.RollbackRetentionGenesisValues;
import com.bloxbean.cardano.yano.runtime.config.RollbackRetentionPlanner;
import com.bloxbean.cardano.yano.runtime.config.RollbackRetentionSettings;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapOptions;
import com.bloxbean.cardano.yano.tx.DefaultTransactionServicesFactory;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
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

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Quarkus composition boundary that maps application properties to a
 * role-specific {@link Yano}.
 */
@ApplicationScoped
public class YanoProducer {

    private static final Logger log = LoggerFactory.getLogger(YanoProducer.class);
    private static final String ROLLBACK_RETENTION_EPOCHS = RollbackRetentionPlanner.ROLLBACK_RETENTION_EPOCHS;
    private static final String UTXO_ROLLBACK_WINDOW = RollbackRetentionPlanner.UTXO_ROLLBACK_WINDOW;
    private static final String ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG =
            RollbackRetentionPlanner.ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG;
    private static final String ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS =
            RollbackRetentionPlanner.ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS;
    private static final String ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS =
            RollbackRetentionPlanner.ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS;
    private static final String BLOCK_BODY_PRUNE_DEPTH =
            RollbackRetentionPlanner.BLOCK_BODY_PRUNE_DEPTH;

    @Inject
    Config appConfig;

    @ConfigProperty(name = YanoPropertyKeys.NETWORK, defaultValue = "mainnet")
    String network;

    @ConfigProperty(name = YanoPropertyKeys.Remote.HOST, defaultValue = "backbone.cardano.iog.io")
    String remoteHost;

    @ConfigProperty(name = YanoPropertyKeys.Remote.PORT, defaultValue = "3001")
    int remotePort;

    @ConfigProperty(name = YanoPropertyKeys.Remote.PROTOCOL_MAGIC, defaultValue = "764824073")
    long protocolMagic;

    @ConfigProperty(name = DnsCachePolicy.DNS_CACHE_TTL_KEY)
    java.util.Optional<Integer> dnsCacheTtl;

    @ConfigProperty(name = DnsCachePolicy.DNS_CACHE_NEGATIVE_TTL_KEY)
    java.util.Optional<Integer> dnsCacheNegativeTtl;

    @ConfigProperty(name = YanoPropertyKeys.Server.PORT, defaultValue = "13337")
    int serverPort;

    @ConfigProperty(name = YanoPropertyKeys.Client.ENABLED, defaultValue = "true")
    boolean clientEnabled;

    @ConfigProperty(name = YanoPropertyKeys.Server.ENABLED, defaultValue = "true")
    boolean serverEnabled;

    @ConfigProperty(name = YanoPropertyKeys.Relay.AUTO_DISCOVERY, defaultValue = "false")
    boolean relayAutoDiscovery;

    @ConfigProperty(name = YanoPropertyKeys.Relay.ADVERTISED_HOST)
    Optional<String> relayAdvertisedHost;

    @ConfigProperty(name = YanoPropertyKeys.Relay.ADVERTISED_PORT, defaultValue = "0")
    int relayAdvertisedPort;

    @ConfigProperty(name = YanoPropertyKeys.Relay.ALLOW_PRIVATE_ADDRESSES, defaultValue = "false")
    boolean relayAllowPrivateAddresses;

    @ConfigProperty(name = YanoPropertyKeys.Relay.CONNECTION_MAX_INBOUND_CONNECTIONS, defaultValue = "100")
    int relayConnectionMaxInboundConnections;

    @ConfigProperty(name = YanoPropertyKeys.Relay.CONNECTION_MAX_CONNECTIONS_PER_IP, defaultValue = "5")
    int relayConnectionMaxConnectionsPerIp;

    @ConfigProperty(name = YanoPropertyKeys.Relay.CONNECTION_SOURCE_PORT_REUSE, defaultValue = "true")
    boolean relayConnectionSourcePortReuse;

    @ConfigProperty(name = YanoPropertyKeys.Storage.ROCKSDB, defaultValue = "true")
    boolean useRocksDB;

    @ConfigProperty(name = YanoPropertyKeys.Storage.PATH, defaultValue = "./chainstate")
    String storagePath;

    @ConfigProperty(name = YanoPropertyKeys.AUTO_SYNC_START, defaultValue = "false")
    boolean autoSyncStart;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @ConfigProperty(name = YanoPropertyKeys.API_PREFIX, defaultValue = "/api/v1")
    String apiPrefix;

    @ConfigProperty(name = YanoPropertyKeys.Events.ENABLED, defaultValue = "true")
    boolean eventsEnabled;

    @ConfigProperty(name = YanoPropertyKeys.Plugins.ENABLED, defaultValue = "true")
    boolean pluginsEnabled;

    @ConfigProperty(name = YanoPropertyKeys.Plugins.LOGGING_ENABLED, defaultValue = "false")
    boolean pluginsLoggingEnabled;

    // UTXO config
    @ConfigProperty(name = YanoPropertyKeys.Utxo.ENABLED, defaultValue = "true")
    boolean utxoEnabled;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.PRUNE_DEPTH, defaultValue = "2160")
    int utxoPruneDepth;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.ROLLBACK_WINDOW, defaultValue = "4320")
    int utxoRollbackWindow;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.PRUNE_BATCH_SIZE, defaultValue = "500")
    int utxoPruneBatchSize;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.PRUNE_SCHEDULE_SECONDS, defaultValue = "5")
    long utxoPruneScheduleSeconds;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.METRICS_LAG_LOG_SECONDS, defaultValue = "10")
    long utxoMetricsLagLogSeconds;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.LAG_FAIL_IF_ABOVE, defaultValue = "-1")
    long utxoLagFailIfAbove;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.INDEX_ADDRESS_HASH, defaultValue = "true")
    boolean utxoIndexAddressHash;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.INDEX_PAYMENT_CREDENTIAL, defaultValue = "true")
    boolean utxoIndexPaymentCred;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.INDEXING_STRATEGY, defaultValue = "both")
    String utxoIndexingStrategy;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.DELTA_SELF_CONTAINED, defaultValue = "false")
    boolean utxoDeltaSelfContained;
    @ConfigProperty(name = YanoPropertyKeys.Utxo.APPLY_ASYNC, defaultValue = "false")
    boolean utxoApplyAsync;

    @ConfigProperty(name = YanoPropertyKeys.Metrics.ENABLED, defaultValue = "true")
    boolean metricsEnabled;
    @ConfigProperty(name = YanoPropertyKeys.Metrics.ROCKSDB_SAMPLE_SECONDS, defaultValue = "0")
    int metricsSampleRocksDbSeconds;
    @ConfigProperty(name = YanoPropertyKeys.Validation.DEFAULT_VALIDATOR_ENABLED, defaultValue = "true")
    boolean defaultValidatorEnabled;

    @ConfigProperty(name = YanoPropertyKeys.Tx.MEMPOOL_MAX_TXS, defaultValue = "10000")
    int txMempoolMaxTxs;

    @ConfigProperty(name = YanoPropertyKeys.Tx.MEMPOOL_MAX_BYTES, defaultValue = "134217728")
    long txMempoolMaxBytes;

    @ConfigProperty(name = YanoPropertyKeys.Tx.MEMPOOL_TTL_SECONDS, defaultValue = "10800")
    long txMempoolTtlSeconds;

    @ConfigProperty(name = YanoPropertyKeys.Tx.DIFFUSION_ENABLED, defaultValue = "true")
    boolean txDiffusionEnabled = true;

    @ConfigProperty(name = YanoPropertyKeys.Tx.DIFFUSION_MODE)
    java.util.Optional<String> txDiffusionMode = java.util.Optional.empty();

    @ConfigProperty(name = YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_TXS_PER_PEER, defaultValue = "100")
    int txDiffusionMaxInFlightTxsPerPeer;

    @ConfigProperty(name = YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_BYTES_PER_PEER, defaultValue = "1048576")
    long txDiffusionMaxInFlightBytesPerPeer;

    @ConfigProperty(name = YanoPropertyKeys.Tx.DIFFUSION_PEER_COOLDOWN_MS, defaultValue = "60000")
    long txDiffusionPeerCooldownMs;

    // CCL "supplementary rules" (GOVCERT/governance/delegatee) layered on top of Scalus validation.
    // Disabled by default — they don't yet account for intra-tx state changes within a single block.
    @ConfigProperty(name = YanoPropertyKeys.Validation.SUPPLEMENTARY_RULES_ENABLED, defaultValue = "false")
    boolean supplementaryRulesEnabled;

    @ConfigProperty(name = ROLLBACK_RETENTION_EPOCHS)
    java.util.Optional<Integer> rollbackRetentionEpochs;

    // Account state config
    @ConfigProperty(name = YanoPropertyKeys.AccountState.ENABLED, defaultValue = "false")
    boolean accountStateEnabled;
    @ConfigProperty(name = YanoPropertyKeys.AccountState.EPOCH_BLOCK_DATA_RETENTION_LAG, defaultValue = "5")
    int accountStateEpochBlockDataRetentionLag;
    @ConfigProperty(name = YanoPropertyKeys.AccountState.SNAPSHOT_RETENTION_EPOCHS, defaultValue = "50")
    int accountStateSnapshotRetentionEpochs;
    @ConfigProperty(name = YanoPropertyKeys.AccountState.STAKE_BALANCE_INDEX_ENABLED, defaultValue = "true")
    boolean stakeBalanceIndexEnabled;
    @ConfigProperty(name = YanoPropertyKeys.AccountHistory.ENABLED, defaultValue = "false")
    boolean accountHistoryEnabled;
    @ConfigProperty(name = YanoPropertyKeys.AccountHistory.TX_EVENTS_ENABLED, defaultValue = "true")
    boolean accountHistoryTxEventsEnabled;
    @ConfigProperty(name = YanoPropertyKeys.AccountHistory.REWARDS_ENABLED, defaultValue = "false")
    boolean accountHistoryRewardsEnabled;
    @ConfigProperty(name = YanoPropertyKeys.AccountHistory.RETENTION_EPOCHS, defaultValue = "0")
    int accountHistoryRetentionEpochs;
    @ConfigProperty(name = YanoPropertyKeys.AccountHistory.PRUNE_INTERVAL_SECONDS, defaultValue = "300")
    long accountHistoryPruneIntervalSeconds;
    @ConfigProperty(name = YanoPropertyKeys.AccountHistory.PRUNE_BATCH_SIZE, defaultValue = "50000")
    int accountHistoryPruneBatchSize;
    @ConfigProperty(name = YanoPropertyKeys.AccountHistory.ROLLBACK_SAFETY_SLOTS)
    java.util.Optional<Long> accountHistoryRollbackSafetySlots;

    // Epoch subsystem config
    @ConfigProperty(name = YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED, defaultValue = "false")
    boolean epochSnapshotAmountsEnabled;
    @ConfigProperty(name = YanoPropertyKeys.EpochSnapshot.BALANCE_MODE, defaultValue = "full-scan")
    String balanceMode; // "full-scan" or "incremental"
    @ConfigProperty(name = YanoPropertyKeys.Ledger.ADAPOT_ENABLED, defaultValue = "false")
    boolean adapotEnabled;
    @ConfigProperty(name = YanoPropertyKeys.Ledger.REWARDS_ENABLED, defaultValue = "false")
    boolean rewardsEnabled;
    @ConfigProperty(name = YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, defaultValue = "false")
    boolean epochParamsTrackingEnabled;
    @ConfigProperty(name = YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED, defaultValue = "false")
    boolean governanceEnabled;
    @ConfigProperty(name = YanoPropertyKeys.SnapshotExport.ENABLED, defaultValue = "false")
    boolean snapshotExportEnabled;
    @ConfigProperty(name = YanoPropertyKeys.SnapshotExport.STAKE, defaultValue = "false")
    boolean snapshotExportStake;
    @ConfigProperty(name = YanoPropertyKeys.SnapshotExport.DREP_DIST, defaultValue = "true")
    boolean snapshotExportDrepDist;
    @ConfigProperty(name = YanoPropertyKeys.SnapshotExport.ADAPOT, defaultValue = "true")
    boolean snapshotExportAdaPot;
    @ConfigProperty(name = YanoPropertyKeys.SnapshotExport.PROPOSALS, defaultValue = "true")
    boolean snapshotExportProposals;
    @ConfigProperty(name = YanoPropertyKeys.Ledger.EXIT_ON_EPOCH_CALC_ERROR, defaultValue = "false")
    boolean exitOnEpochCalcError;
    @ConfigProperty(name = YanoPropertyKeys.Ledger.AUTO_CHECKPOINT_INTERVAL, defaultValue = "0")
    int autoCheckpointInterval;
    @ConfigProperty(name = YanoPropertyKeys.SnapshotExport.DIR, defaultValue = "data")
    String snapshotExportDir;

    // Block body pruning config
    @ConfigProperty(name = YanoPropertyKeys.Chain.BLOCK_BODY_PRUNE_DEPTH, defaultValue = "0")
    int blockBodyPruneDepth;
    @ConfigProperty(name = YanoPropertyKeys.Chain.BLOCK_PRUNE_BATCH_SIZE, defaultValue = "500000")
    int blockPruneBatchSize;
    @ConfigProperty(name = YanoPropertyKeys.Chain.BLOCK_PRUNE_INTERVAL_SECONDS, defaultValue = "300")
    long blockPruneIntervalSeconds;

    // UTXO storage filter config
    @ConfigProperty(name = YanoPropertyKeys.UtxoFilter.ENABLED, defaultValue = "false")
    boolean utxoFilterEnabled;
    @ConfigProperty(name = YanoPropertyKeys.UtxoFilter.ADDRESSES)
    java.util.Optional<java.util.List<String>> utxoFilterAddresses;
    @ConfigProperty(name = YanoPropertyKeys.UtxoFilter.PAYMENT_CREDENTIALS)
    java.util.Optional<java.util.List<String>> utxoFilterPaymentCredentials;

    // Dev mode
    @ConfigProperty(name = YanoPropertyKeys.DEV_MODE, defaultValue = "false")
    boolean devMode;

    // Adhoc rollback — pass via command line, NOT application.yml (to avoid accidental re-rollback)
    // Usage: -Dyano.debug.rollback-to-slot=54172800 or -Dyano.debug.rollback-to-epoch=320
    @ConfigProperty(name = YanoPropertyKeys.Debug.ROLLBACK_TO_SLOT, defaultValue = "-1")
    long debugRollbackToSlot;

    @ConfigProperty(name = YanoPropertyKeys.Debug.ROLLBACK_TO_EPOCH, defaultValue = "-1")
    int debugRollbackToEpoch;

    // Block producer config
    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.ENABLED, defaultValue = "false")
    boolean blockProducerEnabled;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.BLOCK_TIME_MILLIS, defaultValue = "2000")
    int blockTimeMillis;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.LAZY, defaultValue = "false")
    boolean blockProducerLazy;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.GENESIS_TIMESTAMP, defaultValue = "0")
    long genesisTimestamp;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.SLOT_LENGTH_MILLIS, defaultValue = "1000")
    int slotLengthMillis;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.TX_EVALUATION, defaultValue = "true")
    boolean txEvaluationEnabled;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.SCRIPT_EVALUATOR, defaultValue = "scalus")
    String scriptEvaluator;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.VRF_SKEY_FILE)
    java.util.Optional<String> vrfSkeyFile;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.KES_SKEY_FILE)
    java.util.Optional<String> kesSkeyFile;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.OPCERT_FILE)
    java.util.Optional<String> opCertFile;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.SLOT_LEADER_MODE, defaultValue = "false")
    boolean slotLeaderMode;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.STAKE_DATA_PROVIDER_URL)
    java.util.Optional<String> stakeDataProviderUrl;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.INITIAL_EPOCH_NONCE)
    java.util.Optional<String> initialEpochNonce;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.INITIAL_EPOCH, defaultValue = "-1")
    int initialEpoch;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.START_EPOCH, defaultValue = "0")
    int startEpoch;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.PAST_TIME_TRAVEL_MODE, defaultValue = "false")
    boolean pastTimeTravelMode;

    @ConfigProperty(name = YanoPropertyKeys.BlockProducer.PAST_TIME_TRAVEL_SLOT_LEADER_MODE, defaultValue = "false")
    boolean pastTimeTravelSlotLeaderMode;

    // Bootstrap config
    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.ENABLED, defaultValue = "false")
    boolean bootstrapEnabled;

    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.BLOCK_NUMBER, defaultValue = "-1")
    long bootstrapBlockNumber;

    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.PROVIDER, defaultValue = "blockfrost")
    String bootstrapProvider;

    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.ADDRESSES)
    java.util.Optional<java.util.List<String>> bootstrapAddresses;

    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.UTXOS)
    java.util.Optional<java.util.List<String>> bootstrapUtxos;

    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.BLOCKFROST_API_KEY)
    java.util.Optional<String> bootstrapBlockfrostApiKey;

    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.BLOCKFROST_BASE_URL)
    java.util.Optional<String> bootstrapBlockfrostBaseUrl;

    @ConfigProperty(name = YanoPropertyKeys.Bootstrap.KOIOS_BASE_URL)
    java.util.Optional<String> bootstrapKoiosBaseUrl;

    // Genesis config (shared between devnet and relay modes)
    @ConfigProperty(name = YanoPropertyKeys.Genesis.SHELLEY_FILE)
    java.util.Optional<String> shelleyGenesisFile;

    @ConfigProperty(name = YanoPropertyKeys.Genesis.BYRON_FILE)
    java.util.Optional<String> byronGenesisFile;

    @ConfigProperty(name = YanoPropertyKeys.Genesis.ALONZO_FILE)
    java.util.Optional<String> alonzoGenesisFile;

    @ConfigProperty(name = YanoPropertyKeys.Genesis.CONWAY_FILE)
    java.util.Optional<String> conwayGenesisFile;

    @ConfigProperty(name = YanoPropertyKeys.Genesis.SHELLEY_HASH)
    java.util.Optional<String> shelleyGenesisHash;

    @ConfigProperty(name = YanoPropertyKeys.Genesis.PROTOCOL_PARAMETERS_FILE)
    java.util.Optional<String> protocolParametersFile;

    private final ClassLoader pluginClassLoader;
    private Yano yano;

    public YanoProducer(@Named("pluginClassLoader") ClassLoader pluginClassLoader) {
        this.pluginClassLoader = pluginClassLoader;
    }

    private Yano ensureYano() {
        if (yano != null) {
            return yano;
        }

        log.info("Creating Yano with network: {}", network);

        YanoConfig yaciConfig = YanoConfig.defaultForNetwork(network);

        // Resolve genesis files: user config takes precedence, then auto-resolve from bundled classpath resources
        String resolvedShelleyGenesis = resolveGenesisFile(shelleyGenesisFile.orElse(null), protocolMagic, "shelley-genesis.json");
        String resolvedByronGenesis = resolveGenesisFile(byronGenesisFile.orElse(null), protocolMagic, "byron-genesis.json");
        String resolvedAlonzoGenesis = resolveGenesisFile(alonzoGenesisFile.orElse(null), protocolMagic, "alonzo-genesis.json");
        String resolvedConwayGenesis = resolveGenesisFile(conwayGenesisFile.orElse(null), protocolMagic, "conway-genesis.json");

        // Override with configuration properties
        yaciConfig = YanoConfig.builder()
                .enableClient(clientEnabled)
                .enableServer(serverEnabled)
                .remoteHost(remoteHost)
                .remotePort(remotePort)
                .upstream(parseUpstreamConfig())
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
                .pastTimeTravelSlotLeaderMode(pastTimeTravelSlotLeaderMode)
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

        RollbackRetentionGenesisValues rollbackRetentionGenesisValues = rollbackRetentionEpochs.orElse(0) > 0
                ? resolveRollbackRetentionGenesisValues(resolvedShelleyGenesis)
                : new RollbackRetentionGenesisValues(0, 0);
        RollbackRetentionSettings rollbackRetentionSettings =
                resolveRollbackRetentionSettings(rollbackRetentionGenesisValues);

        if (rollbackRetentionSettings.umbrellaEnabled()) {
            if (blockBodyPruneDepth > 0
                    && rollbackRetentionSettings.blockBodyPruneDepth() > blockBodyPruneDepth) {
                log.warn("Raised chain.block-body-prune-depth from {} to {} to satisfy "
                                + "{}={} with genesis epochLength={} activeSlotsCoeff={}",
                        blockBodyPruneDepth,
                        rollbackRetentionSettings.blockBodyPruneDepth(),
                        ROLLBACK_RETENTION_EPOCHS,
                        rollbackRetentionSettings.retentionEpochs(),
                        rollbackRetentionGenesisValues.epochLength(),
                        rollbackRetentionGenesisValues.activeSlotsCoeff());
            }
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
        putRollbackRetentionGlobals(globals, rollbackRetentionSettings);
        globals.put(YanoPropertyKeys.Utxo.ENABLED, utxoEnabled);
        globals.put(YanoPropertyKeys.Utxo.PRUNE_DEPTH, utxoPruneDepth);
        globals.put(YanoPropertyKeys.Utxo.PRUNE_BATCH_SIZE, utxoPruneBatchSize);
        globals.put(YanoPropertyKeys.Utxo.PRUNE_SCHEDULE_SECONDS, utxoPruneScheduleSeconds);
        globals.put(YanoPropertyKeys.Utxo.METRICS_LAG_LOG_SECONDS, utxoMetricsLagLogSeconds);
        globals.put(YanoPropertyKeys.Utxo.LAG_FAIL_IF_ABOVE, utxoLagFailIfAbove);
        globals.put(YanoPropertyKeys.Utxo.INDEX_ADDRESS_HASH, utxoIndexAddressHash);
        globals.put(YanoPropertyKeys.Utxo.INDEX_PAYMENT_CREDENTIAL, utxoIndexPaymentCred);
        globals.put(YanoPropertyKeys.Utxo.INDEXING_STRATEGY, utxoIndexingStrategy);
        globals.put(YanoPropertyKeys.Utxo.DELTA_SELF_CONTAINED, utxoDeltaSelfContained);
        globals.put(YanoPropertyKeys.Utxo.APPLY_ASYNC, utxoApplyAsync);
        globals.put(YanoPropertyKeys.Metrics.ENABLED, metricsEnabled);
        globals.put(YanoPropertyKeys.Metrics.ROCKSDB_SAMPLE_SECONDS, metricsSampleRocksDbSeconds);
        globals.put(YanoPropertyKeys.Validation.DEFAULT_VALIDATOR_ENABLED, defaultValidatorEnabled);
        globals.put(YanoPropertyKeys.Validation.SUPPLEMENTARY_RULES_ENABLED, supplementaryRulesEnabled);
        globals.put(YanoPropertyKeys.Tx.MEMPOOL_MAX_TXS, txMempoolMaxTxs);
        globals.put(YanoPropertyKeys.Tx.MEMPOOL_MAX_BYTES, txMempoolMaxBytes);
        globals.put(YanoPropertyKeys.Tx.MEMPOOL_TTL_SECONDS, txMempoolTtlSeconds);
        globals.put(YanoPropertyKeys.Tx.DIFFUSION_ENABLED, txDiffusionEnabled);
        txDiffusionMode.ifPresent(mode -> globals.put(YanoPropertyKeys.Tx.DIFFUSION_MODE, mode));
        globals.put(YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_TXS_PER_PEER,
                txDiffusionMaxInFlightTxsPerPeer);
        globals.put(YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_BYTES_PER_PEER,
                txDiffusionMaxInFlightBytesPerPeer);
        globals.put(YanoPropertyKeys.Tx.DIFFUSION_PEER_COOLDOWN_MS, txDiffusionPeerCooldownMs);
        globals.put(YanoPropertyKeys.Relay.AUTO_DISCOVERY, relayAutoDiscovery);
        globals.put(YanoPropertyKeys.Relay.ADVERTISED_HOST,
                relayAdvertisedHost.map(String::trim).filter(host -> !host.isBlank()).orElse("auto"));
        globals.put(YanoPropertyKeys.Relay.ADVERTISED_PORT,
                relayAdvertisedPort > 0 ? relayAdvertisedPort : serverPort);
        globals.put(YanoPropertyKeys.Relay.ALLOW_PRIVATE_ADDRESSES, relayAllowPrivateAddresses);
        globals.put(YanoPropertyKeys.Relay.CONNECTION_MAX_INBOUND_CONNECTIONS,
                relayConnectionMaxInboundConnections);
        globals.put(YanoPropertyKeys.Relay.CONNECTION_MAX_CONNECTIONS_PER_IP,
                relayConnectionMaxConnectionsPerIp);
        globals.put(YanoPropertyKeys.Relay.CONNECTION_SOURCE_PORT_REUSE,
                relayConnectionSourcePortReuse);
        globals.put(YanoPropertyKeys.BlockProducer.TX_EVALUATION, txEvaluationEnabled);
        dnsCacheTtl.ifPresent(value -> globals.put(DnsCachePolicy.DNS_CACHE_TTL_KEY, value));
        dnsCacheNegativeTtl.ifPresent(value -> globals.put(DnsCachePolicy.DNS_CACHE_NEGATIVE_TTL_KEY, value));

        // Account state
        globals.put(YanoPropertyKeys.AccountState.ENABLED, accountStateEnabled);
        globals.put(YanoPropertyKeys.AccountState.STAKE_BALANCE_INDEX_ENABLED, stakeBalanceIndexEnabled);
        globals.put(YanoPropertyKeys.AccountHistory.ENABLED, accountHistoryEnabled);
        globals.put(YanoPropertyKeys.AccountHistory.TX_EVENTS_ENABLED, accountHistoryTxEventsEnabled);
        globals.put(YanoPropertyKeys.AccountHistory.REWARDS_ENABLED, accountHistoryRewardsEnabled);
        globals.put(YanoPropertyKeys.AccountHistory.RETENTION_EPOCHS, accountHistoryRetentionEpochs);
        globals.put(YanoPropertyKeys.AccountHistory.PRUNE_INTERVAL_SECONDS, accountHistoryPruneIntervalSeconds);
        globals.put(YanoPropertyKeys.AccountHistory.PRUNE_BATCH_SIZE, accountHistoryPruneBatchSize);

        // Epoch subsystems
        globals.put(YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED, epochSnapshotAmountsEnabled);
        globals.put(YanoPropertyKeys.EpochSnapshot.BALANCE_MODE, balanceMode);
        globals.put(YanoPropertyKeys.Ledger.ADAPOT_ENABLED, adapotEnabled);
        globals.put(YanoPropertyKeys.Ledger.REWARDS_ENABLED, rewardsEnabled);
        globals.put(YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, epochParamsTrackingEnabled);
        globals.put(YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED, governanceEnabled);
        globals.put(YanoPropertyKeys.SnapshotExport.ENABLED, snapshotExportEnabled);
        globals.put(YanoPropertyKeys.SnapshotExport.DIR, snapshotExportDir);
        globals.put(YanoPropertyKeys.SnapshotExport.STAKE, snapshotExportStake);
        globals.put(YanoPropertyKeys.SnapshotExport.DREP_DIST, snapshotExportDrepDist);
        globals.put(YanoPropertyKeys.SnapshotExport.ADAPOT, snapshotExportAdaPot);
        globals.put(YanoPropertyKeys.SnapshotExport.PROPOSALS, snapshotExportProposals);
        globals.put(YanoPropertyKeys.Ledger.EXIT_ON_EPOCH_CALC_ERROR, exitOnEpochCalcError);
        globals.put(YanoPropertyKeys.Ledger.AUTO_CHECKPOINT_INTERVAL, autoCheckpointInterval);

        // Block pruning
        globals.put(YanoPropertyKeys.Chain.BLOCK_PRUNE_BATCH_SIZE, blockPruneBatchSize);
        globals.put(YanoPropertyKeys.Chain.BLOCK_PRUNE_INTERVAL_SECONDS, blockPruneIntervalSeconds);

        // UTXO filters
        globals.put(YanoPropertyKeys.UtxoFilter.ENABLED, utxoFilterEnabled);
        globals.put(YanoPropertyKeys.UtxoFilter.ADDRESSES, utxoFilterAddresses.orElse(java.util.List.of()));
        globals.put(YanoPropertyKeys.UtxoFilter.PAYMENT_CREDENTIALS,
                utxoFilterPaymentCredentials.orElse(java.util.List.of()));

        RuntimeOptions runtimeOptions = YanoAssembly.applyBootstrapPartialStatePolicy(
                yaciConfig,
                new RuntimeOptions(eventsOptions, pluginsOptions, globals));

        // Set plugin classloader on thread context so PluginManager picks it up
        Thread.currentThread().setContextClassLoader(pluginClassLoader);

        YanoDevnetAssembly.Builder assembly = YanoDevnetAssembly.fromConfig(yaciConfig)
                .runtimeOptions(runtimeOptions);

        if (debugRollbackToSlot >= 0 || debugRollbackToEpoch >= 0) {
            assembly.adhocRollback(debugRollbackToSlot, debugRollbackToEpoch);
            log.info("Adhoc rollback configured: slot={}, epoch={}", debugRollbackToSlot, debugRollbackToEpoch);
        }

        if (bootstrapEnabled) {
            DefaultBootstrapDataProviderFactory.create(yaciConfig)
                    .ifPresent(assembly::bootstrapDataProvider);
        }

        yano = assembly.transactionBootstrap(
                        TransactionBootstrapOptions.of(
                                txEvaluationEnabled,
                                YanoAssembly.effectiveDerivedLedgerStateEnabled(yaciConfig, epochParamsTrackingEnabled),
                                supplementaryRulesEnabled,
                                scriptEvaluator),
                        DefaultTransactionServicesFactory::create)
                .build();
        log.info("Yano created successfully");

        return yano;
    }

    @Produces
    @ApplicationScoped
    public NodeLifecycle createNodeLifecycle() {
        return ensureYano().lifecycle();
    }

    @Produces
    @ApplicationScoped
    public ChainQuery createChainQuery() {
        return ensureYano().chain();
    }

    @Produces
    @ApplicationScoped
    public LedgerQuery createLedgerQuery() {
        return ensureYano().ledger();
    }

    @Produces
    @ApplicationScoped
    public TxGateway createTxGateway() {
        return ensureYano().txGateway();
    }

    @Produces
    @ApplicationScoped
    public TxEvaluationGateway createTxEvaluationGateway() {
        return ensureYano().txEvaluationGateway();
    }

    @Produces
    @ApplicationScoped
    public ProducerControl createProducerControl() {
        return ensureYano().producerControl().orElse(UnavailableProducerControl.INSTANCE);
    }

    @Produces
    @ApplicationScoped
    public DevnetControl createDevnetControl() {
        return ensureYano().devnetControl().orElse(UnavailableDevnetControl.INSTANCE);
    }

    @Produces
    @ApplicationScoped
    public DebugLedgerStateAccess createDebugLedgerStateAccess() {
        return ensureYano().debugLedgerStateAccess()
                .orElseThrow(() -> new IllegalStateException("Debug ledger-state access unavailable"));
    }

    @Produces
    @ApplicationScoped
    public NodeKernel createNodeKernel() {
        return ensureYano().kernel()
                .orElseThrow(() -> new IllegalStateException("Runtime kernel unavailable"));
    }

    @Produces
    @ApplicationScoped
    public RuntimeMaintenanceGate createRuntimeMaintenanceGate() {
        return ensureYano().maintenanceGate()
                .orElseThrow(() -> new IllegalStateException("Runtime maintenance gate unavailable"));
    }

    void onStart(@Observes StartupEvent event) {
        log.info("Yano application starting up...");
        log.info("Auto-sync-start enabled: {}", autoSyncStart);

        if (autoSyncStart) {
            try {
                log.info("Auto-starting Yano synchronization...");
                ensureYano().start();
                log.info("Yano started automatically and syncing with {} network", network);
                log.info("REST API available at {}/ for manual control", nodeApiBaseUrl());
            } catch (Exception e) {
                log.error("Failed to auto-start Yano: {}", e.getMessage(), e);
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

    UpstreamConfig parseUpstreamConfig() {
        java.util.Optional<String> mode = appConfig.getOptionalValue(YanoPropertyKeys.Upstream.MODE, String.class);
        List<UpstreamPeerConfig> peers = parseUpstreamPeers();
        if (mode.isEmpty() && peers.isEmpty() && !hasExplicitUpstreamProperty()) {
            return null;
        }
        UpstreamPreset upstreamMode = UpstreamPreset.fromConfig(mode.orElse("trusted-single"));
        boolean discoveryEnabled = relayAutoDiscovery
                || configBoolean(YanoPropertyKeys.Upstream.DISCOVERY_ENABLED, false);
        List<String> peerSnapshotUrls = configStringList(YanoPropertyKeys.Upstream.DISCOVERY_PEER_SNAPSHOT_URLS);
        if (peerSnapshotUrls.isEmpty()) {
            peerSnapshotUrls = defaultPeerSnapshotUrls(upstreamMode, discoveryEnabled);
        }

        return UpstreamConfig.builder()
                .mode(upstreamMode)
                .peers(peers)
                .selection(ChainSelectionConfig.builder()
                        .policy(configString(YanoPropertyKeys.Upstream.SELECTION_POLICY,
                                "trusted-or-quorum-within-rollback-window"))
                        .rollbackWindowSlots(appConfig.getOptionalValue(
                                        YanoPropertyKeys.Upstream.SELECTION_ROLLBACK_WINDOW_SLOTS, Long.class)
                                .orElse(0L))
                        .requireBodyBeforeAdoption(configBoolean(
                                YanoPropertyKeys.Upstream.SELECTION_REQUIRE_BODY_BEFORE_ADOPTION, true))
                        .trustPolicy(configString(YanoPropertyKeys.Upstream.SELECTION_TRUST_POLICY, "trusted-only"))
                        .quorum(configInt(YanoPropertyKeys.Upstream.SELECTION_QUORUM, 2))
                        .tieBreak(configString(YanoPropertyKeys.Upstream.SELECTION_TIE_BREAK, "deterministic"))
                        .build())
                .validation(UpstreamValidationConfig.builder()
                        .level(configString(YanoPropertyKeys.Upstream.VALIDATION_LEVEL, "none"))
                        .bodyLevel(configString(YanoPropertyKeys.Upstream.VALIDATION_BODY_LEVEL, "none"))
                        .opCertCounterMode(configString(
                                YanoPropertyKeys.Upstream.VALIDATION_OPCERT_COUNTER_MODE, "none"))
                        .start(UpstreamValidationStartConfig.builder()
                                .mode(configString(YanoPropertyKeys.Upstream.VALIDATION_START_MODE, "immediate"))
                                .era(configString(YanoPropertyKeys.Upstream.VALIDATION_START_ERA, "conway"))
                                .slot(configLong(YanoPropertyKeys.Upstream.VALIDATION_START_SLOT, -1L))
                                .hash(configString(YanoPropertyKeys.Upstream.VALIDATION_START_HASH, ""))
                                .build())
                        .build())
                .sync(UpstreamSyncConfig.builder()
                        .bulkSource(configString(YanoPropertyKeys.Upstream.SYNC_BULK_SOURCE, "single-trusted"))
                        .fanInStart(configString(YanoPropertyKeys.Upstream.SYNC_FAN_IN_START, "near-tip"))
                        .build())
                .failover(UpstreamFailoverConfig.builder()
                        .cooldownMs(configLong(YanoPropertyKeys.Upstream.FAILOVER_COOLDOWN_MS, 30_000L))
                        .maxFailuresBeforeCooldown(configInt(
                                YanoPropertyKeys.Upstream.FAILOVER_MAX_FAILURES_BEFORE_COOLDOWN, 3))
                .build())
                .tx(UpstreamTxConfig.builder()
                        .forwarding(effectiveUpstreamTxForwarding())
                        .build())
                .governor(UpstreamGovernorConfig.builder()
                        .enabled(configBoolean(YanoPropertyKeys.Upstream.GOVERNOR_ENABLED, false))
                        .targetCold(configInt(YanoPropertyKeys.Upstream.GOVERNOR_TARGET_COLD, 100))
                        .targetWarm(configInt(YanoPropertyKeys.Upstream.GOVERNOR_TARGET_WARM, 8))
                        .targetHot(configInt(YanoPropertyKeys.Upstream.GOVERNOR_TARGET_HOT, 2))
                        .maxConcurrentDials(configInt(
                                YanoPropertyKeys.Upstream.GOVERNOR_MAX_CONCURRENT_DIALS, 4))
                        .build())
                .discovery(UpstreamDiscoveryConfig.builder()
                        .enabled(discoveryEnabled)
                        .peerSharing(relayAutoDiscovery
                                || configBoolean(YanoPropertyKeys.Upstream.DISCOVERY_PEER_SHARING, false))
                        .seeds(configStringList(YanoPropertyKeys.Upstream.DISCOVERY_SEEDS))
                        .peerSnapshotUrls(peerSnapshotUrls)
                        .peerSnapshotFiles(configStringList(YanoPropertyKeys.Upstream.DISCOVERY_PEER_SNAPSHOT_FILES))
                        .peerSnapshotLimit(configInt(
                                YanoPropertyKeys.Upstream.DISCOVERY_PEER_SNAPSHOT_LIMIT, 128))
                        .topologyFile(configString(YanoPropertyKeys.Upstream.DISCOVERY_TOPOLOGY_FILE, ""))
                        .ledgerPeers(configBoolean(YanoPropertyKeys.Upstream.DISCOVERY_LEDGER_PEERS, false))
                        .useLedgerAfterSlot(configLong(
                                YanoPropertyKeys.Upstream.DISCOVERY_USE_LEDGER_AFTER_SLOT, -1L))
                        .allowPrivateAddresses(configBoolean(
                                YanoPropertyKeys.Upstream.DISCOVERY_ALLOW_PRIVATE_ADDRESSES, false))
                        .allowlist(configStringList(YanoPropertyKeys.Upstream.DISCOVERY_ALLOWLIST))
                        .denylist(configStringList(YanoPropertyKeys.Upstream.DISCOVERY_DENYLIST))
                        .build())
                .build();
    }

    private String effectiveUpstreamTxForwarding() {
        String legacyForwarding = configString(YanoPropertyKeys.Upstream.TX_FORWARDING, "active-selected");
        java.util.Optional<String> configuredDiffusionMode = configuredTxDiffusionMode();
        if (configuredDiffusionMode.isEmpty()) {
            return txDiffusionEnabled ? "all-hot-trusted" : "disabled";
        }

        String mode = configuredDiffusionMode.get().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "disabled" -> "disabled";
            case "local-submit-only" -> {
                String normalizedLegacy = legacyForwarding != null
                        ? legacyForwarding.trim().toLowerCase(java.util.Locale.ROOT)
                        : "active-selected";
                yield "all-hot-trusted".equals(normalizedLegacy) ? "all-hot-trusted" : "active-selected";
            }
            case "trusted-hot", "all-hot" -> "all-hot-trusted";
            default -> "disabled";
        };
    }

    private java.util.Optional<String> configuredTxDiffusionMode() {
        if (txDiffusionMode != null && txDiffusionMode.isPresent()) {
            return txDiffusionMode;
        }
        if (appConfig != null) {
            return appConfig.getOptionalValue(YanoPropertyKeys.Tx.DIFFUSION_MODE, String.class);
        }
        return java.util.Optional.empty();
    }

    private boolean hasExplicitUpstreamProperty() {
        for (String propertyName : appConfig.getPropertyNames()) {
            if (propertyName != null && propertyName.startsWith("yano.upstream.")) {
                return true;
            }
        }
        return false;
    }

    private List<String> defaultPeerSnapshotUrls(UpstreamPreset upstreamMode, boolean discoveryEnabled) {
        if (!discoveryEnabled || upstreamMode != UpstreamPreset.P2P_RELAY) {
            return List.of();
        }
        String normalizedNetwork = network != null ? network.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return switch (normalizedNetwork) {
            case "mainnet", "preprod", "preview" -> List.of(
                    "https://book.play.dev.cardano.org/environments/" + normalizedNetwork + "/peer-snapshot.json");
            default -> List.of();
        };
    }

    private List<UpstreamPeerConfig> parseUpstreamPeers() {
        List<UpstreamPeerConfig> peers = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            String prefix = YanoPropertyKeys.Upstream.PEERS + "[" + i + "]";
            java.util.Optional<String> host = appConfig.getOptionalValue(prefix + ".host", String.class);
            if (host.isEmpty()) {
                if (i == 0) {
                    continue;
                }
                break;
            }
            peers.add(UpstreamPeerConfig.builder()
                    .id(appConfig.getOptionalValue(prefix + ".id", String.class).orElse(null))
                    .host(host.get())
                    .port(appConfig.getOptionalValue(prefix + ".port", Integer.class).orElse(remotePort))
                    .source(appConfig.getOptionalValue(prefix + ".source", String.class).orElse("local-root"))
                    .priority(appConfig.getOptionalValue(prefix + ".priority", Integer.class).orElse(i))
                    .trust(appConfig.getOptionalValue(prefix + ".trust", String.class).orElse("trusted"))
                    .build());
        }
        return peers;
    }

    private String configString(String key, String defaultValue) {
        return appConfig.getOptionalValue(key, String.class).orElse(defaultValue);
    }

    private int configInt(String key, int defaultValue) {
        return appConfig.getOptionalValue(key, Integer.class).orElse(defaultValue);
    }

    private long configLong(String key, long defaultValue) {
        return appConfig.getOptionalValue(key, Long.class).orElse(defaultValue);
    }

    private boolean configBoolean(String key, boolean defaultValue) {
        return appConfig.getOptionalValue(key, Boolean.class).orElse(defaultValue);
    }

    private List<String> configStringList(String key) {
        java.util.Optional<List<String>> listValue = appConfig.getOptionalValues(key, String.class);
        if (listValue.isPresent()) {
            return listValue.get().stream()
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return appConfig.getOptionalValue(key, String.class)
                .map(value -> java.util.Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .toList())
                .orElse(List.of());
    }

    void onStop(@Observes ShutdownEvent event) {
        log.info("Yano application shutting down...");
        if (yano != null) {
            log.info("Stopping Yano...");
            yano.close();
            log.info("Yano stopped");
        }
    }

    /**
     * Placeholder for CDI injection sites when producer control is not available
     * for the assembled node role.
     */
    private enum UnavailableProducerControl implements ProducerControl {
        INSTANCE;

        @Override
        public void startProducer() {
            throw unavailableRole("ProducerControl");
        }

        @Override
        public void stopProducer() {
            throw unavailableRole("ProducerControl");
        }

        @Override
        public void resetProducerToChainTip() {
            throw unavailableRole("ProducerControl");
        }
    }

    /**
     * Placeholder for CDI injection sites when devnet control is not available
     * for the assembled node role.
     */
    private enum UnavailableDevnetControl implements DevnetControl {
        INSTANCE;

        @Override
        public void rollbackDevnetToSlot(long targetSlot) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public DevnetRollbackResult rollbackDevnet(DevnetRollbackTarget target) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public SnapshotInfo createDevnetSnapshot(String name) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public void restoreDevnetSnapshot(String name) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public DevnetRestoreResult restoreDevnetSnapshotAndGetTip(String name) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public List<SnapshotInfo> listDevnetSnapshots() {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public void deleteDevnetSnapshot(String name) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public FundResult fundAddress(String address, long lovelace) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public TimeAdvanceResult advanceTimeBySlots(int slots) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public TimeAdvanceResult advanceTimeBySeconds(int seconds) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public long shiftGenesisAndStartProducer(int epochs) {
            throw unavailableRole("DevnetControl");
        }

        @Override
        public TimeAdvanceResult catchUpToWallClock() {
            throw unavailableRole("DevnetControl");
        }
    }

    private static IllegalStateException unavailableRole(String role) {
        return new IllegalStateException(role + " is not available for this Yano assembly");
    }

    private RollbackRetentionGenesisValues resolveRollbackRetentionGenesisValues(String resolvedShelleyGenesis) {
        try {
            NetworkGenesisConfig genesis = NetworkGenesisConfig.load(resolvedShelleyGenesis, null, null, null);
            return new RollbackRetentionGenesisValues(genesis.getEpochLength(), genesis.getActiveSlotsCoeff());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve Shelley genesis values for "
                    + ROLLBACK_RETENTION_EPOCHS + " from " + resolvedShelleyGenesis, e);
        }
    }

    RollbackRetentionSettings resolveRollbackRetentionSettings(RollbackRetentionGenesisValues genesisValues) {
        return RollbackRetentionPlanner.resolve(
                rollbackRetentionEpochs,
                genesisValues.epochLength(),
                genesisValues.activeSlotsCoeff(),
                utxoRollbackWindow,
                isConfigPropertyPresent(UTXO_ROLLBACK_WINDOW),
                accountStateEpochBlockDataRetentionLag,
                isConfigPropertyPresent(ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG),
                accountStateSnapshotRetentionEpochs,
                isConfigPropertyPresent(ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS),
                accountHistoryRollbackSafetySlots,
                isConfigPropertyPresent(ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS),
                blockBodyPruneDepth);
    }

    void putRollbackRetentionGlobals(Map<String, Object> globals, RollbackRetentionSettings settings) {
        globals.put(UTXO_ROLLBACK_WINDOW, settings.utxoRollbackWindow());
        globals.put(ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG,
                settings.accountStateEpochBlockDataRetentionLag());
        globals.put(ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS,
                settings.accountStateSnapshotRetentionEpochs());
        settings.accountHistoryRollbackSafetySlots().ifPresent(v ->
                globals.put(ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS, v));
        globals.put(BLOCK_BODY_PRUNE_DEPTH, settings.blockBodyPruneDepth());
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
        return GenesisFileResolver.resolve(
                userPath, magic, filename, Thread.currentThread().getContextClassLoader());
    }
}
