package com.bloxbean.cardano.yano.appchain.composite.client;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileEpochChainVerifier;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileEpochV1;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Offline trust-boundary verifier for an ADR-015 governed composite profile.
 *
 * <p>The caller supplies portable signed block evidence for the state root,
 * MPF proofs for the current pointer, every retained epoch, and the active
 * marker, plus an explicit authorization policy. The verifier orders the trust
 * checks as finality, one-root MPF inclusion, canonical epoch linkage, then
 * caller policy. It never treats a node's status or an internally consistent
 * epoch chain as authorization.</p>
 */
public final class GovernedCompositeVerifier {
    private GovernedCompositeVerifier() {
    }

    /**
     * Creates an explicit external-authorization policy from proposal hashes
     * independently pinned by the caller. Every non-genesis epoch must have
     * exactly one matching entry keyed by epoch number; extra and missing pins
     * are rejected. This is the simplest safe policy when an auditor does not
     * retain the complete finalized member-approval history.
     */
    public static AuthorizationPolicy requirePinnedProposalHashes(
            Map<Long, byte[]> proposalHashesByEpoch
    ) {
        Map<Long, byte[]> supplied = Objects.requireNonNull(
                proposalHashesByEpoch, "proposalHashesByEpoch");
        Map<Long, byte[]> pins = new LinkedHashMap<>();
        supplied.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Long epoch = Objects.requireNonNull(entry.getKey(), "proposal epoch");
            if (epoch < 1 || pins.putIfAbsent(epoch,
                    exact(entry.getValue(), 32, "proposal hash")) != null) {
                throw new IllegalArgumentException("proposal pins require unique epochs >= 1");
            }
        });
        Map<Long, byte[]> immutablePins = Map.copyOf(pins);
        return context -> {
            List<CompositeProfileEpochV1> epochs = context.epochs();
            if (immutablePins.size() != epochs.size() - 1) {
                return false;
            }
            for (int index = 1; index < epochs.size(); index++) {
                CompositeProfileEpochV1 epoch = epochs.get(index);
                byte[] expected = immutablePins.get(epoch.epochNumber());
                if (expected == null
                        || !MessageDigest.isEqual(expected, epoch.proposalHash())) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Verifies one finalized governed-profile snapshot.
     *
     * @param finalizedEvidence signed block evidence ending at the proof root
     * @param trust independently pinned chain membership and finality threshold
     * @param currentEpochProof inclusion proof for the current epoch pointer
     * @param epochProofs inclusion proofs for epochs {@code 0..current}, in order
     * @param activeMarkerProof inclusion proof for {@code ~composite/profile/v1}
     * @param pinnedGenesisDigest independently pinned genesis profile digest
     * @param maximumEpochs caller policy bound, no greater than the chain bound
     * @param authorizationPolicy verifies membership/approval history for every
     *                            non-genesis epoch proposal hash
     * @return an immutable verified profile snapshot
     */
    public static VerifiedProfile verify(
            EvidenceBundle finalizedEvidence,
            EvidenceVerifier.TrustContext trust,
            AppChainClient.Proof currentEpochProof,
            List<AppChainClient.Proof> epochProofs,
            AppChainClient.Proof activeMarkerProof,
            byte[] pinnedGenesisDigest,
            int maximumEpochs,
            AuthorizationPolicy authorizationPolicy
    ) {
        Objects.requireNonNull(finalizedEvidence, "finalizedEvidence");
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(currentEpochProof, "currentEpochProof");
        Objects.requireNonNull(activeMarkerProof, "activeMarkerProof");
        Objects.requireNonNull(authorizationPolicy, "authorizationPolicy");
        List<AppChainClient.Proof> suppliedEpochs = List.copyOf(
                Objects.requireNonNull(epochProofs, "epochProofs"));
        byte[] genesisDigest = exact(pinnedGenesisDigest, 32, "pinnedGenesisDigest");
        if (maximumEpochs < 1 || maximumEpochs > 65_536) {
            throw invalid("maximumEpochs is outside the v1 bound");
        }

        EvidenceVerifier.Result finality = EvidenceVerifier.verify(finalizedEvidence, trust);
        if (!finality.valid() || finalizedEvidence.blocks().isEmpty()) {
            throw invalid("finalized block evidence did not verify");
        }
        AppBlock finalizedBlock = finalizedEvidence.blocks().getLast();
        byte[] root = exact(finalizedBlock.stateRoot(), 32, "finalized state root");
        String rootHex = HexFormat.of().formatHex(root);
        long height = finalizedBlock.height();

        byte[] pointer = verifyInclusion(currentEpochProof,
                CompositeCommitmentV1.currentProfileEpochKey(), trust.chainId(),
                rootHex, height, Long.BYTES);
        long currentEpoch = ByteBuffer.wrap(pointer).getLong();
        if (currentEpoch < 0 || currentEpoch >= maximumEpochs
                || suppliedEpochs.size() != currentEpoch + 1) {
            throw invalid("current epoch pointer disagrees with supplied history");
        }

        List<byte[]> encodedEpochs = new ArrayList<>(suppliedEpochs.size());
        List<CompositeProfileEpochV1> decodedEpochs = new ArrayList<>(suppliedEpochs.size());
        for (int index = 0; index < suppliedEpochs.size(); index++) {
            byte[] encoded = verifyInclusion(suppliedEpochs.get(index),
                    CompositeCommitmentV1.profileEpochKey(index), trust.chainId(),
                    rootHex, height, CompositeProfileEpochV1.MAX_ENCODED_BYTES);
            CompositeProfileEpochV1 decoded;
            try {
                decoded = CompositeProfileEpochV1.decode(encoded);
            } catch (RuntimeException malformed) {
                throw invalid("profile epoch proof contains a malformed value");
            }
            encodedEpochs.add(encoded);
            decodedEpochs.add(decoded);
        }
        byte[] marker = verifyInclusion(activeMarkerProof,
                CompositeCommitmentV1.profileMarkerKey(), trust.chainId(),
                rootHex, height, CompositeCommitmentV1.MAX_PROFILE_BYTES);

        CompositeProfileEpochChainVerifier.VerifiedStructure structure;
        try {
            structure = CompositeProfileEpochChainVerifier.verifyStructure(
                    genesisDigest, encodedEpochs, marker, maximumEpochs);
        } catch (RuntimeException malformed) {
            throw invalid("profile epoch chain did not verify");
        }
        AuthorizationContext context = new AuthorizationContext(
                trust.chainId(), height, root, decodedEpochs, finalizedEvidence);
        if (!authorizationPolicy.verify(context)) {
            throw invalid("profile epoch authorization policy rejected the history");
        }
        return new VerifiedProfile(height, root, structure, finality.certSignatures());
    }

    private static byte[] verifyInclusion(
            AppChainClient.Proof proof,
            byte[] expectedKey,
            String expectedChain,
            String expectedRootHex,
            long expectedHeight,
            int maximumValueBytes
    ) {
        if (proof == null || !expectedChain.equals(proof.chainId())
                || proof.committedHeight() == null
                || proof.committedHeight() != expectedHeight
                || proof.valueHex() == null) {
            throw invalid("profile proof metadata disagrees with the finalized snapshot");
        }
        byte[] key = decodeHex(proof.keyHex(), 256, "profile proof key");
        byte[] value = decodeHex(proof.valueHex(), maximumValueBytes, "profile proof value");
        if (!MessageDigest.isEqual(key, expectedKey)
                || !expectedRootHex.equals(proof.stateRootHex())
                || !ProofVerifier.verify(proof, expectedRootHex)) {
            throw invalid("profile MPF inclusion proof did not verify");
        }
        return value;
    }

    private static byte[] decodeHex(String value, int maximumBytes, String field) {
        if (value == null || value.length() == 0 || (value.length() & 1) != 0
                || value.length() > maximumBytes * 2
                || !value.matches("[0-9a-f]+")) {
            throw invalid(field + " is not bounded canonical hexadecimal");
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (RuntimeException malformed) {
            throw invalid(field + " is not bounded canonical hexadecimal");
        }
    }

    private static byte[] exact(byte[] value, int length, String field) {
        if (value == null || value.length != length) {
            throw invalid(field + " must contain exactly " + length + " bytes");
        }
        return value.clone();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid governed composite proof: " + reason);
    }

    /**
     * Application trust policy for the already-finalized epoch history.
     * Implementations either reconstruct each proposal's bound membership
     * epoch and approvals/readiness from independently verified finalized block
     * history, or compare every proposal hash with an independent authorization
     * source such as {@link #requirePinnedProposalHashes(Map)}. Returning
     * {@code true} without one of those checks deliberately weakens the result
     * to structural verification and must not be used at a trust boundary.
     */
    @FunctionalInterface
    public interface AuthorizationPolicy {
        boolean verify(AuthorizationContext context);
    }

    /** Exact finalized snapshot passed to the authorization policy. */
    public record AuthorizationContext(
            String chainId,
            long finalizedHeight,
            byte[] stateRoot,
            List<CompositeProfileEpochV1> epochs,
            EvidenceBundle finalizedEvidence
    ) {
        public AuthorizationContext {
            if (chainId == null || chainId.isBlank() || finalizedHeight < 1) {
                throw new IllegalArgumentException("invalid authorization context identity");
            }
            stateRoot = exact(stateRoot, 32, "authorization state root");
            epochs = List.copyOf(Objects.requireNonNull(epochs, "epochs"));
            finalizedEvidence = Objects.requireNonNull(finalizedEvidence, "finalizedEvidence");
        }

        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }

    /** Verified finality/root/profile result. */
    public record VerifiedProfile(
            long finalizedHeight,
            byte[] stateRoot,
            CompositeProfileEpochChainVerifier.VerifiedStructure structure,
            int validFinalitySignatures
    ) {
        public VerifiedProfile {
            if (finalizedHeight < 1 || validFinalitySignatures < 1) {
                throw new IllegalArgumentException("invalid verified profile result");
            }
            stateRoot = exact(stateRoot, 32, "verified state root");
            structure = Objects.requireNonNull(structure, "structure");
        }

        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }
}
