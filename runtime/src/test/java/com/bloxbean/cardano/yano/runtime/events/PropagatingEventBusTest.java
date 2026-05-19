package com.bloxbean.cardano.yano.runtime.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropagatingEventBusTest {
    private record TestEvent(String value) implements Event {
    }

    @Test
    void synchronousListenerFailuresArePropagatedAfterAllListenersRun() {
        PropagatingEventBus bus = new PropagatingEventBus();
        List<String> calls = new ArrayList<>();
        try {
            bus.subscribe(TestEvent.class,
                    ctx -> {
                        calls.add("first");
                        throw new IllegalStateException("first failed");
                    },
                    SubscriptionOptions.builder().priority(0).build());
            bus.subscribe(TestEvent.class,
                    ctx -> calls.add("second"),
                    SubscriptionOptions.builder().priority(10).build());

            PropagatingEventBus.EventDeliveryException failure = assertThrows(
                    PropagatingEventBus.EventDeliveryException.class,
                    () -> bus.publish(new TestEvent("x"),
                            EventMetadata.builder().build(),
                            PublishOptions.builder().build()));

            assertEquals("Event listener failed for TestEvent", failure.getMessage());
            assertEquals(List.of("first", "second"), calls);
        } finally {
            bus.close();
        }
    }

    @Test
    void asyncListenerFailureDisablesSubscriptionAndFailsFuturePublishes() throws Exception {
        PropagatingEventBus bus = new PropagatingEventBus();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attempted = new CountDownLatch(1);
        try {
            bus.subscribe(TestEvent.class,
                    ctx -> {
                        attempted.countDown();
                        throw new IllegalStateException("async failed");
                    },
                    SubscriptionOptions.builder()
                            .executor(executor)
                            .bufferSize(2)
                            .build());

            bus.publish(new TestEvent("first"),
                    EventMetadata.builder().build(),
                    PublishOptions.builder().build());

            assertEquals(true, attempted.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!bus.hasAsyncFailure() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(true, bus.hasAsyncFailure());

            PropagatingEventBus.EventDeliveryException failure = assertThrows(
                    PropagatingEventBus.EventDeliveryException.class,
                    () -> bus.publish(new TestEvent("second"),
                            EventMetadata.builder().build(),
                            PublishOptions.builder().build()));
            assertEquals("Event listener failed for TestEvent", failure.getMessage());
        } finally {
            bus.close();
            executor.shutdownNow();
        }
    }
}
