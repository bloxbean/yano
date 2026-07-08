package com.bloxbean.cardano.yano.appchain.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Java client SDK for a Yano app chain (ADR app-layer/006 E1.1): typed
 * submit/read/proof access over the node's REST surface, live consumption
 * over SSE, and client-side MPF proof verification via {@link ProofVerifier}
 * — records can be verified against an (anchored) state root without
 * trusting the node.
 *
 * <pre>
 * AppChainClient client = AppChainClient.builder("http://localhost:8080/api/v1")
 *         .chainId("my-chain")          // optional with a single-chain node
 *         .apiKey("...")                // when the node enables API-key auth
 *         .build();
 *
 * SubmitResult r = client.submitText("orders", "order #1");
 * Proof proof = client.proof(HexUtil.decode(r.messageId()));
 * boolean ok = ProofVerifier.verify(proof);   // don't trust — verify
 * </pre>
 */
public final class AppChainClient {

    private final String baseUrl;
    private final String chainId;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AppChainClient(Builder builder) {
        this.baseUrl = builder.baseUrl.endsWith("/")
                ? builder.baseUrl.substring(0, builder.baseUrl.length() - 1)
                : builder.baseUrl;
        this.chainId = builder.chainId;
        this.apiKey = builder.apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(builder.connectTimeoutSeconds))
                .build();
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /** Submit an opaque message body. Returns the content-derived message id. */
    public SubmitResult submit(String topic, byte[] body) {
        ObjectNode request = objectMapper.createObjectNode();
        if (topic != null) {
            request.put("topic", topic);
        }
        request.put("bodyHex", Hex.encode(body));
        JsonNode response = postJson(chainPath("/messages"), request.toString(), 202);
        return new SubmitResult(response.path("messageId").asText(),
                response.path("chainId").asText(), response.path("topic").asText(""));
    }

