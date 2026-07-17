package com.bloxbean.cardano.yano.appchain.examples.evidence.client;

import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable typed evidence whose exact head and record leaves were verified
 * against one committed MPF state root. {@code headKey}/{@code recordKey} are
 * domain-local keys; {@code physicalHeadKey}/{@code physicalRecordKey} are the
 * exact MPF proof keys. A composite result also carries the verified profile
 * digest, while a standalone result carries {@code null}.
 */
public record VerifiedEvidence(String chainId,
                               String stateMachineId,
                               byte[] compositeProfileDigest,
                               long committedHeight,
                               byte[] stateRoot,
                               EvidenceHeadV1 head,
                               EvidenceRecordV1 record,
                               EvidenceStatus status,
                               byte[] headKey,
                               byte[] physicalHeadKey,
                               byte[] headValue,
                               byte[] recordKey,
                               byte[] physicalRecordKey,
                               byte[] recordValue) {
    /** Validates and snapshots all proof-bound bytes. */
    public VerifiedEvidence {
        if (chainId == null || chainId.isBlank() || stateMachineId == null
                || stateMachineId.isBlank() || committedHeight < 0) {
            throw new IllegalArgumentException("invalid verified evidence metadata");
        }
        compositeProfileDigest = compositeProfileDigest == null
                ? null : exactSnapshot(compositeProfileDigest, 32);
        stateRoot = exactSnapshot(stateRoot, 32);
        head = Objects.requireNonNull(head, "head");
        record = Objects.requireNonNull(record, "record");
        status = Objects.requireNonNull(status, "status");
        headKey = nonEmptySnapshot(headKey);
        physicalHeadKey = nonEmptySnapshot(physicalHeadKey);
        headValue = nonEmptySnapshot(headValue);
        recordKey = nonEmptySnapshot(recordKey);
        physicalRecordKey = nonEmptySnapshot(physicalRecordKey);
        recordValue = nonEmptySnapshot(recordValue);
    }

    @Override
    public byte[] compositeProfileDigest() {
        return compositeProfileDigest == null ? null : compositeProfileDigest.clone();
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
    public byte[] physicalHeadKey() {
        return physicalHeadKey.clone();
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
    public byte[] physicalRecordKey() {
        return physicalRecordKey.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof VerifiedEvidence value
                && committedHeight == value.committedHeight
                && chainId.equals(value.chainId)
                && stateMachineId.equals(value.stateMachineId)
                && Arrays.equals(compositeProfileDigest, value.compositeProfileDigest)
                && Arrays.equals(stateRoot, value.stateRoot)
                && head.equals(value.head)
                && record.equals(value.record)
                && status == value.status
                && Arrays.equals(headKey, value.headKey)
                && Arrays.equals(physicalHeadKey, value.physicalHeadKey)
                && Arrays.equals(headValue, value.headValue)
                && Arrays.equals(recordKey, value.recordKey)
                && Arrays.equals(physicalRecordKey, value.physicalRecordKey)
                && Arrays.equals(recordValue, value.recordValue);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(chainId, stateMachineId, committedHeight, head, record, status);
        result = 31 * result + Arrays.hashCode(compositeProfileDigest);
        result = 31 * result + Arrays.hashCode(stateRoot);
        result = 31 * result + Arrays.hashCode(headKey);
        result = 31 * result + Arrays.hashCode(physicalHeadKey);
        result = 31 * result + Arrays.hashCode(headValue);
        result = 31 * result + Arrays.hashCode(recordKey);
        result = 31 * result + Arrays.hashCode(physicalRecordKey);
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
