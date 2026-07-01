package com.bloxbean.cardano.yano.runtime.sync;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.helper.PipelineConfig;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.SyncPhase;
import com.bloxbean.cardano.yano.api.config.UpstreamConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPeerConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPreset;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.events.SyncStatusChangedEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.runtime.BodyFetchManager;
import com.bloxbean.cardano.yano.runtime.HeaderSyncManager;
import com.bloxbean.cardano.yano.runtime.apply.LedgerApplyProcessor;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateRecovery;
import com.bloxbean.cardano.yano.runtime.chain.OriginRollbackCapable;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystem;
import com.bloxbean.cardano.yano.p2p.peer.DefaultPeerClientFactory;
import com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.p2p.peer.PeerEndpoint;
import com.bloxbean.cardano.yano.p2p.peer.PeerFailureMessage;
import com.bloxbean.cardano.yano.p2p.peer.PeerRecoveryFailureTracker;
import com.bloxbean.cardano.yano.p2p.peer.PeerRecoveryReason;
import com.bloxbean.cardano.yano.runtime.peer.PeerSession;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionCallbacks;
import com.bloxbean.cardano.yano.p2p.peer.PeerSessionStatus;
import com.bloxbean.cardano.yano.p2p.connection.RelayConnectionListener;
import com.bloxbean.cardano.yano.runtime.server.ServeSubsystem;
import com.bloxbean.cardano.yano.runtime.storage.ChainStorageSubsystem;
import com.bloxbean.cardano.yano.consensus.selection.CandidateHeader;
import com.bloxbean.cardano.yano.consensus.selection.ChainSelectionContext;
import com.bloxbean.cardano.yano.consensus.selection.ChainSelectionDecision;
import com.bloxbean.cardano.yano.consensus.selection.ChainSelectionStrategy;
import com.bloxbean.cardano.yano.p2p.governor.FileBackedPeerStore;
import com.bloxbean.cardano.yano.consensus.selection.HeaderFanIn;
import com.bloxbean.cardano.yano.consensus.selection.InMemoryCandidateHeaderStore;
import com.bloxbean.cardano.yano.p2p.governor.InMemoryPeerStore;
import com.bloxbean.cardano.yano.runtime.sync.multipeer.ObserverPeerSession;
import com.bloxbean.cardano.yano.p2p.governor.PeerAddressPolicy;
import com.bloxbean.cardano.yano.p2p.governor.PeerDescriptor;
import com.bloxbean.cardano.yano.p2p.governor.PeerGovernor;
import com.bloxbean.cardano.yano.p2p.governor.PeerGovernorSnapshot;
import com.bloxbean.cardano.yano.p2p.governor.PeerSource;
import com.bloxbean.cardano.yano.p2p.governor.PeerStore;
import com.bloxbean.cardano.yano.p2p.governor.PeerStoreEntry;
import com.bloxbean.cardano.yano.p2p.governor.PeerUse;
import com.bloxbean.cardano.yano.consensus.selection.TrustedOrQuorumCandidateWithinRollbackWindow;
import com.bloxbean.cardano.yano.p2p.discovery.YaciPeerDiscoveryService;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidator;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidatorFactory;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationSnapshot;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidator;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidatorFactory;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.PeerClass;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxDiffusion;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Owns upstream peer-session lifecycle, recovery, sync phase tracking, and
 * ChainSync rollback coordination.
 */
public final class SyncSubsystem implements Subsystem, PeerSessionCallbacks {
    private static final int MAX_PEER_RECOVERY_FAILURES = 10;
    private static final long DEFAULT_SECURITY_PARAM = 2160L;
    private static final double DEFAULT_ACTIVE_SLOTS_COEFF = 0.05D;
    private static final long DEFAULT_SELECTION_ROLLBACK_WINDOW_SLOTS =
            (long) Math.ceil(DEFAULT_SECURITY_PARAM / DEFAULT_ACTIVE_SLOTS_COEFF);
    private static final long SELECTION_PROMOTION_MIN_LEAD_BLOCKS = 10L;
    private static final long SELECTION_PROMOTION_MIN_INTERVAL_MILLIS = 120_000L;
    private static final long HEADER_CONTINUITY_VALIDATION_LIMIT =
            Long.getLong(YanoPropertyKeys.Pipeline.HEADER_CONTINUITY_VALIDATION_BLOCKS, 100_000L);

    private final YanoConfig config;
    private final ChainState chainState;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final ServeSubsystem serveSubsystem;
    private final LedgerStateSubsystem ledgerStateSubsystem;
    private final ChainStorageSubsystem chainStorage;
    private final BooleanSupplier runtimeRunning;
    private final Supplier<EpochParamProvider> epochParamProviderSupplier;
    private final Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier;
    private final String remoteCardanoHost;
    private final int remoteCardanoPort;
    private final long protocolMagic;
    private final UpstreamConfig upstreamConfig;
    private final CopyOnWriteArrayList<ConfiguredUpstreamPeer> upstreamPeers;
    private final int configuredUpstreamPeerCount;
    private final Logger log;
    private final PeerClientFactory peerClientFactory;
    private final ExecutorService peerRecoveryExecutor;
    private final boolean ownsPeerRecoveryExecutor;
    private final InMemoryCandidateHeaderStore candidateHeaderStore;
    private final HeaderFanIn headerFanIn;
    private final ChainSelectionStrategy chainSelectionStrategy;
    private final HeaderValidator headerValidator;
    private final BodyValidator bodyValidator;
    private final PeerStore peerStore;
    private final PeerGovernor peerGovernor;
    private final PeerAddressPolicy peerAddressPolicy;
    private final YaciPeerDiscoveryService peerDiscoveryService;
    private final Supplier<TxDiffusion> txDiffusionSupplier;
    private final Map<String, ObserverPeerSession> observerSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> observerRetryAfterMillis = new ConcurrentHashMap<>();
    private final Map<String, Long> activeUpstreamRetryAfterMillis = new ConcurrentHashMap<>();
    private final Map<String, Integer> activeUpstreamFailureCounts = new ConcurrentHashMap<>();

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean rollbackInProgress = new AtomicBoolean(false);
    private final AtomicBoolean chainSelectionEvaluationInProgress = new AtomicBoolean(false);
    private final AtomicLong syncGeneration = new AtomicLong();
    private final Object peerSessionLock = new Object();
    private final PeerRecoveryFailureTracker peerRecoveryFailureTracker =
            new PeerRecoveryFailureTracker(MAX_PEER_RECOVERY_FAILURES);

    private volatile PeerSession peerSession;
    private volatile PeerSessionSupervisorHolder supervisorHolder;
    private volatile int activeUpstreamIndex;
    private volatile boolean initialSyncComplete;
    private volatile boolean pipelinedMode;
    private volatile Tip remoteTip;
    private volatile SyncPhase syncPhase = SyncPhase.INITIAL_SYNC;
    private volatile boolean closed;
    private volatile ScheduledFuture<?> intersectionTransitionFuture;
    private volatile ScheduledFuture<?> discoveryBootstrapRetryFuture;
    private volatile long derivedSelectionRollbackWindowSlots;
    private volatile String pendingSelectedUpstreamPeerId;
    private volatile String pendingSelectedUpstreamReason;
    private volatile long lastSelectedUpstreamSwitchAtMillis;
    private PipelineConfig pipelineConfig;
    private long blocksProcessed;
    private long lastProcessedSlot;
    private long rollbackClassificationTimeout = 30_000L;

    public SyncSubsystem(YanoConfig config,
                         ChainState chainState,
                         EventBus eventBus,
                         ScheduledExecutorService scheduler,
                         ServeSubsystem serveSubsystem,
                         LedgerStateSubsystem ledgerStateSubsystem,
                         ChainStorageSubsystem chainStorage,
                         BooleanSupplier runtimeRunning,
                         Supplier<EpochParamProvider> epochParamProviderSupplier,
                         Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                         String remoteCardanoHost,
                         int remoteCardanoPort,
                         long protocolMagic,
                         Logger log) {
        this(config, chainState, eventBus, scheduler, serveSubsystem, ledgerStateSubsystem, chainStorage,
                runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log, defaultPeerRecoveryExecutor(), true,
                DefaultPeerClientFactory.supervised(), null, null);
    }

    public SyncSubsystem(YanoConfig config,
                         ChainState chainState,
                         EventBus eventBus,
                         ScheduledExecutorService scheduler,
                         ExecutorService peerRecoveryExecutor,
                         ServeSubsystem serveSubsystem,
                         LedgerStateSubsystem ledgerStateSubsystem,
                         ChainStorageSubsystem chainStorage,
                         BooleanSupplier runtimeRunning,
                         Supplier<EpochParamProvider> epochParamProviderSupplier,
                         Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                         String remoteCardanoHost,
                         int remoteCardanoPort,
                         long protocolMagic,
                         Logger log) {
        this(config, chainState, eventBus, scheduler, serveSubsystem, ledgerStateSubsystem, chainStorage,
                runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log,
                Objects.requireNonNull(peerRecoveryExecutor, "peerRecoveryExecutor"), false,
                DefaultPeerClientFactory.supervised(), null, null);
    }

    public SyncSubsystem(YanoConfig config,
                         ChainState chainState,
                         EventBus eventBus,
                         ScheduledExecutorService scheduler,
                         ExecutorService peerRecoveryExecutor,
                         ServeSubsystem serveSubsystem,
                         LedgerStateSubsystem ledgerStateSubsystem,
                         ChainStorageSubsystem chainStorage,
                         BooleanSupplier runtimeRunning,
                         Supplier<EpochParamProvider> epochParamProviderSupplier,
                         Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                         String remoteCardanoHost,
                         int remoteCardanoPort,
                         long protocolMagic,
                         Logger log,
                         BodyValidator bodyValidator) {
        this(config, chainState, eventBus, scheduler, peerRecoveryExecutor, serveSubsystem, ledgerStateSubsystem,
                chainStorage, runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log, bodyValidator, null);
    }

    public SyncSubsystem(YanoConfig config,
                         ChainState chainState,
                         EventBus eventBus,
                         ScheduledExecutorService scheduler,
                         ExecutorService peerRecoveryExecutor,
                         ServeSubsystem serveSubsystem,
                         LedgerStateSubsystem ledgerStateSubsystem,
                         ChainStorageSubsystem chainStorage,
                         BooleanSupplier runtimeRunning,
                         Supplier<EpochParamProvider> epochParamProviderSupplier,
                         Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                         String remoteCardanoHost,
                         int remoteCardanoPort,
                         long protocolMagic,
                         Logger log,
                         BodyValidator bodyValidator,
                         Supplier<TxDiffusion> txDiffusionSupplier) {
        this(config, chainState, eventBus, scheduler, serveSubsystem, ledgerStateSubsystem, chainStorage,
                runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log,
                Objects.requireNonNull(peerRecoveryExecutor, "peerRecoveryExecutor"), false,
                DefaultPeerClientFactory.supervised(), bodyValidator, txDiffusionSupplier);
    }

    public SyncSubsystem(YanoConfig config,
                         ChainState chainState,
                         EventBus eventBus,
                         ScheduledExecutorService scheduler,
                         ExecutorService peerRecoveryExecutor,
                         ServeSubsystem serveSubsystem,
                         LedgerStateSubsystem ledgerStateSubsystem,
                         ChainStorageSubsystem chainStorage,
                         BooleanSupplier runtimeRunning,
                         Supplier<EpochParamProvider> epochParamProviderSupplier,
                         Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                         String remoteCardanoHost,
                         int remoteCardanoPort,
                         long protocolMagic,
                         Logger log,
                         BodyValidator bodyValidator,
                         Supplier<TxDiffusion> txDiffusionSupplier,
                         PeerClientFactory peerClientFactory) {
        this(config, chainState, eventBus, scheduler, serveSubsystem, ledgerStateSubsystem, chainStorage,
                runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log,
                Objects.requireNonNull(peerRecoveryExecutor, "peerRecoveryExecutor"), false,
                Objects.requireNonNull(peerClientFactory, "peerClientFactory"), bodyValidator, txDiffusionSupplier);
    }

