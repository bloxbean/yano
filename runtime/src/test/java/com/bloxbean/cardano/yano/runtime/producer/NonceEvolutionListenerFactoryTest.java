package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.NonceEvolutionListener;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolVersionSupplier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonceEvolutionListenerFactoryTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");

    @Test
    void registerSlotLeaderSubscribesNonceListenerAnnotations() throws Exception {
        RecordingEventBus eventBus = new RecordingEventBus();
        SlotLeaderSigningComponents signingComponents = signingComponents();
        EpochNonceState nonceState = new EpochNonceState(
                1200,
                100,
                1.0,
                Constants.BYRON_SLOTS_PER_EPOCH);

        NonceEvolutionListenerFactory.Registration registration = NonceEvolutionListenerFactory.registerSlotLeader(
                eventBus,
                nonceState,
                null,
                signingComponents.signedBlockBuilder(),
                null,
                false,
                42L,
                (slot, hashHex, serialized) -> null,
                null);

        assertThat(registration.listener()).isNotNull();
        assertThat(registration.subscriptionHandles()).hasSize(2);
        assertThat(eventBus.listeners).containsKeys(BlockAppliedEvent.class, RollbackEvent.class);
        assertThat(eventBus.listeners.get(BlockAppliedEvent.class)).hasSize(1);
        assertThat(eventBus.listeners.get(RollbackEvent.class)).hasSize(1);

        registration.close();

        assertThat(registration.subscriptionHandles())
                .allSatisfy(handle -> assertThat(handle.isActive()).isFalse());
    }

    @Test
    void createRequiresNonceState() {
        assertThatThrownBy(() -> NonceEvolutionListenerFactory.create(
                null,
                null,
                "issuer",
                null,
                false,
                42L,
                null,
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nonceState");
    }

    private static SlotLeaderSigningComponents signingComponents() throws Exception {
        SlotLeaderKeyMaterial keyMaterial = SlotLeaderKeyMaterial.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));
        EpochNonceState nonceState = new EpochNonceState(
                1200,
                100,
                1.0,
                Constants.BYRON_SLOTS_PER_EPOCH);
        return SlotLeaderSigningComponents.create(
                keyMaterial,
                129600,
                60,
                nonceState,
                null,
                ProtocolVersionSupplier.fixed(11, 0),
                1.0);
    }

    private static final class RecordingEventBus implements EventBus {
        private final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(Class<E> eventType,
                                                              EventListener<E> listener,
                                                              SubscriptionOptions options) {
            listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(listener);
            return new RecordingHandle();
        }

        @Override
        public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
        }

        @Override
        public void close() {
            listeners.clear();
        }
    }

    private static final class RecordingHandle implements SubscriptionHandle {
        private boolean active = true;

        @Override
        public void close() {
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
