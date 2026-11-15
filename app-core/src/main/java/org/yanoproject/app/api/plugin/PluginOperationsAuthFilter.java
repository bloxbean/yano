package org.yanoproject.app.api.plugin;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.ConfigProvider;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.app.ApiPrefixContract;
import org.yanoproject.app.spi.ApiKeys;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * API-key authentication for {@link PluginOperationsResource}. Plugin operations are always
 * privileged: a request must use the canonical path and carry an unscoped full key from
 * {@code yano.app-chain.api.keys}, and the surface fails closed when no full key is configured.
 * The filter matches the resource class, not a URL substring, so re-rooting the resource cannot
 * un-protect it.
 */
@Provider
public class PluginOperationsAuthFilter implements ContainerRequestFilter {

    // Unit-test overrides. Production leaves them null and resolves the runtime
    // configuration lazily, which is essential for native images: API keys must
    // not be captured or embedded during static initialization, and the path
    // prefix always comes from the immutable artifact contract.
    Optional<String> apiKeysConfig;
    String apiPathPrefix;

    @Inject
    ApiPrefixContract apiPrefixContract;

    @Context
    ResourceInfo resourceInfo;

    @Context
    UriInfo uriInfo;

    private volatile Map<String, Set<String>> parsedKeys;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (resourceInfo == null || resourceInfo.getResourceClass() != PluginOperationsResource.class) {
            return;
        }
        String rawPath = ApiKeys.rawPath(uriInfo);
        if (!ApiKeys.isCanonical(rawPath) || !isCanonicalOperationsPath(rawPath, artifactApiPathPrefix())) {
            requestContext.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("code", "NOT_FOUND",
                            "error", "No plugin operations route matches the request"))
                    .build());
            return;
        }
        Map<String, Set<String>> keys = keys();
        if (!ApiKeys.hasFullKey(keys)) {
            requestContext.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("code", "AUTH_UNAVAILABLE",
                            "error", "Privileged plugin/app-chain operations require "
                                    + "a configured unscoped full API key"))
                    .build());
            return;
        }
        String apiKey = requestContext.getHeaderString(ApiKeys.HEADER);
        if (apiKey == null || !keys.containsKey(apiKey)) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Missing or invalid " + ApiKeys.HEADER)).build());
            return;
        }
        if (!keys.get(apiKey).isEmpty()) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "This API key is submit-only "
                            + "(topic-restricted) and may not call privileged "
                            + "operations"))
                    .build());
        }
    }

    private String artifactApiPathPrefix() {
        if (apiPathPrefix != null) {
            return apiPathPrefix;
        }
        return apiPrefixContract != null ? apiPrefixContract.pathPrefix() : null;
    }

    private static boolean isCanonicalOperationsPath(String rawPath, String apiPathPrefix) {
        if (apiPathPrefix == null) {
            return false;
        }
        String root = apiPathPrefix + "/plugin-operations";
        if (rawPath.equals(root) || rawPath.equals(root + "/bundles")) {
            return true;
        }
        String bundlePrefix = root + "/bundles/";
        String bundleId = rawPath.startsWith(bundlePrefix)
                ? rawPath.substring(bundlePrefix.length()) : null;
        return PluginOperationsResource.validBundleId(bundleId);
    }

    private Map<String, Set<String>> keys() {
        Map<String, Set<String>> current = parsedKeys;
        if (current == null) {
            current = ApiKeys.parse(apiKeysConfig().orElse(""));
            parsedKeys = current;
        }
        return current;
    }

    private Optional<String> apiKeysConfig() {
        return apiKeysConfig != null ? apiKeysConfig : ConfigProvider.getConfig()
                .getOptionalValue(YanoPropertyKeys.AppChain.API_KEYS, String.class);
    }
}
