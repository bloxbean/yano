package org.yanoproject.app.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.YanoPropertyKeys;

import java.util.Map;

/** Reject before endpoint-specific exception handlers can misreport an absent L1 as missing data. */
@Provider
@RequiresCardano
@Priority(Priorities.AUTHORIZATION + 1)
public final class CardanoAvailabilityFilter implements ContainerRequestFilter {
    @ConfigProperty(name = YanoPropertyKeys.MODE, defaultValue = "cardano")
    String mode;

    @Override
    public void filter(ContainerRequestContext request) {
        if (RuntimeMode.parse(mode) == RuntimeMode.APP_LEDGER) {
            request.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "cardano_unavailable", "message",
                            "This operation requires Cardano L1; yano.mode=app-ledger"))
                    .build());
        }
    }
}
