package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Objects;

/**
 * Header advertised by one upstream peer before canonical adoption.
 */
public record CandidateHeader(
        String peerId,
        long slot,
        long blockNumber,
        String blockHash,
        String previousHash,
        boolean trusted,
        long receivedAtMillis,
        String era,
        HeaderValidationEvidence validationEvidence,
        byte[] vrfOutput,
        boolean bodyAvailable,
        boolean bodyValidated
) {
    public CandidateHeader(String peerId,
                           long slot,
                           long blockNumber,
                           String blockHash,
                           String previousHash,
                           boolean trusted,
                           long receivedAtMillis) {
        this(peerId,
                slot,
                blockNumber,
                blockHash,
                previousHash,
                trusted,
                receivedAtMillis,
                null,
                HeaderValidationEvidence.none(),
                null,
                false,
                false);
    }

    public CandidateHeader {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(blockHash, "blockHash");
        if (peerId.isBlank()) {
            throw new IllegalArgumentException("peerId must not be blank");
        }
        if (blockHash.isBlank()) {
            throw new IllegalArgumentException("blockHash must not be blank");
        }
        if (slot < 0 || blockNumber < 0) {
            throw new IllegalArgumentException("slot and blockNumber must be non-negative");
        }
        era = era == null || era.isBlank() ? null : era.trim();
        validationEvidence = validationEvidence != null ? validationEvidence : HeaderValidationEvidence.none();
        vrfOutput = vrfOutput != null ? vrfOutput.clone() : null;
        if (bodyValidated && !bodyAvailable) {
            throw new IllegalArgumentException("bodyValidated requires bodyAvailable");
        }
    }

    @Override
    public byte[] vrfOutput() {
        return vrfOutput != null ? vrfOutput.clone() : null;
    }
}
