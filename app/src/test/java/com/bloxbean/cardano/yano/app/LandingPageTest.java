package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class LandingPageTest {

    @Test
    void rootServesBrandedEntryPoint() {
        given().when().get("/")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Yano"))
                .body(containsString("/ui/status/"))
                .body(containsString("/q/swagger-ui/"));
    }
}
