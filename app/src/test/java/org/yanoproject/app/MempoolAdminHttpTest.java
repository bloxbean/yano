package org.yanoproject.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusMock;
import org.yanoproject.api.MempoolAdminGateway;
import java.util.List;
import static org.hamcrest.Matchers.equalTo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/** Exercise real HTTP routing, config injection and authentication without starting a node. */
@QuarkusTest
@TestProfile(MempoolAdminHttpTest.Profile.class)
class MempoolAdminHttpTest {
    @Test
    void serializesSuccessfulEviction() {
        QuarkusMock.installMockForType((MempoolAdminGateway) hash -> List.of(hash), MempoolAdminGateway.class);
        String hash = "ab".repeat(32);
        given().header("X-Admin-API-Key", "http-test-secret-012345678901234567890123456789")
                .delete("/api/v1/admin/mempool/transactions/" + hash).then().statusCode(200)
                .body("txHash", equalTo(hash)).body("evictedTxHashes[0]", equalTo(hash));
    }
    public static class Profile extends NoAutoStartTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = super.getConfigOverrides();
            overrides.put("yano.mempool.admin.enabled", "true");
            overrides.put("yano.mempool.admin.api-key", "http-test-secret-012345678901234567890123456789");
            return overrides;
        }
    }

    @Test
    void requiresKeyAndHandlesStoppedNode() {
        String path = "/api/v1/admin/mempool/transactions/" + "ab".repeat(32);
        given().delete(path).then().statusCode(401);
        given().header("X-Admin-API-Key", "wrong").delete(path).then().statusCode(401);
        given().header("X-Admin-API-Key", "http-test-secret-012345678901234567890123456789")
                .delete("/api/v1/admin/mempool/transactions/invalid").then().statusCode(400);
        given().header("X-Admin-API-Key", "http-test-secret-012345678901234567890123456789").delete(path).then().statusCode(503);
    }
}
