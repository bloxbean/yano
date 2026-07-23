package com.bloxbean.cardano.yano.catalog;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A strict Semantic Versioning 2.0.0 value.
 *
 * <p>Natural ordering implements SemVer precedence, so build metadata does not
 * participate in {@link #compareTo(SemVersion)}.</p>
 */
public final class SemVersion implements Comparable<SemVersion> {
    /** Maximum accepted encoded SemVer length. */
    public static final int MAX_LENGTH = 128;

    private static final String NUMERIC_IDENTIFIER = "0|[1-9][0-9]*";
    private static final String NON_NUMERIC_IDENTIFIER = "[0-9]*[A-Za-z-][0-9A-Za-z-]*";
    private static final String PRE_RELEASE_IDENTIFIER = "(?:" + NUMERIC_IDENTIFIER + "|"
            + NON_NUMERIC_IDENTIFIER + ")";
    private static final Pattern VERSION = Pattern.compile(
            "^(" + NUMERIC_IDENTIFIER + ")\\.(" + NUMERIC_IDENTIFIER + ")\\.("
                    + NUMERIC_IDENTIFIER + ")"
                    + "(?:-(" + PRE_RELEASE_IDENTIFIER + "(?:\\." + PRE_RELEASE_IDENTIFIER + ")*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
    );

    private final String value;
    private final String major;
    private final String minor;
    private final String patch;
    private final List<String> preReleaseIdentifiers;
    private final List<String> buildIdentifiers;

    private SemVersion(String value, Matcher matcher) {
        this.value = value;
        this.major = matcher.group(1);
        this.minor = matcher.group(2);
        this.patch = matcher.group(3);
        this.preReleaseIdentifiers = splitIdentifiers(matcher.group(4));
        this.buildIdentifiers = splitIdentifiers(matcher.group(5));
    }

    /**
     * Parses a strict Semantic Versioning 2.0.0 value.
     *
     * @param value encoded semantic version
     * @return immutable parsed version
     * @throws IllegalArgumentException if the value is null, oversized, or invalid
     */
    public static SemVersion parse(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("version must be a valid SemVer 2.0.0 value of at most 128 characters");
        }
        Matcher matcher = VERSION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("version must be a valid SemVer 2.0.0 value of at most 128 characters");
        }
        return new SemVersion(value, matcher);
    }

    /**
     * Returns the canonical major numeric identifier.
     *
     * @return major identifier without leading zeroes
     */
    public String major() {
        return major;
    }

    /**
     * Returns the canonical minor numeric identifier.
     *
     * @return minor identifier without leading zeroes
     */
    public String minor() {
        return minor;
    }

    /**
     * Returns the canonical patch numeric identifier.
     *
     * @return patch identifier without leading zeroes
     */
    public String patch() {
        return patch;
    }

    /**
     * Returns immutable prerelease identifiers in source order.
     *
     * @return prerelease identifiers, or an empty list
     */
    public List<String> preReleaseIdentifiers() {
        return preReleaseIdentifiers;
    }

    /**
     * Returns immutable build-metadata identifiers in source order.
     *
     * @return build identifiers, or an empty list
     */
    public List<String> buildIdentifiers() {
        return buildIdentifiers;
    }

    @Override
    public int compareTo(SemVersion other) {
        Objects.requireNonNull(other, "other");
        int result = compareNumeric(major, other.major);
        if (result != 0) {
            return result;
        }
        result = compareNumeric(minor, other.minor);
        if (result != 0) {
            return result;
        }
        result = compareNumeric(patch, other.patch);
        if (result != 0) {
            return result;
        }
        if (preReleaseIdentifiers.isEmpty()) {
            return other.preReleaseIdentifiers.isEmpty() ? 0 : 1;
        }
        if (other.preReleaseIdentifiers.isEmpty()) {
            return -1;
        }
        int sharedLength = Math.min(preReleaseIdentifiers.size(), other.preReleaseIdentifiers.size());
        for (int i = 0; i < sharedLength; i++) {
            result = comparePreReleaseIdentifier(
                    preReleaseIdentifiers.get(i), other.preReleaseIdentifiers.get(i));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(preReleaseIdentifiers.size(), other.preReleaseIdentifiers.size());
    }

    @Override
    public boolean equals(Object object) {
        return object == this || object instanceof SemVersion other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static List<String> splitIdentifiers(String identifiers) {
        return identifiers == null ? List.of() : List.of(identifiers.split("\\.", -1));
    }

    private static int comparePreReleaseIdentifier(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return compareNumeric(left, right);
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }

    private static boolean isNumeric(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static int compareNumeric(String left, String right) {
        int lengthComparison = Integer.compare(left.length(), right.length());
        return lengthComparison != 0 ? lengthComparison : left.compareTo(right);
    }
}
