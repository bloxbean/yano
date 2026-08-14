package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * ADR-025.2 §10.5: the node-served console pages restrict connect-src to the
 * host (plus explicitly configured origins) instead of broad http:/https:
 * schemes, for every console route rather than only the plugin dashboard.
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class ConsoleSecurityHeadersTest {

    @Test
    void consolePagesCarryStrictSecurityHeaders() {
        for (String page : new String[] {
                "/ui/index.html",
                "/ui/status/index.html",
                "/ui/app-chain/index.html",
                "/ui/app-chain/eutxo/index.html"}) {
            given().when().get(page).then().statusCode(200)
                    .header("X-Frame-Options", "DENY")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", Matchers.allOf(
                            Matchers.containsString("connect-src 'self'"),
                            Matchers.not(Matchers.containsString("connect-src 'self' http:")),
                            Matchers.containsString("frame-ancestors 'none'")))
                    .header("Referrer-Policy", "no-referrer")
                    .header("Cache-Control", "no-store");
        }
    }

    @Test
    void consoleDiscoveryDocumentCarriesTheSameHeaders() {
        given().when().get("/ui/api-prefix.json").then().statusCode(200)
                .header("Content-Security-Policy", Matchers.containsString("connect-src 'self'"))
                .header("Cache-Control", "no-store")
                .body("apiPrefix", Matchers.equalTo("/api/v1"));
    }
}
