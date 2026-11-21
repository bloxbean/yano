package org.yanoproject.fixture.consumer;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.NodeLifecycle;
import org.yanoproject.api.TxGateway;
import org.yanoproject.api.plugin.PluginCatalogView;
import org.yanoproject.app.configdump.EffectiveConfigDump;
import org.yanoproject.runtime.assembly.Yano;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-064 M1: a downstream Quarkus application composes the staged yano-app-core through
 * org.yanoproject.app.spi and the injectable {@link Yano} only.
 */
@QuarkusTest
@TestProfile(ConsumerCheckTest.Profile.class)
class ConsumerCheckTest {

    static final String KEY = "consumer-check-full-key";
    static final String ECHO_SERVICE = "org.yanoproject.fixture.plugin-conformance.echoed-global";

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Path plugins = Files.createTempDirectory("consumer-check-plugins");
                Path fixture = Path.of(System.getProperty("yano.consumer-check.fixture-plugin"));
                Files.copy(fixture, plugins.resolve(fixture.getFileName()));
                int serverPort;
                try (ServerSocket socket = new ServerSocket(0)) {
                    serverPort = socket.getLocalPort();
                }
                return Map.of(
                        "yano.auto-sync-start", "false",
                        "yano.client.enabled", "false",
                        "yano.server.port", Integer.toString(serverPort),
                        "yano.storage.rocksdb", "false",
                        "yano.account-state.enabled", "false",
                        "yano.plugins.enabled", "true",
                        "yano.plugins.directory", plugins.toString(),
                        "yano.app-chain.api.keys", KEY + ",scoped=orders",
                        "yano.fixture.consumer-check.admin-keys", "admin-a, admin-b");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Inject
    Yano yano;

    @Inject
    ConsumerGlobals globals;

    @Test
    void oneYanoInstanceBehindUnambiguousProducers() {
        assertEquals(1, Arc.container().beanManager().getBeans(Yano.class).size());
        for (Class<?> role : List.of(Yano.class, NodeLifecycle.class, ChainQuery.class, LedgerQuery.class,
                TxGateway.class, PluginCatalogView.class)) {
            assertTrue(Arc.container().select(role).isResolvable(), role.getName());
        }
    }

    @Test
    void downstreamGlobalsContributionReachesThePlugins() {
        assertTrue(globals.calls() > 0);
        assertEquals(Optional.of("present"), yano.service(ECHO_SERVICE, String.class));
    }

    @Test
    void exactlyOnePluginIndexAndTheFixturePluginIsActive() throws Exception {
        assertEquals(1, Collections.list(Thread.currentThread().getContextClassLoader()
                .getResources("META-INF/yano-plugin-index-v1.json")).size());
        given().header("X-API-Key", KEY)
                .when().get("/api/v1/plugin-operations/bundles")
                .then().statusCode(200)
                .body("items.id", hasItems("org.yanoproject.plugins.logging",
                        "org.yanoproject.fixture.plugin-conformance"));
    }

    @Test
    void downstreamRouteIsServedUnderTheBakedPrefixBehindTheSharedKeyHelper() {
        given().when().get("/api/v1/consumer-check").then().statusCode(401);
        given().header("X-API-Key", "wrong").when().get("/api/v1/consumer-check").then().statusCode(401);
        given().header("X-API-Key", "scoped").when().get("/api/v1/consumer-check").then().statusCode(401);
        given().header("X-API-Key", "admin-b").when().get("/api/v1/consumer-check").then().statusCode(200);
        given().header("X-API-Key", KEY)
                .when().get("/api/v1/consumer-check")
                .then().statusCode(200)
                .body("protocolMagic", equalTo(1));
    }

    @Test
    void downstreamFilterBoundsRequestBodiesWithTheSharedHelper() {
        given().header("X-API-Key", KEY).contentType("text/plain").body("sixteen-bytes-ok")
                .when().post("/api/v1/consumer-check")
                .then().statusCode(200)
                .body("length", equalTo(16));
        given().header("X-API-Key", KEY).contentType("text/plain").body("seventeen-bytes-x")
                .when().post("/api/v1/consumer-check")
                .then().statusCode(413)
                .body("code", equalTo("REQUEST_TOO_LARGE"));
    }

    @Test
    void effectiveConfigurationEqualsYanosOwnApplication() throws Exception {
        SortedMap<String, String> expected = EffectiveConfigDump.read(
                Path.of(System.getProperty("yano.consumer-check.app-effective-config")));
        SortedMap<String, String> actual = EffectiveConfigDump.artifactDefaults();
        SortedMap<String, String> differences = new TreeMap<>();
        expected.forEach((key, value) -> {
            if (!value.equals(actual.get(key))) {
                differences.put(key, "app=" + value + " consumer=" + actual.get(key));
            }
        });
        actual.forEach((key, value) -> {
            if (!expected.containsKey(key)) {
                differences.put(key, "app=<absent> consumer=" + value);
            }
        });
        assertEquals(Map.of(), differences);
    }
}
