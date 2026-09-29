package org.yanoproject.ledger.rules.conway.tx;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decoders for the bounded Conway field types that certificates, proposals and votes carry, with the bounds Haskell's
 * decoders enforce at decoder version 9 and later (cardano-ledger {@code f649f975}); anything else is a
 * {@link TxDecodingException} ({@code ENGINE.DecodingFailure}).
 *
 * <ul>
 *   <li>{@code Url} and {@code DnsName}: UTF-8 text (each chunk valid, {@code decodeString}) of at most 128 bytes
 *       ({@code textDecCBOR 128}, BaseTypes.hs:678-697).</li>
 *   <li>{@code Anchor}: {@code [url, hash]}, the hash a 32-byte {@code SafeHash} (BaseTypes.hs:996-1004, the
 *       record form below decoder version 12).</li>
 *   <li>{@code UnitInterval}: tag 30, two integers (bignums tagged 2/3 too), a non-zero denominator, the reduced ratio in [0, 1] with
 *       numerator and denominator in {@code Word64} ({@code decodeRationalWithTag}, Plain.hs:159-167;
 *       {@code boundRational}, BaseTypes.hs:386-402).</li>
 *   <li>{@code StakePoolRelay} (StakePool.hs:406-421): {@code [0, port / null, ipv4 / null, ipv6 / null]},
 *       {@code [1, port / null, dns_name]}, {@code [2, dns_name]}; a port is a {@code Word16}, an IPv4 address 4
 *       bytes and an IPv6 address 16 ({@code binaryGetDecoder} refuses left-over bytes, Decoder.hs:1345-1377).</li>
 * </ul>
 */
final class BoundedFields {

    /** {@code textDecCBOR 128} at decoder version 9 and later. */
    static final int MAX_TEXT_BYTES = 128;
    static final int ANCHOR_HASH_LENGTH = 32;
    private static final BigInteger WORD64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private BoundedFields() {
    }

    /** Reads a {@code Url}. */
    static void url(CborReader reader, String what) {
        text(reader, what + " url");
    }

    /** Reads a {@code DnsName}. */
    static void dnsName(CborReader reader) {
        text(reader, "relay DNS name");
    }

    private static void text(CborReader reader, String what) {
        byte[] text = reader.readDefiniteText();
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new TxDecodingException(what + " is not valid UTF-8");
        }
        int length = text.length;
        if (length > MAX_TEXT_BYTES) {
            throw new TxDecodingException(what + " exceeds " + MAX_TEXT_BYTES + " bytes: " + length);
        }
    }

    /** Reads an {@code Anchor}. */
    static void anchor(CborReader reader, String what) {
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException(what + " anchor is a two-element array");
        }
        url(reader, what + " anchor");
        byte[] hash = reader.readDefiniteBytes();
        if (hash.length != ANCHOR_HASH_LENGTH) {
            throw new TxDecodingException(what + " anchor data hash of " + hash.length + " bytes");
        }
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException(what + " anchor is a two-element array");
        }
    }

    /** Reads {@code anchor / null}. */
    static void anchorOrNull(CborReader reader, String what) {
        if (reader.peekNull()) {
            reader.readNull();
        } else {
            anchor(reader, what);
        }
    }

    /** Reads a {@code UnitInterval}. */
    static void unitInterval(CborReader reader, String what) {
        boundedRational(reader, what, true);
    }

    /**
     * Reads a {@code UnitInterval} ({@code unit}) or a {@code NonNegativeInterval}: tag 30, two integers, a non-zero
     * denominator, the reduced ratio in [0, 1] or [0, 2^64 - 1] with numerator and denominator in {@code Word64}
     * ({@code decodeRationalWithTag}, Plain.hs:159-167; {@code fromRationalBoundedRatio}, BaseTypes.hs:346-376).
     *
     * @return the reduced numerator and denominator
     */
    static BigInteger[] boundedRational(CborReader reader, String what, boolean unit) {
        if (!reader.skipTag(30)) {
            throw new TxDecodingException(what + " is not a tag-30 rational");
        }
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException(what + " rational has " + length + " elements");
        }
        BigInteger n = integer(reader);
        BigInteger d = integer(reader);
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException(what + " rational has more than 2 elements");
        }
        if (d.signum() == 0) {
            throw new TxDecodingException(what + ": denominator cannot be zero");
        }
        // n % d: reduced, denominator positive
        BigInteger gcd = n.gcd(d);
        BigInteger numerator = n.divide(gcd);
        BigInteger denominator = d.divide(gcd);
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        boolean outOfRange = numerator.signum() < 0 || denominator.compareTo(WORD64_MAX) > 0
                || (unit ? numerator.compareTo(denominator) > 0 : numerator.compareTo(WORD64_MAX) > 0);
        if (outOfRange) {
            throw new TxDecodingException(what + " " + n + "/" + d + (unit ? " is not in [0, 1]"
                    : " is not a non-negative Word64 ratio"));
        }
        return new BigInteger[]{numerator, denominator};
    }

    /**
     * cborg's {@code decodeInteger}: a major-0/1 integer or a bignum, tag 2 (non-negative) or 3 (negative) with the
     * one-byte tag head {@code c2}/{@code c3} and a definite byte string (a longer tag head is a plain tag, which
     * {@code decodeInteger} refuses).
     */
    static BigInteger integer(CborReader reader) {
        int initial = reader.peekInitialByte();
        if (initial == 0xc2 || initial == 0xc3) {
            reader.readTag();
            BigInteger magnitude = new BigInteger(1, reader.readDefiniteBytes());
            return initial == 0xc2 ? magnitude : magnitude.negate().subtract(BigInteger.ONE);
        }
        return reader.readInteger();
    }

    /** Reads a {@code StakePoolRelay}. */
    static void relay(CborReader reader) {
        long length = reader.readArrayHeader();
        long tag = reader.readUnsignedLong();
        long expected;
        switch ((int) Math.min(tag, 3)) {
            case 0 -> {
                portOrNull(reader);
                ipOrNull(reader, 4);
                ipOrNull(reader, 16);
                expected = 4;
            }
            case 1 -> {
                portOrNull(reader);
                dnsName(reader);
                expected = 3;
            }
            case 2 -> {
                dnsName(reader);
                expected = 2;
            }
            default -> throw new TxDecodingException("unknown relay tag " + tag);
        }
        if (length == CborReader.INDEFINITE ? reader.hasNext(length, expected) : length != expected) {
            throw new TxDecodingException("relay tag " + tag + " has " + expected + " elements");
        }
    }

    private static void portOrNull(CborReader reader) {
        if (reader.peekNull()) {
            reader.readNull();
            return;
        }
        if (reader.readUnsignedLong() > 0xFFFF) {
            throw new TxDecodingException("relay port exceeds Word16");
        }
    }

    private static void ipOrNull(CborReader reader, int bytes) {
        if (reader.peekNull()) {
            reader.readNull();
            return;
        }
        int length = reader.readDefiniteBytes().length;
        if (length != bytes) {
            throw new TxDecodingException("relay IPv" + (bytes == 4 ? 4 : 6) + " address of " + length + " bytes");
        }
    }
}
