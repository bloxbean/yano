package com.bloxbean.cardano.yano.appchain.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for a Yano app chain (ADR app-layer/006 E1.4).
 *
 * <pre>
 * yano.appchain.client.base-url = http://localhost:8080/api/v1
 * yano.appchain.client.chain-id = my-chain            # optional (single-chain node)
 * yano.appchain.client.api-key  = ...                 # when the node enables auth
 * </pre>
 */
@ConfigurationProperties(prefix = "yano.appchain.client")
public class AppChainProperties {

    /** Node REST base url including the api prefix, e.g. http://host:8080/api/v1. */
    private String baseUrl;

    /** Chain to address; optional when the node hosts exactly one chain. */
    private String chainId;

    /** X-API-Key value when the node enables yano.app-chain.api.auth. */
    private String apiKey;

    /** HTTP connect timeout in seconds. */
    private long connectTimeoutSeconds = 10;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public long getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }
}
