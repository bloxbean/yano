package org.yanoproject.ledger.rules.fixtures.blueprint;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.util.CborItems;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A forward-only CBOR (RFC 8949) reader over a byte array that keeps byte offsets, so a decoder can hand the exact
 * bytes of an item (a transaction output, a governance action) to another decoder.
 *
 * <p>Every read checks the major type it expects and throws {@link IllegalArgumentException} with the offset when the
 * input does not match, which the ledger-state decoders turn into a fail-closed "cannot decode" reason.</p>
 */
public final class CborReader {

    /** A container length of {@code -1} means indefinite (terminated by a break). */
    public static final long INDEFINITE = -1;

    private static final int MAJOR_UINT = 0;
    private static final int MAJOR_NINT = 1;
    private static final int MAJOR_BYTES = 2;
    private static final int MAJOR_TEXT = 3;
    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_MAP = 5;
    private static final int MAJOR_TAG = 6;
    private static final int MAJOR_SIMPLE = 7;

    private final byte[] data;
    private int pos;

    public CborReader(byte[] data) {
        this(data, 0);
    }

    public CborReader(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
    }

    public int position() {
        return pos;
    }

    public boolean atEnd() {
        return pos >= data.length;
    }

    /** @return the major type of the next item */
    public int peekMajor() {
        requireMore();
        return (data[pos] & 0xff) >>> 5;
    }

    /** @return true when the next byte is a break (the end of an indefinite container) */
    public boolean peekBreak() {
        requireMore();
        return (data[pos] & 0xff) == 0xff;
    }

    /** @return true when the next item is {@code null} (simple value 22) */
    public boolean peekNull() {
        requireMore();
        return (data[pos] & 0xff) == 0xf6;
    }

    public void readNull() {
        if (!peekNull()) {
            throw malformed("expected null");
        }
        pos++;
    }

    public void readBreak() {
        if (!peekBreak()) {
            throw malformed("expected a break");
        }
        pos++;
    }

    /** @return the array length, or {@link #INDEFINITE} */
    public long readArray() {
        return readContainer(MAJOR_ARRAY, "array");
    }

    /** Reads an array header and requires exactly {@code length} elements (definite). */
    public void readArray(long length, String what) {
        long actual = readArray();
        if (actual != length) {
            throw malformed(what + ": expected an array of " + length + ", got "
                    + (actual == INDEFINITE ? "an indefinite array" : actual));
        }
    }

    /** @return the map size (pairs), or {@link #INDEFINITE} */
    public long readMap() {
        return readContainer(MAJOR_MAP, "map");
    }

    /**
     * @param length a container length as returned by {@link #readArray()} / {@link #readMap()}
     * @param index  the number of elements already read
     * @return true while the container has more elements (consumes the break of an indefinite one at its end)
     */
    public boolean hasNext(long length, long index) {
        if (length == INDEFINITE) {
            if (peekBreak()) {
                pos++;
                return false;
            }
            return true;
        }
        return index < length;
    }

    public long readTag() {
        long[] head = head(MAJOR_TAG, "tag");
        return head[1];
    }

    /** @return the tag number when the next item is a tag (consumed), otherwise -1 (nothing consumed) */
    public long readOptionalTag() {
        return peekMajor() == MAJOR_TAG ? readTag() : -1;
    }

    /** An unsigned integer that fits in a signed long. */
    public long readUint() {
        long[] head = head(MAJOR_UINT, "unsigned integer");
        if (head[1] < 0) {
            throw malformed("unsigned integer exceeds 2^63-1");
        }
        return head[1];
    }

    /** An integer (major type 0 or 1, or a bignum tag 2/3) as a {@link BigInteger}. */
    public BigInteger readBigInteger() {
        int major = peekMajor();
        if (major == MAJOR_UINT) {
            long[] head = head(MAJOR_UINT, "integer");
            return unsigned(head[1]);
        }
        if (major == MAJOR_NINT) {
            long[] head = head(MAJOR_NINT, "integer");
            return BigInteger.valueOf(-1).subtract(unsigned(head[1]));
        }
        if (major == MAJOR_TAG) {
            int start = pos;
            long tag = readTag();
            if (tag == 2 || tag == 3) {
                BigInteger magnitude = new BigInteger(1, readBytes());
                return tag == 2 ? magnitude : BigInteger.valueOf(-1).subtract(magnitude);
            }
            pos = start;
        }
        throw malformed("expected an integer");
    }

    /** A signed 64-bit integer (major type 0 or 1). */
    public long readLong() {
        return readBigInteger().longValueExact();
    }

    public int readInt() {
        return Math.toIntExact(readLong());
    }

