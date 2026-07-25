package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class ConsoleObservabilityPageTest {

    @Test
    void observabilityRouteIsARealPackagedStaticPage() {
        String page = given()
                .when().get("/ui/observability/index.html")
                .then().statusCode(200)
                .extract().asString();

        assertTrue(page.contains("Yano · Observability"));
        assertTrue(page.contains("data-console-route=\"observability\""));
        assertTrue(page.contains("Plain Yano mode"));
        assertTrue(page.contains("./yano.sh observability start"));
    }
}
