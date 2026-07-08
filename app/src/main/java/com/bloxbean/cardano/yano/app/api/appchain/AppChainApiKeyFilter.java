package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opt-in API-key authentication for the app-chain REST surface
 * (ADR app-layer/006 E4.1). Disabled by default — when
 * {@code yano.app-chain.api.auth.enabled=true}, every {@code /app-chain}
 * request must carry a configured key in the {@code X-API-Key} header.
 * A key entry of the form {@code key=topicA|topicB} additionally restricts
 * message submission to the listed topics (reads stay unrestricted).
 */
@Provider
public class AppChainApiKeyFilter implements ContainerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    @ConfigProperty(name = YanoPropertyKeys.AppChain.API_AUTH_ENABLED, defaultValue = "false")
    boolean authEnabled;

    @ConfigProperty(name = YanoPropertyKeys.AppChain.API_KEYS)
    Optional<String> apiKeysConfig;

    @Inject
    ObjectMapper objectMapper;

    private volatile Map<String, Set<String>> parsedKeys;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!authEnabled) {
            return;
        }
        String path = requestContext.getUriInfo().getPath();
        if (!path.contains("app-chain")) {
            return;
        }

        String apiKey = requestContext.getHeaderString(API_KEY_HEADER);
        Map<String, Set<String>> keys = keys();
        if (apiKey == null || !keys.containsKey(apiKey)) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Missing or invalid " + API_KEY_HEADER)).build());
            return;
        }

        Set<String> allowedTopics = keys.get(apiKey);
        if (!allowedTopics.isEmpty()
                && "POST".equalsIgnoreCase(requestContext.getMethod())
                && path.endsWith("/messages")) {
            enforceTopicRestriction(requestContext, allowedTopics);
        }
    }

    private void enforceTopicRestriction(ContainerRequestContext requestContext, Set<String> allowedTopics) {
        try {
            byte[] body = requestContext.getEntityStream().readAllBytes();
            // restore the entity for the resource method
            requestContext.setEntityStream(new ByteArrayInputStream(body));
            JsonNode json = objectMapper.readTree(body);
            String topic = json.hasNonNull("topic") ? json.get("topic").asText() : "";
            if (!allowedTopics.contains(topic)) {
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("error", "API key is not allowed to submit to topic '"
                                + topic + "'")).build());
            }
        } catch (Exception e) {
            requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Unreadable request body")).build());
        }
    }

    /** Parse "key1,key2=topicA|topicB" into key → allowed submit topics ("" empty = all). */
    private Map<String, Set<String>> keys() {
        Map<String, Set<String>> current = parsedKeys;
        if (current != null) {
            return current;
        }
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        for (String entry : apiKeysConfig.orElse("").split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                parsed.put(trimmed, Set.of());
            } else {
                Set<String> topics = new java.util.HashSet<>();
                for (String topic : trimmed.substring(eq + 1).split("\\|")) {
                    topics.add(topic.trim());
                }
                parsed.put(trimmed.substring(0, eq).trim(), Set.copyOf(topics));
            }
        }
        parsedKeys = parsed;
        return parsed;
    }

    /** Test hook. */
    void resetCache() {
        parsedKeys = null;
    }
}
