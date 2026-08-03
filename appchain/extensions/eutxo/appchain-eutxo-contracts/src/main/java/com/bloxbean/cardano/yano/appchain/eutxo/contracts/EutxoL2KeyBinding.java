package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Arrays;
import java.util.Objects;

/**
 * Optional deposit-bound L2 authorization key.
 *
 * <p>The canonical absent value is {@code ("", 0, byte[0])}. A present
 * binding is converted to an {@link EutxoL2KeyRegistration} after the
 * deposit's Cardano payment credential has been verified.</p>
 */
public record EutxoL2KeyBinding(
        String authorizationProfile,
        long keyEpoch,
        byte[] publicKey
) {
    public EutxoL2KeyBinding {
        authorizationProfile = Objects.requireNonNull(
                authorizationProfile, "authorizationProfile").trim();
        publicKey = Objects.requireNonNull(publicKey, "publicKey").clone();
        boolean absent = authorizationProfile.isEmpty()
                && keyEpoch == 0 && publicKey.length == 0;
        if (!absent) {
            if (authorizationProfile.isEmpty()
                    || authorizationProfile.length() > 63) {
                throw new IllegalArgumentException(
                        "invalid L2 authorization profile");
            }
            if (keyEpoch < 1) {
                throw new IllegalArgumentException(
                        "L2 key epoch must be positive");
            }
            if (publicKey.length != 32) {
                throw new IllegalArgumentException(
                        "L2 public key must contain 32 bytes");
            }
        }
    }

    public static EutxoL2KeyBinding none() {
        return new EutxoL2KeyBinding("", 0, new byte[0]);
    }

    public boolean present() {
        return !authorizationProfile.isEmpty();
    }

    @Override
    public byte[] publicKey() {
        return publicKey.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoL2KeyBinding binding
                && authorizationProfile.equals(binding.authorizationProfile)
                && keyEpoch == binding.keyEpoch
                && Arrays.equals(publicKey, binding.publicKey);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(authorizationProfile, keyEpoch)
                + Arrays.hashCode(publicKey);
    }
}
