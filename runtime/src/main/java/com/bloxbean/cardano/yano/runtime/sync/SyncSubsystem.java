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
import com.bloxbean.cardano.yano.runtime.peer.DefaultPeerClientFactory;
import com.bloxbean.cardano.yano.runtime.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.runtime.peer.PeerEndpoint;
import com.bloxbean.cardano.yano.runtime.peer.PeerRecoveryFailureTracker;
import com.bloxbean.cardano.yano.runtime.peer.PeerRecoveryReason;
import com.bloxbean.cardano.yano.runtime.peer.PeerSession;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionCallbacks;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionStatus;
import com.bloxbean.cardano.yano.runtime.server.ServeSubsystem;
import com.bloxbean.cardano.yano.runtime.storage.ChainStorageSubsystem;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final Logger log;
    private final PeerClientFactory peerClientFactory;
    private final ExecutorService peerRecoveryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "YanoPeerRecovery");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean rollbackInProgress = new AtomicBoolean(false);
    private final AtomicLong syncGeneration = new AtomicLong();
    private final Object peerSessionLock = new Object();
    private final PeerRecoveryFailureTracker peerRecoveryFailureTracker =
            new PeerRecoveryFailureTracker(MAX_PEER_RECOVERY_FAILURES);

    private volatile PeerSession peerSession;
    private volatile PeerSessionSupervisorHolder supervisorHolder;
    private volatile boolean initialSyncComplete;
    private volatile boolean pipelinedMode;
    private volatile Tip remoteTip;
    private volatile SyncPhase syncPhase = SyncPhase.INITIAL_SYNC;
    private volatile boolean closed;
    private volatile ScheduledFuture<?> intersectionTransitionFuture;
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
                remoteCardanoHost, remoteCardanoPort, protocolMagic, log, DefaultPeerClientFactory.supervised());
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
        if (config.isEnableClient()) {
            this.remoteCardanoHost = Objects.requireNonNull(remoteCardanoHost, "remoteCardanoHost");
        } else {
            this.remoteCardanoHost = remoteCardanoHost != null ? remoteCardanoHost : "localhost";
        }
        this.remoteCardanoPort = remoteCardanoPort;
        this.protocolMagic = protocolMagic;
        this.log = Objects.requireNonNull(log, "log");
        this.peerClientFactory = Objects.requireNonNull(peerClientFactory, "peerClientFactory");
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
            log.info("Starting {} client sync with {}:{}...",
                    usePipeline ? "pipelined" : "sequential", remoteCardanoHost, remoteCardanoPort);
            syncGeneration.incrementAndGet();
            isSyncing.set(true);
            pipelinedMode = usePipeline;

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
                } catch (Exception e) {
                    PeerRecoveryFailureTracker.Snapshot failure =
                            peerRecoveryFailureTracker.recordFailure(PeerRecoveryReason.STARTUP_FAILED, e);
                    log.warn("Initial upstream peer session start failed; supervisor will retry: {}",
                            failure.message(), e);
                    markPeerRecoveryFailure(PeerRecoveryReason.STARTUP_FAILED, e, failure);
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
        PeerSession activeSession = peerSession;
        if (activeSession != null && activeSession.isRunning()) {
            try {
                activeSession.submitTxBytes(txHash, txCbor, txBodyType);
                log.debug("Transaction {} forwarded to upstream node", txHash);
            } catch (Exception e) {
                log.warn("Failed to forward transaction {} to upstream node: {}", txHash, e.getMessage());
            }
        }
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
                syncPhase = SyncPhase.STEADY_STATE;
                BodyFetchManager bodyFetchManager = currentBodyFetchManager();
                if (bodyFetchManager != null) {
                    bodyFetchManager.setSyncPhase(SyncPhase.STEADY_STATE);
                    if (bodyFetchManager.isPaused()) {
                        bodyFetchManager.resume();
                    }
                }
                log.info("NEAR-TIP FAST PATH: remote-local distance={} slots <= {}, transitioned to STEADY_STATE",
                        distance, nearTipThreshold);
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
        peerRecoveryExecutor.shutdown();
        try {
            if (!peerRecoveryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                peerRecoveryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            peerRecoveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
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

    private PeerSession createPeerSession() {
        var session = new PeerSession(
                new PeerEndpoint(remoteCardanoHost, remoteCardanoPort, protocolMagic),
                chainState,
                eventBus,
                this,
                epochParamProviderSupplier.get(),
                peerClientFactory);
        session.setGenesisBootstrapDataSupplier(genesisBootstrapDataSupplier);
        return session;
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
                log.info("Upstream peer session recovered successfully: reason={}", reason);
            } catch (Exception e) {
                PeerRecoveryFailureTracker.Snapshot failure = peerRecoveryFailureTracker.recordFailure(reason, e);
                if (failure.terminal()) {
                    log.error("Upstream peer session recovery reached terminal failure; automatic retries paused: {}",
                            failure.message(), e);
                } else {
                    log.warn("Upstream peer session recovery failed; supervisor will retry after cooldown: {}",
                            failure.message(), e);
                }
                markPeerRecoveryFailure(reason, e, failure);
                isSyncing.set(!failure.terminal());
            }
        }
    }

    private void recordPeerRecoveryFailure(PeerRecoveryReason reason, Exception e) {
        synchronized (peerSessionLock) {
            if (!runtimeRunning.getAsBoolean() || !config.isEnableClient()) {
                return;
            }
            PeerRecoveryFailureTracker.Snapshot failure = peerRecoveryFailureTracker.recordFailure(reason, e);
            if (failure.terminal()) {
                log.error("Upstream peer session recovery reached terminal failure; automatic retries paused: {}",
                        failure.message(), e);
            } else {
                log.warn("Upstream peer session recovery failed; supervisor will retry after cooldown: {}",
                        failure.message(), e);
            }
            markPeerRecoveryFailure(reason, e, failure);
            isSyncing.set(!failure.terminal());
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
