package org.yanoproject.scalusbridge;

import org.yanoproject.ledger.rules.util.CborItems;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Rewrites indefinite-length CBOR arrays and maps as definite-length ones, byte-for-byte otherwise.
 *
 * <p>Scalus's transaction decoder rejects indefinite-length containers in the body and its outputs, which
 * Haskell accepts (Amaru's corpus has such transactions, for example scenarios 00019 and 00151). The body is
 * normalised only to decode it; its original bytes stay the body's raw bytes, so the transaction id is
 * unchanged (see {@code ScalusTransactions}). Byte and text strings, including chunked ones, are copied
 * unchanged.</p>
 */
final class DefiniteLengthCbor {

    private DefiniteLengthCbor() {
    }

    /** @return the transaction with its top-level array and body definite-length; other items unchanged */
    static byte[] normalizeTransaction(byte[] txCbor) {
        Cursor cursor = new Cursor(txCbor, 0);
        int initial = txCbor[0] & 0xff;
        if (initial >>> 5 != 4) {
            throw new IllegalArgumentException("a transaction is a CBOR array");
        }
        List<byte[]> items = new ArrayList<>();
        long count = cursor.containerHead();
        boolean first = true;
        for (long i = 0; count < 0 ? !cursor.atBreak() : i < count; i++) {
            int start = cursor.offset;
            int end = CborItems.skip(txCbor, start);
            byte[] item = Arrays.copyOfRange(txCbor, start, end);
            items.add(first ? normalize(item) : item);
            first = false;
            cursor.offset = end;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(txCbor.length + 8);
        head(out, 4, items.size());
        items.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** @return {@code item} with every indefinite-length array and map made definite */
    static byte[] normalize(byte[] item) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(item.length + 8);
        Cursor cursor = new Cursor(item, 0);
        write(cursor, out);
        if (cursor.offset != item.length) {
            throw new IllegalArgumentException("trailing bytes after the item");
        }
        return out.toByteArray();
    }

    private static void write(Cursor cursor, ByteArrayOutputStream out) {
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
                        write(cursor, child);
                        children.add(child.toByteArray());
                    }
                }
                head(out, major, children.size() / perEntry);
                children.forEach(out::writeBytes);
            }
            case 6 -> {
                int headEnd = cursor.skipHead();
                out.write(data, start, headEnd - start);
                write(cursor, out);
            }
            default -> {
                int end = CborItems.skip(data, start);
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

        /** Skips a tag head; @return the offset after it */
        int skipHead() {
            argument();
            return offset;
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
