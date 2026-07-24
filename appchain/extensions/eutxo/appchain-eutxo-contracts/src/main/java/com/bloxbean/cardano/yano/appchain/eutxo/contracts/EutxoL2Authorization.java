package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;

/** Canonical Jubjub authorization witness carried only by the Yano L2 protocol. */
public record EutxoL2Authorization(
        String paymentCredential,
        long keyEpoch,
        byte[] publicKey,
        byte[] rPoint,
        byte[] s,
        List<Integer> inputIndexes
) {
    public EutxoL2Authorization {
        paymentCredential = credential(paymentCredential);
        if (keyEpoch < 1) {
            throw new IllegalArgumentException("L2 key epoch must be positive");
        }
        publicKey = bytes32(publicKey, "Jubjub public key");
        rPoint = bytes32(rPoint, "Jubjub signature R");
        s = bytes32(s, "Jubjub signature S");
        inputIndexes = List.copyOf(Objects.requireNonNull(
                inputIndexes, "inputIndexes"));
        if (inputIndexes.isEmpty()) {
            throw new IllegalArgumentException(
                    "L2 authorization must reference at least one input");
        }
        int previous = -1;
        for (Integer index : inputIndexes) {
            if (index == null || index < 0 || index <= previous) {
                throw new IllegalArgumentException(
                        "L2 authorization input indexes must be strictly ordered");
            }
            previous = index;
        }
    }

    @Override
    public byte[] publicKey() {
        return publicKey.clone();
    }

    @Override
    public byte[] rPoint() {
        return rPoint.clone();
    }

    @Override
    public byte[] s() {
        return s.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoL2Authorization authorization
                && paymentCredential.equals(authorization.paymentCredential)
                && keyEpoch == authorization.keyEpoch
                && Arrays.equals(publicKey, authorization.publicKey)
                && Arrays.equals(rPoint, authorization.rPoint)
                && Arrays.equals(s, authorization.s)
                && inputIndexes.equals(authorization.inputIndexes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                paymentCredential, keyEpoch, inputIndexes);
        result = 31 * result + Arrays.hashCode(publicKey);
        result = 31 * result + Arrays.hashCode(rPoint);
        return 31 * result + Arrays.hashCode(s);
    }

    static String credential(String value) {
        value = Objects.requireNonNull(value, "paymentCredential").trim();
        if (value.length() != 56
                || !value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "payment credential must be lowercase 28-byte hex");
        }
        try {
            if (HexFormat.of().parseHex(value).length != 28) {
                throw new IllegalArgumentException("invalid payment credential");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "payment credential must be lowercase 28-byte hex", failure);
        }
        return value;
    }

    private static byte[] bytes32(byte[] value, String label) {
        value = Objects.requireNonNull(value, label).clone();
        if (value.length != 32) {
            throw new IllegalArgumentException(label + " must contain 32 bytes");
        }
        return value;
    }
}
