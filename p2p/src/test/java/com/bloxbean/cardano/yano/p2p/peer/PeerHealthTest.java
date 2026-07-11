package com.bloxbean.cardano.yano.p2p.peer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerHealthTest {

    @Test
    void initialSnapshotHasNoProgress() {
        PeerHealth health = new PeerHealth("relay-1", 1000);

        PeerSessionStatus status = health.snapshot(1500);

        assertEquals("relay-1", status.peerName());
        assertEquals(PeerSessionState.NEW, status.state());
        assertEquals(-1, status.lastHeaderSlot());
        assertEquals(-1, status.lastBodyReceivedSlot());
        assertEquals(-1, status.lastBodyAppliedSlot());
        assertEquals(-1, status.applicationProgressAgeMillis());
        assertEquals(-1, status.keepAliveAgeMillis());
        assertFalse(status.bodyFetchInProgress());
    }

    @Test
    void recordsProgressAndComputesAges() {
        PeerHealth health = new PeerHealth("relay-1", 1000);

        health.markState(PeerSessionState.RUNNING);
        health.recordHeaderProgress(200, 20, 1100);
        health.recordBodyReceived(190, 19, 1200);
        health.recordBodyApplied(185, 18, 1300);
        health.recordKeepAliveResponse(1250);

        PeerSessionStatus status = health.snapshot(1500);

        assertEquals(PeerSessionState.RUNNING, status.state());
        assertEquals(200, status.lastHeaderSlot());
        assertEquals(20, status.lastHeaderBlockNumber());
        assertEquals(190, status.lastBodyReceivedSlot());
        assertEquals(19, status.lastBodyReceivedBlockNumber());
        assertEquals(185, status.lastBodyAppliedSlot());
        assertEquals(18, status.lastBodyAppliedBlockNumber());
        assertEquals(200, status.applicationProgressAgeMillis());
        assertEquals(250, status.keepAliveAgeMillis());
    }

    @Test
    void tracksBodyFetchInProgressAge() {
        PeerHealth health = new PeerHealth("relay-1", 1000);

        health.markBodyFetchStarted(2000);
        PeerSessionStatus inProgress = health.snapshot(2600);

        assertTrue(inProgress.bodyFetchInProgress());
        assertEquals(600, inProgress.bodyFetchInProgressAgeMillis());

        health.markBodyFetchCompleted();
        PeerSessionStatus completed = health.snapshot(2700);

        assertFalse(completed.bodyFetchInProgress());
        assertEquals(-1, completed.bodyFetchInProgressAgeMillis());
    }

    @Test
    void duplicateBodyFetchStartKeepsOriginalStartTime() {
        PeerHealth health = new PeerHealth("relay-1", 1000);

        health.markBodyFetchStarted(2000);
        health.markBodyFetchStarted(2500);

        PeerSessionStatus status = health.snapshot(3000);

        assertTrue(status.bodyFetchInProgress());
        assertEquals(2000, status.bodyFetchStartedAtMillis());
        assertEquals(1000, status.bodyFetchInProgressAgeMillis());
    }

    @Test
    void tracksRecoveryAttemptsAndTerminalFailure() {
        PeerHealth health = new PeerHealth("relay-1", 1000);

        assertEquals(1, health.recordRecoveryAttempt(PeerRecoveryReason.NO_PROGRESS));
        assertEquals(2, health.recordRecoveryAttempt(PeerRecoveryReason.FAILED_WRITE));
        health.resetRecoveryAttempts();
        assertEquals(1, health.recordRecoveryAttempt(PeerRecoveryReason.NO_PROGRESS));

        PeerSessionStatus afterAttempts = health.snapshot(2000);
        assertEquals(1, afterAttempts.recoveryAttempts());
        assertEquals(PeerRecoveryReason.NO_PROGRESS, afterAttempts.lastRecoveryReason());

        health.markTerminalFailure(PeerRecoveryReason.TERMINAL_FAILURE, "max attempts exceeded");
        PeerSessionStatus terminal = health.snapshot(2100);

        assertEquals(PeerSessionState.TERMINAL_FAILURE, terminal.state());
        assertEquals(PeerRecoveryReason.TERMINAL_FAILURE, terminal.lastRecoveryReason());
        assertEquals("max attempts exceeded", terminal.terminalFailureMessage());
        assertThrows(IllegalStateException.class, health::resetRecoveryAttempts);
    }

    @Test
    void tracksDisconnectAndFailedWriteSignals() {
        PeerHealth health = new PeerHealth("relay-1", 1000);

        health.recordDisconnect(3000);
        health.recordFailedWrite(3500);

        PeerSessionStatus status = health.snapshot(4000);
        assertEquals(3000, status.lastDisconnectAtMillis());
        assertEquals(3500, status.lastFailedWriteAtMillis());
    }
}
