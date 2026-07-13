package com.bloxbean.cardano.yano.appchain.client;

/** Minimal hex codec — keeps the client SDK dependency-light. */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = DIGITS[v >>> 4];
            out[i * 2 + 1] = DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] decode(String hex) {
        String normalized = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (normalized.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd-length hex string");
        }
        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
