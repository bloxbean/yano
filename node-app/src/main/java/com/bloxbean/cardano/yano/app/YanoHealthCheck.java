package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class YanoHealthCheck implements HealthCheck {

    @Inject
    NodeAPI nodeAPI;

    @Override
    public HealthCheckResponse call() {
        try {
            NodeStatus status = nodeAPI.getStatus();

            boolean isHealthy = status.getStatusMessage() == null ||
                    !status.getStatusMessage().toLowerCase().contains("error");

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
