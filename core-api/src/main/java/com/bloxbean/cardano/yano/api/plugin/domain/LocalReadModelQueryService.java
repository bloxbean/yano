package com.bloxbean.cardano.yano.api.plugin.domain;

/** Narrow query-only facade from domain products to node-local derived models. */
@FunctionalInterface
public interface LocalReadModelQueryService {
    int MAX_REQUEST_BYTES = 64 * 1024;

    LocalReadModelResult query(
            String modelId,
            String chainId,
            String operation,
            byte[] boundedRequest);

    static LocalReadModelQueryService unavailable() {
        return (modelId, chainId, operation, request) ->
                LocalReadModelResult.unavailable();
    }
}
