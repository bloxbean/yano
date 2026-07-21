package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;

import java.security.MessageDigest;

/**
 * Strict, allocation-bounded parser for the three Kubo response envelopes used
 * by the pin adapter.
 *
 * <p>This is intentionally not a general JSON data-binding layer. It accepts
 * only the exact {@code pin/ls}, {@code pin/add}, and Kubo error schemas and
 * normalizes every syntax or shape failure without retaining provider data.</p>
 */
final class KuboJsonResponseParser {
    private static final int MAX_DOCUMENT_BYTES = 16 * 1_024;
    private static final int MAX_NESTING_DEPTH = 4;
    private static final int MAX_TOKENS = 64;
    private static final int MAX_NAME_CHARS = 160;
    private static final int MAX_STRING_CHARS = 512;
    private static final int MAX_NUMBER_CHARS = 12;

    private KuboJsonResponseParser() {
    }

    static PinState parsePinState(byte[] body, CanonicalCid expectedCid) {
        StrictReader reader = new StrictReader(body);
        reader.beginObject();
        boolean seenKeys = false;
        PinState state = null;
        boolean first = true;
        while (!reader.tryEndObject()) {
            if (!first) {
                reader.comma();
            }
            first = false;
            String field = reader.name();
            reader.colon();
            if (!field.equals("Keys") || seenKeys) {
                throw malformed();
            }
            seenKeys = true;
            state = parseKeys(reader, expectedCid);
        }
        if (!seenKeys) {
            throw malformed();
        }
        reader.requireDocumentEnd();
        return state;
    }

    static void parseAddAcknowledgement(byte[] body, CanonicalCid expectedCid) {
        StrictReader reader = new StrictReader(body);
        reader.beginObject();
        boolean seenPins = false;
        boolean first = true;
        while (!reader.tryEndObject()) {
            if (!first) {
                reader.comma();
            }
            first = false;
            String field = reader.name();
            reader.colon();
            if (!field.equals("Pins") || seenPins) {
                throw malformed();
            }
            seenPins = true;
            reader.beginArray();
            String returnedCid = reader.string();
            requireMatchingCid(returnedCid, expectedCid);
            if (!reader.tryEndArray()) {
                throw malformed();
            }
        }
        if (!seenPins) {
            throw malformed();
        }
        reader.requireDocumentEnd();
    }

    static String parseError(byte[] body) {
        StrictReader reader = new StrictReader(body);
        reader.beginObject();
        boolean seenMessage = false;
        boolean seenCode = false;
        boolean seenType = false;
        String message = null;
        boolean first = true;
        while (!reader.tryEndObject()) {
            if (!first) {
                reader.comma();
            }
            first = false;
            String field = reader.name();
            reader.colon();
            switch (field) {
                case "Message" -> {
                    if (seenMessage) {
                        throw malformed();
                    }
                    seenMessage = true;
                    message = reader.string();
                }
                case "Code" -> {
                    if (seenCode) {
                        throw malformed();
                    }
                    seenCode = true;
                    reader.zeroInteger();
                }
                case "Type" -> {
                    if (seenType) {
                        throw malformed();
                    }
                    seenType = true;
                    if (!reader.string().equals("error")) {
                        throw malformed();
                    }
                }
                default -> throw malformed();
            }
        }
        if (!seenMessage || !seenCode || !seenType) {
            throw malformed();
        }
        reader.requireDocumentEnd();
        return message;
    }

    private static PinState parseKeys(StrictReader reader, CanonicalCid expectedCid) {
        reader.beginObject();
        if (reader.tryEndObject()) {
            throw malformed();
        }
        String returnedCid = reader.name();
        requireMatchingCid(returnedCid, expectedCid);
        reader.colon();
        PinState state = parsePinEntry(reader);
        // Kubo must return exactly the requested key. Reject a comma, another
        // key, or a trailing comma without parsing provider-controlled values.
        if (!reader.tryEndObject()) {
            throw malformed();
        }
        return state;
    }

    private static PinState parsePinEntry(StrictReader reader) {
        reader.beginObject();
        boolean seenType = false;
        boolean seenName = false;
        String pinType = null;
        boolean first = true;
        while (!reader.tryEndObject()) {
            if (!first) {
                reader.comma();
            }
            first = false;
            String field = reader.name();
            reader.colon();
            switch (field) {
                case "Type" -> {
                    if (seenType) {
                        throw malformed();
                    }
                    seenType = true;
                    pinType = reader.string();
                }
                case "Name" -> {
                    if (seenName) {
                        throw malformed();
                    }
                    seenName = true;
                    if (!reader.string().isEmpty()) {
                        throw malformed();
                    }
                }
                default -> throw malformed();
            }
        }
        if (!seenType) {
            throw malformed();
        }
        return switch (pinType) {
            case "direct" -> PinState.DIRECT;
            case "recursive" -> PinState.RECURSIVE;
            case "indirect" -> PinState.INDIRECT;
            default -> parseIndirectThrough(pinType);
        };
    }

