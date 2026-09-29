package org.yanoproject.ledger.rules.conway.utxow;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Haskell's phase-1 Plutus script check, {@code isValidPlutusScript} → {@code decodePlutusRunnable} →
 * plutus-ledger-api {@code deserialiseScript ll pv} ({@code PlutusLedgerApi/Common/SerialisedScript.hs}, plutus
 * {@code 1.65.0.0}, the version cardano-node 11.1.2 is built with), reimplemented so that the verdict does not depend
 * on the phase-2 evaluator's decoder.
 *
 * <ol>
 *   <li>The {@code PlutusBinary} is a CBOR byte string ({@code decodeBytes}: definite length only); bytes after it
 *       are allowed for PlutusV1/V2 and a {@code RemainderError} for PlutusV3.</li>
 *   <li>Its content is a flat-encoded program ({@code decodeViaFlatWith}, {@code unflatWith}: the value, a filler of
 *       zero bits and a one bit ending on a byte boundary, nothing after): a version of three naturals, then the
 *       term ({@code UntypedPlutusCore/Core/Instance/Flat.hs}): 4-bit tags 0 variable (a {@code Word64} de Bruijn
 *       index), 1 delay, 2 lambda (binder encoded as nothing), 3 application, 4 constant, 5 force, 6 error, 7 builtin
 *       (7-bit tag), 8 {@code constr} ({@code Word64} tag, list of terms) and 9 {@code case} (term, list of terms);
 *       8 and 9 need program version 1.1.0 or later.</li>
 *   <li>A builtin must be available to the language at the protocol version ({@code builtinsAvailableIn},
 *       {@code PlutusLedgerApi/Common/Versions.hs}); from protocol version 11 a constant's type may have at most 32
 *       nodes ({@code defaultUniSize}) and a {@code constr} at most 1024 fields ({@code maxBoundsByPV}).</li>
 *   <li>Constants ({@code PlutusCore/FlatInstances.hs}, {@code Default/Universe.hs}): a list of 4-bit type tags
 *       (0 integer, 1 bytestring, 2 string, 3 unit, 4 bool, 5 list, 6 pair, 7 application, 8 data, 9–11 BLS12-381
 *       types, which do not flat-decode, 12 array, 13 value) forming exactly one well-kinded type of kind
 *       {@code *}, then the value: zigzag integers, filler-aligned chunked byte strings, UTF-8 text, a bit for bools,
 *       nothing for unit, bit-prefixed lists (arrays too), pairs, {@code Data} as CBOR inside a byte string
 *       ({@code decodeData}: at most 64-byte byte-string chunks, tags 121–127, 1280–1400 and 102, bignums 2/3, no
 *       trailing bytes), and {@code Value} (ascending currency symbols and token names of at most 32 bytes, no empty
 *       inner map, non-zero signed 128-bit quantities).</li>
 * </ol>
 *
 * <p>The program's Plutus Core version is <em>not</em> checked here: {@code plcVersionsAvailableIn} is checked when
 * the script is run ({@code mkTermToEvaluate}, {@code PlutusLedgerApi/Common/Eval.hs:118-122}), which is a phase-2
 * failure. Terms are decoded without recursion; a constant nested beyond the thread's stack cannot be judged and
 * fails closed ({@link IllegalStateException}).</p>
 */
public final class PlutusScriptDecoder {

    private static final int MAX_BUILTIN = 100;
    private static final BigInteger QUANTITY_MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    private static final BigInteger QUANTITY_MIN = BigInteger.ONE.shiftLeft(127).negate();

    private PlutusScriptDecoder() {
    }

    /**
     * @param language      1 PlutusV1, 2 PlutusV2, 3 PlutusV3
     * @param script        the {@code PlutusBinary}
     * @param protocolMajor the protocol major version
     * @return why Haskell would not deserialise the script, or empty when it would
     * @throws IllegalStateException when a constant is nested too deeply to judge
     */
    public static Optional<String> check(int language, byte[] script, int protocolMajor) {
        try {
            decode(language, script, protocolMajor);
            return Optional.empty();
        } catch (Malformed e) {
            return Optional.of(e.getMessage());
        } catch (StackOverflowError e) {
            throw new IllegalStateException("a Plutus script constant is nested too deeply to judge", e);
        }
    }

