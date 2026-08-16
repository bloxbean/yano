package com.bloxbean.cardano.yano.api.events.stream;

import java.util.Set;
import java.util.concurrent.BlockingQueue;

/**
 * Bounded, drop-oldest fan-out of L1 node events for API-layer consumers
 * (the wallet SSE endpoint, ADR-033 M2). Subscribers can never block or fail
 * block application: the publisher side offers into each subscriber's bounded
 * queue and drops the oldest event on overflow.
 */
public interface NodeEventStream {
    String TOPIC_BLOCK = "block";
    String TOPIC_ROLLBACK = "rollback";
    String TOPIC_TX = "tx";

    /**
     * One event on the stream. {@code topic} is one of the TOPIC_* constants;
     * unused fields are null/-1 depending on the topic.
     */
    record NodeEvent(String topic, long slot, long blockNumber, String blockHash, String txHash) {
        public static NodeEvent block(long slot, long blockNumber, String blockHash) {
            return new NodeEvent(TOPIC_BLOCK, slot, blockNumber, blockHash, null);
        }

        public static NodeEvent rollback(long slot, String blockHash) {
            return new NodeEvent(TOPIC_ROLLBACK, slot, -1, blockHash, null);
        }

        public static NodeEvent tx(String txHash) {
            return new NodeEvent(TOPIC_TX, -1, -1, null, txHash);
        }
    }

    interface Subscription extends AutoCloseable {
        BlockingQueue<NodeEvent> queue();

        @Override
        void close();
    }

    /** Registers a subscriber for the given topics (TOPIC_* constants). */
    Subscription subscribe(Set<String> topics);

    boolean isAvailable();

    NodeEventStream UNAVAILABLE = new NodeEventStream() {
        @Override
        public Subscription subscribe(Set<String> topics) {
            throw new IllegalStateException("Node event stream not available");
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    };
}
