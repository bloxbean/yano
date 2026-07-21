package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppChainQueryResourceTest {

    @Test
    void strictEnvelopeRejectsUnknownFieldsAndScalarCoercion() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"paramsHex\":\"00\",\"unexpected\":true}",
                AppChainResource.ChainScopedResource.QueryRequest.class));
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"paramsHex\":1234}",
                AppChainResource.ChainScopedResource.QueryRequest.class));

        var request = mapper.readValue("{\"paramsHex\":\"00ff\"}",
                AppChainResource.ChainScopedResource.QueryRequest.class);
        assertEquals("00ff", request.paramsHex());
    }

    @Test
    void canonicalInputAndOutputHexAreLowercaseAndDefensivelyMapped() {
        AtomicReference<String> seenPath = new AtomicReference<>();
        AtomicReference<byte[]> seenParams = new AtomicReference<>();
        AppChainGateway gateway = gateway((path, params) -> {
            seenPath.set(path);
            seenParams.set(params.clone());
            byte[] root = new byte[32];
            java.util.Arrays.fill(root, (byte) 0xAB);
            return new AppQueryResult("chain-a", "machine-a", 7, root,
                    new byte[]{0x0F, (byte) 0xFF});
        });
        var resource = new AppChainResource.ChainScopedResource(gateway);

        Response response = resource.query("by-id/item_1",
                new AppChainResource.ChainScopedResource.QueryRequest("00ff"));

        assertEquals(200, response.getStatus());
        assertEquals("by-id/item_1", seenPath.get());
        assertArrayEquals(new byte[]{0, (byte) 0xFF}, seenParams.get());
        Map<?, ?> entity = (Map<?, ?>) response.getEntity();
        assertEquals("abababababababababababababababababababababababababababababababab",
                entity.get("stateRoot"));
        assertEquals("0fff", entity.get("payloadHex"));
    }

    @Test
    void invalidOrOversizedInputNeverInvokesGateway() {
        AtomicInteger calls = new AtomicInteger();
        var resource = new AppChainResource.ChainScopedResource(gateway((path, params) -> {
            calls.incrementAndGet();
            return result();
        }));

        assertEquals(400, resource.query("valid",
                new AppChainResource.ChainScopedResource.QueryRequest("A0")).getStatus());
        assertEquals(400, resource.query("valid",
                new AppChainResource.ChainScopedResource.QueryRequest("abc")).getStatus());
        assertEquals(413, resource.query("valid",
                new AppChainResource.ChainScopedResource.QueryRequest(
                        "aa".repeat(64 * 1024 + 1))).getStatus());
        assertEquals(400, resource.query("encoded%2Falias",
                new AppChainResource.ChainScopedResource.QueryRequest("")).getStatus());
        assertEquals(413, resource.query("a".repeat(257),
                new AppChainResource.ChainScopedResource.QueryRequest("")).getStatus());
        assertEquals(0, calls.get());
    }

    @Test
    void omittedParamsBecomeEmptyBytes() {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        var resource = new AppChainResource.ChainScopedResource(gateway((path, params) -> {
            seen.set(params.clone());
            return result();
        }));

        assertEquals(200, resource.query("status", null).getStatus());
        assertArrayEquals(new byte[0], seen.get());
    }

    @Test
    void typedFailuresHaveStableStatusesAndFailedMessageIsRedacted() {
        Map<AppQueryException.Code, Integer> expected = new LinkedHashMap<>();
        expected.put(AppQueryException.Code.INVALID_REQUEST, 400);
        expected.put(AppQueryException.Code.REQUEST_TOO_LARGE, 413);
        expected.put(AppQueryException.Code.UNSUPPORTED, 404);
        expected.put(AppQueryException.Code.BUSY, 429);
        expected.put(AppQueryException.Code.RESULT_TOO_LARGE, 502);
        expected.put(AppQueryException.Code.UNAVAILABLE, 503);
        expected.put(AppQueryException.Code.TIMEOUT, 504);
        expected.put(AppQueryException.Code.FAILED, 500);

        for (Map.Entry<AppQueryException.Code, Integer> entry : expected.entrySet()) {
            String secret = "plugin-secret-" + entry.getKey();
            var resource = new AppChainResource.ChainScopedResource(gateway((path, params) -> {
                throw new AppQueryException(entry.getKey(), secret);
            }));

            Response response = resource.query("status",
                    new AppChainResource.ChainScopedResource.QueryRequest(""));

            assertEquals(entry.getValue(), response.getStatus(), entry.getKey().name());
            Map<?, ?> entity = (Map<?, ?>) response.getEntity();
            assertEquals(entry.getKey().name(), entity.get("code"));
            if (entry.getKey() == AppQueryException.Code.FAILED) {
                assertFalse(entity.toString().contains(secret));
                assertEquals("Query execution failed", entity.get("error"));
            }
        }
    }

    private static AppQueryResult result() {
        return new AppQueryResult("chain-a", "machine-a", 0, new byte[32], new byte[0]);
    }

    private static AppChainGateway gateway(Query query) {
        return (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("query")) {
                        return query.apply((String) args[0], (byte[]) args[1]);
                    }
                    if (method.getName().equals("toString")) {
                        return "query-test-gateway";
                    }
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) {
                        return null;
                    }
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == char.class) {
                        return '\0';
                    }
                    return 0;
                });
    }

    @FunctionalInterface
    private interface Query {
        AppQueryResult apply(String path, byte[] params);
    }
}
