package com.bloxbean.cardano.yano.api.appchain.proof;

import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;

import java.util.Map;
import java.util.Objects;

/** Portable root-fixed typed state claim. Embedded presentation is never authoritative. */
public record StateClaimProofPackageV1(
        String schema,
        ProofSubjectDescriptorV1 descriptor,
        Map<String, String> normalizedCoordinates,
        ProofSubjectProvider.ClaimRequest claim,
        byte[] authenticatedFactBytes,
        StateProofEnvelope primaryProof,
        StateProofEnvelope completenessProof,
        AppAnchorCommitment anchorReference,
        Map<String, Object> verification
) {
    public static final String SCHEMA = "appchain-state-claim-proof-v1";

    public StateClaimProofPackageV1 {
        if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("unsupported state claim schema");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        normalizedCoordinates = Map.copyOf(Objects.requireNonNull(
                normalizedCoordinates, "normalizedCoordinates"));
        primaryProof = Objects.requireNonNull(primaryProof, "primaryProof");
        authenticatedFactBytes = authenticatedFactBytes == null
                ? null : authenticatedFactBytes.clone();
        if (authenticatedFactBytes != null
                && authenticatedFactBytes.length > descriptor.limits().maxValueBytes()) {
            throw new IllegalArgumentException("state claim fact exceeds descriptor limit");
        }
        verification = verification == null ? Map.of() : Map.copyOf(verification);
    }

    @Override public byte[] authenticatedFactBytes() {
        return authenticatedFactBytes == null ? null : authenticatedFactBytes.clone();
    }
}
