package com.bloxbean.cardano.yano.catalog;

/**
 * Supported Yano plugin API major range and minimum global additive level.
 *
 * @param min lowest supported positive API major
 * @param max highest supported positive API major
 * @param minLevel lowest required positive global API level
 */
public record YanoApiRange(int min, int max, int minLevel) {

    /** Validates and creates an inclusive API-major range plus minimum level. */
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
        if (minLevel <= 0) {
            throw new IllegalArgumentException("yanoApi.minLevel must be positive");
        }
    }

    /**
     * Tests a host API major and global additive level for compatibility.
     *
     * @param apiMajor API major to test
     * @param apiLevel global additive API level to test
     * @return {@code true} when the major is in range and the level is sufficient
     */
    public boolean supports(int apiMajor, int apiLevel) {
        return apiMajor >= min && apiMajor <= max && apiLevel >= minLevel;
    }
}
