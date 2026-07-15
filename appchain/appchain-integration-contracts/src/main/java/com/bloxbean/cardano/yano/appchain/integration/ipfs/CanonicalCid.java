package com.bloxbean.cardano.yano.appchain.integration.ipfs;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Canonical, bounded CIDv1 binary representation of an IPFS Content Identifier (CID).
 *
 * <p>The connector contract stores CIDv1 bytes, not a provider-specific textual form. Legacy CIDv0 is accepted only
 * as base58btc client input in its fixed {@code dag-pb}/{@code sha2-256} form and is immediately normalized to CIDv1.
 * CIDv1 accepts registered-code-shaped unsigned values without embedding a multicodec registry; an executor may
 * apply a narrower policy for the codecs it supports.</p>
 */
public final class CanonicalCid {
    /** Maximum accepted canonical CIDv1 binary length in bytes. */
    public static final int MAX_BINARY_LENGTH = 96;
    /** Maximum accepted multihash digest length in bytes. */
    public static final int MAX_DIGEST_LENGTH = 64;

    /** Multicodec value for the legacy CIDv0 {@code dag-pb} codec. */
    public static final long DAG_PB_CODEC = 0x70;
    /** Multihash value for SHA2-256. */
    public static final long SHA2_256_MULTIHASH = 0x12;
    /** SHA2-256 digest length in bytes. */
    public static final int SHA2_256_DIGEST_LENGTH = 32;

    private static final int MAX_TEXT_LENGTH = 160;
    private static final char CIDV1_BASE32_PREFIX = 'b';
    private static final char CIDV1_BASE58_PREFIX = 'z';
    private static final char[] BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE_58 = BigInteger.valueOf(58);

    private final byte[] bytes;
    private final int version;
    private final long codec;
    private final long multihashCode;
    private final int digestLength;
    private final int digestOffset;

    private CanonicalCid(byte[] bytes, int version, long codec, long multihashCode,
                         int digestLength, int digestOffset) {
        this.bytes = bytes;
        this.version = version;
        this.codec = codec;
        this.multihashCode = multihashCode;
        this.digestLength = digestLength;
        this.digestOffset = digestOffset;
    }

    /**
     * Parses and validates canonical CIDv1 binary bytes.
     *
     * @param value the CIDv1 bytes; defensively copied
     * @return the canonical CID
     */
    public static CanonicalCid fromBytes(byte[] value) {
        if (value == null || value.length == 0) {
            throw invalid();
        }
        if (value.length > MAX_BINARY_LENGTH) {
            throw invalid();
        }

        byte[] bytes = value.clone();
        if (isCidV0(bytes)) {
            throw invalid();
        }

        Cursor cursor = new Cursor();
        long version = readUnsignedVarint(bytes, cursor);
        if (version != 1) {
            throw invalid();
        }

        long codec = readUnsignedVarint(bytes, cursor);
        if (codec == 0) {
            throw invalid();
        }

        long multihashCode = readUnsignedVarint(bytes, cursor);
        long digestLengthValue = readUnsignedVarint(bytes, cursor);
        if (digestLengthValue == 0 || digestLengthValue > MAX_DIGEST_LENGTH) {
            throw invalid();
        }

        int digestLength = (int) digestLengthValue;
        int remaining = bytes.length - cursor.position;
        if (remaining != digestLength) {
            throw invalid();
        }

        return new CanonicalCid(bytes, 1, codec, multihashCode, digestLength, cursor.position);
    }

    /**
     * Parses CIDv0 base58btc or CIDv1 {@code b...}/{@code z...} multibase text.
     *
     * @param text the bounded CID text
     * @return a canonical CIDv1 representation
     */
    public static CanonicalCid fromText(String text) {
        if (text == null || text.isEmpty()) {
            throw invalid();
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw invalid();
        }

        char prefix = text.charAt(0);
        if (prefix == CIDV1_BASE32_PREFIX) {
            String encoded = text.substring(1);
            byte[] decoded = decodeBase32(encoded);
            if (!encodeBase32(decoded).equals(encoded)) {
                throw invalid();
            }
            CanonicalCid cid = fromBytes(decoded);
            if (cid.version != 1) {
                throw invalid();
            }
            return cid;
        }
        if (prefix == CIDV1_BASE58_PREFIX) {
            String encoded = text.substring(1);
            byte[] decoded = decodeBase58(encoded);
            if (!encodeBase58(decoded).equals(encoded)) {
                throw invalid();
            }
            CanonicalCid cid = fromBytes(decoded);
            if (cid.version != 1) {
                throw invalid();
            }
            return cid;
        }

        byte[] decoded = decodeBase58(text);
        if (!encodeBase58(decoded).equals(text)) {
            throw invalid();
        }
        if (!isCidV0(decoded)) {
            throw invalid();
        }
        byte[] normalized = new byte[2 + decoded.length];
        normalized[0] = 1;
        normalized[1] = (byte) DAG_PB_CODEC;
        System.arraycopy(decoded, 0, normalized, 2, decoded.length);
        return fromBytes(normalized);
    }

    /**
     * Alias for {@link #fromText(String)}.
     *
     * @param text the bounded CID text
     * @return a canonical CIDv1 representation
     */
    public static CanonicalCid parse(String text) {
        return fromText(text);
    }

    /**
     * Returns the canonical CID version.
     *
     * @return always {@code 1}
     */
    public int version() {
        return version;
    }

    /**
     * Returns the CIDv1 content codec. Legacy CIDv0 text is normalized to {@code dag-pb} ({@code 0x70}).
     *
     * @return the unsigned multicodec value
     */
    public long codec() {
        return codec;
    }

    /**
     * Returns the multihash algorithm code.
     *
     * @return the unsigned multihash code
     */
    public long multihashCode() {
        return multihashCode;
    }

