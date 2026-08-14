package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Immutable, primary-state descriptor of one sealed logical snapshot. */
public record SnapshotDescriptorV1(
        byte[] chainGenerationId,
        byte[] applicationProfileDigest,
        String seriesId,
        long sequence,
        String snapshotId,
        String snapshotProfile,
        byte[] snapshotFormatFingerprint,
        String snapshotProofWireVersion,
        byte[] snapshotRoot,
        byte[] sourceDatasetRoot,
        String sourceCommitmentAlgorithm,
        String sourceCommitmentWireVersion,
        String schemaId,
        long entryCount,
        long baseAppChainHeight,
        long completedAppChainHeight,
        long coveredFromHeight,
        long coveredThroughHeight,
        byte[] previousSnapshotCommitment,
        SnapshotSourceBoundary sourceBoundary,
        AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage recoveryCoverage,
        boolean complete
) {
    private static final byte[] COMMITMENT_DOMAIN =
            "yano-authenticated-snapshot-descriptor-v1".getBytes(StandardCharsets.US_ASCII);

    public SnapshotDescriptorV1 {
        chainGenerationId = require32(chainGenerationId, "chainGenerationId");
        applicationProfileDigest = require32(applicationProfileDigest, "applicationProfileDigest");
        requireText(seriesId, "seriesId");
        requireText(snapshotId, "snapshotId");
        requireText(snapshotProfile, "snapshotProfile");
        snapshotFormatFingerprint = require32(snapshotFormatFingerprint, "snapshotFormatFingerprint");
        requireText(snapshotProofWireVersion, "snapshotProofWireVersion");
        snapshotRoot = require32(snapshotRoot, "snapshotRoot");
        sourceDatasetRoot = require32(sourceDatasetRoot, "sourceDatasetRoot");
        requireText(sourceCommitmentAlgorithm, "sourceCommitmentAlgorithm");
        requireText(sourceCommitmentWireVersion, "sourceCommitmentWireVersion");
        requireText(schemaId, "schemaId");
        previousSnapshotCommitment = require32(previousSnapshotCommitment, "previousSnapshotCommitment");
        sourceBoundary = Objects.requireNonNull(sourceBoundary, "sourceBoundary");
        recoveryCoverage = Objects.requireNonNull(recoveryCoverage, "recoveryCoverage");
        if (sequence < 0 || entryCount < 0 || baseAppChainHeight < 0
                || completedAppChainHeight < 0 || coveredFromHeight < 0
                || coveredThroughHeight < coveredFromHeight) {
            throw new IllegalArgumentException("snapshot descriptor counters must be nonnegative and ordered");
        }
        if (!complete) throw new IllegalArgumentException("v1 descriptor must be complete");
    }

    @Override public byte[] chainGenerationId() { return chainGenerationId.clone(); }
    @Override public byte[] applicationProfileDigest() { return applicationProfileDigest.clone(); }
    @Override public byte[] snapshotFormatFingerprint() { return snapshotFormatFingerprint.clone(); }
    @Override public byte[] snapshotRoot() { return snapshotRoot.clone(); }
    @Override public byte[] sourceDatasetRoot() { return sourceDatasetRoot.clone(); }
    @Override public byte[] previousSnapshotCommitment() { return previousSnapshotCommitment.clone(); }

    public byte[] commitment() {
        byte[] canonical = SnapshotCanonicalCodec.encodeDescriptor(this);
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(COMMITMENT_DOMAIN.length + canonical.length)
                .put(COMMITMENT_DOMAIN).put(canonical).array());
    }

    private static byte[] require32(byte[] value, String name) {
        byte[] copy = Objects.requireNonNull(value, name).clone();
        if (copy.length != 32) throw new IllegalArgumentException(name + " must be 32 bytes");
        return copy;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof SnapshotDescriptorV1 that)) return false;
        return Arrays.equals(SnapshotCanonicalCodec.encodeDescriptor(this),
                SnapshotCanonicalCodec.encodeDescriptor(that));
    }

    @Override public int hashCode() { return Arrays.hashCode(SnapshotCanonicalCodec.encodeDescriptor(this)); }
}
