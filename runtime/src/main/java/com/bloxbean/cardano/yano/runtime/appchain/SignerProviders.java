package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

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
        if (keySpec == null || keySpec.isBlank()) {
            throw new IllegalArgumentException("app-chain signing key is required");
        }
        String spec = keySpec.trim();
        int colon = spec.indexOf(':');
        // A bare hex seed (no scheme) uses the default in-config signer.
        if (colon < 0) {
            return new AppMessageSigner(spec);
        }
        String scheme = spec.substring(0, colon);
        String reference = spec.substring(colon + 1);

        List<ClassLoader> classLoaders = new ArrayList<>();
        if (pluginClassLoader != null) {
            classLoaders.add(pluginClassLoader);
        }
        classLoaders.add(Thread.currentThread().getContextClassLoader());
        List<String> available = new ArrayList<>();
        for (ClassLoader classLoader : classLoaders) {
            for (SignerProviderFactory factory : ServiceLoader.load(SignerProviderFactory.class, classLoader)) {
                available.add(factory.scheme());
                if (scheme.equals(factory.scheme())) {
                    log.info("App-chain signer via '{}' provider {}", scheme, factory.getClass().getName());
                    return factory.create(reference);
                }
            }
        }
        throw new IllegalArgumentException("No SignerProviderFactory for scheme '" + scheme
                + "' (available: " + available + "); provide a plugin jar or a 32-byte hex seed");
    }

    static String publicKeyHex(SignerProvider provider) {
        return HexUtil.encodeHexString(provider.publicKey());
    }
}
