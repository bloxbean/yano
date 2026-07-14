package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * ADR app-layer/008.1 I1.9: the app-chain status page is served as a static
 * resource next to the L1 status page, and the two cross-link.
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class AppChainStatusPageTest {

    @Test
    void appChainStatusPage_isServed() {
        given()
                .when().get("/ui/app-chain/index.html")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("Yano · App Chain"))
                // key panels the page binds data to
                .body(Matchers.containsString("id=\"chainSelect\""))
                .body(Matchers.containsString("id=\"heroTip\""))
                .body(Matchers.containsString("id=\"anchorList\""))
                .body(Matchers.containsString("id=\"blocksBody\""))
                // follower anchor progress must use the independently observed counter
                .body(Matchers.containsString("anchor.leader === false"))
                .body(Matchers.containsString("Anchors Observed"))
                .body(Matchers.containsString("anchor.observedAnchorCount"))
                // links back to the L1 page
                .body(Matchers.containsString("href=\"../status/\""));
    }

    @Test
    void l1StatusPage_linksToAppChainPage() {
        given()
                .when().get("/ui/status/index.html")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("href=\"../app-chain/\""))
                // local-producer hero branch (devnet BP shows production, not sync %)
                .body(Matchers.containsString("Local Producer"))
                .body(Matchers.containsString("PRODUCING"));
    }
}
