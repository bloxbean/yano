package com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Strict, bounded preferred-CBOR subset shared only by portable stdlib contracts. */
public final class StdlibContractCbor {
    public static final int MAX_WIRE_BYTES = 1_048_576;
    public static final int MAX_STATE_KEY_BYTES = 256;
    private static final int MAX_ITEMS = 256;
    private static final int MAX_DEPTH = 8;

    private StdlibContractCbor() {
    }

    public static byte[] encode(DataItem item) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new CborEncoder(output).encode(item);
            byte[] encoded = output.toByteArray();
            if (encoded.length == 0 || encoded.length > MAX_WIRE_BYTES) throw malformed();
            return encoded;
        } catch (Exception failure) {
            throw malformed();
        }
    }

    public static Array decodeArray(byte[] input, int... allowedArities) {
        if (input == null || input.length == 0 || input.length > MAX_WIRE_BYTES) {
            throw malformed();
        }
        try {
            CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(input));
            decoder.setMaxPreallocationSize(MAX_WIRE_BYTES);
            List<DataItem> roots = decoder.decode();
            if (roots.size() != 1 || !(roots.getFirst() instanceof Array array)
                    || array.isChunked() || !allowed(array.getDataItems().size(), allowedArities)) {
                throw malformed();
            }
            requireSafeTree(array);
            if (!Arrays.equals(input, encode(array))) throw malformed();
            return array;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception | StackOverflowError failure) {
            throw malformed();
        }
    }

    public static int uintInt(DataItem value) {
        long parsed = uint(value);
        if (parsed > Integer.MAX_VALUE) throw malformed();
        return (int) parsed;
    }

    public static long uint(DataItem value) {
        if (!(value instanceof UnsignedInteger number)) throw malformed();
        try {
            return number.getValue().longValueExact();
        } catch (ArithmeticException failure) {
            throw malformed();
        }
    }

    public static String text(DataItem value) {
        if (!(value instanceof UnicodeString text) || text.isChunked()) throw malformed();
        return text.getString();
    }

    public static byte[] bytes(DataItem value) {
        if (!(value instanceof ByteString bytes) || bytes.isChunked()) throw malformed();
        return bytes.getBytes().clone();
    }

    public static byte[] bytes(DataItem value, int expectedLength) {
        byte[] parsed = bytes(value);
        if (parsed.length != expectedLength) throw malformed();
        return parsed;
    }

    public static Array array(DataItem value, int maximumItems) {
        if (!(value instanceof Array array) || array.isChunked()
                || array.getDataItems().size() > maximumItems) throw malformed();
        return array;
    }

    public static void requireText(String value, String name) {
        if (value == null || value.isBlank()
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATE_KEY_BYTES) {
            throw new IllegalArgumentException(name + " must be non-empty UTF-8 text of at most 256 bytes");
        }
    }

    public static void requireStateKey(byte[] key, String name) {
        if (key == null || key.length == 0 || key.length > MAX_STATE_KEY_BYTES) {
            throw new IllegalArgumentException(name + " must contain 1-256 bytes");
        }
    }

    public static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid canonical bounded stdlib contract value");
    }

    private static boolean allowed(int arity, int[] allowedArities) {
        for (int candidate : allowedArities) if (arity == candidate) return true;
        return false;
    }

    private static void requireSafeTree(DataItem root) {
        record Pending(DataItem item, int depth) { }
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(root, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            Pending current = pending.removeLast();
            DataItem item = current.item();
            if (++count > MAX_ITEMS || current.depth() > MAX_DEPTH || item.hasTag()) throw malformed();
            if (item instanceof Array array) {
                if (array.isChunked()) throw malformed();
                for (DataItem child : array.getDataItems()) {
                    pending.add(new Pending(child, current.depth() + 1));
                }
            } else if (item instanceof ByteString bytes) {
                if (bytes.isChunked()) throw malformed();
            } else if (item instanceof UnicodeString text) {
                if (text.isChunked()) throw malformed();
            } else if (!(item instanceof UnsignedInteger)) {
                throw malformed();
            }
        }
    }
}
