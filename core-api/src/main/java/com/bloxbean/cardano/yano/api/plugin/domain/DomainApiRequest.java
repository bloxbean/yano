package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, framework-neutral request passed to a domain API handler. */
public record DomainApiRequest(
        String routeId,
        DomainHttpMethod method,
        String path,
        Map<String, String> pathParameters,
        Map<String, List<String>> queryParameters,
        byte[] body
) {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_PATH_PARAMETER_LENGTH = DomainApiRoute.MAX_TEMPLATE_LENGTH;
    public static final int MAX_QUERY_PARAMETER_NAMES = 32;
    public static final int MAX_QUERY_VALUES_PER_NAME = 16;
    public static final int MAX_QUERY_VALUES = 128;
    public static final int MAX_QUERY_VALUE_LENGTH = 2_048;
    public static final int MAX_QUERY_CHARACTERS = 64 * 1024;

    public DomainApiRequest {
        routeId = DomainApiValidation.routeId(routeId);
        method = Objects.requireNonNull(method, "method");
        path = DomainApiValidation.requestPath(path);
        pathParameters = DomainApiValidation.pathParameters(pathParameters);
        queryParameters = DomainApiValidation.queryParameters(queryParameters);
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("body must contain at most 65536 bytes");
        }
        if (method == DomainHttpMethod.GET && body.length != 0) {
            throw new IllegalArgumentException("GET domain API requests must not contain a body");
        }
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Redacts parameter values and request bytes. */
    @Override
    public String toString() {
        return "DomainApiRequest[routeId=" + routeId
                + ", method=" + method
                + ", path=<redacted>"
                + ", pathParameterNames=" + pathParameters.keySet()
                + ", queryParameterNames=" + queryParameters.keySet()
                + ", bodyBytes=" + body.length + "]";
    }
}
