package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Small binary HTTP integration for an operator-owned threshold/HSM signer.
 * The request body is unsigned transaction-body CBOR; the response body is
 * signed transaction CBOR and must carry {@code X-Cardano-Tx-Id}.
 */
public final class HttpExternalSettlementSigner implements ExternalSettlementSigner {
    private static final int MAX_SIGNED_TRANSACTION_BYTES = 1024 * 1024;

    private final URI endpoint;
    private final HttpClient client;
    private final Supplier<String> authorization;
    private final Duration timeout;

    public HttpExternalSettlementSigner(
            URI endpoint,
            HttpClient client,
            Supplier<String> authorization,
            Duration timeout
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client");
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(
                    "external signer endpoint must be an absolute credential-free URI");
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !isLoopback(endpoint.getHost())) {
            throw new IllegalArgumentException(
                    "external signer requires HTTPS except on loopback");
        }
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException(
                    "external signer timeout must be in (0, 2 minutes]");
        }
    }

    @Override
    public SignedSettlement sign(SigningRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/cbor")
                .header("Accept", "application/cbor")
                .header("X-Yano-Claim-Id", request.claim().claimId())
                .header("X-Yano-Chain-Id", request.claim().chainId())
                .header("X-Yano-Bridge-Epoch",
                        Long.toString(request.claim().bridgeEpoch()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        request.unsignedBodyCbor()));
        String credential = authorization.get();
        if (credential != null && !credential.isBlank()) {
            builder.header("Authorization", credential.trim());
        }
        HttpResponse<java.io.InputStream> response = client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        long declaredLength = response.headers()
                .firstValueAsLong("Content-Length").orElse(-1);
        byte[] body;
        try (java.io.InputStream input = response.body()) {
            body = declaredLength > MAX_SIGNED_TRANSACTION_BYTES
                    ? new byte[0]
                    : input.readNBytes(MAX_SIGNED_TRANSACTION_BYTES + 1);
        }
        if (response.statusCode() != 200
                || !endpoint.equals(response.uri())
                || body.length == 0
                || body.length > MAX_SIGNED_TRANSACTION_BYTES) {
            throw new IllegalStateException(
                    "external signer returned an invalid bounded response");
        }
        String transactionId = response.headers()
                .firstValue("X-Cardano-Tx-Id")
                .orElseThrow(() -> new IllegalStateException(
                        "external signer omitted X-Cardano-Tx-Id"));
        return new SignedSettlement(transactionId, body);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }
}
