package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceScenarioL1VisibilityTest {
    private static final String SUBMIT_TX = "10".repeat(32);
    private static final String NOTIFY_TX = "20".repeat(32);
    private static final Set<String> MEMBERS = Set.of(
            "01".repeat(32), "02".repeat(32), "03".repeat(32));

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void requiresEveryDistinctPortableTransactionOnEveryNode() throws Exception {
        List<AtomicInteger> calls = List.of(
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        List<YanoAuditClient> clients = new ArrayList<>();
        for (AtomicInteger callCount : calls) {
            clients.add(client(Set.of(), callCount));
        }

        assertThat(EvidenceScenario.allTransactionsVisibleOnce(
                clients, List.of(SUBMIT_TX, NOTIFY_TX))).isTrue();
        assertThat(calls).allSatisfy(count -> assertThat(count).hasValue(2));
    }

    @Test
    void failsWhenOnlyOneNodeCannotSeeOnlyOneOfThePortableTransactions() throws Exception {
        List<YanoAuditClient> clients = List.of(
                client(Set.of(), new AtomicInteger()),
                client(Set.of(), new AtomicInteger()),
                client(Set.of(NOTIFY_TX), new AtomicInteger()));

        assertThat(EvidenceScenario.allTransactionsVisibleOnce(
                clients, List.of(SUBMIT_TX, NOTIFY_TX))).isFalse();
        assertThatThrownBy(() -> EvidenceScenario.allTransactionsVisibleOnce(
                clients.subList(0, 2), List.of(SUBMIT_TX)))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.ANCHOR_UNAVAILABLE);
    }

    private YanoAuditClient client(Set<String> missing, AtomicInteger calls) throws IOException {
        Set<String> absent = new HashSet<>(missing);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/txs/", exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String hash = path.substring(path.lastIndexOf('/') + 1);
            if (absent.contains(hash)) {
                json(exchange, 404, "{\"error\":\"not found\"}");
            } else {
                json(exchange, 200, "{\"hash\":\"" + hash + "\","
                        + "\"block_height\":7,\"slot\":9,\"valid_contract\":true}");
            }
        });
        server.start();
        servers.add(server);
        return new YanoAuditClient(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                "evidence-chain", MEMBERS, 2, null);
    }

    private static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
