package com.bloxbean.cardano.yano.app.api.events;

import com.bloxbean.cardano.yano.api.events.stream.NodeEventStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * L1 server-sent events for wallets (ADR-033 M2): new blocks, rollbacks, and
 * mempool-accepted transactions. Clients that miss events (bounded queue,
 * drop-oldest) must reconcile via the REST endpoints; the stream is a
 * poll-reducer, not a guaranteed feed.
 */
@Path("events")
public class EventsResource {
    private static final Logger log = LoggerFactory.getLogger(EventsResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_TOPICS =
            Set.of(NodeEventStream.TOPIC_BLOCK, NodeEventStream.TOPIC_ROLLBACK, NodeEventStream.TOPIC_TX);
    private static final long HEARTBEAT_SECONDS = 15;

    @Inject
    NodeEventStream nodeEventStream;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@QueryParam("topics") @DefaultValue("block,rollback,tx") String topics,
                       @Context Sse sse,
                       @Context SseEventSink sink) {
        Set<String> requested = Arrays.stream(topics.split(","))
                .map(String::trim)
                .filter(topic -> !topic.isEmpty())
                .collect(Collectors.toSet());
        if (requested.isEmpty() || !VALID_TOPICS.containsAll(requested)) {
            sendError(sse, sink, "topics must be a comma-separated subset of block,rollback,tx");
            return;
        }
        if (!nodeEventStream.isAvailable()) {
            sendError(sse, sink, "Event stream not available (events disabled)");
            return;
        }

        Thread.ofVirtual().name("l1-events-sse").start(() -> {
            try (sink; NodeEventStream.Subscription subscription = nodeEventStream.subscribe(requested)) {
                while (!sink.isClosed()) {
                    NodeEventStream.NodeEvent event =
                            subscription.queue().poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                    if (sink.isClosed()) {
                        break;
                    }
                    if (event == null) {
                        awaitSend(sink, sse.newEventBuilder().comment("heartbeat").build());
                        continue;
                    }
                    awaitSend(sink, sse.newEventBuilder()
                            .name(event.topic())
                            .mediaType(MediaType.APPLICATION_JSON_TYPE)
                            .data(toJson(event))
                            .build());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("L1 SSE stream ended: {}", e.toString());
            }
        });
    }

    private static void sendError(Sse sse, SseEventSink sink, String message) {
        try (sink) {
            awaitSend(sink, sse.newEventBuilder()
                    .name("error")
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .data(MAPPER.writeValueAsString(Map.of("error", message)))
                    .build());
        } catch (Exception e) {
            log.debug("Unable to send SSE error event: {}", e.toString());
        }
    }

    private static void awaitSend(SseEventSink sink, jakarta.ws.rs.sse.OutboundSseEvent event)
            throws Exception {
        // Completion is the only reliable signal that the remote client is
        // still writable. The virtual thread may block without tying up an
        // event-loop or core-sync thread.
        sink.send(event).toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static String toJson(NodeEventStream.NodeEvent event) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", event.topic());
        if (event.slot() >= 0) body.put("slot", event.slot());
        if (event.blockNumber() >= 0) body.put("block_number", event.blockNumber());
        if (event.blockHash() != null) body.put("block_hash", event.blockHash());
        if (event.txHash() != null) body.put("tx_hash", event.txHash());
        return MAPPER.writeValueAsString(body);
    }
}
