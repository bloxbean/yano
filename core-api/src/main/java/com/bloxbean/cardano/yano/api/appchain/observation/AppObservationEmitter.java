package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Objects;

/** Deterministic block-scoped API for creating and cancelling observation work. */
public interface AppObservationEmitter {
    ObservationSubscriptionId watch(ObservationIntent intent);

    void cancel(ObservationSubscriptionId subscriptionId);

    long activeCount();

    static AppObservationEmitter rejecting(String reason) {
        String message = Objects.requireNonNull(reason, "reason");
        return new AppObservationEmitter() {
            @Override
            public ObservationSubscriptionId watch(ObservationIntent intent) {
                throw new IllegalStateException(message);
            }

            @Override
            public void cancel(ObservationSubscriptionId subscriptionId) {
                throw new IllegalStateException(message);
            }

            @Override
            public long activeCount() {
                return 0;
            }
        };
    }
}