    private static PinState parseIndirectThrough(String pinType) {
        String prefix = "indirect through ";
        if (!pinType.startsWith(prefix)) {
            throw malformed();
        }
        String ancestorText = pinType.substring(prefix.length());
        try {
            CanonicalCid ancestor = CanonicalCid.fromText(ancestorText);
            if (ancestor.bytes().length == 0) {
                throw malformed();
            }
            return PinState.INDIRECT;
        } catch (IpfsProviderException normalized) {
            throw normalized;
        } catch (RuntimeException invalidAncestor) {
            throw malformed();
        }
    }

    private static void requireMatchingCid(String text, CanonicalCid expectedCid) {
        try {
            CanonicalCid returned = CanonicalCid.fromText(text);
            if (!MessageDigest.isEqual(returned.bytes(), expectedCid.bytes())) {
                throw malformed();
            }
        } catch (IpfsProviderException normalized) {
            throw normalized;
        } catch (RuntimeException invalidCid) {
            throw malformed();
        }
    }

    private static IpfsProviderException malformed() {
        return new IpfsProviderException(ConnectorErrorCode.PROVIDER_REJECTED);
    }

    /** Minimal token reader with no polymorphic values or provider object tree. */
    private static final class StrictReader {
        private final byte[] input;
        private final byte[] containers = new byte[MAX_NESTING_DEPTH];
        private int index;
        private int depth;
        private int tokens;

        private StrictReader(byte[] input) {
            if (input == null || input.length > MAX_DOCUMENT_BYTES) {
                throw malformed();
            }
            this.input = input;
        }

        private void beginObject() {
            beginContainer((byte) '{');
        }

        private void beginArray() {
            beginContainer((byte) '[');
        }

        private void beginContainer(byte expected) {
            expect(expected);
            if (depth == MAX_NESTING_DEPTH) {
                throw malformed();
            }
            containers[depth++] = expected;
        }

        private boolean tryEndObject() {
            return tryEndContainer((byte) '{', (byte) '}');
        }

        private boolean tryEndArray() {
            return tryEndContainer((byte) '[', (byte) ']');
        }

        private boolean tryEndContainer(byte expectedOpen, byte expectedClose) {
            if (depth == 0 || containers[depth - 1] != expectedOpen) {
                throw malformed();
            }
            skipWhitespace();
            if (!hasByte() || input[index] != expectedClose) {
                return false;
            }
            index++;
            consumeToken();
            containers[--depth] = 0;
            return true;
        }

        private void comma() {
            expect((byte) ',');
        }

        private void colon() {
            expect((byte) ':');
        }

        private String name() {
            return readString(MAX_NAME_CHARS);
        }

        private String string() {
            return readString(MAX_STRING_CHARS);
        }

        private String readString(int maxChars) {
            skipWhitespace();
            if (!hasByte() || input[index++] != '"') {
                throw malformed();
            }
            consumeToken();
            StringBuilder value = new StringBuilder(Math.min(maxChars, 64));
            while (hasByte()) {
                int current = Byte.toUnsignedInt(input[index++]);
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    appendEscape(value, maxChars);
                } else if (current < 0x20) {
                    throw malformed();
                } else if (current < 0x80) {
                    appendCodePoint(value, current, maxChars);
                } else {
                    appendCodePoint(value, decodeUtf8(current), maxChars);
                }
            }
            throw malformed();
        }

        private void appendEscape(StringBuilder value, int maxChars) {
            if (!hasByte()) {
                throw malformed();
            }
            int escaped = Byte.toUnsignedInt(input[index++]);
            switch (escaped) {
                case '"', '\\', '/' -> appendCodePoint(value, escaped, maxChars);
                case 'b' -> appendCodePoint(value, '\b', maxChars);
                case 'f' -> appendCodePoint(value, '\f', maxChars);
                case 'n' -> appendCodePoint(value, '\n', maxChars);
                case 'r' -> appendCodePoint(value, '\r', maxChars);
                case 't' -> appendCodePoint(value, '\t', maxChars);
                case 'u' -> appendEscapedUnicode(value, maxChars);
                default -> throw malformed();
            }
        }

