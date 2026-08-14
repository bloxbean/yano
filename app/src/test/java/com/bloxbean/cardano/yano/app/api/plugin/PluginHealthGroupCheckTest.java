package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginContributionRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailure;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsTotals;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluginHealthGroupCheckTest {

    private static final String FINGERPRINT = "sha256:" + "d".repeat(64);

    @Test
    void degradedGroupReadsOneSnapshotAndBoundsFailingBundleIds() {
        List<PluginBundleRuntimeInfo> bundles = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            String id = "com.example.plugin" + i;
            PluginContributionRuntimeInfo contribution =
                    new PluginContributionRuntimeInfo(
                            "node-plugin", id, PluginTrustTier.REQUIRED,
                            true, PluginLifecycleState.ACTIVE,
                            PluginHealthStatus.DOWN, PluginFailure.none(),
                            false, List.of());
            bundles.add(new PluginBundleRuntimeInfo(
                    id,
                    PluginLifecycleState.ACTIVE,
                    PluginHealthStatus.DOWN,
                    PluginFailure.none(), false, 10, 0, 0, List.of(),
                    List.of(contribution)));
        }
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 3, 4,
                new PluginOperationsTotals(17, 17, 17, 0, 0,
                        17, 17, 17, 0, 0),
                bundles, List.of());
        AtomicInteger reads = new AtomicInteger();
        PluginHealthGroupCheck check = new PluginHealthGroupCheck();
        check.operations = () -> {
            reads.incrementAndGet();
            return snapshot;
        };

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals(1, reads.get());
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals(17L, ((Number) data.get("failingBundleCount")).longValue());
        assertEquals(16, data.keySet().stream()
                .filter(key -> key.matches("failingBundle\\.\\d+"))
                .count());
    }

    @Test
    void snapshotFailureUsesStableCodeWithoutExceptionText() {
        String sentinel = "secret-plugin-snapshot-error";
        PluginHealthGroupCheck check = new PluginHealthGroupCheck();
        check.operations = () -> {
            throw new IllegalStateException(sentinel);
        };

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals("OPERATIONS_SNAPSHOT_UNAVAILABLE", data.get("error"));
        assertFalse(data.toString().contains(sentinel));
    }

    @Test
    void aggregateStaleSourceMakesTheOperatorGroupDownWithoutChangingReadiness() {
        PluginBundleRuntimeInfo bundle = new PluginBundleRuntimeInfo(
                "com.example.plugin", PluginLifecycleState.ACTIVE,
                PluginHealthStatus.DEGRADED,
                PluginFailure.none(), true, 1, 0, 0, List.of(),
                List.of(new PluginContributionRuntimeInfo(
                        "metrics", "com.example.plugin",
                        PluginTrustTier.AUXILIARY_LOCAL, true,
                        PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                        PluginFailure.none(), true, List.of())));
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 2,
                new PluginOperationsTotals(1, 1, 1, 1, 0, 1, 1, 1, 1, 0),
                List.of(bundle), List.of());
        PluginHealthGroupCheck check = new PluginHealthGroupCheck();
        check.operations = () -> snapshot;

        assertEquals(HealthCheckResponse.Status.DOWN, check.call().getStatus());
    }

    @Test
    void nonSelectedBundleCannotPoisonOperatorHealth() {
        PluginBundleRuntimeInfo denied = new PluginBundleRuntimeInfo(
                "com.example.denied", PluginLifecycleState.NOT_SELECTED,
                PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                false, 1, 0, 0, List.of(), List.of());
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 2,
                new PluginOperationsTotals(1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(denied), List.of());
        PluginHealthGroupCheck check = new PluginHealthGroupCheck();
        check.operations = () -> snapshot;

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(0L, ((Number) response.getData().orElseThrow()
                .get("failingBundleCount")).longValue());
    }

    @Test
    void groupIsNotRegisteredAsReadinessOrLiveness() {
        assertNull(PluginHealthGroupCheck.class.getAnnotation(Readiness.class));
        assertNull(PluginHealthGroupCheck.class.getAnnotation(Liveness.class));
    }
}
