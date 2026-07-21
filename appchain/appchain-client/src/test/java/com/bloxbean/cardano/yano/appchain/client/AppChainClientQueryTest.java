package com.bloxbean.cardano.yano.appchain.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(60)
class AppChainClientQueryTest {

    private static final String ROOT_HEX = "ab".repeat(32);
    private static final String VALID_RESPONSE = """
            {"chainId":"c1","stateMachineId":"evidence","committedHeight":42,
             "stateRoot":"%s","payloadHex":"00ff"}
            """.formatted(ROOT_HEX);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void queryUsesCanonicalWireAndPreservesSnapshotMetadata() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        startServer(exchange -> {
            method.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getRawPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            respond(exchange, 200, VALID_RESPONSE);
        });

        AppChainClient.QueryResult result = client("query-key")
                .query("passport/read", new byte[]{0x01, (byte) 0xab});

        assertThat(method.get()).isEqualTo("POST");
        assertThat(requestPath.get())
                .isEqualTo("/api/v1/app-chain/chains/c1/query/passport/read");
        assertThat(requestBody.get()).isEqualTo("{\"paramsHex\":\"01ab\"}");
        assertThat(apiKey.get()).isEqualTo("query-key");
        assertThat(result.chainId()).isEqualTo("c1");
        assertThat(result.stateMachineId()).isEqualTo("evidence");
        assertThat(result.committedHeight()).isEqualTo(42);
        assertThat(result.stateRoot()).isEqualTo(Hex.decode(ROOT_HEX));
        assertThat(result.payload()).isEqualTo(new byte[]{0x00, (byte) 0xff});

