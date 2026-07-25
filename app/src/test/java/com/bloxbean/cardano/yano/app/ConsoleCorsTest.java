package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(ConsoleCorsTest.EnabledCorsProfile.class)
class ConsoleCorsTest {

    @Test
    void exactStandaloneOriginIsAllowedAndUnknownOriginIsNotReflected() {
        given().header("Origin", "https://console.example.com")
                .when().get("/api/v1/node/config")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", "https://console.example.com");

        given().header("Origin", "https://attacker.example")
                .when().get("/api/v1/node/config")
                .then().statusCode(403)
                .header("Access-Control-Allow-Origin", Matchers.nullValue());
    }

    public static class EnabledCorsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new HashMap<>(new NoAutoStartTestProfile().getConfigOverrides());
            config.put("quarkus.http.cors.enabled", "true");
            config.put("quarkus.http.cors.origins", "https://console.example.com");
            config.put("quarkus.http.cors.methods", "GET,POST,OPTIONS");
            config.put("quarkus.http.cors.headers", "Accept,Content-Type,X-API-Key");
            return config;
        }
    }
}
