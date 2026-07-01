package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Conservative first chain-selection policy.
 */
public final class TrustedOrQuorumCandidateWithinRollbackWindow implements ChainSelectionStrategy {
    @Override
    public ChainSelectionDecision evaluate(ChainSelectionContext context) {
        var eligible = context.candidates().stream()
                .filter(candidate -> candidate.blockNumber() > context.currentBlockNumber())
                .filter(candidate -> withinRollbackWindow(candidate, context))
                .toList();

        CandidateHeader bestAllowed = eligible.stream()
                .filter(candidate -> candidate.trusted() || reachesQuorum(candidate, eligible, context.quorum()))
                .max(Comparator
                        .comparingLong(CandidateHeader::blockNumber)
                        .thenComparing(CandidateHeader::blockHash))
                .orElse(null);

        if (bestAllowed != null) {
            String reason = bestAllowed.trusted()
                    ? "trusted candidate ahead of current chain"
                    : "candidate reached configured quorum";
            return ChainSelectionDecision.adopt(bestAllowed, reason);
        }

        CandidateHeader bestObserved = eligible.stream()
                .max(Comparator
                        .comparingLong(CandidateHeader::blockNumber)
                        .thenComparing(CandidateHeader::blockHash))
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
}
