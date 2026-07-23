package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

/** Stable suffixes used in ADR-010 effect idempotency scopes. */
public enum EvidenceEffectOperation {
    /** Immutable object promotion. */
    OBJECT("object"),
    /** IPFS pin reconciliation. */
    IPFS("ipfs"),
    /** Kafka availability notification. */
    NOTIFY("notify");

    private final String scopeSuffix;

    EvidenceEffectOperation(String scopeSuffix) {
        this.scopeSuffix = scopeSuffix;
    }

    /** Returns the canonical lowercase scope suffix. */
    public String scopeSuffix() {
        return scopeSuffix;
    }
}
