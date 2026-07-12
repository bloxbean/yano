package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void resetRestAssured() {
        // Clear any static request-spec (body/content-type) that could leak
        // into a later method and change routing (RestAssured + QuarkusTest)
        io.restassured.RestAssured.reset();
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
    void restrictedKey_trailingSlash_cannotBypassTopicCheck() {
        // URI variant that still routes to submit() must not skip the topic ACL
        given()
                .header("X-API-Key", "restricted-key")
                .contentType("application/json")
                .body("{\"topic\":\"secrets\",\"body\":\"hello\"}")
                .when().post("/api/v1/app-chain/messages/")
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

    @Test
    void restrictedKey_cannotClaimEffects() {
        // Submit-only key must NOT reach the state-changing effect ops
        // (claim/report/requeue/cancel) — 403 before the 503 a full key gets
        // (ADR-010 F12 / final security review). Claim has a clean JSON body.
        given()
                .header("X-API-Key", "restricted-key")
                .contentType("application/json")
                .body("{\"executorId\":\"w\"}")
                .when().post("/api/v1/app-chain/effects/claim")
                .then()
                .statusCode(403);
    }

    @Test
    void restrictedKey_cannotCallAdminOps() {
        // Pre-existing admin ops are now also closed to submit-only keys
        given()
                .header("X-API-Key", "restricted-key")
                .contentType("application/json")
                .body("{\"height\":1,\"ordinal\":0}")
                .when().post("/api/v1/app-chain/effects/1/0/report")
                .then()
                .statusCode(403);
    }

    @Test
    void fullKey_canClaimEffects() {
        given()
                .header("X-API-Key", "full-access-key")
                .contentType("application/json")
                .body("{\"executorId\":\"w\"}")
                .when().post("/api/v1/app-chain/effects/claim")
                .then()
                .statusCode(503); // past auth; chain disabled downstream
    }
}
