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
     * <p>The default keeps existing suppliers source-compatible while failing clearly if a caller
     * tries to use a three-field CCL slot configuration for epoch-sensitive validation.</p>
     *
     * @return epoch/slot calculator
     */
    default EpochSlotCalc getEpochSlotCalc() {
        throw new IllegalStateException("Epoch slot configuration not available");
    }
}
