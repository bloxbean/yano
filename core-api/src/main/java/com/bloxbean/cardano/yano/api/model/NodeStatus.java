package com.bloxbean.cardano.yano.api.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents the current status of a Yano node.
 * Contains information about sync progress, statistics, and operational state.
 */
@Data
@Builder
public class NodeStatus {
    
    /**
     * Whether the node is currently running
     */
    private final boolean running;
    
    /**
     * Whether the node is actively syncing with remote peers
     */
    private final boolean syncing;
    
    /**
     * Whether the server component is running
     */
    private final boolean serverRunning;
    
    /**
     * Total number of blocks processed by this node
     */
    private final long blocksProcessed;
    
    /**
     * The slot number of the last processed block
     */
    private final long lastProcessedSlot;
    
    /**
     * Current local tip slot (if available)
     */
    private final Long localTipSlot;
    
    /**
     * Current local tip block number (if available)
     */
    private final Long localTipBlockNumber;
    
    /**
     * Remote tip slot (if available and syncing)
     */
    private final Long remoteTipSlot;
    
    /**
     * Remote tip block number (if available and syncing)
     */
    private final Long remoteTipBlockNumber;
    
    /**
     * Sync progress as percentage (0-100), null if not applicable
     */
    private final Double syncProgress;
    
    /**
     * Whether initial sync is complete
     */
    private final boolean initialSyncComplete;
    
    /**
     * Current sync mode (e.g., "sequential", "pipelined", "idle")
     */
    private final String syncMode;
    
    /**
     * Additional status information as free text
     */
    private final String statusMessage;

    /**
     * Whether an exclusive maintenance operation is currently active.
     */
    private final boolean maintenanceActive;

    /**
     * Current exclusive maintenance reason, if active.
     */
    private final String maintenanceReason;

    /**
     * Whether a failed exclusive maintenance operation left runtime services in
     * a degraded state that needs operator action.
     */
    private final boolean runtimeDegraded;

    /**
     * Human-readable reason for the degraded runtime state.
     */
    private final String runtimeDegradedReason;

    /**
     * Maintenance operation that marked the runtime degraded.
     */
    private final String runtimeDegradedOperation;

    /**
     * Timestamp when the runtime was marked degraded.
     */
    private final Long runtimeDegradedAtMillis;

    /**
     * Current upstream peer name, if client sync is active.
     */
    private final String peerName;

    /**
     * Configured upstream behavior preset.
     */
    private final String upstreamMode;

    /**
     * Number of configured upstream peers.
     */
    private final Integer upstreamConfiguredPeerCount;

    /**
     * Number of upstream peers currently connected/hot.
     */
    private final Integer upstreamHotPeerCount;

    /**
     * Number of non-selected observer upstream peers currently running.
     */
    private final Integer upstreamObserverPeerCount;

    /**
     * Number of known peers in the upstream peer store.
     */
    private final Integer upstreamKnownPeerCount;

    /**
     * Number of candidate headers observed outside canonical state.
     */
    private final Integer upstreamCandidateHeaderCount;

    /**
     * Active selected upstream peer id or endpoint.
     */
    private final String upstreamActivePeer;

    /**
     * Transaction forwarding policy for upstream peers.
     */
    private final String upstreamTxForwarding;

    /**
     * Whether multi-peer upstream support is currently observation-only.
     */
    private final Boolean upstreamMultiPeerObservationOnly;

    /**
     * Whether peer discovery is currently running.
     */
    private final Boolean upstreamDiscoveryRunning;

    /**
     * Whether relay-side peer-sharing advertisement is enabled.
     */
    private final Boolean relayAutoDiscovery;

    /**
     * Host advertised through relay peer sharing, if configured.
     */
    private final String relayAdvertisedHost;

    /**
     * Port advertised through relay peer sharing.
     */
    private final Integer relayAdvertisedPort;

    /**
     * Number of active inbound relay connections.
     */
    private final Integer relayInboundConnectionCount;

    /**
     * Number of active outbound relay connections.
     */
    private final Integer relayOutboundConnectionCount;

    /**
     * Number of established relay connections.
     */
    private final Integer relayEstablishedConnectionCount;

