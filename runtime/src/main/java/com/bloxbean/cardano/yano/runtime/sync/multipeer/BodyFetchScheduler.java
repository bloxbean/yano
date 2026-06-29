package com.bloxbean.cardano.yano.runtime.sync.multipeer;

/**
 * Schedules body fetch for selected-chain gaps.
 */
public interface BodyFetchScheduler {
    void scheduleSelectedBody(CandidateHeader selectedHeader);
}
