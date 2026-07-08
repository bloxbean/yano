package com.bloxbean.cardano.yano.api.appchain.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Optional envelope encryption of app-message bodies with a shared group key
 * (ADR app-layer/006 E4.2). AES-256-GCM; the topic is bound as associated data
 * so a ciphertext cannot be replayed under a different topic.
 * <p>
 * The chain stays blob-first — ordering, MPF proofs and L1 anchoring all work
 * over the ciphertext, which is what goes in the envelope {@code body}.
 * Encryption happens at the submitting edge; decryption at the reading edge
 * (a client via the SDK's {@code GroupCipher}, or a server-side state machine
 * given the key). The node never needs the plaintext.
 * <p>
 * Wire format: {@code [version(1)=1][nonce(12)][ciphertext+GCM-tag]}.
 */
public final class BodyCipher {

    private static final byte VERSION = 1;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BodyCipher() {
    }

    /** A fresh random 256-bit key (hex-encode for config/distribution). */
    public static byte[] generateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
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

    /** True if the blob looks like a BodyCipher ciphertext (version byte). */
    public static boolean isEncrypted(byte[] blob) {
        return blob != null && blob.length >= 1 + NONCE_LEN && blob[0] == VERSION;
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
