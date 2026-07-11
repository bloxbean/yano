package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Conservative first chain-selection policy.
 */
public final class TrustedOrQuorumCandidateWithinRollbackWindow implements ChainSelectionStrategy {
    private final CardanoOrientedChainComparator comparator = new CardanoOrientedChainComparator();

    @Override
    public ChainSelectionDecision evaluate(ChainSelectionContext context) {
        var eligible = context.candidates().stream()
                .filter(candidate -> candidate.blockNumber() > context.currentBlockNumber())
                .filter(candidate -> withinRollbackWindow(candidate, context))
                .toList();

        CandidateHeader bestAllowed = bestCandidate(eligible.stream()
                .filter(candidate -> canDriveAdoption(candidate, eligible, context))
                .toList())
                .orElse(null);

        if (bestAllowed != null) {
            String reason = adoptionReason(bestAllowed, eligible, context);
            return ChainSelectionDecision.adopt(bestAllowed, reason);
        }

        CandidateHeader bestObserved = bestCandidate(eligible)
                .orElse(null);
        if (bestObserved == null) {
            return ChainSelectionDecision.keepCurrent("no eligible candidate ahead of current chain");
        }

        return ChainSelectionDecision.observe(bestObserved,
                "single untrusted candidate cannot drive canonical rollback/adoption");
    }

    private boolean reachesQuorum(CandidateHeader candidate, java.util.List<CandidateHeader> eligible, int quorum) {
        Map<String, Long> hashCounts = eligible.stream()
                .filter(other -> other.blockNumber() == candidate.blockNumber())
                .collect(Collectors.groupingBy(CandidateHeader::blockHash, Collectors.counting()));
        return hashCounts.getOrDefault(candidate.blockHash(), 0L) >= quorum;
    }

    private boolean withinRollbackWindow(CandidateHeader candidate, ChainSelectionContext context) {
        if (candidate.slot() >= context.currentSlot()) {
            return true;
        }
        return context.currentSlot() - candidate.slot() <= context.rollbackWindowSlots();
    }

    private boolean canDriveAdoption(CandidateHeader candidate,
                                     java.util.List<CandidateHeader> eligible,
                                     ChainSelectionContext context) {
        if (candidate.trusted()) {
            return true;
        }
        return switch (context.trustPolicy()) {
            case "trusted-only" -> false;
            case "quorum" -> reachesQuorum(candidate, eligible, context.quorum());
            case "validated" -> candidate.validationEvidence().producesEvidence();
            default -> false;
        };
    }

    private String adoptionReason(CandidateHeader candidate,
                                  java.util.List<CandidateHeader> eligible,
                                  ChainSelectionContext context) {
        if (candidate.trusted()) {
            return "trusted candidate ahead of current chain";
        }
        return switch (context.trustPolicy()) {
            case "validated" -> "candidate accepted by configured header validation profile";
            case "quorum" -> reachesQuorum(candidate, eligible, context.quorum())
                    ? "candidate reached configured quorum"
                    : "candidate accepted by policy";
            default -> "candidate accepted by policy";
        };
    }

    private Optional<CandidateHeader> bestCandidate(java.util.List<CandidateHeader> candidates) {
        return candidates.stream()
                .reduce((best, next) -> {
                    ChainComparison comparison = comparator.compare(
                            ChainCandidate.withoutDensity(best),
                            ChainCandidate.withoutDensity(next));
                    return comparison.winner() == ChainComparison.Winner.B ? next : best;
                });
    }
}
