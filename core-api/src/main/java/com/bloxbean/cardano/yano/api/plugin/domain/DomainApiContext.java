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
    private final PrivilegedSystemMessageService privilegedSystemMessages;
    private final L1TransactionBuilderService l1Transactions;

    public DomainApiContext(Map<String, ?> bundleConfig, DomainQueryService queryService) {
        this(bundleConfig, queryService, LocalReadModelQueryService.unavailable(),
                PrivilegedSystemMessageService.unavailable(),
                L1TransactionBuilderService.unavailable());
    }

    public DomainApiContext(
            Map<String, ?> bundleConfig,
            DomainQueryService queryService,
            LocalReadModelQueryService localReadModels
    ) {
        this(bundleConfig, queryService, localReadModels,
                PrivilegedSystemMessageService.unavailable(),
                L1TransactionBuilderService.unavailable());
    }

    public DomainApiContext(
            Map<String, ?> bundleConfig,
            DomainQueryService queryService,
            LocalReadModelQueryService localReadModels,
            PrivilegedSystemMessageService privilegedSystemMessages
    ) {
        this(bundleConfig, queryService, localReadModels,
                privilegedSystemMessages, L1TransactionBuilderService.unavailable());
    }

    public DomainApiContext(
            Map<String, ?> bundleConfig,
            DomainQueryService queryService,
            LocalReadModelQueryService localReadModels,
            PrivilegedSystemMessageService privilegedSystemMessages,
            L1TransactionBuilderService l1Transactions
    ) {
        this.bundleConfig = DomainApiValidation.bundleConfig(bundleConfig);
        this.queryService = bounded(Objects.requireNonNull(queryService, "queryService"));
        this.localReadModels = bounded(Objects.requireNonNull(
                localReadModels, "localReadModels"));
        this.privilegedSystemMessages = bounded(Objects.requireNonNull(
                privilegedSystemMessages, "privilegedSystemMessages"));
        this.l1Transactions = bounded(Objects.requireNonNull(
                l1Transactions, "l1Transactions"));
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

    /** Bounded privileged state-machine command seam; host authentication is mandatory. */
    public PrivilegedSystemMessageService privilegedSystemMessages() {
        return privilegedSystemMessages;
    }

    /** Reviewed non-custodial L1 transaction builder. */
    public L1TransactionBuilderService l1Transactions() {
        return l1Transactions;
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

    private static PrivilegedSystemMessageService bounded(
            PrivilegedSystemMessageService delegate
    ) {
        return new PrivilegedSystemMessageService() {
            @Override
            public void validate(String chainId, String topic, byte[] body) {
                delegate.validate(
                        DomainApiValidation.chainId(chainId),
                        systemTopic(topic),
                        commandBody(body));
            }

            @Override
            public String submit(String chainId, String topic, byte[] body) {
                String messageId = Objects.requireNonNull(delegate.submit(
                                DomainApiValidation.chainId(chainId),
                                systemTopic(topic),
                                commandBody(body)),
                        "privilegedSystemMessages.submit() must not return null");
                if (!messageId.matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException(
                            "privileged system-message id must be lowercase 32-byte hex");
                }
                return messageId;
            }
        };
    }

    private static L1TransactionBuilderService bounded(
            L1TransactionBuilderService delegate
    ) {
        return new L1TransactionBuilderService() {
            @Override
            public long tipSlot() {
                long slot = delegate.tipSlot();
                if (slot < 0) {
                    throw new IllegalStateException(
                            "l1Transactions.tipSlot() must not return a negative slot");
                }
                return slot;
            }

            @Override
            public SpendableInput selectSpendableInput(String sourceAddress) {
                return Objects.requireNonNull(
                        delegate.selectSpendableInput(l1Address(sourceAddress)),
                        "l1Transactions.selectSpendableInput() must not return null");
            }

            @Override
            public UnsignedTransaction buildPayment(PaymentPlan plan) {
                return Objects.requireNonNull(
                        delegate.buildPayment(Objects.requireNonNull(plan, "plan")),
                        "l1Transactions.buildPayment() must not return null");
            }
        };
    }

    private static String l1Address(String value) {
        String normalized = Objects.requireNonNull(value, "sourceAddress").trim();
        if (normalized.isEmpty()
                || normalized.length() > L1TransactionBuilderService.MAX_ADDRESS_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid sourceAddress");
        }
        return normalized;
    }

    private static String systemTopic(String value) {
        String topic = Objects.requireNonNull(value, "topic").trim();
        if (topic.length() < 2
                || topic.length() > PrivilegedSystemMessageService.MAX_TOPIC_LENGTH
                || topic.charAt(0) != '~'
                || !topic.matches("~[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException("invalid privileged system topic");
        }
        return topic;
    }

    private static byte[] commandBody(byte[] value) {
        Objects.requireNonNull(value, "body");
        if (value.length == 0
                || value.length > PrivilegedSystemMessageService.MAX_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "privileged system-message body must contain 1-65536 bytes");
        }
        return value.clone();
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
