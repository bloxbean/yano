package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;

import java.math.BigInteger;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** libsodium's {@code crypto_sign_ed25519_verify_detached} rules, which the node's {@code Ed25519DSIGN} uses. */
class Ed25519Test {

    private static final byte[] MESSAGE = new byte[32];
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    private static byte[] sign(TestKey key) {
        return CryptoConfiguration.INSTANCE.getSigningProvider().sign(MESSAGE, key.secretKey().getBytes());
    }

    @Test
    void aValidSignatureVerifiesAndAnyChangeFails() {
        byte[] signature = sign(TestKey.DEV_42);
        assertThat(Ed25519.verify(TestKey.DEV_42.verificationKey(), signature, MESSAGE)).isTrue();
        assertThat(Ed25519.verify(TestKey.DEV_AA.verificationKey(), signature, MESSAGE)).isFalse();
        byte[] other = MESSAGE.clone();
        other[0] = 1;
        assertThat(Ed25519.verify(TestKey.DEV_42.verificationKey(), signature, other)).isFalse();
    }

    /** {@code sc25519_is_canonical}: {@code S + L} is the same scalar mod L but not canonical. */
    @Test
    void aNonCanonicalScalarIsRejected() {
        byte[] signature = sign(TestKey.DEV_42);
        byte[] le = Arrays.copyOfRange(signature, 32, 64);
        byte[] be = reverse(le);
        byte[] bumped = reverse(toFixed(new BigInteger(1, be).add(L)));
        System.arraycopy(bumped, 0, signature, 32, 32);
        assertThat(Ed25519.scalarIsCanonical(signature)).isFalse();
        assertThat(Ed25519.verify(TestKey.DEV_42.verificationKey(), signature, MESSAGE)).isFalse();
    }

    /** {@code ge25519_has_small_order} for the key and for {@code R}; {@code ge25519_is_canonical} for the key. */
    @Test
    void smallOrderAndNonCanonicalPointsAreRejected() {
        byte[] identity = HexUtil.decodeHexString("01" + "00".repeat(31));
        byte[] signature = sign(TestKey.DEV_42);
        assertThat(Ed25519.verify(identity, signature, MESSAGE)).isFalse();

        byte[] smallR = signature.clone();
        System.arraycopy(identity, 0, smallR, 0, 32);
        assertThat(Ed25519.hasSmallOrder(smallR, 0)).isTrue();
        assertThat(Ed25519.verify(TestKey.DEV_42.verificationKey(), smallR, MESSAGE)).isFalse();

        byte[] withSignBit = identity.clone();
        withSignBit[31] |= (byte) 0x80;
        assertThat(Ed25519.hasSmallOrder(withSignBit, 0)).as("the sign bit is ignored").isTrue();

        byte[] yAboveP = HexUtil.decodeHexString("f0" + "ff".repeat(30) + "7f");
        assertThat(Ed25519.pointIsCanonical(yAboveP)).isFalse();
        assertThat(Ed25519.pointIsCanonical(TestKey.DEV_42.verificationKey())).isTrue();
        assertThat(Ed25519.verify(yAboveP, signature, MESSAGE)).isFalse();
    }

    private static byte[] toFixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] reverse(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }
}
