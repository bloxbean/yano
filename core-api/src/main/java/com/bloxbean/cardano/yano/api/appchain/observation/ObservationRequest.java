package com.bloxbean.cardano.yano.api.appchain.observation;

/** Immutable input supplied to a node-local acquisition provider. */
public record ObservationRequest(
        ObservationDefinition definition,
        ObservationSubscription subscription,
        ObservationRound round
) {
    public ObservationRequest {
        if (definition == null || subscription == null || round == null) {
            throw new NullPointerException("observation request fields must not be null");
        }
    }
}
