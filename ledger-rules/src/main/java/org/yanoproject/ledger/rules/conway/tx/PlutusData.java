package org.yanoproject.ledger.rules.conway.tx;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Plutus {@code Data} well-formedness, as plutus-core's {@code decodeData} (plutus 1.65.0.0,
 * {@code PlutusCore/Data.hs:209-300}) over cborg, followed by "no trailing bytes" ({@code deserialiseOrFail}).
 *
 * <p>The ledger decodes every {@code Data} of a transaction with it: witness-set datums and redeemer data
 * ({@code DecCBOR (PlutusData era) = fromPlainDecoder Cborg.decode}, cardano-ledger-core Plutus/Data.hs:99-103) and
 * inline datums ({@code makeBinaryData}, :220-239, after the definite tag-24 byte string of
 * {@code decodeNestedCborBytes}); Plutus constants of type {@code data} use it too. The rules: integers (major 0 or
 * 1, or bignums tagged 2/3 whose byte string follows the byte-string rule), byte strings definite of at most 64 bytes
 * or indefinite with definite chunks of at most 64 bytes each ({@code decodeBoundedBytes}), lists and maps definite or
 * indefinite, constructors tagged 121–127, 1280–1400 (a list of fields) or 102 ({@code [Word64, fields]}); anything
 * else (text, floats, simple values, other tags) is refused.</p>
 */
public final class PlutusData {

    private static final int MAX_CHUNK = 64;

    private PlutusData() {
    }

    /**
     * @param bytes the CBOR of one {@code Data} item
     * @throws TxDecodingException when plutus-core would not decode it
     */
    public static void validate(byte[] bytes) {
        Cbor cbor = new Cbor(bytes, 0, bytes.length);
        data(cbor);
        if (!cbor.atEnd()) {
            throw new TxDecodingException("Data: trailing bytes");
        }
    }

    private static void data(Cbor c) {
        int head = c.peek();
        int major = head >>> 5;
        int info = head & 0x1f;
        switch (major) {
            case 0, 1 -> c.argument();
            case 2 -> boundedBytes(c);
            case 4 -> list(c);
            case 5 -> {
                long n = c.containerHeader(5);
                for (long i = 0; c.more(n, i); i++) {
                    data(c);
                    data(c);
                }
            }
            case 6 -> {
                if (info == 31) {
                    throw new TxDecodingException("Data: malformed tag");
                }
                c.pos++;
                BigInteger tag = c.argumentOf(info);
                // cborg reports TypeInteger only for the one-byte heads c2/c3; a longer tag head is TypeTag, and
                // decodeConstr rejects tags 2 and 3.
                if (head == 0xc2 || head == 0xc3) {
                    if (c.peek() >>> 5 != 2) {
                        throw new TxDecodingException("Bignum must contain a byte string");
                    }
                    boundedBytes(c);
                } else if (tag.equals(BigInteger.valueOf(102))) {
                    long n = c.containerHeader(4);
                    if (c.peek() >>> 5 != 0) {
                        throw new TxDecodingException("Data: constructor index is not a Word64");
                    }
                    c.argument();
                    list(c);
                    if (n == Cbor.INDEFINITE) {
                        if (c.peek() != 0xff) {
                            throw new TxDecodingException("Expected exactly two elements");
                        }
                        c.pos++;
                    } else if (n != 2) {
                        throw new TxDecodingException("Expected exactly two elements");
                    }
                } else if ((tag.compareTo(BigInteger.valueOf(121)) >= 0 && tag.compareTo(BigInteger.valueOf(128)) < 0)
                        || (tag.compareTo(BigInteger.valueOf(1280)) >= 0
                        && tag.compareTo(BigInteger.valueOf(1401)) < 0)) {
                    list(c);
                } else {
                    throw new TxDecodingException("Unrecognized tag " + tag);
                }
            }
            default -> throw new TxDecodingException("Data: unrecognized value of major type " + major);
        }
    }

    private static void list(Cbor c) {
        long n = c.containerHeader(4);
        for (long i = 0; c.more(n, i); i++) {
            data(c);
        }
    }

    /** {@code decodeBoundedBytes} / {@code decodeBoundedBytesIndef}: every chunk at most 64 bytes. */
    private static void boundedBytes(Cbor c) {
        int head = c.peek();
        if ((head & 0x1f) == 31) {
            c.pos++;
            while (c.peek() != 0xff) {
                int chunk = c.peek();
                if (chunk >>> 5 != 2 || (chunk & 0x1f) == 31) {
                    throw new TxDecodingException("Data: an indefinite byte string chunk must be a definite byte "
                            + "string");
                }
                bounded(c.bytes());
            }
            c.pos++;
        } else {
            bounded(c.bytes());
        }
    }

    private static void bounded(byte[] bytes) {
        if (bytes.length > MAX_CHUNK) {
            throw new TxDecodingException("ByteString exceeds 64 bytes");
        }
    }

    /** A minimal CBOR reader for {@code Data}. */
    private static final class Cbor {
        static final long INDEFINITE = -1;
        private final byte[] data;
        private final int end;
        int pos;

        Cbor(byte[] data, int start, int end) {
            this.data = data;
            this.pos = start;
            this.end = end;
        }

        boolean atEnd() {
            return pos >= end;
        }

        int peek() {
            if (pos >= end) {
                throw new TxDecodingException("Data: unexpected end of CBOR");
            }
            return data[pos] & 0xff;
        }

        BigInteger argument() {
            int head = peek();
            pos++;
            return argumentOf(head & 0x1f);
        }

        BigInteger argumentOf(int info) {
            if (info < 24) {
                return BigInteger.valueOf(info);
            }
            int n = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw new TxDecodingException("Data: unsupported CBOR additional information " + info);
            };
            if (pos + n > end) {
                throw new TxDecodingException("Data: truncated CBOR integer");
            }
            BigInteger v = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                v = v.shiftLeft(8).or(BigInteger.valueOf(data[pos++] & 0xff));
            }
            return v;
        }

        byte[] bytes() {
            int head = peek();
            if (head >>> 5 != 2 || (head & 0x1f) == 31) {
                throw new TxDecodingException("Data: expected a definite byte string");
            }
            pos++;
            BigInteger length = argumentOf(head & 0x1f);
            if (length.compareTo(BigInteger.valueOf(end - pos)) > 0) {
                throw new TxDecodingException("Data: byte string runs past the input");
            }
            byte[] out = Arrays.copyOfRange(data, pos, pos + length.intValue());
            pos += out.length;
            return out;
        }

        long containerHeader(int major) {
            int head = peek();
            if (head >>> 5 != major) {
                throw new TxDecodingException("Data: unexpected CBOR major type " + (head >>> 5));
            }
            pos++;
            if ((head & 0x1f) == 31) {
                return INDEFINITE;
            }
            BigInteger n = argumentOf(head & 0x1f);
            if (n.bitLength() > 31) {
                throw new TxDecodingException("Data: container too large");
            }
            return n.longValue();
        }

        boolean more(long n, long i) {
            if (n != INDEFINITE) {
                return i < n;
            }
            if (peek() == 0xff) {
                pos++;
                return false;
            }
            return true;
        }
    }
}