    public byte[] readBytes() {
        long[] head = head(MAJOR_BYTES, "byte string");
        if (head[1] < 0) {
            throw malformed("indefinite byte strings are not supported");
        }
        int start = pos;
        pos = Math.addExact(start, Math.toIntExact(head[1]));
        if (pos > data.length) {
            throw malformed("truncated byte string");
        }
        return Arrays.copyOfRange(data, start, pos);
    }

    public String readBytesHex() {
        return HexUtil.encodeHexString(readBytes());
    }

    public String readText() {
        long[] head = head(MAJOR_TEXT, "text string");
        if (head[1] < 0) {
            throw malformed("indefinite text strings are not supported");
        }
        int start = pos;
        pos = Math.addExact(start, Math.toIntExact(head[1]));
        if (pos > data.length) {
            throw malformed("truncated text string");
        }
        return new String(data, start, pos - start, StandardCharsets.UTF_8);
    }

    public boolean readBool() {
        requireMore();
        int b = data[pos] & 0xff;
        if (b == 0xf4 || b == 0xf5) {
            pos++;
            return b == 0xf5;
        }
        throw malformed("expected a boolean");
    }

    /**
     * A rational ({@code #6.30([numerator, denominator])}, Haskell {@code BoundedRational}), as a
     * {@code [numerator, denominator]} pair.
     */
    public BigInteger[] readRational() {
        long tag = readTag();
        if (tag != 30) {
            throw malformed("expected a rational (tag 30), got tag " + tag);
        }
        readArray(2, "rational");
        BigInteger numerator = readBigInteger();
        BigInteger denominator = readBigInteger();
        if (denominator.signum() <= 0) {
            throw malformed("rational with a non-positive denominator");
        }
        return new BigInteger[]{numerator, denominator};
    }

    /**
     * @return {@link #readRational()} as an exact decimal
     * @throws IllegalArgumentException when the fraction has no finite decimal expansion (the rules read these
     *                                  parameters as {@link BigDecimal}s and must not see a rounded value)
     */
    public BigDecimal readRationalDecimal() {
        BigInteger[] r = readRational();
        try {
            return new BigDecimal(r[0]).divide(new BigDecimal(r[1]));
        } catch (ArithmeticException e) {
            throw malformed("rational " + r[0] + "/" + r[1] + " has no exact decimal form");
        }
    }

    /** @return the exact bytes of the next item, and moves past it */
    public byte[] readRaw() {
        int start = pos;
        skip();
        return Arrays.copyOfRange(data, start, pos);
    }

    public void skip() {
        requireMore();
        try {
            pos = CborItems.skip(data, pos);
        } catch (IllegalArgumentException e) {
            throw malformed(e.getMessage());
        }
    }

    /** Skips a StrictMaybe ({@code []} / {@code [x]}) and returns whether it held a value (left before it). */
    public boolean readStrictMaybeHeader() {
        long length = readArray();
        if (length == 0) {
            return false;
        }
        if (length != 1) {
            throw malformed("StrictMaybe: expected an array of 0 or 1, got " + length);
        }
        return true;
    }

    private long readContainer(int major, String what) {
        long[] head = head(major, what);
        return head[1];
    }

    /** @return {major, argument or -1 for indefinite}; advances past the head */
    private long[] head(int expectedMajor, String what) {
        requireMore();
        int initial = data[pos] & 0xff;
        int major = initial >>> 5;
        int info = initial & 0x1f;
        if (major != expectedMajor) {
            throw malformed("expected " + what + " (major type " + expectedMajor + "), got major type " + major);
        }
        if (info < 24) {
            pos++;
            return new long[]{major, info};
        }
        if (info == 31) {
            if (major == MAJOR_UINT || major == MAJOR_NINT || major == MAJOR_TAG || major == MAJOR_SIMPLE) {
                throw malformed("indefinite length for major type " + major);
            }
            pos++;
            return new long[]{major, INDEFINITE};
        }
        int length = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw malformed("reserved additional information " + info);
        };
        if (pos + length >= data.length) {
            throw malformed("truncated head");
        }
        long argument = 0;
        for (int i = 1; i <= length; i++) {
            argument = (argument << 8) | (data[pos + i] & 0xff);
        }
        pos += 1 + length;
        return new long[]{major, argument};
    }

    private static BigInteger unsigned(long value) {
        return value >= 0 ? BigInteger.valueOf(value) : BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));
    }

    private void requireMore() {
        if (pos >= data.length) {
            throw malformed("unexpected end of input");
        }
    }

    private IllegalArgumentException malformed(String what) {
        return new IllegalArgumentException(what + " at offset " + pos);
    }
}
