package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoAuditClientTest {
    private static final String TX = "ab".repeat(32);
    private static final String ADDRESS = "addr_test1wzscriptanchor";
    private static final String POLICY = "33".repeat(28);
    private static final Set<String> MEMBERS = Set.of(
            "01".repeat(32), "02".repeat(32), "03".repeat(32));
    private HttpServer server;
    private YanoAuditClient client;
    private final AtomicReference<Response> transaction = new AtomicReference<>();
    private final AtomicReference<String> appStatus = new AtomicReference<>();
    private final AtomicReference<String> receivedApiKey = new AtomicReference<>();

    @Test
    void usesTheStrictCodecEvidenceDocumentLimit() {
        assertThat(YanoAuditClient.MAX_EVIDENCE_BYTES)
                .isEqualTo(EvidenceBundleCodec.MAX_JSON_BYTES);
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/status", exchange -> json(exchange, 200,
                "{\"chain\":{\"blockNumber\":4}}"));
        appStatus.set("{\"chainId\":\"evidence-chain\",\"running\":true,"
                + "\"tipHeight\":12,\"stateRoot\":\"" + "01".repeat(32) + "\","
                + "\"memberKey\":\"" + "02".repeat(32) + "\","
                + "\"members\":3,\"threshold\":2,"
                + "\"stateMachine\":\"evidence-registry\","
                + validAnchor(12, TX) + "}");
        server.createContext("/api/v1/app-chain/chains/evidence-chain/status", exchange -> {
            receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            json(exchange, 200, appStatus.get());
        });
        server.createContext("/api/v1/txs/", exchange -> {
            Response response = transaction.get();
            json(exchange, response.status(), response.body());
        });
        server.start();
        client = new YanoAuditClient(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                "evidence-chain", MEMBERS, 2, new SecretValue("api-key"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsBoundedL1AndAppChainAnchorStatus() {
        assertThat(client.l1BlockNumber()).isEqualTo(4);
        YanoAuditClient.Status status = client.status();
        assertThat(status.height()).isEqualTo(12);
        assertThat(status.stateRoot()).isEqualTo("01".repeat(32));
        assertThat(status.memberKey()).isEqualTo("02".repeat(32));
        assertThat(status.members()).isEqualTo(3);
        assertThat(status.threshold()).isEqualTo(2);
        assertThat(status.stateMachine()).isEqualTo("evidence-registry");
        assertThat(status.anchoredHeight()).isEqualTo(12);
        assertThat(status.anchorTx()).isEqualTo(TX);
        assertThat(status.anchorSlot()).isEqualTo(9);
        assertThat(status.anchorScriptAddress()).isEqualTo(ADDRESS);
        assertThat(status.anchorThreadPolicyId()).isEqualTo(POLICY);
        assertThat(receivedApiKey).hasValue("api-key");
    }

    @Test
    void omitsApiKeyFromRawAndAppChainClientsWhenNoScopedKeyIsConfigured() {
        YanoAuditClient publicClient = new YanoAuditClient(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                "evidence-chain", MEMBERS, 2, null);

        publicClient.status();
        assertThat(receivedApiKey.get()).isNull();
        publicClient.appChain().status();
        assertThat(receivedApiKey.get()).isNull();
    }

    @Test
    void acceptsOnlyExactValidAnchorTransactionAndTreats404AsPending() {
        transaction.set(new Response(404, "{\"error\":\"not found\"}"));
        assertThat(client.anchorTransactionVisible(TX)).isFalse();

        transaction.set(new Response(200, "{\"hash\":\"" + TX + "\","
                + "\"block_height\":7,\"slot\":9,\"valid_contract\":true}"));
        assertThat(client.anchorTransactionVisible(TX)).isTrue();

        transaction.set(new Response(200, "{\"hash\":\"" + TX + "\","
                + "\"block_height\":7,\"slot\":9,\"valid_contract\":false}"));
        assertThatThrownBy(() -> client.anchorTransactionVisible(TX))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.ANCHOR_UNAVAILABLE);
    }

    @Test
    void rejectsCoercedNegativeAndNonCanonicalAnchorStatusFields() {
        appStatus.set("{\"chainId\":\"evidence-chain\",\"running\":true,"
                + "\"tipHeight\":12,\"stateRoot\":\"" + "01".repeat(32) + "\","
                + statusTopology()
                + "\"anchor\":{\"lastAnchoredHeight\":\"12\","
                + "\"lastAnchorTx\":\"" + TX + "\"}}");
        assertThatThrownBy(client::status).isInstanceOf(DemoException.class);

        appStatus.set("{\"chainId\":\"evidence-chain\",\"running\":true,"
                + "\"tipHeight\":12,\"stateRoot\":\"" + "01".repeat(32) + "\","
                + statusTopology()
                + "\"anchor\":{\"lastAnchoredHeight\":-1,"
                + "\"lastAnchorTx\":\"" + TX + "\"}}");
        assertThatThrownBy(client::status).isInstanceOf(DemoException.class);

        appStatus.set("{\"chainId\":\"evidence-chain\",\"running\":true,"
                + "\"tipHeight\":12,\"stateRoot\":\"" + "01".repeat(32) + "\","
                + statusTopology()
                + "\"anchor\":{\"lastAnchoredHeight\":12,"
                + "\"lastAnchorTx\":\"" + TX.toUpperCase() + "\"}}");
        assertThatThrownBy(client::status)
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }

    private static String statusTopology() {
        return "\"memberKey\":\"" + "02".repeat(32) + "\","
                + "\"members\":3,\"threshold\":2,"
                + "\"stateMachine\":\"evidence-registry\",";
    }

    private static String validAnchor(long height, String tx) {
        return "\"anchor\":{\"enabled\":true,\"mode\":\"script\","
                + "\"bootstrapped\":true,\"address\":\"" + ADDRESS + "\","
                + "\"scriptAddress\":\"" + ADDRESS + "\","
                + "\"threadPolicyId\":\"" + POLICY + "\","
                + "\"lastAnchoredHeight\":" + height + ","
                + "\"lastAnchorTx\":\"" + tx + "\",\"lastAnchorL1Slot\":9}";
    }

    private static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Response(int status, String body) {
    }
}
