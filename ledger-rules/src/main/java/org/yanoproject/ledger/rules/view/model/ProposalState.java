package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Set;

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
 * @param paramUpdateKeys   for a {@code ParameterChange}, the keys present in its
 *                          {@code protocol_param_update} map (Conway CDDL numbering, 0–33), read from
 *                          the original action bytes; {@code null} when unknown or not a parameter
 *                          change. CCL's {@code ProtocolParamUpdate} has no fields for the Conway keys
 *                          25–33, so the decoded {@link #action()} cannot answer which parameters a
 *                          proposal changes (for example whether it touches the security group).
 */
public record ProposalState(GovActionId id, GovActionType type, GovAction action, GovActionId prevActionId,
                            long proposedEpoch, long expiresAfterEpoch, BigInteger deposit,
                            String returnAddress, Set<Integer> paramUpdateKeys) {

    /**
     * {@code protocol_param_update} keys of Haskell's security group (Conway/PParams.hs): txFeePerByte 0,
     * txFeeFixed 1, maxBBSize 2, maxTxSize 3, maxBHSize 4, coinsPerUTxOByte 17, maxBlockExUnits 21,
     * maxValSize 22, govActionDeposit 30, minFeeRefScriptCostPerByte 33.
     */
    public static final Set<Integer> SECURITY_GROUP_KEYS = Set.of(0, 1, 2, 3, 4, 17, 21, 22, 30, 33);

    public ProposalState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(deposit, "deposit");
        paramUpdateKeys = paramUpdateKeys == null ? null : Set.copyOf(paramUpdateKeys);
    }

    /** A proposal whose parameter-update keys are unknown. */
    public ProposalState(GovActionId id, GovActionType type, GovAction action, GovActionId prevActionId,
                         long proposedEpoch, long expiresAfterEpoch, BigInteger deposit, String returnAddress) {
        this(id, type, action, prevActionId, proposedEpoch, expiresAfterEpoch, deposit, returnAddress, null);
    }

    /** @return a copy with the given parameter-update keys */
    public ProposalState withParamUpdateKeys(Set<Integer> keys) {
        return new ProposalState(id, type, action, prevActionId, proposedEpoch, expiresAfterEpoch, deposit,
                returnAddress, keys);
    }

    /**
     * @return whether this parameter change touches Haskell's security group, or {@code null} when it is
     *         a parameter change whose keys are unknown; {@code false} for other actions
     */
    public Boolean anyInSecurityGroup() {
        if (type != GovActionType.PARAMETER_CHANGE_ACTION) {
            return false;
        }
        if (paramUpdateKeys == null) {
            return null;
        }
        return paramUpdateKeys.stream().anyMatch(SECURITY_GROUP_KEYS::contains);
    }

    /** @return the lineage purpose, or {@code null} for treasury withdrawals and info actions */
    public GovPurpose purpose() {
        return GovPurpose.of(type);
    }
}
