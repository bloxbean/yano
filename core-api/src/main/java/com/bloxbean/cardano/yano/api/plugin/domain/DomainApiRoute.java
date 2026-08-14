package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable metadata for one host-dispatched domain API route. */
public record DomainApiRoute(
        String routeId,
        DomainHttpMethod method,
        String template,
        DomainApiAccess access
) {
    /** Maximum characters in a relative route template. */
    public static final int MAX_TEMPLATE_LENGTH = 256;
    /** Maximum segments in a relative route template. */
    public static final int MAX_SEGMENTS = 8;

    public DomainApiRoute {
        routeId = DomainApiValidation.routeId(routeId);
        method = Objects.requireNonNull(method, "method");
        template = DomainApiValidation.template(template).value();
        access = Objects.requireNonNull(access, "access");
    }

    /** Parameter names declared by whole-segment {@code {parameter}} entries. */
    public List<String> parameterNames() {
        return DomainApiValidation.template(template).parameterNames();
    }

    /**
     * Validate and return one normalized relative request path using the same
     * grammar enforced by {@link #match(String)} and {@link DomainApiRequest}.
     * Host adapters use this before route lookup so malformed input is
     * distinguishable from a well-formed path with no matching route.
     */
    public static String validatePath(String relativePath) {
        return DomainApiValidation.requestPath(relativePath);
    }

    /**
     * Match one normalized relative request path and return captured whole-
     * segment parameters. Invalid/non-matching paths return empty and never
     * escape the bundle namespace.
     */
    public Optional<Map<String, String>> match(String relativePath) {
        final String normalized;
        try {
            normalized = DomainApiValidation.requestPath(relativePath);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
        String[] templateSegments = template.split("/", -1);
        String[] requestSegments = normalized.split("/", -1);
        if (templateSegments.length != requestSegments.length) {
            return Optional.empty();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 0; index < templateSegments.length; index++) {
            String declared = templateSegments[index];
            String actual = requestSegments[index];
            if (declared.startsWith("{") && declared.endsWith("}")) {
                parameters.put(declared.substring(1, declared.length() - 1), actual);
            } else if (!declared.equals(actual)) {
                return Optional.empty();
            }
        }
        return Optional.of(Map.copyOf(parameters));
    }
}
