package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("node")
@Produces(MediaType.APPLICATION_JSON)
public class YanoResource {

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/status")
    public Response getStatus() {
        return Response.ok(nodeAPI.getStatus()).build();
    }

    @POST
    @Path("/start")
    public Response start() {
        if (nodeAPI.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Node is already running"))
                    .build();
        }

        try {
            nodeAPI.start();
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
        if (!nodeAPI.isRunning()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Node is not running"))
                    .build();
        }

        try {
            nodeAPI.stop();
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
        var tip = nodeAPI.getLocalTip();
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
        var config = nodeAPI.getConfig();
        // Build a safe response map — epoch fields may throw if not yet initialized from genesis
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("protocolMagic", config.getProtocolMagic());
        result.put("clientEnabled", config.isClientEnabled());
        result.put("serverEnabled", config.isServerEnabled());
        result.put("devMode", config.isDevMode());
        if (config instanceof YanoConfig yc) {
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
            String txHash = nodeAPI.submitTransaction(txCbor);
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
        String params = nodeAPI.getProtocolParameters();
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
        var status = nodeAPI.getEpochCalcStatus();
        if (status == null) {
            return Response.ok(Map.of("status", "OK")).build();
        }
        return Response.ok(status).build();
    }

    @POST
    @Path("/recover")
    public Response recoverChainState() {
        try {
            if (nodeAPI.isRunning()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Cannot recover chain state while node is running. Please stop the node first."))
                        .build();
            }

            boolean recovered = nodeAPI.recoverChainState();

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
