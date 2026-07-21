package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.appchain.AppQueryPath;
import com.bloxbean.cardano.yano.api.appchain.ReceivedAppMessage;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;

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
 * <p>
 * The chain-less aliases are HIDDEN from the OpenAPI document
 * ({@code @Operation(hidden = true)}): on a multi-chain node they can only
 * answer 400, so Swagger UI documents the chain-scoped surface exclusively.
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
                .orElseThrow(() -> jsonError(Response.Status.NOT_FOUND, "Unknown app chain: " + chainId));
        return new ChainScopedResource(gateway);
    }

    // ------------------------------------------------------------------
    // Legacy chain-less surface (single-chain deployments)
    // ------------------------------------------------------------------

    @POST
    @Operation(hidden = true)
    @Path("messages")
    @AppChainAccess(AppChainAccess.Level.SUBMIT)
    public Response submit(ChainScopedResource.SubmitRequest request) {
        return singleChain().submit(request);
    }

    @GET
    @Operation(hidden = true)
    @Path("messages")
    public Response messages(@QueryParam("limit") @DefaultValue("100") int limit,
                             @QueryParam("topic") String topic) {
        return singleChain().messages(limit, topic);
    }

    @GET
    @Operation(hidden = true)
    @Path("status")
    public Response status() {
        return singleChain().status();
    }

    @GET
    @Operation(hidden = true)
    @Path("tip")
    public Response tip() {
        return singleChain().tip();
    }

    @GET
    @Operation(hidden = true)
    @Path("blocks/{height}")
    public Response block(@PathParam("height") long height) {
        return singleChain().block(height);
    }

    @GET
    @Operation(hidden = true)
    @Path("proof/{keyHex}")
    public Response proof(@PathParam("keyHex") String keyHex) {
        return singleChain().proof(keyHex);
    }

    @GET
    @Operation(hidden = true)
    @Path("evidence/{messageIdHex}")
    public Response evidence(@PathParam("messageIdHex") String messageIdHex) {
        return singleChain().evidence(messageIdHex);
    }

    @GET
    @Operation(hidden = true)
    @Path("blocks")
    public Response blocks(@QueryParam("from") @DefaultValue("-1") long from,
                           @QueryParam("limit") @DefaultValue("20") int limit) {
        return singleChain().blocks(from, limit);
    }

    @GET
    @Operation(hidden = true)
    @Path("messages/{messageIdHex}")
    public Response messageById(@PathParam("messageIdHex") String messageIdHex) {
        return singleChain().messageById(messageIdHex);
    }

    @GET
    @Operation(hidden = true)
    @Path("messages/by-topic/{topic}")
    public Response messagesByTopic(@PathParam("topic") String topic,
                                    @QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                                    @QueryParam("limit") @DefaultValue("100") int limit) {
        return singleChain().messagesByTopic(topic, fromHeight, limit);
    }

    @GET
    @Operation(hidden = true)
    @Path("messages/by-sender/{senderHex}")
    public Response messagesBySender(@PathParam("senderHex") String senderHex,
                                     @QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                                     @QueryParam("limit") @DefaultValue("100") int limit) {
        return singleChain().messagesBySender(senderHex, fromHeight, limit);
    }

    @GET
    @Operation(hidden = true)
    @Path("effects")
    public Response effects(@QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                            @QueryParam("limit") @DefaultValue("100") int limit) {
        return singleChain().effects(fromHeight, limit);
    }

    @GET
    @Operation(hidden = true)
    @Path("effects/{height}/{ordinal}")
    public Response effect(@PathParam("height") long height, @PathParam("ordinal") int ordinal) {
        return singleChain().effect(height, ordinal);
    }

    @GET
    @Operation(hidden = true)
    @Path("effects/{height}/{ordinal}/proof")
    public Response effectProof(@PathParam("height") long height,
                                @PathParam("ordinal") int ordinal) {
        return singleChain().effectProof(height, ordinal);
    }

    @GET
    @Operation(hidden = true)
    @Path("effects/stats")
    public Response effectStats() {
        return singleChain().effectStats();
    }

    @POST
    @Operation(hidden = true)
    @Path("effects/{height}/{ordinal}/requeue")
    public Response requeueEffect(@PathParam("height") long height,
                                  @PathParam("ordinal") int ordinal) {
        return singleChain().requeueEffect(height, ordinal);
    }

    @POST
    @Operation(hidden = true)
    @Path("effects/{height}/{ordinal}/cancel")
    public Response cancelEffect(@PathParam("height") long height,
                                 @PathParam("ordinal") int ordinal,
                                 @QueryParam("reason") @DefaultValue("operator-cancel") String reason) {
        return singleChain().cancelEffect(height, ordinal, reason);
    }

    @POST
    @Operation(hidden = true)
    @Path("effects/claim")
    public Response claimEffects(ChainScopedResource.ClaimRequest request) {
        return singleChain().claimEffects(request);
    }

    @POST
    @Operation(hidden = true)
    @Path("effects/{height}/{ordinal}/report")
    public Response reportEffect(@PathParam("height") long height,
                                 @PathParam("ordinal") int ordinal,
                                 ChainScopedResource.ReportRequest request) {
        return singleChain().reportEffect(height, ordinal, request);
    }

    @POST
    @Operation(hidden = true)
    @Path("admin/pause")
    public Response pause() {
        return singleChain().pause();
    }

    @POST
    @Operation(hidden = true)
    @Path("admin/resume")
    public Response resume() {
        return singleChain().resume();
    }

    @POST
    @Operation(hidden = true)
    @Path("admin/drain-pool")
    public Response drainPool() {
        return singleChain().drainPool();
    }

    @POST
    @Operation(hidden = true)
    @Path("admin/force-anchor")
    public Response forceAnchor() {
        return singleChain().forceAnchor();
    }

    @POST
    @Operation(hidden = true)
    @Path("admin/anchor/bootstrap")
    public Response bootstrapScriptAnchor() {
        return singleChain().bootstrapScriptAnchor();
    }

    @POST
    @Operation(hidden = true)
    @Path("admin/unlock-stale-round")
    public Response unlockStaleRound() {
        return singleChain().unlockStaleRound();
    }

    @GET
    @Operation(hidden = true)
    @Path("stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@QueryParam("fromHeight") @DefaultValue("-1") long fromHeight,
                       @QueryParam("topic") String topic,
                       @jakarta.ws.rs.core.Context jakarta.ws.rs.sse.Sse sse,
                       @jakarta.ws.rs.core.Context jakarta.ws.rs.sse.SseEventSink sink) {
        singleChain().stream(fromHeight, topic, sse, sink);
    }

    private ChainScopedResource singleChain() {
        int count = appChainGateways.all().size();
        if (count == 0) {
            throw jsonError(Response.Status.SERVICE_UNAVAILABLE,
                    "App chain is not enabled on this node");
        }
        return appChainGateways.single()
                .map(ChainScopedResource::new)
                .orElseThrow(() -> jsonError(Response.Status.BAD_REQUEST,
                        count + " app chains are hosted — use /app-chain/chains/{chainId}/..."));
    }

    /** WebApplicationException carrying the {@code {"error": ...}} JSON contract. */
    private static WebApplicationException jsonError(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message))
                .build());
    }

    /**
     * Endpoints for one chain; used both as a JAX-RS subresource and behind
     * the legacy chain-less paths.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RegisterForReflection
    public static class ChainScopedResource {

        private final AppChainGateway gateway;

        ChainScopedResource(AppChainGateway gateway) {
            this.gateway = gateway;
        }

        public record SubmitRequest(String topic, String body, String bodyHex) {
        }

        /** ADR-011.3 query parameters; omitted or empty hex means empty bytes. */
        @JsonIgnoreProperties(ignoreUnknown = false)
        public record QueryRequest(
                @JsonDeserialize(using = StrictStringDeserializer.class) String paramsHex) {

            /** Keep the envelope strict even if the host mapper ignores unknown properties. */
            @JsonAnySetter
            public void rejectUnknownField(String name, Object ignored) {
                throw new IllegalArgumentException("Unknown app-chain query field: " + name);
            }
        }

        /** Prevent Jackson's scalar-to-string coercion in the strict query envelope. */
        public static final class StrictStringDeserializer extends JsonDeserializer<String> {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context)
                    throws java.io.IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                    return (String) context.handleUnexpectedToken(String.class, parser);
                }
                return parser.getText();
            }
        }

        @POST
        @Path("messages")
        @AppChainAccess(AppChainAccess.Level.SUBMIT)
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
            } catch (com.bloxbean.cardano.yano.api.appchain.PoolFullException e) {
                // Backpressure (ADR 008.1 I1.1): the message was NOT retained/relayed
                return Response.status(429)
                        .entity(Map.of("error", e.getMessage())).build();
            } catch (IllegalStateException e) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("error", e.getMessage())).build();
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
        }

        /**
         * Execute the machine's read hook against one root-fixed committed
         * snapshot. This POST is semantically READ: the body carries bounded
         * opaque parameters and no state transition is performed.
         */
        @POST
        @Path("query/{path: .+}")
        @AppChainAccess(AppChainAccess.Level.READ)
        public Response query(@Encoded @PathParam("path") String path, QueryRequest request) {
            if (path != null && path.length() > AppQueryPath.MAX_LENGTH) {
                return Response.status(413)
                        .entity(Map.of("code", "REQUEST_TOO_LARGE",
                                "error", "App-chain query path exceeds the size limit"))
                        .build();
            }
            try {
                path = AppQueryPath.validate(path);
            } catch (IllegalArgumentException invalidPath) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("code", "INVALID_REQUEST",
                                "error", "App-chain query path is invalid"))
                        .build();
            }
            byte[] params;
            try {
                String encoded = request != null ? request.paramsHex() : null;
                if (encoded == null || encoded.isEmpty()) {
                    params = new byte[0];
                } else {
                    if (encoded.length() > 2 * 64 * 1024) {
                        throw new com.bloxbean.cardano.yano.api.appchain.AppQueryException(
                                com.bloxbean.cardano.yano.api.appchain.AppQueryException.Code.REQUEST_TOO_LARGE,
                                "App-chain query request exceeds the size limit");
                    }
                    if ((encoded.length() & 1) != 0
                            || !encoded.matches("[0-9a-f]+")) {
                        return badRequest("paramsHex must be canonical lowercase hex");
                    }
                    params = HexUtil.decodeHexString(encoded);
                }
            } catch (com.bloxbean.cardano.yano.api.appchain.AppQueryException tooLarge) {
                return Response.status(413)
                        .entity(Map.of("code", tooLarge.code().name(),
                                "error", tooLarge.getMessage()))
                        .build();
            } catch (Exception invalidHex) {
                return badRequest("Invalid paramsHex");
            }

            try {
                var result = gateway.query(path, params);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("chainId", result.chainId());
                response.put("stateMachineId", result.stateMachineId());
                response.put("committedHeight", result.committedHeight());
                response.put("stateRoot", HexUtil.encodeHexString(result.stateRoot()));
                response.put("payloadHex", HexUtil.encodeHexString(result.payload()));
                return Response.ok(response).build();
            } catch (com.bloxbean.cardano.yano.api.appchain.AppQueryException failure) {
                int status = switch (failure.code()) {
                    case INVALID_REQUEST -> 400;
                    case REQUEST_TOO_LARGE -> 413;
                    case UNSUPPORTED -> 404;
                    case BUSY -> 429;
                    case RESULT_TOO_LARGE -> 502;
                    case UNAVAILABLE -> 503;
                    case TIMEOUT -> 504;
                    case FAILED -> 500;
                };
                String message = failure.code()
                        == com.bloxbean.cardano.yano.api.appchain.AppQueryException.Code.FAILED
                        ? "Query execution failed" : failure.getMessage();
                return Response.status(status)
                        .entity(Map.of("code", failure.code().name(), "error", message))
                        .build();
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
        /**
         * Portable, offline-verifiable evidence bundle for a finalized message
         * (ADR 006 E3.4): block(s) + members + L1 anchor reference. Verify with
         * core-api's {@code EvidenceVerifier} — no node access needed.
         */
        public record SnapshotRequest(String path) {
        }

        // --- Query surface (ADR 006 E3.3) ---

        /** Paged block summaries, ascending from {@code from} (default: tip-window). */
        @GET
        @Path("blocks")
        public Response blocks(@QueryParam("from") @DefaultValue("-1") long from,
                               @QueryParam("limit") @DefaultValue("20") int limit) {
            int pageSize = Math.max(1, Math.min(limit, 200));
            long tip = gateway.tipHeight();
            long start = from >= 1 ? from : Math.max(1, tip - pageSize + 1);
            List<Map<String, Object>> page = new ArrayList<>();
            for (long h = start; h < start + pageSize && h <= tip; h++) {
                long height = h;
                gateway.block(height).ifPresent(b -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("height", b.height());
                    summary.put("timestamp", b.timestamp());
                    summary.put("stateRoot", HexUtil.encodeHexString(b.stateRoot()));
                    summary.put("messageCount", b.messages().size());
                    summary.put("certSignatures", b.cert().signatures().size());
                    page.add(summary);
                });
            }
            return Response.ok(Map.of("chainId", gateway.chainId(), "tipHeight", tip,
                    "from", start, "blocks", page)).build();
        }

        /** Lookup one finalized message by id: position + full content. */
        @GET
        @Path("messages/{messageIdHex}")
        public Response messageById(@PathParam("messageIdHex") String messageIdHex) {
            byte[] messageId;
            try {
                messageId = HexUtil.decodeHexString(messageIdHex);
            } catch (Exception e) {
                return badRequest("Invalid messageId hex");
            }
            String normalizedId = HexUtil.encodeHexString(messageId); // canonical lowercase
            return gateway.messageHeight(messageId)
                    .flatMap(height -> gateway.block(height).map(b -> {
                        int index = 0;
                        for (var m : b.messages()) {
                            if (m.getMessageIdHex().equalsIgnoreCase(normalizedId)) {
                                Map<String, Object> result = new LinkedHashMap<>();
                                result.put("messageId", m.getMessageIdHex());
                                result.put("chainId", b.chainId());
                                result.put("height", height);
                                result.put("index", index);
                                result.put("topic", m.getTopic());
                                result.put("sender", HexUtil.encodeHexString(m.getSender()));
                                result.put("senderSeq", m.getSenderSeq());
                                result.put("bodyHex", HexUtil.encodeHexString(m.getBody()));
                                return Response.ok(result).build();
                            }
                            index++;
                        }
                        return Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "Message index inconsistent for " + messageIdHex)).build();
                    }))
                    .orElse(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No finalized message with id " + messageIdHex)).build());
        }

        /** Finalized message refs on a topic, ascending (height, index). */
        @GET
        @Path("messages/by-topic/{topic}")
        public Response messagesByTopic(@PathParam("topic") String topic,
                                        @QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                                        @QueryParam("limit") @DefaultValue("100") int limit) {
            return Response.ok(Map.of("chainId", gateway.chainId(), "topic", topic,
                    "messages", gateway.messagesByTopic(topic, fromHeight, limit))).build();
        }

        /** Emitted effect records, ascending (height, ordinal) — consensus view (ADR-010 F12). */
        @GET
        @Path("effects")
        public Response effects(@QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                                @QueryParam("limit") @DefaultValue("100") int limit) {
            return Response.ok(Map.of("chainId", gateway.chainId(),
                    "effects", gateway.effects(fromHeight, limit))).build();
        }

        /** One emitted effect record by chain position, joined with this node's runtime status. */
        @GET
        @Path("effects/{height}/{ordinal}")
        public Response effect(@PathParam("height") long height,
                               @PathParam("ordinal") int ordinal) {
            return gateway.effect(height, ordinal)
                    .map(view -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("record", view);
                        gateway.effectRuntimeStatus(height, ordinal)
                                .ifPresent(status -> result.put("execution", status));
                        return Response.ok(result).build();
                    })
                    .orElse(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No effect at " + height + "/" + ordinal)).build());
        }

        /**
         * Record-to-effectsRoot-to-historical-stateRoot composed proof
         * (ADR-010 F4). A 410 means the commitment remains but one or more
         * effect records needed for the list path crossed the retention
         * horizon.
         */
        @GET
        @Path("effects/{height}/{ordinal}/proof")
        public Response effectProof(@PathParam("height") long height,
                                    @PathParam("ordinal") int ordinal) {
            com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup lookup;
            try {
                lookup = gateway.effectProof(height, ordinal);
            } catch (IllegalStateException e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("code", "EFFECT_PROOF_INCONSISTENT",
                                "error", e.getMessage())).build();
            }
            if (lookup.status()
                    == com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup.Status.NOT_FOUND) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("code", "EFFECT_NOT_FOUND",
                                "error", "No effect at " + height + "/" + ordinal)).build();
            }
            if (lookup.status()
                    == com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup.Status.PRUNED) {
                return Response.status(Response.Status.GONE)
                        .entity(Map.of("code", "EFFECT_PROOF_PRUNED",
                                "height", height, "ordinal", ordinal,
                                "effectCount", lookup.effectCount(),
                                "error", "Effect proof material passed the retention horizon"))
                        .build();
            }

            var proof = lookup.proof();
            var record = proof.record();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("version", proof.version());
            result.put("chainId", record.chainId());
            result.put("height", record.height());
            result.put("ordinal", record.ordinal());
            result.put("recordCborHex", HexUtil.encodeHexString(record.encode()));
            result.put("effectHashHex", HexUtil.encodeHexString(record.effectHash()));
            result.put("effectCount", proof.effectCount());
            List<Map<String, String>> path = new ArrayList<>(proof.merklePath().size());
            for (var step : proof.merklePath()) {
                path.add(Map.of("side", step.side().name(),
                        "siblingHashHex", HexUtil.encodeHexString(step.siblingHash())));
            }
            result.put("merklePath", path);
            result.put("effectsRootHex", HexUtil.encodeHexString(proof.effectsRoot()));
            result.put("stateKeyHex", HexUtil.encodeHexString(
                    com.bloxbean.cardano.yano.api.appchain.effects.FxKeys.effectsRootKey(record.height())));
            result.put("stateRootHex", HexUtil.encodeHexString(proof.stateRoot()));
            result.put("stateProofWireHex", HexUtil.encodeHexString(proof.stateProofWire()));
            return Response.ok(result).build();
        }

        /** Effect consensus/runtime gauges and cumulative totals. */
        @GET
        @Path("effects/stats")
        public Response effectStats() {
            return Response.ok(Map.of("chainId", gateway.chainId(),
                    "stats", gateway.effectStats())).build();
        }

        /** Operator requeue of a PARKED/QUARANTINED effect (ADR-010 F9). */
        @POST
        @Path("effects/{height}/{ordinal}/requeue")
        public Response requeueEffect(@PathParam("height") long height,
                                      @PathParam("ordinal") int ordinal) {
            boolean requeued = gateway.requeueEffect(height, ordinal);
            return requeued
                    ? Response.ok(Map.of("requeued", true)).build()
                    : Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "Effect not requeueable (unknown, live, "
                                    + "already terminal, or no executor on this node)")).build();
        }

        /** Operator cancel of an open CHAIN effect — injects a CANCELLED result (ADR-010 F9). */
        @POST
        @Path("effects/{height}/{ordinal}/cancel")
        public Response cancelEffect(@PathParam("height") long height,
                                     @PathParam("ordinal") int ordinal,
                                     @QueryParam("reason") @DefaultValue("operator-cancel") String reason) {
            boolean cancelled = gateway.cancelEffect(height, ordinal, reason);
            return cancelled
                    ? Response.accepted(Map.of("cancelled", true)).build()
                    : Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "Effect not cancellable (unknown, closed, "
                                    + "not CHAIN-policy, or sequencing disabled)")).build();
        }

        public record ClaimRequest(String executorId, List<String> types, Integer max,
                                   Long leaseSeconds) {
        }

        /** External-executor claim: lease eligible effects (effects.external.enabled). */
        @POST
        @Path("effects/claim")
        public Response claimEffects(ClaimRequest request) {
            if (request == null || request.executorId() == null || request.executorId().isBlank()) {
                return badRequest("executorId is required");
            }
            var claimed = gateway.claimEffects(request.executorId(),
                    request.types() != null ? java.util.Set.copyOf(request.types()) : java.util.Set.of(),
                    request.max() != null ? request.max() : 16,
                    request.leaseSeconds() != null ? request.leaseSeconds() : 60);
            List<Map<String, Object>> effects = new ArrayList<>(claimed.size());
            for (var effect : claimed) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("height", effect.record().height());
                view.put("ordinal", effect.record().ordinal());
                view.put("type", effect.type());
                view.put("scope", effect.scope());
                view.put("payloadHex", HexUtil.encodeHexString(effect.payload()));
                view.put("expiryHeight", effect.expiryHeight());
                view.put("idempotencyKey", HexUtil.encodeHexString(effect.idHash()));
                view.put("effectId", effect.effectId().canonical());
                effects.add(view);
            }
            return Response.ok(Map.of("chainId", gateway.chainId(), "effects", effects)).build();
        }

        public record ReportRequest(String executorId, Boolean success, String externalRefHex,
                                    String reason) {
        }

        /** External-executor report: definitive outcome for a claimed effect. */
        @POST
        @Path("effects/{height}/{ordinal}/report")
        public Response reportEffect(@PathParam("height") long height,
                                     @PathParam("ordinal") int ordinal,
                                     ReportRequest request) {
            if (request == null || request.executorId() == null || request.success() == null) {
                return badRequest("executorId and success are required");
            }
            byte[] externalRef;
            try {
                externalRef = request.externalRefHex() != null
                        ? HexUtil.decodeHexString(request.externalRefHex()) : new byte[0];
            } catch (Exception e) {
                return badRequest("Invalid externalRefHex");
            }
            boolean accepted = gateway.reportEffect(request.executorId(), height, ordinal,
                    request.success(), externalRef, request.reason());
            return accepted
                    ? Response.ok(Map.of("reported", true)).build()
                    : Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "Report rejected (not claimed by this "
                                    + "executor, unknown, closed, or external mode disabled)")).build();
        }

        /** Finalized message refs from a sender key, ascending (height, index). */
        @GET
        @Path("messages/by-sender/{senderHex}")
        public Response messagesBySender(@PathParam("senderHex") String senderHex,
                                         @QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                                         @QueryParam("limit") @DefaultValue("100") int limit) {
            byte[] sender;
            try {
                sender = HexUtil.decodeHexString(senderHex);
            } catch (Exception e) {
                return badRequest("Invalid sender hex");
            }
            if (sender.length != 32) {
                return badRequest("sender must be a 32-byte (64 hex chars) Ed25519 public key");
            }
            return Response.ok(Map.of("chainId", gateway.chainId(), "sender", senderHex,
                    "messages", gateway.messagesBySender(sender, fromHeight, limit))).build();
        }

        // --- Key rotation (ADR 006 E4.5): staged, operator-coordinated ---

        public record MemberRequest(String publicKey) {
        }

        public record ThresholdRequest(int threshold) {
        }

        @GET
        @Path("admin/members")
        @AppChainAccess(AppChainAccess.Level.PRIVILEGED)
        public Response listMembers() {
            return Response.ok(Map.of("chainId", gateway.chainId(),
                    "members", new ArrayList<>(gateway.members()),
                    "threshold", gateway.effectiveThreshold())).build();
        }

        @POST
        @Path("admin/members/add")
        public Response addMember(MemberRequest request) {
            if (request == null || isBlank(request.publicKey())) {
                return badRequest("'publicKey' is required");
            }
            try {
                gateway.addMember(request.publicKey());
                return listMembers();
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
        }

        @POST
        @Path("admin/members/remove")
        public Response removeMember(MemberRequest request) {
            if (request == null || isBlank(request.publicKey())) {
                return badRequest("'publicKey' is required");
            }
            try {
                gateway.removeMember(request.publicKey());
                return listMembers();
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
        }

        @POST
        @Path("admin/members/reset")
        public Response resetMembers() {
            try {
                gateway.resetMembers();
                return listMembers();
            } catch (IllegalStateException | IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
        }

        @POST
        @Path("admin/threshold")
        public Response setThreshold(ThresholdRequest request) {
            if (request == null) {
                return badRequest("'threshold' is required");
            }
            try {
                gateway.setThreshold(request.threshold());
                return listMembers();
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
        }

        // --- Admin (ADR 006 E5.4): node-local operability controls ---

        @POST
        @Path("admin/pause")
        public Response pause() {
            gateway.pauseSubmissions();
            return Response.ok(Map.of("chainId", gateway.chainId(), "submissionsPaused", true)).build();
        }

        @POST
        @Path("admin/resume")
        public Response resume() {
            gateway.resumeSubmissions();
            return Response.ok(Map.of("chainId", gateway.chainId(), "submissionsPaused", false)).build();
        }

        @POST
        @Path("admin/drain-pool")
        public Response drainPool() {
            int dropped = gateway.drainPool();
            return Response.ok(Map.of("chainId", gateway.chainId(), "dropped", dropped)).build();
        }

        @POST
        @Path("admin/force-anchor")
        public Response forceAnchor() {
            boolean triggered = gateway.forceAnchor();
            return Response.ok(Map.of("chainId", gateway.chainId(), "anchorTriggered", triggered)).build();
        }

        /**
         * Bootstrap the script anchor (ADR 008.4): mint the thread NFT and
         * lock the initial datum at the anchor validator. Admin action;
         * anchor leader with {@code anchor.mode: script} only.
         */
        @POST
        @Path("admin/anchor/bootstrap")
        public Response bootstrapScriptAnchor() {
            try {
                Map<String, Object> result = new java.util.LinkedHashMap<>(gateway.bootstrapScriptAnchor());
                result.put("chainId", gateway.chainId());
                return Response.accepted(result).build();
            } catch (IllegalStateException e) {
                throw jsonError(Response.Status.CONFLICT, e.getMessage());
            }
        }

        /**
         * Operator escape hatch (stale-lock runbook, ADR 008.2/I4.2): clear
         * this member's vote lock at the pending height when the locked
         * proposal is unrecoverable. Run ONLY after confirming no conflicting
         * certificate exists on any member.
         */
        @POST
        @Path("admin/unlock-stale-round")
        public Response unlockStaleRound() {
            try {
                boolean unlocked = gateway.unlockStaleRound();
                return Response.ok(Map.of("chainId", gateway.chainId(), "unlocked", unlocked)).build();
            } catch (IllegalStateException e) {
                throw jsonError(Response.Status.CONFLICT, e.getMessage());
            }
        }

        /**
         * Create an atomic ledger snapshot for fast member onboarding
         * (ADR 006 E5.3). Copy the resulting directory to a new node's
         * app-chain ledger path. Admin action.
         */
        @POST
        @Path("snapshot")
        public Response snapshot(SnapshotRequest request) {
            if (request == null || isBlank(request.path())) {
                return badRequest("'path' (a fresh directory) is required");
            }
            try {
                long height = gateway.snapshot(request.path());
                return Response.ok(Map.of("chainId", gateway.chainId(),
                        "snapshotPath", request.path(), "height", height)).build();
            } catch (IllegalStateException e) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("error", e.getMessage())).build();
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("error", e.getMessage())).build();
            }
        }

        @GET
        @Path("evidence/{messageIdHex}")
        public Response evidence(@PathParam("messageIdHex") String messageIdHex) {
            byte[] messageId;
            try {
                messageId = HexUtil.decodeHexString(messageIdHex);
            } catch (Exception e) {
                return badRequest("Invalid messageId hex");
            }
            return gateway.evidence(messageId)
                    .map(bundle -> Response.ok(
                            com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec.toJson(bundle))
                            .build())
                    .orElse(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No finalized message with id " + messageIdHex)).build());
        }

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

        /**
         * SSE stream of finalized messages (ADR 006 E3.1): replays from
         * {@code fromHeight} (default: live-only from the current tip), then
         * follows new blocks. Event name "app-message", id "height:index";
         * "heartbeat" events keep idle connections alive.
         */
        @GET
        @Path("stream")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void stream(@QueryParam("fromHeight") @DefaultValue("-1") long fromHeight,
                           @QueryParam("topic") String topic,
                           @jakarta.ws.rs.core.Context jakarta.ws.rs.sse.Sse sse,
                           @jakarta.ws.rs.core.Context jakarta.ws.rs.sse.SseEventSink sink) {
            final AppChainGateway chainGateway = this.gateway;
            Thread.ofVirtual().name("app-chain-sse").start(() -> {
                java.util.concurrent.BlockingQueue<com.bloxbean.cardano.yano.api.appchain.AppBlock> liveBlocks =
                        new java.util.concurrent.LinkedBlockingQueue<>(1024);
                AutoCloseable subscription = null;
                try (sink) {
                    // Subscribe BEFORE replay so no block is missed in between
                    subscription = chainGateway.subscribeFinalized(
                            (block, hash) -> liveBlocks.offer(block));

                    long tip = chainGateway.tipHeight();
                    long nextHeight = fromHeight >= 0 ? Math.max(1, fromHeight) : tip + 1;
                    long lastSent = nextHeight - 1;

                    // Replay finalized history
                    for (long h = nextHeight; h <= tip && !sink.isClosed(); h++) {
                        var block = chainGateway.block(h);
                        if (block.isEmpty()) {
                            break;
                        }
                        emitBlock(sse, sink, block.get(), topic);
                        lastSent = h;
                    }

                    // Live phase
                    while (!sink.isClosed()) {
                        var block = liveBlocks.poll(15, java.util.concurrent.TimeUnit.SECONDS);
                        if (sink.isClosed()) {
                            break;
                        }
                        if (block == null) {
                            sink.send(sse.newEventBuilder().name("heartbeat")
                                    .data(String.valueOf(chainGateway.tipHeight())).build());
                            continue;
                        }
                        if (block.height() <= lastSent) {
                            continue; // already replayed
                        }
                        // Fill any gap (queue overflow / bursts) from the ledger
                        for (long h = lastSent + 1; h < block.height() && !sink.isClosed(); h++) {
                            chainGateway.block(h).ifPresent(missed -> emitBlock(sse, sink, missed, topic));
                        }
                        emitBlock(sse, sink, block, topic);
                        lastSent = block.height();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // client disconnects surface as send failures — normal termination
                } finally {
                    if (subscription != null) {
                        try {
                            subscription.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        private static final ObjectMapper SSE_MAPPER = new ObjectMapper();

        private void emitBlock(jakarta.ws.rs.sse.Sse sse, jakarta.ws.rs.sse.SseEventSink sink,
                               com.bloxbean.cardano.yano.api.appchain.AppBlock block, String topicFilter) {
            int index = 0;
            for (var message : block.messages()) {
                int messageIndex = index++;
                if (topicFilter != null && !topicFilter.equals(message.getTopic())) {
                    continue;
                }
                // Build JSON with the mapper so user-controlled fields (topic,
                // chainId) are correctly escaped — raw concatenation would let a
                // topic containing a quote produce malformed JSON and wedge the
                // subscriber in a reconnect loop.
                ObjectNode json = SSE_MAPPER.createObjectNode();
                json.put("chainId", block.chainId());
                json.put("height", block.height());
                json.put("index", messageIndex);
                json.put("messageId", message.getMessageIdHex());
                json.put("topic", message.getTopic());
                json.put("sender", HexUtil.encodeHexString(message.getSender()));
                json.put("senderSeq", message.getSenderSeq());
                json.put("bodyHex", HexUtil.encodeHexString(message.getBody()));
                sink.send(sse.newEventBuilder()
                        .name("app-message")
                        .id(block.height() + ":" + messageIndex)
                        .data(json.toString())
                        .build());
            }
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
