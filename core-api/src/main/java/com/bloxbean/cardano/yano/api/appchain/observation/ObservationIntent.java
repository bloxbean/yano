package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Objects;

/** Pure application request to create bounded observation work after finality. */
public record ObservationIntent(
        String definitionId,
        String route,
        byte[] parameters,
        ObservationAnchorType anchorType,
        long firstDueAnchor,
        long reportDeadlineAnchor,
        long subscriptionExpiryAnchor,
        long cadence,
        int completionPolicy
) {
    public ObservationIntent {
        definitionId = ObservationCbor.boundedText(definitionId, 128, "definition id");
        route = ObservationCbor.boundedText(route, 128, "observation route");
        parameters = ObservationCbor.bounded(parameters, 64 * 1024, "observation parameters");
        anchorType = Objects.requireNonNull(anchorType, "anchorType");
        if (firstDueAnchor < 1 || reportDeadlineAnchor < firstDueAnchor
                || subscriptionExpiryAnchor < reportDeadlineAnchor
                || cadence < 0 || completionPolicy < 0) {
            throw new IllegalArgumentException("invalid observation intent bounds");
        }
    }

    @Override public byte[] parameters() { return parameters.clone(); }

    public static ObservationIntent oneShot(String definitionId, String route,
                                            byte[] parameters, ObservationAnchorType anchorType,
                                            long firstDueAnchor, long reportDeadlineAnchor,
                                            long subscriptionExpiryAnchor) {
        return new ObservationIntent(definitionId, route, parameters, anchorType,
                firstDueAnchor, reportDeadlineAnchor, subscriptionExpiryAnchor, 0, 0);
    }
}
