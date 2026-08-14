package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiGateway;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRouteInfo;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PluginDomainResourceTest {

    @Test
    void validJsonAndOctetsAreReturnedAsOpaqueBytes() {
        StubGateway jsonGateway = new StubGateway(DomainApiAccess.READ,
                new DomainApiResponse(200, DomainApiMediaType.JSON,
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        Response json = resource(jsonGateway).get("bundle", "items/one", uri(Map.of()));

        assertEquals(200, json.getStatus());
        assertEquals("application/json", json.getMediaType().toString());
        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                (byte[]) json.getEntity());

        StubGateway octetGateway = new StubGateway(DomainApiAccess.READ,
                new DomainApiResponse(200, DomainApiMediaType.OCTET_STREAM,
                        new byte[]{1, 2, 3}));
        Response octets = resource(octetGateway).get("bundle", "items/one", uri(Map.of()));
        assertEquals(200, octets.getStatus());
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) octets.getEntity());
    }

    @Test
    void invalidJsonAndUnexpectedFailuresAreRedacted() {
        StubGateway invalidJson = new StubGateway(DomainApiAccess.READ,
                new DomainApiResponse(200, DomainApiMediaType.JSON,
                        "{\"first\":true} {\"second\":true}"
                                .getBytes(StandardCharsets.UTF_8)));
        Response invalid = resource(invalidJson).get("bundle", "items/one", uri(Map.of()));
        assertError(invalid, 500, "FAILED", "Domain API execution failed");

        String secret = "plugin-secret-value";
        StubGateway unexpected = new StubGateway(DomainApiAccess.READ, null);
        unexpected.failure = new IllegalStateException(secret);
        Response failed = resource(unexpected).get("bundle", "items/one", uri(Map.of()));
        assertError(failed, 500, "FAILED", "Domain API execution failed");
        assertFalse(failed.getEntity().toString().contains(secret));
    }

    @Test
    void internalAndUnknownRoutesAreAlwaysAbsent() {
        StubGateway internal = new StubGateway(DomainApiAccess.INTERNAL,
                new DomainApiResponse(200, DomainApiMediaType.JSON, "{}".getBytes()));
        Response hidden = resource(internal).get("bundle", "internal", uri(Map.of()));
        assertEquals(404, hidden.getStatus());
        assertEquals(0, internal.dispatchCalls.get());

        StubGateway unknown = new StubGateway(null,
                new DomainApiResponse(200, DomainApiMediaType.JSON, "{}".getBytes()));
        assertEquals(404, resource(unknown).get("bundle", "missing", uri(Map.of())).getStatus());
        assertEquals(0, unknown.dispatchCalls.get());
    }

    @Test
    void malformedPathAndOversizedPostAreRejectedBeforeLookup() {
        StubGateway gateway = new StubGateway(DomainApiAccess.READ,
                new DomainApiResponse(200, DomainApiMediaType.JSON, "{}".getBytes()));
        PluginDomainResource resource = resource(gateway);

        assertEquals(400, resource.get("bundle", "encoded%2Falias", uri(Map.of())).getStatus());
        assertEquals(413, resource.post("bundle", "submit", uri(Map.of()),
                new byte[64 * 1024 + 1]).getStatus());
        assertEquals(0, gateway.accessCalls.get());
        assertEquals(0, gateway.dispatchCalls.get());
    }

    @Test
    void typedFailuresHaveStableStatuses() {
        Map<DomainApiException.Code, Integer> expected = new LinkedHashMap<>();
        expected.put(DomainApiException.Code.INVALID_REQUEST, 400);
        expected.put(DomainApiException.Code.NOT_FOUND, 404);
        expected.put(DomainApiException.Code.BUSY, 429);
        expected.put(DomainApiException.Code.RESULT_TOO_LARGE, 502);
        expected.put(DomainApiException.Code.UNAVAILABLE, 503);
        expected.put(DomainApiException.Code.TIMEOUT, 504);
        expected.put(DomainApiException.Code.FAILED, 500);

        for (Map.Entry<DomainApiException.Code, Integer> entry : expected.entrySet()) {
            String detail = "detail-" + entry.getKey();
            StubGateway gateway = new StubGateway(DomainApiAccess.READ, null);
            gateway.failure = new DomainApiException(entry.getKey(), detail);

            Response response = resource(gateway).get("bundle", "status", uri(Map.of()));

            assertEquals(entry.getValue(), response.getStatus(), entry.getKey().name());
            Map<?, ?> entity = (Map<?, ?>) response.getEntity();
            assertEquals(entry.getKey().name(), entity.get("code"));
            if (entry.getKey() == DomainApiException.Code.FAILED) {
                assertEquals("Domain API execution failed", entity.get("error"));
                assertFalse(entity.toString().contains(detail));
            }
        }
    }

    private static PluginDomainResource resource(DomainApiGateway gateway) {
        PluginDomainResource resource = new PluginDomainResource();
        resource.domainApis = gateway;
        resource.objectMapper = new ObjectMapper();
        return resource;
    }

    private static UriInfo uri(Map<String, List<String>> query) {
        MultivaluedHashMap<String, String> values = new MultivaluedHashMap<>();
        query.forEach(values::put);
        return (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(),
                new Class<?>[]{UriInfo.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getQueryParameters")) {
                        return values;
                    }
                    if (method.getName().equals("toString")) {
                        return "test-uri";
                    }
                    return null;
                });
    }

    private static void assertError(Response response, int status, String code, String message) {
        assertEquals(status, response.getStatus());
        Map<?, ?> entity = (Map<?, ?>) response.getEntity();
        assertEquals(code, entity.get("code"));
        assertEquals(message, entity.get("error"));
    }

    private static final class StubGateway implements DomainApiGateway {
        private final DomainApiAccess access;
        private final DomainApiResponse response;
        private final AtomicInteger accessCalls = new AtomicInteger();
        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private RuntimeException failure;

        private StubGateway(DomainApiAccess access, DomainApiResponse response) {
            this.access = access;
            this.response = response;
        }

        @Override
        public List<DomainApiRouteInfo> routes() {
            return List.of();
        }

        @Override
        public Optional<DomainApiAccess> access(
                String bundleId, DomainHttpMethod method, String relativePath) {
            accessCalls.incrementAndGet();
            return Optional.ofNullable(access);
        }

        @Override
        public DomainApiResponse dispatch(
                String bundleId,
                DomainHttpMethod method,
                String relativePath,
                Map<String, List<String>> queryParameters,
                byte[] body) {
            dispatchCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
