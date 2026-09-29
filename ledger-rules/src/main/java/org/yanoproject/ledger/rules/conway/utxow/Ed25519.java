package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;

import java.math.BigInteger;

/**
 * Ed25519 signature verification with the semantics of the node's {@code Ed25519DSIGN}
 * (cardano-crypto-class), which calls libsodium's {@code crypto_sign_ed25519_verify_detached} (1.0.18,
 * {@code crypto_sign/ed25519/ref10/open.c}):
 *
 * <ol>
 *   <li>the scalar {@code S} (signature bytes 32–63, little endian) must be canonical, {@code S < L};</li>
 *   <li>{@code R} (signature bytes 0–31) must not be one of the small-order encodings;</li>
 *   <li>the public key must be a canonical encoding ({@code y < p}) and not of small order;</li>
 *   <li>the public key must decode to a curve point, and {@code [S]B - [h]A} must encode to exactly {@code R}, with
 *       {@code h = SHA-512(R || A || message) mod L} (cofactorless, byte comparison).</li>
 * </ol>
 *
 * <p>Steps 1–3 are made here, exactly as libsodium's {@code sc25519_is_canonical},
 * {@code ge25519_has_small_order} and {@code ge25519_is_canonical}; step 4 is the cofactorless verification of
 * the node's crypto provider (CCL's {@link SigningProvider}), which decodes the key and compares the recomputed
 * {@code R} bytewise like libsodium.</p>
 */
final class Ed25519 {

    /** The group order {@code L = 2^252 + 27742317777372353535851937790883648493}. */
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    /** libsodium's {@code ge25519_has_small_order} blacklist (last byte compared without its top bit). */
    private static final byte[][] SMALL_ORDER = {
            hex("0000000000000000000000000000000000000000000000000000000000000000"),
            hex("0100000000000000000000000000000000000000000000000000000000000000"),
            hex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"),
            hex("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a"),
            hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            hex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")};

    private Ed25519() {
    }

    /**
     * @param publicKey the 32-byte verification key
     * @param signature the 64-byte signature
     * @param message   the signed message (a transaction id)
     * @return true when libsodium would accept the signature
     */
    static boolean verify(byte[] publicKey, byte[] signature, byte[] message) {
        if (publicKey.length != 32 || signature.length != 64) {
            return false;
        }
        if (!scalarIsCanonical(signature) || hasSmallOrder(signature, 0) || !pointIsCanonical(publicKey)
                || hasSmallOrder(publicKey, 0)) {
            return false;
        }
        try {
            SigningProvider provider = CryptoConfiguration.INSTANCE.getSigningProvider();
            return provider.verify(signature, message, publicKey);
        } catch (RuntimeException e) {
            // A key that does not decode to a curve point (ge25519_frombytes_negate_vartime fails).
            return false;
        }
    }

    /** {@code sc25519_is_canonical(sig + 32)}: {@code S < L}. */
    static boolean scalarIsCanonical(byte[] signature) {
        byte[] le = new byte[32];
        System.arraycopy(signature, 32, le, 0, 32);
        return littleEndian(le).compareTo(L) < 0;
    }

    /** {@code ge25519_has_small_order}: the first 31 bytes and the last byte's low 7 bits match a blacklisted point. */
    static boolean hasSmallOrder(byte[] bytes, int offset) {
        for (byte[] point : SMALL_ORDER) {
            boolean match = true;
            for (int j = 0; j < 31 && match; j++) {
                match = bytes[offset + j] == point[j];
            }
            if (match && (bytes[offset + 31] & 0x7f) == (point[31] & 0xff)) {
                return true;
            }
        }
        return false;
    }

    /** {@code ge25519_is_canonical}: the encoded {@code y} (sign bit ignored) is below {@code p = 2^255 - 19}. */
    static boolean pointIsCanonical(byte[] point) {
        boolean allOnes = (point[31] & 0x7f) == 0x7f;
        for (int i = 30; i > 0 && allOnes; i--) {
            allOnes = (point[i] & 0xff) == 0xff;
        }
        // y >= p exactly when bytes 1..30 are 0xff, byte 31 is 0x7f (ignoring the sign) and byte 0 >= 0xed.
        return !(allOnes && (point[0] & 0xff) >= 0xed);
    }

    private static BigInteger littleEndian(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) {
            be[i] = le[le.length - 1 - i];
        }
        return new BigInteger(1, be);
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
