package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Arrays;
import java.util.List;

/** Certificate-monotonic v1 policy requiring exactly r reports for one identical value. */
public final class ExactValueQuorumPolicy implements ObservationReconciliationPolicy {
    @Override
    public boolean verify(ObservationDefinition definition, ObservationRound round,
                          List<ObservationReport> reports, byte[] output, byte[] policyTrace) {
        if (reports.size() != round.reportThreshold() || policyTrace.length != 0) {
            return false;
        }
        return reports.stream().allMatch(report -> Arrays.equals(report.value(), output));
    }
}
