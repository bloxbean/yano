package com.bloxbean.cardano.yano.api.plugin.domain;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable construction context for a domain API product.
 *
 * <p>The context intentionally exposes no router, request identity, mutable
 * runtime service, message submission, effect execution, or administration
 * capability. All chain access goes through the bounded query-only facade.</p>
 */
public final class DomainApiContext {
    public static final int MAX_CONFIG_ENTRIES = 256;
    public static final int MAX_CONFIG_COLLECTION_ENTRIES = 256;
    public static final int MAX_CONFIG_DEPTH = 8;
    public static final int MAX_CONFIG_NODES = 2_048;
    public static final int MAX_CONFIG_KEY_LENGTH = 160;
    public static final int MAX_CONFIG_VALUE_LENGTH = 8_192;
    public static final int MAX_CONFIG_CHARACTERS = 64 * 1024;

    private final Map<String, Object> bundleConfig;
    private final DomainQueryService queryService;

    public DomainApiContext(Map<String, ?> bundleConfig, DomainQueryService queryService) {
        this.bundleConfig = DomainApiValidation.bundleConfig(bundleConfig);
        this.queryService = bounded(Objects.requireNonNull(queryService, "queryService"));
    }

    /**
     * Immutable, deeply copied configuration owned by this bundle.
     * Production ADR-011.3 v1 supplies an empty map until a typed,
     * secret-safe domain API configuration contract is defined.
     */
    public Map<String, Object> bundleConfig() {
        return bundleConfig;
    }

    /** Bounded query-only chain facade. */
    public DomainQueryService queryService() {
        return queryService;
    }

    /** Never renders configuration keys or values, which may be credentials. */
    @Override
    public String toString() {
        return "DomainApiContext[bundleConfigEntries=" + bundleConfig.size()
                + ", queryService=<host-owned>]";
    }

    private static DomainQueryService bounded(DomainQueryService delegate) {
        return new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                List<String> source = Objects.requireNonNull(
                        delegate.chainIds(), "queryService.chainIds() must not return null");
                if (source.size() > MAX_CHAIN_IDS) {
                    throw new IllegalStateException("queryService returned more than 256 chain ids");
                }
                List<String> copy = new ArrayList<>(source.size());
                for (String chainId : source) {
                    copy.add(DomainApiValidation.chainId(chainId));
                }
                if (new HashSet<>(copy).size() != copy.size()) {
                    throw new IllegalStateException("queryService returned duplicate chain ids");
                }
                Collections.sort(copy);
                return List.copyOf(copy);
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                String validatedChainId = DomainApiValidation.chainId(chainId);
                String validatedPath = DomainApiValidation.queryPath(path);
                Objects.requireNonNull(params, "params");
                if (params.length > MAX_REQUEST_BYTES) {
                    throw new IllegalArgumentException("params must contain at most 65536 bytes");
                }
                return Objects.requireNonNull(
                        delegate.query(validatedChainId, validatedPath, params.clone()),
                        "queryService.query() must not return null");
            }
        };
    }
}
