package org.yanoproject.ledger.amaru.wire;

import java.util.List;
import java.util.Map;

/** Typed access to {@link CborReader} values; every mismatch is an {@link IllegalArgumentException}. */
final class WireValues {

    private WireValues() {
    }

    static Map<?, ?> map(Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(field + " is not a map");
    }

    static List<?> list(Object value, String field) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException(field + " is not an array");
    }

    static List<?> list(Object value, String field, int size) {
        List<?> list = list(value, field);
        if (list.size() != size) {
            throw new IllegalArgumentException(field + " has " + list.size() + " elements, expected " + size);
        }
        return list;
    }

    static long uint(Object value, String field) {
        if (value instanceof Long l && l >= 0) {
            return l;
        }
        throw new IllegalArgumentException(field + " is not an unsigned integer: " + value);
    }

    static String text(Object value, String field) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(field + " is not a text string");
    }

    static byte[] bytes(Object value, String field, int size) {
        if (value instanceof byte[] bytes && (size < 0 || bytes.length == size)) {
            return bytes;
        }
        throw new IllegalArgumentException(field + " is not a " + size + "-byte string");
    }
}
