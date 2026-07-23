package com.bloxbean.cardano.yano.appchain.testkit.effects;

/** External mutation guarantee a connector can honestly provide after an unknown acknowledgement. */
public enum IdempotencyModel {
    /** A probe/reconciliation step guarantees at most one logical external mutation. */
    PROBE_SINGLE_MUTATION,
    /** An idempotent set operation (such as pin) has an existing-match state but no conflict state. */
    IDEMPOTENT_SET,
    /** Repetition may duplicate externally, but every call carries the same stable dedupe token. */
    STABLE_DEDUPE_TOKEN
}
