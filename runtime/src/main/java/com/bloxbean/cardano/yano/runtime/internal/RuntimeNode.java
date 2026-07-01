package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.*;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.helper.*;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.SyncPhase;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.UpstreamPeerConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.db.RocksDbAccess;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.api.listener.NodeEventListener;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.GenesisParameters;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yano.api.bootstrap.BootstrapDataProvider;
import com.bloxbean.cardano.yano.api.bootstrap.BootstrapOutpoint;
import com.bloxbean.cardano.yano.runtime.bootstrap.BootstrapResult;
import com.bloxbean.cardano.yano.runtime.bootstrap.BootstrapService;
import com.bloxbean.cardano.yano.runtime.blockproducer.*;
import com.bloxbean.cardano.yano.runtime.BodyFetchManager;
import com.bloxbean.cardano.yano.runtime.HeaderSyncManager;
import com.bloxbean.cardano.yano.runtime.chain.BootstrapChainStateWriter;
import com.bloxbean.cardano.yano.runtime.chain.ByronGenesisUtxoMetadataStore;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateRecovery;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateSnapshots;
import com.bloxbean.cardano.yano.runtime.chain.EraMetadataStore;
import com.bloxbean.cardano.yano.runtime.chain.NearestSlotLookup;
import com.bloxbean.cardano.yano.runtime.chronology.ChronologyService;
import com.bloxbean.cardano.yano.runtime.chronology.ChronologySubsystem;
import com.bloxbean.cardano.yano.api.events.NodeStartedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import com.bloxbean.cardano.yano.p2p.peer.PeerRecoveryFailureTracker;
import com.bloxbean.cardano.yano.p2p.peer.PeerRecoveryReason;
import com.bloxbean.cardano.yano.p2p.peer.PeerSessionStatus;
import com.bloxbean.cardano.yano.runtime.producer.DevnetBlockBuilderFactory;
import com.bloxbean.cardano.yano.runtime.producer.DevnetProducerFactory;
import com.bloxbean.cardano.yano.runtime.producer.NonceEvolutionListenerFactory;
import com.bloxbean.cardano.yano.runtime.producer.ProducerStartupCoordinator;
import com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlan;
import com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystem;
import com.bloxbean.cardano.yano.runtime.producer.SlotLeaderKeyMaterial;
import com.bloxbean.cardano.yano.runtime.producer.SlotLeaderProducerFactory;
import com.bloxbean.cardano.yano.runtime.producer.SlotLeaderSigningComponents;
import com.bloxbean.cardano.yano.runtime.producer.StakeDataProviderFactory;
import com.bloxbean.cardano.yano.runtime.plugins.PluginManager;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import com.bloxbean.cardano.yano.ledgerstate.AccountHistoryStore;
import com.bloxbean.cardano.yano.api.account.AccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yano.runtime.config.DefaultEpochParamProvider;
import com.bloxbean.cardano.yano.runtime.config.DnsCachePolicy;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import com.bloxbean.cardano.yano.runtime.PipelineDataListener;
import com.bloxbean.cardano.yano.runtime.SlotTimeCalculator;
import com.bloxbean.cardano.yano.p2p.connection.DefaultRelayConnectionManager;
import com.bloxbean.cardano.yano.p2p.connection.RelayConnectionManager;
import com.bloxbean.cardano.yano.p2p.connection.RelayConnectionSnapshot;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import com.bloxbean.cardano.yano.runtime.devnet.DevnetCatchUpService;
import com.bloxbean.cardano.yano.runtime.devnet.DevnetFaucetService;
import com.bloxbean.cardano.yano.runtime.devnet.DevnetGenesisShiftService;
import com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotCatalogService;
import com.bloxbean.cardano.yano.runtime.devnet.DevnetSnapshotRestoreService;
import com.bloxbean.cardano.yano.runtime.devnet.DevnetTimeAdvanceService;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntime;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntimeProvider;
import com.bloxbean.cardano.yano.runtime.events.PropagatingEventBus;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.runtime.kernel.KernelLifecycleException;
import com.bloxbean.cardano.yano.runtime.kernel.KernelState;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.kernel.RuntimeKernelProvider;
import com.bloxbean.cardano.yano.runtime.kernel.Schedulers;
import com.bloxbean.cardano.yano.runtime.kernel.ServiceRegistry;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemContext;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystem;
import com.bloxbean.cardano.yano.p2p.peer.DefaultPeerClientFactory;
import com.bloxbean.cardano.yano.p2p.peer.LocalBindAddressResolver;
import com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.runtime.server.ServeSubsystem;
import com.bloxbean.cardano.yano.runtime.storage.ChainStorageSubsystem;
import com.bloxbean.cardano.yano.p2p.governor.PeerGovernorSnapshot;
import com.bloxbean.cardano.yano.runtime.sync.SyncSubsystem;
import com.bloxbean.cardano.yano.runtime.sync.UpstreamStatus;
import com.bloxbean.cardano.yano.p2p.governor.PeerStoreEntry;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidator;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yano.runtime.tx.TxSubsystem;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxDiffusionStats;
import com.bloxbean.cardano.yano.runtime.utxo.UtxoSubsystem;
import com.bloxbean.cardano.yano.runtime.utxo.UtxoStoreWriter;
import com.bloxbean.cardano.yano.runtime.validation.DefaultConsensusListener;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


/**
 * Legacy-compatible runtime facade that now delegates major responsibilities to
 * subsystem classes while preserving the public node role interfaces.
 */
