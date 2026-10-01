package org.yanoproject.ledger.rules.conway;

/**
 * Haskell's validation labels (ADR-056 §6; {@code Cardano.Ledger.Rules.ValidationMode}): a {@code static}
 * check ({@code runTestOnSignal}, {@code ?!#}, {@code when2Phase}) depends only on the transaction and is
 * skipped when a validated transaction is re-applied ({@code reapplyTx}); a {@code dynamic} check
 * ({@code runTest}, {@code ?!}, {@code ?!:}) depends on the ledger state or the environment and always runs.
 */
public enum CheckLabel {
    STATIC,
    DYNAMIC
}
