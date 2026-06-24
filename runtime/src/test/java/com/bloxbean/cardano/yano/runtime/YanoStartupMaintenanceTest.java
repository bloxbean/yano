package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yano.runtime.internal.RuntimeNode;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoStartupMaintenanceTest {
    @TempDir
    Path tempDir;

    @Test
    void failedStartupInMaintenancePhaseMarksRuntimeDegradedAndNotRunning() {
        YanoConfig config = YanoConfig.builder()
                .enableBootstrap(true)
                .enableClient(true)
                .enableServer(false)
                .enableBlockProducer(false)
                .useRocksDB(true)
                .rocksDBPath(tempDir.resolve("chainstate").toString())
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42)
                .build();
        RuntimeNode yano = new RuntimeNode(config);

        try {
            assertThatThrownBy(yano::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Bootstrap is enabled but no BootstrapDataProvider");

            assertThat(yano.isRunning()).isFalse();
            assertThat(yano.getStatus().isRuntimeDegraded()).isTrue();
            assertThat(yano.getStatus().getRuntimeDegradedOperation()).isEqualTo("node startup");
            assertThat(yano.getStatus().getRuntimeDegradedReason())
                    .contains("Node startup failed during storage/bootstrap/recovery");
        } finally {
            yano.close();
        }
    }

    @Test
    void directKernelCloseAfterFailedStartupClosesResourcesAndKeepsStatusInspectable() {
        YanoConfig config = YanoConfig.builder()
                .enableBootstrap(true)
                .enableClient(true)
                .enableServer(false)
                .enableBlockProducer(false)
                .useRocksDB(true)
                .rocksDBPath(tempDir.resolve("failed-kernel-close-chainstate").toString())
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42)
                .build();
        RuntimeNode yano = new RuntimeNode(config);

        assertThatThrownBy(yano::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bootstrap is enabled but no BootstrapDataProvider");

        yano.kernel().close();

        assertThat(yano.getStatus().isRuntimeDegraded()).isTrue();
        assertThat(yano.kernel().health()).anySatisfy(health -> {
            assertThat(health.name()).isEqualTo("chain-storage");
            assertThat(health.status()).isEqualTo(SubsystemHealth.Status.DOWN);
        });
    }

    @Test
    void directDevnetMutationWaitsForActiveMaintenance() throws Exception {
        YanoConfig config = YanoConfig.builder()
                .enableClient(false)
                .enableServer(false)
                .enableBlockProducer(false)
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42)
                .build();
        RuntimeNode yano = new RuntimeNode(config);
        try {
            assertMutationWaitsForMaintenance(yano,
                    () -> yano.devnetRuntime().orElseThrow().producerExtensions().advanceBySlots(1),
                    IllegalStateException.class);
            assertMutationWaitsForMaintenance(yano,
                    () -> yano.devnetRuntime().orElseThrow().snapshots().restore("snap"),
                    IllegalStateException.class);
        } finally {
            yano.close();
        }
    }

    @Test
    void directProducerControlWaitsForActiveMaintenance() throws Exception {
        YanoConfig config = YanoConfig.builder()
                .enableClient(false)
                .enableServer(false)
                .enableBlockProducer(false)
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42)
                .build();
        RuntimeNode yano = new RuntimeNode(config);

        try {
            assertMutationWaitsForMaintenance(yano, yano::startProducer, UnsupportedOperationException.class);
            assertMutationWaitsForMaintenance(yano, yano::resetProducerToChainTip, UnsupportedOperationException.class);
        } finally {
            yano.close();
        }
    }

    @Test
    void directNodeLifecycleWaitsForActiveMaintenance() throws Exception {
        YanoConfig config = YanoConfig.builder()
                .enableClient(false)
                .enableServer(false)
                .enableBlockProducer(false)
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42)
                .build();

        RuntimeNode yanoForStop = new RuntimeNode(config);
        try {
            assertMutationWaitsForMaintenance(yanoForStop, yanoForStop::stop);
        } finally {
            yanoForStop.close();
        }

        RuntimeNode yanoForClose = new RuntimeNode(config);
        try {
            assertMutationWaitsForMaintenance(yanoForClose, yanoForClose::close);
        } finally {
            yanoForClose.close();
        }
    }

    @Test
    void startedNodeLifecycleWaitsForActiveMaintenance() throws Exception {
        YanoConfig config = YanoConfig.builder()
                .enableClient(false)
                .enableServer(false)
                .enableBlockProducer(false)
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42)
                .build();

        RuntimeNode yanoForStop = new RuntimeNode(config);
        try {
            yanoForStop.start();
            assertMutationWaitsForMaintenance(yanoForStop, yanoForStop::stop);
        } finally {
            yanoForStop.close();
        }

        RuntimeNode yanoForClose = new RuntimeNode(config);
        try {
            yanoForClose.start();
            assertMutationWaitsForMaintenance(yanoForClose, yanoForClose::close);
        } finally {
            yanoForClose.close();
        }
    }

    @Test
    void successfulRuntimeMaintenanceClearsOnlyMatchingDegradedOperation() {
        YanoConfig config = YanoConfig.builder()
                .enableClient(false)
                .enableServer(false)
                .enableBlockProducer(false)
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42)
                .build();
        RuntimeNode yano = new RuntimeNode(config);

        try {
            yano.getMaintenanceGate().markDegraded("node stop", "stale node stop failure", null);
            assertThat(yano.getStatus().isRuntimeDegraded()).isTrue();

            yano.stop();

            assertThat(yano.getStatus().isRuntimeDegraded()).isFalse();

            yano.getMaintenanceGate().markDegraded("devnet time advance", "stale time advance failure", null);
            yano.stop();

            assertThat(yano.getStatus().isRuntimeDegraded()).isTrue();
            assertThat(yano.getStatus().getRuntimeDegradedOperation()).isEqualTo("devnet time advance");
        } finally {
            yano.close();
        }
    }

    private void assertMutationWaitsForMaintenance(RuntimeNode yano, Runnable operation,
                                                   Class<? extends Throwable> expectedCause) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var maintenance = yano.getMaintenanceGate().enterMaintenance("snapshot restore");

        try {
            var mutation = executor.submit(operation);
            Thread.sleep(100);

            assertThat(mutation.isDone()).isFalse();

            maintenance.close();
            assertThatThrownBy(() -> mutation.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(expectedCause);
        } finally {
            maintenance.close();
            executor.shutdownNow();
        }
    }

    private void assertMutationWaitsForMaintenance(RuntimeNode yano, Runnable operation) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var maintenance = yano.getMaintenanceGate().enterMaintenance("snapshot restore");

        try {
            var mutation = executor.submit(operation);
            Thread.sleep(100);

            assertThat(mutation.isDone()).isFalse();

            maintenance.close();
            mutation.get(2, TimeUnit.SECONDS);
        } finally {
            maintenance.close();
            executor.shutdownNow();
        }
    }
}
