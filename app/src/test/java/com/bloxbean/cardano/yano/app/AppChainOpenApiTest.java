package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * The OpenAPI document (what Swagger UI renders) must document the
 * chain-scoped app-chain surface and HIDE the legacy chain-less aliases:
 * on a multi-chain node the chain-less paths can only answer 400
 * ("N app chains are hosted — use /app-chain/chains/{chainId}/..."), so
 * surfacing them in Swagger UI misleads API users. They stay functional
 * for single-chain deployments — just undocumented.
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class AppChainOpenApiTest {

    @Test
    void chainScopedPaths_areDocumented() {
        given()
                .when().get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("/app-chain/chains/{chainId}/status"))
                .body(Matchers.containsString("/app-chain/chains/{chainId}/messages"))
                .body(Matchers.containsString("/app-chain/chains/{chainId}/evidence/{messageIdHex}"))
                .body(Matchers.containsString("/app-chain/chains/{chainId}/proof/{keyHex}"))
                .body(Matchers.containsString("/app-chain/chains"));
    }

    @Test
    void legacyChainlessAliases_areHidden() {
        String doc = given()
                .when().get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .extract().asString();
        // Path KEYS like "/api/v1/app-chain/status" must be gone; the
        // chain-scoped variants (same suffix after {chainId}) remain.
        org.junit.jupiter.api.Assertions.assertFalse(doc.contains("app-chain/status\""),
                "chain-less /app-chain/status should be hidden from OpenAPI");
        org.junit.jupiter.api.Assertions.assertFalse(doc.contains("app-chain/evidence/{messageIdHex}\""),
                "chain-less /app-chain/evidence should be hidden from OpenAPI");
        org.junit.jupiter.api.Assertions.assertFalse(doc.contains("app-chain/proof/{keyHex}\""),
                "chain-less /app-chain/proof should be hidden from OpenAPI");
        org.junit.jupiter.api.Assertions.assertFalse(doc.contains("app-chain/admin/pause\""),
                "chain-less /app-chain/admin/pause should be hidden from OpenAPI");
    }
}
