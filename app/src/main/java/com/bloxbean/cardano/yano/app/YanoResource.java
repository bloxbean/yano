package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@Path("node")
@Produces(MediaType.APPLICATION_JSON)
public class YanoResource {

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String applicationVersion;

    @Inject
    NodeLifecycle nodeLifecycle;

    @Inject
    ChainQuery chainQuery;

    @Inject
    LedgerQuery ledgerQuery;

    @Inject
    TxGateway txGateway;

    @GET
    @Path("/status")
    public Response getStatus() {
        return Response.ok(nodeLifecycle.getStatus()).build();
    }

    @GET
    @Path("/peers")
    public Response getPeers() {
        return Response.ok(nodeLifecycle.getPeers()).build();
    }

    @POST
    @Path("/start")
    public Response start() {
        if (nodeLifecycle.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Node is already running"))
                    .build();
        }

        try {
            nodeLifecycle.start();
            return Response.ok(Map.of("message", "Node started successfully")).build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to start node: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/stop")
    public Response stop() {
        if (!nodeLifecycle.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Node is not running"))
                    .build();
        }

        try {
            nodeLifecycle.stop();
            return Response.ok(Map.of("message", "Node stopped successfully")).build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to stop node: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/tip")
    public Response getLocalTip() {
        var tip = chainQuery.getLocalTip();
        if (tip == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No local tip available"))
                    .build();
        }
        return Response.ok(tip).build();
    }

    @GET
    @Path("/config")
    public Response getConfig() {
        var config = nodeLifecycle.getConfig();
        // Build a safe response map — epoch fields may throw if not yet initialized from genesis
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("protocolMagic", config.getProtocolMagic());
        result.put("version", applicationVersion);
        result.put("clientEnabled", config.isClientEnabled());
        result.put("serverEnabled", config.isServerEnabled());
        result.put("devMode", config.isDevMode());
        if (config instanceof YanoConfig yc) {
            result.put("network", yc.getNetwork());
            result.put("remoteHost", yc.getRemoteHost());
            result.put("remotePort", yc.getRemotePort());
            result.put("serverPort", yc.getServerPort());
            result.put("useRocksDB", yc.isUseRocksDB());
            result.put("enableBlockProducer", yc.isEnableBlockProducer());
            result.put("enablePipelinedSync", yc.isEnablePipelinedSync());
            if (yc.isEpochParamsInitialized()) {
                result.put("epochLength", yc.getEpochLength());
                result.put("byronSlotsPerEpoch", yc.getByronSlotsPerEpoch());
                result.put("firstNonByronSlot", yc.getFirstNonByronSlot());
            }
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/tx/submit")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response submitTransaction(byte[] txCbor) {
        if (txCbor == null || txCbor.length == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Transaction CBOR bytes required"))
                    .build();
        }

        try {
            String txHash = txGateway.submitTransaction(txCbor);
            return Response.accepted(Map.of("txHash", txHash)).build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to submit transaction: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/protocol-params")
    public Response getProtocolParameters() {
        String params = ledgerQuery.getProtocolParameters();
        if (params == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Protocol parameters not available"))
                    .build();
        }
        return Response.ok(params).build();
    }

    @GET
    @Path("/epoch-calc-status")
    public Response getEpochCalcStatus() {
        var status = ledgerQuery.getEpochCalcStatus();
        if (status == null) {
            return Response.ok(Map.of("status", "OK")).build();
        }
        return Response.ok(status).build();
    }

    @POST
    @Path("/recover")
    public Response recoverChain() {
        try {
            if (nodeLifecycle.isRunning()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Cannot recover chain state while node is running. Please stop the node first."))
                        .build();
            }

            boolean recovered = chainQuery.recoverChain();

            if (recovered) {
                return Response.ok(Map.of("message", "Chain state recovery completed successfully")).build();
            } else {
                return Response.ok(Map.of("message", "No corruption detected or recovery not needed")).build();
            }

        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Recovery failed: " + e.getMessage()))
                    .build();
        }
    }
}
