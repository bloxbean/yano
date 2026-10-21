package org.yanoproject.ledger.amaru.wire;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes the module's response documents into plain Java values: {@link Long} (or
 * {@link BigInteger} above {@code Long.MAX_VALUE}) for integers, {@code byte[]}, {@link String},
 * {@link List}, {@link Map} (insertion-ordered; duplicate keys rejected), {@link Boolean}, {@code null},
 * and {@link Tagged}. Definite and indefinite lengths are accepted. Trailing bytes are an error.
 */
public final class CborReader {

    /** A tagged item. */
    public record Tagged(long tag, Object value) {
    }

    private static final int MAX_DEPTH = 64;

    private final byte[] data;
    private int offset;

    private CborReader(byte[] data) {
        this.data = data;
    }

    /**
     * @return the single item {@code data} holds
     * @throws CborException when it is malformed or followed by trailing bytes
     */
    public static Object decode(byte[] data) {
        CborReader reader = new CborReader(data);
        Object value = reader.item(0);
        if (reader.offset != data.length) {
            throw new CborException("trailing bytes after the document at offset " + reader.offset);
        }
        return value;
    }

    private static final Object BREAK = new Object();

    private Object item(int depth) {
        if (depth > MAX_DEPTH) {
            throw new CborException("nesting deeper than " + MAX_DEPTH);
        }
        int initial = next();
        int major = initial >>> 5;
        int info = initial & 0x1f;
        if (initial == 0xff) {
            return BREAK;
        }
        if (info == 31) {
            return switch (major) {
                case 2, 3 -> indefiniteString(major, depth);
                case 4 -> {
                    List<Object> items = new ArrayList<>();
                    for (Object item = item(depth + 1); item != BREAK; item = item(depth + 1)) {
                        items.add(item);
                    }
                    yield items;
                }
                case 5 -> {
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (Object key = item(depth + 1); key != BREAK; key = item(depth + 1)) {
                        put(map, key, value(depth));
                    }
                    yield map;
                }
                default -> throw new CborException("indefinite length for major type " + major);
            };
        }
        long argument = argument(info);
        return switch (major) {
            case 0 -> argument >= 0 ? (Object) argument : new BigInteger(Long.toUnsignedString(argument));
            case 1 -> {
                if (argument < 0) {
                    throw new CborException("negative integer out of range");
                }
                yield -1 - argument;
            }
            case 2 -> {
                int start = offset;
                yield Arrays.copyOfRange(data, start, advance(argument));
            }
            case 3 -> {
                int start = offset;
                yield new String(data, start, advance(argument) - start, StandardCharsets.UTF_8);
            }
            case 4 -> {
                List<Object> items = new ArrayList<>();
                for (long i = 0; i < length(argument); i++) {
                    items.add(value(depth));
                }
                yield items;
            }
            case 5 -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                for (long i = 0; i < length(argument); i++) {
                    put(map, value(depth), value(depth));
                }
                yield map;
            }
            case 6 -> new Tagged(argument, value(depth));
            default -> switch (info) {
                case 20 -> Boolean.FALSE;
                case 21 -> Boolean.TRUE;
                case 22, 23 -> null;
                default -> throw new CborException("unsupported simple value or float " + info);
            };
        };
    }

    private Object value(int depth) {
        Object value = item(depth + 1);
        if (value == BREAK) {
            throw new CborException("unexpected break");
        }
        return value;
    }

    private static void put(Map<Object, Object> map, Object key, Object value) {
        Object normalized = key instanceof byte[] bytes ? new String(bytes, StandardCharsets.ISO_8859_1) : key;
        if (map.containsKey(normalized)) {
            throw new CborException("duplicate map key " + key);
        }
        map.put(normalized, value);
    }

    private Object indefiniteString(int major, int depth) {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        for (Object chunk = item(depth + 1); chunk != BREAK; chunk = item(depth + 1)) {
            if (major == 2 && chunk instanceof byte[] bytes) {
                chunks.writeBytes(bytes);
            } else if (major == 3 && chunk instanceof String text) {
                chunks.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            } else {
                throw new CborException("bad chunk in an indefinite-length string");
            }
        }
        byte[] all = chunks.toByteArray();
        return major == 2 ? all : new String(all, StandardCharsets.UTF_8);
    }

    private long length(long argument) {
        if (argument < 0 || argument > data.length) {
            throw new CborException("length " + Long.toUnsignedString(argument) + " exceeds the document");
        }
        return argument;
    }

    private int advance(long length) {
        if (length < 0 || length > data.length - offset) {
            throw new CborException("truncated string");
        }
        offset += (int) length;
        return offset;
    }

    private int next() {
        if (offset >= data.length) {
            throw new CborException("truncated document");
        }
        return data[offset++] & 0xff;
    }

    private long argument(int info) {
        if (info < 24) {
            return info;
        }
        int bytes = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new CborException("reserved additional information " + info);
        };
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value = (value << 8) | next();
        }
        return value;
    }

    /** A malformed document. */
    public static final class CborException extends RuntimeException {
        public CborException(String message) {
            super(message);
        }
    }
}
