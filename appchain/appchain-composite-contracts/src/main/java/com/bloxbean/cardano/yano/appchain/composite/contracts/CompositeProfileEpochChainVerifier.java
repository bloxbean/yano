package com.bloxbean.cardano.yano.appchain.composite.contracts;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Dependency-free structural checks for ADR-015 profile markers and epoch
 * chains.
 * <p>
 * This class does not fetch or verify MPF inclusion proofs, finality
 * certificates, membership epochs, or the finalized member commands that
 * authorized an activation. Callers must establish those facts at one trusted
 * finalized state root before passing the proven values here. A structurally
 * valid caller-supplied byte sequence is not, by itself, proof of member
 * authorization.
 */
public final class CompositeProfileEpochChainVerifier {
    private CompositeProfileEpochChainVerifier() {
    }

    /** Checks that a proven active marker has one explicitly pinned digest. */
    public static VerifiedStructure verifyPinnedMarker(
            byte[] activeProfileBytes,
            List<byte[]> allowedProfileDigests
    ) {
        byte[] active = snapshotProfile(activeProfileBytes);
        byte[] digest = CompositeCommitmentV1.profileDigest(active);
        boolean allowed = Objects.requireNonNull(
                allowedProfileDigests, "allowedProfileDigests").stream()
                .map(CompositeProfileEpochChainVerifier::snapshotDigest)
                .anyMatch(candidate -> MessageDigest.isEqual(candidate, digest));
        if (!allowed) {
            throw new IllegalArgumentException(
                    "active composite profile digest is not pinned");
        }
        return new VerifiedStructure(0, 1, digest, active);
    }

    /**
     * Checks the internal linkage of already-proven epoch records and their
     * equality with an already-proven active marker.
     */
    public static VerifiedStructure verifyStructure(
            byte[] pinnedGenesisDigest,
            List<byte[]> encodedEpochs,
            byte[] activeProfileBytes,
            int maximumEpochs
    ) {
        byte[] genesis = snapshotDigest(pinnedGenesisDigest);
        byte[] marker = snapshotProfile(activeProfileBytes);
        List<byte[]> supplied = List.copyOf(Objects.requireNonNull(
                encodedEpochs, "encodedEpochs"));
        if (maximumEpochs < 1 || maximumEpochs > 65_536
                || supplied.isEmpty() || supplied.size() > maximumEpochs) {
            throw new IllegalArgumentException(
                    "profile epoch history is outside the structural bound");
        }
        CompositeProfileEpochV1 previous = null;
        for (int index = 0; index < supplied.size(); index++) {
            CompositeProfileEpochV1 epoch = CompositeProfileEpochV1.decode(
                    supplied.get(index));
            if (epoch.epochNumber() != index) {
                throw new IllegalArgumentException(
                        "profile epoch numbers are not contiguous");
            }
            if (index == 0) {
                if (epoch.fromHeight() != 1
                        || !MessageDigest.isEqual(epoch.profileDigest(), genesis)) {
                    throw new IllegalArgumentException(
                            "profile epoch zero does not match trust root");
                }
            } else if (previous == null || epoch.fromHeight() <= previous.fromHeight()
                    || !MessageDigest.isEqual(
                    epoch.previousProfileDigest(), previous.profileDigest())
                    || allZero(epoch.proposalHash())) {
                throw new IllegalArgumentException(
                        "profile epoch chain is not contiguous");
            }
            previous = epoch;
        }
        if (previous == null
                || !Arrays.equals(previous.canonicalProfileBytes(), marker)) {
            throw new IllegalArgumentException(
                    "active composite marker is not the epoch-chain tail");
        }
        return new VerifiedStructure(previous.epochNumber(), previous.fromHeight(),
                previous.profileDigest(), marker);
    }

    private static boolean allZero(byte[] value) {
        int aggregate = 0;
        for (byte item : value) aggregate |= item;
        return aggregate == 0;
    }

    private static byte[] snapshotProfile(byte[] value) {
        byte[] result = Objects.requireNonNull(value, "profile").clone();
        if (result.length < 1
                || result.length > CompositeCommitmentV1.MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException(
                    "invalid active composite profile bytes");
        }
        return result;
    }

    private static byte[] snapshotDigest(byte[] value) {
        byte[] result = Objects.requireNonNull(value, "digest").clone();
        if (result.length != 32) {
            throw new IllegalArgumentException("profile digest must be 32 bytes");
        }
        return result;
    }

    public record VerifiedStructure(
            long epochNumber,
            long fromHeight,
            byte[] profileDigest,
            byte[] canonicalProfileBytes
    ) {
        public VerifiedStructure {
            if (epochNumber < 0 || fromHeight < 1) {
                throw new IllegalArgumentException("invalid verified structure");
            }
            profileDigest = snapshotDigest(profileDigest);
            canonicalProfileBytes = snapshotProfile(canonicalProfileBytes);
        }

        @Override public byte[] profileDigest() { return profileDigest.clone(); }
        @Override public byte[] canonicalProfileBytes() {
            return canonicalProfileBytes.clone();
        }
    }
}
