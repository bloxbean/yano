package com.bloxbean.cardano.yano.appchain.examples.evidence.client;

import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable typed evidence whose exact head and record leaves were verified
 * against one committed MPF state root.
 */
public record VerifiedEvidence(String chainId,
                               long committedHeight,
                               byte[] stateRoot,
                               EvidenceHeadV1 head,
                               EvidenceRecordV1 record,
                               EvidenceStatus status,
                               byte[] headKey,
                               byte[] headValue,
                               byte[] recordKey,
                               byte[] recordValue) {
    /** Validates and snapshots all proof-bound bytes. */
    public VerifiedEvidence {
        if (chainId == null || chainId.isBlank() || committedHeight < 0) {
            throw new IllegalArgumentException("invalid verified evidence metadata");
        }
        stateRoot = exactSnapshot(stateRoot, 32);
        head = Objects.requireNonNull(head, "head");
        record = Objects.requireNonNull(record, "record");
        status = Objects.requireNonNull(status, "status");
        headKey = nonEmptySnapshot(headKey);
        headValue = nonEmptySnapshot(headValue);
        recordKey = nonEmptySnapshot(recordKey);
        recordValue = nonEmptySnapshot(recordValue);
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
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

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof VerifiedEvidence value
                && committedHeight == value.committedHeight
                && chainId.equals(value.chainId)
                && Arrays.equals(stateRoot, value.stateRoot)
                && head.equals(value.head)
                && record.equals(value.record)
                && status == value.status
                && Arrays.equals(headKey, value.headKey)
                && Arrays.equals(headValue, value.headValue)
                && Arrays.equals(recordKey, value.recordKey)
                && Arrays.equals(recordValue, value.recordValue);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(chainId, committedHeight, head, record, status);
        result = 31 * result + Arrays.hashCode(stateRoot);
        result = 31 * result + Arrays.hashCode(headKey);
        result = 31 * result + Arrays.hashCode(headValue);
        result = 31 * result + Arrays.hashCode(recordKey);
        return 31 * result + Arrays.hashCode(recordValue);
    }

    private static byte[] exactSnapshot(byte[] value, int size) {
        if (value == null || value.length != size) {
            throw new IllegalArgumentException("invalid verified evidence bytes");
        }
        return value.clone();
    }

    private static byte[] nonEmptySnapshot(byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("invalid verified evidence bytes");
        }
        return value.clone();
    }
}
