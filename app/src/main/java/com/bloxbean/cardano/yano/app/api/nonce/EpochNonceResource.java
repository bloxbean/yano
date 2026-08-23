package com.bloxbean.cardano.yano.app.api.nonce;

import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.app.api.ApiGroup;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;

import java.util.Map;

/**
 * REST endpoint for epoch nonce verification and debugging.
 */
@Extension(name = ApiGroup.ADMIN, value = "")
@Path("node")
@Produces(MediaType.APPLICATION_JSON)
public class EpochNonceResource {

    @Inject
    LedgerQuery ledgerQuery;

    @GET
    @Path("/epoch-nonce")
    public Response getCurrentEpochNonce() {
        Map<String, Object> info = ledgerQuery.getEpochNonceInfo();
        if (info == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Epoch nonce state not available"))
                    .build();
        }
        return Response.ok(info).build();
    }
}
