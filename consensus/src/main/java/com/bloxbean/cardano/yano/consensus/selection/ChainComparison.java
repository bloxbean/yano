package com.bloxbean.cardano.yano.consensus.selection;

/**
 * Deterministic comparison outcome between two candidate chains.
 */
public record ChainComparison(Winner winner, Reason reason) {
    public enum Winner {
        A,
        B,
        EQUAL
    }

    public enum Reason {
        LONGER,
        DENSER,
        VRF_TIEBREAK,
        FALLBACK,
        EQUAL
    }

    public static ChainComparison a(Reason reason) {
        return new ChainComparison(Winner.A, reason);
    }

    public static ChainComparison b(Reason reason) {
        return new ChainComparison(Winner.B, reason);
    }

    public static ChainComparison equal() {
        return new ChainComparison(Winner.EQUAL, Reason.EQUAL);
    }
}
