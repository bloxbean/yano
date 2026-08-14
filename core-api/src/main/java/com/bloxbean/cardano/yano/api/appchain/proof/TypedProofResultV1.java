package com.bloxbean.cardano.yano.api.appchain.proof;

import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;

import java.util.Map;

/** Root-fixed typed proof response; fact/claim fields are presentation, never trust roots. */
public record TypedProofResultV1(
        int schemaVersion,
        ProofSubjectDescriptorV1 descriptor,
        Map<String, String> normalizedCoordinates,
        byte[] canonicalLogicalKey,
        byte[] physicalKey,
        StateProofEnvelope proof,
        ProofSubjectProvider.TypedFact fact,
        ProofSubjectProvider.ClaimRequest claim,
        ProofSubjectProvider.ClaimResult claimResult,
        ProofLabVocabulary.TrustLevel trust
) {
    public TypedProofResultV1 {
        if (schemaVersion != 1) throw new IllegalArgumentException("typed proof schemaVersion must be 1");
        normalizedCoordinates = Map.copyOf(normalizedCoordinates);
        canonicalLogicalKey = canonicalLogicalKey.clone();
        physicalKey = physicalKey.clone();
    }
    @Override public byte[] canonicalLogicalKey() { return canonicalLogicalKey.clone(); }
    @Override public byte[] physicalKey() { return physicalKey.clone(); }
}
