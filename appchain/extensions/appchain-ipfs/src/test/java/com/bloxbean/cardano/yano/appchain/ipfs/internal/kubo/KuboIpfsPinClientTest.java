package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClient;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class KuboIpfsPinClientTest {
    private static final String RAW_CID =
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi";
    private static final String OTHER_RAW_CID =
            "bafkreiearwio3sbkwietj4uq3ncur26yidsh6x6wac5bl4nleubh6jdq7y";
    private static final String CID_V0 =
            "QmYwAPJzv5CZsnAzt8auVZRnGiR7JagPyV7LSKmhQfBfZP";
    private static final byte[] EFFECT_ID = HexFormat.of().parseHex("ab".repeat(32));

    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI endpoint;
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final List<CapturedRequest> requests = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void sendsOnlyTheFixedProbeRpcWithTraceAndConfiguredBearer() {
        respondJson(200, pinState(RAW_CID, "recursive", true));

        try (IpfsPinClient client = client(Optional.of("test.token-1"))) {
            assertThat(client.probe(cid(RAW_CID), EFFECT_ID)).isEqualTo(PinState.RECURSIVE);
        }

        assertThat(requests).hasSize(1);
        CapturedRequest request = requests.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getRawPath()).isEqualTo("/api/v0/pin/ls");
        assertThat(request.uri().getRawQuery()).isEqualTo("arg=" + RAW_CID + "&type=all");
        assertThat(request.header("X-Yano-Effect-Id")).isEqualTo("ab".repeat(32));
        assertThat(request.header("Authorization")).isEqualTo("Bearer test.token-1");
        assertThat(request.body()).isEmpty();
    }

    @Test
    void sendsOnlyTheFixedAddRpcAndValidatesItsCidAcknowledgement() {
        respondJson(200, "{\"Pins\":[\"" + RAW_CID + "\"]}");

        try (IpfsPinClient client = client(Optional.empty())) {
            client.add(cid(RAW_CID), false, EFFECT_ID);
        }

        CapturedRequest request = requests.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getRawPath()).isEqualTo("/api/v0/pin/add");
        assertThat(request.uri().getRawQuery())
                .isEqualTo("arg=" + RAW_CID + "&recursive=false&progress=false");
        assertThat(request.header("Authorization")).isNull();
        assertThat(request.header("X-Yano-Effect-Id")).isEqualTo("ab".repeat(32));
    }

    @Test
    void mapsAllExactKuboPinStates() {
        Map<String, PinState> states = Map.of(
                "direct", PinState.DIRECT,
                "recursive", PinState.RECURSIVE,
                "indirect", PinState.INDIRECT);

        try (IpfsPinClient client = client(Optional.empty())) {
            for (Map.Entry<String, PinState> state : states.entrySet()) {
                respondJson(200, pinState(RAW_CID, state.getKey(), true));
                assertThat(client.probe(cid(RAW_CID), EFFECT_ID)).isEqualTo(state.getValue());
            }
        }
    }

    @Test
    void acceptsOnlyAValidBoundedAncestorInKuboIndirectThroughState() {
        List<String> validAncestors = List.of(OTHER_RAW_CID, CID_V0);
        try (IpfsPinClient client = client(Optional.empty())) {
            for (String ancestor : validAncestors) {
                respondJson(200, pinState(RAW_CID, "indirect through " + ancestor, true));
                assertThat(client.probe(cid(RAW_CID), EFFECT_ID)).isEqualTo(PinState.INDIRECT);
            }
            for (String invalid : List.of(
                    "indirect through ",
                    "indirect through not-a-cid",
                    "indirect through " + OTHER_RAW_CID + " trailing",
                    "indirect through " + "x".repeat(513))) {
                respondJson(200, pinState(RAW_CID, invalid, true));
                assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                        ConnectorErrorCode.PROVIDER_REJECTED);
            }
        }
    }

    @Test
    void acceptsTheOlderExactPinShapeWithoutAnEmptyName() {
        respondJson(200, pinState(RAW_CID, "direct", false));

        try (IpfsPinClient client = client(Optional.empty())) {
            assertThat(client.probe(cid(RAW_CID), EFFECT_ID)).isEqualTo(PinState.DIRECT);
        }
    }

    @Test
    void recognizesOnlyTheExactKuboNotPinnedEnvelopeAsAbsent() {
        String body = "{\"Message\":\"path '" + RAW_CID
                + "' is not pinned\",\"Code\":0,\"Type\":\"error\"}";
        respondJson(500, body);

        try (IpfsPinClient client = client(Optional.empty())) {
            assertThat(client.probe(cid(RAW_CID), EFFECT_ID)).isEqualTo(PinState.ABSENT);
        }
    }

    @Test
    void malformedKubo500ResponsesRemainServiceUnavailable() {
        List<byte[]> malformed = List.of(
                "{".getBytes(StandardCharsets.UTF_8),
                "<html>upstream failure</html>".getBytes(StandardCharsets.UTF_8),
                (kuboError("unknown") + " {}").getBytes(StandardCharsets.UTF_8),
                "{\"Message\":\"unknown\",\"Code\":0,\"Type\":\"error\",\"extra\":1}"
                        .getBytes(StandardCharsets.UTF_8),
                new byte[KuboIpfsPinClient.MAX_RESPONSE_BYTES + 1]);

        try (IpfsPinClient client = client(Optional.empty())) {
            for (byte[] body : malformed) {
                responder.set(exchange -> send(exchange, 500, body));
                assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                        ConnectorErrorCode.SERVICE_UNAVAILABLE);
            }
            responder.set(exchange -> sendChunked(exchange, 500,
                    new byte[KuboIpfsPinClient.MAX_RESPONSE_BYTES + 1]));
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void acceptsEquivalentCidTextOnlyAfterCanonicalByteComparison() {
        CanonicalCid canonical = cid(CID_V0);
        responder.set(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/pin/ls")) {
                sendJson(exchange, 200, pinState(CID_V0, "recursive", true));
            } else {
                sendJson(exchange, 200, "{\"Pins\":[\"" + CID_V0 + "\"]}");
            }
        });

        try (IpfsPinClient client = client(Optional.empty())) {
            assertThat(client.probe(canonical, EFFECT_ID)).isEqualTo(PinState.RECURSIVE);
            client.add(canonical, true, EFFECT_ID);
        }

        assertThat(requests.get(0).uri().getRawQuery())
                .startsWith("arg=" + canonical.canonicalText() + "&");
        assertThat(requests.get(1).uri().getRawQuery())
                .startsWith("arg=" + canonical.canonicalText() + "&");
    }

    @Test
    void rejectsMismatchedInvalidAndUnexpectedPinEntries() {
        List<String> bodies = List.of(
                pinState(OTHER_RAW_CID, "recursive", true),
                pinState("not-a-cid", "recursive", true),
                pinState(RAW_CID, "unknown", true),
                "{\"Keys\":{}}",
                "{\"Keys\":{\"" + RAW_CID + "\":{\"Type\":\"direct\",\"Name\":\"named\"}}}",
                "{\"Keys\":{\"" + RAW_CID
                        + "\":{\"Type\":\"direct\",\"Unexpected\":false}}}");

        try (IpfsPinClient client = client(Optional.empty())) {
            for (String body : bodies) {
                respondJson(200, body);
                assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                        ConnectorErrorCode.PROVIDER_REJECTED);
            }
        }
    }

    @Test
    void rejectsMalformedDuplicateTrailingAndOversizedResponses() {
        List<byte[]> bodies = List.of(
                "{".getBytes(StandardCharsets.UTF_8),
                ("{\"Keys\":{},\"Keys\":{" + jsonQuoted(RAW_CID)
                        + ":{\"Type\":\"direct\"}}}").getBytes(StandardCharsets.UTF_8),
                (pinState(RAW_CID, "direct", false) + " {}").getBytes(StandardCharsets.UTF_8),
                ("{\"Keys\":{\"" + RAW_CID + "\":{\"Type\":\"direct\"}},\"extra\":1}")
                        .getBytes(StandardCharsets.UTF_8),
                "x".repeat(KuboIpfsPinClient.MAX_RESPONSE_BYTES + 1)
                        .getBytes(StandardCharsets.UTF_8));

        try (IpfsPinClient client = client(Optional.empty())) {
            for (byte[] body : bodies) {
                responder.set(exchange -> send(exchange, 200, body));
                assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                        ConnectorErrorCode.PROVIDER_REJECTED);
            }
            responder.set(exchange -> sendChunked(exchange, 200,
                    new byte[KuboIpfsPinClient.MAX_RESPONSE_BYTES + 1]));
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.PROVIDER_REJECTED);
        }
    }

    @Test
    void treatsMalformedOrMismatchedSuccessfulAddAcknowledgementsAsUnknown() {
        List<String> bodies = List.of(
                "{\"Pins\":[]}",
                "{\"Pins\":[\"" + OTHER_RAW_CID + "\"]}",
                "{\"Pins\":[\"not-a-cid\"]}",
                "{\"Pins\":[\"" + RAW_CID + "\"],\"extra\":true}",
                "{\"Pins\":[\"" + RAW_CID + "\"],\"Pins\":[]}",
                "{\"Pins\":[\"" + RAW_CID + "\"]} {}");

        try (IpfsPinClient client = client(Optional.empty())) {
            for (String body : bodies) {
                respondJson(200, body);
                assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                        ConnectorErrorCode.ACK_UNKNOWN);
            }
            responder.set(exchange -> send(exchange, 200,
                    new byte[KuboIpfsPinClient.MAX_RESPONSE_BYTES + 1]));
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
            responder.set(exchange -> sendChunked(exchange, 200,
                    new byte[KuboIpfsPinClient.MAX_RESPONSE_BYTES + 1]));
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
        }
    }

    @Test
    void normalizesProbeAndMutationStatusesWithoutProviderText() {
        Map<Integer, ConnectorErrorCode> common = Map.of(
                401, ConnectorErrorCode.AUTH_UNAVAILABLE,
                403, ConnectorErrorCode.POLICY_DENIED,
                429, ConnectorErrorCode.RATE_LIMITED,
                400, ConnectorErrorCode.PROVIDER_REJECTED);

        try (IpfsPinClient client = client(Optional.empty())) {
            for (Map.Entry<Integer, ConnectorErrorCode> status : common.entrySet()) {
                respondJson(status.getKey(), "{\"secret\":\"provider-token\"}");
                assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID), status.getValue());
                assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID), status.getValue());
            }
            respondJson(503, "{\"secret\":\"provider-token\"}");
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.SERVICE_UNAVAILABLE);
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
            for (int unexpectedMutationStatus : List.of(201, 202, 307)) {
                respondJson(unexpectedMutationStatus, "{}");
                assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                        ConnectorErrorCode.ACK_UNKNOWN);
            }
            respondJson(408, "{}");
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.SERVICE_UNAVAILABLE);
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
        }
    }

    @Test
    void mapsAnUnknownWellFormedKubo500ToServiceUnavailable() {
        respondJson(500, "{\"Message\":\"provider secret detail\",\"Code\":0,\"Type\":\"error\"}");

        try (IpfsPinClient client = client(Optional.empty())) {
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void mapsOnlyKnownKuboMissingContentEnvelopesToContentUnavailable() {
        List<String> messages = List.of(
                "pin: block was not found locally (offline): ipld: could not find " + RAW_CID,
                "ipld: could not find " + RAW_CID,
                "merkledag: not found",
                "pin: merkledag: not found",
                "pin: failed to fetch " + RAW_CID + ": merkledag: not found",
                "pin: failed to resolve " + RAW_CID + ": merkledag: not found");

        try (IpfsPinClient client = client(Optional.empty())) {
            for (String message : messages) {
                respondJson(500, kuboError(message));
                assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                        ConnectorErrorCode.CONTENT_UNAVAILABLE);
            }
            respondJson(500, kuboError("ipld: could not find " + OTHER_RAW_CID));
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
            respondJson(500, "{");
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
            responder.set(exchange -> send(exchange, 500,
                    new byte[KuboIpfsPinClient.MAX_RESPONSE_BYTES + 1]));
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
        }
    }

    @Test
    void neverFollowsRedirectsOrUsesAResponseSelectedUrl() {
        AtomicInteger calls = new AtomicInteger();
        responder.set(exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Location", endpoint + "/attacker-selected");
            sendJson(exchange, 307, "{}");
        });

        try (IpfsPinClient client = client(Optional.empty())) {
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.PROVIDER_REJECTED);
        }
        assertThat(calls).hasValue(1);
        assertThat(requests).hasSize(1);
    }

    @Test
    void ignoresTheAmbientProxySelector() {
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
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            }
        });
        try {
            respondJson(200, pinState(RAW_CID, "direct", true));
            try (IpfsPinClient client = client(Optional.empty())) {
                assertThat(client.probe(cid(RAW_CID), EFFECT_ID)).isEqualTo(PinState.DIRECT);
            }
            assertThat(ambientSelections).hasValue(0);
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    @Test
    void boundsRequestTimeoutsAndClassifiesMutationUncertainty() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        responder.set(exchange -> {
            entered.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            sendJson(exchange, 200, pinState(RAW_CID, "direct", true));
        });

        try (IpfsPinClient client = client(Optional.empty(), Duration.ofMillis(40))) {
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.SERVICE_UNAVAILABLE);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID),
                    ConnectorErrorCode.ACK_UNKNOWN);
        }
    }

    @Test
    void requestDeadlineAlsoBoundsAResponseThatStallsAfterHeaders() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        responder.set(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseBody().write(
                    pinState(RAW_CID, "direct", true).getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });

        long started = System.nanoTime();
        try (IpfsPinClient client = client(Optional.empty(), Duration.ofMillis(40))) {
            assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID),
                    ConnectorErrorCode.SERVICE_UNAVAILABLE);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(headersSent.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMillis).isLessThan(400);
    }

    @Test
    void restoresInterruptStatusAndReturnsOnlyShutdown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        responder.set(exchange -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            sendJson(exchange, 200, pinState(RAW_CID, "direct", true));
        });
        IpfsPinClient client = client(Optional.empty(), Duration.ofSeconds(5));
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicBoolean restored = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                client.probe(cid(RAW_CID), EFFECT_ID);
            } catch (Throwable failure) {
                observed.set(failure);
                restored.set(Thread.currentThread().isInterrupted());
            }
        });

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000);
        release.countDown();
        client.close();

        assertThat(caller.isAlive()).isFalse();
        assertThat(observed.get()).isInstanceOf(IpfsProviderException.class);
        assertThat(((IpfsProviderException) observed.get()).code())
                .isEqualTo(ConnectorErrorCode.SHUTDOWN);
        assertThat(restored).isTrue();
    }

    @Test
    void interruptedMutationRestoresInterruptAndRequiresReconciliation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        responder.set(exchange -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            sendJson(exchange, 200, "{\"Pins\":[\"" + RAW_CID + "\"]}");
        });
        IpfsPinClient client = client(Optional.empty(), Duration.ofSeconds(5));
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicBoolean restored = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                client.add(cid(RAW_CID), true, EFFECT_ID);
            } catch (Throwable failure) {
                observed.set(failure);
                restored.set(Thread.currentThread().isInterrupted());
            }
        });

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000);
        release.countDown();
        client.close();

        assertThat(caller.isAlive()).isFalse();
        assertThat(observed.get()).isInstanceOf(IpfsProviderException.class);
        assertThat(((IpfsProviderException) observed.get()).code())
                .isEqualTo(ConnectorErrorCode.ACK_UNKNOWN);
        assertThat(restored).isTrue();
    }

    @Test
    void closesIdempotentlyAndRejectsFurtherCalls() {
        IpfsPinClient client = client(Optional.empty());
        client.close();
        client.close();

        assertCode(() -> client.probe(cid(RAW_CID), EFFECT_ID), ConnectorErrorCode.SHUTDOWN);
        assertCode(() -> client.add(cid(RAW_CID), true, EFFECT_ID), ConnectorErrorCode.SHUTDOWN);
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsInvalidInternalTraceInputsBeforeNetworkIo() {
        try (IpfsPinClient client = client(Optional.empty())) {
            assertCode(() -> client.probe(cid(RAW_CID), null), ConnectorErrorCode.INTERNAL_ERROR);
            assertCode(() -> client.add(cid(RAW_CID), true, new byte[31]),
                    ConnectorErrorCode.INTERNAL_ERROR);
            assertCode(() -> client.probe(null, EFFECT_ID), ConnectorErrorCode.INTERNAL_ERROR);
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void providerExceptionsCannotRetainResponseSecretsOrTransportCauses() {
        String secret = "provider-secret-token-and-endpoint";
        respondJson(200, "{\"unexpected\":\"" + secret + "\"}");

        try (IpfsPinClient client = client(Optional.empty())) {
            assertThatExceptionOfType(IpfsProviderException.class)
                    .isThrownBy(() -> client.probe(cid(RAW_CID), EFFECT_ID))
                    .satisfies(failure -> {
                        assertThat(failure.code()).isEqualTo(ConnectorErrorCode.PROVIDER_REJECTED);
                        assertThat(failure.getMessage()).isEqualTo("PROVIDER_REJECTED");
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.getSuppressed()).isEmpty();
                        assertThat(failure.toString()).doesNotContain(secret, endpoint.toString());
                    });
        }
    }

    @Test
    void factoryOpensIndependentOwnedClients() {
        respondJson(200, pinState(RAW_CID, "direct", true));
        KuboClientConfig config = config(Optional.empty(), Duration.ofSeconds(2));
        KuboIpfsPinClientFactory factory = new KuboIpfsPinClientFactory(config);
        IpfsPinClient first = factory.open();
        IpfsPinClient second = factory.open();
        first.close();

        assertThat(second.probe(cid(RAW_CID), EFFECT_ID)).isEqualTo(PinState.DIRECT);
        second.close();
    }

    private IpfsPinClient client(Optional<String> bearerToken) {
        return client(bearerToken, Duration.ofSeconds(2));
    }

    private IpfsPinClient client(Optional<String> bearerToken, Duration requestTimeout) {
        return new KuboIpfsPinClient(config(bearerToken, requestTimeout));
    }

    private KuboClientConfig config(Optional<String> bearerToken, Duration requestTimeout) {
        return new KuboClientConfig(endpoint, Duration.ofSeconds(1), requestTimeout,
                Duration.ofMillis(100), bearerToken);
    }

    private void respondJson(int status, String body) {
        responder.set(exchange -> sendJson(exchange, status, body));
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        synchronized (requests) {
            requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                    copyHeaders(exchange.getRequestHeaders()), requestBody));
        }
        Responder selected = responder.get();
        if (selected == null) {
            sendJson(exchange, 500, "{}");
            return;
        }
        try {
            selected.respond(exchange);
        } catch (IOException disconnected) {
            exchange.close();
        }
    }

    private static Map<String, List<String>> copyHeaders(Headers headers) {
        java.util.LinkedHashMap<String, List<String>> copy = new java.util.LinkedHashMap<>();
        headers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void sendChunked(HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, 0);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static CanonicalCid cid(String text) {
        return CanonicalCid.fromText(text);
    }

    private static String pinState(String cid, String type, boolean includeName) {
        return "{\"Keys\":{\"" + cid + "\":{\"Type\":\"" + type + "\""
                + (includeName ? ",\"Name\":\"\"" : "") + "}}}";
    }

    private static String jsonQuoted(String value) {
        return "\"" + value + "\"";
    }

    private static String kuboError(String message) {
        return "{\"Message\":\"" + message + "\",\"Code\":0,\"Type\":\"error\"}";
    }

    private static void assertCode(ThrowingCall call, ConnectorErrorCode expected) {
        assertThatExceptionOfType(IpfsProviderException.class)
                .isThrownBy(call::run)
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo(expected);
                    assertThat(failure.getMessage()).isEqualTo(expected.wireCode());
                    assertThat(failure.getCause()).isNull();
                });
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private record CapturedRequest(String method,
                                   URI uri,
                                   Map<String, List<String>> headers,
                                   byte[] body) {
        private CapturedRequest {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        private String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().isEmpty() ? null : entry.getValue().getFirst();
                }
            }
            return null;
        }
    }
}
