package org.yanoproject.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.*;
import org.yanoproject.ledgerstate.DefaultAccountStateStore.DeltaOp;
import org.yanoproject.ledgerstate.EpochParamTracker;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies the effects of ratified governance actions (ENACT rule).
 * <p>
 * Ratified actions are enacted at the epoch boundary following ratification.
 * Each action type has specific state mutations.
 */
public class EnactmentProcessor {
    private static final Logger log = LoggerFactory.getLogger(EnactmentProcessor.class);

    private final GovernanceStateStore governanceStore;
    private final EpochParamTracker paramTracker;

    public EnactmentProcessor(GovernanceStateStore governanceStore, EpochParamTracker paramTracker) {
        this.governanceStore = governanceStore;
        this.paramTracker = paramTracker;
    }

    /**
     * Enact a ratified governance action and update the last-enacted tracker.
     *
     * @param id       The proposal ID
     * @param proposal The proposal record
     * @param epoch    The epoch at which enactment takes effect
     * @param batch    WriteBatch for atomic writes
     * @param deltaOps Delta ops for rollback
     * @return Treasury delta from this enactment (negative = treasury decreases, e.g., withdrawals)
     */
    public BigInteger enact(GovActionId id, GovActionRecord proposal, int epoch,
                            WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        GovActionType type = proposal.actionType();
        BigInteger treasuryDelta = BigInteger.ZERO;

        switch (type) {
            case PARAMETER_CHANGE_ACTION -> {
                var update = enactedParamUpdate(proposal);
                if (update != null && paramTracker != null) {
                    paramTracker.applyEnactedParamChange(epoch, update, batch);
                    log.info("Enacted ParameterChange for epoch {} from {}/{} fields={}", epoch,
                            id.getTransactionId().substring(0, 8), id.getGov_action_index(),
                            changedProtocolParamFields(update));
                    log.debug("Enacted ParameterChange full update for epoch {} from {}/{}: {}",
                            epoch, id.getTransactionId().substring(0, 8), id.getGov_action_index(), update);
                } else {
                    log.info("Enacted ParameterChange for epoch {} from {}/{}", epoch,
                            id.getTransactionId().substring(0, 8), id.getGov_action_index());
                }
            }
            case HARD_FORK_INITIATION_ACTION -> {
                var ppu = enactedParamUpdate(proposal);
                if (ppu != null && paramTracker != null) {
                    paramTracker.applyEnactedParamChange(epoch, ppu, batch);
                }
                String protocolVersion = proposal.govAction() instanceof HardForkInitiationAction hf
                        && hf.getProtocolVersion() != null
                        ? hf.getProtocolVersion().get_1() + "." + hf.getProtocolVersion().get_2()
                        : "unknown";
                log.info("Enacted HardForkInitiation for epoch {} from {}/{} protocolVersion={}", epoch,
                        id.getTransactionId().substring(0, 8), id.getGov_action_index(), protocolVersion);
            }
            case TREASURY_WITHDRAWALS_ACTION -> {
                // Treasury withdrawals reduce treasury and credit reward accounts.
                // The total withdrawal amount is computed here; actual reward account credits
                // are processed by GovernanceEpochProcessor which has access to the account store.
                if (proposal.govAction() instanceof TreasuryWithdrawalsAction twa
                        && twa.getWithdrawals() != null) {
                    for (BigInteger amount : twa.getWithdrawals().values()) {
                        treasuryDelta = treasuryDelta.subtract(amount);
                    }
                }
                log.info("Enacted TreasuryWithdrawals from {}/{}, treasuryDelta={}",
                        id.getTransactionId().substring(0, 8), id.getGov_action_index(), treasuryDelta);
            }
            case NO_CONFIDENCE -> {
                governanceStore.clearAllCommitteeMembers(batch, deltaOps);
                governanceStore.storeCommitteePresent(false, batch, deltaOps);
                log.info("Enacted NoConfidence — committee cleared");
            }
            case UPDATE_COMMITTEE -> {
                if (proposal.govAction() instanceof UpdateCommittee uc) {
                    enactUpdateCommittee(uc, batch, deltaOps);
                }
                governanceStore.storeCommitteePresent(true, batch, deltaOps);
                log.info("Enacted UpdateCommittee from {}/{}", id.getTransactionId().substring(0, 8),
                        id.getGov_action_index());
            }
            case NEW_CONSTITUTION -> {
                var constitution = enactedConstitution(proposal);
                if (constitution != null) {
                    governanceStore.storeConstitution(constitution, batch, deltaOps);
                }
                log.info("Enacted NewConstitution from {}/{}", id.getTransactionId().substring(0, 8),
                        id.getGov_action_index());
            }
            case INFO_ACTION -> {
                // No-op — should never be enacted
            }
        }

        // Track as last enacted action for this purpose type
        GovActionType purposeType = ProposalDropService.getPurposeType(type);
        if (purposeType != null) {
            governanceStore.storeLastEnactedAction(purposeType, id.getTransactionId(),
                    id.getGov_action_index(), batch, deltaOps);
        }

        return treasuryDelta;
    }