    SyncSubsystem(YanoConfig config,
                  ChainState chainState,
                  EventBus eventBus,
                  ScheduledExecutorService scheduler,
                  ServeSubsystem serveSubsystem,
                  LedgerStateSubsystem ledgerStateSubsystem,
                  ChainStorageSubsystem chainStorage,
                  BooleanSupplier runtimeRunning,
                  Supplier<EpochParamProvider> epochParamProviderSupplier,
                  Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                  String remoteCardanoHost,
                  int remoteCardanoPort,
                  long protocolMagic,
                  Logger log,
                  PeerClientFactory peerClientFactory) {
        this(config, chainState, eventBus, scheduler, serveSubsystem, ledgerStateSubsystem, chainStorage,
                runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log, peerClientFactory, null);
    }

    SyncSubsystem(YanoConfig config,
                  ChainState chainState,
                  EventBus eventBus,
                  ScheduledExecutorService scheduler,
                  ServeSubsystem serveSubsystem,
                  LedgerStateSubsystem ledgerStateSubsystem,
                  ChainStorageSubsystem chainStorage,
                  BooleanSupplier runtimeRunning,
                  Supplier<EpochParamProvider> epochParamProviderSupplier,
                  Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                  String remoteCardanoHost,
                  int remoteCardanoPort,
                  long protocolMagic,
                  Logger log,
                  PeerClientFactory peerClientFactory,
                  Supplier<TxDiffusion> txDiffusionSupplier) {
        this(config, chainState, eventBus, scheduler, serveSubsystem, ledgerStateSubsystem, chainStorage,
                runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log, defaultPeerRecoveryExecutor(), true,
                peerClientFactory, null, txDiffusionSupplier);
    }

    SyncSubsystem(YanoConfig config,
                  ChainState chainState,
                  EventBus eventBus,
                  ScheduledExecutorService scheduler,
                  ServeSubsystem serveSubsystem,
                  LedgerStateSubsystem ledgerStateSubsystem,
                  ChainStorageSubsystem chainStorage,
                  BooleanSupplier runtimeRunning,
                  Supplier<EpochParamProvider> epochParamProviderSupplier,
                  Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                  String remoteCardanoHost,
                  int remoteCardanoPort,
                  long protocolMagic,
                  Logger log,
                  ExecutorService peerRecoveryExecutor,
                  boolean ownsPeerRecoveryExecutor,
                  PeerClientFactory peerClientFactory) {
        this(config, chainState, eventBus, scheduler, serveSubsystem, ledgerStateSubsystem, chainStorage,
                runtimeRunning, epochParamProviderSupplier, genesisBootstrapDataSupplier,
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log, peerRecoveryExecutor,
                ownsPeerRecoveryExecutor, peerClientFactory, null, null);
    }

    SyncSubsystem(YanoConfig config,
                  ChainState chainState,
                  EventBus eventBus,
                  ScheduledExecutorService scheduler,
                  ServeSubsystem serveSubsystem,
                  LedgerStateSubsystem ledgerStateSubsystem,
                  ChainStorageSubsystem chainStorage,
                  BooleanSupplier runtimeRunning,
                  Supplier<EpochParamProvider> epochParamProviderSupplier,
                  Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier,
                  String remoteCardanoHost,
                  int remoteCardanoPort,
                  long protocolMagic,
                  Logger log,
                  ExecutorService peerRecoveryExecutor,
                  boolean ownsPeerRecoveryExecutor,
                  PeerClientFactory peerClientFactory,
                  BodyValidator bodyValidator,
                  Supplier<TxDiffusion> txDiffusionSupplier) {
        this.config = Objects.requireNonNull(config, "config");
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.serveSubsystem = Objects.requireNonNull(serveSubsystem, "serveSubsystem");
        this.ledgerStateSubsystem = Objects.requireNonNull(ledgerStateSubsystem, "ledgerStateSubsystem");
        this.chainStorage = Objects.requireNonNull(chainStorage, "chainStorage");
        this.runtimeRunning = runtimeRunning != null ? runtimeRunning : () -> false;
        this.epochParamProviderSupplier = epochParamProviderSupplier != null ? epochParamProviderSupplier : () -> null;
        this.genesisBootstrapDataSupplier = genesisBootstrapDataSupplier != null
                ? genesisBootstrapDataSupplier : GenesisBootstrapData::empty;
        this.protocolMagic = protocolMagic;
        this.log = Objects.requireNonNull(log, "log");
        this.upstreamConfig = config.effectiveUpstream();
        this.upstreamPeers = new CopyOnWriteArrayList<>(
                buildUpstreamPeers(this.upstreamConfig, remoteCardanoHost, remoteCardanoPort, protocolMagic));
        this.configuredUpstreamPeerCount = this.upstreamPeers.size();
        this.candidateHeaderStore = new InMemoryCandidateHeaderStore();
        this.headerFanIn = new HeaderFanIn(candidateHeaderStore);
        this.chainSelectionStrategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        this.headerValidator = HeaderValidatorFactory.from(
                this.upstreamConfig.getValidation(),
                this.epochParamProviderSupplier.get());
        this.bodyValidator = bodyValidator != null
                ? bodyValidator
                : BodyValidatorFactory.from(this.upstreamConfig.getValidation());
        this.peerStore = createPeerStore();
        this.peerGovernor = new PeerGovernor(peerStore, this.upstreamConfig.getGovernor());
        this.peerAddressPolicy = new PeerAddressPolicy(this.upstreamConfig.getDiscovery());
        seedPeerStoreFromConfiguredPeers();
        this.peerDiscoveryService = new YaciPeerDiscoveryService(
                protocolMagic,
                this.upstreamConfig.getDiscovery(),
                peerAddressPolicy,
                this::onDiscoveredPeer);
        ConfiguredUpstreamPeer initialPeer = this.upstreamPeers.isEmpty() ? null : this.upstreamPeers.getFirst();
        this.remoteCardanoHost = initialPeer != null
                ? initialPeer.endpoint().host()
                : remoteCardanoHost != null ? remoteCardanoHost : "localhost";
        this.remoteCardanoPort = initialPeer != null ? initialPeer.endpoint().port() : remoteCardanoPort;
        this.peerRecoveryExecutor = Objects.requireNonNull(peerRecoveryExecutor, "peerRecoveryExecutor");
        this.ownsPeerRecoveryExecutor = ownsPeerRecoveryExecutor;
        this.peerClientFactory = Objects.requireNonNull(peerClientFactory, "peerClientFactory");
        this.txDiffusionSupplier = txDiffusionSupplier != null ? txDiffusionSupplier : TxDiffusion::disabled;
        this.pipelineConfig = createPipelineConfig();
    }

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public void start() {
        // Client sync starts through startClientSync() after bootstrap, rollback,
        // derived-state recovery, nonce repair, and optional server startup.
    }

