package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KuboClientTest {
    private static final String CID =
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi";

    private HttpServer server;
    private KuboClient client;
    private final AtomicReference<Response> pinResponse = new AtomicReference<>();
    private final AtomicReference<byte[]> catResponse = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v0/version", exchange -> json(exchange, 200,
                "{\"Version\":\"0.42.0\"}"));
        server.createContext("/api/v0/add", exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery())
                    .contains("pin=false", "cid-version=1", "raw-leaves=true");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                    .startsWith("multipart/form-data; boundary=");
            assertThat(exchange.getRequestBody().readAllBytes()).contains((byte) 'e');
            json(exchange, 200, "{\"Name\":\"inspection-certificate.bin\","
                    + "\"Hash\":\"" + CID + "\",\"Size\":\"8\"}");
        });
        server.createContext("/api/v0/pin/ls", exchange -> {
            Response response = pinResponse.get();
            json(exchange, response.status(), response.body());
        });
        server.createContext("/api/v0/cat", exchange -> {
            byte[] body = catResponse.get();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new KuboClient(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void probesAddsUnpinnedAndRequiresExactRecursivePinState() {
        client.probe();
        CanonicalCid cid = client.addUnpinned("evidence".getBytes(StandardCharsets.UTF_8));
        assertThat(cid.canonicalText()).isEqualTo(CID);

        pinResponse.set(new Response(200,
                "{\"Keys\":{\"" + CID + "\":{\"Type\":\"recursive\"}}}"));
        assertThat(client.requiredPinPresent(cid, true)).isTrue();
        catResponse.set("evidence".getBytes(StandardCharsets.UTF_8));
        client.requireContent(cid, "evidence".getBytes(StandardCharsets.UTF_8));

        pinResponse.set(new Response(200,
                "{\"Keys\":{\"" + CID + "\":{\"Type\":\"direct\"}}}"));
        assertThat(client.requiredPinPresent(cid, true)).isFalse();
        assertThat(client.requiredPinPresent(cid, false)).isTrue();
    }

    @Test
    void rejectsWrongContentAndChoosesBoundaryAbsentFromDocument() {
        CanonicalCid cid = CanonicalCid.fromText(CID);
        catResponse.set("different".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client.requireContent(
                cid, "evidence".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);

        byte[] document = "contains-yano-evidence-demo-v1".getBytes(StandardCharsets.UTF_8);
        assertThat(new String(document, StandardCharsets.UTF_8))
                .doesNotContain(KuboClient.boundaryFor(document));
    }

    @Test
    void mapsOnlyExactKnownKubo500ToAbsentAndRejectsDuplicateJson() {
        CanonicalCid cid = CanonicalCid.fromText(CID);
        pinResponse.set(new Response(500, "{\"Message\":\"path '" + CID
                + "' is not pinned\",\"Code\":0,\"Type\":\"error\"}"));
        assertThat(client.pinState(cid)).isEqualTo(KuboClient.PinState.ABSENT);

        pinResponse.set(new Response(500,
                "{\"Message\":\"backend unavailable\",\"Code\":0,\"Type\":\"error\"}"));
        assertThatThrownBy(() -> client.pinState(cid))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.SERVICE_TIMEOUT);

        assertThatThrownBy(() -> StrictJson.parse(
                "{\"Version\":\"0.42.0\",\"Version\":\"evil\"}"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DemoException.class);
    }

    private static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Response(int status, String body) {
    }
}
