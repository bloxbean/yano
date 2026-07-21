package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.internal.Blake2b256;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Domain-separated state keys, effect scopes, and Kafka record keys. */
public final class EvidenceKeys {
    private static final byte[] ID_DOMAIN =
            "yano:evidence-id:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEAD_PREFIX =
            "evidence/head/v1/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RECORD_PREFIX =
            "evidence/record/v1/".getBytes(StandardCharsets.US_ASCII);

    private EvidenceKeys() {
    }

    /** BLAKE2b-256 over the frozen id domain followed by the exact ASCII id. */
    public static byte[] idHash(String evidenceId) {
        byte[] id = EvidenceValidation.evidenceId(evidenceId)
                .getBytes(StandardCharsets.US_ASCII);
        return Blake2b256.hash(ByteBuffer.allocate(ID_DOMAIN.length + id.length)
                .put(ID_DOMAIN).put(id).array());
    }

    /** Returns {@code evidence/head/v1/ || idHash}. */
    public static byte[] headKey(String evidenceId) {
        return concat(HEAD_PREFIX, idHash(evidenceId));
    }

    /** Returns {@code evidence/record/v1/ || idHash || uint64be(version)}. */
    public static byte[] recordKey(String evidenceId, long businessVersion) {
        return recordKey(idHash(evidenceId), businessVersion);
    }

    /** Returns the record key from a previously validated scope id hash. */
    public static byte[] recordKey(byte[] evidenceIdHash, long businessVersion) {
        byte[] hash = EvidenceValidation.exactBytes(evidenceIdHash, 32);
        EvidenceValidation.positiveVersion(businessVersion);
        return ByteBuffer.allocate(RECORD_PREFIX.length + hash.length + Long.BYTES)
                .put(RECORD_PREFIX)
                .put(hash)
                .putLong(businessVersion)
                .array();
    }

    /** Returns the canonical ADR-010 idempotency scope for one connector action. */
    public static String effectScope(String evidenceId, long businessVersion,
                                     EvidenceEffectOperation operation) {
        EvidenceValidation.positiveVersion(businessVersion);
        Objects.requireNonNull(operation, "operation");
        return new EvidenceScope(idHash(evidenceId), businessVersion, operation).encode();
    }

    /** Returns {@code idHash || uint64be(version)} for the Kafka event key. */
    public static byte[] kafkaKey(String evidenceId, long businessVersion) {
        EvidenceValidation.positiveVersion(businessVersion);
        return ByteBuffer.allocate(32 + Long.BYTES)
                .put(idHash(evidenceId))
                .putLong(businessVersion)
                .array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length)
                .put(first).put(second).array();
    }
}
