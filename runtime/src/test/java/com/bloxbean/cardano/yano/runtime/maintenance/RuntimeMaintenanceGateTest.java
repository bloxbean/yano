package com.bloxbean.cardano.yano.runtime.maintenance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMaintenanceGateTest {
    @Test
    void maintenanceWaitsForActiveReaders() throws Exception {
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();
        var executor = Executors.newSingleThreadExecutor();

        try (var read = gate.enterRead("query")) {
            var maintenance = executor.submit(() -> {
                try (var lease = gate.enterMaintenance("restore")) {
                    return gate.isMaintenanceActive() && "restore".equals(lease.reason());
                }
            });

            Thread.sleep(100);
            assertThat(maintenance).isNotDone();
            read.close();

            assertThat(maintenance.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readersWaitForActiveMaintenance() throws Exception {
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();
        var executor = Executors.newSingleThreadExecutor();

        try (var maintenance = gate.enterMaintenance("restore")) {
            var reader = executor.submit(() -> {
                try (var ignored = gate.enterRead("query")) {
                    return true;
                }
            });

            Thread.sleep(100);
            assertThat(reader).isNotDone();
            maintenance.close();

            assertThat(reader.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void maintenanceDegradationCanBeMarkedAndCleared() {
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();

        gate.markDegraded("snapshot restore", "resume failed", new IllegalStateException("server stuck"));

        assertThat(gate.isDegraded()).isTrue();
        assertThat(gate.degradation()).isNotNull();
        assertThat(gate.degradation().operation()).isEqualTo("snapshot restore");
        assertThat(gate.degradation().message()).isEqualTo("resume failed");
        assertThat(gate.degradation().cause()).contains("server stuck");
        assertThat(gate.degradation().timestampMillis()).isPositive();

        gate.clearDegraded();

        assertThat(gate.isDegraded()).isFalse();
        assertThat(gate.degradation()).isNull();
    }

    @Test
    void maintenanceLeaseCanMarkAndClearDegradation() {
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();

        try (var maintenance = gate.enterMaintenance("restore")) {
            maintenance.markDegraded("runtime paused", null);
            assertThat(gate.degradation().operation()).isEqualTo("restore");

            maintenance.clearDegraded();
            assertThat(gate.degradation()).isNull();
        }
    }

    @Test
    void maintenanceLeaseOnlyClearsMatchingOperationDegradation() {
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();
        gate.markDegraded("rollback", "rollback failed", null);

        try (var maintenance = gate.enterMaintenance("restore")) {
            maintenance.clearDegraded();
        }

        assertThat(gate.degradation()).isNotNull();
        assertThat(gate.degradation().operation()).isEqualTo("rollback");

        gate.clearDegradedForOperation("rollback");
        assertThat(gate.degradation()).isNull();
    }

    @Test
    void nestedMaintenanceRestoresOuterReason() {
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();

        try (var outer = gate.enterMaintenance("outer")) {
            assertThat(gate.activeReason()).isEqualTo("outer");
            try (var inner = gate.enterMaintenance("inner")) {
                assertThat(gate.activeReason()).isEqualTo("inner");
            }
            assertThat(gate.activeReason()).isEqualTo("outer");
        }

        assertThat(gate.activeReason()).isNull();
    }
}
