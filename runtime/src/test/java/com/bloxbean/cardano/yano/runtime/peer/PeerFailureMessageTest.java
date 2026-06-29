package com.bloxbean.cardano.yano.runtime.peer;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerFailureMessageTest {

    @Test
    void summarizeIncludesRootCauseWithoutStackTrace() {
        RuntimeException failure = new RuntimeException(
                "Failed to start node client",
                new ConnectException("Connection refused: relay.example:3001"));

        assertEquals(
                "RuntimeException: Failed to start node client; "
                        + "cause=ConnectException: Connection refused: relay.example:3001",
                PeerFailureMessage.summarize(failure));
    }

    @Test
    void summarizeFindsSuppressedNetworkCause() {
        RuntimeException failure = new RuntimeException("Failed to start node client");
        failure.addSuppressed(new RuntimeException(
                "Rethrowing promise failure cause",
                new ConnectException("Connection timed out: relay.example:3001")));

        String summary = PeerFailureMessage.summarize(failure);

        assertTrue(summary.contains("RuntimeException: Failed to start node client"));
        assertTrue(summary.contains("ConnectException: Connection timed out: relay.example:3001"));
    }
}
