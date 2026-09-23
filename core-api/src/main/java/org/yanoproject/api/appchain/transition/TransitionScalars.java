package org.yanoproject.api.appchain.transition;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.yanoproject.api.appchain.codec.internal.CborStructurePreflight;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical, bounded CBOR text-keyed scalar maps for transition events and committed settings.
 * Only signed 64-bit integers ({@link Long}), valid Unicode strings, byte arrays, and booleans are accepted.
 * Nulls, nested containers, tags, and floating-point values are excluded; a machine may expose a nested
 * domain object as canonical bytes with its own separately versioned schema.
 *
 * <p>Decoding checks structural bounds before allocating a recursive CBOR object tree and then requires
 * byte-exact canonical re-encoding. Encoding preflights aggregate output size before cloning scalar arrays.
 */
public final class TransitionScalars {
    public static final int MAX_FIELDS = 256;
    private static final CborStructurePreflight.Limits LIMITS = new CborStructurePreflight.Limits(
            TransitionEvent.MAX_PAYLOAD_BYTES, 2, 1 + 2 * MAX_FIELDS, MAX_FIELDS,
            TransitionEvent.MAX_PAYLOAD_BYTES);

    private TransitionScalars() { }

    /** Exact Java scalar representations; acceptance performs no coercion or numeric narrowing. */
    public enum Type {
        INTEGER, TEXT, BYTES, BOOLEAN;

        public boolean accepts(Object value) {
            return switch (this) {
                case INTEGER -> value instanceof Long;
                case TEXT -> value instanceof String;
                case BYTES -> value instanceof byte[];
                case BOOLEAN -> value instanceof Boolean;
            };
        }
    }

    /**
     * Encodes a bounded scalar map with length-first, unsigned-UTF-8 key ordering.
     *
     * @param fields text field names and supported non-null scalar values
     * @return newly allocated canonical CBOR bytes
     * @throws IllegalArgumentException if a value, Unicode sequence, field count, or aggregate byte size is invalid
     */
    public static byte[] encode(Map<String, ?> fields) {
        if (fields.size() > MAX_FIELDS) throw new IllegalArgumentException("too many scalar fields");
        long size = headerSize(fields.size());
        for (var entry : fields.entrySet()) {
            int nameBytes = utf8Length(entry.getKey());
            size += headerSize(nameBytes) + nameBytes + scalarSize(entry.getValue());
            if (size > TransitionEvent.MAX_PAYLOAD_BYTES)
                    throw new IllegalArgumentException("scalar map exceeds byte limit");
        }
        var map = new co.nstant.in.cbor.model.Map();
        fields.keySet().stream().sorted((a, b) -> {
            byte[] left = a.getBytes(StandardCharsets.UTF_8);
            byte[] right = b.getBytes(StandardCharsets.UTF_8);
            int length = Integer.compare(left.length, right.length);
            return length != 0 ? length : Arrays.compareUnsigned(left, right);
        }).forEach(key -> map.put(new UnicodeString(key), item(fields.get(key))));
        byte[] encoded = CborSerializationUtil.serialize(map);
        if (encoded.length > TransitionEvent.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("scalar map exceeds byte limit");
        }
        return encoded;
    }

    /**
     * Decodes exactly one canonical map, rejecting alternative encodings and unsupported scalar types.
     * The map is unmodifiable; decoded byte arrays are independent of the input buffer.
     *
     * @throws IllegalArgumentException if input is malformed, noncanonical, or exceeds the contract's bounds
     */
    public static Map<String, Object> decode(byte[] bytes) {
        if (!CborStructurePreflight.accepts(bytes, LIMITS)) {
            throw new IllegalArgumentException("invalid bounded scalar map");
        }
        DataItem item = CborSerializationUtil.deserializeOne(bytes);
        if (!(item instanceof co.nstant.in.cbor.model.Map map)) {
            throw new IllegalArgumentException("expected scalar map");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (DataItem key : map.getKeys()) {
            if (!(key instanceof UnicodeString text) || key.hasTag()) {
                throw new IllegalArgumentException("expected untagged text field");
            }
            result.put(text.getString(), value(map.get(key)));
        }
        if (!Arrays.equals(bytes, encode(result))) {
            throw new IllegalArgumentException("non-canonical scalar map");
        }
        return Collections.unmodifiableMap(result);
    }

    private static long scalarSize(Object value) {
        if (value instanceof byte[] bytes) return (long) headerSize(bytes.length) + bytes.length;
        if (value instanceof String text) {
            int length = utf8Length(text);
            return (long) headerSize(length) + length;
        }
        if (value instanceof Boolean) return 1;
        if (value instanceof Long number) return headerSize(number < 0 ? -1 - number : number);
        throw new IllegalArgumentException("expected integer, text, bytes, or boolean");
    }

    /** Counts valid UTF-8 bytes without allocating an attacker-sized temporary encoding. */
    private static int utf8Length(String text) {
        if (text == null) throw new IllegalArgumentException("null text field");
        int bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value < 0x80) bytes++;
            else if (value < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(value)) {
                if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw new IllegalArgumentException("invalid Unicode text");
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(value)) throw new IllegalArgumentException("invalid Unicode text");
            else bytes += 3;
            if (bytes > TransitionEvent.MAX_PAYLOAD_BYTES)
                    throw new IllegalArgumentException("scalar text exceeds byte limit");
        }
        return bytes;
    }

    private static int headerSize(long value) {
        return value < 24 ? 1 : value <= 0xff ? 2 : value <= 0xffff ? 3 : value <= 0xffffffffL ? 5 : 9;
    }

    /** Converts one supported scalar to CBOR; callers enforce the enclosing document's aggregate size. */
    public static DataItem item(Object value) {
        if (value instanceof byte[] bytes) return new ByteString(bytes.clone());
        if (value instanceof String text) {
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            if (!text.equals(new String(encoded, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("invalid Unicode text");
            }
            return new UnicodeString(text);
        }
        if (value instanceof Boolean bool) return bool ? SimpleValue.TRUE : SimpleValue.FALSE;
        if (value instanceof Long number) {
            return number >= 0 ? new UnsignedInteger(number) : new NegativeInteger(number);
        }
        throw new IllegalArgumentException("expected integer, text, bytes, or boolean");
    }

    /** Converts one untagged CBOR scalar without numeric truncation; byte arrays are copied. */
    public static Object value(DataItem item) {
        if (item.hasTag()) throw new IllegalArgumentException("tagged scalar");
        if (item instanceof ByteString bytes) return bytes.getBytes().clone();
        if (item instanceof UnicodeString text) return text.getString();
        try {
            if (item instanceof UnsignedInteger number) return number.getValue().longValueExact();
            if (item instanceof NegativeInteger number) return number.getValue().longValueExact();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("scalar integer exceeds signed 64-bit range", overflow);
        }
        if (SimpleValue.TRUE.equals(item)) return true;
        if (SimpleValue.FALSE.equals(item)) return false;
        throw new IllegalArgumentException("invalid scalar type");
    }
}
