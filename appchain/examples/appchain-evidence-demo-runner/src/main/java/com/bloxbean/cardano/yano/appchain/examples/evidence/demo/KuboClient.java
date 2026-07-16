package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/** Bounded client for the exact Kubo add/pin read surface used by the demo. */
final class KuboClient {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int MAX_DOCUMENT_BYTES = 16_777_216;

    private final URI base;
    private final BoundedHttp http;

    KuboClient(URI base) {
        this.base = base;
        this.http = new BoundedHttp(Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    void probe() {
        BoundedHttp.Response response = http.post(uri("/api/v0/version"),
                Map.of("Accept", "application/json"), new byte[0], MAX_RESPONSE_BYTES);
        JsonNode root = successObject(response);
        String version = root.path("Version").asText("");
        if (version.isBlank() || version.length() > 64) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    CanonicalCid addUnpinned(byte[] document) {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_BYTES) {
            throw new DemoException(DemoError.SAMPLE_INVALID);
        }
        String boundary = boundaryFor(document);
        byte[] preamble = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"inspection-certificate.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] ending = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[preamble.length + document.length + ending.length];
        System.arraycopy(preamble, 0, body, 0, preamble.length);
        System.arraycopy(document, 0, body, preamble.length, document.length);
        System.arraycopy(ending, 0, body, preamble.length + document.length, ending.length);

        String query = "/api/v0/add?pin=false&cid-version=1&raw-leaves=true"
                + "&hash=sha2-256&chunker=size-262144&wrap-with-directory=false";
        BoundedHttp.Response response = http.post(uri(query), Map.of(
                        "Accept", "application/json",
                        "Content-Type", "multipart/form-data; boundary=" + boundary),
                body, MAX_RESPONSE_BYTES);
        JsonNode root = successObject(response);
        String hash = root.path("Hash").asText("");
        try {
            return CanonicalCid.fromText(hash);
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    PinState pinState(CanonicalCid cid) {
        String text = cid.canonicalText();
        URI target = uri("/api/v0/pin/ls?arg=" + encode(text) + "&type=all");
        BoundedHttp.Response response = http.post(target,
                Map.of("Accept", "application/json"), new byte[0], MAX_RESPONSE_BYTES);
        if (response.status() != 200) {
            // Kubo reports a known absent pin as a bounded JSON error. Other
            // statuses remain unavailable rather than guessed as absence.
            if (response.status() == 500) {
                JsonNode error = StrictJson.parse(response.body());
                String expected = "path '" + text + "' is not pinned";
                if (error.isObject() && error.size() == 3
                        && error.path("Code").isIntegralNumber()
                        && error.path("Code").asInt(-1) == 0
                        && "error".equals(error.path("Type").asText())
                        && expected.equals(error.path("Message").asText())) {
                    return PinState.ABSENT;
                }
            }
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
        JsonNode root = StrictJson.parse(response.body());
        JsonNode keys = root.path("Keys");
        JsonNode value = keys.path(text);
        if (!root.isObject() || !keys.isObject() || !value.isObject()
                || !value.path("Type").isTextual()) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return switch (value.path("Type").textValue()) {
            case "recursive" -> PinState.RECURSIVE;
            case "direct" -> PinState.DIRECT;
            case "indirect" -> PinState.INDIRECT;
            default -> throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        };
    }

    boolean requiredPinPresent(CanonicalCid cid, boolean recursive) {
        PinState state = pinState(cid);
        return state == PinState.RECURSIVE || !recursive && state == PinState.DIRECT;
    }

    void requireContent(CanonicalCid cid, byte[] expected) {
        if (cid == null || expected == null || expected.length == 0
                || expected.length > MAX_DOCUMENT_BYTES) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        URI target = uri("/api/v0/cat?arg=" + encode(cid.canonicalText()));
        BoundedHttp.Response response = http.post(target,
                Map.of("Accept", "application/octet-stream"), new byte[0], expected.length);
        if (response.status() != 200 || !Arrays.equals(response.body(), expected)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    static String boundaryFor(byte[] document) {
        if (document == null) {
            throw new DemoException(DemoError.SAMPLE_INVALID);
        }
        String base = "yano-" + HexFormat.of().formatHex(Digests.sha256(document));
        String candidate = base;
        for (int suffix = 0; contains(document,
                candidate.getBytes(StandardCharsets.US_ASCII)); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private JsonNode successObject(BoundedHttp.Response response) {
        if (response.status() != 200) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
        JsonNode root = StrictJson.parse(response.body());
        if (!root.isObject()) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return root;
    }

    private URI uri(String suffix) {
        return URI.create(base + suffix);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    enum PinState {
        ABSENT,
        INDIRECT,
        DIRECT,
        RECURSIVE
    }
}
