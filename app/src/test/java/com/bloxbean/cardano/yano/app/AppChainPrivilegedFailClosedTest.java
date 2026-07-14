package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** HTTP-level proof that disabling optional auth never publishes privileged operations. */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class AppChainPrivilegedFailClosedTest {

    @Test
    void privilegedHostOperationIsConfigurationUnavailableWhenAuthIsDisabled() {
        given()
                .contentType("application/json")
                .body("{\"executorId\":\"worker\"}")
                .when().post("/api/v1/app-chain/effects/claim")
                .then()
                .statusCode(503)
                .body("code", equalTo("AUTH_UNAVAILABLE"));
    }

    @Test
    void absentDomainRouteRemainsIndistinguishableFromInternalRoute() {
        given()
                .when().get("/api/v1/plugins/not-installed/status")
                .then()
                .statusCode(404);
    }

    @Test
    void domainGetEntityIsRejectedBeforeRouteLookup() {
        given()
                .contentType("application/octet-stream")
                .body(new byte[]{1})
                .when().get("/api/v1/plugins/not-installed/status")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }
}
