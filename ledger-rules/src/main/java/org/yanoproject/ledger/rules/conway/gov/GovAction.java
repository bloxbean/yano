package org.yanoproject.ledger.rules.conway.gov;

import org.yanoproject.ledger.rules.conway.tx.RawProposal.ProtVer;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.GovPurpose;

/**
 * One governance action in {@code Proposals}, as far as {@code GOV} reads it.
 *
 * @param id              the action id
 * @param actionTag       the action's CDDL tag ({@code RawProposal} constants)
 * @param expiresAfter    {@code gasExpiresAfter}
 * @param hardForkVersion a hard-fork initiation's protocol version, else null
 * @param securityGroup   whether a parameter change touches the stake-pool security group; null when unknown
 */
public record GovAction(GovActionId id, int actionTag, long expiresAfter, ProtVer hardForkVersion,
                        Boolean securityGroup) {

    /** @return the action's purpose, or null for one without a lineage */
    public GovPurpose purpose() {
        return GovRule.purposeOf(actionTag);
    }
}
