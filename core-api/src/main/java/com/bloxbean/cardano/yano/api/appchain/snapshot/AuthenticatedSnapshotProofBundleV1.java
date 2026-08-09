package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

/** Two-level proof: anchored primary descriptor proof plus secondary claim proof. */
public record AuthenticatedSnapshotProofBundleV1(
        int schemaVersion,
        byte[] descriptorBytes,
        StateProofEnvelope descriptorProof,
        StateProof snapshotProof,
        AppAnchorCommitment anchor
) {
    private static final byte[] STORAGE_DOMAIN =
            "yano-authenticated-snapshot-storage-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public AuthenticatedSnapshotProofBundleV1 {
        if (schemaVersion != 1) throw new IllegalArgumentException("snapshot proof bundle version must be 1");
        descriptorBytes = Objects.requireNonNull(descriptorBytes, "descriptorBytes").clone();
        descriptorProof = Objects.requireNonNull(descriptorProof, "descriptorProof");
        snapshotProof = Objects.requireNonNull(snapshotProof, "snapshotProof");
        anchor = Objects.requireNonNull(anchor, "anchor");
        if (!java.util.Arrays.equals(descriptorBytes, descriptorProof.proof().value())) {
            throw new IllegalArgumentException("descriptor proof value differs from descriptor bytes");
        }
        SnapshotDescriptorV1 descriptor = SnapshotCanonicalCodec.decodeDescriptor(descriptorBytes);
        byte[] expectedDescriptorKey = ("snapshots/v1/" + descriptor.seriesId() + "/"
                + String.format(java.util.Locale.ROOT, "%020d", descriptor.sequence()))
                .getBytes(StandardCharsets.US_ASCII);
        var primary = descriptorProof.proof();
        if (!descriptorProof.chainId().equals(anchor.chainId())
                || primary.presence() != StateProof.Presence.PRESENT
                || !Arrays.equals(primary.canonicalKey(), expectedDescriptorKey)
                || primary.snapshot().height() != anchor.anchoredHeight()
                || !Arrays.equals(primary.snapshot().stateRoot(), anchor.stateRoot())
                || !Arrays.equals(descriptorProof.blockHash(), anchor.blockHash())
                || !Arrays.equals(primary.snapshot().identity().genesisId(),
                descriptor.chainGenerationId())
                || !Arrays.equals(primary.snapshot().identity().digest(),
                descriptor.applicationProfileDigest())) {
            throw new IllegalArgumentException("primary descriptor proof is not bound to its L1 anchor");
        }
        byte[] series = descriptor.seriesId().getBytes(StandardCharsets.US_ASCII);
        byte[] expectedSecondaryGenesis = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(
                java.nio.ByteBuffer.allocate(
                        STORAGE_DOMAIN.length + 64 + 8 + 2 + series.length)
                .put(STORAGE_DOMAIN).put(descriptor.chainGenerationId())
                .put(descriptor.snapshotFormatFingerprint()).putLong(descriptor.sequence())
                .putShort((short) series.length).put(series).array());
        if (!snapshotProof.snapshot().identity().profile().id().equals(descriptor.snapshotProfile())
                || !Arrays.equals(snapshotProof.snapshot().identity().profile().formatFingerprint(),
                descriptor.snapshotFormatFingerprint())
                || !snapshotProof.proofEncodingId().equals(descriptor.snapshotProofWireVersion())
                || !Arrays.equals(snapshotProof.snapshot().identity().genesisId(),
                expectedSecondaryGenesis)
                || snapshotProof.snapshot().height() != descriptor.completedAppChainHeight()
                || !Arrays.equals(snapshotProof.snapshot().stateRoot(), descriptor.snapshotRoot())
                || !StateCommitmentProfiles.require(descriptor.snapshotProfile())
                .proofEncodingId().equals(descriptor.snapshotProofWireVersion())) {
            throw new IllegalArgumentException("secondary proof differs from the committed descriptor");
        }
    }
    @Override public byte[] descriptorBytes() { return descriptorBytes.clone(); }

    /** Commitment to the typed fact statement independently of proof encoding. */
    public byte[] statementCommitment() {
        return AuthenticatedSnapshotProofBundleCodec.statementCommitment(this);
    }

    /** Stable commitment over the complete nested proof trust chain. */
    public byte[] bundleCommitment() {
        return AuthenticatedSnapshotProofBundleCodec.bundleCommitment(this);
    }

    public byte[] canonicalBytes() { return AuthenticatedSnapshotProofBundleCodec.encode(this); }
}
