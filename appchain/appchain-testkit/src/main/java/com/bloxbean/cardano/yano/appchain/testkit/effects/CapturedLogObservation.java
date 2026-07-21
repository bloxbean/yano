package com.bloxbean.cardano.yano.appchain.testkit.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded snapshots captured by a fixture-installed test log appender.
 * Entries should contain only the rendered event fields needed for redaction
 * checks, for example logger, level, message, and throwable.
 *
 * @param captureActive whether the fixture retained its appender through the
 *                      operation and any executor cleanup just performed
 * @param entries bounded rendered event snapshots
 */
public record CapturedLogObservation(boolean captureActive, List<?> entries) {
    private static final int MAX_CAPTURED_ENTRIES = 10_000;

    /** Validate and defensively snapshot the observation. */
    public CapturedLogObservation {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_CAPTURED_ENTRIES) {
            throw new IllegalArgumentException("too many captured log entries");
        }
        List<Object> snapshot = new ArrayList<>(Math.min(entries.size(), MAX_CAPTURED_ENTRIES));
        for (Object entry : entries) {
            if (snapshot.size() >= MAX_CAPTURED_ENTRIES) {
                throw new IllegalArgumentException("too many captured log entries");
            }
            snapshot.add(Objects.requireNonNull(entry, "captured log entry"));
        }
        entries = List.copyOf(snapshot);
    }

    /**
     * Create an active observation.
     *
     * @param entries bounded rendered event snapshots
     * @return an active immutable observation
     */
    public static CapturedLogObservation active(List<?> entries) {
        return new CapturedLogObservation(true, entries);
    }
}
