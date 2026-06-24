package com.bloxbean.cardano.yano.runtime.chronology;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystem;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Owns slot-to-wall-clock chronology and era-transition subscription wiring.
 */
public final class ChronologySubsystem implements Subsystem {
    private final ChronologyService chronologyService;
    private final EventBus eventBus;
    private final LedgerStateSubsystem ledgerStateSubsystem;

    private boolean slotTimeEventSubscriptionRegistered;

    public ChronologySubsystem(ChronologyService chronologyService,
                               EventBus eventBus,
                               LedgerStateSubsystem ledgerStateSubsystem) {
        this.chronologyService = Objects.requireNonNull(chronologyService, "chronologyService");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.ledgerStateSubsystem = Objects.requireNonNull(ledgerStateSubsystem, "ledgerStateSubsystem");
    }

    @Override
    public String name() {
        return "chronology";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    public boolean initialize(GenesisConfig genesisConfig, long resolvedGenesisTimestamp) {
        if (!chronologyService.initialize(genesisConfig, resolvedGenesisTimestamp)) {
            return false;
        }
        if (!slotTimeEventSubscriptionRegistered) {
            slotTimeEventSubscriptionRegistered = true;
            eventBus.subscribe(BlockAppliedEvent.class, ctx ->
                            ledgerStateSubsystem.handleEraTransition(ctx.event()),
                    SubscriptionOptions.builder().build());
        }
        return true;
    }

    public OptionalLong slotToUnixTime(long slot) {
        return chronologyService.slotToUnixTime(slot);
    }

    public void invalidateSlotTimeCache() {
        chronologyService.invalidateSlotTimeCache();
    }
}
