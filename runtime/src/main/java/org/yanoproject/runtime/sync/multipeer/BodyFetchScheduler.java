package org.yanoproject.runtime.sync.multipeer;

import org.yanoproject.consensus.selection.CandidateHeader;

/**
 * Schedules body fetch for selected-chain gaps.
 */
public interface BodyFetchScheduler {
    void scheduleSelectedBody(CandidateHeader selectedHeader);
}
