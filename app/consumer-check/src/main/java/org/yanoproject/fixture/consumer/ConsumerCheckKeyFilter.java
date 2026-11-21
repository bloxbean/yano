package org.yanoproject.fixture.consumer;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.yanoproject.app.spi.ApiKeys;

import java.util.Map;
import java.util.Set;

/**
 * Downstream key filter built on the shared {@link ApiKeys} helper. A non-canonical path is 404, no
 * configured full key is 503, a request without a full or admin key is 401, and a body above
 * {@link #BODY_LIMIT} bytes is rejected before the resource reads it.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ConsumerCheckKeyFilter implements ContainerRequestFilter {

    static final String ADMIN_KEYS = "yano.fixture.consumer-check.admin-keys";
    static final int BODY_LIMIT = 16;

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext request) {
        if (resourceInfo.getResourceClass() != ConsumerCheckResource.class) {
            return;
        }
        if (!ApiKeys.isCanonical(ApiKeys.rawPath(request.getUriInfo()))) {
            request.abortWith(Response.status(Response.Status.NOT_FOUND).build());
            return;
        }
        Config config = ConfigProvider.getConfig();
        Map<String, Set<String>> keys = ApiKeys.parse(
                config.getOptionalValue("yano.app-chain.api.keys", String.class).orElse(""));
        if (!ApiKeys.hasFullKey(keys)) {
            request.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE).build());
            return;
        }
        String presented = request.getHeaderString(ApiKeys.HEADER);
        Set<String> topics = keys.get(presented);
        boolean admin = presented != null && ApiKeys.parseSet(config.getOptionalValue(ADMIN_KEYS, String.class).orElse(""))
                .contains(presented);
        if (!admin && (topics == null || !topics.isEmpty())) {
            request.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
            return;
        }
        ApiKeys.boundEntity(request, BODY_LIMIT);
    }
}
