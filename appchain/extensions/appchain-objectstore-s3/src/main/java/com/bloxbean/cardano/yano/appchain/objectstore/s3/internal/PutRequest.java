package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable conditional-create request understood by provider adapters.
 *
 * @param bucket allowlisted destination bucket
 * @param key composed destination key
 * @param contentType exact media type
 * @param contentLength exact body length
 * @param sha256 expected body SHA-256
 * @param userMetadata exact immutable Yano metadata
 * @param encryptionMode configured server-side encryption mode
 * @param kmsKeyId canonical KMS key ARN for SSE-KMS, otherwise {@code null}
 * @param retentionMode configured retention mode
 * @param retainUntilEpochMillis exact retention deadline, when locked
 */
public record PutRequest(String bucket,
                         String key,
                         String contentType,
                         long contentLength,
                         byte[] sha256,
                         Map<String, String> userMetadata,
                         EncryptionMode encryptionMode,
                         String kmsKeyId,
                         ObjectRetentionMode retentionMode,
                         Long retainUntilEpochMillis) {
    /** Validates all bounded request data and snapshots mutable values. */
    public PutRequest {
        bucket = ascii(bucket, 255, "bucket");
        key = ascii(key, 1_024, "key");
        contentType = ascii(contentType, 255, "content type");
        if (contentLength < 0 || contentLength > ObjectPutCommandV1.MAX_OBJECT_BYTES) {
            throw new IllegalArgumentException("invalid content length");
        }
        if (sha256 == null || sha256.length != 32) {
            throw new IllegalArgumentException("invalid SHA-256 digest");
        }
        sha256 = sha256.clone();
        if (userMetadata == null || userMetadata.size() > 16) {
            throw new IllegalArgumentException("invalid user metadata");
        }
        Map<String, String> metadataCopy = new LinkedHashMap<>();
        userMetadata.forEach((name, value) -> metadataCopy.put(
                ascii(name, 64, "metadata name"), ascii(value, 1_024, "metadata value")));
        userMetadata = Map.copyOf(metadataCopy);
        encryptionMode = Objects.requireNonNull(encryptionMode, "encryptionMode");
        kmsKeyId = kmsKeyId == null ? null : ascii(kmsKeyId, 2_048, "KMS key id");
        if (encryptionMode == EncryptionMode.SSE_KMS && kmsKeyId == null
                || encryptionMode != EncryptionMode.SSE_KMS && kmsKeyId != null) {
            throw new IllegalArgumentException("KMS key id does not match encryption mode");
        }
        retentionMode = Objects.requireNonNull(retentionMode, "retentionMode");
        if (retentionMode.retainUntilRequired()) {
            if (retainUntilEpochMillis == null || retainUntilEpochMillis < 0) {
                throw new IllegalArgumentException("locked object requires a retention deadline");
            }
        } else if (retainUntilEpochMillis != null) {
            throw new IllegalArgumentException("unlocked object cannot have a retention deadline");
        }
    }

    /**
     * Returns a defensive digest copy.
     *
     * @return the 32-byte SHA-256 digest
     */
    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    private static String ascii(String value, int maxBytes, String label) {
        if (value == null || value.isEmpty()
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.US_ASCII).length > maxBytes) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }
}
