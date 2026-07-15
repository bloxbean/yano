package com.bloxbean.cardano.yano.appchain.testkit.effects;

import java.util.List;
import java.util.Map;

/** Test adapter exposing bounded facts about fake/external provider interactions. */
public interface ExternalProbe {
    /**
     * Returns the cumulative number of provider calls.
     *
     * @return the non-negative call count
     */
    int calls();

    /**
     * Returns cumulative logical external mutations, excluding probes.
     *
     * @return the non-negative mutation count
     */
    int logicalMutations();

    /**
     * Returns cumulative closes of owned provider clients.
     *
     * @return the non-negative close count
     */
    int closeCalls();

    /**
     * Returns exactly one observed ADR-010 id hash for every counted provider call.
     *
     * @return bounded idempotency-key observations
     */
    List<byte[]> observedIdempotencyKeys();

    /**
     * Returns bounded non-secret provider diagnostics for redaction checks.
     *
     * @return the diagnostic snapshot
     */
    default Map<String, Object> diagnostics() {
        return Map.of();
    }
}
