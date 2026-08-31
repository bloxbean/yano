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
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCfNames;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateEventHandler;
import com.bloxbean.cardano.yano.ledgerstate.AdaPotTracker;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.EpochBoundaryProcessor;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yano.ledgerstate.EpochRewardCalculator;
import com.bloxbean.cardano.yano.ledgerstate.EpochStakeSnapshotService;
import com.bloxbean.cardano.yano.ledgerstate.NetworkConfigBuilder;
import com.bloxbean.cardano.yano.runtime.account.AccountStateStoreDiscovery;
import com.bloxbean.cardano.yano.runtime.chain.ArchiveChainStateCapabilities;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
    private volatile com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink epochArchiveStagingSink =
            com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.NOOP;

    private AccountStateStore accountStateStore;
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
        initialize();
    }

    @Override
    public String name() {
        return "ledger-state";
    }

    public AccountStateStore accountStateStore() {
        return accountStateStore;
    }

    public LedgerStateProvider ledgerStateProvider() {
        return accountStateStore;
    }

    public EpochBoundaryProcessor epochBoundaryProcessor() {
        return epochBoundaryProcessor;
    }

    public void setEpochArchiveStagingSink(
            com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink sink) {
        epochArchiveStagingSink = sink != null ? sink
                : com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.NOOP;
        if (accountStateStore instanceof DefaultAccountStateStore store) {
            store.setEpochArchiveStagingSink(epochArchiveStagingSink);
        } else if (epochBoundaryProcessor != null) {
            epochBoundaryProcessor.setEpochArchiveStagingSink(epochArchiveStagingSink);
        }
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
        return EpochCalcStatusMapper.map(
                epochBoundaryProcessor.getLastVerificationError(),
                epochBoundaryProcessor.getLastBoundaryTelemetry());
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
            // A block body is durably stored before its epoch-transition events run. If the
            // process dies inside the boundary, UTXO/account reconciliation would otherwise
            // apply that first new-epoch block before SNAP resumes. Finish the journaled
            // boundary against the still-canonical pre-block derived state first.
            if (epochBoundaryProcessor != null) {
                epochBoundaryProcessor.recoverInterruptedBoundary();
            }

            if (utxoRecovery != null) {
                utxoRecovery.run();
            }

            if (accountStateReconcilePending) {
                reconcileAccountStateStore();
                accountStateReconcilePending = false;
            }

            if (accountStateStore instanceof DefaultAccountStateStore defaultStore) {
                requirePointerIndexReadyIfApplicable(
                        utxoStateSupplier.get(), defaultStore::requirePointerIndexReady, log);
            }

            publishDirectStartGenesisBootstrapIfNeeded();
            startupDerivedStateRecovered = true;
        } catch (Throwable t) {
            throw new IllegalStateException("Startup ledger-state recovery failed", t);
        }
    }

    static void requirePointerIndexReadyIfApplicable(
            UtxoState utxoState, Consumer<Boolean> readinessCheck, Logger log) {
        Objects.requireNonNull(readinessCheck, "readinessCheck");
        Objects.requireNonNull(log, "log");
        if (utxoState == null) {
            log.info("Pointer-index readiness check not applicable: UTXO state is unavailable");
            return;
        }
        if (!utxoState.isPointerIndexApplicable()) {
            log.info("Pointer-index readiness check not applicable: "
                    + "UTXO state is disabled, filtered, or lacks a complete stake source");
            return;
        }

        boolean ready = utxoState.isPointerIndexReadyAtCurrentCoordinate();
        if (!ready) {
            log.error("Pointer-index readiness check failed: "
                    + "no usable completeness marker at the current UTXO coordinate");
        }
        readinessCheck.accept(ready);
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
    }

    public void handleEraTransition(BlockAppliedEvent event) {
        if (event == null || event.era() == null || eraMetadataStore == null) {
            return;
        }

        eraMetadataStore.setEraStartSlot(event.era().getValue(), event.slot());

        if (event.era().getValue() > com.bloxbean.cardano.yaci.core.model.Era.Byron.getValue()) {
            captureShelleyStartUtxoTotalIfNeeded();
        }
    }

    private void captureShelleyStartUtxoTotalIfNeeded() {
        if (eraMetadataStore == null || eraMetadataStore.getShelleyStartUtxoTotal().isPresent()) {
            return;
        }
        if (utxoStoreSupplier.get() instanceof DefaultUtxoStore defaultUtxo) {
            BigInteger total = defaultUtxo.computeTotalUtxoLovelace();
            eraMetadataStore.setShelleyStartUtxoTotal(total);
            log.info("Captured Shelley-start UTXO total at era transition: {} lovelace", total);
            buildLazyCfNetworkConfigAfterBoundaryCapture();
        }
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
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
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        stop();
        closeEventHandlers();
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
            wireUtxoMetadataDependencies();

            if (accountStateEnabled) {
                initializeAccountState(networkGenesisConfig);
            } else {
                log.info("Account state store not initialized (enabled={})", accountStateEnabled);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to initialize ledger-state subsystem", t);
        }
    }

    private void wireUtxoMetadataDependencies() {
        if (utxoStoreSupplier.get() instanceof DefaultUtxoStore defaultUtxoStore) {
            if (byronMetadataStore != null) {
                defaultUtxoStore.wireAllegraBootstrapRemoval(byronMetadataStore);
            }
            defaultUtxoStore.setShelleyStartBoundaryCapture(this::captureShelleyStartUtxoTotalIfNeeded);
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
        this.accountStateReconcilePending = true;
        log.info("Account state store initialized; reconciliation deferred until startup recovery");

        if (accountStateStore instanceof DefaultAccountStateStore defaultStore) {
            wireDefaultAccountStateStore(defaultStore, networkGenesisConfig);
        }

        this.accountStateEventHandler = new AccountStateEventHandler(eventBus, accountStateStore);
        log.info("Account state store initialized ({}); event handler registered",
                accountStateStore.getClass().getSimpleName());
    }

    private void wireDefaultAccountStateStore(DefaultAccountStateStore defaultStore,
                                              NetworkGenesisConfig networkGenesisConfig) {
        defaultStore.setChainBlockReader(chainBlockReader);
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState != null) {
            defaultStore.setUtxoState(utxoState);
        }

        boolean snapshotAmountsEnabled = resolveBoolean(
                runtimeOptions.globals(), YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED, false);
        if (snapshotAmountsEnabled) {
            defaultStore.setStakeSnapshotService(new EpochStakeSnapshotService(true));
            String balMode = String.valueOf(runtimeOptions.globals()
                    .getOrDefault(YanoPropertyKeys.EpochSnapshot.BALANCE_MODE, "auto"));
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
        defaultStore.setEpochArchiveStagingSink(epochArchiveStagingSink);
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
        if (paramTrackerInstance != null && config.isDevMode()) {
            // Devnet block producer can jump epochs (restart/restore at wall-clock slots),
            // leaving gap epochs without finalized entries. Carry-forward resolves them
            // to the nearest earlier snapshot. Real networks keep exact-match lookups.
            paramTrackerInstance.setCarryForwardLookup(true);
            log.info("Epoch param tracker carry-forward lookup enabled (dev mode)");
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
        Object rewardMode = runtimeOptions.globals().get(
                YanoPropertyKeys.AccountState.EPOCH_REWARD_MODE);
        rewardCalcInstance.setRewardMode(rewardMode != null ? String.valueOf(rewardMode) : "legacy");
        rewardCalcInstance.setBatchLimits(
                resolveInt(runtimeOptions.globals(),
                        YanoPropertyKeys.AccountState.EPOCH_SNAPSHOT_MAX_BATCH_OPERATIONS, 10_000),
                resolveInt(runtimeOptions.globals(),
                        YanoPropertyKeys.AccountState.EPOCH_SNAPSHOT_MAX_BATCH_BYTES, 4 * 1024 * 1024));
        defaultStore.setRewardCalculator(rewardCalcInstance);
        log.info("Epoch reward calculator enabled (mode={})",
                rewardMode != null ? rewardMode : "legacy");
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
            drepDistCalc.setStakeBalanceViewSupplier(defaultStore::openBoundaryStakeBalanceView);
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

    private static int resolveInt(Map<String, Object> globals, String key, int defaultValue) {
        Object value = globals.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
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

        @Override
        public java.util.Optional<com.bloxbean.cardano.yano.api.CanonicalBlockReference>
        getCanonicalBlockReference(long blockNumber) {
            if (chainState instanceof ArchiveChainStateCapabilities capabilities) {
                return capabilities.getCanonicalBlockReference(blockNumber);
            }
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.bloxbean.cardano.yano.api.ByronEpochBoundaryReference>
        getByronEpochBoundaryBlockAtOrBefore(long slot) {
            if (chainState instanceof ArchiveChainStateCapabilities capabilities) {
                return capabilities.getByronEpochBoundaryBlockAtOrBefore(slot);
            }
            return java.util.Optional.empty();
        }

        @Override
        public java.util.OptionalLong getEarliestRetainedBodyBlockNumber() {
            if (chainState instanceof ArchiveChainStateCapabilities capabilities) {
                return capabilities.getEarliestRetainedBodyBlockNumber();
            }
            return java.util.OptionalLong.empty();
        }
    }
}
