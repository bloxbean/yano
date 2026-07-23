package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void typedFacadeUsesFrozenTopicPathsAndCodecs() throws Exception {
        EutxoOutpoint outpoint = new EutxoOutpoint("11".repeat(32), 2);
        EutxoRecord record = new EutxoRecord(
                outpoint,
                "addr_test1vr0typedclient",
                new byte[]{(byte) 0x82, 0x01, 0x02},
                EutxoRecord.Origin.TRANSACTION);
        AtomicReference<String> submitBody = new AtomicReference<>();
        AtomicReference<String> queryBody = new AtomicReference<>();
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.endsWith("/messages")) {
                submitBody.set(body);
                respond(exchange, 202, """
                        {"messageId":"%s","chainId":"eutxo","topic":"eutxo.transactions"}
                        """.formatted("22".repeat(32)));
                return;
            }
            queryBody.set(body);
            byte[] payload = path.endsWith("/query/utxos/outpoint")
                    ? EutxoQueryCodec.optionalRecord(record)
                    : "profile-digest".getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, """
                    {"chainId":"eutxo","stateMachineId":"eutxo-ledger",
                     "committedHeight":7,"stateRoot":"%s","payloadHex":"%s"}
                    """.formatted("33".repeat(32), HexFormat.of().formatHex(payload)));
        });
        EutxoClient client = new EutxoClient(AppChainClient.builder(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1")
                .chainId("eutxo")
                .build());

        AppChainClient.SubmitResult submitted = client.submit(new byte[]{0x01, 0x02});
        assertThat(submitted.topic()).isEqualTo("eutxo.transactions");
        assertThat(submitBody.get())
                .isEqualTo("{\"topic\":\"eutxo.transactions\",\"bodyHex\":\"0102\"}");

        assertThat(client.utxo(outpoint)).contains(record);
        assertThat(queryBody.get()).isEqualTo(
                "{\"paramsHex\":\"" + HexFormat.of().formatHex(
                        EutxoQueryCodec.outpointRequest(outpoint)) + "\"}");
        assertThat(client.profileDigest()).isEqualTo("profile-digest");
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