    /** Submit a UTF-8 text body. */
    public SubmitResult submitText(String topic, String body) {
        return submit(topic, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Recently accepted messages, optionally filtered by topic. */
    public List<Message> messages(int limit, String topic) {
        StringBuilder path = new StringBuilder(chainPath("/messages")).append("?limit=").append(limit);
        if (topic != null) {
            path.append("&topic=").append(URLEncoder.encode(topic, StandardCharsets.UTF_8));
        }
        JsonNode response = getJson(path.toString(), 200);
        List<Message> result = new ArrayList<>();
        for (JsonNode node : response) {
            result.add(Message.from(node));
        }
        return result;
    }

    /** Tip of the chain: height + state root. */
    public Tip tip() {
        JsonNode response = getJson(chainPath("/tip"), 200);
        return new Tip(response.path("chainId").asText(),
                response.path("height").asLong(),
                response.path("stateRoot").asText());
    }

    /** A finalized block, or empty when the height does not exist. */
    public Optional<Block> block(long height) {
        JsonNode response = getJsonOrNull(chainPath("/blocks/" + height));
        return response == null ? Optional.empty() : Optional.of(Block.from(response));
    }

    /** MPF inclusion proof for a state key, or empty when no entry exists. */
    public Optional<Proof> proof(byte[] stateKey) {
        JsonNode response = getJsonOrNull(chainPath("/proof/" + Hex.encode(stateKey)));
        if (response == null) {
            return Optional.empty();
        }
        return Optional.of(new Proof(
                response.path("key").asText(),
                response.path("chainId").asText(null),
                response.path("stateRoot").asText(),
                response.path("proofWireHex").asText(),
                response.hasNonNull("valueHex") ? response.get("valueHex").asText() : null,
                response.hasNonNull("finalizedAtHeight") ? response.get("finalizedAtHeight").asLong() : null));
    }

    /** Raw status map of the chain. */
    public JsonNode status() {
        return getJson(chainPath("/status"), 200);
    }

    /**
     * Subscribe to finalized messages over SSE. Replays from {@code fromHeight}
     * (-1 = live only), then follows; reconnects automatically from the last
     * seen height until the returned handle is closed.
     */
    public AutoCloseable subscribe(long fromHeight, String topic, Consumer<StreamedMessage> consumer) {
        AtomicBoolean closed = new AtomicBoolean(false);
        Thread thread = new Thread(() -> runSseLoop(fromHeight, topic, consumer, closed));
        thread.setName("app-chain-client-sse");
        thread.setDaemon(true);
        thread.start();
        return () -> {
            closed.set(true);
            thread.interrupt();
        };
    }

    private void runSseLoop(long fromHeight, String topic,
                            Consumer<StreamedMessage> consumer, AtomicBoolean closed) {
        // Resume from the last block we saw (inclusive server replay), but track
        // the last delivered (height, index) so a reconnect that replays that
        // block does not re-deliver messages the consumer already processed.
        long nextFromHeight = fromHeight;
        long lastHeight = -1;
        int lastIndex = -1;
        while (!closed.get()) {
            try {
                StringBuilder path = new StringBuilder(chainPath("/stream"))
                        .append("?fromHeight=").append(nextFromHeight);
                if (topic != null) {
                    path.append("&topic=").append(URLEncoder.encode(topic, StandardCharsets.UTF_8));
                }
                HttpRequest request = requestBuilder(path.toString())
                        .header("Accept", "text/event-stream")
                        .GET().build();
                HttpResponse<java.io.InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    Thread.sleep(2000);
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String eventName = null;
                    StringBuilder data = new StringBuilder();
                    String line;
                    while (!closed.get() && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if ("app-message".equals(eventName) && data.length() > 0) {
                                JsonNode json = objectMapper.readTree(data.toString());
                                StreamedMessage message = StreamedMessage.from(json);
                                if (message.height() < lastHeight
                                        || (message.height() == lastHeight && message.index() <= lastIndex)) {
                                    // already delivered before a reconnect — skip
                                } else {
                                    lastHeight = message.height();
                                    lastIndex = message.index();
                                    nextFromHeight = Math.max(nextFromHeight, message.height());
                                    consumer.accept(message);
                                }
                            }
                            eventName = null;
                            data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            eventName = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5).trim());
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (closed.get()) {
                    return;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private String chainPath(String suffix) {
        return chainId != null
                ? baseUrl + "/app-chain/chains/" + chainId + suffix
                : baseUrl + "/app-chain" + suffix;
    }

    private HttpRequest.Builder requestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30));
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return builder;
    }

    private JsonNode postJson(String url, String body, int expectedStatus) {
        try {
            HttpRequest request = requestBuilder(url)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expectedStatus) {
                throw new AppChainClientException("HTTP " + response.statusCode() + " from " + url
                        + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (AppChainClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppChainClientException("Interrupted calling " + url, e);
        } catch (Exception e) {
            throw new AppChainClientException("Request failed: " + url, e);
        }
    }

    private JsonNode getJson(String url, int expectedStatus) {
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expectedStatus) {
                throw new AppChainClientException("HTTP " + response.statusCode() + " from " + url
                        + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (AppChainClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppChainClientException("Interrupted calling " + url, e);
        } catch (Exception e) {
            throw new AppChainClientException("Request failed: " + url, e);
        }
    }

    private JsonNode getJsonOrNull(String url) {
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new AppChainClientException("HTTP " + response.statusCode() + " from " + url
                        + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (AppChainClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppChainClientException("Interrupted calling " + url, e);
        } catch (Exception e) {
            throw new AppChainClientException("Request failed: " + url, e);
        }
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public record SubmitResult(String messageId, String chainId, String topic) {
    }

    public record Tip(String chainId, long height, String stateRootHex) {
    }

    public record Message(String messageId, String chainId, String topic, String senderHex,
                          long senderSeq, String bodyHex, String source) {
        public byte[] body() {
            return Hex.decode(bodyHex);
        }

        static Message from(JsonNode node) {
            return new Message(node.path("messageId").asText(), node.path("chainId").asText(),
                    node.path("topic").asText(""), node.path("sender").asText(),
                    node.path("senderSeq").asLong(), node.path("bodyHex").asText(),
                    node.path("source").asText());
        }
    }

    public record StreamedMessage(String chainId, long height, int index, String messageId,
                                  String topic, String senderHex, long senderSeq, String bodyHex) {
        public byte[] body() {
            return Hex.decode(bodyHex);
        }

        static StreamedMessage from(JsonNode node) {
            return new StreamedMessage(node.path("chainId").asText(), node.path("height").asLong(),
                    node.path("index").asInt(), node.path("messageId").asText(),
                    node.path("topic").asText(""), node.path("sender").asText(),
                    node.path("senderSeq").asLong(), node.path("bodyHex").asText());
        }
    }

    public record Block(long height, String chainId, String prevHashHex, long timestamp,
                        String messagesRootHex, String stateRootHex, String proposerHex,
                        int certSignatures, List<Message> messages) {
        static Block from(JsonNode node) {
            List<Message> messages = new ArrayList<>();
            for (JsonNode message : node.path("messages")) {
                messages.add(Message.from(message));
            }
            return new Block(node.path("height").asLong(), node.path("chainId").asText(),
                    node.path("prevHash").asText(), node.path("timestamp").asLong(),
                    node.path("messagesRoot").asText(), node.path("stateRoot").asText(),
                    node.path("proposer").asText(), node.path("certSignatures").asInt(), messages);
        }
    }

    /**
     * An MPF inclusion proof as served by the node. Verify locally with
     * {@link ProofVerifier#verify(Proof)} — optionally against a state root
     * obtained from an L1 anchor instead of the node's own claim.
     */
    public record Proof(String keyHex, String chainId, String stateRootHex,
                        String proofWireHex, String valueHex, Long finalizedAtHeight) {
    }

    public static final class AppChainClientException extends RuntimeException {
        public AppChainClientException(String message) {
            super(message);
        }

        public AppChainClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Builder {
        private final String baseUrl;
        private String chainId;
        private String apiKey;
        private long connectTimeoutSeconds = 10;

        private Builder(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required, e.g. http://localhost:8080/api/v1");
            }
            this.baseUrl = baseUrl;
        }

        /** Address a specific chain; optional when the node hosts exactly one. */
        public Builder chainId(String chainId) {
            this.chainId = chainId;
            return this;
        }

        /** API key when the node enables yano.app-chain.api.auth. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder connectTimeoutSeconds(long seconds) {
            this.connectTimeoutSeconds = seconds;
            return this;
        }

        public AppChainClient build() {
            return new AppChainClient(this);
        }
    }
}
