package org.yanoproject.scalusbridge;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Carries integers Scalus cannot decode through the phase-2 evaluation (ADR-056 Phase 7c).
 *
 * <p>Haskell decodes several wire integers as {@code Word64}, so values in {@code [2^63, 2^64)} are valid:</p>
 * <ul>
 *   <li>an output's coin and multi-asset quantities ({@code decodeMaryValue} with {@code decodeWord64},
 *       cardano-ledger {@code f649f975} Mary/Value.hs:287-295; preprod token quantities of about 1.5 * 10^19, e.g.
 *       transaction {@code 8e4b1ced…}), and every other {@code Coin} of the body (Coin.hs, {@code decodeWord64});</li>
 *   <li>the alternative of a Plutus {@code Constr} encoded with tag 102 ({@code decodeConstrExtended},
 *       {@code CBOR.decodeWord64}, plutus 1.65.0.0 PlutusCore/Data.hs:298-307; preprod transaction
 *       {@code 2edd684f…} has {@code Constr (2^64-1) []} in two output datums);</li>
 *   <li>a metadatum integer ({@code decodeInteger} for major types 0 and 1, cardano-ledger-core Metadata.hs:161-164;
 *       preprod transaction {@code c0c3e628…} has a CIP-25 price denominator of 10^19).</li>
 * </ul>
 * <p>Scalus 1.1.1 reads all of them as a signed {@code Long} ({@code MultiAsset} and {@code Coin} in its
 * {@code Transaction} model, {@code DataApi}'s tag-102 decoder, {@code Metadatum}), so its decoder throws "Expected
 * Long but got OverLong". A Plutus script sees these values as {@code Integer}s ({@code transValue}, {@code Constr}),
 * so they must reach the script context exactly.</p>
 *
 * <p><b>Narrowing.</b> Each such integer is overwritten in place with a placeholder that Scalus can decode: the same
 * CBOR head length (a 9-byte {@code uint64}), so no container or byte-string length changes, including the tag-24
 * byte strings around datums. Placeholders are {@code >= 2^62}, above every other integer of the transaction and its
 * resolved outputs (so every order and equality among the transaction's integers is kept), and are assigned in the
 * order of the values they replace, one per distinct value. Integers derived while building a context (POSIX times,
 * indexes) are far below {@code 2^62}.</p>
 *
 * <p><b>Restoring.</b> The evaluator builds the script contexts from the narrowed transaction and then replaces, in
 * every script argument, each placeholder by its value: an {@code I} placeholder wherever a ledger integer appears (a
 * value, a withdrawal, a deposit, a governance amount), a {@code Constr} placeholder as a constructor alternative.
 * Metadata never reaches a script context (no Plutus version translates auxiliary data), so its integers are only
 * narrowed.</p>
 *
 * <p><b>Refused</b> ({@link Narrowing#unsupported()}, the evaluator fails closed with an explicit engine failure):</p>
 * <ul>
 *   <li>a wide validity-interval slot (body keys 3 and 8; a script sees it as a POSIX time, not as the integer);</li>
 *   <li>a wide integer in a rational ({@code #6.30([n, d])}): a script sees it reduced
 *       ({@code transBoundedRational}), which a restored placeholder would not be;</li>
 *   <li>a wide negative body integer (only {@code mint} is signed, and Haskell bounds it to {@code Int64});</li>
 *   <li>a wide {@code Constr} alternative in a witness-set datum (its hash, which the script context carries, is
 *       computed from its bytes);</li>
 *   <li>a wide integer in the witness set outside its datums and redeemers (keys, scripts, bootstrap witnesses).</li>
 * </ul>
 * <p>The datums and redeemers are walked as Plutus data, so a wide execution budget inside a redeemer (above every
 * {@code maxTxExUnits}, which phase one rejects) is left to Scalus's decoder, which fails.</p>
 */
final class WideIntegers {

    private static final long PLACEHOLDER_FLOOR = 1L << 62;
    private static final int BODY_TTL = 3;
    private static final int BODY_VALIDITY_START = 8;
    private static final int WITNESS_DATUMS = 4;
    private static final int WITNESS_REDEEMERS = 5;
    private static final long RATIONAL_TAG = 30;

    private WideIntegers() {
    }

    /** Where an integer to narrow sits, and how it comes back. */
    private enum Kind {
        /** A ledger integer; restored as {@code I}. */
        LEDGER,
        /** A {@code Constr} alternative; restored as the constructor index. */
        CONSTR,
        /** A metadatum integer; not restored (never in a script context). */
        METADATA
    }

    /** One integer to overwrite: in which buffer, at which head offset, of what kind and value. */
    private record Edit(int buffer, int offset, Kind kind, BigInteger value) {
    }

    /**
     * The narrowed transaction and resolved-output bytes, and the placeholder maps.
     *
     * @param txCbor       the narrowed transaction (its body's original bytes must stay the transaction's raw body)
     * @param outputs      the narrowed resolved outputs, in the order given
     * @param datums       the narrowed resolved inline datums, in the order given ({@code null} stays {@code null})
     * @param integers     {@code I} placeholder to its value
     * @param constructors {@code Constr} placeholder to its alternative
     * @param unsupported  why the transaction cannot be narrowed, when it cannot
     */
    record Narrowing(byte[] txCbor, List<byte[]> outputs, List<byte[]> datums, Map<BigInteger, BigInteger> integers,
                     Map<BigInteger, BigInteger> constructors, Optional<String> unsupported) {

        /** @return true when nothing was narrowed that a script context carries (no restoring needed) */
        boolean restoresNothing() {
            return integers.isEmpty() && constructors.isEmpty();
        }
    }

    /**
     * @param txCbor  a transaction {@code [body, witnesses, is_valid, auxiliary_data]}
     * @param outputs the CBOR of each resolved output the evaluation reads (spending, reference and collateral)
     * @param datums  each resolved output's original inline datum bytes, parallel to {@code outputs}, or {@code null}
     * @return the narrowing; unchanged bytes (the same arrays) when nothing is out of range
     * @throws IllegalArgumentException when the bytes are not well-formed CBOR
     */
    static Narrowing narrow(byte[] txCbor, List<byte[]> outputs, List<byte[]> datums) {
        List<byte[]> buffers = new ArrayList<>();
        buffers.add(txCbor);
        buffers.addAll(outputs);
        int datumBase = buffers.size();
        buffers.addAll(datums);

        Scan scan = new Scan();
        scan.transaction(txCbor);
        for (int i = 0; i < outputs.size(); i++) {
            scan.ledgerItem(i + 1, outputs.get(i), 0);
        }
        for (int i = 0; i < datums.size(); i++) {
            if (datums.get(i) != null) {
                scan.dataItem(datumBase + i, datums.get(i), 0, false);
            }
        }
        if (scan.unsupported != null) {
            return new Narrowing(txCbor, outputs, datums, Map.of(), Map.of(), Optional.of(scan.unsupported));
        }
        if (scan.edits.isEmpty()) {
            return new Narrowing(txCbor, outputs, datums, Map.of(), Map.of(), Optional.empty());
        }

        Map<BigInteger, BigInteger> integers = placeholders(scan.edits, Kind.LEDGER, scan.maxInteger);
        Map<BigInteger, BigInteger> constructors = placeholders(scan.edits, Kind.CONSTR, scan.maxConstructor);
        byte[][] copies = new byte[buffers.size()][];
        for (Edit edit : scan.edits) {
            if (copies[edit.buffer()] == null) {
                copies[edit.buffer()] = buffers.get(edit.buffer()).clone();
            }
            byte[] target = copies[edit.buffer()];
            BigInteger placeholder = switch (edit.kind()) {
                case LEDGER -> integers.get(edit.value());
                case CONSTR -> constructors.get(edit.value());
                case METADATA -> BigInteger.valueOf(Long.MAX_VALUE);
            };
            // Same head length as the original (a uint64 or nint64 head): only the 8 argument bytes change.
            int major = (target[edit.offset()] & 0xff) >>> 5;
            target[edit.offset()] = (byte) ((major << 5) | 27);
            long argument = placeholder.longValueExact();
            for (int i = 0; i < 8; i++) {
                target[edit.offset() + 1 + i] = (byte) (argument >>> (56 - 8 * i));
            }
        }
        List<byte[]> narrowedOutputs = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            narrowedOutputs.add(copies[i + 1] != null ? copies[i + 1] : outputs.get(i));
        }
        List<byte[]> narrowedDatums = new ArrayList<>();
        for (int i = 0; i < datums.size(); i++) {
            narrowedDatums.add(copies[datumBase + i] != null ? copies[datumBase + i] : datums.get(i));
        }
        return new Narrowing(copies[0] != null ? copies[0] : txCbor, narrowedOutputs, narrowedDatums,
                invert(integers), invert(constructors), Optional.empty());
    }

    /** Order-preserving placeholders for the distinct values of one kind, above {@code max(2^62, floor + 1)}. */
    private static Map<BigInteger, BigInteger> placeholders(List<Edit> edits, Kind kind, long existingMax) {
        TreeSet<BigInteger> values = new TreeSet<>();
        edits.stream().filter(e -> e.kind() == kind).forEach(e -> values.add(e.value()));
        Map<BigInteger, BigInteger> byValue = new HashMap<>();
        long next = Math.max(PLACEHOLDER_FLOOR, existingMax + 1);
        for (BigInteger value : values) {
            if (next < 0) {
                throw new IllegalStateException("no placeholder left below 2^63");
            }
            byValue.put(value, BigInteger.valueOf(next++));
        }
        return byValue;
    }

    private static Map<BigInteger, BigInteger> invert(Map<BigInteger, BigInteger> byValue) {
        Map<BigInteger, BigInteger> byPlaceholder = new TreeMap<>();
        byValue.forEach((value, placeholder) -> byPlaceholder.put(placeholder, value));
        return byPlaceholder;
    }

    /** One pass over every buffer: the integers to narrow, the largest others, and any unsupported position. */
    private static final class Scan {
        private final List<Edit> edits = new ArrayList<>();
        private long maxInteger = -1;
        private long maxConstructor = -1;
        private String unsupported;

        void transaction(byte[] tx) {
            Head top = Head.read(tx, 0);
            if (top.major != 4) {
                throw new IllegalArgumentException("a transaction is a CBOR array");
            }
            int pos = top.end;
            for (long i = 0; top.indefinite ? (tx[pos] & 0xff) != 0xff : i < top.argument; i++) {
                pos = switch ((int) i) {
                    case 0 -> body(tx, pos);
                    case 1 -> witnesses(tx, pos);
                    case 3 -> metadata(tx, pos);
                    default -> skipCounting(tx, pos);
                };
            }
        }

        private int body(byte[] tx, int pos) {
            Head map = Head.read(tx, pos);
            if (map.major != 5) {
                return ledgerItem(0, tx, pos);
            }
            pos = map.end;
            for (long i = 0; map.indefinite ? (tx[pos] & 0xff) != 0xff : i < map.argument; i++) {
                Head key = Head.read(tx, pos);
                pos = ledgerItem(0, tx, pos);
                int before = edits.size();
                String previous = unsupported;
                pos = ledgerItem(0, tx, pos);
                if (key.major == 0 && (key.argument == BODY_TTL || key.argument == BODY_VALIDITY_START)
                        && edits.size() > before && previous == null) {
                    unsupported = "the validity interval slot " + edits.get(before).value() + " (body key "
                            + key.argument + ") is above 2^63-1; a script sees it as a POSIX time";
                }
            }
            return map.indefinite ? pos + 1 : pos;
        }

        private int witnesses(byte[] tx, int pos) {
            Head map = Head.read(tx, pos);
            if (map.major != 5) {
                return opaqueItem(tx, pos, "the witness set");
            }
            pos = map.end;
            for (long i = 0; map.indefinite ? (tx[pos] & 0xff) != 0xff : i < map.argument; i++) {
                Head key = Head.read(tx, pos);
                pos = skipCounting(tx, pos);
                if (key.major == 0 && key.argument == WITNESS_DATUMS) {
                    pos = dataItem(0, tx, pos, true);
                } else if (key.major == 0 && key.argument == WITNESS_REDEEMERS) {
                    pos = dataItem(0, tx, pos, false);
                } else {
                    pos = opaqueItem(tx, pos, "the witness set");
                }
            }
            return map.indefinite ? pos + 1 : pos;
        }

        /** Auxiliary data: every out-of-range integer (metadata, timelock slots) is narrowed, never restored. */
        private int metadata(byte[] tx, int pos) {
            return walk(0, tx, pos, Mode.METADATA, false);
        }

        /** A ledger item (the body, an output): wide unsigned integers are narrowed; tag-24 contents are Data. */
        int ledgerItem(int buffer, byte[] data, int pos) {
            return walk(buffer, data, pos, Mode.LEDGER, false);
        }

        /** Plutus data (or a container of it): wide tag-102 alternatives are narrowed. */
        int dataItem(int buffer, byte[] data, int pos, boolean hashed) {
            return walk(buffer, data, pos, Mode.DATA, hashed);
        }

        /** A part Scalus reads as ledger integers that has no reason to hold a wide one: refused if it does. */
        private int opaqueItem(byte[] data, int pos, String where) {
            int before = edits.size();
            int end = walk(0, data, pos, Mode.LEDGER, false);
            if (edits.size() > before && unsupported == null) {
                unsupported = "an integer above 2^63-1 (" + edits.get(before).value() + ") in " + where;
            }
            return end;
        }

        private int skipCounting(byte[] data, int pos) {
            return walk(0, data, pos, Mode.SKIP, false);
        }

        private enum Mode { LEDGER, DATA, METADATA, SKIP }

        private int walk(int buffer, byte[] data, int pos, Mode mode, boolean hashed) {
            Head head = Head.read(data, pos);
            switch (head.major) {
                case 0 -> {
                    if (head.wide) {
                        if (mode == Mode.LEDGER) {
                            edits.add(new Edit(buffer, pos, Kind.LEDGER, head.unsigned()));
                        } else if (mode == Mode.METADATA) {
                            edits.add(new Edit(buffer, pos, Kind.METADATA, head.unsigned()));
                        }
                    } else {
                        maxInteger = Math.max(maxInteger, head.argument);
                    }
                    return head.end;
                }
                case 1 -> {
                    if (head.wide) {
                        if (mode == Mode.METADATA) {
                            edits.add(new Edit(buffer, pos, Kind.METADATA, head.unsigned()));
                        } else if (mode == Mode.LEDGER && unsupported == null) {
                            unsupported = "a negative integer below -2^63 (-1-" + head.unsigned() + ")";
                        }
                    }
                    return head.end;
                }
                case 2, 3 -> {
                    if (head.indefinite) {
                        int p = head.end;
                        while ((data[p] & 0xff) != 0xff) {
                            p = walk(buffer, data, p, Mode.SKIP, hashed);
                        }
                        return p + 1;
                    }
                    return Math.addExact(head.end, Math.toIntExact(head.argument));
                }
                case 4, 5 -> {
                    int p = head.end;
                    long items = head.major == 5 ? head.argument * 2 : head.argument;
                    for (long i = 0; head.indefinite ? (data[p] & 0xff) != 0xff : i < items; i++) {
                        p = walk(buffer, data, p, mode, hashed);
                    }
                    return head.indefinite ? p + 1 : p;
                }
                case 6 -> {
                    if (head.argument == 24 && mode != Mode.SKIP && mode != Mode.METADATA) {
                        // Embedded CBOR (an inline datum, a reference script): its contents are Plutus data.
                        Head bytes = Head.read(data, head.end);
                        if (bytes.major == 2 && !bytes.indefinite) {
                            int start = bytes.end;
                            int end = Math.addExact(start, Math.toIntExact(bytes.argument));
                            if (end > start) {
                                int inner = walk(buffer, data, start, Mode.DATA, false);
                                if (inner != end) {
                                    throw new IllegalArgumentException("trailing bytes in an embedded CBOR item");
                                }
                            }
                            return end;
                        }
                    }
                    if (head.argument == 102 && mode == Mode.DATA) {
                        return constructor(buffer, data, head.end, hashed);
                    }
                    int before = edits.size();
                    int end = walk(buffer, data, head.end, mode, hashed);
                    if (head.argument == RATIONAL_TAG && mode == Mode.LEDGER && edits.size() > before
                            && unsupported == null) {
                        unsupported = "the rational " + edits.get(before).value() + "/… (tag 30) has a part above "
                                + "2^63-1; a script sees it reduced";
                    }
                    return end;
                }
                default -> {
                    return head.end;
                }
            }
        }

        /** {@code #6.102([alternative, [fields]])}. */
        private int constructor(int buffer, byte[] data, int pos, boolean hashed) {
            Head array = Head.read(data, pos);
            if (array.major != 4) {
                return walk(buffer, data, pos, Mode.DATA, hashed);
            }
            int p = array.end;
            for (long i = 0; array.indefinite ? (data[p] & 0xff) != 0xff : i < array.argument; i++) {
                if (i == 0) {
                    Head index = Head.read(data, p);
                    if (index.major == 0 && index.wide) {
                        if (hashed) {
                            if (unsupported == null) {
                                unsupported = "a witness datum has a Constr alternative above 2^63-1 ("
                                        + index.unsigned() + "); its hash is computed from its bytes";
                            }
                        } else {
                            edits.add(new Edit(buffer, p, Kind.CONSTR, index.unsigned()));
                        }
                    } else if (index.major == 0) {
                        maxConstructor = Math.max(maxConstructor, index.argument);
                    }
                }
                p = walk(buffer, data, p, Mode.DATA, hashed);
            }
            return array.indefinite ? p + 1 : p;
        }
    }

    /** A CBOR head: major type, argument (raw 64 bits), whether indefinite, whether its argument is {@code >= 2^63}. */
    private record Head(int major, long argument, boolean indefinite, boolean wide, int end) {

        BigInteger unsigned() {
            return new BigInteger(Long.toUnsignedString(argument));
        }

        static Head read(byte[] data, int pos) {
            if (pos >= data.length) {
                throw new IllegalArgumentException("truncated CBOR at offset " + pos);
            }
            int initial = data[pos] & 0xff;
            int major = initial >>> 5;
            int info = initial & 0x1f;
            if (info < 24) {
                return new Head(major, info, false, false, pos + 1);
            }
            if (info == 31) {
                if (major == 0 || major == 1 || major == 6) {
                    throw new IllegalArgumentException("indefinite length for major type " + major + " at " + pos);
                }
                return new Head(major, -1, true, false, pos + 1);
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
            boolean wide = length == 8 && value < 0;
            return new Head(major, value, false, wide, pos + 1 + length);
        }
    }
}
