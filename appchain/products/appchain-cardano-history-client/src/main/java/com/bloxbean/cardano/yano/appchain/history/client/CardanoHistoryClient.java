package com.bloxbean.cardano.yano.appchain.history.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit-chain, bounded client for the Cardano History product domain API. */
public final class CardanoHistoryClient {
    public static final String BUNDLE_ID = "com.bloxbean.cardano.yano.appchain.cardano-history";
    public static final String PARAMS_COMPONENT = "l1-epoch-params-v1";
    public static final String STAKE_COMPONENT = "l1-epoch-stake-v1";
    public static final String GOVERNANCE_COMPONENT = "l1-epoch-governance-v1";
    public static final int MAX_EPOCH_PAGE = 15;
    public static final int DEFAULT_EPOCH_PAGE = 15;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final String baseUrl;
    private final String chainId;
    private final String apiKey;
    private final HttpClient http;
    private final CardanoHistoryProofClient proofs;

    private CardanoHistoryClient(Builder builder) {
        baseUrl = trim(builder.baseUrl);
        chainId = required(builder.chainId, "chainId");
        apiKey = builder.apiKey;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        AppChainClient generic = AppChainClient.builder(baseUrl).chainId(chainId)
                .apiKey(apiKey).build();
        proofs = new CardanoHistoryProofClient(generic, PARAMS_COMPONENT,
                STAKE_COMPONENT, GOVERNANCE_COMPONENT);
    }

    public static Builder builder(String baseUrl, String chainId) {
        return new Builder(baseUrl, chainId);
    }

    public Status status() {
        JsonNode node = get("status", "");
        Root root = root(node);
        return new Status(root, optionalLong(node, "latestEpoch"));
    }

    public Epochs epochs(int limit) {
        if (limit < 1 || limit > MAX_EPOCH_PAGE) {
            throw new IllegalArgumentException("limit must be 1-" + MAX_EPOCH_PAGE);
        }
        JsonNode node = get("epochs", "&limit=" + limit);
        Root root = root(node);
        List<Long> epochs = require(node, "epochs").isArray()
                ? java.util.stream.StreamSupport.stream(node.get("epochs").spliterator(), false)
                .map(JsonNode::longValue).toList() : throwMalformed();
        return new Epochs(root, optionalLong(node, "latestEpoch"), epochs);
    }

    public Parameters parameters(long epoch) {
        JsonNode node = get("epochs/" + epoch(epoch) + "/parameters", "");
        return new Parameters(root(node), epoch, bool(node, "found"),
                optionalHex(node, "canonicalValueHex"), proof(node));
    }

    public Stake stake(long epoch, int credentialType, byte[] credentialHash) {
        exact(credentialHash, 28, "credentialHash");
        JsonNode node = get("epochs/" + epoch(epoch) + "/stake/" + credentialType
                + "/" + HexFormat.of().formatHex(credentialHash), "");
        return new Stake(root(node), epoch, bool(node, "complete"),
                bool(node, "absenceProvable"), bool(node, "found"),
                optionalBigInteger(node, "coin"), optionalHex(node, "poolHash"), proof(node));
    }

    public DRep drep(long epoch, int drepType, byte[] drepHash) {
        exact(drepHash, 28, "drepHash");
        JsonNode node = get("epochs/" + epoch(epoch) + "/dreps/" + drepType
                + "/" + HexFormat.of().formatHex(drepHash), "");
        return new DRep(root(node), epoch, bool(node, "complete"),
                bool(node, "absenceProvable"), bool(node, "found"),
                optionalBigInteger(node, "coin"), proof(node));
    }

    public Proposal proposal(long epoch, byte[] transactionId, int index) {
        exact(transactionId, 32, "transactionId");
        if (index < 0 || index > 65_535) throw new IllegalArgumentException("index must be 0-65535");
        JsonNode node = get("proposals/" + HexFormat.of().formatHex(transactionId)
                + "/" + index, "&epoch=" + epoch(epoch));
        return new Proposal(root(node), epoch, bool(node, "complete"),
                bool(node, "absenceProvable"), bool(node, "found"),
                optionalText(node, "actionType"), optionalText(node, "status"),
                optionalText(node, "reason"), proof(node));
    }