        byte[] returnedRoot = result.stateRoot();
        byte[] returnedPayload = result.payload();
        returnedRoot[0] = 0;
        returnedPayload[0] = 1;
        assertThat(result.stateRoot()).isEqualTo(Hex.decode(ROOT_HEX));
        assertThat(result.payload()).isEqualTo(new byte[]{0x00, (byte) 0xff});
    }

    @Test
    void queryAcceptsFrozenPathParameterAndResultBoundaries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Integer> requestBytes = new AtomicReference<>();
        String maxPayloadHex = "5a".repeat(1024 * 1024);
        startServer(exchange -> {
            calls.incrementAndGet();
            requestBytes.set(exchange.getRequestBody().readAllBytes().length);
            respond(exchange, 200, """
                    {"chainId":"c1","stateMachineId":"evidence","committedHeight":0,
                     "stateRoot":"%s","payloadHex":"%s"}
                    """.formatted(ROOT_HEX, maxPayloadHex));
        });

        byte[] maxParams = new byte[64 * 1024];
        AppChainClient.QueryResult result = client(null).query("a".repeat(256), maxParams);

        assertThat(calls).hasValue(1);
        assertThat(requestBytes.get()).isEqualTo(2 * maxParams.length + 16);
        assertThat(result.payload()).hasSize(1024 * 1024).containsOnly((byte) 0x5a);
    }

    @Test
    void queryAcceptsExactly128CanonicalPathSegments() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        startServer(exchange -> {
            requestPath.set(exchange.getRequestURI().getRawPath());
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, VALID_RESPONSE);
        });
        String path = String.join("/", java.util.Collections.nCopies(128, "a"));

        client(null).query(path, new byte[0]);

        assertThat(path).hasSize(255);
        assertThat(requestPath.get()).endsWith("/query/" + path);
    }

    @Test
    void queryBindsConfiguredChainIdentity() throws Exception {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, VALID_RESPONSE.replace(
                    "\"chainId\":\"c1\"", "\"chainId\":\"other\""));
        });

        assertThatThrownBy(() -> client(null).query("read", new byte[0]))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain query response chain mismatch");
    }

    @Test
    void queryRequiresAnExplicitNonBlankChainBeforeIo() {
        AppChainClient unscoped = AppChainClient.builder("http://localhost:1/api/v1").build();
        AppChainClient blank = AppChainClient.builder("http://localhost:1/api/v1")
                .chainId(" ")
                .build();

        assertThatThrownBy(() -> unscoped.query("read", new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chainId is required for committed-state queries");
        assertThatThrownBy(() -> blank.query("read", new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chainId is required for committed-state queries");
    }

    @Test
    void queryRejectsNonCanonicalPathsAndOversizedOrNullParamsBeforeIo() {
        AppChainClient client = AppChainClient.builder("http://localhost:1/api/v1")
                .chainId("c1")
                .build();
        List<String> invalidPaths = List.of(
                "", "/read", "read/", "a//b", ".", "..", "a/../b",
                "a%2fb", "a b", "é", "a\u0000b", "_starts-with-punctuation",
                "a".repeat(257), String.join("/", java.util.Collections.nCopies(129, "a")));

        for (String path : invalidPaths) {
            assertThatThrownBy(() -> client.query(path, new byte[0]))
                    .as("path %s", path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("query path");
        }
        assertThatThrownBy(() -> client.query(null, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query path");
        assertThatThrownBy(() -> client.query("read", new byte[64 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 KiB");
        assertThatThrownBy(() -> client.query("read", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("params");
    }

    @Test
    void queryRejectsMalformedMismatchedAndNonCanonicalResponses() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(VALID_RESPONSE);
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, response.get());
        });
        AppChainClient client = client(null);
        String oversizedPayload = "00".repeat(1024 * 1024 + 1);
        List<String> invalidResponses = List.of(
                // Unknown and duplicate fields are rejected by the frozen envelope/parser.
                validResponseFields() + ",\"unexpected\":true}",
                validResponseFields() + ",\"payloadHex\":\"00\"}",
                VALID_RESPONSE + "{}",
                VALID_RESPONSE.replace("\"chainId\":\"c1\"", "\"chainId\":\"other\""),
                VALID_RESPONSE.replace("\"stateMachineId\":\"evidence\"", "\"stateMachineId\":\" \""),
                VALID_RESPONSE.replace("\"committedHeight\":42", "\"committedHeight\":-1"),
                VALID_RESPONSE.replace("\"committedHeight\":42", "\"committedHeight\":1.5"),
                VALID_RESPONSE.replace("\"committedHeight\":42",
                        "\"committedHeight\":9223372036854775808"),
                VALID_RESPONSE.replace(ROOT_HEX, "ab".repeat(31)),
                VALID_RESPONSE.replace(ROOT_HEX, "AB".repeat(32)),
                VALID_RESPONSE.replace("\"payloadHex\":\"00ff\"", "\"payloadHex\":\"0\""),
                VALID_RESPONSE.replace("\"payloadHex\":\"00ff\"", "\"payloadHex\":\"00FF\""),
                VALID_RESPONSE.replace("\"payloadHex\":\"00ff\"",
                        "\"payloadHex\":\"" + oversizedPayload + "\""),
                VALID_RESPONSE.replace("\"stateRoot\":\"" + ROOT_HEX + "\"", "\"stateRoot\":7"));

        for (String invalid : invalidResponses) {
            response.set(invalid);
            assertThatThrownBy(() -> client.query("evidence/read", new byte[0]))
                    .isInstanceOf(AppChainClient.AppChainClientException.class)
                    .hasMessageNotContaining("00ff")
                    .hasMessageNotContaining("unexpected");
        }
    }

    @Test
    void queryBoundsRawResponseBeforeJsonParsing() throws Exception {
        byte[] oversized = new byte[2 * 1024 * 1024 + 64 * 1024 + 1];
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0); // chunked: exercise the streaming bound
            exchange.getResponseBody().write(oversized);
            exchange.close();
        });

        assertThatThrownBy(() -> client(null).query("read", new byte[0]))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain query response exceeds the client size limit");
    }

    @Test
    void queryRejectsDeclaredOversizeBeforeReadingTheBody() throws Exception {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2L * 1024 * 1024 + 64 * 1024 + 1);
            exchange.getResponseBody().write(0);
            exchange.getResponseBody().flush();
            exchange.close();
        });

        assertThatThrownBy(() -> client(null).query("read", new byte[0]))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain query response exceeds the client size limit");
    }

    @Test
    void queryResultCopiesConstructorInputsAndAcceptsServerIdentifierContract() {
        byte[] root = Hex.decode(ROOT_HEX);
        byte[] payload = new byte[]{1, 2, 3};
        String machine = "m".repeat(129) + "\nlegacy";

        AppChainClient.QueryResult result = new AppChainClient.QueryResult(
                "chain", machine, 1, root, payload);
        root[0] = 0;
        payload[0] = 0;

        assertThat(result.stateMachineId()).isEqualTo(machine);
        assertThat(result.stateRoot()).isEqualTo(Hex.decode(ROOT_HEX));
        assertThat(result.payload()).containsExactly(1, 2, 3);
    }

    @Test
    void queryErrorIncludesOnlyBoundedCodeAndNeverResponseOrApiKey() throws Exception {
        String secret = "api-key-canary";
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500,
                    "{\"code\":\"FAILED\",\"error\":\"" + secret + " from provider\"}");
        });

        assertThatThrownBy(() -> client(secret).query("read", new byte[0]))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain query failed with HTTP 500 (FAILED)")
                .hasMessageNotContaining(secret)
                .hasNoCause();
    }

    private String validResponseFields() {
        return VALID_RESPONSE.strip().substring(0, VALID_RESPONSE.strip().length() - 1);
    }

    private AppChainClient client(String apiKey) {
        AppChainClient.Builder builder = AppChainClient.builder(
                        "http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .chainId("c1");
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1/query", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                exchange.close();
                if (failure instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                throw new IOException("test query handler failed", failure);
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
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
