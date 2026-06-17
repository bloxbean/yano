package com.bloxbean.cardano.yano.runtime.ledger;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yano.api.ChainBlockReader;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.account.AccountStateStore;
import com.bloxbean.cardano.yano.api.account.AccountStateStoreContext;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.db.RocksDbAccess;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.GenesisBlockEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.ledgerstate.AccountHistoryStore;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCfNames;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateEventHandler;
import com.bloxbean.cardano.yano.ledgerstate.AdaPotTracker;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.EpochBoundaryProcessor;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yano.ledgerstate.EpochRewardCalculator;
import com.bloxbean.cardano.yano.ledgerstate.EpochStakeSnapshotService;
import com.bloxbean.cardano.yano.ledgerstate.NetworkConfigBuilder;
import com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter;
import com.bloxbean.cardano.yano.runtime.account.AccountStateStoreDiscovery;
import com.bloxbean.cardano.yano.runtime.chain.ByronGenesisUtxoMetadataStore;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateSnapshots;
import com.bloxbean.cardano.yano.runtime.chain.EraMetadataStore;
import com.bloxbean.cardano.yano.runtime.config.DefaultEpochParamProvider;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisValuesFactory;
import com.bloxbean.cardano.yano.runtime.era.EraProviderImpl;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStore;
import com.bloxbean.cardano.yano.runtime.utxo.UtxoStoreWriter;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Owns account-state/history derived-state wiring, epoch boundary processors,
 * governance processors, startup reconciliation, and snapshot/rollback hooks.
 */
public final class LedgerStateSubsystem implements Subsystem {
    private final YanoConfig config;
    private final RuntimeOptions runtimeOptions;
    private final ChainState chainState;
    private final ChainBlockReader chainBlockReader;
    private final EventBus eventBus;
    private final Logger log;
    private final RocksDbAccess rocksAccess;
    private final EraMetadataStore eraMetadataStore;
    private final ByronGenesisUtxoMetadataStore byronMetadataStore;
    private final ChainStateSnapshots snapshots;
    private final Supplier<UtxoStoreWriter> utxoStoreSupplier;
    private final Supplier<UtxoState> utxoStateSupplier;
    private final Supplier<byte[]> genesisHashSupplier;
    private final InMemoryDevnetGenesis inMemoryDevnetGenesis;
    private final AccountHistorySubsystem accountHistorySubsystem;

    private AccountStateStore accountStateStore;
    private AccountHistoryStore accountHistoryStore;
    private EpochBoundaryProcessor epochBoundaryProcessor;
    private AccountStateEventHandler accountStateEventHandler;
    private EpochParamProvider epochParamProvider;
    private EraProviderImpl eraService;
    private GenesisBootstrapData genesisBootstrapData = GenesisBootstrapData.empty();
    private boolean accountStateReconcilePending;
    private boolean startupDerivedStateRecovered;
    private boolean closed;

