package com.bloxbean.cardano.yano.appchain.integration.internal;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Strict preferred-CBOR helpers used only by the frozen connector codecs.
 *
 * @hidden internal implementation detail; not part of the connector SDK API
 */
public final class CanonicalCbor {
    private static final int MAX_NESTING_DEPTH = 8;
    private static final int MAX_DATA_ITEMS = 512;

    private CanonicalCbor() {
    }

    public static byte[] encode(DataItem item) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new CborEncoder(output).encode(item);
            return output.toByteArray();
        } catch (Exception ignored) {
            throw malformed();
        }
    }

    public static Array decodeArray(byte[] bytes, int maxBytes, int expectedArity) {
        Array array = decodeArray(bytes, maxBytes);
        List<DataItem> items = array.getDataItems();
        if (items.isEmpty()) {
            throw malformed();
        }
        // Version classification must not depend on the v1 arity. This lets future
        // versions be rejected distinctly even when their shape differs from v1.
        requireVersion(items.get(0));
        if (items.size() != expectedArity) {
            throw malformed();
        }
        return array;
    }

    public static Array decodeArray(byte[] bytes, int maxBytes) {
        DataItem item = decodeValue(bytes, maxBytes);
        if (!(item instanceof Array array)) {
            throw malformed();
        }
        return array;
    }

    public static DataItem decodeValue(byte[] bytes, int maxBytes) {
        byte[] input = boundedSnapshot(bytes, maxBytes);
        preflight(input, maxBytes);
        try {
            List<DataItem> decoded = decoder(input, maxBytes).decode();
            if (decoded.size() != 1) {
                throw malformed();
            }
            DataItem item = decoded.get(0);
            rejectNonContractForms(item);
            if (!Arrays.equals(input, encode(item))) {
                throw malformed();
            }
            return item;
        } catch (ConnectorContractException exception) {
            throw exception;
        } catch (StackOverflowError ignored) {
            // Defensive backstop. The iterative preflight bounds nesting before the
            // third-party decoder sees the input.
            throw malformed();
        } catch (Exception ignored) {
            throw malformed();
        }
    }

    public static List<DataItem> items(Array array) {
        return array.getDataItems();
    }

    public static void requireVersion(DataItem item) {
        long version = uint(item);
        if (version != 1) {
            throw new ConnectorContractException(ConnectorErrorCode.UNSUPPORTED_VERSION);
        }
    }

    public static long uint(DataItem item) {
        if (!(item instanceof UnsignedInteger number)) {
            throw malformed();
        }
        try {
            return number.getValue().longValueExact();
        } catch (ArithmeticException ignored) {
            throw malformed();
        }
    }

    public static int uintInt(DataItem item) {
        long value = uint(item);
        if (value > Integer.MAX_VALUE) {
            throw malformed();
        }
        return (int) value;
    }

    public static Long nullableUint(DataItem item) {
        if (isNull(item)) {
            return null;
        }
        return uint(item);
    }

    public static String text(DataItem item) {
        if (!(item instanceof UnicodeString text)) {
            throw malformed();
        }
        return text.getString();
    }

    public static String nullableText(DataItem item) {
        return isNull(item) ? null : text(item);
    }

    public static byte[] bytes(DataItem item) {
        if (!(item instanceof ByteString bytes)) {
            throw malformed();
        }
        return bytes.getBytes().clone();
    }

    public static boolean bool(DataItem item) {
        if (!(item instanceof SimpleValue value)) {
            throw malformed();
        }
        if (value.getSimpleValueType() == SimpleValueType.TRUE) {
            return true;
        }
        if (value.getSimpleValueType() == SimpleValueType.FALSE) {
            return false;
        }
        throw malformed();
    }

    public static Array array(DataItem item, int expectedArity) {
        if (!(item instanceof Array array) || array.getDataItems().size() != expectedArity) {
            throw malformed();
        }
        return array;
    }

    public static Array array(DataItem item) {
        if (!(item instanceof Array array)) {
            throw malformed();
        }
        return array;
    }

    public static DataItem nullable(Long value) {
        return value == null ? SimpleValue.NULL : new UnsignedInteger(value);
    }

    public static DataItem nullable(String value) {
        return value == null ? SimpleValue.NULL : new UnicodeString(value);
    }

    public static SimpleValue boolValue(boolean value) {
        return value ? SimpleValue.TRUE : SimpleValue.FALSE;
    }

    public static void requireEncodedBound(byte[] encoded, int maximum) {
        if (encoded.length > maximum) {
            throw malformed();
        }
    }

    public static boolean isCanonicalValue(byte[] bytes, int maximum) {
        if (bytes == null || bytes.length == 0 || maximum <= 0 || bytes.length > maximum) {
            return false;
        }
        byte[] input = bytes.clone();
        try {
            preflight(input, maximum);
            List<DataItem> decoded = decoder(input, maximum).decode();
            if (decoded.size() != 1) {
                return false;
            }
            rejectNonContractForms(decoded.get(0));
            return Arrays.equals(input, encode(decoded.get(0)));
        } catch (Exception | StackOverflowError ignored) {
            return false;
        }
    }

    private static boolean isNull(DataItem item) {
        return item instanceof SimpleValue value
                && value.getSimpleValueType() == SimpleValueType.NULL;
    }

    /**
     * Validates the encoded-size bound before allocating one stable input snapshot.
     *
     * @param bytes the caller-owned input bytes
     * @param maximum the maximum accepted length
     * @return a defensive copy within the requested bound
     */
    public static byte[] boundedSnapshot(byte[] bytes, int maximum) {
        if (bytes == null || bytes.length == 0 || maximum <= 0 || bytes.length > maximum) {
            throw malformed();
        }
        return bytes.clone();
    }

    private static void rejectNonContractForms(DataItem item) {
        Deque<DataItem> pending = new ArrayDeque<>();
        pending.add(item);
        int visited = 0;
        while (!pending.isEmpty()) {
            DataItem current = pending.removeLast();
            if (++visited > MAX_DATA_ITEMS || current.hasTag()) {
                throw malformed();
            }
            if (current instanceof Array array) {
                if (array.isChunked()) {
                    throw malformed();
                }
                pending.addAll(array.getDataItems());
                continue;
            }
            if (current instanceof ByteString bytes) {
                if (bytes.isChunked()) {
                    throw malformed();
                }
                continue;
            }
            if (current instanceof UnicodeString text) {
                if (text.isChunked()) {
                    throw malformed();
                }
                continue;
            }
            if (current instanceof UnsignedInteger) {
                continue;
            }
            if (current instanceof SimpleValue value
                    && (value.getSimpleValueType() == SimpleValueType.FALSE
                    || value.getSimpleValueType() == SimpleValueType.TRUE
                    || value.getSimpleValueType() == SimpleValueType.NULL)) {
                continue;
            }
            throw malformed();
        }
    }

    /**
     * Reject dangerous and non-preferred forms before the object-building decoder
     * can allocate from attacker-controlled lengths or recurse on attacker-controlled
     * nesting. Only the small CBOR subset used by connector contracts is admitted.
     */
    private static void preflight(byte[] bytes, int maximum) {
        if (bytes == null || bytes.length == 0 || maximum <= 0 || bytes.length > maximum) {
            throw malformed();
        }

        long[] remainingByDepth = new long[MAX_NESTING_DEPTH + 1];
        remainingByDepth[0] = 1;
        int depth = 0;
        int offset = 0;
        int items = 0;

        while (depth >= 0) {
            if (remainingByDepth[depth] == 0) {
                depth--;
                continue;
            }
            remainingByDepth[depth]--;
            if (++items > MAX_DATA_ITEMS || offset >= bytes.length) {
                throw malformed();
            }

            int initial = bytes[offset++] & 0xff;
            int majorType = initial >>> 5;
            int additional = initial & 0x1f;
            RawArgument argument = readArgument(bytes, offset, additional);
            offset = argument.nextOffset();
            long value = argument.value();

            switch (majorType) {
                case 0 -> {
                    // Unsigned integers are the only admitted numeric form.
                }
                case 2, 3 -> {
                    if (value > bytes.length - offset) {
                        throw malformed();
                    }
                    offset += (int) value;
                }
                case 4 -> {
                    if (value > MAX_DATA_ITEMS - items) {
                        throw malformed();
                    }
                    if (value > 0) {
                        if (depth >= MAX_NESTING_DEPTH) {
                            throw malformed();
                        }
                        remainingByDepth[++depth] = value;
                    }
                }
                case 7 -> {
                    if (additional != 20 && additional != 21 && additional != 22) {
                        throw malformed();
                    }
                }
                default -> throw malformed();
            }
        }

        if (offset != bytes.length) {
            throw malformed();
        }
    }

    private static RawArgument readArgument(byte[] bytes, int offset, int additional) {
        if (additional < 24) {
            return new RawArgument(additional, offset);
        }
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
        if ((width == 1 && value < 24)
                || (width == 2 && value <= 0xffL)
                || (width == 4 && value <= 0xffffL)
                || (width == 8 && value <= 0xffff_ffffL)) {
            throw malformed();
        }
        return new RawArgument(value, offset + width);
    }

    private static CborDecoder decoder(byte[] bytes, int maximum) {
        CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(bytes));
        decoder.setMaxPreallocationSize(maximum);
        return decoder;
    }

    public static ConnectorContractException malformed() {
        return new ConnectorContractException(ConnectorErrorCode.INVALID_PAYLOAD);
    }

    private record RawArgument(long value, int nextOffset) {
    }
}
