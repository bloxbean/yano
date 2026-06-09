package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yano.api.events.HeaderAppliedEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class HeaderAppliedEventPublisher implements AutoCloseable {
    private static final int DEFAULT_QUEUE_CAPACITY =
            positiveIntProperty("yano.headerAppliedEvent.queueCapacity", 8192);

    private final EventBus eventBus;
    private final BlockingQueue<HeaderAppliedEvent> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public HeaderAppliedEventPublisher(EventBus eventBus) {
        this(eventBus, DEFAULT_QUEUE_CAPACITY);
    }

    HeaderAppliedEventPublisher(EventBus eventBus, int queueCapacity) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus cannot be null");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.worker = Thread.ofVirtual()
                .name("HeaderAppliedEventPublisher")
                .start(this::run);
    }

    void publishLater(long slot, long blockNumber, String blockHash) {
        if (!running.get()) {
            return;
        }

        HeaderAppliedEvent event = new HeaderAppliedEvent(slot, blockNumber, blockHash);
        if (!queue.offer(event)) {
            log.warn("Dropping HeaderAppliedEvent because queue is full: slot={}, block={}",
                    slot, blockNumber);
        }
    }

    private void run() {
        while (running.get() || !queue.isEmpty()) {
            try {
                HeaderAppliedEvent event = queue.poll(1, TimeUnit.SECONDS);
                if (event != null) {
                    publish(event);
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Error publishing HeaderAppliedEvent", e);
            }
        }
    }

    private void publish(HeaderAppliedEvent event) {
        EventMetadata metadata = EventMetadata.builder()
                .origin("runtime")
                .slot(event.slot())
                .blockNo(event.blockNumber())
                .blockHash(event.blockHash())
                .build();
        eventBus.publish(event, metadata, PublishOptions.builder().build());
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            worker.interrupt();
            if (worker != Thread.currentThread()) {
                try {
                    worker.join(TimeUnit.SECONDS.toMillis(2));
                    if (worker.isAlive()) {
                        log.warn("HeaderAppliedEventPublisher worker did not stop within timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for HeaderAppliedEventPublisher worker to stop");
                }
            }
        }
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
            log.warn("Ignoring non-positive {}={}, using {}", name, value, defaultValue);
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid {}={}, using {}", name, value, defaultValue);
        }
        return defaultValue;
    }
}