    public LedgerStateSubsystem(YanoConfig config,
                                RuntimeOptions runtimeOptions,
                                ChainState chainState,
                                EventBus eventBus,
                                Logger log,
                                RocksDbAccess rocksAccess,
                                EraMetadataStore eraMetadataStore,
                                ByronGenesisUtxoMetadataStore byronMetadataStore,
                                ChainStateSnapshots snapshots,
                                Supplier<UtxoStoreWriter> utxoStoreSupplier,
                                Supplier<UtxoState> utxoStateSupplier,
                                Supplier<byte[]> genesisHashSupplier,
                                InMemoryDevnetGenesis inMemoryDevnetGenesis) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.chainBlockReader = new ChainStateBlockReader(this.chainState);
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.log = Objects.requireNonNull(log, "log");
        this.rocksAccess = rocksAccess;
        this.eraMetadataStore = eraMetadataStore;
        this.byronMetadataStore = byronMetadataStore;
        this.snapshots = snapshots;
        this.utxoStoreSupplier = utxoStoreSupplier != null ? utxoStoreSupplier : () -> null;
        this.utxoStateSupplier = utxoStateSupplier != null ? utxoStateSupplier : () -> null;
        this.genesisHashSupplier = genesisHashSupplier != null ? genesisHashSupplier : () -> null;
        this.inMemoryDevnetGenesis = inMemoryDevnetGenesis;
        this.accountHistorySubsystem = new AccountHistorySubsystem(
                config,
                this.runtimeOptions,
                chainState,
                eventBus,
                log);
        initialize();
    }

    @Override
    public String name() {
        return "ledger-state";
    }

    public AccountStateStore accountStateStore() {
        return accountStateStore;
    }

    public AccountHistoryStore accountHistoryStore() {
        return accountHistoryStore;
    }

    public LedgerStateProvider ledgerStateProvider() {
        return accountStateStore;
    }

    public AccountHistoryProvider accountHistoryProvider() {
        return accountHistoryStore;
    }

    public EpochBoundaryProcessor epochBoundaryProcessor() {
        return epochBoundaryProcessor;
    }

    public EpochParamProvider epochParamProvider() {
        return epochParamProvider;
    }

    public EraProviderImpl eraService() {
        return eraService;
    }

    public GenesisBootstrapData currentGenesisBootstrapData() {
        return genesisBootstrapData != null ? genesisBootstrapData : GenesisBootstrapData.empty();
    }

    public EpochParamProvider effectiveEpochParamProvider() {
        if (accountStateStore instanceof DefaultAccountStateStore store) {
            EpochParamTracker tracker = store.getParamTracker();
            if (tracker != null && tracker.isEnabled()) {
                return tracker;
            }
        }
        return epochParamProvider;
    }

    public boolean epochParamsTrackingEnabled() {
        Object value = runtimeOptions.globals()
                .getOrDefault(YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, "false");
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    public List<RollbackCapableStore> rollbackCapableStores(UtxoStoreWriter utxoStore) {
        var stores = new ArrayList<RollbackCapableStore>();
        if (accountHistoryStore instanceof RollbackCapableStore rollbackStore) {
            stores.add(rollbackStore);
        }
        if (accountStateStore instanceof RollbackCapableStore rollbackStore) {
            stores.add(rollbackStore);
        }
        if (utxoStore instanceof RollbackCapableStore rollbackStore) {
            stores.add(rollbackStore);
        }
        if (chainState instanceof RollbackCapableStore rollbackStore) {
            stores.add(rollbackStore);
        }
        return stores;
    }

    public Map<String, Object> epochCalcStatus() {
        if (epochBoundaryProcessor == null) {
            return null;
        }
        var err = epochBoundaryProcessor.getLastVerificationError();
        if (err == null) {
            return null;
        }
        return Map.of(
                "status", "ERROR",
                "epoch", err.epoch(),
                "expectedTreasury", err.expectedTreasury().toString(),
                "actualTreasury", err.actualTreasury().toString(),
                "treasuryDiff", err.treasuryDiff().toString(),
                "expectedReserves", err.expectedReserves().toString(),
                "actualReserves", err.actualReserves().toString(),
                "reservesDiff", err.reservesDiff().toString());
    }

    public void refreshGenesisBootstrapData(NetworkGenesisConfig networkGenesisConfig) {
        if (networkGenesisConfig == null) {
            return;
        }
        refreshGenesisBootstrapData(networkGenesisConfig.getShelleyGenesisData());
    }

    public void refreshGenesisBootstrapData(
            com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData shelleyGenesisData) {
        if (shelleyGenesisData == null) {
            return;
        }
        byte[] hash = genesisHashSupplier.get();
        String hashHex = hash != null ? HexUtil.encodeHexString(hash) : null;
        this.genesisBootstrapData = new GenesisBootstrapData(hashHex, shelleyGenesisData.bootstrap());
        var shelley = this.genesisBootstrapData.shelley();
        if (shelley != null && shelley.hasStaking()) {
            log.info("Genesis bootstrap payload ready: hash={}, pools={}, delegations={}",
                    hashHex, shelley.pools().size(), shelley.delegations().size());
        }
    }

    public void completeStartupRecovery(Runnable utxoRecovery) {
        if (startupDerivedStateRecovered) {
            return;
        }

        try {
            if (utxoRecovery != null) {
                utxoRecovery.run();
            }

            if (accountStateReconcilePending) {
                reconcileAccountStateStore();
                accountStateReconcilePending = false;
            }
            accountHistorySubsystem.completeStartupRecovery();

            if (epochBoundaryProcessor != null) {
                epochBoundaryProcessor.recoverInterruptedBoundary();
            }

            publishDirectStartGenesisBootstrapIfNeeded();
            startupDerivedStateRecovered = true;
        } catch (Throwable t) {
            throw new IllegalStateException("Startup ledger-state recovery failed", t);
        }
    }

    public void completeStartupRecovery() {
        completeStartupRecovery(null);
    }

    public void reinitializeAndReconcileAfterSnapshotRestore() {
        if (accountStateStore != null) {
            accountStateStore.reinitialize();
            try {
                accountStateStore.reconcile(chainBlockReader);
            } catch (Throwable t) {
                throw new IllegalStateException("Account state reconciliation after snapshot restore failed", t);
            }
        }
        accountHistorySubsystem.reinitializeAndReconcileAfterSnapshotRestore(rocksAccess);
        accountHistoryStore = accountHistorySubsystem.store();
    }

    public boolean isAccountHistoryPruneServiceRunning() {
        return accountHistorySubsystem.isPruneServiceRunning();
    }

    public boolean pauseAccountHistoryPruneServiceAndAwait(Duration timeout) {
        return accountHistorySubsystem.pausePruneServiceAndAwait(timeout);
    }

    public void resumeAfterSnapshotRestore(boolean accountHistoryPrunePaused) {
        accountHistorySubsystem.resumeAfterSnapshotRestore(accountHistoryPrunePaused);
    }

    public void ensureAccountHistoryRolledBack(Point rollbackPoint) {
        accountHistorySubsystem.ensureRolledBack(rollbackPoint);
    }

    /**
     * Delete exported epoch snapshot directories after a rollback target.
     */
    public void cleanupSnapshotExportsAfterRollback(int targetEpoch) {
        Object exportDirObj = runtimeOptions.globals().get(YanoPropertyKeys.SnapshotExport.DIR);
        if (exportDirObj == null) {
            return;
        }
        String exportDir = String.valueOf(exportDirObj);
        if (exportDir.isBlank()) {
            return;
        }

        java.io.File dir = new java.io.File(exportDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }

        java.io.File[] epochDirs = dir.listFiles(f -> f.isDirectory() && f.getName().startsWith("epoch="));
        if (epochDirs == null) {
            return;
        }

        int deleted = 0;
        for (java.io.File epochDir : epochDirs) {
            try {
                int epoch = Integer.parseInt(epochDir.getName().substring("epoch=".length()));
                if (epoch > targetEpoch) {
                    Files.walk(epochDir.toPath())
                            .sorted(java.util.Comparator.reverseOrder())
                            .map(java.nio.file.Path::toFile)
                            .forEach(java.io.File::delete);
                    deleted++;
                }
            } catch (NumberFormatException ignored) {
            } catch (Exception e) {
                log.warn("Failed to delete epoch snapshot export dir {}: {}", epochDir, e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("Ledger snapshot export cleanup deleted {} epoch directories after epoch {}", deleted, targetEpoch);
        }
    }

    public void handleEraTransition(BlockAppliedEvent event) {
        if (event == null || event.era() == null || eraMetadataStore == null) {
            return;
        }

        eraMetadataStore.setEraStartSlot(event.era().getValue(), event.slot());

        if (event.era().getValue() > com.bloxbean.cardano.yaci.core.model.Era.Byron.getValue()
                && utxoStoreSupplier.get() instanceof DefaultUtxoStore defaultUtxo
                && eraMetadataStore.getShelleyStartUtxoTotal().isEmpty()) {
            BigInteger total = defaultUtxo.computeTotalUtxoLovelace();
            eraMetadataStore.setShelleyStartUtxoTotal(total);
            log.info("Captured Shelley-start UTXO total at era transition: {} lovelace", total);
            buildLazyCfNetworkConfigAfterBoundaryCapture();
        }
    }

    @Override
    public void start() {
        accountHistorySubsystem.start();
    }

    @Override
    public void stop() {
        accountHistorySubsystem.pauseBackgroundServices();
    }

    public void closeEventHandlers() {
        try {
            if (accountStateEventHandler != null) {
                accountStateEventHandler.close();
            }
        } catch (Exception ignored) {
        } finally {
            accountStateEventHandler = null;
        }
        accountHistorySubsystem.closeEventHandlers();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        stop();
        closeEventHandlers();
        accountHistorySubsystem.close();
        closed = true;
    }

    @Override
    public SubsystemHealth health() {
        if (closed) {
            return SubsystemHealth.down(name(), "closed");
        }
        return SubsystemHealth.up(name());
    }

    private void initialize() {
        try {
            boolean accountStateEnabled = resolveBoolean(
                    runtimeOptions.globals(), YanoPropertyKeys.AccountState.ENABLED, false);
            NetworkGenesisConfig networkGenesisConfig = resolveNetworkGenesisConfig();
            refreshGenesisBootstrapData(networkGenesisConfig);

            if (accountStateEnabled) {
                initializeAccountState(networkGenesisConfig);
            } else {
                log.info("Account state store not initialized (enabled={})", accountStateEnabled);
                if (resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.AccountHistory.ENABLED, false)) {
                    throw new IllegalStateException(
                            "Account history requested but not initialized because account-state is disabled");
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to initialize ledger-state subsystem", t);
        }
    }

    private void initializeAccountState(NetworkGenesisConfig networkGenesisConfig) {
        if (networkGenesisConfig != null) {
            long firstNonByronSlot = DefaultEpochParamProvider.resolveFirstNonByronSlot(
                    networkGenesisConfig.getNetworkMagic(),
                    networkGenesisConfig.hasByronGenesis());
            this.epochParamProvider = DefaultEpochParamProvider.fromNetworkGenesisConfig(
                    networkGenesisConfig, firstNonByronSlot);
        } else {
            throw new IllegalStateException(
                    "Account-state requires genesis configuration (file-based or in-memory devnet genesis) "
                            + "to initialize epoch parameters");
        }

        if (eraMetadataStore != null) {
            this.eraService = new EraProviderImpl(eraMetadataStore, epochParamProvider.getEpochSlotCalc());
        }

        var storeContext = new AccountStateStoreContext(
                chainBlockReader, rocksAccess, runtimeOptions.globals(), log, epochParamProvider);
        this.accountStateStore = AccountStateStoreDiscovery.discover(
                storeContext, Thread.currentThread().getContextClassLoader());
        if (config.isEnableClient()) {
            this.accountStateReconcilePending = true;
            log.info("Account state store initialized; reconciliation deferred until startup recovery");
        } else {
            reconcileAccountStateStore();
        }

        if (accountStateStore instanceof DefaultAccountStateStore defaultStore) {
            wireDefaultAccountStateStore(defaultStore, networkGenesisConfig);
        }

        accountHistorySubsystem.initialize(rocksAccess, epochParamProvider);
        this.accountHistoryStore = accountHistorySubsystem.store();

        this.accountStateEventHandler = new AccountStateEventHandler(eventBus, accountStateStore);
        log.info("Account state store initialized ({}); event handler registered",
                accountStateStore.getClass().getSimpleName());
        if (!config.isEnableClient()) {
            publishDirectStartGenesisBootstrapIfNeeded();
        }
    }

    private void wireDefaultAccountStateStore(DefaultAccountStateStore defaultStore,
                                              NetworkGenesisConfig networkGenesisConfig) {
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState != null) {
            defaultStore.setUtxoState(utxoState);
        }

        boolean snapshotAmountsEnabled = resolveBoolean(
                runtimeOptions.globals(), YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED, false);
        if (snapshotAmountsEnabled) {
            defaultStore.setStakeSnapshotService(new EpochStakeSnapshotService(true));
            String balMode = String.valueOf(runtimeOptions.globals()
                    .getOrDefault(YanoPropertyKeys.EpochSnapshot.BALANCE_MODE, "full-scan"));
            defaultStore.setBalanceMode(balMode);
            log.info("Epoch stake snapshot amounts enabled (balance-mode={})", balMode);
        }

        boolean adaPotEnabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.Ledger.ADAPOT_ENABLED, false);
        if (adaPotEnabled && rocksAccess != null) {
            var cfHandle = (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(AccountStateCfNames.ACCT_STATE);
            if (cfHandle != null) {
                if (networkGenesisConfig == null) {
                    throw new IllegalStateException(
                            "AdaPot enabled but no Shelley genesis file configured - cannot resolve maxLovelaceSupply");
                }
                BigInteger maxLovelaceSupply = BigInteger.valueOf(
                        networkGenesisConfig.getShelleyGenesisData().maxLovelaceSupply());
                var adaPotTracker = new AdaPotTracker(
                        (RocksDB) rocksAccess.getDb(), cfHandle, true, maxLovelaceSupply);
                defaultStore.setAdaPotTracker(adaPotTracker);
                log.info("AdaPot tracker enabled (maxLovelaceSupply={})", maxLovelaceSupply);
            }
        }

        boolean epochParamsEnabled = resolveBoolean(
                runtimeOptions.globals(), YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, false);
        EpochParamTracker paramTrackerInstance = wireEpochParamTracker(defaultStore, epochParamsEnabled);
        EpochRewardCalculator rewardCalcInstance = wireRewardCalculator(defaultStore);
        org.cardanofoundation.rewards.calculation.config.NetworkConfig cfNetConfig =
                buildCfNetworkConfig(networkGenesisConfig);
        if (rewardCalcInstance != null && cfNetConfig != null) {
            rewardCalcInstance.setCfNetworkConfig(cfNetConfig);
        }

        if (eraService != null) {
            defaultStore.setEraProvider(eraService);
        }
        Integer firstConwayEpoch = eraService != null ? eraService.resolveFirstConwayEpochOrNull() : null;
        log.info("firstConwayEpoch resolved: {}", firstConwayEpoch);

        wireEpochBoundaryProcessor(defaultStore, adaPotEnabled, epochParamsEnabled,
                rewardCalcInstance, paramTrackerInstance, cfNetConfig);
        wireGovernance(defaultStore, paramTrackerInstance);

        defaultStore.migrateAcctRegSlots();
        wireSnapshotExporter(defaultStore);
    }

    private EpochParamTracker wireEpochParamTracker(DefaultAccountStateStore defaultStore,
                                                    boolean epochParamsEnabled) {
        EpochParamTracker paramTrackerInstance = null;
        if (epochParamsEnabled && rocksAccess != null) {
            var cfEpochParams = (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(AccountStateCfNames.EPOCH_PARAMS);
            paramTrackerInstance = new EpochParamTracker(
                    epochParamProvider, true, (RocksDB) rocksAccess.getDb(), cfEpochParams);
            if (eraService != null) {
                paramTrackerInstance.setEraProvider(eraService);
            }
            defaultStore.setParamTracker(paramTrackerInstance);
            log.info("Epoch param tracker enabled (with RocksDB persistence)");
        } else if (epochParamsEnabled) {
            paramTrackerInstance = new EpochParamTracker(epochParamProvider, true);
            if (eraService != null) {
                paramTrackerInstance.setEraProvider(eraService);
            }
            defaultStore.setParamTracker(paramTrackerInstance);
            log.info("Epoch param tracker enabled (in-memory only)");
        }
        return paramTrackerInstance;
    }

    private EpochRewardCalculator wireRewardCalculator(DefaultAccountStateStore defaultStore) {
        boolean rewardsEnabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.Ledger.REWARDS_ENABLED, false);
        if (!rewardsEnabled || rocksAccess == null) {
            return null;
        }
        var cfState = (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(AccountStateCfNames.ACCT_STATE);
        var cfSnapshot = (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT);
        if (cfState == null || cfSnapshot == null) {
            return null;
        }
        var rewardCalcInstance = new EpochRewardCalculator(
                (RocksDB) rocksAccess.getDb(), cfState, cfSnapshot, true);
        rewardCalcInstance.setLedgerStateProvider(defaultStore);
        rewardCalcInstance.setAccountStateStore(defaultStore);
        rewardCalcInstance.setEraProvider(eraService);
        defaultStore.setRewardCalculator(rewardCalcInstance);
        log.info("Epoch reward calculator enabled");
        return rewardCalcInstance;
    }

    private org.cardanofoundation.rewards.calculation.config.NetworkConfig buildCfNetworkConfig(
            NetworkGenesisConfig networkGenesisConfig) {
        long magic = config.getProtocolMagic();
        if (networkGenesisConfig != null) {
            var overrides = buildOverridesFromChainState(networkGenesisConfig);
            try {
                var genesisValues = NetworkGenesisValuesFactory.build(networkGenesisConfig, overrides);
                var cfNetConfig = NetworkConfigBuilder.build(genesisValues);
                log.info("CF NetworkConfig built from genesis");
                return cfNetConfig;
            } catch (IllegalStateException e) {
                if (chainState.getTip() == null) {
                    log.info("Unknown+Byron network: cfNetConfig deferred until boundary capture (fresh sync)");
                    return null;
                }
                throw e;
            }
        }

        boolean known = magic == 764824073L || magic == 1L || magic == 2L;
        if (known) {
            var cfNetConfig = EpochRewardCalculator.resolveNetworkConfig(magic);
            log.info("CF NetworkConfig resolved from built-in config for known network magic={}", magic);
            return cfNetConfig;
        }
        throw new IllegalStateException(
                "No genesis files configured for unknown network (magic=" + magic + "). "
                        + "Cannot build CF NetworkConfig without genesis.");
    }

    private void wireEpochBoundaryProcessor(DefaultAccountStateStore defaultStore,
                                            boolean adaPotEnabled,
                                            boolean epochParamsEnabled,
                                            EpochRewardCalculator rewardCalcInstance,
                                            EpochParamTracker paramTrackerInstance,
                                            org.cardanofoundation.rewards.calculation.config.NetworkConfig cfNetConfig) {
        boolean rewardsEnabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.Ledger.REWARDS_ENABLED, false);
        if (!adaPotEnabled && !rewardsEnabled && !epochParamsEnabled) {
            return;
        }

        long magic = config.getProtocolMagic();
        defaultStore.setNetworkMagic(magic);
        epochBoundaryProcessor = new EpochBoundaryProcessor(
                defaultStore.getAdaPotTracker(),
                rewardCalcInstance,
                paramTrackerInstance,
                epochParamProvider,
                magic,
                cfNetConfig);
        defaultStore.setEpochBoundaryProcessor(epochBoundaryProcessor);
        epochBoundaryProcessor.setSnapshotCreator(defaultStore);

        if (utxoStoreSupplier.get() instanceof DefaultUtxoStore defaultUtxoStore
                && byronMetadataStore != null) {
            defaultUtxoStore.wireAllegraBootstrapRemoval(byronMetadataStore);
        }

        boolean exitOnCalcError = resolveBoolean(
                runtimeOptions.globals(), YanoPropertyKeys.Ledger.EXIT_ON_EPOCH_CALC_ERROR, false);
        epochBoundaryProcessor.setExitOnEpochCalcError(exitOnCalcError);

        int checkpointInterval = (int) parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.Ledger.AUTO_CHECKPOINT_INTERVAL), 0);
        if (checkpointInterval > 0 && snapshots != null) {
            Path snapshotsDir = Path.of(snapshots.getDbPath()).getParent().resolve("epoch-snapshots");
            epochBoundaryProcessor.setAutoCheckpoint(checkpointInterval, epoch -> {
                try {
                    Path epochDir = snapshotsDir.resolve("epoch-" + epoch);
                    if (Files.exists(epochDir)) {
                        return;
                    }
                    Files.createDirectories(snapshotsDir);
                    snapshots.createSnapshot(epochDir.toString());
                    log.info("Auto-checkpoint created for epoch {} at {}", epoch, epochDir);
                } catch (Exception e) {
                    log.warn("Auto-checkpoint failed for epoch {}: {}", epoch, e.getMessage());
                }
            });
            log.info("Auto-checkpoint enabled: every {} epochs -> {}", checkpointInterval, snapshotsDir);
        }

        log.info("Epoch boundary processor wired (adapot={}, rewards={}, params={}, exitOnCalcError={})",
                adaPotEnabled, rewardsEnabled, epochParamsEnabled, exitOnCalcError);
    }

    private void wireGovernance(DefaultAccountStateStore defaultStore,
                                EpochParamTracker paramTrackerInstance) {
        boolean governanceEnabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED, false);
        if (!governanceEnabled || rocksAccess == null) {
            return;
        }

        var rocksDb = (RocksDB) rocksAccess.getDb();
        var cfState = (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(AccountStateCfNames.ACCT_STATE);
        var cfSnapshot = (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT);
        var cfDelta = (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(AccountStateCfNames.ACCT_DELTA);
        if (cfState == null) {
            return;
        }

        var govStore = new com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore(rocksDb, cfState);
        var govBlockProcessor = new com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceBlockProcessor(
                govStore, paramTrackerInstance != null ? paramTrackerInstance : epochParamProvider);
        defaultStore.setGovernanceBlockProcessor(govBlockProcessor);

        if (epochBoundaryProcessor != null) {
            var tallyCalc = new com.bloxbean.cardano.yano.ledgerstate.governance.ratification.VoteTallyCalculator();
            var ratEngine = new com.bloxbean.cardano.yano.ledgerstate.governance.ratification.RatificationEngine(govStore, tallyCalc);
            var enactProc = new com.bloxbean.cardano.yano.ledgerstate.governance.ratification.EnactmentProcessor(govStore, paramTrackerInstance);
            var dropService = new com.bloxbean.cardano.yano.ledgerstate.governance.ratification.ProposalDropService();
            var drepDistCalc = new com.bloxbean.cardano.yano.ledgerstate.governance.epoch.DRepDistributionCalculator(
                    rocksDb, cfState, cfSnapshot, govStore);
            var drepExpiryCalc = new com.bloxbean.cardano.yano.ledgerstate.governance.epoch.DRepExpiryCalculator();

            var govEpochProcessor = new com.bloxbean.cardano.yano.ledgerstate.governance.epoch.GovernanceEpochProcessor(
                    rocksDb, cfState, cfDelta,
                    govStore, drepDistCalc, drepExpiryCalc,
                    ratEngine, enactProc, dropService,
                    epochParamProvider,
                    paramTrackerInstance,
                    defaultStore.getAdaPotTracker(),
                    defaultStore::resolvePoolStakeForEpoch,
                    defaultStore.asRewardRestStore(),
                    config.getConwayGenesisFile());
            if (eraService != null) {
                govEpochProcessor.setEraProvider(eraService);
            }
            govEpochProcessor.setBoundaryDeltaWriter(defaultStore::commitBoundaryDelta);
            epochBoundaryProcessor.setGovernanceEpochProcessor(govEpochProcessor);
        }
        log.info("Governance subsystem enabled (block processor + epoch processor)");
    }

    private void wireSnapshotExporter(DefaultAccountStateStore defaultStore) {
        boolean exportEnabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.SnapshotExport.ENABLED, false);
        if (!exportEnabled) {
            return;
        }

        String exportDir = String.valueOf(runtimeOptions.globals().getOrDefault(
                YanoPropertyKeys.SnapshotExport.DIR, "data"));
        var loader = ServiceLoader.load(EpochSnapshotExporter.class);
        var impl = loader.findFirst();
        if (impl.isPresent()) {
            var exporter = impl.get();
            exporter.setOutputDir(exportDir);
            exporter.setNetworkMagic(config.getProtocolMagic());
            var exportOptions = Map.of(
                    "stake", String.valueOf(runtimeOptions.globals().getOrDefault(
                            YanoPropertyKeys.SnapshotExport.STAKE, "false")),
                    "drep-dist", String.valueOf(runtimeOptions.globals().getOrDefault(
                            YanoPropertyKeys.SnapshotExport.DREP_DIST, "true")),
                    "adapot", String.valueOf(runtimeOptions.globals().getOrDefault(
                            YanoPropertyKeys.SnapshotExport.ADAPOT, "true")),
                    "proposals", String.valueOf(runtimeOptions.globals().getOrDefault(
                            YanoPropertyKeys.SnapshotExport.PROPOSALS, "true")));
            exporter.configure(exportOptions);
            defaultStore.setSnapshotExporter(exporter);
            if (epochBoundaryProcessor != null) {
                epochBoundaryProcessor.setSnapshotExporter(exporter);
            }
            log.info("Epoch snapshot export enabled: {} -> {}", exporter.getClass().getSimpleName(), exportDir);
        } else {
            log.warn("Snapshot export enabled but no EpochSnapshotExporter implementation found on classpath. "
                    + "Add the epoch-export module to enable parquet export.");
        }
    }

    private void reconcileAccountStateStore() {
        if (accountStateStore == null) {
            return;
        }
        try {
            accountStateStore.reconcile(chainBlockReader);
            log.info("Account state reconciliation complete at startup");
        } catch (Throwable t) {
            throw new IllegalStateException("Account state reconciliation failed at startup", t);
        }
    }

    private void publishDirectStartGenesisBootstrapIfNeeded() {
        boolean failClosed = false;
        try {
            if (accountStateStore == null || epochParamProvider == null || chainState.getTip() == null) {
                return;
            }

            int firstNonByronEpoch = epochParamProvider.getEpochSlotCalc()
                    .slotToEpoch(epochParamProvider.getShelleyStartSlot());
            if (firstNonByronEpoch != 0) {
                return;
            }

            GenesisBootstrapData payload = currentGenesisBootstrapData();
            failClosed = shouldFailClosedGenesisBootstrapPublication(payload);
            if (eraService == null) {
                ensureGenesisBootstrapEraAvailable(payload, false, "era metadata service is unavailable");
                return;
            }

            var startEra = eraService.getEarliestKnownEra();
            if (startEra.isEmpty()) {
                ensureGenesisBootstrapEraAvailable(payload, false, "earliest known era is unavailable");
                return;
            }

            Point firstBlock = chainState.getFirstBlock();
            long slot = firstBlock != null ? firstBlock.getSlot() : epochParamProvider.getShelleyStartSlot();
            String hash = firstBlock != null && firstBlock.getHash() != null ? firstBlock.getHash() : "";
            EventMetadata meta = EventMetadata.builder()
                    .origin("runtime-startup")
                    .slot(slot)
                    .blockNo(0)
                    .blockHash(hash)
                    .build();

            String producerPoolHash = payload.hasShelleyStaking() ? resolveRequiredStoredGenesisProducerPoolHash() : null;
            eventBus.publish(new GenesisBlockEvent(startEra.get(), 0, slot, 0, hash, payload, producerPoolHash),
                    meta, PublishOptions.builder().build());
            log.info("Published startup genesis bootstrap event for direct-start chain");
        } catch (Throwable t) {
            if (failClosed) {
                throw new RuntimeException("Failed to publish startup genesis bootstrap event", t);
            }
            log.warn("Failed to publish startup genesis bootstrap event: {}", t.toString());
        }
    }

    public static boolean shouldFailClosedGenesisBootstrapPublication(GenesisBootstrapData payload) {
        return payload != null && (payload.hasShelleyStaking() || payload.shelleyGenesisHashHex() != null);
    }

    static void ensureGenesisBootstrapEraAvailable(GenesisBootstrapData payload,
                                                   boolean available,
                                                   String reason) {
        if (!available && shouldFailClosedGenesisBootstrapPublication(payload)) {
            String detail = reason != null && !reason.isBlank() ? reason : "era metadata is unavailable";
            throw new IllegalStateException("Cannot publish startup genesis bootstrap event: " + detail);
        }
    }

    private String resolveRequiredStoredGenesisProducerPoolHash() {
        try {
            byte[] blockBytes = chainState.getBlockByNumber(0L);
            if (blockBytes == null) {
                throw new IllegalStateException("stored block 0 body is missing");
            }
            var block = BlockSerializer.INSTANCE.deserialize(blockBytes);
            HeaderBody headerBody = block != null && block.getHeader() != null
                    ? block.getHeader().getHeaderBody() : null;
            if (headerBody == null || headerBody.getBlockNumber() != 0) {
                throw new IllegalStateException("stored block 0 has no valid header body");
            }

            String issuerVkey = headerBody.getIssuerVkey();
            if (issuerVkey == null || issuerVkey.isBlank()) {
                throw new IllegalStateException("stored block 0 has no issuer vkey");
            }

            return HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(issuerVkey)));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to derive genesis producer pool hash from stored block 0", t);
        }
    }

    private void buildLazyCfNetworkConfigAfterBoundaryCapture() {
        if (inMemoryDevnetGenesis == null
                && (config.getShelleyGenesisFile() == null || config.getShelleyGenesisFile().isBlank())) {
            return;
        }
        try {
            var networkGenesisConfig = resolveNetworkGenesisConfigForLazyBoundary();
            var lazyOverrides = buildOverridesFromChainState(networkGenesisConfig);
            var genesisValues = NetworkGenesisValuesFactory.build(networkGenesisConfig, lazyOverrides);
            var lazyCfConfig = NetworkConfigBuilder.build(genesisValues);

            if (accountStateStore instanceof DefaultAccountStateStore defaultStore) {
                var rewardCalculator = defaultStore.getRewardCalculator();
                if (rewardCalculator != null) {
                    rewardCalculator.setCfNetworkConfig(lazyCfConfig);
                }
            }
            if (epochBoundaryProcessor != null) {
                epochBoundaryProcessor.setCfNetworkConfig(lazyCfConfig);
            }
            log.info("Lazily built cfNetConfig after boundary capture for unknown+Byron network");
        } catch (Exception e) {
            log.error("Failed to lazily build cfNetConfig after boundary capture: {}", e.getMessage());
            throw new RuntimeException("Failed to lazily build cfNetConfig after boundary capture", e);
        }
    }

    private NetworkGenesisConfig resolveNetworkGenesisConfig() {
        if (inMemoryDevnetGenesis != null) {
            log.info("Using in-memory devnet genesis");
            return NetworkGenesisConfig.fromInMemory(
                    inMemoryDevnetGenesis.shelley(),
                    inMemoryDevnetGenesis.byron(),
                    inMemoryDevnetGenesis.conway());
        }
        if (config.getShelleyGenesisFile() != null && !config.getShelleyGenesisFile().isBlank()) {
            return NetworkGenesisConfig.load(
                    config.getShelleyGenesisFile(),
                    config.getByronGenesisFile(),
                    config.getAlonzoGenesisFile(),
                    config.getConwayGenesisFile());
        }
        return null;
    }

    private NetworkGenesisConfig resolveNetworkGenesisConfigForLazyBoundary() {
        return inMemoryDevnetGenesis != null
                ? NetworkGenesisConfig.fromInMemory(
                        inMemoryDevnetGenesis.shelley(),
                        inMemoryDevnetGenesis.byron(),
                        inMemoryDevnetGenesis.conway())
                : NetworkGenesisConfig.load(
                        config.getShelleyGenesisFile(),
                        config.getByronGenesisFile(),
                        null,
                        config.getConwayGenesisFile());
    }

    private NetworkGenesisValuesFactory.Overrides buildOverridesFromChainState(
            NetworkGenesisConfig networkGenesisConfig) {
        BigInteger overrideUtxo = null;
        Integer overrideShelleyEpoch = null;
        Integer overrideAllegraEpoch = null;
        Integer overrideVasilEpoch = null;

        if (eraMetadataStore != null) {
            overrideUtxo = eraMetadataStore.getShelleyStartUtxoTotal().orElse(null);
            var epochCalc = epochParamProvider != null ? epochParamProvider.getEpochSlotCalc() : null;
            if (epochCalc != null) {
                var firstNonByronSlot = eraMetadataStore.getFirstNonByronEraStartSlot();
                if (firstNonByronSlot.isPresent()) {
                    overrideShelleyEpoch = epochCalc.slotToEpoch(firstNonByronSlot.getAsLong());
                }
                var allegraSlot = eraMetadataStore.getEraStartSlot(
                        com.bloxbean.cardano.yaci.core.model.Era.Allegra.getValue());
                if (allegraSlot.isPresent()) {
                    overrideAllegraEpoch = epochCalc.slotToEpoch(allegraSlot.getAsLong());
                }
                var babbageSlot = eraMetadataStore.getEraStartSlot(
                        com.bloxbean.cardano.yaci.core.model.Era.Babbage.getValue());
                if (babbageSlot.isPresent()) {
                    overrideVasilEpoch = epochCalc.slotToEpoch(babbageSlot.getAsLong());
                }
            }
        }

        return new NetworkGenesisValuesFactory.Overrides(
                overrideUtxo, overrideShelleyEpoch, overrideAllegraEpoch, overrideVasilEpoch);
    }

    private static boolean resolveBoolean(Map<String, Object> globals, String key, boolean def) {
        Object value = globals.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return def;
    }

    private static long parseLong(Object obj, long def) {
        if (obj instanceof Number number) {
            return number.longValue();
        }
        if (obj != null) {
            try {
                return Long.parseLong(String.valueOf(obj));
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    /**
     * Chain block reader adapter backed by the configured chain-state store.
     */
    private record ChainStateBlockReader(ChainState chainState) implements ChainBlockReader {
        private ChainStateBlockReader {
            Objects.requireNonNull(chainState, "chainState");
        }

        @Override
        public ChainTip getLocalTip() {
            return chainState.getTip();
        }

        @Override
        public byte[] getBlockByNumber(long blockNumber) {
            return chainState.getBlockByNumber(blockNumber);
        }

        @Override
        public com.bloxbean.cardano.yaci.core.model.Era getBlockEra(long blockNumber) {
            return chainState.getBlockEra(blockNumber);
        }
    }
}
