package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.runtime.appchain.SharedTransportTestFakes.FakeFallbackLink;
import com.bloxbean.cardano.yano.runtime.appchain.SharedTransportTestFakes.FakeTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared link's transport state machine: shared → grace (bounded local
 * queue) → dedicated fallback → back to shared (fallback retired, queue
 * flushed).
 */
class SharedAppPeerLinkTest {

    private static final String KEY = "apphost:13337";

    private FakeTransport transport;
    private FakeFallbackLink fallback;
    private AtomicInteger fallbackDials;
    private SharedAppPeerLink link;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        fallback = new FakeFallbackLink();
        fallbackDials = new AtomicInteger();
        link = new SharedAppPeerLink(transport, KEY, "apphost:13337",
                () -> {
                    fallbackDials.incrementAndGet();
                    return fallback;
                },
                0 /* immediate fallback for tests */,
                LoggerFactory.getLogger("test"));
    }

    @Test
    void sharedUp_enqueueGoesToSharedSession() {
        transport.up = true;
        AppMessage message = SharedTransportTestFakes.message("m1");

        link.enqueue(message);

        assertThat(transport.enqueued).containsExactly(message);
        assertThat(link.transport()).isEqualTo("shared");
        assertThat(link.isConnected()).isTrue();
        assertThat(fallbackDials.get()).isZero();
    }

    @Test
    void sharedDown_messagesQueueThenDrainIntoFallback() {
        transport.up = false;
        AppMessage queued = SharedTransportTestFakes.message("q1");
        link.enqueue(queued);                    // grace queue (no fallback yet)
        assertThat(fallbackDials.get()).isZero();

        link.ensureConnectedAsync();             // starts the grace window
        assertThat(fallbackDials.get()).isZero();

        link.ensureConnectedAsync();             // grace (0ms) elapsed → fallback
        assertThat(fallbackDials.get()).isEqualTo(1);
        assertThat(fallback.enqueued).containsExactly(queued);   // queue drained
        assertThat(fallback.connectTicks.get()).isGreaterThan(0);
        assertThat(link.transport()).isEqualTo("dedicated-fallback");

        AppMessage next = SharedTransportTestFakes.message("m2");
        link.enqueue(next);                      // subsequent traffic → fallback
        assertThat(fallback.enqueued).containsExactly(queued, next);
    }

    @Test
    void sharedRestored_fallbackRetiredAndTrafficReturns() {
        transport.up = false;
        link.ensureConnectedAsync();
        link.ensureConnectedAsync();             // fallback engaged
        assertThat(fallbackDials.get()).isEqualTo(1);

        transport.up = true;
        link.ensureConnectedAsync();             // shared back → retire fallback
        assertThat(fallback.shutdownCalled).isTrue();
        assertThat(link.transport()).isEqualTo("shared");

        AppMessage message = SharedTransportTestFakes.message("m1");
        link.enqueue(message);
        assertThat(transport.enqueued).containsExactly(message);
    }

    @Test
    void graceQueueFlushesToSharedWhenItReturnsBeforeFallback() {
        transport.up = false;
        AppMessage queued = SharedTransportTestFakes.message("q1");
        link.enqueue(queued);

        transport.up = true;
        link.ensureConnectedAsync();

        assertThat(transport.enqueued).containsExactly(queued);
        assertThat(fallbackDials.get()).isZero();
    }

    @Test
    void catchUpRoutesToActiveTransport() {
        link.wireCatchUpHandler((p, b, t) -> {
        });

        transport.up = true;
        assertThat(link.requestCatchUp("c1", 1, 50)).isTrue();
        assertThat(transport.catchUpChains).containsExactly("c1");

        transport.up = false;
        link.ensureConnectedAsync();
        link.ensureConnectedAsync();             // fallback engaged
        assertThat(link.requestCatchUp("c1", 1, 50)).isTrue();
        assertThat(fallback.catchUpChains).containsExactly("c1");
    }

    @Test
    void keepAliveForwardsOnlyToFallback() {
        transport.up = true;
        link.keepAliveTick();
        assertThat(fallback.keepAliveTicks.get()).isZero();  // shared: L1 owns keep-alive

        transport.up = false;
        link.ensureConnectedAsync();
        link.ensureConnectedAsync();             // fallback engaged
        link.keepAliveTick();
        assertThat(fallback.keepAliveTicks.get()).isEqualTo(1);
    }

    @Test
    void shutdownStopsEverything() {
        transport.up = false;
        link.ensureConnectedAsync();
        link.ensureConnectedAsync();             // fallback engaged
        link.shutdown();
        assertThat(fallback.shutdownCalled).isTrue();

        AppMessage message = SharedTransportTestFakes.message("m1");
        link.enqueue(message);                   // no-op after shutdown
        assertThat(fallback.enqueued).isEmpty();
        assertThat(link.requestCatchUp("c1", 1, 50)).isFalse();
        link.ensureConnectedAsync();             // must not redial
        assertThat(fallbackDials.get()).isEqualTo(1);
    }

    @Test
    void expiredGraceMessagesAreDroppedOnFlush() {
        transport.up = false;
        AppMessage expired = AppMessage.builder()
                .messageId(new byte[32]).chainId("c1").topic("t")
                .sender(new byte[32]).senderSeq(1)
                .expiresAt(1) // long past
                .body("x".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
        link.enqueue(expired);

        transport.up = true;
        link.ensureConnectedAsync();

        assertThat(transport.enqueued).isEmpty();
    }
}