    /** @return true when the script deserialises */
    public static boolean isWellFormed(int language, byte[] script, int protocolMajor) {
        return check(language, script, protocolMajor).isEmpty();
    }

    // ------------------------------------------------------------------ CBOR envelope

    private static void decode(int language, byte[] script, int pv) {
        if (language < 1 || language > 3) {
            throw new IllegalArgumentException("not a Plutus language: " + language);
        }
        Cbor cbor = new Cbor(script, 0, script.length);
        int head = cbor.peek();
        if (head >>> 5 != 2 || (head & 0x1f) == 31) {
            throw new Malformed("the script is not a definite-length CBOR byte string");
        }
        byte[] flat = cbor.bytes();
        if (language == 3 && !cbor.atEnd()) {
            throw new Malformed("RemainderError: " + (script.length - cbor.pos) + " bytes after the script");
        }
        new Program(flat, language, pv).decode();
    }

    // ------------------------------------------------------------------ flat program

    /** One flat decoding run. */
    private static final class Program {
        private final Bits in;
        private final int language;
        private final int pv;
        private boolean v110;

        Program(byte[] flat, int language, int pv) {
            this.in = new Bits(flat);
            this.language = language;
            this.pv = pv;
        }

        void decode() {
            BigInteger major = in.natural();
            BigInteger minor = in.natural();
            in.natural(); // patch
            v110 = major.compareTo(BigInteger.ONE) > 0
                    || (major.equals(BigInteger.ONE) && minor.signum() > 0);
            terms();
            in.filler();
            if (!in.atEnd()) {
                throw new Malformed("TooMuchSpace: bytes after the program");
            }
        }

        /** A pending item of the iterative term decoder. */
        private static final class ListFrame {
            final boolean constr;
            int fields;

            ListFrame(boolean constr) {
                this.constr = constr;
            }
        }

        /** Decodes one term, iteratively: {@code null} on the stack is a term, a {@link ListFrame} a list. */
        private void terms() {
            Deque<Object> stack = new ArrayDeque<>();
            stack.push(TERM);
            while (!stack.isEmpty()) {
                Object top = stack.pop();
                if (top instanceof ListFrame list) {
                    if (in.bit()) {
                        list.fields++;
                        stack.push(list);
                        stack.push(TERM);
                    } else if (list.constr && pv >= 11 && list.fields > 1024) {
                        throw new Malformed("constr with " + list.fields + " fields is not available in protocol "
                                + "version " + pv);
                    }
                    continue;
                }
                int tag = in.bits(4);
                switch (tag) {
                    case 0 -> in.word64();                       // Var: de Bruijn index
                    case 1, 2, 5 -> stack.push(TERM);           // Delay, LamAbs (binder: nothing), Force
                    case 3 -> {                                  // Apply
                        stack.push(TERM);
                        stack.push(TERM);
                    }
                    case 4 -> constant();
                    case 6 -> {                                  // Error
                    }
                    case 7 -> builtin(in.bits(7));
                    case 8 -> {
                        requireV110("constr");
                        in.word64();
                        stack.push(new ListFrame(true));
                    }
                    case 9 -> {
                        requireV110("case");
                        stack.push(new ListFrame(false));
                        stack.push(TERM);
                    }
                    default -> throw new Malformed("Unknown term constructor tag: " + tag);
                }
            }
        }

        private void requireV110(String what) {
            if (!v110) {
                throw new Malformed("'" + what + "' is not allowed before version 1.1.0");
            }
        }

        private void builtin(int tag) {
            if (tag > MAX_BUILTIN) {
                throw new Malformed("Failed to decode builtin tag, got: " + tag);
            }
            if (!builtinAvailable(language, pv, tag)) {
                throw new Malformed("Builtin function " + tag + " is not available in PlutusV" + language
                        + " at protocol version " + pv);
            }
        }

