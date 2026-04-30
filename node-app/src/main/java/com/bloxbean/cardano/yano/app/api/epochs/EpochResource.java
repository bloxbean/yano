package com.bloxbean.cardano.yano.app.api.epochs;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.app.api.EpochUtil;
import com.bloxbean.cardano.yano.app.api.epochs.dto.AdaPotDto;
import com.bloxbean.cardano.yano.app.api.epochs.dto.EpochDto;
import com.bloxbean.cardano.yano.app.api.epochs.dto.ProtocolParamsDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("epochs")
@Produces(MediaType.APPLICATION_JSON)
public class EpochResource {

    private static final Logger log = LoggerFactory.getLogger(EpochResource.class);

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/latest")
    public Response getLatestEpoch() {
        int epoch = currentEpoch();
        return Response.ok(EpochDto.ofEpoch(epoch)).build();
    }

    @GET
    @Path("/latest/parameters")
    public Response getLatestParameters() {
        return protocolParamsResponse(currentEpoch());
    }

    @GET
    @Path("/{number}/parameters")
    public Response getParametersByEpoch(@PathParam("number") int number) {
        if (number < 0) return badRequest("epoch must be greater than or equal to 0");
        return protocolParamsResponse(number);
    }

    @GET
    @Path("/latest/adapot")
    public Response getLatestAdaPot() {
        LedgerStateProvider ledgerStateProvider = ledgerStateProvider();
        if (ledgerStateProvider == null) return adaPotUnavailable();

        try {
            return ledgerStateProvider.getLatestAdaPot(currentEpoch())
                    .map(AdaPotDto::from)
                    .map(dto -> Response.ok(dto).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No AdaPot data available"))
                            .build());
        } catch (IllegalStateException e) {
            return ledgerStateReadUnavailable("AdaPot state read failed", e);
        }
    }

    @GET
    @Path("/{number}/adapot")
    public Response getAdaPot(@PathParam("number") int number) {
        if (number < 0) return badRequest("epoch must be greater than or equal to 0");

        LedgerStateProvider ledgerStateProvider = ledgerStateProvider();
        if (ledgerStateProvider == null) return adaPotUnavailable();

        try {
            return ledgerStateProvider.getAdaPot(number)
                    .map(AdaPotDto::from)
                    .map(dto -> Response.ok(dto).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No AdaPot data for epoch " + number))
                            .build());
        } catch (IllegalStateException e) {
            return ledgerStateReadUnavailable("AdaPot state read failed", e);
        }
    }

    @GET
    @Path("/adapots")
    public Response listAdaPots(@QueryParam("from") @DefaultValue("0") int from,
                                @QueryParam("to") @DefaultValue("-1") int to,
                                @QueryParam("page") @DefaultValue("1") int page,
                                @QueryParam("count") @DefaultValue("20") int count,
                                @QueryParam("order") @DefaultValue("asc") String order) {
        if (from < 0) return badRequest("from must be greater than or equal to 0");
        if (page < 1) return badRequest("page must be greater than or equal to 1");
        if (count < 1 || count > 100) return badRequest("count must be between 1 and 100");

        int resolvedTo = to >= 0 ? to : currentEpoch();
        if (resolvedTo < from) return badRequest("to must be greater than or equal to from");

        boolean descending;
        if ("asc".equalsIgnoreCase(order)) {
            descending = false;
        } else if ("desc".equalsIgnoreCase(order)) {
            descending = true;
        } else {
            return badRequest("order must be asc or desc");
        }

        LedgerStateProvider ledgerStateProvider = ledgerStateProvider();
        if (ledgerStateProvider == null) return adaPotUnavailable();

        try {
            List<AdaPotDto> adaPots = new ArrayList<>();
            long offset = (long) (page - 1) * count;
            if (offset > Integer.MAX_VALUE) return Response.ok(adaPots).build();

            int firstEpoch = descending ? resolvedTo - (int) offset : from + (int) offset;
            for (int i = 0; i < count; i++) {
                int epoch = descending ? firstEpoch - i : firstEpoch + i;
                if (epoch < from || epoch > resolvedTo) break;
                ledgerStateProvider.getAdaPot(epoch)
                        .map(AdaPotDto::from)
                        .ifPresent(adaPots::add);
            }

            return Response.ok(adaPots).build();
        } catch (IllegalStateException e) {
            return ledgerStateReadUnavailable("AdaPot state read failed", e);
        }
    }

    private Response protocolParamsResponse(int epoch) {
        LedgerStateProvider ledgerStateProvider = nodeAPI.getLedgerStateProvider();
        if (ledgerStateProvider == null) {
            log.warn("Protocol parameters requested but ledger state provider is not available");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Ledger state provider not available"))
                    .build();
        }

        try {
            return ledgerStateProvider.getProtocolParameters(epoch)
                    .map(snapshot -> ProtocolParamsDto.from(snapshot, nodeAPI.getEpochNonce(epoch)))
                    .map(dto -> Response.ok(dto).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Protocol parameters not available for epoch " + epoch))
                            .build());
        } catch (IllegalStateException e) {
            return ledgerStateReadUnavailable("Protocol parameter state read failed", e);
        }
    }

    private LedgerStateProvider ledgerStateProvider() {
        LedgerStateProvider ledgerStateProvider = nodeAPI.getLedgerStateProvider();
        if (ledgerStateProvider == null || !ledgerStateProvider.isAdaPotTrackingEnabled()) {
            return null;
        }
        return ledgerStateProvider;
    }

    private Response adaPotUnavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "AdaPot tracking is not enabled"))
                .build();
    }

    private Response ledgerStateReadUnavailable(String message, IllegalStateException e) {
        log.warn("{}: {}", message, e.getMessage());
        log.debug("{} details", message, e);
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", message))
                .build();
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message))
                .build();
    }

    private int currentEpoch() {
        ChainState cs = nodeAPI.getChainState();
        ChainTip tip = cs != null ? cs.getTip() : null;
        if (tip == null) return 0;
        return EpochUtil.slotToEpoch(tip.getSlot(), nodeAPI.getConfig());
    }
}
