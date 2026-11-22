package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Decoders for the bounded Conway field types that {@link HaskellPolicy} checks, with the bounds
 * Haskell's decoders enforce at decoder version 9 and later (cardano-ledger {@code f649f975}); anything else is a
 * {@link TxDecodingException} ({@code ENGINE.DecodingFailure}).
 *
 * <ul>
 *   <li>{@code Url} and {@code DnsName}: UTF-8 text (each chunk valid, {@code decodeString}) of at most 128 bytes
 *       ({@code textDecCBOR 128}, BaseTypes.hs:678-697).</li>
 *   <li>{@code UnitInterval}: tag 30, two integers (bignums tagged 2/3 too), a non-zero denominator, the reduced
 *       ratio in [0, 1] with numerator and denominator in {@code Word64} ({@code decodeRationalWithTag},
 *       Decoder.hs:514-526; {@code boundRational}, BaseTypes.hs:386-402).</li>
 * </ul>
 */
final class BoundedFields {

    /** {@code textDecCBOR 128} at decoder version 9 and later. */
    static final int MAX_TEXT_BYTES = 128;
    private static final BigInteger WORD64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private BoundedFields() {
    }

    /**
     * Reads a {@code Url}.
     *
     * @param field the field it is, named only when it is rejected
     */
    static void url(CborSpan item, Supplier<String> field) {
        text(item, () -> field.get() + " url");
    }

    /** Reads a {@code DnsName}. */
    static void dnsName(CborSpan item) {
        text(item, () -> "relay DNS name");
    }

    private static void text(CborSpan item, Supplier<String> what) {
        byte[] text = StrictCbor.definiteText(item);
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new TxDecodingException(what.get() + " is not valid UTF-8");
        }
        int length = text.length;
        if (length > MAX_TEXT_BYTES) {
            throw new TxDecodingException(what.get() + " exceeds " + MAX_TEXT_BYTES + " bytes: " + length);
        }
    }

    /**
     * Reads a {@code UnitInterval} ({@code unit}) or a {@code NonNegativeInterval}: a {@link #ratio}, in [0, 1] or
     * [0, 2^64 - 1] with numerator and denominator in {@code Word64} ({@code fromRationalBoundedRatio},
     * BaseTypes.hs:346-376).
     */
    static void boundedRational(CborSpan item, String what, boolean unit) {
        BigInteger[] ratio = ratio(item, what);
        BigInteger numerator = ratio[0];
        BigInteger denominator = ratio[1];
        boolean outOfRange = numerator.signum() < 0 || denominator.compareTo(WORD64_MAX) > 0
                || (unit ? numerator.compareTo(denominator) > 0 : numerator.compareTo(WORD64_MAX) > 0);
        if (outOfRange) {
            throw new TxDecodingException(what + " " + numerator + "/" + denominator + (unit ? " is not in [0, 1]"
                    : " is not a non-negative Word64 ratio"));
        }
    }

    /**
     * A tag-30 rational as {@code decodeRationalWithTag} reads it (Decoder.hs:514-526): two integers ({@link #integer})
     * in an array, a non-zero denominator, and the value {@code n % d}, reduced with the denominator positive.
     *
     * @return the reduced numerator and denominator
     */
    static BigInteger[] ratio(CborSpan item, String what) {
        if (item.tag() != 30) {
            throw new TxDecodingException(what + " is not a tag-30 rational");
        }
        List<CborSpan> parts = StrictCbor.array(item.untag());
        if (parts.size() != 2) {
            throw new TxDecodingException(what + " rational has " + parts.size() + " elements");
        }
        BigInteger n = integer(parts.get(0));
        BigInteger d = integer(parts.get(1));
        if (d.signum() == 0) {
            throw new TxDecodingException(what + ": denominator cannot be zero");
        }
        BigInteger gcd = d.signum() < 0 ? n.gcd(d).negate() : n.gcd(d);
        return new BigInteger[]{n.divide(gcd), d.divide(gcd)};
    }

    /**
     * cborg's {@code decodeInteger}: a major-0/1 integer or a bignum, tag 2 (non-negative) or 3 (negative) with the
     * one-byte tag head {@code c2}/{@code c3} and a definite byte string (a longer tag head is a plain tag, which
     * {@code decodeInteger} refuses).
     */
    static BigInteger integer(CborSpan item) {
        int initial = item.buffer()[item.offset()] & 0xff;
        if (initial == 0xc2 || initial == 0xc3) {
            BigInteger magnitude = new BigInteger(1, StrictCbor.definiteBytes(item.untag()));
            return initial == 0xc2 ? magnitude : magnitude.negate().subtract(BigInteger.ONE);
        }
        return StrictCbor.integer(item);
    }
}
