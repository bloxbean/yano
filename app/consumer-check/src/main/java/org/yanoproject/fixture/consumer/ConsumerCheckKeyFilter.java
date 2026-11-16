package org.yanoproject.fixture.consumer;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.ConfigProvider;
import org.yanoproject.app.spi.ApiKeys;

import java.util.Map;
import java.util.Set;

/** Downstream key filter built on the shared {@link ApiKeys} helper: a full key opens the fixture route. */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ConsumerCheckKeyFilter implements ContainerRequestFilter {

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
        Map<String, Set<String>> keys = ApiKeys.parse(ConfigProvider.getConfig()
                .getOptionalValue("yano.app-chain.api.keys", String.class).orElse(""));
        Set<String> topics = keys.get(request.getHeaderString(ApiKeys.HEADER));
        if (topics == null || !topics.isEmpty()) {
            request.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
        }
    }
}
