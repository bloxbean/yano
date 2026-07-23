package com.bloxbean.cardano.yano.appchain.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainClientDirectConnectionTest {
    @Test
    @ResourceLock("java.net.ProxySelector.default")
    void directConnectionsBypassesAmbientProxyWithoutDroppingApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> apiKey = new AtomicReference<>();
        server.createContext("/api/v1/app-chain/chains/evidence-chain/status", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] body = "{\"running\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ProxySelector original = ProxySelector.getDefault();
        AtomicInteger ambientSelections = new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                ambientSelections.incrementAndGet();
                return List.of(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", 1)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address,
                                      java.io.IOException failure) {
            }
        });
        try {
            AppChainClient client = AppChainClient.builder(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1")
                    .chainId("evidence-chain")
                    .apiKey("private-api-key")
                    .directConnections()
                    .build();
            assertThat(client.status().path("running").asBoolean()).isTrue();
            assertThat(apiKey).hasValue("private-api-key");
            assertThat(ambientSelections).hasValue(0);
        } finally {
            ProxySelector.setDefault(original);
            server.stop(0);
        }
    }
}
