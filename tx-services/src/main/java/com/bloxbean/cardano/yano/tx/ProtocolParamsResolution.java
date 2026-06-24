package com.bloxbean.cardano.yano.tx;

import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;

/**
 * Selected protocol-parameter source for transaction validation/evaluation.
 */
record ProtocolParamsResolution(EpochProtocolParamsSupplier supplier,
                                String source,
                                boolean requireLedgerStateProvider) {
}
