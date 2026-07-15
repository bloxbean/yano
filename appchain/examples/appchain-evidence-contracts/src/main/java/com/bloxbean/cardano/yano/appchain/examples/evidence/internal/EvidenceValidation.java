package com.bloxbean.cardano.yano.appchain.examples.evidence.internal;

import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** Deterministic validation shared by the evidence domain codecs. */
public final class EvidenceValidation {
    private static final Pattern EVIDENCE_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private EvidenceValidation() {
    }

    public static String evidenceId(String value) {
        if (value == null || !EVIDENCE_ID.matcher(value).matches()
                || value.getBytes(StandardCharsets.US_ASCII).length
                > EvidenceContract.MAX_EVIDENCE_ID_BYTES) {
            throw invalid();
        }
        return value;
    }

    public static long positiveVersion(long value) {
        if (value <= 0) {
            throw invalid();
        }
        return value;
    }

    public static byte[] exactBytes(byte[] value, int length) {
        if (value == null || value.length != length) {
            throw invalid();
        }
        return value.clone();
    }

    public static byte[] boundedBytes(byte[] value, int maximum) {
        if (value == null || value.length > maximum) {
            throw invalid();
        }
        return value.clone();
    }

    public static ObjectPutCommandV1 objectCommand(byte[] encoded) {
        try {
            return ObjectPutCommandV1.decode(encoded);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static IpfsPinCommandV1 ipfsCommand(byte[] encoded) {
        try {
            return IpfsPinCommandV1.decode(encoded);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    /** Reuses the frozen connector alias policy without copying its regex. */
    public static void kafkaRoute(String target, String topic) {
        try {
            new KafkaPublishCommandV1(target, topic, new byte[0],
                    EvidenceContract.EVENT_CONTENT_TYPE, new byte[0], List.of());
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_EVIDENCE_CONTRACT");
    }
}
