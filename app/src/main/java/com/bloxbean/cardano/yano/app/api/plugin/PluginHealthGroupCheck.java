package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailureCode;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsView;
import io.smallrye.health.api.HealthGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;

import java.util.List;

/** Cache-only plugin operator health; deliberately not liveness or readiness. */
@HealthGroup("plugins")
@ApplicationScoped
public class PluginHealthGroupCheck implements HealthCheck {

    private static final int MAX_FAILING_BUNDLE_IDS = 16;

    @Inject
    PluginOperationsView operations;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("plugins");
        try {
            PluginOperationsSnapshot snapshot = operations.snapshot();
            List<PluginBundleRuntimeInfo> failing = snapshot.bundles().stream()
                    .filter(bundle -> bundle.lifecycle()
                            != PluginLifecycleState.NOT_SELECTED)
                    .filter(PluginHealthGroupCheck::operatorFailure)
                    .toList();
            boolean down = !failing.isEmpty();

            builder.withData("catalogFingerprint", snapshot.catalogFingerprint())
                    .withData("generation", snapshot.generation())
                    .withData("bundles", snapshot.totals().discoveredBundles())
                    .withData("selectedBundles", snapshot.totals().selectedBundles())
                    .withData("activeBundles", snapshot.totals().activeBundles())
                    .withData("degradedBundles", snapshot.totals().degradedBundles())
                    .withData("failedBundles", snapshot.totals().failedBundles())
                    .withData("staleSources", snapshot.totals().staleSources())
                    .withData("failingBundleCount", failing.size());
            for (int i = 0; i < Math.min(failing.size(), MAX_FAILING_BUNDLE_IDS); i++) {
                builder.withData("failingBundle." + i, failing.get(i).id());
            }
            return down ? builder.down().build() : builder.up().build();
        } catch (RuntimeException snapshotUnavailable) {
            return builder.down()
                    .withData("error", "OPERATIONS_SNAPSHOT_UNAVAILABLE")
                    .build();
        }
    }

    private static boolean operatorFailure(PluginBundleRuntimeInfo bundle) {
        return bundle.lifecycle() == PluginLifecycleState.FAILED
                || bundle.health() == PluginHealthStatus.DEGRADED
                || bundle.health() == PluginHealthStatus.DOWN
                || bundle.failure().code() != PluginFailureCode.NONE
                || bundle.metricsStale()
                || bundle.contributions().stream().anyMatch(contribution -> contribution.stale()
                        || contribution.lifecycle() == PluginLifecycleState.FAILED
                        || contribution.health() == PluginHealthStatus.DEGRADED
                        || contribution.health() == PluginHealthStatus.DOWN
                        || contribution.failure().code() != PluginFailureCode.NONE
                        || contribution.instances().stream().anyMatch(instance -> instance.stale()
                                || instance.lifecycle() == PluginLifecycleState.FAILED
                                || instance.health() == PluginHealthStatus.DEGRADED
                                || instance.health() == PluginHealthStatus.DOWN
                                || instance.failure().code() != PluginFailureCode.NONE));
    }
}
