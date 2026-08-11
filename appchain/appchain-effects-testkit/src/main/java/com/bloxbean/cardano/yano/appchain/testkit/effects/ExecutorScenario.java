package com.bloxbean.cardano.yano.appchain.testkit.effects;

/** Standard provider-independent executor behaviors exercised by the conformance suite. */
public enum ExecutorScenario {
    /** A valid instruction completes successfully. */
    SUCCESS,
    /** The first call is transient and a bounded retry succeeds. */
    TRANSIENT_THEN_SUCCESS,
    /** An unknown acknowledgement is reconciled after a simulated restart. */
    UNKNOWN_ACK_THEN_RECONCILE,
    /** Equivalent provider state already exists and confirms without mutation. */
    EXISTING_MATCH,
    /** Conflicting provider state already exists and fails definitively. */
    EXISTING_CONFLICT,
    /** A provider call remains blocked while bounded shutdown is exercised. */
    BLOCKED_CALL
}
