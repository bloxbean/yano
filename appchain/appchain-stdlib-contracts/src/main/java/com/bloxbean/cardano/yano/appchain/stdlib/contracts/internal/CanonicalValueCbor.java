package com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Bounded RFC 8949 deterministic-CBOR validator for authenticated-map values.
 *
 * <p>The parser operates directly on the encoded bytes. It accepts exactly one
 * definite-length item, checks preferred integer/length and float encodings,
 * validates UTF-8, and enforces length-first map-key ordering without building
 * an attacker-controlled object graph.</p>
 */
public final class CanonicalValueCbor {
    public static final int MAX_DEPTH = 32;
    public static final int MAX_ITEMS = 65_536;
    public static final int MAX_CONTAINER_ITEMS = 65_536;

    private CanonicalValueCbor() {
    }

    public static boolean accepts(byte[] value, int maximumBytes) {
        if (value == null || value.length == 0 || maximumBytes < 1
                || value.length > maximumBytes) {
            return false;
        }
        try {
            new Parser(value).parse();
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static final class Parser {
        private final byte[] bytes;
        private int items;

        private Parser(byte[] bytes) {
            this.bytes = bytes;
        }

        private void parse() {
            int end = item(0, 0);
            if (end != bytes.length) {
                throw malformed();
            }
        }

        private int item(int offset, int depth) {
            if (depth > MAX_DEPTH || ++items > MAX_ITEMS || offset >= bytes.length) {
                throw malformed();
            }
            int initial = unsigned(offset++);
            int major = initial >>> 5;
            int additional = initial & 0x1f;
            if (major == 7) {
                return simple(offset, additional);
            }
            Argument argument = argument(offset, additional);
            offset = argument.nextOffset();
            return switch (major) {
                case 0, 1 -> offset;
                case 2 -> string(offset, argument);
                case 3 -> text(offset, argument);
                case 4 -> array(offset, depth, argument);
                case 5 -> map(offset, depth, argument);
                case 6 -> throw malformed();
                default -> throw malformed();
            };
        }

        private int string(int offset, Argument argument) {
            int length = boundedLength(argument, offset);
            return offset + length;
        }

        private int text(int offset, Argument argument) {
            int length = boundedLength(argument, offset);
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, offset, length));
            } catch (CharacterCodingException malformedUtf8) {
                throw malformed();
            }
            return offset + length;
        }

        private int array(int offset, int depth, Argument argument) {
            int count = boundedCount(argument);
            for (int index = 0; index < count; index++) {
                offset = item(offset, depth + 1);
            }
            return offset;
        }

        private int map(int offset, int depth, Argument argument) {
            int count = boundedCount(argument);
            int previousStart = -1;
            int previousEnd = -1;
            for (int index = 0; index < count; index++) {
                int keyStart = offset;
                offset = item(offset, depth + 1);
                if (previousStart >= 0 && compareEncoded(
                        previousStart, previousEnd, keyStart, offset) >= 0) {
                    throw malformed();
                }
                previousStart = keyStart;
                previousEnd = offset;
                offset = item(offset, depth + 1);
            }
            return offset;
        }

        private int simple(int offset, int additional) {
            return switch (additional) {
                case 20, 21, 22 -> offset;
                case 25 -> half(offset);
                case 26 -> single(offset);
                case 27 -> decimal(offset);
                default -> throw malformed();
            };
        }

        private int half(int offset) {
            requireAvailable(offset, Short.BYTES);
            int bits = unsigned(offset) << 8 | unsigned(offset + 1);
            int exponent = bits >>> 10 & 0x1f;
            int mantissa = bits & 0x03ff;
            if (exponent == 0x1f && mantissa != 0 && bits != 0x7e00) {
                throw malformed();
            }
            return offset + Short.BYTES;
        }

        private int single(int offset) {
            requireAvailable(offset, Float.BYTES);
            int bits = (int) readUnsigned(offset, Float.BYTES);
            float value = Float.intBitsToFloat(bits);
            if (Float.isNaN(value)) {
                throw malformed();
            }
            short half = Float.floatToFloat16(value);
            if (Float.floatToRawIntBits(Float.float16ToFloat(half)) == bits) {
                throw malformed();
            }
            return offset + Float.BYTES;
        }

        private int decimal(int offset) {
            requireAvailable(offset, Double.BYTES);
            long bits = readUnsigned(offset, Double.BYTES);
            double value = Double.longBitsToDouble(bits);
            if (Double.isNaN(value)) {
                throw malformed();
            }
            float single = (float) value;
            if (Double.doubleToRawLongBits((double) single) == bits) {
                throw malformed();
            }
            return offset + Double.BYTES;
        }

        private Argument argument(int offset, int additional) {
            if (additional < 24) {
                return new Argument(additional, false, offset);
            }
            int width = switch (additional) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw malformed();
            };
            requireAvailable(offset, width);
            long value = readUnsigned(offset, width);
            if (width == 1 && value < 24
                    || width == 2 && value <= 0xffL
                    || width == 4 && value <= 0xffffL
                    || width == 8 && unsigned(offset) == 0
                    && unsigned(offset + 1) == 0
                    && unsigned(offset + 2) == 0
                    && unsigned(offset + 3) == 0) {
                throw malformed();
            }
            boolean exceedsSignedLong = width == 8 && (bytes[offset] & 0x80) != 0;
            return new Argument(value, exceedsSignedLong, offset + width);
        }

        private int boundedLength(Argument argument, int offset) {
            if (argument.exceedsSignedLong() || argument.value() > bytes.length - offset) {
                throw malformed();
            }
            return (int) argument.value();
        }

        private int boundedCount(Argument argument) {
            if (argument.exceedsSignedLong() || argument.value() > MAX_CONTAINER_ITEMS) {
                throw malformed();
            }
            return (int) argument.value();
        }

        private int compareEncoded(int leftStart, int leftEnd, int rightStart, int rightEnd) {
            int leftLength = leftEnd - leftStart;
            int rightLength = rightEnd - rightStart;
            int lengthComparison = Integer.compare(leftLength, rightLength);
            if (lengthComparison != 0) {
                return lengthComparison;
            }
            for (int index = 0; index < leftLength; index++) {
                int comparison = Integer.compare(
                        unsigned(leftStart + index), unsigned(rightStart + index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        private long readUnsigned(int offset, int width) {
            long value = 0;
            for (int index = 0; index < width; index++) {
                value = value << 8 | unsigned(offset + index);
            }
            return value;
        }

        private int unsigned(int offset) {
            return bytes[offset] & 0xff;
        }

        private void requireAvailable(int offset, int length) {
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw malformed();
            }
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid canonical CBOR value");
    }

    private record Argument(long value, boolean exceedsSignedLong, int nextOffset) {
    }
}
