package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Rewrites indefinite-length CBOR arrays and maps as definite-length ones, byte-for-byte otherwise.
 *
 * <p>Haskell accepts indefinite-length arrays and maps wherever the body has a list, a set or a map. Two decoders
 * do not:</p>
 * <ul>
 *   <li>Scalus's transaction decoder rejects indefinite-length containers in the body and its outputs (Amaru's
 *       corpus has such transactions, for example scenarios 00019 and 00151; see {@code ScalusTransactions});</li>
 *   <li>CCL's {@code Transaction.deserialize} fails on some of them: cbor-java keeps the {@code break} marker as the
 *       last item of an indefinite array, which CCL's {@code PoolRegistration.deserialize} casts to a byte string
 *       (the pool owners, preview transaction {@code 1c09afd8…}; see {@code CclTransactions}).</li>
 * </ul>
 * <p>The copy is only decoded: the original bytes stay the source of the transaction id and every hash. Byte and text
 * strings, including chunked ones, are copied unchanged.</p>
 */
public final class DefiniteLengthCbor {

    private static final long SET_TAG = 258;

    private DefiniteLengthCbor() {
    }

    /**
     * For CCL, which reads only the structure: the transaction's ids and hashes always come from the original bytes.
     *
     * @return the transaction with every item definite-length
     */
    public static byte[] normalizeTransaction(byte[] txCbor) {
        return normalize(txCbor, true, false);
    }

    /**
     * For Scalus, which also hashes the witness set's datums and the auxiliary data from their bytes: only the body is
     * rewritten, and the caller gives the decoded body its original bytes back.
     *
     * @param dropSetTags also remove the body's set tags ({@code #6.258}), which Haskell allows but does not require
     *                    ({@code decodeSetLikeEnforceNoDuplicates}, cardano-ledger-binary Decoder.hs:1081-1085)
     * @return the transaction with its top-level array and body definite-length; other items unchanged
     */
    public static byte[] normalizeBody(byte[] txCbor, boolean dropSetTags) {
        return normalize(txCbor, false, dropSetTags);
    }

    private static byte[] normalize(byte[] txCbor, boolean allItems, boolean dropSetTags) {
        Cursor cursor = new Cursor(txCbor, 0);
        int initial = txCbor[0] & 0xff;
        if (initial >>> 5 != 4) {
            throw new IllegalArgumentException("a transaction is a CBOR array");
        }
        List<byte[]> items = new ArrayList<>();
        long count = cursor.containerHead();
        for (long i = 0; count < 0 ? !cursor.atBreak() : i < count; i++) {
            int start = cursor.offset;
            int end = CborSpan.skip(txCbor, start, txCbor.length);
            byte[] item = Arrays.copyOfRange(txCbor, start, end);
            items.add(allItems || i == 0 ? normalizeItem(item, dropSetTags) : item);
            cursor.offset = end;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(txCbor.length + 8);
        head(out, 4, items.size());
        items.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static byte[] normalizeItem(byte[] item, boolean dropSetTags) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(item.length + 8);
        Cursor cursor = new Cursor(item, 0);
        write(cursor, out, dropSetTags);
        if (cursor.offset != item.length) {
            throw new IllegalArgumentException("trailing bytes after the item");
        }
        return out.toByteArray();
    }

    private static void write(Cursor cursor, ByteArrayOutputStream out, boolean dropSetTags) {
        byte[] data = cursor.data;
        int start = cursor.offset;
        int initial = data[start] & 0xff;
        int major = initial >>> 5;
        switch (major) {
            case 4, 5 -> {
                long count = cursor.containerHead();
                List<byte[]> children = new ArrayList<>();
                int perEntry = major == 5 ? 2 : 1;
                for (long i = 0; count < 0 ? !cursor.atBreak() : i < count; i++) {
                    for (int k = 0; k < perEntry; k++) {
                        ByteArrayOutputStream child = new ByteArrayOutputStream();
                        write(cursor, child, dropSetTags);
                        children.add(child.toByteArray());
                    }
                }
                head(out, major, children.size() / perEntry);
                children.forEach(out::writeBytes);
            }
            case 6 -> {
                long tag = cursor.tagNumber();
                int headEnd = cursor.offset;
                if (!(dropSetTags && tag == SET_TAG)) {
                    out.write(data, start, headEnd - start);
                }
                write(cursor, out, dropSetTags);
            }
            default -> {
                int end = CborSpan.skip(data, start, data.length);
                out.write(data, start, end - start);
                cursor.offset = end;
            }
        }
    }

    private static void head(ByteArrayOutputStream out, int major, long value) {
        int type = major << 5;
        if (value < 24) {
            out.write(type | (int) value);
        } else if (value < 0x100) {
            out.write(type | 24);
            out.write((int) value);
        } else if (value < 0x10000) {
            out.write(type | 25);
            out.write((int) (value >>> 8));
            out.write((int) value & 0xff);
        } else if (value < 0x1_0000_0000L) {
            out.write(type | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xff);
            }
        } else {
            out.write(type | 27);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xff);
            }
        }
    }

    /** A position in encoded bytes. */
    private static final class Cursor {
        private final byte[] data;
        private int offset;

        Cursor(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        boolean atBreak() {
            if ((data[offset] & 0xff) == 0xff) {
                offset++;
                return true;
            }
            return false;
        }

        /** Reads an array or map head; @return its length, or -1 when indefinite */
        long containerHead() {
            int info = data[offset] & 0x1f;
            if (info == 31) {
                offset++;
                return -1;
            }
            return argument();
        }

        /** Reads a tag head; @return its number */
        long tagNumber() {
            return argument();
        }

        private long argument() {
            int info = data[offset++] & 0x1f;
            if (info < 24) {
                return info;
            }
            int bytes = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw new IllegalArgumentException("unsupported additional information " + info);
            };
            long value = 0;
            for (int i = 0; i < bytes; i++) {
                value = (value << 8) | (data[offset++] & 0xff);
            }
            return value;
        }
    }
}
