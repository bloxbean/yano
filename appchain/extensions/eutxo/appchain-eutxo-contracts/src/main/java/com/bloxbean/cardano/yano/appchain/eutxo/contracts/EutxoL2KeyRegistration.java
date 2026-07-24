package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Replicated binding from a Cardano payment credential to one active L2 key epoch. */
public record EutxoL2KeyRegistration(
        String paymentCredential,
        String authorizationProfile,
        long keyEpoch,
        byte[] publicKey,
        Status status
) {
    private static final int VERSION = 1;

    public enum Status {
        ACTIVE,
        REVOKED
    }

    public EutxoL2KeyRegistration {
        paymentCredential = EutxoL2Authorization.credential(paymentCredential);
        authorizationProfile = Objects.requireNonNull(
                authorizationProfile, "authorizationProfile").trim();
        if (authorizationProfile.isEmpty() || authorizationProfile.length() > 63) {
            throw new IllegalArgumentException("invalid authorization profile");
        }
        if (keyEpoch < 1) {
            throw new IllegalArgumentException("L2 key epoch must be positive");
        }
        publicKey = Objects.requireNonNull(publicKey, "publicKey").clone();
        if (publicKey.length != 32) {
            throw new IllegalArgumentException(
                    "registered L2 public key must contain 32 bytes");
        }
        status = Objects.requireNonNull(status, "status");
    }

    @Override
    public byte[] publicKey() {
        return publicKey.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoL2KeyRegistration registration
                && paymentCredential.equals(registration.paymentCredential)
                && authorizationProfile.equals(
                registration.authorizationProfile)
                && keyEpoch == registration.keyEpoch
                && Arrays.equals(publicKey, registration.publicKey)
                && status == registration.status;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                paymentCredential, authorizationProfile, keyEpoch, status);
        return 31 * result + Arrays.hashCode(publicKey);
    }

    public byte[] encode() {
        byte[] credential = paymentCredential.getBytes(StandardCharsets.US_ASCII);
        byte[] profile = authorizationProfile.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(
                4 + 4 + credential.length + 4 + profile.length
                        + 8 + 4 + publicKey.length + 4);
        buffer.putInt(VERSION);
        put(buffer, credential);
        put(buffer, profile);
        buffer.putLong(keyEpoch);
        put(buffer, publicKey);
        buffer.putInt(status.ordinal());
        return buffer.array();
    }

    public static EutxoL2KeyRegistration decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            if (buffer.getInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported L2 key registration version");
            }
            EutxoL2KeyRegistration registration =
                    new EutxoL2KeyRegistration(
                            new String(get(buffer, 56), StandardCharsets.US_ASCII),
                            new String(get(buffer, 63), StandardCharsets.US_ASCII),
                            buffer.getLong(),
                            get(buffer, 32),
                            status(buffer.getInt()));
            if (buffer.hasRemaining()
                    || !Arrays.equals(encoded, registration.encode())) {
                throw new IllegalArgumentException(
                        "non-canonical L2 key registration");
            }
            return registration;
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException) {
                throw failure;
            }
            throw new IllegalArgumentException(
                    "invalid L2 key registration", failure);
        }
    }

    private static Status status(int ordinal) {
        if (ordinal < 0 || ordinal >= Status.values().length) {
            throw new IllegalArgumentException("invalid L2 key status");
        }
        return Status.values()[ordinal];
    }

    private static void put(ByteBuffer buffer, byte[] value) {
        buffer.putInt(value.length).put(value);
    }

    private static byte[] get(ByteBuffer buffer, int maximum) {
        int length = buffer.getInt();
        if (length < 0 || length > maximum || length > buffer.remaining()) {
            throw new IllegalArgumentException("invalid L2 key field");
        }
        byte[] value = new byte[length];
        buffer.get(value);
        return value;
    }
}
