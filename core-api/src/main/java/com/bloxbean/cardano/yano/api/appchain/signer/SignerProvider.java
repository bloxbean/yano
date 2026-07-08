package com.bloxbean.cardano.yano.api.appchain.signer;

/**
 * Sign-only signing identity for an app-chain member (ADR app-layer/006 E4.3).
 * Abstracts where the private key lives so a KMS/HSM/Vault plugin can hold it
 * instead of a raw seed in config — the node never sees or exports the key.
 * <p>
 * The default is the in-config Ed25519 seed. Custom providers are supplied via
 * {@link SignerProviderFactory} (ServiceLoader), selected by a scheme prefix in
 * {@code yano.app-chain.signing-key} (e.g. {@code kms:...}, {@code vault:...}).
 */
public interface SignerProvider {

    /** Ed25519 signature over {@code message} (envelope auth scheme 0). */
    byte[] sign(byte[] message);

    /** The 32-byte Ed25519 public key — this member's identity. */
    byte[] publicKey();

    /** The public key as lower-case hex. */
    default String publicKeyHex() {
        return com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(publicKey());
    }
}