        private void appendEscapedUnicode(StringBuilder value, int maxChars) {
            int first = hexQuad();
            if (first >= 0xD800 && first <= 0xDBFF) {
                if (index + 2 > input.length || input[index++] != '\\'
                        || input[index++] != 'u') {
                    throw malformed();
                }
                int second = hexQuad();
                if (second < 0xDC00 || second > 0xDFFF) {
                    throw malformed();
                }
                int codePoint = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00);
                appendCodePoint(value, codePoint, maxChars);
            } else if (first >= 0xDC00 && first <= 0xDFFF) {
                throw malformed();
            } else {
                appendCodePoint(value, first, maxChars);
            }
        }

        private int hexQuad() {
            if (input.length - index < 4) {
                throw malformed();
            }
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = hexDigit(Byte.toUnsignedInt(input[index++]));
                if (digit < 0) {
                    throw malformed();
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private int decodeUtf8(int first) {
            if (first >= 0xC2 && first <= 0xDF) {
                int second = continuation();
                return ((first & 0x1F) << 6) | (second & 0x3F);
            }
            if (first >= 0xE0 && first <= 0xEF) {
                int second = continuation();
                int third = continuation();
                if ((first == 0xE0 && second < 0xA0)
                        || (first == 0xED && second >= 0xA0)) {
                    throw malformed();
                }
                return ((first & 0x0F) << 12)
                        | ((second & 0x3F) << 6)
                        | (third & 0x3F);
            }
            if (first >= 0xF0 && first <= 0xF4) {
                int second = continuation();
                int third = continuation();
                int fourth = continuation();
                if ((first == 0xF0 && second < 0x90)
                        || (first == 0xF4 && second >= 0x90)) {
                    throw malformed();
                }
                return ((first & 0x07) << 18)
                        | ((second & 0x3F) << 12)
                        | ((third & 0x3F) << 6)
                        | (fourth & 0x3F);
            }
            throw malformed();
        }

        private int continuation() {
            if (!hasByte()) {
                throw malformed();
            }
            int next = Byte.toUnsignedInt(input[index++]);
            if ((next & 0xC0) != 0x80) {
                throw malformed();
            }
            return next;
        }

        private static void appendCodePoint(StringBuilder value, int codePoint, int maxChars) {
            value.appendCodePoint(codePoint);
            if (value.length() > maxChars) {
                throw malformed();
            }
        }

        private void zeroInteger() {
            skipWhitespace();
            consumeToken();
            int start = index;
            if (hasByte() && input[index] == '-') {
                index++;
            }
            if (!hasByte()) {
                throw malformed();
            }
            int firstDigit = Byte.toUnsignedInt(input[index++]);
            if (firstDigit < '0' || firstDigit > '9') {
                throw malformed();
            }
            boolean zero = firstDigit == '0';
            if (zero && hasByte() && isDigit(input[index])) {
                throw malformed();
            }
            while (hasByte() && isDigit(input[index])) {
                zero = false;
                index++;
            }
            if (index - start > MAX_NUMBER_CHARS || !zero
                    || (hasByte() && !isNumberDelimiter(input[index]))) {
                throw malformed();
            }
        }

        private void requireDocumentEnd() {
            if (depth != 0) {
                throw malformed();
            }
            skipWhitespace();
            if (hasByte()) {
                throw malformed();
            }
        }

        private void expect(byte expected) {
            skipWhitespace();
            if (!hasByte() || input[index++] != expected) {
                throw malformed();
            }
            consumeToken();
        }

        private void consumeToken() {
            if (++tokens > MAX_TOKENS) {
                throw malformed();
            }
        }

        private void skipWhitespace() {
            while (hasByte()) {
                byte current = input[index];
                if (current != ' ' && current != '\t' && current != '\n' && current != '\r') {
                    return;
                }
                index++;
            }
        }

        private boolean hasByte() {
            return index < input.length;
        }

        private static boolean isDigit(byte value) {
            return value >= '0' && value <= '9';
        }

        private static boolean isNumberDelimiter(byte value) {
            return value == ' ' || value == '\t' || value == '\n' || value == '\r'
                    || value == ',' || value == '}' || value == ']';
        }

        private static int hexDigit(int value) {
            if (value >= '0' && value <= '9') {
                return value - '0';
            }
            if (value >= 'a' && value <= 'f') {
                return value - 'a' + 10;
            }
            if (value >= 'A' && value <= 'F') {
                return value - 'A' + 10;
            }
            return -1;
        }
    }
}
