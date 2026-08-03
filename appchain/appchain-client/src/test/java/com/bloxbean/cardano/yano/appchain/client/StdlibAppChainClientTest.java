package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class StdlibAppChainClientTest {
    private static final String MESSAGE_ID = "ab".repeat(32);
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void typedFacadeSubmitsCanonicalStockContractBytes() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1/messages", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 202, """
                    {"messageId":"%s","chainId":"c1","topic":"%s"}
                    """.formatted(MESSAGE_ID, BalancesContract.DEFAULT_TOPIC));
        });
        server.start();
        AppChainClient raw = AppChainClient.builder(
                        "http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .chainId("c1").build();

        AppChainClient.SubmitResult result =
                new StdlibAppChainClient(raw).transfer("acct-2", BigInteger.TEN);

        var body = json.readTree(request.get());
        assertThat(body.path("topic").asText()).isEqualTo(BalancesContract.DEFAULT_TOPIC);
        assertThat(BalancesContract.decodeCommand(
                Hex.decode(body.path("bodyHex").asText())).amount()).isEqualTo(BigInteger.TEN);
        assertThat(result.messageId()).isEqualTo(MESSAGE_ID);
    }

    @Test
    void authenticatedMapFacadeUsesFrozenCommandAndRootBoundQueryDtos() throws Exception {
        AtomicReference<String> submitRequest = new AtomicReference<>();
        AtomicReference<String> queryRequest = new AtomicReference<>();
        byte[] root = Hex.decode("44".repeat(32));
        byte[] key = "sku-1".getBytes(StandardCharsets.US_ASCII);
        AuthenticatedMapContract.Entry entry = AuthenticatedMapContract.Entry.active(
                1, Hex.decode("11".repeat(32)),
                "value".getBytes(StandardCharsets.US_ASCII), 3, 3);
        byte[] payload = AuthenticatedMapContract.encodePointResult(
                new AuthenticatedMapContract.PointResult(
                        3, root, "products", key,
                        AuthenticatedMapContract.PRESENCE_ACTIVE, entry));

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1/messages", exchange -> {
            submitRequest.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 202, """
                    {"messageId":"%s","chainId":"c1","topic":"%s"}
                    """.formatted(MESSAGE_ID, AuthenticatedMapContract.DEFAULT_TOPIC));
        });
        server.createContext(
                "/api/v1/app-chain/chains/c1/query/authenticated-map/entry-v1",
                exchange -> {
                    queryRequest.set(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));
                    respond(exchange, 200, """
                            {"chainId":"c1","stateMachineId":"authenticated-map",
                             "committedHeight":3,"stateRoot":"%s","payloadHex":"%s"}
                            """.formatted(Hex.encode(root), Hex.encode(payload)));
                });
        server.start();
        StdlibAppChainClient client = new StdlibAppChainClient(AppChainClient.builder(
                        "http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .chainId("c1").build());

        client.authenticatedMapMutate(AuthenticatedMapContract.Mutation.put(
                "products", key, "value".getBytes(StandardCharsets.US_ASCII)));
        AuthenticatedMapContract.PointResult result =
                client.authenticatedMapEntry("products", key);

        var submitted = json.readTree(submitRequest.get());
        AuthenticatedMapContract.Command decodedCommand =
                AuthenticatedMapContract.decodeCommand(
                        Hex.decode(submitted.path("bodyHex").asText()));
        assertThat(submitted.path("topic").asText())
                .isEqualTo(AuthenticatedMapContract.DEFAULT_TOPIC);
        assertThat(decodedCommand.mutations()).hasSize(1);
        assertThat(result.entry().value()).isEqualTo("value".getBytes(StandardCharsets.US_ASCII));

        var queried = json.readTree(queryRequest.get());
        AuthenticatedMapContract.PointQuery decodedQuery =
                AuthenticatedMapContract.decodePointQuery(
                        Hex.decode(queried.path("paramsHex").asText()));
        assertThat(decodedQuery.collectionId()).isEqualTo("products");
        assertThat(decodedQuery.applicationKey()).isEqualTo(key);
    }

    @Test
    void authenticatedMapPreflightFailureNeverReachesHttpSubmission() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1/messages", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "{}");
        });
        server.start();
        StdlibAppChainClient client = new StdlibAppChainClient(AppChainClient.builder(
                        "http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .chainId("c1").build());
        AuthenticatedMapContract.Genesis genesis = new AuthenticatedMapContract.Genesis(
                "c1", AuthenticatedMapContract.PROFILE_MPF_BLAKE2B256_V1,
                new byte[32], new byte[32], new byte[32], new byte[32],
                16, 1024,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "records", AuthenticatedMapContract.AUTH_OPEN, false,
                        1, 1)),
                List.of());
        AuthenticatedMapPreflight preflight = AuthenticatedMapPreflight.fromGenesis(genesis);

        assertThatThrownBy(() -> client.authenticatedMapMutate(
                AuthenticatedMapContract.Mutation.put(
                        "records", new byte[]{1}, new byte[]{1, 2}), preflight))
                .isInstanceOf(AuthenticatedMapPreflight.PreflightException.class);
        assertThat(requests).hasValue(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
