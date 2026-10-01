package org.yanoproject.ledger.rules;

/**
 * The Haskell rule (STS) that reports a {@link LedgerFailure}, plus {@link #ENGINE} for failures
 * that are not ledger rules: unavailable state, unsupported eras and Yano admission policy.
 */
public enum LedgerRuleName {
    MEMPOOL,
    LEDGER,
    CERTS,
    /**
     * Conway {@code CERT}, the dispatch from {@code CERTS} to {@code DELEG}, {@code POOL} and {@code GOVCERT}
     * (Conway/Rules/Cert.hs). It has no predicate of its own, so no {@link LedgerFailure} names it; the Java engine
     * uses it to label that rule's frame.
     */
    CERT,
    DELEG,
    POOL,
    GOVCERT,
    GOV,
    UTXOW,
    UTXO,
    UTXOS,
    ENGINE
}
