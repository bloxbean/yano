package com.bloxbean.cardano.yano.appchain.roles.contracts.internal;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowException;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Strict preferred-CBOR subset with a dependency-free raw-byte preflight. */
public final class RoleWorkflowCbor {
    private RoleWorkflowCbor() {
    }

    public static byte[] encode(DataItem item) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new CborEncoder(output).encode(item);
            return output.toByteArray();
        } catch (Exception failure) {
            throw malformed();
        }
    }

    public static Array decodeArray(byte[] input, int expectedArity) {
        byte[] bytes = bounded(input, RoleWorkflowLimits.MAX_COMMAND_BYTES);
        preflight(bytes);
        try {
            CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(bytes));
            decoder.setMaxPreallocationSize(RoleWorkflowLimits.MAX_COMMAND_BYTES);
            List<DataItem> roots = decoder.decode();
            if (roots.size() != 1 || !(roots.getFirst() instanceof Array array)
                    || array.isChunked() || array.getDataItems().size() != expectedArity) {
                throw malformed();
            }
            rejectForms(array);
            if (!Arrays.equals(bytes, encode(array))) {
                throw malformed();
            }
            return array;
        } catch (RoleWorkflowException failure) {
            throw failure;
        } catch (Exception | StackOverflowError failure) {
            throw malformed();
        }
    }

    public static byte[] bounded(byte[] value, int maximum) {
        if (value == null || value.length == 0 || value.length > maximum) {
            throw malformed();
        }
        return value.clone();
    }

    public static long uint(DataItem value) {
        if (!(value instanceof UnsignedInteger number)) throw malformed();
        try {
            return number.getValue().longValueExact();
        } catch (ArithmeticException failure) {
            throw malformed();
        }
    }

    public static int uintInt(DataItem value) {
        long parsed = uint(value);
        if (parsed > Integer.MAX_VALUE) throw malformed();
        return (int) parsed;
    }

    public static String text(DataItem value) {
        if (!(value instanceof UnicodeString text) || text.isChunked()) throw malformed();
        return text.getString();
    }

    public static byte[] bytes(DataItem value, int length) {
        byte[] parsed = bytes(value);
        if (parsed.length != length) throw malformed();
        return parsed;
    }

    public static byte[] bytes(DataItem value) {
        if (!(value instanceof ByteString bytes) || bytes.isChunked()) throw malformed();
        return bytes.getBytes().clone();
    }

    public static Array array(DataItem value, int maximum) {
        if (!(value instanceof Array array) || array.isChunked()
                || array.getDataItems().size() > maximum) throw malformed();
        return array;
    }

    public static boolean bool(DataItem value) {
        if (!(value instanceof SimpleValue simple)) throw malformed();
        if (simple.getSimpleValueType() == SimpleValueType.TRUE) return true;
        if (simple.getSimpleValueType() == SimpleValueType.FALSE) return false;
        throw malformed();
    }

    public static void requireCanonical(byte[] supplied, byte[] normalized) {
        if (!Arrays.equals(supplied, normalized)) throw malformed();
    }

    private static void rejectForms(DataItem root) {
        Deque<DataItem> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        while (!pending.isEmpty()) {
            DataItem item = pending.removeLast();
            if (++seen > RoleWorkflowLimits.MAX_CBOR_ITEMS || item.hasTag()) throw malformed();
            if (item instanceof Array array) {
                if (array.isChunked()) throw malformed();
                pending.addAll(array.getDataItems());
            } else if (item instanceof ByteString bytes) {
                if (bytes.isChunked()) throw malformed();
            } else if (item instanceof UnicodeString text) {
                if (text.isChunked()) throw malformed();
            } else if (!(item instanceof UnsignedInteger)
                    && !(item instanceof SimpleValue simple
                    && (simple.getSimpleValueType() == SimpleValueType.TRUE
                    || simple.getSimpleValueType() == SimpleValueType.FALSE))) {
                throw malformed();
            }
        }
    }

    private static void preflight(byte[] bytes) {
        long[] remaining = new long[RoleWorkflowLimits.MAX_NESTING_DEPTH + 1];
        remaining[0] = 1;
        int depth = 0;
        int offset = 0;
        int count = 0;
        while (depth >= 0) {
            if (remaining[depth] == 0) {
                depth--;
                continue;
            }
            remaining[depth]--;
            if (++count > RoleWorkflowLimits.MAX_CBOR_ITEMS || offset >= bytes.length) throw malformed();
            int initial = bytes[offset++] & 0xff;
            int major = initial >>> 5;
            int additional = initial & 0x1f;
            Raw argument = argument(bytes, offset, additional);
            offset = argument.nextOffset();
            long value = argument.value();
            switch (major) {
                case 0 -> { }
                case 2, 3 -> {
                    if (value > bytes.length - offset) throw malformed();
                    offset += (int) value;
                }
                case 4 -> {
                    if (value > RoleWorkflowLimits.MAX_CBOR_ITEMS - count
                            || depth >= RoleWorkflowLimits.MAX_NESTING_DEPTH) throw malformed();
                    if (value > 0) remaining[++depth] = value;
                }
                case 7 -> {
                    if (additional != 20 && additional != 21) throw malformed();
                }
                default -> throw malformed();
            }
        }
        if (offset != bytes.length) throw malformed();
    }

    private static Raw argument(byte[] bytes, int offset, int additional) {
        if (additional < 24) return new Raw(additional, offset);
        int width = switch (additional) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw malformed();
        };
        if (offset > bytes.length - width || (width == 8 && (bytes[offset] & 0x80) != 0)) {
            throw malformed();
        }
        long value = 0;
        for (int index = 0; index < width; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        }
        if ((width == 1 && value < 24) || (width == 2 && value <= 0xffL)
                || (width == 4 && value <= 0xffffL)
                || (width == 8 && value <= 0xffff_ffffL)) throw malformed();
        return new Raw(value, offset + width);
    }

    public static RoleWorkflowException malformed() {
        return new RoleWorkflowException(RoleWorkflowResultCode.INVALID_PAYLOAD);
    }

    private record Raw(long value, int nextOffset) {
    }
}
