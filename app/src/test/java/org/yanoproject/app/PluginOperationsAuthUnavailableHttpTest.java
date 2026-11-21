package org.yanoproject.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Real-HTTP proof that plugin operations fail closed when no unscoped full key is configured. */
@QuarkusTest
@TestProfile(PluginOperationsAuthUnavailableHttpTest.ScopedOnlyProfile.class)
class PluginOperationsAuthUnavailableHttpTest {

    public static class ScopedOnlyProfile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("yano.app-chain.api.keys", "restricted-key=orders");
            return overrides;
        }
    }

    @Test
    void privilegedOperationsAreConfigurationUnavailable() {
        given().when().get("/api/v1/plugin-operations")
                .then().statusCode(503)
                .body("code", equalTo("AUTH_UNAVAILABLE"));
        given().header("X-API-Key", "restricted-key")
                .when().get("/api/v1/plugin-operations/bundles")
                .then().statusCode(503)
                .body("code", equalTo("AUTH_UNAVAILABLE"));
    }

    @Test
    void nonCanonicalPathIsRejectedBeforeTheKeyCheck() {
        given().when().get("/api/v1/plugin-operations/")
                .then().statusCode(404)
                .body("code", equalTo("NOT_FOUND"));
    }
}
