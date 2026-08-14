package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainL1SubscriptionLifecycleTest {

    @Test
    void secondSubscriptionFailureClosesFirstAndPromotesFatalCloseFailure() {
        IllegalStateException subscriptionFailure =
                new IllegalStateException("subscription credential must not escape to logs");
        TestVirtualMachineError fatalClose = new TestVirtualMachineError();
        TrackingHandle first = new TrackingHandle(fatalClose);
        ScriptedEventBus eventBus = new ScriptedEventBus(first, subscriptionFailure);

        assertThatThrownBy(() -> AppChainSubsystem.acquireL1Subscriptions(
                eventBus, ignored -> { }, ignored -> { }))
                .isSameAs(fatalClose);

        assertThat(first.closeCalls).hasValue(1);
        assertThat(fatalClose.getSuppressed()).containsExactly(subscriptionFailure);
        assertThat(eventBus.subscribeCalls).hasValue(2);
    }

    @Test
    void completeSubscriptionPairIsPublishedOnlyAfterBothAreAcquired() {
        TrackingHandle first = new TrackingHandle(null);
        TrackingHandle second = new TrackingHandle(null);
        ScriptedEventBus eventBus = new ScriptedEventBus(first, second);

        List<SubscriptionHandle> subscriptions =
                AppChainSubsystem.acquireL1Subscriptions(
                        eventBus, ignored -> { }, ignored -> { });

        assertThat(subscriptions).containsExactly(first, second);
        assertThatThrownBy(() -> subscriptions.add(new TrackingHandle(null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(first.closeCalls).hasValue(0);
        assertThat(second.closeCalls).hasValue(0);
    }

    private static final class ScriptedEventBus implements EventBus {
        private final Object first;
        private final Object second;
        private final AtomicInteger subscribeCalls = new AtomicInteger();

        private ScriptedEventBus(Object first, Object second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> eventType,
                EventListener<E> listener,
                SubscriptionOptions options
        ) {
            Object scripted = subscribeCalls.getAndIncrement() == 0 ? first : second;
            if (scripted instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (scripted instanceof Error error) {
                throw error;
            }
            return (SubscriptionHandle) scripted;
        }

        @Override
        public <E extends Event> void publish(
                E event,
                EventMetadata metadata,
                PublishOptions options
        ) {
        }

        @Override
        public void close() {
        }
    }

    private static final class TrackingHandle implements SubscriptionHandle {
        private final Throwable closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private TrackingHandle(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            active.set(false);
            if (closeFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (closeFailure instanceof Error error) {
                throw error;
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }
}
