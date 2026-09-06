package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical application-facing projection of a finalized observation certificate. */
public record ObservationResult(
        int version,
        byte[] resultId,
        byte[] subscriptionId,
        long roundNumber,
        byte[] definitionDigest,
        ObservationResultStatus status,
        byte[] value,
        byte[] valueEvidenceDigest,
        byte[] certificateDigest,
        int sourceCount,
        int reporterCount,
        byte[] freshnessSummary,
        long finalizedHeight
) {
    public static final int MAX_ENCODED_BYTES = 128 * 1024;
    private static final int FIELDS = 13;

    public ObservationResult {
        if (version != ObservationCbor.VERSION || roundNumber < 0 || sourceCount < 0
                || reporterCount < 0 || finalizedHeight < 1) {
            throw new IllegalArgumentException("invalid observation result fields");
        }
        resultId = ObservationCbor.fixed(resultId, 32, "result id");
        subscriptionId = ObservationCbor.fixed(subscriptionId, 32, "subscription id");
        definitionDigest = ObservationCbor.fixed(definitionDigest, 32, "definition digest");
        status = Objects.requireNonNull(status, "status");
        value = ObservationCbor.bounded(value, 64 * 1024, "observation result value");
        if (status != ObservationResultStatus.VALUE && value.length != 0) {
            throw new IllegalArgumentException("non-value observation result cannot carry value bytes");
        }
        valueEvidenceDigest = ObservationCbor.fixed(valueEvidenceDigest, 32, "value/evidence digest");
        certificateDigest = certificateDigest == null || certificateDigest.length == 0
                ? null : ObservationCbor.fixed(certificateDigest, 32, "certificate digest");
        freshnessSummary = ObservationCbor.bounded(freshnessSummary, 1024, "freshness summary");
        byte[] expected = ObservationHashes.resultId(subscriptionId, roundNumber,
                definitionDigest, status, valueEvidenceDigest);
        if (!Arrays.equals(resultId, expected)) {
            throw new IllegalArgumentException("observation result id mismatch");
        }
    }

    @Override public byte[] resultId() { return resultId.clone(); }
    @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
    @Override public byte[] definitionDigest() { return definitionDigest.clone(); }
    @Override public byte[] value() { return value.clone(); }
    @Override public byte[] valueEvidenceDigest() { return valueEvidenceDigest.clone(); }
    @Override public byte[] certificateDigest() { return certificateDigest == null ? null : certificateDigest.clone(); }
    @Override public byte[] freshnessSummary() { return freshnessSummary.clone(); }

    public byte[] encode() {
        Array result = ObservationCbor.array();
        ObservationCbor.uint(result, version);
        ObservationCbor.bytes(result, resultId);
        ObservationCbor.bytes(result, subscriptionId);
        ObservationCbor.uint(result, roundNumber);
        ObservationCbor.bytes(result, definitionDigest);
        ObservationCbor.uint(result, status.code());
        ObservationCbor.bytes(result, value);
        ObservationCbor.bytes(result, valueEvidenceDigest);
        ObservationCbor.bytes(result, certificateDigest == null ? new byte[0] : certificateDigest);
        ObservationCbor.uint(result, sourceCount);
        ObservationCbor.uint(result, reporterCount);
        ObservationCbor.bytes(result, freshnessSummary);
        ObservationCbor.uint(result, finalizedHeight);
        return ObservationCbor.encode(result);
    }

    public static ObservationResult decode(byte[] bytes) {
        try {
            List<DataItem> f = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 40, 20,
                    64 * 1024, FIELDS, "result");
            byte[] certificate = ObservationCbor.bytesValue(f.get(8));
            ObservationResult value = new ObservationResult(
                    ObservationCbor.intValue(f.get(0)), ObservationCbor.bytesValue(f.get(1)),
                    ObservationCbor.bytesValue(f.get(2)), ObservationCbor.longValue(f.get(3)),
                    ObservationCbor.bytesValue(f.get(4)),
                    ObservationResultStatus.fromCode(ObservationCbor.intValue(f.get(5))),
                    ObservationCbor.bytesValue(f.get(6)), ObservationCbor.bytesValue(f.get(7)),
                    certificate.length == 0 ? null : certificate,
                    ObservationCbor.intValue(f.get(9)), ObservationCbor.intValue(f.get(10)),
                    ObservationCbor.bytesValue(f.get(11)), ObservationCbor.longValue(f.get(12)));
            ObservationCbor.canonical(bytes, value.encode(), "result");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("result");
        }
    }
}
