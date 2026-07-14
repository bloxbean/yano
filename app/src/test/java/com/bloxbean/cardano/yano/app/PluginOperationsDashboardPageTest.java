package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class PluginOperationsDashboardPageTest {

    @Test
    void operationsApiFailsClosedWhenPrivilegedAuthenticationIsDisabled() {
        given()
                .when().get("/api/v1/plugin-operations")
                .then()
                .statusCode(503);
    }

    @Test
    void pluginHealthAndMetricsAdaptersArePublishedFromTheHostCache() {
        given()
                .when().get("/q/health/group/plugins")
                .then()
                .statusCode(200)
                .body("checks.name", Matchers.hasItem("plugins"));

        given()
                .when().get("/q/metrics")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("yano_plugin_bundles"));
    }

    @Test
    void dashboardShellIsServedWithoutInventoryOrRemoteAssets() {
        given()
                .when().get("/ui/plugins/index.html")
                .then()
                .statusCode(200)
                .header("X-Frame-Options", "DENY")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy",
                        Matchers.containsString("frame-ancestors 'none'"))
                .header("Referrer-Policy", "no-referrer")
                .body(Matchers.containsString("Yano · Plugin Operations"))
                .body(Matchers.containsString("id=\"authPanel\""))
                .body(Matchers.containsString("id=\"dashboard\" hidden"))
                .body(Matchers.containsString("host-provided API prefix"))
                .body(Matchers.containsString("id=\"apiKey\""))
                .body(Matchers.containsString("maxlength=\"4096\" disabled"))
                .body(Matchers.containsString("Content-Security-Policy"))
                .body(Matchers.containsString("script-src 'self'"))
                .body(Matchers.containsString("src=\"app.js\""))
                .body(Matchers.containsString("href=\"styles.css\""))
                .body(Matchers.containsString("Catalog bundles"))
                .body(Matchers.not(Matchers.containsString("http://")))
                .body(Matchers.not(Matchers.containsString("https://")));

        for (String asset : new String[]{"app.js", "styles.css"}) {
            given()
                    .when().get("/ui/plugins/" + asset)
                    .then()
                    .statusCode(200)
                    .header("X-Frame-Options", "DENY")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy",
                            Matchers.containsString("frame-ancestors 'none'"))
                    .header("Referrer-Policy", "no-referrer")
                    .header("Cache-Control", "no-store");
        }
    }

    @Test
    void dashboardScriptUsesSessionCredentialAndSafeDomConstruction() {
        given()
                .when().get("/ui/plugins/app.js")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("sessionStorage"))
                .body(Matchers.containsString("'X-API-Key': key"))
                .body(Matchers.containsString("new AbortController()"))
                .body(Matchers.containsString("REQUEST_TIMEOUT_MS"))
                .body(Matchers.containsString(
                        "PREFIX_DISCOVERY_PATH = '/ui/plugins/api-prefix.json'"))
                .body(Matchers.containsString("Accept: 'application/json'"))
                .body(Matchers.containsString("redirect: 'error'"))
                .body(Matchers.containsString("await response.json()"))
                .body(Matchers.containsString("discovery.apiPrefix"))
                .body(Matchers.containsString("response.redirected"))
                .body(Matchers.containsString(
                        "responseUrl.origin !== location.origin"))
                .body(Matchers.containsString(
                        "responseUrl.pathname !== PREFIX_DISCOVERY_PATH"))
                .body(Matchers.containsString("stored.prefix === apiPrefix"))
                .body(Matchers.containsString(
                        "JSON.stringify({ prefix: apiPrefix, key: value })"))
                .body(Matchers.containsString("candidate.includes('%')"))
                .body(Matchers.containsString("segment === '.' || segment === '..'"))
                .body(Matchers.containsString("parsed.username || parsed.password"))
                .body(Matchers.containsString("if (!apiPrefix || !apiRoot || !key)"))
                .body(Matchers.containsString("setCredentialEntryEnabled(false)"))
                .body(Matchers.containsString("/bundles/${encodeURIComponent(id)}"))
                .body(Matchers.containsString("counts.get('DOWN')"))
                .body(Matchers.containsString("failing > 0 ? 'DOWN'"))
                .body(Matchers.containsString("unknown > 0 ? 'UNKNOWN' : 'UP'"))
                .body(Matchers.containsString(
                        "'CATALOG VALID · LIFECYCLE NOT OBSERVED'"))
                .body(Matchers.containsString("detail('Health checks'"))
                .body(Matchers.containsString("healthCheckLabel"))
                .body(Matchers.containsString("['id'], 'check'"))
                .body(Matchers.containsString("['status'], 'UNKNOWN'"))
                .body(Matchers.containsString("['description'], ''"))
                .body(Matchers.containsString(
                        "first(contribution, ['lifecycleObserved'], false) === true"))
                .body(Matchers.not(Matchers.containsString(
                        "state === 'ACTIVE' || state === 'VALIDATED'")))
                .body(Matchers.containsString(
                        "${observedContributionCount}/${contributionCount} lifecycle observed"))
                .body(Matchers.containsString("PAGE_SIZE = 100"))
                .body(Matchers.containsString("MAX_LOADED_BUNDLES = 500"))
                .body(Matchers.containsString("dashboard cap reached"))
                .body(Matchers.containsString("apiPrefix === '/'"))
                .body(Matchers.containsString("? '/plugin-operations'"))
                .body(Matchers.containsString("loadBundlePages(loadedPageTarget"))
                .body(Matchers.containsString("textContent"))
                .body(Matchers.not(Matchers.containsString("localStorage")))
                .body(Matchers.not(Matchers.containsString("innerHTML")))
                .body(Matchers.not(Matchers.containsString("outerHTML")))
                .body(Matchers.not(Matchers.containsString("insertAdjacentHTML")))
                .body(Matchers.not(Matchers.containsString("URLSearchParams")))
                .body(Matchers.not(Matchers.containsString("location.search")))
                .body(Matchers.not(Matchers.containsString("?api=")))
                .body(Matchers.not(Matchers.containsString("?key=")));
    }

    @Test
    void dashboardReusesCredentialsOnlyForTheSameNormalizedApiPrefix() {
        given()
                .when().get("/ui/plugins/app.js")
                .then()
                .statusCode(200)
                .body(Matchers.containsString(
                        "stored.prefix === apiPrefix && typeof stored.key === 'string'"))
                .body(Matchers.containsString(
                        "JSON.stringify({ prefix: apiPrefix, key: value })"));
    }

    @Test
    void dashboardAcceptsApiPrefixOnlyFromTheFixedHostDiscoveryResponse() {
        given()
                .when().get("/ui/plugins/app.js")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("? stored.key : ''"))
                .body(Matchers.containsString("fetch(PREFIX_DISCOVERY_PATH"))
                .body(Matchers.containsString("Accept: 'application/json'"))
                .body(Matchers.containsString("candidate.includes('%')"))
                .body(Matchers.containsString("candidate.includes('+')"))
                .body(Matchers.containsString("candidate.startsWith('//')"))
                .body(Matchers.containsString("/[\\\\?#;\\u0000-\\u001f\\u007f]/"))
                .body(Matchers.containsString("segment === '.' || segment === '..'"))
                .body(Matchers.containsString("parsed.username || parsed.password"))
                .body(Matchers.containsString(
                        "The host plugin API prefix could not be verified"))
                .body(Matchers.not(Matchers.containsString("URLSearchParams")))
                .body(Matchers.not(Matchers.containsString("location.search")));
    }

    @Test
    void immutableDashboardDiscoveryDocumentCannotBeSteeredByARequestQuery() {
        given()
                .queryParam("api", "/attacker/proxy")
                .when().get("/ui/plugins/api-prefix.json")
                .then()
                .statusCode(200)
                .header("Cache-Control", "no-store")
                .body("apiPrefix", Matchers.equalTo("/api/v1"));
    }

    @Test
    void dashboardRejectsRedirectsForDiscoveryAndCredentialBearingRequests() {
        String script = given()
                .when().get("/ui/plugins/app.js")
                .then()
                .statusCode(200)
                .extract().asString();

        assertEquals(2, occurrences(script, "redirect: 'error'"));
    }

    @Test
    void existingDashboardsLinkToPluginOperations() {
        given()
                .when().get("/ui/status/index.html")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("href=\"../plugins/\""));

        given()
                .when().get("/ui/app-chain/index.html")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("href=\"../plugins/\""));
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        for (int cursor = 0; (cursor = value.indexOf(fragment, cursor)) >= 0;
             cursor += fragment.length()) {
            count++;
        }
        return count;
    }
}
