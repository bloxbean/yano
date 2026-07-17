package com.bloxbean.cardano.yano.appchain.spring;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E1.4: the starter auto-configures the client/template from
 * properties and @AppChainListener methods receive finalized messages —
 * against a stubbed node (REST + SSE), no real chain needed.
 */
@Timeout(60)
class AppChainAutoConfigurationTest {

    private static final String MESSAGE_ID = "aabb".repeat(16);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AppChainAutoConfiguration.class));

    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow(); // interrupt held-open SSE handlers
    }

    @Test
    void backsOff_withoutBaseUrl() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AppChainClient.class);
            assertThat(context).doesNotHaveBean(AppChainTemplate.class);
        });
    }

    @Test
    void autoConfigures_andListenerReceivesMessages() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // REST stub: submit + tip
        server.createContext("/api/v1/app-chain/messages", exchange -> {
            byte[] response = ("{\"messageId\":\"" + MESSAGE_ID
                    + "\",\"chainId\":\"c1\",\"topic\":\"orders\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        // SSE stub: two finalized messages, then hold open
        server.createContext("/api/v1/app-chain/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            out.write(("event:app-message\nid:1:0\n"
                    + "data:{\"chainId\":\"c1\",\"height\":1,\"index\":0,\"messageId\":\"m1\","
                    + "\"topic\":\"orders\",\"sender\":\"aa\",\"senderSeq\":1,"
                    + "\"bodyHex\":\"" + hex("order-1") + "\"}\n\n").getBytes(StandardCharsets.UTF_8));
            out.write(("event:app-message\nid:2:0\n"
                    + "data:{\"chainId\":\"c1\",\"height\":2,\"index\":0,\"messageId\":\"m2\","
                    + "\"topic\":\"orders\",\"sender\":\"aa\",\"senderSeq\":2,"
                    + "\"bodyHex\":\"" + hex("order-2") + "\"}\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
            }
            exchange.close();
        });
        // A real executor — the SSE handler holds its connection open, which
        // would wedge the default single dispatch thread and block the REST stub.
        serverExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverExecutor);
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v1";

        runner.withPropertyValues(
                        "yano.appchain.client.base-url=" + baseUrl,
                        "yano.appchain.client.api-key=k1")
                .withUserConfiguration(ListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AppChainClient.class);
                    assertThat(context).hasSingleBean(AppChainTemplate.class);

                    // Template submit against the stub
                    AppChainTemplate template = context.getBean(AppChainTemplate.class);
                    assertThat(template.send("orders", "hello")).isEqualTo(MESSAGE_ID);

                    // @AppChainListener methods received the SSE messages,
                    // with String/byte[]/StreamedMessage parameter conversion
                    OrderHandler handler = context.getBean(OrderHandler.class);
                    assertThat(handler.latch.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(handler.texts).containsExactly("order-1", "order-2");
                    assertThat(handler.envelopes).hasSize(2);
                    assertThat(handler.envelopes.get(0).messageId()).isEqualTo("m1");
                });
    }

    @Test
    void invalidListenerSignature_failsStartup() {
        runner.withPropertyValues("yano.appchain.client.base-url=http://localhost:1/api/v1")
                .withUserConfiguration(BadListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root.getMessage())
                            .contains("@AppChainListener")
                            .contains("exactly one parameter");
                });
    }

    @Configuration
    static class ListenerConfig {
        @Bean
        OrderHandler orderHandler() {
            return new OrderHandler();
        }
    }

    static class OrderHandler {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final List<AppChainClient.StreamedMessage> envelopes = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(4); // 2 msgs × 2 listeners

        @AppChainListener(topic = "orders")
        void onText(String body) {
            texts.add(body);
            latch.countDown();
        }

        @AppChainListener(topic = "orders")
        void onEnvelope(AppChainClient.StreamedMessage message) {
            envelopes.add(message);
            latch.countDown();
        }
    }

    @Configuration
    static class BadListenerConfig {
        @Bean
        BadHandler badHandler() {
            return new BadHandler();
        }
    }

    static class BadHandler {
        @AppChainListener(topic = "x")
        void wrongSignature(int nope) {
        }
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
