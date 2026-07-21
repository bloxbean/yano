package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Digests {
    private Digests() {
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static byte[] fromHex(String value) {
        if (value == null || !value.matches("[0-9a-f]+")
                || (value.length() & 1) != 0) {
            throw new IllegalArgumentException("invalid canonical hex");
        }
        return HexFormat.of().parseHex(value);
    }
}
