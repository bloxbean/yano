package com.bloxbean.cardano.yano.bootstrap.providers;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBootstrapDataProviderFactoryTest {
    @Test
    void missingBlockfrostApiKeyReturnsEmpty() {
        var config = YanoConfig.preprodDefault();
        config.setBootstrapProvider("blockfrost");

        assertTrue(DefaultBootstrapDataProviderFactory.create(config).isEmpty());
    }

    @Test
    void missingProviderDefaultsToBlockfrost() {
        var config = YanoConfig.preprodDefault();
        config.setBootstrapBlockfrostBaseUrl("https://blockfrost.example.test");
        config.setBootstrapBlockfrostApiKey("test-key");

        assertInstanceOf(BlockfrostBootstrapProvider.class,
                DefaultBootstrapDataProviderFactory.create(config).orElseThrow());
    }

    @Test
    void unknownProviderFallsBackToBlockfrostCompatibilityPath() {
        var config = YanoConfig.preprodDefault();
        config.setBootstrapProvider("custom-unknown");
        config.setBootstrapBlockfrostBaseUrl("https://blockfrost.example.test");
        config.setBootstrapBlockfrostApiKey("test-key");

        assertInstanceOf(BlockfrostBootstrapProvider.class,
                DefaultBootstrapDataProviderFactory.create(config).orElseThrow());
    }

    @Test
    void koiosProviderDoesNotRequireApiKey() {
        var config = YanoConfig.preprodDefault();
        config.setBootstrapProvider("koios");
        config.setBootstrapKoiosBaseUrl("https://koios.example.test");

        assertInstanceOf(KoiosBootstrapProvider.class,
                DefaultBootstrapDataProviderFactory.create(config).orElseThrow());
    }

    @Test
    void blockfrostProviderUsesConfiguredBaseUrlAndApiKey() {
        var config = YanoConfig.preprodDefault();
        config.setBootstrapProvider("blockfrost");
        config.setBootstrapBlockfrostBaseUrl("https://blockfrost.example.test");
        config.setBootstrapBlockfrostApiKey("test-key");

        assertInstanceOf(BlockfrostBootstrapProvider.class,
                DefaultBootstrapDataProviderFactory.create(config).orElseThrow());
    }
}