    /**
     * Number of relay connections currently connecting or handshaking.
     */
    private final Integer relayConnectingConnectionCount;

    /**
     * Number of inbound connections rejected by relay admission policy.
     */
    private final Long relayRejectedInboundConnections;

    /**
     * Number of outbound connection attempts failed or rejected by the connection manager.
     */
    private final Long relayFailedOutboundConnections;

    /**
     * Configured inbound connection limit per remote IP.
     */
    private final Integer relayConnectionsPerIpMax;

    /**
     * Number of peers admitted by the relay peer governor.
     */
    private final Integer relayKnownPeerCount;

    /**
     * Number of cold peers in the relay peer governor.
     */
    private final Integer relayColdPeerCount;

    /**
     * Number of warm peers in the relay peer governor.
     */
    private final Integer relayWarmPeerCount;

    /**
     * Number of hot peers in the relay peer governor.
     */
    private final Integer relayHotPeerCount;

    /**
     * Number of peers currently suppressed by governor backoff.
     */
    private final Integer relayBackoffPeerCount;

    /**
     * Number of peers currently quarantined by the governor.
     */
    private final Integer relayQuarantinedPeerCount;

    /**
     * Number of peers eligible for peer-sharing responses.
     */
    private final Integer relaySharablePeerCount;

    /**
     * Number of inbound peers known by the governor.
     */
    private final Integer relayInboundPeerCount;

    /**
     * Number of gossip peers known by the governor.
     */
    private final Integer relayGossipPeerCount;

    /**
     * Number of ledger peers known by the governor.
     */
    private final Integer relayLedgerPeerCount;

    /**
     * Number of bootstrap peers known by the governor.
     */
    private final Integer relayBootstrapPeerCount;

    /**
     * Configured governor hot peer target.
     */
    private final Integer relayGovernorTargetHotPeers;

    /**
     * Configured governor warm peer target.
     */
    private final Integer relayGovernorTargetWarmPeers;

    /**
     * Last governor reconcile time.
     */
    private final Long relayGovernorLastReconcileAtMillis;

    /**
     * Configured upstream header validation level.
     */
    private final String upstreamValidationLevel;

    /**
     * Number of upstream Shelley+ headers accepted by validation.
     */
    private final Long upstreamValidationAcceptedHeaders;

    /**
     * Number of upstream Shelley+ headers rejected by validation.
     */
    private final Long upstreamValidationRejectedHeaders;

    /**
     * Last upstream header validation rejection stage.
     */
    private final String upstreamValidationLastRejectedStage;

    /**
     * Last upstream header validation rejection reason.
     */
    private final String upstreamValidationLastRejectedReason;

    /**
     * Current number of transactions in the local mempool.
     */
    private final Integer mempoolSize;

    /**
     * Current total transaction bytes retained by the local mempool.
     */
    private final Long mempoolBytes;

    /**
     * Configured maximum transaction count for the local mempool.
     */
    private final Integer mempoolMaxTxs;

    /**
     * Configured maximum transaction bytes for the local mempool.
     */
    private final Long mempoolMaxBytes;

    /**
     * Configured transaction TTL in seconds for the local mempool.
     */
    private final Long mempoolTtlSeconds;

    /**
     * Whether transaction admission is currently accepting new transactions.
     */
    private final Boolean mempoolAccepting;

    /**
     * Whether transaction validation services are currently available.
     */
    private final Boolean mempoolValidationAvailable;

    /**
     * Whether transaction evaluation services are currently available.
     */
    private final Boolean mempoolEvaluationAvailable;

    /**
     * Configured transaction diffusion mode.
     */
    private final String txDiffusionMode;

    /**
     * Whether transaction diffusion mode is enabled beyond disabled mode.
     */
    private final Boolean txDiffusionEnabled;

    /**
     * Number of peers currently tracked by tx diffusion state.
     */
    private final Integer txDiffusionPeerCount;

    /**
     * Count of accepted mempool events observed by tx diffusion.
     */
    private final Long txDiffusionAcceptedMempoolEvents;

    /**
     * Count of inbound transaction ids selected for body request.
     */
    private final Long txDiffusionInboundTxIdsRequested;

    /**
     * Count of inbound transaction ids not requested because of policy, limits, or cooldown.
     */
    private final Long txDiffusionInboundTxIdsRejected;