    /**
     * Returns the multihash digest length.
     *
     * @return the digest length in bytes
     */
    public int digestLength() {
        return digestLength;
    }

    /**
     * Returns a defensive copy of the multihash digest.
     *
     * @return the digest bytes
     */
    public byte[] digest() {
        return Arrays.copyOfRange(bytes, digestOffset, digestOffset + digestLength);
    }

    /**
     * Returns a defensive copy of the canonical CIDv1 bytes.
     *
     * @return the canonical CIDv1 bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Alias for {@link #bytes()}.
     *
     * @return the canonical CIDv1 bytes
     */
    public byte[] toBytes() {
        return bytes();
    }

    /**
     * Returns {@code z}-prefixed CIDv1 base58btc text.
     *
     * @return the CIDv1 base58btc representation
     */
    public String toBase58Text() {
        return CIDV1_BASE58_PREFIX + encodeBase58(bytes);
    }

    /**
     * Returns canonical lowercase CIDv1 base32 multibase text.
     *
     * @return the canonical CIDv1 base32 representation
     */
    public String toBase32Text() {
        return CIDV1_BASE32_PREFIX + encodeBase32(bytes);
    }

    /**
     * Returns the preferred canonical textual representation.
     *
     * @return lowercase CIDv1 base32 multibase text
     */
    public String canonicalText() {
        return toString();
    }

    @Override
    public String toString() {
        return toBase32Text();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CanonicalCid cid && Arrays.equals(bytes, cid.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    private static boolean isCidV0(byte[] bytes) {
        return bytes.length == 2 + SHA2_256_DIGEST_LENGTH
                && Byte.toUnsignedInt(bytes[0]) == SHA2_256_MULTIHASH
                && Byte.toUnsignedInt(bytes[1]) == SHA2_256_DIGEST_LENGTH;
    }

    private static long readUnsignedVarint(byte[] bytes, Cursor cursor) {
        int start = cursor.position;
        long value = 0;

        for (int index = 0; index < 9; index++) {
            if (cursor.position >= bytes.length) {
                throw invalid();
            }
            int current = Byte.toUnsignedInt(bytes[cursor.position++]);
            long group = current & 0x7fL;
            value |= group << (index * 7);

            if ((current & 0x80) == 0) {
                if (cursor.position - start > 1 && group == 0) {
                    throw invalid();
                }
                return value;
            }
        }
        throw invalid();
    }

    private static String encodeBase32(byte[] input) {
        StringBuilder encoded = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | Byte.toUnsignedInt(value);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(BASE32_ALPHABET[(buffer >>> bits) & 0x1f]);
            }
            buffer &= bits == 0 ? 0 : (1 << bits) - 1;
        }
        if (bits != 0) {
            encoded.append(BASE32_ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return encoded.toString();
    }

    private static byte[] decodeBase32(String encoded) {
        if (encoded.isEmpty()) {
            throw invalid();
        }
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(encoded.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int index = 0; index < encoded.length(); index++) {
            char character = encoded.charAt(index);
            int value;
            if (character >= 'a' && character <= 'z') {
                value = character - 'a';
            } else if (character >= '2' && character <= '7') {
                value = character - '2' + 26;
            } else {
                throw invalid();
            }

            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                decoded.write((buffer >>> bits) & 0xff);
                buffer &= bits == 0 ? 0 : (1 << bits) - 1;
            }
        }
        if (bits >= 5 || buffer != 0) {
            throw invalid();
        }
        return decoded.toByteArray();
    }

    private static String encodeBase58(byte[] input) {
        int leadingZeroes = 0;
        while (leadingZeroes < input.length && input[leadingZeroes] == 0) {
            leadingZeroes++;
        }

        BigInteger value = new BigInteger(1, input);
        StringBuilder encoded = new StringBuilder(input.length * 2);
        while (value.signum() != 0) {
            BigInteger[] division = value.divideAndRemainder(BASE_58);
            encoded.append(BASE58_ALPHABET.charAt(division[1].intValue()));
            value = division[0];
        }
        encoded.append("1".repeat(leadingZeroes));
        return encoded.reverse().toString();
    }

    private static byte[] decodeBase58(String encoded) {
        if (encoded.isEmpty()) {
            throw invalid();
        }

        BigInteger value = BigInteger.ZERO;
        for (int index = 0; index < encoded.length(); index++) {
            int digit = BASE58_ALPHABET.indexOf(encoded.charAt(index));
            if (digit < 0) {
                throw invalid();
            }
            value = value.multiply(BASE_58).add(BigInteger.valueOf(digit));
            if (value.bitLength() > MAX_BINARY_LENGTH * 8) {
                throw invalid();
            }
        }

        byte[] magnitude = value.toByteArray();
        int magnitudeOffset = magnitude.length > 1 && magnitude[0] == 0 ? 1 : 0;
        int leadingZeroes = 0;
        while (leadingZeroes < encoded.length() && encoded.charAt(leadingZeroes) == '1') {
            leadingZeroes++;
        }
        int magnitudeLength = value.signum() == 0 ? 0 : magnitude.length - magnitudeOffset;
        if (leadingZeroes + magnitudeLength > MAX_BINARY_LENGTH) {
            throw invalid();
        }

        byte[] decoded = new byte[leadingZeroes + magnitudeLength];
        if (magnitudeLength != 0) {
            System.arraycopy(magnitude, magnitudeOffset, decoded, leadingZeroes, magnitudeLength);
        }
        return decoded;
    }

    private static ConnectorContractException invalid() {
        return new ConnectorContractException(ConnectorErrorCode.INVALID_PAYLOAD);
    }

    private static final class Cursor {
        private int position;
    }
}
