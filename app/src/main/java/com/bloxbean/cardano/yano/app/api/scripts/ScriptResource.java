package com.bloxbean.cardano.yano.app.api.scripts;

import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.MempoolQueryGateway;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.app.api.ApiGroup;
import com.bloxbean.cardano.yano.app.api.scripts.dto.ScriptCborDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;

@Extension(name = ApiGroup.CORE, value = "")
@Path("scripts")
@Produces(MediaType.APPLICATION_JSON)
public class ScriptResource {

    @Inject
    LedgerQuery ledgerQuery;

    @Inject
    MempoolQueryGateway mempoolQueryGateway;

    @GET
    @Path("/{script_hash}/cbor")
    public Response getScriptCbor(@PathParam("script_hash") String scriptHash,
                                  @QueryParam("include_mempool")
                                  @DefaultValue("false") boolean includeMempool) {
        UtxoState u = ledgerQuery.getUtxoState();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }

        var scriptRef = includeMempool
                ? mempoolQueryGateway.getScriptRefBytesByHash(scriptHash)
                : u.getScriptRefBytesByHash(scriptHash);
        return scriptRef
                .map(ReferenceScriptUtil::deserializeScriptRef)
                .map(script -> {
                    try {
                        return Response.ok(new ScriptCborDto(
                                HexUtil.encodeHexString(script.serializeScriptBody()))).build();
                    } catch (CborSerializationException e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Script not found\"}")
                        .build());
    }
}
