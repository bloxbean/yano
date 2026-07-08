package com.bloxbean.cardano.yano.api.appchain;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry of the app chains this node participates in (multi-chain support,
 * ADR app-layer/006 E5.2). With a single configured chain, {@link #single()}
 * returns it — the compatibility path for chain-agnostic callers.
 */
public interface AppChainGateways {

    Optional<AppChainGateway> byId(String chainId);

    Collection<AppChainGateway> all();

    /** The only chain, when exactly one is configured; empty otherwise. */
    default Optional<AppChainGateway> single() {
        Collection<AppChainGateway> chains = all();
        return chains.size() == 1 ? Optional.of(chains.iterator().next()) : Optional.empty();
    }

    static AppChainGateways empty() {
        return new AppChainGateways() {
            @Override
            public Optional<AppChainGateway> byId(String chainId) {
                return Optional.empty();
            }

            @Override
            public Collection<AppChainGateway> all() {
                return java.util.List.of();
            }
        };
    }
}
