package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;

import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Redacted, closeable Jubjub session key for high-frequency L2 authorization.
 *
 * <p>This is not a Cardano signing key. Its public key is registered through
 * an L1-authorized operation; signatures are private proof witnesses.</p>
 */
public final class EutxoL2SessionKey implements AutoCloseable {
    private static final byte[] KDF_SALT =
            "yano:eutxo:l2:cip8-kdf:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENCRYPTION_AAD =
            "yano:eutxo:l2:session-key:v1".getBytes(StandardCharsets.US_ASCII);
    private static final int ENCRYPTION_ITERATIONS = 210_000;
    private final byte[] scalar;
    private boolean destroyed;

    private EutxoL2SessionKey(byte[] scalar) {
        this.scalar = scalar.clone();
    }

    public static EutxoL2SessionKey random() {
        SecureRandom random = new SecureRandom();
        byte[] entropy = new byte[64];
        random.nextBytes(entropy);
        try {
            return fromEntropy(entropy, "random-session-key");
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    /**
     * Optional deterministic derivation from a CIP-8 signature.
     *
     * <p>The context must be the canonical wallet prompt digest and bind the
     * chain, network, profiles, account, purpose, and version. The signature
     * and derived scalar are secrets and must not be logged.</p>
     */
    public static EutxoL2SessionKey fromCip8Signature(
            byte[] cip8Signature,
            byte[] context
    ) {
        Objects.requireNonNull(cip8Signature, "cip8Signature");
        Objects.requireNonNull(context, "context");
        if (cip8Signature.length < 64 || context.length != 32) {
            throw new IllegalArgumentException(
                    "CIP-8 signature must contain at least 64 bytes and context 32 bytes");
        }
        byte[] input = new byte[cip8Signature.length + context.length];
        System.arraycopy(cip8Signature, 0, input, 0, cip8Signature.length);
        System.arraycopy(context, 0, input, cip8Signature.length, context.length);
        try {
            return fromEntropy(hmac(KDF_SALT, input), "cip8-session-key");
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    public byte[] publicKey() {
        return EdDSAJubjub.keypairFromSecret(secret()).pk().toBytes();
    }

    /** Encrypts this key for local storage using AES-256-GCM and PBKDF2. */
    public byte[] encrypt(char[] password) {
        requirePassword(password);
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] key = deriveEncryptionKey(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ENCRYPTION_AAD);
            byte[] ciphertext = cipher.doFinal(unsigned32(secret()));
            return ByteBuffer.allocate(
                            Integer.BYTES + Integer.BYTES
                                    + salt.length + nonce.length
                                    + Integer.BYTES + ciphertext.length)
                    .putInt(1)
                    .putInt(ENCRYPTION_ITERATIONS)
                    .put(salt)
                    .put(nonce)
                    .putInt(ciphertext.length)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "L2 session key encryption failed", failure);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Opens an encrypted local key envelope. */
    public static EutxoL2SessionKey decrypt(
            byte[] envelope,
            char[] password
    ) {
        Objects.requireNonNull(envelope, "envelope");
        requirePassword(password);
        if (envelope.length < 16 + 12 + 16 + 12
                || envelope.length > 512) {
            throw new IllegalArgumentException(
                    "invalid encrypted L2 session-key envelope");
        }
        try {
            ByteBuffer input = ByteBuffer.wrap(envelope);
            if (input.getInt() != 1
                    || input.getInt() != ENCRYPTION_ITERATIONS) {
                throw new IllegalArgumentException(
                        "unsupported encrypted L2 session-key envelope");
            }
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            input.get(salt);
            input.get(nonce);
            int length = input.getInt();
            if (length < 48 || length != input.remaining()) {
                throw new IllegalArgumentException(
                        "invalid encrypted L2 session-key payload");
            }
            byte[] ciphertext = new byte[length];
            input.get(ciphertext);
            byte[] key = deriveEncryptionKey(password, salt);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
                cipher.updateAAD(ENCRYPTION_AAD);
                byte[] scalar = cipher.doFinal(ciphertext);
                BigInteger value = new BigInteger(1, scalar);
                if (scalar.length != 32 || value.signum() <= 0
                        || value.compareTo(JubjubCurve.SUBGROUP_ORDER) >= 0) {
                    Arrays.fill(scalar, (byte) 0);
                    throw new IllegalArgumentException(
                            "decrypted L2 session key is invalid");
                }
                EutxoL2SessionKey result = new EutxoL2SessionKey(scalar);
                Arrays.fill(scalar, (byte) 0);
                return result;
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (GeneralSecurityException | java.nio.BufferUnderflowException failure) {
            throw new IllegalArgumentException(
                    "encrypted L2 session key cannot be opened", failure);
        }
    }

    public EutxoL2Authorization sign(
            EutxoL2Transaction unsignedTransaction,
            String paymentCredential,
            long keyEpoch,
            List<Integer> inputIndexes
    ) {
        Objects.requireNonNull(unsignedTransaction, "unsignedTransaction");
        BigInteger message = new BigInteger(
                1, unsignedTransaction.signingCommitment())
                .mod(JubjubCurve.BASE_FIELD_PRIME);
        var signature = EdDSAJubjub.sign(secret(), message);
        return new EutxoL2Authorization(
                paymentCredential,
                keyEpoch,
                publicKey(),
                signature.r().toBytes(),
                littleEndian32(signature.s()),
                inputIndexes);
    }

    public boolean destroyed() {
        return destroyed;
    }

    @Override
    public void close() {
        Arrays.fill(scalar, (byte) 0);
        destroyed = true;
    }

    @Override
    public String toString() {
        return "EutxoL2SessionKey[REDACTED]";
    }

    private BigInteger secret() {
        if (destroyed) {
            throw new IllegalStateException("L2 session key has been destroyed");
        }
        return new BigInteger(1, scalar);
    }

    private static EutxoL2SessionKey fromEntropy(
            byte[] entropy,
            String purpose
    ) {
        byte[] expanded = hmac(
                purpose.getBytes(StandardCharsets.US_ASCII), entropy);
        BigInteger value = new BigInteger(1, expanded)
                .mod(JubjubCurve.SUBGROUP_ORDER.subtract(BigInteger.ONE))
                .add(BigInteger.ONE);
        Arrays.fill(expanded, (byte) 0);
        return new EutxoL2SessionKey(unsigned32(value));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static byte[] deriveEncryptionKey(
            char[] password,
            byte[] salt
    ) {
        PBEKeySpec specification = new PBEKeySpec(
                password, salt, ENCRYPTION_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(
                    "PBKDF2WithHmacSHA256 is unavailable", impossible);
        } finally {
            specification.clearPassword();
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 12) {
            throw new IllegalArgumentException(
                    "L2 key password must contain at least 12 characters");
        }
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] encoded = value.toByteArray();
        int start = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
        int length = encoded.length - start;
        if (length > 32) {
            throw new IllegalArgumentException("scalar exceeds 32 bytes");
        }
        byte[] result = new byte[32];
        System.arraycopy(encoded, start, result, 32 - length, length);
        return result;
    }

    private static byte[] littleEndian32(BigInteger value) {
        byte[] result = unsigned32(value);
        for (int left = 0, right = result.length - 1;
             left < right; left++, right--) {
            byte item = result[left];
            result[left] = result[right];
            result[right] = item;
        }
        return result;
    }
}
