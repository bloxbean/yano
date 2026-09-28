package org.yanoproject.ledger.rules.conway;

/**
 * A protocol-version gate as data (ADR-056 §4): the major versions at which a check runs, or at which a
 * constructor is the one Haskell reports.
 *
 * @param min the first major version (inclusive)
 * @param max the last major version (inclusive), or {@link Integer#MAX_VALUE} while still active
 */
public record PvRange(int min, int max) {

    /** Every Conway protocol version. */
    public static final PvRange ALWAYS = new PvRange(9, Integer.MAX_VALUE);

    public PvRange {
        if (min > max) {
            throw new IllegalArgumentException("empty protocol version range " + min + ".." + max);
        }
    }

    public static PvRange from(int min) {
        return new PvRange(min, Integer.MAX_VALUE);
    }

    public static PvRange between(int min, int max) {
        return new PvRange(min, max);
    }

    public boolean contains(int protocolMajor) {
        return protocolMajor >= min && protocolMajor <= max;
    }

    @Override
    public String toString() {
        return max == Integer.MAX_VALUE ? min + "+" : min == max ? String.valueOf(min) : min + "–" + max;
    }
}