    /**
     * Count of inbound transaction ids ignored because they were already known or already requested.
     */
    private final Long txDiffusionInboundTxIdsIgnored;

    /**
     * Count of inbound transaction bodies admitted into the mempool.
     */
    private final Long txDiffusionInboundTxBodiesAccepted;

    /**
     * Count of inbound transaction bodies rejected by parsing, validation, or mempool admission.
     */
    private final Long txDiffusionInboundTxBodiesRejected;

    /**
     * Count of inbound transaction bodies ignored because they were not requested or ingress was disabled.
     */
    private final Long txDiffusionInboundTxBodiesIgnored;

    /**
     * Count of local transactions forwarded to tx-submission peers.
     */
    private final Long txDiffusionOutboundForwarded;

    /**
     * Count of local transaction forwards suppressed by per-peer state or policy.
     */
    private final Long txDiffusionOutboundSuppressed;

    /**
     * Count of mempool transaction bodies served from diffusion state.
     */
    private final Long txDiffusionServedTxs;

    /**
     * Bytes of mempool transaction bodies served from diffusion state.
     */
    private final Long txDiffusionServedBytes;

    /**
     * Number of inbound transaction bodies currently requested from peers.
     */
    private final Long txDiffusionInFlightTxs;

    /**
     * Bytes of inbound transaction bodies currently requested from peers.
     */
    private final Long txDiffusionInFlightBytes;

    /**
     * Current upstream peer session state.
     */
    private final String peerState;

    /**
     * Last peer recovery reason, if any.
     */
    private final String peerRecoveryReason;

    /**
     * Consecutive peer recovery failures since the last successful recovery.
     */
    private final Integer peerRecoveryFailures;

    /**
     * Maximum consecutive peer recovery failures before automatic retries pause.
     */
    private final Integer peerMaxRecoveryFailures;

    /**
     * Whether peer recovery retries are paused after repeated failures.
     */
    private final boolean peerRecoveryTerminal;

    /**
     * Terminal or latest peer recovery failure message, if any.
     */
    private final String peerTerminalFailureMessage;

    /**
     * Age in milliseconds since header/body application progress.
     */
    private final Long peerApplicationProgressAgeMillis;

    /**
     * Age in milliseconds since the last upstream keepalive response.
     */
    private final Long peerKeepAliveAgeMillis;

    /**
     * Whether a body fetch batch is currently in progress.
     */
    private final Boolean peerBodyFetchInProgress;

    /**
     * Age in milliseconds of the in-progress body fetch batch.
     */
    private final Long peerBodyFetchInProgressAgeMillis;
    
    /**
     * Timestamp when this status was created (System.currentTimeMillis())
     */
    private final long timestamp;
    
    /**
     * Calculate sync progress if both local and remote tips are available
     */
    public Double calculateSyncProgress() {
        if (localTipSlot != null && remoteTipSlot != null && remoteTipSlot > 0) {
            if (localTipSlot >= remoteTipSlot) {
                return 100.0;
            }
            return Math.min(100.0, (localTipSlot.doubleValue() / remoteTipSlot.doubleValue()) * 100.0);
        }
        return syncProgress;
    }
    
    /**
     * Check if the node is considered "in sync" (within reasonable distance of remote tip)
     */
    public boolean isInSync() {
        if (localTipSlot != null && remoteTipSlot != null) {
            // A locally applied tip can be ahead of the last advertised peer tip.
            if (localTipSlot >= remoteTipSlot) {
                return true;
            }
            // Otherwise consider in sync if the local tip is within 10 slots behind the remote tip.
            return remoteTipSlot - localTipSlot <= 10;
        }
        return false;
    }
    
    /**
     * Get a human-readable status summary
     */
    public String getStatusSummary() {
        if (runtimeDegraded) {
            return "Degraded";
        }

        if (!running) {
            return "Stopped";
        }
        
        if (!syncing) {
            return serverRunning ? "Running (Server Only)" : "Running (Idle)";
        }
        
        Double progress = calculateSyncProgress();
        if (progress != null) {
            return String.format("Syncing (%.1f%%)", progress);
        }
        
        return "Syncing";
    }
}
