package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.runtime.plugins.LegacyServiceLoaderProviderRegistry;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.slf4j.Logger;

/**
 * Resolves the member signing key spec (ADR app-layer/006 E4.3):
 * <ul>
 *   <li>a bare 32-byte hex seed → the default {@link AppMessageSigner} (key in
 *       config, current behavior)</li>
 *   <li>{@code scheme:reference} → a {@link SignerProviderFactory} discovered
 *       via ServiceLoader (KMS/HSM/Vault plugin jars) — the node never holds
 *       the raw key</li>
 * </ul>
 * Utility helpers ({@code signHex}, {@code publicKeyHex}) wrap any provider.
 */
final class SignerProviders {

    private SignerProviders() {
    }

    static SignerProvider resolve(String keySpec, ClassLoader pluginClassLoader, Logger log) {
        return resolveFromRegistry(keySpec,
                new LegacyServiceLoaderProviderRegistry(pluginClassLoader), log);
    }

    static SignerProvider resolveFromRegistry(String keySpec,
                                              PluginProviderRegistry providers,
                                              Logger log) {
        if (keySpec == null || keySpec.isBlank()) {
            throw new IllegalArgumentException("app-chain signing key is required");
        }
        String spec = keySpec.trim();
        int colon = spec.indexOf(':');
        // A bare hex seed (no scheme) uses the default in-config signer.
        if (colon < 0) {
            SignerProvider signer = new AppMessageSigner(spec);
            return stableIdentity(signer, signer.publicKey());
        }
        String scheme = spec.substring(0, colon);
        String reference = spec.substring(colon + 1);

        SignerProviderFactory factory = providers.find(SignerProviderFactory.class, scheme).orElse(null);
        if (factory != null) {
            log.info("App-chain signer via '{}' provider {}", scheme, factory.getClass().getName());
            try {
                SignerProvider signer = java.util.Objects.requireNonNull(factory.create(reference),
                        "SignerProviderFactory.create returned null");
                byte[] publicKey = java.util.Objects.requireNonNull(
                        signer.publicKey(), "SignerProvider.publicKey returned null");
                if (publicKey.length != 32) {
                    throw new IllegalStateException(
                            "SignerProvider.publicKey must return a 32-byte Ed25519 key");
                }
                return stableIdentity(signer, publicKey);
            } catch (PluginActivationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new PluginActivationException(
                        "Configured plugin signer provider '" + scheme + "' failed to activate",
                        failure);
            }
        }
        throw new PluginActivationException(
                "Configured plugin signer provider '" + scheme
                        + "' is not selected (available: "
                        + providers.names(SignerProviderFactory.class)
                        + "); provide a selected plugin bundle or a 32-byte hex seed",
                null);
    }

    static String publicKeyHex(SignerProvider provider) {
        return HexUtil.encodeHexString(provider.publicKey());
    }

    private static SignerProvider stableIdentity(SignerProvider delegate, byte[] publicKey) {
        byte[] stablePublicKey = java.util.Arrays.copyOf(publicKey, publicKey.length);
        String stablePublicKeyHex = HexUtil.encodeHexString(stablePublicKey)
                .toLowerCase(java.util.Locale.ROOT);
        return new SignerProvider() {
            @Override
            public byte[] sign(byte[] message) {
                return delegate.sign(message);
            }

            @Override
            public byte[] publicKey() {
                return java.util.Arrays.copyOf(stablePublicKey, stablePublicKey.length);
            }

            @Override
            public String publicKeyHex() {
                return stablePublicKeyHex;
            }
        };
    }
}
