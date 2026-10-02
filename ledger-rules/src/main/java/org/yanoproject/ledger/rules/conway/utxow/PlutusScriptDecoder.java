package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;

import org.yanoproject.ledger.rules.conway.tx.PlutusData;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;

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
 *       ({@code decodeData}: at most 64-byte byte-string chunks, tags 121–127, 1280–1400 and 102, bignums 2/3;
 *       bytes after the item are ignored, {@code deserialiseOrFail}), and {@code Value} (ascending currency symbols
 *       and token names of at most 32 bytes, no empty inner map, non-zero signed 128-bit quantities).</li>
 * </ol>
 *
 * <p>The program's Plutus Core version is <em>not</em> checked here: {@code plcVersionsAvailableIn} is checked when
 * the script is run ({@code mkTermToEvaluate}, {@code PlutusLedgerApi/Common/Eval.hs:118-122}), which is a phase-2
 * failure. Terms and {@code Data} constants are decoded without recursion; a constant of another type nested beyond
 * the thread's stack cannot be judged and fails closed ({@link IllegalStateException}).</p>
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

    /**
     * The {@code PlutusBinary} as a PlutusV1/V2 script runs: its leading CBOR item. Bytes after it are allowed for
     * PlutusV1/V2 ({@code deserialiseScript}: {@code RemainderError} only from PlutusV3,
     * {@code PlutusLedgerApi/Common/SerialisedScript.hs:261-264}) and ignored when the script is decoded to run.
     *
     * @return the leading CBOR item, or {@code script} itself when nothing follows it
     */
    public static byte[] leadingItem(byte[] script) {
        int end = CborSpan.skip(script, 0, script.length);
        return end == script.length ? script : Arrays.copyOf(script, end);
    }

    /**
     * The program's Plutus Core version, from the flat program's header (three naturals).
     *
     * @return {@code [major, minor, patch]}, or empty when the binary does not hold a flat program header
     */
    public static Optional<List<BigInteger>> programVersion(byte[] script) {
        try {
            Bits in = new Bits(envelope(script).byteString());
            return Optional.of(List.of(in.natural(), in.natural(), in.natural()));
        } catch (Malformed | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ CBOR envelope

    private static void decode(int language, byte[] script, int pv) {
        if (language < 1 || language > 3) {
            throw new IllegalArgumentException("not a Plutus language: " + language);
        }
        CborSpan envelope = envelope(script);
        if (language == 3 && envelope.length() != script.length) {
            throw new Malformed("RemainderError: " + (script.length - envelope.length()) + " bytes after the script");
        }
        new Program(envelope.byteString(), language, pv).decode();
    }

    /** @return the {@code PlutusBinary}'s leading item, a definite-length byte string ({@code decodeBytes}) */
    private static CborSpan envelope(byte[] script) {
        if (script.length == 0) {
            throw new Malformed("unexpected end of CBOR");
        }
        int head = script[0] & 0xff;
        if (head >>> 5 != 2 || (head & 0x1f) == 31) {
            throw new Malformed("the script is not a definite-length CBOR byte string");
        }
        try {
            return CborSpan.at(script, 0);
        } catch (CborRuntimeException e) {
            throw new Malformed(e.getMessage());
        }
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

    /**
     * {@code Data} constants: {@link PlutusData#validateFirst}, plutus-core's {@code decodeData} through serialise's
     * {@code deserialiseOrFail} ({@code FlatViaSerialise}), which ignores bytes after the item.
     */
    private static final class Data {

        static void decode(byte[] bytes) {
            try {
                PlutusData.validateFirst(bytes);
            } catch (TxDecodingException e) {
                throw new Malformed(e.getMessage());
            }
        }
    }

    /** A decoding failure: Haskell's {@code ScriptDecodeError}. */
    private static final class Malformed extends RuntimeException {
        Malformed(String message) {
            super(message, null, false, false);
        }
    }
}
