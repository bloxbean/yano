package org.yanoproject.ledger.rules;

/**
 * The Haskell rule (STS) that reports a {@link LedgerFailure}, plus {@link #ENGINE} for failures
 * that are not ledger rules: unavailable state, unsupported eras and Yano admission policy.
 */
public enum LedgerRuleName {
    MEMPOOL,
    LEDGER,
    CERTS,
    DELEG,
    POOL,
    GOVCERT,
    GOV,
    UTXOW,
    UTXO,
    UTXOS,
    ENGINE
}
