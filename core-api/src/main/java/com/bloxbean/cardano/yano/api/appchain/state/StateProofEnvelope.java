package com.bloxbean.cardano.yano.api.appchain.state;

import com.bloxbean.cardano.yano.api.appchain.FinalityCert;

import java.util.Objects;

/** Canonical profile-tagged proof response bound to one finalized app block. */
public record StateProofEnvelope(
        int proofSchemaVersion,
        String chainId,
        byte[] blockHash,
        StateProof proof,
        FinalityCert finalityCertificate
) {
    public static final int PROOF_SCHEMA_VERSION = 1;

    public StateProofEnvelope {
        if (proofSchemaVersion != PROOF_SCHEMA_VERSION) {
            throw new IllegalArgumentException("state proof envelope schemaVersion must be 1");
        }
        chainId = Objects.requireNonNull(chainId, "chainId");
        if (chainId.isBlank() || !chainId.equals(chainId.trim())) {
            throw new IllegalArgumentException(
                    "state proof envelope chainId must be nonblank without surrounding whitespace");
        }
        blockHash = Objects.requireNonNull(blockHash, "blockHash").clone();
        if (blockHash.length != 32) {
            throw new IllegalArgumentException("state proof envelope blockHash must contain 32 bytes");
        }
        proof = Objects.requireNonNull(proof, "proof");
        if (proof.snapshot().height() <= 0) {
            throw new IllegalArgumentException("state proof envelope requires a finalized height");
        }
        finalityCertificate = Objects.requireNonNull(finalityCertificate, "finalityCertificate");
    }

    @Override public byte[] blockHash() { return blockHash.clone(); }
}
