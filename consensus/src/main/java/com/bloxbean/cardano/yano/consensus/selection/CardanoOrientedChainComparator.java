package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * Deterministic Cardano-oriented chain comparison primitive.
 */
public final class CardanoOrientedChainComparator {
    private static final int VRF_OUTPUT_SIZE = 64;

    public ChainComparison compare(ChainCandidate a, ChainCandidate b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");

        CandidateHeader tipA = a.tip();
        CandidateHeader tipB = b.tip();

        if (tipA.blockNumber() > tipB.blockNumber()) {
            return ChainComparison.a(ChainComparison.Reason.LONGER);
        }
        if (tipB.blockNumber() > tipA.blockNumber()) {
            return ChainComparison.b(ChainComparison.Reason.LONGER);
        }

        if (a.densityKnown() && b.densityKnown()) {
            if (a.blocksInDensityWindow() > b.blocksInDensityWindow()) {
                return ChainComparison.a(ChainComparison.Reason.DENSER);
            }
            if (b.blocksInDensityWindow() > a.blocksInDensityWindow()) {
                return ChainComparison.b(ChainComparison.Reason.DENSER);
            }
        }

        if (hasValidatedVrfOutput(tipA) && hasValidatedVrfOutput(tipB)) {
            int vrf = Arrays.compareUnsigned(tipA.vrfOutput(), tipB.vrfOutput());
            if (vrf < 0) {
                return ChainComparison.a(ChainComparison.Reason.VRF_TIEBREAK);
            }
            if (vrf > 0) {
                return ChainComparison.b(ChainComparison.Reason.VRF_TIEBREAK);
            }
        }

        int fallback = Comparator
                .comparingLong(CandidateHeader::slot)
                .thenComparing(CandidateHeader::blockHash)
                .thenComparing(CandidateHeader::peerId)
                .compare(tipA, tipB);
        if (fallback < 0) {
            return ChainComparison.a(ChainComparison.Reason.FALLBACK);
        }
        if (fallback > 0) {
            return ChainComparison.b(ChainComparison.Reason.FALLBACK);
        }
        return ChainComparison.equal();
    }

    public ChainCandidate better(ChainCandidate a, ChainCandidate b) {
        ChainComparison comparison = compare(a, b);
        return switch (comparison.winner()) {
            case A, EQUAL -> a;
            case B -> b;
        };
    }

    private static boolean hasValidatedVrfOutput(CandidateHeader header) {
        byte[] vrfOutput = header.vrfOutput();
        return vrfOutput != null
                && vrfOutput.length == VRF_OUTPUT_SIZE
                && header.validationEvidence().includesStage("vrf-proof");
    }
}
