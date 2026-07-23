package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.nio.file.Path;
import java.util.Locale;

/** Bounded publication-load request against an already-running demo chain. */
record LoadRequest(int count,
                   int concurrency,
                   Mode mode,
                   int maxInFlight,
                   String idPrefix,
                   Path sampleFile) {
    static final int MAX_COUNT = 50_000;
    static final int MAX_CONCURRENCY = 16;
    static final int MAX_IN_FLIGHT = 5_000;
    private static final String ID_PREFIX = "[a-z][a-z0-9-]{0,55}";

    LoadRequest {
        if (count < 1 || count > MAX_COUNT
                || concurrency < 1 || concurrency > MAX_CONCURRENCY
                || concurrency > count
                || mode == null
                || maxInFlight < concurrency || maxInFlight > MAX_IN_FLIGHT
                || maxInFlight > count
                || idPrefix == null || !idPrefix.matches(ID_PREFIX)
                || sampleFile == null) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        sampleFile = sampleFile.toAbsolutePath().normalize();
        String lastEvidenceId = String.format(Locale.ROOT, "%s-%06d", idPrefix, count);
        if (!lastEvidenceId.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
    }

    LoadRequest(int count, int concurrency, String idPrefix, Path sampleFile) {
        this(count, concurrency, Mode.LIFECYCLE, concurrency, idPrefix, sampleFile);
    }

    String evidenceId(int index) {
        if (index < 1 || index > count) {
            throw new IllegalArgumentException("load item index is outside the request");
        }
        return String.format(Locale.ROOT, "%s-%06d", idPrefix, index);
    }

    enum Mode {
        LIFECYCLE,
        PIPELINE;

        static Mode parse(String value) {
            if (value == null || value.isBlank() || "lifecycle".equals(value)) {
                return LIFECYCLE;
            }
            if ("pipeline".equals(value)) {
                return PIPELINE;
            }
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
