package com.bloxbean.cardano.yano.api.appchain;

import java.util.List;
import java.util.regex.Pattern;

/** Shared canonical grammar for generic and domain-initiated query paths. */
public final class AppQueryPath {
    public static final int MAX_LENGTH = 256;
    public static final int MAX_SEGMENTS = 128;

    private static final Pattern SEGMENT = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._~-]*");

    private AppQueryPath() {
    }

    /**
     * Validate an already-decoded, normalized relative query path.
     * Percent escapes are deliberately forbidden so every adapter validates
     * the raw form once and dispatches one unambiguous value.
     */
    public static String validate(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH
                || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('%') >= 0 || value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidPath();
        }
        List<String> segments = List.of(value.split("/", -1));
        if (segments.size() > MAX_SEGMENTS
                || segments.stream().anyMatch(String::isEmpty)
                || segments.stream().anyMatch(segment -> segment.equals(".")
                        || segment.equals(".."))
                || segments.stream().anyMatch(segment -> !SEGMENT.matcher(segment).matches())) {
            throw invalidPath();
        }
        return value;
    }

    private static IllegalArgumentException invalidPath() {
        return new IllegalArgumentException(
                "query path must be a normalized relative path containing 1-128 "
                        + "unreserved ASCII segments and at most 256 characters");
    }
}
