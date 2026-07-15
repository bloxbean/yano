package com.bloxbean.cardano.yano.appchain.examples.evidence.query;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.Arrays;
import java.util.List;

/**
 * Query response carrying exact state keys and values so a proof client binds
 * decoded domain fields to the leaves it verifies.
 */
public record EvidenceGetResponseV1(boolean found,
                                    byte[] headKey,
                                    byte[] headValue,
                                    byte[] recordKey,
                                    byte[] recordValue) {
    /** Validates absence shape or the complete head/record/key binding. */
    public EvidenceGetResponseV1 {
        headKey = snapshot(headKey);
        headValue = snapshot(headValue);
        recordKey = snapshot(recordKey);
        recordValue = snapshot(recordValue);
        if (!found) {
            if (headKey.length != 0 || headValue.length != 0
                    || recordKey.length != 0 || recordValue.length != 0) {
                throw EvidenceValidation.invalid();
            }
        } else {
            validateFound(headKey, headValue, recordKey, recordValue);
        }
    }

    /** Returns the one canonical not-found response. */
    public static EvidenceGetResponseV1 notFound() {
        return new EvidenceGetResponseV1(false, new byte[0], new byte[0],
                new byte[0], new byte[0]);
    }

    /** Creates a bound found response from typed values. */
    public static EvidenceGetResponseV1 found(EvidenceHeadV1 head,
                                              EvidenceRecordV1 record) {
        if (head == null || record == null) {
            throw EvidenceValidation.invalid();
        }
        return new EvidenceGetResponseV1(true,
                EvidenceKeys.headKey(head.evidenceId()), head.encode(),
                EvidenceKeys.recordKey(record.evidenceId(), record.businessVersion()),
                record.encode());
    }

    @Override
    public byte[] headKey() {
        return headKey.clone();
    }

    @Override
    public byte[] headValue() {
        return headValue.clone();
    }

    @Override
    public byte[] recordKey() {
        return recordKey.clone();
    }

    @Override
    public byte[] recordValue() {
        return recordValue.clone();
    }

    /** Returns the decoded head, only for a found response. */
    public EvidenceHeadV1 head() {
        if (!found) {
            throw EvidenceValidation.invalid();
        }
        return EvidenceHeadV1.decode(headValue);
    }

    /** Returns the decoded immutable record, only for a found response. */
    public EvidenceRecordV1 record() {
        if (!found) {
            throw EvidenceValidation.invalid();
        }
        return EvidenceRecordV1.decode(recordValue);
    }

    /** Encodes {@code [1, found, headKey, headValue, recordKey, recordValue]}. */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(EvidenceContract.SCHEMA_VERSION));
        root.add(CanonicalCbor.boolValue(found));
        root.add(new ByteString(headKey));
        root.add(new ByteString(headValue));
        root.add(new ByteString(recordKey));
        root.add(new ByteString(recordValue));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded,
                EvidenceContract.MAX_QUERY_RESPONSE_BYTES);
        return encoded;
    }

    /** Decodes and validates one strict canonical response. */
    public static EvidenceGetResponseV1 decode(byte[] encoded) {
        Array root = CanonicalCbor.decodeArray(encoded,
                EvidenceContract.MAX_QUERY_RESPONSE_BYTES, 6);
        List<DataItem> fields = CanonicalCbor.items(root);
        return new EvidenceGetResponseV1(
                CanonicalCbor.bool(fields.get(1)),
                CanonicalCbor.bytes(fields.get(2)),
                CanonicalCbor.bytes(fields.get(3)),
                CanonicalCbor.bytes(fields.get(4)),
                CanonicalCbor.bytes(fields.get(5)));
    }

    private static void validateFound(byte[] headKey, byte[] headValue,
                                      byte[] recordKey, byte[] recordValue) {
        if (headKey.length == 0 || headValue.length == 0
                || recordKey.length == 0 || recordValue.length == 0) {
            throw EvidenceValidation.invalid();
        }
        EvidenceHeadV1 head = EvidenceHeadV1.decode(headValue);
        EvidenceRecordV1 record = EvidenceRecordV1.decode(recordValue);
        if (!head.evidenceId().equals(record.evidenceId())
                || !Arrays.equals(head.ownerPublicKey(), record.ownerPublicKey())
                || record.businessVersion() > head.latestVersion()
                || !Arrays.equals(headKey, EvidenceKeys.headKey(head.evidenceId()))
                || !Arrays.equals(recordKey, EvidenceKeys.recordKey(
                record.evidenceId(), record.businessVersion()))) {
            throw EvidenceValidation.invalid();
        }
    }

    private static byte[] snapshot(byte[] value) {
        if (value == null) {
            throw EvidenceValidation.invalid();
        }
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceGetResponseV1 response
                && found == response.found
                && Arrays.equals(headKey, response.headKey)
                && Arrays.equals(headValue, response.headValue)
                && Arrays.equals(recordKey, response.recordKey)
                && Arrays.equals(recordValue, response.recordValue);
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(found);
        result = 31 * result + Arrays.hashCode(headKey);
        result = 31 * result + Arrays.hashCode(headValue);
        result = 31 * result + Arrays.hashCode(recordKey);
        return 31 * result + Arrays.hashCode(recordValue);
    }
}
