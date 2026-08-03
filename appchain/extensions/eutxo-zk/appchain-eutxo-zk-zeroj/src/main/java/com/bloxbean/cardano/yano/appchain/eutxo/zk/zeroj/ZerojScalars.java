package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.zeroj.bls12381.ec.G1Point;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

final class ZerojScalars {
    static final BigInteger FIELD = G1Point.R;

    private ZerojScalars() {
    }

    static BigInteger scalar(byte[] value) {
        Objects.requireNonNull(value, "value");
        return new BigInteger(1, value).mod(FIELD);
    }

    static BigInteger domain(String value) {
        try {
            return scalar(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static byte[] bytes32(BigInteger value) {
        byte[] source = value.mod(FIELD).toByteArray();
        byte[] result = new byte[32];
        int count = Math.min(source.length, result.length);
        System.arraycopy(source, source.length - count,
                result, result.length - count, count);
        return result;
    }
}
