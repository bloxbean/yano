package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.appchain.ReceivedAppMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                .orElseThrow(() -> jsonError(Response.Status.NOT_FOUND, "Unknown app chain: " + chainId));
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

    @GET
    @Path("evidence/{messageIdHex}")
    public Response evidence(@PathParam("messageIdHex") String messageIdHex) {
        return singleChain().evidence(messageIdHex);
    }

    @GET
    @Path("blocks")
    public Response blocks(@QueryParam("from") @DefaultValue("-1") long from,
                           @QueryParam("limit") @DefaultValue("20") int limit) {
        return singleChain().blocks(from, limit);
    }

    @GET
    @Path("messages/{messageIdHex}")
    public Response messageById(@PathParam("messageIdHex") String messageIdHex) {
        return singleChain().messageById(messageIdHex);
    }

    @GET
    @Path("messages/by-topic/{topic}")
    public Response messagesByTopic(@PathParam("topic") String topic,
                                    @QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                                    @QueryParam("limit") @DefaultValue("100") int limit) {
        return singleChain().messagesByTopic(topic, fromHeight, limit);
    }

    @GET
    @Path("messages/by-sender/{senderHex}")
    public Response messagesBySender(@PathParam("senderHex") String senderHex,
                                     @QueryParam("fromHeight") @DefaultValue("0") long fromHeight,
                                     @QueryParam("limit") @DefaultValue("100") int limit) {
        return singleChain().messagesBySender(senderHex, fromHeight, limit);
    }

    @POST
    @Path("admin/pause")
    public Response pause() {
        return singleChain().pause();
    }

    @POST
    @Path("admin/resume")
    public Response resume() {
        return singleChain().resume();
    }

    @POST
    @Path("admin/drain-pool")
    public Response drainPool() {
        return singleChain().drainPool();
    }

    @POST
    @Path("admin/force-anchor")
    public Response forceAnchor() {
        return singleChain().forceAnchor();
    }

    @GET
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
