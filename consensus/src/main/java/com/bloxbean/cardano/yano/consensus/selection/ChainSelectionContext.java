package com.bloxbean.cardano.yano.consensus.selection;

import java.util.List;

/**
 * Inputs needed to decide whether a candidate can become canonical.
 */
public record ChainSelectionContext(
        long currentBlockNumber,
        long currentSlot,
        long rollbackWindowSlots,
        int quorum,
        String trustPolicy,
        List<CandidateHeader> candidates
) {
    public ChainSelectionContext(long currentBlockNumber,
                                 long currentSlot,
                                 long rollbackWindowSlots,
                                 int quorum,
                                 List<CandidateHeader> candidates) {
        this(currentBlockNumber, currentSlot, rollbackWindowSlots, quorum, "quorum", candidates);
    }

    public ChainSelectionContext {
        trustPolicy = trustPolicy == null || trustPolicy.isBlank()
                ? "quorum"
                : trustPolicy.trim().toLowerCase(java.util.Locale.ROOT);
        candidates = candidates != null ? List.copyOf(candidates) : List.of();
        if (rollbackWindowSlots <= 0) {
            throw new IllegalArgumentException("rollbackWindowSlots must be positive");
        }
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be positive");
        }
        if (!java.util.Set.of("trusted-only", "quorum", "validated").contains(trustPolicy)) {
            throw new IllegalArgumentException("unsupported trustPolicy: " + trustPolicy);
        }
    }
}
