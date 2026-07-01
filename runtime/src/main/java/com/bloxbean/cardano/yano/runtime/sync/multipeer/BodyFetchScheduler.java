package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yano.consensus.selection.CandidateHeader;

/**
 * Schedules body fetch for selected-chain gaps.
 */
public interface BodyFetchScheduler {
    void scheduleSelectedBody(CandidateHeader selectedHeader);
}
