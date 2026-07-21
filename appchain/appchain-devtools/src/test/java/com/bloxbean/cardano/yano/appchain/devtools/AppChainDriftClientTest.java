package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainDriftClientTest {
    private static final String CONSENSUS = "a".repeat(64);
    private static final String CATALOG = "sha256:" + "b".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void cliComparesProjectAndMultiplePeersWithoutPrintingDigestValues() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        Path project = temporary.resolve("orders");
        AppChainProjectModel.Lock lock = renderer.initialize(project, blueprint());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/node-a/app-chain/chains/orders/identity", exchange -> {
            byte[] body = identity(lock, CONSENSUS);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/node-b/app-chain/chains/orders/identity", exchange -> {
            byte[] body = identity(lock, CONSENSUS);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            StringWriter output = new StringWriter();
            StringWriter error = new StringWriter();

            int exit = new AppChainDevtoolsCli().run(new String[]{
                            "appchain", "drift", project.toString(),
                            "--peer", base + "/node-a/",
                            "--peer", base + "/node-b/",
                            "--format", "json"
                    }, new PrintWriter(output), new PrintWriter(error));

            assertThat(exit).isZero();
            assertThat(error.toString()).isEmpty();
            assertThat(output.toString()).contains("DRIFT_OK", "cluster.consensus-profile")
                    .doesNotContain(lock.resolvedConfigDigest(), CONSENSUS, CATALOG);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mismatchesAreCategorizedAndSinglePeerComparisonIsIncomplete() throws Exception {
        AppChainProjectModel.Lock lock = lock();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a/app-chain/chains/orders/identity", exchange -> {
            byte[] body = identity(lock, CONSENSUS);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/b/app-chain/chains/orders/identity", exchange -> {
            var changed = new AppChainProjectModel.RuntimeIdentity(
                    "v1", "orders", "f".repeat(64), null, CATALOG,
                    lock.resolvedConfigDigest(), "e".repeat(64), "PROJECT_BOUND");
            byte[] body = new ObjectMapper().writeValueAsBytes(changed);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AppChainDriftClient client = new AppChainDriftClient();
            AppChainProjectModel.DriftReport incomplete = client.compare(
                    lock, "orders", List.of(URI.create(base + "/a/")), null);
            assertThat(incomplete.status()).isEqualTo("DRIFT_INCOMPLETE");

            AppChainProjectModel.DriftReport drift = client.compare(
                    lock, "orders", List.of(
                            URI.create(base + "/a/"), URI.create(base + "/b/")), null);
            assertThat(drift.status()).isEqualTo("DRIFT_DETECTED");
            assertThat(drift.checks())
                    .filteredOn(check -> "MISMATCH".equals(check.status()))
                    .extracting(AppChainProjectModel.DriftCheck::category)
                    .containsExactlyInAnyOrder("release.catalog", "cluster.consensus-profile");
        } finally {
            server.stop(0);
        }

        assertThatThrownBy(() -> AppChainDriftClient.identityEndpoint(
                URI.create("https://secret@example.com"), "orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without credentials");
    }

    private static byte[] identity(AppChainProjectModel.Lock lock, String consensus)
            throws IOException {
        return new ObjectMapper().writeValueAsBytes(new AppChainProjectModel.RuntimeIdentity(
                "v1", "orders", consensus, null, CATALOG,
                lock.resolvedConfigDigest(), lock.catalogDigests().get("releaseIndex"),
                "PROJECT_BOUND"));
    }

    private static AppChainProjectModel.Blueprint blueprint() {
        return new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata("orders"),
                new AppChainProjectModel.Spec(
                        System.getProperty("yano.version", "0.1.0-pre11"),
                        "devnet",
                        new AppChainProjectModel.RuntimeSelection("jvm"),
                        new AppChainProjectModel.DeploymentSelection("host"),
                        List.of(new AppChainProjectModel.ChainIntent(
                                "orders", "audit-log", List.of(), Map.of(),
                                new AppChainProjectModel.Topology(
                                        1, List.of(), List.of(), "two-thirds", "fixed",
                                        "static", null, null)))));
    }

    private static AppChainProjectModel.Lock lock() {
        return new AppChainProjectModel.Lock(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.LOCK_KIND,
                "schema", "0".repeat(64), Map.of("releaseIndex", "d".repeat(64)),
                "test", "jvm", "host", "devnet", "audit-log:1",
                List.of(), List.of(), List.of(), Map.of(), "c".repeat(64),
                "FULL", "STABLE", List.of(), Map.of());
    }
}
