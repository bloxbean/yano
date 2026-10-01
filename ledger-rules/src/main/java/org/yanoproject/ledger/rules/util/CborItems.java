package org.yanoproject.ledger.rules.util;

import java.util.Arrays;

/**
 * Minimal CBOR (RFC 8949) item walker: finds where an encoded data item ends without decoding
 * or re-encoding it.
 *
 * <p>Handles all eight major types, definite and indefinite lengths, and tags. It is iterative, so
 * deeply nested input cannot overflow the stack. It checks structure only as far as needed to find
 * item boundaries; semantic validation is left to the real decoder.</p>
 */
public final class CborItems {

    private static final long INDEFINITE = -1;

    private CborItems() {
    }

    /**
     * @param data   encoded bytes
     * @param offset start of a data item
     * @return the offset just past that item
     * @throws IllegalArgumentException if the bytes are truncated or not well-formed CBOR
     */
    public static int skip(byte[] data, int offset) {
        if (offset < 0 || offset >= data.length) {
            throw malformed("item offset " + offset + " out of range", offset);
        }
        long[] pending = new long[16];
        int depth = 0;
        pending[depth++] = 1;
        int off = offset;
        while (depth > 0) {
            long remaining = pending[depth - 1];
            if (remaining == 0) {
                depth--;
                continue;
            }
            if (off >= data.length) {
                throw malformed("truncated", off);
            }
            int initial = data[off] & 0xff;
            if (initial == 0xff) {
                if (remaining != INDEFINITE) {
                    throw malformed("unexpected break", off);
                }
                off++;
                depth--;
                continue;
            }
            if (remaining != INDEFINITE) {
                pending[depth - 1] = remaining - 1;
            }
            int major = initial >>> 5;
            int info = initial & 0x1f;
            off++;
            if (info == 31) {
                if (major == 0 || major == 1 || major == 6 || major == 7) {
                    throw malformed("indefinite length not allowed for major type " + major, off - 1);
                }
                pending = push(pending, depth++, INDEFINITE);
                continue;
            }
            long argument;
            int argBytes = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> {
                    if (info > 27) {
                        throw malformed("reserved additional information " + info, off - 1);
                    }
                    yield 0;
                }
            };
            if (argBytes == 0) {
                argument = info;
            } else {
                if (off + argBytes > data.length) {
                    throw malformed("truncated argument", off);
                }
                argument = 0;
                for (int i = 0; i < argBytes; i++) {
                    argument = (argument << 8) | (data[off + i] & 0xff);
                }
                off += argBytes;
            }
            int available = data.length - off;
            switch (major) {
                case 0, 1, 7 -> {
                    // Integers, simple values and floats: the argument is the whole payload.
                }
                case 2, 3 -> {
                    if (argument < 0 || argument > available) {
                        throw malformed("string length " + Long.toUnsignedString(argument) + " exceeds input", off);
                    }
                    off += (int) argument;
                }
                case 4, 5 -> {
                    long items = major == 5 ? argument * 2 : argument;
                    // Every item takes at least one byte; this also rejects overflowed counts.
                    if (argument < 0 || items < 0 || items > available) {
                        throw malformed("container size " + Long.toUnsignedString(argument) + " exceeds input", off);
                    }
                    if (items > 0) {
                        pending = push(pending, depth++, items);
                    }
                }
                case 6 -> pending = push(pending, depth++, 1);
                default -> throw new IllegalStateException("unreachable major type " + major);
            }
        }
        return off;
    }

    private static long[] push(long[] stack, int depth, long value) {
        long[] s = depth == stack.length ? Arrays.copyOf(stack, stack.length * 2) : stack;
        s[depth] = value;
        return s;
    }

    private static IllegalArgumentException malformed(String what, int offset) {
        return new IllegalArgumentException("Malformed CBOR at offset " + offset + ": " + what);
    }
}
