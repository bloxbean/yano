package com.bloxbean.cardano.yano.appchain.composite;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Consensus-relevant identity, routes, activation, and quota for one component generation.
 *
 * <p>Every scheduled generation with the same {@code componentId} shares one
 * physical namespace and is initialized on restart; consequently those
 * generations must use exactly the same state encoding, initialization
 * contract, and late-result callback semantics. The explicit
 * {@code stateAndResultCompatibilityId} commits that contract independently
 * from {@code configurationId}, so a schema-compatible generation may change
 * effective configuration without weakening late-result safety.
 * The profile rejects a changed compatibility identity. A schema-changing or
 * callback-incompatible replacement must use a new component ID (and thus a
 * new namespace) plus an explicit deterministic migration workflow, or a
 * fresh chain.</p>
 */
public record ComponentDescriptor(
        String componentId,
        String semanticVersion,
        String configurationId,
        String stateAndResultCompatibilityId,
        long fromHeight,
        long untilHeight,
        List<String> topics,
        List<String> queryPaths,
        int maxEffectsPerBlock
) {
    public static final int MAX_ROUTES = 64;

    public ComponentDescriptor {
        componentId = CompositeValidation.id(componentId, "componentId");
        semanticVersion = CompositeValidation.printable(semanticVersion, "semanticVersion");
        configurationId = CompositeValidation.printable(configurationId, "configurationId");
        stateAndResultCompatibilityId = CompositeValidation.printable(
                stateAndResultCompatibilityId, "stateAndResultCompatibilityId");
        CompositeValidation.activation(fromHeight, untilHeight, "component activation");
        topics = sortedRoutes(topics, "topics");
        queryPaths = sortedRoutes(queryPaths, "queryPaths");
        if (maxEffectsPerBlock < 0 || maxEffectsPerBlock > 1_048_576) {
            throw new IllegalArgumentException("maxEffectsPerBlock must be between 0 and 1048576");
        }
    }

    public ComponentGeneration generation() {
        return new ComponentGeneration(componentId, semanticVersion, fromHeight);
    }

    public boolean activeAt(long height) {
        return height >= fromHeight && (untilHeight == 0 || height < untilHeight);
    }

    private static List<String> sortedRoutes(Collection<String> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.size() > MAX_ROUTES) {
            throw new IllegalArgumentException(field + " may contain at most " + MAX_ROUTES + " entries");
        }
        List<String> sorted = values.stream()
                .map(value -> CompositeValidation.route(value, field + " entry"))
                .sorted()
                .toList();
        if (sorted.stream().distinct().count() != sorted.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return sorted;
    }
}
