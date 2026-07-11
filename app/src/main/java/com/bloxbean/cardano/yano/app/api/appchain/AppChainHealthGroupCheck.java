package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import io.smallrye.health.api.HealthGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;

import java.util.Map;

/**
 * Operational app-chain health group (ADR app-layer/008.1 I1.8), exposed at
 * {@code /q/health/group/appchain}. Deliberately NOT part of readiness —
 * readiness stays "subsystem running" (a two-node bootstrap would deadlock on
 * peer connectivity). This group reports degradation an operator should alert
 * on: stall, anchor errors, sink delivery errors, paused submissions.
 */
@HealthGroup("appchain")
@ApplicationScoped
public class AppChainHealthGroupCheck implements HealthCheck {

    @Inject
    AppChainGateways appChainGateways;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("app-chain");
        boolean up = true;
        try {
            var gateways = appChainGateways.all();
            if (gateways.isEmpty()) {
                return builder.up().withData("chains", 0).build();
            }
            for (AppChainGateway gateway : gateways) {
                Map<String, Object> status = gateway.status();
                String chain = gateway.chainId();
                boolean stalled = Boolean.TRUE.equals(status.get("stalled"));
                boolean paused = Boolean.TRUE.equals(status.get("submissionsPaused"));
                String anchorError = nestedString(status.get("anchor"), "lastError");
                String sinkError = firstSinkError(status.get("sinks"));

                if (stalled) {
                    up = false;
                    builder.withData(chain + ".stalled", true);
                }
                if (paused) {
                    up = false;
                    builder.withData(chain + ".submissionsPaused", true);
                }
                if (anchorError != null) {
                    up = false;
                    builder.withData(chain + ".anchorError", anchorError);
                }
                if (sinkError != null) {
                    up = false;
                    builder.withData(chain + ".sinkError", sinkError);
                }
                builder.withData(chain + ".tipHeight", gateway.tipHeight());
            }
        } catch (Exception e) {
            return builder.down().withData("error", e.toString()).build();
        }
        return up ? builder.up().build() : builder.down().build();
    }

    private static String nestedString(Object map, String key) {
        if (map instanceof Map<?, ?> m && m.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static String firstSinkError(Object sinks) {
        if (sinks instanceof Map<?, ?> sinkMap) {
            for (Object value : sinkMap.values()) {
                String error = nestedString(value, "lastError");
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }
}
