package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
public class YanoResourceTest {

    @Test
    public void testGetStatus() {
        given()
            .when().get("/api/v1/node/status")
            .then()
                .statusCode(200)
                .body("running", is(false));
    }

    @Test
    public void testGetConfig() {
        given()
            .when().get("/api/v1/node/config")
            .then()
                .statusCode(200)
                .body("protocolMagic", notNullValue())
                .body("network", not(isEmptyOrNullString()))
                .body("version", not(isEmptyOrNullString()));
    }

    @Test
    public void testGetPeers() {
        given()
            .when().get("/api/v1/node/peers")
            .then()
                .statusCode(200)
                .body("peers", notNullValue())
                .body("knownPeerCount", notNullValue());
    }

    @Test
    public void testHealthCheck() {
        given()
            .when().get("/q/health/ready")
            .then()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
