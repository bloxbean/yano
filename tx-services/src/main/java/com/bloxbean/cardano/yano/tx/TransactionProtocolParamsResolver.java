package com.bloxbean.cardano.yano.tx;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.runtime.tx.EffectiveProtocolParamsSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Chooses the effective protocol-parameter supplier used by transaction
 * services.
 */
final class TransactionProtocolParamsResolver {
    private static final Logger log = LoggerFactory.getLogger(TransactionProtocolParamsResolver.class);

    private TransactionProtocolParamsResolver() {
    }

    static ProtocolParamsResolution select(boolean effectiveEpochParamsTrackingEnabled,
                                           LedgerStateProvider ledgerStateProvider,
                                           EpochSlotCalc epochSlotCalc,
                                           Supplier<ProtocolParams> staticParamsSupplier,
                                           Supplier<ProtocolParams> genesisParamsSupplier) {
        if (effectiveEpochParamsTrackingEnabled && ledgerStateProvider != null) {
            log.info("Transaction protocol params source: effective-ledger");
            return new ProtocolParamsResolution(
                    new EffectiveProtocolParamsSupplier(ledgerStateProvider, epochSlotCalc),
                    "effective-ledger",
                    true);
        }

        if (effectiveEpochParamsTrackingEnabled) {
            log.warn("Epoch-param tracking is enabled but LedgerStateProvider is unavailable for transaction "
                    + "validation/evaluation. Falling back to protocol-param.json / genesis bootstrap.");
        }

        ProtocolParams staticParams = staticParamsSupplier != null ? staticParamsSupplier.get() : null;
        if (staticParams != null) {
            return new ProtocolParamsResolution(slot -> staticParams, "static-protocol-param-json", false);
        }

        ProtocolParams genesisParams = genesisParamsSupplier != null ? genesisParamsSupplier.get() : null;
        if (genesisParams != null) {
            return new ProtocolParamsResolution(slot -> genesisParams, "genesis-bootstrap", false);
        }

        return null;
    }

    static long resolveRuntimeCurrentSlot(LongSupplier currentSlotSupplier) {
        if (currentSlotSupplier == null) {
            throw new IllegalStateException("Failed to resolve current slot from runtime");
        }

        try {
            long slot = currentSlotSupplier.getAsLong();
            if (slot >= 0) return slot;
            throw new IllegalStateException("current slot supplier returned " + slot);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve current slot from runtime", e);
        }
    }
}
