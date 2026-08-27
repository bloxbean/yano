package com.bloxbean.cardano.yano.app.api;

import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeMaintenanceGateFilterTest {
    @Test
    void maintenanceEndpointsBypassReadLeaseWithTrailingSlash() {
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/rollback/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/restore/checkpoint-1/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/snapshot/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "DELETE", "api/v1/devnet/snapshot/checkpoint-1/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/fund/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/time/advance/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/epochs/shift/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/epochs/catch-up/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/node/recover/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/node/start/"));
        assertTrue(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/node/stop/"));
    }

    @Test
    void nonMaintenanceEndpointsUseReadLease() {
        assertFalse(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "GET", "api/v1/devnet/rollback/"));
        assertFalse(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "GET", "api/v1/devnet/snapshot/"));
        assertFalse(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "POST", "api/v1/devnet/snapshots/"));
        assertFalse(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "GET", "api/v1/node/start/"));
        assertFalse(RuntimeMaintenanceGateFilter.isExclusiveMaintenanceEndpoint(
                "GET", "api/v1/node/stop/"));
    }

    @Test
    void requestDestructionReleasesLeaseWhenResponseFilteringIsSkipped() throws Exception {
        var gate = new RuntimeMaintenanceGate();
        var requestLease = new RuntimeMaintenanceReadLease();
        requestLease.open(gate, "GET api/v1/failing-request");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempting = new CountDownLatch(1);
            var maintenance = executor.submit(() -> {
                attempting.countDown();
                try (var ignored = gate.enterMaintenance("node stop")) {
                    return true;
                }
            });
            assertThat(attempting.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maintenance).isNotDone();

            requestLease.close();

            assertThat(maintenance.get(1, TimeUnit.SECONDS)).isTrue();
            requestLease.close();
        }
    }
}
