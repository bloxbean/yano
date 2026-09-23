package org.yanoproject.app.api.appchain;

import org.yanoproject.api.appchain.AppChainGateway;
import org.yanoproject.api.appchain.AppSubmissionRejectedException;
import org.yanoproject.api.appchain.PoolFullException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppChainSubmissionResourceTest {
    @Test
    void exposesOnlySanitizedAdmissionCode() {
        assertRejected("EVENT_PAYLOAD_TOO_LARGE", "EVENT_PAYLOAD_TOO_LARGE");
        assertRejected("private payload / token=secret", "APPLICATION_REJECTED");
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
