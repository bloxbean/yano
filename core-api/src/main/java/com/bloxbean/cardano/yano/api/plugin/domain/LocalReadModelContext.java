package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, bounded construction context for one local read-model contribution. */
public final class LocalReadModelContext {
    private final String network;
    private final Map<String, Object> bundleConfig;
    private final List<FinalizedChainView> chains;
    private final LocalReadModelHost host;

    public LocalReadModelContext(
            String network,
            Map<String, ?> bundleConfig,
            List<? extends FinalizedChainView> chains,
            LocalReadModelHost host
    ) {
        this.network = requireNetwork(network);
        this.bundleConfig = DomainApiValidation.bundleConfig(bundleConfig);
        Objects.requireNonNull(chains, "chains");
        if (chains.size() > DomainQueryService.MAX_CHAIN_IDS) {
            throw new IllegalArgumentException("too many finalized chain views");
        }
        ArrayList<FinalizedChainView> copy = new ArrayList<>(chains);
        copy.sort(Comparator.comparing(FinalizedChainView::chainId));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).chainId().equals(copy.get(index).chainId())) {
                throw new IllegalArgumentException("duplicate finalized chain view");
            }
        }
        this.chains = List.copyOf(copy);
        this.host = Objects.requireNonNull(host, "host");
    }

    public String network() {
        return network;
    }

    public Map<String, Object> bundleConfig() {
        return bundleConfig;
    }

    public List<FinalizedChainView> chains() {
        return chains;
    }

    public Optional<FinalizedChainView> chain(String chainId) {
        return chains.stream().filter(chain -> chain.chainId().equals(chainId)).findFirst();
    }

    public LocalReadModelHost host() {
        return host;
    }

    @Override
    public String toString() {
        return "LocalReadModelContext[bundleConfigEntries=" + bundleConfig.size()
                + ", network=" + network + ", chains=" + chains.size()
                + ", host=<host-owned>]";
    }

    private static String requireNetwork(String value) {
        if (value == null || value.isBlank() || value.length() > 32
                || !value.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                    "network must be a 1-32 character lowercase ASCII identifier");
        }
        return value;
    }
}
