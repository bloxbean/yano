package com.bloxbean.cardano.yano.bootstrap.providers;

import com.bloxbean.cardano.yano.api.bootstrap.BootstrapDataProvider;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Shared factory for the default bootstrap data providers.
 */
public final class DefaultBootstrapDataProviderFactory {
    private static final Logger log = LoggerFactory.getLogger(DefaultBootstrapDataProviderFactory.class);

    private DefaultBootstrapDataProviderFactory() {
    }

    public static Optional<BootstrapDataProvider> create(YanoConfig config) {
        try {
            String providerType = config.getBootstrapProvider() != null
                    ? config.getBootstrapProvider().toLowerCase() : "blockfrost";
            String network = config.getNetwork() != null ? config.getNetwork() : "preprod";

            BootstrapDataProvider provider = switch (providerType) {
                case "koios" -> config.getBootstrapKoiosBaseUrl() != null
                        && !config.getBootstrapKoiosBaseUrl().isBlank()
                        ? new KoiosBootstrapProvider(config.getBootstrapKoiosBaseUrl())
                        : KoiosBootstrapProvider.forNetwork(network);
                default -> blockfrostProvider(config, network).orElse(null);
            };

            if (provider == null) {
                return Optional.empty();
            }
            log.info("Bootstrap data provider configured: {}", providerType);
            return Optional.of(provider);
        } catch (Exception e) {
            log.error("Failed to configure bootstrap provider: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<BootstrapDataProvider> blockfrostProvider(YanoConfig config, String network) {
        String apiKey = config.getBootstrapBlockfrostApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Bootstrap enabled but no Blockfrost API key configured. Set yano.bootstrap.blockfrost.api-key");
            return Optional.empty();
        }
        if (config.getBootstrapBlockfrostBaseUrl() != null
                && !config.getBootstrapBlockfrostBaseUrl().isBlank()) {
            return Optional.of(new BlockfrostBootstrapProvider(
                    config.getBootstrapBlockfrostBaseUrl(), apiKey));
        }
        return Optional.of(BlockfrostBootstrapProvider.forNetwork(network, apiKey));
    }
}
