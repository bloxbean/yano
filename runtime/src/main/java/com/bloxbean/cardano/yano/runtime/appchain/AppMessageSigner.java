package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;

import java.util.Objects;

/**
 * Default in-config {@link SignerProvider}: the Ed25519 signing identity of an
 * app-chain member from a 32-byte seed (envelope auth scheme 0). The public key
 * is derived from the seed and is the member identity in the envelope's
 * {@code sender} field. Custom backends (KMS/HSM) plug in via
 * {@link com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory}.
 */
final class AppMessageSigner implements SignerProvider {
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

    @Override
    public byte[] publicKey() {
        return publicKey;
    }

    @Override
    public String publicKeyHex() {
        return HexUtil.encodeHexString(publicKey);
    }

    @Override
    public byte[] sign(byte[] message) {
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