    /** Latest L1-confirmed app-chain root reported by this node. */
    public ConfirmedAnchor confirmedAnchor() {
        JsonNode node = getEndpoint(baseUrl + "/app-chain/chains/" + encode(chainId)
                + "/anchor/commitment");
        if (!chainId.equals(text(node, "chainId"))) throw malformed();
        Root root = new Root(chainId, nonnegative(node, "anchoredHeight"),
                canonicalHex(text(node, "stateRoot"), 32));
        return new ConfirmedAnchor(root, text(node, "mode"),
                canonicalHex(text(node, "blockHash"), 32),
                text(node, "transactionHash"), nonnegative(node, "l1Slot"));
    }

    /** Root-fixed parameter proof against the latest L1-confirmed root. */
    public Optional<CardanoHistoryProofBundle.ProtocolParameters> parameterProof(long epoch) {
        Root anchor = confirmedAnchor().root();
        var proof = proofs.protocolParameters(epoch, anchor.committedHeight())
                .filter(value -> value.fact().decodedValue() != null);
        proof.ifPresent(value -> requireSameRoot(anchor, value.fact().proof().stateRootHex()));
        return proof;
    }

    /** Root-fixed nested authenticated-snapshot stake proof against the latest L1-confirmed root. */
    public Optional<CardanoHistoryProofBundle.SnapshotStake> stakeProof(
            long epoch, int credentialType, byte[] credentialHash,
            CardanoHistoryProofBundle.StakeMode mode, BigInteger coin, byte[] poolHash) {
        Root anchor = confirmedAnchor().root();
        var proof = proofs.stake(epoch, credentialType, credentialHash, mode, coin,
                poolHash, anchor.committedHeight());
        proof.ifPresent(value -> requireSameRoot(
                anchor, value.proof().anchor().stateRootHex()));
        return proof;
    }

    private JsonNode get(String path, String suffix) {
        return getEndpoint(baseUrl + "/plugins/" + BUNDLE_ID + "/" + path
                + "?chain=" + encode(chainId) + suffix);
    }

