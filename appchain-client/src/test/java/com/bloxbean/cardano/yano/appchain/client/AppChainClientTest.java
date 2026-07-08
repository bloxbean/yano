package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client SDK behavior against a stubbed node: REST parsing, API-key header,
 * SSE consumption, and REAL client-side MPF proof verification (proof built
 * with a local trie, verified via the SDK with no node involved).
 */
@Timeout(60)
class AppChainClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void restSurface_parsesAndSendsApiKey() throws Exception {
        List<String> seenApiKeys = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1/messages", exchange -> {
            seenApiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] response;
            int status;
            if ("POST".equals(exchange.getRequestMethod())) {
                response = "{\"messageId\":\"aabb\",\"chainId\":\"c1\",\"topic\":\"orders\"}"
                        .getBytes(StandardCharsets.UTF_8);
                status = 202;
            } else {
                response = ("[{\"messageId\":\"aabb\",\"chainId\":\"c1\",\"topic\":\"orders\","
                        + "\"sender\":\"cc\",\"senderSeq\":7,\"bodyHex\":\"68690a\",\"source\":\"PEER\"}]")
                        .getBytes(StandardCharsets.UTF_8);
                status = 200;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/v1/app-chain/chains/c1/tip", exchange -> {
            byte[] response = "{\"chainId\":\"c1\",\"height\":5,\"stateRoot\":\"beef\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AppChainClient client = AppChainClient.builder(
                        "http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .chainId("c1")
                .apiKey("secret-key")
                .build();

        AppChainClient.SubmitResult submit = client.submitText("orders", "hi");
        assertThat(submit.messageId()).isEqualTo("aabb");

        List<AppChainClient.Message> messages = client.messages(10, "orders");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).senderSeq()).isEqualTo(7);
        assertThat(messages.get(0).body()).isEqualTo(new byte[]{0x68, 0x69, 0x0a});

        AppChainClient.Tip tip = client.tip();
        assertThat(tip.height()).isEqualTo(5);

        assertThat(seenApiKeys).allMatch("secret-key"::equals);
    }

    @Test
    void sseSubscribe_receivesMessages() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            out.write(("event:app-message\nid:1:0\n"
                    + "data:{\"chainId\":\"c1\",\"height\":1,\"index\":0,\"messageId\":\"m1\","
                    + "\"topic\":\"t\",\"sender\":\"aa\",\"senderSeq\":1,\"bodyHex\":\"01\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("event:heartbeat\ndata:1\n\n").getBytes(StandardCharsets.UTF_8));
            out.write(("event:app-message\nid:2:0\n"
                    + "data:{\"chainId\":\"c1\",\"height\":2,\"index\":0,\"messageId\":\"m2\","
                    + "\"topic\":\"t\",\"sender\":\"aa\",\"senderSeq\":2,\"bodyHex\":\"02\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(60_000); // keep the stream open until the client closes
            } catch (InterruptedException ignored) {
            }
            exchange.close();
        });
        server.start();

        AppChainClient client = AppChainClient.builder(
                "http://localhost:" + server.getAddress().getPort() + "/api/v1").build();

        List<AppChainClient.StreamedMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        AutoCloseable subscription = client.subscribe(-1, null, message -> {
            received.add(message);
            latch.countDown();
        });

        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        subscription.close();

        assertThat(received).hasSize(2);
        assertThat(received.get(0).messageId()).isEqualTo("m1");
        assertThat(received.get(1).height()).isEqualTo(2);
    }

    /** In-memory store for building a trie in tests (no dependency on MPF internals). */
    private static final class MapNodeStore implements NodeStore {
        private final java.util.Map<String, byte[]> map = new java.util.HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return map.get(Hex.encode(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            map.put(Hex.encode(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            map.remove(Hex.encode(hash));
        }
    }

    @Test
    void proofVerification_realMpfProof_noNodeInvolved() {
        // Build a real trie locally (playing the role of the node's state)
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        byte[] key = "order-123".getBytes(StandardCharsets.UTF_8);
        byte[] value = "approved".getBytes(StandardCharsets.UTF_8);
        trie.put(key, value);
        trie.put("other".getBytes(StandardCharsets.UTF_8), "x".getBytes(StandardCharsets.UTF_8));
        byte[] root = trie.getRootHash();
        byte[] proofWire = trie.getProofWire(key).orElseThrow();

        AppChainClient.Proof proof = new AppChainClient.Proof(
                Hex.encode(key), "c1", Hex.encode(root),
                Hex.encode(proofWire), Hex.encode(value), 3L);

        // The SDK verifies with zero node access
        assertThat(ProofVerifier.verify(proof)).isTrue();

        // Against an independently obtained (anchored) root
        assertThat(ProofVerifier.verify(proof, Hex.encode(root))).isTrue();

        // Tampered value or wrong root fails closed
        AppChainClient.Proof tampered = new AppChainClient.Proof(
                Hex.encode(key), "c1", Hex.encode(root),
                Hex.encode(proofWire), Hex.encode("REJECTED".getBytes(StandardCharsets.UTF_8)), 3L);
        assertThat(ProofVerifier.verify(tampered)).isFalse();
        assertThat(ProofVerifier.verify(proof, Hex.encode(new byte[32]))).isFalse();
    }

    @Test
    void hexRoundTrip() {
        byte[] bytes = {0x00, 0x01, (byte) 0xab, (byte) 0xff};
        assertThat(Hex.decode(Hex.encode(bytes))).isEqualTo(bytes);
        assertThat(Hex.encode(bytes)).isEqualTo("0001abff");
    }
}
