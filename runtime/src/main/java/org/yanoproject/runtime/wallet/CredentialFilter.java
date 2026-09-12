package org.yanoproject.runtime.wallet;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.TreeSet;

/**
 * Version-one node-local GCS: version(1), seed(16), count(uint32 BE), Rice bits.
 * The count is fixed-width to keep framing strict and bounded. Inputs are distinct
 * typed credential bytes; hash collisions remain in the encoded multiset.
 */
public final class CredentialFilter {
    public static final int MAX_ELEMENTS = 100_000;
    private static final int HEADER_SIZE = 21;
    private static final int P = 13;
    private static final long M = 12_288;

    private CredentialFilter() { }

    public static byte[] encode(byte[] seed, Collection<byte[]> elements) {
        SipHash24 hash = new SipHash24(seed);
        if (elements.size() > MAX_ELEMENTS) throw new IllegalArgumentException("Too many filter elements");
        TreeSet<byte[]> distinct = new TreeSet<>(Arrays::compareUnsigned);
        distinct.addAll(elements);
        int count = distinct.size();
        long range = count * M;
        long[] values = new long[count];
        int index = 0;
        for (byte[] element : distinct) values[index++] = map(hash.hash(element), range);
        Arrays.sort(values);
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_SIZE + count * 2);
        out.write(1);
        out.writeBytes(seed);
        out.writeBytes(ByteBuffer.allocate(4).putInt(count).array());
        BitWriter bits = new BitWriter(out);
        long previous = 0;
        for (long value : values) {
            long delta = value - previous;
            for (long q = delta >>> P; q > 0; q--) bits.write(1);
            bits.write(0);
            for (int bit = P - 1; bit >= 0; bit--) bits.write((int) (delta >>> bit) & 1);
            previous = value;
        }
        bits.finish();
        return out.toByteArray();
    }

    /** Validates the entire encoding even after a match, so corruption cannot hide gaps. */
    public static boolean matches(byte[] encoded, Collection<byte[]> queries) {
        if (encoded.length < HEADER_SIZE || encoded[0] != 1) {
            throw new IllegalArgumentException("Unsupported or truncated credential filter");
        }
        int count = ByteBuffer.wrap(encoded, 17, 4).getInt();
        if (count < 0 || count > MAX_ELEMENTS) throw new IllegalArgumentException("Invalid filter count");
        if (queries.size() > MAX_ELEMENTS) throw new IllegalArgumentException("Too many filter queries");
        long range = count * M;
        long[] values = new long[count == 0 ? 0 : queries.size()];
        if (count > 0) {
            SipHash24 hash = new SipHash24(Arrays.copyOfRange(encoded, 1, 17));
            int i = 0;
            for (byte[] query : queries) values[i++] = map(hash.hash(query), range);
            Arrays.sort(values);
        }
        BitReader bits = new BitReader(encoded, HEADER_SIZE * 8);
        long current = 0;
        int queryIndex = 0;
        boolean matched = false;
        for (int i = 0; i < count; i++) {
            long quotient = 0;
            while (bits.read() == 1) {
                if (++quotient > (range >>> P)) throw new IllegalArgumentException("Invalid Rice quotient");
            }
            long delta = quotient << P;
            for (int bit = P - 1; bit >= 0; bit--) delta |= (long) bits.read() << bit;
            current += delta;
            if (current >= range) throw new IllegalArgumentException("Filter value outside range");
            while (queryIndex < values.length && values[queryIndex] < current) queryIndex++;
            if (queryIndex < values.length && values[queryIndex] == current) matched = true;
        }
        bits.requireCanonicalEnd();
        return matched;
    }

    private static long map(long hash, long range) {
        // Unsigned high half of hash * positive range; no BigInteger or division.
        return Math.multiplyHigh(hash, range) + (hash < 0 ? range : 0);
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out;
        private int value;
        private int count;

        private BitWriter(ByteArrayOutputStream out) { this.out = out; }

        void write(int bit) {
            value = (value << 1) | bit;
            if (++count == 8) {
                out.write(value);
                value = 0;
                count = 0;
            }
        }

        void finish() {
            if (count != 0) out.write(value << (8 - count));
        }
    }

    private static final class BitReader {
        private final byte[] bytes;
        private int position;

        private BitReader(byte[] bytes, int position) {
            this.bytes = bytes;
            this.position = position;
        }

        int read() {
            if (position / 8 >= bytes.length) throw new IllegalArgumentException("Truncated Rice stream");
            int bit = (bytes[position / 8] >>> (7 - position % 8)) & 1;
            position++;
            return bit;
        }

        void requireCanonicalEnd() {
            if ((position + 7) / 8 != bytes.length) throw new IllegalArgumentException("Trailing filter bytes");
            while (position % 8 != 0) {
                if (read() != 0) throw new IllegalArgumentException("Nonzero filter padding");
            }
        }
    }
}
