package com.bloxbean.cardano.yano.appchain.client;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Client-side envelope encryption for app-message bodies (ADR app-layer/006
 * E4.2), wire-compatible with the node-side {@code BodyCipher}. AES-256-GCM
 * with the topic bound as associated data.
 *
 * <pre>
 * byte[] key = Hex.decode(groupKeyHex);
 * client.submit("orders", GroupCipher.encrypt(key, "orders", plaintext));
 * // reader:
 * byte[] plain = GroupCipher.decrypt(key, "orders", message.body());
 * </pre>
 *
 * The chain stays blob-first — the node stores/gossips/proves/anchors the
 * ciphertext and never sees the plaintext or the key.
 */
public final class GroupCipher {

    private static final byte VERSION = 1;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private GroupCipher() {
    }

    public static byte[] encrypt(byte[] key, String topic, byte[] plaintext) {
        requireKey(key);
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(topic));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[1 + NONCE_LEN + ct.length];
            out[0] = VERSION;
            System.arraycopy(nonce, 0, out, 1, NONCE_LEN);
            System.arraycopy(ct, 0, out, 1 + NONCE_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Body encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, String topic, byte[] blob) {
        requireKey(key);
        if (blob == null || blob.length < 1 + NONCE_LEN || blob[0] != VERSION) {
            throw new IllegalArgumentException("Not a valid encrypted body");
        }
        try {
            byte[] nonce = Arrays.copyOfRange(blob, 1, 1 + NONCE_LEN);
            byte[] ct = Arrays.copyOfRange(blob, 1 + NONCE_LEN, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(topic));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Body decryption failed (wrong key/topic or tampered)", e);
        }
    }

    private static byte[] aad(String topic) {
        return (topic != null ? topic : "").getBytes(StandardCharsets.UTF_8);
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Group key must be 32 bytes (AES-256)");
        }
    }
}
