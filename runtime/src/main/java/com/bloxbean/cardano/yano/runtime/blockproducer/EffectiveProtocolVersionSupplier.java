package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Resolves produced-block protocol version from epoch-effective protocol params.
 */
public class EffectiveProtocolVersionSupplier implements ProtocolVersionSupplier {

    private final LedgerStateProvider ledgerStateProvider;
    private final EpochSlotCalc epochSlotCalc;
    private final IntConsumer epochBootstrapper;

    private volatile CacheEntry cache;

    public EffectiveProtocolVersionSupplier(LedgerStateProvider ledgerStateProvider,
                                            EpochSlotCalc epochSlotCalc,
                                            EpochParamTracker tracker) {
        this(ledgerStateProvider, epochSlotCalc, epoch -> {
            if (tracker != null && tracker.isEnabled()) {
                tracker.bootstrapEpochIfNeeded(epoch);
            }
        });
    }

    EffectiveProtocolVersionSupplier(LedgerStateProvider ledgerStateProvider,
                                     EpochSlotCalc epochSlotCalc,
                                     IntConsumer epochBootstrapper) {
        this.ledgerStateProvider = Objects.requireNonNull(ledgerStateProvider,
                "ledgerStateProvider must not be null");
        this.epochSlotCalc = Objects.requireNonNull(epochSlotCalc, "epochSlotCalc must not be null");
        this.epochBootstrapper = epochBootstrapper;
    }

    @Override
    public ProtocolVersion getProtocolVersion(long slot) {
        if (slot < 0) {
            throw new IllegalStateException("Effective protocol version requires a non-negative slot; got " + slot);
        }

        int epoch = epochSlotCalc.slotToEpoch(slot);
        CacheEntry current = cache;
        if (current != null && current.epoch == epoch) {
            return current.protocolVersion;
        }

        ProtocolVersion resolved = resolve(epoch);
        cache = new CacheEntry(epoch, resolved);
        return resolved;
    }

    private ProtocolVersion resolve(int epoch) {
        var snapshot = ledgerStateProvider.getProtocolParameters(epoch);
        if (snapshot.isEmpty() && epochBootstrapper != null) {
            epochBootstrapper.accept(epoch);
            snapshot = ledgerStateProvider.getProtocolParameters(epoch);
        }

        var params = snapshot.orElseThrow(() -> new IllegalStateException(
                "Effective protocol parameters are unavailable for epoch " + epoch));

        Integer major = params.protocolMajorVer();
        Integer minor = params.protocolMinorVer();
        if (major == null || major <= 0 || minor == null || minor < 0) {
            throw new IllegalStateException(
                    "Effective protocol version is unavailable or invalid for epoch " + epoch);
        }

        return new ProtocolVersion(major, minor);
    }

    private record CacheEntry(int epoch, ProtocolVersion protocolVersion) {}
}
