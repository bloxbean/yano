package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable provider-neutral metadata returned by HEAD or GET.
 *
 * @param versionId immutable provider version id, optional only for a source object
 * @param etag optional provider ETag (never treated as a digest)
 * @param contentLength exact provider content length
 * @param contentType exact provider content type
 * @param userMetadata bounded provider user metadata
 * @param encryptionMode observed provider encryption mode
 * @param kmsKeyId observed canonical KMS key ARN, when applicable
 * @param retentionMode observed provider retention mode
 * @param retainUntilEpochMillis observed provider retention deadline, when locked
 */
public record StoredObjectMetadata(String versionId,
                                   String etag,
                                   long contentLength,
                                   String contentType,
                                   Map<String, String> userMetadata,
                                   EncryptionMode encryptionMode,
                                   String kmsKeyId,
                                   ObjectRetentionMode retentionMode,
                                   Long retainUntilEpochMillis) {
    private static final int MAX_METADATA_ENTRIES = 16;

    /** Validates and snapshots all provider observations. */
    public StoredObjectMetadata {
        versionId = optionalAscii(versionId, 1_024, "version id");
        etag = optionalAscii(etag, 256, "etag");
        if (contentLength < 0 || contentLength > ObjectPutCommandV1.MAX_OBJECT_BYTES) {
            throw new IllegalArgumentException("object length is outside the v1 bound");
        }
        contentType = requiredAscii(contentType, 255, "content type");
        if (userMetadata == null || userMetadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("object metadata is not bounded");
        }
        Map<String, String> metadataCopy = new LinkedHashMap<>();
        userMetadata.forEach((name, value) -> metadataCopy.put(
                requiredAscii(name, 64, "metadata name"),
                requiredAscii(value, 1_024, "metadata value")));
        userMetadata = Map.copyOf(metadataCopy);
        encryptionMode = Objects.requireNonNull(encryptionMode, "encryptionMode");
        kmsKeyId = optionalAscii(kmsKeyId, 2_048, "KMS key id");
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

    private static String optionalAscii(String value, int maxBytes, String label) {
        return value == null ? null : requiredAscii(value, maxBytes, label);
    }

    private static String requiredAscii(String value, int maxBytes, String label) {
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
