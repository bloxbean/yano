package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.events.EpochTransitionEvent;
import com.bloxbean.cardano.yano.api.events.PostEpochTransitionEvent;
import com.bloxbean.cardano.yano.api.events.PreEpochTransitionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Epoch transition emission across multi-epoch jumps (devnet restart/restore at
 * wall-clock slots). Default: one collapsed transition for the whole jump.
 * With process-skipped-epochs: one full transition per skipped epoch.
 */
class BlockProducerHelperEpochTransitionTest {

    /** 100-slot epochs: slot 50 → epoch 0, slot 350 → epoch 3. */
    private static final EpochParamProvider PROVIDER = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public long getEpochLength() { return 100; }
    };

    private RecordingEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new RecordingEventBus();
        BlockProducerHelper.setEpochParamProvider(PROVIDER);
        BlockProducerHelper.setProcessSkippedEpochs(false);
    }

    @AfterEach
    void tearDown() {
        BlockProducerHelper.setProcessSkippedEpochs(false);
        BlockProducerHelper.resetEpochTrackingToSlot(-1);
    }

    @Test
    @DisplayName("Multi-epoch jump collapses into a single transition by default")
    void jumpCollapsesIntoSingleTransitionByDefault() {
        BlockProducerHelper.resetEpochTrackingToSlot(50); // epoch 0

        BlockProducerHelper.prepareEpochTransitionBeforeBlock(eventBus, 350, 10, "test"); // epoch 3

        assertThat(eventBus.events()).hasSize(3);
        assertThat(eventBus.events().get(0)).isEqualTo(new PreEpochTransitionEvent(0, 3, 350, 10));
        assertThat(eventBus.events().get(1)).isEqualTo(new EpochTransitionEvent(0, 3, 350, 10));
        assertThat(eventBus.events().get(2)).isEqualTo(new PostEpochTransitionEvent(0, 3, 350, 10));
    }

    @Test
    @DisplayName("process-skipped-epochs fires one full transition per skipped epoch")
    void processSkippedEpochsFiresPerEpochTransitions() {
        BlockProducerHelper.setProcessSkippedEpochs(true);
        BlockProducerHelper.resetEpochTrackingToSlot(50); // epoch 0

        BlockProducerHelper.prepareEpochTransitionBeforeBlock(eventBus, 350, 10, "test"); // epoch 3

        assertThat(eventBus.events()).hasSize(9);
        for (int epoch = 1; epoch <= 3; epoch++) {
            int base = (epoch - 1) * 3;
            assertThat(eventBus.events().get(base))
                    .isEqualTo(new PreEpochTransitionEvent(epoch - 1, epoch, 350, 10));
            assertThat(eventBus.events().get(base + 1))
                    .isEqualTo(new EpochTransitionEvent(epoch - 1, epoch, 350, 10));
            assertThat(eventBus.events().get(base + 2))
                    .isEqualTo(new PostEpochTransitionEvent(epoch - 1, epoch, 350, 10));
        }
    }

    @Test
    @DisplayName("Single-epoch step behaves identically with process-skipped-epochs enabled")
    void singleEpochStepUnaffectedByFlag() {
        BlockProducerHelper.setProcessSkippedEpochs(true);
        BlockProducerHelper.resetEpochTrackingToSlot(50); // epoch 0

        BlockProducerHelper.prepareEpochTransitionBeforeBlock(eventBus, 150, 2, "test"); // epoch 1

        assertThat(eventBus.events()).hasSize(3);
        assertThat(eventBus.events().get(0)).isEqualTo(new PreEpochTransitionEvent(0, 1, 150, 2));
    }

    @Test
    @DisplayName("No transition fires when the epoch has not advanced")
    void noTransitionWithinSameEpoch() {
        BlockProducerHelper.setProcessSkippedEpochs(true);
        BlockProducerHelper.resetEpochTrackingToSlot(50); // epoch 0

        BlockProducerHelper.prepareEpochTransitionBeforeBlock(eventBus, 80, 2, "test"); // still epoch 0

        assertThat(eventBus.events()).isEmpty();
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<Event> events = new ArrayList<>();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(Class<E> eventType,
                                                              EventListener<E> listener,
                                                              SubscriptionOptions options) {
            return new SubscriptionHandle() {
                @Override
                public void close() {
                }

                @Override
                public boolean isActive() {
                    return true;
                }
            };
        }

        @Override
        public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
            events.add(event);
        }

        @Override
        public void close() {
        }

        List<Event> events() {
            return events;
        }
    }
}
