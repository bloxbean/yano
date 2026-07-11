package com.bloxbean.cardano.yano.consensus.selection;

/**
 * Result of evaluating candidate headers.
 */
public record ChainSelectionDecision(Action action, CandidateHeader selected, String reason) {
    public enum Action {
        ADOPT,
        OBSERVE,
        KEEP_CURRENT
    }

    public static ChainSelectionDecision adopt(CandidateHeader selected, String reason) {
        return new ChainSelectionDecision(Action.ADOPT, selected, reason);
    }

    public static ChainSelectionDecision observe(CandidateHeader selected, String reason) {
        return new ChainSelectionDecision(Action.OBSERVE, selected, reason);
    }

    public static ChainSelectionDecision keepCurrent(String reason) {
        return new ChainSelectionDecision(Action.KEEP_CURRENT, null, reason);
    }
}
