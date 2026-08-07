package com.bloxbean.cardano.yano.runtime.events;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.MemPoolTransactionReceivedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.events.stream.NodeEventStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single bus subscription fanned out to any number of API-layer stream
 * consumers (SSE). Listeners run on the publishing thread, so they must never
 * throw (the bus is fail-closed) and never block: events are offered into each
 * subscriber's bounded queue, dropping the oldest entry on overflow.
 */
public final class L1EventFanout implements NodeEventStream {
    private static final Logger log = LoggerFactory.getLogger(L1EventFanout.class);
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 1024;

    private final EventBus eventBus;
    private final CopyOnWriteArrayList<ClientSubscription> subscribers = new CopyOnWriteArrayList<>();
    private boolean busSubscribed;

    public L1EventFanout(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Subscription subscribe(Set<String> topics) {
        ensureBusSubscription();
        ClientSubscription subscription = new ClientSubscription(Set.copyOf(topics));
        subscribers.add(subscription);
        return subscription;
    }

    @Override
    public boolean isAvailable() {
        return eventBus != null;
    }

    /**
     * Lazily registers the single bus subscription. Synchronized (not a CAS
     * latch): if a subscribe call throws, the flag stays false so the next
     * client retries instead of permanently degrading the stream to heartbeats.
     */
    private synchronized void ensureBusSubscription() {
        if (eventBus == null || busSubscribed) {
            return;
        }
        SubscriptionOptions options = SubscriptionOptions.builder().build();
        eventBus.subscribe(BlockAppliedEvent.class, ctx -> {
            var event = ctx.event();
            deliver(NodeEvent.block(event.slot(), event.blockNumber(), event.blockHash()));
        }, options);
        eventBus.subscribe(RollbackEvent.class, ctx -> {
            var target = ctx.event().target();
            deliver(NodeEvent.rollback(
                    target != null ? target.getSlot() : -1,
                    target != null ? target.getHash() : null));
        }, options);
        eventBus.subscribe(MemPoolTransactionReceivedEvent.class, ctx -> {
            var tx = ctx.event().transaction();
            if (tx != null && tx.txHash() != null) {
                deliver(NodeEvent.tx(HexUtil.encodeHexString(tx.txHash())));
            }
        }, options);
        busSubscribed = true;
    }

    private void deliver(NodeEvent event) {
        // Runs on the block-apply/submit thread: absolutely no exceptions, no blocking.
        try {
            for (ClientSubscription subscription : subscribers) {
                subscription.offer(event);
            }
        } catch (Throwable t) {
            log.warn("L1 event fan-out failed: {}", t.toString());
        }
    }

    private final class ClientSubscription implements Subscription {
        private final Set<String> topics;
        private final BlockingQueue<NodeEvent> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);

        private ClientSubscription(Set<String> topics) {
            this.topics = topics;
        }

        // Synchronized: block-apply and mempool threads both produce; the
        // poll/offer pair must be atomic so drop-oldest stays single-iteration.
        synchronized void offer(NodeEvent event) {
            if (!topics.contains(event.topic())) {
                return;
            }
            while (!queue.offer(event)) {
                queue.poll(); // drop oldest; a slow SSE client must not stall the node
            }
        }

        @Override
        public BlockingQueue<NodeEvent> queue() {
            return queue;
        }

        @Override
        public void close() {
            subscribers.remove(this);
        }
    }
}
