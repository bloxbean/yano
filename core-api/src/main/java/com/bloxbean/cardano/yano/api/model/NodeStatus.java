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
     * Current upstream peer name, if client sync is active.
     */
    private final String peerName;

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
            // Consider in sync if within 10 slots of remote tip
            return Math.abs(remoteTipSlot - localTipSlot) <= 10;
        }
        return false;
    }
    
    /**
     * Get a human-readable status summary
     */
    public String getStatusSummary() {
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
