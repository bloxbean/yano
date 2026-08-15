package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

import java.util.Objects;

/** Published after a rollback is visible through canonical UTXO state. */
public record UtxoStateRolledBackEvent(RollbackEvent rollbackEvent) implements Event {
    public UtxoStateRolledBackEvent {
        Objects.requireNonNull(rollbackEvent, "rollbackEvent");
    }
}
