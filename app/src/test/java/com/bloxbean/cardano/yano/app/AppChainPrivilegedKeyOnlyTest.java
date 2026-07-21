package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * HTTP-level proof that privileged routes remain protected when broad
 * READ/SUBMIT authentication is disabled.
 */
@QuarkusTest
@TestProfile(AppChainPrivilegedKeyOnlyTest.KeyOnlyProfile.class)
class AppChainPrivilegedKeyOnlyTest {

    static final String FULL_KEY = "key-only-full-access";

    public static class KeyOnlyProfile extends NoAutoStartTestProfile
            implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("yano.app-chain.api.auth.enabled", "false");
            overrides.put("yano.app-chain.api.keys", FULL_KEY);
            return overrides;
        }
    }

    @Test
    void readsAndSubmissionsRemainPublic() {
        given()
                .when().get("/api/v1/app-chain/status")
                .then().statusCode(503); // no chain: request passed the filter

        given()
                .contentType("application/json")
                .body("{\"topic\":\"orders\",\"body\":\"hello\"}")
                .when().post("/api/v1/app-chain/messages")
                .then().statusCode(503); // no chain: request passed the filter
    }

    @Test
    void privilegedOperationsStillRequireTheFullKey() {
        given()
                .contentType("application/json")
                .body("{\"executorId\":\"worker\"}")
                .when().post("/api/v1/app-chain/effects/claim")
                .then().statusCode(401);

        given()
                .header("X-API-Key", FULL_KEY)
                .contentType("application/json")
                .body("{\"executorId\":\"worker\"}")
                .when().post("/api/v1/app-chain/effects/claim")
                .then().statusCode(503); // no chain: authenticated past the filter
    }

    @Test
    void pluginOperationsStillRequireTheFullKey() {
        given()
                .when().get("/api/v1/plugin-operations")
                .then().statusCode(401);

        given()
                .header("X-API-Key", FULL_KEY)
                .when().get("/api/v1/plugin-operations")
                .then().statusCode(200);
    }
}
