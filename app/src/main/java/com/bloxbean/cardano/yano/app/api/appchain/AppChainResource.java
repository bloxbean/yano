package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.appchain.ReceivedAppMessage;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the app chain(s). Chain-scoped paths
 * ({@code /app-chain/chains/{chainId}/...}) address a specific chain; the
 * legacy chain-less paths ({@code /app-chain/...}) keep working when exactly
 * one chain is configured (ADR app-layer/006 E5.2).
 * The message body is an opaque application payload; it can be supplied as
 * hex ({@code bodyHex}) or plain text ({@code body}).
 */
@Path("app-chain")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppChainResource {

    @Inject
    AppChainGateways appChainGateways;

    // ------------------------------------------------------------------
    // Multi-chain surface
    // ------------------------------------------------------------------

    @GET
    @Path("chains")
    public Response chains() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppChainGateway gateway : appChainGateways.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("chainId", gateway.chainId());
            entry.put("tipHeight", gateway.tipHeight());
            entry.put("stateRoot", HexUtil.encodeHexString(gateway.stateRoot()));
            result.add(entry);
        }
        return Response.ok(result).build();
    }

    /** Chain-scoped subresource: /app-chain/chains/{chainId}/... */
    @Path("chains/{chainId}")
    public ChainScopedResource chain(@PathParam("chainId") String chainId) {
        AppChainGateway gateway = appChainGateways.byId(chainId)
                .orElseThrow(() -> new NotFoundException("Unknown app chain: " + chainId));
        return new ChainScopedResource(gateway);
    }

    // ------------------------------------------------------------------
    // Legacy chain-less surface (single-chain deployments)
    // ------------------------------------------------------------------

    @POST
    @Path("messages")
    public Response submit(ChainScopedResource.SubmitRequest request) {
        return singleChain().submit(request);
    }

    @GET
    @Path("messages")
    public Response messages(@QueryParam("limit") @DefaultValue("100") int limit,
                             @QueryParam("topic") String topic) {
        return singleChain().messages(limit, topic);
    }

    @GET
    @Path("status")
    public Response status() {
        return singleChain().status();
    }

    @GET
    @Path("tip")
    public Response tip() {
        return singleChain().tip();
    }

    @GET
    @Path("blocks/{height}")
    public Response block(@PathParam("height") long height) {
        return singleChain().block(height);
    }

    @GET
    @Path("proof/{keyHex}")
    public Response proof(@PathParam("keyHex") String keyHex) {
        return singleChain().proof(keyHex);
    }

    private ChainScopedResource singleChain() {
        int count = appChainGateways.all().size();
        if (count == 0) {
            throw new ServiceUnavailableException("App chain is not enabled on this node");
        }
        return appChainGateways.single()
                .map(ChainScopedResource::new)
                .orElseThrow(() -> new BadRequestException(
                        count + " app chains are hosted — use /app-chain/chains/{chainId}/..."));
    }

    /**
     * Endpoints for one chain; used both as a JAX-RS subresource and behind
     * the legacy chain-less paths.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public static class ChainScopedResource {

        private final AppChainGateway gateway;

        ChainScopedResource(AppChainGateway gateway) {
            this.gateway = gateway;
        }

        public record SubmitRequest(String topic, String body, String bodyHex) {
        }

        @POST
        @Path("messages")
        public Response submit(SubmitRequest request) {
            if (request == null || (isBlank(request.body()) && isBlank(request.bodyHex()))) {
                return badRequest("Either 'body' (text) or 'bodyHex' (hex bytes) is required");
            }
            byte[] body;
            try {
                body = !isBlank(request.bodyHex())
                        ? HexUtil.decodeHexString(request.bodyHex().trim())
                        : request.body().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                return badRequest("Invalid bodyHex: " + e.getMessage());
            }

            try {
                String messageId = gateway.submit(request.topic(), body);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("messageId", messageId);
                result.put("chainId", gateway.chainId());
                result.put("topic", request.topic() != null ? request.topic() : "");
                return Response.accepted(result).build();
            } catch (IllegalStateException e) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("error", e.getMessage())).build();
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
        }

        @GET
        @Path("messages")
        public Response messages(@QueryParam("limit") @DefaultValue("100") int limit,
                                 @QueryParam("topic") String topic) {
            try {
                List<Map<String, Object>> result = new ArrayList<>();
                for (ReceivedAppMessage message : gateway.recentMessages(limit)) {
                    if (topic != null && !topic.equals(message.topic())) {
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("messageId", message.messageIdHex());
                    entry.put("chainId", message.chainId());
                    entry.put("topic", message.topic());
                    entry.put("sender", message.senderHex());
                    entry.put("senderSeq", message.senderSeq());
                    entry.put("expiresAt", message.expiresAt());
                    entry.put("bodyHex", HexUtil.encodeHexString(message.body()));
                    entry.put("receivedAt", message.receivedAt());
                    entry.put("source", message.source().name());
                    result.add(entry);
                }
                return Response.ok(result).build();
            } catch (IllegalStateException e) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("error", e.getMessage())).build();
            }
        }

        @GET
        @Path("status")
        public Response status() {
            try {
                return Response.ok(gateway.status()).build();
            } catch (IllegalStateException e) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("error", e.getMessage())).build();
            }
        }

        @GET
        @Path("tip")
        public Response tip() {
            long height = gateway.tipHeight();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chainId", gateway.chainId());
            result.put("height", height);
            result.put("stateRoot", HexUtil.encodeHexString(gateway.stateRoot()));
            return Response.ok(result).build();
        }

        @GET
        @Path("blocks/{height}")
        public Response block(@PathParam("height") long height) {
            return gateway.block(height)
                    .map(b -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("height", b.height());
                        result.put("chainId", b.chainId());
                        result.put("prevHash", HexUtil.encodeHexString(b.prevHash()));
                        result.put("timestamp", b.timestamp());
                        result.put("messagesRoot", HexUtil.encodeHexString(b.messagesRoot()));
                        result.put("stateRoot", HexUtil.encodeHexString(b.stateRoot()));
                        result.put("proposer", HexUtil.encodeHexString(b.proposer()));
                        result.put("certSignatures", b.cert().signatures().size());
                        List<Map<String, Object>> msgs = new ArrayList<>();
                        for (var m : b.messages()) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("messageId", m.getMessageIdHex());
                            entry.put("topic", m.getTopic());
                            entry.put("sender", HexUtil.encodeHexString(m.getSender()));
                            entry.put("senderSeq", m.getSenderSeq());
                            entry.put("bodyHex", HexUtil.encodeHexString(m.getBody()));
                            msgs.add(entry);
                        }
                        result.put("messages", msgs);
                        return Response.ok(result).build();
                    })
                    .orElse(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No app block at height " + height)).build());
        }

        /**
         * MPF inclusion proof for a state key (hex). For the built-in ordered-log
         * app the key is the message id; the proof verifies the message's finalized
         * position against the (anchorable) state root.
         */
        @GET
        @Path("proof/{keyHex}")
        public Response proof(@PathParam("keyHex") String keyHex) {
            byte[] key;
            try {
                key = HexUtil.decodeHexString(keyHex);
            } catch (Exception e) {
                return badRequest("Invalid key hex");
            }
            var proof = gateway.stateProof(key);
            if (proof.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "No state entry for key")).build();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", keyHex);
            result.put("chainId", gateway.chainId());
            result.put("stateRoot", HexUtil.encodeHexString(gateway.stateRoot()));
            result.put("proofWireHex", HexUtil.encodeHexString(proof.get()));
            gateway.stateValue(key)
                    .ifPresent(v -> result.put("valueHex", HexUtil.encodeHexString(v)));
            gateway.messageHeight(key)
                    .ifPresent(h -> result.put("finalizedAtHeight", h));
            return Response.ok(result).build();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static Response badRequest(String message) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", message)).build();
        }
    }
}
