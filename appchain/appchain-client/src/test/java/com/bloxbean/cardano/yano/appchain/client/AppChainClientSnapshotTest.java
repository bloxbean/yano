package com.bloxbean.cardano.yano.appchain.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class AppChainClientSnapshotTest {
    private HttpServer server;

    @AfterEach
    void close() {
        if (server != null) server.stop(0);
    }

    @Test
    void preservesNotAnchoredAndNotLocalProofAvailability() throws Exception {
        start(exchange -> respond(exchange, 503,
                "{\"code\":\"SNAPSHOT_NOT_ANCHORED\",\"error\":\"not anchored\"}"));
        AppChainClient client = client();
        assertThatThrownBy(() -> client.authenticatedSnapshotProof("epoch-stake", 7, new byte[]{1}))
                .isInstanceOfSatisfying(AppChainClient.SnapshotProofUnavailableException.class,
                        failure -> assertThat(failure.availability()).isEqualTo(
                                AppChainClient.SnapshotProofAvailability.NOT_ANCHORED));
        server.stop(0);

        start(exchange -> respond(exchange, 503,
                "{\"code\":\"SNAPSHOT_NOT_LOCAL\",\"error\":\"restore explicitly\"}"));
        assertThatThrownBy(() -> client().authenticatedSnapshotProof("epoch-stake", 7, new byte[]{1}))
                .isInstanceOfSatisfying(AppChainClient.SnapshotProofUnavailableException.class,
                        failure -> assertThat(failure.availability()).isEqualTo(
                                AppChainClient.SnapshotProofAvailability.NOT_LOCAL));

        server.stop(0);
        start(exchange -> respond(exchange, 409,
                "{\"code\":\"DISPUTED\",\"error\":\"deep rollback\"}"));
        assertThatThrownBy(() -> client().authenticatedSnapshotProof("epoch-stake", 7, new byte[]{1}))
                .isInstanceOfSatisfying(AppChainClient.SnapshotProofUnavailableException.class,
                        failure -> assertThat(failure.availability()).isEqualTo(
                                AppChainClient.SnapshotProofAvailability.DISPUTED));
    }

    @Test
    void descriptorNotFoundRemainsAnEmptyLookup() throws Exception {
        start(exchange -> respond(exchange, 404,
                "{\"code\":\"UNKNOWN_DESCRIPTOR\",\"error\":\"missing\"}"));
        assertThat(client().authenticatedSnapshot("epoch-stake", 9)).isEmpty();
    }

    @Test
    void parsesRootBoundSnapshotCatalogPageAndForwardsOpaqueCursor() throws Exception {
        String root = "11".repeat(32);
        start(exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery())
                    .isEqualTo("limit=20&series=epoch-stake&cursor=opaque_1");
            respond(exchange, 200, "{\"items\":[{\"seriesId\":\"epoch-stake\","
                    + "\"sequence\":7,\"snapshotId\":\"stake-7\",\"entryCount\":1,"
                    + "\"completedAppChainHeight\":9,\"profile\":\"mpf-blake2b256-v1\","
                    + "\"lifecycle\":\"ONLINE\"}],\"nextCursor\":null,"
                    + "\"viewHeight\":9,\"viewRootHex\":\"" + root + "\"}");
        });
        var page = client().authenticatedSnapshots("epoch-stake", "opaque_1", 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.viewHeight()).isEqualTo(9);
        assertThat(page.viewRootHex()).isEqualTo(root);
    }

    @Test
    void requestsAnExactRetainedAnchorHeightWhenSelected() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 503,
                    "{\"code\":\"SNAPSHOT_NOT_ANCHORED\",\"error\":\"not retained\"}");
        });

        assertThatThrownBy(() -> client().authenticatedSnapshotProof(
                "epoch-stake", 7, new byte[]{1}, 42L))
                .isInstanceOf(AppChainClient.SnapshotProofUnavailableException.class);
        assertThat(body.get()).contains("\"keyHex\":\"01\"")
                .contains("\"anchorHeight\":42");
    }

    @Test
    void exposesTypedLocalAndCompleteCallerPinnedVerificationResults() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"valid\":true,\"primaryValid\":true,"
                    + "\"secondaryValid\":true,\"disputed\":false,\"trusted\":true,"
                    + "\"trust\":\"caller-pinned-root-cryptographic-verification\"}");
        });
        var pinned = new AppChainClient.SnapshotPinnedAnchor(
                "history", "script", "mpf-blake2b256-v1", "11".repeat(32),
                "22".repeat(32), "33".repeat(32), 42,
                "44".repeat(32), "tx-hash", 100);

        var result = client().verifyAuthenticatedSnapshotProof(new byte[]{(byte) 0x80}, pinned);

        assertThat(result.valid()).isTrue();
        assertThat(result.trusted()).isTrue();
        assertThat(body.get()).contains("\"trustMode\":\"caller-pinned-root\"")
                .contains("\"expectedAnchoredHeight\":42")
                .contains("\"expectedAnchorTransactionHash\":\"tx-hash\"");
    }

    private AppChainClient client() {
        return AppChainClient.builder("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1")
                .chainId("history").directConnections().build();
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
