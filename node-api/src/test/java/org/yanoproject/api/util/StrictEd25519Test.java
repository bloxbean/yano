package org.yanoproject.api.util;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** libsodium's {@code crypto_sign_ed25519_verify_detached} rules, which the node's {@code Ed25519DSIGN} uses. */
class StrictEd25519Test {

    // RFC 8032 §7.1 TEST 1 (empty message).
    private static final byte[] PUBLIC_KEY = HexUtil.decodeHexString(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] SIGNATURE = HexUtil.decodeHexString(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                    + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
    private static final byte[] MESSAGE = new byte[0];
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    @Test
    void aValidSignatureVerifiesAndAnyChangeFails() {
        assertThat(StrictEd25519.verify(PUBLIC_KEY, SIGNATURE, MESSAGE)).isTrue();
        assertThat(StrictEd25519.verify(PUBLIC_KEY, SIGNATURE, new byte[] {1})).isFalse();
        byte[] otherKey = PUBLIC_KEY.clone();
        otherKey[0] ^= 1;
        assertThat(StrictEd25519.verify(otherKey, SIGNATURE, MESSAGE)).isFalse();
        assertThat(StrictEd25519.verify(PUBLIC_KEY, Arrays.copyOf(SIGNATURE, 63), MESSAGE)).isFalse();
        assertThat(StrictEd25519.verify(null, SIGNATURE, MESSAGE)).isFalse();
    }

    /** {@code sc25519_is_canonical}: {@code S + L} is the same scalar mod L but not canonical. */
    @Test
    void aNonCanonicalScalarIsRejected() {
        byte[] signature = SIGNATURE.clone();
        byte[] be = reverse(Arrays.copyOfRange(signature, 32, 64));
        byte[] bumped = reverse(toFixed(new BigInteger(1, be).add(L)));
        System.arraycopy(bumped, 0, signature, 32, 32);
        assertThat(StrictEd25519.scalarIsCanonical(signature)).isFalse();
        assertThat(StrictEd25519.verify(PUBLIC_KEY, signature, MESSAGE)).isFalse();
    }

    /** {@code ge25519_has_small_order} for the key and for {@code R}; {@code ge25519_is_canonical} for the key. */
    @Test
    void smallOrderAndNonCanonicalPointsAreRejected() {
        byte[] identity = HexUtil.decodeHexString("01" + "00".repeat(31));
        byte[] anyMessageForgery = new byte[64];
        System.arraycopy(identity, 0, anyMessageForgery, 0, 32);
        assertThat(StrictEd25519.verify(identity, anyMessageForgery, MESSAGE)).isFalse();

        byte[] smallR = SIGNATURE.clone();
        System.arraycopy(identity, 0, smallR, 0, 32);
        assertThat(StrictEd25519.hasSmallOrder(smallR, 0)).isTrue();
        assertThat(StrictEd25519.verify(PUBLIC_KEY, smallR, MESSAGE)).isFalse();

        byte[] withSignBit = identity.clone();
        withSignBit[31] |= (byte) 0x80;
        assertThat(StrictEd25519.hasSmallOrder(withSignBit, 0)).as("the sign bit is ignored").isTrue();

        byte[] yAboveP = HexUtil.decodeHexString("f0" + "ff".repeat(30) + "7f");
        assertThat(StrictEd25519.pointIsCanonical(yAboveP)).isFalse();
        assertThat(StrictEd25519.pointIsCanonical(PUBLIC_KEY)).isTrue();
        assertThat(StrictEd25519.verify(yAboveP, SIGNATURE, MESSAGE)).isFalse();
    }

    /**
     * Edge-case vectors from novifinancial/ed25519-speccheck ({@code cases.json} at commit
     * 65519336fda78a3d016e947df6d82848aca0c9da, Apache-2.0). Expected outcomes are libsodium's,
     * confirmed with PyNaCl 1.6.2: only vector 3 verifies.
     */
    private static final String[][] SPECCHECK_VECTORS = {   // message, public key, signature, libsodium
            {"8c93255d71dcab10e8f379c26200f3c7bd5f09d9bc3068d3ef4edeb4853022b6", "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa", "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a0000000000000000000000000000000000000000000000000000000000000000", "false"},
            {"9bd9f44f4dcc75bd531b56b2cd280b0bb38fc1cd6d1230e14861d861de092e79", "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa", "f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43a5bb704786be79fc476f91d3f3f89b03984d8068dcf1bb7dfc6637b45450ac04", "false"},
            {"aebf3f2601a0c8c5d39cc7d8911642f740b78168218da8471772b35f9d35b9ab", "f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43", "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa8c4bd45aecaca5b24fb97bc10ac27ac8751a7dfe1baff8b953ec9f5833ca260e", "false"},
            {"9bd9f44f4dcc75bd531b56b2cd280b0bb38fc1cd6d1230e14861d861de092e79", "cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d", "9046a64750444938de19f227bb80485e92b83fdb4b6506c160484c016cc1852f87909e14428a7a1d62e9f22f3d3ad7802db02eb2e688b6c52fcd6648a98bd009", "true"},
            {"e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec4011eaccd55b53f56c", "cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d", "160a1cb0dc9c0258cd0a7d23e94d8fa878bcb1925f2c64246b2dee1796bed5125ec6bc982a269b723e0668e540911a9a6a58921d6925e434ab10aa7940551a09", "false"},
            {"e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec4011eaccd55b53f56c", "cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d", "21122a84e0b5fca4052f5b1235c80a537878b38f3142356b2c2384ebad4668b7e40bc836dac0f71076f9abe3a53f9c03c1ceeeddb658d0030494ace586687405", "false"},
            {"85e241a07d148b41e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec40", "442aad9f089ad9e14647b1ef9099a1ff4798d78589e66f28eca69c11f582a623", "e96f66be976d82e60150baecff9906684aebb1ef181f67a7189ac78ea23b6c0e547f7690a0e2ddcd04d87dbc3490dc19b3b3052f7ff0538cb68afb369ba3a514", "false"},
            {"85e241a07d148b41e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec40", "442aad9f089ad9e14647b1ef9099a1ff4798d78589e66f28eca69c11f582a623", "8ce5b96c8f26d0ab6c47958c9e68b937104cd36e13c33566acd2fe8d38aa19427e71f98a473474f2f13f06f97c20d58cc3f54b8bd0d272f42b695dd7e89a8c22", "false"},
            {"9bedc267423725d473888631ebf45988bad3db83851ee85c85e241a07d148b41", "f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43", "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff03be9678ac102edcd92b0210bb34d7428d12ffc5df5f37e359941266a4e35f0f", "false"},
            {"9bedc267423725d473888631ebf45988bad3db83851ee85c85e241a07d148b41", "f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43", "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffca8c5b64cd208982aa38d4936621a4775aa233aa0505711d8fdcfdaa943d4908", "false"},
            {"e96b7021eb39c1a163b6da4e3093dcd3f21387da4cc4572be588fafae23c155b", "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "a9d55260f765261eb9b84e106f665e00b867287a761990d7135963ee0a7d59dca5bb704786be79fc476f91d3f3f89b03984d8068dcf1bb7dfc6637b45450ac04", "false"},
            {"39a591f5321bbe07fd5a23dc2f39d025d74526615746727ceefd6e82ae65c06f", "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "a9d55260f765261eb9b84e106f665e00b867287a761990d7135963ee0a7d59dca5bb704786be79fc476f91d3f3f89b03984d8068dcf1bb7dfc6637b45450ac04", "false"}
    };

    @Test
    void matchesLibsodiumOnEdgeCaseVectors() {
        for (int index = 0; index < SPECCHECK_VECTORS.length; index++) {
            String[] vector = SPECCHECK_VECTORS[index];
            assertThat(StrictEd25519.verify(HexUtil.decodeHexString(vector[1]),
                    HexUtil.decodeHexString(vector[2]), HexUtil.decodeHexString(vector[0])))
                    .as("speccheck vector %d", index).isEqualTo(Boolean.parseBoolean(vector[3]));
        }
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
