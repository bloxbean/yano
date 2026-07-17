package com.bloxbean.cardano.yano.appchain.composite;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

final class CompositeValidation {
    static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    static final int MAX_TEXT_BYTES = 128;

    private CompositeValidation() {
    }

    static String id(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match [a-z][a-z0-9-]{0,62}");
        }
        return value;
    }

    static String printable(String value, String field) {
        Objects.requireNonNull(value, field);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (value.isEmpty() || bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(field + " must contain 1-" + MAX_TEXT_BYTES + " UTF-8 bytes");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException(field + " must contain printable ASCII without spaces");
            }
        }
        return value;
    }

    static String route(String value, String field) {
        printable(value, field);
        if (value.startsWith("~")) {
            throw new IllegalArgumentException(field + " must not use a reserved system route");
        }
        return value;
    }

    static void activation(long fromHeight, long untilHeight, String field) {
        if (fromHeight < 1 || untilHeight < 0 || (untilHeight != 0 && untilHeight <= fromHeight)) {
            throw new IllegalArgumentException(field
                    + " must have fromHeight >= 1 and untilHeight == 0 or > fromHeight");
        }
    }

    static boolean overlaps(long leftFrom, long leftUntil, long rightFrom, long rightUntil) {
        long leftEnd = leftUntil == 0 ? Long.MAX_VALUE : leftUntil;
        long rightEnd = rightUntil == 0 ? Long.MAX_VALUE : rightUntil;
        return leftFrom < rightEnd && rightFrom < leftEnd;
    }
}
