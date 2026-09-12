package org.yanoproject.app.api.utxos;

import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.MempoolQueryGateway;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.UtxoReadView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.app.api.ApiGroup;
import org.yanoproject.app.api.utxos.dto.UtxoDto;
import org.yanoproject.app.api.utxos.dto.UtxoDtoMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Extension(name = ApiGroup.CORE, value = "")
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UtxoResource {
    private static final Logger LOG = LoggerFactory.getLogger(UtxoResource.class);

    @Inject
    LedgerQuery ledgerQuery;

    @Inject
    MempoolQueryGateway mempoolQueryGateway;

    private UtxoState utxo() {
        return ledgerQuery.getUtxoState();
    }

    @GET
    @Path("/addresses/{address}/utxos")
    public Response getUtxosByAddress(@PathParam("address") String address,
                                      @QueryParam("page") @DefaultValue("1") int page,
                                      @QueryParam("count") @DefaultValue("20") int count,
                                      @QueryParam("order") @DefaultValue("asc") String order,
                                      @QueryParam("use_payment_credential") @DefaultValue("false") boolean usePaymentCredential,
                                      @QueryParam("include_mempool") @DefaultValue("false") boolean includeMempool) {
        UtxoState u = utxo();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }
        if (count <= 0) count = 20;
        if (count > UtxoReadView.MAX_PAGE_SIZE) return invalidCount();
        if (page < 1) page = 1;
        if (includeMempool) return overlay(address, usePaymentCredential, null, page, count, order);
        var list = usePaymentCredential
                ? u.getUtxosByPaymentCredential(address, page, count)
                : u.getUtxosByAddress(address, page, count);
        List<UtxoDto> body = UtxoDtoMapper.toDtoList(list, ledgerQuery::slotToUnixTime);
        return Response.ok(body).build();
    }

    @GET
    @Path("/addresses/{address}/utxos/{asset}")
    public Response getUtxosByAddressAndAsset(@PathParam("address") String address,
                                              @PathParam("asset") String asset,
                                              @QueryParam("page") @DefaultValue("1") int page,
                                              @QueryParam("count") @DefaultValue("20") int count,
                                              @QueryParam("order") @DefaultValue("asc") String order,
                                              @QueryParam("include_mempool") @DefaultValue("false") boolean includeMempool) {
        UtxoState u = utxo();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }
        if (count <= 0) count = 20;
        if (count > UtxoReadView.MAX_PAGE_SIZE) return invalidCount();
        if (page < 1) page = 1;
        if (includeMempool) return overlay(address, false, asset, page, count, order);

        // Fetch UTXOs, then filter by asset
        var list = u.getUtxosByAddress(address, page, count);
        List<Utxo> filtered;
        if ("lovelace".equalsIgnoreCase(asset)) {
            filtered = list; // All UTXOs have lovelace
        } else {
            filtered = list.stream()
                    .filter(utxo -> utxo.assets() != null && utxo.assets().stream()
                            .anyMatch(a -> asset.equals(a.policyId() + a.assetName())))
                    .collect(Collectors.toList());
        }
        List<UtxoDto> body = UtxoDtoMapper.toDtoList(filtered, ledgerQuery::slotToUnixTime);
        return Response.ok(body).build();
    }

    @GET
    @Path("/utxos/{txHash}/{index}")
    public Response getUtxo(@PathParam("txHash") String txHash,
                            @PathParam("index") int index,
                            @QueryParam("include_mempool")
                            @DefaultValue("false") boolean includeMempool) {
        UtxoState u = utxo();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }
        Outpoint outpoint = new Outpoint(txHash, index);
        var result = includeMempool
                ? mempoolQueryGateway.resolveUtxo(outpoint)
                : u.getUtxo(outpoint);
        return result
                .map(utxo -> Response.ok(UtxoDtoMapper.toDto(utxo, ledgerQuery::slotToUnixTime)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/credentials/{paymentCredential}/utxos")
    public Response getUtxosByPaymentCredential(@PathParam("paymentCredential") String paymentCredential,
                                                @QueryParam("page") @DefaultValue("1") int page,
                                                @QueryParam("count") @DefaultValue("20") int count,
                                                @QueryParam("order") @DefaultValue("asc") String order,
                                                @QueryParam("include_mempool") @DefaultValue("false") boolean includeMempool) {
        UtxoState u = utxo();
        if (u == null || !u.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"UTXO state disabled\"}")
                    .build();
        }
        if (count <= 0) count = 20;
        if (count > UtxoReadView.MAX_PAGE_SIZE) return invalidCount();
        if (page < 1) page = 1;
        if (includeMempool) return overlay(paymentCredential, true, null, page, count, order);
        var list = u.getUtxosByPaymentCredential(paymentCredential, page, count);
        List<UtxoDto> body = UtxoDtoMapper.toDtoList(list, ledgerQuery::slotToUnixTime);
        return Response.ok(body).build();
    }


    private Response overlay(String query, boolean credential, String asset, int page, int count, String order) {
        try {
            var list = mempoolQueryGateway.listUtxos(query, credential, asset, page, count,
                    "desc".equalsIgnoreCase(order));
            return Response.ok(UtxoDtoMapper.toDtoList(list, ledgerQuery::slotToUnixTime)).build();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOG.warn("Mempool UTxO listing unavailable: {}", e.toString());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Mempool UTxO listings unavailable")).build();
        }
    }

    private static Response invalidCount() {
        return Response.status(400).entity(Map.of("error", "count must not exceed " + UtxoReadView.MAX_PAGE_SIZE)).build();
    }
}
