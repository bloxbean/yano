package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

import java.util.Objects;

/**
 * Published after a {@link BlockAppliedEvent} is visible through canonical
 * UTXO state. This is an acknowledgement event, not a second block event.
 */
public record UtxoStateAppliedEvent(BlockAppliedEvent blockAppliedEvent) implements Event {
    public UtxoStateAppliedEvent {
        Objects.requireNonNull(blockAppliedEvent, "blockAppliedEvent");
    }
}
