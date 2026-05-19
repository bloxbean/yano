package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.PipelineConfig;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.runtime.BodyFetchManager;
import com.bloxbean.cardano.yano.runtime.HeaderSyncManager;
import com.bloxbean.cardano.yano.runtime.PipelineDataListener;
import com.bloxbean.cardano.yano.runtime.SyncTipContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Owns the lifecycle of one active upstream peer session.
 *
 * <p>Phase 2 extracts the existing single-peer startup/stop lifecycle without
 * adding recovery decisions. Future phases will add health tracking and
 * supervisor-driven replacement around this object.</p>
 */
@Slf4j
public class PeerSession {
    private final ChainState chainState;
    private final EventBus eventBus;
    private final PeerSessionCallbacks callbacks;
    private final EpochParamProvider epochParamProvider;
    private final PeerHealth peerHealth;
    private final PeerEndpoint endpoint;
    private final PeerClientFactory peerClientFactory;

    private PeerClient peerClient;
    private HeaderSyncManager headerSyncManager;
    private BodyFetchManager bodyFetchManager;
    private PipelineDataListener pipelineDataListener;

    public PeerSession(String host,
                       int port,
                       long protocolMagic,
                       ChainState chainState,
                       EventBus eventBus,
                       PeerSessionCallbacks callbacks,
                       EpochParamProvider epochParamProvider) {
        this(new PeerEndpoint(host, port, protocolMagic),
                chainState,
                eventBus,
                callbacks,
                epochParamProvider,
                DefaultPeerClientFactory.supervised());
    }

