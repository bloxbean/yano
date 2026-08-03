package com.bloxbean.cardano.yano.runtime.appchain;

/** Package-private deterministic failure seam for ADR-025 crash-boundary tests. */
@FunctionalInterface
interface StateCommitFaultInjector {
    StateCommitFaultInjector NONE = point -> { };

    void at(FaultPoint point);

    enum FaultPoint {
        AFTER_CANDIDATE_OPEN,
        BEFORE_PREPARE,
        AFTER_PREPARE,
        BEFORE_BACKEND_STAGE,
        AFTER_BACKEND_STAGE,
        BEFORE_LEDGER_STAGE,
        BEFORE_DURABLE_WRITE,
        AFTER_DURABLE_WRITE,
        AFTER_COMMIT_VERIFICATION,
        BEFORE_RESTART_VERIFICATION,
        AFTER_RESTART_VERIFICATION
    }
}
