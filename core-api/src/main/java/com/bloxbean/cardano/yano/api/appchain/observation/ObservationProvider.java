package com.bloxbean.cardano.yano.api.appchain.observation;

/**
 * Operator-selected node-local acquisition adapter. Calls occur only outside
 * deterministic block execution. A failure is local and produces no report.
 * The host serializes calls to one provider instance, while different
 * definition providers may run concurrently.
 */
public interface ObservationProvider extends AutoCloseable {
    ObservationCandidate acquire(ObservationRequest request) throws Exception;

    @Override
    default void close() {
    }
}
