package org.yanoproject.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;

/** Real-HTTP outcomes of the plugin-operations key check with full and topic-scoped keys configured. */
@QuarkusTest
@TestProfile(PluginOperationsAuthHttpTest.KeysProfile.class)
class PluginOperationsAuthHttpTest {

    public static class KeysProfile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("yano.app-chain.api.keys", "full-access-key,restricted-key=orders|invoices");
            return overrides;
        }
    }

    @Test
    void missingOrInvalidKeyIsUnauthorized() {
        given().when().get("/api/v1/plugin-operations").then().statusCode(401);
        given().header("X-API-Key", "nope")
                .when().get("/api/v1/plugin-operations").then().statusCode(401);
    }

    @Test
    void topicScopedKeyIsForbidden() {
        given().header("X-API-Key", "restricted-key")
                .when().get("/api/v1/plugin-operations").then().statusCode(403);
    }

    @Test
    void fullKeyReachesTheResource() {
        given().header("X-API-Key", "full-access-key")
                .when().get("/api/v1/plugin-operations")
                .then().statusCode(200)
                .body("catalogFingerprint", startsWith("sha256:"));
        given().header("X-API-Key", "full-access-key")
                .when().get("/api/v1/plugin-operations/bundles")
                .then().statusCode(200);
    }

    @Test
    void nonCanonicalAliasesAreRejected() {
        for (String path : new String[] {
                "/api/v1/plugin-operations/",
                "/api/v1/plugin-operations;x=1",
                "/api/v1/plugin-operations/bundles/"}) {
            given().header("X-API-Key", "full-access-key")
                    .when().get(path)
                    .then().statusCode(404);
        }
    }
}
