package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-028 UI-M2: the shared console artifact serves the app-chain operations
 * route next to the L1 status route, and the two cross-link.
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class AppChainStatusPageTest {

    @Test
    void appChainStatusPage_isServed() {
        String page = given()
                .when().get("/ui/app-chain/index.html")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(page.contains("Yano · App Chains"));
        assertTrue(page.contains("data-console-route=\"app-chain\""));
        for (String panel : new String[]{"App-chain operations", "Consensus &amp; traffic",
                "L1 anchor", "Effect executors", "Profile governance", "Live messages",
                "Recent blocks", "Finalized message", "Authenticated fetch SSE"}) {
            assertTrue(page.contains(panel), panel);
        }
        assertTrue(page.contains("href=\"../status/\""));
    }

    @Test
    void l1StatusPage_linksToAppChainPage() {
        given()
                .when().get("/ui/status/index.html")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("Yano · Node Status"))
                .body(Matchers.containsString("href=\"../app-chain/\""))
                .body(Matchers.containsString("Transaction diffusion"))
                .body(Matchers.containsString("browser session · up to 1 hour"));
    }

}
