package org.yanoproject.tx;

import org.yanoproject.ledgerrules.EpochProtocolParamsSupplier;

/**
 * Selected protocol-parameter source for transaction validation/evaluation.
 */
record ProtocolParamsResolution(EpochProtocolParamsSupplier supplier,
                                String source,
                                boolean requireLedgerStateProvider) {
}
