package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClient;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Opt-in compatibility coverage against a real offline Kubo daemon. */
@EnabledIfSystemProperty(named = "yano.ipfs.integration.enabled", matches = "true")
class KuboIpfsPinClientRealIntegrationTest {
    private static final byte[] FIXTURE =
            "phase-1.3-real-kubo-fixture".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INDIRECT_FIXTURE =
            "0123456789abcdef".repeat(8).getBytes(StandardCharsets.US_ASCII);
    private static final CanonicalCid FIXTURE_CID = CanonicalCid.fromText(
            "bafkreicosf6sgdnw3flmsyljjvmv3tclxqqom7tx2okkpezbydp2lc5tma");
    private static final CanonicalCid INDIRECT_ROOT_CID = CanonicalCid.fromText(
            "bafybeihd35th6jgk5cbwcx53sstu3jgs23f2tusa2n5xkvkbqk2rlvrc6y");
    private static final CanonicalCid INDIRECT_CHILD_CID = CanonicalCid.fromText(
            "bafkreib6wg6uhgkh5n3ctghfm3gmfyezy6irdczpiblzzrhx3ivvaynx7e");
    private static final CanonicalCid MISSING_CID = CanonicalCid.fromText(
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi");
    private static final byte[] EFFECT_ID = new byte[32];

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void provesNewExistingAndMissingPinPathsAgainstRealKubo() throws Exception {
        URI configuredEndpoint = URI.create(requiredProperty("yano.ipfs.integration.endpoint"));
        KuboClientConfig config = new KuboClientConfig(configuredEndpoint,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Optional.empty());
        URI endpoint = config.apiEndpoint();

        cleanupFixture(endpoint);
        try {
            provisionUnpinnedFixture(endpoint);
            provisionIndirectFixture(endpoint);
            try (IpfsPinClient client = new KuboIpfsPinClient(config)) {
                assertThat(client.probe(INDIRECT_ROOT_CID, EFFECT_ID))
                        .isEqualTo(PinState.RECURSIVE);
                assertThat(client.probe(INDIRECT_CHILD_CID, EFFECT_ID))
                        .isEqualTo(PinState.INDIRECT);
                assertThat(client.probe(FIXTURE_CID, EFFECT_ID)).isEqualTo(PinState.ABSENT);
                client.add(FIXTURE_CID, true, EFFECT_ID);
                assertThat(client.probe(FIXTURE_CID, EFFECT_ID)).isEqualTo(PinState.RECURSIVE);
            }

            // A fresh HTTP client simulates connector/runtime restart. Kubo's
            // durable pinset must reconcile without relying on process memory.
            try (IpfsPinClient restarted = new KuboIpfsPinClient(config)) {
                assertThat(restarted.probe(FIXTURE_CID, EFFECT_ID))
                        .isEqualTo(PinState.RECURSIVE);
                restarted.add(FIXTURE_CID, true, EFFECT_ID);
                assertThat(restarted.probe(FIXTURE_CID, EFFECT_ID))
                        .isEqualTo(PinState.RECURSIVE);

                assertThat(restarted.probe(MISSING_CID, EFFECT_ID)).isEqualTo(PinState.ABSENT);
                assertThatExceptionOfType(IpfsProviderException.class)
                        .isThrownBy(() -> restarted.add(MISSING_CID, true, EFFECT_ID))
                        .satisfies(failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.CONTENT_UNAVAILABLE));
                assertThat(restarted.probe(MISSING_CID, EFFECT_ID)).isEqualTo(PinState.ABSENT);
            }
        } finally {
            cleanupFixture(endpoint);
        }
    }

    private static void provisionUnpinnedFixture(URI endpoint) throws Exception {
        provisionFixture(endpoint, FIXTURE, "fixture.bin",
                "/api/v0/add?cid-version=1&raw-leaves=true&pin=false"
                        + "&progress=false&wrap-with-directory=false",
                FIXTURE_CID);
    }

    private static void provisionIndirectFixture(URI endpoint) throws Exception {
        provisionFixture(endpoint, INDIRECT_FIXTURE, "indirect-fixture.bin",
                "/api/v0/add?cid-version=1&raw-leaves=true&pin=true"
                        + "&progress=false&wrap-with-directory=false&chunker=size-32",
                INDIRECT_ROOT_CID);
    }

    private static void provisionFixture(URI endpoint,
                                         byte[] fixture,
                                         String filename,
                                         String path,
                                         CanonicalCid expectedCid) throws Exception {
        String boundary = "yano-adr013-kubo-fixture-v1";
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[prefix.length + fixture.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(fixture, 0, body, prefix.length, fixture.length);
        System.arraycopy(suffix, 0, body, prefix.length + fixture.length, suffix.length);

        try {
            RpcResponse response = post(endpoint, path, body,
                    "multipart/form-data; boundary=" + boundary);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("\"Hash\":\"" + expectedCid + "\"");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static void cleanupFixture(URI endpoint) {
        postIgnoringFailure(endpoint,
                "/api/v0/pin/rm?arg=" + FIXTURE_CID + "&recursive=true");
        postIgnoringFailure(endpoint,
                "/api/v0/pin/rm?arg=" + INDIRECT_ROOT_CID + "&recursive=true");
        postIgnoringFailure(endpoint,
                "/api/v0/block/rm?arg=" + FIXTURE_CID + "&force=true&quiet=true");
        postIgnoringFailure(endpoint,
                "/api/v0/block/rm?arg=" + INDIRECT_ROOT_CID + "&force=true&quiet=true");
        postIgnoringFailure(endpoint,
                "/api/v0/block/rm?arg=" + INDIRECT_CHILD_CID + "&force=true&quiet=true");
    }

    private static void postIgnoringFailure(URI endpoint, String path) {
        try {
            post(endpoint, path, new byte[0], null);
        } catch (Exception ignored) {
            // Best-effort cleanup runs both before and after the isolated test.
        }
    }

    private static RpcResponse post(URI endpoint,
                                    String path,
                                    byte[] body,
                                    String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build()) {
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new RpcResponse(response.statusCode(), response.body());
        }
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("missing required integration property");
        }
        return value;
    }

    private record RpcResponse(int status, String body) {
    }
}