    private JsonNode getEndpoint(String endpoint) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET();
        if (apiKey != null && !apiKey.isBlank()) request.header("X-API-Key", apiKey);
        try {
            HttpResponse<InputStream> response = http.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) throw new CardanoHistoryClientException(
                    Error.RESPONSE_TOO_LARGE, "Cardano History response exceeded 2 MiB");
            if (response.statusCode() == 404 || response.statusCode() == 503) {
                throw new CardanoHistoryClientException(Error.UNAVAILABLE,
                        "Cardano History data is unavailable");
            }
            if (response.statusCode() != 200) throw new CardanoHistoryClientException(
                    Error.TRANSPORT, "Cardano History request failed with HTTP " + response.statusCode());
            return JSON.readTree(body);
        } catch (CardanoHistoryClientException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CardanoHistoryClientException(Error.TRANSPORT, "Cardano History request interrupted");
        } catch (IOException | RuntimeException failure) {
            throw new CardanoHistoryClientException(Error.TRANSPORT, "Cardano History request failed", failure);
        }
    }

    private Root root(JsonNode node) {
        if (integer(node, "apiVersion") != 1
                || !chainId.equals(text(node, "chainId"))
                || !"cardano-history".equals(text(node, "applicationId"))) throw malformed();
        long height = nonnegative(node, "committedHeight");
        String stateRoot = canonicalHex(text(node, "stateRoot"), 32);
        return new Root(chainId, height, stateRoot);
    }

    private static ProofCoordinates proof(JsonNode node) {
        JsonNode proof = require(node, "proof");
        return new ProofCoordinates(text(proof, "kind"), optionalText(proof, "physicalKey"),
                optionalText(proof, "factPhysicalKey"),
                optionalText(proof, "completenessPhysicalKey"),
                optionalText(proof, "seriesId"), optionalText(proof, "secondaryKey"));
    }

    private static void requireSameRoot(Root expected, String actual) {
        if (!expected.stateRootHex().equals(actual)) throw new CardanoHistoryClientException(
                Error.SNAPSHOT_RACE, "State advanced between query and root-fixed proof");
    }

    private static String trim(String value) {
        String result = required(value, "baseUrl");
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long epoch(long value) {
        if (value < 0) throw new IllegalArgumentException("epoch must be nonnegative");
        return value;
    }

    private static void exact(byte[] value, int bytes, String name) {
        if (value == null || value.length != bytes) throw new IllegalArgumentException(
                name + " must contain " + bytes + " bytes");
    }

    private static JsonNode require(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null) throw malformed();
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = require(node, field);
        if (!value.isTextual() || value.textValue().isBlank()) throw malformed();
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isTextual() ? value.textValue() : throwMalformed();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = require(node, field);
        if (!value.isBoolean()) throw malformed();
        return value.booleanValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = require(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw malformed();
        return value.intValue();
    }

    private static long nonnegative(JsonNode node, String field) {
        JsonNode value = require(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw malformed();
        return value.longValue();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isIntegralNumber()
                && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : throwMalformed();
    }

    private static BigInteger optionalBigInteger(JsonNode node, String field) {
        String value = optionalText(node, field);
        try {
            return value == null ? null : new BigInteger(value);
        } catch (NumberFormatException malformed) {
            throw malformed();
        }
    }

    private static String optionalHex(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value != null && (value.length() % 2 != 0 || !value.matches("[0-9a-f]+"))) throw malformed();
        return value;
    }

    private static String canonicalHex(String value, int bytes) {
        if (value.length() != bytes * 2 || !value.matches("[0-9a-f]+")) throw malformed();
        return value;
    }

    private static CardanoHistoryClientException malformed() {
        return new CardanoHistoryClientException(Error.MALFORMED_RESPONSE,
                "Malformed Cardano History response");
    }

    private static <T> T throwMalformed() {
        throw malformed();
    }

    public enum Error { UNAVAILABLE, TRANSPORT, RESPONSE_TOO_LARGE, MALFORMED_RESPONSE, SNAPSHOT_RACE }

    public static final class CardanoHistoryClientException extends RuntimeException {
        private final Error error;
        public CardanoHistoryClientException(Error error, String message) { super(message); this.error = error; }
        public CardanoHistoryClientException(Error error, String message, Throwable cause) {
            super(message, cause); this.error = error;
        }
        public Error error() { return error; }
    }

    public record Root(String chainId, long committedHeight, String stateRootHex) { }
    public record ConfirmedAnchor(Root root, String mode, String blockHashHex,
                                  String transactionHash, long l1Slot) { }
    public record ProofCoordinates(String kind, String physicalKey, String factPhysicalKey,
                                   String completenessPhysicalKey, String seriesId,
                                   String secondaryKey) { }
    public record Status(Root root, Long latestEpoch) { }
    public record Epochs(Root root, Long latestEpoch, List<Long> epochs) {
        public Epochs { epochs = List.copyOf(epochs); }
    }
    public record Parameters(Root root, long epoch, boolean found,
                             String canonicalValueHex, ProofCoordinates proof) { }
    public record Stake(Root root, long epoch, boolean complete, boolean absenceProvable,
                        boolean found, BigInteger coin, String poolHashHex,
                        ProofCoordinates proof) { }
    public record DRep(Root root, long epoch, boolean complete, boolean absenceProvable,
                       boolean found, BigInteger coin, ProofCoordinates proof) { }
    public record Proposal(Root root, long epoch, boolean complete, boolean absenceProvable,
                           boolean found, String actionType, String status, String reason,
                           ProofCoordinates proof) { }

    public static final class Builder {
        private final String baseUrl;
        private final String chainId;
        private String apiKey;
        private Builder(String baseUrl, String chainId) {
            this.baseUrl = required(baseUrl, "baseUrl");
            this.chainId = required(chainId, "chainId");
        }
        public Builder apiKey(String value) { apiKey = value; return this; }
        public CardanoHistoryClient build() { return new CardanoHistoryClient(this); }
    }
}
