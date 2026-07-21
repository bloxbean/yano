package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.regex.Pattern;

/** Shared validation for the framework-neutral plugin operations contracts. */
final class PluginOperationsValidation {
    static final int MAX_BUNDLE_ID_LENGTH = 160;
    static final int MAX_DESCRIPTION_LENGTH = 256;
    static final int MAX_IDENTIFIER_LENGTH = 64;

    private static final String DNS_LABEL =
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern BUNDLE_ID = Pattern.compile(
            DNS_LABEL + "(?:\\." + DNS_LABEL + ")+" );
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern METRIC_NAME = Pattern.compile(
            "[a-z][a-z0-9_.-]{0,99}");
    private static final Pattern METRIC_UNIT = Pattern.compile(
            "[a-z][a-z0-9_.-]{0,31}");

    private PluginOperationsValidation() {
    }

    static String bundleId(String value, String field) {
        if (value == null || value.length() < 3
                || value.length() > MAX_BUNDLE_ID_LENGTH
                || !BUNDLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field
                    + " must be a 3-160 character lowercase reverse-DNS identifier");
        }
        return value;
    }

    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field
                    + " must match [a-z][a-z0-9_.-]{0,63}");
        }
        return value;
    }

    static String metricName(String value) {
        if (value == null || !METRIC_NAME.matcher(value).matches()
                || value.startsWith("yano.")) {
            throw new IllegalArgumentException(
                    "metric name must be a relative lowercase ASCII name of at most 100 characters");
        }
        return value;
    }

    static String metricUnit(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!METRIC_UNIT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "metric baseUnit must be empty or a lowercase ASCII unit of at most 32 characters");
        }
        return value;
    }

    static String description(String value, String field) {
        if (value == null || value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(field + " must contain at most 256 printable ASCII characters");
        }
        printableAscii(value, field);
        return value;
    }

    static String boundedAscii(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field
                    + " must be non-blank ASCII without surrounding whitespace and at most "
                    + maximum + " characters");
        }
        printableAscii(value, field);
        return value;
    }

    static void printableAscii(String value, String field) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException(field + " must contain printable ASCII only");
            }
        }
    }

    static void nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    static void nonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
