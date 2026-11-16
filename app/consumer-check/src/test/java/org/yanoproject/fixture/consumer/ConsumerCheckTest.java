package org.yanoproject.fixture.consumer;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
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
import org.yanoproject.runtime.assembly.Yano;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-064 M1: a downstream Quarkus application composes the staged yano-app-core. */
@QuarkusTest
@TestProfile(ConsumerCheckTest.Profile.class)
class ConsumerCheckTest {

    static final String KEY = "consumer-check-full-key";

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
                        "yano.app-chain.api.keys", KEY);
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
        assertSame(ClientProxy.unwrap(yano), ClientProxy.unwrap(Arc.container().instance(Yano.class).get()));
        for (Class<?> role : List.of(NodeLifecycle.class, ChainQuery.class, LedgerQuery.class,
                TxGateway.class, PluginCatalogView.class)) {
            assertTrue(Arc.container().select(role).isResolvable(), role.getName());
        }
    }

    @Test
    void downstreamGlobalsContributorIsMerged() {
        assertTrue(globals.calls() > 0);
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
    void downstreamRouteIsServedUnderTheBakedPrefix() {
        given().when().get("/api/v1/consumer-check")
                .then().statusCode(200)
                .body("protocolMagic", equalTo(1));
    }
}
