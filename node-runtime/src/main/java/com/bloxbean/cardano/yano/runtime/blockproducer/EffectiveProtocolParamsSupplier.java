package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Supplies epoch-effective protocol parameters for transaction validation and script evaluation.
 */
public class EffectiveProtocolParamsSupplier implements EpochProtocolParamsSupplier {

    private static final Logger log = LoggerFactory.getLogger(EffectiveProtocolParamsSupplier.class);

    private final LedgerStateProvider ledgerStateProvider;
    private final EpochSlotCalc epochSlotCalc;
    private final ProtocolParams fallbackProtocolParams;

    private volatile CacheEntry cache;
    private volatile Integer fallbackWarningEpoch;

    public EffectiveProtocolParamsSupplier(LedgerStateProvider ledgerStateProvider,
                                           EpochSlotCalc epochSlotCalc,
                                           ProtocolParams fallbackProtocolParams) {
        this.ledgerStateProvider = ledgerStateProvider;
        this.epochSlotCalc = Objects.requireNonNull(epochSlotCalc, "epochSlotCalc must not be null");
        this.fallbackProtocolParams = fallbackProtocolParams;
    }

    @Override
    public ProtocolParams getProtocolParams(long slot) {
        int epoch = epochSlotCalc.slotToEpoch(Math.max(0, slot));
        CacheEntry current = cache;
        if (current != null && current.epoch == epoch) {
            return current.protocolParams;
        }

        ProtocolParams resolved = null;
        if (ledgerStateProvider != null) {
            resolved = ledgerStateProvider.getProtocolParameters(epoch)
                    .map(ProtocolParamsMapper::fromSnapshot)
                    .orElse(null);
        }

        if (resolved != null) {
            cache = new CacheEntry(epoch, resolved);
            return resolved;
        }

        if (fallbackProtocolParams != null) {
            warnFallback(epoch);
            return fallbackProtocolParams;
        }

        throw new IllegalStateException("Effective protocol parameters are unavailable for epoch " + epoch);
    }

    private void warnFallback(int epoch) {
        Integer warned = fallbackWarningEpoch;
        if (warned != null && warned == epoch) return;
        fallbackWarningEpoch = epoch;
        log.warn("Effective protocol parameters unavailable for epoch {}; using static protocol-param.json fallback", epoch);
    }

    private record CacheEntry(int epoch, ProtocolParams protocolParams) {}
}