    public void startClientSync() {
        boolean usePipeline = config.isEnablePipelinedSync();
        try {
            syncGeneration.incrementAndGet();
            cancelIntersectionTransition();
            isSyncing.set(true);
            pipelinedMode = usePipeline;
            if (!ensureInitialUpstreamPeer()) {
                return;
            }

            ConfiguredUpstreamPeer activeUpstream = activeUpstreamPeer();
            log.info("Starting {} client sync with {}:{}...",
                    usePipeline ? "pipelined" : "sequential",
                    activeUpstream.endpoint().host(), activeUpstream.endpoint().port());

            ClientSyncTips syncTips = prepareClientSyncTips(
                    usePipeline,
                    chainState.getHeaderTip(),
                    chainState.getTip(),
                    "client sync startup");
            ChainTip headerTip = syncTips.headerTip();
            ChainTip bodyTip = syncTips.bodyTip();
            ChainTip localTip = selectClientSyncStartTip(usePipeline, headerTip, bodyTip);

            log.info("Local header_tip: {}, body_tip: {}, mode={}, using: {} for sync",
                    headerTip, bodyTip, usePipeline ? "pipelined" : "sequential",
                    localTip != null ? "slot " + localTip.getSlot() : "genesis");

            Point startPoint = determineStartPoint(localTip);
            log.info("Starting client sync from point: {}", startPoint);
            remoteTip = null;

            synchronized (peerSessionLock) {
                if (!runtimeRunning.getAsBoolean() || !config.isEnableClient()) {
                    log.info("Skipping client sync startup because Yano is stopping");
                    isSyncing.set(false);
                    return;
                }

                peerSession = createPeerSession();
                startPeerSessionSupervisor();
                try {
                    if (pipelinedMode) {
                        startPipelinedClientSync(localTip, remoteTip, startPoint);
                    } else {
                        startSequentialClientSync(startPoint);
                    }
                    peerRecoveryFailureTracker.recordSuccess();
                    clearActiveUpstreamFailure(activeUpstream.id());
                    startMultiPeerSupport(startPoint);
                } catch (Exception e) {
                    PeerRecoveryFailureTracker.Snapshot failure =
                            peerRecoveryFailureTracker.recordFailure(PeerRecoveryReason.STARTUP_FAILED, e);
                    log.warn("Initial upstream peer session start failed; supervisor will retry: {}",
                            failureMessage(failure, e));
                    markPeerRecoveryFailure(PeerRecoveryReason.STARTUP_FAILED, e, failure);
                    advanceActiveUpstreamAfterFailure(PeerRecoveryReason.STARTUP_FAILED, e);
                    if (failure.terminal()) {
                        isSyncing.set(false);
                    } else {
                        requestPeerRecovery(PeerRecoveryReason.STARTUP_FAILED);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to start client sync", e);
            isSyncing.set(false);
            pipelinedMode = false;
            throw new RuntimeException("Failed to start client sync", e);
        }
    }

    public boolean stopForShutdown() {
        boolean unsafeLedgerApplyWorker = false;
        syncGeneration.incrementAndGet();
        cancelIntersectionTransition();
        cancelDiscoveryBootstrapRetry();
        stopMultiPeerSupport();
        if (!isSyncing.get()) {
            return false;
        }

        PeerSession sessionToStop;
        synchronized (peerSessionLock) {
            PeerSessionSupervisorHolder holder = supervisorHolder;
            if (holder != null) {
                holder.close();
                supervisorHolder = null;
            }
            sessionToStop = peerSession;
        }

        try {
            if (sessionToStop != null) {
                unsafeLedgerApplyWorker = !stopPeerSessionForShutdown(sessionToStop, "active");
            }
        } catch (Exception e) {
            unsafeLedgerApplyWorker = true;
            log.warn("Error stopping peer session", e);
        }

        PeerSession replacementToStop = null;
        synchronized (peerSessionLock) {
            if (peerSession == sessionToStop) {
                peerSession = null;
            } else if (peerSession != null) {
                log.warn("Peer session changed during shutdown; stopping replacement session before close");
                replacementToStop = peerSession;
                peerSession = null;
            }
        }
        if (replacementToStop != null) {
            try {
                unsafeLedgerApplyWorker |= !stopPeerSessionForShutdown(replacementToStop, "replacement");
            } catch (Exception e) {
                unsafeLedgerApplyWorker = true;
                log.warn("Error stopping replacement peer session", e);
            }
        }
        isSyncing.set(false);
        return unsafeLedgerApplyWorker;
    }

    @Override
    public void stop() {
        stopForShutdown();
    }

    public void submitTxBytes(String txHash, byte[] txCbor, TxBodyType txBodyType) {
        String forwarding = normalizedTxForwarding();
        if ("disabled".equals(forwarding)) {
            log.debug("Transaction {} upstream forwarding disabled by policy", txHash);
            return;
        }
        TxDiffusion txDiffusion = txDiffusion();
        PeerSession activeSession = peerSession;
        ConfiguredUpstreamPeer active = activeUpstreamPeer();
        if (activeSession != null && activeSession.isRunning()
                && ("active-selected".equals(forwarding)
                || ("all-hot-trusted".equals(forwarding) && active.trusted()))) {
            PeerClass peerClass = PeerClass.ACTIVE_SELECTED;
            try {
                if (!txDiffusion.isEnabled()
                        || txDiffusion.reserveLocalSubmitForward(active.id(), peerClass, txHash, txCbor.length)) {
                    activeSession.submitTxBytes(txHash, txCbor, txBodyType);
                    txDiffusion.onLocalSubmitForwarded(active.id(), peerClass, txHash, txCbor.length);
                    log.debug("Transaction {} forwarded to upstream node", txHash);
                } else {
                    log.debug("Transaction {} already queued for upstream peer {}", txHash, active.id());
                }
            } catch (Exception e) {
                log.warn("Failed to forward transaction {} to upstream node: {}", txHash, e.getMessage());
            }
        }
        if ("all-hot-trusted".equals(forwarding)) {
            observerSessions.values().forEach(observer -> {
                if (!observer.trusted()) {
                    return;
                }
                PeerClass peerClass = PeerClass.TRUSTED_HOT;
                try {
                    if (!txDiffusion.isEnabled()
                            || txDiffusion.reserveLocalSubmitForward(observer.peerId(), peerClass, txHash, txCbor.length)) {
                        observer.submitTxBytes(txHash, txCbor, txBodyType);
                        txDiffusion.onLocalSubmitForwarded(observer.peerId(), peerClass, txHash, txCbor.length);
                    }
                } catch (Exception e) {
                    log.debug("Failed to forward transaction {} to observer upstream {}: {}",
                            txHash, observer.peerId(), e.getMessage());
                }
            });
        }
    }

    private TxDiffusion txDiffusion() {
        TxDiffusion txDiffusion = txDiffusionSupplier.get();
        return txDiffusion != null ? txDiffusion : TxDiffusion.disabled();
    }

    public HeaderSyncManager currentHeaderSyncManager() {
        PeerSession activeSession = peerSession;
        return activeSession != null ? activeSession.getHeaderSyncManager() : null;
    }

    public BodyFetchManager currentBodyFetchManager() {
        PeerSession activeSession = peerSession;
        return activeSession != null ? activeSession.getBodyFetchManager() : null;
    }

    public PeerSessionStatus currentPeerSessionStatus() {
        PeerSession activeSession = peerSession;
        return activeSession != null ? activeSession.getStatus() : null;
    }

    public PeerRecoveryFailureTracker.Snapshot peerRecoverySnapshot() {
        return peerRecoveryFailureTracker.snapshot();
    }

    public UpstreamStatus upstreamStatus() {
        String txForwarding = upstreamConfig.getTx() != null
                ? upstreamConfig.getTx().getForwarding()
                : "active-selected";
        if (upstreamPeers.isEmpty()) {
            return UpstreamStatus.idle(upstreamMode(), 0, txForwarding);
        }
        ConfiguredUpstreamPeer active = activeUpstreamPeer();
        PeerSession session = peerSession;
        int observerCount = runningObserverCount();
        boolean activeHot = session != null && session.isRunning();
        HeaderValidationSnapshot validation = headerValidator.snapshot();
        return new UpstreamStatus(
                upstreamMode(),
                configuredUpstreamPeerCount,
                (activeHot ? 1 : 0) + observerCount,
                observerCount,
                peerGovernor.snapshot().knownPeerCount(),
                candidateHeaderStore.all().size(),
                active.id(),
                active.endpoint().displayName(),
                txForwarding,
                false,
                peerDiscoveryService.isRunning(),
                validation.level(),
                validation.acceptedHeaders(),
                validation.rejectedHeaders(),
                validation.lastRejectedStage(),
                validation.lastRejectedReason());
    }

    public List<PeerStoreEntry> peerStoreEntries() {
        return peerGovernor.peerStoreEntries();
    }

    public List<PeerStoreEntry> sharablePeerEntries() {
        return peerGovernor.sharablePeers(Integer.MAX_VALUE);
    }

    public PeerGovernorSnapshot peerGovernorSnapshot() {
        return peerGovernor.snapshot();
    }

    public RelayConnectionListener peerGovernorConnectionListener() {
        return peerGovernor;
    }

    public boolean isSyncing() {
        return isSyncing.get();
    }

    public boolean isPipelinedMode() {
        return pipelinedMode;
    }

    public boolean isInitialSyncComplete() {
        return initialSyncComplete;
    }

    public SyncPhase syncPhase() {
        return syncPhase;
    }

    public Tip remoteTip() {
        return remoteTip;
    }

    public long blocksProcessed() {
        return blocksProcessed;
    }

    public long lastProcessedSlot() {
        return lastProcessedSlot;
    }

    @Override
    public void handleChainSyncRollback(Point point) {
        synchronized (peerSessionLock) {
            rollbackInProgress.set(true);
            try {
                doHandleChainSyncRollback(point);
            } finally {
                rollbackInProgress.set(false);
            }
        }
    }

    @Override
    public void updateSyncProgress(long slot, long blockNumber) {
        lastProcessedSlot = slot;
        blocksProcessed++;
        long observedRemoteTipSlot = observedRemoteTipSlot();
        if (syncPhase == SyncPhase.INITIAL_SYNC && !initialSyncComplete
                && observedRemoteTipSlot >= 0) {
            long distance = Math.max(0L, observedRemoteTipSlot - slot);
            if (distance <= 10) {
                initialSyncComplete = true;
                log.info("Initial sync complete at slot {} (distance to tip: {} slots)", slot, distance);
            }
        }

        if (syncPhase == SyncPhase.INITIAL_SYNC && initialSyncComplete) {
            SyncPhase prev = syncPhase;
            syncPhase = SyncPhase.STEADY_STATE;
            log.info("Transitioned to STEADY_STATE sync phase");
            eventBus.publish(new SyncStatusChangedEvent(prev, syncPhase),
                    EventMetadata.builder().origin("runtime").build(),
                    PublishOptions.builder().build());
            ensureMultiPeerObserversFromLocalTip();

            BodyFetchManager bodyFetchManager = currentBodyFetchManager();
            if (pipelinedMode && bodyFetchManager != null) {
                bodyFetchManager.setSyncPhase(SyncPhase.STEADY_STATE);
                bodyFetchManager.wakeFetchLoop();
                if (bodyFetchManager.isPaused()) {
                    bodyFetchManager.resume();
                    log.info("BodyFetchManager resumed after transition to STEADY_STATE");
                }
            }
        }
    }

    @Override
    public void notifyServerNewBlockStored() {
        if (syncPhase == SyncPhase.STEADY_STATE && serveSubsystem.notifyNewDataAvailable()) {
            log.debug("Notified server agents about new block availability (at tip)");
        }
    }

    @Override
    public void resumeBodyFetchOnHeaderFlow() {
        BodyFetchManager bodyFetchManager = currentBodyFetchManager();
        if (pipelinedMode && syncPhase == SyncPhase.INTERSECT_PHASE
                && bodyFetchManager != null && bodyFetchManager.isPaused()) {
            long distance = Long.MAX_VALUE;
            try {
                if (remoteTip != null && remoteTip.getPoint() != null) {
                    distance = Math.max(0, remoteTip.getPoint().getSlot() - lastProcessedSlot);
                }
            } catch (Exception ignored) {
            }

            long nearTipThreshold = 1000;
            SyncPhase nextPhase = distance <= nearTipThreshold ? SyncPhase.STEADY_STATE : SyncPhase.INITIAL_SYNC;
            SyncPhase prev = syncPhase;
            syncPhase = nextPhase;
            bodyFetchManager.setSyncPhase(nextPhase);
            bodyFetchManager.resume();

            log.info("FAST RESUME: Headers flowing - transitioned to {} (distance to tip: {} slots)",
                    nextPhase, distance == Long.MAX_VALUE ? "unknown" : String.valueOf(distance));
            if (prev != syncPhase) {
                eventBus.publish(new SyncStatusChangedEvent(prev, syncPhase),
                        EventMetadata.builder().origin("runtime").build(),
                        PublishOptions.builder().build());
            }
        }
    }

    @Override
    public void onIntersectionFound() {
        syncPhase = SyncPhase.INTERSECT_PHASE;
        log.info("Transitioned to INTERSECT_PHASE - expect rollback to intersection");

        BodyFetchManager bodyFetchManager = currentBodyFetchManager();
        if (pipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.setSyncPhase(SyncPhase.INTERSECT_PHASE);
        }

        long generation = syncGeneration.get();
        cancelIntersectionTransition();
        intersectionTransitionFuture = scheduler.schedule(() -> {
            if (runtimeRunning.getAsBoolean()
                    && generation == syncGeneration.get()
                    && syncPhase == SyncPhase.INTERSECT_PHASE) {
                long distance = Long.MAX_VALUE;
                try {
                    if (remoteTip != null && remoteTip.getPoint() != null) {
                        distance = Math.max(0, remoteTip.getPoint().getSlot() - lastProcessedSlot);
                    }
                } catch (Exception ignored) {
                }

                long nearTipThreshold = 1000;
                SyncPhase nextPhase = distance <= nearTipThreshold ? SyncPhase.STEADY_STATE : SyncPhase.INITIAL_SYNC;
                syncPhase = nextPhase;
                log.info("Auto-transitioned to {} after intersection phase timeout (distance to tip: {} slots)",
                        nextPhase, distance == Long.MAX_VALUE ? "unknown" : String.valueOf(distance));

                BodyFetchManager scheduledBodyFetchManager = currentBodyFetchManager();
                if (pipelinedMode && scheduledBodyFetchManager != null) {
                    scheduledBodyFetchManager.setSyncPhase(nextPhase);
                    if (scheduledBodyFetchManager.isPaused()) {
                        scheduledBodyFetchManager.resume();
                        log.info("BodyFetchManager resumed after auto-transition to {}", nextPhase);
                    }
                }
            }
        }, rollbackClassificationTimeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public void maybeFastTransitionToSteadyState(Tip remoteTip) {
        try {
            if (remoteTip == null || remoteTip.getPoint() == null) {
                return;
            }
            this.remoteTip = remoteTip;

            ChainTip localTip = chainState.getTip();
            if (!pipelinedMode || localTip == null) {
                return;
            }

            long remoteSlot = remoteTip.getPoint().getSlot();
            long distance = Math.max(0, remoteSlot - localTip.getSlot());
            long nearTipThreshold = 1000;
            if (distance <= nearTipThreshold) {
                boolean transitioned = syncPhase != SyncPhase.STEADY_STATE;
                syncPhase = SyncPhase.STEADY_STATE;
                BodyFetchManager bodyFetchManager = currentBodyFetchManager();
                if (bodyFetchManager != null) {
                    bodyFetchManager.setSyncPhase(SyncPhase.STEADY_STATE);
                    if (bodyFetchManager.isPaused()) {
                        bodyFetchManager.resume();
                    }
                }
                if (transitioned) {
                    log.info("NEAR-TIP FAST PATH: remote-local distance={} slots <= {}, transitioned to STEADY_STATE",
                            distance, nearTipThreshold);
                    ensureMultiPeerObserversFromLocalTip();
                }
            }
        } catch (Exception e) {
            log.debug("Fast transition near-tip check failed: {}", e.toString());
        }
    }

    @Override
    public void onPeerDisconnected() {
        PeerSessionSupervisorHolder holder = supervisorHolder;
        if (!runtimeRunning.getAsBoolean() || !config.isEnableClient() || holder == null) {
            return;
        }
        holder.notifyDisconnect();
    }

    @Override
    public void requestPeerRecovery(PeerRecoveryReason reason) {
        PeerSessionSupervisorHolder holder = supervisorHolder;
        if (!runtimeRunning.getAsBoolean() || !config.isEnableClient() || holder == null) {
            return;
        }
        holder.requestRecovery(reason != null ? reason : PeerRecoveryReason.UNKNOWN);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        stopForShutdown();
        cancelIntersectionTransition();
        PeerSessionSupervisorHolder holder = supervisorHolder;
        if (holder != null) {
            holder.close();
            supervisorHolder = null;
        }
        if (ownsPeerRecoveryExecutor) {
            peerRecoveryExecutor.shutdown();
            try {
                if (!peerRecoveryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    peerRecoveryExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                peerRecoveryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        closed = true;
    }

    @Override
    public SubsystemHealth health() {
        if (closed) {
            return SubsystemHealth.down(name(), "closed");
        }
        if (peerRecoveryFailureTracker.isTerminal()) {
            return SubsystemHealth.down(name(), peerRecoveryFailureTracker.snapshot().message());
        }
        return SubsystemHealth.up(name());
    }

    private void doHandleChainSyncRollback(Point point) {
        ChainTip localTip = chainState.getTip();
        long rollbackSlot = point.getSlot();
        BodyFetchManager bodyFetchManager = currentBodyFetchManager();

        if (pipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.pause();
            log.info("BodyFetchManager paused for rollback to slot {}", rollbackSlot);
        }

        if (localTip != null && localTip.getBlockNumber() > 1000 && rollbackSlot == 0) {
            log.error("CATASTROPHIC ROLLBACK DETECTED");
            log.error("Current tip: slot={}, block={}", localTip.getSlot(), localTip.getBlockNumber());
            log.error("Rollback requested to: slot={}", rollbackSlot);
            Thread.dumpStack();
            log.error("EMERGENCY EXIT - Check logs for debugging information");
            System.exit(1);
            return;
        }

        if (rollbackSlot == 0) {
            log.warn("Rollback requested to genesis (slot 0)");
        }

        boolean isReal = isRealRollback(point);
        chainState.rollbackTo(rollbackSlot);

        try {
            eventBus.publish(new RollbackEvent(point, isReal),
                    EventMetadata.builder().origin("runtime").build(),
                    PublishOptions.builder().build());
        } catch (Exception ex) {
            log.error("RollbackEvent publish failed after chainstate rollback to slot {}. "
                    + "Refusing to continue with possibly inconsistent ledger state.", rollbackSlot, ex);
            log.error("EMERGENCY EXIT - rollback listeners did not complete after chainstate rollback");
            System.exit(1);
            throw new RuntimeException("Rollback event handling failed after chainstate rollback to slot "
                    + rollbackSlot, ex);
        }

        try {
            ledgerStateSubsystem.ensureAccountHistoryRolledBack(point);
        } catch (Exception ex) {
            log.error("Account history rollback verification failed after chainstate rollback to slot {}. "
                    + "Refusing to continue with possibly inconsistent ledger state.", rollbackSlot, ex);
            log.error("EMERGENCY EXIT - account history did not verify after chainstate rollback");
            System.exit(1);
            throw new RuntimeException("Account history rollback verification failed after chainstate rollback to slot "
                    + rollbackSlot, ex);
        }

        log.info("ROLLBACK_EVENT: slot={}, type={}, phase={}, serverNotified={}",
                rollbackSlot, isReal ? "REAL_REORG" : "RECONNECTION", syncPhase,
                isReal && serveSubsystem.isRunning());
        log.info("Rollback to slot: {} (from tip: {}) - Type: {}",
                rollbackSlot,
                localTip != null ? String.format("slot=%d, block=%d", localTip.getSlot(), localTip.getBlockNumber()) : "null",
                isReal ? "REAL_REORG" : "RECONNECTION");

        if (isReal && serveSubsystem.notifyNewDataAvailable()) {
            log.info("Notified server agents about chain reorganization");
        }

        attemptCorruptionRecovery("post-rollback");

        EpochParamProvider epochParamProvider = epochParamProviderSupplier.get();
        if (bodyFetchManager != null && epochParamProvider != null) {
            int rolledBackEpoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(rollbackSlot);
            bodyFetchManager.initializePreviousEpoch(rolledBackEpoch);
        }

        reconcileSyncPhaseAfterRollback(bodyFetchManager);

        if (pipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.resume();
            log.info("BodyFetchManager resumed after rollback - will detect and handle gaps automatically");
        }
    }

    private void seedPeerStoreFromConfiguredPeers() {
        for (ConfiguredUpstreamPeer peer : upstreamPeers) {
            long now = System.currentTimeMillis();
            PeerSource source = PeerSource.from(peer.source());
            peerGovernor.addOrUpdatePeer(new PeerDescriptor(
                    peer.id(),
                    peer.endpoint().host(),
                    peer.endpoint().port(),
                    source,
                    peer.source(),
                    peer.trusted(),
                    source == PeerSource.LOCAL_ROOT || source == PeerSource.STATIC_UPSTREAM,
                    source != PeerSource.INBOUND,
                    1,
                    1,
                    now,
                    now,
                    null,
                    scoreForConfiguredPeer(peer)));
        }
    }

    private boolean ensureInitialUpstreamPeer() {
        if (!upstreamPeers.isEmpty()) {
            cancelDiscoveryBootstrapRetry();
            return true;
        }
        if (!discoveryBootstrapEnabled()) {
            isSyncing.set(false);
            throw new IllegalStateException("No upstream peer is configured");
        }

        log.info("No configured upstream peer available; starting discovery bootstrap");
        peerDiscoveryService.start();
        selectDiscoveredBootstrapPeer();
        if (!upstreamPeers.isEmpty()) {
            cancelDiscoveryBootstrapRetry();
            ConfiguredUpstreamPeer selected = activeUpstreamPeer();
            log.info("Selected discovery bootstrap upstream peer {} from {}",
                    selected.endpoint().displayName(), selected.source());
            return true;
        }

        log.warn("Discovery bootstrap has not produced an upstream peer yet; will retry");
        scheduleDiscoveryBootstrapRetry(30_000L);
        return false;
    }

    private void selectDiscoveredBootstrapPeer() {
        if (!upstreamPeers.isEmpty()) {
            return;
        }
        peerGovernor.hotPeers(PeerUse.CHAIN_SYNC, 1).stream()
                .filter(peer -> peerAddressPolicy.allows(peer.source(), peer.host(), peer.port()))
                .findFirst()
                .ifPresent(this::ensureKnownUpstreamPeer);
    }

    private boolean discoveryBootstrapEnabled() {
        return upstreamMode().multiPeer()
                && upstreamConfig.getDiscovery() != null
                && upstreamConfig.getDiscovery().isEnabled();
    }

    private void scheduleDiscoveryBootstrapRetry(long delayMillis) {
        if (!runtimeRunning.getAsBoolean() || !config.isEnableClient()) {
            return;
        }
        ScheduledFuture<?> existing = discoveryBootstrapRetryFuture;
        if (existing != null && !existing.isDone() && !existing.isCancelled()) {
            return;
        }
        discoveryBootstrapRetryFuture = scheduler.schedule(() -> {
            discoveryBootstrapRetryFuture = null;
            if (!runtimeRunning.getAsBoolean() || !config.isEnableClient() || peerSession != null) {
                return;
            }
            try {
                startClientSync();
            } catch (Exception e) {
                log.warn("Discovery bootstrap retry failed: {}", PeerFailureMessage.summarize(e));
            }
        }, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void cancelDiscoveryBootstrapRetry() {
        ScheduledFuture<?> future = discoveryBootstrapRetryFuture;
        if (future != null) {
            future.cancel(false);
            discoveryBootstrapRetryFuture = null;
        }
    }

    private PeerStore createPeerStore() {
        boolean discoveryEnabled = upstreamConfig.getDiscovery() != null
                && upstreamConfig.getDiscovery().isEnabled();
        if (!upstreamMode().multiPeer() && !discoveryEnabled) {
            return new InMemoryPeerStore();
        }
        Path path = peerStorePath();
        if (path == null) {
            return new InMemoryPeerStore();
        }
        log.info("Using file-backed upstream peer store at {}", path);
        return new FileBackedPeerStore(path);
    }

    private Path peerStorePath() {
        String storagePath = config.getRocksDBPath();
        if (storagePath == null || storagePath.isBlank()) {
            return null;
        }
        Path chainPath = Path.of(storagePath).toAbsolutePath();
        Path parent = chainPath.getParent();
        String name = chainPath.getFileName() != null ? chainPath.getFileName().toString() : "chainstate";
        return (parent != null ? parent : Path.of(".").toAbsolutePath())
                .resolve(name + "-upstream-peers.json");
    }

    private void onDiscoveredPeer(PeerDescriptor peer) {
        if (peer == null || !peerAddressPolicy.allows(peer.source(), peer.host(), peer.port())) {
            return;
        }
        PeerDescriptor admitted = peerGovernor.addOrUpdatePeer(peer);
        if (admitted == null) {
            return;
        }
        ensureKnownUpstreamPeer(admitted);
        if (peerSession == null && isSyncing.get() && discoveryBootstrapEnabled()) {
            ScheduledFuture<?> retry = discoveryBootstrapRetryFuture;
            if (retry != null && !retry.isDone() && !retry.isCancelled()) {
                retry.cancel(false);
                discoveryBootstrapRetryFuture = null;
                scheduleDiscoveryBootstrapRetry(0L);
            }
        }
        if ("peer-snapshot".equals(peer.sourceId())) {
            return;
        }
        if (shouldStartMultiPeerObservers()) {
            ensureMultiPeerObserversFromLocalTip();
        }
    }

    private void startMultiPeerSupport(Point startPoint) {
        if (!upstreamMode().multiPeer()) {
            return;
        }
        peerDiscoveryService.start();
        if (shouldStartMultiPeerObservers()) {
            ensureMultiPeerObservers(startPoint);
        }
    }

    private void ensureMultiPeerObserversFromLocalTip() {
        if (!upstreamMode().multiPeer() || !shouldStartMultiPeerObservers()) {
            return;
        }
        Point startPoint = observerStartPoint();
        if (startPoint != null) {
            ensureMultiPeerObservers(startPoint);
        }
    }

    private void ensureMultiPeerObservers(Point startPoint) {
        if (!runtimeRunning.getAsBoolean() || !config.isEnableClient() || !upstreamMode().multiPeer()) {
            return;
        }
        if (startPoint == null) {
            return;
        }
        int targetHot = targetHotPeers();
        if (targetHot <= 1) {
            return;
        }
        int observerTarget = Math.max(0, targetHot - 1);
        ConfiguredUpstreamPeer active = activeUpstreamPeer();
        List<PeerDescriptor> hotPeers = peerGovernor.hotPeers(
                PeerUse.CHAIN_SYNC,
                Math.max(targetHot, peerGovernor.snapshot().knownPeerCount()));
        List<String> selectedObserverIds = new ArrayList<>();

        observerSessions.forEach((id, observer) -> {
            if (selectedObserverIds.size() >= observerTarget) {
                return;
            }
            if (observer == null || sameEndpoint(observer.endpoint(), active.endpoint()) || !observer.isRunning()) {
                return;
            }
            selectedObserverIds.add(id);
        });

        for (PeerDescriptor entry : hotPeers) {
            if (selectedObserverIds.size() >= observerTarget) {
                break;
            }
            if (sameEndpoint(entry.host(), entry.port(), active.endpoint())) {
                continue;
            }
            if (selectedObserverIds.contains(entry.id())) {
                continue;
            }
            if (!peerAddressPolicy.allows(entry.source(), entry.host(), entry.port())) {
                continue;
            }
            if (!observerRetryAllowed(entry.id())) {
                continue;
            }
            ConfiguredUpstreamPeer peer = ensureKnownUpstreamPeer(entry);
            observerSessions.compute(peer.id(), (id, existing) -> {
                if (existing != null && existing.isRunning()) {
                    return existing;
                }
                if (existing != null) {
                    existing.close();
                }
                ObserverPeerSession observer = new ObserverPeerSession(
                        peer.id(),
                        peer.endpoint(),
                        peer.trusted(),
                        headerFanIn,
                        peerClientFactory,
                        this::onCandidateHeader,
                        headerValidator);
                try {
                    observer.start(startPoint);
                    observerRetryAfterMillis.remove(peer.id());
                    return observer;
                } catch (Exception e) {
                    log.warn("Failed to start observer upstream peer {} at {}: {}",
                            peer.id(), peer.endpoint().displayName(), PeerFailureMessage.summarize(e));
                    observerRetryAfterMillis.put(peer.id(), System.currentTimeMillis() + 60_000L);
                    observer.close();
                    return null;
                }
            });
            ObserverPeerSession observer = observerSessions.get(peer.id());
            if (observer != null && observer.isRunning()) {
                selectedObserverIds.add(peer.id());
            }
        }
        observerSessions.forEach((id, observer) -> {
            if (sameEndpoint(observer.endpoint(), active.endpoint()) || !selectedObserverIds.contains(id)) {
                observer.close();
                observerSessions.remove(id);
            }
        });
    }

    private void stopMultiPeerSupport() {
        observerSessions.values().forEach(ObserverPeerSession::close);
        observerSessions.clear();
        observerRetryAfterMillis.clear();
        activeUpstreamRetryAfterMillis.clear();
        activeUpstreamFailureCounts.clear();
        peerGovernor.flushPeerStore();
        peerDiscoveryService.close();
    }

    private void onCandidateHeader(CandidateHeader header) {
        if (header == null || !upstreamMode().multiPeer()) {
            return;
        }
        candidateHeaderStore.pruneBeforeSlot(Math.max(0L, header.slot() - selectionRollbackWindowSlots()));
        evaluateChainSelectionAsync();
    }

    private void evaluateChainSelectionAsync() {
        if (!chainSelectionEvaluationInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            peerRecoveryExecutor.execute(() -> {
                try {
                    evaluateChainSelection();
                } finally {
                    chainSelectionEvaluationInProgress.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            chainSelectionEvaluationInProgress.set(false);
            log.debug("Skipping chain-selection evaluation because peer recovery executor is closed");
        }
    }

    private void evaluateChainSelection() {
        if (!runtimeRunning.getAsBoolean() || !config.isEnableClient() || !upstreamMode().multiPeer()) {
            return;
        }
        ChainTip current = chainState.getHeaderTip();
        if (current == null) {
            current = chainState.getTip();
        }
        if (current == null) {
            return;
        }

        ChainSelectionDecision decision = chainSelectionStrategy.evaluate(new ChainSelectionContext(
                current.getBlockNumber(),
                current.getSlot(),
                selectionRollbackWindowSlots(),
                selectionQuorum(),
                selectionTrustPolicy(),
                headerFanIn.candidatesAfter(current.getBlockNumber())));
        if (decision.action() != ChainSelectionDecision.Action.ADOPT || decision.selected() == null) {
            if (decision.action() == ChainSelectionDecision.Action.OBSERVE && log.isDebugEnabled()) {
                log.debug("Observed upstream candidate without adoption: peer={}, block={}, reason={}",
                        decision.selected().peerId(), decision.selected().blockNumber(), decision.reason());
            }
            return;
        }

        CandidateHeader selected = decision.selected();
        ConfiguredUpstreamPeer active = activeUpstreamPeer();
        if (active.id().equals(selected.peerId())) {
            return;
        }
        if (!shouldPromoteCandidate(selected, current, active, decision.reason())) {
            return;
        }
        switchSelectedUpstream(selected.peerId(), decision.reason());
    }

    private boolean shouldPromoteCandidate(CandidateHeader selected,
                                           ChainTip current,
                                           ConfiguredUpstreamPeer active,
                                           String reason) {
        if (activeUpstreamHasFailure(active)) {
            return true;
        }

        long leadBlocks = selected.blockNumber() - current.getBlockNumber();
        if (leadBlocks < SELECTION_PROMOTION_MIN_LEAD_BLOCKS) {
            if (log.isDebugEnabled()) {
                log.debug("Keeping active upstream despite observed candidate lead: active={}, candidatePeer={}, "
                                + "leadBlocks={}, minLeadBlocks={}, reason={}",
                        active.endpoint().displayName(),
                        selected.peerId(),
                        leadBlocks,
                        SELECTION_PROMOTION_MIN_LEAD_BLOCKS,
                        reason);
            }
            return false;
        }

        long lastSwitch = lastSelectedUpstreamSwitchAtMillis;
        long now = System.currentTimeMillis();
        if (lastSwitch > 0 && now - lastSwitch < SELECTION_PROMOTION_MIN_INTERVAL_MILLIS) {
            if (log.isDebugEnabled()) {
                log.debug("Keeping active upstream because selected-peer switch cooldown is active: active={}, "
                                + "candidatePeer={}, leadBlocks={}, elapsedMillis={}, cooldownMillis={}, reason={}",
                        active.endpoint().displayName(),
                        selected.peerId(),
                        leadBlocks,
                        now - lastSwitch,
                        SELECTION_PROMOTION_MIN_INTERVAL_MILLIS,
                        reason);
            }
            return false;
        }

        return true;
    }

    private void switchSelectedUpstream(String peerId, String reason) {
        synchronized (peerSessionLock) {
            if (pendingSelectedUpstreamPeerId != null) {
                return;
            }
            int next = upstreamIndex(peerId);
            if (next < 0 || next == Math.floorMod(activeUpstreamIndex, upstreamPeers.size())) {
                return;
            }
            ConfiguredUpstreamPeer previous = activeUpstreamPeer();
            ConfiguredUpstreamPeer selected = upstreamPeers.get(next);
            pendingSelectedUpstreamPeerId = peerId;
            pendingSelectedUpstreamReason = reason;
            log.warn("Scheduling selected upstream switch by chain selection: from={}, to={}, reason={}",
                    previous.endpoint().displayName(), selected.endpoint().displayName(), reason);
        }
        scheduleImmediatePeerRecovery(PeerRecoveryReason.MANUAL);
    }

    private ConfiguredUpstreamPeer applyPendingSelectedUpstreamForRecovery(PeerRecoveryReason recoveryReason) {
        if (recoveryReason != PeerRecoveryReason.MANUAL) {
            return null;
        }
        synchronized (peerSessionLock) {
            String peerId = pendingSelectedUpstreamPeerId;
            if (peerId == null || peerId.isBlank()) {
                return null;
            }
            int next = upstreamIndex(peerId);
            if (next < 0) {
                log.warn("Discarding pending selected upstream switch because peer is no longer known: peerId={}",
                        peerId);
                pendingSelectedUpstreamPeerId = null;
                pendingSelectedUpstreamReason = null;
                return null;
            }
            int current = Math.floorMod(activeUpstreamIndex, upstreamPeers.size());
            if (next == current) {
                pendingSelectedUpstreamPeerId = null;
                pendingSelectedUpstreamReason = null;
                return upstreamPeers.get(next);
            }
            ConfiguredUpstreamPeer previous = activeUpstreamPeer();
            activeUpstreamIndex = next;
            ConfiguredUpstreamPeer selected = upstreamPeers.get(next);
            String reason = pendingSelectedUpstreamReason;
            pendingSelectedUpstreamPeerId = null;
            pendingSelectedUpstreamReason = null;
            lastSelectedUpstreamSwitchAtMillis = System.currentTimeMillis();
            log.warn("Switching selected upstream by chain selection: from={}, to={}, reason={}",
                    previous.endpoint().displayName(),
                    selected.endpoint().displayName(),
                    reason != null ? reason : "manual recovery");
            return selected;
        }
    }

    private void releaseObserverBeforeCanonicalSelection(ConfiguredUpstreamPeer selected) {
        if (selected == null) {
            return;
        }
        ObserverPeerSession observer = observerSessions.remove(selected.id());
        if (observer == null) {
            return;
        }
        observerRetryAfterMillis.remove(selected.id());
        try {
            observer.close();
            log.info("Released observer upstream peer {} at {} before canonical selection",
                    selected.id(), selected.endpoint().displayName());
        } catch (Exception e) {
            log.debug("Error releasing observer upstream peer {} before canonical selection",
                    selected.id(), e);
        }
    }

    private ConfiguredUpstreamPeer ensureKnownUpstreamPeer(PeerStoreEntry entry) {
        return ensureKnownUpstreamPeer(PeerDescriptor.fromStore(entry));
    }

    private ConfiguredUpstreamPeer ensureKnownUpstreamPeer(PeerDescriptor entry) {
        int existing = upstreamIndex(entry.id());
        if (existing < 0) {
            existing = upstreamIndex(entry.host(), entry.port());
        }
        if (existing >= 0) {
            return upstreamPeers.get(existing);
        }
        ConfiguredUpstreamPeer peer = new ConfiguredUpstreamPeer(
                entry.id(),
                new PeerEndpoint(entry.host(), entry.port(), protocolMagic),
                entry.trustable(),
                priorityForPeer(entry),
                entry.source().configValue());
        upstreamPeers.addIfAbsent(peer);
        return peer;
    }

    private Point observerStartPoint() {
        ChainTip headerTip = chainState.getHeaderTip();
        if (headerTip != null) {
            return new Point(headerTip.getSlot(), HexUtil.encodeHexString(headerTip.getBlockHash()));
        }
        ChainTip bodyTip = chainState.getTip();
        if (bodyTip != null) {
            return new Point(bodyTip.getSlot(), HexUtil.encodeHexString(bodyTip.getBlockHash()));
        }
        return "always".equals(normalizedFanInStart()) ? Point.ORIGIN : null;
    }

    private boolean shouldStartMultiPeerObservers() {
        String fanInStart = normalizedFanInStart();
        return "always".equals(fanInStart)
                || initialSyncComplete
                || syncPhase == SyncPhase.STEADY_STATE;
    }

    private int targetHotPeers() {
        if (upstreamConfig.getGovernor() == null) {
            return Math.min(2, Math.max(1, upstreamPeers.size()));
        }
        return Math.max(1, upstreamConfig.getGovernor().getTargetHot());
    }

    long selectionRollbackWindowSlots() {
        long configured = upstreamConfig.getSelection() != null
                ? upstreamConfig.getSelection().getRollbackWindowSlots()
                : 0L;
        if (configured > 0) {
            return configured;
        }
        long cached = derivedSelectionRollbackWindowSlots;
        if (cached > 0) {
            return cached;
        }
        long derived = deriveSelectionRollbackWindowSlots();
        derivedSelectionRollbackWindowSlots = derived;
        return derived;
    }

    private long deriveSelectionRollbackWindowSlots() {
        long securityParam = DEFAULT_SECURITY_PARAM;
        double activeSlotsCoeff = DEFAULT_ACTIVE_SLOTS_COEFF;

        try {
            EpochParamProvider provider = epochParamProviderSupplier.get();
            if (provider != null) {
                securityParam = provider.getSecurityParam();
                activeSlotsCoeff = provider.getActiveSlotsCoeff();
            }
        } catch (Exception e) {
            log.warn("Failed to derive upstream selection rollback window from genesis; using fallback {} slots: {}",
                    DEFAULT_SELECTION_ROLLBACK_WINDOW_SLOTS, PeerFailureMessage.summarize(e));
            return DEFAULT_SELECTION_ROLLBACK_WINDOW_SLOTS;
        }

        if (securityParam <= 0) {
            securityParam = DEFAULT_SECURITY_PARAM;
        }
        if (!Double.isFinite(activeSlotsCoeff) || activeSlotsCoeff <= 0) {
            activeSlotsCoeff = DEFAULT_ACTIVE_SLOTS_COEFF;
        }

        double slots = Math.ceil((double) securityParam / activeSlotsCoeff);
        long rollbackWindowSlots = Double.isFinite(slots) && slots < Long.MAX_VALUE
                ? Math.max(1L, (long) slots)
                : DEFAULT_SELECTION_ROLLBACK_WINDOW_SLOTS;
        log.info("Derived upstream selection rollback window from genesis: securityParam={}, "
                        + "activeSlotsCoeff={}, rollbackWindowSlots={}",
                securityParam, activeSlotsCoeff, rollbackWindowSlots);
        return rollbackWindowSlots;
    }

    private int selectionQuorum() {
        return upstreamConfig.getSelection() != null
                ? upstreamConfig.getSelection().getQuorum()
                : 2;
    }

    private String selectionTrustPolicy() {
        return upstreamConfig.getSelection() != null
                ? upstreamConfig.getSelection().getTrustPolicy()
                : "trusted-only";
    }

    private int runningObserverCount() {
        return (int) observerSessions.values().stream()
                .filter(ObserverPeerSession::isRunning)
                .count();
    }

    private boolean observerRetryAllowed(String peerId) {
        Long retryAfter = observerRetryAfterMillis.get(peerId);
        if (retryAfter == null) {
            return true;
        }
        if (System.currentTimeMillis() >= retryAfter) {
            observerRetryAfterMillis.remove(peerId);
            return true;
        }
        return false;
    }

    private int upstreamIndex(String peerId) {
        if (peerId == null || upstreamPeers.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < upstreamPeers.size(); i++) {
            if (peerId.equals(upstreamPeers.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private int upstreamIndex(String host, int port) {
        if (host == null || upstreamPeers.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < upstreamPeers.size(); i++) {
            if (sameEndpoint(host, port, upstreamPeers.get(i).endpoint())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameEndpoint(PeerEndpoint left, PeerEndpoint right) {
        return left != null && right != null
                && sameEndpoint(left.host(), left.port(), right);
    }

    private static boolean sameEndpoint(String host, int port, PeerEndpoint endpoint) {
        if (host == null || endpoint == null || port != endpoint.port()) {
            return false;
        }
        try {
            return PeerDescriptor.endpointId(host, port)
                    .equals(PeerDescriptor.endpointId(endpoint.host(), endpoint.port()));
        } catch (RuntimeException e) {
            return host.trim().equalsIgnoreCase(endpoint.host()) && port == endpoint.port();
        }
    }

    private String normalizedFanInStart() {
        if (upstreamConfig.getSync() == null || upstreamConfig.getSync().getFanInStart() == null) {
            return "near-tip";
        }
        return upstreamConfig.getSync().getFanInStart().trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedTxForwarding() {
        if (upstreamConfig.getTx() == null || upstreamConfig.getTx().getForwarding() == null) {
            return "active-selected";
        }
        return upstreamConfig.getTx().getForwarding().trim().toLowerCase(Locale.ROOT);
    }

    private static int scoreForConfiguredPeer(ConfiguredUpstreamPeer peer) {
        int priorityScore = Math.max(0, 10_000 - peer.priority());
        return peer.trusted() ? priorityScore + 10_000 : priorityScore;
    }

    private static int priorityForPeer(PeerDescriptor peer) {
        int score = Math.max(0, peer.score());
        if (score > 0) {
            return Math.max(0, 1_000_000 - score);
        }
        return switch (peer.source()) {
            case STATIC_UPSTREAM, LOCAL_ROOT -> 0;
            case BOOTSTRAP, PUBLIC_ROOT -> 5_000;
            case LEDGER -> 10_000;
            case GOSSIP -> 20_000;
            case INBOUND -> 100_000;
        };
    }

    private UpstreamPreset upstreamMode() {
        return upstreamConfig != null && upstreamConfig.getMode() != null
                ? upstreamConfig.getMode()
                : UpstreamPreset.TRUSTED_SINGLE;
    }

    private ConfiguredUpstreamPeer activeUpstreamPeer() {
        if (upstreamPeers.isEmpty()) {
            return new ConfiguredUpstreamPeer(
                    "remote",
                    new PeerEndpoint(remoteCardanoHost, remoteCardanoPort, protocolMagic),
                    true,
                    0,
                    "legacy-remote");
        }
        int index = Math.floorMod(activeUpstreamIndex, upstreamPeers.size());
        return upstreamPeers.get(index);
    }

    private boolean advanceActiveUpstreamAfterFailure(PeerRecoveryReason reason, Exception cause) {
        if (upstreamMode() == UpstreamPreset.TRUSTED_SINGLE) {
            return false;
        }
        if (upstreamPeers.isEmpty()) {
            return false;
        }
        int previous = Math.floorMod(activeUpstreamIndex, upstreamPeers.size());
        ConfiguredUpstreamPeer previousPeer = upstreamPeers.get(previous);
        markActiveUpstreamFailure(previousPeer);
        ensureDiscoveryFailureFallbackPeers();
        if (upstreamPeers.size() <= 1) {
            return false;
        }

        int next = nextPreferredUpstreamIndex(previous);
        if (next == previous) {
            return false;
        }

        ConfiguredUpstreamPeer nextPeer = upstreamPeers.get(next);
        releaseObserverBeforeCanonicalSelection(nextPeer);
        activeUpstreamIndex = next;
        log.warn("Switching active upstream after failure: reason={}, from={}, to={}, error={}",
                reason,
                previousPeer.endpoint().displayName(),
                nextPeer.endpoint().displayName(),
                cause != null ? cause.getMessage() : "none");
        return true;
    }

    private void ensureDiscoveryFailureFallbackPeers() {
        if (!discoveryBootstrapEnabled()) {
            return;
        }
        int before = upstreamPeers.size();
        peerDiscoveryService.start();
        peerGovernor.hotPeers(PeerUse.CHAIN_SYNC, Math.max(1, peerGovernor.snapshot().knownPeerCount())).stream()
                .filter(peer -> peerAddressPolicy.allows(peer.source(), peer.host(), peer.port()))
                .filter(peer -> upstreamIndex(peer.id()) < 0)
                .filter(peer -> upstreamIndex(peer.host(), peer.port()) < 0)
                .findFirst()
                .ifPresent(this::ensureKnownUpstreamPeer);
        if (upstreamPeers.size() > before) {
            log.info("Added {} discovered upstream peer(s) for active failover",
                    upstreamPeers.size() - before);
        }
    }

    private int nextPreferredUpstreamIndex(int previous) {
        int trustedUnfailed = nextPreferredUpstreamIndex(previous, true, false);
        if (trustedUnfailed >= 0) {
            return trustedUnfailed;
        }
        int unfailed = nextPreferredUpstreamIndex(previous, false, false);
        if (unfailed >= 0) {
            return unfailed;
        }
        int trustedRetry = nextPreferredUpstreamIndex(previous, true, true);
        if (trustedRetry >= 0) {
            return trustedRetry;
        }
        int retry = nextPreferredUpstreamIndex(previous, false, true);
        if (retry >= 0) {
            return retry;
        }
        return (previous + 1) % upstreamPeers.size();
    }

    private int nextPreferredUpstreamIndex(int previous, boolean trustedOnly, boolean includePreviouslyFailed) {
        for (int offset = 1; offset < upstreamPeers.size(); offset++) {
            int candidate = (previous + offset) % upstreamPeers.size();
            ConfiguredUpstreamPeer peer = upstreamPeers.get(candidate);
            if (trustedOnly && !peer.trusted()) {
                continue;
            }
            if (!includePreviouslyFailed && activeUpstreamHasFailure(peer)) {
                continue;
            }
            if (activeUpstreamRetryAllowed(peer)) {
                return candidate;
            }
        }
        return -1;
    }

    private void markActiveUpstreamFailure(ConfiguredUpstreamPeer peer) {
        if (peer == null || peer.id() == null) {
            return;
        }
        activeUpstreamFailureCounts.merge(peer.id(), 1, Integer::sum);
        long cooldownMillis = activeUpstreamFailoverCooldownMillis();
        if (cooldownMillis <= 0) {
            return;
        }
        activeUpstreamRetryAfterMillis.put(peer.id(), System.currentTimeMillis() + cooldownMillis);
    }

    private void clearActiveUpstreamFailure(String peerId) {
        if (peerId != null) {
            activeUpstreamRetryAfterMillis.remove(peerId);
            activeUpstreamFailureCounts.remove(peerId);
        }
    }

    private boolean activeUpstreamHasFailure(ConfiguredUpstreamPeer peer) {
        return peer != null
                && peer.id() != null
                && activeUpstreamFailureCounts.getOrDefault(peer.id(), 0) > 0;
    }

    private boolean activeUpstreamRetryAllowed(ConfiguredUpstreamPeer peer) {
        if (peer == null || peer.id() == null) {
            return true;
        }
        Long retryAfter = activeUpstreamRetryAfterMillis.get(peer.id());
        if (retryAfter == null) {
            return true;
        }
        if (System.currentTimeMillis() >= retryAfter) {
            activeUpstreamRetryAfterMillis.remove(peer.id());
            return true;
        }
        return false;
    }

    private long activeUpstreamFailoverCooldownMillis() {
        if (upstreamConfig.getFailover() == null) {
            return 30_000L;
        }
        return Math.max(0L, upstreamConfig.getFailover().getCooldownMs());
    }

    private static List<ConfiguredUpstreamPeer> buildUpstreamPeers(UpstreamConfig upstreamConfig,
                                                                   String remoteHost,
                                                                   int remotePort,
                                                                   long protocolMagic) {
        UpstreamConfig effective = upstreamConfig;
        if ((effective == null || effective.getPeers() == null || effective.getPeers().isEmpty())
                && remoteHost != null && !remoteHost.isBlank() && remotePort > 0
                && (effective == null || !effective.discoveryBootstrapEnabled())) {
            effective = UpstreamConfig.trustedSingleFromRemote(remoteHost, remotePort);
        }
        if (effective == null || effective.getPeers() == null || effective.getPeers().isEmpty()) {
            return List.of();
        }

        List<ConfiguredUpstreamPeer> peers = new ArrayList<>();
        for (UpstreamPeerConfig peer : effective.orderedPeers()) {
            if (peer == null) {
                continue;
            }
            peers.add(new ConfiguredUpstreamPeer(
                    peer.effectiveId(),
                    new PeerEndpoint(peer.getHost(), peer.getPort(), protocolMagic),
                    peer.trusted(),
                    peer.getPriority(),
                    peer.getSource() != null ? peer.getSource() : "local-root"));
        }
        return List.copyOf(peers);
    }

    private PeerSession createPeerSession() {
        ConfiguredUpstreamPeer activeUpstream = activeUpstreamPeer();
        var session = new PeerSession(
                activeUpstream.endpoint(),
                chainState,
                eventBus,
                this,
                epochParamProviderSupplier.get(),
                peerClientFactory,
                headerValidator,
                bodyValidator);
        session.setGenesisBootstrapDataSupplier(genesisBootstrapDataSupplier);
        return session;
    }

    private record ConfiguredUpstreamPeer(String id,
                                          PeerEndpoint endpoint,
                                          boolean trusted,
                                          int priority,
                                          String source) {
    }

    private void startPeerSessionSupervisor() {
        if (supervisorHolder == null) {
            supervisorHolder = new PeerSessionSupervisorHolder(
                    scheduler,
                    () -> peerSession,
                    this::recoverPeerSession,
                    this::isPeerRecoveryDeferred,
                    peerRecoveryExecutor);
        }
        supervisorHolder.start();
    }

    private boolean isPeerRecoveryDeferred() {
        return rollbackInProgress.get() || peerRecoveryFailureTracker.isTerminal();
    }

    private void recoverPeerSession(PeerRecoveryReason reason) {
        PeerSession oldSession;
        synchronized (peerSessionLock) {
            if (!runtimeRunning.getAsBoolean() || !config.isEnableClient()) {
                return;
            }
            if (peerRecoveryFailureTracker.isTerminal()) {
                log.warn("Skipping upstream peer session recovery because recovery is terminal: {}",
                        peerRecoveryFailureTracker.snapshot().message());
                return;
            }
            if (rollbackInProgress.get()) {
                log.info("Deferring upstream peer session recovery while rollback is in progress: reason={}", reason);
                return;
            }
            oldSession = peerSession;
        }

        LedgerApplyProcessor.RecoveryPoint recoveryPoint = null;
        if (oldSession != null) {
            try {
                log.warn("Recovering upstream peer session: reason={}", reason);
                if (!oldSession.quiesceNetworkForRecovery(Duration.ofSeconds(10))) {
                    throw new IllegalStateException("Timed out waiting for old-session rollback callbacks to drain");
                }
                recoveryPoint = oldSession.closeGenerationAndReadRecoveryPoint(Duration.ofMinutes(5));
            } catch (Exception e) {
                log.warn("Could not read peer recovery point through ledger apply barrier; "
                        + "delaying peer replacement until a safe point is available", e);
                recordPeerRecoveryFailure(reason, e);
                return;
            }
            try {
                while (!oldSession.stop(Duration.ofMinutes(1))) {
                    log.error("Stale peer session did not stop after ledger apply safe point; "
                            + "continuing to wait before starting replacement");
                }
            } catch (Exception e) {
                log.warn("Error stopping stale peer session during recovery", e);
                recordPeerRecoveryFailure(reason, e);
                return;
            }
        }

        ConfiguredUpstreamPeer pendingSelected = applyPendingSelectedUpstreamForRecovery(reason);

        // Observer maintenance can run while a chain-selection promotion is quiescing the old
        // canonical session. Purge again after the active index is final so the replacement
        // canonical dial is never racing an observer connection to the same endpoint.
        releaseObserverBeforeCanonicalSelection(pendingSelected != null ? pendingSelected : activeUpstreamPeer());

        synchronized (peerSessionLock) {
            if (!runtimeRunning.getAsBoolean() || !config.isEnableClient()) {
                return;
            }
            if (peerRecoveryFailureTracker.isTerminal()) {
                log.warn("Skipping upstream peer session restart because recovery is terminal: {}",
                        peerRecoveryFailureTracker.snapshot().message());
                return;
            }
            try {
                if (oldSession != null && peerSession != oldSession) {
                    log.info("Skipping peer session recovery start because the active session changed");
                    return;
                }
                peerSession = null;
                boolean usePipeline = config.isEnablePipelinedSync();
                syncGeneration.incrementAndGet();
                cancelIntersectionTransition();
                isSyncing.set(true);
                pipelinedMode = usePipeline;

                ClientSyncTips syncTips = prepareClientSyncTips(
                        usePipeline,
                        recoveryPoint != null ? recoveryPoint.headerTip() : chainState.getHeaderTip(),
                        recoveryPoint != null ? recoveryPoint.bodyTip() : chainState.getTip(),
                        "peer recovery");
                ChainTip localTip = selectClientSyncStartTip(usePipeline, syncTips.headerTip(), syncTips.bodyTip());
                Point startPoint = determineStartPoint(localTip);
                log.info("Restarting upstream sync from point: {}", startPoint);
                remoteTip = usableRemoteTip(remoteTip, startPoint, localTip, "peer recovery");

                if (!runtimeRunning.getAsBoolean() || !config.isEnableClient()) {
                    log.info("Skipping peer session recovery start because Yano is stopping");
                    return;
                }

                peerSession = createPeerSession();
                if (pipelinedMode) {
                    startPipelinedClientSync(localTip, remoteTip, startPoint);
                } else {
                    startSequentialClientSync(startPoint);
                }
                peerRecoveryFailureTracker.recordSuccess();
                clearActiveUpstreamFailure(activeUpstreamPeer().id());
                startMultiPeerSupport(startPoint);
                log.info("Upstream peer session recovered successfully: reason={}", reason);
            } catch (Exception e) {
                PeerRecoveryFailureTracker.Snapshot failure = peerRecoveryFailureTracker.recordFailure(reason, e);
                boolean switched = false;
                if (failure.terminal()) {
                    log.error("Upstream peer session recovery reached terminal failure; automatic retries paused: {}",
                            failureMessage(failure, e));
                } else {
                    log.warn("Upstream peer session recovery failed; supervisor will retry after cooldown: {}",
                            failureMessage(failure, e));
                    switched = advanceActiveUpstreamAfterFailure(reason, e);
                }
                markPeerRecoveryFailure(reason, e, failure);
                isSyncing.set(!failure.terminal());
                if (switched && shouldFastRetryAfterActiveUpstreamSwitch(reason)) {
                    scheduleImmediatePeerRecovery(reason);
                }
            }
        }
    }

    private void recordPeerRecoveryFailure(PeerRecoveryReason reason, Exception e) {
        synchronized (peerSessionLock) {
            if (!runtimeRunning.getAsBoolean() || !config.isEnableClient()) {
                return;
            }
            PeerRecoveryFailureTracker.Snapshot failure = peerRecoveryFailureTracker.recordFailure(reason, e);
            boolean switched = false;
            if (failure.terminal()) {
                log.error("Upstream peer session recovery reached terminal failure; automatic retries paused: {}",
                        failureMessage(failure, e));
            } else {
                log.warn("Upstream peer session recovery failed; supervisor will retry after cooldown: {}",
                        failureMessage(failure, e));
                switched = advanceActiveUpstreamAfterFailure(reason, e);
            }
            markPeerRecoveryFailure(reason, e, failure);
            isSyncing.set(!failure.terminal());
            if (switched && shouldFastRetryAfterActiveUpstreamSwitch(reason)) {
                scheduleImmediatePeerRecovery(reason);
            }
        }
    }

    private boolean shouldFastRetryAfterActiveUpstreamSwitch(PeerRecoveryReason reason) {
        return reason == PeerRecoveryReason.STARTUP_FAILED
                && !peerRecoveryFailureTracker.isTerminal()
                && runtimeRunning.getAsBoolean()
                && config.isEnableClient();
    }

    private void scheduleImmediatePeerRecovery(PeerRecoveryReason reason) {
        try {
            log.info("Retrying active upstream immediately after failover switch: reason={}", reason);
            peerRecoveryExecutor.execute(() -> recoverPeerSession(reason));
        } catch (RejectedExecutionException e) {
            log.warn("Immediate upstream peer recovery submission rejected: reason={}", reason, e);
        }
    }

    private void cancelIntersectionTransition() {
        ScheduledFuture<?> future = intersectionTransitionFuture;
        if (future != null) {
            future.cancel(false);
            intersectionTransitionFuture = null;
        }
    }

    void setRollbackClassificationTimeoutMillis(long timeoutMillis) {
        rollbackClassificationTimeout = timeoutMillis;
    }

    boolean hasPendingIntersectionTransition() {
        ScheduledFuture<?> future = intersectionTransitionFuture;
        return future != null && !future.isCancelled() && !future.isDone();
    }

    private void markPeerRecoveryFailure(PeerRecoveryReason reason,
                                         Exception e,
                                         PeerRecoveryFailureTracker.Snapshot failure) {
        PeerSession failedSession = peerSession;
        if (!failure.terminal()) {
            if (failedSession != null) {
                failedSession.getPeerHealth().recordRecoveryAttempt(reason);
            }
            return;
        }
        if (failedSession == null) {
            failedSession = createPeerSession();
            peerSession = failedSession;
        }

        failedSession.getPeerHealth().recordRecoveryAttempt(reason);
        String message = failure.message() != null
                ? failure.message()
                : "Peer session recovery failed: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        failedSession.getPeerHealth().markTerminalFailure(
                reason != null ? reason : PeerRecoveryReason.UNKNOWN,
                message);
    }

    private static String failureMessage(PeerRecoveryFailureTracker.Snapshot failure, Exception e) {
        String message = failure != null ? failure.message() : null;
        String detail = PeerFailureMessage.summarize(e);
        if (message == null || message.isBlank()) {
            return detail;
        }
        return message + "; " + detail;
    }

    private void startPipelinedClientSync(ChainTip localTip, Tip remoteTip, Point startPoint) {
        syncPhase = SyncPhase.INITIAL_SYNC;
        log.info("ChainSync agent started - reset to INITIAL_SYNC phase");
        boolean remoteTipAvailable = remoteTip != null && remoteTip.getPoint() != null;
        Point remotePoint = remoteTipAvailable ? remoteTip.getPoint() : startPoint;
        boolean shouldUseBulkSync = !remoteTipAvailable || shouldUseBulkSync(localTip, remotePoint);

        if (shouldUseBulkSync) {
            if (remoteTipAvailable) {
                log.info("BULK PIPELINED SYNC: {} slots behind, using high-performance pipeline",
                        remotePoint.getSlot() - (localTip != null ? localTip.getSlot() : 0));
            } else {
                log.info("BULK PIPELINED SYNC: remote tip unavailable until ChainSync intersection");
            }
            initialSyncComplete = false;
            pipelineConfig = PipelineConfig.builder()
                    .headerPipelineDepth(300)
                    .bodyBatchSize(100)
                    .maxParallelBodies(15)
                    .batchTimeout(Duration.ofSeconds(1))
                    .enableParallelProcessing(true)
                    .processingThreads(6)
                    .headerBufferSize(1500)
                    .build();
        } else {
            log.info("REAL-TIME PIPELINED SYNC: Near tip, using optimized real-time pipeline");
            initialSyncComplete = true;
            pipelineConfig = createPipelineConfig();
        }

        peerSession.startPipelined(startPoint, pipelineConfig);
    }

    private void startSequentialClientSync(Point startPoint) {
        log.info("SEQUENTIAL SYNC: Using traditional header+body sync");
        initialSyncComplete = false;
        peerSession.startSequential(startPoint, pipelineConfig);
    }

    private Point determineStartPoint(ChainTip localTip) {
        if (localTip == null) {
            log.info("No local tip found, starting from genesis");
            if (config.getSyncStartSlot() > 0) {
                log.info("Using configured sync start slot: {}", config.getSyncStartSlot());
                return new Point(config.getSyncStartSlot(), config.getSyncStartBlockHash());
            }
            return Point.ORIGIN;
        }

        log.info("Local tip found at slot {}, starting sync from there", localTip.getSlot());
        return new Point(localTip.getSlot(), HexUtil.encodeHexString(localTip.getBlockHash()));
    }

    private ClientSyncTips prepareClientSyncTips(boolean usePipeline,
                                                 ChainTip headerTip,
                                                 ChainTip bodyTip,
                                                 String context) {
        if (!usePipeline || headerTip == null) {
            return new ClientSyncTips(headerTip, bodyTip);
        }

        if (isHeaderChainUsableForPipelinedSync(headerTip, bodyTip, context)) {
            return new ClientSyncTips(headerTip, bodyTip);
        }

        log.warn("Header cursor is not safe for pipelined sync during {}; "
                        + "discarding header-only cache back to durable body tip. header_tip={}, body_tip={}",
                context, describeTip(headerTip), describeTip(bodyTip));
        discardHeaderOnlyCacheToBodyTip(bodyTip, context);
        ClientSyncTips repaired = new ClientSyncTips(chainState.getHeaderTip(), chainState.getTip());

        if (repaired.headerTip() != null
                && !isHeaderChainUsableForPipelinedSync(repaired.headerTip(), repaired.bodyTip(), context + " repair")) {
            throw new IllegalStateException("Unable to repair unsafe header cursor for pipelined sync during " + context);
        }

        log.info("Header cursor after repair during {}: header_tip={}, body_tip={}",
                context, describeTip(repaired.headerTip()), describeTip(repaired.bodyTip()));
        return repaired;
    }

    private boolean isHeaderChainUsableForPipelinedSync(ChainTip headerTip, ChainTip bodyTip, String context) {
        if (headerTip == null) {
            return true;
        }
        if (chainState.getBlockHeader(headerTip.getBlockHash()) == null) {
            log.warn("Header tip points to missing header during {}: {}", context, describeTip(headerTip));
            return false;
        }

        long headerBlock = headerTip.getBlockNumber();
        if (headerBlock <= 0) {
            return true;
        }

        if (bodyTip != null && chainState.getBlockHeader(bodyTip.getBlockHash()) == null) {
            log.warn("Body tip has no matching header during {}: {}", context, describeTip(bodyTip));
            return false;
        }

        long lowerExclusiveBlock;
        if (bodyTip != null) {
            lowerExclusiveBlock = Math.max(0L, bodyTip.getBlockNumber());
            long headerOnlyBlocks = headerBlock - lowerExclusiveBlock;
            if (headerOnlyBlocks > HEADER_CONTINUITY_VALIDATION_LIMIT) {
                log.warn("Header cursor is {} blocks ahead of body tip during {}, exceeding validation limit {}; "
                                + "discarding header-only cache for correctness",
                        headerOnlyBlocks, context, HEADER_CONTINUITY_VALIDATION_LIMIT);
                return false;
            }
        } else {
            lowerExclusiveBlock = Math.max(0L, headerBlock - HEADER_CONTINUITY_VALIDATION_LIMIT);
            if (lowerExclusiveBlock > 0) {
                log.info("Validating the last {} header-only blocks during {} because no durable body tip exists",
                        HEADER_CONTINUITY_VALIDATION_LIMIT, context);
            }
        }

        for (long blockNumber = headerBlock; blockNumber > lowerExclusiveBlock; blockNumber--) {
            if (chainState.getBlockHeaderByNumber(blockNumber) == null) {
                log.warn("Header continuity gap detected during {}: missing header #{} while validating {} down to {}",
                        context, blockNumber, describeTip(headerTip), lowerExclusiveBlock);
                return false;
            }
        }
        return true;
    }

    private void discardHeaderOnlyCacheToBodyTip(ChainTip bodyTip, String context) {
        if (bodyTip != null) {
            chainState.rollbackTo(bodyTip.getSlot());
            return;
        }
        if (chainState instanceof OriginRollbackCapable originRollback) {
            originRollback.rollbackToOrigin();
            return;
        }
        throw new IllegalStateException("Cannot discard unsafe header-only cursor without a durable body tip during "
                + context + " for chain state " + chainState.getClass().getName());
    }

    private String describeTip(ChainTip tip) {
        if (tip == null) {
            return "null";
        }
        return "block #" + tip.getBlockNumber()
                + " slot " + tip.getSlot()
                + " hash " + HexUtil.encodeHexString(tip.getBlockHash());
    }

    private ChainTip selectClientSyncStartTip(boolean usePipeline, ChainTip headerTip, ChainTip bodyTip) {
        if (usePipeline) {
            return headerTip != null ? headerTip : bodyTip;
        }
        if (headerTip != null && bodyTip != null && headerTip.getSlot() > bodyTip.getSlot()) {
            log.info("Sequential sync ignoring header_tip slot {} ahead of body_tip slot {}; "
                            + "body cursor is the only durable body-apply restart point",
                    headerTip.getSlot(), bodyTip.getSlot());
        } else if (headerTip != null && bodyTip == null) {
            log.info("Sequential sync ignoring header_tip slot {} because no durable body tip exists", headerTip.getSlot());
        }
        return bodyTip;
    }

    private Tip usableRemoteTip(Tip candidate, Point startPoint, ChainTip localTip, String context) {
        if (candidate != null && candidate.getPoint() != null) {
            return candidate;
        }
        log.warn("Remote tip unavailable during {}; ChainSync intersection will refresh it", context);
        return null;
    }

    private boolean shouldUseBulkSync(ChainTip localTip, Point chainTip) {
        if (localTip == null) {
            log.info("No local tip, using bulk sync to get initial blockchain data");
            return true;
        }
        long slotDifference = chainTip.getSlot() - localTip.getSlot();
        if (slotDifference > config.getFullSyncThreshold()) {
            log.info("Local tip is {} slots behind (> {} threshold), using bulk sync",
                    slotDifference, config.getFullSyncThreshold());
            return true;
        }
        log.info("Local tip is {} slots behind (<= {} threshold), using real-time sync",
                slotDifference, config.getFullSyncThreshold());
        return false;
    }

    private long observedRemoteTipSlot() {
        long observedSlot = -1L;
        Tip tip = remoteTip;
        if (tip != null && tip.getPoint() != null) {
            observedSlot = tip.getPoint().getSlot();
        }
        BodyFetchManager bodyFetchManager = currentBodyFetchManager();
        if (bodyFetchManager != null) {
            observedSlot = Math.max(observedSlot, bodyFetchManager.getObservedNetworkTipSlot());
        }
        return observedSlot;
    }

    private boolean isRealRollback(Point rollbackPoint) {
        if (syncPhase == SyncPhase.INTERSECT_PHASE || syncPhase == SyncPhase.INITIAL_SYNC) {
            log.info("Rollback during {} phase - skipping server notification", syncPhase);
            return false;
        }
        ChainTip bodyTipBeforeRollback = chainState.getTip();
        if (bodyTipBeforeRollback != null && rollbackPoint.getSlot() < bodyTipBeforeRollback.getSlot()) {
            log.info("Real chain reorganization detected - rollback from slot {} to slot {}",
                    bodyTipBeforeRollback.getSlot(), rollbackPoint.getSlot());
            return true;
        }
        return false;
    }

    private void reconcileSyncPhaseAfterRollback(BodyFetchManager bodyFetchManager) {
        if (!pipelinedMode || bodyFetchManager == null || remoteTip == null || remoteTip.getPoint() == null) {
            return;
        }
        ChainTip bodyTip = chainState.getTip();
        if (bodyTip == null) {
            return;
        }

        long distance = Math.max(0, remoteTip.getPoint().getSlot() - bodyTip.getSlot());
        long nearTipThreshold = 1000;
        if (syncPhase == SyncPhase.STEADY_STATE && distance > nearTipThreshold) {
            SyncPhase prev = syncPhase;
            syncPhase = SyncPhase.INITIAL_SYNC;
            bodyFetchManager.setSyncPhase(SyncPhase.INITIAL_SYNC);
            log.info("Rollback moved local tip {} slots behind remote tip; transitioned to INITIAL_SYNC", distance);
            eventBus.publish(new SyncStatusChangedEvent(prev, syncPhase),
                    EventMetadata.builder().origin("runtime").build(),
                    PublishOptions.builder().build());
        }
    }

    private void attemptCorruptionRecovery(String context) {
        try {
            ChainStateRecovery recovery = chainStorage.recoveryOrNull();
            if (recovery == null) {
                return;
            }

            if (recovery.detectCorruption()) {
                log.warn("Corruption detected during {} - attempting recovery", context);
                recovery.recoverFromCorruption();
                log.info("Recovery completed during {} - continuing sync", context);
            } else {
                log.debug("No corruption detected during {} check", context);
            }
        } catch (Exception e) {
            log.warn("Recovery attempt during {} failed: {}", context, e.toString());
        }
    }

    private boolean stopPeerSessionForShutdown(PeerSession session, String label) {
        if (session.stop(Duration.ofMinutes(10))) {
            return true;
        }
        log.error("Yano stop could not stop the {} peer session at a ledger apply safe point after 10 minutes; "
                + "forcing ledger apply shutdown", label);
        if (session.forceStop(Duration.ofSeconds(30))) {
            log.warn("Forced {} peer session shutdown completed after interrupting ledger apply worker", label);
            return true;
        }
        log.error("Forced {} peer session shutdown failed; ledger apply worker may still be running", label);
        return false;
    }

    private PipelineConfig createPipelineConfig() {
        return PipelineConfig.builder()
                .headerPipelineDepth(config.getHeaderPipelineDepth())
                .bodyBatchSize(config.getBodyBatchSize())
                .maxParallelBodies(config.getMaxParallelBodies())
                .batchTimeout(Duration.ofSeconds(2))
                .enableParallelProcessing(true)
                .processingThreads(4)
                .headerBufferSize(config.getHeaderPipelineDepth() * 5)
                .build();
    }

    private static ExecutorService defaultPeerRecoveryExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "YanoPeerRecovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Pair of header and body tips used to report client sync progress.
     */
    private record ClientSyncTips(ChainTip headerTip, ChainTip bodyTip) {
    }

    /**
     * Owns the peer session supervisor so subsystem shutdown has one close path.
     */
    private static final class PeerSessionSupervisorHolder implements AutoCloseable {
        private final com.bloxbean.cardano.yano.runtime.peer.PeerSessionSupervisor supervisor;

        private PeerSessionSupervisorHolder(ScheduledExecutorService scheduler,
                                            Supplier<PeerSession> sessionSupplier,
                                            java.util.function.Consumer<PeerRecoveryReason> recoveryHandler,
                                            java.util.function.BooleanSupplier recoveryDeferredSupplier,
                                            ExecutorService recoveryExecutor) {
            this.supervisor = new com.bloxbean.cardano.yano.runtime.peer.PeerSessionSupervisor(
                    scheduler,
                    sessionSupplier,
                    recoveryHandler,
                    com.bloxbean.cardano.yano.runtime.peer.PeerSessionSupervisor.Policy.defaults(),
                    recoveryDeferredSupplier,
                    recoveryExecutor);
        }

        private void start() {
            supervisor.start();
        }

        private void notifyDisconnect() {
            supervisor.notifyDisconnect();
        }

        private void requestRecovery(PeerRecoveryReason reason) {
            supervisor.requestRecovery(reason);
        }

        @Override
        public void close() {
            supervisor.close();
        }
    }
}
