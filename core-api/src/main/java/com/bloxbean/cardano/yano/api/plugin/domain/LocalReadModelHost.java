package com.bloxbean.cardano.yano.api.plugin.domain;

/**
 * Host-side registration seam. This interface is never placed in a plugin
 * construction context.
 */
public interface LocalReadModelHost extends LocalReadModelQueryService {
    AutoCloseable register(
            String modelId,
            String chainId,
            LocalReadModel model);

    @FunctionalInterface
    interface LocalReadModel {
        LocalReadModelResult query(String operation, byte[] boundedRequest);
    }

    static LocalReadModelHost unavailable() {
        return new LocalReadModelHost() {
            @Override
            public AutoCloseable register(
                    String modelId,
                    String chainId,
                    LocalReadModel model
            ) {
                throw new IllegalStateException(
                        "local read-model host is unavailable");
            }

            @Override
            public LocalReadModelResult query(
                    String modelId,
                    String chainId,
                    String operation,
                    byte[] boundedRequest
            ) {
                return LocalReadModelResult.unavailable();
            }
        };
    }
}
