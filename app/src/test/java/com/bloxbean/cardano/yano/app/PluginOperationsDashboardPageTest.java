package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class PluginOperationsDashboardPageTest {

    @Test
    void operationsApiFailsClosedWithoutConfiguredFullKey() {
        given().when().get("/api/v1/plugin-operations").then().statusCode(503);
    }

    @Test
    void pluginHealthAndMetricsAdaptersArePublishedFromTheHostCache() {
        given().when().get("/q/health/group/plugins").then().statusCode(200)
                .body("checks.name", Matchers.hasItem("plugins"));
        given().when().get("/q/metrics").then().statusCode(200)
                .body(Matchers.containsString("yano_plugin_bundles"));
    }

    @Test
    void dashboardShellIsPackagedWithStrictSecurityHeaders() {
        given().when().get("/ui/plugins/index.html").then().statusCode(200)
                .header("X-Frame-Options", "DENY")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", Matchers.allOf(
                        Matchers.containsString("connect-src 'self'"),
                        Matchers.containsString("frame-ancestors 'none'")))
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store")
                .body(Matchers.containsString("Yano · Plugin Operations"))
                .body(Matchers.containsString("data-console-route=\"plugins\""))
                .body(Matchers.containsString("Connect to plugin operations"))
                .body(Matchers.containsString("host-provided API prefix"))
                .body(Matchers.containsString("content-security-policy"));
    }

    @Test
    void immutableDashboardDiscoveryDocumentCannotBeSteeredByARequestQuery() {
        given().queryParam("api", "/attacker/proxy")
                .when().get("/ui/plugins/api-prefix.json")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("apiPrefix", Matchers.equalTo("/api/v1"));
    }

    @Test
    void existingDashboardsLinkToPluginOperations() {
        given().when().get("/ui/status/index.html").then().statusCode(200)
                .body(Matchers.containsString("href=\"../plugins/\""));
        given().when().get("/ui/app-chain/index.html").then().statusCode(200)
                .body(Matchers.containsString("href=\"../plugins/\""));
    }
}
