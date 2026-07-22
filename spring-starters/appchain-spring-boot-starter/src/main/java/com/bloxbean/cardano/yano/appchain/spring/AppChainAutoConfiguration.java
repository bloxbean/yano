package com.bloxbean.cardano.yano.appchain.spring;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.StdlibAppChainClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Yano app-chain client (ADR app-layer/006 E1.4).
 * Activates when {@code yano.appchain.client.base-url} is set; provides an
 * {@link AppChainClient}, an {@link AppChainTemplate} and the
 * {@link AppChainListener} wiring. Every bean is {@code @ConditionalOnMissingBean},
 * so applications can override any piece.
 */
@AutoConfiguration
@EnableConfigurationProperties(AppChainProperties.class)
@ConditionalOnProperty(prefix = "yano.appchain.client", name = "base-url")
public class AppChainAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AppChainClient appChainClient(AppChainProperties properties) {
        AppChainClient.Builder builder = AppChainClient.builder(properties.getBaseUrl())
                .connectTimeoutSeconds(properties.getConnectTimeoutSeconds());
        if (properties.getChainId() != null && !properties.getChainId().isBlank()) {
            builder.chainId(properties.getChainId());
        }
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.apiKey(properties.getApiKey());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public AppChainTemplate appChainTemplate(AppChainClient client) {
        return new AppChainTemplate(client);
    }

    @Bean
    @ConditionalOnMissingBean
    public StdlibAppChainClient stdlibAppChainClient(AppChainClient client) {
        return new StdlibAppChainClient(client);
    }

    @Bean
    @ConditionalOnMissingBean
    public StdlibAppChainTemplate stdlibAppChainTemplate(StdlibAppChainClient client) {
        return new StdlibAppChainTemplate(client);
    }

    // static + ObjectProvider: a BeanPostProcessor must not drag the client
    // (and any user override's dependency graph) into the BPP-registration
    // phase — the client is looked up lazily at subscription time.
    @Bean
    @ConditionalOnMissingBean
    public static AppChainListenerProcessor appChainListenerProcessor(
            ObjectProvider<AppChainClient> clientProvider) {
        return new AppChainListenerProcessor(clientProvider);
    }
}
