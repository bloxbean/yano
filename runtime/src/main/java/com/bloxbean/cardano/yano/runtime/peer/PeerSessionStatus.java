package com.bloxbean.cardano.yano.runtime.peer;

/**
 * Immutable snapshot of peer session health.
 */
public record PeerSessionStatus(
        String peerName,
        PeerSessionState state,
        long createdAtMillis,
        long lastHeaderReceivedAtMillis,
        long lastBodyReceivedAtMillis,
        long lastBodyAppliedAtMillis,
        long lastKeepAliveResponseAtMillis,
        long lastDisconnectAtMillis,
        long lastFailedWriteAtMillis,
        long lastHeaderSlot,
        long lastHeaderBlockNumber,
        long lastBodyReceivedSlot,
        long lastBodyReceivedBlockNumber,
        long lastBodyAppliedSlot,
        long lastBodyAppliedBlockNumber,
        boolean bodyFetchInProgress,
        long bodyFetchStartedAtMillis,
        int recoveryAttempts,
        PeerRecoveryReason lastRecoveryReason,
        String terminalFailureMessage,
        long nowMillis,
        long applicationProgressAgeMillis,
        long keepAliveAgeMillis,
        long bodyFetchInProgressAgeMillis
) {
}
