package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedSnapshotProofBundleCodecTest {
    private static final byte[] STORAGE_DOMAIN =
            "yano-authenticated-snapshot-storage-v1\0".getBytes(StandardCharsets.US_ASCII);

    @Test
    void roundTripsTheCompleteTrustChainCanonically() {
        AuthenticatedSnapshotProofBundleV1 bundle = bundle();
        byte[] encoded = bundle.canonicalBytes();

        AuthenticatedSnapshotProofBundleV1 decoded = AuthenticatedSnapshotProofBundleCodec.decode(encoded);

        assertThat(decoded.canonicalBytes()).containsExactly(encoded);
        assertThat(decoded.bundleCommitment()).containsExactly(bundle.bundleCommitment());
        assertThat(decoded.statementCommitment()).containsExactly(bundle.statementCommitment());
        assertThat(decoded.anchor().transactionHash()).isEqualTo(bundle.anchor().transactionHash());
        assertThat(decoded.descriptorProof().proof().nativeProof())
                .containsExactly(bundle.descriptorProof().proof().nativeProof());
        assertThat(decoded.snapshotProof().nativeProof()).containsExactly(bundle.snapshotProof().nativeProof());
        assertThat(encoded).hasSize(1508);
        assertThat(java.util.HexFormat.of().formatHex(sha256(encoded)))
                .isEqualTo("bde92162a21cbd745880a6849e78553a630cc64643b12ea4c4f034563cbc82dd");
    }

    @Test
    void rejectsAnyTransportMutationAndAnchorOrProofSubstitutionChangesTheCommitment() {
        AuthenticatedSnapshotProofBundleV1 original = bundle();
        byte[] encoded = original.canonicalBytes();
        encoded[encoded.length / 2] ^= 1;
        assertThatThrownBy(() -> AuthenticatedSnapshotProofBundleCodec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class);

        AppAnchorCommitment changedAnchor = new AppAnchorCommitment(
                original.anchor().chainId(), original.anchor().mode(), original.anchor().anchoredHeight(),
                original.anchor().stateRoot(), original.anchor().blockHash(), "different-tx", original.anchor().l1Slot());
        var substituted = new AuthenticatedSnapshotProofBundleV1(1, original.descriptorBytes(),
                original.descriptorProof(), original.snapshotProof(), changedAnchor);
        assertThat(substituted.bundleCommitment()).isNotEqualTo(original.bundleCommitment());

        StateProof proof = original.snapshotProof();
        StateProof changedProof = new StateProof(proof.snapshot(), proof.canonicalKey(), proof.value(),
                proof.presence(), proof.proofEncodingId(), new byte[]{9, 8, 7});
        var proofSubstituted = new AuthenticatedSnapshotProofBundleV1(1, original.descriptorBytes(),
                original.descriptorProof(), changedProof, original.anchor());
        assertThat(proofSubstituted.bundleCommitment()).isNotEqualTo(original.bundleCommitment());
    }

    private static AuthenticatedSnapshotProofBundleV1 bundle() {
        var profile = StateCommitmentProfiles.MPF;
        byte[] chainGeneration = repeated(1);
        StateCommitmentIdentity primaryIdentity = StateCommitmentIdentity.explicit(profile, chainGeneration);
        byte[] primaryRoot = repeated(2);
        byte[] blockHash = repeated(3);
        byte[] snapshotRoot = repeated(4);
        byte[] series = "epoch-stake".getBytes(StandardCharsets.US_ASCII);
        byte[] secondaryGenesis = Blake2bUtil.blake2bHash256(ByteBuffer.allocate(
                        STORAGE_DOMAIN.length + 64 + 8 + 2 + series.length)
                .put(STORAGE_DOMAIN).put(chainGeneration).put(profile.formatFingerprint())
                .putLong(170).putShort((short) series.length).put(series).array());
        SnapshotDescriptorV1 descriptor = new SnapshotDescriptorV1(
                chainGeneration, primaryIdentity.digest(), "epoch-stake", 170, "stake-170",
                profile.id(), profile.formatFingerprint(), profile.proofEncodingId(), snapshotRoot,
                repeated(5), "blake2b256", "epoch-stake-source-v1", "epoch-stake-v1", 1,
                16, 17, 16, 17, repeated(0),
                new SnapshotSourceBoundary.L1Epoch(170, 171, 170, 1234, repeated(6)),
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET, true);
        byte[] descriptorBytes = SnapshotCanonicalCodec.encodeDescriptor(descriptor);
        byte[] descriptorKey = ("snapshots/v1/epoch-stake/" + String.format(Locale.ROOT, "%020d", 170))
                .getBytes(StandardCharsets.US_ASCII);
        StateProof primaryProof = new StateProof(
                new StateSnapshot(primaryIdentity, 17, primaryRoot), descriptorKey, descriptorBytes,
                StateProof.Presence.PRESENT, profile.proofEncodingId(), new byte[]{1, 2, 3});
        StateProofEnvelope envelope = new StateProofEnvelope(1, "history-chain", blockHash, primaryProof,
                new FinalityCert(FinalityCert.SCHEME_ED25519,
                        List.of(new FinalityCert.Signature(repeated(7), repeated(8, 64)))));
        StateProof secondaryProof = new StateProof(
                new StateSnapshot(StateCommitmentIdentity.explicit(profile, secondaryGenesis), 17, snapshotRoot),
                new byte[]{10, 11}, new byte[]{12, 13}, StateProof.Presence.PRESENT,
                profile.proofEncodingId(), new byte[]{4, 5, 6});
        AppAnchorCommitment anchor = new AppAnchorCommitment(
                "history-chain", "SCRIPT", 17, primaryRoot, blockHash, "anchor-tx", 4321);
        return new AuthenticatedSnapshotProofBundleV1(
                1, descriptorBytes, envelope, secondaryProof, anchor);
    }

    private static byte[] repeated(int value) {
        return repeated(value, 32);
    }

    private static byte[] repeated(int value, int length) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
