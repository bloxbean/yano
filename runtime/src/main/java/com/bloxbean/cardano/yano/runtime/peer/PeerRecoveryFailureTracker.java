package com.bloxbean.cardano.yano.runtime.peer;

import java.util.Objects;

/**
 * Tracks consecutive peer recovery startup failures.
 */
public final class PeerRecoveryFailureTracker {
    private final int maxFailures;
    private int consecutiveFailures;
    private boolean terminal;
    private PeerRecoveryReason lastReason = PeerRecoveryReason.UNKNOWN;
    private String message;

    public PeerRecoveryFailureTracker(int maxFailures) {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive");
        }
        this.maxFailures = maxFailures;
    }

    public synchronized Snapshot recordFailure(PeerRecoveryReason reason, Throwable failure) {
        lastReason = Objects.requireNonNullElse(reason, PeerRecoveryReason.UNKNOWN);
        consecutiveFailures++;
        terminal = consecutiveFailures >= maxFailures;

        String detail = failure != null && failure.getMessage() != null
                ? failure.getMessage()
                : failure != null ? failure.getClass().getName() : "unknown";
        message = "Peer recovery failed " + consecutiveFailures + "/" + maxFailures
                + " times; last reason=" + lastReason + ": " + detail;
        return snapshot();
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        terminal = false;
        lastReason = PeerRecoveryReason.UNKNOWN;
        message = null;
    }

    public synchronized boolean isTerminal() {
        return terminal;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(maxFailures, consecutiveFailures, terminal, lastReason, message);
    }

    public record Snapshot(
            int maxFailures,
            int consecutiveFailures,
            boolean terminal,
            PeerRecoveryReason lastReason,
            String message
    ) {
    }
}
