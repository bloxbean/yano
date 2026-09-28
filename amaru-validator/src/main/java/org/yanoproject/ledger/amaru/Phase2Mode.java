package org.yanoproject.ledger.amaru;

/** Who runs the Plutus scripts (ADR-057 decision 3, {@code yano.validation.amaru.phase2}). */
public enum Phase2Mode {
    /**
     * Amaru judges phase one ({@code mode = phase_one}) and the node's
     * {@link org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator} (Scalus, step 1d) runs the scripts.
     * The default for {@code engine: amaru}.
     */
    SCALUS,
    /**
     * Amaru's full validation, including its own UPLC machine ({@code mode = full}); configured as
     * {@code phase2: amaru}. Used for oracle runs.
     */
    FULL
}
