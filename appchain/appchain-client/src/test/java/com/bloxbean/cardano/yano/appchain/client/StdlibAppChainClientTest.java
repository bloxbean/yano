package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
