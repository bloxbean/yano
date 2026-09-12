package org.yanoproject.app;

import org.yanoproject.api.NodeLifecycle;
import org.yanoproject.api.config.NodeConfig;
import org.yanoproject.api.listener.NodeEventListener;
import org.yanoproject.api.model.NodeStatus;
import org.yanoproject.app.archive.HistoryArchiveService;
import org.yanoproject.app.archive.ProjectionHistoryService;
import org.yanoproject.runtime.kernel.NodeKernel;
import org.yanoproject.runtime.kernel.Schedulers;
import org.yanoproject.runtime.kernel.ServiceRegistry;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.runtime.kernel.SubsystemHealth;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YanoHealthCheckTest {

    @Test
    void readinessIsDownWhenProjectionInitializationFailedEvenIfKernelIsHealthy() {
        YanoHealthCheck healthCheck = new YanoHealthCheck();
        healthCheck.projectionHistory = mock(ProjectionHistoryService.class);
        when(healthCheck.projectionHistory.hasInitializationFailure()).thenReturn(true);
        healthCheck.nodeKernel = kernel(SubsystemHealth.up("chain-storage"));

        try {
            assertEquals(HealthCheckResponse.Status.DOWN, healthCheck.call().getStatus());
        } finally {
            healthCheck.nodeKernel.close();
        }
    }

    @Test
    void readinessIsDownWhenHistoryReadFacadeInitializationFailedEvenIfKernelIsHealthy() {
        YanoHealthCheck healthCheck = new YanoHealthCheck();
        healthCheck.historyArchive = mock(HistoryArchiveService.class);
        when(healthCheck.historyArchive.hasInitializationFailure()).thenReturn(true);
        healthCheck.nodeKernel = kernel(SubsystemHealth.up("chain-storage"));

        try {
            assertEquals(HealthCheckResponse.Status.DOWN, healthCheck.call().getStatus());
        } finally {
            healthCheck.nodeKernel.close();
        }
    }

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

    @Test
    void readinessUsesKernelHealthWhenAvailable() {
        YanoHealthCheck healthCheck = new YanoHealthCheck();
        healthCheck.nodeLifecycle = lifecycle(NodeStatus.builder()
                .running(true)
                .statusMessage("Node is running")
                .build());
        healthCheck.nodeKernel = kernel(SubsystemHealth.degraded("chain-storage", "snapshot restore failed"));

        try {
            assertEquals(HealthCheckResponse.Status.DOWN, healthCheck.call().getStatus());
        } finally {
            healthCheck.nodeKernel.close();
        }
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

    private static NodeKernel kernel(SubsystemHealth health) {
        return new NodeKernel(
                List.of(new HealthOnlySubsystem(health)),
                new SubsystemContext(null, new Schedulers(), Map.of(), new ServiceRegistry()));
    }

    private record HealthOnlySubsystem(SubsystemHealth health) implements Subsystem {
        @Override
        public String name() {
            return health.name();
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public SubsystemHealth health() {
            return health;
        }
    }
}
