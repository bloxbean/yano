package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.events.UtxoStateAppliedEvent;
import com.bloxbean.cardano.yano.runtime.events.PropagatingEventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UtxoAcknowledgementIsolationTest {
    private record UnrelatedEvent() implements Event { }

    @Test
    void synchronousAcknowledgementFailureDoesNotFailDurableApplyOrEventBus() {
        PropagatingEventBus bus = new PropagatingEventBus();
        CountingWriter writer = new CountingWriter();
        bus.subscribe(UtxoStateAppliedEvent.class,
                ignored -> { throw new IllegalStateException("bookkeeping failed"); },
                SubscriptionOptions.builder().build());
        try (UtxoEventHandler ignored = new UtxoEventHandler(bus, writer)) {
            assertThatCode(() -> publish(bus, blockEvent())).doesNotThrowAnyException();
            assertThat(writer.applied).hasValue(1);
            assertThat(bus.hasAsyncFailure()).isFalse();
            assertThatCode(() -> bus.publish(new UnrelatedEvent(), metadata(), options()))
                    .doesNotThrowAnyException();
        } finally {
            bus.close();
        }
    }

    @Test
    void asynchronousAcknowledgementFailureDoesNotPoisonWriterOrEventBus() throws Exception {
        PropagatingEventBus bus = new PropagatingEventBus();
        CountingWriter writer = new CountingWriter();
        bus.subscribe(UtxoStateAppliedEvent.class,
                ignored -> { throw new IllegalStateException("bookkeeping failed"); },
                SubscriptionOptions.builder().build());
        try (UtxoEventHandlerAsync handler = new UtxoEventHandlerAsync(bus, writer)) {
            publish(bus, blockEvent());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (writer.applied.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(writer.applied).hasValue(1);
            assertThat(handler.hasFailure()).isFalse();
            assertThat(bus.hasAsyncFailure()).isFalse();
            assertThatCode(() -> bus.publish(new UnrelatedEvent(), metadata(), options()))
                    .doesNotThrowAnyException();
        } finally {
            bus.close();
        }
    }

    @Test
    void byronNativeApplyUsesFollowingCompatibilityEventForExactlyOneAcknowledgement() {
        PropagatingEventBus bus = new PropagatingEventBus();
        CountingWriter writer = new CountingWriter();
        AtomicInteger acknowledgements = new AtomicInteger();
        bus.subscribe(UtxoStateAppliedEvent.class, ignored -> acknowledgements.incrementAndGet(),
                SubscriptionOptions.builder().build());
        try (UtxoEventHandler ignored = new UtxoEventHandler(bus, writer)) {
            ByronMainBlockAppliedEvent nativeEvent = byronEvent();
            bus.publish(nativeEvent, metadata(), options());
            bus.publish(new BlockAppliedEvent(Era.Byron, nativeEvent.slot(), nativeEvent.blockNumber(),
                    nativeEvent.blockHash(), null), metadata(), options());

            assertThat(writer.byronApplied).hasValue(1);
            assertThat(acknowledgements).hasValue(1);
        } finally {
            bus.close();
        }
    }

    @Test
    void asynchronousByronApplyPreservesNativeBeforeCompatibilityAndAcknowledgesOnce() throws Exception {
        PropagatingEventBus bus = new PropagatingEventBus();
        CountingWriter writer = new CountingWriter();
        AtomicInteger acknowledgements = new AtomicInteger();
        bus.subscribe(UtxoStateAppliedEvent.class, ignored -> acknowledgements.incrementAndGet(),
                SubscriptionOptions.builder().build());
        try (UtxoEventHandlerAsync ignored = new UtxoEventHandlerAsync(bus, writer)) {
            ByronMainBlockAppliedEvent nativeEvent = byronEvent();
            bus.publish(nativeEvent, metadata(), options());
            bus.publish(new BlockAppliedEvent(Era.Byron, nativeEvent.slot(), nativeEvent.blockNumber(),
                    nativeEvent.blockHash(), null), metadata(), options());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (acknowledgements.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(writer.order).containsExactly("byron", "generic");
            assertThat(acknowledgements).hasValue(1);
        } finally {
            bus.close();
        }
    }

    @Test
    void asynchronousByronFailureIsRecordedUnderNativeTypeAndSuppressesAcknowledgement() throws Exception {
        PropagatingEventBus bus = new PropagatingEventBus();
        AtomicInteger acknowledgements = new AtomicInteger();
        bus.subscribe(UtxoStateAppliedEvent.class, ignored -> acknowledgements.incrementAndGet(),
                SubscriptionOptions.builder().build());
        try (UtxoEventHandlerAsync ignored = new UtxoEventHandlerAsync(bus, new FailingByronWriter())) {
            ByronMainBlockAppliedEvent nativeEvent = byronEvent();
            bus.publish(nativeEvent, metadata(), options());
            try {
                bus.publish(new BlockAppliedEvent(Era.Byron, nativeEvent.slot(), nativeEvent.blockNumber(),
                        nativeEvent.blockHash(), null), metadata(), options());
            } catch (PropagatingEventBus.EventDeliveryException expectedAfterAsyncFailure) {
                // Native failure can become visible before compatibility publication.
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!bus.hasAsyncFailure() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(bus.hasAsyncFailure()).isTrue();
            assertThat(bus.asyncFailure().getMessage())
                    .contains(ByronMainBlockAppliedEvent.class.getSimpleName());
            assertThat(acknowledgements).hasValue(0);
        } finally {
            bus.close();
        }
    }

    private static BlockAppliedEvent blockEvent() {
        Block block = Block.builder().era(Era.Babbage)
                .transactionBodies(List.of()).invalidTransactions(List.of()).build();
        return new BlockAppliedEvent(Era.Babbage, 1, 1, "ab".repeat(32), block);
    }

    private static ByronMainBlockAppliedEvent byronEvent() {
        ByronMainBlock block = ByronMainBlock.builder()
                .body(ByronBlockBody.builder().txPayload(List.of()).build())
                .build();
        return new ByronMainBlockAppliedEvent(1L, 1L, "bc".repeat(32), block);
    }

    private static void publish(PropagatingEventBus bus, BlockAppliedEvent event) {
        bus.publish(event, metadata(), options());
    }

    private static EventMetadata metadata() {
        return EventMetadata.builder().origin("test").build();
    }

    private static PublishOptions options() {
        return PublishOptions.builder().build();
    }

    private static final class CountingWriter implements UtxoStoreWriter {
        private final AtomicInteger applied = new AtomicInteger();
        private final AtomicInteger byronApplied = new AtomicInteger();
        private final CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();

        @Override public void applyBlock(BlockAppliedEvent event) {
            applied.incrementAndGet();
            order.add("generic");
        }
        @Override public void applyByronBlock(ByronMainBlockAppliedEvent event) {
            byronApplied.incrementAndGet();
            order.add("byron");
        }
        @Override public void rollbackTo(RollbackEvent event) { }
        @Override public void reconcile(ChainState chainState) { }
        @Override public boolean isEnabled() { return true; }
    }

    private static final class FailingByronWriter implements UtxoStoreWriter {
        @Override public void applyBlock(BlockAppliedEvent event) { }
        @Override public void applyByronBlock(ByronMainBlockAppliedEvent event) {
            throw new IllegalStateException("Byron apply failed");
        }
        @Override public void rollbackTo(RollbackEvent event) { }
        @Override public void reconcile(ChainState chainState) { }
        @Override public boolean isEnabled() { return true; }
    }
}