    public PeerSession(PeerEndpoint endpoint,
                       ChainState chainState,
                       EventBus eventBus,
                       PeerSessionCallbacks callbacks,
                       EpochParamProvider epochParamProvider,
                       PeerClientFactory peerClientFactory) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.epochParamProvider = epochParamProvider;
        this.peerClientFactory = Objects.requireNonNull(peerClientFactory, "peerClientFactory");
        this.peerHealth = new PeerHealth(endpoint.displayName(), System.currentTimeMillis());
    }

    public void startPipelined(Point startPoint, PipelineConfig pipelineConfig) {
        Objects.requireNonNull(startPoint, "startPoint");
        Objects.requireNonNull(pipelineConfig, "pipelineConfig");

        peerHealth.markState(PeerSessionState.STARTING);
        try {
            ensurePeerClient(startPoint);
            initializePipelineManagers(pipelineConfig);

            pipelineDataListener = new PipelineDataListener(headerSyncManager, bodyFetchManager, callbacks, peerHealth);
            peerClient.connect(pipelineDataListener, null);
            peerClient.enableTxSubmission();
            peerClient.startHeaderSync(startPoint, true);
            log.info("🔗 ==> Header sync started with pipelining enabled");

            bodyFetchManager.start();
            log.info("📦 ==> Body fetch manager started for range-based fetching");
            peerHealth.markState(PeerSessionState.RUNNING);
            log.info("🚀 Pipeline startup complete - HeaderSync and BodyFetch active");
        } catch (RuntimeException e) {
            markStartupFailed(e);
            throw e;
        }
    }

    public void startSequential(Point startPoint, PipelineConfig pipelineConfig) {
        Objects.requireNonNull(startPoint, "startPoint");
        Objects.requireNonNull(pipelineConfig, "pipelineConfig");

        peerHealth.markState(PeerSessionState.STARTING);
        try {
            ensurePeerClient(startPoint);
            initializePipelineManagers(pipelineConfig);

            pipelineDataListener = new PipelineDataListener(headerSyncManager, bodyFetchManager, callbacks, peerHealth);
            peerClient.connect(pipelineDataListener, null);
            peerClient.enableTxSubmission();
            peerClient.startSync(startPoint);
            peerHealth.markState(PeerSessionState.RUNNING);
            log.info("📦 ==> Sequential sync started from point: {}", startPoint);
        } catch (RuntimeException e) {
            markStartupFailed(e);
            throw e;
        }
    }

    public void stop() {
        if (peerHealth.isTerminalFailure()) {
            stopResources();
            peerHealth.markBodyFetchCompleted();
            return;
        }
        peerHealth.markState(PeerSessionState.STOPPING);
        stopResources();
        peerHealth.markBodyFetchCompleted();
        peerHealth.markState(PeerSessionState.STOPPED);
    }

    private void stopResources() {
        if (bodyFetchManager != null && bodyFetchManager.isRunning()) {
            try {
                bodyFetchManager.stop();
            } catch (Exception e) {
                log.warn("Error stopping BodyFetchManager", e);
            }
        }

        if (peerClient != null) {
            try {
                log.info("Stopping PeerClient...");
                peerClient.stop();
            } catch (Exception e) {
                log.warn("Error stopping PeerClient", e);
            }
        }
    }

    public boolean isRunning() {
        return peerClient != null && peerClient.isRunning();
    }

    public void submitTxBytes(String txHash, byte[] txCbor, TxBodyType txBodyType) {
        if (peerClient == null || !peerClient.isRunning()) {
            return;
        }

        peerClient.submitTxBytes(txHash, txCbor, txBodyType);
    }

    public HeaderSyncManager getHeaderSyncManager() {
        return headerSyncManager;
    }

    public BodyFetchManager getBodyFetchManager() {
        return bodyFetchManager;
    }

    public PeerClient getPeerClient() {
        return peerClient;
    }

    public PeerHealth getPeerHealth() {
        return peerHealth;
    }

    public PeerSessionStatus getStatus() {
        refreshKeepAliveHealth();
        return peerHealth.snapshot(System.currentTimeMillis());
    }

    private void ensurePeerClient(Point startPoint) {
        if (peerClient == null) {
            peerClient = peerClientFactory.create(endpoint, startPoint);
        }
    }

    private void initializePipelineManagers(PipelineConfig pipelineConfig) {
        stopExistingBodyFetchManager();

        SyncTipContext syncTipContext = new SyncTipContext();
        headerSyncManager = new HeaderSyncManager(peerClient, chainState, 50000, syncTipContext);
        log.info("📋 HeaderSyncManager created");

        long gapThreshold = Math.max(pipelineConfig.getBodyBatchSize() / 10, 100);
        int maxBatchSize = pipelineConfig.getBodyBatchSize();

        // Preserve current Yano behavior while the lifecycle is being extracted.
        maxBatchSize = 5000;

        bodyFetchManager = new BodyFetchManager(
                peerClient,
                chainState,
                eventBus,
                gapThreshold,
                maxBatchSize,
                500,
                1000,
                syncTipContext
        );
        bodyFetchManager.setPeerHealth(peerHealth);
        log.info("📦 BodyFetchManager created with gapThreshold={}, maxBatchSize={}",
                gapThreshold, maxBatchSize);

        if (epochParamProvider != null) {
            bodyFetchManager.setEpochParamProvider(epochParamProvider);
            ChainTip tip = chainState.getTip();
            if (tip != null && tip.getSlot() > 0) {
                int tipEpoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(tip.getSlot());
                bodyFetchManager.initializePreviousEpoch(tipEpoch);
            }
        }

        log.info("🔗 Pipeline managers initialized and ready");
        log.info("ℹ️  HeaderSyncManager will receive headers through ChainSync protocol");
        log.info("ℹ️  BodyFetchManager will monitor for gaps and fetch ranges automatically");
    }

    private void stopExistingBodyFetchManager() {
        if (bodyFetchManager != null && bodyFetchManager.isRunning()) {
            bodyFetchManager.stop();
        }
    }

    private void refreshKeepAliveHealth() {
        if (peerClient == null) {
            return;
        }

        long lastKeepAliveResponseTime = peerClient.getLastKeepAliveResponseTime();
        if (lastKeepAliveResponseTime > 0) {
            peerHealth.recordKeepAliveResponse(lastKeepAliveResponseTime);
        }
    }

    private void markStartupFailed(RuntimeException e) {
        stopResources();
        peerHealth.markBodyFetchCompleted();
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        peerHealth.markTerminalFailure(PeerRecoveryReason.STARTUP_FAILED, "Peer session startup failed: " + message);
    }
}
