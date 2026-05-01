package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;

import java.util.Objects;

/**
 * Supplies epoch-effective protocol parameters for transaction validation and script evaluation.
 */
public class EffectiveProtocolParamsSupplier implements EpochProtocolParamsSupplier {

    private final LedgerStateProvider ledgerStateProvider;
    private final EpochSlotCalc epochSlotCalc;

    private volatile CacheEntry cache;

    public EffectiveProtocolParamsSupplier(LedgerStateProvider ledgerStateProvider,
                                           EpochSlotCalc epochSlotCalc) {
        this.ledgerStateProvider = Objects.requireNonNull(ledgerStateProvider,
                "ledgerStateProvider must not be null");
        this.epochSlotCalc = Objects.requireNonNull(epochSlotCalc, "epochSlotCalc must not be null");
    }

    @Override
    public ProtocolParams getProtocolParams(long slot) {
        if (slot < 0) {
            throw new IllegalStateException("Effective protocol parameters require a non-negative slot; got " + slot);
        }

        int epoch = epochSlotCalc.slotToEpoch(slot);
        CacheEntry current = cache;
        if (current != null && current.epoch == epoch) {
            return current.protocolParams;
        }

        ProtocolParams resolved = ledgerStateProvider.getProtocolParameters(epoch)
                .map(ProtocolParamsMapper::fromSnapshot)
                .orElse(null);

        if (resolved != null) {
            cache = new CacheEntry(epoch, resolved);
            return resolved;
        }

        throw new IllegalStateException("Effective protocol parameters are unavailable for epoch " + epoch);
    }

    private record CacheEntry(int epoch, ProtocolParams protocolParams) {}
}
