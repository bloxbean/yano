package com.bloxbean.cardano.yano.ledgerrules;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

/**
 * Supplies the effective protocol parameters for the epoch containing a slot.
 */
@FunctionalInterface
public interface EpochProtocolParamsSupplier {

    ProtocolParams getProtocolParams(long slot);
}
