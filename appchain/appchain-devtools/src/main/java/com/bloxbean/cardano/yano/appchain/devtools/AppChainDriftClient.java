package com.bloxbean.cardano.yano.appchain.devtools;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.regex.Pattern;

/** Bounded client and secret-free comparison logic for protected runtime identities. */
final class AppChainDriftClient {
    private static final int MAX_IDENTITY_BYTES = 64 * 1024;
    private static final Pattern SHA256 = Pattern.compile("(?:sha256:)?[0-9a-f]{64}");

    private final HttpClient http;
    private final ObjectMapper json;

    AppChainDriftClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    AppChainDriftClient(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    AppChainProjectModel.DriftReport compare(
            AppChainProjectModel.Lock lock,
            String chainId,
            List<URI> peers,
            String apiKey) throws IOException {
        if (peers == null || peers.isEmpty()) {
            throw new IllegalArgumentException("at least one --peer is required");
        }
        if (apiKey != null && (apiKey.length() > 4_096
                || apiKey.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("operator API key has an invalid header form");
        }
        List<AppChainProjectModel.RuntimeIdentity> identities = new ArrayList<>();
        for (URI peer : peers) {
            identities.add(read(peer, chainId, apiKey));
        }

        List<AppChainProjectModel.DriftCheck> checks = new ArrayList<>();
        for (int index = 0; index < identities.size(); index++) {
            String label = "peer-" + (index + 1);
            AppChainProjectModel.RuntimeIdentity identity = identities.get(index);
            checks.add(check("identity.schema", label,
                    Objects.equals("v1", identity.schemaVersion())));
            checks.add(check("identity.chain", label,
                    Objects.equals(chainId, identity.chainId())));
            checks.add(compareRequired("project.resolved-config", label,
                    lock.resolvedConfigDigest(), identity.resolvedConfigDigest()));
            checks.add(compareRequired("release.catalog", label,
                    lock.catalogDigests().get("releaseIndex"), identity.releaseCatalogDigest()));
        }

        AppChainProjectModel.RuntimeIdentity baseline = identities.getFirst();
        if (identities.size() == 1) {
            checks.add(status("cluster.consensus-profile", "peer-1", "BASELINE_ONLY"));
            checks.add(status("cluster.plugin-catalog", "peer-1", "BASELINE_ONLY"));
            checks.add(baseline.compositeProfileDigest() == null
                    ? status("cluster.composite-profile", "peer-1", "NOT_APPLICABLE")
                    : status("cluster.composite-profile", "peer-1", "BASELINE_ONLY"));
        } else {
            for (int index = 1; index < identities.size(); index++) {
                String label = "peer-" + (index + 1);
                AppChainProjectModel.RuntimeIdentity identity = identities.get(index);
                checks.add(compareRequired("cluster.consensus-profile", label,
                        baseline.consensusProfileDigest(), identity.consensusProfileDigest()));
                checks.add(compareRequired("cluster.plugin-catalog", label,
                        baseline.pluginCatalogFingerprint(), identity.pluginCatalogFingerprint()));
                checks.add(compareOptional("cluster.composite-profile", label,
                        baseline.compositeProfileDigest(), identity.compositeProfileDigest()));
            }
        }

        String status = checks.stream().anyMatch(check -> "MISMATCH".equals(check.status()))
                ? "DRIFT_DETECTED"
                : checks.stream().anyMatch(check -> "MISSING".equals(check.status())
                        || "BASELINE_ONLY".equals(check.status()))
                ? "DRIFT_INCOMPLETE" : "DRIFT_OK";
        return new AppChainProjectModel.DriftReport(
                status, identities.size(), List.copyOf(checks));
    }

    private AppChainProjectModel.RuntimeIdentity read(
            URI base, String chainId, String apiKey) throws IOException {
        URI endpoint = identityEndpoint(base, chainId);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("X-API-Key", apiKey);
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("runtime identity request was interrupted", interrupted);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("runtime identity endpoint returned HTTP "
                        + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_IDENTITY_BYTES + 1);
            if (bytes.length > MAX_IDENTITY_BYTES) {
                throw new IOException("runtime identity response exceeds the safety limit");
            }
            AppChainProjectModel.RuntimeIdentity identity = json.readValue(
                    bytes, AppChainProjectModel.RuntimeIdentity.class);
            validateIdentity(identity);
            return identity;
        }
    }

    static URI identityEndpoint(URI base, String chainId) {
        Objects.requireNonNull(base, "peer");
        String scheme = base.getScheme() == null
                ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException(
                    "--peer must be an http(s) base URL without credentials, query, or fragment");
        }
        String path = base.getPath() == null ? "/" : base.getPath();
        if (!path.endsWith("/")) path += "/";
        String segment = URLEncoder.encode(chainId, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return base.resolve(path + "app-chain/chains/" + segment + "/identity");
    }

    private static void validateIdentity(AppChainProjectModel.RuntimeIdentity identity)
            throws IOException {
        if (identity == null || identity.schemaVersion() == null || identity.chainId() == null) {
            throw new IOException("runtime identity response is incomplete");
        }
        for (String digest : List.of(
                nullToEmpty(identity.consensusProfileDigest()),
                nullToEmpty(identity.compositeProfileDigest()),
                nullToEmpty(identity.pluginCatalogFingerprint()),
                nullToEmpty(identity.resolvedConfigDigest()),
                nullToEmpty(identity.releaseCatalogDigest()))) {
            if (!digest.isEmpty() && !SHA256.matcher(digest).matches()) {
                throw new IOException("runtime identity response contains a malformed digest");
            }
        }
    }

    private static AppChainProjectModel.DriftCheck compareRequired(
            String category, String peer, String expected, String actual) {
        if (expected == null || actual == null) return status(category, peer, "MISSING");
        return check(category, peer, Objects.equals(expected, actual));
    }

    private static AppChainProjectModel.DriftCheck compareOptional(
            String category, String peer, String expected, String actual) {
        if (expected == null && actual == null) return status(category, peer, "NOT_APPLICABLE");
        if (expected == null || actual == null) return status(category, peer, "MISMATCH");
        return check(category, peer, Objects.equals(expected, actual));
    }

    private static AppChainProjectModel.DriftCheck check(
            String category, String peer, boolean matches) {
        return status(category, peer, matches ? "MATCH" : "MISMATCH");
    }

    private static AppChainProjectModel.DriftCheck status(
            String category, String peer, String status) {
        return new AppChainProjectModel.DriftCheck(category, peer, status);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
