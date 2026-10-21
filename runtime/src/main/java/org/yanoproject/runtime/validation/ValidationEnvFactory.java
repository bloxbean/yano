package org.yanoproject.runtime.validation;

import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.LedgerView;

/**
 * Builds the non-state {@link ValidationEnv} for a slot (ADR-056 §2): slot, epoch, the view's protocol
 * version, the network id, slot timing and the phase-2 environment digest. Supplied by the transaction
 * services, which know the network's genesis and slot timing.
 */
@FunctionalInterface
public interface ValidationEnvFactory {

    /**
     * @param slot the validation slot
     * @param view the view the request will read (its protocol parameters give the version and digest)
     * @throws org.yanoproject.ledger.rules.view.LedgerStateUnavailableException when the view cannot supply
     *                                                                           the protocol parameters
     */
    ValidationEnv create(long slot, LedgerView view);
}
