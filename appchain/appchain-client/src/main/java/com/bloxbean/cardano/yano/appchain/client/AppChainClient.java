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
import java.util.Locale;
import java.util.Objects;
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

    private static final int MAX_EFFECT_PROOF_RESPONSE_BYTES = 40 * 1024 * 1024;

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

    /**
     * Submit a typed payload with an encode function (ADR 006 E1.3). Pass a
     * method reference to any codec — e.g. {@code codec::encode} for a
     * core-api {@code JacksonCborCodec}, or {@link CborCodec}.
     */
    public <T> SubmitResult submitTyped(String topic, T payload,
                                        java.util.function.Function<T, byte[]> encoder) {
        return submit(topic, encoder.apply(payload));
    }

    /**
     * Subscribe to finalized messages decoded to a typed payload. Messages that
     * fail to decode are skipped (they belong to a different schema/topic).
     */
    public <T> AutoCloseable subscribeTyped(long fromHeight, String topic,
                                            java.util.function.Function<byte[], T> decoder,
                                            java.util.function.BiConsumer<T, StreamedMessage> consumer) {
        return subscribe(fromHeight, topic, message -> {
            T payload;
            try {
                payload = decoder.apply(message.body());
            } catch (Exception e) {
                return; // not our type — skip
            }
            consumer.accept(payload, message);
        });
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

    // ------------------------------------------------------------------
    // Effects (ADR app-layer/010 F12): read surface, operator actions, and
    // the external-executor claim/report loop — everything a Java worker
    // needs to act as an external effect executor.
    // ------------------------------------------------------------------

    /** Emitted effect records (consensus view), ascending (height, ordinal). */
    public JsonNode effects(long fromHeight, int limit) {
        return getJson(chainPath("/effects?fromHeight=" + fromHeight + "&limit=" + limit), 200);
    }

    /** One effect: emission record + this node's execution status (when present). */
    public Optional<JsonNode> effect(long height, int ordinal) {
        return Optional.ofNullable(getJsonOrNull(chainPath("/effects/" + height + "/" + ordinal)));
    }

    /**
     * Composed proof that the canonical effect at {@code (height, ordinal)} is
     * a leaf of that block's ordered effects root and that the effects root is
     * included in the block's historical state root.
     * <p>
     * A missing effect ({@code 404}) and an effect whose list-proof material
     * has aged out of node retention ({@code 410}) are intentionally distinct:
     * callers can retry the former only if they addressed the wrong node/chain,
     * while the latter requires an archived proof or a longer retention policy.
     */
    public EffectProofLookup effectProof(long height, int ordinal) {
        if (height <= 0) {
            throw new IllegalArgumentException("height must be > 0");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
        String url = chainPath("/effects/" + height + "/" + ordinal + "/proof");
        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (java.io.InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_EFFECT_PROOF_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_EFFECT_PROOF_RESPONSE_BYTES) {
                    throw new AppChainClientException(
                            "Effect proof response exceeds 40 MiB from " + url);
                }
                responseBody = new String(bytes, StandardCharsets.UTF_8);
            }
            JsonNode body = responseBody.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(responseBody);
            if (response.statusCode() == 404) {
                return EffectProofLookup.notFound(errorMessage(body, "Effect not found"));
            }
            if (response.statusCode() == 410) {
                return EffectProofLookup.pruned(
                        errorMessage(body, "Effect proof material was pruned"),
                        body.path("effectCount").asInt(0));
            }
            if (response.statusCode() != 200) {
                throw new AppChainClientException("HTTP " + response.statusCode() + " from " + url
                        + ": " + responseBody);
            }

            // Permit a future/wrapper response while keeping the v1 wire body
            // flat. The proof itself remains the same typed contract.
            JsonNode proofNode = body.hasNonNull("proof") ? body.get("proof") : body;
            EffectProof proof = EffectProof.from(proofNode);
            if (proof.height() != height || proof.ordinal() != ordinal) {
                throw new AppChainClientException("Effect proof identity mismatch: requested "
                        + height + "/" + ordinal + " but server returned "
                        + proof.height() + "/" + proof.ordinal());
            }
            if (chainId != null && !chainId.equals(proof.chainId())) {
                throw new AppChainClientException("Effect proof chain mismatch: requested "
                        + chainId + " but server returned " + proof.chainId());
            }
            return EffectProofLookup.available(proof);
        } catch (AppChainClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppChainClientException("Interrupted calling " + url, e);
        } catch (Exception e) {
            throw new AppChainClientException("Request failed: " + url, e);
        }
    }

    /** Effect consensus/runtime gauges and cumulative totals of the connected node. */
    public JsonNode effectStats() {
        return getJson(chainPath("/effects/stats"), 200);
    }

    /** Operator requeue of a PARKED/QUARANTINED effect (ADR-010 F9). */
    public boolean requeueEffect(long height, int ordinal) {
        return postForOk(chainPath("/effects/" + height + "/" + ordinal + "/requeue"), "{}");
    }

    /** Operator cancel of an open CHAIN effect (ADR-010 F9). */
    public boolean cancelEffect(long height, int ordinal, String reason) {
        return postForOk(chainPath("/effects/" + height + "/" + ordinal + "/cancel?reason="
                + java.net.URLEncoder.encode(reason, java.nio.charset.StandardCharsets.UTF_8)), "{}");
    }

    /**
     * External-executor claim (ADR-010 F5): lease up to {@code max} eligible
     * effects of the given types. Requires {@code effects.external.enabled}
     * on the node. Execute each, then {@link #reportEffect}. At-least-once:
     * pass each effect's {@code idempotencyKey} to the external system.
     */
    public JsonNode claimEffects(String executorId, java.util.List<String> types, int max,
                                 long leaseSeconds) {
        var request = objectMapper.createObjectNode();
        request.put("executorId", executorId);
        request.put("max", max);
        request.put("leaseSeconds", leaseSeconds);
        var typesNode = request.putArray("types");
        if (types != null) {
            types.forEach(typesNode::add);
        }
        return postJson(chainPath("/effects/claim"), request.toString(), 200);
    }

    /** External-executor report: the definitive outcome of a claimed effect. */
    public boolean reportEffect(String executorId, long height, int ordinal, boolean success,
                                byte[] externalRef, String reason) {
        var request = objectMapper.createObjectNode();
        request.put("executorId", executorId);
        request.put("success", success);
        if (externalRef != null && externalRef.length > 0) {
            request.put("externalRefHex", Hex.encode(externalRef));
        }
        if (reason != null) {
            request.put("reason", reason);
        }
        return postForOk(chainPath("/effects/" + height + "/" + ordinal + "/report"),
                request.toString());
    }

    private boolean postForOk(String url, String body) {
        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder(url)
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
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

    private static String errorMessage(JsonNode body, String fallback) {
        String error = body.path("error").asText("");
        return error.isBlank() ? fallback : error;
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

    /** Result of looking up a composed effect proof. */
    public record EffectProofLookup(EffectProofStatus status, EffectProof proof,
                                    String message, int effectCount) {
        public EffectProofLookup {
            Objects.requireNonNull(status, "status");
            if (status == EffectProofStatus.AVAILABLE && proof == null) {
                throw new IllegalArgumentException("AVAILABLE requires a proof");
            }
            if (status != EffectProofStatus.AVAILABLE && proof != null) {
                throw new IllegalArgumentException(status + " must not carry a proof");
            }
        }

        static EffectProofLookup available(EffectProof proof) {
            return new EffectProofLookup(EffectProofStatus.AVAILABLE,
                    Objects.requireNonNull(proof, "proof"), null, proof.effectCount());
        }

        static EffectProofLookup notFound(String message) {
            return new EffectProofLookup(EffectProofStatus.NOT_FOUND, null, message, 0);
        }

        static EffectProofLookup pruned(String message, int effectCount) {
            return new EffectProofLookup(EffectProofStatus.PRUNED, null, message,
                    Math.max(0, effectCount));
        }

        public boolean available() {
            return status == EffectProofStatus.AVAILABLE;
        }
    }

    public enum EffectProofStatus {
        AVAILABLE,
        NOT_FOUND,
        PRUNED
    }

    /**
     * REST wire model for ADR-010's composed proof. The canonical effect
     * record remains CBOR hex; all hashes and the MPF proof remain hex so the
     * client never depends on core-api's record classes.
     */
    public record EffectProof(int version,
                              String chainId,
                              long height,
                              int ordinal,
                              String recordCborHex,
                              String effectHashHex,
                              int effectCount,
                              List<EffectMerkleStep> merklePath,
                              String effectsRootHex,
                              String stateKeyHex,
                              String stateRootHex,
                              String stateProofWireHex) {

        public EffectProof {
            merklePath = merklePath != null ? List.copyOf(merklePath) : List.of();
        }

        static EffectProof from(JsonNode node) {
            List<EffectMerkleStep> path = new ArrayList<>();
            JsonNode pathNode = required(node, "merklePath");
            if (!pathNode.isArray()) {
                throw new IllegalArgumentException("merklePath must be an array");
            }
            if (pathNode.size() > 20) {
                throw new IllegalArgumentException("merklePath exceeds the v1 maximum depth");
            }
            for (JsonNode step : pathNode) {
                // `side` is the v1 name; accept the early-design `sibling`
                // spelling so pre-release nodes fail forward cleanly.
                String sideText = step.hasNonNull("side")
                        ? step.get("side").asText()
                        : requiredText(step, "sibling");
                EffectMerkleSide side;
                try {
                    side = EffectMerkleSide.valueOf(sideText.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown effect Merkle side: " + sideText, e);
                }
                String siblingHash = step.hasNonNull("siblingHashHex")
                        ? step.get("siblingHashHex").asText()
                        : step.path("hashHex").asText("");
                path.add(new EffectMerkleStep(side, siblingHash));
            }
            return new EffectProof(
                    required(node, "version").asInt(),
                    requiredText(node, "chainId"),
                    required(node, "height").asLong(),
                    required(node, "ordinal").asInt(),
                    requiredText(node, "recordCborHex"),
                    requiredText(node, "effectHashHex"),
                    required(node, "effectCount").asInt(),
                    path,
                    requiredText(node, "effectsRootHex"),
                    requiredText(node, "stateKeyHex"),
                    requiredText(node, "stateRootHex"),
                    requiredText(node, "stateProofWireHex"));
        }

        private static JsonNode required(JsonNode node, String field) {
            if (node == null || !node.hasNonNull(field)) {
                throw new IllegalArgumentException("Missing effect proof field: " + field);
            }
            return node.get(field);
        }

        private static String requiredText(JsonNode node, String field) {
            String value = required(node, field).asText();
            if (value.isBlank()) {
                throw new IllegalArgumentException("Empty effect proof field: " + field);
            }
            return value;
        }
    }

    public record EffectMerkleStep(EffectMerkleSide side, String siblingHashHex) {
        public EffectMerkleStep {
            Objects.requireNonNull(side, "side");
            siblingHashHex = siblingHashHex != null ? siblingHashHex : "";
        }
    }

    public enum EffectMerkleSide {
        LEFT,
        RIGHT,
        PASS_THROUGH
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
