package org.yanoproject.ledger.rules.conway.tx;

import java.math.BigInteger;

/**
 * Plutus {@code Data} well-formedness, as plutus-core's {@code decodeData} (plutus 1.65.0.0,
 * {@code PlutusCore/Data.hs:209-300}) over cborg.
 *
 * <p>The rules: integers (major 0 or 1, or bignums tagged 2/3 whose byte string follows the byte-string rule), byte
 * strings definite of at most 64 bytes or indefinite with definite chunks of at most 64 bytes each
 * ({@code decodeBoundedBytes}), lists and maps definite or indefinite, constructors tagged 121–127, 1280–1400 (a list
 * of fields) or 102 ({@code [Word64, fields]}); anything else (text, floats, simple values, other tags) is refused.</p>
 *
 * <p>Two entry points, as the callers decode differently:</p>
 * <ul>
 *   <li>{@link #validate}: the bytes are one {@code Data} and nothing else. The ledger's datums and redeemer data are
 *       items of the transaction ({@code DecCBOR (PlutusData era) = fromPlainDecoder Cborg.decode}, cardano-ledger-core
 *       Plutus/Data.hs:99-103), and an inline datum is decoded with {@code decodeFull'}, which refuses leftover bytes
 *       ({@code makeBinaryData}, :154-173, after the definite tag-24 byte string of {@code decodeNestedCborBytes}).</li>
 *   <li>{@link #validateFirst}: a {@code Data} constant of a Plutus script ({@code FlatViaSerialise}), decoded with
 *       serialise's {@code deserialiseOrFail} (Codec/Serialise.hs:134-143), which stops after the first item and
 *       ignores whatever follows it.</li>
 * </ul>
 *
 * <p>The walk keeps an explicit stack, so {@code Data} of any nesting depth is judged in linear time on any
 * thread.</p>
 */
public final class PlutusData {

    private static final int MAX_CHUNK = 64;
    private static final BigInteger TAG_GENERAL_CONSTR = BigInteger.valueOf(102);

    private PlutusData() {
    }

    /**
     * @param bytes the CBOR of one {@code Data} item, nothing after it
     * @throws TxDecodingException when plutus-core would not decode it
     */
    public static void validate(byte[] bytes) {
        CborCursor cursor = new CborCursor(bytes, 0, bytes.length);
        data(cursor);
        if (!cursor.atEnd()) {
            throw new TxDecodingException("Data: trailing bytes");
        }
    }

    /**
     * @param bytes the CBOR of a {@code Data} item, possibly followed by other bytes, which are ignored
     * @throws TxDecodingException when plutus-core would not decode the leading item
     */
    public static void validateFirst(byte[] bytes) {
        data(new CborCursor(bytes, 0, bytes.length));
    }

    /** Reads one {@code Data} item. */
    private static void data(CborCursor c) {
        CborCursor.Containers open = new CborCursor.Containers();
        do {
            item(c, open);
        } while (open.next(c));
    }

    /** Reads one item: a leaf in full, or a container's head with a frame for its items. */
    private static void item(CborCursor c, CborCursor.Containers open) {
        int head = c.peek();
        int major = head >>> 5;
        switch (major) {
            case 0, 1 -> c.argument(c.next());
            case 2 -> boundedBytes(c);
            case 4 -> open.open(4, c.containerHeader(4));
            case 5 -> open.open(5, c.containerHeader(5));
            case 6 -> {
                if ((head & 0x1f) == 31) {
                    throw new TxDecodingException("Data: malformed tag");
                }
                BigInteger tag = c.argument(c.next());
                // cborg reports TypeInteger only for the one-byte heads c2/c3; a longer tag head is TypeTag, and
                // decodeConstr rejects tags 2 and 3.
                if (head == 0xc2 || head == 0xc3) {
                    if (c.peek() >>> 5 != 2) {
                        throw new TxDecodingException("Bignum must contain a byte string");
                    }
                    boundedBytes(c);
                } else if (tag.equals(TAG_GENERAL_CONSTR)) {
                    long n = c.containerHeader(4);
                    if (c.peek() >>> 5 != 0) {
                        throw new TxDecodingException("Data: constructor index is not a Word64");
                    }
                    c.argument(c.next());
                    // [index, fields]: the index is read here, the fields list on top of the sealed array
                    open.seal(n, 2);
                    open.open(4, c.containerHeader(4));
                } else if ((tag.compareTo(BigInteger.valueOf(121)) >= 0 && tag.compareTo(BigInteger.valueOf(128)) < 0)
                        || (tag.compareTo(BigInteger.valueOf(1280)) >= 0
                        && tag.compareTo(BigInteger.valueOf(1401)) < 0)) {
                    open.open(4, c.containerHeader(4));
                } else {
                    throw new TxDecodingException("Unrecognized tag " + tag);
                }
            }
            default -> throw new TxDecodingException("Data: unrecognized value of major type " + major);
        }
    }

    /** {@code decodeBoundedBytes} / {@code decodeBoundedBytesIndef}: every chunk at most 64 bytes. */
    private static void boundedBytes(CborCursor c) {
        if ((c.peek() & 0x1f) == 31) {
            c.next();
            while (!c.atBreak()) {
                int chunk = c.peek();
                if (chunk >>> 5 != 2 || (chunk & 0x1f) == 31) {
                    throw new TxDecodingException("Data: an indefinite byte string chunk must be a definite byte "
                            + "string");
                }
                bounded(c.definiteString(2));
            }
        } else {
            bounded(c.definiteString(2));
        }
    }

    private static void bounded(byte[] bytes) {
        if (bytes.length > MAX_CHUNK) {
            throw new TxDecodingException("ByteString exceeds 64 bytes");
        }
    }
}
