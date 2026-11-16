package org.yanoproject.fixture.consumer;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** The downstream key filter answers 503 while no full key is configured ({@code ApiKeys.hasFullKey}). */
@QuarkusTest
@TestProfile(ConsumerCheckNoFullKeyTest.ScopedOnlyProfile.class)
class ConsumerCheckNoFullKeyTest {

    public static class ScopedOnlyProfile extends ConsumerCheckTest.Profile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("yano.app-chain.api.keys", "scoped=orders");
            return overrides;
        }
    }

    @Test
    void noConfiguredFullKeyIsUnavailable() {
        given().header("X-API-Key", "scoped").when().get("/api/v1/consumer-check").then().statusCode(503);
        given().header("X-API-Key", "admin-a").when().get("/api/v1/consumer-check").then().statusCode(503);
    }
}
