package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * ADR-006 E4.1: opt-in API-key auth for the /app-chain REST surface,
 * including per-key topic restrictions for submissions. The app chain itself
 * is disabled here — anything that gets PAST the filter returns 503, which
 * distinguishes filter rejections (401/403) from downstream handling.
 */
@QuarkusTest
@TestProfile(AppChainApiKeyFilterTest.AuthEnabledProfile.class)
class AppChainApiKeyFilterTest {

    public static class AuthEnabledProfile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new java.util.HashMap<>(super.getConfigOverrides());
            overrides.put("yano.app-chain.api.auth.enabled", "true");
            overrides.put("yano.app-chain.api.keys", "full-access-key,restricted-key=orders|invoices");
            return overrides;
        }
    }

    @Test
    void missingKey_isRejected() {
        given()
                .when().get("/api/v1/app-chain/status")
                .then()
                .statusCode(401);
    }

    @Test
    void wrongKey_isRejected() {
        given()
                .header("X-API-Key", "nope")
                .when().get("/api/v1/app-chain/status")
                .then()
                .statusCode(401);
    }

    @Test
    void validKey_passesFilter() {
        given()
                .header("X-API-Key", "full-access-key")
                .when().get("/api/v1/app-chain/status")
                .then()
                .statusCode(503); // app chain disabled — request got PAST auth
    }

    @Test
    void restrictedKey_allowedTopic_passesFilter() {
        given()
                .header("X-API-Key", "restricted-key")
                .contentType("application/json")
                .body("{\"topic\":\"orders\",\"body\":\"hello\"}")
                .when().post("/api/v1/app-chain/messages")
                .then()
                .statusCode(503); // past auth; chain disabled downstream
    }

    @Test
    void restrictedKey_disallowedTopic_isForbidden() {
        given()
                .header("X-API-Key", "restricted-key")
                .contentType("application/json")
                .body("{\"topic\":\"secrets\",\"body\":\"hello\"}")
                .when().post("/api/v1/app-chain/messages")
                .then()
                .statusCode(403);
    }

    @Test
    void restrictedKey_readsAreUnrestricted() {
        given()
                .header("X-API-Key", "restricted-key")
                .when().get("/api/v1/app-chain/messages")
                .then()
                .statusCode(503); // past auth
    }

    @Test
    void otherEndpoints_remainOpen() {
        given()
                .when().get("/api/v1/node/status")
                .then()
                .statusCode(200);
    }
}
