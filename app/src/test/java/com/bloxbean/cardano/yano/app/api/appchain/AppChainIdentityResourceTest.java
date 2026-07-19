package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppChainIdentityResourceTest {
    private static final String CONSENSUS = "a".repeat(64);
    private static final String COMPOSITE = "b".repeat(64);
    private static final String CATALOG = "sha256:" + "c".repeat(64);
    private static final String RESOLVED = "d".repeat(64);
    private static final String RELEASE = "e".repeat(64);

    @Test
    void exposesOnlyRedactedRuntimeAndProjectIdentities() throws Exception {
        AppChainGateway gateway = gateway(Map.of(
                "consensusProfile", Map.of("digest", CONSENSUS, "maxBlockBytes", 1234),
                "stateMachineStatus", Map.of("activeProfileDigest", COMPOSITE),
                "memberKey", "private-runtime-detail"));
        var resource = new AppChainResource.ChainScopedResource(gateway,
                new AppChainResource.RuntimeIdentityContext(CATALOG, RESOLVED, RELEASE));

        try (Response response = resource.identity()) {
            assertEquals(200, response.getStatus());
            assertEquals(Map.of(
                    "schemaVersion", "v1",
                    "chainId", "orders",
                    "consensusProfileDigest", CONSENSUS,
                    "compositeProfileDigest", COMPOSITE,
                    "pluginCatalogFingerprint", CATALOG,
                    "resolvedConfigDigest", RESOLVED,
                    "releaseCatalogDigest", RELEASE,
                    "identityCoverage", "PROJECT_BOUND"), response.getEntity());
            assertFalse(response.getEntity().toString().contains("maxBlockBytes"));
            assertFalse(response.getEntity().toString().contains("memberKey"));
            assertFalse(response.getEntity().toString().contains("private-runtime-detail"));
        }

        Method identity = AppChainResource.ChainScopedResource.class.getMethod("identity");
        assertEquals(AppChainAccess.Level.PRIVILEGED,
                identity.getAnnotation(AppChainAccess.class).value());
    }

    @Test
    void omitsMalformedOrUnavailableDigests() {
        AppChainGateway gateway = gateway(Map.of(
                "consensusProfile", Map.of("digest", "not-a-digest")));
        var resource = new AppChainResource.ChainScopedResource(gateway,
                new AppChainResource.RuntimeIdentityContext("wrong", null, null));

        try (Response response = resource.identity()) {
            assertEquals(Map.of(
                    "schemaVersion", "v1",
                    "chainId", "orders",
                    "identityCoverage", "RUNTIME_ONLY"), response.getEntity());
        }
    }

    @Test
    void runtimeFailureDoesNotEchoInternalDetails() {
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> {
                    if ("status".equals(method.getName())) {
                        throw new IllegalStateException("secret-path=/private/node0");
                    }
                    return "chainId".equals(method.getName()) ? "orders" : null;
                });
        var resource = new AppChainResource.ChainScopedResource(gateway);

        try (Response response = resource.identity()) {
            assertEquals(503, response.getStatus());
            assertFalse(response.getEntity().toString().contains("secret-path"));
            assertEquals(Map.of("error", "App-chain identity is unavailable"),
                    response.getEntity());
        }
    }

    private static AppChainGateway gateway(Map<String, Object> status) {
        return (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "orders";
                    case "status" -> status;
                    case "toString" -> "identity-test-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }
}
