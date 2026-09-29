package org.yanoproject.scalusbridge;

import java.util.Arrays;
import java.util.Optional;

/**
 * Finds an integer in a transaction body that Scalus cannot decode.
 *
 * <p>The ledger's coins, slots and indexes are unsigned 64-bit integers on the wire (Haskell decodes a {@code Coin}
 * with {@code decodeWord64}, cardano-ledger {@code Coin.hs:108-112}; CDDL {@code coin = uint}). Scalus decodes them as
 * a signed {@code Long}, so an integer in {@code [2^63, 2^64)} makes its transaction decoder throw
 * ("Expected Long but got OverLong"). No real network holds such a coin (the supply is below 2^56 lovelace), but a
 * treasury withdrawal or a slot can be stated that large, and Haskell accepts it. The evaluator checks the body with
 * this scan before handing the transaction to Scalus and fails closed with an explicit engine failure.</p>
 *
 * <p>Only the body is scanned: it holds no Plutus data (inline datums and scripts are byte strings there), so every
 * integer in it is a ledger integer. A negative integer below {@code -2^63} (only {@code mint} is signed) is reported
 * too.</p>
 *
 * <p><b>Scope limits</b> (both theoretical):</p>
 * <ul>
 *   <li>A few body integers are Haskell {@code Integer}s, not {@code Word64}s: the numerator and denominator of a
 *       rational inside a {@code ParameterChange} proposal (a {@code #6.30} unit interval or non-negative interval).
 *       One at or above 2^63 is flagged here although Haskell accepts it, so such a transaction fails closed with
 *       {@code CoinOutOfEvaluatorRange} instead of being evaluated.</li>
 *   <li>The witness set and the auxiliary data are not scanned. An integer at or above 2^63 there that Scalus decodes
 *       as a long (for example an execution-unit budget) still makes Scalus's decoder throw, which the Java engine
 *       reports as the opaque {@code ENGINE.JavaEngineFailure}. It is never accepted.</li>
 * </ul>
 */
final class SignedLongRange {

    private SignedLongRange() {
    }

    /**
     * @param txCbor a transaction, {@code [body, witnesses, is_valid, auxiliary_data]}
     * @return a description of the first body integer outside the signed 64-bit range, or empty
     * @throws IllegalArgumentException when the bytes are not well-formed CBOR
     */
    static Optional<String> firstOutOfRange(byte[] txCbor) {
        int pos = 0;
        int[] head = new int[1];
        long[] arg = new long[1];
        // The transaction array's head, then its first item: the body.
        pos = head(txCbor, pos, head, arg);
        if (head[0] != 4) {
            throw new IllegalArgumentException("a transaction is an array");
        }
        long[] pending = new long[64];
        int depth = 0;
        pending[depth++] = 1;
        while (depth > 0) {
            if (pending[depth - 1] == 0) {
                depth--;
                continue;
            }
            if (pos >= txCbor.length) {
                throw new IllegalArgumentException("truncated transaction body");
            }
            if ((txCbor[pos] & 0xff) == 0xff) {
                if (pending[depth - 1] != -1) {
                    throw new IllegalArgumentException("unexpected break at offset " + pos);
                }
                pos++;
                depth--;
                continue;
            }
            if (pending[depth - 1] > 0) {
                pending[depth - 1]--;
            }
            int start = pos;
            pos = head(txCbor, pos, head, arg);
            int major = head[0];
            boolean indefinite = (txCbor[start] & 0x1f) == 31;
            if (indefinite && (major == 0 || major == 1 || major == 6)) {
                throw new IllegalArgumentException("indefinite length for major type " + major + " at offset " + start);
            }
            switch (major) {
                case 0 -> {
                    if (arg[0] < 0) {
                        return Optional.of("unsigned integer " + Long.toUnsignedString(arg[0]) + " at body offset "
                                + start);
                    }
                }
                case 1 -> {
                    if (arg[0] < 0) {
                        return Optional.of("negative integer -1-" + Long.toUnsignedString(arg[0])
                                + " at body offset " + start);
                    }
                }
                case 2, 3 -> {
                    if (indefinite) {
                        pending = push(pending, depth++, -1);
                    } else {
                        pos = Math.addExact(pos, Math.toIntExact(arg[0]));
                    }
                }
                case 4 -> pending = push(pending, depth++, indefinite ? -1 : arg[0]);
                case 5 -> pending = push(pending, depth++, indefinite ? -1 : Math.multiplyExact(arg[0], 2));
                case 6 -> pending = push(pending, depth++, 1);
                default -> {
                    // simple values and floats: nothing to check
                }
            }
        }
        return Optional.empty();
    }

    private static long[] push(long[] stack, int depth, long value) {
        long[] s = depth < stack.length ? stack : Arrays.copyOf(stack, stack.length * 2);
        s[depth] = value;
        return s;
    }

    /** Reads a head at {@code pos}: major type into {@code major[0]}, argument into {@code arg[0]} (-1: indefinite). */
    private static int head(byte[] data, int pos, int[] major, long[] arg) {
        if (pos >= data.length) {
            throw new IllegalArgumentException("truncated CBOR at offset " + pos);
        }
        int initial = data[pos] & 0xff;
        major[0] = initial >>> 5;
        int info = initial & 0x1f;
        if (info < 24) {
            arg[0] = info;
            return pos + 1;
        }
        if (info == 31) {
            arg[0] = -1;
            return pos + 1;
        }
        int length = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new IllegalArgumentException("reserved additional information at offset " + pos);
        };
        if (pos + length >= data.length) {
            throw new IllegalArgumentException("truncated CBOR head at offset " + pos);
        }
        long value = 0;
        for (int i = 1; i <= length; i++) {
            value = (value << 8) | (data[pos + i] & 0xff);
        }
        arg[0] = value;
        return pos + 1 + length;
    }
}
