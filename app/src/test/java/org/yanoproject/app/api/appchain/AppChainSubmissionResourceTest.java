package org.yanoproject.app.api.appchain;

import org.yanoproject.api.appchain.AppChainGateway;
import org.yanoproject.api.appchain.AppSubmissionRejectedException;
import org.yanoproject.api.appchain.PoolFullException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppChainSubmissionResourceTest {
    @Test
    void exposesOnlySanitizedAdmissionCode() {
        assertRejected("EVENT_PAYLOAD_TOO_LARGE", "EVENT_PAYLOAD_TOO_LARGE");
        assertRejected("private payload / token=secret", "APPLICATION_REJECTED");
    }

    @Test
    void structuredDetailsAppearOnlyWhenAllowlistedDetailsSurvive() {
        var denied = new AppSubmissionRejectedException("ADMISSION_RULE_DENIED", Map.of("rule", "transfer-limit",
                "deny", "TRANSFER_LIMIT_EXCEEDED", "write", 2, "note", "never echoed"));
        try (Response response = resource(denied)
                .submit(new AppChainResource.ChainScopedResource.SubmitRequest("topic", "body", null))) {
            assertEquals(400, response.getStatus());
            assertEquals(Map.of("code", "ADMISSION_RULE_DENIED", "details", Map.of("rule", "transfer-limit",
                    "deny", "TRANSFER_LIMIT_EXCEEDED", "write", 2L)), response.getEntity());
            assertEquals(List.of("code", "details"), List.copyOf(((Map<?, ?>) response.getEntity()).keySet()));
        }
        // Hostile details are dropped; the body keeps its historical {code} shape.
        var hostile = new AppSubmissionRejectedException("ADMISSION_RULE_DENIED", Map.of("rule", "<script>",
                "deny", "private token"));
        try (Response response = resource(hostile)
                .submit(new AppChainResource.ChainScopedResource.SubmitRequest("topic", "body", null))) {
            assertEquals(Map.of("code", "ADMISSION_RULE_DENIED"), response.getEntity());
        }
    }

    @Test
    void poolBackpressureRemains429() {
        try (Response response = resource(new PoolFullException("busy"))
                .submit(new AppChainResource.ChainScopedResource.SubmitRequest("topic", "body", null))) {
            assertEquals(429, response.getStatus());
        }
    }

    private static void assertRejected(String pluginReason, String code) {
        try (Response response = resource(new AppSubmissionRejectedException(pluginReason))
                .submit(new AppChainResource.ChainScopedResource.SubmitRequest("topic", "body", null))) {
            assertEquals(400, response.getStatus());
            assertEquals(Map.of("code", code), response.getEntity());
        }
    }

    private static AppChainResource.ChainScopedResource resource(RuntimeException failure) {
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submit")) {
                        throw failure;
                    }
                    throw new AssertionError("Unexpected gateway operation: " + method.getName());
                });
        return new AppChainResource.ChainScopedResource(gateway);
    }
}
