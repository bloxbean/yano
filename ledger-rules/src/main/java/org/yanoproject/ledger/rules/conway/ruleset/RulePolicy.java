package org.yanoproject.ledger.rules.conway.ruleset;

/**
 * A versioned rule that is a function rather than a check or a step (ADR-056 Phase 5c): a value a state step or the
 * effects deriver computes differently at different protocol versions, such as a new DRep's expiry. Versioned like a
 * unit: a delta supersedes it with another implementation ({@link RuleSetDelta.Builder#supersede(PolicyKey,
 * RulePolicy)}); implementations are stateless named classes.
 */
public interface RulePolicy {

    /** @return where Haskell defines it */
    String haskellRef();

    /** @return the implementation's parameters, for the manifest; empty when it has none */
    default String variant() {
        return "";
    }
}
