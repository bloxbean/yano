package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;

import java.math.BigInteger;
import java.util.Objects;

/**
 * An active governance proposal (Haskell {@code GovActionState}, without the votes).
 *
 * @param id                the action id
 * @param type              the action type
 * @param action            the CCL governance action (read-only), or {@code null} if the backing
 *                          store does not keep the payload
 * @param prevActionId      the parent in the purpose's lineage, or {@code null}
 * @param proposedEpoch     {@code gasProposedIn}
 * @param expiresAfterEpoch {@code gasExpiresAfter}: the last epoch in which it can be voted on; the
 *                          proposal stays readable after it until a boundary removes it
 * @param deposit           the proposal deposit
 * @param returnAddress     the deposit return (reward) address, as carried by the transaction
 */
public record ProposalState(GovActionId id, GovActionType type, GovAction action, GovActionId prevActionId,
                            long proposedEpoch, long expiresAfterEpoch, BigInteger deposit,
                            String returnAddress) {

    public ProposalState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(deposit, "deposit");
    }

    /** @return the lineage purpose, or {@code null} for treasury withdrawals and info actions */
    public GovPurpose purpose() {
        return GovPurpose.of(type);
    }
}
