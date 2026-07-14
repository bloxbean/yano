package com.bloxbean.cardano.yano.catalog;

/**
 * Inclusive supported Yano plugin API-major range.
 *
 * @param min lowest supported positive API major
 * @param max highest supported positive API major
 */
public record YanoApiRange(int min, int max) {

    /** Validates and creates an inclusive API-major range. */
    public YanoApiRange {
        if (min <= 0) {
            throw new IllegalArgumentException("yanoApi.min must be positive");
        }
        if (max <= 0) {
            throw new IllegalArgumentException("yanoApi.max must be positive");
        }
        if (min > max) {
            throw new IllegalArgumentException("yanoApi range must satisfy min <= max");
        }
    }

    /**
     * Tests an API major for compatibility.
     *
     * @param apiMajor API major to test
     * @return {@code true} when the value is within this inclusive range
     */
    public boolean supports(int apiMajor) {
        return apiMajor >= min && apiMajor <= max;
    }
}
