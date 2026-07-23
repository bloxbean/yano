package com.bloxbean.cardano.yano.appchain.client;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final ProxySelector DIRECT_PROXY_SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address,
                                  java.io.IOException failure) {
            // No proxy is selected.
        }
    };

    private static final int MAX_EFFECT_PROOF_RESPONSE_BYTES = 40 * 1024 * 1024;
    private static final int MAX_SUBMIT_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_STATE_PROOF_KEY_BYTES = 256;
    private static final int MAX_STATE_PROOF_VALUE_BYTES = 1024 * 1024;
    private static final int MAX_STATE_PROOF_WIRE_BYTES = 1024 * 1024;
    private static final int MAX_STATE_PROOF_RESPONSE_BYTES =
            2 * (MAX_STATE_PROOF_KEY_BYTES + MAX_STATE_PROOF_VALUE_BYTES
                    + MAX_STATE_PROOF_WIRE_BYTES) + 64 * 1024;
    private static final int MAX_QUERY_PATH_CHARACTERS = 256;
    private static final int MAX_QUERY_PATH_SEGMENTS = 128;
    private static final int MAX_QUERY_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_QUERY_RESULT_BYTES = 1024 * 1024;
    private static final Duration QUERY_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /**
     * One maximum result needs two JSON hex characters per byte. The remaining
     * 64 KiB is a strict envelope/metadata allowance; it is intentionally not
     * an unbounded {@code BodyHandler.ofString()} allocation.
     */
    private static final int MAX_QUERY_RESPONSE_BYTES = 2 * MAX_QUERY_RESULT_BYTES + 64 * 1024;
    private static final int INITIAL_BOUNDED_RESPONSE_BYTES = 8 * 1024;
    private static final Set<String> QUERY_RESPONSE_FIELDS = Set.of(
            "chainId", "stateMachineId", "committedHeight", "stateRoot", "payloadHex");
    private static final Set<String> SUBMIT_RESPONSE_FIELDS = Set.of(
            "messageId", "chainId", "topic");
    private static final Set<String> STATE_PROOF_RESPONSE_FIELDS = Set.of(
            "key", "chainId", "stateRoot", "proofWireHex", "valueHex",
            "finalizedAtHeight", "committedHeight");
    private static final ObjectMapper STRICT_RESPONSE_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

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
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(builder.connectTimeoutSeconds));
        if (builder.directConnections) {
            http.proxy(DIRECT_PROXY_SELECTOR);
        }
        this.httpClient = http.build();
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /** Submit an opaque message body. Returns the content-derived message id. */
    public SubmitResult submit(String topic, byte[] body) {
        Objects.requireNonNull(body, "body");
        ObjectNode request = objectMapper.createObjectNode();
        if (topic != null) {
            request.put("topic", topic);
        }
        request.put("bodyHex", Hex.encode(body));
        String endpoint = chainPath("/messages");
        try {
            HttpRequest httpRequest = requestBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            request.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = sendBounded(
                    httpRequest, MAX_SUBMIT_RESPONSE_BYTES, "App-chain submit");
            if (response.statusCode() != 202) {
                throw boundedHttpFailure(
                        "App-chain submit", response.statusCode(), response.body());
            }
            return parseSubmitResult(response.body(), topic != null ? topic : "");
        } catch (AppChainClientException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AppChainClientException("App-chain submit was interrupted", interrupted);
        } catch (Exception failure) {
            throw new AppChainClientException("App-chain submit request failed", failure);
        }
    }

    private SubmitResult parseSubmitResult(byte[] responseBytes, String expectedTopic) {
        final JsonNode response;
        try {
            response = STRICT_RESPONSE_JSON.readTree(responseBytes);
        } catch (Exception malformed) {
            throw new AppChainClientException("Malformed app-chain submit response");
        }
        if (response == null || !response.isObject()
                || response.size() != SUBMIT_RESPONSE_FIELDS.size()
                || SUBMIT_RESPONSE_FIELDS.stream().anyMatch(field -> !response.has(field))) {
            throw new AppChainClientException("Malformed app-chain submit response envelope");
        }
        JsonNode messageIdNode = response.get("messageId");
        JsonNode topicNode = response.get("topic");
        if (!messageIdNode.isTextual() || !topicNode.isTextual()) {
            throw new AppChainClientException("Invalid app-chain submit response metadata");
        }
        String messageId = messageIdNode.textValue();
        String resultChainId = requiredQueryIdentifier(response, "chainId");
        String resultTopic = topicNode.textValue();
        if (messageId.length() != 64 || !isCanonicalLowerHex(messageId)) {
            throw new AppChainClientException("Invalid app-chain submit message id");
        }
        if (chainId != null && !chainId.equals(resultChainId)) {
            throw new AppChainClientException("App-chain submit response chain mismatch");
        }
        if (!expectedTopic.equals(resultTopic)) {
            throw new AppChainClientException("App-chain submit response topic mismatch");
        }
        return new SubmitResult(messageId, resultChainId, resultTopic);
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

    /**
     * MPF inclusion or exclusion proof for a state key, or empty when the node
     * cannot produce a committed proof snapshot (for example before block 1).
     */
    public Optional<Proof> proof(byte[] stateKey) {
        Objects.requireNonNull(stateKey, "stateKey");
        if (stateKey.length == 0 || stateKey.length > MAX_STATE_PROOF_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "state proof key must contain 1-256 bytes");
        }
        byte[] requestedKey = stateKey.clone();
        String endpoint = chainPath("/proof/" + Hex.encode(requestedKey));
        try {
            HttpRequest request = requestBuilder(endpoint)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = sendBounded(
                    request, MAX_STATE_PROOF_RESPONSE_BYTES, "App-chain state proof");
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw boundedHttpFailure(
                        "App-chain state proof", response.statusCode(), response.body());
            }
            return Optional.of(parseStateProof(requestedKey, response.body()));
        } catch (AppChainClientException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AppChainClientException("App-chain state proof was interrupted", interrupted);
        } catch (Exception failure) {
            throw new AppChainClientException("App-chain state proof request failed", failure);
        }
    }

    private Proof parseStateProof(byte[] requestedKey, byte[] responseBytes) {
        final JsonNode response;
        try {
            response = STRICT_RESPONSE_JSON.readTree(responseBytes);
        } catch (Exception malformed) {
            throw new AppChainClientException("Malformed app-chain state proof response");
        }
        if (response == null || !response.isObject()
                || !hasOnlyFields(response, STATE_PROOF_RESPONSE_FIELDS)
                || !hasRequiredFields(response, Set.of(
                "key", "chainId", "stateRoot", "proofWireHex"))) {
            throw new AppChainClientException("Malformed app-chain state proof envelope");
        }

        String keyHex = requiredCanonicalBoundedHex(
                response, "key", 0, MAX_STATE_PROOF_KEY_BYTES);
        String resultChainId = requiredQueryIdentifier(response, "chainId");
        String rootHex = requiredCanonicalBoundedHex(response, "stateRoot", 32, 32);
        String proofWireHex = requiredCanonicalBoundedHex(
                response, "proofWireHex", 1, MAX_STATE_PROOF_WIRE_BYTES);
        String valueHex = optionalCanonicalBoundedHex(
                response, "valueHex", MAX_STATE_PROOF_VALUE_BYTES);
        Long finalizedAtHeight = optionalNonNegativeLong(response, "finalizedAtHeight");
        Long committedHeight = optionalNonNegativeLong(response, "committedHeight");

        if (finalizedAtHeight != null && committedHeight != null
                && finalizedAtHeight > committedHeight) {
            throw new AppChainClientException(
                    "App-chain state proof contains contradictory heights");
        }

        if (!Arrays.equals(requestedKey, Hex.decode(keyHex))) {
            throw new AppChainClientException("App-chain state proof key mismatch");
        }
        if (chainId != null && !chainId.equals(resultChainId)) {
            throw new AppChainClientException("App-chain state proof chain mismatch");
        }
        return new Proof(keyHex, resultChainId, rootHex, proofWireHex,
                valueHex, finalizedAtHeight, committedHeight);
    }

    /** Raw status map of the chain. */
    public JsonNode status() {
        return getJson(chainPath("/status"), 200);
    }

    /**
     * Execute a bounded, read-only state-machine query against one committed
     * app-chain snapshot.
     * <p>
     * The opaque parameters and result remain codec-neutral byte strings. Use
     * the same application codec (for example {@link CborCodec}) at both edges.
     * The returned height and root identify the exact committed snapshot that
     * produced the result; this method does not claim that the payload itself
     * is an MPF proof.
     *
     * @param path canonical relative query path from the state-machine contract
     * @param params opaque parameters, at most 64 KiB
     * @return immutable result bytes and their committed-snapshot metadata
     * @throws IllegalArgumentException for a non-canonical path or oversized parameters
     * @throws IllegalStateException if this client was built without a chain id
     * @throws AppChainClientException for transport failures or an invalid server response
     */
    public QueryResult query(String path, byte[] params) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalStateException(
                    "chainId is required for committed-state queries");
        }
        String canonicalPath = validateQueryPath(path);
        Objects.requireNonNull(params, "params");
        if (params.length > MAX_QUERY_REQUEST_BYTES) {
            throw new IllegalArgumentException("query params must not exceed 64 KiB");
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("paramsHex", Hex.encode(params));
        String endpoint = chainPath("/query/" + canonicalPath);
        try {
            HttpRequest request = requestBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = sendBounded(
                    request, MAX_QUERY_RESPONSE_BYTES, "App-chain query");
            byte[] responseBytes = response.body();
            if (response.statusCode() != 200) {
                throw queryHttpFailure(response.statusCode(), responseBytes);
            }
            return parseQueryResult(responseBytes);
        } catch (AppChainClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppChainClientException("App-chain query was interrupted", e);
        } catch (Exception e) {
            throw new AppChainClientException("App-chain query request failed", e);
        }
    }

    private HttpResponse<byte[]> sendBounded(HttpRequest request, int maximumBytes,
                                             String operation) throws InterruptedException {
        AtomicReference<BoundedBodySubscriber> activeBody = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = httpClient.sendAsync(request, responseInfo -> {
                boolean declaredTooLarge = responseInfo.headers()
                        .firstValueAsLong("Content-Length")
                        .stream()
                        .anyMatch(length -> length > maximumBytes);
                BoundedBodySubscriber subscriber = new BoundedBodySubscriber(
                        maximumBytes, declaredTooLarge);
                activeBody.set(subscriber);
                return subscriber;
            });
        } catch (RuntimeException transportFailure) {
            throw new AppChainClientException(operation + " request failed", transportFailure);
        }
        try {
            return pending.get(QUERY_REQUEST_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            abortResponseBody(activeBody);
            throw interrupted;
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            abortResponseBody(activeBody);
            throw new AppChainClientException(operation + " request timed out");
        } catch (ExecutionException transportFailure) {
            pending.cancel(true);
            abortResponseBody(activeBody);
            if (hasCause(transportFailure, BoundedResponseTooLargeException.class)) {
                throw new AppChainClientException(
                        operation + " response exceeds the client size limit");
            }
            if (hasCause(transportFailure, HttpTimeoutException.class)) {
                throw new AppChainClientException(operation + " request timed out");
            }
            throw new AppChainClientException(operation + " request failed", transportFailure);
        }
    }

    private static void abortResponseBody(AtomicReference<BoundedBodySubscriber> activeBody) {
        BoundedBodySubscriber subscriber = activeBody.get();
        if (subscriber != null) {
            subscriber.abort();
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private QueryResult parseQueryResult(byte[] responseBytes) {
        final JsonNode response;
        try {
            response = STRICT_RESPONSE_JSON.readTree(responseBytes);
        } catch (Exception malformed) {
            // Parser diagnostics can quote attacker-controlled response bytes.
            throw new AppChainClientException("Malformed app-chain query response");
        }
        if (response == null || !response.isObject()
                || response.size() != QUERY_RESPONSE_FIELDS.size()
                || QUERY_RESPONSE_FIELDS.stream().anyMatch(field -> !response.has(field))) {
            throw new AppChainClientException("Malformed app-chain query response envelope");
        }

        String resultChainId = requiredQueryIdentifier(response, "chainId");
        String stateMachineId = requiredQueryIdentifier(response, "stateMachineId");
        JsonNode heightNode = response.get("committedHeight");
        if (!heightNode.isIntegralNumber() || !heightNode.canConvertToLong()
                || heightNode.longValue() < 0) {
            throw new AppChainClientException("Invalid app-chain query committed height");
        }
        String stateRootHex = requiredCanonicalQueryHex(response, "stateRoot", 32);
        String payloadHex = requiredCanonicalQueryHex(response, "payloadHex", -1);
        if (payloadHex.length() > 2 * MAX_QUERY_RESULT_BYTES) {
            throw new AppChainClientException("App-chain query payload exceeds 1 MiB");
        }
        if (chainId != null && !chainId.equals(resultChainId)) {
            throw new AppChainClientException("App-chain query response chain mismatch");
        }

        try {
            return new QueryResult(resultChainId, stateMachineId, heightNode.longValue(),
                    Hex.decode(stateRootHex), Hex.decode(payloadHex));
        } catch (IllegalArgumentException malformed) {
            throw new AppChainClientException("Malformed app-chain query response");
        }
    }

    private static String requiredQueryIdentifier(JsonNode response, String field) {
        JsonNode value = response.get(field);
        if (value == null || !value.isTextual()) {
            throw new AppChainClientException("Invalid app-chain query response metadata");
        }
        String identifier = value.textValue();
        if (identifier.isBlank()) {
            throw new AppChainClientException("Invalid app-chain query response metadata");
        }
        return identifier;
    }

    private static String requiredCanonicalQueryHex(JsonNode response, String field,
                                                     int exactBytes) {
        JsonNode value = response.get(field);
        if (value == null || !value.isTextual()) {
            throw new AppChainClientException("Invalid app-chain query response encoding");
        }
        String hex = value.textValue();
        if ((hex.length() & 1) != 0 || (exactBytes >= 0 && hex.length() != exactBytes * 2)) {
            throw new AppChainClientException("Invalid app-chain query response encoding");
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new AppChainClientException("Invalid app-chain query response encoding");
            }
        }
        return hex;
    }

    private static String requiredCanonicalBoundedHex(JsonNode response, String field,
                                                       int minimumBytes, int maximumBytes) {
        JsonNode value = response.get(field);
        if (value == null || !value.isTextual()) {
            throw new AppChainClientException("Invalid app-chain state proof encoding");
        }
        String hex = value.textValue();
        if ((hex.length() & 1) != 0 || hex.length() < minimumBytes * 2
                || hex.length() > maximumBytes * 2 || !isCanonicalLowerHex(hex)) {
            throw new AppChainClientException("Invalid app-chain state proof encoding");
        }
        return hex;
    }

    private static String optionalCanonicalBoundedHex(JsonNode response, String field,
                                                       int maximumBytes) {
        if (!response.has(field)) {
            return null;
        }
        return requiredCanonicalBoundedHex(response, field, 0, maximumBytes);
    }

    private static Long optionalNonNegativeLong(JsonNode response, String field) {
        if (!response.has(field)) {
            return null;
        }
        JsonNode value = response.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw new AppChainClientException("Invalid app-chain state proof height");
        }
        return value.longValue();
    }

    private static boolean hasOnlyFields(JsonNode object, Set<String> allowed) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasRequiredFields(JsonNode object, Set<String> required) {
        return required.stream().allMatch(object::has);
    }

    private static boolean isCanonicalLowerHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static AppChainClientException queryHttpFailure(int status, byte[] responseBytes) {
        return boundedHttpFailure("App-chain query", status, responseBytes);
    }

    private static AppChainClientException boundedHttpFailure(String operation, int status,
                                                               byte[] responseBytes) {
        String code = null;
        try {
            JsonNode body = STRICT_RESPONSE_JSON.readTree(responseBytes);
            if (body != null && body.isObject() && body.path("code").isTextual()) {
                String candidate = body.path("code").textValue();
                if (candidate.length() <= 32 && !candidate.isEmpty()
                        && candidate.chars().allMatch(c -> c == '_' || (c >= 'A' && c <= 'Z'))) {
                    code = candidate;
                }
            }
        } catch (Exception ignored) {
            // Never reflect an untrusted response body into the exception.
        }
        String suffix = code != null ? " (" + code + ")" : "";
        return new AppChainClientException(
                operation + " failed with HTTP " + status + suffix);
    }

    private static String validateQueryPath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_QUERY_PATH_CHARACTERS
                || path.startsWith("/") || path.endsWith("/")) {
            throw invalidQueryPath();
        }
        int segments = 0;
        int segmentStart = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i < path.length() && path.charAt(i) != '/') {
                continue;
            }
            if (i == segmentStart || ++segments > MAX_QUERY_PATH_SEGMENTS) {
                throw invalidQueryPath();
            }
            String segment = path.substring(segmentStart, i);
            if (segment.equals(".") || segment.equals("..")
                    || !isAsciiAlphaNumeric(segment.charAt(0))) {
                throw invalidQueryPath();
            }
            for (int j = 1; j < segment.length(); j++) {
                char c = segment.charAt(j);
                if (!isAsciiAlphaNumeric(c) && c != '.' && c != '_' && c != '~' && c != '-') {
                    throw invalidQueryPath();
                }
            }
            segmentStart = i + 1;
        }
        return path;
    }

    private static boolean isAsciiAlphaNumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
    }

    private static IllegalArgumentException invalidQueryPath() {
        return new IllegalArgumentException(
                "query path must be a normalized relative path containing 1-128 "
                        + "unreserved ASCII segments and at most 256 characters");
    }

    /** Completes only after the entire bounded body arrives, so the deadline covers body reads. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final int maximumBytes;
        private final boolean rejectDeclaredLength;
        private byte[] buffer;
        private Flow.Subscription subscription;
        private int size;
        private boolean done;

        private BoundedBodySubscriber(int maximumBytes, boolean rejectDeclaredLength) {
            if (maximumBytes <= 0) {
                throw new IllegalArgumentException("maximumBytes must be positive");
            }
            this.maximumBytes = maximumBytes;
            this.rejectDeclaredLength = rejectDeclaredLength;
            this.buffer = rejectDeclaredLength
                    ? new byte[0] : new byte[Math.min(INITIAL_BOUNDED_RESPONSE_BYTES, maximumBytes)];
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription newSubscription) {
            if (subscription != null) {
                newSubscription.cancel();
                return;
            }
            subscription = newSubscription;
            if (rejectDeclaredLength) {
                fail(new BoundedResponseTooLargeException());
                return;
            }
            newSubscription.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> items) {
            if (done) {
                return;
            }
            for (ByteBuffer item : items) {
                int remaining = item.remaining();
                if (remaining > maximumBytes - size) {
                    fail(new BoundedResponseTooLargeException());
                    return;
                }
                ensureCapacity(size + remaining);
                item.get(buffer, size, remaining);
                size += remaining;
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onError(Throwable failure) {
            fail(new BoundedResponseReadException());
        }

        @Override
        public synchronized void onComplete() {
            if (done) {
                return;
            }
            done = true;
            byte[] body = Arrays.copyOf(buffer, size);
            Arrays.fill(buffer, (byte) 0);
            result.complete(body);
        }

        private synchronized void fail(RuntimeException failure) {
            if (done) {
                return;
            }
            done = true;
            Arrays.fill(buffer, (byte) 0);
            if (subscription != null) {
                subscription.cancel();
            }
            result.completeExceptionally(failure);
        }

        private synchronized void abort() {
            fail(new BoundedResponseReadException());
        }

        private void ensureCapacity(int required) {
            if (required <= buffer.length) {
                return;
            }
            int doubled = Math.min(maximumBytes,
                    Math.max(INITIAL_BOUNDED_RESPONSE_BYTES, buffer.length * 2));
            int capacity = Math.max(required, doubled);
            byte[] expanded = Arrays.copyOf(buffer, capacity);
            Arrays.fill(buffer, (byte) 0);
            buffer = expanded;
        }
    }

    private static final class BoundedResponseTooLargeException extends RuntimeException {
        private BoundedResponseTooLargeException() {
            super(null, null, false, false);
        }
    }

    private static final class BoundedResponseReadException extends RuntimeException {
        private BoundedResponseReadException() {
            super(null, null, false, false);
        }
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

    /** Opaque query result bound to the committed snapshot that produced it. */
    public record QueryResult(String chainId, String stateMachineId, long committedHeight,
                              byte[] stateRoot, byte[] payload) {
        public QueryResult {
            if (chainId == null || chainId.isBlank()) {
                throw new IllegalArgumentException("chainId must not be blank");
            }
            if (stateMachineId == null || stateMachineId.isBlank()) {
                throw new IllegalArgumentException("stateMachineId must not be blank");
            }
            if (committedHeight < 0) {
                throw new IllegalArgumentException("committedHeight must not be negative");
            }
            Objects.requireNonNull(stateRoot, "stateRoot");
            if (stateRoot.length != 32) {
                throw new IllegalArgumentException("stateRoot must contain exactly 32 bytes");
            }
            Objects.requireNonNull(payload, "payload");
            if (payload.length > MAX_QUERY_RESULT_BYTES) {
                throw new IllegalArgumentException("payload must not exceed 1 MiB");
            }
            stateRoot = stateRoot.clone();
            payload = payload.clone();
        }

        @Override
        public byte[] stateRoot() {
            return stateRoot.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
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
     * An MPF inclusion or exclusion proof as served by the node. A null
     * {@code valueHex} denotes exclusion. Verify inclusion locally with
     * {@link ProofVerifier#verify(Proof)} or either form through the raw
     * verifier methods, optionally against a state root obtained from an L1
     * anchor instead of the node's own claim.
     */
    public record Proof(String keyHex, String chainId, String stateRootHex,
                        String proofWireHex, String valueHex, Long finalizedAtHeight,
                        Long committedHeight) {
        /**
         * Source-compatible constructor for proof envelopes produced before
         * the atomic committed-snapshot height was exposed.
         */
        public Proof(String keyHex, String chainId, String stateRootHex,
                     String proofWireHex, String valueHex, Long finalizedAtHeight) {
            this(keyHex, chainId, stateRootHex, proofWireHex, valueHex,
                    finalizedAtHeight, null);
        }
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
        private boolean directConnections;

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

        /**
         * Ignores ambient JVM proxy selectors for this client. Existing
         * builders retain their current proxy behavior unless this is called.
         */
        public Builder directConnections() {
            this.directConnections = true;
            return this;
        }

        public AppChainClient build() {
            return new AppChainClient(this);
        }
    }
}
