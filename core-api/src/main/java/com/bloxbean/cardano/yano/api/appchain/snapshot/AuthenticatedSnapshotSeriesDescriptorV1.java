package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfile;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

import java.util.Objects;
import java.util.Arrays;

/** Immutable declaration of one reusable authenticated-snapshot series. */
public record AuthenticatedSnapshotSeriesDescriptorV1(
        String seriesId,
        String schemaId,
        Trigger trigger,
        String snapshotProfile,
        byte[] formatFingerprint,
        String proofWireVersion,
        VerificationTarget verificationTarget,
        Visibility visibility,
        String sourceCommitmentAlgorithm,
        String sourceCommitmentWireVersion,
        int maxEntriesPerChunk,
        int maxChunkBytes,
        int maxKeyBytes,
        int maxValueBytes,
        long maxEntriesPerSnapshot,
        RecoveryCoverage recoveryCoverage
) {
    public AuthenticatedSnapshotSeriesDescriptorV1 {
        requireId(seriesId, "seriesId");
        requireId(schemaId, "schemaId");
        trigger = Objects.requireNonNull(trigger, "trigger");
        StateCommitmentProfile profile = StateCommitmentProfiles.require(snapshotProfile);
        formatFingerprint = Objects.requireNonNull(formatFingerprint, "formatFingerprint").clone();
        if (formatFingerprint.length != 32
                || !java.util.Arrays.equals(formatFingerprint, profile.formatFingerprint())) {
            throw new IllegalArgumentException("formatFingerprint does not match snapshotProfile");
        }
        requireId(proofWireVersion, "proofWireVersion");
        verificationTarget = Objects.requireNonNull(verificationTarget, "verificationTarget");
        visibility = Objects.requireNonNull(visibility, "visibility");
        requireId(sourceCommitmentAlgorithm, "sourceCommitmentAlgorithm");
        requireId(sourceCommitmentWireVersion, "sourceCommitmentWireVersion");
        if (maxEntriesPerChunk <= 0 || maxChunkBytes <= 0 || maxKeyBytes <= 0
                || maxKeyBytes > 256 || maxValueBytes <= 0 || maxValueBytes > 1024 * 1024
                || maxEntriesPerSnapshot <= 0) {
            throw new IllegalArgumentException("snapshot quotas must be positive");
        }
        recoveryCoverage = Objects.requireNonNull(recoveryCoverage, "recoveryCoverage");
        if (recoveryCoverage == RecoveryCoverage.FULL_STATE) {
            throw new IllegalArgumentException("FULL_STATE recovery snapshots are not released in v1");
        }
    }

    @Override public byte[] formatFingerprint() { return formatFingerprint.clone(); }

    public AuthenticatedSnapshotSeriesDescriptorV1 withSeriesId(String scopedSeriesId) {
        return new AuthenticatedSnapshotSeriesDescriptorV1(scopedSeriesId, schemaId, trigger,
                snapshotProfile, formatFingerprint, proofWireVersion, verificationTarget,
                visibility, sourceCommitmentAlgorithm, sourceCommitmentWireVersion,
                maxEntriesPerChunk, maxChunkBytes, maxKeyBytes, maxValueBytes,
                maxEntriesPerSnapshot, recoveryCoverage);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuthenticatedSnapshotSeriesDescriptorV1 that
                && seriesId.equals(that.seriesId) && schemaId.equals(that.schemaId)
                && trigger == that.trigger && snapshotProfile.equals(that.snapshotProfile)
                && Arrays.equals(formatFingerprint, that.formatFingerprint)
                && proofWireVersion.equals(that.proofWireVersion)
                && verificationTarget == that.verificationTarget && visibility == that.visibility
                && sourceCommitmentAlgorithm.equals(that.sourceCommitmentAlgorithm)
                && sourceCommitmentWireVersion.equals(that.sourceCommitmentWireVersion)
                && maxEntriesPerChunk == that.maxEntriesPerChunk
                && maxChunkBytes == that.maxChunkBytes
                && maxKeyBytes == that.maxKeyBytes
                && maxValueBytes == that.maxValueBytes
                && maxEntriesPerSnapshot == that.maxEntriesPerSnapshot
                && recoveryCoverage == that.recoveryCoverage;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(seriesId, schemaId, trigger, snapshotProfile, proofWireVersion,
                verificationTarget, visibility, sourceCommitmentAlgorithm,
                sourceCommitmentWireVersion, maxEntriesPerChunk, maxChunkBytes,
                maxKeyBytes, maxValueBytes, maxEntriesPerSnapshot, recoveryCoverage);
        return 31 * result + Arrays.hashCode(formatFingerprint);
    }

    private static void requireId(String value, String name) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }

    public enum Trigger { APPLICATION_MESSAGE, APP_HEIGHT_INTERVAL, L1_EPOCH_BOUNDARY }
    public enum VerificationTarget { ON_CHAIN, OFF_CHAIN }
    public enum Visibility { PUBLIC, PRIVATE }
    public enum RecoveryCoverage { DATASET, FULL_STATE }
}
