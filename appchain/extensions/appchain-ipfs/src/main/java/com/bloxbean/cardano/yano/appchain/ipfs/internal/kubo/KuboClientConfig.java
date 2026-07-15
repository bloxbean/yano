package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable construction values for one Kubo RPC client.
 *
 * <p>This internal value is deliberately independent of the public connector
 * configuration model. It accepts only an origin URI; every RPC path and query
 * is hardcoded by the adapter.</p>
 */
public final class KuboClientConfig {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_BEARER_TOKEN_LENGTH = 2_048;
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("[A-Za-z0-9\\-._~+/]+={0,16}");

    private final URI apiEndpoint;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration closeTimeout;
    private final Optional<String> bearerToken;

    /**
     * Creates validated internal Kubo client values.
     *
     * @param apiEndpoint configured Kubo HTTP(S) origin, without path/query
     * @param connectTimeout positive bounded TCP/TLS connection timeout
     * @param requestTimeout positive bounded timeout for each RPC request
     * @param closeTimeout positive bounded graceful client shutdown timeout
     * @param bearerToken optional RFC 6750 bearer token
     */
    public KuboClientConfig(URI apiEndpoint,
                            Duration connectTimeout,
                            Duration requestTimeout,
                            Duration closeTimeout,
                            Optional<String> bearerToken) {
        this.apiEndpoint = normalizeEndpoint(apiEndpoint);
        this.connectTimeout = timeout(connectTimeout);
        this.requestTimeout = timeout(requestTimeout);
        this.closeTimeout = timeout(closeTimeout);
        this.bearerToken = token(bearerToken);
    }

    /**
     * Returns the normalized HTTP(S) origin.
     *
     * @return configured origin
     */
    public URI apiEndpoint() {
        return apiEndpoint;
    }

    /**
     * Returns the validated connection timeout.
     *
     * @return connection timeout
     */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * Returns the validated per-request timeout.
     *
     * @return request timeout
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns the validated graceful-close timeout.
     *
     * @return close timeout
     */
    public Duration closeTimeout() {
        return closeTimeout;
    }

    Optional<String> bearerToken() {
        return bearerToken;
    }

    @Override
    public String toString() {
        return "KuboClientConfig[apiEndpoint=<redacted>"
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", closeTimeout=" + closeTimeout
                + ", bearerToken=" + (bearerToken.isPresent() ? "<redacted>" : "<absent>")
                + ']';
    }

    private static URI normalizeEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "apiEndpoint");
        String scheme = endpoint.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Kubo API endpoint must be absolute");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Kubo API endpoint must use HTTP(S)");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Kubo API endpoint must be an origin");
        }
        String path = endpoint.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("Kubo API endpoint must not contain a path");
        }
        int port = endpoint.getPort();
        if (port == 0 || port > 65_535) {
            throw new IllegalArgumentException("Kubo API endpoint port is invalid");
        }
        try {
            return new URI(scheme, null, endpoint.getHost().toLowerCase(Locale.ROOT),
                    port, null, null, null);
        } catch (URISyntaxException invalidEndpoint) {
            throw new IllegalArgumentException("Kubo API endpoint is invalid");
        }
    }

    private static Duration timeout(Duration value) {
        Objects.requireNonNull(value, "timeout");
        if (value.isZero() || value.isNegative() || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Kubo timeout is outside the supported range");
        }
        return value;
    }

    private static Optional<String> token(Optional<String> value) {
        Objects.requireNonNull(value, "bearerToken");
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String token = Objects.requireNonNull(value.get(), "bearerToken value");
        if (token.length() > MAX_BEARER_TOKEN_LENGTH
                || !BEARER_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("Kubo bearer token is invalid");
        }
        return Optional.of(token);
    }
}
