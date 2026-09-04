package com.bloxbean.cardano.yano.api.appchain.observation;

/** Definition-pinned pure, bounded verifier for one report's source evidence. */
@FunctionalInterface
public interface ObservationEvidenceVerifier {
    boolean verify(ObservationDefinition definition, ObservationRound round,
                   ObservationReport report);
}
