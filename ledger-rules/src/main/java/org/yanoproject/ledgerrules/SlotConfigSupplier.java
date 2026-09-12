package org.yanoproject.ledgerrules;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import org.yanoproject.api.util.EpochSlotCalc;

/**
 * Supplies the slot timing configuration used by transaction validation and evaluation.
 */
@FunctionalInterface
public interface SlotConfigSupplier {

    SlotConfig getSlotConfig();

    /**
     * Supplies the epoch geometry associated with the slot timing configuration.
     *
     * <p>The default keeps existing third-party suppliers source-compatible. Callers that validate
     * epoch-sensitive ledger rules should provide it so adapters do not have to assume mainnet
     * epoch geometry.</p>
     *
     * @return epoch/slot calculator, or {@code null} when the supplier has no epoch information
     */
    default EpochSlotCalc getEpochSlotCalc() {
        return null;
    }
}
