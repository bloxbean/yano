package com.bloxbean.cardano.yano.api.appchain.observation;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical signed 128-bit fixed-point claim; no implicit or lossy rescaling. */
public record ObservationFixedPoint(BigInteger units, int scale) {
    public static final int MAX_SCALE = 18;
    public static final int ENCODED_BYTES = 18;
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
    private static final BigInteger MIN = BigInteger.ONE.shiftLeft(127).negate();
    private static final BigInteger MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);

    public ObservationFixedPoint {
        Objects.requireNonNull(units, "units");
        if (scale < 0 || scale > MAX_SCALE || units.compareTo(MIN) < 0 || units.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("Fixed-point scale or integer outside protocol bounds");
        }
    }

    /** Strict decimal input, padded exactly to the requested scale, never rounded. */
    public static ObservationFixedPoint parse(String decimal, int scale) {
        if (decimal == null || decimal.length() > 64 || !DECIMAL.matcher(decimal).matches()
                || scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("Invalid fixed-point decimal");
        }
        int dot = decimal.indexOf('.');
        int fractional = dot < 0 ? 0 : decimal.length() - dot - 1;
        if (fractional > scale) throw new IllegalArgumentException("Fixed-point scale mismatch");
        BigInteger units = new BigInteger(decimal.replace(".", ""));
        if (units.signum() == 0 && decimal.startsWith("-")) {
            throw new IllegalArgumentException("Negative zero is not canonical");
        }
        return new ObservationFixedPoint(units.multiply(BigInteger.TEN.pow(scale - fractional)), scale);
    }

    public byte[] encode() {
        byte[] encoded = new byte[ENCODED_BYTES];
        encoded[0] = 1;
        encoded[1] = (byte) scale;
        if (units.signum() < 0) Arrays.fill(encoded, 2, encoded.length, (byte) 0xff);
        byte[] integer = units.toByteArray();
        System.arraycopy(integer, 0, encoded, encoded.length - integer.length, integer.length);
        return encoded;
    }

    public static ObservationFixedPoint decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_BYTES || encoded[0] != 1) {
            throw new IllegalArgumentException("Invalid fixed-point encoding");
        }
        return new ObservationFixedPoint(new BigInteger(Arrays.copyOfRange(encoded, 2, encoded.length)),
                Byte.toUnsignedInt(encoded[1]));
    }
}
