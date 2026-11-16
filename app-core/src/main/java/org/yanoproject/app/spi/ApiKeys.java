package org.yanoproject.app.spi;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API-key parsing and request-shape checks shared by the host's REST authentication filters.
 *
 * <p>A key entry of the form {@code key=topicA|topicB} is a topic-scoped key; an entry without
 * {@code =topics} is an unscoped full key.</p>
 */
public final class ApiKeys {

    public static final String HEADER = "X-API-Key";

    private ApiKeys() {
    }

    /** Parses {@code "key1,key2=topicA|topicB"} into key to allowed topics; an empty set marks a full key. */
    public static Map<String, Set<String>> parse(String config) {
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        for (String entry : (config != null ? config : "").split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                parsed.put(trimmed, Set.of());
            } else {
                Set<String> topics = new HashSet<>();
                for (String topic : trimmed.substring(eq + 1).split("\\|")) {
                    topics.add(topic.trim());
                }
                parsed.put(trimmed.substring(0, eq).trim(), Set.copyOf(topics));
            }
        }
        return parsed;
    }

    /** Parses a comma-separated list of keys without topic scopes. */
    public static Set<String> parseSet(String config) {
        return Arrays.stream((config != null ? config : "").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True when at least one parsed key is an unscoped full key. */
    public static boolean hasFullKey(Map<String, Set<String>> keys) {
        return keys.values().stream().anyMatch(Set::isEmpty);
    }

    /**
     * The still-encoded request path, or {@code null} when it cannot be read. RESTEasy removes matrix
     * parameters and one trailing slash while matching a resource, so authorization decisions that
     * depend on the path must inspect this raw form.
     */
    public static String rawPath(UriInfo uriInfo) {
        try {
            var requestUri = uriInfo != null ? uriInfo.getRequestUri() : null;
            return requestUri != null
                    ? requestUri.getRawPath()
                    : uriInfo != null ? uriInfo.getPath(false) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * True for a non-empty raw path without percent-encoding, matrix parameters, empty segments,
     * a trailing slash or dot segments.
     */
    public static boolean isCanonical(String rawPath) {
        return rawPath != null && !rawPath.isEmpty()
                && rawPath.indexOf('%') < 0
                && rawPath.indexOf(';') < 0
                && !rawPath.contains("//")
                && !rawPath.endsWith("/")
                && !containsDotSegment(rawPath);
    }

    /**
     * Buffers at most {@code limit} entity bytes before deserialization and restores them for the
     * resource. Aborts with 413 {@code REQUEST_TOO_LARGE} above the limit and with 400
     * {@code INVALID_REQUEST} when the entity cannot be read.
     *
     * @return {@code false} when the request was aborted
     */
    public static boolean boundEntity(ContainerRequestContext requestContext, int limit) {
        if (!requestContext.hasEntity()) {
            return true;
        }
        try {
            byte[] body = requestContext.getEntityStream().readNBytes(limit + 1);
            if (body.length > limit) {
                requestContext.abortWith(Response.status(413)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("code", "REQUEST_TOO_LARGE",
                                "error", "Request entity exceeds the size limit"))
                        .build());
                return false;
            }
            requestContext.setEntityStream(new ByteArrayInputStream(body));
            return true;
        } catch (Exception unreadable) {
            requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("code", "INVALID_REQUEST",
                            "error", "Unreadable request body"))
                    .build());
            return false;
        }
    }

    private static boolean containsDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
