package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.events.UtxoStateAppliedEvent;
import com.bloxbean.cardano.yano.api.events.UtxoStateRolledBackEvent;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yano.runtime.util.LifecycleFailures.rethrowIfProcessFatalReachable;

import java.util.List;

/**
 * Synchronous event handler that delegates to a UtxoStoreWriter.
 * Keeps ordering and atomicity identical to current behavior.
 */
@Slf4j
public final class UtxoEventHandler implements AutoCloseable {
    private final EventBus bus;
    private final UtxoStoreWriter writer;
    private final List<SubscriptionHandle> handles;

    public UtxoEventHandler(EventBus bus, UtxoStoreWriter writer) {
        this.bus = bus;
        this.writer = writer;
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        this.handles = AnnotationListenerRegistrar.register(bus, this, defaults);
    }

    @DomainEventListener(order = 100)
    public void onByronMainBlockApplied(ByronMainBlockAppliedEvent e) {
        if (writer != null && writer.isEnabled()) {
            writer.applyByronBlock(e);
            // The following compatibility BlockAppliedEvent publishes the single
            // UTXO acknowledgement after this native apply has committed.
        }
    }

    @DomainEventListener(order = 100)
    public void onBlockApplied(BlockAppliedEvent e) {
        if (writer != null && writer.isEnabled()) {
            writer.applyBlock(e);
            publishAcknowledgement(new UtxoStateAppliedEvent(e), "UTXO apply");
        }
    }

    @DomainEventListener(order = 100)
    public void onRollback(RollbackEvent e) {
        if (writer != null && writer.isEnabled()) {
            writer.rollbackTo(e);
            publishAcknowledgement(new UtxoStateRolledBackEvent(e), "UTXO rollback");
        }
    }

    private void publishAcknowledgement(com.bloxbean.cardano.yaci.events.api.Event acknowledgement,
                                        String operation) {
        try {
            bus.publish(acknowledgement, EventMetadata.builder().build(),
                    PublishOptions.builder().build());
        } catch (Throwable e) {
            rethrowIfProcessFatalReachable(e);
            // The canonical write has already committed. A derived-state listener
            // must not make that durable write appear to have failed.
            log.error("Listener failed after {}; canonical state remains applied: {}",
                    operation, e.toString(), e);
        }
    }

    @Override
    public void close() {
        if (handles != null) handles.forEach(h -> { try { h.close(); } catch (Exception ignored) {} });
    }
}
