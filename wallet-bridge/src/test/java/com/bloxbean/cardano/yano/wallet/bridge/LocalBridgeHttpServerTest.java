package com.bloxbean.cardano.yano.wallet.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalBridgeHttpServerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void hasReservedDefaultEndpoint() {
        assertThat(LocalBridgeHttpServer.DEFAULT_PORT).isEqualTo(47_000);
        assertThat(LocalBridgeHttpServer.defaultEndpointUri()).hasToString("http://127.0.0.1:47000/cip30");
    }

    @Test
    void servesReadMethodsOverLoopbackHttpWhenOriginIsAllowed() throws Exception {
        LocalCip30BridgeService service = service(new FakeBackend(), request -> true);
        try (LocalBridgeHttpServer server = start(
                service,
                (origin, permissions) -> origin.equals("http://localhost:3000"))) {
            JsonNode enable = post(server.endpointUri(), Map.of(
                    "method", "enable",
                    "origin", "http://localhost:3000",
                    "permissions", List.of("READ_WALLET")));
            String token = enable.get("result").get("token").asText();

            JsonNode networkId = post(server.endpointUri(), Map.of("method", "getNetworkId", "token", token));
            JsonNode balance = post(server.endpointUri(), Map.of("method", "getBalance", "token", token));
            JsonNode utxos = post(server.endpointUri(), Map.of("method", "getUtxos", "token", token));

            assertThat(networkId.get("success").asBoolean()).isTrue();
            assertThat(networkId.get("result").asInt()).isZero();
            assertThat(balance.get("result").asText()).isEqualTo("1a001e8480");
            assertThat(utxos.get("result")).hasSize(1);
        }
    }

    @Test
    void refusesEnableWhenOriginPolicyRejectsRequest() throws Exception {
        try (LocalBridgeHttpServer server = start(
                service(new FakeBackend(), request -> true),
                BridgeHttpAccessPolicy.denyAll())) {
            HttpResponse<String> response = rawPost(server.endpointUri(), Map.of(
                    "method", "enable",
                    "origin", "http://evil.example",
                    "permissions", List.of("READ_WALLET")));
            JsonNode body = objectMapper.readTree(response.body());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("error").get("code").asText()).isEqualTo("REFUSED");
        }
    }

    @Test
    void refusesEnableWhenBodyOriginDoesNotMatchBrowserOriginHeader() throws Exception {
        try (LocalBridgeHttpServer server = start(
                service(new FakeBackend(), request -> true),
                (origin, permissions) -> origin.equals("http://localhost:3000"))) {
            HttpResponse<String> response = rawPost(server.endpointUri(), Map.of(
                    "method", "enable",
                    "origin", "http://localhost:3000",
                    "permissions", List.of("READ_WALLET")), "http://evil.example");
            JsonNode body = objectMapper.readTree(response.body());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("error").get("code").asText()).isEqualTo("REFUSED");
            assertThat(body.get("error").get("message").asText()).contains("origin mismatch");
        }
    }

    @Test
    void refusesTokenUseFromDifferentBrowserOrigin() throws Exception {
        try (LocalBridgeHttpServer server = start(
                service(new FakeBackend(), request -> true),
                (origin, permissions) -> origin.equals("http://localhost:3000"))) {
            JsonNode enable = post(server.endpointUri(), Map.of(
                    "method", "enable",
                    "origin", "http://localhost:3000",
                    "permissions", List.of("READ_WALLET")));
            String token = enable.get("result").get("token").asText();

            HttpResponse<String> response = rawPost(server.endpointUri(), Map.of(
                    "method", "getBalance",
                    "token", token), "http://evil.example");
            JsonNode body = objectMapper.readTree(response.body());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("error").get("code").asText()).isEqualTo("REFUSED");
            assertThat(body.get("error").get("method").asText()).isEqualTo("getBalance");
        }
    }

    @Test
    void returnsRefusedWhenSignApprovalIsRejected() throws Exception {
        LocalCip30BridgeService service = service(new FakeBackend(), request -> false);
        try (LocalBridgeHttpServer server = start(service, (origin, permissions) -> true)) {
            JsonNode enable = post(server.endpointUri(), Map.of(
                    "method", "enable",
                    "origin", "http://localhost:3000",
                    "permissions", List.of("SIGN_TX")));
            String token = enable.get("result").get("token").asText();

            HttpResponse<String> response = rawPost(server.endpointUri(), Map.of(
                    "method", "signTx",
                    "token", token,
                    "txCborHex", "84a400",
                    "partialSign", true));
            JsonNode body = objectMapper.readTree(response.body());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("error").get("method").asText()).isEqualTo("signTx");
        }
    }

    @Test
    void servesJavascriptCip30Shim() throws Exception {
        try (LocalBridgeHttpServer server = start(
                service(new FakeBackend(), request -> true),
                (origin, permissions) -> true)) {
            URI scriptUri = server.endpointUri().resolve("/yano-cip30.js");
            HttpRequest request = HttpRequest.newBuilder(scriptUri)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/javascript; charset=utf-8");
            assertThat(response.body()).contains("window.cardano.yano");
            assertThat(response.body()).contains("window.yanoWallet");
            assertThat(response.body()).contains("getBalance");
            assertThat(response.body()).contains("signTx");
            assertThat(response.body()).contains("submitTx");
            assertThat(response.body()).contains("origin: window.location.origin");
            assertThat(response.body()).contains("http://127.0.0.1:47000");
            assertThat(response.body()).doesNotContain("localStorage");
        }
    }

    private LocalBridgeHttpServer start(
            LocalCip30BridgeService service,
            BridgeHttpAccessPolicy accessPolicy) {
        return LocalBridgeHttpServer.start(service, accessPolicy, 0);
    }

    private JsonNode post(URI uri, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = rawPost(uri, body);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();
        return json;
    }

    private HttpResponse<String> rawPost(URI uri, Map<String, Object> body) throws Exception {
        return rawPost(uri, body, "http://localhost:3000");
    }

    private HttpResponse<String> rawPost(URI uri, Map<String, Object> body, String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private LocalCip30BridgeService service(FakeBackend backend, BridgeApprovalHandler approvalHandler) {
        return new LocalCip30BridgeService(new InMemoryBridgeSessionRegistry(), backend, approvalHandler);
    }

    private static class FakeBackend implements BridgeWalletBackend {
        @Override
        public int networkId() {
            return 0;
        }

        @Override
        public String balanceCborHex() {
            return "1a001e8480";
        }

        @Override
        public List<String> utxosCborHex() {
            return List.of("82825820aa");
        }

        @Override
        public String changeAddressHex() {
            return "abcd";
        }

        @Override
        public List<String> rewardAddressHexes() {
            return List.of("dcba");
        }

        @Override
        public BridgeSignTxResult signTx(String txCborHex, boolean partialSign) {
            return new BridgeSignTxResult("a100");
        }

        @Override
        public String submitTx(String txCborHex) {
            return "c".repeat(64);
        }
    }
}
