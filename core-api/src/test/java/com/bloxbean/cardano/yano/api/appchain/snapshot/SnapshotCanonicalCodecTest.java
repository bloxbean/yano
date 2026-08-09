package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotCanonicalCodecTest {
    @Test
    void descriptorHeadAndReceiptRoundTripCanonically() {
        byte[] hash = bytes(7);
        SnapshotDescriptorV1 descriptor = new SnapshotDescriptorV1(bytes(1), bytes(2),
                "epoch-stake", 170, "stake-170", StateCommitmentProfiles.MPF.id(),
                StateCommitmentProfiles.MPF.formatFingerprint(),
                StateCommitmentProfiles.MPF.proofEncodingId(), bytes(3), bytes(4),
                "blake2b256", "epoch-stake-source-v1", "epoch-stake-v1", 2,
                20, 22, 20, 21, bytes(5),
                new SnapshotSourceBoundary.L1Epoch(170, 171, 170, 99, hash),
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET, true);
        byte[] encoded = SnapshotCanonicalCodec.encodeDescriptor(descriptor);

        assertThat(SnapshotCanonicalCodec.decodeDescriptor(encoded)).isEqualTo(descriptor);
        assertThat(descriptor.commitment()).hasSize(32);

        SnapshotHeadV1 head = new SnapshotHeadV1(170, descriptor.commitment());
        assertThat(SnapshotCanonicalCodec.decodeHead(SnapshotCanonicalCodec.encodeHead(head)))
                .isEqualTo(head);

        SnapshotBuildReceiptV1 receipt = new SnapshotBuildReceiptV1(170, "stake-170",
                descriptor.sourceBoundary(), 20, 20, 21,
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET,
                bytes(8), bytes(4), "blake2b256", "epoch-stake-source-v1",
                2, 1, 2, 1, new byte[]{1}, bytes(7), bytes(9));
        SnapshotBuildReceiptV1 decoded = SnapshotCanonicalCodec.decodeReceipt(
                SnapshotCanonicalCodec.encodeReceipt(receipt));
        assertThat(decoded.sequence()).isEqualTo(receipt.sequence());
        assertThat(decoded.partialRoot()).containsExactly(receipt.partialRoot());
    }

    @Test
    void rejectsTrailingFieldsAndFullState() {
        assertThatThrownBy(() -> new AuthenticatedSnapshotSeriesDescriptorV1(
                "series", "schema", AuthenticatedSnapshotSeriesDescriptorV1.Trigger.APPLICATION_MESSAGE,
                StateCommitmentProfiles.MPF.id(), StateCommitmentProfiles.MPF.formatFingerprint(),
                StateCommitmentProfiles.MPF.proofEncodingId(),
                AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN,
                AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                "blake2b256", "source-v1", 10, 1000, 256, 8192, 100,
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.FULL_STATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SnapshotCanonicalCodec.decodeHead(new byte[]{(byte) 0x84, 1, 0, 0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }
}
