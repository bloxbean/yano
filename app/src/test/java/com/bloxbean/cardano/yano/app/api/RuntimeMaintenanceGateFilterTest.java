package com.bloxbean.cardano.yano.app.api;

import org.junit.jupiter.api.Test;

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
}
