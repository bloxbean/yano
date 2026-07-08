package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.Objects;

/**
 * Ed25519 signing identity of this app-chain member (envelope auth scheme 0).
 * The private key is a 32-byte seed; the public key is derived from it and is
 * the member identity carried in the envelope's {@code sender} field.
 */
final class AppMessageSigner {
    private final byte[] privateKey;
    private final byte[] publicKey;
    private final SigningProvider signingProvider;

    AppMessageSigner(String privateKeyHex) {
        Objects.requireNonNull(privateKeyHex, "app-chain signing key is required");
        this.privateKey = HexUtil.decodeHexString(privateKeyHex.trim());
        if (privateKey.length != 32)
            throw new IllegalArgumentException("App-chain signing key must be a 32-byte Ed25519 seed (hex), got "
                    + privateKey.length + " bytes");
        this.publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(privateKey);
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
    }

    byte[] publicKey() {
        return publicKey;
    }

    String publicKeyHex() {
        return HexUtil.encodeHexString(publicKey);
    }

    byte[] sign(byte[] message) {
        return signingProvider.sign(message, privateKey);
    }

    static boolean verify(byte[] signature, byte[] message, byte[] publicKey) {
        try {
            return CryptoConfiguration.INSTANCE.getSigningProvider().verify(signature, message, publicKey);
        } catch (Exception e) {
            return false;
        }
    }
}
