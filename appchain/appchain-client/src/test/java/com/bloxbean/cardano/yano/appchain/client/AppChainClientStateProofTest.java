package com.bloxbean.cardano.yano.appchain.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class AppChainClientStateProofTest {
    private static final String ROOT = "ab".repeat(32);
    private static final String INCLUSION = """
            {"key":"01","chainId":"c1","stateRoot":"%s","proofWireHex":"80",
             "valueHex":"ff","finalizedAtHeight":3,"committedHeight":42}
            """.formatted(ROOT);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void proofParsesAtomicSnapshotAndPreservesLegacyMessageHeight() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        start(exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, INCLUSION);
        });

        AppChainClient.Proof proof = client().proof(new byte[]{1}).orElseThrow();

        assertThat(path.get()).isEqualTo("/api/v1/app-chain/chains/c1/proof/01");
        assertThat(proof.keyHex()).isEqualTo("01");
        assertThat(proof.chainId()).isEqualTo("c1");
        assertThat(proof.stateRootHex()).isEqualTo(ROOT);
        assertThat(proof.valueHex()).isEqualTo("ff");
        assertThat(proof.finalizedAtHeight()).isEqualTo(3);
        assertThat(proof.committedHeight()).isEqualTo(42);
    }

    @Test
    void proofPreservesAtomicExclusionEnvelope() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"key":"01","chainId":"c1","stateRoot":"%s",
                 "proofWireHex":"80","committedHeight":42}
                """.formatted(ROOT)));

        AppChainClient.Proof proof = client().proof(new byte[]{1}).orElseThrow();

        assertThat(proof.valueHex()).isNull();
        assertThat(proof.finalizedAtHeight()).isNull();
        assertThat(proof.committedHeight()).isEqualTo(42);
    }

    @Test
    void proofKeepsLegacyEnvelopeSourceCompatible() throws Exception {
        start(exchange -> respond(exchange, 200,
                INCLUSION.replace(",\"committedHeight\":42", "")));

        AppChainClient.Proof proof = client().proof(new byte[]{1}).orElseThrow();

        assertThat(proof.committedHeight()).isNull();
        AppChainClient.Proof constructed = new AppChainClient.Proof(
                "01", "c1", ROOT, "80", "ff", 3L);
        assertThat(constructed.committedHeight()).isNull();
    }

    @Test
    void proofRejectsMismatchedUnknownDuplicateAndNonCanonicalFields() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(INCLUSION);
        start(exchange -> respond(exchange, 200, response.get()));
        String fields = INCLUSION.strip();
        fields = fields.substring(0, fields.length() - 1);
        String duplicate = fields + ",\"key\":\"01\"}";
        String unknown = fields + ",\"extra\":true}";

        for (String invalid : new String[]{
                INCLUSION.replace("\"key\":\"01\"", "\"key\":\"02\""),
                INCLUSION.replace("\"chainId\":\"c1\"", "\"chainId\":\"c2\""),
                INCLUSION.replace(ROOT, ROOT.toUpperCase()),
                INCLUSION.replace("\"proofWireHex\":\"80\"", "\"proofWireHex\":\"\""),
                INCLUSION.replace("\"committedHeight\":42", "\"committedHeight\":-1"),
                INCLUSION.replace("\"finalizedAtHeight\":3", "\"finalizedAtHeight\":43"),
                duplicate,
                unknown,
                INCLUSION + "{}"
        }) {
            response.set(invalid);
            assertThatThrownBy(() -> client().proof(new byte[]{1}))
                    .isInstanceOf(AppChainClient.AppChainClientException.class)
                    .hasMessageNotContaining("proofWireHex");
        }
    }

    @Test
    void proofDistinguishesMissingAndBoundsKeyBeforeIo() throws Exception {
        start(exchange -> respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}"));

        Optional<AppChainClient.Proof> missing = client().proof(new byte[]{1});

        assertThat(missing).isEmpty();
        assertThatThrownBy(() -> client().proof(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-256 bytes");
        assertThatThrownBy(() -> client().proof(new byte[257]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-256 bytes");
    }

    @Test
    void proofFailureNeverReflectsResponseOrApiKey() throws Exception {
        String secret = "proof-api-key-canary";
        start(exchange -> respond(exchange, 500,
                "{\"code\":\"FAILED\",\"error\":\"" + secret + " from provider\"}"));
        AppChainClient client = AppChainClient.builder(baseUrl())
                .chainId("c1")
                .apiKey(secret)
                .build();

        assertThatThrownBy(() -> client.proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain state proof failed with HTTP 500 (FAILED)")
                .hasMessageNotContaining(secret)
                .hasNoCause();
    }

    @Test
    void proofBoundsRawEnvelopeAndIndividualProofComponents() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(INCLUSION);
        start(exchange -> {
            String body = response.get();
            if (body == null) {
                // Exact client envelope limit plus one byte. A declared length
                // must be rejected without allocating or reading that body.
                exchange.sendResponseHeaders(200, 4_260_353L);
                exchange.getResponseBody().write(0);
                exchange.getResponseBody().flush();
                exchange.close();
                return;
            }
            respond(exchange, 200, body);
        });

        response.set(null);
        assertThatThrownBy(() -> client().proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain state proof response exceeds the client size limit");

        response.set(INCLUSION.replace("\"proofWireHex\":\"80\"",
                "\"proofWireHex\":\"" + "00".repeat(1024 * 1024 + 1) + "\""));
        assertThatThrownBy(() -> client().proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("Invalid app-chain state proof encoding");
    }

    private AppChainClient client() {
        return AppChainClient.builder(baseUrl()).chainId("c1").build();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/api/v1";
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1/proof", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                exchange.close();
                throw failure instanceof IOException ioFailure
                        ? ioFailure : new IOException("proof test handler failed", failure);
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
