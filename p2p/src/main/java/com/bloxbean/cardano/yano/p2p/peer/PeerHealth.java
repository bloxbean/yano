package com.bloxbean.cardano.yano.p2p.peer;

import java.util.Objects;

/**
 * Mutable per-session health state for one upstream peer.
 *
 * <p>This class is intentionally passive. Phase 1 uses it as a tested state
 * container before peer lifecycle and recovery decisions are wired in.</p>
 */
public final class PeerHealth {
    private final String peerName;
    private final long createdAtMillis;

    private PeerSessionState state = PeerSessionState.NEW;
    private long lastHeaderReceivedAtMillis;
    private long lastBodyReceivedAtMillis;
    private long lastBodyAppliedAtMillis;
    private long lastKeepAliveResponseAtMillis;
    private long lastDisconnectAtMillis;
    private long lastFailedWriteAtMillis;
    private long lastHeaderSlot = -1;
    private long lastHeaderBlockNumber = -1;
    private long lastBodyReceivedSlot = -1;
    private long lastBodyReceivedBlockNumber = -1;
    private long lastBodyAppliedSlot = -1;
    private long lastBodyAppliedBlockNumber = -1;
    private boolean bodyFetchInProgress;
    private long bodyFetchStartedAtMillis;
    private int recoveryAttempts;
    private PeerRecoveryReason lastRecoveryReason = PeerRecoveryReason.UNKNOWN;
    private String terminalFailureMessage;

    public PeerHealth(String peerName, long createdAtMillis) {
        this.peerName = Objects.requireNonNull(peerName, "peerName");
        this.createdAtMillis = createdAtMillis;
    }

    public synchronized void markState(PeerSessionState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public synchronized boolean isTerminalFailure() {
        return state == PeerSessionState.TERMINAL_FAILURE;
    }

    public synchronized void recordHeaderProgress(long slot, long blockNumber, long nowMillis) {
        lastHeaderReceivedAtMillis = nowMillis;
        lastHeaderSlot = slot;
        lastHeaderBlockNumber = blockNumber;
    }

    public synchronized void recordBodyReceived(long slot, long blockNumber, long nowMillis) {
        lastBodyReceivedAtMillis = nowMillis;
        lastBodyReceivedSlot = slot;
        lastBodyReceivedBlockNumber = blockNumber;
    }

    public synchronized void recordBodyApplied(long slot, long blockNumber, long nowMillis) {
        lastBodyAppliedAtMillis = nowMillis;
        lastBodyAppliedSlot = slot;
        lastBodyAppliedBlockNumber = blockNumber;
    }

    public synchronized void recordKeepAliveResponse(long nowMillis) {
        lastKeepAliveResponseAtMillis = nowMillis;
    }

    public synchronized void recordDisconnect(long nowMillis) {
        lastDisconnectAtMillis = nowMillis;
    }

    public synchronized void recordFailedWrite(long nowMillis) {
        lastFailedWriteAtMillis = nowMillis;
    }

    public synchronized void markBodyFetchStarted(long nowMillis) {
        if (!bodyFetchInProgress) {
            bodyFetchInProgress = true;
            bodyFetchStartedAtMillis = nowMillis;
        }
    }

    public synchronized void markBodyFetchCompleted() {
        bodyFetchInProgress = false;
        bodyFetchStartedAtMillis = 0;
    }

    public synchronized int recordRecoveryAttempt(PeerRecoveryReason reason) {
        lastRecoveryReason = Objects.requireNonNullElse(reason, PeerRecoveryReason.UNKNOWN);
        recoveryAttempts++;
        return recoveryAttempts;
    }

    public synchronized void resetRecoveryAttempts() {
        if (state == PeerSessionState.TERMINAL_FAILURE) {
            throw new IllegalStateException("Cannot reset recovery attempts after terminal failure; create a new PeerHealth");
        }
        recoveryAttempts = 0;
        lastRecoveryReason = PeerRecoveryReason.UNKNOWN;
    }

    public synchronized void markTerminalFailure(PeerRecoveryReason reason, String message) {
        state = PeerSessionState.TERMINAL_FAILURE;
        lastRecoveryReason = Objects.requireNonNullElse(reason, PeerRecoveryReason.TERMINAL_FAILURE);
        terminalFailureMessage = message;
    }

    public synchronized long lastApplicationProgressAtMillis() {
        return Math.max(lastHeaderReceivedAtMillis, Math.max(lastBodyReceivedAtMillis, lastBodyAppliedAtMillis));
    }

    public synchronized PeerSessionStatus snapshot(long nowMillis) {
        return new PeerSessionStatus(
                peerName,
                state,
                createdAtMillis,
                lastHeaderReceivedAtMillis,
                lastBodyReceivedAtMillis,
                lastBodyAppliedAtMillis,
                lastKeepAliveResponseAtMillis,
                lastDisconnectAtMillis,
                lastFailedWriteAtMillis,
                lastHeaderSlot,
                lastHeaderBlockNumber,
                lastBodyReceivedSlot,
                lastBodyReceivedBlockNumber,
                lastBodyAppliedSlot,
                lastBodyAppliedBlockNumber,
                bodyFetchInProgress,
                bodyFetchStartedAtMillis,
                recoveryAttempts,
                lastRecoveryReason,
                terminalFailureMessage,
                nowMillis,
                ageSince(nowMillis, lastApplicationProgressAtMillis()),
                ageSince(nowMillis, lastKeepAliveResponseAtMillis),
                bodyFetchInProgress ? ageSince(nowMillis, bodyFetchStartedAtMillis) : -1
        );
    }

    private static long ageSince(long nowMillis, long timestampMillis) {
        if (timestampMillis <= 0) {
            return -1;
        }
        return Math.max(0, nowMillis - timestampMillis);
    }
}
