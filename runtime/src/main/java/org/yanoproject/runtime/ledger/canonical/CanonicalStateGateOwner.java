package org.yanoproject.runtime.ledger.canonical;

/**
 * Implemented by chain-state stores that own the {@link CanonicalStateGate} protecting their
 * canonical application (ADR-056 §3). The gate belongs to the store because snapshots must be
 * released before the store closes or replaces its database.
 */
public interface CanonicalStateGateOwner {

    /** @return the gate guarding canonical writes to this store */
    CanonicalStateGate canonicalStateGate();
}
