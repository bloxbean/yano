package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.List;

/** Definition-pinned deterministic policy over canonical report order. */
@FunctionalInterface
public interface ObservationReconciliationPolicy {
    boolean verify(ObservationDefinition definition, ObservationRound round,
                   List<ObservationReport> reports, byte[] output, byte[] policyTrace);
}
