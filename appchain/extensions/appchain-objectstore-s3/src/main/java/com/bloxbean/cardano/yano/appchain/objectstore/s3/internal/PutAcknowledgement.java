package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

import java.nio.charset.StandardCharsets;

/**
 * Provider acknowledgement for one conditional destination create.
 *
 * @param versionId mandatory immutable version identifier
 * @param etag optional provider entity tag
 */
public record PutAcknowledgement(String versionId, String etag) {
    /** Requires an immutable version id and bounds the optional ETag. */
    public PutAcknowledgement {
        versionId = ascii(versionId, 1_024, "version id");
        if ("null".equals(versionId)) {
            throw new IllegalArgumentException("invalid version id");
        }
        etag = etag == null ? null : ascii(etag, 256, "etag");
    }

    private static String ascii(String value, int maxBytes, String label) {
        if (value == null || value.isEmpty()
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.US_ASCII).length > maxBytes
                || value.chars().anyMatch(character ->
                character < 0x20 || character > 0x7e)) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }
}
