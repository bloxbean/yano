package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.listener.NodeEventListener;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YanoHealthCheckTest {

    @Test
    void readinessIsDownWhenPeerRecoveryIsTerminal() {
        YanoHealthCheck healthCheck = new YanoHealthCheck();
        healthCheck.nodeLifecycle = lifecycle(NodeStatus.builder()
                .running(true)
                .syncing(false)
                .peerRecoveryTerminal(true)
                .statusMessage("Node is running")
                .build());

        assertEquals(HealthCheckResponse.Status.DOWN, healthCheck.call().getStatus());
    }

    @Test
    void readinessIsDownWhenRuntimeIsDegraded() {
        YanoHealthCheck healthCheck = new YanoHealthCheck();
        healthCheck.nodeLifecycle = lifecycle(NodeStatus.builder()
                .running(true)
                .syncing(false)
                .runtimeDegraded(true)
                .runtimeDegradedReason("Snapshot restored but runtime services did not resume")
                .statusMessage("Node is running")
                .build());

        assertEquals(HealthCheckResponse.Status.DOWN, healthCheck.call().getStatus());
    }

    private static NodeLifecycle lifecycle(NodeStatus status) {
        return new NodeLifecycle() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return status.isRunning();
            }

            @Override
            public boolean isSyncing() {
                return status.isSyncing();
            }

            @Override
            public boolean isServerRunning() {
                return status.isServerRunning();
            }

            @Override
            public NodeStatus getStatus() {
                return status;
            }

            @Override
            public NodeConfig getConfig() {
                return null;
            }

            @Override
            public void addNodeEventListener(NodeEventListener listener) {
            }

            @Override
            public void removeNodeEventListener(NodeEventListener listener) {
            }
        };
    }
}
