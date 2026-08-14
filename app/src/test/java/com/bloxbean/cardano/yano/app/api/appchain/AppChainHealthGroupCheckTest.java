package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppChainHealthGroupCheckTest {

    @Test
    void pluginControlledErrorsAreReducedToHostBooleans() {
        String sentinel = "secret-plugin-error-sentinel";
        AppChainGateway gateway = gateway(() -> Map.of(
                "anchor", Map.of("lastError", sentinel),
                "sinks", Map.of("sink-a", Map.of("lastError", sentinel))));

        AppChainHealthGroupCheck check = new AppChainHealthGroupCheck();
        check.appChainGateways = gateways(gateway);

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals(true, data.get("chain-a.anchorError"));
        assertEquals(true, data.get("chain-a.sinkError"));
        assertFalse(data.toString().contains(sentinel));
    }

    @Test
    void statusFailuresExposeOnlyAStableHostCode() {
        String sentinel = "secret-status-exception-sentinel";
        AppChainGateway gateway = gateway(() -> {
            throw new IllegalStateException(sentinel);
        });

        AppChainHealthGroupCheck check = new AppChainHealthGroupCheck();
        check.appChainGateways = gateways(gateway);

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals("STATUS_UNAVAILABLE", data.get("error"));
        assertTrue(data.values().stream().noneMatch(value -> String.valueOf(value).contains(sentinel)));
    }

    @Test
    void scheduledProfileWithoutLocalCatalogEntryIsOperationallyDown() {
        AppChainGateway gateway = gateway(() -> Map.of(
                "stateMachineStatus", Map.of(
                        "mode", "governed",
                        "proposalStatus", "SCHEDULED",
                        "locallyReady", false)));
        AppChainHealthGroupCheck check = new AppChainHealthGroupCheck();
        check.appChainGateways = gateways(gateway);

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals(true, response.getData().orElseThrow()
                .get("chain-a.scheduledProfileMissing"));
    }

    private static AppChainGateways gateways(AppChainGateway gateway) {
        return new AppChainGateways() {
            @Override
            public Optional<AppChainGateway> byId(String chainId) {
                return Optional.of(gateway).filter(item -> item.chainId().equals(chainId));
            }

            @Override
            public List<AppChainGateway> all() {
                return List.of(gateway);
            }
        };
    }

    private static AppChainGateway gateway(Supplier<Map<String, Object>> status) {
        return (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "tipHeight" -> 12L;
                    case "status" -> status.get();
                    case "toString" -> "gateway-probe";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
