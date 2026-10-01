package org.yanoproject.ledger.rules.conway.ruleset;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.ledger.rules.view.LedgerView;

/**
 * The expiry epoch of a newly registered DRep ({@code computeDRepExpiryVersioned}, Conway/Rules/GovCert.hs:282-292),
 * which the bootstrap phase computes differently ({@link ConwayPolicies#DREP_EXPIRY}).
 */
public interface DRepExpiry extends RulePolicy {

    /**
     * @param pp           the epoch-effective protocol parameters ({@code ppDRepActivity})
     * @param currentEpoch the current epoch
     * @param state        the state the registration applies to (its dormant-epoch counter)
     * @return the new DRep's expiry epoch
     */
    long expiry(ProtocolParams pp, long currentEpoch, LedgerView state);
}
