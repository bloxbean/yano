package com.bloxbean.cardano.yano.api.util;

/**
 * Small hex validation helpers for API parsing.
 *
 * <p>Encoding and decoding should continue to use Yaci's HexUtil.
 */
public final class CardanoHex {
    public static final int HASH_28_BYTES_HEX_LENGTH = 56;
    public static final int HASH_32_BYTES_HEX_LENGTH = 64;

    private CardanoHex() {
    }

    public static boolean isHex(String value) {
        if (value == null || (value.length() & 1) != 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    public static boolean isHex(String value, int expectedLength) {
        return value != null && value.length() == expectedLength && isHex(value);
    }

    public static boolean isHash28Bytes(String value) {
        return isHex(value, HASH_28_BYTES_HEX_LENGTH);
    }

    public static boolean isHash32Bytes(String value) {
        return isHex(value, HASH_32_BYTES_HEX_LENGTH);
    }

    public static boolean isTxHash(String value) {
        return isHash32Bytes(value);
    }
}
