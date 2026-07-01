package com.bloxbean.cardano.yano.p2p.peer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerRecoveryFailureTrackerTest {

    @Test
    void entersTerminalAfterMaxFailures() {
        PeerRecoveryFailureTracker tracker = new PeerRecoveryFailureTracker(2);

        PeerRecoveryFailureTracker.Snapshot first = tracker.recordFailure(
                PeerRecoveryReason.STARTUP_FAILED,
                new RuntimeException("connect failed"));
        PeerRecoveryFailureTracker.Snapshot second = tracker.recordFailure(
                PeerRecoveryReason.STARTUP_FAILED,
                new RuntimeException("connect failed again"));

        assertFalse(first.terminal());
        assertTrue(second.terminal());
        assertEquals(2, second.consecutiveFailures());
        assertEquals(PeerRecoveryReason.STARTUP_FAILED, second.lastReason());
        assertTrue(second.message().contains("2/2"));
    }

    @Test
    void successResetsFailuresAndTerminalState() {
        PeerRecoveryFailureTracker tracker = new PeerRecoveryFailureTracker(1);
        tracker.recordFailure(PeerRecoveryReason.NO_PROGRESS, new RuntimeException("failed"));

        tracker.recordSuccess();

        PeerRecoveryFailureTracker.Snapshot snapshot = tracker.snapshot();
        assertFalse(snapshot.terminal());
        assertEquals(0, snapshot.consecutiveFailures());
        assertEquals(PeerRecoveryReason.UNKNOWN, snapshot.lastReason());
    }

    @Test
    void rejectsInvalidMaxFailures() {
        assertThrows(IllegalArgumentException.class, () -> new PeerRecoveryFailureTracker(0));
    }
}