        private void constant() {
            List<Integer> tags = new ArrayList<>();
            while (in.bit()) {
                tags.add(in.bits(4));
            }
            int[] cursor = {0};
            Type type = Type.decode(tags, cursor);
            if (cursor[0] != tags.size()) {
                throw new Malformed("Failed to decode a universe");
            }
            if (!type.isStar()) {
                throw new Malformed("A non-star type can't have a value to decode");
            }
            value(type);
            if (pv >= 11 && type.size() > 32) {
                throw new Malformed("Constant of type size " + type.size() + " is not available in protocol version "
                        + pv);
            }
        }

        private void value(Type type) {
            switch (type.tag) {
                case 0 -> in.natural();                          // integer (zigzag)
                case 1 -> in.byteString();
                case 2 -> utf8(in.byteString());
                case 3 -> {                                      // unit
                }
                case 4 -> in.bit();
                case 8 -> Data.decode(in.byteString());
                case 9, 10, 11 -> throw new Malformed("Flat decoding is not supported for BLS12-381 objects");
                case 13 -> value();
                case 7 -> {
                    Type f = type.function;
                    if (f.tag == 5 || f.tag == 12) {             // list a, array a
                        while (in.bit()) {
                            value(type.argument);
                        }
                    } else {                                     // (pair a) b
                        value(f.argument);
                        value(type.argument);
                    }
                }
                default -> throw new Malformed("A non-star type can't have a value to decode");
            }
        }

        /** {@code Flat Value} ({@code PlutusCore/Value.hs:209-219}, {@code buildValueWith}). */
        private void value() {
            byte[] previousCurrency = null;
            while (in.bit()) {
                byte[] currency = key();
                if (previousCurrency != null && Arrays.compareUnsigned(previousCurrency, currency) >= 0) {
                    throw new Malformed("Value Flat decoder: currency symbols not strictly ascending");
                }
                previousCurrency = currency;
                byte[] previousToken = null;
                int tokens = 0;
                while (in.bit()) {
                    byte[] token = key();
                    BigInteger quantity = zigzag(in.natural());
                    if (quantity.compareTo(QUANTITY_MIN) < 0 || quantity.compareTo(QUANTITY_MAX) > 0) {
                        throw new Malformed("Quantity out of signed 128-bit integer bounds");
                    }
                    if (previousToken != null && Arrays.compareUnsigned(previousToken, token) >= 0) {
                        throw new Malformed("Value Flat decoder: token names not strictly ascending");
                    }
                    if (quantity.signum() == 0) {
                        throw new Malformed("Value Flat decoder: zero quantity");
                    }
                    previousToken = token;
                    tokens++;
                }
                if (tokens == 0) {
                    throw new Malformed("Value Flat decoder: empty inner map");
                }
            }
        }

        private byte[] key() {
            byte[] key = in.byteString();
            if (key.length > 32) {
                throw new Malformed("Invalid Value key of " + key.length + " bytes");
            }
            return key;
        }

        private static final Object TERM = new Object();
    }

    private static BigInteger zigzag(BigInteger n) {
        return n.testBit(0) ? n.add(BigInteger.ONE).shiftRight(1).negate() : n.shiftRight(1);
    }

