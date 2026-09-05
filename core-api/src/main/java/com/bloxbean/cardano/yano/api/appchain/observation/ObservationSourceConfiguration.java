package com.bloxbean.cardano.yano.api.appchain.observation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical helpers for consensus-identifying Phase-1 source configurations. */
public final class ObservationSourceConfiguration {
    private ObservationSourceConfiguration() {
    }

    public static byte[] attestorSetDigest(List<byte[]> publicKeys) {
        List<byte[]> keys = new ArrayList<>(Objects.requireNonNull(publicKeys, "publicKeys"));
        keys.replaceAll(key -> {
            if (key == null || key.length != 32) {
                throw new IllegalArgumentException("attestor public key must be 32 bytes");
            }
            return key.clone();
        });
        keys.sort(Arrays::compareUnsigned);
        return digest("attestors-v1", keys);
    }

    public static byte[] httpsSourceDigest(String canonicalUrl, String method, String sourceId,
                                           String versionHeader) {
        List<byte[]> values = List.of(
                boundedAscii(canonicalUrl, 2048, "canonical URL"),
                boundedAscii(method, 8, "HTTP method"),
                boundedAscii(sourceId, 256, "source id"),
                boundedAscii(versionHeader, 128, "version header"));
        return digest("https-exact-v1", values);
    }

    public static byte[] attestedHttpsSourceDigest(String canonicalUrl, String method,
                                                   List<byte[]> publicKeys) {
        return digest("https-attested-v1", List.of(
                boundedAscii(canonicalUrl, 2048, "canonical URL"),
                boundedAscii(method, 8, "HTTP method"),
                attestorSetDigest(publicKeys)));
    }

    private static byte[] digest(String type, List<byte[]> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(("yano/observation/source-config/" + type + "\0")
                    .getBytes(StandardCharsets.US_ASCII));
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(values.size());
                for (byte[] value : values) {
                    out.writeInt(value.length);
                    out.write(value);
                }
            }
            return ObservationHashes.digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static byte[] boundedAscii(String value, int maxBytes, String label) {
        Objects.requireNonNull(value, label);
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length == 0 || bytes.length > maxBytes
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return bytes;
    }
}
