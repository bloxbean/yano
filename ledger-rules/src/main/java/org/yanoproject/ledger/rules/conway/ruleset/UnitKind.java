package org.yanoproject.ledger.rules.conway.ruleset;

/** What a {@link RuleUnit} is (ADR-056 Phase 5c). */
public enum UnitKind {
    /** A Haskell predicate ({@code runTest}, {@code ?!}, {@code failOnJust}, …): it reports failures and changes nothing. */
    CHECK,
    /**
     * A state-transition step (the pre-certificate step, a certificate's application, {@code proposalsAddAction}, the
     * script preparation): it advances the transition's state and always runs, whatever the validation mode.
     */
    STEP
}
