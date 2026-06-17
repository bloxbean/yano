package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class YanoHealthCheck implements HealthCheck {

    @Inject
    NodeLifecycle nodeLifecycle;

    @Inject
    NodeKernel nodeKernel;

    @Override
    public HealthCheckResponse call() {
        try {
            if (nodeKernel != null) {
                boolean kernelHealthy = nodeKernel.health().stream()
                        .allMatch(health -> health.status() == SubsystemHealth.Status.UP);
                return kernelHealthy
                        ? HealthCheckResponse.up("yano")
                        : HealthCheckResponse.down("yano");
            }

            NodeStatus status = nodeLifecycle.getStatus();

            boolean isHealthy = status != null
                    && !status.isRuntimeDegraded()
                    && !status.isPeerRecoveryTerminal()
                    && (status.getStatusMessage() == null
                    || !status.getStatusMessage().toLowerCase().contains("error"));

            if (isHealthy) {
                return HealthCheckResponse.up("yano");
            } else {
                return HealthCheckResponse.down("yano");
            }
        } catch (Exception e) {
            return HealthCheckResponse.down("yano");
        }
    }
}
