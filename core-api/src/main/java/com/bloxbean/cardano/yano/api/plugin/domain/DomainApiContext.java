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
    private final LocalReadModelQueryService localReadModels;

    public DomainApiContext(Map<String, ?> bundleConfig, DomainQueryService queryService) {
        this(bundleConfig, queryService, LocalReadModelQueryService.unavailable());
    }

    public DomainApiContext(
            Map<String, ?> bundleConfig,
            DomainQueryService queryService,
            LocalReadModelQueryService localReadModels
    ) {
        this.bundleConfig = DomainApiValidation.bundleConfig(bundleConfig);
        this.queryService = bounded(Objects.requireNonNull(queryService, "queryService"));
        this.localReadModels = bounded(Objects.requireNonNull(
                localReadModels, "localReadModels"));
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

    /** Bounded node-local derived-model facade; never exposes storage handles. */
    public LocalReadModelQueryService localReadModels() {
        return localReadModels;
    }

    /** Never renders configuration keys or values, which may be credentials. */
    @Override
    public String toString() {
        return "DomainApiContext[bundleConfigEntries=" + bundleConfig.size()
                + ", queryService=<host-owned>]";
    }

    private static LocalReadModelQueryService bounded(
            LocalReadModelQueryService delegate
    ) {
        return (modelId, chainId, operation, request) -> {
            String validatedModel = identifier(modelId, "modelId", 160);
            String validatedChain = DomainApiValidation.chainId(chainId);
            String validatedOperation =
                    identifier(operation, "operation", 160);
            Objects.requireNonNull(request, "request");
            if (request.length > LocalReadModelQueryService.MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException(
                        "local read-model request exceeds 65536 bytes");
            }
            return Objects.requireNonNull(delegate.query(
                            validatedModel,
                            validatedChain,
                            validatedOperation,
                            request.clone()),
                    "localReadModels.query() must not return null");
        };
    }

    private static String identifier(
            String value,
            String field,
            int maximum
    ) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
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
