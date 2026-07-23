package com.bloxbean.cardano.yano.appchain.integration.internal;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorLimits;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Semantic validation shared by encoders and decoders.
 *
 * @hidden internal implementation detail; not part of the connector SDK API
 */
public final class ContractValidation {
    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");
    private static final Pattern HEADER_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern OBJECT_SEGMENT = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._~!$'()+,;=@-]{0,127}");

    private ContractValidation() {
    }

    public static String alias(String value, String field) {
        Objects.requireNonNull(field, "field");
        if (value == null || !ALIAS.matcher(value).matches()
                || asciiLength(value) > ConnectorLimits.MAX_ALIAS_BYTES) {
            throw malformed();
        }
        return value;
    }

    public static String optionalAlias(String value, String field) {
        return value == null ? null : alias(value, field);
    }

    public static String contentType(String value) {
        if (value == null || asciiLength(value) < 1
                || asciiLength(value) > ConnectorLimits.MAX_CONTENT_TYPE_BYTES
                || !CONTENT_TYPE.matcher(value).matches()) {
            throw malformed();
        }
        return value;
    }

    public static String headerName(String value) {
        if (value == null || !HEADER_NAME.matcher(value).matches()
                || value.startsWith("yano-")) {
            throw malformed();
        }
        return value;
    }

    public static String objectKey(String value) {
        if (value == null || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('%') >= 0
                || asciiLength(value) < 1
                || asciiLength(value) > ConnectorLimits.MAX_OBJECT_KEY_BYTES) {
            throw malformed();
        }
        String[] segments = value.split("/", -1);
        if (segments.length > 32) {
            throw malformed();
        }
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")
                    || !OBJECT_SEGMENT.matcher(segment).matches()) {
                throw malformed();
            }
        }
        return value;
    }

    public static String actionType(String value) {
        if (!ConnectorTypes.KAFKA_PUBLISH.equals(value)
                && !ConnectorTypes.OBJECT_PUT.equals(value)
                && !ConnectorTypes.IPFS_PIN.equals(value)) {
            throw malformed();
        }
        return value;
    }

    public static byte[] bytes(byte[] value, int minimum, int maximum) {
        if (value == null || value.length < minimum || value.length > maximum) {
            throw malformed();
        }
        return value.clone();
    }

    public static byte[] hash32(byte[] value) {
        return bytes(value, ConnectorLimits.HASH_BYTES, ConnectorLimits.HASH_BYTES);
    }

    public static long bounded(long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw malformed();
        }
        return value;
    }

    public static String boundedAscii(String value, int minimum, int maximum) {
        if (value == null || asciiLength(value) < minimum || asciiLength(value) > maximum) {
            throw malformed();
        }
        return value;
    }

    public static int asciiLength(String value) {
        if (value == null) {
            return -1;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw malformed();
            }
        }
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    private static ConnectorContractException malformed() {
        return new ConnectorContractException(ConnectorErrorCode.INVALID_PAYLOAD);
    }
}