    private static void utf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            throw new Malformed("Input contains invalid UTF-8 data");
        }
    }

    /**
     * {@code builtinsAvailableIn ll pv} (Versions.hs:320-330): PlutusV1 has batch 1 (tags 0–50) from protocol version
     * 5 and everything from 11; PlutusV2 batches 1–2 (0–51) from 7, batch 3 (52–53) from 8, batch 4b (73–74) from
     * 10, and batches 4a (54–72), 5 (75–86) and 6 (87–100) from 11; PlutusV3 batches 1–4 (0–74) from 9, batch 5
     * from 10 and batch 6 from 11.
     */
    static boolean builtinAvailable(int language, int pv, int tag) {
        if (tag < 0 || tag > MAX_BUILTIN) {
            return false;
        }
        if (pv >= 11) {
            return true;
        }
        return switch (language) {
            case 1 -> tag <= 50;
            case 2 -> tag <= 51 || (pv >= 8 && tag <= 53) || (pv >= 10 && (tag == 73 || tag == 74));
            case 3 -> tag <= 74 || (pv >= 10 && tag <= 86);
            default -> false;
        };
    }

    // ------------------------------------------------------------------ types

    /** A constant's type: a leaf (tags 0–4, 8–11, 13), a type operator (5 list, 6 pair, 12 array) or an application. */
    private static final class Type {
        final int tag;
        final Type function;
        final Type argument;

        Type(int tag, Type function, Type argument) {
            this.tag = tag;
            this.function = function;
            this.argument = argument;
        }

        /** {@code withDecodedUni}: one type from the tags, checking kinds ({@code withApplicable}). */
        static Type decode(List<Integer> tags, int[] cursor) {
            if (cursor[0] >= tags.size()) {
                throw new Malformed("Failed to decode a universe");
            }
            int tag = tags.get(cursor[0]++);
            if (tag == 7) {
                Type f = decode(tags, cursor);
                Type a = decode(tags, cursor);
                if (f.arity() < 1 || !a.isStar()) {
                    throw new Malformed("Failed to decode a universe");
                }
                return new Type(7, f, a);
            }
            if (tag > 13) {
                throw new Malformed("Failed to decode a universe");
            }
            return new Type(tag, null, null);
        }

        /** @return how many more type arguments this type takes (0 for kind {@code *}) */
        int arity() {
            return switch (tag) {
                case 5, 12 -> 1;
                case 6 -> 2;
                case 7 -> function.arity() - 1;
                default -> 0;
            };
        }

        boolean isStar() {
            return arity() == 0;
        }

        /** {@code defaultUniSize}. */
        int size() {
            return tag == 7 ? function.size() + argument.size() + 1 : 1;
        }
    }

    // ------------------------------------------------------------------ flat bits

    /** A most-significant-bit-first reader over the flat bytes. */
    private static final class Bits {
        private final byte[] data;
        private int pos;
        private int used;

        Bits(byte[] data) {
            this.data = data;
        }

        boolean bit() {
            if (pos >= data.length) {
                throw new Malformed("NotEnoughSpace");
            }
            boolean b = (data[pos] & (0x80 >>> used)) != 0;
            if (++used == 8) {
                used = 0;
                pos++;
            }
            return b;
        }

        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | (bit() ? 1 : 0);
            }
            return v;
        }

        int byte8() {
            return bits(8);
        }

        /** {@code dUnsigned} for an unbounded type (Natural, and Integer before zigzag): 7-bit groups, LSB first. */
        BigInteger natural() {
            BigInteger v = BigInteger.ZERO;
            int shift = 0;
            while (true) {
                int b = byte8();
                v = v.or(BigInteger.valueOf(b & 0x7f).shiftLeft(shift));
                if ((b & 0x80) == 0) {
                    return v;
                }
                shift += 7;
            }
        }

        /** {@code dWord64}: at most ten groups, the tenth holding at most one bit and no continuation. */
        void word64() {
            for (int group = 0; group < 10; group++) {
                int b = byte8();
                if (group == 9) {
                    if ((b & 0x80) != 0 || (b & 0x7f) > 1) {
                        throw new Malformed("Unexpected extra byte in unsigned integer");
                    }
                    return;
                }
                if ((b & 0x80) == 0) {
                    return;
                }
            }
        }

        /** {@code dFiller}: zero bits then a one bit. */
        void filler() {
            while (!bit()) {
                // keep reading
            }
        }

        /** {@code dByteString}: a filler, then byte-aligned chunks each preceded by its length, ending with 0. */
        byte[] byteString() {
            filler();
            if (used != 0) {
                throw new Malformed("usedBits /= 0");
            }
            List<byte[]> chunks = new ArrayList<>();
            int total = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new Malformed("NotEnoughSpace");
                }
                int n = data[pos++] & 0xff;
                if (n == 0) {
                    break;
                }
                if (pos + n > data.length) {
                    throw new Malformed("NotEnoughSpace");
                }
                chunks.add(Arrays.copyOfRange(data, pos, pos + n));
                pos += n;
                total += n;
            }
            byte[] out = new byte[total];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, out, offset, chunk.length);
                offset += chunk.length;
            }
            return out;
        }

        boolean atEnd() {
            return pos == data.length && used == 0;
        }
    }

    // ------------------------------------------------------------------ Data

    /** {@code decodeData} (PlutusCore/Data.hs:209-300) over cborg, then no trailing bytes ({@code deserialiseOrFail}). */
    private static final class Data {

        static void decode(byte[] bytes) {
            Cbor cbor = new Cbor(bytes, 0, bytes.length);
            data(cbor);
            if (!cbor.atEnd()) {
                throw new Malformed("Data: trailing bytes");
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
                        throw new Malformed("Data: malformed tag");
                    }
                    c.pos++;
                    BigInteger tag = c.argumentOf(info);
                    // cborg reports TypeInteger only for the one-byte heads c2/c3; a longer tag head is TypeTag,
                    // and decodeConstr rejects tags 2 and 3.
                    if (head == 0xc2 || head == 0xc3) {
                        if (c.peek() >>> 5 != 2) {
                            throw new Malformed("Bignum must contain a byte string");
                        }
                        boundedBytes(c);
                    } else if (tag.equals(BigInteger.valueOf(102))) {
                        long n = c.containerHeader(4);
                        if (c.peek() >>> 5 != 0) {
                            throw new Malformed("Data: constructor index is not a Word64");
                        }
                        c.argument();
                        list(c);
                        if (n == Cbor.INDEFINITE) {
                            if (c.peek() != 0xff) {
                                throw new Malformed("Expected exactly two elements");
                            }
                            c.pos++;
                        } else if (n != 2) {
                            throw new Malformed("Expected exactly two elements");
                        }
                    } else if ((tag.compareTo(BigInteger.valueOf(121)) >= 0 && tag.compareTo(BigInteger.valueOf(128)) < 0)
                            || (tag.compareTo(BigInteger.valueOf(1280)) >= 0
                            && tag.compareTo(BigInteger.valueOf(1401)) < 0)) {
                        list(c);
                    } else {
                        throw new Malformed("Unrecognized tag " + tag);
                    }
                }
                default -> throw new Malformed("Data: unrecognized value of major type " + major);
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
                        throw new Malformed("Data: an indefinite byte string chunk must be a definite byte string");
                    }
                    bounded(c.bytes());
                }
                c.pos++;
            } else {
                bounded(c.bytes());
            }
        }

        private static void bounded(byte[] bytes) {
            if (bytes.length > 64) {
                throw new Malformed("ByteString exceeds 64 bytes");
            }
        }
    }

    // ------------------------------------------------------------------ CBOR

    /** A minimal CBOR reader for the script envelope and {@code Data}. */
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
                throw new Malformed("unexpected end of CBOR");
            }
            return data[pos] & 0xff;
        }

        /** Reads an integer head (major 0 or 1) and returns its argument. */
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
                default -> throw new Malformed("unsupported CBOR additional information " + info);
            };
            if (pos + n > end) {
                throw new Malformed("truncated CBOR integer");
            }
            BigInteger v = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                v = v.shiftLeft(8).or(BigInteger.valueOf(data[pos++] & 0xff));
            }
            return v;
        }

        /** A definite byte string. */
        byte[] bytes() {
            int head = peek();
            if (head >>> 5 != 2 || (head & 0x1f) == 31) {
                throw new Malformed("expected a definite byte string");
            }
            pos++;
            BigInteger length = argumentOf(head & 0x1f);
            if (length.compareTo(BigInteger.valueOf(end - pos)) > 0) {
                throw new Malformed("byte string runs past the input");
            }
            byte[] out = Arrays.copyOfRange(data, pos, pos + length.intValue());
            pos += out.length;
            return out;
        }

        long containerHeader(int major) {
            int head = peek();
            if (head >>> 5 != major) {
                throw new Malformed("unexpected CBOR major type " + (head >>> 5));
            }
            pos++;
            if ((head & 0x1f) == 31) {
                return INDEFINITE;
            }
            BigInteger n = argumentOf(head & 0x1f);
            if (n.bitLength() > 31) {
                throw new Malformed("container too large");
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

    /** A decoding failure: Haskell's {@code ScriptDecodeError}. */
    private static final class Malformed extends RuntimeException {
        Malformed(String message) {
            super(message, null, false, false);
        }
    }
}
