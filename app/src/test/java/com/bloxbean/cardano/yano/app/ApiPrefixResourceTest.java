package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestProfile(ApiPrefixResourceTest.CustomApiPrefixProfile.class)
class ApiPrefixResourceTest {

    @Test
    void customApiPrefixShouldRouteProductionApi() {
        given()
                .when().get("/bf/node/status")
                .then()
                .statusCode(200)
                .body("running", is(false));
    }

    @Test
    void defaultApiPrefixShouldNotRemainActiveWhenCustomPrefixIsConfigured() {
        given()
                .when().get("/api/v1/node/status")
                .then()
                .statusCode(404);
    }

    public static class CustomApiPrefixProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("yano.api-prefix", "/bf");
        }
    }
}
