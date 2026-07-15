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
class AppChainClientSubmitTest {
    private static final String MESSAGE_ID = "ab".repeat(32);
    private static final String VALID = """
            {"messageId":"%s","chainId":"c1","topic":"evidence.command.v1"}
            """.formatted(MESSAGE_ID);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitUsesCanonicalWireAndBindsResponseIdentity() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        start(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 202, VALID);
        });

        AppChainClient.SubmitResult result = client(null).submit(
                "evidence.command.v1", new byte[]{0, (byte) 0xff});

        assertThat(requestBody.get())
                .isEqualTo("{\"topic\":\"evidence.command.v1\",\"bodyHex\":\"00ff\"}");
        assertThat(result.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(result.chainId()).isEqualTo("c1");
        assertThat(result.topic()).isEqualTo("evidence.command.v1");
    }

    @Test
    void submitRejectsMalformedDuplicateUnknownAndMismatchedResponses() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(VALID);
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 202, response.get());
        });
        String fields = VALID.strip();
        fields = fields.substring(0, fields.length() - 1);

        for (String invalid : new String[]{
                VALID.replace(MESSAGE_ID, "ab"),
                VALID.replace(MESSAGE_ID, MESSAGE_ID.toUpperCase()),
                VALID.replace("\"chainId\":\"c1\"", "\"chainId\":\"other\""),
                VALID.replace("evidence.command.v1", "other"),
                fields + ",\"messageId\":\"" + MESSAGE_ID + "\"}",
                fields + ",\"extra\":true}",
                VALID + "{}"
        }) {
            response.set(invalid);
            assertThatThrownBy(() -> client(null).submit(
                    "evidence.command.v1", new byte[]{1}))
                    .isInstanceOf(AppChainClient.AppChainClientException.class)
                    .hasMessageNotContaining(MESSAGE_ID);
        }
    }

    @Test
    void submitBoundsRawResponseAndRejectsNullBodyBeforeIo() throws Exception {
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, 64 * 1024 + 1L);
            exchange.getResponseBody().write(0);
            exchange.getResponseBody().flush();
            exchange.close();
        });

        assertThatThrownBy(() -> client(null).submit("topic", new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain submit response exceeds the client size limit");
        assertThatThrownBy(() -> client(null).submit("topic", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("body");
    }

    @Test
    void submitFailureNeverReflectsResponseOrApiKey() throws Exception {
        String secret = "submit-api-key-canary";
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 503,
                    "{\"code\":\"UNAVAILABLE\",\"error\":\"" + secret + "\"}");
        });

        assertThatThrownBy(() -> client(secret).submit("topic", new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain submit failed with HTTP 503 (UNAVAILABLE)")
                .hasMessageNotContaining(secret)
                .hasNoCause();
    }

    private AppChainClient client(String apiKey) {
        AppChainClient.Builder builder = AppChainClient.builder(
                        "http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .chainId("c1");
        return apiKey == null ? builder.build() : builder.apiKey(apiKey).build();
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1/messages", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                exchange.close();
                throw failure instanceof IOException ioFailure
                        ? ioFailure : new IOException("submit test handler failed", failure);
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
