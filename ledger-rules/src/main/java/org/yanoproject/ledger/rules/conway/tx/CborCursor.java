package org.yanoproject.ledger.rules.conway.tx;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * A forward cursor over CBOR heads, for the validators whose Haskell rules depend on the encoding itself and that must
 * run in linear time over input of any nesting depth: Plutus {@code Data} ({@link PlutusData}: byte-string chunk sizes,
 * the one-byte bignum heads), metadata ({@link RawAuxData}: text chunks) and the byte strings of a native script
 * ({@link RawScript}). {@code CborSpan} cannot serve them: it exposes neither heads nor chunks, and enumerating a
 * span's children walks each child again, which is quadratic in the depth. Nesting is tracked on an explicit stack
 * ({@link Containers}), never by recursion. Malformed input is a {@link TxDecodingException}.
 */
final class CborCursor {

    /** A container length meaning "indefinite, terminated by a break". */
    static final long INDEFINITE = -1;

    private final byte[] data;
    private final int end;
    private int pos;

    CborCursor(byte[] data, int start, int end) {
        this.data = data;
        this.pos = start;
        this.end = end;
    }

    boolean atEnd() {
        return pos >= end;
    }

    /** @return the next head's initial byte, not consumed */
    int peek() {
        if (pos >= end) {
            throw new TxDecodingException("unexpected end of CBOR at " + pos);
        }
        return data[pos] & 0xff;
    }

    /** @return the next head's initial byte, consumed (its argument, if any, follows) */
    int next() {
        int initial = peek();
        pos++;
        return initial;
    }

    /** Consumes a break and returns true, or returns false when the next byte is not a break. */
    boolean atBreak() {
        if (peek() == 0xff) {
            pos++;
            return true;
        }
        return false;
    }

    /** @return the argument of a head whose initial byte was consumed, as unsigned 64 bits */
    BigInteger argument(int initial) {
        int info = initial & 0x1f;
        if (info < 24) {
            return BigInteger.valueOf(info);
        }
        int size = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new TxDecodingException("unsupported CBOR additional information " + info + " at "
                    + (pos - 1));
        };
        if (size > end - pos) {
            throw new TxDecodingException("truncated CBOR head at " + pos);
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < size; i++) {
            value = value.shiftLeft(8).or(BigInteger.valueOf(data[pos++] & 0xff));
        }
        return value;
    }

    /** @return the length of an array or map head of {@code major} (consumed), or {@link #INDEFINITE} */
    long containerHeader(int major) {
        int initial = next();
        if (initial >>> 5 != major) {
            throw new TxDecodingException("unexpected CBOR major type " + (initial >>> 5) + " at " + (pos - 1));
        }
        if ((initial & 0x1f) == 31) {
            return INDEFINITE;
        }
        BigInteger length = argument(initial);
        if (length.bitLength() > 31) {
            throw new TxDecodingException("CBOR container too large at " + pos);
        }
        return length.longValue();
    }

    /** @return the content of the definite-length string of {@code major} at the cursor (a chunk), consumed */
    byte[] definiteString(int major) {
        int initial = next();
        if (initial >>> 5 != major || (initial & 0x1f) == 31) {
            throw new TxDecodingException("expected a definite-length string of major type " + major + " at "
                    + (pos - 1));
        }
        BigInteger length = argument(initial);
        if (length.compareTo(BigInteger.valueOf(end - pos)) > 0) {
            throw new TxDecodingException("CBOR string runs past the input at " + pos);
        }
        byte[] out = Arrays.copyOfRange(data, pos, pos + length.intValue());
        pos += out.length;
        return out;
    }

    /**
     * The open arrays and maps of a walk: after reading an item's head the walker opens its container, and
     * {@link #next} says whether another item must be read, consuming the breaks of indefinite containers and closing
     * finished ones. A break may end an indefinite map only before a key; anywhere else it is read as an item, which
     * every walker refuses.
     */
    static final class Containers {
        private static final int ITEMS = 0;
        private static final int INDEFINITE_ITEMS = 1;
        private static final int INDEFINITE_MAP_KEY = 2;
        private static final int INDEFINITE_MAP_VALUE = 3;
        private static final int SEALED = 4;
        private static final int SEALED_INDEFINITE = 5;

        private int[] kind = new int[16];
        private long[] count = new long[16];
        private int depth;

        /** Opens a container of {@code major} 4 or 5 whose head gave {@code length} (or {@link #INDEFINITE}). */
        void open(int major, long length) {
            if (length == INDEFINITE) {
                push(major == 5 ? INDEFINITE_MAP_KEY : INDEFINITE_ITEMS, 0);
            } else {
                push(ITEMS, major == 5 ? 2 * length : length);
            }
        }

        /**
         * Opens an array whose {@code items} items the walker reads itself (each one opened on top of this): when they
         * are done, an indefinite array must end with its break and a definite one must have had exactly that many.
         */
        void seal(long length, int items) {
            if (length == INDEFINITE) {
                push(SEALED_INDEFINITE, 0);
            } else {
                push(SEALED, length - items);
            }
        }

        /** @return true when another item must be read, false when the walk is complete */
        boolean next(CborCursor c) {
            while (depth > 0) {
                int top = depth - 1;
                switch (kind[top]) {
                    case ITEMS -> {
                        if (count[top] == 0) {
                            depth--;
                        } else {
                            count[top]--;
                            return true;
                        }
                    }
                    case INDEFINITE_ITEMS -> {
                        if (!c.atBreak()) {
                            return true;
                        }
                        depth--;
                    }
                    case INDEFINITE_MAP_KEY -> {
                        if (!c.atBreak()) {
                            kind[top] = INDEFINITE_MAP_VALUE;
                            return true;
                        }
                        depth--;
                    }
                    case INDEFINITE_MAP_VALUE -> {
                        kind[top] = INDEFINITE_MAP_KEY;
                        return true;
                    }
                    default -> {
                        boolean indefinite = kind[top] == SEALED_INDEFINITE;
                        long extra = count[top];
                        depth--;
                        if (indefinite ? !c.atBreak() : extra != 0) {
                            throw new TxDecodingException("an array at " + c.pos + " does not have the expected "
                                    + "number of elements");
                        }
                    }
                }
            }
            return false;
        }

        private void push(int frameKind, long frameCount) {
            if (depth == kind.length) {
                kind = Arrays.copyOf(kind, depth * 2);
                count = Arrays.copyOf(count, depth * 2);
            }
            kind[depth] = frameKind;
            count[depth] = frameCount;
            depth++;
        }
    }
}