@Slf4j
public class RuntimeNode implements NodeLifecycle, ChainQuery, LedgerQuery, TxGateway, TxEvaluationGateway,
        ProducerControl, AutoCloseable, DebugLedgerStateAccess, RuntimeKernelProvider, DevnetRuntimeProvider {
    // Configuration
    private final YanoConfig config;

    // Client components (for syncing with remote nodes)
    private final String remoteCardanoHost;
    private final int remoteCardanoPort;
    private final long protocolMagic;
    private final ChainStorageSubsystem chainStorage;
    private final ChainState chainState;

    // Server components (for serving other clients)
    private final ServeSubsystem serveSubsystem;
    private final RelayConnectionManager relayConnectionManager;
    private final int serverPort;

    // Block producer (devnet, slot-leader, or time-travel strategy)
    private final ProducerSubsystem producerSubsystem = new ProducerSubsystem();
    private final ProducerStartupCoordinator producerStartupCoordinator;
    private final ProducerStartupPlan producerStartupPlanOverride;
    private volatile EpochNonceState epochNonceState; // shared nonce state (accessible for REST endpoint)
    private volatile GenesisConfig genesisConfig;
    private volatile StaticProtocolParamsSnapshotCache staticProtocolParamsSnapshotCache;
    private volatile long resolvedGenesisTimestamp;
    private final ChronologySubsystem chronologySubsystem;
    private final TxSubsystem txSubsystem;
    private final DevnetRuntime devnetRuntime;
    // Status tracking
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean unsafeLedgerApplyShutdown;

    /**
     * Cached static protocol-parameter snapshot keyed by the source JSON.
     */
    private record StaticProtocolParamsSnapshotCache(String json, ProtocolParamsSnapshot snapshot) {}

    private record SourcePortProbeTarget(String host, int port) {}

    private final Schedulers schedulers;
    private final ScheduledExecutorService scheduler;
    private final NodeKernel kernel;

    private final CopyOnWriteArrayList<BlockChainDataListener> blockChainDataListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeEventListener> nodeEventListeners = new CopyOnWriteArrayList<>();

    // Events & Plugins
    private final RuntimeOptions runtimeOptions;
    private final BodyValidator bodyValidator;
    private final EventBus eventBus;
    private PluginManager pluginManager;
    private final UtxoSubsystem utxoSubsystem;
    private final LedgerStateSubsystem ledgerStateSubsystem;
    private final SyncSubsystem syncSubsystem;
    private volatile UtxoStoreWriter utxoStore;
    private BootstrapDataProvider bootstrapDataProvider;
    private volatile List<SubscriptionHandle> nonceListenerSubscriptions = List.of();

    // In-memory devnet genesis — set before start() for devnet mode without genesis files
    private InMemoryDevnetGenesis inMemoryDevnetGenesis;

    // Adhoc rollback — one-shot rollback on startup, before chain sync.
    // Set via command line, NOT application.yml (to avoid accidental re-rollback).
    private long adhocRollbackToSlot = -1;
    private int adhocRollbackToEpoch = -1;

    public RuntimeNode(YanoConfig config) {
        this(config, RuntimeOptions.defaults(), null);
    }

    public RuntimeNode(YanoConfig config, RuntimeOptions options) {
        this(config, options, null);
    }

    /**
     * @param inMemoryGenesis in-memory devnet genesis (nullable — only for devnet block-producer mode)
     */
    public RuntimeNode(YanoConfig config, RuntimeOptions options, InMemoryDevnetGenesis inMemoryGenesis) {
        this(config, options, inMemoryGenesis, null);
    }

    /**
     * Constructor used by explicit runtime assembly recipes.
     *
     * @param inMemoryGenesis in-memory devnet genesis (nullable — only for devnet block-producer mode)
     * @param producerStartupPlan recipe-selected block producer startup plan; {@code null} derives a plan from config
     */
    public RuntimeNode(YanoConfig config,
                       RuntimeOptions options,
                       InMemoryDevnetGenesis inMemoryGenesis,
                       ProducerStartupPlan producerStartupPlan) {
        this(config, options, inMemoryGenesis, producerStartupPlan, new Schedulers());
    }

    /**
     * Constructor used by explicit runtime assembly recipes that already own a
     * kernel scheduler context.
     *
     * @param inMemoryGenesis in-memory devnet genesis (nullable — only for devnet block-producer mode)
     * @param producerStartupPlan recipe-selected block producer startup plan; {@code null} derives a plan from config
     * @param schedulers kernel scheduler context shared by runtime subsystems
     */
    public RuntimeNode(YanoConfig config,
                       RuntimeOptions options,
                       InMemoryDevnetGenesis inMemoryGenesis,
                       ProducerStartupPlan producerStartupPlan,
                       Schedulers schedulers) {
        this(config, options, inMemoryGenesis, producerStartupPlan, schedulers, BodyValidator.none());
    }

    public RuntimeNode(YanoConfig config,
                       RuntimeOptions options,
                       InMemoryDevnetGenesis inMemoryGenesis,
                       ProducerStartupPlan producerStartupPlan,
                       Schedulers schedulers,
                       BodyValidator bodyValidator) {
        if (inMemoryGenesis != null && (!config.isDevMode() || !config.isEnableBlockProducer())) {
            throw new IllegalStateException(
                    "In-memory devnet genesis is only valid when devMode=true and enableBlockProducer=true");
        }
        if (producerStartupPlan != null && !config.isEnableBlockProducer()) {
            throw new IllegalStateException(
                    "Producer startup plan is only valid when enableBlockProducer=true");
        }
        this.inMemoryDevnetGenesis = inMemoryGenesis;
        this.config = config;
        this.runtimeOptions = options != null ? options : RuntimeOptions.defaults();
        this.bodyValidator = bodyValidator != null ? bodyValidator : BodyValidator.none();
        this.producerStartupPlanOverride = producerStartupPlan;
        this.schedulers = Objects.requireNonNull(schedulers, "schedulers");
        this.scheduler = this.schedulers.scheduled();
        DnsCachePolicy.configureForClientMode(this.runtimeOptions.globals(), config.isEnableClient());
        this.remoteCardanoHost = config.getRemoteHost();
        this.remoteCardanoPort = config.getRemotePort();
        this.protocolMagic = config.getProtocolMagic();
        this.serverPort = config.getServerPort();

        this.chainStorage = new ChainStorageSubsystem(config, this.runtimeOptions, log);
        this.chainState = chainStorage.chainState();
        // Configure Yaci
        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);

        log.info("Yano initialized");
        log.info("Remote: {}:{} (magic: {})", remoteCardanoHost, remoteCardanoPort, protocolMagic);
        log.info("Server port: {}", serverPort);
        log.info("Storage: {}", config.isUseRocksDB() ? "RocksDB" : "InMemory");

        // Event bus
        EventsOptions ev = this.runtimeOptions.events();
        this.eventBus = ev.enabled() ? new PropagatingEventBus() : new NoopEventBus();
        this.txSubsystem = new TxSubsystem(eventBus, scheduler, this.runtimeOptions, this::getUtxoState, log);
        AtomicReference<Supplier<List<PeerStoreEntry>>> peerStoreSupplierRef =
                new AtomicReference<>(List::of);
        this.relayConnectionManager = new DefaultRelayConnectionManager(
                (int) parseLong(
                        this.runtimeOptions.globals().get(YanoPropertyKeys.Relay.CONNECTION_MAX_INBOUND_CONNECTIONS),
                        DefaultRelayConnectionManager.DEFAULT_MAX_INBOUND_CONNECTIONS),
                (int) parseLong(
                        this.runtimeOptions.globals().get(YanoPropertyKeys.Relay.CONNECTION_MAX_CONNECTIONS_PER_IP),
                        DefaultRelayConnectionManager.DEFAULT_MAX_CONNECTIONS_PER_IP),
                log);
        this.serveSubsystem = new ServeSubsystem(
                serverPort,
                protocolMagic,
                chainState,
                txSubsystem,
                config.isEnableBlockProducer(),
                txSubsystem::txDiffusion,
                resolveBoolean(this.runtimeOptions.globals(), YanoPropertyKeys.Relay.AUTO_DISCOVERY, false),
                resolveString(this.runtimeOptions.globals(), YanoPropertyKeys.Relay.ADVERTISED_HOST, ""),
                (int) parseLong(this.runtimeOptions.globals().get(YanoPropertyKeys.Relay.ADVERTISED_PORT), serverPort),
                resolveBoolean(this.runtimeOptions.globals(), YanoPropertyKeys.Relay.ALLOW_PRIVATE_ADDRESSES, false),
                () -> peerStoreSupplierRef.get().get(),
                relayConnectionManager,
                log);

        // Register default consensus listener (accept-all placeholder)
        var consensusListener = new DefaultConsensusListener();
        AnnotationListenerRegistrar.register(eventBus, consensusListener,
                SubscriptionOptions.builder().build());

        // Initialize plugins (discovery is deferred to start())
        if (this.runtimeOptions.plugins().enabled()) {
            pluginManager = new PluginManager(eventBus, scheduler, this.runtimeOptions.plugins().config(), Thread.currentThread().getContextClassLoader());
        }

        chainStorage.runStartupMigrations();

        this.utxoSubsystem = new UtxoSubsystem(
                config,
                this.runtimeOptions,
                chainState,
                rocksDbSupplierOrNull(),
                eventBus,
                scheduler,
                log);
        this.utxoStore = utxoSubsystem.store();
        this.ledgerStateSubsystem = new LedgerStateSubsystem(
                config,
                this.runtimeOptions,
                chainState,
                eventBus,
                log,
                rocksDbAccessOrNull(),
                eraMetadataStoreOrNull(),
                byronGenesisUtxoMetadataStoreOrNull(),
                chainState instanceof ChainStateSnapshots snapshots ? snapshots : null,
                () -> this.utxoStore,
                utxoSubsystem::state,
                this::resolveGenesisHash,
                inMemoryDevnetGenesis);
        this.chronologySubsystem = new ChronologySubsystem(
                new ChronologyService(this.chainState),
                eventBus,
                ledgerStateSubsystem);
        PeerClientFactory peerClientFactory = relayConnectionManager.wrapPeerClientFactory(
                createPeerClientFactory());
        this.syncSubsystem = new SyncSubsystem(
                config,
                chainState,
                eventBus,
                scheduler,
                this.schedulers.tasks(),
                serveSubsystem,
                ledgerStateSubsystem,
                chainStorage,
                isRunning::get,
                this::getEpochParamProvider,
                this::currentGenesisBootstrapData,
                remoteCardanoHost,
                remoteCardanoPort,
                protocolMagic,
                log,
                this.bodyValidator,
                txSubsystem::txDiffusion,
                peerClientFactory);
        relayConnectionManager.addListener(this.syncSubsystem.peerGovernorConnectionListener());
        peerStoreSupplierRef.set(this.syncSubsystem::sharablePeerEntries);
        this.producerStartupCoordinator = new ProducerStartupCoordinator(producerStartupActions());
        this.devnetRuntime = RuntimeDevnetRuntime.create(
                this::rollbackDevnet,
                producerSubsystem::hasProduction,
                producerSubsystem::modeOrNull,
                this::advanceTimeBySlots,
                this::advanceTimeUntilSlot,
                this::advanceTimeBySeconds,
                this::catchUpToWallClock,
                this::shiftGenesisAndStartProducer,
                this::fundAddress,
                this::createDevnetSnapshot,
                this::restoreDevnetSnapshotAndGetTip,
                this::listDevnetSnapshots,
                this::deleteDevnetSnapshot);
        this.kernel = new NodeKernel(
                runtimeKernelSubsystems(),
                new SubsystemContext(eventBus, schedulers, this.runtimeOptions.globals(), new ServiceRegistry()));
    }

    private RocksDbSupplier rocksDbSupplierOrNull() {
        return chainStorage.rocksDbSupplierOrNull();
    }

    private RocksDbAccess rocksDbAccessOrNull() {
        return chainStorage.rocksDbAccessOrNull();
    }

    private EraMetadataStore eraMetadataStoreOrNull() {
        return chainStorage.eraMetadataStoreOrNull();
    }

    private ByronGenesisUtxoMetadataStore byronGenesisUtxoMetadataStoreOrNull() {
        return chainStorage.byronGenesisUtxoMetadataStoreOrNull();
    }

    @Override
    public NodeKernel kernel() {
        return kernel;
    }

    private List<Subsystem> runtimeKernelSubsystems() {
        return RuntimeKernelStages.create(runtimeKernelActions());
    }

    private SubsystemHealth runtimeHealth(String name) {
        try {
            NodeStatus status = getStatus();
            if (status != null && status.isRuntimeDegraded()) {
                return SubsystemHealth.degraded(name, status.getRuntimeDegradedReason());
            }
            if (status != null && status.isPeerRecoveryTerminal()) {
                return SubsystemHealth.down(name, status.getPeerTerminalFailureMessage());
            }
            if (status != null && status.getStatusMessage() != null
                    && status.getStatusMessage().toLowerCase().contains("error")) {
                return SubsystemHealth.down(name, status.getStatusMessage());
            }
            return SubsystemHealth.up(name);
        } catch (Exception e) {
            return SubsystemHealth.down(name, e.toString());
        }
    }

    private RuntimeKernelStages.Actions runtimeKernelActions() {
        return new RuntimeKernelStages.Actions() {
            @Override
            public boolean isClosed() {
                return closed.get();
            }

            @Override
            public boolean markRunningForStartup() {
                return isRunning.compareAndSet(false, true);
            }

            @Override
            public void markStoppedAfterStartupFailure() {
                isRunning.set(false);
            }

            @Override
            public boolean markStoppingForShutdown() {
                return isRunning.compareAndSet(true, false);
            }

            @Override
            public RuntimeMaintenanceGate maintenanceGate() {
                return chainStorage.maintenanceGate();
            }

            @Override
            public void logStarting() {
                log.info("Starting Yano...");
            }

            @Override
            public void logAlreadyRunning() {
                log.warn("Node is already running");
            }

            @Override
            public void logStopping() {
                log.info("Stopping Yano...");
            }

            @Override
            public void logStopped() {
                log.info("Yano stopped");
            }

            @Override
            public void logStartupCleanupFailure(RuntimeException failure) {
                log.warn("Runtime cleanup after failed startup also failed", failure);
            }

            @Override
            public void stopRuntimeServices() {
                RuntimeNode.this.stopRuntimeServices();
            }

            @Override
            public void closeRuntimeResourcesUnderMaintenance() {
                withRuntimeMaintenance("node close", () -> closeRuntimeResources(unsafeLedgerApplyShutdown));
            }

            @Override
            public SubsystemHealth runtimeHealth(String name) {
                return RuntimeNode.this.runtimeHealth(name);
            }

            @Override
            public boolean isServerEnabled() {
                return config.isEnableServer();
            }

            @Override
            public boolean deferServerStartUntilClientStateReady() {
                return config.isEnableServer() && config.isEnableClient() && !config.isEnableBlockProducer();
            }

            @Override
            public void startServer() {
                RuntimeNode.this.startServer();
            }

            @Override
            public void stopServer() {
                serveSubsystem.stop();
            }

            @Override
            public SubsystemHealth serverHealth(String stageName) {
                return config.isEnableServer() && isRunning.get()
                        ? serveSubsystem.health()
                        : SubsystemHealth.up(stageName);
            }

            @Override
            public String txName() {
                return txSubsystem.name();
            }

            @Override
            public void startTx() {
                txSubsystem.start();
            }

            @Override
            public void stopTx() {
                txSubsystem.stop();
            }

            @Override
            public SubsystemHealth txHealth() {
                return txSubsystem.health();
            }

            @Override
            public void runBootstrapRecovery() {
                loadGenesisConfigForStartup();
                if (!config.isEnableBlockProducer() && config.getShelleyGenesisFile() != null
                        && chainState.getTip() == null) {
                    initializeGenesisUtxos();
                }
                if (config.isEnableBootstrap() && config.isEnableClient()
                        && chainState.getTip() == null && chainState.getHeaderTip() == null) {
                    performBootstrap();
                }
                validateChainState();
                performStartupAdhocRollback();
                completeStartupDerivedStateRecovery();
            }

            @Override
            public String utxoName() {
                return utxoSubsystem.name();
            }

            @Override
            public void startUtxo() {
                utxoSubsystem.startBackgroundServices();
            }

            @Override
            public void stopUtxo() {
                utxoSubsystem.pauseBackgroundServices();
            }

            @Override
            public SubsystemHealth utxoHealth() {
                return utxoSubsystem.health();
            }

            @Override
            public String ledgerStateName() {
                return ledgerStateSubsystem.name();
            }

            @Override
            public void startLedgerState() {
                ledgerStateSubsystem.start();
            }

            @Override
            public void stopLedgerState() {
                ledgerStateSubsystem.stop();
            }

            @Override
            public SubsystemHealth ledgerStateHealth() {
                return ledgerStateSubsystem.health();
            }

            @Override
            public String chainStorageName() {
                return chainStorage.name();
            }

            @Override
            public void startChainPrune() {
                chainStorage.startBlockPruneService();
            }

            @Override
            public void stopChainPrune() {
                chainStorage.stopBlockPruneService();
            }

            @Override
            public SubsystemHealth chainStorageHealth() {
                return chainStorage.health();
            }

            @Override
            public String producerName() {
                return producerSubsystem.name();
            }

            @Override
            public void startProducer() {
                if (config.isEnableBlockProducer()) {
                    producerStartupCoordinator.start();
                }
            }

            @Override
            public void stopProducer() {
                producerSubsystem.stop();
            }

            @Override
            public SubsystemHealth producerHealth() {
                return producerSubsystem.health();
            }

            @Override
            public void closeNonceListeners() {
                closeNonceListenerSubscriptions();
            }

            @Override
            public String chronologyName() {
                return chronologySubsystem.name();
            }

            @Override
            public void startChronology() {
                initSlotTimeCalculator();
            }

            @Override
            public SubsystemHealth chronologyHealth() {
                return chronologySubsystem.health();
            }

            @Override
            public String syncName() {
                return syncSubsystem.name();
            }

            @Override
            public void startSync() {
                initRelayNonceTrackingIfRequired();
                if (config.isEnableClient()) {
                    syncSubsystem.startClientSync();
                }
            }

            @Override
            public void stopSyncForShutdown() {
                boolean unsafeLedgerApplyWorker = syncSubsystem.stopForShutdown();
                if (unsafeLedgerApplyWorker) {
                    unsafeLedgerApplyShutdown = true;
                }
            }

            @Override
            public SubsystemHealth syncHealth() {
                return config.isEnableClient() ? syncSubsystem.health() : SubsystemHealth.up(syncSubsystem.name());
            }

            @Override
            public void startPublication() {
                publishStartupEventAndInitializeFilters();
            }

            @Override
            public void finishSuccessfulStartup() {
                log.info("Yano started successfully");
                printStartupStatus();
            }
        };
    }

    private ChainStateSnapshots snapshotsOrThrow() {
        return chainStorage.snapshotsOrThrow();
    }

    private BootstrapChainStateWriter bootstrapWriterOrNull() {
        return chainStorage.bootstrapWriterOrNull();
    }

    private ChainStateRecovery chainStateRecoveryOrNull() {
        return chainStorage.recoveryOrNull();
    }

    @Override
    public Optional<DevnetRuntime> devnetRuntime() {
        return Optional.of(devnetRuntime);
    }

    @Override
    public UtxoState getUtxoState() {
        return utxoSubsystem.state();
    }

    public LedgerStateProvider getLedgerStateProvider() {
        return ledgerStateSubsystem.ledgerStateProvider();
    }

    public EpochParamProvider getEpochParamProvider() {
        return ledgerStateSubsystem.epochParamProvider();
    }

    private boolean epochParamsTrackingEnabled() {
        return ledgerStateSubsystem.epochParamsTrackingEnabled();
    }

    private String runtimeProtocolParametersFile() {
        return epochParamsTrackingEnabled() ? null : config.getProtocolParametersFile();
    }

    private String runtimeProtocolParametersJson() {
        if (inMemoryDevnetGenesis == null || epochParamsTrackingEnabled()) {
            return null;
        }
        return inMemoryDevnetGenesis.protocolParametersJson();
    }

    /**
     * Resolve the *effective* {@link EpochParamProvider} for nonce evolution.
     * Returns the {@link EpochParamTracker} when wired and enabled — it carries on-chain
     * protocol-param updates (e.g. mainnet epoch 259 extraEntropy). Falls back to the
     * genesis-backed {@link #epochParamProvider} otherwise.
     * <p>
     * Mirrors the pattern at {@code DefaultAccountStateStore.java:1362}.
     */
    private EpochParamProvider effectiveEpochParamProvider() {
        return ledgerStateSubsystem.effectiveEpochParamProvider();
    }

    private ProtocolVersionSupplier createBlockProtocolVersionSupplier() {
        return resolveBlockProtocolVersionSupplier(
                epochParamsTrackingEnabled(),
                effectiveEpochParamProvider(),
                getLedgerStateProvider(),
                this::createStaticBlockProtocolVersionSupplier,
                this::createGenesisBlockProtocolVersionSupplier);
    }

    public static ProtocolVersionSupplier resolveBlockProtocolVersionSupplier(boolean epochParamsTrackingEnabled,
                                                                              EpochParamProvider effectiveProvider,
                                                                              LedgerStateProvider ledgerStateProvider,
                                                                              Supplier<ProtocolVersionSupplier> staticSupplier,
                                                                              Supplier<ProtocolVersionSupplier> genesisSupplier) {
        if (epochParamsTrackingEnabled) {
            EpochParamTracker tracker = effectiveProvider instanceof EpochParamTracker t && t.isEnabled()
                    ? t : null;
            if (tracker != null && ledgerStateProvider != null) {
                log.info("Block protocol version source: effective-ledger");
                return new EffectiveProtocolVersionSupplier(
                        ledgerStateProvider,
                        effectiveProvider.getEpochSlotCalc(),
                        tracker);
            }

            log.warn("Epoch-param tracking is enabled but effective block protocol version source is unavailable "
                            + "(tracker={}, ledgerStateProvider={}). Falling back to protocol-param.json / Shelley genesis.",
                    tracker != null, ledgerStateProvider != null);
        }

        ProtocolVersionSupplier staticProtocolVersionSupplier = staticSupplier != null ? staticSupplier.get() : null;
        if (staticProtocolVersionSupplier != null) {
            return staticProtocolVersionSupplier;
        }

        ProtocolVersionSupplier genesisProtocolVersionSupplier = genesisSupplier != null ? genesisSupplier.get() : null;
        if (genesisProtocolVersionSupplier != null) {
            return genesisProtocolVersionSupplier;
        }

        throw new IllegalStateException(
                "No protocol version source available for block production. Configure epoch-param tracking "
                        + "with ledger state, protocol-param.json, or a valid Shelley genesis protocolVersion.");
    }

    private ProtocolVersionSupplier createStaticBlockProtocolVersionSupplier() {
        // Static fallback mode is fixed until the operator reconfigures or effective tracking becomes available.
        String protocolParamsJson = null;
        String source = null;
        if (genesisConfig != null && genesisConfig.hasProtocolParameters()) {
            protocolParamsJson = genesisConfig.getProtocolParameters();
            source = inMemoryDevnetGenesis != null ? "in-memory-devnet" : config.getProtocolParametersFile();
        } else if (inMemoryDevnetGenesis != null
                && inMemoryDevnetGenesis.protocolParametersJson() != null
                && !inMemoryDevnetGenesis.protocolParametersJson().isBlank()) {
            protocolParamsJson = inMemoryDevnetGenesis.protocolParametersJson();
            source = "in-memory-devnet";
        } else if (config.getProtocolParametersFile() != null
                && !config.getProtocolParametersFile().isBlank()) {
            source = config.getProtocolParametersFile();
            try {
                protocolParamsJson = Files.readString(Path.of(config.getProtocolParametersFile()));
            } catch (Exception e) {
                log.warn("Failed to read protocol-param.json for block protocol version fallback file={}: {}",
                        source, e.toString());
                return null;
            }
        }

        if (protocolParamsJson == null || protocolParamsJson.isBlank()) {
            return null;
        }

        try {
            StaticProtocolVersionSupplier supplier =
                    StaticProtocolVersionSupplier.fromProtocolParametersJson(protocolParamsJson);
            ProtocolVersion version = supplier.getProtocolVersion(0);
            log.info("Block protocol version source: protocol-param-json file={} version={}.{}",
                    sourceLabel(source), version.major(), version.minor());
            return supplier;
        } catch (Exception e) {
            log.warn("Failed to resolve block protocol version from protocol-param.json file={}: {}",
                    sourceLabel(source), e.toString());
            return null;
        }
    }

    private ProtocolVersionSupplier createGenesisBlockProtocolVersionSupplier() {
        // Genesis fallback mode is fixed until the operator reconfigures or effective tracking becomes available.
        var shelley = genesisConfig != null ? genesisConfig.getShelleyGenesisData() : null;
        if (shelley == null && inMemoryDevnetGenesis != null) {
            shelley = inMemoryDevnetGenesis.shelley();
        }
        if (shelley == null) {
            return null;
        }

        long major = shelley.protocolMajor();
        long minor = shelley.protocolMinor();
        if (major <= 0 || minor < 0) {
            log.warn("Shelley genesis protocolVersion is invalid for block protocol version fallback "
                            + "file={} version={}.{}",
                    sourceLabel(inMemoryDevnetGenesis != null ? "in-memory-devnet" : config.getShelleyGenesisFile()),
                    major, minor);
            return null;
        }

        ProtocolVersionSupplier supplier = ProtocolVersionSupplier.fixed(major, minor);
        String source = inMemoryDevnetGenesis != null ? "in-memory-devnet" : config.getShelleyGenesisFile();
        log.info("Block protocol version source: shelley-genesis file={} version={}.{}",
                sourceLabel(source), major, minor);
        return supplier;
    }

    private static String sourceLabel(String source) {
        return source != null && !source.isBlank() ? source : "not-configured";
    }

    public AccountStateStore getAccountStateStore() {
        return ledgerStateSubsystem.accountStateStore();
    }

    @Override
    public Optional<DefaultAccountStateStore> getDefaultAccountStateStore() {
        AccountStateStore store = getAccountStateStore();
        return store instanceof DefaultAccountStateStore defaultStore
                ? Optional.of(defaultStore)
                : Optional.empty();
    }

    public AccountHistoryStore getAccountHistoryStore() {
        return ledgerStateSubsystem.accountHistoryStore();
    }

    private void completeStartupDerivedStateRecovery() {
        ledgerStateSubsystem.completeStartupRecovery(utxoSubsystem::completeStartupRecovery);
    }

    private void pauseRuntimeBackgroundServices() {
        utxoSubsystem.pauseBackgroundServices();

        ledgerStateSubsystem.stop();

        chainStorage.stopBlockPruneService();
    }

    @Override
    public AccountHistoryProvider getAccountHistoryProvider() {
        return ledgerStateSubsystem.accountHistoryProvider();
    }

    private void publishStartupEventAndInitializeFilters() {
        if (pluginManager != null && runtimeOptions.plugins().enabled()) {
            try {
                pluginManager.discoverAndInit();
                pluginManager.startAll();
            } catch (Exception e) {
                log.warn("Plugin manager init/start failed: {}", e.toString(), e);
            }
        }

        utxoSubsystem.initializeFilterChain(
                pluginManager != null ? pluginManager.getStorageFilters() : List.of());

        EventMetadata meta = EventMetadata.builder().origin("runtime").build();
        eventBus.publish(
                new NodeStartedEvent(System.currentTimeMillis()),
                meta,
                PublishOptions.builder().build());
    }

    /**
     * Start the node (both client and server)
     */
    public void start() {
        try {
            kernel.start();
        } catch (KernelLifecycleException e) {
            throw unwrapKernelStartupFailure(e);
        }
    }

    private RuntimeException unwrapKernelStartupFailure(KernelLifecycleException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            for (Throwable suppressed : e.getSuppressed()) {
                runtimeException.addSuppressed(suppressed);
            }
            return runtimeException;
        }
        if (cause instanceof Error error) {
            for (Throwable suppressed : e.getSuppressed()) {
                error.addSuppressed(suppressed);
            }
            throw error;
        }
        return e;
    }

    private void loadGenesisConfigForStartup() {
        // Always load genesis config if any genesis files are configured (for protocol params, epoch length, etc.)
        if (genesisConfig != null || (!hasAnyGenesisConfig() && inMemoryDevnetGenesis == null)) {
            return;
        }

        if (inMemoryDevnetGenesis != null) {
            genesisConfig = GenesisConfig.fromInMemory(
                    inMemoryDevnetGenesis.shelley(),
                    inMemoryDevnetGenesis.byron(),
                    runtimeProtocolParametersJson());
        } else {
            genesisConfig = GenesisConfig.load(
                    config.getShelleyGenesisFile(),
                    config.getByronGenesisFile(),
                    runtimeProtocolParametersFile());
        }

        // Propagate epoch params from genesis to config (for REST layer).
        propagateGenesisToConfig(genesisConfig);

        log.info("Genesis config loaded (protocolParams={}, shelleyData={})",
                genesisConfig.hasProtocolParameters() ? "available" : "none",
                genesisConfig.getShelleyGenesisData() != null ? "available" : "none");
    }

    /**
     * Perform bootstrap using the provided data provider.
     * Called automatically during start() if bootstrap is enabled and chain state is empty.
     */
    private void performBootstrap() {
        BootstrapChainStateWriter bootstrapWriter = bootstrapWriterOrNull();
        if (bootstrapWriter == null) {
            log.warn("Bootstrap requires synthetic chain-state write support. Skipping.");
            return;
        }

        if (bootstrapDataProvider == null) {
            throw new IllegalStateException(
                    "Bootstrap is enabled but no BootstrapDataProvider is configured. "
                            + "Set a provider via setBootstrapDataProvider() before calling start().");
        }

        log.info("=== Bootstrap State Mode ===");
        log.info("Block: {}", config.getBootstrapBlockNumber() <= 0 ? "latest" : config.getBootstrapBlockNumber());

        BootstrapService bootstrapService = new BootstrapService(chainState, bootstrapWriter, utxoStore);

        List<BootstrapOutpoint> outpoints = null;
        if (config.getBootstrapUtxos() != null) {
            outpoints = config.getBootstrapUtxos().stream()
                    .map(c -> new BootstrapOutpoint(c.getTxHash(), c.getOutputIndex()))
                    .toList();
        }

        BootstrapResult result = bootstrapService.bootstrap(
                config.getBootstrapBlockNumber(),
                config.getBootstrapAddresses(),
                outpoints,
                bootstrapDataProvider);

        log.info("=== Bootstrap Complete: block #{}, slot={}, {} UTXOs ===",
                result.blockNumber(), result.slot(), result.utxosInjected());
    }

    /**
     * Set the bootstrap data provider. Must be called before start() if bootstrap is enabled.
     */
    public void setBootstrapDataProvider(BootstrapDataProvider provider) {
        this.bootstrapDataProvider = provider;
    }

    /**
     * Get the UTXO store writer. Used by BootstrapResource for incremental UTXO refresh.
     */
    public UtxoStoreWriter getUtxoStoreWriter() {
        return utxoStore;
    }

    /**
     * Start the server component
     */
    private void startServer() {
        serveSubsystem.start();
    }

    private ProducerStartupCoordinator.Actions producerStartupActions() {
        return new ProducerStartupCoordinator.Actions() {
            @Override
            public void wireBlockProducerHelpers() {
                BlockProducerHelper.setGenesisBootstrapDataSupplier(RuntimeNode.this::currentGenesisBootstrapData);
                BlockProducerHelper.setProducerPoolHashSupplier(null);
                if (getEpochParamProvider() != null) {
                    BlockProducerHelper.setEpochParamProvider(getEpochParamProvider());
                }
            }

            @Override
            public ProducerStartupPlan startupPlan() {
                return producerStartupPlan();
            }

            @Override
            public YanoConfig config() {
                return config;
            }

            @Override
            public ChainState chainState() {
                return chainState;
            }

            @Override
            public EventBus eventBus() {
                return eventBus;
            }

            @Override
            public GenesisConfig genesisConfig() {
                return genesisConfig;
            }

            @Override
            public ChainTip chainTip() {
                return chainState.getTip();
            }

            @Override
            public void loadAndPropagateGenesisConfig() {
                RuntimeNode.this.loadAndPropagateGenesisConfig();
            }

            @Override
            public void autoDeriveBlockTimeMillis() {
                RuntimeNode.this.autoDeriveBlockTimeMillis();
            }

            @Override
            public void autoDeriveSlotLengthMillis() {
                RuntimeNode.this.autoDeriveSlotLengthMillis();
            }

            @Override
            public long computeEpochShiftMillis(int epochs) {
                return RuntimeNode.this.computeEpochShiftMillis(epochs);
            }

            @Override
            public void setResolvedGenesisTimestamp(long timestampMillis) {
                resolvedGenesisTimestamp = timestampMillis;
            }

            @Override
            public long resolvedGenesisTimestamp() {
                return resolvedGenesisTimestamp;
            }

            @Override
            public void refreshGenesisBootstrapDataFromGenesis() {
                refreshGenesisBootstrapData(genesisConfig.getShelleyGenesisData());
            }

            @Override
            public DevnetBlockBuilder createDevnetBlockBuilder(boolean freshStart) {
                return devnetBlockBuilderFactory().create(freshStart);
            }

            @Override
            public void configureGenesisProducerPoolHash(DevnetBlockBuilder blockBuilder) {
                RuntimeNode.this.configureGenesisProducerPoolHash(blockBuilder);
            }

            @Override
            public void setConwayEraStartIfFreshStart(boolean freshStart) {
                RuntimeNode.this.setConwayEraStartIfFreshStart(freshStart);
            }

            @Override
            public DevnetBlockProducer createLiveDevnetProducer(DevnetBlockBuilder blockBuilder) {
                return RuntimeNode.this.createLiveDevnetProducer(blockBuilder);
            }

            @Override
            public void storeGenesisUtxosIfNeeded(boolean freshStart) {
                RuntimeNode.this.storeGenesisUtxosIfNeeded(freshStart);
            }

            @Override
            public void notifyServeNewDataAvailable() {
                serveSubsystem.notifyNewDataAvailable();
            }

            @Override
            public void setEpochNonceState(EpochNonceState epochNonceState) {
                RuntimeNode.this.epochNonceState = epochNonceState;
            }

            @Override
            public void initializeNonceShelleyStartSlot(EpochNonceState epochNonceState) {
                RuntimeNode.this.initializeNonceShelleyStartSlot(epochNonceState);
            }

            @Override
            public NonceStateStore nonceStoreOrNull() {
                return chainState instanceof NonceStateStore nonceStore ? nonceStore : null;
            }

            @Override
            public EpochParamProvider effectiveEpochParamProvider() {
                return RuntimeNode.this.effectiveEpochParamProvider();
            }

            @Override
            public byte[] resolveGenesisHash() {
                return RuntimeNode.this.resolveGenesisHash();
            }

            @Override
            public void initializeProducerNonceState(EpochNonceState nonceState,
                                                     NonceStateStore nonceStore,
                                                     NonceReplayService replayService,
                                                     String operation,
                                                     String modeDescription) {
                RuntimeNode.this.initializeProducerNonceState(
                        nonceState, nonceStore, replayService, operation, modeDescription);
            }

            @Override
            public ProtocolVersionSupplier createBlockProtocolVersionSupplier() {
                return RuntimeNode.this.createBlockProtocolVersionSupplier();
            }

            @Override
            public NonceEvolutionListener.NonceCursorResolver nonceCursorResolver() {
                return RuntimeNode.this::resolveNonceSnapshotCursor;
            }

            @Override
            public void replaceNonceListenerSubscriptions(List<SubscriptionHandle> subscriptionHandles) {
                RuntimeNode.this.replaceNonceListenerSubscriptions(subscriptionHandles);
            }

            @Override
            public SlotLeaderProducerFactory slotLeaderProducerFactory() {
                return RuntimeNode.this.slotLeaderProducerFactory();
            }

            @Override
            public void deferPastTimeTravelBlockProducer() {
                RuntimeNode.this.deferPastTimeTravelBlockProducer();
            }
        };
    }

    private void deferPastTimeTravelBlockProducer() {
        log.info("Past time travel mode: block production deferred until /epochs/shift is called");
        loadAndPropagateGenesisConfig();
        autoDeriveBlockTimeMillis();
        autoDeriveSlotLengthMillis();
    }

    private void loadAndPropagateGenesisConfig() {
        if (genesisConfig == null) {
            if (inMemoryDevnetGenesis != null) {
                genesisConfig = GenesisConfig.fromInMemory(
                        inMemoryDevnetGenesis.shelley(),
                        inMemoryDevnetGenesis.byron(),
                        runtimeProtocolParametersJson());
            } else {
                genesisConfig = GenesisConfig.load(
                        config.getShelleyGenesisFile(),
                        config.getByronGenesisFile(),
                        runtimeProtocolParametersFile());
            }

            propagateGenesisToConfig(genesisConfig);
            refreshGenesisBootstrapData(genesisConfig.getShelleyGenesisData());
        }
    }

    private GenesisBootstrapData currentGenesisBootstrapData() {
        return ledgerStateSubsystem.currentGenesisBootstrapData();
    }

    private void refreshGenesisBootstrapData(NetworkGenesisConfig networkGenesisConfig) {
        ledgerStateSubsystem.refreshGenesisBootstrapData(networkGenesisConfig);
    }

    private void refreshGenesisBootstrapData(
            com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData shelleyGenesisData) {
        ledgerStateSubsystem.refreshGenesisBootstrapData(shelleyGenesisData);
    }

    /**
     * Initialize epoch nonce tracking for relay/client mode.
     * <p>
     * Relay mode does not use the nonce to produce blocks, but the REST/API layer
     * still exposes it and it is useful for validating synced mainnet/preprod state.
     * The important startup invariant is that the nonce state must describe the
     * durable body tip, not the header tip. Header sync may run ahead of body
     * apply in pipelined mode, while nonce evolution depends on block bodies.
     * <p>
     * When {@link NonceStateStore} is available, startup uses
     * {@link NonceReplayService} to restore a cursor-bearing snapshot or replay
     * stored block bodies up to the current body tip. This repairs the case where
     * the process stopped after block bodies were committed but before the latest
     * nonce snapshot was persisted.
     */
    private void initNonceTracking() {
        if (config.isEnableBootstrap()) {
            epochNonceState = null;
            log.warn("initNonceTracking called in bootstrap mode; skipping because partial chain state "
                    + "cannot replay nonce history");
            return;
        }

        if (genesisConfig == null || genesisConfig.getShelleyGenesisData() == null) {
            log.debug("Nonce tracking not initialized: no shelley genesis data");
            return;
        }

        try {
            var shelleyData = genesisConfig.getShelleyGenesisData();
            long epochLength = shelleyData.epochLength();
            long securityParam = shelleyData.securityParam();
            double activeSlotsCoeff = genesisConfig.getActiveSlotsCoeff();
            if (activeSlotsCoeff <= 0) activeSlotsCoeff = 0.05; // default for public networks

            long byronSlotsPerEpoch = genesisConfig.getByronGenesisData() != null
                    ? genesisConfig.getByronGenesisData().epochLength() : Constants.BYRON_SLOTS_PER_EPOCH;
            epochNonceState = new EpochNonceState(epochLength, securityParam, activeSlotsCoeff, byronSlotsPerEpoch);
            initializeNonceShelleyStartSlot(epochNonceState);
            NonceStateStore nonceStore = (chainState instanceof NonceStateStore)
                    ? (NonceStateStore) chainState : null;

            EpochParamProvider effectiveParamProvider = effectiveEpochParamProvider();
            boolean trackedParams = effectiveParamProvider instanceof EpochParamTracker tracker
                    && tracker.isEnabled();
            long networkMagic = config.getProtocolMagic();
            NonceEvolutionListenerFactory.logTrackingMode(trackedParams, networkMagic);
            NonceReplayService replayService = null;
            byte[] genesisHash = resolveGenesisHash();

            if (nonceStore != null) {
                replayService = new NonceReplayService(
                        chainState,
                        nonceStore,
                        new EpochNonceEvolver(effectiveParamProvider, trackedParams, networkMagic),
                        genesisHash);
                replayService.repairToBodyTip(epochNonceState, genesisHash, "startup");
            } else if (genesisHash != null) {
                epochNonceState.initFromGenesisHash(genesisHash);
            }

            if (epochNonceState.getEpochNonce() == null) {
                log.debug("Nonce tracking not initialized: no shelley genesis hash or durable nonce state available");
                epochNonceState = null;
                return;
            }

            if (nonceStore != null) {
                nonceStore.storeEpochNonce(epochNonceState.getCurrentEpoch(), epochNonceState.getEpochNonce());
            }

            // Register listener — no own-block skipping (null issuerVkey) since we're not producing
            var nonceListener = new NonceEvolutionListener(epochNonceState, nonceStore, null,
                    effectiveParamProvider, trackedParams, networkMagic,
                    this::resolveNonceSnapshotCursor, replayService);
            replaceNonceListenerSubscriptions(AnnotationListenerRegistrar.register(eventBus, nonceListener,
                    com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder().build()));

            log.info("Epoch nonce tracking initialized for relay mode (epochLength={}, k={}, f={})",
                    epochLength, securityParam, activeSlotsCoeff);
        } catch (Exception e) {
            epochNonceState = null;
            throw new IllegalStateException("Failed to initialize nonce tracking", e);
        }
    }

    public void initRelayNonceTrackingIfRequired() {
        if (!shouldInitializeRelayNonceTracking(config.isEnableBlockProducer(), config.isEnableBootstrap())) {
            if (config.isEnableBootstrap() && !config.isEnableBlockProducer()) {
                epochNonceState = null;
                log.info("Epoch nonce tracking disabled in bootstrap mode; "
                        + "partial chain state cannot replay nonce history");
            }
            return;
        }

        initNonceTracking();
    }

    public static boolean shouldInitializeRelayNonceTracking(boolean blockProducerEnabled, boolean bootstrapEnabled) {
        return !blockProducerEnabled && !bootstrapEnabled;
    }

    private void replaceNonceListenerSubscriptions(List<SubscriptionHandle> subscriptionHandles) {
        closeNonceListenerSubscriptions();
        nonceListenerSubscriptions = subscriptionHandles != null ? List.copyOf(subscriptionHandles) : List.of();
    }

    private void closeNonceListenerSubscriptions() {
        List<SubscriptionHandle> handles = nonceListenerSubscriptions;
        nonceListenerSubscriptions = List.of();
        for (SubscriptionHandle handle : handles) {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("Error closing nonce listener subscription", e);
            }
        }
    }

    /**
     * Initialize nonce state before any local block production starts.
     * <p>
     * Producer mode is stricter than relay mode: a wrong nonce changes leader
     * checks and the VRF proof included in produced blocks. For that reason this
     * method first tries to repair persisted nonce state to the durable body tip.
     * Only an empty chain may fall back to a configured initial nonce or genesis
     * hash initialization.
     * <p>
     * The resulting state is also persisted as the current epoch nonce so a
     * subsequent restart can verify or repair from a cursor-bearing snapshot
     * instead of trusting an in-memory checkpoint that no longer exists.
     *
     * @param nonceState mutable nonce state used by the producer and listener
     * @param nonceStore durable nonce store, or {@code null} for in-memory chain state
     * @param replayService optional repair service backed by stored block bodies
     * @param repairReason short reason included in repair logs
     * @param modeDescription human-readable producer mode for error messages
     */
    private void initializeProducerNonceState(EpochNonceState nonceState,
                                              NonceStateStore nonceStore,
                                              NonceReplayService replayService,
                                              String repairReason,
                                              String modeDescription) {
        boolean initialized = false;
        ChainTip bodyTip = chainState.getTip();

        if (replayService != null
                && !(bodyTip == null && hasConfiguredInitialEpochNonce())) {
            var repair = replayService.repairToBodyTip(nonceState, repairReason);
            initialized = nonceState.getEpochNonce() != null;
            if (initialized) {
                log.info("Nonce state repaired for {}: source={}, replayedBlocks={}",
                        modeDescription, repair.source(), repair.replayedBlocks());
            }
        }

        if (!initialized && hasConfiguredInitialEpochNonce()) {
            byte[] nonce = HexUtil.decodeHexString(config.getInitialEpochNonce());
            nonceState.seedFromExternal(config.getInitialEpoch(), nonce);
            initialized = true;
            log.info("Nonce state seeded from config for {}: epoch={}",
                    modeDescription, config.getInitialEpoch());
            if (nonceStore != null && bodyTip == null) {
                nonceStore.storeLatestNonceSnapshot(NonceStateSnapshot.origin(nonceState.serialize()));
            }
        }

        if (!initialized) {
            byte[] genesisHash = resolveGenesisHash();
            if (genesisHash == null) {
                throw new IllegalStateException(
                        "Shelley genesis hash required for nonce initialization in " + modeDescription);
            }
            nonceState.initFromGenesisHash(genesisHash);
            initialized = true;
            if (nonceStore != null && bodyTip == null) {
                nonceStore.storeLatestNonceSnapshot(NonceStateSnapshot.origin(nonceState.serialize()));
            }
        }

        if (nonceStore != null) {
            nonceStore.storeEpochNonce(nonceState.getCurrentEpoch(), nonceState.getEpochNonce());
        }
    }

    /**
     * Return true only when the external initial nonce configuration is complete.
     * A nonce without its epoch is ambiguous, and an epoch without a nonce cannot
     * seed {@link EpochNonceState}.
     */
    private boolean hasConfiguredInitialEpochNonce() {
        return config.getInitialEpochNonce() != null
                && !config.getInitialEpochNonce().isBlank()
                && config.getInitialEpoch() >= 0;
    }

    /**
     * Build the durable cursor envelope for a nonce snapshot.
     * <p>
     * The callback is used by {@link NonceEvolutionListener} after normal block
     * apply and after rollback repair. The caller provides the ChainSync point
     * that triggered the snapshot, but that point is not always the correct
     * durable cursor for nonce state. In pipelined sync, ChainSync/header state
     * can be ahead of body apply. Nonce state follows body apply because the
     * nonce algorithm consumes block headers from stored bodies.
     * <p>
     * Therefore the persisted {@link NonceStateSnapshot} is always stamped with
     * the current ChainState body tip. If the body tip is ahead of the rollback
     * point, the local state is inconsistent with the requested rollback and the
     * snapshot is rejected instead of storing a misleading repair cursor.
     */
    private NonceStateSnapshot resolveNonceSnapshotCursor(long slot, String hashHex, byte[] serializedNonceState) {
        ChainTip tip = chainState.getTip();
        if (tip == null) {
            return NonceStateSnapshot.origin(serializedNonceState);
        }

        if (tip.getSlot() > slot) {
            throw new IllegalStateException("Cannot persist rollback nonce snapshot: body tip slot "
                    + tip.getSlot() + " is ahead of rollback slot " + slot + ", hash=" + hashHex);
        }

        // ChainSync may roll back only header state while body apply is behind
        // the rollback point. Nonce follows body apply, so the durable cursor
        // must always be the post-rollback body tip, not the ChainSync point.
        return new NonceStateSnapshot(tip.getSlot(), tip.getBlockNumber(), tip.getBlockHash(), serializedNonceState);
    }

    /**
     * Populate the Shelley start slot used by era-aware nonce calculations.
     * <p>
     * Mainnet and public test networks have a Byron-to-Shelley boundary, while
     * many devnets start directly in Shelley/Conway. The value can come from
     * persisted era metadata after a prior sync, from the active epoch parameter
     * provider, from already-propagated config, or finally from genesis/network
     * defaults. Keeping this resolution in one place prevents relay, producer,
     * and replay paths from deriving different nonce epochs for the same slot.
     */
    private void initializeNonceShelleyStartSlot(EpochNonceState nonceState) {
        if (nonceState == null || nonceState.isShelleyStartSlotSet()) {
            return;
        }

        EraMetadataStore eraMetadataStore = eraMetadataStoreOrNull();
        if (eraMetadataStore != null) {
            var persistedStart = eraMetadataStore.getFirstNonByronEraStartSlot();
            if (persistedStart.isPresent()) {
                nonceState.setShelleyStartSlot(persistedStart.getAsLong());
                return;
            }
        }

        EpochParamProvider epochParamProvider = getEpochParamProvider();
        if (epochParamProvider != null) {
            nonceState.setShelleyStartSlot(epochParamProvider.getShelleyStartSlot());
            return;
        }

        if (config.isEpochParamsInitialized()) {
            nonceState.setShelleyStartSlot(config.getFirstNonByronSlot());
            return;
        }

        if (genesisConfig != null && genesisConfig.getShelleyGenesisData() != null) {
            long firstNonByronSlot = DefaultEpochParamProvider.resolveFirstNonByronSlot(
                    protocolMagic, genesisConfig.getByronGenesisData() != null);
            nonceState.setShelleyStartSlot(firstNonByronSlot);
        }
    }

    /**
     * Resolve the shelley genesis hash: use configured hash if available, otherwise hash the file.
     */
    private byte[] resolveGenesisHash() {
        String configHash = config.getShelleyGenesisHash();
        if (configHash != null && !configHash.isBlank()) {
            log.info("Using configured shelley-genesis-hash: {}", configHash);
            return HexUtil.decodeHexString(configHash);
        }

        String shelleyGenesisFile = config.getShelleyGenesisFile();
        if (shelleyGenesisFile != null) {
            try {
                byte[] genesisBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(shelleyGenesisFile));
                byte[] hash = Blake2bUtil.blake2bHash256(genesisBytes);
                log.info("Derived shelley-genesis hash from file: {}", HexUtil.encodeHexString(hash));
                return hash;
            } catch (Exception e) {
                log.error("Failed to read shelley genesis file: {}", e.getMessage());
            }
        }
        return null;
    }

    private void autoDeriveBlockTimeMillis() {
        if (config.getBlockTimeMillis() <= 0) {
            if (genesisConfig.getShelleyGenesisData() != null) {
                double activeSlotsCoeff = genesisConfig.getActiveSlotsCoeff();
                if (activeSlotsCoeff <= 0) activeSlotsCoeff = 1.0;
                double slotLength = genesisConfig.getShelleyGenesisData().slotLength();
                int derived = (int) (slotLength * 1000 / activeSlotsCoeff);
                config.setBlockTimeMillis(derived);
                log.info("Auto-derived blockTimeMillis={} from genesis (slotLength={}, activeSlotsCoeff={})",
                        derived, slotLength, activeSlotsCoeff);
            } else {
                config.setBlockTimeMillis(1000);
                log.info("No genesis data available, using default blockTimeMillis=1000");
            }
        } else {
            log.info("Using explicit blockTimeMillis={}", config.getBlockTimeMillis());
        }
    }

    private void autoDeriveSlotLengthMillis() {
        if (config.getSlotLengthMillis() <= 0) {
            if (genesisConfig.getShelleyGenesisData() != null
                    && genesisConfig.getShelleyGenesisData().slotLength() > 0) {
                int derivedSlotLength = (int) (genesisConfig.getShelleyGenesisData().slotLength() * 1000);
                config.setSlotLengthMillis(derivedSlotLength);
                log.info("Auto-derived slotLengthMillis={} from genesis slotLength={}",
                        derivedSlotLength, genesisConfig.getShelleyGenesisData().slotLength());
            } else {
                config.setSlotLengthMillis(1000);
                log.info("No genesis data available, using default slotLengthMillis=1000");
            }
        } else {
            log.info("Using explicit slotLengthMillis={}", config.getSlotLengthMillis());
        }
    }

    /**
     * Store genesis UTXOs in the UTXO store using blake2b(address) tx hash convention.
     * Must be called AFTER genesis block is stored in chainState (so getTip() returns the correct hash/slot).
     */
    private void storeGenesisUtxosIfNeeded(boolean freshStart) {
        if (freshStart && genesisConfig.hasInitialFunds() && utxoStore != null) {
            var tip = chainState.getTip();
            String blockHash = tip != null ? HexUtil.encodeHexString(tip.getBlockHash()) : "";
            long slot = tip != null ? tip.getSlot() : 0;
            utxoStore.storeGenesisUtxos(genesisConfig.getInitialFunds(),
                    config.getProtocolMagic(), slot, 0, blockHash);
        }
    }

    /**
     * Fresh devnet shortcut: mark Conway era at slot 0 so EraProviderImpl treats the
     * devnet as Conway-or-later from genesis. This is intentional for devnets that
     * start post-bootstrap with PV10+ behavior — not generic Conway detection for synced chains.
     */
    private void setConwayEraStartIfFreshStart(boolean freshStart) {
        EraMetadataStore eraMetadataStore = eraMetadataStoreOrNull();
        if (freshStart && eraMetadataStore != null) {
            eraMetadataStore.setEraStartSlot(Era.Conway.value, 0);
        }
    }

    /**
     * Compute the epoch shift in milliseconds for fast-forwarding genesis timestamp.
     * Does NOT set config.genesisTimestamp — callers apply the shift themselves.
     */
    private long computeEpochShiftMillis(int epochs) {
        var shelleyData = genesisConfig.getShelleyGenesisData();
        long epochLengthSlots = shelleyData.epochLength();
        double slotLengthSec = shelleyData.slotLength();
        return (long) (epochs * epochLengthSlots * slotLengthSec * 1000);
    }

    /**
     * Create a live DevnetBlockProducer and install it as the active producer strategy.
     * Does NOT call start() — caller controls configuration and start timing.
     */
    private DevnetBlockProducer createLiveDevnetProducer(DevnetBlockBuilder blockBuilder) {
        return devnetProducerFactory().createLive(blockBuilder, devnetProducerSettings());
    }

    /**
     * Create a deferred time-travel DevnetBlockProducer and install it as the active producer strategy.
     * Does NOT call start() — shifted-genesis flow enables sequential slots before starting.
     */
    private DevnetBlockProducer createDevnetTimeTravelProducer(DevnetBlockBuilder blockBuilder) {
        return devnetProducerFactory().createTimeTravel(blockBuilder, devnetProducerSettings());
    }

    private DevnetProducerFactory.Settings devnetProducerSettings() {
        return new DevnetProducerFactory.Settings(
                config.getBlockTimeMillis(),
                config.isLazyBlockProduction(),
                resolvedGenesisTimestamp,
                config.getSlotLengthMillis(),
                genesisConfig);
    }

    private DevnetBlockBuilderFactory devnetBlockBuilderFactory() {
        return new DevnetBlockBuilderFactory(
                config,
                genesisConfig,
                new DevnetBlockBuilderFactory.Dependencies(
                        chainState,
                        this::effectiveEpochParamProvider,
                        this::resolveGenesisHash,
                        this::initializeNonceShelleyStartSlot,
                        this::initializeProducerNonceState,
                        this::createBlockProtocolVersionSupplier));
    }

    private DevnetProducerFactory devnetProducerFactory() {
        return new DevnetProducerFactory(
                new DevnetProducerFactory.Dependencies(
                        chainState,
                        txSubsystem,
                        serveSubsystem::server,
                        eventBus,
                        scheduler,
                        producerSubsystem));
    }

    private SlotLeaderProducerFactory slotLeaderProducerFactory() {
        return new SlotLeaderProducerFactory(
                new SlotLeaderProducerFactory.Dependencies(
                        chainState,
                        txSubsystem,
                        serveSubsystem::server,
                        eventBus,
                        scheduler,
                        producerSubsystem));
    }

    private void configureGenesisProducerPoolHash(DevnetBlockBuilder blockBuilder) {
        if (blockBuilder instanceof SignedBlockBuilder signedBlockBuilder) {
            String poolHash = signedBlockBuilder.getIssuerPoolHashHex();
            com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerHelper.setProducerPoolHashSupplier(() -> poolHash);
            log.info("Genesis producer pool hash available for block-producer events: {}", poolHash);
        } else {
            com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerHelper.setProducerPoolHashSupplier(null);
        }
    }

    /**
     * Create a Praos-aware past-time-travel producer for multi-node devnets.
     * The stake distribution comes from Shelley genesis because no indexer is
     * available during companion bootstrap.
     */
    private SlotLeaderTimeTravelBlockProducer createSlotLeaderTimeTravelProducer(boolean freshStart) {
        var shelleyData = genesisConfig.getShelleyGenesisData();
        if (shelleyData == null) {
            throw new IllegalStateException("Shelley genesis data required for past-time-travel slot-leader mode");
        }
        if (config.getShelleyGenesisFile() == null || config.getShelleyGenesisFile().isBlank()) {
            throw new IllegalStateException("Shelley genesis file required for past-time-travel slot-leader mode");
        }

        try {
            SlotLeaderKeyMaterial keyMaterial = SlotLeaderKeyMaterial.load(config);
            String poolHash = keyMaterial.poolHash();
            log.info("Past-time-travel slot-leader pool hash: {}", poolHash);

            long epochLength = shelleyData.epochLength();
            long securityParam = shelleyData.securityParam();
            double activeSlotsCoeff = genesisConfig.getActiveSlotsCoeff();
            long byronSlotsPerEpoch = genesisConfig.getByronGenesisData() != null
                    ? genesisConfig.getByronGenesisData().epochLength() : Constants.BYRON_SLOTS_PER_EPOCH;

            epochNonceState = new EpochNonceState(epochLength, securityParam, activeSlotsCoeff, byronSlotsPerEpoch);
            initializeNonceShelleyStartSlot(epochNonceState);

            NonceStateStore nonceStore = (chainState instanceof NonceStateStore)
                    ? (NonceStateStore) chainState : null;

            EpochParamProvider effectiveParamProvider = effectiveEpochParamProvider();
            boolean trackedParams = effectiveParamProvider instanceof EpochParamTracker tracker
                    && tracker.isEnabled();
            long networkMagic = config.getProtocolMagic();
            NonceReplayService replayService = nonceStore != null && !freshStart
                    ? new NonceReplayService(chainState, nonceStore,
                            new EpochNonceEvolver(effectiveParamProvider, trackedParams, networkMagic),
                            resolveGenesisHash())
                    : null;
            initializeProducerNonceState(epochNonceState, nonceStore, replayService,
                    "past-time-travel-startup", "past-time-travel slot-leader mode");

            ProtocolVersionSupplier protocolVersionSupplier = createBlockProtocolVersionSupplier();

            var signingComponents = SlotLeaderSigningComponents.create(
                    keyMaterial,
                    shelleyData.slotsPerKESPeriod(),
                    shelleyData.maxKESEvolutions(),
                    epochNonceState,
                    nonceStore,
                    protocolVersionSupplier,
                    activeSlotsCoeff);
            var signedBlockBuilder = signingComponents.signedBlockBuilder();
            var slotLeaderCheck = signingComponents.slotLeaderCheck();

            var stakeDataProvider = StakeDataProviderFactory.createGenesisTimeTravelProvider(
                    Path.of(config.getShelleyGenesisFile()),
                    poolHash);

            long sequentialScanLimitSlots = Math.max(epochLength, 1000L);
            return slotLeaderProducerFactory().createTimeTravel(
                    signedBlockBuilder,
                    epochNonceState,
                    slotLeaderCheck,
                    stakeDataProvider,
                    poolHash,
                    resolvedGenesisTimestamp,
                    config.getSlotLengthMillis(),
                    config.getBlockTimeMillis(),
                    sequentialScanLimitSlots);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create past-time-travel slot-leader block producer", e);
        }
    }

    /**
     * Initialize the SlotTimeCalculator from genesis config data.
     */
    private void initSlotTimeCalculator() {
        chronologySubsystem.initialize(genesisConfig, resolvedGenesisTimestamp);
    }

    /**
     * Set the transaction evaluator. Called externally (e.g. from app)
     * to inject a concrete evaluator implementation.
     *
     */
    public long getResolvedGenesisTimestamp() {
        return resolvedGenesisTimestamp;
    }

    public void setTransactionEvaluator(TransactionValidator evaluator) {
        txSubsystem.setTransactionEvaluator(evaluator);
    }

    public void setScriptEvaluator(TransactionEvaluator scriptEvaluator) {
        txSubsystem.setScriptEvaluator(scriptEvaluator);
    }

    @Override
    public boolean isTransactionEvaluationAvailable() {
        return txSubsystem.isTransactionEvaluationAvailable();
    }

    @Override
    public List<TxEvaluationResult> evaluateTransaction(byte[] txCbor) throws Exception {
        return txSubsystem.evaluateTransaction(txCbor);
    }

    @Override
    public void startProducer() {
        withRuntimeMaintenance("producer start", () -> {
            if (!producerSubsystem.hasProduction()) {
                throw new UnsupportedOperationException("Producer control is not available");
            }
            try {
                producerSubsystem.start();
            } catch (RuntimeException | Error e) {
                markRuntimeDegraded(
                        "producer start",
                        "Producer start failed after producer control mutation started; restart required",
                        e);
                throw e;
            }
        });
    }

    @Override
    public void stopProducer() {
        withRuntimeMaintenance("producer stop", () -> {
            if (!producerSubsystem.hasProduction()) {
                throw new UnsupportedOperationException("Producer control is not available");
            }
            try {
                producerSubsystem.stop();
            } catch (RuntimeException | Error e) {
                markRuntimeDegraded(
                        "producer stop",
                        "Producer stop failed after producer control mutation started; restart required",
                        e);
                throw e;
            }
        });
    }

    @Override
    public void resetProducerToChainTip() {
        withRuntimeMaintenance("producer reset", () -> {
            if (!producerSubsystem.hasProduction()) {
                throw new UnsupportedOperationException("Producer control is not available");
            }
            try {
                producerSubsystem.resetToChainTip();
            } catch (RuntimeException | Error e) {
                markRuntimeDegraded(
                        "producer reset",
                        "Producer reset failed after producer control mutation started; restart required",
                        e);
                throw e;
            }
        });
    }

    @Override
    public boolean isProducerRunning() {
        return producerSubsystem.isRunning();
    }

    public EpochNonceState getEpochNonceState() {
        return epochNonceState;
    }

    @Override
    public java.util.Map<String, Object> getEpochNonceInfo() {
        if (epochNonceState == null) return null;
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("epoch", epochNonceState.getCurrentEpoch());
        byte[] nonce = epochNonceState.getEpochNonce();
        map.put("nonce", nonce != null ? HexUtil.encodeHexString(nonce) : null);
        byte[] evolving = epochNonceState.getEvolvingNonce();
        map.put("evolving_nonce", evolving != null ? HexUtil.encodeHexString(evolving) : null);
        byte[] candidate = epochNonceState.getCandidateNonce();
        map.put("candidate_nonce", candidate != null ? HexUtil.encodeHexString(candidate) : null);
        return map;
    }

    @Override
    public String getEpochNonce(int epoch) {
        if (epoch < 0) return null;
        if (chainState instanceof NonceStateStore nonceStore) {
            byte[] stored = nonceStore.getEpochNonce(epoch);
            if (stored != null) {
                return HexUtil.encodeHexString(stored);
            }
        }
        if (epochNonceState != null && epochNonceState.getCurrentEpoch() == epoch) {
            byte[] current = epochNonceState.getEpochNonce();
            return current != null ? HexUtil.encodeHexString(current) : null;
        }
        return null;
    }

    /**
     * Pre-populate genesis UTXOs for relay mode.
     * Stores an empty genesis block and writes UTXOs directly to the UTXO store
     * using tx_hash = blake2b(address) convention (matching yaci-store and wallets).
     */
    private void initializeGenesisUtxos() {
        log.info("Initializing genesis UTXOs...");

        if (inMemoryDevnetGenesis != null) {
            genesisConfig = GenesisConfig.fromInMemory(
                    inMemoryDevnetGenesis.shelley(),
                    inMemoryDevnetGenesis.byron(),
                    runtimeProtocolParametersJson());
        } else {
            genesisConfig = GenesisConfig.load(
                    config.getShelleyGenesisFile(),
                    config.getByronGenesisFile(),
                    runtimeProtocolParametersFile());
        }

        propagateGenesisToConfig(genesisConfig);

        boolean hasFunds = genesisConfig.hasInitialFunds() || genesisConfig.hasByronBalances();

        if (hasFunds) {
            // Store genesis UTXOs directly in UTXO store with blake2b(address) tx hashes.
            String blockHash = "0000000000000000000000000000000000000000000000000000000000000000";

            if (utxoStore != null) {
                if (genesisConfig.hasInitialFunds()) {
                    utxoStore.storeGenesisUtxos(genesisConfig.getInitialFunds(),
                            config.getProtocolMagic(), 0, 0, blockHash);
                }
                if (genesisConfig.hasByronBalances()) {
                    utxoStore.storeByronGenesisUtxos(genesisConfig.getByronBalances(),
                            0, 0, blockHash);

                    // Persist Byron genesis UTXO outpoint keys for Allegra removal, then free memory
                    if (utxoStore instanceof com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStore defaultUtxo
                            && byronGenesisUtxoMetadataStoreOrNull() instanceof ByronGenesisUtxoMetadataStore byronMetadataStore) {
                        var keys = defaultUtxo.getByronGenesisOutpointKeys();
                        if (!keys.isEmpty()) {
                            byronMetadataStore.setByronGenesisUtxoKeys(keys);
                            defaultUtxo.clearByronGenesisOutpointKeys();
                        }
                    }
                }
            }

            log.info("Genesis UTXOs stored: {} shelley + {} byron fund entries",
                    genesisConfig.getInitialFunds().size(),
                    genesisConfig.getByronBalances().size());
        } else {
            log.info("No genesis funds found in genesis files");
        }
    }

    private boolean hasAnyGenesisConfig() {
        return (config.getShelleyGenesisFile() != null && !config.getShelleyGenesisFile().isBlank())
                || (config.getByronGenesisFile() != null && !config.getByronGenesisFile().isBlank())
                || (!epochParamsTrackingEnabled()
                    && config.getProtocolParametersFile() != null && !config.getProtocolParametersFile().isBlank());
    }

    @Override
    public String submitTransaction(byte[] txCbor) {
        if (!isRunning.get()) {
            throw new IllegalStateException("Cannot submit transaction while node is not running");
        }
        return txSubsystem.submitTransaction(
                txCbor,
                (txHash, acceptedTxCbor) -> syncSubsystem.submitTxBytes(txHash, acceptedTxCbor, TxBodyType.CONWAY));
    }

    @Override
    public String getProtocolParameters() {
        return genesisConfig != null ? genesisConfig.getProtocolParameters() : null;
    }

    @Override
    public Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
        if (epoch < 0) {
            return Optional.empty();
        }

        LedgerStateProvider ledgerStateProvider = getLedgerStateProvider();
        if (ledgerStateProvider != null) {
            Optional<ProtocolParamsSnapshot> ledgerParams = ledgerStateProvider.getProtocolParameters(epoch);
            if (ledgerParams.isPresent()) {
                return ledgerParams;
            }
        }

        return staticProtocolParamsSnapshot(epoch);
    }

    private Optional<ProtocolParamsSnapshot> staticProtocolParamsSnapshot(int epoch) {
        String json = getProtocolParameters();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        StaticProtocolParamsSnapshotCache cached = staticProtocolParamsSnapshotCache;
        if (cached != null && cached.json().equals(json)) {
            return Optional.of(cached.snapshot().withEpoch(epoch));
        }

        try {
            ProtocolParamsSnapshot snapshot = ProtocolParamsMapper.fromNodeProtocolParamSnapshot(json, epoch);
            staticProtocolParamsSnapshotCache = new StaticProtocolParamsSnapshotCache(json, snapshot);
            return Optional.of(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("Static protocol parameters are not available", e);
        }
    }

    @Override
    public GenesisParameters getGenesisParameters() {
        if (genesisConfig == null || genesisConfig.getShelleyGenesisData() == null) {
            return null;
        }
        var d = genesisConfig.getShelleyGenesisData();
        return new GenesisParameters(
                d.activeSlotsCoeff(),
                d.updateQuorum(),
                String.valueOf(d.maxLovelaceSupply()),
                d.networkMagic(),
                d.epochLength(),
                d.systemStart(),
                d.slotsPerKESPeriod(),
                (int) d.slotLength(),
                d.maxKESEvolutions(),
                d.securityParam()
        );
    }

    @Override
    public java.util.Map<String, Object> getEpochCalcStatus() {
        return ledgerStateSubsystem.epochCalcStatus();
    }

    /**
     * Configure one-shot adhoc rollback on startup. Set via command line args.
     * Only one of slot or epoch should be set (epoch takes precedence if both set).
     */
    public void setAdhocRollback(long rollbackToSlot, int rollbackToEpoch) {
        this.adhocRollbackToSlot = rollbackToSlot;
        this.adhocRollbackToEpoch = rollbackToEpoch;
    }

    /**
     * Propagate genesis-derived epoch params to YanoConfig so the REST layer
     * (EpochUtil) has era-aware values for epoch/slot conversion.
     */
    /**
     * Propagate genesis-derived epoch params to YanoConfig so the REST layer
     * (EpochUtil) and other consumers have era-aware values for epoch/slot conversion.
     * <p>
     * Must set all three fields together: epochLength, byronSlotsPerEpoch, firstNonByronSlot.
     * Fails fast if firstNonByronSlot cannot be resolved for an unknown Byron network.
     */
    private void propagateGenesisToConfig(com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig gc) {
        if (gc.getShelleyGenesisData() != null && gc.getShelleyGenesisData().epochLength() > 0) {
            config.setEpochLength(gc.getShelleyGenesisData().epochLength());
        }
        if (gc.getByronGenesisData() != null && gc.getByronGenesisData().k() > 0) {
            config.setByronSlotsPerEpoch(gc.getByronGenesisData().epochLength());
        } else if (gc.getShelleyGenesisData() != null && gc.getShelleyGenesisData().securityParam() > 0) {
            // Fallback: derive byronSlotsPerEpoch from Shelley securityParam
            config.setByronSlotsPerEpoch(gc.getShelleyGenesisData().securityParam() * 10);
        }
        // Resolve firstNonByronSlot — fail fast for unknown Byron networks
        long firstNonByron = DefaultEpochParamProvider
                .resolveFirstNonByronSlot(protocolMagic, gc.getByronGenesisData() != null);
        config.setFirstNonByronSlot(firstNonByron);
    }

    /**
     * Perform adhoc rollback if configured. Called from start() after chain-state
     * validation but before derived-state startup recovery and chain sync begin.
     * Does NOT require dev mode.
     * <p>
     * Rolls back ALL authoritative stores synchronously in dependency-safe order
     * (AccountState → UTXO → ChainState), verifies post-rollback state, then
     * asks the ledger subsystem to clean up derived export artifacts.
     * <p>
     * Both body tip and header tip are considered when deciding whether rollback
     * is needed. Pipelined sync can persist headers ahead of bodies, so a startup
     * rollback that only looks at the body tip could leave orphan header state
     * beyond the requested rollback point.
     */
    private void performStartupAdhocRollback() {
        long targetSlot = -1;

        if (adhocRollbackToEpoch >= 0) {
            EpochParamProvider epochParamProvider = getEpochParamProvider();
            var epochCalc = epochParamProvider != null
                    ? epochParamProvider.getEpochSlotCalc()
                    : new EpochSlotCalc(
                            config.getEpochLength(),
                            com.bloxbean.cardano.yaci.core.common.Constants.BYRON_SLOTS_PER_EPOCH, 0);

            int firstNonByronEpoch = epochCalc.firstNonByronEpoch();
            if (adhocRollbackToEpoch < firstNonByronEpoch) {
                log.error("Adhoc rollback-to-epoch={} is before first non-Byron epoch {}. Skipping.",
                        adhocRollbackToEpoch, firstNonByronEpoch);
                return;
            }

            targetSlot = epochCalc.epochToStartSlot(adhocRollbackToEpoch);
            log.info("Adhoc rollback: epoch {} → slot {}", adhocRollbackToEpoch, targetSlot);
        } else if (adhocRollbackToSlot >= 0) {
            targetSlot = adhocRollbackToSlot;
        }

        if (targetSlot < 0) return; // No rollback requested

        ChainTip currentTip = chainState.getTip();
        ChainTip currentHeaderTip = chainState.getHeaderTip();
        long currentBodySlot = currentTip != null ? currentTip.getSlot() : -1;
        long currentHeaderSlot = currentHeaderTip != null ? currentHeaderTip.getSlot() : -1;
        long currentMaxSlot = Math.max(currentBodySlot, currentHeaderSlot);
        if (currentMaxSlot < 0) {
            log.warn("Adhoc rollback requested (slot={}) but chain is empty. Skipping.", targetSlot);
            return;
        }

        if (targetSlot >= currentMaxSlot) {
            log.info("Adhoc rollback target slot {} >= current stored tip {}. Nothing to roll back.",
                    targetSlot, currentMaxSlot);
            return;
        }

        // Find the nearest stored block at or before the target slot
        Long nearestSlot = null;
        if (chainState instanceof NearestSlotLookup nearestSlotLookup) {
            nearestSlot = nearestSlotLookup.findNearestSlotAtOrBefore(targetSlot);
        }
        if (nearestSlot == null) {
            log.error("Adhoc rollback: no stored block found at or before slot {}. Skipping.", targetSlot);
            return;
        }
        if (!nearestSlot.equals(targetSlot)) {
            log.info("Adhoc rollback: exact slot {} not found, using nearest block at slot {}", targetSlot, nearestSlot);
        }
        targetSlot = nearestSlot;

        var stores = ledgerStateSubsystem.rollbackCapableStores(utxoStore);

        // Compute common rollback floor from ALL stores
        long commonFloor = 0;
        for (var store : stores) {
            long floor = store.getRollbackFloorSlot();
            if (floor > commonFloor) commonFloor = floor;
        }

        // Log per-store status
        log.info("=== Adhoc Rollback ===");
        log.info("Target slot: {}", targetSlot);
        log.info("Current body tip: slot={}, block={}",
                currentTip != null ? currentTip.getSlot() : "none",
                currentTip != null ? currentTip.getBlockNumber() : "none");
        log.info("Current header tip: slot={}, block={}",
                currentHeaderTip != null ? currentHeaderTip.getSlot() : "none",
                currentHeaderTip != null ? currentHeaderTip.getBlockNumber() : "none");
        log.info("Common rollback floor: {}", commonFloor);
        for (var store : stores) {
            log.info("  {}: latest={}, floor={}", store.storeName(),
                    store.getLatestAppliedSlot(), store.getRollbackFloorSlot());
        }

        // Validate target is within admissible range
        if (targetSlot < commonFloor) {
            log.error("=== Adhoc Rollback ABORTED ===");
            log.error("Target slot {} is below common rollback floor {}.", targetSlot, commonFloor);
            log.error("Historical reward-input facts (block issuers/fees) have been pruned beyond this point.");
            log.error("Replay of epoch boundaries before this slot would produce incorrect rewards.");
            log.error("Options: restore from checkpoint, or resync with larger retention:");
            log.error("  yano.account-state.epoch-block-data-retention-lag (default 5, try 20+)");
            throw new RuntimeException("Adhoc rollback aborted: target " + targetSlot
                    + " below floor " + commonFloor);
        }

        // Rollback in dependency-safe order: derived state first, chain tip last
        // Order: AccountState → UTXO → ChainState
        for (var store : stores) {
            log.info("Rolling back {}", store.storeName());
            store.rollbackToSlot(targetSlot);
        }

        // Post-rollback verification: each store must report latestAppliedSlot <= targetSlot
        for (var store : stores) {
            long actual = store.getLatestAppliedSlot();
            if (actual > targetSlot) {
                throw new IllegalStateException(
                        store.storeName() + " reports latestAppliedSlot=" + actual
                                + " after rollback to " + targetSlot);
            }
        }

        ChainTip newTip = chainState.getTip();
        ChainTip newHeaderTip = chainState.getHeaderTip();
        if (newHeaderTip != null && newHeaderTip.getSlot() > targetSlot) {
            throw new IllegalStateException("ChainState header tip reports slot="
                    + newHeaderTip.getSlot() + " after adhoc rollback to " + targetSlot);
        }

        // Resolve target epoch for cleanup
        Integer targetEpoch = null;
        EpochParamProvider epochParamProvider = getEpochParamProvider();
        if (epochParamProvider != null) {
            targetEpoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(targetSlot);
        }

        log.info("=== Adhoc Rollback Complete ===");
        log.info("New tip: slot={}, block={}", newTip != null ? newTip.getSlot() : "none",
                newTip != null ? newTip.getBlockNumber() : "none");
        log.info("New header tip: slot={}, block={}",
                newHeaderTip != null ? newHeaderTip.getSlot() : "none",
                newHeaderTip != null ? newHeaderTip.getBlockNumber() : "none");

        // Cleanup derived ledger export artifacts.
        if (targetEpoch != null) {
            ledgerStateSubsystem.cleanupSnapshotExportsAfterRollback(targetEpoch);
        }
    }

    private void rollbackDevnetToSlot(long targetSlot) {
        rollbackDevnet(DevnetRollbackTarget.slot(targetSlot));
    }

    private DevnetRollbackResult rollbackDevnet(DevnetRollbackTarget target) {
        requireDevMode("Rollback");
        try (var maintenance = chainStorage.maintenanceGate()
                .enterMaintenance("devnet rollback")) {
            long targetSlot = resolveDevnetRollbackTarget(target);
            ChainTip currentTip = chainState.getTip();
            if (currentTip == null) {
                throw new IllegalStateException("No chain tip available - chain is empty");
            }

            if (targetSlot < 0) {
                throw new IllegalArgumentException("Target slot must be >= 0, got: " + targetSlot);
            }

            if (targetSlot >= currentTip.getSlot()) {
                throw new IllegalArgumentException("Target slot " + targetSlot
                        + " must be less than current tip slot " + currentTip.getSlot());
            }

            log.info("API-triggered rollback: target slot={}, current tip slot={}, block={}",
                    targetSlot, currentTip.getSlot(), currentTip.getBlockNumber());

            boolean wasRunning = producerSubsystem.isRunning();
            boolean utxoPruneWasRunning = utxoSubsystem.isPruneServiceRunning();
            boolean accountHistoryPruneWasRunning = ledgerStateSubsystem.isAccountHistoryPruneServiceRunning();
            boolean blockPruneWasRunning = chainStorage.isBlockPruneServiceRunning();
            boolean utxoPrunePaused = false;
            boolean accountHistoryPrunePaused = false;
            boolean blockPrunePaused = false;
            boolean rollbackStarted = false;
            boolean rollbackCompleted = false;

            // 1. Stop block producer and derived-state pruners before rollback.
            if (wasRunning) {
                producerSubsystem.stop();
            }

            try {
                if (utxoPruneWasRunning) {
                    if (!utxoSubsystem.pausePruneServiceAndAwait(Duration.ofSeconds(5))) {
                        throw new IllegalStateException("Cannot rollback devnet because UTXO prune service did not stop");
                    }
                    utxoPrunePaused = true;
                }
                if (accountHistoryPruneWasRunning) {
                    if (!ledgerStateSubsystem.pauseAccountHistoryPruneServiceAndAwait(Duration.ofSeconds(5))) {
                        throw new IllegalStateException(
                                "Cannot rollback devnet because account-history prune service did not stop");
                    }
                    accountHistoryPrunePaused = true;
                }
                if (blockPruneWasRunning) {
                    if (!chainStorage.stopBlockPruneServiceAndAwait(Duration.ofSeconds(5))) {
                        throw new IllegalStateException("Cannot rollback devnet because block-body prune service did not stop");
                    }
                    blockPrunePaused = true;
                }

                // 2. Rollback chain state (removes blocks/headers after target slot)
                rollbackStarted = true;
                chainState.rollbackTo(targetSlot);

                // 3. Get new tip after rollback for the Point
                ChainTip newTip = chainState.getTip();
                Point rollbackPoint;
                if (newTip != null) {
                    rollbackPoint = new Point(newTip.getSlot(), HexUtil.encodeHexString(newTip.getBlockHash()));
                } else {
                    rollbackPoint = new Point(targetSlot, "0000000000000000000000000000000000000000000000000000000000000000");
                }

                // 4. Publish RollbackEvent (isReal=true so UTXO deltas get unwound)
                try {
                    EventMetadata meta = EventMetadata.builder().origin("api-rollback").build();
                    eventBus.publish(new RollbackEvent(rollbackPoint, true),
                            meta, PublishOptions.builder().build());
                } catch (Exception ex) {
                    log.warn("RollbackEvent publish failed: {}", ex.toString());
                    throw new RuntimeException("RollbackEvent publish failed during API rollback", ex);
                }
                if (!utxoSubsystem.drainAsyncHandlerAndRestart(Duration.ofSeconds(30))) {
                    throw new IllegalStateException("Async UTXO handler did not drain after API rollback");
                }
                ensureAccountHistoryRolledBack(rollbackPoint);

                // 5. Notify server (ChainSyncServerAgent sends Rollbackward to connected clients)
                if (serveSubsystem.notifyNewDataAvailable()) {
                    log.info("Notified server agents about API-triggered rollback");
                }

                // 6. Reset BodyFetchManager epoch tracker to rolled-back tip epoch
                BodyFetchManager bodyFetchManager = currentBodyFetchManager();
                EpochParamProvider epochParamProvider = getEpochParamProvider();
                if (bodyFetchManager != null && epochParamProvider != null) {
                    int rolledBackEpoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(targetSlot);
                    bodyFetchManager.initializePreviousEpoch(rolledBackEpoch);
                }

                // 7. Reset block producer to resume from new tip
                producerSubsystem.resetToChainTip();

                log.info("API-triggered rollback complete: new tip slot={}, block={}",
                        newTip != null ? newTip.getSlot() : "null",
                        newTip != null ? newTip.getBlockNumber() : "null");
                rollbackCompleted = true;
                maintenance.clearDegraded();
                return new DevnetRollbackResult(
                        newTip != null ? newTip.getSlot() : 0,
                        newTip != null ? newTip.getBlockNumber() : 0);
            } finally {
                boolean canResume = !rollbackStarted || rollbackCompleted;
                if (canResume) {
                    if (utxoPrunePaused) {
                        utxoSubsystem.startBackgroundServices();
                    }
                    if (accountHistoryPrunePaused) {
                        ledgerStateSubsystem.start();
                    }
                    if (blockPrunePaused) {
                        chainStorage.startBlockPruneService();
                    }
                } else {
                    String message = "Devnet rollback failed after chain state changed; runtime services remain paused "
                            + "and the node should be restarted before continuing";
                    log.error(message);
                    maintenance.markDegraded(message, null);
                }

                // 8. Resume block producer only after a successful rollback or pre-rollback abort.
                if (wasRunning && canResume) {
                    producerSubsystem.start();
                }
            }
        }
    }

    private long resolveDevnetRollbackTarget(DevnetRollbackTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Rollback target is required");
        }

        int paramCount = 0;
        if (target.slot() != null) paramCount++;
        if (target.blockNumber() != null) paramCount++;
        if (target.count() != null) paramCount++;

        if (paramCount == 0 || paramCount > 1) {
            throw new IllegalArgumentException("Exactly one of 'slot', 'blockNumber', or 'count' must be provided");
        }

        if (target.slot() != null) {
            return target.slot();
        }

        if (target.blockNumber() != null) {
            Long slot = chainState.getSlotByBlockNumber(target.blockNumber());
            if (slot == null) {
                throw new IllegalArgumentException("No block found with number " + target.blockNumber());
            }
            return slot;
        }

        if (target.count() < 0) {
            throw new IllegalArgumentException("Count must be >= 0, got: " + target.count());
        }

        ChainTip tip = chainState.getTip();
        if (tip == null) {
            throw new IllegalArgumentException("Chain is empty, cannot rollback by count");
        }

        long targetBlockNumber = tip.getBlockNumber() - target.count();
        if (targetBlockNumber < 0) {
            throw new IllegalArgumentException("Count " + target.count()
                    + " exceeds current chain height " + tip.getBlockNumber());
        }

        Long slot = chainState.getSlotByBlockNumber(targetBlockNumber);
        if (slot == null) {
            throw new IllegalArgumentException("No block found at block number " + targetBlockNumber);
        }
        return slot;
    }

    // --- Devnet developer tools: Snapshot, Fund, Time Advance ---

    private void requireDevMode(String operation) {
        if (!config.isDevMode()) {
            throw new IllegalStateException(operation + " requires dev mode (yano.dev-mode=true)");
        }
        if (!producerSubsystem.hasDevnetProduction()) {
            throw new IllegalStateException(operation + " requires block producer to be running");
        }
    }

    private <T> T withRuntimeMaintenance(String reason, Supplier<T> operation) {
        try (var maintenance = chainStorage.maintenanceGate().enterMaintenance(reason)) {
            T result = operation.get();
            maintenance.clearDegraded();
            return result;
        }
    }

    private void withRuntimeMaintenance(String reason, Runnable operation) {
        try (var maintenance = chainStorage.maintenanceGate().enterMaintenance(reason)) {
            operation.run();
            maintenance.clearDegraded();
        }
    }

    private void markRuntimeDegraded(String operation, String message, Throwable cause) {
        chainStorage.maintenanceGate().markDegraded(operation, message, cause);
    }

    private <T> T withRuntimeRead(String operationName, Supplier<T> operation) {
        try (var ignored = chainStorage.maintenanceGate().enterRead(operationName)) {
            return operation.get();
        }
    }

    private SnapshotInfo createDevnetSnapshot(String name) {
        return withRuntimeMaintenance("devnet snapshot create " + name, () -> {
            return devnetSnapshotCatalogService().create(name);
        });
    }

    private void restoreDevnetSnapshot(String name) {
        restoreDevnetSnapshotAndGetTip(name);
    }

    private DevnetRestoreResult restoreDevnetSnapshotAndGetTip(String name) {
        try (var maintenance = chainStorage.maintenanceGate()
                .enterMaintenance("devnet snapshot restore " + name)) {
            requireDevMode("Restore");
            return devnetSnapshotRestoreService().restoreAndGetTip(name, maintenance);
        }
    }

    private DevnetSnapshotRestoreService devnetSnapshotRestoreService() {
        return new DevnetSnapshotRestoreService(
                chainState,
                snapshotsOrThrow(),
                producerSubsystem::serviceOrNull,
                new DevnetSnapshotRestoreService.Actions() {
                    @Override
                    public boolean isBlockProducerRunning() {
                        return producerSubsystem.isRunning();
                    }

                    @Override
                    public void stopBlockProducer() {
                        producerSubsystem.stop();
                    }

                    @Override
                    public void resetBlockProducerToChainTip() {
                        producerSubsystem.resetToChainTip();
                    }

                    @Override
                    public void startBlockProducer() {
                        producerSubsystem.start();
                    }

                    @Override
                    public boolean isServerRunning() {
                        return serveSubsystem.isRunning();
                    }

                    @Override
                    public boolean stopServerAndAwait(Duration timeout) {
                        return serveSubsystem.stopAndAwait(timeout);
                    }

                    @Override
                    public void startServer() {
                        RuntimeNode.this.startServer();
                    }

                    @Override
                    public void notifyServerNewDataAvailable() {
                        serveSubsystem.notifyNewDataAvailable();
                    }

                    @Override
                    public boolean isTxAdmissionAccepting() {
                        return txSubsystem.isAccepting();
                    }

                    @Override
                    public void pauseTxAdmissionAndAwait() {
                        txSubsystem.pauseAdmissionAndAwait();
                    }

                    @Override
                    public void startTxAdmission() {
                        txSubsystem.start();
                    }

                    @Override
                    public void stopTxAdmission() {
                        txSubsystem.stop();
                    }

                    @Override
                    public void clearPendingTransactions() {
                        txSubsystem.clearPendingTransactions();
                    }

                    @Override
                    public boolean isAsyncUtxoHandlerRunning() {
                        return utxoSubsystem.isAsyncHandlerRunning();
                    }

                    @Override
                    public boolean pauseAsyncUtxoHandlerAndAwait(Duration timeout) {
                        return utxoSubsystem.pauseAsyncHandlerAndAwait(timeout);
                    }

                    @Override
                    public boolean isUtxoPruneServiceRunning() {
                        return utxoSubsystem.isPruneServiceRunning();
                    }

                    @Override
                    public boolean pauseUtxoPruneServiceAndAwait(Duration timeout) {
                        return utxoSubsystem.pausePruneServiceAndAwait(timeout);
                    }

                    @Override
                    public boolean isUtxoMetricsSamplerRunning() {
                        return utxoSubsystem.isStoreMetricsSamplerRunning();
                    }

                    @Override
                    public boolean pauseUtxoMetricsSamplerAndAwait(Duration timeout) {
                        return utxoSubsystem.pauseStoreMetricsSamplerAndAwait(timeout);
                    }

                    @Override
                    public void reinitializeUtxoAndReconcileAfterSnapshotRestore() {
                        utxoSubsystem.reinitializeAndReconcileAfterSnapshotRestore();
                        utxoStore = utxoSubsystem.store();
                    }

                    @Override
                    public void resumeUtxoAfterSnapshotRestore(boolean asyncUtxoHandlerPaused,
                                                               boolean utxoPrunePaused,
                                                               boolean utxoMetricsSamplerPaused) {
                        utxoSubsystem.resumeAfterSnapshotRestore(
                                asyncUtxoHandlerPaused,
                                utxoPrunePaused,
                                utxoMetricsSamplerPaused);
                    }

                    @Override
                    public boolean isAccountHistoryPruneServiceRunning() {
                        return ledgerStateSubsystem.isAccountHistoryPruneServiceRunning();
                    }

                    @Override
                    public boolean pauseAccountHistoryPruneServiceAndAwait(Duration timeout) {
                        return ledgerStateSubsystem.pauseAccountHistoryPruneServiceAndAwait(timeout);
                    }

                    @Override
                    public void reinitializeLedgerAndReconcileAfterSnapshotRestore() {
                        ledgerStateSubsystem.reinitializeAndReconcileAfterSnapshotRestore();
                    }

                    @Override
                    public void resumeLedgerAfterSnapshotRestore(boolean accountHistoryPrunePaused) {
                        ledgerStateSubsystem.resumeAfterSnapshotRestore(accountHistoryPrunePaused);
                    }

                    @Override
                    public boolean isBlockPruneServiceRunning() {
                        return chainStorage.isBlockPruneServiceRunning();
                    }

                    @Override
                    public boolean stopBlockPruneServiceAndAwait(Duration timeout) {
                        return chainStorage.stopBlockPruneServiceAndAwait(timeout);
                    }

                    @Override
                    public void startBlockPruneService() {
                        chainStorage.startBlockPruneService();
                    }

                    @Override
                    public void invalidateSlotTimeCache() {
                        chronologySubsystem.invalidateSlotTimeCache();
                    }
                });
    }

    private List<SnapshotInfo> listDevnetSnapshots() {
        return withRuntimeRead("devnet snapshot list",
                () -> devnetSnapshotCatalogService().list());
    }

    private void deleteDevnetSnapshot(String name) {
        withRuntimeMaintenance("devnet snapshot delete " + name,
                () -> devnetSnapshotCatalogService().delete(name));
    }

    private DevnetSnapshotCatalogService devnetSnapshotCatalogService() {
        return new DevnetSnapshotCatalogService(
                config::isDevMode,
                producerSubsystem::hasDevnetProduction,
                chainState,
                () -> chainState instanceof ChainStateSnapshots snapshots ? snapshots : null,
                this::snapshotsOrThrow,
                producerSubsystem::serviceOrNull);
    }

    private FundResult fundAddress(String address, long lovelace) {
        return withRuntimeMaintenance("devnet faucet",
                () -> devnetFaucetService().fundAddress(address, lovelace));
    }

    private DevnetFaucetService devnetFaucetService() {
        return new DevnetFaucetService(
                config::isDevMode,
                producerSubsystem::hasProduction,
                () -> utxoStore,
                this::markRuntimeDegraded);
    }

    private TimeAdvanceResult advanceTimeBySlots(int slots) {
        return withRuntimeMaintenance("devnet time advance",
                () -> devnetTimeAdvanceService().advanceBySlots(slots));
    }

    private TimeAdvanceResult advanceTimeUntilSlot(long targetSlot) {
        return withRuntimeMaintenance("devnet time advance",
                () -> devnetTimeAdvanceService().advanceUntilSlot(targetSlot));
    }

    private TimeAdvanceResult advanceTimeBySeconds(int seconds) {
        return withRuntimeMaintenance("devnet time advance",
                () -> devnetTimeAdvanceService().advanceBySeconds(seconds));
    }

    private DevnetTimeAdvanceService devnetTimeAdvanceService() {
        return new DevnetTimeAdvanceService(
                config::isDevMode,
                producerSubsystem::hasDevnetProduction,
                chainState,
                producerSubsystem,
                DevnetTimeAdvanceService.DEFAULT_MAX_ADVANCE_SLOTS,
                this::markRuntimeDegraded);
    }

    /**
     * Shift genesis timestamp back by a number of epochs, then start block producer.
     * Used in past-time-travel mode where block production is deferred until this is called.
     *
     * @param epochs number of epochs to shift genesis back
     * @return the shift in milliseconds
     */
    private long shiftGenesisAndStartProducer(int epochs) {
        return withRuntimeMaintenance("devnet genesis shift",
                () -> devnetGenesisShiftService().shiftGenesisAndStartProducer(epochs));
    }

    private DevnetGenesisShiftService devnetGenesisShiftService() {
        return new DevnetGenesisShiftService(
                config::isPastTimeTravelMode,
                this::producerStartupPlan,
                producerSubsystem::hasProduction,
                System::currentTimeMillis,
                new DevnetGenesisShiftService.Actions() {
                    @Override
                    public GenesisConfig genesisConfig() {
                        return genesisConfig;
                    }

                    @Override
                    public void setConfigGenesisTimestamp(long timestampMillis) {
                        config.setGenesisTimestamp(timestampMillis);
                    }

                    @Override
                    public String shelleyGenesisFile() {
                        return config.getShelleyGenesisFile();
                    }

                    @Override
                    public void applyShiftedGenesis(GenesisConfig shiftedGenesisConfig) {
                        genesisConfig = shiftedGenesisConfig;
                        propagateGenesisToConfig(genesisConfig);
                        refreshGenesisBootstrapData(genesisConfig.getShelleyGenesisData());
                    }

                    @Override
                    public boolean isFreshStart() {
                        return chainState.getTip() == null;
                    }

                    @Override
                    public void setResolvedGenesisTimestamp(long timestampMillis) {
                        resolvedGenesisTimestamp = timestampMillis;
                    }

                    @Override
                    public void initSlotTimeCalculator() {
                        RuntimeNode.this.initSlotTimeCalculator();
                    }

                    @Override
                    public void setConwayEraStartIfFreshStart(boolean freshStart) {
                        RuntimeNode.this.setConwayEraStartIfFreshStart(freshStart);
                    }

                    @Override
                    public void storeGenesisUtxosIfNeeded(boolean freshStart) {
                        RuntimeNode.this.storeGenesisUtxosIfNeeded(freshStart);
                    }

                    @Override
                    public void startSlotLeaderTimeTravel(boolean freshStart) {
                        startShiftedSlotLeaderTimeTravelProducer(freshStart);
                    }

                    @Override
                    public void startDevnetTimeTravel(boolean freshStart) {
                        startShiftedDevnetTimeTravelProducer(freshStart);
                    }
                },
                this::markRuntimeDegraded);
    }

    private void startShiftedSlotLeaderTimeTravelProducer(boolean freshStart) {
        SlotLeaderTimeTravelBlockProducer producer = createSlotLeaderTimeTravelProducer(freshStart);
        producerSubsystem.setForceSequentialSlots(true, "Past time travel");
        producer.start();
    }

    private void startShiftedDevnetTimeTravelProducer(boolean freshStart) {
        DevnetBlockBuilder blockBuilder = devnetBlockBuilderFactory().create(freshStart);
        DevnetBlockProducer producer = createDevnetTimeTravelProducer(blockBuilder);
        producerSubsystem.setForceSequentialSlots(true, "Past time travel");
        producer.start();
    }

    private ProducerStartupPlan producerStartupPlan() {
        return producerStartupPlanOverride != null
                ? producerStartupPlanOverride
                : ProducerStartupPlan.from(config);
    }

    /**
     * Catch up to wall-clock slot by rapidly producing blocks.
     * Used in past-time-travel mode after epoch shifts and tx injection are done.
     *
     * @return the time advance result
     */
    private TimeAdvanceResult catchUpToWallClock() {
        return withRuntimeMaintenance("devnet catch-up",
                () -> devnetCatchUpService().catchUpToWallClock());
    }

    private DevnetCatchUpService devnetCatchUpService() {
        return new DevnetCatchUpService(
                config::isDevMode,
                config::isPastTimeTravelSlotLeaderMode,
                chainState,
                producerSubsystem,
                () -> resolvedGenesisTimestamp,
                config::getSlotLengthMillis,
                System::currentTimeMillis,
                this::markRuntimeDegraded);
    }

    @Override
    public long slotToUnixTime(long slot) {
        return chronologySubsystem.slotToUnixTime(slot).orElse(0L);
    }

    private HeaderSyncManager currentHeaderSyncManager() {
        return syncSubsystem.currentHeaderSyncManager();
    }

    private BodyFetchManager currentBodyFetchManager() {
        return syncSubsystem.currentBodyFetchManager();
    }

    private PeerSessionStatus currentPeerSessionStatus() {
        return syncSubsystem.currentPeerSessionStatus();
    }


    /**
     * Stop Yano.
     */
    public void stop() {
        KernelState state = kernel.state();
        if (state == KernelState.CREATED || state == KernelState.STOPPED || state == KernelState.FAILED) {
            withRuntimeMaintenance("node stop", () -> {
            });
            return;
        }
        kernel.stop();
    }

    @Override
    public void close() {
        kernel.close();
    }

    private void stopRuntimeServices() {
        // Stop client sync
        boolean unsafeLedgerApplyWorker = syncSubsystem.stopForShutdown();

        // Stop block producer
        producerSubsystem.stop();
        closeNonceListenerSubscriptions();

        // Disable admission before stopping the server so a half-stopped N2N
        // server cannot continue admitting transactions.
        txSubsystem.stop();

        // Stop server
        serveSubsystem.stop();

        pauseRuntimeBackgroundServices();

        if (unsafeLedgerApplyWorker) {
            unsafeLedgerApplyShutdown = true;
        }
    }

    private void closeRuntimeResources(boolean unsafeLedgerApplyWorker) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            producerSubsystem.stop();
        } catch (Exception e) {
            log.warn("Error stopping block producer during close", e);
        }

        closeNonceListenerSubscriptions();

        closeTerminalResource("transaction subsystem", txSubsystem::close);
        closeTerminalResource("server subsystem", serveSubsystem::close);

        pauseRuntimeBackgroundServices();

        closeTerminalResource("sync subsystem", syncSubsystem::close);

        schedulers.close();

        if (!unsafeLedgerApplyWorker) {
            try {
                if (!utxoSubsystem.drainAsyncHandlerBeforeClose(Duration.ofSeconds(30))) {
                    unsafeLedgerApplyWorker = true;
                }
            } catch (Exception e) {
                unsafeLedgerApplyWorker = true;
                log.warn("Error draining async UTXO event handler during shutdown", e);
            }
        }

        if (!unsafeLedgerApplyWorker) {
            // Stop plugins/listeners before closing the bus and ChainState only after all apply
            // workers are stopped/drained. Closing shared state first can make a queued async
            // listener fail while applying an accepted block event.
            try { if (pluginManager != null) pluginManager.close(); } catch (Exception ignored) {}
            try { utxoSubsystem.closeEventHandlers(); } catch (Exception ignored) {}
            try { ledgerStateSubsystem.closeEventHandlers(); } catch (Exception ignored) {}
            try { eventBus.close(); } catch (Exception ignored) {}
            try { utxoSubsystem.close(); } catch (Exception ignored) {}
            try { ledgerStateSubsystem.close(); } catch (Exception ignored) {}
        } else {
            log.error("Skipping plugin/listener close because an apply worker did not stop or drain");
            log.error("Skipping EventBus close because an apply worker did not stop or drain");
        }

        boolean unsafeLedgerApplyWorkerAtClose = unsafeLedgerApplyWorker;
        closeTerminalResource("chain storage", () -> chainStorage.closeAfterRuntimeDrain(unsafeLedgerApplyWorkerAtClose));
    }

    private void closeTerminalResource(String name, Runnable closeAction) {
        try {
            closeAction.run();
        } catch (Exception e) {
            log.warn("Error closing {} during runtime close", name, e);
        }
    }

    // Rollback handling - coordinates between managers and handles server notifications
    public void handleChainSyncRollback(Point point) {
        syncSubsystem.handleChainSyncRollback(point);
    }

    private void ensureAccountHistoryRolledBack(Point rollbackPoint) {
        ledgerStateSubsystem.ensureAccountHistoryRolledBack(rollbackPoint);
    }

    /**
     * Update sync phase based on sync progress
     */
    public void updateSyncProgress(long slot, long blockNumber) {
        syncSubsystem.updateSyncProgress(slot, blockNumber);
    }

    /**
     * Notify the server about new block availability when blocks are stored in pipeline mode.
     * This is called by PipelineDataListener after blocks are successfully stored by BodyFetchManager.
     * Only notifies during STEADY_STATE (at tip) to avoid excessive notifications during initial sync.
     */
    public void notifyServerNewBlockStored() {
        syncSubsystem.notifyServerNewBlockStored();
    }

    /**
     * Resume BodyFetchManager when headers start flowing after intersection.
     * This provides immediate resume instead of waiting for the 30s timeout.
     */
    public void resumeBodyFetchOnHeaderFlow() {
        syncSubsystem.resumeBodyFetchOnHeaderFlow();
    }

    public void onPeerDisconnected() {
        syncSubsystem.onPeerDisconnected();
    }

    public void requestPeerRecovery(PeerRecoveryReason reason) {
        syncSubsystem.requestPeerRecovery(reason);
    }

    // Status and monitoring methods
    public boolean isRunning() {
        return isRunning.get();
    }

    public boolean isSyncing() {
        return syncSubsystem.isSyncing();
    }

    public boolean isServerRunning() {
        return serveSubsystem.isRunning();
    }

    public long getBlocksProcessed() {
        return syncSubsystem.blocksProcessed();
    }

    public long getLastProcessedSlot() {
        return syncSubsystem.lastProcessedSlot();
    }

    @Override
    public ChainTip getLocalTip() {
        return chainState.getTip();
    }

    @Override
    public byte[] getBlock(byte[] blockHash) {
        return chainState.getBlock(blockHash);
    }

    @Override
    public byte[] getBlockByNumber(long blockNumber) {
        return chainState.getBlockByNumber(blockNumber);
    }

    @Override
    public Era getBlockEra(long blockNumber) {
        return chainState.getBlockEra(blockNumber);
    }

    public RuntimeMaintenanceGate getMaintenanceGate() {
        return chainStorage.maintenanceGate();
    }

    public YanoConfig getConfig() {
        return config;
    }

    @Override
    public boolean recoverChain() {
        try (var maintenance = chainStorage.maintenanceGate().enterMaintenance("chain state recovery")) {
            if (isRunning()) {
                throw new IllegalStateException("Cannot recover chain state while node is running. Stop the node first.");
            }

            ChainStateRecovery recovery = chainStateRecoveryOrNull();
            if (recovery != null) {
                log.info("🔧 Initiating chain state recovery...");

                // First check if recovery is needed
                if (!recovery.detectCorruption()) {
                    log.info("✅ No corruption detected, recovery not needed");
                    return false;
                }

                // Perform recovery
                try {
                    recovery.recoverFromCorruption();
                } catch (RuntimeException | Error e) {
                    maintenance.markDegraded(
                            "Chain state recovery failed; storage may need operator repair or process restart",
                            e);
                    throw e;
                }
                maintenance.clearDegraded();
                return true;
            } else {
                log.info("Chain state recovery not supported for current storage");
                return false;
            }
        }
    }

    @Override
    public void registerListeners(Object... listeners) {
        var defaultOption = SubscriptionOptions.builder().build();
        for (Object listener : listeners) {
            AnnotationListenerRegistrar.register(eventBus, listener, defaultOption);
        }
    }

    @Override
    public void registerListener(Object listener, SubscriptionOptions sbOptions) {
        AnnotationListenerRegistrar.register(eventBus, listener, sbOptions);
    }

    /**
     * Validate chain state integrity and attempt automatic recovery if corruption is detected
     */
    private void validateChainState() {
        ChainStateRecovery recovery = chainStateRecoveryOrNull();
        if (recovery != null) {
            log.info("🔍 Validating chain state integrity...");

            if (recovery.detectCorruption()) {
                log.warn("🚨 Chain state corruption detected during startup!");

                // Attempt automatic recovery
                try {
                    log.info("🔧 Attempting automatic recovery...");
                    recovery.recoverFromCorruption();
                    log.info("✅ Chain state recovered successfully - sync can proceed");
                } catch (Exception e) {
                    log.error("❌ Automatic recovery failed", e);
                    throw new RuntimeException("Chain state is corrupted and automatic recovery failed. " +
                            "Please manually recover using: curl -X POST http://localhost:8080/api/v1/node/recover", e);
                }
            } else {
                log.info("✅ Chain state integrity validated - no corruption detected");
            }
        } else {
            log.debug("Chain state validation skipped (in-memory storage)");
        }
    }

    @Override
    public NodeStatus getStatus() {
        ChainTip localTip = statusLocalTip();
        ChainTip headerTip = statusHeaderTip();
        PeerSessionStatus peerStatus = currentPeerSessionStatus();
        PeerRecoveryFailureTracker.Snapshot recoveryStatus = syncSubsystem.peerRecoverySnapshot();
        UpstreamStatus upstreamStatus = syncSubsystem.upstreamStatus();
        TxDiffusionStats txDiffusionStats = txSubsystem.txDiffusionStats();
        RuntimeMaintenanceGate maintenanceGate = chainStorage.maintenanceGate();
        RuntimeMaintenanceGate.Degradation maintenanceDegradation = maintenanceGate.degradation();

        String statusMessage = "Node is " + (isRunning() ? "running" : "stopped");

        // Add pipeline-specific status if in pipeline mode
        if (syncSubsystem.isPipelinedMode()) {
            statusMessage += " (phase: " + syncSubsystem.syncPhase().name() + ")";
            HeaderSyncManager headerSyncManager = currentHeaderSyncManager();
            BodyFetchManager bodyFetchManager = currentBodyFetchManager();

            // Add header tip information
            if (headerTip != null) {
                // Calculate header-body gap for pipeline monitoring
                long gap = localTip != null ?
                        headerTip.getSlot() - localTip.getSlot() :
                        headerTip.getSlot();

                statusMessage += String.format(" [gap: %d blocks]", gap);
            }

            // Add header metrics if available
            if (headerSyncManager != null) {
                var headerMetrics = headerSyncManager.getHeaderMetrics();
                statusMessage += String.format(" [headers: %d]", headerMetrics.totalHeaders);
            }

            // Add body metrics if available
            if (bodyFetchManager != null) {
                var bodyStatus = bodyFetchManager.getStatus();
                statusMessage += String.format(" [bodies: %d]", bodyStatus.bodiesReceived);
            }
        }

        if (peerStatus != null) {
            statusMessage += String.format(" [peer: %s/%s]", peerStatus.peerName(), peerStatus.state());
        }

        if (recoveryStatus.consecutiveFailures() > 0) {
            statusMessage += String.format(" [peerRecovery: %d/%d%s]",
                    recoveryStatus.consecutiveFailures(),
                    recoveryStatus.maxFailures(),
                    recoveryStatus.terminal() ? " terminal" : "");
        }

        if (maintenanceGate.isMaintenanceActive()) {
            statusMessage += String.format(" [maintenance: %s]", maintenanceGate.activeReason());
        }

        if (maintenanceDegradation != null) {
            statusMessage += String.format(" [runtimeDegraded: %s]", maintenanceDegradation.message());
        }

        Tip remoteTip = syncSubsystem.remoteTip();
        Point remotePoint = remoteTip != null ? remoteTip.getPoint() : null;
        RelayConnectionSnapshot relayConnectionSnapshot = relayConnectionManager.snapshot();
        PeerGovernorSnapshot peerGovernorSnapshot = syncSubsystem.peerGovernorSnapshot();

        return NodeStatus.builder()
                .running(isRunning())
                .syncing(isSyncing())
                .serverRunning(isServerRunning())
                .blocksProcessed(syncSubsystem.blocksProcessed())
                .lastProcessedSlot(syncSubsystem.lastProcessedSlot())
                .localTipSlot(localTip != null ? localTip.getSlot() : null)
                .localTipBlockNumber(localTip != null ? localTip.getBlockNumber() : null)
                .remoteTipSlot(remotePoint != null ? remotePoint.getSlot() : null)
                .remoteTipBlockNumber(remotePoint != null ? remoteTip.getBlock() : null)
                .initialSyncComplete(syncSubsystem.isInitialSyncComplete())
                .syncMode(syncSubsystem.isPipelinedMode() ? "pipelined" : "sequential")
                .statusMessage(statusMessage)
                .maintenanceActive(maintenanceGate.isMaintenanceActive())
                .maintenanceReason(maintenanceGate.activeReason())
                .runtimeDegraded(maintenanceDegradation != null)
                .runtimeDegradedReason(maintenanceDegradation != null ? maintenanceDegradation.message() : null)
                .runtimeDegradedOperation(maintenanceDegradation != null ? maintenanceDegradation.operation() : null)
                .runtimeDegradedAtMillis(maintenanceDegradation != null ? maintenanceDegradation.timestampMillis() : null)
                .peerName(peerStatus != null ? peerStatus.peerName() : null)
                .upstreamMode(upstreamStatus.mode().configValue())
                .upstreamConfiguredPeerCount(upstreamStatus.configuredPeerCount())
                .upstreamHotPeerCount(upstreamStatus.hotPeerCount())
                .upstreamObserverPeerCount(upstreamStatus.observerPeerCount())
                .upstreamKnownPeerCount(upstreamStatus.knownPeerCount())
                .upstreamCandidateHeaderCount(upstreamStatus.candidateHeaderCount())
                .upstreamActivePeer(upstreamStatus.activePeerName())
                .upstreamTxForwarding(upstreamStatus.txForwarding())
                .upstreamMultiPeerObservationOnly(upstreamStatus.multiPeerObservationOnly())
                .upstreamDiscoveryRunning(upstreamStatus.discoveryRunning())
                .relayAutoDiscovery(serveSubsystem.isRelayAutoDiscoveryEnabled())
                .relayAdvertisedHost(serveSubsystem.advertisedHost())
                .relayAdvertisedPort(serveSubsystem.advertisedPort())
                .relayInboundConnectionCount(relayConnectionSnapshot.inboundConnectionCount())
                .relayOutboundConnectionCount(relayConnectionSnapshot.outboundConnectionCount())
                .relayEstablishedConnectionCount(relayConnectionSnapshot.establishedConnectionCount())
                .relayConnectingConnectionCount(relayConnectionSnapshot.connectingConnectionCount())
                .relayRejectedInboundConnections(relayConnectionSnapshot.rejectedInboundConnections())
                .relayFailedOutboundConnections(relayConnectionSnapshot.failedOutboundConnections())
                .relayConnectionsPerIpMax(relayConnectionSnapshot.connectionsPerIpMax())
                .relayKnownPeerCount(peerGovernorSnapshot.knownPeerCount())
                .relayColdPeerCount(peerGovernorSnapshot.coldPeerCount())
                .relayWarmPeerCount(peerGovernorSnapshot.warmPeerCount())
                .relayHotPeerCount(peerGovernorSnapshot.hotPeerCount())
                .relayBackoffPeerCount(peerGovernorSnapshot.backoffPeerCount())
                .relayQuarantinedPeerCount(peerGovernorSnapshot.quarantinedPeerCount())
                .relaySharablePeerCount(peerGovernorSnapshot.sharablePeerCount())
                .relayInboundPeerCount(peerGovernorSnapshot.inboundPeerCount())
                .relayGossipPeerCount(peerGovernorSnapshot.gossipPeerCount())
                .relayLedgerPeerCount(peerGovernorSnapshot.ledgerPeerCount())
                .relayBootstrapPeerCount(peerGovernorSnapshot.bootstrapPeerCount())
                .relayGovernorTargetHotPeers(peerGovernorSnapshot.targetHotPeers())
                .relayGovernorTargetWarmPeers(peerGovernorSnapshot.targetWarmPeers())
                .relayGovernorLastReconcileAtMillis(peerGovernorSnapshot.lastReconcileAtMillis())
                .upstreamValidationLevel(upstreamStatus.validationLevel())
                .upstreamValidationAcceptedHeaders(upstreamStatus.validationAcceptedHeaders())
                .upstreamValidationRejectedHeaders(upstreamStatus.validationRejectedHeaders())
                .upstreamValidationLastRejectedStage(upstreamStatus.validationLastRejectedStage())
                .upstreamValidationLastRejectedReason(upstreamStatus.validationLastRejectedReason())
                .mempoolSize(txSubsystem.mempoolSize())
                .mempoolBytes(txSubsystem.mempoolBytes())
                .mempoolMaxTxs(txSubsystem.mempoolMaxTxs())
                .mempoolMaxBytes(txSubsystem.mempoolMaxBytes())
                .mempoolTtlSeconds(txSubsystem.mempoolTtlSeconds())
                .mempoolAccepting(txSubsystem.isAccepting())
                .mempoolValidationAvailable(txSubsystem.transactionValidationService() != null)
                .mempoolEvaluationAvailable(txSubsystem.isTransactionEvaluationAvailable())
                .txDiffusionMode(txSubsystem.txDiffusionMode())
                .txDiffusionEnabled(txSubsystem.txDiffusionEnabled())
                .txDiffusionPeerCount(txDiffusionStats.peerCount())
                .txDiffusionAcceptedMempoolEvents(txDiffusionStats.acceptedMempoolEvents())
                .txDiffusionInboundAccepted(txDiffusionStats.inboundAccepted())
                .txDiffusionInboundRejected(txDiffusionStats.inboundRejected())
                .txDiffusionInboundIgnored(txDiffusionStats.inboundIgnored())
                .txDiffusionOutboundForwarded(txDiffusionStats.outboundForwarded())
                .txDiffusionOutboundSuppressed(txDiffusionStats.outboundSuppressed())
                .txDiffusionServedTxs(txDiffusionStats.servedTxs())
                .txDiffusionServedBytes(txDiffusionStats.servedBytes())
                .txDiffusionInFlightTxs(txDiffusionStats.inFlightTxs())
                .txDiffusionInFlightBytes(txDiffusionStats.inFlightBytes())
                .peerState(peerStatus != null ? peerStatus.state().name() : null)
                .peerRecoveryReason(peerRecoveryReason(peerStatus, recoveryStatus))
                .peerRecoveryFailures(recoveryStatus.consecutiveFailures())
                .peerMaxRecoveryFailures(recoveryStatus.maxFailures())
                .peerRecoveryTerminal(recoveryStatus.terminal())
                .peerTerminalFailureMessage(peerStatus != null && peerStatus.terminalFailureMessage() != null
                        ? peerStatus.terminalFailureMessage()
                        : recoveryStatus.message())
                .peerApplicationProgressAgeMillis(peerStatus != null ? peerStatus.applicationProgressAgeMillis() : null)
                .peerKeepAliveAgeMillis(peerStatus != null ? peerStatus.keepAliveAgeMillis() : null)
                .peerBodyFetchInProgress(peerStatus != null ? peerStatus.bodyFetchInProgress() : null)
                .peerBodyFetchInProgressAgeMillis(peerStatus != null ? peerStatus.bodyFetchInProgressAgeMillis() : null)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ChainTip statusLocalTip() {
        if (closed.get()) {
            return null;
        }
        try {
            return getLocalTip();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private ChainTip statusHeaderTip() {
        if (closed.get()) {
            return null;
        }
        try {
            return chainState.getHeaderTip();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static String peerRecoveryReason(PeerSessionStatus peerStatus,
                                             PeerRecoveryFailureTracker.Snapshot recoveryStatus) {
        if (peerStatus != null
                && peerStatus.recoveryAttempts() > 0
                && peerStatus.lastRecoveryReason() != null
                && peerStatus.lastRecoveryReason() != PeerRecoveryReason.UNKNOWN) {
            return peerStatus.lastRecoveryReason().name();
        }

        if (recoveryStatus != null
                && recoveryStatus.consecutiveFailures() > 0
                && recoveryStatus.lastReason() != null
                && recoveryStatus.lastReason() != PeerRecoveryReason.UNKNOWN) {
            return recoveryStatus.lastReason().name();
        }

        return null;
    }

    @Override
    public void addBlockChainDataListener(BlockChainDataListener listener) {
        if (listener != null && !blockChainDataListeners.contains(listener)) {
            blockChainDataListeners.add(listener);
        }
    }

    @Override
    public void removeBlockChainDataListener(BlockChainDataListener listener) {
        blockChainDataListeners.remove(listener);
    }

    @Override
    public void addNodeEventListener(NodeEventListener listener) {
        if (listener != null && !nodeEventListeners.contains(listener)) {
            nodeEventListeners.add(listener);
        }
    }

    @Override
    public void removeNodeEventListener(NodeEventListener listener) {
        nodeEventListeners.remove(listener);
    }

    /**
     * Print detailed startup status for debugging
     */
    private void printStartupStatus() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🚀 YACI NODE STARTUP STATUS");
        log.info("═══════════════════════════════════════════════════════════");

        // Client status
        log.info("📡 CLIENT MODE: {}", config.isEnableClient() ? "ENABLED" : "DISABLED");
        if (config.isEnableClient()) {
            log.info("   └─ Remote: {}:{}", remoteCardanoHost, remoteCardanoPort);
            log.info("   └─ Syncing: {}", isSyncing() ? "YES" : "NO");
            log.info("   └─ Blocks processed: {}", syncSubsystem.blocksProcessed());
            log.info("   └─ Last slot: {}", syncSubsystem.lastProcessedSlot());
        }

        // Server status
        log.info("🌐 SERVER MODE: {}", config.isEnableServer() ? "ENABLED" : "DISABLED");
        if (config.isEnableServer()) {
            log.info("   └─ Port: {}", serverPort);
            log.info("   └─ Running: {}", isServerRunning() ? "YES" : "NO");
            log.info("   └─ Protocol magic: {}", protocolMagic);
        }

        // Block producer status
        if (config.isEnableBlockProducer()) {
            log.info("BLOCK PRODUCER: ENABLED (devnet)");
            log.info("   Block interval: {}ms", config.getBlockTimeMillis());
            log.info("   Lazy mode: {}", config.isLazyBlockProduction());
        }

        // Chain state status
        ChainTip tip = chainState.getTip();
        log.info("💾 CHAIN STATE: {}", tip != null ? "HAS DATA" : "EMPTY");
        if (tip != null) {
            log.info("   └─ Tip slot: {}", tip.getSlot());
            log.info("   └─ Tip block: {}", tip.getBlockNumber());
            log.info("   └─ Storage: {}", config.isUseRocksDB() ? "RocksDB" : "InMemory");
        } else {
            log.warn("   └─ ⚠️  NO BLOCKCHAIN DATA - Server cannot serve requests");
        }

        // Overall status
        boolean canServeClients = tip != null && isServerRunning();
        log.info("🎯 READY TO SERVE: {}", canServeClients ? "YES ✅" : "NO ❌");

        if (!canServeClients) {
            log.warn("⚠️  DIAGNOSTIC: Real Cardano nodes will not connect because:");
            if (tip == null) {
                log.warn("   • Server has no blockchain data to serve");
                log.warn("   • Wait for client sync to download blocks first");
            }
            if (!isServerRunning()) {
                log.warn("   • Server is not running properly");
            }
        }

        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * Handle intersection found event - transition to INTERSECT_PHASE
     */
    public void onIntersectionFound() {
        syncSubsystem.onIntersectionFound();
    }

    /**
     * If local tip is already close to the remote tip, transition to STEADY_STATE immediately.
     * Invoked on intersection-found with the remote tip info available.
     */
    public void maybeFastTransitionToSteadyState(Tip remoteTip) {
        syncSubsystem.maybeFastTransitionToSteadyState(remoteTip);
    }

    private PeerClientFactory createPeerClientFactory() {
        boolean sourcePortReuse = resolveBoolean(
                this.runtimeOptions.globals(),
                YanoPropertyKeys.Relay.CONNECTION_SOURCE_PORT_REUSE,
                true);
        if (!sourcePortReuse) {
            return DefaultPeerClientFactory.supervised();
        }

        Optional<String> bindHost = resolveSourcePortReuseBindHost();
        if (bindHost.isEmpty()) {
            log.warn("Relay outbound source-port reuse enabled, but no concrete local bind address could be resolved; using normal outbound dials");
            return DefaultPeerClientFactory.supervised();
        }

        log.info("Relay outbound source-port reuse enabled: binding upstream dials to {}:{}",
                bindHost.get(), serverPort);
        return DefaultPeerClientFactory.supervisedWithLocalBind(bindHost.get(), serverPort);
    }

    private Optional<String> resolveSourcePortReuseBindHost() {
        for (SourcePortProbeTarget target : sourcePortProbeTargets()) {
            Optional<String> localHost = LocalBindAddressResolver.resolveForRemote(target.host(), target.port());
            if (localHost.isPresent()) {
                log.debug("Resolved relay source-port bind address {} using route to {}:{}",
                        localHost.get(), target.host(), target.port());
                return localHost;
            }
        }
        return Optional.empty();
    }

    private List<SourcePortProbeTarget> sourcePortProbeTargets() {
        List<SourcePortProbeTarget> targets = new ArrayList<>();
        if (config.effectiveUpstream() != null) {
            for (UpstreamPeerConfig peer : config.effectiveUpstream().orderedPeers()) {
                addSourcePortProbeTarget(targets, peer.getHost(), peer.getPort());
            }
        }
        addSourcePortProbeTarget(targets, remoteCardanoHost, remoteCardanoPort);

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<SourcePortProbeTarget> deduplicated = new ArrayList<>();
        for (SourcePortProbeTarget target : targets) {
            String key = target.host() + "\0" + target.port();
            if (seen.add(key)) {
                deduplicated.add(target);
            }
        }
        return deduplicated;
    }

    private static void addSourcePortProbeTarget(List<SourcePortProbeTarget> targets, String host, int port) {
        if (host == null || host.isBlank() || port <= 0 || port > 65_535) {
            return;
        }
        targets.add(new SourcePortProbeTarget(host.trim(), port));
    }

    private static long parseLong(Object obj, long def) {
        if (obj instanceof Number n) return n.longValue();
        if (obj != null) {
            try { return Long.parseLong(String.valueOf(obj)); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean resolveBoolean(Map<String, Object> globals, String key, boolean def) {
        Object val = globals != null ? globals.get(key) : null;
        if (val instanceof Boolean b) return b;
        if (val != null) {
            try { return Boolean.parseBoolean(String.valueOf(val)); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String resolveString(Map<String, Object> globals, String key, String def) {
        Object val = globals != null ? globals.get(key) : null;
        if (val == null) {
            return def;
        }
        String str = String.valueOf(val).trim();
        return str.isEmpty() ? def : str;
    }
}
