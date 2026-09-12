package org.yanoproject.api.appchain.l1view;

import java.util.Optional;

/** Independent retained-block or proof-backed resolver for a pointer-only request. */
public interface HistoricalObservationResolver {
    String resolverId();

    Optional<L1Observation> resolve(HistoricalObservationPointer pointer);
}
