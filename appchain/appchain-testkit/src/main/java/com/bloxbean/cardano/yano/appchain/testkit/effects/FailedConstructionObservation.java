package com.bloxbean.cardano.yano.appchain.testkit.effects;

import java.util.Map;
import java.util.Objects;

/**
 * Resource accounting captured after deliberately failing executor
 * construction. Log capture must have remained active through cleanup of all
 * partially created resources.
 *
 * @param failure the normalized construction failure
 * @param resourcesCreated number of owned resources acquired before failure
 * @param resourcesClosed number of those resources closed during cleanup
 * @param diagnostics bounded non-secret diagnostic snapshots
 * @param capturedLogs logs captured through construction cleanup
 */
public record FailedConstructionObservation(Throwable failure,
                                            int resourcesCreated,
                                            int resourcesClosed,
                                            Map<String, Object> diagnostics,
                                            CapturedLogObservation capturedLogs) {
    /** Validates resource counts and snapshots the diagnostics map. */
    public FailedConstructionObservation {
        Objects.requireNonNull(failure, "failure");
        if (resourcesCreated < 0 || resourcesClosed < 0) {
            throw new IllegalArgumentException("resource counts must be non-negative");
        }
        diagnostics = diagnostics != null ? Map.copyOf(diagnostics) : Map.of();
        Objects.requireNonNull(capturedLogs, "capturedLogs");
    }
}
