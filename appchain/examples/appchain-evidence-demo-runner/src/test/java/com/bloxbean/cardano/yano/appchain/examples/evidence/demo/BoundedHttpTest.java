package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock("java.net.ProxySelector.default")
class BoundedHttpTest {
    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] body = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void neverFollowsRedirectsAndBoundsUndeclaredBodies() {
        BoundedHttp client = new BoundedHttp(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(client.get(base.resolve("/redirect"), Map.of(), 32).status()).isEqualTo(302);
        assertThatThrownBy(() -> client.get(base.resolve("/large"), Map.of(), 10))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }

    @Test
    void ignoresAmbientProxySelectorForCredentialBearingRequests() {
        ProxySelector original = ProxySelector.getDefault();
        AtomicInteger selected = new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                selected.incrementAndGet();
                return List.of(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", 1)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress socketAddress,
                                      java.io.IOException failure) {
            }
        });
        try {
            BoundedHttp client = new BoundedHttp(Duration.ofSeconds(1), Duration.ofSeconds(2));
            assertThat(client.get(base.resolve("/ok"), Map.of("X-API-Key", "secret"), 32)
                    .status()).isEqualTo(200);
            assertThat(selected).hasValue(0);
        } finally {
            ProxySelector.setDefault(original);
        }
    }
}
