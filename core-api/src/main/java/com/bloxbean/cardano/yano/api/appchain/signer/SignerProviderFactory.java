package com.bloxbean.cardano.yano.api.appchain.signer;

/**
 * ServiceLoader SPI for custom {@link SignerProvider} backends (ADR
 * app-layer/006 E4.3). A key spec of the form {@code scheme:rest} routes to the
 * factory whose {@link #scheme()} matches; {@code rest} is the backend-specific
 * key reference (e.g. a KMS key ARN, a Vault path).
 * <p>
 * Ship an implementation plus a
 * {@code META-INF/services/com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory}
 * entry in a plugin jar. Cloud KMS/HSM/Vault backends are intended plugins;
 * none ship in the default distribution.
 */
public interface SignerProviderFactory {

    /** The key-spec scheme this factory handles, e.g. {@code "kms"}. */
    String scheme();

    /**
     * Create a signer for {@code keyReference} (the part after {@code scheme:}).
     * Must fail fast if the key is unavailable.
     */
    SignerProvider create(String keyReference);
}