    private static List<String> changedProtocolParamFields(Object update) {
        List<String> fields = new ArrayList<>();
        if (update == null) return fields;

        for (Field field : update.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                if (field.get(update) != null) {
                    fields.add(field.getName());
                }
            } catch (RuntimeException | IllegalAccessException e) {
                // Diagnostics must never affect enactment.
                log.debug("Unable to inspect protocol parameter field {}", field.getName(), e);
            }
        }

        fields.sort(Comparator.naturalOrder());
        return fields;
    }

    private void enactUpdateCommittee(UpdateCommittee uc, WriteBatch batch,
                                      List<DeltaOp> deltaOps) throws RocksDBException {
        CommitteeUpdate change = committeeUpdateOf(uc);

        // Remove members
        for (GovernanceStateStore.CredentialKey member : change.removals()) {
            governanceStore.removeCommitteeMember(member.credType(), member.hash(), batch, deltaOps);
        }

        // Add new members with term epochs.
        // Preserve any existing hot key authorization (may have been submitted before enrollment).
        // The existing record is read from committed state, not from this batch.
        for (CommitteeAddition addition : change.additions()) {
            var existing = governanceStore.getCommitteeMember(
                    addition.member().credType(), addition.member().hash());
            CommitteeMemberRecord record = enactedMemberRecord(existing.orElse(null), addition.expiryEpoch());
            governanceStore.storeCommitteeMember(addition.member().credType(), addition.member().hash(),
                    record, batch, deltaOps);
        }

        // Update committee threshold
        if (change.hasThreshold()) {
            governanceStore.storeCommitteeThreshold(change.thresholdNumerator(), change.thresholdDenominator(),
                    batch, deltaOps);
        }
    }

    // ===== Pure enactment effects (shared with the ADR-056 boundary preview) =====

    /**
     * @return the protocol-parameter update an enacted ParameterChange or HardForkInitiation applies
     *         (for a hard fork, only the protocol version); {@code null} for other actions or when the
     *         action carries no update
     */
    public static ProtocolParamUpdate enactedParamUpdate(GovActionRecord proposal) {
        if (proposal.actionType() == GovActionType.PARAMETER_CHANGE_ACTION
                && proposal.govAction() instanceof ParameterChangeAction pca) {
            return pca.getProtocolParamUpdate();
        }
        if (proposal.actionType() == GovActionType.HARD_FORK_INITIATION_ACTION
                && proposal.govAction() instanceof HardForkInitiationAction hf
                && hf.getProtocolVersion() != null) {
            return ProtocolParamUpdate.builder()
                    .protocolMajorVer((int) hf.getProtocolVersion().get_1())
                    .protocolMinorVer((int) hf.getProtocolVersion().get_2())
                    .build();
        }
        return null;
    }

    /** @return the constitution an enacted NewConstitution stores; {@code null} when it stores none */
    public static GovernanceCborCodec.ConstitutionRecord enactedConstitution(GovActionRecord proposal) {
        if (proposal.actionType() == GovActionType.NEW_CONSTITUTION
                && proposal.govAction() instanceof NewConstitution nc && nc.getConstitution() != null) {
            var anchor = nc.getConstitution().getAnchor();
            String scriptHash = nc.getConstitution().getScripthash();
            return new GovernanceCborCodec.ConstitutionRecord(
                    anchor != null ? anchor.getAnchor_url() : null,
                    anchor != null ? anchor.getAnchor_data_hash() : null,
                    scriptHash);
        }
        return null;
    }

    /** A member added by an enacted UpdateCommittee, with its term. */
    public record CommitteeAddition(GovernanceStateStore.CredentialKey member, int expiryEpoch) {
    }

    /**
     * The committee changes of an UpdateCommittee action, in the order enactment applies them:
     * removals, then additions, then the threshold.
     *
     * @param removals             cold credentials removed
     * @param additions            cold credentials added, with their terms
     * @param thresholdNumerator   new quorum numerator, or {@code null} when unchanged
     * @param thresholdDenominator new quorum denominator, or {@code null} when unchanged
     */
    public record CommitteeUpdate(List<GovernanceStateStore.CredentialKey> removals, List<CommitteeAddition> additions,
                                  BigInteger thresholdNumerator, BigInteger thresholdDenominator) {
        public boolean hasThreshold() {
            return thresholdNumerator != null && thresholdDenominator != null;
        }
    }

    /** Decodes the committee changes of an UpdateCommittee action (credential hashes as given). */
    public static CommitteeUpdate committeeUpdateOf(UpdateCommittee uc) {
        List<GovernanceStateStore.CredentialKey> removals = new ArrayList<>();
        if (uc.getMembersForRemoval() != null) {
            for (Credential cred : uc.getMembersForRemoval()) {
                removals.add(new GovernanceStateStore.CredentialKey(credTypeFromModel(cred), cred.getHash()));
            }
        }
        List<CommitteeAddition> additions = new ArrayList<>();
        if (uc.getNewMembersAndTerms() != null) {
            for (var entry : uc.getNewMembersAndTerms().entrySet()) {
                Credential cred = entry.getKey();
                additions.add(new CommitteeAddition(
                        new GovernanceStateStore.CredentialKey(credTypeFromModel(cred), cred.getHash()),
                        entry.getValue()));
            }
        }
        BigInteger num = null;
        BigInteger den = null;
        if (uc.getThreshold() != null) {
            num = uc.getThreshold().getNumerator();
            den = uc.getThreshold().getDenominator();
        }
        return new CommitteeUpdate(List.copyOf(removals), List.copyOf(additions), num, den);
    }

    /**
     * The record an UpdateCommittee addition stores: an existing hot-key authorization is kept
     * (with the new term), otherwise a record without hot key.
     *
     * @param existing the member's record in committed state before this enactment, or {@code null}
     */
    public static CommitteeMemberRecord enactedMemberRecord(CommitteeMemberRecord existing, int expiryEpoch) {
        if (existing != null && existing.hasHotKey()) {
            return new CommitteeMemberRecord(existing.hotCredType(), existing.hotHash(), expiryEpoch, false);
        }
        return CommitteeMemberRecord.noHotKey(expiryEpoch);
    }

    /**
     * Maps an action type to its purpose root key ({@code UPDATE_COMMITTEE} for both committee
     * actions); {@code null} for actions without a purpose chain.
     */
    public static GovActionType purposeOf(GovActionType type) {
        return ProposalDropService.getPurposeType(type);
    }

    private static int credTypeFromModel(Credential cred) {
        return cred.getType() == com.bloxbean.cardano.yaci.core.model.certs.StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }
}
