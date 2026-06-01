package com.bloxbean.cardano.yano.ledgerrules;

import com.bloxbean.cardano.client.common.model.SlotConfig;

/**
 * Supplies the slot timing configuration used by transaction validation and evaluation.
 */
@FunctionalInterface
public interface SlotConfigSupplier {

    SlotConfig getSlotConfig();
}
