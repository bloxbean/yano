package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Objects;

/**
 * Candidate chain facts used by deterministic chain comparison.
 */
public record ChainCandidate(
        CandidateHeader tip,
        boolean densityKnown,
        long blocksInDensityWindow
) {
    public ChainCandidate {
        Objects.requireNonNull(tip, "tip");
        if (blocksInDensityWindow < 0) {
            throw new IllegalArgumentException("blocksInDensityWindow must be non-negative");
        }
        if (!densityKnown) {
            blocksInDensityWindow = 0;
        }
    }

    public static ChainCandidate withoutDensity(CandidateHeader tip) {
        return new ChainCandidate(tip, false, 0);
    }

    public static ChainCandidate withDensity(CandidateHeader tip, long blocksInDensityWindow) {
        return new ChainCandidate(tip, true, blocksInDensityWindow);
    }
}
