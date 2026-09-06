package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.PoolFullException;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AppChainObservationResourceTest {
    @Test
    void queueReceiptDoesNotClaimDurabilityOrFinality() {
        var resource = resource(bytes -> {
            assertArrayEquals(new byte[]{1, 2}, bytes);
            return "report-digest";
        });
        try (Response response = resource.observationReport(new ByteArrayInputStream(new byte[]{1, 2}))) {
            assertEquals(202, response.getStatus());
            assertEquals(Map.of("reportDigest", "report-digest", "chainId", "chain", "status", "QUEUED"),
                    response.getEntity());
        }
    }

    @Test
    void boundsAndFailuresHaveExplicitHttpOutcomes() {
        AtomicInteger calls = new AtomicInteger();
        var resource = resource(bytes -> { calls.incrementAndGet(); return "ignored"; });
        try (Response response = resource.observationReport(new ByteArrayInputStream(
                new byte[ObservationReport.MAX_ENCODED_BYTES + 1]))) {
            assertEquals(413, response.getStatus());
            assertEquals(0, calls.get());
        }
        for (RuntimeException failure : new RuntimeException[]{new PoolFullException("busy"),
                new IllegalArgumentException("malformed"), new IllegalStateException("stopped")}) {
            var failing = resource(bytes -> { throw failure; });
            try (Response response = failing.observationReport(new ByteArrayInputStream(new byte[]{1}))) {
                assertEquals(failure instanceof PoolFullException ? 429
                        : failure instanceof IllegalArgumentException ? 400 : 503, response.getStatus());
            }
        }
    }

    private static AppChainResource.ChainScopedResource resource(Function<byte[], String> ingress) {
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "chainId" -> "chain";
                    case "submitObservationReport" -> ingress.apply((byte[]) args[0]);
                    default -> throw new AssertionError("Unexpected gateway operation: " + method.getName());
                });
        return new AppChainResource.ChainScopedResource(gateway);
    }
}
