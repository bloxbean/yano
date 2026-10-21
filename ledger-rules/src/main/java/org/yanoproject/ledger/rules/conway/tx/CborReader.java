package org.yanoproject.ledger.rules.conway.tx;

import org.yanoproject.ledger.rules.util.CborItems;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A forward-only reader over encoded CBOR (RFC 8949) that decodes just the heads, integers and byte strings the
 * Conway rules need, and hands out {@link CborSlice}s of the original bytes for everything else.
 *
 * <p>It never re-encodes: containers are walked item by item and the caller keeps the byte ranges. Definite and
 * indefinite containers are both supported (callers loop with {@link #hasNext(long, long)}); tags can be
 * skipped explicitly ({@link #skipTag(long)}, for tag-258 sets) or kept inside a slice. Malformed input
 * raises {@link TxDecodingException}.</p>
 */
public final class CborReader {

    /** A container length meaning "indefinite, terminated by a break". */
    public static final long INDEFINITE = -1;

    private static final int MAJOR_UNSIGNED = 0;
    private static final int MAJOR_NEGATIVE = 1;
    private static final int MAJOR_BYTES = 2;
    private static final int MAJOR_TEXT = 3;
    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_MAP = 5;
    private static final int MAJOR_TAG = 6;
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    private final byte[] data;
    private final int end;
    private int pos;

    public CborReader(byte[] data) {
        this(data, 0, data.length);
    }

    public CborReader(byte[] data, CborSlice slice) {
        this(data, slice.start(), slice.end());
    }

    public CborReader(byte[] data, int start, int end) {
        if (start < 0 || end > data.length || start > end) {
            throw new TxDecodingException("CBOR range [" + start + ", " + end + ") out of bounds");
        }
        this.data = data;
        this.pos = start;
        this.end = end;
    }

    public int position() {
        return pos;
    }

    public boolean atEnd() {
        return pos >= end;
    }

    /** @return the major type of the next item */
    public int peekMajor() {
        return peek() >>> 5;
    }

    /** @return the next item's initial byte, not consumed */
    public int peekInitialByte() {
        return peek();
    }

    /** @return true when the next byte is the {@code null} simple value */
    public boolean peekNull() {
        return peek() == 0xf6;
    }

    /**
     * Loop condition for a container of {@code length} entries (or {@link #INDEFINITE}), consuming the break of
     * an indefinite one.
     *
     * @param length the container length from {@link #readArrayHeader()} / {@link #readMapHeader()}
     * @param index  how many entries were read so far
     */
    public boolean hasNext(long length, long index) {
        if (length != INDEFINITE) {
            return index < length;
        }
        if (peek() == 0xff) {
            pos++;
            return false;
        }
        return true;
    }

    /** @return the array length, or {@link #INDEFINITE} */
    public long readArrayHeader() {
        return containerHeader(MAJOR_ARRAY, "array");
    }

    /** @return the map length (entries), or {@link #INDEFINITE} */
    public long readMapHeader() {
        return containerHeader(MAJOR_MAP, "map");
    }

    /** Skips a tag head if the next item carries {@code tag}; @return whether it did */
    public boolean skipTag(long tag) {
        if (peekMajor() != MAJOR_TAG) {
            return false;
        }
        int saved = pos;
        int initial = data[pos++] & 0xff;
        long value = argument(initial & 0x1f);
        if (value != tag) {
            pos = saved;
            return false;
        }
        return true;
    }

    /** @return a tag number (the tagged item follows) */
    public long readTag() {
        int initial = next();
        expectMajor(initial, MAJOR_TAG, "tag");
        return argument(initial & 0x1f);
    }

    /** @return an unsigned integer that fits a {@code long} */
    public long readUnsignedLong() {
        BigInteger value = readUnsigned();
        if (value.bitLength() > 63) {
            throw new TxDecodingException("unsigned integer " + value + " does not fit 63 bits at " + pos);
        }
        return value.longValue();
    }

    /** @return an unsigned integer (major type 0), up to 2^64 - 1 */
    public BigInteger readUnsigned() {
        int initial = next();
        expectMajor(initial, MAJOR_UNSIGNED, "unsigned integer");
        return unsignedArgument(initial & 0x1f);
    }

    /** @return a signed integer (major type 0 or 1) */
    public BigInteger readInteger() {
        int initial = next();
        int major = initial >>> 5;
        if (major == MAJOR_UNSIGNED) {
            return unsignedArgument(initial & 0x1f);
        }
        if (major == MAJOR_NEGATIVE) {
            return unsignedArgument(initial & 0x1f).negate().subtract(BigInteger.ONE);
        }
        throw new TxDecodingException("expected an integer at " + (pos - 1) + ", found major type " + major);
    }

    /** @return a byte string's content (chunks of an indefinite one concatenated) */
    public byte[] readBytes() {
        int initial = next();
        expectMajor(initial, MAJOR_BYTES, "byte string");
        if ((initial & 0x1f) == 31) {
            // RFC 8949 §3.2.3: every chunk is a definite-length byte string; nesting is not allowed.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (peek() != 0xff) {
                int chunk = next();
                if (chunk >>> 5 != MAJOR_BYTES || (chunk & 0x1f) == 31) {
                    throw new TxDecodingException("an indefinite byte string chunk must be a definite byte string at "
                            + (pos - 1));
                }
                out.writeBytes(definiteBytes(chunk));
            }
            pos++;
            return out.toByteArray();
        }
        return definiteBytes(initial);
    }

    /**
     * @return a definite-length byte string's content: Haskell's {@code decodeBytes} and {@code decodeByteArray} (hashes,
     *         account addresses, credentials, the {@code PackedBytes} of a {@code Hash}) refuse an indefinite byte string
     *         below decoder version 12 ({@code decodeBytesDefinite}, {@code decodeByteArrayDefinite},
     *         cardano-ledger-binary Decoding/Decoder.hs:350-361, 1426-1434)
     */
    public byte[] readDefiniteBytes() {
        if (peek() == 0x5f) {
            throw new TxDecodingException("an indefinite-length byte string at " + pos
                    + " (definite only below decoder version 12)");
        }
        return readBytes();
    }

    /**
     * @return a definite-length text string's UTF-8 bytes: Haskell's {@code decodeString} (cborg's) refuses an
     *         indefinite text string
     */
    public byte[] readDefiniteText() {
        if (peek() == 0x7f) {
            throw new TxDecodingException("an indefinite-length text string at " + pos);
        }
        return readTextChunks().getFirst();
    }

    /**
     * @return a text string's chunks as UTF-8 bytes: one chunk for a definite string, every chunk of an indefinite
     *         one (each a definite text string, RFC 8949 §3.2.3)
     */
    public List<byte[]> readTextChunks() {
        int initial = next();
        expectMajor(initial, MAJOR_TEXT, "text string");
        if ((initial & 0x1f) != 31) {
            return List.of(definiteBytes(initial));
        }
        List<byte[]> chunks = new ArrayList<>();
        while (peek() != 0xff) {
            int chunk = next();
            if (chunk >>> 5 != MAJOR_TEXT || (chunk & 0x1f) == 31) {
                throw new TxDecodingException("an indefinite text string chunk must be a definite text string at "
                        + (pos - 1));
            }
            chunks.add(definiteBytes(chunk));
        }
        pos++;
        return chunks;
    }

    private byte[] definiteBytes(int initial) {
        long length = argument(initial & 0x1f);
        if (length > end - pos) {
            throw new TxDecodingException("byte string of " + length + " bytes exceeds the input at " + pos);
        }
        byte[] value = new byte[(int) length];
        System.arraycopy(data, pos, value, 0, value.length);
        pos += value.length;
        return value;
    }

    /** @return a boolean simple value */
    public boolean readBoolean() {
        int initial = next();
        return switch (initial) {
            case 0xf4 -> false;
            case 0xf5 -> true;
            default -> throw new TxDecodingException("expected a boolean at " + (pos - 1));
        };
    }

    /** Consumes a {@code null}. */
    public void readNull() {
        if (next() != 0xf6) {
            throw new TxDecodingException("expected null at " + (pos - 1));
        }
    }

    /** @return the byte range of the next data item (tags included), and moves past it */
    public CborSlice readItem() {
        int start = pos;
        int stop;
        try {
            stop = CborItems.skip(data, pos);
        } catch (IllegalArgumentException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
        if (stop > end) {
            throw new TxDecodingException("item at " + start + " runs past its container");
        }
        pos = stop;
        return new CborSlice(start, stop);
    }

    /** @return a copy of the bytes of {@code slice}, a range of this reader's input */
    public byte[] copy(CborSlice slice) {
        return slice.copy(data);
    }

    /** Skips the next data item. */
    public void skip() {
        readItem();
    }

    private long containerHeader(int major, String what) {
        int initial = next();
        expectMajor(initial, major, what);
        if ((initial & 0x1f) == 31) {
            return INDEFINITE;
        }
        return argument(initial & 0x1f);
    }

    private long argument(int info) {
        BigInteger value = unsignedArgument(info);
        if (value.bitLength() > 31) {
            throw new TxDecodingException("length " + value + " too large at " + pos);
        }
        return value.longValue();
    }

    private BigInteger unsignedArgument(int info) {
        if (info < 24) {
            return BigInteger.valueOf(info);
        }
        int bytes = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new TxDecodingException("unsupported additional information " + info + " at " + pos);
        };
        if (bytes > end - pos) {
            throw new TxDecodingException("truncated integer at " + pos);
        }
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value = (value << 8) | (data[pos++] & 0xff);
        }
        BigInteger result = BigInteger.valueOf(value);
        return value < 0 ? result.add(TWO_64) : result;
    }

    private int peek() {
        if (pos >= end) {
            throw new TxDecodingException("unexpected end of CBOR at " + pos);
        }
        return data[pos] & 0xff;
    }

    private int next() {
        int b = peek();
        pos++;
        return b;
    }

    private void expectMajor(int initial, int major, String what) {
        if (initial >>> 5 != major) {
            throw new TxDecodingException("expected " + what + " at " + (pos - 1) + ", found initial byte 0x"
                    + Integer.toHexString(initial));
        }
    }
}
