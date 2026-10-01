package org.yanoproject.ledger.amaru.wire;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * The CBOR subset the v1 interface uses, written exactly as the Rust reference encoder (minicbor)
 * writes it: every head in its shortest form, definite lengths only.
 */
public final class CborWriter {

    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private final ByteArrayOutputStream out;

    public CborWriter() {
        this(256);
    }

    public CborWriter(int initialCapacity) {
        this.out = new ByteArrayOutputStream(initialCapacity);
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    private CborWriter head(int major, long argument) {
        int mt = major << 5;
        if (Long.compareUnsigned(argument, 24) < 0) {
            out.write(mt | (int) argument);
        } else if (Long.compareUnsigned(argument, 0x100) < 0) {
            out.write(mt | 24);
            out.write((int) argument);
        } else if (Long.compareUnsigned(argument, 0x10000) < 0) {
            out.write(mt | 25);
            writeBigEndian(argument, 2);
        } else if (Long.compareUnsigned(argument, 0x1_0000_0000L) < 0) {
            out.write(mt | 26);
            writeBigEndian(argument, 4);
        } else {
            out.write(mt | 27);
            writeBigEndian(argument, 8);
        }
        return this;
    }

    private void writeBigEndian(long value, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)) & 0xff);
        }
    }

    /** An unsigned integer; {@code value} must be non-negative. */
    public CborWriter uint(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value for an unsigned integer: " + value);
        }
        return head(0, value);
    }

    /** An unsigned integer up to 2^64 - 1. */
    public CborWriter uint(BigInteger value) {
        if (value.signum() < 0 || value.compareTo(U64_MAX) > 0) {
            throw new IllegalArgumentException("not a u64: " + value);
        }
        return head(0, value.longValue());
    }

    /** A signed integer (major type 0 or 1). */
    public CborWriter integer(long value) {
        return value >= 0 ? head(0, value) : head(1, -1 - value);
    }

    public CborWriter bytes(byte[] value) {
        head(2, value.length);
        out.writeBytes(value);
        return this;
    }

    public CborWriter text(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        head(3, utf8.length);
        out.writeBytes(utf8);
        return this;
    }

    public CborWriter array(long length) {
        return head(4, length);
    }

    public CborWriter map(long entries) {
        return head(5, entries);
    }

    public CborWriter tag(long tag) {
        return head(6, tag);
    }

    public CborWriter bool(boolean value) {
        out.write(value ? 0xf5 : 0xf4);
        return this;
    }

    public CborWriter nil() {
        out.write(0xf6);
        return this;
    }

    /** Pre-encoded CBOR, copied as is. */
    public CborWriter raw(byte[] encoded) {
        out.writeBytes(encoded);
        return this;
    }
}
